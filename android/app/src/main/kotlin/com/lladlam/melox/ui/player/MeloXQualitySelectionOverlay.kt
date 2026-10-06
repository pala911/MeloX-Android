package com.lladlam.melox.ui.player

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lladlam.melox.core.audio.MusicQuality
import com.lladlam.melox.core.audio.MusicQualityRuntime
import com.lladlam.melox.core.audio.NeteaseQualityClient
import com.lladlam.melox.core.audio.SongAudioResource
import com.lladlam.melox.core.audio.SongAudioAvailability
import com.lladlam.melox.core.download.MeloXDownloadStore
import com.lladlam.melox.core.music.model.AudioQualityTier
import com.lladlam.melox.core.music.model.MusicSource
import com.lladlam.melox.playback.PlaybackTrackIdentity
import com.lladlam.melox.playback.CrossProviderPlaybackRuntime
import com.lladlam.melox.playback.PlaybackStageRuntime
import com.lladlam.melox.playback.ProviderPlaybackQualityRuntime
import com.lladlam.melox.core.music.provider.PlaybackAccountStore
import com.lladlam.melox.ui.glass.MeloXGlassButton
import com.lladlam.melox.ui.glass.MeloXGlassButtonStyle
import com.lladlam.melox.ui.glass.MeloXGlassDialog
import kotlinx.coroutines.delay

/** Reports the active audio quality; changing the preference belongs to Settings. */
@Composable
internal fun MeloXQualitySelectionOverlay(
    state: MeloXPlaybackUiState,
    visible: Boolean,
    onDismiss: () -> Unit,
    onOpenPlaybackSettings: () -> Unit,
) {
    val context = LocalContext.current.applicationContext
    val downloads = remember(context) { MeloXDownloadStore.get(context) }
    val client = remember(context) {
        NeteaseQualityClient(
            cookieProvider = { PlaybackAccountStore.neteaseCookie(context) },
        )
    }
    val identity = remember(state.mediaId) { state.mediaId?.let(PlaybackTrackIdentity::decode) }
    val source = identity?.source ?: MusicSource.Netease
    val foreground = MaterialTheme.colorScheme.onSurface
    val songId = identity
        ?.takeIf { it.source == MusicSource.Netease }
        ?.value
        ?.toLongOrNull()
    val downloadedQuality = songId?.let(downloads::downloadedQuality)
    var availability by remember(state.mediaId, visible) {
        mutableStateOf(SongAudioAvailability.Unknown)
    }
    var providerActual by remember(state.mediaId, visible) {
        mutableStateOf(ProviderPlaybackQualityRuntime.actualFor(identity))
    }
    var providerResource by remember(state.mediaId, visible) {
        mutableStateOf(ProviderPlaybackQualityRuntime.resourceFor(identity))
    }
    var fallbackSource by remember(state.mediaId, visible) {
        mutableStateOf(CrossProviderPlaybackRuntime.sourceFor(songId))
    }

    LaunchedEffect(visible, source, songId, downloadedQuality) {
        if (!visible) return@LaunchedEffect
        if (source != MusicSource.Netease) {
            availability = SongAudioAvailability.Unknown
            while (visible) {
                providerActual = ProviderPlaybackQualityRuntime.actualFor(identity)
                providerResource = ProviderPlaybackQualityRuntime.resourceFor(identity)
                delay(180L)
            }
            return@LaunchedEffect
        }
        fallbackSource = CrossProviderPlaybackRuntime.sourceFor(songId)
        if (songId == null) return@LaunchedEffect
        if (downloadedQuality != null) {
            availability = SongAudioAvailability.Unknown
            return@LaunchedEffect
        }
        availability = runCatching { client.audioAvailability(songId) }
            .getOrDefault(SongAudioAvailability.Unknown)
    }
    LaunchedEffect(visible, songId) {
        if (!visible || songId == null) return@LaunchedEffect
        while (visible) {
            fallbackSource = CrossProviderPlaybackRuntime.sourceFor(songId)
            delay(180L)
        }
    }

    BackHandler(enabled = visible, onBack = onDismiss)

    // 「当前音质」必须读生效档，与播放页标签（MusicQualityRuntime.selected）同源。
    // MusicQualityPreferences.read 是设置页回显用的基线档；命中移动数据单档位覆盖时
    // 两者不同，会造成标签显示覆盖档、弹层显示基线档的不一致。
    val selected = downloadedQuality ?: MusicQualityRuntime.selected
    val actualQuality = when {
        downloadedQuality != null -> downloadedQuality
        source == MusicSource.Netease -> MusicQualityRuntime.actualFor(songId) ?: selected
        providerActual != null -> providerActual!!.toMusicQuality() ?: selected
        else -> selected
    }
    // 与播放页标签同一优先级：跨源兜底 > 实际服务的 stage（LX/CHKSZ/bili）>
    // mediaId 自己的源。只有 stage 与 mediaId 源不同（例如网易云 id 被 LX 放了）
    // 才标「实际音源」，纯官方网易云仍显示「当前播放」。
    val stageLabel = PlaybackStageRuntime.stageFor(state.mediaId)
    val ownStage = when (source) {
        MusicSource.Netease -> PlaybackStageRuntime.LabelNetease
        MusicSource.Local -> PlaybackStageRuntime.LabelLocal
        else -> source.displayName
    }
    val actualSourceName = fallbackSource?.displayName ?: stageLabel ?: ownStage
    val sourceLabel = when {
        downloadedQuality != null -> "已下载音频"
        fallbackSource != null -> "实际音源：${fallbackSource!!.displayName}"
        stageLabel != null && stageLabel != ownStage -> "实际音源：$stageLabel"
        source != MusicSource.Netease -> "${source.displayName} 音源"
        else -> "当前播放"
    }
    val details = qualityDetails(
        resource = availability.resourceFor(actualQuality),
        provider = providerResource,
        // What the bytes on the wire actually measure (the LX CDN probe wins over
        // the tier label below): showing the service's printed number for a file a
        // third-party source handed back would claim a rate the stream may not hit.
        measuredBitrate = MusicQualityRuntime.bitrateFor(songId),
        quality = actualQuality,
        sourceDisplayName = actualSourceName,
        downloaded = downloadedQuality != null,
    )

    MeloXGlassDialog(
        visible = visible,
        onDismiss = onDismiss,
    ) {
        Text("音质", style = MaterialTheme.typography.titleLarge)
        Text(
            text = "当前音质：${actualQuality.title}",
            modifier = Modifier.padding(top = 9.dp),
            color = foreground.copy(alpha = 0.68f),
            fontSize = 14.sp,
        )
        Text(
            text = details,
            modifier = Modifier.padding(top = 4.dp),
            color = foreground.copy(alpha = 0.68f),
            fontSize = 14.sp,
        )
        Text(
            text = sourceLabel,
            modifier = Modifier.padding(top = 4.dp),
            color = foreground.copy(alpha = 0.54f),
            fontSize = 12.sp,
        )
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            MeloXGlassButton(
                onClick = {
                    onDismiss()
                    onOpenPlaybackSettings()
                },
                modifier = Modifier.weight(1f),
                style = MeloXGlassButtonStyle.Plain,
            ) { Text("设置") }
            MeloXGlassButton(
                onClick = onDismiss,
                modifier = Modifier.weight(1f),
                style = MeloXGlassButtonStyle.BorderedProminent,
            ) { Text("确认") }
        }
    }
}

