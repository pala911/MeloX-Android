package com.lladlam.melox.ui.library

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionLayout
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import com.lladlam.melox.ui.animation.MeloXMotion
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed as gridItemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.BlurredEdgeTreatment
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import com.lladlam.melox.R
import com.lladlam.melox.core.account.NeteaseSessionStore
import com.lladlam.melox.core.audio.MusicQualityPreferences
import com.lladlam.melox.core.download.MeloXDownloadStore
import com.lladlam.melox.core.download.MeloXDownloadPlaylistRef
import com.lladlam.melox.core.download.MeloXProviderDownloadStore
import com.lladlam.melox.core.library.NeteaseLibraryClient
import com.lladlam.melox.core.library.NeteaseLibraryCache
import com.lladlam.melox.core.library.NeteaseLibrarySnapshot
import com.lladlam.melox.core.library.NeteasePlaylistDetail
import com.lladlam.melox.core.library.NeteasePlaylistSummary
import com.lladlam.melox.core.recommendation.LocalRecommendationStore
import com.lladlam.melox.playback.ProviderPlaybackCommands
import com.lladlam.melox.core.model.SearchSong
import com.lladlam.melox.core.music.model.MusicAccountSummary
import com.lladlam.melox.core.music.model.MusicAlbumSummary
import com.lladlam.melox.core.music.model.MusicArtistSummary
import com.lladlam.melox.core.music.model.MusicSource
import com.lladlam.melox.core.music.model.MusicTrack
import com.lladlam.melox.core.music.provider.MeloXLegacyUiBridge
import com.lladlam.melox.core.music.provider.MeloXMusicProviders
import com.lladlam.melox.core.music.provider.loadAllPlaylistTracks
import com.lladlam.melox.core.music.provider.AlbumCapability
import com.lladlam.melox.core.music.provider.ArtistCapability
import com.lladlam.melox.core.music.provider.LibraryCollectionCapability
import com.lladlam.melox.core.music.provider.MusicProvider
import com.lladlam.melox.core.music.provider.PlaylistCapability
import com.lladlam.melox.core.music.provider.PlaylistSyncCapability
import com.lladlam.melox.playback.MeloXPlaybackService
import com.lladlam.melox.core.music.provider.UserLibraryCapability
import com.lladlam.melox.core.music.provider.LocalAggregationCapability
import com.lladlam.melox.core.network.NeteaseMusicOperationsClient
import com.lladlam.melox.core.network.NeteaseCollectionDetailsClient
import com.lladlam.melox.core.network.NeteaseSearchClient
import com.lladlam.melox.playback.PlaybackCommands
import com.lladlam.melox.ui.MeloXBottomContentClearance
import com.lladlam.melox.ui.glass.meloXLiquidBottomBar
import com.lladlam.melox.ui.glass.MeloXActionIcon
import com.lladlam.melox.ui.glass.MeloXSwipeAction
import com.lladlam.melox.ui.glass.MeloXSwipeActionRow
import com.lladlam.melox.ui.glass.MeloXSymbol
import com.lladlam.melox.ui.glass.MeloXShapes
import com.lladlam.melox.ui.glass.MeloXTypography
import com.lladlam.melox.ui.glass.meloXContentSurface
import com.lladlam.melox.ui.glass.MeloXIosTopBar
import com.lladlam.melox.ui.glass.MeloXGlassButton
import com.lladlam.melox.ui.glass.MeloXGlassButtonStyle
import com.lladlam.melox.ui.glass.meloXLiquidButton
import com.lladlam.melox.ui.glass.meloXLiquidTabSelection
import com.lladlam.melox.ui.player.MeloXFlowingLightBackdrop
import com.lladlam.melox.ui.player.MeloXSongActionsOverlay
import com.lladlam.melox.ui.sharing.MeloXNeteaseResourceShareActivity
import com.lladlam.melox.ui.settings.MeloXSettingsRuntime
import com.lladlam.melox.ui.settings.MeloXSwipeFullAction
import com.lladlam.melox.ui.layout.rememberMeloXWindowInfo
import com.lladlam.melox.ui.settings.MeloXSettingsPreferences
import com.lladlam.melox.ui.theme.isMeloXDarkTheme
import com.lladlam.melox.ui.podcast.MeloXPodcastScreen
import com.lladlam.melox.ui.cloud.MeloXCloudMusicScreen
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

private enum class MeloXLibraryPage(@androidx.annotation.StringRes val titleRes: Int) {
    Songs(R.string.library_page_songs),
    Playlists(R.string.library_page_playlists),
    Albums(R.string.library_browse_albums),
    Artists(R.string.library_browse_artists),
    Podcasts(R.string.library_page_podcasts),
    Cloud(R.string.library_page_cloud),
    History(R.string.library_page_history),
    Downloads(R.string.library_page_downloads),
}

/**
 * Presentation capability gate only. A page is present when the active source
 * implements the corresponding capability, not because of a source name.
 */
private fun MeloXLibraryPage.isEnabled(source: MusicSource, provider: MusicProvider?): Boolean = when {
    source == MusicSource.Bilibili -> this == MeloXLibraryPage.Playlists || this == MeloXLibraryPage.Downloads
    source == MusicSource.Local -> this == MeloXLibraryPage.Songs
    source != MusicSource.Netease -> when (this) {
        MeloXLibraryPage.Playlists -> true
        MeloXLibraryPage.Albums, MeloXLibraryPage.Artists -> provider is LibraryCollectionCapability
        else -> false
    }
    this == MeloXLibraryPage.Albums || this == MeloXLibraryPage.Artists -> false
    this == MeloXLibraryPage.Podcasts -> MeloXSettingsRuntime.podcastsEnabled && MeloXSettingsRuntime.podcastsLibraryPlacement
    this == MeloXLibraryPage.History -> MeloXSettingsRuntime.listeningHistoryEnabled
    this == MeloXLibraryPage.Cloud -> MeloXSettingsRuntime.cloudMusicEnabled && MeloXSettingsRuntime.cloudLibraryPlacement
    this == MeloXLibraryPage.Downloads -> MeloXSettingsRuntime.downloadsEnabled && MeloXSettingsRuntime.downloadsLibraryPlacement
    else -> true
}

