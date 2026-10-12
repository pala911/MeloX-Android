package com.lladlam.melox

import android.app.Activity
import android.app.Application
import android.app.NotificationManager
import android.content.Context
import android.os.Bundle
import android.content.ComponentCallbacks2
import android.content.Context
import android.content.res.Configuration
import com.lladlam.melox.core.diagnostics.MeloXCrashStore
import com.lladlam.melox.core.network.MeloXHttpClient
import com.lladlam.melox.core.audio.MusicQualityPreferences
import com.lladlam.melox.core.audio.MusicQualityRuntime
import com.lladlam.melox.core.audio.MobileDataQualityRuntime
import com.lladlam.melox.core.diagnostics.MeloXCrashRecorder
import com.lladlam.melox.core.display.MeloXFrameRateHost
import com.lladlam.melox.platform.xiaomi.ShizukuXmsfNetworkHelper
import com.lladlam.melox.ui.animation.MeloXSystemScale
import com.lladlam.melox.ui.player.ArtworkDynamicPaletteProvider
import java.io.File

class MeloXApplication : Application() {
    override fun attachBaseContext(context: Context) {
        super.attachBaseContext(context)
        // Before ContentProviders/stores: the melox_* -> ysyy_* renames must land
        // before anything can open the new (empty) names.
        YsyyStorageMigration.runOnce(context)
    }

    override fun onCreate() {
        super.onCreate()
        // 崩溃现场记录：尽早接管，保证第一次 UI 帧之后的任何崩溃都能落到 filesDir/crash/。
        // ⚠ 只记录、不降级 —— 玻璃是否继续渲染与它无关（见 MeloXCrashRecorder 说明）。
        MeloXCrashRecorder.install(this)
        // 上游 0.6.2（806a23b）：持久化上次崩溃。
        MeloXCrashStore.install(this)
        // 本地保留：小米 XMSF 隐藏 API 豁免。上游 0.6.5 把这个调用摘掉了（注释见
        // ShizukuXmsfNetworkHelper），但我们本地已改过该文件（4c31bc2 -> 4fd12ab），
        // 方法定义仍在，摘掉会让小米机型上 XMSF 免授权失效。
        ShizukuXmsfNetworkHelper.installHiddenApiExemptions()
        MusicQualityRuntime.selected = MusicQualityPreferences.read(this)
        MobileDataQualityRuntime.install(this)
        MeloXHttpClient.initialize(this)
        // 让 Compose 侧的整页转场（首页 / 搜索 / 资料库 / 播客的一镜到底）跟随**与系统
        // Activity 转场同一个**动画缩放键（TRANSITION_ANIMATION_SCALE）：否则在「过渡动画
        // 缩放」上调速度时，只有 Activity 页变、Compose 页纹丝不动。见 MeloXSystemScale。
        MeloXSystemScale.install(this)
        // 可变刷新率：用 lifecycle callbacks 统一挂到**每个** Activity（8 个），
        // 各 Activity 零改动。见 MeloXFrameRateHost 的说明。
        MeloXFrameRateHost.install(this)
        registerActivityLifecycleCallbacks(MeloXAppVisibility)
        registerComponentCallbacks(MeloXMemoryCallbacks)
    }
}

object MeloXAppVisibility : Application.ActivityLifecycleCallbacks {
    private const val PREFS_NAME = "melox_visibility"
    private const val KEY_LAST_BACKGROUND_MS = "last_background_ms"

    @Volatile
    private var startedActivities = 0
    @Volatile
    private var foregroundSession = 0L
    private val foregroundState = androidx.compose.runtime.mutableStateOf(false)

    val isForeground: Boolean get() = foregroundState.value
    val foregroundSessionId: Long get() = foregroundSession

    override fun onActivityStarted(activity: Activity) {
        if (startedActivities == 0) foregroundSession++
        startedActivities++
        foregroundState.value = startedActivities > 0
    }
    override fun onActivityStopped(activity: Activity) {
        startedActivities = (startedActivities - 1).coerceAtLeast(0)
        foregroundState.value = startedActivities > 0
        if (startedActivities == 0) recordBackgrounded(activity)
    }

