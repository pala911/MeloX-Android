package com.lladlam.melox.ui.player

import android.content.Context
import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import com.lladlam.melox.R
import com.lladlam.melox.core.music.model.MusicArtistRef
import com.lladlam.melox.core.music.model.MusicPlaylistSummary
import com.lladlam.melox.core.music.model.MusicResourceId
import com.lladlam.melox.core.music.model.MusicSource
import com.lladlam.melox.core.music.model.MusicTrack
import com.lladlam.melox.core.music.model.ProviderTrackMetadata
import com.lladlam.melox.core.music.provider.FavoriteCapability
import com.lladlam.melox.core.music.provider.DownloadCapability
import com.lladlam.melox.core.music.provider.MeloXMusicProviders
import com.lladlam.melox.core.music.provider.PlaylistSyncCapability
import com.lladlam.melox.core.music.provider.PlaylistWriteCapability
import com.lladlam.melox.core.music.provider.ProviderAccountManager
import com.lladlam.melox.core.network.MeloXSearchKind
import com.lladlam.melox.core.provider.bilibili.BilibiliLyricOffsetStore
import com.lladlam.melox.core.provider.local.LocalRecognitionCoordinator
import com.lladlam.melox.core.download.MeloXProviderDownloadStore
import com.lladlam.melox.ui.glass.MeloXActionIcon
import com.lladlam.melox.ui.animation.meloXPanelEnter
import com.lladlam.melox.ui.animation.meloXPanelExit
import com.lladlam.melox.ui.glass.MeloXIosGroupedList
import com.lladlam.melox.ui.glass.MeloXIosListRow
import com.lladlam.melox.ui.glass.MeloXLiquidSlider
import com.lladlam.melox.ui.search.MeloXSearchLaunchBus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.compose.ui.res.stringResource

private enum class ProviderSongActionPage {
    Main,
    Sleep,
    Playlists,
}

/**
 * Actions valid for non-NetEase provider tracks. Remote writes only appear when
 * the active provider exposes an explicit capability backed by a real platform
 * API. QQ uses FavoriteCapability; Kugou uses PlaylistWriteCapability.
 */