@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
fun LibraryScreen(
    session: NeteaseSessionStore,
    onLogin: (() -> Unit)?,
    source: MusicSource = MusicSource.Netease,
    forcedPageName: String? = null,
    playlistBackEnabled: Boolean = true,
    onModalVisibilityChanged: (Boolean) -> Unit = {},
) {
    val context = LocalContext.current
    val window = rememberMeloXWindowInfo()
    val appContext = context.applicationContext
    val scope = rememberCoroutineScope()
    val client = remember(appContext) {
        NeteaseLibraryClient(
            cookieProvider = { NeteaseSessionStore.readCookie(appContext) },
        )
    }
    val provider = remember(source, appContext) {
        if (source == MusicSource.Netease) null else MeloXMusicProviders.create(appContext).require(source)
    }
    val providerLibrary = provider as? UserLibraryCapability
    val cache = remember(appContext) { NeteaseLibraryCache(appContext) }
    val downloadStore = remember(appContext) { MeloXDownloadStore.get(appContext) }
    val libraryPagePreferenceKey = if (source == MusicSource.Netease) {
        "library_last_page"
    } else {
        "library_last_page_${source.storageValue}"
    }

    val initialLibraryPage = remember(source, forcedPageName) {
        val fallback = if (source == MusicSource.Netease || source == MusicSource.Local) MeloXLibraryPage.Songs else MeloXLibraryPage.Playlists
        val name = forcedPageName ?: if (MeloXSettingsRuntime.rememberLibraryPage) {
            MeloXSettingsPreferences.string(appContext, libraryPagePreferenceKey, fallback.name)
        } else fallback.name
        runCatching { MeloXLibraryPage.valueOf(name) }
            .getOrDefault(fallback)
            .takeIf { forcedPageName != null || it.isEnabled(source, provider) }
            ?: fallback
    }
    var selectedPage by remember(source, forcedPageName) { mutableStateOf(initialLibraryPage) }
    var selectedPlaylist by remember(source, session.cookie) { mutableStateOf<NeteasePlaylistSummary?>(null) }
    var savedAlbums by remember(source) { mutableStateOf<List<MusicAlbumSummary>>(emptyList()) }
    var followedArtists by remember(source) { mutableStateOf<List<MusicArtistSummary>>(emptyList()) }
    var selectedAlbum by remember(source) { mutableStateOf<MusicAlbumSummary?>(null) }
    var selectedArtist by remember(source) { mutableStateOf<MusicArtistSummary?>(null) }
    var showLocalRecommendations by remember(source) { mutableStateOf(false) }
    var snapshot by remember(source, session.cookie) { mutableStateOf<NeteaseLibrarySnapshot?>(null) }
    var providerAccount by remember(source) { mutableStateOf<MusicAccountSummary?>(null) }
    var loading by remember(source, session.cookie) { mutableStateOf(source != MusicSource.Netease) }
    var errorMessage by remember(source, session.cookie) { mutableStateOf<String?>(null) }
    val playlistListState = rememberLazyListState()

    suspend fun refreshLibrary() {
        loading = true
        errorMessage = null
        if (source == MusicSource.Netease) {
            if (!session.isLoggedIn) {
                loading = false
                return
            }
            if (session.profile == null) session.refreshProfile(force = true)
            val userId = session.profile?.userId
            if (userId == null) {
                loading = false
                return
            }
            runCatching { client.snapshot(userId) }
                .onSuccess {
                    snapshot = it
                    cache.saveSnapshot(userId, it)
                }
                .onFailure { errorMessage = it.message ?: appContext.getString(R.string.library_load_failed) }
        } else {
            if (source == MusicSource.Local) {
                runCatching {
                    withContext(Dispatchers.IO) {
                        val tracks = (provider as? LocalAggregationCapability)
                            ?.aggregationTracks(page = 1, pageSize = 200)
                            ?.items
                            ?: error(appContext.getString(R.string.library_local_unavailable))
                        providerAccount = MusicAccountSummary(
                            source = MusicSource.Local,
                            id = "local",
                            displayName = appContext.getString(R.string.library_local_name),
                            subtitle = appContext.getString(R.string.library_local_subtitle),
                        )
                        snapshot = NeteaseLibrarySnapshot(
                            likedSongs = tracks.map(MeloXLegacyUiBridge::track),
                            playlists = emptyList(),
                            recentSongs = emptyList(),
                            likedPlaylistId = null,
                        )
                    }
                }.onFailure {
                    providerAccount = null
                    snapshot = null
                    errorMessage = it.message ?: appContext.getString(R.string.library_local_failed)
                }
                loading = false
                return
            }
            val capability = providerLibrary
            if (capability == null) {
                providerAccount = null
                snapshot = null
                errorMessage = appContext.getString(R.string.library_capability_missing, source.displayName)
                loading = false
                return
            }
            runCatching {
                withContext(Dispatchers.IO) {
                    val account = capability.accountSummary()
                    val playlists = if (account != null && provider is PlaylistCapability) {
                        capability.userPlaylists(page = 1, pageSize = 100).items
                    } else {
                        emptyList()
                    }
                    account to playlists
                }
            }.onSuccess { (account, playlists) ->
                providerAccount = account
                snapshot = if (account == null) null else MeloXLegacyUiBridge.library(playlists)
                val collections = provider as? LibraryCollectionCapability
                if (account != null && collections != null) {
                    runCatching {
                        withContext(Dispatchers.IO) {
                            collections.savedAlbums(page = 1, pageSize = 100).items to
                                collections.followedArtists(page = 1, pageSize = 100).items
                        }
                    }.onSuccess { (albums, artists) ->
                        savedAlbums = albums
                        followedArtists = artists
                    }
                } else {
                    savedAlbums = emptyList()
                    followedArtists = emptyList()
                }
            }.onFailure { failure ->
                providerAccount = null
                snapshot = null
                errorMessage = failure.message ?: appContext.getString(R.string.library_source_failed, source.displayName)
            }
        }
        loading = false
    }

    LaunchedEffect(source, session.cookie, session.profile?.userId) {
        if (source == MusicSource.Netease) {
            val userId = session.profile?.userId ?: return@LaunchedEffect
            cache.loadSnapshot(userId)?.let { snapshot = it }
            if (NeteaseLibraryCache.beginLibraryColdStartRefresh(userId)) {
                refreshLibrary()
            }
        } else {
            refreshLibrary()
        }
    }

    LaunchedEffect(source, selectedPage) {
        if (forcedPageName == null && MeloXSettingsRuntime.rememberLibraryPage) {
            MeloXSettingsPreferences.setString(appContext, libraryPagePreferenceKey, selectedPage.name)
        }
    }

    LaunchedEffect(
        source,
        MeloXSettingsRuntime.podcastsEnabled,
        MeloXSettingsRuntime.listeningHistoryEnabled,
        MeloXSettingsRuntime.cloudMusicEnabled,
        MeloXSettingsRuntime.downloadsEnabled,
    ) {
        if (forcedPageName == null && !selectedPage.isEnabled(source, provider)) {
            selectedPage = if (source == MusicSource.Netease || source == MusicSource.Local) MeloXLibraryPage.Songs else MeloXLibraryPage.Playlists
        }
    }

    BackHandler(enabled = playlistBackEnabled && selectedPlaylist != null) {
        selectedPlaylist = null
    }
    BackHandler(enabled = selectedAlbum != null) { selectedAlbum = null }
    BackHandler(enabled = selectedArtist != null) { selectedArtist = null }

    if (source == MusicSource.Netease && !session.isLoggedIn) {
        MeloXLibraryLoginUnavailable(onLogin, source)
        return
    }
    if (source != MusicSource.Netease && source != MusicSource.Local && !loading && providerAccount == null && errorMessage == null) {
        MeloXLibraryLoginUnavailable(onLogin, source)
        return
    }
    val openAlbum = selectedAlbum
    if (openAlbum != null) {
        MeloXUnifiedProviderAlbumDetailScreen(album = openAlbum, onBack = { selectedAlbum = null })
        return
    }
    val openArtist = selectedArtist
    if (openArtist != null) {
        MeloXProviderArtistDetailScreen(artist = openArtist, onBack = { selectedArtist = null })
        return
    }

    PullToRefreshBox(
        isRefreshing = loading && snapshot != null,
        onRefresh = { scope.launch { refreshLibrary() } },
        modifier = Modifier.fillMaxSize(),
    ) {
      SharedTransitionLayout(modifier = Modifier.fillMaxSize()) {
          val sharedScope = this

        AnimatedContent(
            targetState = selectedPlaylist,
            modifier = Modifier.fillMaxSize(),
            transitionSpec = {
                val openingDetail = targetState != null
                (
                    fadeIn(
                        animationSpec = tween(
                            durationMillis = MeloXMotion.ContentEnterMillis,
                            delayMillis = 0,
                            easing = FastOutSlowInEasing,
                        ),
                    ) togetherWith fadeOut(
                        animationSpec = tween(
                            durationMillis = MeloXMotion.ContentExitMillis,
                            easing = FastOutSlowInEasing,
                        ),
                    )
                ).apply {
                    // The newest target must stay above any retained outgoing
                    // playlist content while an interrupted reverse animation finishes.
                    targetContentZIndex = if (openingDetail) 2f else 0f
                }
            },
            contentKey = { playlist -> playlist?.id ?: Long.MIN_VALUE },
            label = "library-playlist-detail-transition",
        ) { targetPlaylist ->
            val playlistTransitionVisibilityScope = this
            if (targetPlaylist != null) {
                MeloXPlaylistDetailScreen(
                    initialPlaylist = targetPlaylist,
                    client = client,
                    onBack = { selectedPlaylist = null },
                    sharedTransitionScope = sharedScope,
                    animatedVisibilityScope = playlistTransitionVisibilityScope,
                    onModalVisibilityChanged = onModalVisibilityChanged,
                    onSongLikeChanged = { song, liked ->
                        val current = snapshot ?: return@MeloXPlaylistDetailScreen
                        val updated = current.withSongLiked(song, liked)
                        snapshot = updated
                        session.profile?.userId?.let { userId ->
                            scope.launch { cache.saveSnapshot(userId, updated) }
                        }
                    },
                )
            } else {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(MaterialTheme.colorScheme.background)
                        .statusBarsPadding()
                        .padding(horizontal = if (window.supportsTwoPane) window.gutter else 0.dp),
                ) {
                    MeloXIosTopBar(
                        title = stringResource(R.string.tab_library),
                    )

                    MeloXLibrarySegmentedPicker(
                        selected = selectedPage,
                        onSelected = { selectedPage = it },
                        source = source,
                        forcedPageName = forcedPageName,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                    )

                    if (errorMessage != null && snapshot == null) {
                        Box(
                            modifier = Modifier.fillMaxSize(),
                            contentAlignment = Alignment.Center,
                        ) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text(
                                    text = errorMessage.orEmpty(),
                                    color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.55f),
                                    textAlign = TextAlign.Center,
                                )
                                Text(
                                    text = stringResource(R.string.library_reload),
                                    modifier = Modifier
                                        .padding(top = 12.dp)
                                        .clip(RoundedCornerShape(18.dp))
                                        .clickable { scope.launch { refreshLibrary() } }
                                        .padding(horizontal = 16.dp, vertical = 8.dp),
                                    color = MaterialTheme.colorScheme.primary,
                                    fontWeight = FontWeight.SemiBold,
                                )
                            }
                        }
                    } else if (loading && snapshot == null) {
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator()
                        }
                    } else {
                        val data = snapshot ?: NeteaseLibrarySnapshot(emptyList(), emptyList(), emptyList())
                        when (selectedPage) {
                            MeloXLibraryPage.Songs -> MeloXLibrarySongsPage(
                                songs = data.likedSongs,
                                onPlay = { song ->
                                    PlaybackCommands.playQueue(
                                        context = context,
                                        songs = data.likedSongs,
                                        selectedSongId = song.id,
                                        onFailure = { errorMessage = it.message ?: context.getString(R.string.library_play_failed) },
                                    )
                                },
                                onPlayAll = {
                                    data.likedSongs.firstOrNull()?.let { first ->
                                        PlaybackCommands.playQueue(
                                            context = context,
                                            songs = data.likedSongs,
                                            selectedSongId = first.id,
                                            onFailure = { errorMessage = it.message ?: context.getString(R.string.library_play_failed) },
                                        )
                                    }
                                },
                                onHeartMode = if (source == MusicSource.Netease) {
                                    {
                                        val seed = data.likedSongs.randomOrNull()
                                        val playlistId = data.likedPlaylistId
                                        if (seed != null && playlistId != null) scope.launch {
                                            runCatching { client.intelligenceModeSongs(seed.id, playlistId) }
                                                .onSuccess { songs -> songs.firstOrNull()?.let { PlaybackCommands.playQueue(context, songs, it.id, heartMode = true) } }
                                                .onFailure { errorMessage = it.message ?: context.getString(R.string.library_heart_failed) }
                                        }
                                    }
                                } else null,
                            )

                            MeloXLibraryPage.Playlists -> MeloXLibraryPlaylistsPage(
                                playlists = data.playlists,
                                localRecommendations = LocalRecommendationStore.readRecommendedTracks(context),
                                onLocalRecommendationsClick = { showLocalRecommendations = true },
                                onPlaylistClick = { selectedPlaylist = it },
                                listState = playlistListState,
                                sharedTransitionScope = sharedScope,
                                animatedVisibilityScope = playlistTransitionVisibilityScope,
                            )

                            MeloXLibraryPage.Podcasts -> MeloXPodcastScreen(subscriptionsOnly = true)

                            MeloXLibraryPage.Cloud -> Box(
                                modifier = Modifier.weight(1f).fillMaxWidth(),
                            ) {
                                MeloXCloudMusicScreen(embedded = true)
                            }

                            MeloXLibraryPage.History -> MeloXLibrarySongsPage(
                                songs = data.recentSongs,
                                onPlay = { song ->
                                    PlaybackCommands.playQueue(
                                        context = context, songs = data.recentSongs, selectedSongId = song.id,
                                        onFailure = { errorMessage = it.message ?: context.getString(R.string.library_play_failed) },
                                    )
                                },
                                onPlayAll = {
                                    data.recentSongs.firstOrNull()?.let { first ->
                                        PlaybackCommands.playQueue(
                                            context = context, songs = data.recentSongs, selectedSongId = first.id,
                                            onFailure = { errorMessage = it.message ?: context.getString(R.string.library_play_failed) },
                                        )
                                    }
                                },
                            )

                            MeloXLibraryPage.Downloads -> MeloXLibraryDownloadsPage(downloadStore)

                            MeloXLibraryPage.Albums -> MeloXLibraryCoverListPage(
                                rows = savedAlbums.map { album ->
                                    MeloXLibraryCoverRow(
                                        key = album.id.value,
                                        title = album.title,
                                        subtitle = album.artists.joinToString(" / ") { it.name }
                                            .ifBlank { album.id.source.displayName },
                                        artworkUrl = album.artworkUrl,
                                    )
                                },
                                emptyRes = R.string.library_no_albums,
                                onClick = { index -> selectedAlbum = savedAlbums.getOrNull(index) },
                            )

                            MeloXLibraryPage.Artists -> MeloXLibraryCoverListPage(
                                rows = followedArtists.map { artist ->
                                    MeloXLibraryCoverRow(
                                        key = artist.id.value,
                                        title = artist.name,
                                        subtitle = artist.description?.takeIf { it.isNotBlank() }
                                            ?: artist.id.source.displayName,
                                        artworkUrl = artist.artworkUrl,
                                    )
                                },
                                emptyRes = R.string.library_no_artists,
                                onClick = { index -> selectedArtist = followedArtists.getOrNull(index) },
                            )
                        }
                    }
                }
            }
        }
      }
    }
    if (showLocalRecommendations) {
        LocalRecommendationPlaylistScreen(
            onBack = { showLocalRecommendations = false },
        )
    }
}

private enum class MeloXDownloadsPage { Root, Active, Playlists, PlaylistDetail }
private enum class MeloXLocalBrowseMode(@androidx.annotation.StringRes val titleRes: Int) {
    Songs(R.string.library_page_songs),
    Artists(R.string.library_browse_artists),
    Albums(R.string.library_browse_albums),
    Folders(R.string.library_browse_folders),
}

