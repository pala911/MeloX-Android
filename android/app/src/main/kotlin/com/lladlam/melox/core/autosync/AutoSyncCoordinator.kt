package com.lladlam.melox.core.autosync

import android.content.Context
import android.util.Log
import com.lladlam.melox.core.lyrics.LyricLine
import com.lladlam.melox.core.lyrics.LyricSyllable
import com.lladlam.melox.core.lyrics.LyricTimingKind
import com.lladlam.melox.core.lyrics.LyricsDocument
import com.lladlam.melox.core.lyrics.LyricQuality
import com.lladlam.melox.ui.settings.MeloXSettingsRuntime
import java.io.File
import kotlin.math.max
import kotlin.math.min
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Everything that turns a line-timed lyric into a real word-level timeline.
 *
 * Runs entirely on device: the playback service lends the cached audio bytes,
 * [OnnxCtcEngine] produces frame log-probabilities, [CtcForcedAligner] aligns
 * them and [AutoSyncTimelineStore] remembers the result. Nothing here is
 * allowed to fabricate timing — a line that cannot be aligned is left exactly
 * as the provider returned it.
 */
internal object AutoSyncCoordinator {

    private const val TAG = "MeloXAutoSync"
    private const val SAMPLE_RATE = 16_000
    private const val PADDING_MS = 300L
    private const val MAX_LINE_MS = 12_000L
    private const val MIN_LINE_MS = 250L

    private val generationLock = Mutex()

    /**
     * @return the stored document upgraded with generated word timing, or
     *   `null` when nothing was cached for this exact lyric.
     */
    fun applyStored(context: Context, document: LyricsDocument, trackKey: String): LyricsDocument? {
        if (!MeloXSettingsRuntime.autoSyncWordLyricsEnabled || document.lines.any { it.syllables.isNotEmpty() }) return null
        val stored = AutoSyncTimelineStore.load(context, trackKey) ?: return null
        if (stored.sourceHash != AutoSyncTimelineStore.sourceHash(document)) return null
        if (stored.modelKey.isBlank() || stored.modelKey != AutoSyncModelStore.timelineModelKey(context, stored.language)) return null
        return merge(document, stored)
    }

    /**
     * Generates a word-level timeline for [document] and persists it.
     *
     * @return the upgraded document, or `null` when AutoSync is disabled, the
     *   lyrics already carry word timing, no model/audio is available, or the
     *   alignment produced nothing usable.
     */
    suspend fun generate(
        context: Context,
        document: LyricsDocument,
        trackKey: String,
        onProgress: ((Float) -> Unit)? = null,
    ): LyricsDocument? = generationLock.withLock {
        if (AutoSyncPlaybackBridge.isCurrentTrack?.invoke(trackKey) == false) return@withLock null
        applyStored(context, document, trackKey)?.let { return@withLock it }
        try {
            generateUncached(context, document, trackKey, onProgress)
        } finally {
            // Keep at most one language session resident, and release native
            // memory immediately after each serialized job (including cancel).
            OnnxCtcEngine.releaseAll()
            ZhAlignmentEngine.releaseAll()
            System.gc()
        }
    }

    private suspend fun generateUncached(
        context: Context,
        document: LyricsDocument,
        trackKey: String,
        onProgress: ((Float) -> Unit)?,
    ): LyricsDocument? {
        if (!MeloXSettingsRuntime.autoSyncWordLyricsEnabled) return null
        if (document.lines.isEmpty()) return null
        if (document.lines.any { it.syllables.isNotEmpty() }) return null

        val language = AutoSyncText.detectLanguage(document.lines.map(LyricLine::text)) ?: return null
        if (language == "ja") {
            Log.i(TAG, "Japanese AutoSync unavailable: on-device G2P dependency is not installed")
            return null
        }
        // Chinese is a monotonic-aligner/CIF pack, not CTC: a separate engine and
        // a separate decoder, never the Viterbi path below.
        if (language == "zh") return generateChinese(context, document, trackKey, onProgress)
        val engine = OnnxCtcEngine.forLanguage(context, language) ?: run {
            Log.i(TAG, "no usable AutoSync model for $language")
            return null
        }
        val modelKey = AutoSyncModelStore.timelineModelKey(context, language) ?: return null
        val resolveStartedAt = android.os.SystemClock.elapsedRealtime()
        val audioFile = runCatching { AutoSyncPlaybackBridge.audioResolver?.invoke(trackKey) }
            .onFailure {
                if (it is CancellationException) throw it
                Log.w(TAG, "audio resolver failed", it)
            }
            .getOrNull()
        if (audioFile == null || !audioFile.isFile || audioFile.length() == 0L) {
            Log.i(TAG, "no cached audio available for alignment (${audioFile?.absolutePath ?: "null"})")
            return null
        }
        Log.i(
            TAG,
            "audio resolved in ${android.os.SystemClock.elapsedRealtime() - resolveStartedAt}ms: " +
                "${audioFile.name} ${audioFile.length()}B",
        )

        val range = decodeRange(document)
        val startedAt = android.os.SystemClock.elapsedRealtime()
        val pcm = runCatching {
            AutoSyncAudioDecoder.decode(
                file = audioFile,
                targetSampleRate = SAMPLE_RATE,
                startMs = range.first,
                endMs = range.second,
                timeoutMs = (30_000L + (range.second - range.first) / 2).coerceAtMost(180_000L),
                shouldContinue = {
                    MeloXSettingsRuntime.autoSyncWordLyricsEnabled &&
                        AutoSyncPlaybackBridge.isCurrentTrack?.invoke(trackKey) != false
                },
            )
        }.onFailure {
            if (it is CancellationException) throw it
            Log.w(TAG, "audio decode failed", it)
        }.getOrNull() ?: return null
        return pcm.use {
            alignEnglish(context, document, trackKey, onProgress, engine, modelKey, language, pcm, range, startedAt)
        }
    }

