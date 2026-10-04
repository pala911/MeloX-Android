package com.lladlam.melox.ui.discovery

import androidx.activity.compose.BackHandler
import androidx.activity.compose.PredictiveBackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.ui.graphics.Brush
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionLayout
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.zIndex
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import com.lladlam.melox.core.account.NeteaseAccountProfile
import com.lladlam.melox.R
import com.lladlam.melox.core.account.NeteaseSessionStore
import com.lladlam.melox.core.account.rememberNeteaseSessionStore
import com.lladlam.melox.core.library.NeteaseHomeContent
import com.lladlam.melox.core.library.NeteaseLibraryCache
import com.lladlam.melox.core.library.NeteaseLibraryClient
import com.lladlam.melox.core.library.NeteasePlaylistSummary
import com.lladlam.melox.core.model.SearchSong
import com.lladlam.melox.core.music.model.MusicAccountSummary
import com.lladlam.melox.core.music.model.MusicHomeFeed
import com.lladlam.melox.core.music.model.MusicPlaylistSummary
import com.lladlam.melox.core.music.model.MusicRankingSummary
import com.lladlam.melox.core.music.model.MusicSource
import com.lladlam.melox.core.music.model.MusicTrack
import com.lladlam.melox.core.music.provider.HomeFeedCapability
import com.lladlam.melox.core.music.provider.MeloXMusicProviders
import com.lladlam.melox.core.music.provider.MeloXLegacyUiBridge
import com.lladlam.melox.core.music.provider.PlaylistCapability
import com.lladlam.melox.core.music.provider.RankingCapability
import com.lladlam.melox.core.music.provider.UserLibraryCapability
import com.lladlam.melox.core.recommendation.LocalRecommendationStore
import com.lladlam.melox.playback.PlaybackCommands
import com.lladlam.melox.playback.ProviderPlaybackCommands
import com.lladlam.melox.ui.animation.MeloXMotion
import com.lladlam.melox.ui.animation.meloXPageEnter
import com.lladlam.melox.ui.animation.meloXPageExit
import com.lladlam.melox.ui.animation.meloXSettledMillis
import com.lladlam.melox.ui.account.MeloXAccountActivity
import com.lladlam.melox.ui.collection.MeloXCollectionDetailActivity
import com.lladlam.melox.ui.glass.MeloXActionIcon
import com.lladlam.melox.ui.glass.MeloXGlassCard
import com.lladlam.melox.ui.glass.MeloXGlassButton
import com.lladlam.melox.ui.glass.MeloXGlassButtonStyle
import com.lladlam.melox.ui.glass.MeloXShapes
import com.lladlam.melox.ui.glass.MeloXSystemColors
import com.lladlam.melox.ui.glass.MeloXTypography
import com.lladlam.melox.ui.glass.meloXContentSurface
import com.lladlam.melox.ui.glass.meloXGlassSurface
import com.lladlam.melox.ui.glass.MeloXIosTopBar
import com.lladlam.melox.ui.glass.MeloXSymbol
import com.lladlam.melox.ui.glass.MeloXSymbolIcon
import com.lladlam.melox.ui.glass.MeloXSymbolVariant
import com.lladlam.melox.ui.podcast.MeloXPodcastScreen
import com.lladlam.melox.ui.library.MeloXUnifiedPlaylistDetailScreen
import com.lladlam.melox.ui.library.MeloXUnifiedSongListDetailScreen
import com.lladlam.melox.ui.settings.MeloXSettingsRuntime
import com.lladlam.melox.ui.layout.rememberMeloXWindowInfo
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val Accent = Color(0xFFFF3147)
private val Categories = listOf("推荐歌单", "排行榜", "精品歌单", "播客", "全部", "华语", "欧美", "流行", "摇滚", "民谣", "电子", "轻音乐", "影视原声", "ACG")

/** Display label for an explore category id. The id itself stays Chinese. */
@Composable
private fun exploreCategoryLabel(id: String): String = when (id) {
    "推荐歌单" -> stringResource(R.string.home_cat_for_you_short)
    "排行榜" -> stringResource(R.string.home_cat_charts)
    "精品歌单" -> stringResource(R.string.home_cat_picks_short)
    "播客" -> stringResource(R.string.home_cat_podcasts)
    "全部" -> stringResource(R.string.home_cat_all)
    "华语" -> stringResource(R.string.home_cat_chinese)
    "欧美" -> stringResource(R.string.home_cat_western)
    "流行" -> stringResource(R.string.home_cat_pop)
    "摇滚" -> stringResource(R.string.home_cat_rock)
    "民谣" -> stringResource(R.string.home_cat_folk)
    "电子" -> stringResource(R.string.home_cat_electronic)
    "轻音乐" -> stringResource(R.string.home_cat_easy)
    "影视原声" -> stringResource(R.string.home_cat_soundtrack)
    "ACG" -> stringResource(R.string.home_cat_acg)
    else -> id
}

// 首页/发现页覆盖层过渡的收尾余量：动画时长之外再留一点，等 sharedElement 的
// overlay 层真正收干净，再解锁并放开卡片。给少了会在切换瞬间看到 overlay 残影。
private const val DiscoveryOverlaySettleMillis = 60L

private sealed interface DiscoveryTrack {
    val key: String
    val title: String
    val artist: String
    val artworkUrl: String?

    data class Netease(val song: SearchSong) : DiscoveryTrack {
        override val key: String = "netease:${song.id}"
        override val title: String = song.name
        override val artist: String = song.artists
        override val artworkUrl: String? = song.artworkUrl
    }

    data class Provider(val track: MusicTrack) : DiscoveryTrack {
        override val key: String = "${track.id.source.storageValue}:${track.id.value}"
        override val title: String = track.title
        override val artist: String = track.artistText
        override val artworkUrl: String? = track.artworkUrl
    }
}

private sealed interface DiscoveryCollection {
    val key: String
    val title: String
    val artworkUrl: String?
    val creatorName: String
    val playCount: Long
    val description: String?

    data class Netease(val playlist: NeteasePlaylistSummary) : DiscoveryCollection {
        override val key: String = "netease-playlist:${playlist.id}"
        override val title: String = playlist.name
        override val artworkUrl: String? = playlist.coverUrl
        override val creatorName: String = playlist.creatorName
        override val playCount: Long = playlist.playCount
        override val description: String? = playlist.description
    }

    data class ProviderPlaylist(val playlist: MusicPlaylistSummary) : DiscoveryCollection {
        override val key: String = "${playlist.id.source.storageValue}-playlist:${playlist.id.value}"
        override val title: String = playlist.title
        override val artworkUrl: String? = playlist.artworkUrl
        override val creatorName: String = playlist.creatorName.orEmpty()
        override val playCount: Long = playlist.playCount ?: 0L
        override val description: String? = playlist.description
    }

    data class ProviderRanking(val ranking: MusicRankingSummary) : DiscoveryCollection {
        override val key: String = "${ranking.id.source.storageValue}-ranking:${ranking.id.value}"
        override val title: String = ranking.title
        override val artworkUrl: String? = ranking.artworkUrl
        override val creatorName: String = ranking.id.source.displayName
        override val playCount: Long = 0L
        override val description: String? = ranking.subtitle
    }
}

private data class HomeAccountUi(
    val name: String,
    val avatarUrl: String?,
    val subtitle: String,
    val onClick: (() -> Unit)? = null,
)

private sealed interface HomeBlock {
    data object QuickActions : HomeBlock
    data class Collections(
        val title: String,
        val trailing: String,
        val values: List<DiscoveryCollection>,
    ) : HomeBlock
    data class Tracks(
        val title: String,
        val trailing: String,
        val values: List<DiscoveryTrack>,
    ) : HomeBlock
    data class Podcasts(val values: List<com.lladlam.melox.core.library.NeteaseHomePodcast>) : HomeBlock
}

@Composable
fun MeloXHomeScreen(
    source: MusicSource = MusicSource.Netease,
    onOpenTool: (String) -> Unit = {},
) {
    if (source == MusicSource.Netease) {
        NeteaseHomeDataScreen(onOpenTool)
    } else {
        ProviderHomeDataScreen(source, onOpenTool)
    }
}