@Composable
private fun MeloXLibraryDownloadsPage(downloads: MeloXDownloadStore) {
    val context = LocalContext.current
    val providerDownloads = remember(context) { MeloXProviderDownloadStore.get(context) }
    var page by remember { mutableStateOf(MeloXDownloadsPage.Root) }
    var selectedPlaylistId by remember { mutableStateOf<Long?>(null) }
    var selecting by remember { mutableStateOf(false) }
    var selectedIds by remember { mutableStateOf<Set<Long>>(emptySet()) }
    var browseMode by remember { mutableStateOf(MeloXLocalBrowseMode.Songs) }
    var browseGroup by remember { mutableStateOf<String?>(null) }
    var exportMessage by remember { mutableStateOf<String?>(null) }

    val active = downloads.activeDownloads.values.toList()
    val completed = downloads.downloads.toList()
    val providerCompleted = providerDownloads.downloads.toList()
    val groups = downloads.downloadedPlaylists
    val browseGroups = remember(completed, browseMode) {
        when (browseMode) {
            MeloXLocalBrowseMode.Songs -> emptyMap()
            MeloXLocalBrowseMode.Artists -> completed.groupBy { it.song.artists.ifBlank { context.getString(R.string.library_unknown_artist) } }
            MeloXLocalBrowseMode.Albums -> completed.groupBy { it.song.album.ifBlank { context.getString(R.string.library_unknown_album) } }
            MeloXLocalBrowseMode.Folders -> mapOf("Music/YSYY" to completed)
        }.toSortedMap()
    }
    val visibleCompleted = remember(completed, browseMode, browseGroup, browseGroups) {
        if (browseMode == MeloXLocalBrowseMode.Songs) completed
        else browseGroup?.let { browseGroups[it].orEmpty() }.orEmpty()
    }

    fun exportSelected() {
        if (selectedIds.isEmpty()) return
        if (
            Build.VERSION.SDK_INT <= Build.VERSION_CODES.P &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED
        ) {
            (context as? Activity)?.let {
                ActivityCompat.requestPermissions(it, arrayOf(Manifest.permission.WRITE_EXTERNAL_STORAGE), 4104)
            }
            exportMessage = context.getString(R.string.library_export_permission)
            return
        }
        downloads.exportToMusicLibrary(selectedIds) { result ->
            exportMessage = result.fold(
                onSuccess = { context.getString(R.string.library_exported, it) },
                onFailure = { it.message ?: context.getString(R.string.library_export_failed) },
            )
        }
    }

    BackHandler(enabled = page != MeloXDownloadsPage.Root) {
        page = if (page == MeloXDownloadsPage.PlaylistDetail) MeloXDownloadsPage.Playlists else MeloXDownloadsPage.Root
        if (page != MeloXDownloadsPage.PlaylistDetail) selectedPlaylistId = null
    }

    when (page) {
        MeloXDownloadsPage.Root -> LazyColumn(
  modifier = Modifier.fillMaxSize(),
  contentPadding = PaddingValues(start = 14.dp, end = 14.dp, bottom = 146.dp),
  verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
  if (active.isNotEmpty()) {
      item {
          DownloadNavigationCard(
              title = stringResource(R.string.library_downloading),
              subtitle = stringResource(R.string.library_download_remaining, formatDownloadSpeed(downloads.aggregateDownloadBytesPerSecond), active.size),
              onClick = { page = MeloXDownloadsPage.Active },
          )
      }
  }
  if (completed.isNotEmpty()) {
      item {
          Row(
              Modifier.fillMaxWidth().padding(top = 14.dp, bottom = 8.dp),
              horizontalArrangement = Arrangement.SpaceBetween,
              verticalAlignment = Alignment.CenterVertically,
          ) {
              Text(stringResource(R.string.library_downloaded), fontSize = 20.sp, fontWeight = FontWeight.Bold)
              Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                  if (selecting) {
                      Text(
                          if (selectedIds.size == completed.size) stringResource(R.string.library_deselect_all) else stringResource(R.string.library_select_all),
                          color = MaterialTheme.colorScheme.primary,
                          fontWeight = FontWeight.SemiBold,
                          modifier = Modifier.clickable {
                              selectedIds = if (selectedIds.size == completed.size) emptySet()
                                  else completed.map { it.song.id }.toSet()
                          },
                      )
                      Text(
                          stringResource(R.string.action_cancel),
                          color = MaterialTheme.colorScheme.primary,
                          fontWeight = FontWeight.SemiBold,
                          modifier = Modifier.clickable {
                              selecting = false
                              selectedIds = emptySet()
                          },
                      )
                  } else {
                      Text(
                          stringResource(R.string.library_select),
                          color = MaterialTheme.colorScheme.primary,
                          fontWeight = FontWeight.SemiBold,
                          modifier = Modifier.clickable { selecting = true },
                      )
                      Text(
                          stringResource(R.string.library_play_all),
                          color = MaterialTheme.colorScheme.primary,
                          fontWeight = FontWeight.SemiBold,
                          modifier = Modifier.clickable {
                              downloads.downloadedSongs.firstOrNull()?.let {
                                  PlaybackCommands.playQueue(context, downloads.downloadedSongs, it.id)
                              }
                          },
                      )
                  }
              }
          }
      }
      item {
          Row(
              Modifier.fillMaxWidth().padding(bottom = 8.dp),
              horizontalArrangement = Arrangement.spacedBy(8.dp),
          ) {
              MeloXLocalBrowseMode.entries.forEach { mode ->
                  Text(
                      stringResource(mode.titleRes),
                      color = if (browseMode == mode) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onBackground.copy(alpha = .55f),
                      fontWeight = if (browseMode == mode) FontWeight.Bold else FontWeight.Medium,
                      modifier = Modifier
                          .clip(RoundedCornerShape(14.dp))
                          .background(MaterialTheme.colorScheme.onBackground.copy(alpha = if (browseMode == mode) .10f else .04f))
                          .clickable {
                              browseMode = mode
                              browseGroup = null
                              selectedIds = emptySet()
                          }
                          .padding(horizontal = 12.dp, vertical = 7.dp),
                  )
              }
          }
      }
      exportMessage?.let { value ->
          item { Text(value, color = MaterialTheme.colorScheme.primary, fontSize = 12.sp, modifier = Modifier.padding(bottom = 8.dp)) }
      }
  if (groups.isNotEmpty()) {
      item {
          DownloadNavigationCard(
              title = stringResource(R.string.library_downloaded_playlists),
              subtitle = stringResource(R.string.library_playlist_groups, groups.size),
              onClick = { page = MeloXDownloadsPage.Playlists },
          )
      }
  }
      if (browseMode != MeloXLocalBrowseMode.Songs && browseGroup == null) {
          items(browseGroups.entries.toList(), key = { "browse-${browseMode.name}-${it.key}" }) { group ->
              DownloadNavigationCard(
                  title = group.key,
                  subtitle = stringResource(R.string.library_song_count, group.value.size),
                  onClick = { browseGroup = group.key },
              )
          }
      } else if (browseMode != MeloXLocalBrowseMode.Songs) {
          item { DownloadsSubpageHeader(browseGroup.orEmpty()) { browseGroup = null } }
      }
      items(visibleCompleted, key = { "download-${it.song.id}" }) { item ->
          val checked = item.song.id in selectedIds
          Row(
              Modifier
                  .fillMaxWidth()
                  .height(62.dp)
                  .clickable {
                      if (selecting) {
                          selectedIds = if (checked) selectedIds - item.song.id else selectedIds + item.song.id
                      } else {
                          PlaybackCommands.playQueue(context, downloads.downloadedSongs, item.song.id)
                      }
                  },
              verticalAlignment = Alignment.CenterVertically,
          ) {
              AsyncImage(
                  model = downloads.localArtworkUri(item.song.id) ?: item.song.artworkUrl,
                  contentDescription = null,
                  contentScale = ContentScale.Crop,
                  modifier = Modifier.size(48.dp).clip(RoundedCornerShape(9.dp)),
              )
              Column(Modifier.weight(1f).padding(start = 12.dp)) {
                  Text(item.song.name, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.SemiBold)
                  Text(
                      "${item.song.artists} · ${item.quality.title}",
                      maxLines = 1,
                      overflow = TextOverflow.Ellipsis,
                      color = MaterialTheme.colorScheme.onBackground.copy(alpha = .48f),
                      fontSize = 12.sp,
                  )
              }
              if (selecting) {
                  Text(if (checked) "✓" else "○", color = MaterialTheme.colorScheme.primary, fontSize = 20.sp, modifier = Modifier.padding(10.dp))
              } else {
                  Text(stringResource(R.string.provider_delete), color = MaterialTheme.colorScheme.error, modifier = Modifier.clickable { downloads.remove(item.song.id) }.padding(10.dp))
              }
          }
      }
      if (selecting) {
          item {
              val canDelete = selectedIds.isNotEmpty()
              Row(Modifier.fillMaxWidth().padding(top = 16.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                  Box(
                      Modifier.weight(1f).height(48.dp).clip(RoundedCornerShape(18.dp))
                          .background(MaterialTheme.colorScheme.primary.copy(alpha = if (canDelete) .14f else .05f))
                          .clickable(enabled = canDelete) { exportSelected() },
                      contentAlignment = Alignment.Center,
                  ) {
                      Text(stringResource(R.string.library_export_selected), color = MaterialTheme.colorScheme.primary.copy(alpha = if (canDelete) 1f else .4f), fontWeight = FontWeight.SemiBold)
                  }
                  Box(
                      Modifier.weight(1f).height(48.dp).clip(RoundedCornerShape(18.dp))
                          .background(MaterialTheme.colorScheme.error.copy(alpha = if (canDelete) .14f else .05f))
                          .clickable(enabled = canDelete) {
                              downloads.removeMany(selectedIds)
                              selectedIds = emptySet()
                              selecting = false
                          },
                      contentAlignment = Alignment.Center,
                  ) {
                      Text(
                          if (canDelete) stringResource(R.string.library_delete_count, selectedIds.size) else stringResource(R.string.library_select_songs),
                          color = MaterialTheme.colorScheme.error.copy(alpha = if (canDelete) 1f else .4f),
                          fontWeight = FontWeight.SemiBold,
                      )
                  }
              }
          }
      }
  }

  if (providerCompleted.isNotEmpty()) {
      item {
          Text("Spotify / YouTube Music", fontSize = 20.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 18.dp, bottom = 8.dp))
      }
      items(providerCompleted, key = { "provider-download-${it.track.id.source.storageValue}:${it.track.id.value}" }) { item ->
          Row(
              Modifier.fillMaxWidth().height(62.dp).clickable {
                  ProviderPlaybackCommands.playQueue(context, providerCompleted.map { it.track }, item.track.id)
              },
              verticalAlignment = Alignment.CenterVertically,
          ) {
              AsyncImage(
                  model = item.track.artworkUrl,
                  contentDescription = null,
                  contentScale = ContentScale.Crop,
                  modifier = Modifier.size(48.dp).clip(RoundedCornerShape(9.dp)),
              )
              Column(Modifier.weight(1f).padding(start = 12.dp)) {
                  Text(item.track.title, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.SemiBold)
                  Text(
                      "${item.track.artistText} · ${item.track.id.source.displayName}",
                      maxLines = 1,
                      overflow = TextOverflow.Ellipsis,
                      color = MaterialTheme.colorScheme.onBackground.copy(alpha = .48f),
                      fontSize = 12.sp,
                  )
              }
              Text(stringResource(R.string.provider_delete), color = MaterialTheme.colorScheme.error, modifier = Modifier.clickable { providerDownloads.remove(item.track.id) }.padding(10.dp))
          }
      }
  }

  if (active.isEmpty() && completed.isEmpty() && providerCompleted.isEmpty()) {
      item {
          Box(Modifier.fillMaxWidth().height(260.dp), contentAlignment = Alignment.Center) {
              Column(horizontalAlignment = Alignment.CenterHorizontally) {
                  Text(stringResource(R.string.library_none_downloaded), fontSize = 19.sp, fontWeight = FontWeight.SemiBold)
                  Text(
                      stringResource(R.string.library_download_hint),
                      modifier = Modifier.padding(top = 7.dp),
                      color = MaterialTheme.colorScheme.onBackground.copy(alpha = .48f),
                      fontSize = 13.sp,
                  )
              }
          }
      }
  }
        }

        MeloXDownloadsPage.Active -> LazyColumn(
  modifier = Modifier.fillMaxSize(),
  contentPadding = PaddingValues(start = 14.dp, end = 14.dp, bottom = 146.dp),
  verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
  item { DownloadsSubpageHeader(stringResource(R.string.library_downloading)) { page = MeloXDownloadsPage.Root } }
  item {
      Text(
          stringResource(R.string.library_download_remaining, formatDownloadSpeed(downloads.aggregateDownloadBytesPerSecond), active.size),
          color = MaterialTheme.colorScheme.onBackground.copy(alpha = .52f),
          fontSize = 13.sp,
          modifier = Modifier.padding(bottom = 10.dp),
      )
  }
  items(active, key = { "active-${it.song.id}" }) { item ->
      Row(Modifier.fillMaxWidth().height(66.dp), verticalAlignment = Alignment.CenterVertically) {
          AsyncImage(
              model = item.song.artworkUrl,
              contentDescription = null,
              contentScale = ContentScale.Crop,
              modifier = Modifier.size(48.dp).clip(RoundedCornerShape(9.dp)),
          )
          Column(Modifier.weight(1f).padding(start = 12.dp)) {
              Text(item.song.name, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.SemiBold)
              val progress = item.fractionCompleted?.let { "${(it * 100).toInt()}%" } ?: stringResource(R.string.library_preparing)
              Text(
                  "$progress · ${formatDownloadSpeed(item.bytesPerSecond)} · ${item.quality.title}",
                  color = MaterialTheme.colorScheme.onBackground.copy(alpha = .48f),
                  fontSize = 12.sp,
              )
          }
          Text(stringResource(R.string.action_cancel), color = MaterialTheme.colorScheme.error, modifier = Modifier.clickable { downloads.cancel(item.song.id) }.padding(10.dp))
      }
  }
  if (active.isEmpty()) {
      item { Text(stringResource(R.string.library_none_active), color = MaterialTheme.colorScheme.onBackground.copy(alpha = .5f), modifier = Modifier.padding(top = 24.dp)) }
  }
        }

        MeloXDownloadsPage.Playlists -> LazyColumn(
  modifier = Modifier.fillMaxSize(),
  contentPadding = PaddingValues(start = 14.dp, end = 14.dp, bottom = 146.dp),
  verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
  item { DownloadsSubpageHeader(stringResource(R.string.library_downloaded_playlists)) { page = MeloXDownloadsPage.Root } }
  items(groups, key = { "download-playlist-${it.playlist.id}" }) { group ->
      Row(
          Modifier.fillMaxWidth().height(68.dp).clickable {
              selectedPlaylistId = group.playlist.id
              page = MeloXDownloadsPage.PlaylistDetail
          },
          verticalAlignment = Alignment.CenterVertically,
      ) {
          AsyncImage(
              model = downloads.localPlaylistArtworkUri(group.playlist.id) ?: group.playlist.artworkUrl,
              contentDescription = null,
              contentScale = ContentScale.Crop,
              modifier = Modifier.size(52.dp).clip(RoundedCornerShape(10.dp)),
          )
          Column(Modifier.weight(1f).padding(start = 12.dp)) {
              Text(group.playlist.name, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.SemiBold)
              Text(stringResource(R.string.library_downloaded_count, group.songs.size), color = MaterialTheme.colorScheme.onBackground.copy(alpha = .48f), fontSize = 12.sp)
          }
          MeloXActionIcon("›", Modifier.size(18.dp), MaterialTheme.colorScheme.onBackground.copy(alpha = .4f))
      }
  }
        }

        MeloXDownloadsPage.PlaylistDetail -> {
  val group = groups.firstOrNull { it.playlist.id == selectedPlaylistId }
  val songs = group?.songs?.map { it.song }.orEmpty()
  LazyColumn(
      modifier = Modifier.fillMaxSize(),
      contentPadding = PaddingValues(start = 14.dp, end = 14.dp, bottom = 146.dp),
      verticalArrangement = Arrangement.spacedBy(4.dp),
  ) {
      item { DownloadsSubpageHeader(group?.playlist?.name ?: stringResource(R.string.library_downloaded_playlists)) { page = MeloXDownloadsPage.Playlists } }
      group?.let { existing ->
          item {
              Row(Modifier.fillMaxWidth().padding(bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                  AsyncImage(
                      model = downloads.localPlaylistArtworkUri(existing.playlist.id) ?: existing.playlist.artworkUrl,
                      contentDescription = null,
                      contentScale = ContentScale.Crop,
                      modifier = Modifier.size(72.dp).clip(RoundedCornerShape(12.dp)),
                  )
                  Column(Modifier.weight(1f).padding(start = 12.dp)) {
                      Text(existing.playlist.name, fontSize = 18.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                      Text(stringResource(R.string.library_downloaded_songs, songs.size), color = MaterialTheme.colorScheme.onBackground.copy(alpha = .5f), fontSize = 12.sp)
                  }
                  Text(
                      stringResource(R.string.library_play_all),
                      color = MaterialTheme.colorScheme.primary,
                      fontWeight = FontWeight.SemiBold,
                      modifier = Modifier.clickable {
                          songs.firstOrNull()?.let { PlaybackCommands.playQueue(context, songs, it.id) }
                      }.padding(8.dp),
                  )
              }
          }
      }
      items(group?.songs.orEmpty(), key = { "playlist-song-${it.song.id}" }) { item ->
          Row(
              Modifier.fillMaxWidth().height(62.dp).clickable {
                  PlaybackCommands.playQueue(context, songs, item.song.id)
              },
              verticalAlignment = Alignment.CenterVertically,
          ) {
              AsyncImage(
                  model = downloads.localArtworkUri(item.song.id) ?: item.song.artworkUrl,
                  contentDescription = null,
                  contentScale = ContentScale.Crop,
                  modifier = Modifier.size(48.dp).clip(RoundedCornerShape(9.dp)),
              )
              Column(Modifier.weight(1f).padding(start = 12.dp)) {
                  Text(item.song.name, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.SemiBold)
                  Text(item.song.artists, maxLines = 1, overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onBackground.copy(alpha = .48f), fontSize = 12.sp)
              }
          }
      }
  }
        }
    }
}

@Composable
private fun DownloadNavigationCard(title: String, subtitle: String, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 4.dp).height(66.dp)
  .meloXContentSurface(
      shape = MeloXShapes.card,
      surfaceColor = MaterialTheme.colorScheme.onBackground.copy(alpha = .055f),
  )
  .clickable(onClick = onClick)
  .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
  Text(title, fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
  Text(subtitle, color = MaterialTheme.colorScheme.onBackground.copy(alpha = .48f), fontSize = 12.sp, modifier = Modifier.padding(top = 3.dp))
        }
        MeloXActionIcon("›", Modifier.size(18.dp), MaterialTheme.colorScheme.onBackground.copy(alpha = .42f))
    }
}