    private suspend fun alignEnglish(
        context: Context,
        document: LyricsDocument,
        trackKey: String,
        onProgress: ((Float) -> Unit)?,
        engine: OnnxCtcEngine,
        modelKey: String,
        language: String,
        pcm: AutoSyncPcmFile,
        range: Pair<Long, Long>,
        startedAt: Long,
    ): LyricsDocument? {
        Log.i(
            TAG,
            "decoded ${pcm.sampleCount} samples in ${android.os.SystemClock.elapsedRealtime() - startedAt}ms " +
                "(range ${range.first}..${range.second}ms)",
        )
        if (pcm.sampleCount * 1000L / SAMPLE_RATE < MIN_LINE_MS) return null
        val totalMs = range.first + pcm.sampleCount * 1000L / SAMPLE_RATE
        val decodeMs = android.os.SystemClock.elapsedRealtime() - startedAt

        val timings = ArrayList<AutoSyncLineTiming>(document.lines.size)
        var alignedUnits = 0
        var failedLines = 0
        var lastReported = -1

        document.lines.forEachIndexed { index, line ->
            currentCoroutineContext().ensureActive()
            if (AutoSyncPlaybackBridge.isCurrentTrack?.invoke(trackKey) == false) return null
            onProgress?.let { report ->
                val bucket = index * 100 / document.lines.size.coerceAtLeast(1)
                if (bucket != lastReported) {
                    lastReported = bucket
                    report(bucket / 100f)
                }
            }
            if (!AutoSyncText.isAlignable(line.text)) return@forEachIndexed
            // The decoded buffer starts at the range, not at 0 ms.
            if (windowFirst(document, index, line, totalMs, range.first) == null) return@forEachIndexed
            val window = windowFor(document, index, line, totalMs) ?: return@forEachIndexed
            val startSample = ((window.first - range.first) * SAMPLE_RATE / 1000L)
                .toInt().coerceIn(0, pcm.sampleCount)
            val endSample = ((window.second - range.first) * SAMPLE_RATE / 1000L)
                .toInt().coerceIn(startSample, pcm.sampleCount)
            if (endSample - startSample < SAMPLE_RATE / 10) return@forEachIndexed
            val snippet = pcm.readWindow(startSample, endSample)

            val normalized = AutoSyncText.normalizeEn(line.text)
            val tokenIds = AutoSyncText.tokenIds(normalized, engine.manifest)
            if (tokenIds.isEmpty()) return@forEachIndexed

            val inferenceAt = android.os.SystemClock.elapsedRealtime()
            val alignment = runCatching { engine.alignWindow(snippet, tokenIds) }
                .onFailure { Log.w(TAG, "inference failed on line $index", it) }
                .getOrNull() ?: run { failedLines++; return@forEachIndexed }
            if (!alignment.feasible) {
                failedLines++
                Log.i(TAG, "line $index not feasible: ${alignment.reasons}")
                return@forEachIndexed
            }

            val units = AutoSyncText.groupWords(
                originalText = line.text,
                normalized = normalized,
                tokenIds = tokenIds,
                spans = alignment.spans,
                manifest = engine.manifest,
                frameHopSeconds = engine.manifest.frameHopSeconds,
            ).let { sanitize(it, snippet.size * 1000L / SAMPLE_RATE) }
            if (units.isEmpty()) {
                failedLines++
                return@forEachIndexed
            }
            alignedUnits += units.size
            Log.i(TAG, "aligned line=$index/${document.lines.size} units=${units.size} " +
                "inference=${android.os.SystemClock.elapsedRealtime() - inferenceAt}ms")
            timings.add(
                AutoSyncLineTiming(
                    index = index,
                    units = units.map { unit ->
                        AutoSyncUnit(unit.text, window.first + unit.startMs, window.first + unit.endMs)
                    },
                    meanScore = alignment.meanScore,
                ),
            )
        }

        if (timings.isEmpty()) {
            Log.i(TAG, "alignment produced nothing usable for $trackKey")
            return null
        }

        val timeline = AutoSyncTimeline(
            trackKey = trackKey,
            sourceHash = AutoSyncTimelineStore.sourceHash(document),
            language = language,
            engine = engine.sourceModel,
            lines = timings,
            createdAtMs = System.currentTimeMillis(),
            modelKey = modelKey,
        )
        if (AutoSyncPlaybackBridge.isCurrentTrack?.invoke(trackKey) == false) return null
        AutoSyncTimelineStore.save(context, timeline)
        Log.i(
            TAG,
            "aligned $trackKey lines=${timings.size}/${document.lines.size} units=$alignedUnits " +
                "failed=$failedLines decode=${decodeMs}ms total=" +
                "${android.os.SystemClock.elapsedRealtime() - startedAt}ms model=${engine.modelSizeBytes}B " +
                "rss=${currentRssBytes()}B",
        )
        onProgress?.invoke(1f)
        return merge(document, timeline)
    }

