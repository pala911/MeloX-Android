package com.lladlam.melox.core.audio

import android.content.Context
import java.util.concurrent.ConcurrentHashMap

/** Android mirror of MeloX/Core/Settings/Playback/MusicQuality.swift. */
enum class MusicQuality(
    val apiLevel: String,
    val title: String,
) {
    Standard("standard", "标准"),
    High("exhigh", "高品质"),
    Lossless("lossless", "无损"),
    HiResolution("hires", "Hi-Res"),
    HighDefinitionSurround("jyeffect", "高清环绕声"),
    ImmersiveSurround("sky", "沉浸环绕声"),
    UltraClearMaster("jymaster", "超清母带");

    val requiresImmersiveType: Boolean
        get() = this == ImmersiveSurround

    val playbackFallbacks: List<MusicQuality>
        get() = when (this) {
            Standard -> listOf(Standard)
            High -> listOf(High, Standard)
            Lossless -> listOf(Lossless, High, Standard)
            HiResolution -> listOf(HiResolution, Lossless, High, Standard)
            HighDefinitionSurround -> listOf(HighDefinitionSurround, Lossless, High, Standard)
            ImmersiveSurround -> listOf(
                ImmersiveSurround,
                HighDefinitionSurround,
                Lossless,
                High,
                Standard,
            )
            UltraClearMaster -> listOf(UltraClearMaster, HiResolution, Lossless, High, Standard)
        }

    fun playbackCandidates(availability: SongAudioAvailability): List<MusicQuality> =
        playbackFallbacks.filter { availability.supports(it.apiLevel) != false }

    companion object {
        fun fromApiLevel(level: String?): MusicQuality? =
            entries.firstOrNull { it.apiLevel == level }
    }
}

data class SongAudioResource(
    val bitrate: Int?,
    val sampleRate: Int?,
    val size: Long?,
)

data class SongAudioAvailability(
    val standard: SongAudioResource? = null,
    val medium: SongAudioResource? = null,
    val high: SongAudioResource? = null,
    val lossless: SongAudioResource? = null,
    val hiResolution: SongAudioResource? = null,
    val highDefinitionSurround: SongAudioResource? = null,
    val immersiveSurround: SongAudioResource? = null,
    val ultraClearMaster: SongAudioResource? = null,
    val isKnown: Boolean = false,
) {
    fun supports(apiLevel: String): Boolean? {
        if (!isKnown) return null
        return when (apiLevel) {
            "standard" -> standard != null
            "exhigh" -> high != null
            "lossless" -> lossless != null
            "hires" -> hiResolution != null
            "jyeffect" -> highDefinitionSurround != null
            "sky" -> immersiveSurround != null
            "jymaster" -> ultraClearMaster != null
            else -> false
        }
    }

    companion object {
        val Unknown = SongAudioAvailability()
    }
}

object MusicQualityPreferences {
    private const val PREFERENCES_NAME = "ysyy_playback"
    private const val KEY_QUALITY = "music_quality"

    fun read(context: Context): MusicQuality {
        val raw = context.applicationContext
            .getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
            .getString(KEY_QUALITY, null)
        return MusicQuality.fromApiLevel(raw) ?: MusicQuality.Standard
    }

    fun write(context: Context, quality: MusicQuality) {
        context.applicationContext
            .getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_QUALITY, quality.apiLevel)
            .apply()
        MusicQualityRuntime.selected = quality
    }
}

/**
 * Small process-local bridge between the Media3 resolver and Compose UI.
 * The resolver records the server-returned `level`, so the chip can display
 * the actual quality after MeloX-style fallback instead of the requested label.
 */
object MusicQualityRuntime {
    private data class QualityRecord(
        val requested: MusicQuality,
        val actual: MusicQuality,
        /**
         * Bitrate measured off the stream that is actually playing (the LX CDN
         * probe), when there is one. Null for resolutions that report a tier but
         * never see the bytes - the UI then falls back to the bitrate the music
         * service labels that tier with.
         */
        val bitrate: Int? = null,
    )

    @Volatile
    private var baseSelected: MusicQuality = MusicQuality.Standard

    /**
     * 写入 = 存用户的基础选择（设置里选的档）；读取 = 当前生效档——命中移动数据
     * 单档位覆盖（非 WiFi 且设置了覆盖档）时返回覆盖档，否则返回基础选择。
     * 设置页/下载等要回显用户选择的地方用 [MusicQualityPreferences.read]，不要读这里。
     */
    var selected: MusicQuality
        get() {
            val overrideQuality = MobileDataQualityRuntime.overrideQuality
            return if (overrideQuality != null && MobileDataQualityRuntime.onMobileData) {
                overrideQuality
            } else {
                baseSelected
            }
        }
        set(value) {
            baseSelected = value
        }

    private val actualBySong = ConcurrentHashMap<Long, QualityRecord>()

    fun recordActual(
        songId: Long,
        requested: MusicQuality,
        actual: MusicQuality,
        bitrate: Int? = null,
    ) {
        // Background analysis can resolve the same song at Standard while the
        // foreground decoder keeps playing Hi-Res. Never let that secondary
        // request replace the quality reported for the user's active selection.
        if (requested == selected) {
            actualBySong[songId] = QualityRecord(requested, actual, bitrate)
        }
    }

    fun actualFor(songId: Long?): MusicQuality? =
        songId
            ?.let(actualBySong::get)
            ?.takeIf { it.requested == selected }
            ?.actual

    /**
     * Measured bitrate for [songId], under the same freshness rule as
     * [actualFor]: a record written for a selection the user has since changed
     * must not contribute a number to a dialog about the current tier.
     */
    fun bitrateFor(songId: Long?): Int? =
        songId
            ?.let(actualBySong::get)
            ?.takeIf { it.requested == selected }
            ?.bitrate

    fun clear(songId: Long? = null) {
        if (songId == null) actualBySong.clear() else actualBySong.remove(songId)
    }
}