@Composable
internal fun MeloXProviderSongActionsOverlay(
    state: MeloXPlaybackUiState,
    identity: MusicResourceId,
    visible: Boolean,
    onDismiss: () -> Unit,
    onNavigateSearch: ((String, MeloXSearchKind) -> Unit)? = null,
    onLocalMetadataChanged: () -> Unit = {},
    // ★ 「天际歌词」入口（2026-10-07）。只有平板双栏传得进来，见
    //   [MeloXNowPlayingActionsSheet] 同名声明的注释；为 null 时本行不渲染。
    onShowSkyline: (() -> Unit)? = null,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val provider = remember(identity.source) {
        MeloXMusicProviders.create(context).require(identity.source)
    }
    val favoriteCapability = provider as? FavoriteCapability
    val downloadCapability = provider as? DownloadCapability
    val downloadStore = remember { MeloXProviderDownloadStore.get(context) }
    val playlistWriteCapability = provider as? PlaylistWriteCapability
    val playlistSyncCapability = provider as? PlaylistSyncCapability
    val accountManager = remember { ProviderAccountManager(context) }
    val providerLoggedIn = remember(identity.source, visible) {
        accountManager.state(identity.source).loggedIn
    }
    val actionTrack = remember(identity, state.title, state.artist, state.album, state.durationMs) {
        MusicTrack(
            id = identity,
            title = state.title.ifBlank { "未知歌曲" },
            artists = listOf(
                MusicArtistRef(name = state.artist.ifBlank { "未知歌手" }),
            ),
            durationMs = state.durationMs.takeIf { it > 0L },
            providerMetadata = when (identity.source) {
                MusicSource.Kugou -> ProviderTrackMetadata.Kugou(hash = identity.value)
                MusicSource.Kuwo -> ProviderTrackMetadata.Kuwo(mid = identity.value.toLongOrNull() ?: 0L)
                else -> ProviderTrackMetadata.Empty
            },
        )
    }

    var page by remember(identity, visible) { mutableStateOf(ProviderSongActionPage.Main) }
    var favoriteKnownState by remember(identity) { mutableStateOf<Boolean?>(null) }
    var favoriteWorking by remember(identity) { mutableStateOf(false) }
    var writablePlaylists by remember(identity) { mutableStateOf<List<MusicPlaylistSummary>>(emptyList()) }
    var showCreatePlaylist by remember(identity, visible, page) { mutableStateOf(false) }
    var newPlaylistName by remember(identity, visible, page) { mutableStateOf("") }
    var playlistsLoading by remember(identity) { mutableStateOf(false) }
    var playlistWriteWorking by remember(identity) { mutableStateOf(false) }
    var actionStatus by remember(identity) { mutableStateOf<String?>(null) }
    var actionError by remember(identity) { mutableStateOf<String?>(null) }
    var recognitionWorking by remember(identity) { mutableStateOf(false) }
    val downloaded = downloadStore.isDownloaded(identity)
    val downloading = downloadStore.isDownloading(identity)
    val bilibiliOffsetState = if (identity.source == MusicSource.Bilibili) {
        BilibiliLyricOffsetStore.state(context, identity.value)
    } else null

    BackHandler(enabled = visible) {
        if (page == ProviderSongActionPage.Main) onDismiss() else page = ProviderSongActionPage.Main
    }

    if (visible) ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = MaterialTheme.colorScheme.surface,
        contentColor = MaterialTheme.colorScheme.onSurface,
        shape = RoundedCornerShape(topStart = 38.dp, topEnd = 38.dp),
        dragHandle = {
            Box(Modifier.fillMaxWidth().height(18.dp), contentAlignment = Alignment.Center) {
                Box(
                    Modifier.size(width = 58.dp, height = 4.dp)
                        .background(MaterialTheme.colorScheme.onSurface.copy(alpha = .24f), RoundedCornerShape(99.dp)),
                )
            }
        },
    ) {
        AnimatedContent(
                    targetState = page,
                    transitionSpec = {
                        meloXPanelEnter() togetherWith meloXPanelExit()
                    },
            modifier = Modifier.fillMaxWidth(),
            label = "provider-song-action-page",
        ) { target ->
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .verticalScroll(rememberScrollState())
                            .padding(horizontal = 18.dp, vertical = 18.dp),
                        verticalArrangement = Arrangement.spacedBy(2.dp),
                    ) {
                        ProviderActionHeader(
                            state = state,
                            subtitle = when (target) {
                                ProviderSongActionPage.Main -> stringResource(R.string.player_source_actions, identity.source.displayName)
                                ProviderSongActionPage.Sleep -> stringResource(R.string.player_sleep_timer)
                                ProviderSongActionPage.Playlists -> stringResource(R.string.player_choose_playlist)
                            },
                        )

                        MeloXIosGroupedList(surfaceColor = MaterialTheme.colorScheme.surfaceContainerHigh) {
                        when (target) {
                            ProviderSongActionPage.Main -> {
                                // ★ 平板双栏专属：⋯ 里补一条进「天际歌词」的通道（2026-10-07）。
                                //   放**第一条**（与 `MeloXSongActionsOverlay` 同位置）—— 它切的是
                                //   "整屏呈现方式"，与下面那一串歌曲操作不是一类。`onShowSkyline`
                                //   为 null（手机 / 竖屏）时整行不渲染，旧菜单逐像素不变。
                                onShowSkyline?.let { openSkyline ->
                                    ProviderActionItem(stringResource(R.string.player_skyline_lyrics), "☰") {
                                        openSkyline()
                                        onDismiss()
                                    }
                                }
                                if (identity.source == MusicSource.Local) {
                                    ProviderActionItem(
                                        title = if (recognitionWorking) stringResource(R.string.player_recognizing) else stringResource(R.string.player_recognize),
                                        symbol = "⌁",
                                        enabled = !recognitionWorking,
                                    ) {
                                        recognitionWorking = true
                                        actionStatus = null
                                        actionError = null
                                        scope.launch {
                                            runCatching {
                                                LocalRecognitionCoordinator(context).recognize(identity.value)
                                            }.onSuccess { outcome ->
                                                actionStatus = outcome.matched?.let {
                                                    context.getString(R.string.player_matched, it.name, it.artists)
                                                } ?: context.getString(R.string.player_no_match)
                                                onLocalMetadataChanged()
                                            }.onFailure {
                                                actionError = it.message ?: context.getString(R.string.player_recognize_failed)
                                            }
                                            recognitionWorking = false
                                        }
                                    }
                                }
                                if (favoriteCapability != null) {
                                    ProviderActionItem(
                                        title = when {
                                            !providerLoggedIn -> stringResource(R.string.player_login_for_favorites, identity.source.displayName)
                                            favoriteWorking -> stringResource(R.string.player_updating_favorites)
                                            favoriteKnownState == true -> stringResource(R.string.player_remove_favorite)
                                            else -> stringResource(R.string.player_add_favorite)
                                        },
                                        symbol = if (favoriteKnownState == true) "♥" else "♡",
                                        enabled = providerLoggedIn && !favoriteWorking,
                                    ) {
                                        val targetFavorite = favoriteKnownState != true
                                        favoriteWorking = true
                                        actionStatus = null
                                        actionError = null
                                        scope.launch {
                                            runCatching {
                                                favoriteCapability.setFavorite(actionTrack, targetFavorite)
                                            }.onSuccess {
                                                favoriteKnownState = targetFavorite
                                                actionStatus = if (targetFavorite) {
                                                    context.getString(R.string.player_added_favorite, identity.source.displayName)
                                                } else {
                                                    context.getString(R.string.player_removed_favorite, identity.source.displayName)
                                                }
                                            }.onFailure { failure ->
                                                actionError = failure.message ?: context.getString(R.string.player_favorite_failed)
                                            }
                                            favoriteWorking = false
                                        }
                                    }
                                }

                                if (downloadCapability != null) {
                                    ProviderActionItem(
                                        title = when {
                                            downloaded -> stringResource(R.string.player_downloaded_local)
                                            downloading -> stringResource(R.string.player_downloading)
                                            else -> stringResource(R.string.player_download_local)
                                        },
                                        symbol = if (downloaded) "✓" else "↓",
                                        enabled = !downloaded && !downloading,
                                    ) {
                                        downloadStore.start(actionTrack)
                                        actionStatus = context.getString(R.string.player_download_queued, identity.source.displayName)
                                    }
                                }

                                if (playlistWriteCapability != null) {
                                    ProviderActionItem(
                                        title = if (providerLoggedIn) stringResource(R.string.player_add_to_playlist) else stringResource(R.string.player_login_for_playlist, identity.source.displayName),
                                        symbol = "＋",
                                        enabled = providerLoggedIn && !playlistsLoading,
                                    ) {
                                        page = ProviderSongActionPage.Playlists
                                        playlistsLoading = true
                                        actionStatus = null
                                        actionError = null
                                        scope.launch {
                                            runCatching {
                                                withContext(Dispatchers.IO) {
                                                    playlistWriteCapability.writablePlaylists(page = 1, pageSize = 50).items
                                                }
                                            }.onSuccess { playlists ->
                                                writablePlaylists = playlists
                                                if (playlists.isEmpty()) actionError = context.getString(R.string.player_no_user_playlists)
                                            }.onFailure { failure ->
                                                writablePlaylists = emptyList()
                                                actionError = failure.message ?: context.getString(R.string.player_writable_playlists_failed)
                                            }
                                            playlistsLoading = false
                                        }
                                    }
                                }

                                ProviderActionItem(stringResource(R.string.player_sleep_timer), "◷") { page = ProviderSongActionPage.Sleep }
                                ProviderActionItem(stringResource(R.string.player_add_to_queue), "+") {
                                    state.addCurrentToQueue()
                                    onDismiss()
                                }
                                ProviderActionItem(stringResource(R.string.player_system_share), "↗") {
                                    shareProviderSong(context, state, identity)
                                    onDismiss()
                                }
                                if (identity.source != MusicSource.Bilibili && state.album.isNotBlank() && onNavigateSearch != null) {
                                    ProviderActionItem(stringResource(R.string.player_go_album, state.album), "▣") {
                                        val target = state.album
                                        MeloXSearchLaunchBus.post(target, MeloXSearchKind.Albums)
                                        onDismiss()
                                        onNavigateSearch(target, MeloXSearchKind.Albums)
                                    }
                                }
                                if (identity.source != MusicSource.Bilibili && state.artist.isNotBlank() && onNavigateSearch != null) {
                                    ProviderActionItem(stringResource(R.string.player_go_artist, state.artist), "♬") {
                                        val target = state.artist.substringBefore(" /")
                                        MeloXSearchLaunchBus.post(target, MeloXSearchKind.Artists)
                                        onDismiss()
                                        onNavigateSearch(target, MeloXSearchKind.Artists)
                                    }
                                }

                            }

                            ProviderSongActionPage.Sleep -> {
                                listOf(15, 30, 45, 60).forEach { minutes ->
                                    ProviderActionItem(stringResource(R.string.player_sleep_minutes, minutes), "◷") {
                                        state.setSleepTimer(minutes)
                                        onDismiss()
                                    }
                                }
                                if (state.sleepTimerEndRealtimeMs > 0L) {
                                    ProviderActionItem(stringResource(R.string.player_cancel_sleep), "×") {
                                        state.cancelSleepTimer()
                                        onDismiss()
                                    }
                                }
                                ProviderActionItem(stringResource(R.string.player_back), "‹") { page = ProviderSongActionPage.Main }
                            }

                            ProviderSongActionPage.Playlists -> {
                                if (playlistSyncCapability != null && providerLoggedIn) {
                                    ProviderActionItem(
                                        title = stringResource(R.string.player_new_playlist),
                                        symbol = "＋",
                                        enabled = !playlistWriteWorking,
                                    ) {
                                        newPlaylistName = ""
                                        showCreatePlaylist = true
                                    }
                                }
                                when {
                                    playlistsLoading -> ProviderActionItem(stringResource(R.string.player_loading_writable), "…", enabled = false) {}
                                    writablePlaylists.isEmpty() -> ProviderActionItem(stringResource(R.string.player_no_writable), "—", enabled = false) {}
                                    else -> writablePlaylists.forEach { playlist ->
                                        ProviderActionItem(
                                            title = playlist.title,
                                            symbol = "▣",
                                            enabled = !playlistWriteWorking,
                                        ) {
                                            val capability = playlistWriteCapability ?: return@ProviderActionItem
                                            playlistWriteWorking = true
                                            actionError = null
                                            actionStatus = null
                                            scope.launch {
                                                runCatching {
                                                    capability.addTrackToPlaylist(actionTrack, playlist)
                                                }.onSuccess {
                                                    actionStatus = context.getString(R.string.player_added_to_playlist, playlist.title)
                                                    page = ProviderSongActionPage.Main
                                                }.onFailure { failure ->
                                                    actionError = failure.message ?: context.getString(R.string.player_add_playlist_failed)
                                                }
                                                playlistWriteWorking = false
                                            }
                                        }
                                    }
                                }
                                ProviderActionItem(stringResource(R.string.player_back), "‹") { page = ProviderSongActionPage.Main }
                            }
                        }
                        }
                        if (target == ProviderSongActionPage.Main && identity.source == MusicSource.Bilibili) {
                            val persistedOffset = bilibiliOffsetState?.value ?: 0
                            var displayedOffset by remember(identity.value, persistedOffset) {
                                mutableIntStateOf(persistedOffset)
                            }
                            Column(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 12.dp)) {
                                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                    Text(stringResource(R.string.player_lyric_offset), fontWeight = FontWeight.SemiBold)
                                    Text(
                                        when {
                                            displayedOffset == 0 -> stringResource(R.string.player_lyric_sync)
                                            displayedOffset > 0 -> stringResource(R.string.player_lyric_ahead, displayedOffset)
                                            else -> stringResource(R.string.player_lyric_behind, displayedOffset)
                                        },
                                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = .62f),
                                        fontSize = 12.sp,
                                    )
                                }
                                MeloXLiquidSlider(
                                    value = displayedOffset.toFloat(),
                                    onTransientValueChange = { raw ->
                                        displayedOffset = raw.toInt()
                                    },
                                    onValueChange = { quantized ->
                                        displayedOffset = quantized.toInt()
                                        BilibiliLyricOffsetStore.write(context, identity.value, quantized.toInt())
                                    },
                                    valueRange = -5_000f..5_000f,
                                    stepSize = 100f,
                                    visibilityThreshold = 1f,
                                    contentDescription = stringResource(R.string.player_lyric_offset),
                                )
                            }
                        }
                        ProviderActionStatus(actionStatus, actionError)
                        if (showCreatePlaylist) {
                            AlertDialog(
                                onDismissRequest = { if (!playlistWriteWorking) showCreatePlaylist = false },
                                title = { Text(stringResource(R.string.player_new_playlist)) },
                                text = {
                                    OutlinedTextField(
                                        value = newPlaylistName,
                                        onValueChange = { newPlaylistName = it },
                                        singleLine = true,
                                        label = { Text(stringResource(R.string.player_playlist_name)) },
                                    )
                                },
                                confirmButton = {
                                    TextButton(
                                        enabled = !playlistWriteWorking,
                                        onClick = {
                                            if (playlistWriteWorking) return@TextButton
                                            val sync = playlistSyncCapability ?: return@TextButton
                                            val write = playlistWriteCapability ?: return@TextButton
                                            val name = newPlaylistName.trim().ifBlank {
                                                context.getString(R.string.player_new_playlist)
                                            }
                                            playlistWriteWorking = true
                                            actionError = null
                                            actionStatus = null
                                            scope.launch {
                                                runCatching {
                                                    withContext(Dispatchers.IO) {
                                                        val created = sync.createPlaylist(name)
                                                        write.addTrackToPlaylist(actionTrack, created)
                                                        created
                                                    }
                                                }.onSuccess { created ->
                                                    showCreatePlaylist = false
                                                    actionStatus = context.getString(R.string.player_added_to_playlist, created.title)
                                                    page = ProviderSongActionPage.Main
                                                }.onFailure { failure ->
                                                    actionError = failure.message ?: context.getString(R.string.player_playlist_create_failed)
                                                }
                                                playlistWriteWorking = false
                                            }
                                        },
                                    ) {
                                        Text(stringResource(R.string.player_create_and_add))
                                    }
                                },
                                dismissButton = {
                                    TextButton(
                                        enabled = !playlistWriteWorking,
                                        onClick = { showCreatePlaylist = false },
                                    ) {
                                        Text(stringResource(android.R.string.cancel))
                                    }
                                },
                            )
                        }
        }
    }
}

}