@Composable
private fun NeteaseHomeDataScreen(onOpenTool: (String) -> Unit) {
    val context = LocalContext.current.applicationContext
    val cache = remember(context) { NeteaseLibraryCache(context) }
    val client by remember(context) {
        lazy { NeteaseLibraryClient({ NeteaseSessionStore.readCookie(context) }) }
    }
    val scope = rememberCoroutineScope()
    val session = rememberNeteaseSessionStore()
    var content by remember { mutableStateOf<NeteaseHomeContent?>(null) }
    var refreshing by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var selectedCollection by remember { mutableStateOf<DiscoveryCollection?>(null) }
    // 内容与可见性分开持有：返回时只把 selectedCollection 置空（让列表卡片重新进入过渡），
    // overlayCollection 保留，退出过渡期间详情仍被组合 → 封面才能一镜到底地 morph 回卡片。
    var overlayCollection by remember { mutableStateOf<DiscoveryCollection?>(null) }
    // 卡片可见性必须与 selectedCollection 同帧同步：点击即退出、返回即进入，才能与详情
    // hero 的 AnimatedVisibility 完全重叠。原「占用 key」方案把它压后到 LaunchedEffect
    // 里赋值 —— 进入晚一帧、返回要等 PageExitMillis + settle(280ms) 才放开，返回方向的
    // 卡片 enter 落在 hero exit(220ms) 结束之后，两端永不重叠 → sharedElement 无从配对。
    // 过渡串行化已由 overlayTransitionBusy 排队负责，不再需要占用 key。
    var selectedArtworkSlot by remember { mutableStateOf<String?>(null) }
    // 覆盖层进入/退出过渡是否仍在进行。退出没跑完就点下一张卡会让 HomeOverlayPage 的
    // AnimatedVisibility 取消旧过渡、直奔新目标，旧卡被遗弃在半途。过渡期间的打开请求
    // 先排队，等本次过渡跑完立刻执行（直接忽略会丢输入）。
    var overlayTransitionBusy by remember { mutableStateOf(false) }
    var pendingCollection by remember { mutableStateOf<DiscoveryCollection?>(null) }
    var songList by remember { mutableStateOf<HomeSongList?>(null) }
    // 内容与可见性分开持有（同 overlayCollection）：退出过渡期间 songList 已为 null，
    // 但详情内容要留到过渡跑完。否则返回按钮一按就对着空 Box 播退出动画 → 直接消失
    // （手势返回因为先跟手滑出，看不出这个问题）。
    var overlaySongList by remember { mutableStateOf<HomeSongList?>(null) }
    LaunchedEffect(songList) {
        if (songList == null && overlaySongList != null) {
            delay(meloXSettledMillis(MeloXMotion.PageExitMillis, DiscoveryOverlaySettleMillis))
            overlaySongList = null
        }
    }
    var activeAction by remember { mutableStateOf<String?>(null) }
    var localRecommendations by remember { mutableStateOf(emptyList<com.lladlam.melox.core.recommendation.LocalRecommendationItem>()) }
    var localCandidates by remember { mutableStateOf(emptyList<MusicTrack>()) }
    val homeCacheKey = "${session.cookie.hashCode()}_${MeloXSettingsRuntime.musicArea}_${MeloXSettingsRuntime.podcastsEnabled}"

    fun refresh(forceServer: Boolean = false) {
        if (refreshing) return
        scope.launch {
            refreshing = true
            runCatching {
                if (session.isLoggedIn && session.profile == null) session.refreshProfile(force = true)
                withContext(Dispatchers.IO) {
                    client.homeContent(
                        area = MeloXSettingsRuntime.musicArea,
                        userId = session.profile?.userId,
                        podcastsEnabled = MeloXSettingsRuntime.podcastsEnabled,
                        refresh = forceServer,
                    )
                }
            }.onSuccess {
                content = it
                cache.saveHomeContent(homeCacheKey, it)
                error = null
            }.onFailure { error = it.message ?: context.getString(R.string.home_load_failed) }
            refreshing = false
        }
    }

    LaunchedEffect(homeCacheKey) {
        val localData = withContext(Dispatchers.IO) {
            LocalRecommendationStore.readRecommendations(context) to
                LocalRecommendationStore.readCandidateTracks(context)
        }
        localRecommendations = localData.first
        localCandidates = localData.second
        content = cache.loadHomeContent(homeCacheKey)
        if (session.isLoggedIn) session.refreshProfile()
        if (NeteaseLibraryCache.beginHomeColdStartRefresh(homeCacheKey)) refresh(false)
    }

    val blocks = content?.let { value ->
        buildList<HomeBlock> {
            if (localRecommendations.isNotEmpty()) {
                val orderedCandidates = localRecommendations.mapNotNull { recommendation ->
                    localCandidates.firstOrNull { candidate ->
                        candidate.title == recommendation.title && candidate.artistText == recommendation.artist
                    }?.let { DiscoveryTrack.Provider(it) }
                }
                if (orderedCandidates.isNotEmpty()) add(HomeBlock.Tracks(context.getString(R.string.home_for_you_melox), context.getString(R.string.home_local_algorithm), orderedCandidates))
            }
            MeloXSettingsRuntime.homeSectionOrder.forEach { section ->
                when (section) {
                    "QuickActions" -> if (MeloXSettingsRuntime.homeQuickActionsEnabled) add(HomeBlock.QuickActions)
                    "Playlists" -> if (MeloXSettingsRuntime.homePlaylistsEnabled && value.playlists.isNotEmpty()) {
                        add(HomeBlock.Collections(context.getString(R.string.home_action_daily), context.getString(R.string.home_pull_to_refresh), value.playlists.map { DiscoveryCollection.Netease(it) }))
                    }
                    "NewSongs" -> if (MeloXSettingsRuntime.homeNewSongsEnabled && value.newSongs.isNotEmpty()) {
                        add(HomeBlock.Tracks(context.getString(R.string.home_for_you), context.getString(R.string.home_new_songs), value.newSongs.map { DiscoveryTrack.Netease(it) }))
                    }
                }
            }
            if (value.recentlyTrending.isNotEmpty()) add(HomeBlock.Tracks(context.getString(R.string.home_recent_trending), context.getString(R.string.home_from_netease_home), value.recentlyTrending.map { DiscoveryTrack.Netease(it) }))
            if (value.tailoredSongs.isNotEmpty()) add(HomeBlock.Tracks(context.getString(R.string.home_based_on_taste), context.getString(R.string.home_personalized), value.tailoredSongs.map { DiscoveryTrack.Netease(it) }))
            if (value.chartPlaylists.isNotEmpty()) add(HomeBlock.Collections(context.getString(R.string.home_action_charts), context.getString(R.string.home_netease_charts), value.chartPlaylists.map { DiscoveryCollection.Netease(it) }))
            if (value.radarPlaylists.isNotEmpty()) add(HomeBlock.Collections(context.getString(R.string.home_action_radar), context.getString(R.string.home_your_radar), value.radarPlaylists.map { DiscoveryCollection.Netease(it) }))
            if (value.personalPlaylists.isNotEmpty()) add(HomeBlock.Collections(context.getString(R.string.home_my_playlists), context.getString(R.string.home_kept_for_you), value.personalPlaylists.map { DiscoveryCollection.Netease(it) }))
            if (value.regionalSongs.isNotEmpty()) add(HomeBlock.Tracks(context.getString(R.string.home_area_trending, MeloXSettingsRuntime.musicArea), context.getString(R.string.home_area_picks), value.regionalSongs.map { DiscoveryTrack.Netease(it) }))
            if (value.roamingSongs.isNotEmpty()) add(HomeBlock.Tracks(context.getString(R.string.home_action_roam), context.getString(R.string.home_explore_more), value.roamingSongs.map { DiscoveryTrack.Netease(it) }))
            if (value.similarSongs.isNotEmpty()) add(HomeBlock.Tracks(context.getString(R.string.home_action_similar), context.getString(R.string.home_from_now_playing), value.similarSongs.map { DiscoveryTrack.Netease(it) }))
            if (
                value.podcasts.isNotEmpty() &&
                MeloXSettingsRuntime.podcastsEnabled &&
                MeloXSettingsRuntime.podcastsHomePlacement
            ) add(HomeBlock.Podcasts(value.podcasts))
        }
    }

    val account = session.profile?.let { profile ->
        HomeAccountUi(
            name = profile.nickname.takeUnless { it.isBlank() || it.equals("null", ignoreCase = true) } ?: context.getString(R.string.home_netease_user),
            avatarUrl = profile.avatarUrl,
            subtitle = profile.signature
                ?.takeUnless { it.isBlank() || it.equals("null", ignoreCase = true) }
                ?: context.getString(R.string.home_profile_subtitle),
            onClick = { MeloXAccountActivity.launch(context, profile.userId) },
        )
    }

    SharedTransitionLayout(Modifier.fillMaxSize()) {
        val sharedScope = this
        // 覆盖层过渡生命周期：只负责上锁/解锁与排队。卡片可见性已由 selectedCollection
        // 同帧同步驱动（见声明处注释），与详情 hero 完全对齐。
        LaunchedEffect(selectedCollection) {
            overlayTransitionBusy = true
            if (selectedCollection != null) {
                delay(meloXSettledMillis(MeloXMotion.PageEnterMillis, DiscoveryOverlaySettleMillis))
            } else if (overlayCollection != null) {
                delay(meloXSettledMillis(MeloXMotion.PageExitMillis, DiscoveryOverlaySettleMillis))
                selectedArtworkSlot = null
                overlayCollection = null
            }
            overlayTransitionBusy = false
            // 只在这段过渡自己跑完后接上排队请求。中途被取消时不能在这里清空，
            // 否则下一次点击会被丢掉，用户还得再点一次。
            pendingCollection?.let {
                pendingCollection = null
                selectedCollection = it
                overlayCollection = it
            }
        }
        MeloXHomeLayout(
            source = MusicSource.Netease,
            account = account,
            blocks = blocks,
            refreshing = refreshing,
            error = error,
            onRefresh = { refresh(true) },
            activeAction = activeAction,
            onQuickAction = quickAction@ { action ->
            when (action) {
                "听歌识曲" -> {
                    onOpenTool("Recognition")
                    return@quickAction
                }
                "私信" -> {
                    onOpenTool("Messages")
                    return@quickAction
                }
                "播客" -> {
                    onOpenTool("Podcasts")
                    return@quickAction
                }
                "下载" -> {
                    onOpenTool("Downloads")
                    return@quickAction
                }
                "云盘" -> {
                    onOpenTool("Cloud")
                    return@quickAction
                }
            }
            activeAction = action
            scope.launch {
                runCatching {
                    when (action) {
                        "每日推荐" -> client.dailyRecommendedSongs()
                        "热歌榜" -> client.hotSongs()
                        "私人漫游" -> client.personalFm(explore = true)
                        "私人雷达" -> {
                            val uid = session.profile?.userId ?: throw IllegalStateException(context.getString(R.string.home_login_required))
                            val snapshot = client.snapshot(uid)
                            val radar = snapshot.playlists.firstOrNull { it.name.contains("雷达") }
                                ?: throw IllegalStateException(context.getString(R.string.home_no_radar))
                            client.playlistDetail(radar.id).songs
                        }
                        "相似歌曲" -> PlaybackCommands.currentSongId()?.let { client.similarSongsBlocking(it) }
                            ?: throw IllegalStateException(context.getString(R.string.home_play_song_first))
                        "心动模式" -> {
                            val userId = session.profile?.userId ?: throw IllegalStateException(context.getString(R.string.home_login_required))
                            val snapshot = client.snapshot(userId)
                            val seed = snapshot.likedSongs.randomOrNull() ?: throw IllegalStateException(context.getString(R.string.home_liked_empty))
                            val playlistId = snapshot.likedPlaylistId ?: throw IllegalStateException(context.getString(R.string.home_liked_playlist_missing))
                            client.intelligenceModeSongs(seed.id, playlistId)
                        }
                        else -> emptyList()
                    }
                }.onSuccess { songs ->
                    if (action in HomeSongListActions && songs.isNotEmpty()) {
                        val list = HomeSongList(
                            title = action,
                            subtitle = context.getString(R.string.home_source_song_count, songs.size),
                            artworkUrl = songs.firstOrNull()?.artworkUrl,
                            songs = songs,
                        )
                        overlaySongList = list
                        songList = list
                    } else {
                        songs.firstOrNull()?.let {
                            PlaybackCommands.playQueue(context, songs, it.id, heartMode = action == "心动模式")
                        } ?: run { error = context.getString(R.string.home_no_playable) }
                    }
                }.onFailure { error = it.message ?: context.getString(R.string.home_action_load_failed, action) }
                activeAction = null
            }
        },
        onCollection = { collection, slot ->
            // 上一段过渡（进入/退出）没跑完就先排队，等它跑完立刻执行；直接执行会
            // 掐断旧过渡把卡片遗弃在半途，直接忽略又会丢输入。
            selectedArtworkSlot = slot
            if (overlayTransitionBusy) pendingCollection = collection
            else {
                selectedCollection = collection
                overlayCollection = collection
            }
        },
            // 与卡片侧 `"$slot:${collection.key}" != selectedCollectionKey` 同口径、
            // 同帧求值：点击当帧卡片就退出，返回当帧卡片就进入。
            selectedCollectionKey = selectedCollection?.let { collection ->
                selectedArtworkSlot?.let { slot -> "$slot:${collection.key}" } ?: collection.key
            },
            sharedTransitionScope = sharedScope,
        )

        // 首页留在底下。歌单页只是叠上去，打开时从右侧滑入，预见式返回滑开时露出首页。
        HomeOverlayPage(
            shown = selectedCollection != null,
            onBack = { selectedCollection = null },
            // 封面是共享元素，跟手滑出会让封面一起飞出屏外再 morph 回来。
            followPredictiveBack = false,
        ) {
            overlayCollection?.let { collection ->
                DiscoveryCollectionDetail(
                    collection = collection,
                    onBack = { selectedCollection = null },
                    sharedTransitionScope = sharedScope,
                    animatedVisibilityScope = this,
                    artworkSharedKey = discoveryCollectionArtworkSharedKey(
                        collection,
                        selectedArtworkSlot ?: "detail",
                    ),
                )
            }
        }
        HomeOverlayPage(
            shown = songList != null,
            onBack = { songList = null },
        ) {
            overlaySongList?.let { list ->
                HomeSongListDetail(list = list, onBack = { songList = null })
            }
        }
    }
}