    /** Wall-clock ms of the most recent moment the app dropped fully to background.
     *  Persisted to disk so a process-death cold start can still recognise itself as a return. */
    fun lastBackgroundedAtMs(context: Context): Long {
        return try {
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getLong(KEY_LAST_BACKGROUND_MS, 0L)
        } catch (_: Throwable) { 0L }
    }

    private fun recordBackgrounded(activity: Activity) {
        try {
            activity.application.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .putLong(KEY_LAST_BACKGROUND_MS, System.currentTimeMillis())
                .apply()
        } catch (_: Throwable) { }
    }
    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
    override fun onActivityResumed(activity: Activity) = Unit
    override fun onActivityPaused(activity: Activity) = Unit
    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
    override fun onActivityDestroyed(activity: Activity) = Unit
}

private object MeloXMemoryCallbacks : ComponentCallbacks2 {
    override fun onTrimMemory(level: Int) {
        if (level >= ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN) {
            ArtworkDynamicPaletteProvider.clearMemoryCache()
        }
    }

    override fun onLowMemory() = ArtworkDynamicPaletteProvider.clearMemoryCache()

    override fun onConfigurationChanged(newConfig: Configuration) = Unit
}

/**
 * One-shot melox_* -> ysyy_* storage migration for the YSYY rename.
 *
 * Runs from [MeloXApplication.attachBaseContext], before ContentProviders and any store
 * reads, so every shared_prefs file and internal directory is renamed before its new
 * name can be opened empty. Public MediaStore folders (Music/MeloX, Pictures/MeloX) are
 * intentionally not migrated: old exports stay where they are, new ones go to Music/YSYY
 * and Pictures/YSYY.
 */
private object YsyyStorageMigration {
    private const val MARKER = "ysyy_storage_migration"
    private const val MARKER_KEY = "done"

    private val PREFS = arrayOf(
        "melox_app_settings",
        "melox_apple_music",
        "melox_audio_analysis",
        "melox_auto_cache_counts",
        "melox_bilibili_lyric_offsets",
        "melox_bilibili_playback_associations",
        "melox_bilibili_session",
        "melox_chksz_api",
        "melox_github_routing",
        "melox_jellyfin_session",
        "melox_kugou_playback_session",
        "melox_kugou_session",
        "melox_kuwo_playback_session",
        "melox_kuwo_session",
        "melox_last_playback",
        "melox_local_music",
        "melox_lx_user_sources",
        "melox_lyric_bindings",
        "melox_music_providers",
        "melox_playback",
        "melox_playback_account",
        "melox_playback_modes",
        "melox_playback_queue",
        "melox_qq_music_playback_session",
        "melox_qq_music_session",
        "melox_remote_config_consent",
        "melox_remote_notices",
        "melox_spotify_client_config",
        "melox_spotify_session",
        "melox_third_party_music_sources",
        "melox_youtube_music_session",
    )

    private val DIRS = arrayOf(
        "melox_downloads",
        "melox_http",
        "melox_media",
        "melox_provider_downloads",
    )

    private val OLD_CHANNELS = arrayOf(
        "melox_analysis",
        "melox_floating_lyrics",
        "melox_lyrics",
        "melox_super_island_lyrics_v1",
        "melox_vivo_atomic_island",
    )

    fun runOnce(context: Context) {
        try {
            val marker = context.getSharedPreferences(MARKER, Context.MODE_PRIVATE)
            if (marker.getBoolean(MARKER_KEY, false)) return

            val prefsDir = File(context.applicationInfo.dataDir, "shared_prefs")
            for (old in PREFS) {
                val source = File(prefsDir, "$old.xml")
                if (!source.exists()) continue
                val target = File(prefsDir, "ysyy_" + old.removePrefix("melox_") + ".xml")
                if (!target.exists()) source.renameTo(target)
            }

            for (base in arrayOf(context.filesDir, context.cacheDir)) {
                for (old in DIRS) {
                    val source = File(base, old)
                    if (!source.exists()) continue
                    val target = File(base, "ysyy_" + old.removePrefix("melox_"))
                    if (!target.exists()) source.renameTo(target)
                }
            }

            val notifications = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
            for (old in OLD_CHANNELS) notifications?.deleteNotificationChannel(old)

            marker.edit().putBoolean(MARKER_KEY, true).apply()
        } catch (_: Throwable) {
            // Marker stays unset so the next start retries the migration.
        }
    }
}