@Composable
private fun DownloadsSubpageHeader(title: String, onBack: () -> Unit) {
    Row(Modifier.fillMaxWidth().height(58.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(34.dp).clickable(onClick = onBack), contentAlignment = Alignment.Center) {
            MeloXActionIcon("‹", Modifier.size(20.dp), MaterialTheme.colorScheme.onBackground)
        }
        Text(title, style = MeloXTypography.title2)
    }
}

private fun formatDownloadSpeed(bytesPerSecond: Long): String = when {
    bytesPerSecond >= 1024L * 1024L -> "%.1f MB/s".format(bytesPerSecond / (1024.0 * 1024.0))
    bytesPerSecond >= 1024L -> "%.0f KB/s".format(bytesPerSecond / 1024.0)
    bytesPerSecond > 0L -> "$bytesPerSecond B/s"
    else -> "0 KB/s"
}

@Composable
private fun MeloXLibraryLoginUnavailable(
    onLogin: (() -> Unit)?,
    source: MusicSource,
    forcedPageName: String? = null,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .statusBarsPadding()
            .padding(horizontal = 20.dp),
    ) {
        MeloXIosTopBar(
            title = stringResource(R.string.tab_library),
            contentPadding = PaddingValues(horizontal = 0.dp),
        )
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
            contentAlignment = Alignment.Center,
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(stringResource(R.string.library_login_required), fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
                Text(
                    if (source == MusicSource.Netease) {
                        stringResource(R.string.library_login_netease_body)
                    } else {
                        stringResource(R.string.library_login_source_body, source.displayName)
                    },
                    modifier = Modifier.padding(top = 8.dp),
                    color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.50f),
                    textAlign = TextAlign.Center,
                )
                if (onLogin != null) {
                    MeloXGlassButton(
                        onClick = onLogin,
                        modifier = Modifier.padding(top = 18.dp),
                        style = MeloXGlassButtonStyle.BorderedProminent,
                        shape = RoundedCornerShape(18.dp),
                        contentPadding = PaddingValues(horizontal = 18.dp, vertical = 10.dp),
                    ) {
                        Text(
                            if (source == MusicSource.Netease) stringResource(R.string.app_login_netease) else stringResource(R.string.library_login_source, source.displayName),
                            color = Color.White,
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun MeloXLibrarySegmentedPicker(
    selected: MeloXLibraryPage,
    onSelected: (MeloXLibraryPage) -> Unit,
    source: MusicSource,
    forcedPageName: String? = null,
    modifier: Modifier = Modifier,
) {
    val pickerContext = LocalContext.current.applicationContext
    val pageProvider = remember(source, pickerContext) {
        if (source == MusicSource.Netease) {
            null
        } else {
            runCatching { MeloXMusicProviders.create(pickerContext).require(source) }.getOrNull()
        }
    }
    val pages = MeloXLibraryPage.entries.filter { it.isEnabled(source, pageProvider) || it.name == forcedPageName }
    val panelShape = MeloXShapes.compact
    val lensShape = RoundedCornerShape(15.dp)
    val panelBackdrop = rememberLayerBackdrop()
    val dark = isMeloXDarkTheme()
    val selectedIndex = pages.indexOf(selected).coerceAtLeast(0)
    val lensPosition by animateFloatAsState(
        targetValue = selectedIndex.toFloat(),
        animationSpec = spring(
            dampingRatio = 1f,
            stiffness = 460f,
            visibilityThreshold = 0.001f,
        ),
        label = "library-segment-lens-position",
    )
    val panelTint = MaterialTheme.colorScheme.surface.copy(alpha = 0.10f)
    val panelSurface = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.055f)
    val selectionTint = if (dark) {
        Color.White.copy(alpha = 0.22f)
    } else {
        Color.White.copy(alpha = 0.72f)
    }

    BoxWithConstraints(
        modifier = modifier
            .fillMaxWidth()
            .height(30.dp)
            .meloXLiquidBottomBar(
                shape = panelShape,
                tint = panelTint,
                surfaceColor = panelSurface,
            ),
    ) {
        val panelWidthPx = constraints.maxWidth

        Row(
            modifier = Modifier
                .fillMaxSize()
                .alpha(0f)
                .layerBackdrop(panelBackdrop)
                .meloXLiquidBottomBar(
                    shape = panelShape,
                    tint = panelTint,
                    surfaceColor = panelSurface,
                ),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            pages.forEach { page ->
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight(),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(stringResource(page.titleRes), fontSize = 13.sp)
                }
            }
        }

        Box(
            modifier = Modifier
                .fillMaxWidth(1f / pages.size)
                .fillMaxHeight()
                .offset {
                    IntOffset(
                        x = (lensPosition * panelWidthPx / pages.size).roundToInt(),
                        y = 0,
                    )
                }
                .padding(horizontal = 1.dp, vertical = 1.dp)
                .meloXLiquidTabSelection(
                    shape = lensShape,
                    selected = true,
                    tint = selectionTint,
                    panelBackdrop = panelBackdrop,
                ),
        )

        Row(
            modifier = Modifier.fillMaxSize(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            pages.forEach { page ->
                val isSelected = page == selected
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                        ) { onSelected(page) },
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = stringResource(page.titleRes),
                        fontSize = 13.sp,
                        fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Medium,
                        color = MaterialTheme.colorScheme.onBackground,
                    )
                }
            }
        }
    }
}

@Composable
private fun MeloXLibrarySongsPage(
    songs: List<SearchSong>,
    onPlay: (SearchSong) -> Unit,
    onPlayAll: () -> Unit,
    onHeartMode: (() -> Unit)? = null,
) {
    if (songs.isEmpty()) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(
                stringResource(R.string.library_no_songs),
                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.48f),
                fontSize = 17.sp,
            )
        }
        return
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = MeloXBottomContentClearance),
    ) {
        item {
            MeloXPlayAllRow(onPlayAll)
            onHeartMode?.let { action ->
                Row(
                    Modifier.fillMaxWidth().height(52.dp).clickable(onClick = action).padding(start = 20.dp, end = 18.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    MeloXActionIcon("♥", Modifier.size(22.dp), Color(0xFFFF3B30))
                    Text(stringResource(R.string.home_action_heart), fontSize = 17.sp, color = MaterialTheme.colorScheme.onBackground)
                }
            }
            MeloXInsetDivider(leading = 68.dp)
        }
        items(songs, key = { it.id }) { song ->
            MeloXLibraryTrackRow(song = song, onClick = { onPlay(song) })
            MeloXInsetDivider(leading = 68.dp)
        }
    }
}

@Composable
private fun MeloXPlayAllRow(onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(58.dp)
            .clickable(onClick = onClick)
            .padding(start = 20.dp, end = 18.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        MeloXPlayGlyph(
            modifier = Modifier.size(28.dp),
            color = Color(0xFFFF3147),
        )
        Text(
            text = stringResource(R.string.library_play_all),
            fontSize = 17.sp,
            fontWeight = FontWeight.Normal,
            color = MaterialTheme.colorScheme.onBackground,
        )
    }
}

@Composable
private fun MeloXLibraryTrackRow(
    song: SearchSong,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(66.dp)
            .clickable(onClick = onClick)
            .padding(start = 18.dp, end = 18.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        AsyncImage(
            model = song.artworkUrl,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .size(44.dp)
                .clip(RoundedCornerShape(6.dp)),
        )
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(3.dp),
        ) {
            Text(
                text = song.name,
                fontSize = 17.sp,
                lineHeight = 21.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                color = MaterialTheme.colorScheme.onBackground,
            )
            Text(
                text = song.artists,
                fontSize = 13.sp,
                lineHeight = 16.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.46f),
            )
        }
        if (song.durationMs > 0L) {
            Text(
                text = formatDuration(song.durationMs),
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.46f),
            )
        }
    }
}

private data class MeloXLibraryCoverRow(
    val key: String,
    val title: String,
    val subtitle: String,
    val artworkUrl: String?,
)

