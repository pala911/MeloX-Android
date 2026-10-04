package com.lladlam.melox.ui.settings

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.StatFs
import android.provider.Settings as AndroidSettings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.PredictiveBackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.Animatable
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lladlam.melox.ui.animation.meloXPageEnter
import com.lladlam.melox.ui.animation.meloXPageExit
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.zIndex
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.core.content.ContextCompat
import androidx.core.app.NotificationCompat
import coil3.compose.AsyncImage
import com.lladlam.melox.R
import com.lladlam.melox.BuildConfig
import com.lladlam.melox.core.account.NeteaseSessionStore
import com.lladlam.melox.core.diagnostics.MeloXLogExporter
import com.lladlam.melox.core.diagnostics.MeloXLogDeviceInfo
import com.lladlam.melox.core.provider.bilibili.BilibiliPlaybackAssociationStore
import com.lladlam.melox.core.music.provider.PlaybackAccountSlot
import com.lladlam.melox.core.music.provider.PlaybackAccountStore
import com.lladlam.melox.core.music.model.MusicSource
import com.lladlam.melox.core.music.model.MusicPlaylistSummary
import com.lladlam.melox.core.music.model.MusicResourceId
import com.lladlam.melox.core.music.provider.MeloXMusicProviders
import com.lladlam.melox.core.music.provider.UserLibraryCapability
import com.lladlam.melox.core.library.NeteaseLibraryClient
import com.lladlam.melox.core.provider.qqmusic.QQMusicSessionStore
import com.lladlam.melox.core.provider.kugou.KugouSessionStore
import com.lladlam.melox.ui.account.QQMusicLoginScreen
import com.lladlam.melox.ui.account.KugouLoginScreen
import com.lladlam.melox.ui.account.NeteaseLoginScreen
import com.lladlam.melox.playback.ProviderPlaybackQualityRuntime
import com.lladlam.melox.playback.CrossProviderPlaybackPreferences
import com.lladlam.melox.core.audio.MusicQuality
import com.lladlam.melox.core.audio.MusicQualityPreferences
import com.lladlam.melox.core.download.MeloXDownloadStore
import com.lladlam.melox.core.network.MeloXMessageContact
import com.lladlam.melox.core.network.MeloXPrivateMessage
import com.lladlam.melox.core.network.NeteaseMusicOperationsClient
import com.lladlam.melox.core.network.NeteaseSearchClient
import com.lladlam.melox.core.network.MeloXHttpClient
import com.lladlam.melox.core.network.MeloXGitHubRouting
import com.lladlam.melox.core.network.MeloXGitHubSource
import com.lladlam.melox.core.network.parseNeteaseListenTogetherInvitation
import com.lladlam.melox.core.recommendation.LocalAnalysisStage
import com.lladlam.melox.core.recommendation.LocalRecommendationEngine
import com.lladlam.melox.core.recommendation.LocalRecommendationStore
import com.lladlam.melox.core.recognition.SongRecognitionClient
import com.lladlam.melox.core.recognition.SongRecognitionResult
import com.lladlam.melox.core.update.MeloXRelease
import com.lladlam.melox.core.update.MeloXDevCommit
import com.lladlam.melox.core.update.MeloXUpdateClient
import com.lladlam.melox.playback.PlaybackCommands
import com.lladlam.melox.playback.MeloXAutoMixFadeCurve
import com.lladlam.melox.playback.MeloXAutoMixDiagnostics
import com.lladlam.melox.playback.MeloXAutoMixFallback
import com.lladlam.melox.playback.MeloXAutoMixMode
import com.lladlam.melox.playback.MeloXAutoMixSettings
import com.lladlam.melox.playback.MeloXEqualizerController
import com.lladlam.melox.playback.MeloXPlaybackModePreferences
import com.lladlam.melox.playback.MeloXAudioAnalysisPreferences
import com.lladlam.melox.playback.MeloXPlaybackService
import com.lladlam.melox.playback.MeloXMediaCache
import com.lladlam.melox.playback.MeloXListenTogetherCoordinator
import com.lladlam.melox.platform.floating.MeloXFloatingLyricsService
import com.lladlam.melox.platform.xiaomi.HyperOsFocusBridge
import com.lladlam.melox.ui.MeloXBottomContentClearance
import com.lladlam.melox.ui.MeloXPinkCat
import com.lladlam.melox.ui.glass.MeloXActionIcon
import com.lladlam.melox.ui.glass.MeloXGlassTextField
import com.lladlam.melox.ui.glass.MeloXGlassDialog
import com.lladlam.melox.ui.glass.MeloXGlassButton
import com.lladlam.melox.ui.glass.MeloXGlassButtonStyle
import com.lladlam.melox.ui.glass.MeloXGlassToggle
import com.lladlam.melox.ui.glass.MeloXLiquidSlider
import com.lladlam.melox.ui.glass.MeloXSettingsDropdown
import com.lladlam.melox.ui.glass.MeloXShapes
import com.lladlam.melox.ui.glass.MeloXTypography
import com.lladlam.melox.ui.glass.MeloXIosGroupedList
import com.lladlam.melox.ui.glass.MeloXIosListRow
import com.lladlam.melox.ui.glass.MeloXIosTopBar
import com.lladlam.melox.ui.glass.MeloXPinnedListPage
import com.lladlam.melox.ui.glass.MeloXSymbol
import com.lladlam.melox.ui.glass.MeloXSymbolIcon
import com.lladlam.melox.ui.glass.MeloXSymbolVariant
import com.lladlam.melox.ui.glass.MeloXSystemColors
import com.lladlam.melox.ui.glass.meloXContentSurface
import com.lladlam.melox.ui.glass.meloXLiquidButton
import com.lladlam.melox.ui.legal.MELOX_LEGAL_VERSION
import com.lladlam.melox.ui.legal.MeloXLegalDocument
import com.lladlam.melox.ui.legal.MeloXLegalDocumentDialog
import com.lladlam.melox.ui.legal.MeloXLegalLinks
import com.lladlam.melox.core.remoteconfig.MeloXRemoteConfigRuntime
import com.lladlam.melox.core.remoteconfig.MeloXRemoteConfigSource
import com.lladlam.melox.core.remoteconfig.MeloXRemoteConfigConsent
import com.lladlam.melox.ui.legal.MeloXCloudControlConsentDialog
import java.text.DateFormat
import java.util.Date
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.CancellationException

private enum class SettingsRoute(val titleRes: Int) {
    Playback(R.string.settings_route_playback),
    PlayerAppearance(R.string.settings_route_appearance),
    Lyrics(R.string.settings_route_lyrics),
    SystemPlayback(R.string.settings_route_system_playback),
    SkylineLyrics(R.string.settings_route_skyline),
    FloatingLyrics(R.string.settings_route_floating),
    ContentFeatures(R.string.settings_route_features),
    Recognition(R.string.settings_route_recognition),
    Messages(R.string.settings_route_messages),
    ListenTogether(R.string.settings_route_listen_together),
    Content(R.string.settings_route_content),
    Storage(R.string.settings_route_storage),
    TabLayout(R.string.settings_route_tabs),
    General(R.string.settings_route_general),
    RemoteConfig(R.string.settings_route_remote_config),
    About(R.string.settings_route_about),
    Legal(R.string.settings_route_legal),
    Privacy(R.string.settings_route_privacy),
    Developer(R.string.settings_route_developer),
    Experimental(R.string.settings_route_experimental),
}

private data class SettingsItem(
    val route: SettingsRoute,
    val subtitleRes: Int,
    val symbol: String,
    val keywordsRes: Int,
)

private data class SettingsSection(val titleRes: Int, val items: List<SettingsItem>)

private val SettingsSections = listOf(
    SettingsSection(R.string.settings_section_app, listOf(
        SettingsItem(SettingsRoute.General, R.string.settings_sub_general, "⚙", R.string.settings_kw_general),
        SettingsItem(SettingsRoute.PlayerAppearance, R.string.settings_sub_appearance, "✦", R.string.settings_kw_appearance),
        SettingsItem(SettingsRoute.Content, R.string.settings_sub_content, "▦", R.string.settings_kw_content),
        SettingsItem(SettingsRoute.Playback, R.string.settings_sub_playback, "♫", R.string.settings_kw_playback),
        SettingsItem(SettingsRoute.Lyrics, R.string.settings_sub_lyrics, "❞", R.string.settings_kw_lyrics),
        SettingsItem(SettingsRoute.Storage, R.string.settings_sub_storage, "▰", R.string.settings_kw_storage),
    )),
    SettingsSection(R.string.settings_section_more, listOf(
        SettingsItem(SettingsRoute.ContentFeatures, R.string.settings_sub_features, "☷", R.string.settings_kw_features),
        SettingsItem(SettingsRoute.Recognition, R.string.settings_sub_recognition, "⌁", R.string.settings_kw_recognition),
        SettingsItem(SettingsRoute.Messages, R.string.settings_sub_messages, "✉", R.string.settings_kw_messages),
        SettingsItem(SettingsRoute.ListenTogether, R.string.settings_sub_listen_together, "◎", R.string.settings_kw_listen_together),
        SettingsItem(SettingsRoute.TabLayout, R.string.settings_sub_tabs, "▥", R.string.settings_kw_tabs),
        SettingsItem(SettingsRoute.SystemPlayback, R.string.settings_sub_system_playback, "▣", R.string.settings_kw_system_playback),
        SettingsItem(SettingsRoute.SkylineLyrics, R.string.settings_sub_skyline, "▱", R.string.settings_kw_skyline),
        SettingsItem(SettingsRoute.FloatingLyrics, R.string.settings_sub_floating, "▤", R.string.settings_kw_floating),
    )),
    SettingsSection(R.string.settings_section_about, listOf(
        SettingsItem(SettingsRoute.RemoteConfig, R.string.settings_sub_remote_config, "⌁", R.string.settings_kw_remote_config),
        SettingsItem(SettingsRoute.About, R.string.settings_sub_about, "ⓘ", R.string.settings_kw_about),
        SettingsItem(SettingsRoute.Legal, R.string.settings_sub_legal, "▤", R.string.settings_kw_legal),
        SettingsItem(SettingsRoute.Developer, R.string.settings_sub_developer, "⌘", R.string.settings_kw_developer),
        SettingsItem(SettingsRoute.Experimental, R.string.settings_sub_experimental, "✦", R.string.settings_kw_experimental),
    )),
)

@Composable
fun SettingsScreen(
    session: NeteaseSessionStore,
    source: MusicSource = MusicSource.Netease,
    onLogin: () -> Unit,
    onOpenAccount: (() -> Unit)? = null,
    onOpenServices: (() -> Unit)? = null,
    onOpenMessages: (() -> Unit)? = null,
    initialRouteRequest: String? = null,
    onInitialRouteConsumed: () -> Unit = {},
) {
    val context = LocalContext.current
    var route by remember { mutableStateOf<SettingsRoute?>(null) }
    // Retain the detail content while its exit animation runs. `route?.let {}` would
    // blank the page the moment Back is pressed, so the slide-out animated an empty box
    // (gesture back only looked fine because it drives backProgress before closing).
    var detailRoute by remember { mutableStateOf<SettingsRoute?>(null) }
    if (route != null) detailRoute = route
    val backProgress = remember { Animatable(0f) }
    var search by remember { mutableStateOf("") }
    // Keep the root ScrollState alive while a detail route is displayed.
    // Creating it inside the root-only branch reset Settings to y=0 on Back.
    val rootScrollState = rememberScrollState()

    LaunchedEffect(Unit) { MeloXSettingsPreferences.initialize(context) }
    LaunchedEffect(Unit) {
        if (LocalRecommendationStore.isAlgorithmEnabled(context) && LocalRecommendationStore.hasPersonalizationConsent(context)) {
            LocalRecommendationEngine.start(context)
        }
    }
    LaunchedEffect(initialRouteRequest) {
        initialRouteRequest?.let { requested ->
            route = SettingsRoute.entries.firstOrNull { it.name == requested }
            onInitialRouteConsumed()
        }
    }
    LaunchedEffect(session.cookie) {
        if (session.isLoggedIn) session.refreshProfile()
    }

    PredictiveBackHandler(enabled = route != null) {
        try {
            it.collect { event -> backProgress.snapTo(event.progress) }
            // Close first, then finish the slide: the exit transition starts from the
            // gesture position while the remaining offset plays out. Resetting the
            // progress after closing would snap the already off-screen page back to
            // the center for one exit-animation pass - a visible "reappear" on
            // gesture back (same fix as the search detail page).
            route = null
            backProgress.animateTo(1f, tween(160))
        } catch (_: CancellationException) {
            backProgress.animateTo(0f)
        }
    }
    // Gesture back leaves the offset at 1 (page fully off-screen). Reset it on the
    // next open, otherwise the new detail page enters still shifted off-screen.
    LaunchedEffect(route) { if (route != null) backProgress.snapTo(0f) }

    AnimatedVisibility(
        visible = route != null,
        enter = meloXPageEnter(fromRight = true),
        exit = meloXPageExit(toRight = true),
        modifier = Modifier.fillMaxSize().zIndex(1f),
    ) {
        detailRoute?.let { selectedRoute ->
            Box(
                Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        translationX = size.width * backProgress.value
                        val scale = 1f - 0.08f * backProgress.value
                        scaleX = scale
                        scaleY = scale
                        transformOrigin = TransformOrigin(0f, 0.5f)
                    },
            ) {
                SettingsDetailScreen(route = selectedRoute, source = source, session = session, onBack = { route = null })
            }
        }
    }
    AnimatedVisibility(
        // Keep the destination underneath the detail page so predictive back
        // reveals real Settings content instead of the Scaffold background.
        visible = true,
        enter = meloXPageEnter(fromRight = false),
        exit = meloXPageExit(toRight = false),
        modifier = Modifier
            .fillMaxSize()
            .zIndex(0f)
            .then(if (route != null) Modifier.clearAndSetSemantics { } else Modifier),
    ) {
    val normalized = search.trim().lowercase()
    val visibleSections = buildList {
        for (section in SettingsSections) {
            val filtered = buildList {
                for (item in section.items) {
                    val haystack = listOf(
                        stringResource(item.route.titleRes),
                        stringResource(item.subtitleRes),
                        stringResource(item.keywordsRes),
                    ).joinToString(" ").lowercase()
                    if (normalized.isBlank() || haystack.contains(normalized)) add(item)
                }
            }
            if (filtered.isNotEmpty()) add(SettingsSection(section.titleRes, filtered))
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .verticalScroll(rootScrollState)
            .padding(horizontal = 20.dp)
            .padding(top = 18.dp, bottom = MeloXBottomContentClearance),
    ) {
        MeloXIosTopBar(
            title = stringResource(R.string.tab_settings),
            modifier = Modifier.padding(horizontal = 0.dp),
            contentPadding = PaddingValues(horizontal = 0.dp),
        )
        Spacer(Modifier.height(14.dp))
        SettingsSearchField(value = search, onValueChange = { search = it })
        Spacer(Modifier.height(20.dp))

        if (normalized.isBlank() || stringResource(R.string.settings_account_keywords).lowercase().contains(normalized)) {
            SettingsAccountCard(
                session = session,
                onLogin = onLogin,
                onOpenAccount = onOpenAccount,
                onOpenServices = onOpenServices,
            )
            Spacer(Modifier.height(24.dp))
        }

        visibleSections.forEach { section ->
            Text(
                stringResource(section.titleRes),
                modifier = Modifier.padding(start = 8.dp, bottom = 8.dp, top = 8.dp),
                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.48f),
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
            )
            SettingsSectionCard(section) { selected ->
                if (selected == SettingsRoute.Messages && onOpenMessages != null) {
                    onOpenMessages()
                } else {
                    route = selected
                }
            }
            Spacer(Modifier.height(22.dp))
        }

        if (visibleSections.isEmpty() && normalized.isNotBlank()) {
            Text(
                stringResource(R.string.settings_search_empty),
                modifier = Modifier.padding(vertical = 36.dp),
                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.48f),
            )
        }

        if (normalized.isBlank()) {
            SettingsResetCard()
            Spacer(Modifier.height(18.dp))
            if (session.isLoggedIn) {
                SettingsDangerButton(stringResource(R.string.settings_sign_out)) { session.clear() }
            }
        }
    }
    }
}

@Composable
private fun SettingsSearchField(value: String, onValueChange: (String) -> Unit) {
    MeloXGlassTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = Modifier.fillMaxWidth(),
        leadingContent = {
            MeloXSymbolIcon(
                symbol = MeloXSymbol.Search,
                modifier = Modifier.size(20.dp),
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = .55f),
            )
        },
        placeholder = { Text(stringResource(R.string.settings_search_placeholder), color = MaterialTheme.colorScheme.onSurface.copy(alpha = .42f), fontSize = 16.sp) },
        textStyle = androidx.compose.ui.text.TextStyle(
            color = MaterialTheme.colorScheme.onSurface,
            fontSize = 16.sp,
            lineHeight = 21.sp,
        ),
    )
}

@Composable
private fun SettingsAccountCard(
    session: NeteaseSessionStore,
    onLogin: () -> Unit,
    onOpenAccount: (() -> Unit)?,
    onOpenServices: (() -> Unit)?,
) {
    val accent = com.lladlam.melox.ui.glass.MeloXSystemColors.Red
    Text(
        stringResource(R.string.accessibility_account),
        modifier = Modifier.padding(start = 8.dp, bottom = 8.dp),
        fontSize = 13.sp,
        color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.48f),
    )
    MeloXIosGroupedList(surfaceColor = MaterialTheme.colorScheme.surface) {
        when {
            session.isLoggedIn && session.profile != null -> {
                val profile = session.profile!!
                MeloXIosListRow(
                    title = profile.nickname,
                    leading = { AsyncImage(model = profile.avatarUrl, contentDescription = null, modifier = Modifier.size(30.dp).clip(CircleShape)) },
                    detail = stringResource(R.string.provider_logged_in),
                    chevronTint = accent,
                     onClick = onOpenAccount ?: onLogin,
                    showTopSeparator = false,
                )
            }
            session.isLoggedIn && session.isRefreshing -> MeloXIosListRow(
                title = stringResource(R.string.settings_account_loading),
                leading = { CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp, color = accent) },
                onClick = onOpenServices,
                showTopSeparator = false,
            )
            else -> MeloXIosListRow(
                title = stringResource(R.string.app_login_netease),
                leading = { MeloXSymbolIcon(MeloXSymbol.Person, Modifier.size(30.dp), accent, MeloXSymbolVariant.Fill) },
                chevronTint = accent,
                 onClick = onOpenAccount ?: onLogin,
                showTopSeparator = false,
            )
        }
        if (onOpenServices != null) {
            MeloXIosListRow(
                title = stringResource(R.string.tab_services),
                subtitle = stringResource(R.string.settings_services_subtitle),
                leading = { MeloXSymbolIcon(MeloXSymbol.MusicNote, Modifier.size(30.dp), accent) },
                chevronTint = accent,
                onClick = onOpenServices,
                showTopSeparator = true,
            )
        }
    }
}

@Composable
private fun SettingsSectionCard(section: SettingsSection, onOpen: (SettingsRoute) -> Unit) {
    val accent = com.lladlam.melox.ui.glass.MeloXSystemColors.Red
    MeloXIosGroupedList(surfaceColor = MaterialTheme.colorScheme.surface) {
        section.items.forEach { item ->
            MeloXIosListRow(
                title = stringResource(item.route.titleRes),
                leading = {
                    Box(Modifier.size(28.dp), contentAlignment = Alignment.Center) {
                        MeloXActionIcon(item.symbol, Modifier.size(22.dp), accent)
                    }
                },
                chevronTint = accent,
                onClick = { onOpen(item.route) },
                showTopSeparator = item != section.items.first(),
            )
        }
    }
}

@Composable
private fun SettingsDetailScreen(route: SettingsRoute, source: MusicSource, session: NeteaseSessionStore, onBack: () -> Unit) {
    val context = LocalContext.current
    MeloXPinnedListPage(
        title = stringResource(route.titleRes),
        onNavigateBack = onBack,
        bottomPadding = MeloXBottomContentClearance,
    ) {
        item(key = "settings-detail:${route.name}") {
            Column {
                when (route) {
                    SettingsRoute.Playback -> PlaybackSettings(context)
                    SettingsRoute.PlayerAppearance -> PlayerAppearanceSettings(context)
                    SettingsRoute.Lyrics -> LyricsSettings(context)
                    SettingsRoute.SystemPlayback -> SystemPlaybackSettings(context)
                    SettingsRoute.SkylineLyrics -> SkylineLyricsSettings(context)
                    SettingsRoute.FloatingLyrics -> FloatingLyricsSettings(context)
                    SettingsRoute.ContentFeatures -> ContentFeatureSettings(context)
                    SettingsRoute.Recognition -> RecognitionSettings(context)
                    SettingsRoute.Messages -> MessagesSettings(context)
                    SettingsRoute.ListenTogether -> ListenTogetherSettings(context)
                    SettingsRoute.Content -> ContentSettings(context)
                    SettingsRoute.Storage -> StorageSettings(context)
                    SettingsRoute.TabLayout -> TabLayoutSettings(context)
                    SettingsRoute.General -> GeneralSettings(context)
                    SettingsRoute.RemoteConfig -> RemoteConfigSettings()
                    SettingsRoute.About -> AboutSettings(context)
                    SettingsRoute.Legal -> LegalSettings(context)
                    SettingsRoute.Privacy -> PrivacySettings(context)
                    SettingsRoute.Developer -> DeveloperSettings()
                    SettingsRoute.Experimental -> ExperimentalSettings(context, source, session)
                }
            }
        }
    }
}

