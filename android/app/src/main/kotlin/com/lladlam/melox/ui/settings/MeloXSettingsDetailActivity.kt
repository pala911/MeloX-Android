package com.lladlam.melox.ui.settings

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import com.lladlam.melox.core.account.rememberNeteaseSessionStore
import com.lladlam.melox.core.music.model.MusicSource
import com.lladlam.melox.ui.MeloXPageHost
import com.lladlam.melox.ui.finishMeloXPage
import com.lladlam.melox.ui.glass.LocalMeloXBackdrop
import com.lladlam.melox.ui.prepareMeloXPageWindow
import com.lladlam.melox.ui.startMeloXPage
import com.lladlam.melox.ui.theme.MeloXTheme
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop

/**
 * **设置详情页 —— 一个真实 Activity。**
 *
 * ### 为什么是 Activity 而不是应用内覆盖层（2026-10-02 重构）
 * 用户点名要 Orange（`com.baron.orange`）设置页那种 push/pop 手感：顺滑、可连续打断、
 * 带系统屏幕圆角、支持预测式返回。逆向那份 APK 后确认那套手感是**零 App 代码的系统
 * Activity 转场**（全包没有 `res/anim*`、没有 `overrideActivityTransition`、主题也没覆盖
 * `windowAnimationStyle`）—— 也就是 WindowManager 在驱动窗口的位置与速度。
 *
 * 原来这里是 `SettingsScreen` 里的一个覆盖层（`MeloXInterruptiblePage` + `AnimatedContent`
 * 弹簧位移 + `meloXPageRecede` 让列表页左移 1/3 + 自研 `PredictiveBackHandler`）。那套写法
 * 结构性地做不到「零顿挫」：Compose 动画挂在组合/绘制帧上，被打断时位置与速度不能双连续。
 * 改成独立 Activity 后，推入 / 退回 / 打断 / 预测式返回**全部**由平台负责。
 *
 * ### 约定
 * - 主题走 `Theme.MeloX.Page`（**不透明**窗口）—— 半透明窗口在系统里拿不到那套原生转场。
 * - **不要**给这里加 `overridePendingTransition` / `ActivityOptions.makeCustomAnimation`，
 *   也不要在主题里加 `windowAnimationStyle`。
 * - `prepareMeloXPageWindow()` 必须在 `setContent` 之前：它按 App 真实配色设窗口底色，
 *   避免转场第一帧闪一次错色。
 *
 * @see com.lladlam.melox.ui.MeloXPageHost
 * @see com.lladlam.melox.ui.startMeloXPage
 */
class MeloXSettingsDetailActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val route = intent.getStringExtra(EXTRA_ROUTE)
            ?.let { name -> SettingsRoute.entries.firstOrNull { it.name == name } }
            ?: run { finish(); return }
        val source = intent.getStringExtra(EXTRA_SOURCE)
            ?.let { name -> runCatching { MusicSource.valueOf(name) }.getOrNull() }
            ?: MusicSource.Netease
        enableEdgeToEdge()
        prepareMeloXPageWindow()
        setContent {
            MeloXPageHost {
                MeloXTheme {
                    // ⚠ 玻璃背板（2026-10-08 修复）：这里也是**独立 compose 树**，与
                    //   MeloXNeteaseLoginActivity 同构 —— 之前没人 provide
                    //   `LocalMeloXBackdrop` ⇒ 子页里所有 `MeloXGlassToggle` /
                    //   玻璃控件经 `meloXGlassBackdrop()` 拿到 null，全部走
                    //   无玻璃 else 分支 ⇒ 长按永远没有液态效果（真机探针实证）。
                    //   模式对齐 NeteaseLogin：稳定源层（纯背景色）+ provider 包裹内容。
                    val pageBackdrop = rememberLayerBackdrop()
                    Box(Modifier.fillMaxSize()) {
                        Box(
                            Modifier
                                .fillMaxSize()
                                .background(MaterialTheme.colorScheme.background)
                                .layerBackdrop(pageBackdrop),
                        )
                        CompositionLocalProvider(LocalMeloXBackdrop provides pageBackdrop) {
                            SettingsDetailScreen(
                                route = route,
                                source = source,
                                session = rememberNeteaseSessionStore(),
                                onBack = ::finishMeloXPage,
                            )
                        }
                    }
                }
            }
        }
    }

    companion object {
        private const val EXTRA_ROUTE = "settings_route"
        private const val EXTRA_SOURCE = "settings_source"

        /** 由 [SettingsScreen] 的列表项点击调用。 */
        fun launch(context: Context, route: SettingsRoute, source: MusicSource) =
            launch(context, route.name, source)

        /**
         * 深链入口：`route` 用 [SettingsRoute] 的枚举名。名字不认识时**什么都不做**
         * （不要启动一个立刻 `finish()` 的空 Activity —— 那会闪一下）。
         */
        fun launch(context: Context, route: String, source: MusicSource) {
            if (SettingsRoute.entries.none { it.name == route }) return
            context.startMeloXPage(
                Intent(context, MeloXSettingsDetailActivity::class.java)
                    .putExtra(EXTRA_ROUTE, route)
                    .putExtra(EXTRA_SOURCE, source.name)
                    .apply { if (context !is Activity) addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) },
            )
        }
    }
}