@Composable
private fun MeloXLibraryCoverListPage(
    rows: List<MeloXLibraryCoverRow>,
    @androidx.annotation.StringRes emptyRes: Int,
    onClick: (Int) -> Unit,
) {
    if (rows.isEmpty()) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(
                stringResource(emptyRes),
                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.48f),
                fontSize = 17.sp,
            )
        }
        return
    }
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = MeloXBottomContentClearance),
    ) {
        items(rows.size, key = { index -> "$index:${rows[index].key}" }) { index ->
            val row = rows[index]
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(72.dp)
                    .clickable { onClick(index) }
                    .padding(horizontal = 18.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                AsyncImage(
                    model = row.artworkUrl,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .size(52.dp)
                        .clip(RoundedCornerShape(8.dp)),
                )
                Column(Modifier.padding(start = 14.dp).weight(1f)) {
                    Text(
                        row.title,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        fontSize = 16.sp,
                        color = MaterialTheme.colorScheme.onBackground,
                    )
                    if (row.subtitle.isNotBlank()) {
                        Text(
                            row.subtitle,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            fontSize = 13.sp,
                            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.46f),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun MeloXProviderArtistDetailScreen(
    artist: MusicArtistSummary,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val capability = remember(artist.id.source) {
        MeloXMusicProviders.create(context).require(artist.id.source) as? ArtistCapability
    }
    // Keep the provider tracks as MusicTrack. Converting them to the legacy Netease
    // SearchSong and playing through PlaybackCommands resolved provider (e.g. QQ)
    // artist songs against the wrong source, so playback failed intermittently.
    var tracks by remember(artist.id) { mutableStateOf<List<MusicTrack>>(emptyList()) }
    var loading by remember(artist.id) { mutableStateOf(true) }
    var errorMessage by remember(artist.id) { mutableStateOf<String?>(null) }
    LaunchedEffect(artist.id) {
        val reader = capability
        if (reader == null) {
            loading = false
            errorMessage = context.getString(R.string.library_capability_missing, artist.id.source.displayName)
            return@LaunchedEffect
        }
        runCatching {
            withContext(Dispatchers.IO) {
                reader.artistDetail(artist, page = 1, pageSize = 100).tracks
            }
        }.onSuccess {
            tracks = it
            errorMessage = null
        }.onFailure { failure ->
            errorMessage = failure.message
        }
        loading = false
    }
    Column(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .statusBarsPadding(),
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .height(52.dp)
                .padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.size(44.dp).clickable(onClick = onBack), contentAlignment = Alignment.Center) {
                Text("‹", fontSize = 28.sp, color = MaterialTheme.colorScheme.onBackground)
            }
            Text(
                artist.name,
                modifier = Modifier.weight(1f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                fontSize = 17.sp,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onBackground,
            )
        }
        when {
            loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
            errorMessage != null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(errorMessage.orEmpty(), color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.62f))
            }
            tracks.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    stringResource(R.string.library_no_songs),
                    color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.48f),
                )
            }
            else -> LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(bottom = MeloXBottomContentClearance),
            ) {
                items(tracks.size, key = { index -> "$index:${tracks[index].id.value}" }) { index ->
                    val track = tracks[index]
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(56.dp)
                            .clickable {
                                ProviderPlaybackCommands.playQueue(
                                    context = context,
                                    tracks = tracks,
                                    selectedTrackId = track.id,
                                    onFailure = { failure -> errorMessage = failure.message },
                                )
                            }
                            .padding(horizontal = 18.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                track.title,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                fontSize = 16.sp,
                                color = MaterialTheme.colorScheme.onBackground,
                            )
                            Text(
                                track.artistText,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                fontSize = 13.sp,
                                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.46f),
                            )
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
private fun MeloXLibraryPlaylistsPage(
    playlists: List<NeteasePlaylistSummary>,
    localRecommendations: List<com.lladlam.melox.core.music.model.MusicTrack> = emptyList(),
    onLocalRecommendationsClick: () -> Unit = {},
    onPlaylistClick: (NeteasePlaylistSummary) -> Unit,
    listState: LazyListState,
    sharedTransitionScope: SharedTransitionScope,
    animatedVisibilityScope: AnimatedVisibilityScope,
) {
    if (playlists.isEmpty()) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(
                stringResource(R.string.library_no_playlists),
                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.48f),
                fontSize = 17.sp,
            )
        }
        return
    }

    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = MeloXBottomContentClearance),
    ) {
        if (localRecommendations.isNotEmpty()) {
            item {
                Row(
                    modifier = Modifier.fillMaxWidth().height(66.dp).clickable(onClick = onLocalRecommendationsClick).padding(horizontal = 18.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    AsyncImage(localRecommendations.firstOrNull()?.artworkUrl, null, contentScale = ContentScale.Crop, modifier = Modifier.size(44.dp).clip(RoundedCornerShape(6.dp)))
                    Column(Modifier.weight(1f)) {
                        Text(stringResource(R.string.home_for_you_melox), fontSize = 17.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(stringResource(R.string.library_local_meta, localRecommendations.size), fontSize = 12.sp, color = MaterialTheme.colorScheme.onBackground.copy(alpha = .48f))
                    }
                    MeloXActionIcon("›", Modifier.size(18.dp), MaterialTheme.colorScheme.onBackground.copy(alpha = .4f))
                }
                MeloXInsetDivider(leading = 74.dp)
            }
        }
        item {
            Text(
                text = stringResource(R.string.library_page_playlists),
                modifier = Modifier.padding(start = 20.dp, top = 8.dp, bottom = 6.dp),
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.50f),
            )
        }
        items(playlists, key = { it.id }) { playlist ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(66.dp)
                    .clickable { onPlaylistClick(playlist) }
                    .padding(start = 18.dp, end = 18.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                val sharedArtworkModifier = with(sharedTransitionScope) {
                    Modifier.sharedElement(
                        sharedContentState = rememberSharedContentState(
                            key = playlistArtworkSharedKey(playlist.id),
                        ),
                        animatedVisibilityScope = animatedVisibilityScope,
                        // The cover itself must stay in the shared overlay for
                        // the row -> detail flight. The detail screen clips
                        // its settled cover; only the transition uses this
                        // elevated layer.
                        renderInOverlayDuringTransition = true,
                        zIndexInOverlay = 1f,
                    )
                }

                AsyncImage(
                    model = playlist.coverUrl,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = sharedArtworkModifier
                        .size(44.dp)
                        .clip(RoundedCornerShape(6.dp)),
                )
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(3.dp),
                ) {
                    Text(
                        playlist.name,
                        fontSize = 17.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        stringResource(R.string.library_song_count, playlist.trackCount),
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.48f),
                    )
                }
                MeloXActionIcon(
                    "›",
                    Modifier.size(18.dp),
                    MaterialTheme.colorScheme.onBackground.copy(alpha = 0.40f),
                )
            }
            MeloXInsetDivider(leading = 84.dp)
        }
    }
}

@Composable
private fun LocalRecommendationPlaylistScreen(onBack: () -> Unit) {
    val context = LocalContext.current.applicationContext
    val tracks = LocalRecommendationStore.readRecommendedTracks(context)
    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).statusBarsPadding()) {
        MeloXPlaylistToolbar(
            foreground = MaterialTheme.colorScheme.onBackground,
            onBack = onBack,
            onShare = {},
            showMore = false,
            onMore = {},
        )
        Column(Modifier.padding(horizontal = 20.dp, vertical = 10.dp)) {
            Text(stringResource(R.string.home_for_you_melox), fontSize = 25.sp, fontWeight = FontWeight.Bold)
            Text(stringResource(R.string.library_local_detail, tracks.size), modifier = Modifier.padding(top = 5.dp), fontSize = 13.sp, color = MaterialTheme.colorScheme.onBackground.copy(alpha = .54f))
        }
        if (tracks.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(stringResource(R.string.library_no_similar), textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onBackground.copy(alpha = .55f))
            }
            return
        }
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = MeloXBottomContentClearance)) {
            itemsIndexed(tracks, key = { _, track -> "local-recommendation-${track.id.source.storageValue}-${track.id.value}" }) { index, track ->
                Row(Modifier.fillMaxWidth().clickable { ProviderPlaybackCommands.playQueue(context, tracks, track.id) }.padding(horizontal = 20.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("${index + 1}", Modifier.width(38.dp), color = MaterialTheme.colorScheme.onBackground.copy(alpha = .45f), textAlign = TextAlign.Center)
                    AsyncImage(track.artworkUrl, null, contentScale = ContentScale.Crop, modifier = Modifier.size(48.dp).clip(RoundedCornerShape(8.dp)))
                    Column(Modifier.weight(1f).padding(start = 12.dp)) {
                        Text(track.title, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.SemiBold)
                        Text("${track.artistText} · ${track.id.source.displayName}", maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 12.sp, color = MaterialTheme.colorScheme.onBackground.copy(alpha = .5f))
                    }
                }
            }
        }
    }
}

@Composable
private fun MeloXInsetDivider(leading: androidx.compose.ui.unit.Dp) {
    HorizontalDivider(
        modifier = Modifier.padding(start = leading, end = 18.dp),
        thickness = 0.6.dp,
        color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.12f),
    )
}

/** Canonical playlist detail used by Library, Home, Explore, Search and account entry points. */
@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
internal fun MeloXUnifiedSongListDetailScreen(
    playlist: NeteasePlaylistSummary,
    songs: List<SearchSong>,
    onBack: () -> Unit,
    sharedTransitionScope: SharedTransitionScope,
    animatedVisibilityScope: AnimatedVisibilityScope,
    artworkSharedKey: String? = null,
) {
    val context = LocalContext.current.applicationContext
    val client = remember(context) {
        NeteaseLibraryClient(cookieProvider = { NeteaseSessionStore.readCookie(context) })
    }
    MeloXPlaylistDetailScreen(
        initialPlaylist = playlist,
        client = client,
        onBack = onBack,
        sharedTransitionScope = sharedTransitionScope,
        animatedVisibilityScope = animatedVisibilityScope,
        onModalVisibilityChanged = {},
        providedSongs = songs,
        artworkSharedKey = artworkSharedKey,
    )
}

@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
internal fun MeloXUnifiedPlaylistDetailScreen(
    playlist: NeteasePlaylistSummary,
    onBack: () -> Unit,
    onModalVisibilityChanged: (Boolean) -> Unit = {},
    // 入口页（首页/发现页/搜索）若要把列表卡片封面一镜到底地接到详情 hero，
    // 就把自己顶层 SharedTransitionLayout 的作用域与配对 key 传进来；
    // 不传则保持「自建作用域 + 淡入」的原有行为。
    sharedTransitionScope: SharedTransitionScope? = null,
    animatedVisibilityScope: AnimatedVisibilityScope? = null,
    artworkSharedKey: String? = null,
) {
    val context = LocalContext.current.applicationContext
    val client = remember(context) {
        NeteaseLibraryClient(cookieProvider = { NeteaseSessionStore.readCookie(context) })
    }
    if (sharedTransitionScope != null && animatedVisibilityScope != null) {
        MeloXPlaylistDetailScreen(
            initialPlaylist = playlist,
            client = client,
            onBack = onBack,
            sharedTransitionScope = sharedTransitionScope,
            animatedVisibilityScope = animatedVisibilityScope,
            onModalVisibilityChanged = onModalVisibilityChanged,
            artworkSharedKey = artworkSharedKey,
        )
    } else {
        SharedTransitionLayout(Modifier.fillMaxSize()) {
            AnimatedVisibility(visible = true) {
                MeloXPlaylistDetailScreen(
                    initialPlaylist = playlist,
                    client = client,
                    onBack = onBack,
                    sharedTransitionScope = this@SharedTransitionLayout,
                    animatedVisibilityScope = this,
                    onModalVisibilityChanged = onModalVisibilityChanged,
                    artworkSharedKey = artworkSharedKey,
                )
            }
        }
    }
}

@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
internal fun MeloXUnifiedAlbumDetailScreen(
    albumId: Long,
    onBack: () -> Unit,
    onModalVisibilityChanged: (Boolean) -> Unit = {},
) {
    val placeholder = remember(albumId) {
        NeteasePlaylistSummary(
            id = albumId,
            name = "",
            coverUrl = null,
            trackCount = 0,
            creatorName = "",
        )
    }
    val context = LocalContext.current.applicationContext
    val client = remember(context) {
        NeteaseLibraryClient(cookieProvider = { NeteaseSessionStore.readCookie(context) })
    }
    SharedTransitionLayout(Modifier.fillMaxSize()) {
        AnimatedVisibility(visible = true) {
            MeloXPlaylistDetailScreen(
                initialPlaylist = placeholder,
                client = client,
                onBack = onBack,
                sharedTransitionScope = this@SharedTransitionLayout,
                animatedVisibilityScope = this,
                onModalVisibilityChanged = onModalVisibilityChanged,
                albumId = albumId,
            )
        }
    }
}

@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
internal fun MeloXUnifiedProviderAlbumDetailScreen(
    album: MusicAlbumSummary,
    onBack: () -> Unit,
    // 与 MeloXUnifiedPlaylistDetailScreen 同款：入口页（搜索页等）若要把结果卡片
    // 封面一镜到底接到详情 hero，就把自己顶层的 SharedTransitionLayout 作用域与
    // 配对 key 传进来；不传则维持「自建作用域 + 淡入」。
    sharedTransitionScope: SharedTransitionScope? = null,
    animatedVisibilityScope: AnimatedVisibilityScope? = null,
    artworkSharedKey: String? = null,
) {
    val legacyId = remember(album.id) {
        album.id.toString().hashCode().toLong().let { value -> if (value >= 0L) -value - 1L else value }
    }
    val placeholder = remember(album, legacyId) {
        NeteasePlaylistSummary(
            id = legacyId,
            name = album.title,
            coverUrl = album.artworkUrl,
            trackCount = album.trackCount?.toInt() ?: 0,
            creatorName = album.artists.joinToString(" / ") { it.name },
        )
    }
    val context = LocalContext.current.applicationContext
    val client = remember(context) {
        NeteaseLibraryClient(cookieProvider = { NeteaseSessionStore.readCookie(context) })
    }
    if (sharedTransitionScope != null && animatedVisibilityScope != null) {
        MeloXPlaylistDetailScreen(
            initialPlaylist = placeholder,
            client = client,
            onBack = onBack,
            sharedTransitionScope = sharedTransitionScope,
            animatedVisibilityScope = animatedVisibilityScope,
            onModalVisibilityChanged = {},
            providerAlbum = album,
            artworkSharedKey = artworkSharedKey,
        )
    } else {
        SharedTransitionLayout(Modifier.fillMaxSize()) {
            AnimatedVisibility(visible = true) {
                MeloXPlaylistDetailScreen(
                    initialPlaylist = placeholder,
                    client = client,
                    onBack = onBack,
                    sharedTransitionScope = this@SharedTransitionLayout,
                    animatedVisibilityScope = this,
                    onModalVisibilityChanged = {},
                    providerAlbum = album,
                    artworkSharedKey = artworkSharedKey,
                )
            }
        }
    }
}