private val HomeSongListActions = setOf("每日推荐", "热歌榜", "私人雷达")

@Composable
private fun HomeOverlayPage(
    shown: Boolean,
    onBack: () -> Unit,
    // 带共享元素的页面（歌单详情）关掉跟手位移：封面本身就是共享元素，跟着页面滑出
    // 屏外后，提交时再 snap 回来 + morph，看起来像「封面飞出去再飞回原位」。这类页面
    // 在提交后直接走 AnimatedVisibility 退出 + 共享元素 morph，与返回按钮完全一致。
    // 没有共享元素的页面（每日推荐/热歌榜/私人雷达）保留跟手滑出，滑开时露出首页。
    followPredictiveBack: Boolean = true,
    // 以 receiver 暴露 AnimatedVisibilityScope，让内部详情页能把封面
    // 接到同一个 SharedTransitionLayout 里的列表卡片上（一镜到底）。
    content: @Composable AnimatedVisibilityScope.() -> Unit,
) {
    val progress = remember { Animatable(0f) }
    // 手势返回走完后 progress 停在 1（页面已滑出屏外）。下一次进入必须归零，
    // 否则新页面会带着满位移进来、永远停在屏幕右侧外面。
    LaunchedEffect(shown) { if (shown) progress.snapTo(0f) }
    // 详情叠在首页上。普通返回和预见式返回都先关掉它，
    // 滑开时露出底下的首页，而不是灰色底或「再按一次退出」。
    BackHandler(enabled = shown, onBack = onBack)
    PredictiveBackHandler(enabled = shown) {
        try {
            it.collect { event -> if (followPredictiveBack) progress.snapTo(event.progress) }
            if (followPredictiveBack) {
                // 与搜索页详情返回一致：先关闭（退出过渡从手势落点启动），再让剩余
                // 位移收尾。不能在关闭后 snapTo(0f)——那会把已滑出屏幕的页面瞬间
                // 闪回屏幕正中再滑出去，手势返回会看到页面消失又重现一次。
                onBack()
                progress.animateTo(1f, tween(160))
            } else {
                // 共享元素页面：不跟手，直接关；退出动画自己会滑出并把封面 morph 回卡片。
                onBack()
            }
        } catch (_: CancellationException) {
            if (followPredictiveBack) progress.animateTo(0f, tween(160))
        }
    }
    AnimatedVisibility(
        visible = shown,
        enter = meloXPageEnter(fromRight = true),
        exit = meloXPageExit(toRight = true),
        modifier = Modifier.fillMaxSize().zIndex(if (shown) 1f else 0f),
    ) {
        Box(
            Modifier
                .fillMaxSize()
                .graphicsLayer {
                    translationX = size.width * progress.value
                    val scale = 1f - 0.08f * progress.value
                    scaleX = scale
                    scaleY = scale
                    transformOrigin = TransformOrigin(0f, 0.5f)
                },
        ) {
            content()
        }
    }
}