@Composable
private fun LegalSettings(context: android.content.Context) {
    var selectedDocument by remember { mutableStateOf<MeloXLegalDocument?>(null) }
    var cloudControlEnabled by remember { mutableStateOf(MeloXRemoteConfigConsent.enabled(context)) }
    var showCloudControlConsent by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val consentVersion = remember {
        MeloXSettingsPreferences.string(context, "legal_consent_version")
    }

    SettingsGlassGroup {
        Column(Modifier.padding(16.dp)) {
            Text(stringResource(R.string.settings_legal_documents), fontSize = 19.sp, fontWeight = FontWeight.SemiBold)
            Text(
                text = stringResource(R.string.settings_legal_version, MELOX_LEGAL_VERSION),
                modifier = Modifier.padding(top = 7.dp),
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = .62f),
                fontSize = 13.sp,
                lineHeight = 19.sp,
            )
            Text(
                text = if (consentVersion.isBlank()) {
                    stringResource(R.string.settings_legal_no_consent)
                } else {
                    stringResource(R.string.settings_legal_consented, consentVersion)
                },
                modifier = Modifier.padding(top = 3.dp),
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = .52f),
                fontSize = 12.sp,
                lineHeight = 18.sp,
            )
        }
    }
    Spacer(Modifier.height(10.dp))
    MeloXIosGroupedList(surfaceColor = MaterialTheme.colorScheme.surface) {
        MeloXIosListRow(
            title = stringResource(R.string.legal_privacy),
            leading = {
                MeloXSymbolIcon(
                    MeloXSymbol.Info,
                    Modifier.size(24.dp),
                    MeloXSystemColors.Blue,
                )
            },
            onClick = { selectedDocument = MeloXLegalDocument.PrivacyPolicy },
            showTopSeparator = false,
        )
        MeloXIosListRow(
            title = stringResource(R.string.settings_legal_disclaimer),
            leading = {
                MeloXSymbolIcon(
                    MeloXSymbol.Book,
                    Modifier.size(24.dp),
                    MeloXSystemColors.Blue,
                )
            },
            onClick = { selectedDocument = MeloXLegalDocument.Disclaimer },
            showTopSeparator = true,
        )
        MeloXIosListRow(
            title = stringResource(R.string.legal_cloud),
            leading = {
                MeloXSymbolIcon(
                    MeloXSymbol.Info,
                    Modifier.size(24.dp),
                    MeloXSystemColors.Blue,
                )
            },
            onClick = { selectedDocument = MeloXLegalDocument.CloudControlPrivacy },
            showTopSeparator = true,
        )
        MeloXIosListRow(
            title = stringResource(R.string.settings_legal_third_party),
            leading = {
                MeloXSymbolIcon(
                    MeloXSymbol.Book,
                    Modifier.size(24.dp),
                    MeloXSystemColors.Blue,
                )
            },
            onClick = { selectedDocument = MeloXLegalDocument.ThirdPartyMusicSources },
            showTopSeparator = true,
        )
    }
    Spacer(Modifier.height(10.dp))
    SettingsGlassGroup {
        SettingsExternalToggleRow(
            title = stringResource(R.string.settings_legal_remote_toggle),
            value = cloudControlEnabled,
            note = stringResource(R.string.settings_legal_remote_note),
            grouped = true,
        ) { requested ->
            if (requested) {
                showCloudControlConsent = true
            } else {
                MeloXRemoteConfigConsent.reject(context)
                cloudControlEnabled = false
                scope.launch { MeloXRemoteConfigRuntime.clearCache(context) }
            }
        }
    }
    Text(
        stringResource(R.string.settings_legal_remote_footer),
        modifier = Modifier.padding(horizontal = 6.dp, vertical = 7.dp),
        fontSize = 12.sp,
        lineHeight = 17.sp,
        color = MaterialTheme.colorScheme.onSurface.copy(alpha = .5f),
    )

    selectedDocument?.let { document ->
        MeloXLegalDocumentDialog(
            document = document,
            onDismiss = { selectedDocument = null },
        )
    }
    if (showCloudControlConsent) {
        MeloXCloudControlConsentDialog(
            onReject = { showCloudControlConsent = false },
            onAccept = {
                MeloXRemoteConfigConsent.accept(context)
                MeloXRemoteConfigRuntime.initializeAndRefresh(context, BuildConfig.VERSION_CODE, force = true)
                cloudControlEnabled = true
                showCloudControlConsent = false
            },
        )
    }
}

@Composable
private fun PrivacySettings(context: android.content.Context) {
    var algorithmEnabled by remember { mutableStateOf(LocalRecommendationStore.isAlgorithmEnabled(context)) }
    var consent by remember { mutableStateOf(LocalRecommendationStore.hasPersonalizationConsent(context)) }
    var showPolicy by remember { mutableStateOf(false) }
    var showClearPersonalization by remember { mutableStateOf(false) }
    var secondsLeft by remember { mutableIntStateOf(10) }
    val progress by LocalRecommendationStore.progress

    LaunchedEffect(showPolicy) {
        if (!showPolicy) return@LaunchedEffect
        secondsLeft = 10
        while (secondsLeft > 0) { kotlinx.coroutines.delay(1_000L); secondsLeft-- }
    }

    SettingsGlassGroup {
        Column(Modifier.padding(16.dp)) {
            Text(stringResource(R.string.settings_privacy_data_title), fontSize = 19.sp, fontWeight = FontWeight.SemiBold)
            Text(stringResource(R.string.settings_privacy_data_body), modifier = Modifier.padding(top = 8.dp), color = MaterialTheme.colorScheme.onSurface.copy(alpha = .62f), fontSize = 13.sp, lineHeight = 19.sp)
        }
    }
    Spacer(Modifier.height(10.dp))
    SettingsGlassGroup {
        SettingsExternalToggleRow(stringResource(R.string.settings_privacy_algorithm), algorithmEnabled, stringResource(R.string.settings_privacy_algorithm_note)) {
            algorithmEnabled = it
            LocalRecommendationStore.setAlgorithmEnabled(context, it)
            if (it && consent) LocalRecommendationEngine.start(context) else if (!it) LocalRecommendationEngine.stop()
        }
    }
    Spacer(Modifier.height(10.dp))
    SettingsGlassGroup {
        Text(stringResource(R.string.settings_privacy_personalization), modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp), fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
        Text(if (consent) stringResource(R.string.settings_privacy_consented) else stringResource(R.string.settings_privacy_not_consented), modifier = Modifier.padding(horizontal = 16.dp).padding(bottom = 10.dp), fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = .58f))
        SettingsActionButton(if (consent) stringResource(R.string.settings_privacy_withdraw) else stringResource(R.string.settings_privacy_read_policy)) {
            if (consent) {
                consent = false
                LocalRecommendationStore.clearPersonalization(context)
                LocalRecommendationEngine.stop()
            } else showPolicy = true
        }
        SettingsActionButton(stringResource(R.string.settings_privacy_clear_all)) { showClearPersonalization = true }
    }
    Spacer(Modifier.height(10.dp))
    SettingsGlassGroup {
        Column(Modifier.padding(16.dp)) {
            Text(stringResource(R.string.settings_privacy_status), fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
            Text(
                if (progress.isFullAnalysis) stringResource(R.string.settings_privacy_full_analysis, progress.stage, progress.processed, progress.total)
                else stringResource(R.string.settings_privacy_quick_update, progress.stage),
                modifier = Modifier.padding(top = 8.dp),
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = .62f),
            )
            Text(stringResource(R.string.settings_privacy_backend, progress.modelBackend), modifier = Modifier.padding(top = 4.dp), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = .52f))
            progress.message?.let { Text(it, modifier = Modifier.padding(top = 5.dp), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = .52f)) }
        }
    }
    SettingsActionButton(stringResource(R.string.settings_privacy_start_full)) {
        if (algorithmEnabled && consent) LocalRecommendationEngine.startFullAnalysis(context)
    }
    if (showPolicy) {
        MeloXGlassDialog(visible = true, onDismiss = { if (secondsLeft == 0) showPolicy = false }) {
            Text(stringResource(R.string.settings_privacy_policy_title), style = MaterialTheme.typography.titleMedium)
            Text(stringResource(R.string.settings_privacy_policy_body), modifier = Modifier.padding(top = 10.dp), fontSize = 13.sp, lineHeight = 19.sp)
            Spacer(Modifier.height(16.dp))
            SettingsActionButton(if (secondsLeft > 0) stringResource(R.string.settings_privacy_wait, secondsLeft) else stringResource(R.string.legal_agree_enable)) {
                if (secondsLeft == 0) {
                    consent = true; showPolicy = false
                    LocalRecommendationStore.setPersonalizationConsent(context, true)
                    LocalRecommendationStore.setConsentAt(context, System.currentTimeMillis())
                    if (algorithmEnabled) LocalRecommendationEngine.start(context)
                }
            }
        }
    }
    if (showClearPersonalization) {
        MeloXGlassDialog(visible = true, onDismiss = { showClearPersonalization = false }) {
            Text(stringResource(R.string.settings_privacy_clear_title), style = MaterialTheme.typography.titleMedium)
            Text(stringResource(R.string.settings_privacy_clear_body), modifier = Modifier.padding(top = 10.dp), fontSize = 13.sp, lineHeight = 19.sp)
            Row(Modifier.fillMaxWidth().padding(top = 18.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                SettingsActionButton(stringResource(R.string.action_cancel), Modifier.weight(1f)) { showClearPersonalization = false }
                SettingsDangerButton(stringResource(R.string.settings_privacy_clear_confirm), Modifier.weight(1f)) {
                    LocalRecommendationEngine.stop()
                    LocalRecommendationStore.clearPersonalization(context)
                    consent = false
                    showClearPersonalization = false
                }
            }
        }
    }
}

@Composable
private fun SystemPlaybackSettings(context: android.content.Context) {
    var systemLyrics by remember { mutableStateOf(MeloXSettingsRuntime.systemLyricsEnabled) }
    var notifications by remember { mutableStateOf(MeloXSettingsRuntime.lyricNotificationsEnabled) }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        notifications = granted
        MeloXSettingsPreferences.setBoolean(context, "lyrics_notifications_enabled", granted)
    }

    val protocol = remember { HyperOsFocusBridge.protocol(context) }
    SettingsGlassGroup {
        SettingsExternalToggleRow(
            title = stringResource(R.string.settings_system_lyrics),
            value = systemLyrics,
            note = stringResource(R.string.settings_system_lyrics_note),
            grouped = true,
        ) {
            systemLyrics = it
            MeloXSettingsPreferences.setBoolean(context, "system_lyrics_enabled", it)
        }
        MeloXSettingsDropdown(
            title = stringResource(R.string.settings_system_title_format),
            selected = MeloXSettingsRuntime.systemLyricTitleMode,
            items = listOf(
                MeloXSystemLyricTitleMode.LyricFirst to stringResource(R.string.settings_system_title_lyric),
                MeloXSystemLyricTitleMode.SongFirst to stringResource(R.string.settings_system_title_song),
            ),
            onSelected = { MeloXSettingsPreferences.setString(context, "system_lyrics_title_mode", it.name) },
            grouped = true,
        )
        SettingsExternalToggleRow(
            title = stringResource(R.string.settings_lyric_notification),
            value = notifications,
            note = stringResource(R.string.settings_lyric_notification_note),
            grouped = true,
        ) { enabled ->
            if (enabled && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
            ) {
                permissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            } else {
                notifications = enabled
                MeloXSettingsPreferences.setBoolean(context, "lyrics_notifications_enabled", enabled)
            }
        }
        val hyperIsland = remember { mutableStateOf(MeloXSettingsPreferences.boolean(context, "hyperos_super_island_enabled", false)) }
        SettingsExternalToggleRow(
            title = stringResource(R.string.settings_hyperos_island),
            value = hyperIsland.value,
            note = stringResource(R.string.settings_hyperos_island_note),
            grouped = true,
        ) { enabled ->
            hyperIsland.value = enabled
            MeloXSettingsPreferences.setBoolean(context, "hyperos_super_island_enabled", enabled)
        }
    }
    if (protocol == HyperOsFocusBridge.Protocol.HyperOs3) {
        Spacer(Modifier.height(10.dp))
        SettingsActionButton(stringResource(R.string.settings_shizuku_permission)) {
            HyperOsFocusBridge.requestShizukuPermission(context)
        }
    }
    Spacer(Modifier.height(10.dp))
    SettingsGlassGroup {
        SettingsToggleRow(context, stringResource(R.string.settings_notify_next_line), "lyrics_notification_next_line", false, grouped = true)
        SettingsToggleRow(context, stringResource(R.string.settings_notify_progress), "lyrics_notification_progress", true, grouped = true)
        SettingsToggleRow(context, stringResource(R.string.settings_notify_artwork), "lyrics_notification_artwork", true, grouped = true)
        SettingsToggleRow(context, stringResource(R.string.settings_notify_background_only), "lyrics_notification_background_only", false, grouped = true)
        SettingsToggleRow(context, stringResource(R.string.settings_notify_dismiss_paused), "lyrics_notification_dismiss_paused", true, grouped = true)
    }
    NotificationTemplateField(context, stringResource(R.string.settings_notify_title_template), "lyrics_notification_title_template", "{lyric}")
    NotificationTemplateField(context, stringResource(R.string.settings_notify_subtitle_template), "lyrics_notification_subtitle_template", "{song} · {artist}")
    NotificationTemplateField(context, stringResource(R.string.settings_notify_fallback), "lyrics_notification_fallback", "{song} · {artist}")
    Text(
        stringResource(R.string.settings_notify_variables),
        color = MaterialTheme.colorScheme.onSurface.copy(alpha = .46f),
        fontSize = 11.sp,
        modifier = Modifier.padding(bottom = 10.dp),
    )
    SettingsActionButton(stringResource(R.string.settings_notify_test)) {
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel("melox_lyrics", context.getString(R.string.settings_notify_channel_lyrics), NotificationManager.IMPORTANCE_LOW))
        manager.notify(
            10_043,
            NotificationCompat.Builder(context, "melox_lyrics")
                .setSmallIcon(android.R.drawable.ic_media_play)
                .setContentTitle(context.getString(R.string.settings_notify_test_title))
                .setContentText(context.getString(R.string.settings_notify_test_text))
                .setStyle(NotificationCompat.BigTextStyle().bigText(context.getString(R.string.settings_notify_test_big)))
                .setSilent(true)
                .build(),
        )
    }
}

@Composable
private fun ExperimentalSettings(context: android.content.Context, source: MusicSource, session: NeteaseSessionStore) {
    var playbackEnabled by remember { mutableStateOf(PlaybackAccountStore.isEnabled(context)) }
    var showNeteaseLogin by remember { mutableStateOf(false) }
    var showQQLogin by remember { mutableStateOf(false) }
    var showKugouLogin by remember { mutableStateOf(false) }
    var showClearPlaybackConfirmation by remember { mutableStateOf(false) }
    var showClearLyricBindingsConfirmation by remember { mutableStateOf(false) }
    var showClearBilibiliAssociationsConfirmation by remember { mutableStateOf(false) }
    var bilibiliLyricAlignment by remember(source) {
        mutableStateOf(MeloXSettingsPreferences.boolean(context, "bilibili_lyric_audio_alignment", false))
    }
    var refreshRevision by remember { mutableIntStateOf(0) }

    if (source == MusicSource.Bilibili) {
        SettingsGlassGroup {
            SettingsExternalToggleRow(
                title = stringResource(R.string.settings_bilibili_align),
                value = bilibiliLyricAlignment,
                grouped = true,
            ) { enabled ->
                bilibiliLyricAlignment = enabled
                MeloXSettingsPreferences.setBoolean(context, "bilibili_lyric_audio_alignment", enabled)
            }
        }
        SettingsInfoCard(stringResource(R.string.settings_bilibili_align_warning))
        Spacer(Modifier.height(10.dp))
        SettingsActionButton(stringResource(R.string.settings_bilibili_clear)) { showClearBilibiliAssociationsConfirmation = true }
        Spacer(Modifier.height(10.dp))
    }

    if (showClearBilibiliAssociationsConfirmation) {
        MeloXGlassDialog(visible = true, onDismiss = { showClearBilibiliAssociationsConfirmation = false }) {
            Text(stringResource(R.string.settings_bilibili_clear_title), style = MaterialTheme.typography.titleLarge)
            Text(stringResource(R.string.settings_bilibili_clear_body), Modifier.padding(top = 8.dp))
            Row(Modifier.fillMaxWidth().padding(top = 18.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                SettingsActionButton(stringResource(R.string.action_cancel), Modifier.weight(1f)) { showClearBilibiliAssociationsConfirmation = false }
                SettingsActionButton(stringResource(R.string.provider_clear), Modifier.weight(1f)) {
                    BilibiliPlaybackAssociationStore.clear(context)
                    showClearBilibiliAssociationsConfirmation = false
                }
            }
        }
    }

    if (showNeteaseLogin) {
        val session = remember { NeteaseSessionStore(context) }
        Dialog(
            onDismissRequest = { showNeteaseLogin = false },
            properties = DialogProperties(usePlatformDefaultWidth = false),
        ) {
            NeteaseLoginScreen(
                session = session,
                onDismiss = { showNeteaseLogin = false },
                onLoggedIn = { showNeteaseLogin = false; refreshRevision++ },
                targetSlot = PlaybackAccountSlot.Playback,
            )
        }
    }
    if (showQQLogin) {
        Dialog(
            onDismissRequest = { showQQLogin = false },
            properties = DialogProperties(usePlatformDefaultWidth = false),
        ) {
            QQMusicLoginScreen(
                onDismiss = { showQQLogin = false },
                onLoggedIn = { showQQLogin = false; refreshRevision++ },
                targetSlot = PlaybackAccountSlot.Playback,
            )
        }
    }
    if (showKugouLogin) {
        Dialog(
            onDismissRequest = { showKugouLogin = false },
            properties = DialogProperties(usePlatformDefaultWidth = false),
        ) {
            KugouLoginScreen(
                onDismiss = { showKugouLogin = false },
                onLoggedIn = { showKugouLogin = false; refreshRevision++ },
                targetSlot = PlaybackAccountSlot.Playback,
            )
        }
    }

    SettingsGlassGroup {
        SettingsExternalToggleRow(
            title = stringResource(R.string.settings_playback_second_account),
            value = playbackEnabled,
            grouped = true,
        ) { enabled ->
            playbackEnabled = enabled
            PlaybackAccountStore.setEnabled(context, enabled)
            if (!enabled) ProviderPlaybackQualityRuntime.clear()
        }
    }
    if (playbackEnabled) {
        Spacer(Modifier.height(10.dp))
        SettingsGlassGroup {
            SettingsInfoCard(stringResource(R.string.settings_playback_second_account_note))
        }
        Spacer(Modifier.height(10.dp))

        val neteaseCookie = remember(refreshRevision) { NeteaseSessionStore.readPlaybackCookie(context) }
        val qqLoggedIn = remember(refreshRevision) { QQMusicSessionStore.read(context, playback = true).isLoggedIn }
        val kugouLoggedIn = remember(refreshRevision) { KugouSessionStore.read(context, playback = true).isLoggedIn }

        SettingsGlassGroup {
            MeloXIosListRow(
                title = stringResource(R.string.account_netease),
                subtitle = if (NeteaseSessionStore.containsMusicU(neteaseCookie)) stringResource(R.string.provider_logged_in) else stringResource(R.string.provider_not_signed_in),
                leading = { MeloXSymbolIcon(MeloXSymbol.MusicNote, Modifier.size(24.dp), MaterialTheme.colorScheme.primary) },
                onClick = { showNeteaseLogin = true },
                showTopSeparator = false,
            )
            MeloXIosListRow(
                title = stringResource(R.string.settings_qq_music),
                subtitle = if (qqLoggedIn) stringResource(R.string.provider_logged_in) else stringResource(R.string.provider_not_signed_in),
                leading = { MeloXSymbolIcon(MeloXSymbol.MusicNote, Modifier.size(24.dp), MaterialTheme.colorScheme.primary) },
                onClick = { showQQLogin = true },
            )
            MeloXIosListRow(
                title = stringResource(R.string.settings_kugou),
                subtitle = if (kugouLoggedIn) stringResource(R.string.provider_logged_in) else stringResource(R.string.provider_not_signed_in),
                leading = { MeloXSymbolIcon(MeloXSymbol.MusicNote, Modifier.size(24.dp), MaterialTheme.colorScheme.primary) },
                onClick = { showKugouLogin = true },
            )
            MeloXIosListRow(
                title = stringResource(R.string.settings_apple_music),
                subtitle = stringResource(R.string.settings_apple_second_account),
                leading = { MeloXSymbolIcon(MeloXSymbol.MusicNote, Modifier.size(24.dp), MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)) },
                onClick = null,
            )
        }
        Spacer(Modifier.height(10.dp))
        SettingsDangerButton(stringResource(R.string.settings_clear_second_accounts)) { showClearPlaybackConfirmation = true }
        MeloXGlassDialog(
            visible = showClearPlaybackConfirmation,
            onDismiss = { showClearPlaybackConfirmation = false },
        ) {
            Text(stringResource(R.string.settings_clear_second_accounts_title), style = MaterialTheme.typography.titleMedium)
            Text(
                stringResource(R.string.settings_clear_second_accounts_body),
                modifier = Modifier.padding(top = 8.dp),
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = .62f),
            )
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 18.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                SettingsActionButton(stringResource(R.string.action_cancel), Modifier.weight(1f)) { showClearPlaybackConfirmation = false }
                SettingsActionButton(stringResource(R.string.provider_clear), Modifier.weight(1f)) {
                    PlaybackAccountStore.clear(context)
                    ProviderPlaybackQualityRuntime.clear()
                    refreshRevision++
                    showClearPlaybackConfirmation = false
                }
            }
        }
    }
    Spacer(Modifier.height(10.dp))
    if (!MeloXSettingsRuntime.automaticLyricSelectionEnabled) {
        SettingsInfoCard(stringResource(R.string.settings_auto_lyrics_required))
        return
    }
    var enabled by remember {
        mutableStateOf(MeloXSettingsPreferences.boolean(context, "experimental_lyric_strong_binding", false))
    }
    SettingsGlassGroup {
        SettingsExternalToggleRow(
            title = stringResource(R.string.settings_lyric_binding),
            value = enabled,
            grouped = true,
        ) { value ->
            enabled = value
            MeloXSettingsPreferences.setBoolean(context, "experimental_lyric_strong_binding", value)
            if (!value) com.lladlam.melox.core.lyrics.LyricBindingStore.clear(context)
        }
    }
    Spacer(Modifier.height(10.dp))
    SettingsDangerButton(stringResource(R.string.settings_clear_lyric_bindings)) {
        showClearLyricBindingsConfirmation = true
    }
    MeloXGlassDialog(
        visible = showClearLyricBindingsConfirmation,
        onDismiss = { showClearLyricBindingsConfirmation = false },
    ) {
        Text(stringResource(R.string.settings_clear_lyric_bindings_title), style = MaterialTheme.typography.titleMedium)
        Text(
            stringResource(R.string.settings_clear_lyric_bindings_body),
            modifier = Modifier.padding(top = 8.dp),
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = .62f),
            fontSize = 13.sp,
            lineHeight = 19.sp,
        )
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 18.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            SettingsActionButton(stringResource(R.string.action_cancel), Modifier.weight(1f)) {
                showClearLyricBindingsConfirmation = false
            }
            SettingsDangerButton(stringResource(R.string.provider_clear), Modifier.weight(1f)) {
                com.lladlam.melox.core.lyrics.LyricBindingStore.clear(context)
                showClearLyricBindingsConfirmation = false
            }
        }
    }
}