@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
private fun MeloXPlaylistDetailScreen(
    initialPlaylist: NeteasePlaylistSummary,
    client: NeteaseLibraryClient,
    onBack: () -> Unit,
    sharedTransitionScope: SharedTransitionScope,
    animatedVisibilityScope: AnimatedVisibilityScope,
    onModalVisibilityChanged: (Boolean) -> Unit,
    onSongLikeChanged: (SearchSong, Boolean) -> Unit = { _, _ -> },
    albumId: Long? = null,
    providerAlbum: MusicAlbumSummary? = null,
    providedSongs: List<SearchSong>? = null,
    // 由入口页（首页/发现页/搜索）指定的共享元素 key，用于把列表卡片
    // 的封面一镜到底地 morph 到详情 hero。null 时退回本页默认 key。
    artworkSharedKey: String? = null,
) {
    val context = LocalContext.current
    val detailWindow = rememberMeloXWindowInfo()
    val appContext = context.applicationContext
    val scope = rememberCoroutineScope()
    val cache = remember(appContext) { NeteaseLibraryCache(appContext) }
    val accountClient = remember(appContext) {
        NeteaseSearchClient(cookieProvider = { NeteaseSessionStore.readCookie(appContext) })
    }
    val operationsClient = remember(appContext) {
        NeteaseMusicOperationsClient(cookieProvider = { NeteaseSessionStore.readCookie(appContext) })
    }
    val albumClient = remember(appContext) {
        NeteaseCollectionDetailsClient(cookieProvider = { NeteaseSessionStore.readCookie(appContext) })
    }
    val providerAlbumCapability = remember(providerAlbum?.id?.source, appContext) {
        providerAlbum?.let { backing ->
            MeloXMusicProviders.create(appContext).require(backing.id.source) as? AlbumCapability
        }
    }
    val providerPlaylist = initialPlaylist.providerPlaylist
    val providerSync = remember(providerPlaylist?.id?.source, appContext) {
        providerPlaylist?.let { backing ->
            MeloXMusicProviders.create(appContext).require(backing.id.source) as? PlaylistSyncCapability
        }
    }
    val providerPlaylistCapability = remember(providerPlaylist?.id?.source, appContext) {
        providerPlaylist?.let { backing ->
            MeloXMusicProviders.create(appContext).require(backing.id.source) as? PlaylistCapability
        }
    }
    val isProviderPlaylist = providerPlaylist != null
    val isAlbum = albumId != null || providerAlbum != null
    val isProviderCollection = isProviderPlaylist || providerAlbum != null
    var detail by remember(initialPlaylist.id) { mutableStateOf<NeteasePlaylistDetail?>(null) }
    var loading by remember(initialPlaylist.id) { mutableStateOf(true) }
    var errorMessage by remember(initialPlaylist.id) { mutableStateOf<String?>(null) }
    var searchQuery by remember(initialPlaylist.id) { mutableStateOf("") }
    var showPlaylistActions by remember(initialPlaylist.id) { mutableStateOf(false) }
    var showBatchDownload by remember(initialPlaylist.id) { mutableStateOf(false) }
    var selectedTrackAction by remember(initialPlaylist.id) { mutableStateOf<SearchSong?>(null) }
    var showProviderRename by remember(initialPlaylist.id) { mutableStateOf(false) }
    var providerSyncTrack by remember(initialPlaylist.id) { mutableStateOf<SearchSong?>(null) }
    var providerSyncBusy by remember(initialPlaylist.id) { mutableStateOf(false) }
    var providerSyncError by remember(initialPlaylist.id) { mutableStateOf<String?>(null) }
    var isSaved by remember(initialPlaylist.id) { mutableStateOf<Boolean?>(null) }
    var currentUserId by remember(initialPlaylist.id) { mutableStateOf<Long?>(null) }
    var savingPlaylist by remember(initialPlaylist.id) { mutableStateOf(false) }
    var palette by remember(initialPlaylist.coverUrl) { mutableStateOf(MeloXDetailPalette.LightFallback) }
    var sortMode by remember(initialPlaylist.id) { mutableStateOf(MeloXPlaylistSortMode.Original) }

    DisposableEffect(showPlaylistActions, showBatchDownload, selectedTrackAction, showProviderRename, providerSyncTrack) {
        val visible = showProviderRename || providerSyncTrack != null ||
            (!isProviderCollection && (showPlaylistActions || showBatchDownload || selectedTrackAction != null))
        onModalVisibilityChanged(visible)
        onDispose {
            if (visible) onModalVisibilityChanged(false)
        }
    }

    suspend fun refreshSavedState() {
        if (isAlbum) return
        if (isProviderPlaylist) {
            isSaved = null
            return
        }
        val cookie = NeteaseSessionStore.readCookie(appContext)
        if (!NeteaseSessionStore.containsMusicU(cookie)) {
            isSaved = null
            return
        }
        runCatching {
            val profile = accountClient.accountProfile(cookie)
            currentUserId = profile.userId
            withContext(Dispatchers.IO) {
                client.userPlaylistsBlocking(profile.userId)
            }.any { it.id == initialPlaylist.id }
        }.onSuccess { isSaved = it }
    }

    suspend fun refreshPlaylist() {
        loading = true
        errorMessage = null
        if (providedSongs != null) {
            detail = NeteasePlaylistDetail(summary = initialPlaylist, songs = providedSongs)
            isSaved = null
            loading = false
            return
        }
        if (providerAlbum != null) {
            val capability = providerAlbumCapability
            if (capability == null) {
                errorMessage = appContext.getString(R.string.library_album_unavailable, providerAlbum.id.source.displayName)
                loading = false
                return
            }
            runCatching {
                withContext(Dispatchers.IO) { capability.albumDetail(providerAlbum, page = 1, pageSize = 150) }
            }.onSuccess { album ->
                detail = NeteasePlaylistDetail(
                    summary = initialPlaylist.copy(
                        name = album.summary.title,
                        coverUrl = album.summary.artworkUrl,
                        trackCount = album.tracks.size,
                        creatorName = album.summary.artists.joinToString(" / ") { it.name },
                    ),
                    songs = album.tracks.map(MeloXLegacyUiBridge::track),
                )
                isSaved = null
            }.onFailure { errorMessage = it.message ?: appContext.getString(R.string.library_album_failed) }
        } else if (albumId != null) {
            runCatching { albumClient.albumDetail(albumId) }
                .onSuccess { album ->
                    detail = NeteasePlaylistDetail(
                        summary = NeteasePlaylistSummary(
                            id = album.album.id,
                            name = album.album.name,
                            coverUrl = album.album.artworkUrl,
                            trackCount = album.songs.size,
                            creatorName = album.album.artistText,
                            description = album.description,
                        ),
                        songs = album.songs,
                    )
                    isSaved = album.subscribed
                }
                .onFailure { errorMessage = it.message ?: appContext.getString(R.string.library_album_failed) }
        } else if (providerPlaylist != null) {
            val capability = providerPlaylistCapability
            if (capability == null) {
                errorMessage = appContext.getString(R.string.library_playlist_unavailable, providerPlaylist.id.source.displayName)
                loading = false
                return
            }
            runCatching {
                withContext(Dispatchers.IO) {
                    capability.loadAllPlaylistTracks(providerPlaylist, pageSize = 200)
                }
            }.onSuccess { providerDetail ->
                detail = MeloXLegacyUiBridge.playlistDetail(providerDetail)
            }.onFailure { errorMessage = it.message ?: appContext.getString(R.string.library_playlist_failed) }
        } else {
            runCatching { client.playlistDetail(initialPlaylist.id) }
                .onSuccess {
                    detail = it
                    cache.savePlaylistDetail(initialPlaylist.id, it)
                }
                .onFailure { errorMessage = it.message ?: appContext.getString(R.string.library_playlist_failed) }
        }
        loading = false
    }

    LaunchedEffect(initialPlaylist.id, providerPlaylist?.id) {
        if (isAlbum || providerPlaylist != null) {
            refreshPlaylist()
        } else {
            cache.loadPlaylistDetail(initialPlaylist.id)?.let { detail = it }
            loading = detail == null
            if (NeteaseLibraryCache.beginPlaylistColdStartRefresh(initialPlaylist.id)) {
                refreshPlaylist()
            }
        }
    }

    LaunchedEffect(initialPlaylist.id, isProviderPlaylist) {
        refreshSavedState()
    }

    val albumFallbackName = stringResource(R.string.library_album_fallback)
    val albumFallbackCreator = stringResource(R.string.share_netease)
    val displayed = (detail?.summary ?: initialPlaylist).let { summary ->
        if (albumId != null && detail == null) {
            summary.copy(
                name = summary.name.ifBlank { albumFallbackName },
                creatorName = summary.creatorName.ifBlank { albumFallbackCreator },
            )
        } else {
            summary
        }
    }
    val songs = detail?.songs.orEmpty()
    val ownedPlaylistId = displayed.id.takeIf {
        !isAlbum &&
        displayed.creatorUserId != null && displayed.creatorUserId == currentUserId
    }

    LaunchedEffect(displayed.coverUrl) {
        palette = MeloXDetailPaletteProvider.paletteFor(displayed.coverUrl)
    }

    val foreground = if (palette.prefersDarkAppearance) Color.White else Color.Black
    val secondary = foreground.copy(alpha = 0.48f)
    val orderedSongs = remember(songs, sortMode) {
        when (sortMode) {
            MeloXPlaylistSortMode.Original -> songs
            MeloXPlaylistSortMode.Title -> songs.sortedBy { it.name.lowercase() }
            MeloXPlaylistSortMode.Artist -> songs.sortedWith(compareBy({ it.artists.lowercase() }, { it.name.lowercase() }))
            MeloXPlaylistSortMode.Album -> songs.sortedWith(compareBy({ it.album.lowercase() }, { it.name.lowercase() }))
        }
    }
    val filteredSongs = remember(orderedSongs, searchQuery) {
        val query = searchQuery.trim().lowercase()
        if (query.isEmpty()) orderedSongs else orderedSongs.filter { song ->
            song.name.lowercase().contains(query) ||
                song.artists.lowercase().contains(query) ||
                song.album.lowercase().contains(query)
        }
    }

    PullToRefreshBox(
        isRefreshing = loading && detail != null,
        onRefresh = { scope.launch { refreshPlaylist() } },
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black),
    ) {
        // Keep the exact MeloX artwork-driven background renderer for every source.
        MeloXFlowingLightBackdrop(
            artworkUrl = displayed.coverUrl,
            isPlaying = false,
            modifier = Modifier.fillMaxSize(),
        )
        // The animated backdrop can drift darker or lighter than the source
        // artwork used by the palette sampler. Add Apple's legibility layer so
        // the chosen foreground remains readable throughout that motion.
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    if (palette.prefersDarkAppearance) {
                        Color.Black.copy(alpha = 0.26f)
                    } else {
                        Color.White.copy(alpha = 0.46f)
                    },
                ),
        )

        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding(),
        ) {
            MeloXPlaylistToolbar(
                foreground = foreground,
                onBack = onBack,
                onShare = {
                    if (providerAlbum != null) {
                        shareProviderAlbum(context, providerAlbum)
                    } else if (isAlbum) {
                        MeloXNeteaseResourceShareActivity.launch(
                            context,
                            "album",
                            displayed.id,
                            displayed.name,
                            "https://music.163.com/album?id=${displayed.id}",
                        )
                    } else {
                        sharePlaylistFromDetail(context, displayed)
                    }
                },
                showMore = (!isAlbum && !isProviderCollection) || (providerSync != null && !isAlbum),
                onMore = {
                    if (providerSync != null) showProviderRename = true else showPlaylistActions = true
                },
            )
            MeloXPlaylistSearchField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                foreground = foreground,
                modifier = Modifier.padding(horizontal = 18.dp, vertical = 10.dp),
            )

            LazyVerticalGrid(
                columns = GridCells.Fixed(if (detailWindow.supportsTwoPane) 2 else 1),
                modifier = Modifier.fillMaxSize(),
                state = rememberLazyGridState(),
                contentPadding = PaddingValues(
                    start = if (detailWindow.supportsTwoPane) detailWindow.gutter else 0.dp,
                    end = if (detailWindow.supportsTwoPane) detailWindow.gutter else 0.dp,
                    bottom = MeloXBottomContentClearance,
                ),
            ) {
                item(span = { androidx.compose.foundation.lazy.grid.GridItemSpan(maxLineSpan) }) {
                    MeloXStandardPlaylistHero(
                        playlist = displayed,
                        tracks = filteredSongs,
                        foreground = foreground,
                        secondary = secondary,
                        sourceLabel = providerAlbum?.id?.source?.displayName
                            ?: displayed.providerPlaylist?.id?.source?.displayName
                            ?: stringResource(R.string.share_netease),
                        onPlay = {
                            filteredSongs.firstOrNull()?.let { first ->
                                PlaybackCommands.playQueue(
                                    context = context,
                                    songs = filteredSongs,
                                    selectedSongId = first.id,
                                    onFailure = { errorMessage = it.message ?: context.getString(R.string.library_play_failed) },
                                )
                            }
                        },
                        onShuffle = {
                            val shuffled = filteredSongs.shuffled()
                            shuffled.firstOrNull()?.let { first ->
                                PlaybackCommands.playQueue(
                                    context = context,
                                    songs = shuffled,
                                    selectedSongId = first.id,
                                    onFailure = { errorMessage = it.message ?: context.getString(R.string.library_play_failed) },
                                )
                            }
                        },
                        isSaved = isSaved == true,
                        showSaveAction = !isProviderCollection && (!isAlbum || isSaved != null),
                        onToggleSaved = {
                            if (!isProviderPlaylist && !savingPlaylist) {
                                val desired = isSaved != true
                                savingPlaylist = true
                                scope.launch {
                                    runCatching {
                                        if (isAlbum) albumClient.setAlbumSubscribed(displayed.id, desired)
                                        else operationsClient.setPlaylistSubscribed(displayed.id, desired)
                                    }.onSuccess {
                                        isSaved = desired
                                    }.onFailure {
                                        errorMessage = it.message ?: if (isAlbum) context.getString(R.string.library_album_save_failed) else context.getString(R.string.library_playlist_save_failed)
                                    }
                                    savingPlaylist = false
                                }
                            }
                        },
                        sharedTransitionScope = sharedTransitionScope,
                        animatedVisibilityScope = animatedVisibilityScope,
                        artworkSharedKey = artworkSharedKey ?: playlistArtworkSharedKey(displayed.id),
                    )
                }

                when {
                    loading && songs.isEmpty() -> item {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(180.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            CircularProgressIndicator(color = foreground)
                        }
                    }
                    errorMessage != null && songs.isEmpty() -> item {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(220.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center,
                        ) {
                            Text(
                                errorMessage.orEmpty(),
                                color = secondary,
                                textAlign = TextAlign.Center,
                            )
                            Text(
                                stringResource(R.string.account_retry),
                                modifier = Modifier
                                    .padding(top = 12.dp)
                                    .clickable {
                                        scope.launch {
                                            refreshPlaylist()
                                        }
                                    }
                                    .padding(8.dp),
                                color = foreground,
                                fontWeight = FontWeight.SemiBold,
                            )
                        }
                    }
                    filteredSongs.isEmpty() -> item {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(180.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(stringResource(R.string.library_no_songs), color = secondary)
                        }
                    }
                    else -> gridItemsIndexed(
                        items = filteredSongs,
                        key = { _, song -> song.id },
                    ) { index, song ->
                        MeloXPlaylistTrackRow(
                            song = song,
                            index = index,
                            foreground = foreground,
                            showMore = providerSync != null || !isProviderCollection,
                            onClick = {
                                PlaybackCommands.playQueue(
                                    context = context,
                                    songs = filteredSongs,
                                    selectedSongId = song.id,
                                    onFailure = { errorMessage = it.message ?: context.getString(R.string.library_play_failed) },
                                )
                            },
                            onMore = {
                                if (providerSync != null) providerSyncTrack = song else selectedTrackAction = song
                            },
                            onPlayNext = { PlaybackCommands.playNext(context, song) },
                            onPlayLast = { PlaybackCommands.addToQueue(context, song) },
                            endAction = run {
                                val sync = providerSync
                                val playlist = providerPlaylist
                                val track = song.providerTrack
                                if (sync != null && playlist != null && track != null) {
                                    MeloXSwipeAction(context.getString(R.string.library_remove_song), MeloXSymbol.Trash, Color(0xFFFF3B30)) {
                                        if (!providerSyncBusy) {
                                            providerSyncBusy = true
                                            scope.launch {
                                                runCatching {
                                                    withContext(Dispatchers.IO) { sync.removeTrackFromPlaylist(track, playlist) }
                                                }.onSuccess { refreshPlaylist() }
                                                    .onFailure { errorMessage = it.message ?: context.getString(R.string.library_remove_failed) }
                                                providerSyncBusy = false
                                            }
                                        }
                                    }
                                } else if (isProviderCollection) {
                                    null
                                } else if (ownedPlaylistId != null) {
                                    MeloXSwipeAction(context.getString(R.string.library_remove_song), MeloXSymbol.Trash, Color(0xFFFF3B30)) {
                                        scope.launch {
                                            runCatching { operationsClient.removeSongFromPlaylist(song.id, ownedPlaylistId) }
                                                .onSuccess { refreshPlaylist() }
                                                .onFailure { errorMessage = it.message ?: context.getString(R.string.library_remove_failed) }
                                        }
                                    }
                                } else {
                                    MeloXSwipeAction(context.getString(R.string.artist_add_library), MeloXSymbol.Heart, Color(0xFFFF3B30)) {
                                        scope.launch {
                                            runCatching { operationsClient.setSongLiked(song.id, true) }
                                                .onSuccess {
                                                    currentUserId?.let { userId ->
                                                        cache.loadSnapshot(userId)?.let { cached ->
                                                            cache.saveSnapshot(userId, cached.withSongLiked(song, true))
                                                        }
                                                    }
                                                    onSongLikeChanged(song, true)
                                                }
                                                .onFailure { errorMessage = it.message ?: context.getString(R.string.library_add_failed) }
                                        }
                                    }
                                }
                            },
                        )
                        if (song.id != filteredSongs.lastOrNull()?.id) {
                            HorizontalDivider(
                                modifier = Modifier.padding(start = 66.dp, end = 20.dp),
                                thickness = 0.6.dp,
                                color = foreground.copy(alpha = 0.12f),
                            )
                        }
                    }
                }
            }
        }

        val sync = providerSync
        val backingPlaylist = providerPlaylist
        if (sync != null && backingPlaylist != null && (showProviderRename || providerSyncTrack != null)) {
            val track = providerSyncTrack
            val index = track?.let { selected -> filteredSongs.indexOfFirst { it.id == selected.id } } ?: -1
            val canReorder = sortMode == MeloXPlaylistSortMode.Original && searchQuery.isBlank()
            val mutatePlaylist: (suspend () -> Unit) -> Unit = { block ->
                if (!providerSyncBusy) {
                    providerSyncBusy = true
                    providerSyncError = null
                    scope.launch {
                        runCatching { withContext(Dispatchers.IO) { block() } }
                            .onSuccess {
                                showProviderRename = false
                                providerSyncTrack = null
                                refreshPlaylist()
                            }
                            .onFailure { providerSyncError = it.message }
                        providerSyncBusy = false
                    }
                }
            }
            MeloXProviderPlaylistSyncSheet(
                title = displayed.name,
                trackName = track?.name,
                canRename = track == null,
                canDelete = track == null && sync.canDeletePlaylists,
                onDelete = {
                    if (!providerSyncBusy) {
                        providerSyncBusy = true
                        providerSyncError = null
                        scope.launch {
                            runCatching { withContext(Dispatchers.IO) { sync.deletePlaylist(backingPlaylist) } }
                                .onSuccess {
                                    showProviderRename = false
                                    onBack()
                                }
                                .onFailure { providerSyncError = it.message }
                            providerSyncBusy = false
                        }
                    }
                },
                canRemove = track?.providerTrack != null,
                canMoveUp = canReorder && index > 0,
                canMoveDown = canReorder && index >= 0 && index < filteredSongs.lastIndex,
                busy = providerSyncBusy,
                error = providerSyncError,
                onRename = { name -> mutatePlaylist { sync.renamePlaylist(backingPlaylist, name) } },
                onRemove = {
                    track?.providerTrack?.let { current ->
                        mutatePlaylist { sync.removeTrackFromPlaylist(current, backingPlaylist) }
                    }
                },
                onMoveUp = {
                    track?.providerTrack?.let { current ->
                        mutatePlaylist { sync.reorderPlaylistTrack(backingPlaylist, current, index - 1) }
                    }
                },
                onMoveDown = {
                    track?.providerTrack?.let { current ->
                        mutatePlaylist { sync.reorderPlaylistTrack(backingPlaylist, current, index + 1) }
                    }
                },
                onDismiss = {
                    showProviderRename = false
                    providerSyncTrack = null
                    providerSyncError = null
                },
            )
        }

        if (!isProviderCollection) {
            MeloXPlaylistActionsOverlay(
                playlist = displayed,
                visible = showPlaylistActions,
                onDismiss = { showPlaylistActions = false },
                onRefresh = { scope.launch { refreshPlaylist() } },
                onBatchDownload = { showBatchDownload = true },
                onAnalyzePlaylist = {
                    ContextCompat.startForegroundService(
                        context,
                        Intent(context, MeloXPlaybackService::class.java).apply {
                            action = MeloXPlaybackService.ACTION_ANALYZE_PLAYLIST
                            putExtra(
                                MeloXPlaybackService.EXTRA_ANALYSIS_SOURCE,
                                MusicSource.Netease.storageValue,
                            )
                            putExtra(
                                MeloXPlaybackService.EXTRA_ANALYSIS_PLAYLIST_ID,
                                displayed.id.toString(),
                            )
                        },
                    )
                },
                sortMode = sortMode,
                onSortModeChanged = { sortMode = it },
            )
            MeloXBatchDownloadSheet(
                songs = songs,
                sourcePlaylist = MeloXDownloadPlaylistRef(
                    id = displayed.id,
                    name = displayed.name,
                    artworkUrl = displayed.coverUrl,
                ),
                visible = showBatchDownload,
                onDismiss = { showBatchDownload = false },
            )
            val actionSong = selectedTrackAction
            if (actionSong != null) {
                MeloXSongActionsOverlay(
                    song = actionSong,
                    queue = songs,
                    visible = true,
                    onDismiss = { selectedTrackAction = null },
                    sourcePlaylist = MeloXDownloadPlaylistRef(
                        id = displayed.id,
                        name = displayed.name,
                        artworkUrl = displayed.coverUrl,
                    ),
                    sourceOwnedPlaylistId = ownedPlaylistId,
                    onSourcePlaylistChanged = {
                        selectedTrackAction = null
                        scope.launch { refreshPlaylist() }
                    },
                )
            }
        }
    }
}