@Composable
private fun ProviderActionStatus(
    status: String?,
    error: String?,
) {
    status?.let { message ->
        Text(
            message,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.66f),
            fontSize = 12.sp,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
        )
    }
    error?.let { message ->
        Text(
            message,
            color = Color(0xFFFF8A80),
            fontSize = 12.sp,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
        )
    }
}

@Composable
private fun ProviderActionHeader(
    state: MeloXPlaybackUiState,
    subtitle: String,
) {
    val foreground = MaterialTheme.colorScheme.onSurface
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AsyncImage(
            model = state.artworkUrl,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.size(52.dp),
        )
        Column(Modifier.weight(1f).padding(start = 12.dp)) {
            Text(
                state.title.ifBlank { stringResource(R.string.player_now_playing) },
                color = foreground,
                fontSize = 17.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                state.artist.ifBlank { subtitle },
                color = foreground.copy(alpha = 0.58f),
                fontSize = 12.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                subtitle,
                color = foreground.copy(alpha = 0.38f),
                fontSize = 10.sp,
                maxLines = 1,
            )
        }
    }
}

@Composable
private fun ProviderActionItem(
    title: String,
    symbol: String,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    val foreground = MaterialTheme.colorScheme.onSurface
    MeloXIosListRow(
        title = title,
        leading = {
            MeloXActionIcon(
                token = symbol,
                color = foreground.copy(alpha = if (enabled) .82f else .31f),
                enabled = enabled,
                modifier = Modifier.size(22.dp),
            )
        },
        onClick = if (enabled) onClick else null,
        showTopSeparator = true,
    )
}