@Composable
private fun NotificationTemplateField(
    context: android.content.Context,
    title: String,
    key: String,
    default: String,
) {
    var value by remember(key) { mutableStateOf(MeloXSettingsPreferences.string(context, key, default)) }
    Text(title, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = .48f), modifier = Modifier.padding(top = 8.dp, bottom = 6.dp))
    MeloXGlassTextField(
        value = value,
        onValueChange = {
            value = it.take(80)
            MeloXSettingsPreferences.setString(context, key, value)
        },
        modifier = Modifier.fillMaxWidth(),
        textStyle = androidx.compose.ui.text.TextStyle(color = MaterialTheme.colorScheme.onSurface, fontSize = 15.sp),
    )
    Spacer(Modifier.height(6.dp))
}

@Composable
private fun SkylineLyricsSettings(context: android.content.Context) {
    var enabled by remember { mutableStateOf(MeloXSettingsRuntime.skylineEnabled) }
    SettingsGlassGroup {
        SettingsExternalToggleRow(
            title = stringResource(R.string.settings_skyline_auto),
            value = enabled,
            note = stringResource(R.string.settings_skyline_auto_note),
            grouped = true,
        ) {
            enabled = it
            MeloXSettingsPreferences.setBoolean(context, "lyrics_skyline_enabled", it)
        }
        SettingsToggleRow(context, stringResource(R.string.settings_skyline_song_info), "lyrics_skyline_song_info", true, grouped = true)
        SettingsToggleRow(context, stringResource(R.string.settings_skyline_keep_awake), "lyrics_skyline_keep_awake", true, stringResource(R.string.settings_skyline_keep_awake_note), grouped = true)
        LyricsChoiceSetting(
            context,
            stringResource(R.string.settings_skyline_ambient_count),
            "lyrics_skyline_ambient_lines",
            2,
            listOf(0, 1, 2, 3, 4),
            grouped = true,
        ) { if (it == 0) context.getString(R.string.settings_skyline_off) else context.getString(R.string.settings_skyline_lines, it) }
    }
    Spacer(Modifier.height(10.dp))
    SettingsGlassGroup {
        LyricsChoiceSetting(context, stringResource(R.string.settings_skyline_max_chars), "lyrics_skyline_ambient_max_characters", 4, listOf(1, 2, 3, 4), grouped = true) { context.getString(R.string.settings_skyline_chars, it) }
        LyricsChoiceSetting(context, stringResource(R.string.settings_skyline_max_visible), "lyrics_skyline_ambient_max_visible", 16, listOf(4, 8, 12, 16, 20, 24), grouped = true) { context.getString(R.string.settings_skyline_groups, it) }
    }
    Spacer(Modifier.height(10.dp))
    var currentFontSize by remember { mutableStateOf(MeloXSettingsRuntime.skylineCurrentFontSize) }
    var currentScale by remember { mutableStateOf(MeloXSettingsRuntime.skylineCurrentMaximumScale) }
    var currentWidth by remember { mutableStateOf(MeloXSettingsRuntime.skylineCurrentWidth) }
    var nextFontSize by remember { mutableStateOf(MeloXSettingsRuntime.skylineNextFontSize) }
    var nextOpacity by remember { mutableStateOf(MeloXSettingsRuntime.skylineNextOpacity) }
    var currentSpacing by remember { mutableStateOf(MeloXSettingsRuntime.skylineCurrentSpacing) }
    var ambientFontSize by remember { mutableStateOf(MeloXSettingsRuntime.skylineAmbientFontSize) }
    var ambientOpacity by remember { mutableStateOf(MeloXSettingsRuntime.skylineAmbientOpacity) }
    var ambientBlur by remember { mutableStateOf(MeloXSettingsRuntime.skylineAmbientBlur) }
    var ambientTilt by remember { mutableStateOf(MeloXSettingsRuntime.skylineAmbientMaximumTilt) }
    var ambientDrift by remember { mutableStateOf(MeloXSettingsRuntime.skylineAmbientDrift) }
    SettingsFloatSlider(stringResource(R.string.settings_skyline_current_size), currentFontSize, 36f..84f, 47, { context.getString(R.string.settings_unit_sp, it.toInt()) }) {
        currentFontSize = it; MeloXSettingsPreferences.setFloat(context, "lyrics_skyline_current_font_size", it)
    }
    SettingsFloatSlider(stringResource(R.string.settings_skyline_word_scale), currentScale, 1f..1.2f, 19, { context.getString(R.string.settings_unit_scale, it) }) {
        currentScale = it; MeloXSettingsPreferences.setFloat(context, "lyrics_skyline_current_max_scale", it)
    }
    SettingsFloatSlider(stringResource(R.string.settings_skyline_center_width), currentWidth, .4f..82f / 100f, 20, { context.getString(R.string.settings_unit_percent, (it * 100).toInt()) }) {
        currentWidth = it; MeloXSettingsPreferences.setFloat(context, "lyrics_skyline_current_width", it)
    }
    SettingsFloatSlider(stringResource(R.string.settings_skyline_next_size), nextFontSize, 14f..44f, 29, { context.getString(R.string.settings_unit_sp, it.toInt()) }) {
        nextFontSize = it; MeloXSettingsPreferences.setFloat(context, "lyrics_skyline_next_font_size", it)
    }
    SettingsFloatSlider(stringResource(R.string.settings_skyline_next_brightness), nextOpacity, .2f..8f / 10f, 11, { context.getString(R.string.settings_unit_percent, (it * 100).toInt()) }) {
        nextOpacity = it; MeloXSettingsPreferences.setFloat(context, "lyrics_skyline_next_opacity", it)
    }
    SettingsFloatSlider(stringResource(R.string.settings_skyline_center_spacing), currentSpacing, 4f..36f, 31, { context.getString(R.string.settings_unit_dp, it.toInt()) }) {
        currentSpacing = it; MeloXSettingsPreferences.setFloat(context, "lyrics_skyline_current_spacing", it)
    }
    SettingsFloatSlider(stringResource(R.string.settings_skyline_bg_size), ambientFontSize, 24f..72f, 47, { context.getString(R.string.settings_unit_sp, it.toInt()) }) {
        ambientFontSize = it; MeloXSettingsPreferences.setFloat(context, "lyrics_skyline_ambient_font_size", it)
    }
    SettingsFloatSlider(stringResource(R.string.settings_skyline_bg_brightness), ambientOpacity, .4f..1.8f, 13, { context.getString(R.string.settings_unit_times_1, it) }) {
        ambientOpacity = it; MeloXSettingsPreferences.setFloat(context, "lyrics_skyline_ambient_opacity", it)
    }
    SettingsFloatSlider(stringResource(R.string.settings_skyline_bg_blur), ambientBlur, 0f..2f, 19, { context.getString(R.string.settings_unit_times_1, it) }) {
        ambientBlur = it; MeloXSettingsPreferences.setFloat(context, "lyrics_skyline_ambient_blur", it)
    }
    SettingsFloatSlider(stringResource(R.string.settings_skyline_max_tilt), ambientTilt, 0f..20f, 19, { context.getString(R.string.settings_unit_degrees, it.toInt()) }) {
        ambientTilt = it; MeloXSettingsPreferences.setFloat(context, "lyrics_skyline_ambient_max_tilt", it)
    }
    SettingsFloatSlider(stringResource(R.string.settings_skyline_drift), ambientDrift, 0f..2f, 19, { context.getString(R.string.settings_unit_times_1, it) }) {
        ambientDrift = it; MeloXSettingsPreferences.setFloat(context, "lyrics_skyline_ambient_drift", it)
    }
    SettingsActionButton(stringResource(R.string.settings_skyline_reset)) {
        listOf(
            "lyrics_skyline_current_font_size" to 54f, "lyrics_skyline_current_max_scale" to 1.1f,
            "lyrics_skyline_next_font_size" to 24f, "lyrics_skyline_current_spacing" to 14f,
            "lyrics_skyline_current_width" to .64f, "lyrics_skyline_next_opacity" to .48f,
            "lyrics_skyline_ambient_font_size" to 44f, "lyrics_skyline_ambient_opacity" to 1f,
            "lyrics_skyline_ambient_blur" to 1f, "lyrics_skyline_ambient_max_tilt" to 8f,
            "lyrics_skyline_ambient_drift" to 1f,
        ).forEach { (key, value) -> MeloXSettingsPreferences.setFloat(context, key, value) }
        MeloXSettingsPreferences.setInt(context, "lyrics_skyline_ambient_max_characters", 4)
        MeloXSettingsPreferences.setInt(context, "lyrics_skyline_ambient_max_visible", 16)
        currentFontSize = 54f; currentScale = 1.1f; nextFontSize = 24f; currentSpacing = 14f
        currentWidth = .64f; nextOpacity = .48f; ambientFontSize = 44f; ambientOpacity = 1f
        ambientBlur = 1f; ambientTilt = 8f; ambientDrift = 1f
    }
}

@Composable
private fun FloatingLyricsSettings(context: android.content.Context) {
    var enabled by remember { mutableStateOf(MeloXSettingsRuntime.floatingLyricsEnabled) }
    var permissionGranted by remember { mutableStateOf(AndroidSettings.canDrawOverlays(context)) }

    fun startFloatingLyrics() {
        MeloXSettingsPreferences.setBoolean(context, "floating_lyrics_enabled", true)
        enabled = true
        ContextCompat.startForegroundService(context, Intent(context, MeloXFloatingLyricsService::class.java))
    }

    val overlayPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) {
        permissionGranted = AndroidSettings.canDrawOverlays(context)
        if (permissionGranted) startFloatingLyrics()
    }

    SettingsGlassGroup {
        SettingsExternalToggleRow(
            title = stringResource(R.string.settings_floating_show),
            value = enabled,
            note = stringResource(R.string.settings_floating_show_note),
            grouped = true,
        ) { shouldEnable ->
            if (!shouldEnable) {
                enabled = false
                MeloXSettingsPreferences.setBoolean(context, "floating_lyrics_enabled", false)
                context.stopService(Intent(context, MeloXFloatingLyricsService::class.java))
            } else if (AndroidSettings.canDrawOverlays(context)) {
                permissionGranted = true
                startFloatingLyrics()
            } else {
                overlayPermissionLauncher.launch(
                    Intent(
                        AndroidSettings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:${context.packageName}"),
                    ),
                )
            }
        }
        MeloXSettingsDropdown(
            title = stringResource(R.string.settings_floating_secondary),
            selected = MeloXSettingsRuntime.floatingSecondaryMode,
            items = listOf(
                MeloXSecondaryLyricMode.Auto to stringResource(R.string.settings_floating_secondary_auto),
                MeloXSecondaryLyricMode.Translation to stringResource(R.string.settings_floating_translation),
                MeloXSecondaryLyricMode.Romanization to stringResource(R.string.settings_floating_romanization),
                MeloXSecondaryLyricMode.NextLine to stringResource(R.string.settings_floating_next_line),
                MeloXSecondaryLyricMode.Hidden to stringResource(R.string.settings_floating_hidden),
            ),
            onSelected = { MeloXSettingsPreferences.setString(context, "floating_lyrics_secondary_mode", it.name) },
            grouped = true,
        )
        LyricsChoiceSetting(
            context,
            stringResource(R.string.settings_floating_font_size),
            "floating_lyrics_font_size",
            18,
            listOf(14, 16, 18, 20, 24, 28),
            grouped = true,
        ) { context.getString(R.string.settings_unit_sp, it) }
        SettingsToggleRow(context, stringResource(R.string.settings_floating_contrast), "floating_lyrics_high_contrast", true, stringResource(R.string.settings_floating_contrast_note), grouped = true)
    }
}

@Composable
private fun PlaybackSettings(context: android.content.Context) {
    var quality by remember { mutableStateOf(MusicQualityPreferences.read(context)) }
    var volumeMode by remember { mutableStateOf(MeloXSettingsRuntime.volumeControlMode) }
    SettingsGlassGroup {
        MeloXSettingsDropdown(
            title = stringResource(R.string.settings_playback_quality),
            selected = quality,
            items = MusicQuality.entries.map { entry ->
                entry to stringResource(when (entry) {
                    MusicQuality.Standard -> R.string.settings_quality_standard
                    MusicQuality.High -> R.string.settings_quality_high
                    MusicQuality.Lossless -> R.string.settings_quality_lossless
                    MusicQuality.HiResolution -> R.string.settings_quality_hires
                    MusicQuality.HighDefinitionSurround -> R.string.settings_quality_surround
                    MusicQuality.ImmersiveSurround -> R.string.settings_quality_immersive
                    MusicQuality.UltraClearMaster -> R.string.settings_quality_master
                })
            },
            onSelected = { quality = it; PlaybackCommands.changeQuality(context, it) },
            grouped = true,
        )
        MeloXSettingsDropdown(
            title = stringResource(R.string.settings_volume_slider),
            selected = volumeMode,
            items = listOf(
                MeloXVolumeControlMode.System to stringResource(R.string.settings_volume_system),
                MeloXVolumeControlMode.Player to stringResource(R.string.settings_volume_player),
            ),
            onSelected = { volumeMode = it; MeloXSettingsPreferences.setString(context, "playback_volume_mode", it.name) },
            grouped = true,
        )
        SettingsToggleRow(
            context,
            stringResource(R.string.settings_allow_other_apps),
            "playback_allow_other_apps",
            false,
            stringResource(R.string.settings_allow_other_apps_note),
            grouped = true,
        )
        SettingsToggleRow(context, stringResource(R.string.settings_remember_player_page), "playback_remember_page", true, grouped = true)
        SettingsToggleRow(
            context,
            stringResource(R.string.settings_save_queue_on_exit),
            "playback_save_queue",
            true,
            stringResource(R.string.settings_save_queue_on_exit_note),
            grouped = true,
        )
        SettingsToggleRow(
            context,
            stringResource(R.string.settings_remember_last_song),
            "playback_remember_last_song",
            true,
            stringResource(R.string.settings_remember_last_song_note),
            grouped = true,
        )
        SettingsToggleRow(context, stringResource(R.string.settings_heart_mode_on_launch), "playback_heart_mode_on_launch", false, grouped = true)
        SettingsToggleRow(context, stringResource(R.string.settings_previous_restarts), "playback_previous_restarts", true, grouped = true)
        SettingsToggleRow(
            context,
            stringResource(R.string.settings_show_quality_tip),
            "player_show_quality_tip",
            true,
            stringResource(R.string.settings_show_quality_tip_note),
            grouped = true,
        )
        LyricsChoiceSetting(
            context,
            stringResource(R.string.settings_player_transition_duration),
            "player_transition_duration_ms",
             360,
             listOf(240, 300, 360, 460, 600),
            grouped = true,
        ) { context.getString(R.string.settings_duration_ms, it) }
    }
    Spacer(Modifier.height(10.dp))
    EqualizerSettings(context)
    Spacer(Modifier.height(10.dp))
    AutoMixSettings(context)
    Spacer(Modifier.height(10.dp))
    AnalysisCacheSettings(context)
}

@Composable
private fun EqualizerSettings(context: android.content.Context) {
    var enabled by remember { mutableStateOf(MeloXSettingsPreferences.boolean(context, "equalizer_enabled", false)) }
    var preset by remember { mutableStateOf(MeloXSettingsPreferences.string(context, "equalizer_preset", "Flat")) }
    var preamp by remember { mutableStateOf(MeloXSettingsPreferences.number(context, "equalizer_preamp_db", 0f)) }
    SettingsExternalToggleRow(stringResource(R.string.settings_equalizer), enabled, stringResource(R.string.settings_equalizer_note)) {
        enabled = it
        MeloXSettingsPreferences.setBoolean(context, "equalizer_enabled", it)
    }
    if (!enabled) return
    Text(stringResource(R.string.settings_eq_presets), fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = .48f))
    Spacer(Modifier.height(8.dp))
    SettingsGlassGroup {
        MeloXEqualizerController.PRESETS.keys.forEach { value ->
            val label = mapOf(
                "Flat" to stringResource(R.string.settings_eq_flat), "Bass" to stringResource(R.string.settings_eq_bass), "Vocal" to stringResource(R.string.settings_eq_vocal), "Treble" to stringResource(R.string.settings_eq_treble),
                "Electronic" to stringResource(R.string.settings_eq_electronic), "Rock" to stringResource(R.string.settings_eq_rock), "Classical" to stringResource(R.string.settings_eq_classical), "Custom" to stringResource(R.string.settings_eq_custom),
            ).getValue(value)
            SettingsChoiceRow(label, preset == value) {
                preset = value
                MeloXSettingsPreferences.setString(context, "equalizer_preset", value)
            }
        }
    }
    if (preset == "Custom") {
        Spacer(Modifier.height(12.dp))
        listOf("31 Hz", "62 Hz", "125 Hz", "250 Hz", "500 Hz", "1 kHz", "2 kHz", "4 kHz", "8 kHz", "16 kHz")
            .forEachIndexed { index, label ->
                var gain by remember(index) {
                    mutableStateOf(MeloXSettingsPreferences.number(context, "equalizer_custom_band_$index", 0f))
                }
                SettingsFloatSlider(label, gain, -12f..12f, 47, { value ->
                    val rounded = kotlin.math.round(value * 2f) / 2f
                    val format = if (rounded > 0f) R.string.settings_eq_gain_positive else R.string.settings_eq_gain_db
                    context.getString(format, rounded)
                }) { value ->
                    gain = kotlin.math.round(value * 2f) / 2f
                    MeloXSettingsPreferences.setFloat(context, "equalizer_custom_band_$index", gain)
                }
        }
    }
    Spacer(Modifier.height(12.dp))
    Text(stringResource(R.string.settings_eq_preamp), fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = .48f))
    Spacer(Modifier.height(8.dp))
    SettingsFloatSlider(stringResource(R.string.settings_eq_input_gain), preamp, -12f..12f, 47, { value ->
        val rounded = kotlin.math.round(value * 2f) / 2f
        val format = if (rounded > 0f) R.string.settings_eq_gain_positive else R.string.settings_eq_gain_db
        context.getString(format, rounded)
    }) { value ->
        preamp = kotlin.math.round(value * 2f) / 2f
        MeloXSettingsPreferences.setFloat(context, "equalizer_preamp_db", preamp)
    }
}

