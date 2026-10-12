package com.lladlam.melox.core.autosync

import kotlin.math.floor

/**
 * Chinese `fa-zh` post-processing: `us_alphas` / `us_peaks` -> per-token times.
 *
 * A one-to-one port of MeloX-AutoSync `android_export/zh/postprocess_reference.py`
 * (itself an independent implementation of FunASR
 * `ts_prediction_lfr6_standard`), which is the specification Android must
 * follow. The Chinese pack is **not** a CTC model, so [CtcForcedAligner] is
 * never used for it.
 *
 * Constants are frozen by the published pack (`manifest.json.postprocess`):
 * TIME_RATE 0.02 s, force_time_shift -1.5, START_END_THRESHOLD 5,
 * MAX_TOKEN_DURATION 12, peak threshold 1 - 1e-4.
 *
 * `ZhCifPostprocessTest` pins every case against the reference output.
 */
internal object ZhCifPostprocess {

    /** 10.0 * 6 / 1000 / 3: seconds per up-sampled frame. */
    const val TIME_RATE = 0.02

    private const val START_END_THRESHOLD = 5.0
    private const val MAX_TOKEN_DURATION = 12.0
    private const val FORCE_TIME_SHIFT = -1.5
    private const val PEAK_THRESHOLD = 1.0 - 1e-4

    data class TokenSpan(val startMs: Long, val endMs: Long)

    /**
     * @param alphas `us_alphas` truncated to `T_enc * 3` up-sampled frames.
     * @param peaks `us_peaks` truncated the same way.
     * @param tokenCount number of text tokens (the `token_num` input minus one).
     * @return one span per token, in order; empty when the model output is
     *   unusable (the caller then keeps the original line timing).
     */
    fun tokens(alphasIn: FloatArray, peaksIn: FloatArray, tokenCount: Int): List<TokenSpan> {
        if (tokenCount <= 0 || alphasIn.isEmpty() || peaksIn.isEmpty()) return emptyList()

        var alphas = alphasIn
        var peaks = peaksIn
        var firePlace = firePlace(peaks)
        if (firePlace.size != tokenCount + 1) {
            // Re-normalise so the alpha mass matches the token count, exactly as
            // the reference does, then re-derive the peaks sequentially.
            val mass = alphas.sum().toDouble()
            if (mass <= 0.0) return emptyList()
            val scale = mass / (tokenCount + 1)
            alphas = FloatArray(alphas.size) { (alphas[it] / scale).toFloat() }
            peaks = cifWoHidden(alphas)
            firePlace = firePlace(peaks)
        }
        if (firePlace.isEmpty()) return emptyList()

        val numFrames = peaks.size.toDouble()
        val starts = ArrayList<Double>(tokenCount + 2)
        val ends = ArrayList<Double>(tokenCount + 2)
        val silence = ArrayList<Boolean>(tokenCount + 2)

        if (firePlace[0] > START_END_THRESHOLD) {
            starts.add(0.0)
            ends.add(firePlace[0] * TIME_RATE)
            silence.add(true)
        }

        for (index in 0 until firePlace.size - 1) {
            starts.add(firePlace[index] * TIME_RATE)
            ends.add(firePlace[index + 1] * TIME_RATE)
            silence.add(false)
            if (firePlace[index + 1] - firePlace[index] > MAX_TOKEN_DURATION) {
                // A token may not claim more than MAX_TOKEN_DURATION frames: the
                // remainder becomes an explicit silence, never a longer token.
                val split = firePlace[index] + MAX_TOKEN_DURATION
                ends[ends.size - 1] = split * TIME_RATE
                starts.add(split * TIME_RATE)
                ends.add(firePlace[index + 1] * TIME_RATE)
                silence.add(true)
            }
        }
        if (starts.isEmpty()) return emptyList()

        if (numFrames - firePlace.last() > START_END_THRESHOLD) {
            val tail = (numFrames + firePlace.last()) * 0.5
            ends[ends.size - 1] = tail * TIME_RATE
            starts.add(tail * TIME_RATE)
            ends.add(numFrames * TIME_RATE)
            silence.add(true)
        } else {
            ends[ends.size - 1] = numFrames * TIME_RATE
        }

        val result = ArrayList<TokenSpan>(tokenCount)
        for (index in starts.indices) {
            if (silence[index]) continue
            result.add(
                TokenSpan(
                    startMs = (starts[index] * 1000.0).toLong(),
                    endMs = (ends[index] * 1000.0).toLong(),
                ),
            )
        }
        return result
    }

    /** Indices whose peak crosses the threshold, shifted by the fixed -1.5. */
    private fun firePlace(peaks: FloatArray): DoubleArray {
        val hits = ArrayList<Double>(peaks.size)
        for (index in peaks.indices) {
            if (peaks[index] >= PEAK_THRESHOLD) hits.add(index + FORCE_TIME_SHIFT)
        }
        return hits.toDoubleArray()
    }

    /**
     * Sequential CIF integrator (the reference's `cif_wo_hidden_loop`): the
     * running sum fires once it reaches the threshold and is reduced by exactly
     * one threshold, so the reported value is the post-fire remainder.
     */
    private fun cifWoHidden(alphas: FloatArray): FloatArray {
        val fires = FloatArray(alphas.size)
        var integrate = 0.0
        for (index in alphas.indices) {
            integrate += alphas[index]
            fires[index] = integrate.toFloat()
            if (integrate >= PEAK_THRESHOLD) integrate -= PEAK_THRESHOLD
        }
        return fires
    }

    /** Closed form of [cifWoHidden]; kept for cross-checking, as in the reference. */
    internal fun cifWoHiddenClosedForm(alphas: FloatArray): FloatArray {
        val fires = FloatArray(alphas.size)
        var sum = 0.0
        var completedBefore = 0.0
        for (index in alphas.indices) {
            sum += alphas[index]
            fires[index] = (sum - completedBefore * PEAK_THRESHOLD).toFloat()
            completedBefore = floor(sum / PEAK_THRESHOLD)
        }
        return fires
    }
}
