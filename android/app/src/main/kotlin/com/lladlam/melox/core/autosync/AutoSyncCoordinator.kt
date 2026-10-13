package com.lladlam.melox.core.autosync

import android.content.Context
import android.util.Log
import com.lladlam.melox.core.lyrics.LyricSyllable
import com.lladlam.melox.core.lyrics.LyricTimingKind
import com.lladlam.melox.core.lyrics.LyricsDocument
import com.lladlam.melox.core.lyrics.LyricQuality
import com.lladlam.melox.ui.settings.MeloXSettingsRuntime
import java.io.File

/**
 * AutoSync: previously an on-device ONNX forced aligner that turned a line-timed
 * lyric into a real word-level timeline.
 *
 * ## 本地 fork 已停用神经对齐（2026-10-13）
 *
 * 上游 0.6.5 用 `onnxruntime-android` 做 CTC 强制对齐，代价是：
 *
 *  1. **包体 +55.7 MB**（`libonnxruntime.so` arm64 32.6 MB + armv7 23.1 MB），
 *     占整包 68%。Neri 同类功能只用解析 YRC，整包 11.6 MB。
 *  2. **release 构建必崩**：上游没给 `ai.onnxruntime` 写 ProGuard keep 规则，
 *     `.so` 运行时按 JNI 类名+签名反查 Java 方法，R8 一改名就
 *     `JNI DETECTED ERROR: java_class == null` → SIGABRT。症状是加载歌词时
 *     进程直接死，表现为「放几秒就停并退回后台」。上游 CI 只跑 `assembleDebug`
 *     （不过 R8）所以从未暴露。
 *  3. 多数歌根本用不上：网易云 YRC / AMLL 本身就带逐字时间戳，**解析即可**，
 *     不需要神经网络。只有「完全没有逐字时间戳」的歌才需要强制对齐。
 *
 * 因此这里保留**已缓存结果的读取**（`applyStored` / `merge`，让之前生成的
 * 时间轴不失效），但**不再生成**新时间轴 —— `generate()` 直接返回 `null`。
 * 逐字歌词继续由 [com.lladlam.melox.core.lyrics.AmlldbLyricsClient] 等
 * 服务端逐字源提供，功能不缺。
 */
internal object AutoSyncCoordinator {

    private const val TAG = "MeloXAutoSync"

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
     * 神经对齐已停用 —— 永远不生成新时间轴。
     *
     * 保留这个签名是为了让 [AutoSyncPlaybackBridge] 与歌词加载器那侧的调用点
     * 不用一起改；逐字歌词由服务端逐字源（YRC / AMLL）提供。
     */
    suspend fun generate(
        context: Context,
        document: LyricsDocument,
        trackKey: String,
        onProgress: ((Float) -> Unit)? = null,
    ): LyricsDocument? {
        Log.i(
            TAG,
            "generate skipped: on-device neural alignment disabled in this fork " +
                "(songId=$trackKey); word timing comes from YRC/AMLL",
        )
        return null
    }

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
}

/**
 * Lets the playback service lend AutoSync the same cached audio bytes the
 * analysis pipeline already uses, without the lyric loader needing a decoder or
 * a media item of its own. Both live in the same process.
 *
 * 神经对齐停用后已无人消费，保留仅为不改动 Service 侧的清理代码。
 */
object AutoSyncPlaybackBridge {
    /** Resolves the currently playing track to a local file, if one is cached. */
    @Volatile
    var audioResolver: (suspend (String) -> File?)? = null

    @Volatile
    var isCurrentTrack: (suspend (String) -> Boolean)? = null
}