private fun NeteaseLibrarySnapshot.withSongLiked(song: SearchSong, liked: Boolean): NeteaseLibrarySnapshot =
    copy(
        likedSongs = if (liked) {
            listOf(song) + likedSongs.filterNot { it.id == song.id }
        } else {
            likedSongs.filterNot { it.id == song.id }
        },
    )

@Composable
private fun MeloXPlaylistToolbar(
    foreground: Color,
    onBack: () -> Unit,
    onShare: () -> Unit,
    showMore: Boolean = true,
    onMore: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(58.dp)
            .padding(horizontal = 18.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        MeloXGlassCircleButton(
            foreground = foreground,
            size = 44.dp,
            onClick = onBack,
        ) {
            MeloXBackGlyph(Modifier.size(22.dp), foreground)
        }

        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            MeloXGlassCircleButton(
                foreground = foreground,
                size = 44.dp,
                onClick = onShare,
            ) {
                MeloXShareGlyph(Modifier.size(22.dp), Color(0xFFFF3147))
            }
            if (showMore) {
                MeloXGlassCircleButton(
                    foreground = foreground,
                    size = 44.dp,
                    onClick = onMore,
                ) {
                    Text(
                        "•••",
                        color = Color(0xFFFF3147),
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 1.sp,
                    )
                }
            }
        }
    }
}