private data class HomeSongList(
    val title: String,
    val subtitle: String,
    val artworkUrl: String?,
    val songs: List<SearchSong>,
)

@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
private fun HomeSongListDetail(
    list: HomeSongList,
    onBack: () -> Unit,
) {
    val playlist = remember(list.title, list.artworkUrl, list.songs.size) {
        NeteasePlaylistSummary(
            id = -(list.title.hashCode().toLong() and 0x7fff_ffffL) - 1L,
            name = list.title,
            coverUrl = list.artworkUrl,
            trackCount = list.songs.size,
            creatorName = list.subtitle,
        )
    }
    SharedTransitionLayout(Modifier.fillMaxSize()) {
        AnimatedContent(
            targetState = playlist,
            modifier = Modifier.fillMaxSize(),
            transitionSpec = {
                (
                    fadeIn(
                        animationSpec = tween(
                            durationMillis = MeloXMotion.ContentEnterMillis,
                            easing = FastOutSlowInEasing,
                        ),
                    ) togetherWith fadeOut(
                        animationSpec = tween(
                            durationMillis = MeloXMotion.ContentExitMillis,
                            easing = FastOutSlowInEasing,
                        ),
                    )
                ).apply { targetContentZIndex = 2f }
            },
            label = "home-song-list-detail",
        ) { target ->
            AnimatedVisibility(visible = true) {
                MeloXUnifiedSongListDetailScreen(
                    playlist = target,
                    songs = list.songs,
                    onBack = onBack,
                    sharedTransitionScope = this@SharedTransitionLayout,
                    animatedVisibilityScope = this,
                )
            }
        }
    }
}

@Composable
private fun ProviderHomeDataScreen(source: MusicSource, onOpenTool: (String) -> Unit) {
    val context = LocalContext.current.applicationContext
    val scope = rememberCoroutineScope()
    val registry = remember(source) { MeloXMusicProviders.create(context) }
    val provider = remember(source, registry) { registry.require(source) }
    val home = provider as? HomeFeedCapability
    val library = provider as? UserLibraryCapability
    var feed by remember(source) { mutableStateOf<MusicHomeFeed?>(null) }
    var account by remember(source) { mutableStateOf<MusicAccountSummary?>(null) }
    var refreshing by remember(source) { mutableStateOf(false) }
    var error by remember(source) { mutableStateOf<String?>(null) }
    var selectedCollection by remember(source) { mutableStateOf<DiscoveryCollection?>(null) }

    fun refresh() {
        if (refreshing || home == null) return
        scope.launch {
            refreshing = true
            runCatching {
                withContext(Dispatchers.IO) {
                    val feedResult = home.homeFeed(playlistLimit = 12, newSongLimit = 16, rankingLimit = 10)
                    val accountResult = runCatching { library?.accountSummary() }.getOrNull()
                    feedResult to accountResult
                }
            }.onSuccess { (feedResult, accountResult) ->
                feed = feedResult
                account = accountResult
                error = null
            }.onFailure { error = it.message ?: context.getString(R.string.home_load_failed) }
            refreshing = false
        }
    }

    LaunchedEffect(source) { refresh() }

    val blocks = feed?.let { value ->
        buildList<HomeBlock> {
            if (provider is PlaylistCapability && value.recommendedPlaylists.isNotEmpty()) {
                add(HomeBlock.Collections(context.getString(R.string.home_action_daily), source.displayName, value.recommendedPlaylists.map { DiscoveryCollection.ProviderPlaylist(it) }))
            }
            if (value.newSongs.isNotEmpty()) {
                add(HomeBlock.Tracks(context.getString(R.string.home_for_you), context.getString(R.string.home_new_songs), value.newSongs.map { DiscoveryTrack.Provider(it) }))
            }
            if (provider is RankingCapability && value.rankings.isNotEmpty()) {
                add(HomeBlock.Collections(context.getString(R.string.home_action_charts), source.displayName, value.rankings.map { DiscoveryCollection.ProviderRanking(it) }))
            }
        }
    }
    val accountUi = account?.let {
        HomeAccountUi(
            name = it.displayName,
            avatarUrl = it.avatarUrl,
            // Some providers serialize a missing subtitle as the literal string "null".
            // Keep that transport detail out of the UI and show a useful source label.
            subtitle = it.subtitle
                ?.takeUnless { value -> value.isBlank() || value.equals("null", ignoreCase = true) }
                ?: source.displayName,
        )
    }

    MeloXHomeLayout(
        source = source,
        account = accountUi,
        blocks = blocks,
        refreshing = refreshing,
        error = if (home == null) context.getString(R.string.home_unavailable, source.displayName) else error,
        onRefresh = ::refresh,
        activeAction = null,
        onQuickAction = { action ->
            when (action) {
                "听歌识曲" -> onOpenTool("Recognition")
                "私信" -> onOpenTool("Messages")
                "播客" -> onOpenTool("Podcasts")
                "下载" -> onOpenTool("Downloads")
                "云盘" -> onOpenTool("Cloud")
            }
        },
        onCollection = { collection, _ -> selectedCollection = collection },
    )
    HomeOverlayPage(
        shown = selectedCollection != null,
        onBack = { selectedCollection = null },
    ) {
        selectedCollection?.let { collection ->
            DiscoveryCollectionDetail(collection = collection, onBack = { selectedCollection = null })
        }
    }
}