@Composable
private fun AutoMixSettings(context: android.content.Context) {
    var settings by remember { mutableStateOf(MeloXAutoMixSettings.read(context)) }
    fun refresh() { settings = MeloXAutoMixSettings.read(context) }
    var showSmartQueueIntro by remember { mutableStateOf(false) }

    val legacyAutoMix = remember { MeloXSettingsPreferences.boolean(context, "playback_auto_mix", false) }
    var autoMixEnabled by remember {
        mutableStateOf(MeloXPlaybackModePreferences.autoMix(context) || legacyAutoMix)
    }
    LaunchedEffect(legacyAutoMix) {
        if (legacyAutoMix) {
            MeloXPlaybackModePreferences.setAutoMix(context, true)
            MeloXSettingsPreferences.setBoolean(context, "playback_auto_mix", false)
        }
    }
    SettingsGlassGroup {
        SettingsExternalToggleRow(
            title = stringResource(R.string.settings_automix),
            value = autoMixEnabled,
            note = stringResource(R.string.settings_automix_note),
            grouped = true,
        ) {
            autoMixEnabled = it
            MeloXPlaybackModePreferences.setAutoMix(context, it)
        }
        SettingsExternalToggleRow(
            title = stringResource(R.string.settings_transition_animation),
            value = MeloXSettingsRuntime.transitionUiEnabled,
            grouped = true,
        ) { enabled ->
            MeloXSettingsPreferences.setBoolean(context, "transition_ui_enabled", enabled)
            MeloXSettingsRuntime.transitionUiEnabled = enabled
        }
    }
    if (autoMixEnabled) {
        Spacer(Modifier.height(10.dp))
        if (settings.mode == MeloXAutoMixMode.Smart) SettingsGlassGroup {
            SettingsExternalToggleRow(
                title = stringResource(R.string.settings_smart_queue),
                value = MeloXSettingsRuntime.smartQueueEnabled,
                grouped = true,
            ) { enabled ->
                if (enabled && !MeloXSettingsPreferences.boolean(context, "smart_queue_intro_shown", false)) {
                    showSmartQueueIntro = true
                } else {
                    MeloXPlaybackModePreferences.setSmartQueue(context, enabled)
                    MeloXSettingsRuntime.smartQueueEnabled = enabled
                }
            }
        }
        Spacer(Modifier.height(10.dp))
        SettingsGlassGroup {
            MeloXSettingsDropdown(
            title = stringResource(R.string.settings_automix_mode),
            selected = settings.mode,
            items = listOf(MeloXAutoMixMode.Smart to stringResource(R.string.settings_automix_smart), MeloXAutoMixMode.Fixed to stringResource(R.string.settings_automix_fixed)),
            onSelected = { MeloXPlaybackModePreferences.setAutoMixString(context, "automix_mode", it.name); refresh() },
            grouped = true,
        )
        if (settings.mode == MeloXAutoMixMode.Smart) {
            SettingsExternalToggleRow(stringResource(R.string.settings_skip_quiet_opening), settings.skipQuietOpening, stringResource(R.string.settings_skip_quiet_opening_note), grouped = true) {
                MeloXPlaybackModePreferences.setAutoMixBoolean(context, "automix_skip_quiet_opening", it)
                refresh()
            }
            SettingsExternalToggleRow(stringResource(R.string.settings_analyze_streaming), settings.analyzeStreaming, stringResource(R.string.settings_analyze_streaming_note), grouped = true) {
                MeloXPlaybackModePreferences.setAutoMixBoolean(context, "automix_analyze_streaming", it)
                refresh()
            }
            MeloXSettingsDropdown(
                title = stringResource(R.string.settings_smart_transition_length),
                selected = settings.transitionBars,
                items = listOf(4, 8, 16).map { it to context.getString(R.string.settings_bars, it) },
                onSelected = { MeloXPlaybackModePreferences.setAutoMixInt(context, "automix_transition_bars", it); refresh() },
                grouped = true,
            )
            MeloXSettingsDropdown(
                title = stringResource(R.string.settings_tail_cut),
                selected = settings.tailCutBars,
                items = listOf(0 to stringResource(R.string.settings_tail_keep), 2 to context.getString(R.string.settings_tail_cut_bars, 2), 4 to context.getString(R.string.settings_tail_cut_bars, 4), 8 to context.getString(R.string.settings_tail_cut_bars, 8)),
                onSelected = { MeloXPlaybackModePreferences.setAutoMixInt(context, "automix_tail_cut_bars", it); refresh() },
                grouped = true,
            )
            val confidenceOptions = listOf(.30f, .42f, .55f, .70f)
            MeloXSettingsDropdown(
                title = stringResource(R.string.settings_min_confidence),
                selected = confidenceOptions.minByOrNull { kotlin.math.abs(settings.minimumConfidence - it) } ?: .42f,
                items = confidenceOptions.map { it to "${(it * 100).toInt()}%" },
                onSelected = { MeloXPlaybackModePreferences.setAutoMixFloat(context, "automix_minimum_confidence", it); refresh() },
                grouped = true,
            )
        }
        val durationOptions = listOf(3_000L, 6_000L, 8_000L, 12_000L, 16_000L, 20_000L)
        MeloXSettingsDropdown(
            title = stringResource(R.string.settings_crossfade_duration),
            selected = durationOptions.minByOrNull { kotlin.math.abs(settings.fixedDurationMs - it) } ?: 6_000L,
            items = durationOptions.map { it to context.getString(R.string.settings_seconds, (it / 1_000).toInt()) },
            onSelected = { MeloXPlaybackModePreferences.setAutoMixLong(context, "automix_fixed_duration_ms", it); refresh() },
            grouped = true,
        )
        val preloadOptions = listOf(30_000L, 60_000L, 90_000L, 120_000L, 180_000L)
        MeloXSettingsDropdown(
            title = stringResource(R.string.settings_preload_lead),
            selected = preloadOptions.minByOrNull { kotlin.math.abs(settings.preloadLeadMs - it) } ?: 60_000L,
            items = preloadOptions.map { it to context.getString(R.string.settings_seconds, (it / 1_000).toInt()) },
            onSelected = { MeloXPlaybackModePreferences.setAutoMixLong(context, "automix_preload_lead_ms", it); refresh() },
            grouped = true,
        )
        MeloXSettingsDropdown(
            title = stringResource(R.string.settings_fade_curve),
            selected = settings.fadeCurve,
            items = listOf(
                MeloXAutoMixFadeCurve.EqualPower to stringResource(R.string.settings_fade_equal_power),
                MeloXAutoMixFadeCurve.Smooth to stringResource(R.string.settings_fade_smooth),
                MeloXAutoMixFadeCurve.Linear to stringResource(R.string.settings_fade_linear),
            ),
            onSelected = { MeloXPlaybackModePreferences.setAutoMixString(context, "automix_fade_curve", it.name); refresh() },
            grouped = true,
        )
        MeloXSettingsDropdown(
            title = stringResource(R.string.settings_analysis_fallback),
            selected = settings.fallback,
            items = listOf(
                MeloXAutoMixFallback.Crossfade to stringResource(R.string.settings_fallback_selected_duration),
                MeloXAutoMixFallback.ShortCrossfade to stringResource(R.string.settings_fallback_short_crossfade),
                MeloXAutoMixFallback.Normal to stringResource(R.string.settings_fallback_normal),
            ),
            onSelected = { MeloXPlaybackModePreferences.setAutoMixString(context, "automix_fallback", it.name); refresh() },
            grouped = true,
        )
        SettingsExternalToggleRow(stringResource(R.string.settings_tempo_matching), settings.tempoMatching, stringResource(R.string.settings_tempo_matching_note), grouped = true) {
            MeloXPlaybackModePreferences.setAutoMixBoolean(context, "automix_tempo_matching", it)
            refresh()
        }
        if (settings.tempoMatching) {
            val adjustmentOptions = listOf(.02f, .05f, .08f, .10f)
            MeloXSettingsDropdown(
                title = stringResource(R.string.settings_max_tempo_adjustment),
                selected = adjustmentOptions.minByOrNull { kotlin.math.abs(settings.maxTempoAdjustment - it) } ?: .05f,
                items = adjustmentOptions.map { it to "${(it * 100).toInt()}%" },
                onSelected = { MeloXPlaybackModePreferences.setAutoMixFloat(context, "automix_max_tempo_adjustment", it); refresh() },
                grouped = true,
            )
        }
    }
    }
    if (showSmartQueueIntro) {
        MeloXGlassDialog(visible = true, onDismiss = { showSmartQueueIntro = false }) {
            Text(stringResource(R.string.settings_smart_queue), style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(12.dp))
            Text(stringResource(R.string.settings_smart_queue_intro))
            Spacer(Modifier.height(8.dp))
            Text(stringResource(R.string.settings_smart_queue_rules), fontWeight = FontWeight.Bold)
            Text(stringResource(R.string.settings_smart_queue_rule_closest))
            Text(stringResource(R.string.settings_smart_queue_rule_unique))
            Text(stringResource(R.string.settings_smart_queue_rule_background))
            Spacer(Modifier.height(18.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                SettingsActionButton(stringResource(R.string.action_cancel), Modifier.weight(1f)) { showSmartQueueIntro = false }
                SettingsActionButton(stringResource(R.string.settings_action_enable), Modifier.weight(1f)) {
                    MeloXPlaybackModePreferences.setSmartQueue(context, true)
                    MeloXSettingsRuntime.smartQueueEnabled = true
                    MeloXSettingsPreferences.setBoolean(context, "smart_queue_intro_shown", true)
                    showSmartQueueIntro = false
                }
            }
        }
    }
}

@Composable
private fun AnalysisCacheSettings(context: android.content.Context) {
    var showClearConfirmation by remember { mutableStateOf(false) }
    SettingsGlassGroup {
        SettingsActionButton(stringResource(R.string.settings_clear_analysis_cache)) { showClearConfirmation = true }
    }
    if (showClearConfirmation) {
        MeloXGlassDialog(visible = true, onDismiss = { showClearConfirmation = false }) {
            Text(stringResource(R.string.settings_clear_analysis_cache_confirm))
            Row(Modifier.fillMaxWidth().padding(top = 18.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                SettingsActionButton(stringResource(R.string.action_cancel), Modifier.weight(1f)) { showClearConfirmation = false }
                SettingsActionButton(stringResource(R.string.settings_privacy_clear_confirm), Modifier.weight(1f)) {
                    MeloXAudioAnalysisPreferences.clearAll(context)
                    showClearConfirmation = false
                }
            }
        }
    }
}

@Composable
private fun PlayerAppearanceSettings(context: android.content.Context) {
    LyricsStringChoiceSetting(
        context,
        stringResource(R.string.settings_player_shell),
        "player_shell",
        MeloXSettingsRuntime.playerShell.name,
        com.lladlam.melox.ui.settings.MeloXPlayerShell.entries.map { it.name },
    ) {
        when (com.lladlam.melox.ui.settings.MeloXPlayerShell.valueOf(it)) {
            com.lladlam.melox.ui.settings.MeloXPlayerShell.AppleMusic -> "Apple Music"
            com.lladlam.melox.ui.settings.MeloXPlayerShell.Classic -> context.getString(R.string.settings_player_shell_classic)
        }
    }
    Spacer(Modifier.height(10.dp))
    SettingsToggleRow(
        context,
        stringResource(R.string.settings_frosted_glass),
        "player_frosted_glass",
        false,
        stringResource(R.string.settings_frosted_glass_note),
    )
    Spacer(Modifier.height(10.dp))
    SettingsGlassGroup {
        MeloXSettingsDropdown(
            title = stringResource(R.string.settings_player_background),
            selected = MeloXSettingsRuntime.playerBackgroundMode,
            items = listOf(
                MeloXPlayerBackgroundMode.FlowingLight to stringResource(R.string.settings_bg_flowing_light),
                MeloXPlayerBackgroundMode.AppleLyrics to stringResource(R.string.settings_bg_apple_lyrics),
                MeloXPlayerBackgroundMode.BlurredArtwork to stringResource(R.string.settings_bg_blurred_artwork),
            ),
            onSelected = {
                MeloXSettingsPreferences.setString(context, "player_background_mode", it.name)
                MeloXSettingsRuntime.flowingBackdropEnabled = it != MeloXPlayerBackgroundMode.BlurredArtwork
            },
            grouped = true,
        )
        if (MeloXSettingsRuntime.playerBackgroundMode == MeloXPlayerBackgroundMode.FlowingLight) {
            PreferenceFloatSlider(
                context,
                stringResource(R.string.settings_flowing_speed),
                "player_flowing_speed",
                1f,
                .25f..2f,
                6,
            ) { "%.2fx".format(it) }
            PreferenceFloatSlider(
                context,
                stringResource(R.string.settings_flowing_saturation),
                "player_flowing_saturation",
                1f,
                0f..2f,
                19,
            ) { context.getString(R.string.settings_unit_percent, (it * 100).toInt()) }
            PreferenceFloatSlider(
                context,
                stringResource(R.string.settings_flowing_brightness),
                "player_flowing_brightness",
                1f,
                .4f..1.6f,
                11,
            ) { context.getString(R.string.settings_unit_percent, (it * 100).toInt()) }
        }
        SettingsToggleRow(context, stringResource(R.string.settings_flowing_backdrop), "player_flowing_backdrop", true, stringResource(R.string.settings_flowing_backdrop_note), grouped = true)
        SettingsToggleRow(context, stringResource(R.string.settings_background_isolation), "player_background_isolation", true, stringResource(R.string.settings_background_isolation_note), grouped = true)
        LyricsChoiceSetting(context, stringResource(R.string.settings_background_fps), "lyrics_background_frame_rate", 24, listOf(15, 24, 30, 45, 60), grouped = true) { value ->
            when (value) {
                15 -> context.getString(R.string.settings_fps_battery, 15)
                24 -> context.getString(R.string.settings_fps_battery, 24)
                30 -> context.getString(R.string.settings_fps_balanced, 30)
                else -> context.getString(R.string.settings_fps_recommended, value)
            }
        }
    }
    Spacer(Modifier.height(10.dp))
    SettingsGlassGroup {
        SettingsToggleRow(context, stringResource(R.string.settings_reduce_motion), "reduce_motion", false, stringResource(R.string.settings_reduce_motion_note), grouped = true)
        SettingsToggleRow(context, stringResource(R.string.settings_artwork_motion), "player_artwork_motion", true, grouped = true)
        MeloXSettingsDropdown(
            title = stringResource(R.string.settings_screen_awake_scope),
            selected = MeloXSettingsRuntime.screenAwakeMode,
            items = listOf(
                MeloXScreenAwakeMode.Disabled to stringResource(R.string.settings_skyline_off),
                MeloXScreenAwakeMode.Player to stringResource(R.string.settings_awake_player),
                MeloXScreenAwakeMode.Lyrics to stringResource(R.string.settings_awake_lyrics),
                MeloXScreenAwakeMode.HiddenLyricsInterface to stringResource(R.string.settings_awake_hidden_lyrics),
            ),
            onSelected = { MeloXSettingsPreferences.setString(context, "player_screen_awake_mode", it.name) },
            grouped = true,
        )
    }
    Spacer(Modifier.height(10.dp))
    SettingsGlassGroup {
        SettingsToggleRow(
            context,
            stringResource(R.string.settings_immersive_playback),
            "immersive_playback",
            false,
            stringResource(R.string.settings_immersive_playback_note),
            grouped = true,
        )
    }
}

@Composable
private fun LyricsSettings(context: android.content.Context) {
    var lyricsStyle by remember { mutableStateOf(MeloXSettingsRuntime.lyricsStyle) }
    // Group 1: 歌词样式-歌词渲染质量-逐字歌词-普通LRC-点击跳转-长按分享-间奏倒计时-自动跟随-手动滚动恢复-减弱动画
    SettingsGlassGroup {
        MeloXSettingsDropdown(
            title = stringResource(R.string.settings_lyrics_style),
            selected = lyricsStyle,
            items = listOf(
                MeloXLyricsStyle.AppleMusic to "Apple Music",
                MeloXLyricsStyle.Eva to stringResource(R.string.settings_lyrics_style_eva),
                MeloXLyricsStyle.TextPV to stringResource(R.string.settings_lyrics_style_text_pv),
            ),
            onSelected = { lyricsStyle = it; MeloXSettingsPreferences.setString(context, "lyrics_style", it.name) },
            grouped = true,
        )
        MeloXSettingsDropdown(
            title = stringResource(R.string.settings_lyrics_rendering),
            selected = MeloXSettingsRuntime.lyricRenderingQuality,
            items = listOf(
                MeloXLyricsRenderingQuality.Low to stringResource(R.string.settings_lyrics_quality_low),
                MeloXLyricsRenderingQuality.Balanced to stringResource(R.string.settings_lyrics_quality_balanced),
                MeloXLyricsRenderingQuality.High to stringResource(R.string.settings_lyrics_quality_high),
            ),
            onSelected = { MeloXSettingsPreferences.setString(context, "lyrics_rendering_quality", it.name) },
            grouped = true,
        )
        SettingsToggleRow(context, stringResource(R.string.settings_lyrics_auto_select), "lyrics_auto_select", true, grouped = true)
        SettingsToggleRow(context, stringResource(R.string.settings_lyrics_word_by_word), "lyrics_word_by_word", true, grouped = true)
        SettingsToggleRow(context, stringResource(R.string.settings_lyrics_pseudo_timing), "lyrics_pseudo_timing", true, stringResource(R.string.settings_lyrics_pseudo_timing_note), grouped = true)
        SettingsToggleRow(context, stringResource(R.string.settings_lyrics_tap_seek), "lyrics_tap_seek", true, grouped = true)
        SettingsToggleRow(context, stringResource(R.string.settings_lyrics_long_press_share), "lyrics_long_press_share", true, grouped = true)
        SettingsToggleRow(context, stringResource(R.string.settings_lyrics_interlude), "lyrics_interlude_countdown", true, stringResource(R.string.settings_lyrics_interlude_note), grouped = true)
        SettingsToggleRow(context, stringResource(R.string.settings_lyrics_auto_follow), "lyrics_auto_follow", true, grouped = true)
        LyricsChoiceSetting(context, stringResource(R.string.settings_lyrics_follow_delay), "lyrics_follow_delay_ms", 3_000, listOf(1_500, 3_000, 5_000, 8_000), grouped = true) { context.getString(R.string.settings_seconds_float, it / 1_000f) }
        SettingsToggleRow(context, stringResource(R.string.settings_lyrics_reduce_motion), "lyrics_reduce_motion", false, stringResource(R.string.settings_lyrics_reduce_motion_note), grouped = true)
    }
    if (lyricsStyle == MeloXLyricsStyle.TextPV) {
        var pvStyle by remember { mutableStateOf(MeloXSettingsRuntime.textPVStyle) }
        var pvMotionIntensity by remember { mutableStateOf(MeloXSettingsRuntime.textPVMotionIntensity) }
        var pvAnimationSpeed by remember { mutableStateOf(MeloXSettingsRuntime.textPVAnimationSpeed) }
        Spacer(Modifier.height(10.dp))
        SettingsGlassGroup {
            Text(stringResource(R.string.settings_text_pv_style), modifier = Modifier.padding(horizontal = 18.dp, vertical = 4.dp), fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = .48f))
            listOf(
                MeloXTextPVStyle.BlueBold to stringResource(R.string.settings_pv_blue_bold),
                MeloXTextPVStyle.KineticSplit to stringResource(R.string.settings_pv_kinetic_split),
                MeloXTextPVStyle.BluePlane to stringResource(R.string.settings_pv_blue_plane),
                MeloXTextPVStyle.CyberGrunge to stringResource(R.string.settings_pv_cyber_grunge),
                MeloXTextPVStyle.Geometric to stringResource(R.string.settings_pv_geometric),
                MeloXTextPVStyle.RainCity to stringResource(R.string.settings_pv_rain_city),
                MeloXTextPVStyle.CyberpunkHUD to stringResource(R.string.settings_pv_cyberpunk_hud),
                MeloXTextPVStyle.EmotionCinema to stringResource(R.string.settings_pv_emotion_cinema),
                MeloXTextPVStyle.HystericNight to stringResource(R.string.settings_pv_hysteric_night),
                MeloXTextPVStyle.SpiderWeb to stringResource(R.string.settings_pv_spider_web),
                MeloXTextPVStyle.StaggeredText to stringResource(R.string.settings_pv_staggered),
                MeloXTextPVStyle.CalmVillain to stringResource(R.string.settings_pv_calm_villain),
                MeloXTextPVStyle.GirlyClouds to stringResource(R.string.settings_pv_girly_clouds),
                MeloXTextPVStyle.SweetPink to stringResource(R.string.settings_pv_sweet_pink),
                MeloXTextPVStyle.FlyMeToTheMoon to "Fly Me to the Moon",
                MeloXTextPVStyle.KawaiiPixel to stringResource(R.string.settings_pv_kawaii_pixel),
                MeloXTextPVStyle.CrimeScene to stringResource(R.string.settings_pv_crime_scene),
                MeloXTextPVStyle.Haruhikage to stringResource(R.string.settings_pv_haruhikage),
            ).forEach { (style, title) ->
                SettingsChoiceRow(title, pvStyle == style) {
                    MeloXSettingsPreferences.setString(context, "lyrics_text_pv_style", style.name)
                    pvStyle = style
                    pvAnimationSpeed = style.referenceAnimationSpeed
                }
            }
        }
        Spacer(Modifier.height(10.dp))
        SettingsFloatSlider(stringResource(R.string.settings_pv_motion_intensity), pvMotionIntensity, 0f..2f, 19) {
            pvMotionIntensity = it; MeloXSettingsPreferences.setFloat(context, "lyrics_text_pv_motion_intensity", it)
        }
        SettingsFloatSlider(stringResource(R.string.settings_pv_animation_speed), pvAnimationSpeed, 0f..4f, 39) {
            pvAnimationSpeed = it; MeloXSettingsPreferences.setFloat(context, "lyrics_text_pv_animation_speed", it)
        }
        Spacer(Modifier.height(10.dp))
        SettingsActionButton(stringResource(R.string.settings_pv_reset)) {
            MeloXSettingsPreferences.setString(context, "lyrics_text_pv_style", MeloXTextPVStyle.BlueBold.name)
            MeloXSettingsPreferences.setFloat(context, "lyrics_text_pv_motion_intensity", 1f)
            MeloXSettingsPreferences.setFloat(context, "lyrics_text_pv_animation_speed", 2f)
            pvStyle = MeloXTextPVStyle.BlueBold; pvMotionIntensity = 1f; pvAnimationSpeed = 2f
        }
    }

    // Group 2: 显示翻译-翻译歌词大小-翻译歌词亮度
    Spacer(Modifier.height(10.dp))
    SettingsGlassGroup {
        SettingsToggleRow(context, stringResource(R.string.settings_show_translation), "lyrics_translation", true, grouped = true)
        LyricsFloatChoiceSetting(context, stringResource(R.string.settings_translation_size), "lyrics_translation_font_scale", .65f, listOf(.5f, .55f, .6f, .65f, .7f, .75f, .8f), grouped = true) { context.getString(R.string.settings_unit_percent, (it * 100).toInt()) }
        LyricsFloatChoiceSetting(context, stringResource(R.string.settings_translation_brightness), "lyrics_translation_opacity", .9f, listOf(.4f, .5f, .6f, .7f, .8f, .9f), grouped = true) { context.getString(R.string.settings_unit_percent, (it * 100).toInt()) }
        LyricsStringChoiceSetting(
            context, stringResource(R.string.settings_translation_scope), "lyrics_translation_display_mode",
            MeloXLyricAnnotationDisplayMode.AllLines.name, MeloXLyricAnnotationDisplayMode.entries.map { it.name }, grouped = true,
        ) { if (it == MeloXLyricAnnotationDisplayMode.FocusedLine.name) context.getString(R.string.settings_annotation_focused) else context.getString(R.string.settings_annotation_all) }
        SettingsToggleRow(context, stringResource(R.string.settings_show_romanization), "lyrics_romanization", false, grouped = true)
        LyricsStringChoiceSetting(
            context, stringResource(R.string.settings_romanization_scope), "lyrics_romanization_display_mode",
            MeloXLyricAnnotationDisplayMode.FocusedLine.name, MeloXLyricAnnotationDisplayMode.entries.map { it.name }, grouped = true,
        ) { if (it == MeloXLyricAnnotationDisplayMode.FocusedLine.name) context.getString(R.string.settings_annotation_focused) else context.getString(R.string.settings_annotation_all) }
        LyricsFloatChoiceSetting(context, stringResource(R.string.settings_romanization_size), "lyrics_romanization_font_scale", .65f, listOf(.5f, .55f, .6f, .65f, .7f, .75f, .8f), grouped = true) { context.getString(R.string.settings_unit_percent, (it * 100).toInt()) }
        LyricsFloatChoiceSetting(context, stringResource(R.string.settings_romanization_brightness), "lyrics_romanization_opacity", .9f, listOf(.4f, .5f, .6f, .7f, .8f, .9f), grouped = true) { context.getString(R.string.settings_unit_percent, (it * 100).toInt()) }
    }

    // Group 3: 歌词提前量-提前量同时应用于逐字高亮-歌词刷新率-歌词字号-歌词字重-抬升方式
    Spacer(Modifier.height(10.dp))
    SettingsGlassGroup {
        LyricsChoiceSetting(context, stringResource(R.string.settings_lyrics_advance), "lyrics_advance_ms", 0, listOf(-1_000, -500, -200, 0, 200, 500, 1_000, 2_000, 5_000), grouped = true) { value ->
            if (value == 0) context.getString(R.string.player_lyric_sync) else if (value > 0) context.getString(R.string.settings_lyrics_ahead_ms, value) else context.getString(R.string.settings_lyrics_behind_ms, -value)
        }
        SettingsToggleRow(context, stringResource(R.string.settings_lyrics_advance_words), "lyrics_advance_word_by_word", false, grouped = true)
        LyricsChoiceSetting(context, stringResource(R.string.settings_lyrics_refresh_rate), "lyrics_refresh_rate", 60, listOf(30, 60, 90, 120), grouped = true) { context.getString(R.string.settings_fps, it) }
        LyricsFloatChoiceSetting(context, stringResource(R.string.settings_lyrics_font_size), "lyrics_font_scale", 1f, listOf(.85f, 1f, 1.12f, 1.25f), grouped = true) { context.getString(R.string.settings_unit_percent, (it * 100).toInt()) }
        LyricsStringChoiceSetting(
            context, stringResource(R.string.settings_lyrics_font_weight), "lyrics_font_weight", MeloXLyricsFontWeight.Heavy.name,
            MeloXLyricsFontWeight.entries.map { it.name }, grouped = true,
        ) { value ->
            when (MeloXLyricsFontWeight.valueOf(value)) {
                MeloXLyricsFontWeight.Light -> context.getString(R.string.settings_weight_light)
                MeloXLyricsFontWeight.Regular -> context.getString(R.string.settings_weight_regular)
                MeloXLyricsFontWeight.Medium -> context.getString(R.string.settings_weight_medium)
                MeloXLyricsFontWeight.SemiBold -> context.getString(R.string.settings_weight_semibold)
                MeloXLyricsFontWeight.Bold -> context.getString(R.string.settings_weight_bold)
                MeloXLyricsFontWeight.Heavy -> context.getString(R.string.settings_weight_heavy)
            }
        }
        LyricsStringChoiceSetting(
            context, stringResource(R.string.settings_lyrics_lift), "lyrics_lift_mode", MeloXLyricsGroupingMode.Character.name,
            MeloXLyricsGroupingMode.entries.map { it.name }, grouped = true,
        ) { if (it == MeloXLyricsGroupingMode.Word.name) context.getString(R.string.settings_lift_word) else context.getString(R.string.settings_lift_character) }
    }

    // Group 4: 长音识别方式-逐字歌词光效-仅长音显示光晕-逐字光晕-长音延展-长音判定时长-行间距-远近模糊
    Spacer(Modifier.height(10.dp))
    SettingsGlassGroup {
        LyricsStringChoiceSetting(
            context, stringResource(R.string.settings_long_tone_detection), "lyrics_long_tone_detection", MeloXLyricsGroupingMode.Character.name,
            MeloXLyricsGroupingMode.entries.map { it.name }, grouped = true,
        ) { if (it == MeloXLyricsGroupingMode.Word.name) context.getString(R.string.settings_detect_word) else context.getString(R.string.settings_detect_character) }
        SettingsToggleRow(context, stringResource(R.string.settings_lyrics_glow), "lyrics_glow_enabled", true, grouped = true)
        SettingsToggleRow(context, stringResource(R.string.settings_glow_long_tones_only), "lyrics_glow_long_tones_only", true, grouped = true)
        LyricsFloatChoiceSetting(context, stringResource(R.string.settings_glow_strength), "lyrics_glow_strength", 1f, listOf(0f, .6f, 1f, 1.4f), grouped = true) { if (it == 0f) context.getString(R.string.settings_skyline_off) else context.getString(R.string.settings_unit_percent, (it * 100).toInt()) }
        LyricsFloatChoiceSetting(context, stringResource(R.string.settings_long_tone_stretch), "lyrics_long_tone_strength", 1f, listOf(0f, .6f, 1f, 1.4f), grouped = true) { if (it == 0f) context.getString(R.string.settings_skyline_off) else context.getString(R.string.settings_unit_percent, (it * 100).toInt()) }
        LyricsChoiceSetting(context, stringResource(R.string.settings_long_tone_threshold), "lyrics_long_tone_threshold_ms", 950, listOf(300, 500, 700, 950, 1_200, 1_500), grouped = true) { context.getString(R.string.settings_seconds_float, it / 1000f) }
        LyricsFloatChoiceSetting(context, stringResource(R.string.settings_line_spacing), "lyrics_spacing_scale", 1f, listOf(.8f, 1f, 1.2f, 1.4f), grouped = true) { context.getString(R.string.settings_unit_percent, (it * 100).toInt()) }
        LyricsFloatChoiceSetting(context, stringResource(R.string.settings_depth_blur), "lyrics_blur_strength", 1f, listOf(0f, .5f, .8f, 1f), grouped = true) { if (it == 0f) context.getString(R.string.settings_skyline_off) else context.getString(R.string.settings_unit_percent, (it * 100).toInt()) }
    }

    // Group 5: 当前行放大-未播放文字亮度-控制栏自动隐藏-滚动隐藏UI阈值
    Spacer(Modifier.height(10.dp))
    SettingsGlassGroup {
        LyricsFloatChoiceSetting(context, stringResource(R.string.settings_focus_scale), "lyrics_focus_scale", 1.02f, listOf(1f, 1.02f, 1.04f, 1.08f), grouped = true) { context.getString(R.string.settings_unit_percent, (it * 100).toInt()) }
        LyricsFloatChoiceSetting(context, stringResource(R.string.settings_inactive_opacity), "lyrics_inactive_opacity", .42f, listOf(.3f, .42f, .5f, .6f), grouped = true) { context.getString(R.string.settings_unit_percent, (it * 100).toInt()) }
        LyricsChoiceSetting(context, stringResource(R.string.settings_controls_auto_hide), "lyrics_interface_auto_hide_ms", 5_000, (3..15).map { it * 1_000 }, grouped = true) { context.getString(R.string.settings_seconds, it / 1_000) }
        LyricsChoiceSetting(context, stringResource(R.string.settings_scroll_hide_threshold), "lyrics_scroll_hide_threshold_dp", 200, listOf(40, 80, 120, 160, 200, 240), grouped = true) { context.getString(R.string.settings_unit_dp, it) }
    }

    // Group 6: 启用位移回弹-启用升格回弹-升格回弹时长-焦点回弹时长
    Spacer(Modifier.height(10.dp))
    SettingsGlassGroup {
        SettingsToggleRow(context, stringResource(R.string.settings_cascade_bounce_enabled), "lyrics_cascade_bounce_enabled", true, grouped = true)
        SettingsToggleRow(context, stringResource(R.string.settings_scale_bounce_enabled), "lyrics_scale_bounce_enabled", true, grouped = true)
        LyricsChoiceSetting(context, stringResource(R.string.settings_scale_bounce_duration), "lyrics_scale_bounce_duration_ms", 580, listOf(150, 250, 350, 450, 580, 700, 800), grouped = true) { context.getString(R.string.settings_duration_ms, it) }
        LyricsChoiceSetting(context, stringResource(R.string.settings_focus_bounce_duration), "lyrics_focus_color_lead_ms", 0, listOf(-300, -200, -100, -50, 0, 50, 100, 200, 300), grouped = true) { if (it == 0) context.getString(R.string.player_lyric_sync) else context.getString(R.string.settings_signed_ms, it) }
    }

    // Group 7: 最大回弹弹性-回弹强度梯度-升格回弹弹性
    Spacer(Modifier.height(10.dp))
    PreferenceFloatSlider(context, stringResource(R.string.settings_max_cascade_bounce), "lyrics_cascade_bounce", .26f, 0f..8f / 10f, 79) { context.getString(R.string.settings_unit_percent, (it * 100).toInt()) }
    PreferenceFloatSlider(context, stringResource(R.string.settings_bounce_gradient), "lyrics_cascade_bounce_gradient", .85f, 0f..1f, 99) { context.getString(R.string.settings_unit_percent, (it * 100).toInt()) }
    PreferenceFloatSlider(context, stringResource(R.string.settings_scale_bounce_amount), "lyrics_scale_bounce", .32f, 0f..5f / 10f, 49) { context.getString(R.string.settings_unit_percent, (it * 100).toInt()) }

    // Group 8: 高光渐变宽度-渐变削减程度
    Spacer(Modifier.height(10.dp))
    PreferenceFloatSlider(context, stringResource(R.string.settings_highlight_width), "lyrics_highlight_gradient_width", .7f, .4f..3f, 25) { context.getString(R.string.settings_char_widths, it) }
    PreferenceFloatSlider(context, stringResource(R.string.settings_gradient_reduction), "lyrics_highlight_gradient_reduction", .65f, 0f..1f, 19) { context.getString(R.string.settings_unit_percent, (it * 100).toInt()) }

    // Group 9: 焦点垂直位置-默认逐句模糊加强-隐藏UI逐句模糊加强-非焦点歌词变暗
    Spacer(Modifier.height(10.dp))
    PreferenceFloatSlider(context, stringResource(R.string.settings_focus_position), "lyrics_focus_position", .25f, .05f..8f / 10f, 74) { context.getString(R.string.settings_focus_from_top, (it * 100).toInt()) }
    PreferenceFloatSlider(context, stringResource(R.string.settings_distance_blur), "lyrics_distance_blur_scale", 1.05f, 0f..1.5f, 29) { context.getString(R.string.settings_unit_percent, (it * 100).toInt()) }
    PreferenceFloatSlider(context, stringResource(R.string.settings_hidden_blur), "lyrics_hidden_blur_scale", .85f, 0f..1.5f, 29) { context.getString(R.string.settings_unit_percent, (it * 100).toInt()) }
    PreferenceFloatSlider(context, stringResource(R.string.settings_dim_amount), "lyrics_dim_amount", 1f, 0f..1f, 49) { context.getString(R.string.settings_unit_percent, (it * 100).toInt()) }

    // Group 10: 基础拖尾延迟-逐句拖尾增量-后续歌词启动延迟-拖尾追赶节奏-追赶速度梯度-位移收束时长
    Spacer(Modifier.height(10.dp))
    PreferenceFloatSlider(context, stringResource(R.string.settings_cascade_delay), "lyrics_cascade_delay_ms", 21f, 0f..100f, 99) { context.getString(R.string.settings_milliseconds, it.toInt()) }
    PreferenceFloatSlider(context, stringResource(R.string.settings_cascade_delay_increase), "lyrics_cascade_delay_increase_ms", 5f, 0f..100f, 99) { context.getString(R.string.settings_ms_per_line, it.toInt()) }
    PreferenceFloatSlider(context, stringResource(R.string.settings_following_delay), "lyrics_cascade_following_delay_ms", 30f, 0f..200f, 199) { context.getString(R.string.settings_milliseconds, it.toInt()) }
    PreferenceFloatSlider(context, stringResource(R.string.settings_catch_up_ratio), "lyrics_cascade_catch_up_ratio", .97f, .5f..1f, 49) { context.getString(R.string.settings_unit_percent, (it * 100).toInt()) }
    PreferenceFloatSlider(context, stringResource(R.string.settings_chase_gradient), "lyrics_cascade_chase_gradient", .70f, 0f..1f, 99) { context.getString(R.string.settings_unit_percent, (it * 100).toInt()) }
    PreferenceFloatSlider(context, stringResource(R.string.settings_cascade_duration), "lyrics_cascade_duration_ms", 740f, 200f..1_200f, 99) { context.getString(R.string.settings_seconds_2f, it / 1_000f) }

    // Group 11: 瞬移阈值
    Spacer(Modifier.height(10.dp))
    PreferenceFloatSlider(context, stringResource(R.string.settings_snap_threshold), "lyrics_snap_threshold_ms", 260f, 50f..500f, 89) { context.getString(R.string.settings_milliseconds, it.toInt()) }
}

@Composable
private fun LyricsStringChoiceSetting(
    context: android.content.Context,
    title: String,
    key: String,
    default: String,
    values: List<String>,
    grouped: Boolean = false,
    label: (String) -> String,
) {
    val groupRowIndex = LocalSettingsGroupRowIndex.current
    val showSep = if (grouped) groupRowIndex.intValue++.let { it > 0 } else false
    var selected by remember(key) { mutableStateOf(MeloXSettingsPreferences.string(context, key, default)) }
    MeloXSettingsDropdown(
        title = title,
        selected = selected,
        items = values.map { it to label(it) },
        onSelected = {
            selected = it
            MeloXSettingsPreferences.setString(context, key, it)
        },
        grouped = grouped,
        showTopSeparator = showSep,
    )
    if (!grouped) Spacer(Modifier.height(10.dp))
}

@Composable
private fun LyricsChoiceSetting(
    context: android.content.Context,
    title: String,
    key: String,
    default: Int,
    values: List<Int>,
    grouped: Boolean = false,
    label: (Int) -> String,
) {
    val groupRowIndex = LocalSettingsGroupRowIndex.current
    val effectiveShowTopSeparator = if (grouped) groupRowIndex.intValue++.let { it > 0 } else false
    var selected by remember(key) { mutableStateOf(MeloXSettingsPreferences.int(context, key, default)) }
    MeloXSettingsDropdown(
        title = title,
        selected = selected,
        items = values.map { it to label(it) },
        onSelected = {
            selected = it
            MeloXSettingsPreferences.setInt(context, key, it)
        },
        grouped = grouped,
        showTopSeparator = effectiveShowTopSeparator,
    )
    if (!grouped) Spacer(Modifier.height(10.dp))
}

@Composable
private fun LyricsFloatChoiceSetting(
    context: android.content.Context,
    title: String,
    key: String,
    default: Float,
    values: List<Float>,
    grouped: Boolean = false,
    label: (Float) -> String,
) {
    val groupRowIndex = LocalSettingsGroupRowIndex.current
    val effectiveShowTopSeparator = if (grouped) groupRowIndex.intValue++.let { it > 0 } else false
    var selected by remember(key) { mutableStateOf(MeloXSettingsPreferences.float(context, key, default)) }
    val selectedValue = values.minByOrNull { kotlin.math.abs(selected - it) } ?: default
    MeloXSettingsDropdown(
        title = title,
        selected = selectedValue,
        items = values.map { it to label(it) },
        onSelected = {
            selected = it
            MeloXSettingsPreferences.setFloat(context, key, it)
        },
        grouped = grouped,
        showTopSeparator = effectiveShowTopSeparator,
    )
    if (!grouped) Spacer(Modifier.height(10.dp))
}

@Composable
private fun SettingsFloatSlider(
    title: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    steps: Int,
    label: (Float) -> String = { "${(it * 100).toInt()}%" },
    onValueChange: (Float) -> Unit,
) {
    // Show the value the finger is currently on while dragging; the setting is
    // still only committed once the drag ends.
    var draggingValue by remember { mutableStateOf<Float?>(null) }
    val shownValue = draggingValue ?: value
    Row(
        // Match MeloXIosListRow's 16dp gutter so the label lines up with the
        // neighbouring setting titles.
        modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 12.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(title, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = .48f))
        Text(label(shownValue), fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = .58f))
    }
    MeloXLiquidSlider(
        value = value,
        onValueChange = onValueChange,
        onTransientValueChange = { draggingValue = it },
        onValueChangeFinished = { draggingValue = null },
        valueRange = range,
        stepSize = if (steps > 0) (range.endInclusive - range.start) / (steps + 1) else 0f,
        visibilityThreshold = 0.001f,
        modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp),
    )
}