private fun shareProviderSong(
    context: Context,
    state: MeloXPlaybackUiState,
    identity: MusicResourceId,
) {
    val providerUrl = when (identity.source) {
        MusicSource.QQMusic -> "https://y.qq.com/n/ryqq/songDetail/${identity.value}"
        MusicSource.Kugou,
        MusicSource.Kuwo,
        MusicSource.Netease -> null
        MusicSource.AppleMusic -> "https://music.apple.com/song/${identity.value}"
        MusicSource.Bilibili -> identity.value.substringBefore(':').takeIf(String::isNotBlank)?.let {
            "https://www.bilibili.com/video/$it"
        }
        MusicSource.Spotify -> "https://open.spotify.com/track/${identity.value}"
        MusicSource.YouTubeMusic -> "https://music.youtube.com/watch?v=${identity.value}"
        MusicSource.Jellyfin -> null
        MusicSource.Local -> null
    }
    val text = buildString {
        append(state.title.ifBlank { context.getString(R.string.player_now_playing) })
        if (state.artist.isNotBlank()) append(" · ").append(state.artist)
        providerUrl?.let { append('\n').append(it) }
    }
    val intent = Intent.createChooser(
        Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, text)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        },
        context.getString(R.string.player_share_song),
    ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    context.startActivity(intent)
}
