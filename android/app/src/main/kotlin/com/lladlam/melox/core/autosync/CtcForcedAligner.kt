package com.lladlam.melox.core.autosync

/**
 * CTC forced alignment (Viterbi), a one-to-one port of MeloX-AutoSync
 * `src/autosync/alignment/ctc.py::forced_align`.
 *
 * The port deliberately keeps the same state layout, the same `-1e30` sentinel
 * and the same tie-breaking (first maximum wins, i.e. "stay" beats "advance"),
 * so the Kotlin and Python implementations must agree on every span. Any
 * behavioural change here is a divergence from the reference engine and will be
 * caught by [CtcForcedAlignerTest].
 *
 * Android only needs the alignment path; the training-side helpers are omitted.
 */
internal object CtcForcedAligner {

    /** Same sentinel as the Python engine; `-inf` never survives arithmetic. */
    const val NEG_INF: Double = -1e30

    /**
     * A single token claiming more than this fraction of the frames is treated as
     * a collapsed alignment: the acoustic model failed to distinguish the tokens
     * and the DP dumped the whole audio into one state.
     */
    private const val MAX_SPAN_FRACTION = 0.5

    data class Span(
        val token: Int,
        /** Inclusive frame indices into the log-probability time axis. */
        val startFrame: Int,
        val endFrame: Int,
        /** Mean token log-probability over the assigned frames. */
        val score: Double,
    ) {
        val numFrames: Int get() = endFrame - startFrame + 1
    }

    data class Alignment(
        val spans: List<Span?>,
        val totalLogProb: Double,
        val feasible: Boolean,
        val requiredFrames: Int,
        val reasons: List<String>,
        val meanScore: Double,
    )

    /**
     * Minimum frames needed to emit [targets] under CTC rules: each token needs at
     * least one frame, and every pair of *identical adjacent* tokens additionally
     * needs a blank frame between them.
     */
    fun minimumFrames(targets: List<Int>): Int {
        if (targets.isEmpty()) return 0
        var extra = 0
        for (k in 0 until targets.size - 1) {
            if (targets[k] == targets[k + 1]) extra++
        }
        return targets.size + extra
    }