@Composable
private fun PreferenceFloatSlider(
    context: android.content.Context,
    title: String,
    key: String,
    default: Float,
    range: ClosedFloatingPointRange<Float>,
    steps: Int,
    label: (Float) -> String,
) {
    var value by remember(key) { mutableStateOf(MeloXSettingsPreferences.float(context, key, default)) }
    SettingsFloatSlider(title, value, range, steps, label) {
        value = it
        MeloXSettingsPreferences.setFloat(context, key, it)
    }
}

@Composable
private fun ContentFeatureSettings(context: android.content.Context) {
    SettingsGlassGroup {
        SettingsToggleRow(context, stringResource(R.string.settings_feature_podcasts), "feature_podcasts", true, grouped = true)
        SettingsToggleRow(context, stringResource(R.string.settings_feature_history), "feature_history", true, grouped = true)
        SettingsToggleRow(context, stringResource(R.string.settings_feature_downloads), "feature_downloads", true, stringResource(R.string.settings_feature_downloads_note), grouped = true)
        SettingsToggleRow(context, stringResource(R.string.settings_feature_cloud), "feature_cloud_music", true, stringResource(R.string.settings_feature_cloud_note), grouped = true)
    }
}

@Composable
private fun MessagesSettings(context: android.content.Context) {
    val ops = remember(context) {
        NeteaseMusicOperationsClient(cookieProvider = { NeteaseSessionStore.readCookie(context) })
    }
    val account = remember(context) {
        NeteaseSearchClient(cookieProvider = { NeteaseSessionStore.readCookie(context) })
    }
    val scope = rememberCoroutineScope()
    var contacts by remember { mutableStateOf<List<MeloXMessageContact>>(emptyList()) }
    var selected by remember { mutableStateOf<MeloXMessageContact?>(null) }
    var messages by remember { mutableStateOf<List<MeloXPrivateMessage>>(emptyList()) }
    var currentUserId by remember { mutableStateOf(0L) }
    var draft by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var reload by remember { mutableStateOf(0) }

    LaunchedEffect(selected?.id, reload) {
        busy = true
        error = null
        runCatching {
            val profile = account.accountProfile()
            currentUserId = profile.userId
            val contact = selected
            if (contact == null) {
                val recent = ops.privateMessageConversations(profile.userId)
                val follows = ops.messageContacts(profile.userId)
                contacts = (recent + follows).filter { it.id != profile.userId }.distinctBy(MeloXMessageContact::id)
            } else {
                messages = ops.privateMessageHistory(contact.id)
            }
        }.onFailure { error = it.message ?: context.getString(R.string.settings_messages_load_failed) }
        busy = false
    }

    if (selected == null) {
        Text(stringResource(R.string.settings_messages_contacts), fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = .48f))
        Spacer(Modifier.height(8.dp))
        if (busy) Row(verticalAlignment = Alignment.CenterVertically) {
            CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
            Spacer(Modifier.size(10.dp))
            Text(stringResource(R.string.settings_messages_loading), color = MaterialTheme.colorScheme.onSurface.copy(alpha = .55f))
        }
        error?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(vertical = 8.dp)) }
        LazyColumn(Modifier.fillMaxWidth().height(440.dp)) {
            items(contacts.take(100), key = MeloXMessageContact::id) { contact ->
                Row(
                    Modifier.fillMaxWidth().clickable { selected = contact }.padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    AsyncImage(contact.avatarUrl, null, Modifier.size(42.dp).clip(CircleShape))
                    Spacer(Modifier.size(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(contact.name, fontWeight = FontWeight.SemiBold, maxLines = 1)
                        if (contact.signature.isNotBlank()) Text(contact.signature, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSurface.copy(alpha = .45f))
                    }
                    MeloXActionIcon("›", Modifier.size(18.dp), MaterialTheme.colorScheme.onSurface.copy(alpha = .3f))
                }
            }
        }
        return
    }

    val contact = selected!!
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        SettingsRoundButton("‹") { selected = null; messages = emptyList(); draft = "" }
        Spacer(Modifier.size(12.dp))
        AsyncImage(contact.avatarUrl, null, Modifier.size(38.dp).clip(CircleShape))
        Spacer(Modifier.size(10.dp))
        Text(contact.name, fontSize = 20.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.weight(1f))
        Text(stringResource(R.string.settings_messages_refresh), Modifier.clickable { reload++ }.padding(8.dp), color = MaterialTheme.colorScheme.primary)
    }
    Spacer(Modifier.height(14.dp))
    if (busy && messages.isEmpty()) CircularProgressIndicator(Modifier.size(24.dp))
    error?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(vertical = 8.dp)) }
    LazyColumn(Modifier.fillMaxWidth().height(420.dp)) {
        items(messages.takeLast(60), key = MeloXPrivateMessage::id) { message ->
            val outgoing = message.fromUserId == currentUserId
            Row(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                if (outgoing) Spacer(Modifier.weight(.2f))
                Text(
                    message.text,
                    modifier = Modifier.background(
                        if (outgoing) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onBackground.copy(alpha = .08f),
                        RoundedCornerShape(16.dp),
                    ).padding(horizontal = 12.dp, vertical = 9.dp).weight(.8f, fill = false),
                    color = if (outgoing) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onBackground,
                    fontSize = 14.sp,
                )
                if (!outgoing) Spacer(Modifier.weight(.2f))
            }
        }
    }
    Spacer(Modifier.height(12.dp))
    Row(
        Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.onBackground.copy(alpha = .06f), RoundedCornerShape(22.dp)).padding(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BasicTextField(
            value = draft,
            onValueChange = { draft = it },
            textStyle = androidx.compose.ui.text.TextStyle(color = MaterialTheme.colorScheme.onBackground, fontSize = 15.sp),
            modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
            decorationBox = { inner ->
                Box {
                    if (draft.isBlank()) Text(stringResource(R.string.settings_messages_compose), color = MaterialTheme.colorScheme.onSurface.copy(alpha = .38f))
                    inner()
                }
            },
        )
        Text(
            if (busy) "…" else stringResource(R.string.settings_messages_send),
            color = MaterialTheme.colorScheme.primary,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.clickable(enabled = !busy && draft.isNotBlank()) {
                val text = draft.trim()
                busy = true
                scope.launch {
                    runCatching { ops.sendPrivateText(text, contact.id) }
                        .onSuccess { draft = ""; messages = ops.privateMessageHistory(contact.id) }
                        .onFailure { error = it.message ?: context.getString(R.string.settings_messages_send_failed) }
                    busy = false
                }
            }.padding(horizontal = 10.dp, vertical = 8.dp),
        )
    }
}