@Composable
private fun MeloXHomeLayout(
    source: MusicSource,
    account: HomeAccountUi?,
    blocks: List<HomeBlock>?,
    refreshing: Boolean,
    error: String?,
    onRefresh: () -> Unit,
    activeAction: String?,
    onQuickAction: (String) -> Unit,
    onCollection: (DiscoveryCollection, String) -> Unit,
    // 当前打开的 collection key。被打开的那张卡片必须「退出」，否则
    // sharedElement 没有 enter/exit 可配对，封面不会 morph（官方范式）。
    selectedCollectionKey: String? = null,
    // 由 host 顶层 SharedTransitionLayout 传入；为空时卡片不做共享元素。
    sharedTransitionScope: SharedTransitionScope? = null,
) {
    val context = LocalContext.current.applicationContext
    PullToRefreshBox(isRefreshing = refreshing, onRefresh = onRefresh, modifier = Modifier.fillMaxSize()) {
        if (blocks == null) {
            EmptyOrLoading(refreshing, error)
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .statusBarsPadding(),
                contentPadding = PaddingValues(top = 18.dp, bottom = 146.dp),
                verticalArrangement = Arrangement.spacedBy(22.dp),
            ) {
                item {
                    MeloXIosTopBar(
                        title = stringResource(R.string.tab_home),
                        contentPadding = PaddingValues(horizontal = 20.dp),
                        actions = {
                            account?.let { HomeAccountButton(it) }
                        },
                    )
                }
                item {
                    Text(
                        text = stringResource(R.string.home_greeting),
                        modifier = Modifier.padding(horizontal = 20.dp),
                        style = MeloXTypography.headline,
                        color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.58f),
                    )
                }
                blocks.forEach { block ->
                    when (block) {
                        HomeBlock.QuickActions -> item { HomeQuickActions(source, activeAction, onQuickAction) }
                        is HomeBlock.Collections -> {
                            item { SectionTitle(block.title, block.trailing, Modifier.padding(horizontal = 20.dp)) }
                            item {
                                CollectionRow(
                                    values = block.values,
                                    slot = block.title,
                                    onSelect = onCollection,
                                    selectedCollectionKey = selectedCollectionKey,
                                    sharedTransitionScope = sharedTransitionScope,
                                )
                            }
                        }
                        is HomeBlock.Tracks -> {
                            item { SectionTitle(block.title, block.trailing, Modifier.padding(horizontal = 20.dp)) }
                            item { ThreeLineSongCarousel(block.values) { track -> playDiscoveryQueue(context, block.values, track) } }
                        }
                        is HomeBlock.Podcasts -> item {
                            LazyRow(
                                contentPadding = PaddingValues(horizontal = 20.dp),
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                            ) {
                                items(block.values, key = { "podcast-${it.programId ?: it.id}" }) { podcast ->
                                    Column(
                                        Modifier.width(150.dp).clickable {
                                            podcast.programId?.let { MeloXCollectionDetailActivity.launchPodcastProgram(context, it) }
                                                ?: MeloXCollectionDetailActivity.launchPodcast(context, podcast.id)
                                        },
                                    ) {
                                        AsyncImage(
                                            podcast.artworkUrl,
                                            null,
                                            contentScale = ContentScale.Crop,
                                            modifier = Modifier.size(150.dp).clip(RoundedCornerShape(14.dp)),
                                        )
                                        Text(
                                            podcast.name,
                                            Modifier.padding(top = 6.dp),
                                            maxLines = 2,
                                            overflow = TextOverflow.Ellipsis,
                                            fontWeight = FontWeight.SemiBold,
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
                if (blocks.isEmpty() && error == null) {
                    item { Text(stringResource(R.string.home_empty_source, source.displayName), Modifier.padding(horizontal = 20.dp), color = MaterialTheme.colorScheme.onBackground.copy(alpha = .5f)) }
                }
                error?.let { message -> item { Text(message, Modifier.padding(horizontal = 20.dp), color = MaterialTheme.colorScheme.error, fontSize = 13.sp) } }
            }
        }
    }
}

@Composable
private fun HomeAccountButton(account: HomeAccountUi) {
    val accountDescription = stringResource(R.string.accessibility_account)
    Box(
        modifier = Modifier
            .size(42.dp)
            .meloXGlassSurface(
                shape = CircleShape,
                tint = MeloXSystemColors.Red.copy(alpha = 0.12f),
                surfaceColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.18f),
            )
            .semantics {
                contentDescription = accountDescription
                role = Role.Button
            }
            .clickable(enabled = account.onClick != null, onClick = { account.onClick?.invoke() }),
        contentAlignment = Alignment.Center,
    ) {
        if (account.avatarUrl.isNullOrBlank()) {
            MeloXSymbolIcon(
                MeloXSymbol.Person,
                Modifier.size(25.dp),
                MeloXSystemColors.Red,
                MeloXSymbolVariant.Fill,
            )
        } else {
            AsyncImage(
                account.avatarUrl,
                null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize().clip(CircleShape),
            )
        }
    }
}

@Composable
private fun HomeQuickActions(source: MusicSource, active: String?, perform: (String) -> Unit) {
    data class Action(
        val title: String,
        val eyebrow: String,
        val subtitle: String,
        val symbol: MeloXSymbol,
        val colors: List<Color>,
    )
    val actions = buildList {
        if (source == MusicSource.Netease) {
            add(Action("每日推荐", stringResource(R.string.home_eyebrow_daily), stringResource(R.string.home_subtitle_daily), MeloXSymbol.Calendar, listOf(Color(0xFFFF5B8A), Color(0xFFFF3147))))
            add(Action("热歌榜", stringResource(R.string.home_eyebrow_hot), stringResource(R.string.home_subtitle_hot), MeloXSymbol.Flame, listOf(Color(0xFFFFA14A), Color(0xFFFF5A36))))
            add(Action("心动模式", stringResource(R.string.home_eyebrow_heart), stringResource(R.string.home_subtitle_heart), MeloXSymbol.Heart, listOf(Color(0xFFFF6EAC), Color(0xFF9B5DE5))))
            add(Action("私人雷达", stringResource(R.string.home_eyebrow_radar), stringResource(R.string.home_subtitle_radar), MeloXSymbol.RadioWaves, listOf(Color(0xFF6B7BFF), Color(0xFF8C52FF))))
            add(Action("私人漫游", stringResource(R.string.home_eyebrow_roam), stringResource(R.string.home_subtitle_roam), MeloXSymbol.Walk, listOf(Color(0xFF26C6DA), Color(0xFF4285F4))))
            add(Action("相似歌曲", stringResource(R.string.home_eyebrow_similar), stringResource(R.string.home_subtitle_similar), MeloXSymbol.List, listOf(Color(0xFF58C9A3), Color(0xFF159D9A))))
        }
        add(Action("听歌识曲", stringResource(R.string.home_eyebrow_recognize), stringResource(R.string.home_subtitle_recognize), MeloXSymbol.Microphone, listOf(Color(0xFF7B61FF), Color(0xFF36C5F0))))
        if (source == MusicSource.Netease) add(Action("私信", stringResource(R.string.home_eyebrow_messages), stringResource(R.string.home_subtitle_messages), MeloXSymbol.Mail, listOf(Color(0xFFFF6B8B), Color(0xFFFF3B30))))
        if (source == MusicSource.Netease && MeloXSettingsRuntime.podcastsEnabled && MeloXSettingsRuntime.podcastsHomePlacement) {
            add(Action("播客", stringResource(R.string.home_eyebrow_podcasts), stringResource(R.string.home_subtitle_podcasts), MeloXSymbol.RadioWaves, listOf(Color(0xFF8B5CF6), Color(0xFFEC4899))))
        }
        if (MeloXSettingsRuntime.downloadsEnabled && MeloXSettingsRuntime.downloadsHomePlacement) {
            add(Action("下载", stringResource(R.string.home_eyebrow_downloads), stringResource(R.string.home_subtitle_downloads), MeloXSymbol.Download, listOf(Color(0xFF0EA5E9), Color(0xFF14B8A6))))
        }
        if (source == MusicSource.Netease && MeloXSettingsRuntime.cloudMusicEnabled && MeloXSettingsRuntime.cloudHomePlacement) {
            add(Action("云盘", stringResource(R.string.home_eyebrow_cloud), stringResource(R.string.home_subtitle_cloud), MeloXSymbol.Storage, listOf(Color(0xFF64748B), Color(0xFF6366F1))))
        }
    }
    LazyRow(
        contentPadding = PaddingValues(horizontal = 20.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        items(actions, key = { it.title }) { action ->
            Column(
                Modifier
                    .width(310.dp)
                    .clickable(enabled = active == null) { perform(action.title) },
            ) {
                Text(action.eyebrow.uppercase(), color = MaterialTheme.colorScheme.onBackground.copy(alpha = .55f), fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                Text(action.title, modifier = Modifier.padding(top = 3.dp), fontSize = 21.sp, fontWeight = FontWeight.SemiBold)
                Text(action.subtitle, modifier = Modifier.padding(top = 2.dp), color = MaterialTheme.colorScheme.onBackground.copy(alpha = .52f), fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Box(
                    Modifier
                        .fillMaxWidth()
                        .aspectRatio(1.48f)
                        .padding(top = 8.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .background(Brush.linearGradient(action.colors)),
                    contentAlignment = Alignment.Center,
                ) {
                    MeloXSymbolIcon(
                        symbol = action.symbol,
                        modifier = Modifier.fillMaxSize(),
                        color = Color.White.copy(alpha = .24f),
                        iconSize = 72.sp,
                    )
                    Text(action.title, modifier = Modifier.align(Alignment.BottomStart).padding(18.dp), color = Color.White, fontSize = 25.sp, fontWeight = FontWeight.Bold)
                    if (active == action.title) {
                        CircularProgressIndicator(Modifier.size(52.dp), color = Color.White, strokeWidth = 3.dp)
                    }
                }
            }
        }
    }
}

@Composable
fun MeloXExploreScreen(source: MusicSource = MusicSource.Netease) {
    if (source == MusicSource.Netease) {
        NeteaseExploreDataScreen()
    } else {
        ProviderExploreDataScreen(source)
    }
}

@Composable
private fun NeteaseExploreDataScreen() {
    val context = LocalContext.current.applicationContext
    val cache = remember(context) { NeteaseLibraryCache(context) }
    val client = remember(context) { NeteaseLibraryClient({ NeteaseSessionStore.readCookie(context) }) }
    val scope = rememberCoroutineScope()
    val visibleCategories = Categories.filter { item ->
        (item != "精品歌单" || MeloXSettingsRuntime.showHighQualityPlaylists) &&
            (item != "播客" || MeloXSettingsRuntime.podcastsEnabled)
    }
    var category by remember { mutableStateOf(visibleCategories.first()) }
    var collections by remember { mutableStateOf<List<DiscoveryCollection>>(emptyList()) }
    var refreshing by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var selectedCollection by remember { mutableStateOf<DiscoveryCollection?>(null) }
    // 见 NeteaseHomeDataScreen：内容保留到退出过渡结束，返回时封面才能 morph 回卡片。
    var overlayCollection by remember { mutableStateOf<DiscoveryCollection?>(null) }
    // 见 NeteaseHomeDataScreen：卡片可见性同帧同步，过渡串行化由排队负责。
    var overlayTransitionBusy by remember { mutableStateOf(false) }
    var pendingCollection by remember { mutableStateOf<DiscoveryCollection?>(null) }
    var selectedArtworkSlot by remember { mutableStateOf<String?>(null) }

    fun refresh() {
        if (category == "播客" || refreshing) return
        val requested = category
        scope.launch {
            refreshing = true
            runCatching { client.explorePlaylists(requested) }
                .onSuccess {
                    if (category == requested) collections = it.map { playlist -> DiscoveryCollection.Netease(playlist) }
                    cache.saveExplore(requested, it)
                    error = null
                }
                .onFailure { error = it.message ?: context.getString(R.string.home_explore_failed) }
            refreshing = false
        }
    }

    LaunchedEffect(category) {
        if (category == "播客") return@LaunchedEffect
        collections = cache.loadExplore(category).orEmpty().map { DiscoveryCollection.Netease(it) }
        if (NeteaseLibraryCache.beginExploreColdStartRefresh(category)) refresh()
    }

    SharedTransitionLayout(Modifier.fillMaxSize()) {
        val sharedScope = this
        // 覆盖层过渡生命周期，同 NeteaseHomeDataScreen。
        LaunchedEffect(selectedCollection) {
            overlayTransitionBusy = true
            if (selectedCollection != null) {
                delay(meloXSettledMillis(MeloXMotion.PageEnterMillis, DiscoveryOverlaySettleMillis))
            } else if (overlayCollection != null) {
                delay(meloXSettledMillis(MeloXMotion.PageExitMillis, DiscoveryOverlaySettleMillis))
                selectedArtworkSlot = null
                overlayCollection = null
            }
            overlayTransitionBusy = false
            pendingCollection?.let {
                pendingCollection = null
                selectedCollection = it
                overlayCollection = it
            }
        }
        MeloXExploreLayout(
            categories = visibleCategories,
            category = category,
            onCategory = { category = it },
            collections = collections,
            refreshing = refreshing,
            error = error,
            onRefresh = ::refresh,
            showPodcast = category == "播客",
            onCollection = { collection, slot ->
                // 上一段过渡（进入/退出）没跑完就先排队，等它跑完立刻执行；直接执行会
                // 掐断旧过渡把卡片遗弃在半途，直接忽略又会丢输入。
                selectedArtworkSlot = slot
                if (overlayTransitionBusy) pendingCollection = collection
                else {
                    selectedCollection = collection
                    overlayCollection = collection
                }
            },
            // 与卡片侧 `"$slot:${collection.key}" != selectedCollectionKey` 同口径、
            // 同帧求值：点击当帧卡片就退出，返回当帧卡片就进入。
            selectedCollectionKey = selectedCollection?.let { collection ->
                selectedArtworkSlot?.let { slot -> "$slot:${collection.key}" } ?: collection.key
            },
            sharedTransitionScope = sharedScope,
        )
        HomeOverlayPage(
            shown = selectedCollection != null,
            onBack = { selectedCollection = null },
            // 封面是共享元素，跟手滑出会让封面一起飞出屏外再 morph 回来。
            followPredictiveBack = false,
        ) {
            overlayCollection?.let { collection ->
                DiscoveryCollectionDetail(
                    collection = collection,
                    onBack = { selectedCollection = null },
                    sharedTransitionScope = sharedScope,
                    animatedVisibilityScope = this,
                    artworkSharedKey = discoveryCollectionArtworkSharedKey(
                        collection,
                        selectedArtworkSlot ?: "detail",
                    ),
                )
            }
        }
    }
}

@Composable
private fun ProviderExploreDataScreen(source: MusicSource) {
    val context = LocalContext.current.applicationContext
    val scope = rememberCoroutineScope()
    val provider = remember(source) { MeloXMusicProviders.create(context).require(source) }
    val home = provider as? HomeFeedCapability
    var feed by remember(source) { mutableStateOf<MusicHomeFeed?>(null) }
    var category by remember(source) { mutableStateOf("推荐歌单") }
    var refreshing by remember(source) { mutableStateOf(false) }
    var error by remember(source) { mutableStateOf<String?>(null) }
    var selectedCollection by remember(source) { mutableStateOf<DiscoveryCollection?>(null) }

    fun refresh() {
        if (refreshing || home == null) return
        scope.launch {
            refreshing = true
            runCatching {
                withContext(Dispatchers.IO) { home.homeFeed(playlistLimit = 40, newSongLimit = 0, rankingLimit = 30) }
            }.onSuccess {
                feed = it
                error = null
                if (category == "推荐歌单" && it.recommendedPlaylists.isEmpty() && it.rankings.isNotEmpty()) category = "排行榜"
            }.onFailure { error = it.message ?: context.getString(R.string.home_explore_failed) }
            refreshing = false
        }
    }

    LaunchedEffect(source) { refresh() }

    val categories = buildList {
        add("推荐歌单")
        if (feed?.rankings?.isNotEmpty() == true) add("排行榜")
    }
    val collections = when (category) {
        "排行榜" -> feed?.rankings.orEmpty().map { DiscoveryCollection.ProviderRanking(it) }
        else -> feed?.recommendedPlaylists.orEmpty().map { DiscoveryCollection.ProviderPlaylist(it) }
    }

    MeloXExploreLayout(
        categories = categories,
        category = category,
        onCategory = { category = it },
        collections = collections,
        refreshing = refreshing,
        error = if (home == null) context.getString(R.string.home_explore_unavailable, source.displayName) else error,
        onRefresh = ::refresh,
        showPodcast = false,
        onCollection = { collection, _ -> selectedCollection = collection },
    )
    HomeOverlayPage(
        shown = selectedCollection != null,
        onBack = { selectedCollection = null },
    ) {
        selectedCollection?.let { collection ->
            DiscoveryCollectionDetail(collection = collection, onBack = { selectedCollection = null })
        }
    }
}

@Composable
private fun MeloXExploreLayout(
    categories: List<String>,
    category: String,
    onCategory: (String) -> Unit,
    collections: List<DiscoveryCollection>,
    refreshing: Boolean,
    error: String?,
    onRefresh: () -> Unit,
    showPodcast: Boolean,
    onCollection: (DiscoveryCollection, String) -> Unit,
    selectedCollectionKey: String? = null,
    sharedTransitionScope: SharedTransitionScope? = null,
) {
    Column(Modifier.fillMaxSize().statusBarsPadding().padding(top = 18.dp)) {
        MeloXIosTopBar(
            title = stringResource(R.string.tab_explore),
            // Explore is not inside a horizontally padded LazyColumn like
            // Home, so give the large title the same 20dp safe inset.
            contentPadding = PaddingValues(horizontal = 20.dp),
        )
        LazyRow(
            contentPadding = PaddingValues(horizontal = 20.dp, vertical = 14.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(categories) { item ->
                MeloXGlassButton(
                    onClick = { onCategory(item) },
                    modifier = Modifier.height(38.dp),
                    style = if (category == item) {
                        com.lladlam.melox.ui.glass.MeloXGlassButtonStyle.BorderedProminent
                    } else {
                        com.lladlam.melox.ui.glass.MeloXGlassButtonStyle.Bordered
                    },
                    tint = if (category == item) Accent else Color.Unspecified,
                    contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp),
                ) {
                    Text(
                        text = exploreCategoryLabel(item),
                        color = if (category == item) Color.White else MaterialTheme.colorScheme.onBackground,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Medium,
                    )
                }
            }
        }
        Box(modifier = Modifier.weight(1f)) {
            if (showPodcast) {
                MeloXPodcastScreen()
            } else {
                PullToRefreshBox(isRefreshing = refreshing, onRefresh = onRefresh, modifier = Modifier.fillMaxSize()) {
                    if (collections.isEmpty()) EmptyOrLoading(refreshing, error) else CollectionGrid(
                        values = collections,
                        slot = category,
                        onSelect = onCollection,
                        selectedCollectionKey = selectedCollectionKey,
                        sharedTransitionScope = sharedTransitionScope,
                    )
                }
            }
        }
    }
}

@Composable
private fun LargeTitle(text: String, modifier: Modifier = Modifier) = Text(
    text,
    modifier,
    // Match iOS largeTitle metrics instead of letting the CJK fallback
    // overpower the content below it.
    style = MeloXTypography.largeTitle,
    color = MaterialTheme.colorScheme.onBackground,
)

@Composable
private fun SectionTitle(title: String, trailing: String, modifier: Modifier = Modifier) = Row(
    modifier.fillMaxWidth(),
    horizontalArrangement = Arrangement.SpaceBetween,
) {
    Text(title, style = MeloXTypography.title2)
    Text(trailing, color = MaterialTheme.colorScheme.onBackground.copy(alpha = .42f), fontSize = 13.sp)
}

/**
 * 让「当前打开的那张卡片」退出 —— 这是 sharedElement 能配对的硬前提。
 * 其余卡片恒可见（首次组合不播动画），只有被点开的那张有 exit，
 * 封面才会从卡片位置连续地 morph 到详情 hero（官方 AnimatedVisibility 范式）。
 */
@Composable
private fun DiscoveryCardVisibility(
    collection: DiscoveryCollection,
    slot: String,
    selectedCollectionKey: String?,
    modifier: Modifier = Modifier,
    content: @Composable AnimatedVisibilityScope.() -> Unit,
) {
    AnimatedVisibility(
        visible = "$slot:${collection.key}" != selectedCollectionKey,
        enter = fadeIn() + scaleIn(),
        exit = fadeOut() + scaleOut(),
        modifier = modifier,
        label = "discovery-card-${collection.key}",
        content = content,
    )
}

@Composable
private fun CollectionRow(
    values: List<DiscoveryCollection>,
    slot: String,
    onSelect: (DiscoveryCollection, String) -> Unit,
    selectedCollectionKey: String? = null,
    sharedTransitionScope: SharedTransitionScope? = null,
) {
    LazyRow(
        contentPadding = PaddingValues(horizontal = 20.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        itemsIndexed(values, key = { _, value -> value.key }) { index, collection ->
            DiscoveryCardVisibility(
                collection = collection,
                slot = "$slot-$index",
                selectedCollectionKey = selectedCollectionKey,
            ) {
                CollectionCard(
                    value = collection,
                    modifier = Modifier.width(if (index == 0) 246.dp else 174.dp),
                    onClick = { onSelect(collection, "$slot-$index") },
                    sharedTransitionScope = sharedTransitionScope,
                    animatedVisibilityScope = this,
                    artworkSlot = "$slot-$index",
                )
            }
        }
    }
}

@Composable
private fun ThreeLineSongCarousel(values: List<DiscoveryTrack>, onSelect: (DiscoveryTrack) -> Unit) {
    val window = rememberMeloXWindowInfo()
    LazyRow(
        contentPadding = PaddingValues(horizontal = 20.dp),
        horizontalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        items(values.chunked(3), key = { group -> group.joinToString("-") { it.key } }) { group ->
            Column(
                modifier = Modifier.width(if (window.supportsTwoPane) 390.dp else 320.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                group.forEach { track -> SongRow(track) { onSelect(track) } }
            }
        }
    }
}

@Composable
private fun CollectionGrid(
    values: List<DiscoveryCollection>,
    slot: String,
    onSelect: (DiscoveryCollection, String) -> Unit,
    selectedCollectionKey: String? = null,
    sharedTransitionScope: SharedTransitionScope? = null,
) {
    val window = rememberMeloXWindowInfo()
    LazyVerticalGrid(
        columns = GridCells.Fixed(window.gridColumns),
        contentPadding = PaddingValues(start = window.gutter, end = window.gutter, bottom = 146.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        values.firstOrNull()?.let { hero ->
            item(key = "hero-${hero.key}", span = { androidx.compose.foundation.lazy.grid.GridItemSpan(maxLineSpan) }) {
                DiscoveryCardVisibility(
                    collection = hero,
                    slot = "$slot-hero",
                    selectedCollectionKey = selectedCollectionKey,
                ) {
                    HeroCollectionCard(
                        value = hero,
                        onClick = { onSelect(hero, "$slot-hero") },
                        sharedTransitionScope = sharedTransitionScope,
                        animatedVisibilityScope = this,
                        artworkSlot = "$slot-hero",
                    )
                }
            }
        }
        items(values.drop(1), key = DiscoveryCollection::key) { collection ->
            DiscoveryCardVisibility(
                collection = collection,
                slot = "$slot-${collection.key}",
                selectedCollectionKey = selectedCollectionKey,
            ) {
                CollectionCard(
                    value = collection,
                    modifier = Modifier.fillMaxWidth(),
                    onClick = { onSelect(collection, "$slot-${collection.key}") },
                    sharedTransitionScope = sharedTransitionScope,
                    animatedVisibilityScope = this,
                    artworkSlot = "$slot-${collection.key}",
                )
            }
        }
    }
}

@Composable
private fun HeroCollectionCard(
    value: DiscoveryCollection,
    onClick: () -> Unit,
    sharedTransitionScope: SharedTransitionScope? = null,
    animatedVisibilityScope: AnimatedVisibilityScope? = null,
    artworkSlot: String = "hero",
) {
    Box(
        Modifier
            .fillMaxWidth()
            .aspectRatio(if (rememberMeloXWindowInfo().supportsTwoPane) 2.9f else 1.75f)
            .clip(MeloXShapes.largeCard)
            .clickable(onClick = onClick),
    ) {
        AsyncImage(
            value.artworkUrl,
            null,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .meloXDiscoverySharedArtwork(
                    sharedTransitionScope = sharedTransitionScope,
                    animatedVisibilityScope = animatedVisibilityScope,
                    key = discoveryCollectionArtworkSharedKey(value, artworkSlot),
                )
                .fillMaxSize(),
        )
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = .82f)))))
        Column(Modifier.align(Alignment.BottomStart).padding(20.dp)) {
            Text(stringResource(R.string.home_featured_this_week), color = Color.White.copy(alpha = .72f), fontSize = 12.sp, fontWeight = FontWeight.Bold)
            Text(value.title, color = Color.White, fontSize = 26.sp, lineHeight = 30.sp, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
            (value.description ?: value.creatorName).takeIf(String::isNotBlank)?.let { Text(it, color = Color.White.copy(alpha = .72f), fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis) }
        }
    }
}

@Composable
private fun CollectionCard(
    value: DiscoveryCollection,
    modifier: Modifier,
    onClick: () -> Unit,
    sharedTransitionScope: SharedTransitionScope? = null,
    animatedVisibilityScope: AnimatedVisibilityScope? = null,
    artworkSlot: String = "card",
) {
    Column(
        modifier
            .clickable(onClick = onClick)
            .padding(8.dp),
    ) {
        val artworkShape = RoundedCornerShape(14.dp)
        // Keep the card visually complete while remote artwork is loading.
        // Without a stable surface, the fixed image slot becomes a blank hole
        // and the title appears detached from its card on slower networks.
        Box(
            modifier = Modifier
                .meloXDiscoverySharedArtwork(
                    sharedTransitionScope = sharedTransitionScope,
                    animatedVisibilityScope = animatedVisibilityScope,
                    key = discoveryCollectionArtworkSharedKey(value, artworkSlot),
                )
                .fillMaxWidth()
                .height(174.dp)
                .clip(artworkShape),
        ) {
            AsyncImage(
                value.artworkUrl,
                null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        }
        Text(
            value.title,
            modifier = Modifier.padding(top = 7.dp),
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            fontSize = 15.sp,
            lineHeight = 19.sp,
            fontWeight = FontWeight.SemiBold,
        )
        if (MeloXSettingsRuntime.showPlaylistPlayCount && value.playCount > 0L) {
            val countContext = LocalContext.current
            Text(stringResource(R.string.home_play_count, compactCount(countContext, value.playCount)), color = MaterialTheme.colorScheme.onBackground.copy(alpha = .42f), fontSize = 11.sp)
        }
    }
}

private fun discoveryCollectionArtworkSharedKey(collection: DiscoveryCollection, slot: String): String =
    "discovery-collection-artwork-$slot-${collection.key}"

/**
 * 把列表卡片封面挂到宿主顶层的 SharedTransitionLayout 上，使首页/发现页
 * 打开歌单时封面能从卡片位置一镜到底地 morph 到详情 hero。
 * key 带 [DiscoveryCollection.key] 前缀：同一个首页里不同 block 可能含
 * 同一歌单，裸 id 会在同一作用域内撞 key。
 * scope 为空（尚未接入共享元素的宿主）时原样返回，不影响常规渲染。
 */
@Composable
@OptIn(ExperimentalSharedTransitionApi::class)
private fun Modifier.meloXDiscoverySharedArtwork(
    sharedTransitionScope: SharedTransitionScope?,
    animatedVisibilityScope: AnimatedVisibilityScope?,
    key: String,
): Modifier {
    if (sharedTransitionScope == null || animatedVisibilityScope == null) return this
    return with(sharedTransitionScope) {
        this@meloXDiscoverySharedArtwork.sharedElement(
            sharedContentState = rememberSharedContentState(key = key),
            animatedVisibilityScope = animatedVisibilityScope,
            // 过渡期间封面留在共享 overlay 层，避免被列表/详情的裁剪吃掉。
            renderInOverlayDuringTransition = true,
            zIndexInOverlay = 1f,
        )
    }
}

private fun compactCount(context: android.content.Context, value: Long): String = when {
    value >= 100_000_000L -> context.getString(R.string.home_count_hundred_million, value / 100_000_000.0)
    value >= 10_000L -> context.getString(R.string.home_count_ten_thousand, value / 10_000.0)
    else -> value.toString()
}

@Composable
private fun SongRow(song: DiscoveryTrack, onClick: () -> Unit) {
    val artworkShape = RoundedCornerShape(9.dp)
    Row(
        Modifier
            .fillMaxWidth()
            .height(66.dp)
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(48.dp)
                .clip(artworkShape),
        ) {
            AsyncImage(song.artworkUrl, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(song.title, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.SemiBold)
            Text(song.artist, maxLines = 1, overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onBackground.copy(alpha = .48f), fontSize = 13.sp)
        }
    }
}

@Composable
private fun DiscoveryCollectionDetail(
    collection: DiscoveryCollection,
    onBack: () -> Unit,
    // 由首页/发现页 host 透传：与列表卡片处于同一 SharedTransitionLayout
    // 作用域、并用同一个 key，才能把封面一镜到底地 morph 到详情 hero。
    sharedTransitionScope: SharedTransitionScope? = null,
    animatedVisibilityScope: AnimatedVisibilityScope? = null,
    artworkSharedKey: String? = null,
) {
    when (collection) {
        is DiscoveryCollection.Netease -> {
            MeloXUnifiedPlaylistDetailScreen(
                playlist = collection.playlist,
                onBack = onBack,
                sharedTransitionScope = sharedTransitionScope,
                animatedVisibilityScope = animatedVisibilityScope,
                artworkSharedKey = artworkSharedKey,
            )
            return
        }
        is DiscoveryCollection.ProviderPlaylist -> {
            MeloXUnifiedPlaylistDetailScreen(
                playlist = MeloXLegacyUiBridge.playlist(collection.playlist),
                onBack = onBack,
                sharedTransitionScope = sharedTransitionScope,
                animatedVisibilityScope = animatedVisibilityScope,
                artworkSharedKey = artworkSharedKey,
            )
            return
        }
        is DiscoveryCollection.ProviderRanking -> Unit
    }
    val context = LocalContext.current.applicationContext
    var tracks by remember(collection.key) { mutableStateOf<List<DiscoveryTrack>?>(null) }
    var error by remember(collection.key) { mutableStateOf<String?>(null) }
    BackHandler(onBack = onBack)

    LaunchedEffect(collection.key) {
        runCatching {
            withContext(Dispatchers.IO) {
                val ranking = (collection as DiscoveryCollection.ProviderRanking).ranking
                val provider = MeloXMusicProviders.create(context).require(ranking.id.source)
                val capability = provider as? RankingCapability
                    ?: throw IllegalStateException(context.getString(R.string.home_ranking_unavailable, ranking.id.source.displayName))
                capability.rankingTracks(ranking, page = 1, pageSize = 150)
                    .items.map { DiscoveryTrack.Provider(it) }
            }
        }.onSuccess { tracks = it }
            .onFailure { error = it.message ?: context.getString(R.string.home_content_failed) }
    }

    // ProviderRanking is the only detail that does not delegate to
    // MeloXUnifiedPlaylistDetailScreen (which paints an opaque background), and the
    // overlay host is intentionally transparent so the gesture-back slide reveals
    // the page underneath. Without a background here the chart content renders
    // straight on top of the home/discovery page.
    LazyColumn(
        modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).statusBarsPadding(),
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 18.dp, bottom = 146.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(40.dp).clickable(onClick = onBack), contentAlignment = Alignment.Center) {
                    MeloXActionIcon("‹", Modifier.size(22.dp), MaterialTheme.colorScheme.onBackground)
                }
                Text(collection.title, fontSize = 30.sp, lineHeight = 36.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        item {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                AsyncImage(
                    collection.artworkUrl,
                    null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.size(156.dp).clip(RoundedCornerShape(22.dp)),
                )
                Column(Modifier.weight(1f)) {
                    Text(collection.title, fontSize = 20.sp, fontWeight = FontWeight.Bold, maxLines = 3, overflow = TextOverflow.Ellipsis)
                    if (collection.creatorName.isNotBlank()) {
                        Text(
                            collection.creatorName,
                            modifier = Modifier.padding(top = 7.dp),
                            color = MaterialTheme.colorScheme.onBackground.copy(alpha = .52f),
                            fontSize = 13.sp,
                        )
                    }
                    val value = tracks.orEmpty()
                    if (value.isNotEmpty()) {
                        Text(
                            stringResource(R.string.home_play_all),
                            modifier = Modifier
                                .padding(top = 15.dp)
                                .clip(RoundedCornerShape(22.dp))
                                .background(Accent)
                                .clickable { playDiscoveryQueue(context, value, value.first()) }
                                .padding(horizontal = 16.dp, vertical = 10.dp),
                            color = Color.White,
                            fontWeight = FontWeight.Bold,
                        )
                    }
                }
            }
        }
        collection.description?.takeIf(String::isNotBlank)?.let { description ->
            item { Text(description, color = MaterialTheme.colorScheme.onBackground.copy(alpha = .55f), fontSize = 13.sp, lineHeight = 19.sp) }
        }
        val value = tracks
        when {
            value != null -> items(value, key = DiscoveryTrack::key) { song ->
                SongRow(song) { playDiscoveryQueue(context, value, song) }
            }
            error != null -> item { Text(error.orEmpty(), color = MaterialTheme.colorScheme.error) }
            else -> item {
                Box(Modifier.fillMaxWidth().height(180.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = Accent)
                }
            }
        }
    }
}

private fun playDiscoveryQueue(
    context: android.content.Context,
    tracks: List<DiscoveryTrack>,
    selected: DiscoveryTrack,
) {
    when (selected) {
        is DiscoveryTrack.Netease -> {
            val songs = tracks.mapNotNull { (it as? DiscoveryTrack.Netease)?.song }
            PlaybackCommands.playQueue(context, songs, selected.song.id)
        }
        is DiscoveryTrack.Provider -> {
            val providerTracks = tracks.mapNotNull { (it as? DiscoveryTrack.Provider)?.track }
            ProviderPlaybackCommands.playQueue(context, providerTracks, selected.track.id)
        }
    }
}

@Composable
private fun EmptyOrLoading(loading: Boolean, error: String?) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        if (loading) CircularProgressIndicator(color = Accent)
        else Text(error ?: stringResource(R.string.melox_state_empty), color = MaterialTheme.colorScheme.onBackground.copy(alpha = .5f))
    }
}