    /**
     * Chinese pass: raw PCM + `token_num` into the monotonic aligner, then
     * [ZhCifPostprocess] for per-character times.
     *
     * A line that leans on Latin script, whose characters cannot be mapped back
     * to the original text, or whose output does not line up one-to-one with the
     * tokens is **skipped entirely** and keeps its provider line timing.
     */
    private suspend fun generateChinese(
        context: Context,
        document: LyricsDocument,
        trackKey: String,
        onProgress: ((Float) -> Unit)?,
    ): LyricsDocument? {
        val engine = ZhAlignmentEngine.forLanguage(context, "zh") ?: return null
        val modelKey = AutoSyncModelStore.timelineModelKey(context, "zh") ?: return null
        val audioFile = AutoSyncPlaybackBridge.audioResolver?.invoke(trackKey) ?: return null
        if (!audioFile.isFile || audioFile.length() == 0L) return null

        val range = decodeRange(document)
        val startedAt = android.os.SystemClock.elapsedRealtime()
        val pcm = runCatching {
            AutoSyncAudioDecoder.decode(
                file = audioFile,
                targetSampleRate = SAMPLE_RATE,
                startMs = range.first,
                endMs = range.second,
                timeoutMs = (30_000L + (range.second - range.first) / 2).coerceAtMost(180_000L),
                shouldContinue = {
                    MeloXSettingsRuntime.autoSyncWordLyricsEnabled &&
                        AutoSyncPlaybackBridge.isCurrentTrack?.invoke(trackKey) != false
                },
            )
        }.onFailure {
            if (it is CancellationException) throw it
            Log.w(TAG, "audio decode failed (zh)", it)
        }.getOrNull() ?: return null
        return pcm.use {
            alignChinese(context, document, trackKey, onProgress, engine, modelKey, pcm, range, startedAt)
        }
    }