@Composable
private fun ContentSettings(context: android.content.Context) {
    var area by remember { mutableStateOf(MeloXSettingsRuntime.musicArea) }
    var crossProviderFallback by remember {
        mutableStateOf(CrossProviderPlaybackPreferences.enabled(context))
    }
    var showCrossProviderFallbackNotice by remember { mutableStateOf(false) }
    SettingsGlassGroup {
        MeloXSettingsDropdown(
            title = stringResource(R.string.settings_content_area),
            selected = area,
            items = listOf(
                "全部" to stringResource(R.string.settings_content_area_all),
                "华语" to stringResource(R.string.settings_content_area_chinese),
                "欧美" to stringResource(R.string.settings_content_area_western),
                "日本" to stringResource(R.string.settings_content_area_japan),
                "韩国" to stringResource(R.string.settings_content_area_korea),
            ),
            onSelected = { area = it; MeloXSettingsPreferences.setString(context, "music_area", it) },
            grouped = true,
        )
        SettingsToggleRow(context, stringResource(R.string.settings_content_hq_playlists), "content_high_quality_playlist", true, grouped = true)
        SettingsToggleRow(context, stringResource(R.string.settings_content_play_count), "content_playlist_play_count", true, grouped = true)
        SettingsExternalToggleRow(
            title = stringResource(R.string.settings_content_cross_provider),
            value = crossProviderFallback,
            grouped = true,
        ) { enabled ->
            if (enabled) {
                showCrossProviderFallbackNotice = true
            } else {
                crossProviderFallback = false
                CrossProviderPlaybackPreferences.setEnabled(context, false)
            }
        }
    }
    if (crossProviderFallback) {
        Spacer(Modifier.height(10.dp))
        SettingsInfoCard(
            stringResource(R.string.settings_content_cross_provider_active),
        )
    }
    if (showCrossProviderFallbackNotice) {
        MeloXGlassDialog(
            visible = true,
            onDismiss = { showCrossProviderFallbackNotice = false },
        ) {
            Text(stringResource(R.string.settings_content_cross_provider_title), style = MaterialTheme.typography.titleMedium)
            Text(
                text = stringResource(R.string.settings_content_cross_provider_body),
                modifier = Modifier.padding(top = 10.dp),
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = .72f),
                fontSize = 13.sp,
                lineHeight = 19.sp,
            )
            Text(
                text = stringResource(R.string.settings_content_cross_provider_match),
                modifier = Modifier.padding(top = 8.dp),
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = .72f),
                fontSize = 13.sp,
                lineHeight = 19.sp,
            )
            Text(
                text = stringResource(R.string.settings_content_cross_provider_terms),
                modifier = Modifier.padding(top = 8.dp),
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = .56f),
                fontSize = 12.sp,
                lineHeight = 18.sp,
            )
            MeloXLegalLinks(modifier = Modifier.padding(top = 6.dp))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                SettingsActionButton(stringResource(R.string.action_cancel), Modifier.weight(1f)) {
                    showCrossProviderFallbackNotice = false
                }
                SettingsActionButton(stringResource(R.string.settings_content_cross_provider_enable), Modifier.weight(1f)) {
                    crossProviderFallback = true
                    CrossProviderPlaybackPreferences.setEnabled(context, true)
                    showCrossProviderFallbackNotice = false
                }
            }
        }
    }
}

@Composable
private fun ListenTogetherSettings(context: android.content.Context) {
    val app = context.applicationContext
    val state by MeloXListenTogetherCoordinator.state(app).collectAsState()
    val room = state.room
    val scope = rememberCoroutineScope()
    val ops = remember(app) {
        NeteaseMusicOperationsClient(cookieProvider = { NeteaseSessionStore.readCookie(app) })
    }
    var invitation by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }

    SettingsInfoCard(stringResource(R.string.settings_listen_info))
    Spacer(Modifier.height(12.dp))
    if (room == null) {
        SettingsActionButton(stringResource(R.string.settings_listen_start)) {
            if (!busy) {
                busy = true
                message = null
                scope.launch {
                    runCatching { ops.createListenTogetherRoom() }
                        .onSuccess { MeloXListenTogetherCoordinator.adoptRoom(app, it) }
                        .onFailure { message = it.message ?: context.getString(R.string.settings_listen_create_failed) }
                    busy = false
                }
            }
        }
        Spacer(Modifier.height(10.dp))
        MeloXGlassTextField(
            value = invitation,
            onValueChange = { invitation = it },
            placeholder = { Text(stringResource(R.string.settings_listen_paste), color = MaterialTheme.colorScheme.onSurface.copy(alpha = .4f)) },
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(10.dp))
        SettingsActionButton(stringResource(R.string.settings_listen_join)) {
            val parsed = parseNeteaseListenTogetherInvitation(invitation)
            if (parsed == null) {
                message = context.getString(R.string.settings_listen_invite_invalid)
            } else if (!busy) {
                busy = true
                message = null
                scope.launch {
                    runCatching { ops.joinListenTogetherRoom(parsed.roomId, parsed.inviterId) }
                        .onSuccess { MeloXListenTogetherCoordinator.adoptRoom(app, it) }
                        .onFailure { message = it.message ?: context.getString(R.string.settings_listen_join_failed) }
                    busy = false
                }
            }
        }
    } else {
        SettingsGlassGroup {
            MeloXIosListRow(stringResource(R.string.settings_listen_room), detail = room.id, showTopSeparator = false)
            MeloXIosListRow(stringResource(R.string.settings_listen_members), detail = stringResource(R.string.settings_listen_member_count, room.users.size.coerceAtLeast(1)))
            room.users.forEach { user -> MeloXIosListRow(user.name) }
            MeloXIosListRow(
                stringResource(R.string.settings_listen_connection),
                detail = when (state.phase) {
                    MeloXListenTogetherCoordinator.Phase.Connected -> stringResource(R.string.settings_listen_synced)
                    MeloXListenTogetherCoordinator.Phase.Reconnecting -> stringResource(R.string.settings_listen_reconnecting)
                    MeloXListenTogetherCoordinator.Phase.Idle -> stringResource(R.string.settings_listen_resuming)
                },
            )
        }
        Spacer(Modifier.height(10.dp))
        SettingsActionButton(stringResource(R.string.settings_listen_share)) {
            val inviter = room.users.firstOrNull()?.id ?: room.creatorId
            val url = "https://music.163.com/listen-together/share/?roomId=${room.id}&inviterId=$inviter"
            context.startActivity(
                Intent.createChooser(
                    Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, url),
                    context.getString(R.string.settings_listen_share_chooser),
                ),
            )
        }
        Spacer(Modifier.height(10.dp))
        SettingsDangerButton(stringResource(R.string.settings_listen_end)) {
            if (!busy) {
                busy = true
                scope.launch {
                    runCatching { ops.endListenTogetherRoom(room.id) }
                        .onSuccess { MeloXListenTogetherCoordinator.clearRoom(app) }
                        .onFailure { message = it.message ?: context.getString(R.string.settings_listen_leave_failed) }
                    busy = false
                }
            }
        }
    }
    if (busy) {
        Row(Modifier.fillMaxWidth().padding(top = 12.dp), horizontalArrangement = Arrangement.Center) {
            CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
        }
    }
    (message ?: state.lastError)?.let {
        Text(it, color = MaterialTheme.colorScheme.error, fontSize = 12.sp, modifier = Modifier.padding(top = 10.dp))
    }
}

@Composable
private fun StorageSettings(context: android.content.Context) {
    var usage by remember { mutableStateOf(MeloXStorageUsage()) }
    var loading by remember { mutableStateOf(true) }
    var maintenanceMessage by remember { mutableStateOf<String?>(null) }
    var confirmation by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val downloads = remember(context) { MeloXDownloadStore.get(context) }
    suspend fun refresh() {
        loading = true
        usage = withContext(Dispatchers.IO) {
            val stats = StatFs(context.filesDir.path)
            MeloXStorageUsage(
                downloads = downloads.totalByteCount,
                networkCache = listOf("melox_http", "image_cache", "coil3_disk_cache")
                    .sumOf { context.cacheDir.resolve(it).treeByteCount() },
                playbackCache = context.cacheDir.resolve("melox_media").treeByteCount(),
                temporary = context.cacheDir.resolve("automix_analysis").treeByteCount(),
                localData = listOf(
                    context.filesDir.resolve("automix_analysis_index.json"),
                    context.filesDir.resolve("automix_analysis"),
                    context.filesDir.resolve("netease_library_cache"),
                ).sumOf { it.treeByteCount() },
                deviceTotal = stats.totalBytes,
                deviceAvailable = stats.availableBytes,
            )
        }
        loading = false
    }
    LaunchedEffect(Unit) { refresh() }

    var autoCache by remember { mutableStateOf(MeloXSettingsPreferences.boolean(context, "downloads_auto_cache", false)) }

    val deviceUsed = (usage.deviceTotal - usage.deviceAvailable).coerceAtLeast(0L)
    val deviceFraction = if (usage.deviceTotal > 0L) deviceUsed.toFloat() / usage.deviceTotal else 0f
    Box(Modifier.fillMaxWidth().meloXContentSurface(MeloXShapes.largeCard).padding(20.dp)) {
        Column(verticalArrangement = Arrangement.spacedBy(9.dp)) {
            MeloXSymbolIcon(MeloXSymbol.Storage, Modifier.size(30.dp), MaterialTheme.colorScheme.onSurface)
            Text(stringResource(R.string.settings_storage_managed), color = MaterialTheme.colorScheme.onSurface.copy(alpha = .55f))
            Text(if (loading) stringResource(R.string.settings_storage_calculating) else formatBytes(usage.managed), fontSize = 34.sp, fontWeight = FontWeight.Bold)
            LinearProgressIndicator(
                progress = { deviceFraction.coerceIn(0f, 1f) },
                modifier = Modifier.fillMaxWidth().height(6.dp).clip(CircleShape),
            )
            Text(
                stringResource(R.string.settings_storage_device, formatBytes(deviceUsed), formatBytes(usage.deviceAvailable)),
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = .50f),
            )
            Text(stringResource(R.string.settings_storage_reclaimable, formatBytes(usage.reclaimable)), fontSize = 12.sp)
        }
    }
    Spacer(Modifier.height(18.dp))
    Text(stringResource(R.string.settings_storage_items), fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = .48f))
    Spacer(Modifier.height(8.dp))
    SettingsGlassGroup {
        StorageUsageRow(MeloXSymbol.Download, stringResource(R.string.settings_storage_downloads), usage.downloads, false)
        StorageUsageRow(MeloXSymbol.Apps, stringResource(R.string.settings_storage_network), usage.networkCache, true)
        StorageUsageRow(MeloXSymbol.AutoMix, stringResource(R.string.settings_storage_playback), usage.playbackCache, true)
        StorageUsageRow(MeloXSymbol.RadioWaves, stringResource(R.string.settings_storage_temporary), usage.temporary, true)
        StorageUsageRow(MeloXSymbol.Storage, stringResource(R.string.settings_storage_local), usage.localData, true)
    }
    Spacer(Modifier.height(18.dp))
    Text(stringResource(R.string.settings_storage_cleanup), fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = .48f))
    Spacer(Modifier.height(8.dp))
    SettingsGlassGroup {
        StorageActionRow(MeloXSymbol.Trash, stringResource(R.string.settings_storage_clear_all), false) { confirmation = "all_cache" }
        StorageActionRow(MeloXSymbol.Apps, stringResource(R.string.settings_storage_clear_network), true) { confirmation = "network_cache" }
        StorageActionRow(MeloXSymbol.AutoMix, stringResource(R.string.settings_storage_clear_playback), true) { confirmation = "playback_cache" }
    }
    Spacer(Modifier.height(18.dp))

    SettingsGlassGroup {
        SettingsToggleRow(context, stringResource(R.string.settings_storage_download_lyrics), "download_lyrics", true, stringResource(R.string.settings_storage_download_lyrics_note), grouped = true)
        SettingsExternalToggleRow(stringResource(R.string.settings_storage_auto_cache), autoCache, stringResource(R.string.settings_storage_auto_cache_note), grouped = true) {
            autoCache = it
            MeloXSettingsPreferences.setBoolean(context, "downloads_auto_cache", it)
        }
    }
    if (autoCache) {
        LyricsChoiceSetting(context, stringResource(R.string.settings_storage_threshold), "downloads_auto_cache_threshold", 3, listOf(2, 3, 5, 8, 10)) { context.getString(R.string.settings_storage_plays, it) }
        var cacheQuality by remember { mutableStateOf(MeloXSettingsPreferences.string(context, "downloads_auto_cache_quality", MusicQuality.Standard.name)) }
        Text(stringResource(R.string.settings_storage_auto_quality), fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = .48f))
        Spacer(Modifier.height(8.dp))
        SettingsGlassGroup {
            MusicQuality.entries.forEach { value ->
                SettingsChoiceRow(stringResource(value.localizedTitleRes()), cacheQuality == value.name) {
                    cacheQuality = value.name
                    MeloXSettingsPreferences.setString(context, "downloads_auto_cache_quality", value.name)
                }
            }
        }
        Spacer(Modifier.height(10.dp))
        SettingsActionButton(stringResource(R.string.settings_storage_reset_stats)) { downloads.resetAutomaticCacheHistory() }
    }
    Spacer(Modifier.height(10.dp))

    if (downloads.activeDownloads.isNotEmpty()) {
        Text(stringResource(R.string.settings_storage_downloading), modifier = Modifier.padding(top=10.dp,bottom=8.dp), fontWeight=FontWeight.SemiBold)
        SettingsGlassGroup {
            downloads.activeDownloads.values.forEach { active ->
                Row(Modifier.fillMaxWidth().padding(14.dp), verticalAlignment=Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(active.song.name, maxLines=1, overflow=TextOverflow.Ellipsis)
                        Text(active.fractionCompleted?.let { stringResource(R.string.settings_storage_download_progress, (it * 100).toInt(), stringResource(active.quality.localizedTitleRes())) } ?: stringResource(active.quality.localizedTitleRes()), color=MaterialTheme.colorScheme.onSurface.copy(alpha=.5f), fontSize=11.sp)
                    }
                    Text(stringResource(R.string.settings_storage_cancel), color=MaterialTheme.colorScheme.error, modifier=Modifier.clickable { downloads.cancel(active.song.id) }.padding(8.dp))
                }
            }
        }
    }

    if (downloads.downloads.isNotEmpty()) {
        Spacer(Modifier.height(12.dp))
        SettingsDangerButton(stringResource(R.string.settings_storage_delete_all)) { downloads.removeAll() }
    }
    downloads.errorMessage?.let { Text(it, color=MaterialTheme.colorScheme.error, fontSize=12.sp, modifier=Modifier.padding(top=10.dp)) }
    maintenanceMessage?.let { Text(it, color=MaterialTheme.colorScheme.primary, fontSize=12.sp, modifier=Modifier.padding(top=10.dp)) }
    Spacer(Modifier.height(14.dp))
    SettingsActionButton(stringResource(R.string.settings_storage_repair)) {
        downloads.repairStorage { result ->
            maintenanceMessage = result.fold(
                onSuccess = {
                    context.getString(
                        R.string.settings_storage_repair_done,
                        it.missingRecordsRemoved,
                        it.orphanFilesRemoved,
                        formatBytes(it.recoveredBytes),
                    )
                },
                onFailure = { it.message ?: context.getString(R.string.settings_storage_repair_failed) },
            )
        }
    }
    Spacer(Modifier.height(10.dp))
    SettingsActionButton(stringResource(R.string.settings_storage_clear_temp)) {
        confirmation = "temporary"
    }
    confirmation?.let { action ->
        val (title, message) = when (action) {
            "all_cache" -> stringResource(R.string.settings_storage_confirm_all_title) to stringResource(R.string.settings_storage_confirm_all_body)
            "network_cache" -> stringResource(R.string.settings_storage_confirm_network_title) to stringResource(R.string.settings_storage_confirm_network_body)
            "playback_cache" -> stringResource(R.string.settings_storage_confirm_playback_title) to stringResource(R.string.settings_storage_confirm_playback_body)
            else -> stringResource(R.string.settings_storage_confirm_temp_title) to stringResource(R.string.settings_storage_confirm_temp_body)
        }
        MeloXGlassDialog(visible = true, onDismiss = { confirmation = null }) {
            Text(title, fontSize = 20.sp, fontWeight = FontWeight.Bold)
            Text(message, Modifier.padding(top = 8.dp), color = MaterialTheme.colorScheme.onSurface.copy(alpha = .62f))
            Row(Modifier.fillMaxWidth().padding(top = 18.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                SettingsActionButton(stringResource(R.string.action_cancel), Modifier.weight(1f)) { confirmation = null }
                SettingsActionButton(stringResource(R.string.settings_storage_clear), Modifier.weight(1f)) {
                    confirmation = null
                    scope.launch {
                        withContext(Dispatchers.IO) {
                            when (action) {
                                "all_cache" -> { MeloXHttpClient.clearCache(); MeloXMediaCache.clear(context) }
                                "network_cache" -> MeloXHttpClient.clearCache()
                                "playback_cache" -> MeloXMediaCache.clear(context)
                                else -> context.cacheDir.resolve("automix_analysis").deleteRecursively()
                            }
                        }
                        maintenanceMessage = context.getString(R.string.settings_storage_cleared)
                        refresh()
                    }
                }
            }
        }
    }
}

