package com.lladlam.melox.playback

import com.lladlam.melox.core.audio.MusicQualityRuntime
import com.lladlam.melox.core.music.model.AudioQualityTier
import com.lladlam.melox.core.music.model.MusicResourceId
import java.util.concurrent.ConcurrentHashMap

/** Process-local bridge from provider VKey resolution to the shared player UI. */
object ProviderPlaybackQualityRuntime {
    private data class QualityRecord(
        val requested: AudioQualityTier,
        val actual: AudioQualityTier,
        val bitrate: Int? = null,
        val format: String? = null,
    )

    /** 音质弹层展示用：解析时实测的流参数（LX/CHKSZ 兜底链路拿不到，保持 null）。 */
    data class ProviderAudioResource(
        val bitrate: Int? = null,
        val format: String? = null,
    )

    private val actualByTrack = ConcurrentHashMap<String, QualityRecord>()

    fun recordActual(
        id: MusicResourceId,
        requested: AudioQualityTier,
        actual: AudioQualityTier,
        bitrate: Int? = null,
        format: String? = null,
    ) {
        // Independent background analysis resolves Standard for the same stable
        // media identity. Preserve the foreground result selected by the user.
        if (requested == MusicQualityRuntime.selected.toCommonTier()) {
            actualByTrack[PlaybackTrackIdentity.encode(id)] =
                QualityRecord(requested, actual, bitrate, format)
        }
    }

    /**
     * 记录仍然有效才返回（音质档一变，Media3 还没重新解析同一条 provider item，
     * 此时旧码率不能拿来展示——与 [actualFor] 同一道护栏）。
     */
    private fun freshRecord(id: MusicResourceId?): QualityRecord? {
        id ?: return null
        val record = actualByTrack[PlaybackTrackIdentity.encode(id)] ?: return null
        return record.takeIf { record.requested == MusicQualityRuntime.selected.toCommonTier() }
    }

    fun actualFor(id: MusicResourceId?): AudioQualityTier? = freshRecord(id)?.actual

    fun resourceFor(id: MusicResourceId?): ProviderAudioResource? = freshRecord(id)
        ?.let { ProviderAudioResource(it.bitrate, it.format) }
        ?.takeIf { it.bitrate != null || it.format != null }

    fun clear(id: MusicResourceId? = null) {
        if (id == null) actualByTrack.clear()
        else actualByTrack.remove(PlaybackTrackIdentity.encode(id))
    }
}