    private suspend fun alignChinese(
        context: Context,
        document: LyricsDocument,
        trackKey: String,
        onProgress: ((Float) -> Unit)?,
        engine: ZhAlignmentEngine,
        modelKey: String,
        pcm: AutoSyncPcmFile,
        range: Pair<Long, Long>,
        startedAt: Long,
    ): LyricsDocument? {
        Log.i(TAG, "decoded(zh) ${pcm.sampleCount} samples in ${android.os.SystemClock.elapsedRealtime() - startedAt}ms")
        if (pcm.sampleCount * 1000L / SAMPLE_RATE < MIN_LINE_MS) return null
        val totalMs = range.first + pcm.sampleCount * 1000L / SAMPLE_RATE

        val timings = ArrayList<AutoSyncLineTiming>(document.lines.size)
        var failedLines = 0

        document.lines.forEachIndexed { index, line ->
            currentCoroutineContext().ensureActive()
            if (AutoSyncPlaybackBridge.isCurrentTrack?.invoke(trackKey) == false) return null
            onProgress?.invoke(index.toFloat() / document.lines.size.coerceAtLeast(1))
            if (!ZhText.isAlignableForChinese(line.text)) return@forEachIndexed
            // The decoded buffer starts at the range, not at 0 ms.
            if (windowFirst(document, index, line, totalMs, range.first) == null) return@forEachIndexed
            val window = windowFor(document, index, line, totalMs) ?: return@forEachIndexed
            val startSample = ((window.first - range.first) * SAMPLE_RATE / 1000L)
                .toInt().coerceIn(0, pcm.sampleCount)
            val endSample = ((window.second - range.first) * SAMPLE_RATE / 1000L)
                .toInt().coerceIn(startSample, pcm.sampleCount)
            if (endSample - startSample < SAMPLE_RATE / 10) return@forEachIndexed
            val snippet = pcm.readWindow(startSample, endSample)

            val normalized = ZhText.normalize(line.text)
            val tokenIds = ZhText.tokenIds(normalized, engine.manifest)
            if (tokenIds.isEmpty()) return@forEachIndexed

            val spans = runCatching { engine.alignWindow(snippet, tokenIds) }
                .onFailure { Log.w(TAG, "Chinese inference failed on line $index", it) }
                .getOrNull() ?: run { failedLines++; return@forEachIndexed }

            val units = chineseUnits(line.text, normalized, engine.manifest, spans)
                .let { sanitize(it, snippet.size * 1000L / SAMPLE_RATE) }
            if (units.isEmpty()) {
                failedLines++
                return@forEachIndexed
            }
            timings.add(
                AutoSyncLineTiming(
                    index = index,
                    units = units.map { unit ->
                        AutoSyncUnit(unit.text, window.first + unit.startMs, window.first + unit.endMs)
                    },
                    meanScore = 0.0,
                ),
            )
        }

        if (timings.isEmpty()) return null
        val timeline = AutoSyncTimeline(
            trackKey = trackKey,
            sourceHash = AutoSyncTimelineStore.sourceHash(document),
            language = "zh",
            engine = engine.manifest.sourceModel,
            lines = timings,
            createdAtMs = System.currentTimeMillis(),
            modelKey = modelKey,
        )
        if (AutoSyncPlaybackBridge.isCurrentTrack?.invoke(trackKey) == false) return null
        AutoSyncTimelineStore.save(context, timeline)
        Log.i(
            TAG,
            "aligned(zh) $trackKey lines=${timings.size}/${document.lines.size} " +
                "failed=$failedLines total=${android.os.SystemClock.elapsedRealtime() - startedAt}ms",
        )
        onProgress?.invoke(1f)
        return merge(document, timeline)
    }

    /**
     * Maps per-token spans back onto the original lyric characters.
     *
     * `normalizeZh` only deletes characters, so a greedy scan recovers the
     * position of every normalised character in the original line. When the
     * mapping is not exact the line is refused outright: timing that cannot be
     * attributed to the displayed text is never reported.
     */
    private fun chineseUnits(
        original: String,
        normalized: String,
        manifest: AutoSyncManifest,
        spans: List<ZhCifPostprocess.TokenSpan>,
    ): List<AutoSyncUnit> {
        if (spans.isEmpty() || normalized.isEmpty()) return emptyList()
        val lookup = HashMap<String, Int>(manifest.tokens.size * 2)
        manifest.tokens.forEachIndexed { index, token -> lookup.putIfAbsent(token, index) }
        val keptPositions = ArrayList<Int>(normalized.length)
        for (index in normalized.indices) {
            if (lookup.containsKey(normalized[index].toString())) keptPositions.add(index)
        }
        if (keptPositions.size != spans.size) return emptyList()

        val toOriginal = HashMap<Int, Int>(normalized.length * 2)
        var cursor = 0
        for (index in original.indices) {
            if (cursor < normalized.length && original[index] == normalized[cursor]) {
                toOriginal[cursor] = index
                cursor++
            }
        }
        if (cursor != normalized.length) return emptyList()

        val units = ArrayList<AutoSyncUnit>(spans.size)
        for (index in spans.indices) {
            val originalIndex = toOriginal[keptPositions[index]] ?: return emptyList()
            val character = original[originalIndex]
            if (character.isWhitespace()) continue
            units.add(
                AutoSyncUnit(character.toString(), spans[index].startMs, spans[index].endMs),
            )
        }
        return units
    }