private data class MeloXStorageUsage(
    val downloads: Long = 0L,
    val networkCache: Long = 0L,
    val playbackCache: Long = 0L,
    val temporary: Long = 0L,
    val localData: Long = 0L,
    val deviceTotal: Long = 0L,
    val deviceAvailable: Long = 0L,
) {
    val managed: Long get() = downloads + networkCache + playbackCache + temporary + localData
    val reclaimable: Long get() = networkCache + playbackCache + temporary
}

@Composable
private fun StorageUsageRow(symbol: MeloXSymbol, title: String, bytes: Long, showSeparator: Boolean) {
    MeloXIosListRow(
        title = title,
        detail = formatBytes(bytes),
        leading = { MeloXSymbolIcon(symbol, Modifier.size(22.dp), MeloXSystemColors.Red) },
        showTopSeparator = showSeparator,
    )
}

@Composable
private fun StorageActionRow(symbol: MeloXSymbol, title: String, showSeparator: Boolean, onClick: () -> Unit) {
    MeloXIosListRow(
        title = title,
        leading = { MeloXSymbolIcon(symbol, Modifier.size(22.dp), MeloXSystemColors.Red) },
        onClick = onClick,
        showTopSeparator = showSeparator,
    )
}

private fun java.io.File.treeByteCount(): Long = when {
    isFile -> length()
    isDirectory -> listFiles()?.sumOf { it.treeByteCount() } ?: 0L
    else -> 0L
}

@androidx.annotation.StringRes
private fun MusicQuality.localizedTitleRes(): Int = when (this) {
    MusicQuality.Standard -> R.string.settings_quality_standard
    MusicQuality.High -> R.string.settings_quality_high
    MusicQuality.Lossless -> R.string.settings_quality_lossless
    MusicQuality.HiResolution -> R.string.settings_quality_hires
    MusicQuality.HighDefinitionSurround -> R.string.settings_quality_surround
    MusicQuality.ImmersiveSurround -> R.string.settings_quality_immersive
    MusicQuality.UltraClearMaster -> R.string.settings_quality_master
}

private fun formatBytes(bytes: Long): String = when {
    bytes >= 1024L * 1024L * 1024L -> "%.2f GB".format(bytes / 1024.0 / 1024.0 / 1024.0)
    bytes >= 1024L * 1024L -> "%.1f MB".format(bytes / 1024.0 / 1024.0)
    bytes >= 1024L -> "%.1f KB".format(bytes / 1024.0)
    else -> "$bytes B"
}

@Composable
private fun TabLayoutSettings(context: android.content.Context) {
    SettingsGlassGroup {
        SettingsToggleRow(context, stringResource(R.string.settings_tabs_home), "tab_home", true, grouped = true)
        SettingsToggleRow(context, stringResource(R.string.settings_tabs_explore), "tab_explore", true, grouped = true)
        SettingsToggleRow(context, stringResource(R.string.settings_tabs_library), "tab_library", true, grouped = true)
    }
    listOf(
        Triple(stringResource(R.string.settings_tabs_podcasts), "podcasts", MeloXSettingsRuntime.podcastsEnabled),
        Triple(stringResource(R.string.settings_tabs_downloads), "downloads", MeloXSettingsRuntime.downloadsEnabled),
        Triple(stringResource(R.string.settings_tabs_cloud), "cloud", MeloXSettingsRuntime.cloudMusicEnabled),
    ).forEach { (title, key, enabled) ->
        Spacer(Modifier.height(10.dp))
        SettingsGlassGroup {
            Text(title, modifier = Modifier.padding(horizontal = 18.dp, vertical = 4.dp), fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = .48f))
            SettingsToggleRow(context, stringResource(R.string.settings_tabs_home_shortcut), "placement_${key}_home", key == "podcasts", if (enabled) null else stringResource(R.string.settings_tabs_enable_first, title), grouped = true)
            SettingsToggleRow(context, stringResource(R.string.settings_tabs_library_page), "placement_${key}_library", true, grouped = true)
            SettingsToggleRow(context, stringResource(R.string.settings_tabs_own_tab), "placement_${key}_tab", false, grouped = true)
        }
    }
    Spacer(Modifier.height(10.dp))
    var order by remember { mutableStateOf(MeloXSettingsRuntime.tabOrder) }
    SettingsGlassGroup {
        Text(stringResource(R.string.settings_tabs_order), modifier = Modifier.padding(horizontal = 18.dp, vertical = 4.dp), fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = .48f))
        order.forEachIndexed { index, page ->
            Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(when (page) { "Home" -> stringResource(R.string.settings_tabs_home); "Explore" -> stringResource(R.string.settings_tabs_explore); "Library" -> stringResource(R.string.settings_tabs_library); "Podcasts" -> stringResource(R.string.settings_tabs_podcasts); "Downloads" -> stringResource(R.string.settings_tabs_downloads); "Cloud" -> stringResource(R.string.settings_tabs_cloud); else -> stringResource(R.string.settings_tabs_settings) }, Modifier.weight(1f))
                MeloXActionIcon("↑", Modifier.size(18.dp).clickable(enabled = index > 0) {
                    order = order.toMutableList().apply { add(index - 1, removeAt(index)) }
                    MeloXSettingsPreferences.setString(context, "tab_order", order.joinToString(","))
                }.padding(10.dp), MaterialTheme.colorScheme.primary.copy(alpha = if (index > 0) 1f else .25f))
                MeloXActionIcon("↓", Modifier.size(18.dp).clickable(enabled = index < order.lastIndex) {
                    order = order.toMutableList().apply { add(index + 1, removeAt(index)) }
                    MeloXSettingsPreferences.setString(context, "tab_order", order.joinToString(","))
                }.padding(10.dp), MaterialTheme.colorScheme.primary.copy(alpha = if (index < order.lastIndex) 1f else .25f))
            }
        }
    }
    Spacer(Modifier.height(10.dp))
    var homeOrder by remember { mutableStateOf(MeloXSettingsRuntime.homeSectionOrder) }
    SettingsGlassGroup {
        Text(stringResource(R.string.settings_tabs_home_sections), modifier = Modifier.padding(horizontal = 18.dp, vertical = 4.dp), fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = .48f))
        SettingsToggleRow(context, stringResource(R.string.settings_tabs_shortcuts), "home_quick_actions", true, grouped = true)
        SettingsToggleRow(context, stringResource(R.string.settings_tabs_playlists), "home_playlists", true, grouped = true)
        SettingsToggleRow(context, stringResource(R.string.settings_tabs_new_songs), "home_new_songs", true, grouped = true)
        SettingsToggleRow(context, stringResource(R.string.settings_tabs_remember_library), "library_remember_page", true, grouped = true)
        homeOrder.forEachIndexed { index, section ->
            Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(when (section) { "QuickActions" -> stringResource(R.string.settings_tabs_shortcuts); "Playlists" -> stringResource(R.string.settings_tabs_playlists); else -> stringResource(R.string.settings_tabs_new_songs) }, Modifier.weight(1f))
                MeloXActionIcon("↑", Modifier.size(18.dp).clickable(enabled = index > 0) {
                    homeOrder = homeOrder.toMutableList().apply { add(index - 1, removeAt(index)) }
                    MeloXSettingsPreferences.setString(context, "home_section_order", homeOrder.joinToString(","))
                }.padding(10.dp), MaterialTheme.colorScheme.primary.copy(alpha = if (index > 0) 1f else .25f))
                MeloXActionIcon("↓", Modifier.size(18.dp).clickable(enabled = index < homeOrder.lastIndex) {
                    homeOrder = homeOrder.toMutableList().apply { add(index + 1, removeAt(index)) }
                    MeloXSettingsPreferences.setString(context, "home_section_order", homeOrder.joinToString(","))
                }.padding(10.dp), MaterialTheme.colorScheme.primary.copy(alpha = if (index < homeOrder.lastIndex) 1f else .25f))
            }
        }
    }
    Spacer(Modifier.height(10.dp))
    var libraryPage by remember { mutableStateOf(MeloXSettingsRuntime.defaultLibraryPage) }
    SettingsGlassGroup {
        MeloXSettingsDropdown(
            title = stringResource(R.string.settings_tabs_default_library),
            selected = libraryPage,
            items = listOf(
                "Songs" to stringResource(R.string.settings_tabs_songs),
                "Playlists" to stringResource(R.string.settings_tabs_playlist_page),
                "Podcasts" to stringResource(R.string.settings_tabs_podcasts),
                "Cloud" to stringResource(R.string.settings_tabs_cloud),
                "History" to stringResource(R.string.settings_tabs_history),
                "Downloads" to stringResource(R.string.settings_tabs_downloads),
            )
                .filter { (value, _) ->
                    (value != "Podcasts" || MeloXSettingsRuntime.podcastsEnabled) &&
                        (value != "Podcasts" || MeloXSettingsRuntime.podcastsLibraryPlacement) &&
                        (value != "Cloud" || MeloXSettingsRuntime.cloudMusicEnabled) &&
                        (value != "Cloud" || MeloXSettingsRuntime.cloudLibraryPlacement) &&
                        (value != "History" || MeloXSettingsRuntime.listeningHistoryEnabled) &&
                        (value != "Downloads" || MeloXSettingsRuntime.downloadsEnabled) &&
                        (value != "Downloads" || MeloXSettingsRuntime.downloadsLibraryPlacement)
                },
            onSelected = { libraryPage = it; MeloXSettingsPreferences.setString(context, "library_default_page", it) },
            grouped = true,
        )
        Text(stringResource(R.string.settings_tabs_search_note), modifier = Modifier.padding(horizontal = 18.dp, vertical = 4.dp), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = .46f))
    }
}

@Composable
private fun GeneralSettings(context: android.content.Context) {
    var theme by remember { mutableStateOf(MeloXSettingsRuntime.themeMode) }
    var swipeFullAction by remember { mutableStateOf(MeloXSettingsRuntime.swipeFullAction) }
    SettingsGlassGroup {
        MeloXSettingsDropdown(
            title = stringResource(R.string.settings_general_theme),
            selected = theme,
            items = listOf(
                MeloXThemeMode.System to stringResource(R.string.settings_general_theme_system),
                MeloXThemeMode.Light to stringResource(R.string.settings_general_theme_light),
                MeloXThemeMode.Dark to stringResource(R.string.settings_general_theme_dark),
            ),
            onSelected = { theme = it; MeloXSettingsPreferences.setString(context, "theme_mode", it.name) },
            grouped = true,
        )
        var defaultTab by remember { mutableStateOf(MeloXSettingsRuntime.defaultTab) }
        MeloXSettingsDropdown(
            title = stringResource(R.string.settings_general_launch),
            selected = defaultTab,
            items = listOf(
                "Home" to stringResource(R.string.settings_tabs_home),
                "Explore" to stringResource(R.string.settings_tabs_explore),
                "Library" to stringResource(R.string.settings_tabs_library),
                "Settings" to stringResource(R.string.settings_tabs_settings),
            ),
            onSelected = { defaultTab = it; MeloXSettingsPreferences.setString(context, "general_default_tab", it) },
            grouped = true,
        )
        SettingsToggleRow(context, stringResource(R.string.settings_general_remember_tab), "general_remember_tab", true, grouped = true)
        MeloXSettingsDropdown(
            title = stringResource(R.string.settings_general_swipe),
            selected = swipeFullAction,
            items = listOf(
                MeloXSwipeFullAction.PlayNext to stringResource(R.string.settings_general_play_next),
                MeloXSwipeFullAction.AddToQueue to stringResource(R.string.settings_general_add_queue),
            ),
            onSelected = {
                swipeFullAction = it
                MeloXSettingsPreferences.setString(context, "general_swipe_full_action", it.name)
            },
            grouped = true,
        )
        SettingsToggleRow(context, stringResource(R.string.settings_general_clipboard), "general_clipboard_links", true, stringResource(R.string.settings_general_clipboard_note), grouped = true)
        SettingsToggleRow(context, stringResource(R.string.settings_general_haptics), "general_haptic_feedback", true, grouped = true)
        SettingsToggleRow(
            context,
            stringResource(R.string.settings_general_system_font),
            "system_font",
            false,
            stringResource(R.string.settings_general_system_font_note),
            grouped = true,
        )
        SettingsToggleRow(
            context,
            stringResource(R.string.settings_general_tabbar),
            "general_disable_auto_tabbar_shrink",
            false,
            stringResource(R.string.settings_general_tabbar_note),
            grouped = true,
        )
    }
}

@Composable
private fun RecognitionSettings(context: android.content.Context) {
    val client = remember(context) { SongRecognitionClient(context.applicationContext) }
    val scope = rememberCoroutineScope()
    var duration by remember {
        mutableStateOf(MeloXSettingsPreferences.string(context, "recognition_duration", "6").toIntOrNull() ?: 6)
    }
    var working by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf(context.getString(R.string.settings_recognition_ready)) }
    var error by remember { mutableStateOf<String?>(null) }
    var results by remember { mutableStateOf<List<SongRecognitionResult>>(emptyList()) }
    var recognitionJob by remember { mutableStateOf<Job?>(null) }
    DisposableEffect(client) {
        onDispose {
            recognitionJob?.cancel()
            client.close()
        }
    }

    fun startCapture() {
        recognitionJob?.cancel()
        recognitionJob = scope.launch {
            working = true
            error = null
            if (duration != 0) results = emptyList()
            try {
                if (duration == 0) {
                    status = context.getString(R.string.settings_recognition_continuous)
                    while (isActive) {
                        val found = client.recognize(9)
                        if (found.isNotEmpty()) {
                            results = (found + results).distinctBy { it.song.id }.take(100)
                        }
                    }
                } else {
                    status = context.getString(R.string.settings_recognition_listening, duration)
                    val found = client.recognize(duration)
                    results = found
                    status = if (found.isEmpty()) context.getString(R.string.settings_recognition_none) else context.getString(R.string.settings_recognition_done)
                }
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                status = if (results.isEmpty()) context.getString(R.string.settings_recognition_stopped) else context.getString(R.string.settings_recognition_stopped_kept)
            } catch (failure: Throwable) {
                error = failure.message ?: context.getString(R.string.settings_recognition_failed)
                status = context.getString(R.string.settings_recognition_incomplete)
            } finally {
                working = false
            }
        }
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) startCapture() else error = context.getString(R.string.settings_recognition_mic_denied)
    }

    Spacer(Modifier.height(14.dp))
    SettingsGlassGroup {
        listOf(
            3 to stringResource(R.string.settings_recognition_seconds_fast),
            6 to stringResource(R.string.settings_recognition_seconds_recommended),
            9 to stringResource(R.string.settings_recognition_seconds_noisy),
        ).forEach { (value, title) ->
            SettingsChoiceRow(title, duration == value) {
                if (!working) {
                    duration = value
                    MeloXSettingsPreferences.setString(context, "recognition_duration", value.toString())
                }
            }
        }
    }
    Spacer(Modifier.height(14.dp))
    SettingsActionButton(if (working) stringResource(R.string.settings_recognition_stop) else stringResource(R.string.settings_recognition_start)) {
        if (working) {
            recognitionJob?.cancel()
        } else if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            startCapture()
        } else {
            permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }
    Row(Modifier.fillMaxWidth().padding(vertical = 16.dp), verticalAlignment = Alignment.CenterVertically) {
        if (working) {
            CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
            Spacer(Modifier.size(10.dp))
        }
        Text(status, color = MaterialTheme.colorScheme.onSurface.copy(alpha = .62f), fontSize = 13.sp)
    }
    error?.let { message ->
        Text(message, color = MaterialTheme.colorScheme.error, fontSize = 13.sp)
        Spacer(Modifier.height(12.dp))
    }
    if (results.isNotEmpty()) {
        Text(stringResource(R.string.settings_recognition_results), fontSize = 20.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(8.dp))
        SettingsGlassGroup {
            Column {
                results.forEach { result ->
                    Row(
                        Modifier.fillMaxWidth().clickable {
                            PlaybackCommands.playQueue(
                                context = context,
                                songs = results.map { it.song },
                                selectedSongId = result.song.id,
                                startPositionMs = result.startTimeMs,
                            )
                        }.padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        AsyncImage(result.song.artworkUrl, null, modifier = Modifier.size(48.dp).clip(RoundedCornerShape(9.dp)))
                        Spacer(Modifier.size(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(result.song.name, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.SemiBold)
                            Text(result.song.artists, maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = .5f))
                        }
                        if (result.startTimeMs > 0L) Text("${result.startTimeMs / 1000}s", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = .42f))
                    }
                }
            }
        }
    }
}