private fun SongAudioAvailability.resourceFor(quality: MusicQuality): SongAudioResource? = when (quality) {
    MusicQuality.Standard -> standard
    MusicQuality.High -> high ?: medium
    MusicQuality.Lossless -> lossless
    MusicQuality.HiResolution -> hiResolution
    MusicQuality.HighDefinitionSurround -> highDefinitionSurround
    MusicQuality.ImmersiveSurround -> immersiveSurround
    MusicQuality.UltraClearMaster -> ultraClearMaster
}

private fun qualityDetails(
    resource: SongAudioResource?,
    provider: ProviderPlaybackQualityRuntime.ProviderAudioResource?,
    measuredBitrate: Int?,
    quality: MusicQuality,
    sourceDisplayName: String,
    downloaded: Boolean,
): String {
    if (downloaded) return "本地文件 · 参数以下载音频为准"
    val details = buildList {
        // Measured beats labelled: the LX probe and the provider tier path both see
        // the file that is playing, while resource.bitrate is only what the music
        // service prints for the tier. They usually agree, but a third-party source
        // can hand back a file at a different rate than the service's own copy.
        (measuredBitrate ?: provider?.bitrate ?: resource?.bitrate)?.let { add("${it / 1000} kbps") }
        resource?.sampleRate?.let { add("${it / 1_000.0} kHz") }
        provider?.format?.let { add(it) }
        when (quality) {
            MusicQuality.HighDefinitionSurround,
            MusicQuality.ImmersiveSurround,
            -> add("5.1 声道")
            // 非环绕档按立体声报，与网易云链路同口径；否则 provider 歌因为拿不到
            // SongAudioResource 会连声道都不显示，只剩一句「未提供音频参数」。
            else -> add("2 声道")
        }
    }
    return details.joinToString(" · ").ifBlank { "$sourceDisplayName 未提供音频参数" }
}

private fun AudioQualityTier.toMusicQuality(): MusicQuality? = when (this) {
    AudioQualityTier.Standard -> MusicQuality.Standard
    AudioQualityTier.High -> MusicQuality.High
    AudioQualityTier.Lossless -> MusicQuality.Lossless
    AudioQualityTier.HiResolution -> MusicQuality.HiResolution
    AudioQualityTier.Immersive -> MusicQuality.ImmersiveSurround
    AudioQualityTier.Master -> MusicQuality.UltraClearMaster
}
