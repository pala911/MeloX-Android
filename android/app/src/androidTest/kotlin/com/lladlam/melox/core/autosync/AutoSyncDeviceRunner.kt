package com.lladlam.melox.core.autosync

import android.app.Activity
import android.app.Instrumentation
import android.os.Bundle
import android.os.SystemClock
import android.media.AudioManager
import android.util.Log
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlin.math.abs
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import com.lladlam.melox.core.account.NeteaseSessionStore
import com.lladlam.melox.core.network.NeteaseSearchClient
import com.lladlam.melox.ui.settings.MeloXSettingsRuntime
import com.lladlam.melox.core.lyrics.LyricTimelineProcessor
import com.lladlam.melox.core.network.MeloXGitHubRouting

/**
 * Device regression runner. Uses a caller-supplied cached audio file; no songs,
 * lyrics or weights are shipped in either APK. Run with `am instrument -w -e
 * audio cache/automix_analysis/<file>.media .../AutoSyncDeviceRunner`.
 */
class AutoSyncDeviceRunner : Instrumentation() {
    private lateinit var args: Bundle

    override fun onCreate(arguments: Bundle?) {
        args = arguments ?: Bundle()
        start()
    }

    override fun onStart() {
        val result = Bundle()
        try {
            runBlocking(Dispatchers.IO) {
                if (args.getString("syncModels") == "true") {
                    val repository = AutoSyncModelRepository(targetContext, MeloXGitHubRouting(targetContext))
                    val index = checkNotNull(repository.fetchIndex().getOrThrow())
                    check(index.releaseTag == AutoSyncModelRepository.RELEASE_TAG)
                    result.putString("modelRelease", index.releaseTag)
                    result.putString("installedLanguages", AutoSyncModelStore.installedLanguages(targetContext).joinToString(","))
                }
                val relative = checkNotNull(args.getString("audio")) { "supply -e audio <cached path>" }
                val audio = File(targetContext.dataDir, relative).canonicalFile
                check(audio.toPath().startsWith(targetContext.dataDir.canonicalFile.toPath()))
                check(audio.isFile)
                val existingPcm = audio.parentFile?.listFiles()?.filter { it.name.startsWith("autosync-pcm-") }
                    ?.map { it.name }?.toSet().orEmpty()
                val started = SystemClock.elapsedRealtime()
                AutoSyncAudioDecoder.decode(audio, endMs = args.getString("endMs")?.toLong() ?: Long.MAX_VALUE,
                    timeoutMs = 180_000L).use { pcm ->
                    check(pcm.sampleCount > 16000)
                    val durationMs = pcm.sampleCount * 1000L / pcm.sampleRate
                    result.putLong("decodeMs", SystemClock.elapsedRealtime() - started)
                    result.putLong("audioMs", durationMs)
                    val windowStart = args.getString("startMs")?.toLong() ?: minOf(15000L, (durationMs - 1000L).coerceAtLeast(0L))
                    val first = (windowStart * pcm.sampleRate / 1000L).toInt()
                    val last = minOf(first + pcm.sampleRate * 8, pcm.sampleCount)
                    val snippet = pcm.readWindow(first, last)
                    check(snippet.any { abs(it) > 0.001f }) { "decoded window is silent" }
                    val text = args.getString("text")
                    if (!text.isNullOrBlank()) {
                        val engine = checkNotNull(OnnxCtcEngine.forLanguage(targetContext, "en"))
                        val ids = AutoSyncText.tokenIds(AutoSyncText.normalizeEn(text), engine.manifest)
                        val inferenceAt = SystemClock.elapsedRealtime()
                        val alignment = engine.alignWindow(snippet, ids)
                        check(alignment.feasible && alignment.spans.isNotEmpty()) { "alignment failed: ${alignment.reasons}" }
                        result.putLong("inferenceMs", SystemClock.elapsedRealtime() - inferenceAt)
                        result.putInt("alignedTokens", alignment.spans.size)
                    }
                    // Non-frame-aligned seek must produce the same origin as full decode.
                    val seekMs = 10013L
                    if (durationMs > seekMs + 3000L) AutoSyncAudioDecoder.decode(audio, startMs = seekMs, endMs = seekMs + 3000L,
                        timeoutMs = 30_000L, mp3FramesPerBuffer = 1).use { cropped ->
                        check(abs(cropped.sampleCount - 48000) <= 32) { "seek length=${cropped.sampleCount}" }
                        val reference = pcm.readWindow((seekMs * 16).toInt(), ((seekMs + 3000) * 16).toInt())
                        val actual = cropped.readWindow(0, cropped.sampleCount)
                        // Ignore decoder warm-up and filter edges, compare the interior.
                        val count = minOf(reference.size, actual.size)
                        var error = 0.0
                        for (i in 4000 until count - 100) error += abs(reference[i] - actual[i])
                        val mean = error / (count - 4100)
                        result.putDouble("seekMeanAbsError", mean)
                        check(mean < 0.01) { "seek origin mismatch: $mean" }
                    }
                }
                check(audio.parentFile?.listFiles()?.filter { it.name.startsWith("autosync-pcm-") }
                    ?.map { it.name }?.toSet().orEmpty() == existingPcm) { "device test leaked a PCM file" }
                args.getString("songId")?.toLong()?.let { id ->
                    Log.i("MeloXAutoSync", "device regression: loading provider lyrics for $id")
                    val document = LyricTimelineProcessor.process(NeteaseSearchClient(
                        cookieProvider = { NeteaseSessionStore.readCookie(targetContext) },
                    ).lyrics(id))
                    check(document.lines.isNotEmpty()) { "provider returned no lyrics" }
                    check(document.lines.none { it.syllables.isNotEmpty() }) { "song already has authored word timing" }
                    Log.i("MeloXAutoSync", "device regression: generating ${document.lines.size} lines for $id")
                    val resolver = AutoSyncPlaybackBridge.audioResolver
                    val current = AutoSyncPlaybackBridge.isCurrentTrack
                    val enabled = MeloXSettingsRuntime.autoSyncWordLyricsEnabled
                    try {
                        AutoSyncPlaybackBridge.audioResolver = { audio }
                        AutoSyncPlaybackBridge.isCurrentTrack = { true }
                        MeloXSettingsRuntime.autoSyncWordLyricsEnabled = true
                        val generationAt = SystemClock.elapsedRealtime()
                        val audioManager = targetContext.getSystemService(AudioManager::class.java)
                        val musicBefore = audioManager?.isMusicActive == true
                        val rssBefore = rssBytes()
                        val running = AtomicBoolean(true)
                        val peakRss = AtomicLong(rssBefore)
                        val monitor = Thread {
                            while (running.get()) {
                                peakRss.accumulateAndGet(rssBytes(), ::maxOf)
                                try { Thread.sleep(100) } catch (_: InterruptedException) { break }
                            }
                        }.apply { name = "AutoSyncRssMonitor"; isDaemon = true; start() }
                        val upgraded = try {
                            checkNotNull(AutoSyncCoordinator.generate(targetContext, document, "netease:$id"))
                        } finally {
                            running.set(false)
                            monitor.join(500)
                        }
                        val musicAfter = audioManager?.isMusicActive == true
                        check(upgraded.lines.map { it.text } == document.lines.map { it.text })
                        check(!upgraded.pseudoTimingAllowed)
                        result.putLong("generationMs", SystemClock.elapsedRealtime() - generationAt)
                        result.putLong("rssBeforeBytes", rssBefore)
                        result.putLong("rssPeakBytes", peakRss.get())
                        result.putBoolean("musicActiveBefore", musicBefore)
                        result.putBoolean("musicActiveAfter", musicAfter)
                        result.putBoolean("musicActivityPreserved", musicBefore == musicAfter)
                        result.putInt("wordLines", upgraded.lines.count { it.syllables.isNotEmpty() })
                        result.putInt("units", upgraded.lines.sumOf { it.syllables.size })
                        val cached = checkNotNull(AutoSyncCoordinator.applyStored(targetContext, document, "netease:$id"))
                        check(cached == upgraded) { "persisted timeline differs from generated document" }
                    } finally {
                        AutoSyncPlaybackBridge.audioResolver = resolver
                        AutoSyncPlaybackBridge.isCurrentTrack = current
                        MeloXSettingsRuntime.autoSyncWordLyricsEnabled = enabled
                    }
                }
                result.putString("stream", "AutoSync device regression passed: $result\n")
            }
            Log.i("MeloXAutoSync", result.toString())
            finish(Activity.RESULT_OK, result)
        } catch (error: Throwable) {
            Log.e("MeloXAutoSync", "device regression failed", error)
            result.putString("stream", error.stackTraceToString())
            finish(Activity.RESULT_CANCELED, result)
        }
    }

    private fun rssBytes(): Long = runCatching {
        File("/proc/self/status").useLines { lines ->
            lines.first { it.startsWith("VmRSS:") }.substringAfter(':').trim().substringBefore(' ').toLong() * 1024L
        }
    }.getOrDefault(-1L)
}