    /**
     * @param logProbs `T x C` log-probabilities (e.g. `log_softmax` of the model
     *   logits), outer index is the frame.
     * @param targets target token ids, blanks excluded.
     * @param blank index of the CTC blank symbol.
     */
    fun align(
        logProbs: Array<DoubleArray>,
        targets: List<Int>,
        blank: Int = 0,
    ): Alignment {
        val t = logProbs.size
        val c = logProbs.firstOrNull()?.size ?: 0
        val n = targets.size
        val required = minimumFrames(targets)

        if (n == 0) {
            return Alignment(emptyList(), 0.0, true, required, emptyList(), 0.0)
        }
        // With no frames the vocabulary size is unknown; the T == 0 branch below
        // reports "no frames" instead. Python gets `C` from the 2-D array shape,
        // so this keeps the same outcome for the same request.
        if (c > 0) {
            for (token in targets) {
                require(token in 0 until c) { "target token $token out of range [0, $c)" }
            }
        }
        if (t == 0) {
            return Alignment(
                spans = List(n) { null },
                totalLogProb = NEG_INF,
                feasible = false,
                requiredFrames = required,
                reasons = listOf("no frames (T=0)"),
                meanScore = 0.0,
            )
        }
        if (t < required) {
            return Alignment(
                spans = List(n) { null },
                totalLogProb = NEG_INF,
                feasible = false,
                requiredFrames = required,
                reasons = listOf("too few frames: T=$t < required=$required for $n tokens"),
                meanScore = 0.0,
            )
        }

        val s = 2 * n + 1
        val ext = IntArray(s) { blank }
        for (k in 0 until n) ext[2 * k + 1] = targets[k]

        var dp = DoubleArray(s) { NEG_INF }
        dp[0] = logProbs[0][ext[0]]
        if (s > 1) dp[1] = logProbs[0][ext[1]]

        // backPtr[t - 1][state] in {0: stay, 1: from s-1, 2: from s-2}
        val backPtr = Array((t - 1).coerceAtLeast(0)) { ByteArray(s) }
        val c2 = DoubleArray(s)
        val c3 = DoubleArray(s)
        var next = DoubleArray(s)

        for (frame in 1 until t) {
            val prev = dp
            // c1 = stay (already `prev`)
            c2[0] = NEG_INF
            for (state in 1 until s) c2[state] = prev[state - 1]
            // c3 = from s - 2, only when the two tokens around the skipped blank differ
            c3[0] = NEG_INF
            if (s > 1) c3[1] = NEG_INF
            if (s > 2) {
                for (state in 2 until s) {
                    c3[state] = if (ext[state] != ext[state - 2]) prev[state - 2] else NEG_INF
                }
            }
            val lp = logProbs[frame]
            for (state in 0 until s) {
                var bestValue = prev[state]
                var idx = 0
                val value2 = c2[state]
                if (value2 > bestValue) {
                    bestValue = value2
                    idx = 1
                }
                val value3 = c3[state]
                if (value3 > bestValue) {
                    bestValue = value3
                    idx = 2
                }
                backPtr[frame - 1][state] = idx.toByte()
                next[state] = bestValue + lp[ext[state]]
            }
            val recycled = dp
            dp = next
            next = recycled
        }

        val total = dp.max()
        val endState = if (s == 1) {
            0
        } else if (dp[s - 1] >= dp[s - 2]) {
            s - 1
        } else {
            s - 2
        }

        val path = IntArray(t)
        var state = endState
        for (frame in t - 1 downTo 0) {
            path[frame] = state
            if (frame > 0) state -= backPtr[frame - 1][state].toInt()
        }

        val spans = ArrayList<Span?>(n)
        val missing = ArrayList<Int>()
        for (k in 0 until n) {
            val tokenState = 2 * k + 1
            var first = -1
            var last = -1
            for (frame in 0 until t) {
                if (path[frame] == tokenState) {
                    if (first < 0) first = frame
                    last = frame
                }
            }
            if (first < 0) {
                spans.add(null)
                missing.add(k)
                continue
            }
            val token = targets[k]
            var sum = 0.0
            for (frame in first..last) sum += logProbs[frame][token]
            spans.add(Span(token, first, last, sum / (last - first + 1)))
        }

        val reasons = ArrayList<String>(4)
        if (!total.isFinite() || total <= NEG_INF / 2) {
            reasons.add("all paths have -inf probability")
        }
        if (missing.isNotEmpty()) {
            reasons.add("${missing.size} token(s) received zero frames: ${missing.take(8)}")
        }
        if (!spansAreMonotonic(spans)) {
            reasons.add("spans are not monotonic")
        }
        reasons.addAll(degeneracyReasons(spans, t))

        val validScores = spans.filterNotNull().map { it.score }
        val meanScore = if (validScores.isEmpty()) 0.0 else validScores.average()
        return Alignment(
            spans = spans,
            totalLogProb = total,
            feasible = reasons.isEmpty(),
            requiredFrames = required,
            reasons = reasons,
            meanScore = meanScore,
        )
    }

    private fun spansAreMonotonic(spans: List<Span?>): Boolean {
        var previousEnd = -1
        for (span in spans) {
            if (span == null) continue
            if (span.startFrame <= previousEnd) return false
            if (span.endFrame < span.startFrame) return false
            previousEnd = span.endFrame
        }
        return true
    }

    private fun degeneracyReasons(spans: List<Span?>, t: Int): List<String> {
        val valid = spans.filterNotNull()
        if (valid.isEmpty() || t <= 0) return listOf("no usable spans")
        val top = valid.maxOf { it.numFrames }
        val fraction = top.toDouble() / t.toDouble()
        return if (valid.size >= 4 && fraction > MAX_SPAN_FRACTION) {
            listOf("collapsed: one token spans ${(fraction * 100).toInt()}% of the audio")
        } else {
            emptyList()
        }
    }

    /** Numerically stable log-softmax over one frame of logits. */
    fun logSoftmax(logits: FloatArray): DoubleArray {
        var max = Float.NEGATIVE_INFINITY
        for (value in logits) if (value > max) max = value
        var sum = 0.0
        for (value in logits) sum += kotlin.math.exp((value - max).toDouble())
        val logSum = kotlin.math.ln(sum)
        return DoubleArray(logits.size) { (logits[it] - max) - logSum }
    }
}