@Composable
private fun RemoteConfigSettings() {
    val context = LocalContext.current.applicationContext
    val status by MeloXRemoteConfigRuntime.status.collectAsState()
    val scope = rememberCoroutineScope()
    val githubRouting = remember { MeloXGitHubRouting(context) }
    val consentEnabled = MeloXRemoteConfigConsent.enabled(context)
    val source = if (!consentEnabled) {
        stringResource(R.string.settings_remote_declined)
    } else when (status.source) {
        MeloXRemoteConfigSource.BuiltIn -> stringResource(R.string.settings_remote_builtin)
        MeloXRemoteConfigSource.VerifiedRemote -> stringResource(R.string.settings_remote_verified)
        MeloXRemoteConfigSource.VersionInapplicable -> stringResource(R.string.settings_remote_inapplicable)
    }
    SettingsGlassGroup {
        Column(Modifier.fillMaxWidth().padding(16.dp)) {
            Text(source, fontSize = 18.sp, fontWeight = FontWeight.Bold)
            Text(
                stringResource(R.string.settings_remote_summary),
                modifier = Modifier.padding(top = 7.dp),
                fontSize = 13.sp,
                lineHeight = 18.sp,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = .58f),
            )
        }
    }
    Spacer(Modifier.height(12.dp))
    SettingsGlassGroup {
        RemoteConfigStatusLine(stringResource(R.string.settings_remote_version), status.config.configVersion.toString())
        RemoteConfigStatusLine(
            stringResource(R.string.settings_remote_access),
            githubRouting.effectiveRoute()?.takeIf { githubRouting.selectedSource() == MeloXGitHubSource.Auto }
                ?.let { stringResource(R.string.settings_remote_route, githubSourceLabel(it.source), it.latencyMs) }
                ?: githubSourceLabel(githubRouting.selectedSource()),
        )
        RemoteConfigStatusLine(stringResource(R.string.settings_remote_key), status.keyId ?: stringResource(R.string.settings_remote_builtin_key))
        RemoteConfigStatusLine(stringResource(R.string.settings_remote_last_check), formatRemoteConfigTime(context, status.lastCheckedAtEpochMs))
        RemoteConfigStatusLine(stringResource(R.string.settings_remote_last_update), formatRemoteConfigTime(context, status.lastUpdatedAtEpochMs))
        RemoteConfigStatusLine(
            stringResource(R.string.settings_remote_breakers),
            status.config.disabledCapabilities.takeIf(Set<String>::isNotEmpty)?.joinToString("、") ?: stringResource(R.string.settings_remote_none),
        )
        RemoteConfigStatusLine(stringResource(R.string.settings_remote_fallback_order), status.config.fallback.order.joinToString(" → "))
        RemoteConfigStatusLine(stringResource(R.string.settings_remote_timeout), stringResource(R.string.settings_remote_timeout_value, status.config.fallback.timeoutMs))
    }
    status.error?.let { error ->
        Spacer(Modifier.height(12.dp))
        SettingsInfoCard(stringResource(R.string.settings_remote_check_failed, error))
    }
    Spacer(Modifier.height(12.dp))
    SettingsActionButton(
        when {
            !consentEnabled -> stringResource(R.string.settings_remote_enable_first)
            status.refreshing -> stringResource(R.string.settings_remote_checking)
            else -> stringResource(R.string.settings_remote_check)
        },
    ) {
        if (consentEnabled && !status.refreshing) {
            scope.launch { MeloXRemoteConfigRuntime.refresh(force = true) }
        }
    }
    Spacer(Modifier.height(10.dp))
    SettingsDangerButton(stringResource(R.string.settings_remote_clear)) {
        scope.launch { MeloXRemoteConfigRuntime.clearCache(context) }
    }
}

@Composable
private fun RemoteConfigStatusLine(title: String, value: String) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title, Modifier.weight(1f), color = MaterialTheme.colorScheme.onSurface.copy(alpha = .62f))
        Text(value, modifier = Modifier.weight(1.4f), textAlign = TextAlign.End, fontSize = 13.sp)
    }
}

private fun formatRemoteConfigTime(context: android.content.Context, value: Long): String = if (value <= 0L) {
    context.getString(R.string.settings_remote_not_yet)
} else {
    DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(value))
}

@Composable
private fun githubSourceLabel(source: MeloXGitHubSource): String = when (source) {
    MeloXGitHubSource.Auto -> stringResource(R.string.settings_about_source_auto)
    MeloXGitHubSource.GitHubDoh -> stringResource(R.string.settings_about_source_doh)
    MeloXGitHubSource.GhFast -> stringResource(R.string.settings_about_source_ghfast)
    MeloXGitHubSource.GhProxy -> stringResource(R.string.settings_about_source_ghproxy)
    MeloXGitHubSource.GhProxyOrg -> stringResource(R.string.settings_about_source_ghproxy_org)
}

@Composable
private fun AboutSettings(context: android.content.Context) {
    val githubRouting = remember { MeloXGitHubRouting(context) }
    val updateClient = remember { MeloXUpdateClient(context, routing = githubRouting) }
    val scope = rememberCoroutineScope()
    var checking by remember { mutableStateOf(false) }
    var exportingLogs by remember { mutableStateOf(false) }
    var showLogExportInfo by remember { mutableStateOf(false) }
    var release by remember { mutableStateOf<MeloXRelease?>(null) }
    var updateStatus by remember { mutableStateOf<String?>(null) }
    var versionTapCount by remember { mutableIntStateOf(0) }
    var lastVersionTapAt by remember { mutableStateOf(0L) }
    var showCatEgg by remember { mutableStateOf(false) }
    var downloadSource by remember { mutableStateOf(githubRouting.selectedSource()) }
    val exportLogsLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("text/plain"),
    ) { uri ->
        if (uri == null) {
            exportingLogs = false
        } else {
            scope.launch {
                runCatching {
                    MeloXLogExporter.exportRecentLogs(context, uri)
                }.onSuccess { result ->
                    updateStatus = context.getString(R.string.settings_about_exported, result.lineCount)
                }.onFailure { error ->
                    updateStatus = error.message ?: context.getString(R.string.settings_about_export_failed)
                }
                exportingLogs = false
            }
        }
    }
    Box(
        modifier = Modifier.pointerInput(Unit) {
            var distance = 0f
            detectVerticalDragGestures(
                onDragStart = { distance = 0f },
                onVerticalDrag = { _, dragAmount ->
                    distance += dragAmount
                    if (distance > 180f) {
                        showCatEgg = true
                        distance = 0f
                    }
                },
            )
        },
    ) {
        SettingsGlassGroup {
            Column(Modifier.padding(18.dp)) {
            Text("MeloX Android", fontSize = 22.sp, fontWeight = FontWeight.Bold)
            Text(
                stringResource(R.string.settings_about_version, BuildConfig.VERSION_NAME),
                modifier = Modifier
                    .padding(top = 7.dp)
                    .clickable {
                        val now = System.currentTimeMillis()
                        versionTapCount = if (now - lastVersionTapAt < 2_000L) versionTapCount + 1 else 1
                        lastVersionTapAt = now
                        if (versionTapCount >= 7) {
                            showCatEgg = true
                            versionTapCount = 0
                        }
                    },
                color = MaterialTheme.colorScheme.onSurface.copy(alpha=.62f),
            )
            Text(stringResource(R.string.settings_about_maintainer), modifier = Modifier.padding(top=14.dp), fontWeight=FontWeight.SemiBold)
            Text(stringResource(R.string.settings_about_upstream), modifier = Modifier.padding(top=5.dp), color = MaterialTheme.colorScheme.onSurface.copy(alpha=.58f))
            }
        }
    }
    if (showCatEgg) {
        MeloXGlassDialog(visible = true, onDismiss = { showCatEgg = false }) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                MeloXPinkCat()
                Text(stringResource(R.string.settings_about_cat_title), style = MaterialTheme.typography.titleLarge)
                Text(stringResource(R.string.settings_about_cat_body), modifier = Modifier.padding(top = 7.dp), color = MaterialTheme.colorScheme.onSurface.copy(alpha = .62f))
                MeloXGlassButton(
                    onClick = { showCatEgg = false },
                    modifier = Modifier.fillMaxWidth().padding(top = 16.dp),
                    style = MeloXGlassButtonStyle.BorderedProminent,
                ) { Text(stringResource(R.string.settings_about_cat_accept)) }
            }
        }
    }
    Spacer(Modifier.height(14.dp))
    SettingsToggleRow(context, stringResource(R.string.settings_about_auto_update), "update_auto_check", true, stringResource(R.string.settings_about_auto_update_note))
    Spacer(Modifier.height(10.dp))
    MeloXSettingsDropdown(
        title = stringResource(R.string.settings_about_github_source),
        selected = downloadSource,
        items = listOf(
            MeloXGitHubSource.Auto to stringResource(R.string.settings_about_source_auto),
            MeloXGitHubSource.GitHubDoh to stringResource(R.string.settings_about_source_doh),
            MeloXGitHubSource.GhFast to stringResource(R.string.settings_about_source_ghfast),
            MeloXGitHubSource.GhProxy to stringResource(R.string.settings_about_source_ghproxy),
            MeloXGitHubSource.GhProxyOrg to stringResource(R.string.settings_about_source_ghproxy_org),
        ),
        onSelected = {
            downloadSource = it
            githubRouting.selectSource(it)
        },
    )
    Text(
        githubRouting.effectiveRoute()?.takeIf { downloadSource == MeloXGitHubSource.Auto }?.let {
            stringResource(R.string.settings_about_source_selected, githubSourceLabel(it.source), it.latencyMs)
        } ?: stringResource(R.string.settings_about_source_auto_note),
        modifier = Modifier.padding(horizontal = 6.dp, vertical = 6.dp),
        fontSize = 12.sp,
        lineHeight = 17.sp,
        color = MaterialTheme.colorScheme.onSurface.copy(alpha = .5f),
    )
    SettingsActionButton(if (checking) stringResource(R.string.settings_about_checking) else stringResource(R.string.settings_about_check_update)) {
        if (!checking) scope.launch {
            checking = true
            runCatching { updateClient.latestStableRelease(forceSourceBenchmark = true) }
                .onSuccess {
                    release = it
                    updateStatus = if (updateClient.isNewer(it.version, BuildConfig.VERSION_NAME)) {
                        context.getString(R.string.settings_about_new_version, it.version, it.name)
                    } else {
                        context.getString(R.string.settings_about_up_to_date, BuildConfig.VERSION_NAME)
                    }
                }
                .onFailure { updateStatus = it.message ?: context.getString(R.string.settings_about_update_failed) }
            checking = false
        }
    }
    val currentCommit = BuildConfig.GIT_SHA.take(7)
    if (currentCommit.isNotBlank()) {
        Spacer(Modifier.height(10.dp))
        Spacer(Modifier.height(10.dp))
        Text(
            text = stringResource(R.string.settings_about_dev_commit, currentCommit),
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = .42f),
        )
    }
    var devCommit by remember { mutableStateOf<MeloXDevCommit?>(null) }
    SettingsActionButton(stringResource(R.string.settings_about_check_dev)) {
        if (!checking) scope.launch {
            checking = true
            runCatching { updateClient.latestDevCommit() }
                .onSuccess { latest ->
                    devCommit = latest
                    updateStatus = if (latest.sha == BuildConfig.GIT_SHA) {
                        context.getString(R.string.settings_about_dev_current, latest.sha.take(7))
                    } else {
                        context.getString(R.string.settings_about_dev_new, latest.sha.take(7), latest.message)
                    }
                }
                .onFailure { updateStatus = it.message ?: context.getString(R.string.settings_about_dev_failed) }
            checking = false
        }
    }
    devCommit?.takeIf { it.sha != BuildConfig.GIT_SHA }?.let { latest ->
        Spacer(Modifier.height(10.dp))
        SettingsActionButton(stringResource(R.string.settings_about_download_dev)) {
            scope.launch {
                val target = runCatching { updateClient.devBuildUrl() }.getOrNull()
                    ?: "https://github.com/lladlam/MeloX-Android/actions/workflows/build.yml"
                runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(target))) }
                    .onFailure { updateStatus = it.message ?: context.getString(R.string.settings_about_download_failed) }
            }
        }
        Text(
            text = stringResource(R.string.settings_about_dev_detail, latest.sha.take(7), latest.author, latest.message),
            modifier = Modifier.padding(top = 8.dp),
            fontSize = 12.sp,
            lineHeight = 18.sp,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = .58f),
        )
    }
    updateStatus?.let { message ->
        Spacer(Modifier.height(10.dp))
        Text(message, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = .58f))
    }
    release?.takeIf { updateClient.isNewer(it.version, BuildConfig.VERSION_NAME) }?.let { available ->
        Spacer(Modifier.height(10.dp))
        SettingsActionButton(if (available.apkUrl != null) stringResource(R.string.settings_about_download_apk, available.version) else stringResource(R.string.settings_about_open_release, available.version)) {
            scope.launch {
                val target = runCatching { updateClient.downloadUrl(available) }.getOrNull() ?: available.pageUrl
                runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(target))) }
                    .onFailure { updateStatus = it.message ?: context.getString(R.string.settings_about_open_link_failed) }
            }
        }
        if (available.notes.isNotBlank()) {
            Text(available.notes.take(700), modifier = Modifier.padding(top = 10.dp), fontSize = 12.sp, lineHeight = 18.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = .58f))
        }
    }
    Spacer(Modifier.height(14.dp))
    Spacer(Modifier.height(10.dp))
    SettingsActionButton(stringResource(R.string.settings_about_restore_player)) {
        MeloXSettingsPreferences.resetRecommendedPlayerSettings(context)
        updateStatus = context.getString(R.string.settings_about_restored)
    }
    Spacer(Modifier.height(14.dp))
    SettingsGlassGroup {
        Column(Modifier.padding(16.dp)) {
            Text(stringResource(R.string.settings_about_licenses_title), fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
            Text(
                stringResource(R.string.settings_about_licenses_body),
                modifier = Modifier.padding(top = 10.dp),
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = .62f),
                fontSize = 13.sp,
                lineHeight = 20.sp,
            )
        }
    }
    Spacer(Modifier.height(14.dp))
    SettingsActionButton(stringResource(R.string.settings_about_github)) {
        runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/lladlam/MeloX-Android"))) }
    }
    Spacer(Modifier.height(10.dp))
    SettingsActionButton(stringResource(R.string.settings_about_ios)) {
        runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/youshen2/MeloX"))) }
    }
    Spacer(Modifier.height(10.dp))
    SettingsActionButton(stringResource(R.string.settings_about_upstream_licenses)) {
        runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/youshen2/MeloX/blob/main/MeloX/Features/Legal/ProjectLicensesView.swift"))) }
    }
    Spacer(Modifier.height(10.dp))
    SettingsActionButton(stringResource(R.string.settings_about_qq_group)) {
        runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://qm.qq.com/q/wbhFQxj7mo"))) }
    }
    Spacer(Modifier.height(10.dp))
    SettingsActionButton(stringResource(R.string.settings_about_sponsor)) {
        runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://ifdian.net/a/lladlam"))) }
    }
    Spacer(Modifier.height(10.dp))
    SettingsActionButton(if (exportingLogs) stringResource(R.string.settings_about_exporting) else stringResource(R.string.settings_about_export)) {
        if (!exportingLogs) showLogExportInfo = true
    }

    if (showLogExportInfo) {
        val deviceInfo: MeloXLogDeviceInfo = MeloXLogExporter.collectDeviceInfo(context)
        MeloXGlassDialog(
            visible = true,
            onDismiss = { showLogExportInfo = false },
        ) {
            Text(stringResource(R.string.settings_about_export_title), style = MaterialTheme.typography.titleMedium)
            Text(
                stringResource(R.string.settings_about_export_body),
                modifier = Modifier.padding(top = 8.dp),
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = .64f),
                fontSize = 13.sp,
                lineHeight = 19.sp,
            )
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 14.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(MaterialTheme.colorScheme.onSurface.copy(alpha = .045f))
                    .padding(horizontal = 14.dp, vertical = 11.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(stringResource(R.string.settings_about_android_version, deviceInfo.androidVersion), fontSize = 13.sp)
                Text(stringResource(R.string.settings_about_phone_model, deviceInfo.phoneModel), fontSize = 13.sp)
                Text(stringResource(R.string.settings_about_system_version, deviceInfo.systemVersion), fontSize = 13.sp)
                Text(
                    stringResource(R.string.settings_about_signed_in, deviceInfo.loggedMusicSources.takeIf { it.isNotEmpty() }?.joinToString("、") ?: stringResource(R.string.settings_about_none)),
                    fontSize = 13.sp,
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 18.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                SettingsActionButton(stringResource(R.string.action_cancel), Modifier.weight(1f)) { showLogExportInfo = false }
                SettingsActionButton(stringResource(R.string.settings_about_choose_location), Modifier.weight(1f)) {
                    showLogExportInfo = false
                    exportingLogs = true
                    exportLogsLauncher.launch("MeloX-logs-${System.currentTimeMillis()}.txt")
                }
            }
        }
    }
}

@Composable
private fun DeveloperSettings() {
    val context = LocalContext.current
    var diagnosticsVisible by remember {
        mutableStateOf(BuildConfig.DEBUG && MeloXSettingsPreferences.boolean(context, "developer_automix_diagnostics", false))
    }
    var diagnostics by remember { mutableStateOf(MeloXAutoMixDiagnostics.snapshot()) }
    if (BuildConfig.DEBUG) {
        SettingsToggleRow(
            context = context,
            title = stringResource(R.string.settings_dev_overlay),
            key = "developer_performance_overlay",
            default = false,
            note = stringResource(R.string.settings_dev_overlay_note),
        )
        SettingsExternalToggleRow(
            title = stringResource(R.string.settings_dev_automix),
            value = diagnosticsVisible,
            note = stringResource(R.string.settings_dev_automix_note),
        ) {
            diagnosticsVisible = it
            MeloXSettingsPreferences.setBoolean(context, "developer_automix_diagnostics", it)
        }
        if (diagnosticsVisible) {
            Spacer(Modifier.height(8.dp))
            SettingsActionButton(stringResource(R.string.settings_dev_refresh)) { diagnostics = MeloXAutoMixDiagnostics.snapshot() }
            Spacer(Modifier.height(10.dp))
        }
    }
    Spacer(Modifier.height(10.dp))
}

private val LocalSettingsGroupedRows = staticCompositionLocalOf { false }
internal val LocalSettingsGroupRowIndex = staticCompositionLocalOf { mutableIntStateOf(0) }

@Composable
private fun SettingsToggleRow(
    context: android.content.Context,
    title: String,
    key: String,
    default: Boolean,
    note: String? = null,
    grouped: Boolean = false,
    showTopSeparator: Boolean = false,
) {
    var value by remember(key) { mutableStateOf(MeloXSettingsPreferences.boolean(context, key, default)) }
    val groupRowIndex = LocalSettingsGroupRowIndex.current
    val effectiveShowTopSeparator = when {
        showTopSeparator -> true
        grouped -> groupRowIndex.intValue++.let { it > 0 }
        else -> false
    }
    @Composable fun row() {
        MeloXIosListRow(
            title = title,
            trailing = {
                MeloXGlassToggle(checked = value, onCheckedChange = {
                    value = it
                    MeloXSettingsPreferences.setBoolean(context, key, it)
                })
            },
            showTopSeparator = effectiveShowTopSeparator,
        )
    }
    if (grouped || LocalSettingsGroupedRows.current) row() else {
        SettingsGlassGroup { row() }
        Spacer(Modifier.height(10.dp))
    }
}

@Composable
private fun SettingsExternalToggleRow(
    title: String,
    value: Boolean,
    note: String? = null,
    grouped: Boolean = false,
    onValueChange: (Boolean) -> Unit,
) {
    val groupRowIndex = LocalSettingsGroupRowIndex.current
    val effectiveShowTopSeparator = if (grouped) groupRowIndex.intValue++.let { it > 0 } else false
    val row = @Composable {
        MeloXIosListRow(
            title = title,
            trailing = { MeloXGlassToggle(checked = value, onCheckedChange = onValueChange) },
            showTopSeparator = effectiveShowTopSeparator,
        )
    }
    if (grouped) {
        row()
    } else {
        SettingsGlassGroup { row() }
        Spacer(Modifier.height(10.dp))
    }
}

@Composable
private fun SettingsChoiceRow(title: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title, Modifier.weight(1f), fontSize = 16.sp)
        if (selected) Text("✓", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun SettingsGlassGroup(content: @Composable ColumnScope.() -> Unit) {
    val rowIndex = remember { mutableIntStateOf(0) }
    rowIndex.intValue = 0
    CompositionLocalProvider(LocalSettingsGroupRowIndex provides rowIndex) {
        MeloXIosGroupedList(surfaceColor = MaterialTheme.colorScheme.surface, content = content)
    }
}

@Composable
private fun SettingsInfoCard(value: String) {
    SettingsGlassGroup {
        Text(
            value,
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = .52f),
        )
    }
}

@Composable
private fun SettingsActionButton(title: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Box(
        modifier.fillMaxWidth().height(50.dp).meloXLiquidButton(
            shape = RoundedCornerShape(25.dp),
            surfaceColor = MaterialTheme.colorScheme.onBackground.copy(alpha = .06f),
        ).clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { Text(title, fontWeight = FontWeight.SemiBold) }
}

@Composable
private fun SettingsRoundButton(text: String, onClick: () -> Unit) {
    Box(
        Modifier.size(44.dp).meloXLiquidButton(shape = CircleShape).clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { MeloXActionIcon(text, Modifier.size(20.dp), MaterialTheme.colorScheme.onSurface) }
}

@Composable
private fun SettingsResetCard() {
    val context = LocalContext.current
    SettingsDangerButton(stringResource(R.string.settings_reset_player)) {
        MeloXSettingsPreferences.reset(context)
        MeloXPlaybackModePreferences.reset(context)
        PlaybackCommands.changeQuality(context, MusicQuality.Standard)
    }
}

@Composable
private fun SettingsDangerButton(title: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Box(
        modifier.fillMaxWidth().height(54.dp).meloXLiquidButton(
            shape = RoundedCornerShape(27.dp),
            tint = MaterialTheme.colorScheme.error.copy(alpha = .25f),
            surfaceColor = MaterialTheme.colorScheme.error.copy(alpha = .20f),
        ).clickable(onClick = onClick).padding(horizontal = 18.dp),
        contentAlignment = Alignment.CenterStart,
    ) { Text(title, color = MaterialTheme.colorScheme.error, fontWeight = FontWeight.SemiBold) }
}