    /**
     * Span of audio AutoSync actually needs: from shortly before the first
     * lyric to shortly after the last one. Intro, interludes and outro are
     * skipped, which can be more than half of a track.
     */
    private fun decodeRange(document: LyricsDocument): Pair<Long, Long> {
        val first = document.lines.firstOrNull()?.timeMs?.coerceAtLeast(0L) ?: 0L
        val last = document.lines.lastOrNull()?.timeMs?.coerceAtLeast(first) ?: first
        val start = (first - RANGE_LEAD_MS).coerceAtLeast(0L)
        val end = last + RANGE_TAIL_MS
        return start to end.coerceAtLeast(start + 1_000L)
    }

    /** Null when the line's window falls outside the decoded range. */
    private fun windowFirst(
        document: LyricsDocument,
        index: Int,
        line: LyricLine,
        totalMs: Long,
        rangeStartMs: Long,
    ): Long? {
        val window = windowFor(document, index, line, totalMs) ?: return null
        return window.first.takeIf { it >= rangeStartMs }
    }

    private const val RANGE_LEAD_MS = 5_000L
    private const val RANGE_TAIL_MS = 15_000L

    private fun currentRssBytes(): Long = runCatching {
        File("/proc/self/status").useLines { lines ->
            lines.first { it.startsWith("VmRSS:") }.substringAfter(':').trim().substringBefore(' ').toLong() * 1024L
        }
    }.getOrDefault(-1L)

    /** Merges generated timing into the display document without touching text. */
    fun merge(document: LyricsDocument, timeline: AutoSyncTimeline): LyricsDocument? {
        val byIndex = timeline.lines.associateBy { it.index }
        var changed = false
        val lines = document.lines.mapIndexed { index, line ->
            val timing = byIndex[index]?.takeIf { it.units.isNotEmpty() } ?: return@mapIndexed line
            changed = true
            line.copy(
                syllables = timing.units.map { unit ->
                    LyricSyllable(unit.text, unit.startMs, unit.endMs.coerceAtLeast(unit.startMs + 1L))
                },
                timingKind = LyricTimingKind.Precise,
            )
        }
        if (!changed) return null
        return document.copy(
            lines = lines,
            quality = LyricQuality.WordSynchronized,
            // Lines the model could not align keep provider line timing; never let a
            // renderer fill those with an even split that looks model-generated.
            pseudoTimingAllowed = false,
            autoSyncModelKey = timeline.modelKey,
        )
    }

    /**
     * Audio window for one line, mirroring the reference engine: from the line
     * timestamp to the next line, plus padding that never crosses a neighbour,
     * capped so a long instrumental tail is not fed to the model.
     */
    private fun windowFor(
        document: LyricsDocument,
        index: Int,
        line: LyricLine,
        totalMs: Long,
    ): Pair<Long, Long>? {
        val nextStart = document.lines.getOrNull(index + 1)?.timeMs
        var start = max(0L, line.timeMs - PADDING_MS)
        var end = min(nextStart?.takeIf { it > line.timeMs } ?: totalMs, totalMs)
        end = min(totalMs, end + PADDING_MS)
        if (end - line.timeMs > MAX_LINE_MS) end = min(totalMs, line.timeMs + MAX_LINE_MS)
        if (end <= start) {
            end = min(totalMs, start + MAX_LINE_MS)
        }
        if (end - start < MIN_LINE_MS) return null
        return start to end
    }

    /** Clamps units into the window and enforces monotonic, positive durations. */
    private fun sanitize(units: List<AutoSyncUnit>, windowMs: Long): List<AutoSyncUnit> {
        if (units.isEmpty()) return units
        val result = ArrayList<AutoSyncUnit>(units.size)
        var lastEnd = 0L
        units.forEach { unit ->
            var start = unit.startMs.coerceIn(0L, windowMs)
            var end = unit.endMs.coerceIn(0L, windowMs)
            if (end < start) {
                val swap = start
                start = end
                end = swap
            }
            if (start < lastEnd) start = lastEnd
            if (end <= start) end = min(windowMs, start + 10L)
            if (end <= start) return@forEach
            result.add(AutoSyncUnit(unit.text, start, end))
            lastEnd = end
        }
        return result
    }
}

/**
 * Lets the playback service hand AutoSync the same cached audio bytes the
 * analysis pipeline already uses, without the lyric loader needing a decoder or
 * a media item of its own. Both live in the same process.
 */
object AutoSyncPlaybackBridge {
    /** Resolves the currently playing track to a local file, if one is cached. */
    @Volatile
    var audioResolver: (suspend (String) -> File?)? = null

    @Volatile
    var isCurrentTrack: (suspend (String) -> Boolean)? = null
}