@Composable
private fun MeloXPlaylistSearchField(
    value: String,
    onValueChange: (String) -> Unit,
    foreground: Color,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(44.dp)
            .clip(RoundedCornerShape(22.dp))
            .background(glassColor(foreground)),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        MeloXSearchGlyph(
            modifier = Modifier
                .padding(start = 14.dp)
                .size(20.dp),
            color = foreground,
        )
        Box(
            modifier = Modifier
                .weight(1f)
                .padding(horizontal = 10.dp),
            contentAlignment = Alignment.CenterStart,
        ) {
            if (value.isEmpty()) {
                Text(
                    stringResource(R.string.library_search_in_playlist),
                    color = foreground.copy(alpha = 0.46f),
                    fontSize = 17.sp,
                )
            }
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                singleLine = true,
                textStyle = androidx.compose.ui.text.TextStyle(
                    color = foreground,
                    fontSize = 17.sp,
                ),
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
private fun MeloXStandardPlaylistHero(
    playlist: NeteasePlaylistSummary,
    tracks: List<SearchSong>,
    foreground: Color,
    secondary: Color,
    sourceLabel: String,
    onPlay: () -> Unit,
    onShuffle: () -> Unit,
    isSaved: Boolean,
    showSaveAction: Boolean,
    onToggleSaved: () -> Unit,
    sharedTransitionScope: SharedTransitionScope,
    animatedVisibilityScope: AnimatedVisibilityScope,
    // 调用方可覆盖共享元素 key：首页/发现页的卡片与这里配对的 key
    // 必须带 collection 前缀，否则同一首页里两个 block 含同一歌单时
    // 同一作用域内会出现重复 key。
    artworkSharedKey: String = playlistArtworkSharedKey(playlist.id),
) {
    BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
        val artworkSize = minOf(maxWidth * 0.68f, 300.dp)
        var descriptionExpanded by remember(playlist.id) { mutableStateOf(false) }
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 26.dp, bottom = 22.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            val sharedArtworkModifier = with(sharedTransitionScope) {
                Modifier.sharedElement(
                    sharedContentState = rememberSharedContentState(
                        key = artworkSharedKey,
                    ),
                    animatedVisibilityScope = animatedVisibilityScope,
                    renderInOverlayDuringTransition = true,
                    zIndexInOverlay = 1f,
                )
            }

            // 阴影必须留在 sharedElement 修饰链之外。写在 sharedElement 之后的
            // elevation 会被当作元素内容一起画进过渡 overlay，返回时在封面四周
            // 留下一圈黑色投影（浅色背景上混成暗框）。外层 Box 承担阴影，
            // 静态观感不变，共享元素只剩图片本身。
            Box(
                modifier = Modifier
                    .size(artworkSize)
                    .shadow(
                        elevation = 18.dp,
                        shape = RoundedCornerShape(12.dp),
                        clip = false,
                        ambientColor = Color.Black.copy(alpha = 0.18f),
                        spotColor = Color.Black.copy(alpha = 0.18f),
                    ),
            ) {
                AsyncImage(
                    model = playlist.coverUrl,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = sharedArtworkModifier
                        .fillMaxSize()
                        .clip(RoundedCornerShape(12.dp)),
                )
            }

            Text(
                text = playlist.name,
                modifier = Modifier.padding(top = 24.dp, start = 24.dp, end = 24.dp),
                color = foreground,
                fontSize = 22.sp,
                lineHeight = 27.sp,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )

            Text(
                text = playlist.creatorName.ifBlank { sourceLabel },
                modifier = Modifier.padding(top = 8.dp),
                color = foreground,
                fontSize = 20.sp,
                lineHeight = 24.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )

            Text(
                text = buildString {
                    append(stringResource(R.string.library_song_count, if (playlist.trackCount > 0) playlist.trackCount else tracks.size))
                    if (MeloXSettingsRuntime.showPlaylistPlayCount && playlist.playCount > 0) {
                        append(stringResource(R.string.library_play_count_suffix, compactPlayCount(playlist.playCount)))
                    }
                },
                modifier = Modifier.padding(top = 7.dp),
                color = secondary,
                fontSize = 15.sp,
                lineHeight = 19.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
            )

            Row(
                modifier = Modifier.padding(top = 17.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                MeloXGlassCircleButton(
                    foreground = foreground,
                    size = 54.dp,
                    enabled = tracks.isNotEmpty(),
                    onClick = onShuffle,
                ) {
                    MeloXShuffleGlyph(Modifier.size(26.dp), foreground)
                }

                Box(
                    modifier = Modifier
                        .width(140.dp)
                        .height(50.dp)
                        .meloXLiquidButton(
                            shape = RoundedCornerShape(25.dp),
                            enabled = tracks.isNotEmpty(),
                            tint = if (foreground == Color.White) Color.White else Color.Black,
                            surfaceColor = if (foreground == Color.White) {
                                Color.White.copy(alpha = 0.82f)
                            } else {
                                Color.Black.copy(alpha = 0.82f)
                            },
                            lensRadius = 12.dp,
                            refractionHeight = 20.dp,
                        )
                        .clickable(enabled = tracks.isNotEmpty(), onClick = onPlay),
                    contentAlignment = Alignment.Center,
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(7.dp),
                    ) {
                        MeloXPlayGlyph(
                            Modifier.size(19.dp),
                            if (foreground == Color.White) Color.Black else Color.White,
                        )
                        Text(
                            stringResource(R.string.action_play),
                            color = if (foreground == Color.White) Color.Black else Color.White,
                            fontSize = 19.sp,
                            fontWeight = FontWeight.Bold,
                        )
                    }
                }

                if (showSaveAction) {
                    MeloXGlassCircleButton(
                        foreground = foreground,
                        size = 54.dp,
                        onClick = onToggleSaved,
                    ) {
                        Text(
                            if (isSaved) "✓" else "+",
                            color = foreground,
                            fontSize = if (isSaved) 24.sp else 34.sp,
                            lineHeight = 34.sp,
                            fontWeight = if (isSaved) FontWeight.SemiBold else FontWeight.Light,
                        )
                    }
                }
            }

            playlist.description
                ?.takeUnless { description ->
                    description.isBlank() || description.equals("null", ignoreCase = true)
                }
                ?.let { description ->
                    Text(
                        text = description,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(start = 20.dp, end = 20.dp, top = 24.dp)
                            .clickable { descriptionExpanded = !descriptionExpanded },
                        color = secondary,
                        fontSize = 14.sp,
                        lineHeight = 20.sp,
                        maxLines = if (descriptionExpanded) 12 else 3,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
        }
    }
}

@Composable
private fun MeloXPlaylistTrackRow(
    song: SearchSong,
    index: Int,
    foreground: Color,
    showMore: Boolean = true,
    onClick: () -> Unit,
    onMore: () -> Unit,
    onPlayNext: () -> Unit,
    onPlayLast: () -> Unit,
    endAction: MeloXSwipeAction?,
) {
    MeloXSwipeActionRow(
        startActions = listOf(
            MeloXSwipeAction(stringResource(R.string.player_play_next), MeloXSymbol.Next, Color(0xFF8E5AF7), onPlayNext),
            MeloXSwipeAction(stringResource(R.string.artist_play_later), MeloXSymbol.Queue, Color(0xFFFF9F0A), onPlayLast),
        ),
        endActions = listOfNotNull(endAction),
        startFullSwipeActionIndex = if (MeloXSettingsRuntime.swipeFullAction == MeloXSwipeFullAction.AddToQueue) 1 else 0,
        onClick = onClick,
        onLongClick = if (showMore) onMore else null,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 20.dp, end = 20.dp, top = 11.dp, bottom = 11.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Row(
                modifier = Modifier.weight(1f),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    text = "${index + 1}",
                    modifier = Modifier.width(40.dp),
                    color = foreground.copy(alpha = 0.48f),
                    fontSize = 20.sp,
                    textAlign = TextAlign.Center,
                    maxLines = 1,
                )
                Text(
                    text = song.name,
                    modifier = Modifier.weight(1f),
                    color = foreground,
                    fontSize = 17.sp,
                    lineHeight = 22.sp,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun MeloXGlassCircleButton(
    foreground: Color,
    size: androidx.compose.ui.unit.Dp,
    enabled: Boolean = true,
    onClick: () -> Unit,
    content: @Composable () -> Unit,
) {
    Box(
        modifier = Modifier
            .size(size)
            .meloXLiquidButton(
                shape = CircleShape,
                enabled = enabled,
                surfaceColor = glassColor(foreground).copy(alpha = 0.48f),
                lensRadius = 11.dp,
                refractionHeight = 18.dp,
            )
            .clickable(
                enabled = enabled,
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        content()
    }
}

private fun glassColor(foreground: Color): Color =
    if (foreground == Color.White) Color.Black.copy(alpha = 0.22f)
    else Color.White.copy(alpha = 0.64f)

@Composable
private fun MeloXPlayGlyph(modifier: Modifier, color: Color) {
    Canvas(modifier) {
        val path = Path().apply {
            moveTo(size.width * 0.24f, size.height * 0.12f)
            lineTo(size.width * 0.86f, size.height * 0.50f)
            lineTo(size.width * 0.24f, size.height * 0.88f)
            close()
        }
        drawPath(path, color)
    }
}

@Composable
private fun MeloXBackGlyph(modifier: Modifier, color: Color) {
    Canvas(modifier) {
        val stroke = size.minDimension * 0.14f
        val p = Path().apply {
            moveTo(size.width * 0.67f, size.height * 0.14f)
            lineTo(size.width * 0.32f, size.height * 0.50f)
            lineTo(size.width * 0.67f, size.height * 0.86f)
        }
        drawPath(p, color, style = Stroke(width = stroke, cap = StrokeCap.Round))
    }
}

@Composable
private fun MeloXSearchGlyph(modifier: Modifier, color: Color) {
    Canvas(modifier) {
        val stroke = size.minDimension * 0.11f
        drawCircle(
            color = color,
            radius = size.minDimension * 0.30f,
            center = Offset(size.width * 0.42f, size.height * 0.40f),
            style = Stroke(width = stroke),
        )
        drawLine(
            color = color,
            start = Offset(size.width * 0.62f, size.height * 0.61f),
            end = Offset(size.width * 0.86f, size.height * 0.85f),
            strokeWidth = stroke,
            cap = StrokeCap.Round,
        )
    }
}

@Composable
private fun MeloXShareGlyph(modifier: Modifier, color: Color) {
    Canvas(modifier) {
        val stroke = size.minDimension * 0.09f
        drawRoundRect(
            color = color,
            topLeft = Offset(size.width * 0.20f, size.height * 0.40f),
            size = androidx.compose.ui.geometry.Size(size.width * 0.60f, size.height * 0.50f),
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(size.width * 0.08f),
            style = Stroke(width = stroke),
        )
        drawLine(
            color = color,
            start = Offset(size.width * 0.50f, size.height * 0.63f),
            end = Offset(size.width * 0.50f, size.height * 0.12f),
            strokeWidth = stroke,
            cap = StrokeCap.Round,
        )
        val arrow = Path().apply {
            moveTo(size.width * 0.34f, size.height * 0.28f)
            lineTo(size.width * 0.50f, size.height * 0.11f)
            lineTo(size.width * 0.66f, size.height * 0.28f)
        }
        drawPath(arrow, color, style = Stroke(width = stroke, cap = StrokeCap.Round))
    }
}

@Composable
private fun MeloXShuffleGlyph(modifier: Modifier, color: Color) {
    Canvas(modifier) {
        val stroke = size.minDimension * 0.095f
        val top = Path().apply {
            moveTo(size.width * 0.10f, size.height * 0.28f)
            cubicTo(
                size.width * 0.34f, size.height * 0.28f,
                size.width * 0.54f, size.height * 0.72f,
                size.width * 0.78f, size.height * 0.72f,
            )
        }
        val bottom = Path().apply {
            moveTo(size.width * 0.10f, size.height * 0.72f)
            cubicTo(
                size.width * 0.34f, size.height * 0.72f,
                size.width * 0.54f, size.height * 0.28f,
                size.width * 0.78f, size.height * 0.28f,
            )
        }
        drawPath(top, color, style = Stroke(width = stroke, cap = StrokeCap.Round))
        drawPath(bottom, color, style = Stroke(width = stroke, cap = StrokeCap.Round))
        val a1 = Path().apply {
            moveTo(size.width * 0.70f, size.height * 0.17f)
            lineTo(size.width * 0.89f, size.height * 0.28f)
            lineTo(size.width * 0.70f, size.height * 0.39f)
        }
        val a2 = Path().apply {
            moveTo(size.width * 0.70f, size.height * 0.61f)
            lineTo(size.width * 0.89f, size.height * 0.72f)
            lineTo(size.width * 0.70f, size.height * 0.83f)
        }
        drawPath(a1, color, style = Stroke(width = stroke, cap = StrokeCap.Round))
        drawPath(a2, color, style = Stroke(width = stroke, cap = StrokeCap.Round))
    }
}

private fun playlistArtworkSharedKey(playlistId: Long): String =
    "library-playlist-artwork-$playlistId"

private fun formatDuration(milliseconds: Long): String {
    val totalSeconds = milliseconds.coerceAtLeast(0L) / 1_000L
    return "%d:%02d".format(totalSeconds / 60L, totalSeconds % 60L)
}

@Composable
private fun compactPlayCount(value: Long): String = when {
    value >= 100_000_000L -> stringResource(R.string.library_count_hundred_million, value / 100_000_000.0)
    value >= 10_000L -> stringResource(R.string.library_count_ten_thousand, value / 10_000.0)
    else -> value.toString()
}

private fun optimized160Artwork(url: String?): String? {
    val source = url?.takeIf(String::isNotBlank) ?: return null
    if (!source.contains(".music.126.net")) return source
    val separator = if (source.contains('?')) '&' else '?'
    return if (source.contains("param=")) source else "$source${separator}param=160y160"
}

private fun sharePlaylistFromDetail(context: android.content.Context, playlist: NeteasePlaylistSummary) {
    val providerPlaylist = playlist.providerPlaylist
    if (providerPlaylist == null) {
        com.lladlam.melox.ui.sharing.MeloXNeteaseResourceShareActivity.launch(
            context = context,
            type = "playlist",
            id = playlist.id,
            title = playlist.name,
            url = "https://music.163.com/playlist?id=${playlist.id}",
        )
        return
    }

    val text = buildString {
        append(providerPlaylist.title)
        if (!providerPlaylist.creatorName.isNullOrBlank()) append(" · ").append(providerPlaylist.creatorName)
        append('\n').append(providerPlaylist.id.source.displayName)
    }
    val intent = android.content.Intent.createChooser(
        android.content.Intent(android.content.Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(android.content.Intent.EXTRA_TEXT, text)
            addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
        },
        context.getString(R.string.library_share_playlist),
    ).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
    context.startActivity(intent)
}

private fun shareProviderAlbum(context: android.content.Context, album: MusicAlbumSummary) {
    val text = buildString {
        append(album.title)
        val artists = album.artists.joinToString(" / ") { it.name }
        if (artists.isNotBlank()) append(" · ").append(artists)
        append('\n').append(album.id.source.displayName)
    }
    val intent = android.content.Intent.createChooser(
        android.content.Intent(android.content.Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(android.content.Intent.EXTRA_TEXT, text)
            addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
        },
        context.getString(R.string.library_share_album),
    ).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
    context.startActivity(intent)
}
