package com.lladlam.melox.ui.player

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat

/**
 * 沉浸式播放：`hide()` 真隐藏系统栏（图标消失），[show()] 恢复。
 *
 * [type] 选择隐藏哪些栏（位掩码）：
 * - 竖屏默认 [WindowInsetsCompat.Type.statusBars]（只藏顶栏，底栏保持可见）；
 * - 横屏传 [WindowInsetsCompat.Type.systemBars]（顶栏 + 底部导航小白条都藏）。
 *
 * ⚠ 配套前提：`MeloXTheme` 已把 window 常驻 edge-to-edge
 * （`setDecorFitsSystemWindows(false)`）。fits=true 下 `hide()` 会改变 content frame 高度
 * （status bar 空间让给内容），背景页 `Box(fillMaxSize)` 尺寸随之跳变 = 返回时「抽搐」。
 * edge-to-edge 下 content frame 恒定，`hide()` 只切可见性、不动布局。
 *
 * ⚠ **转屏抽搐修复（2026-09-29）**：原实现把「状态应用」和「离场还原」都塞进同一个
 * `DisposableEffect(enabled, type)`。转横屏时 `enabled`(false→true，因 `|| isLandscape`)
 * 与 `type`(statusBars→systemBars) **同时变化**，触发旧 effect 的
 * `onDispose { controller.show(type) }` —— 旧系统栏被瞬间 `show` 出来、随即被新 effect
 * `hide` 掉 = 用户看到的「抽搐一下」。
 *
 * 拆成两段：
 * 1. `DisposableEffect(Unit)` 只负责**真正离场**（播放页从 composition 移除 / 应用退出）
 *    时还原系统栏；旋转不触发它，因此旋转途中不会 `show` 闪一下。
 * 2. `LaunchedEffect(enabled, type)` 只负责**应用当前状态**（hide/show），无破坏性
 *    `onDispose`。旋转时它只是把 `hide(statusBars)` 升级成 `hide(systemBars)`，
 *    中间不 `show`，所以不闪。
 *
 * 亮色图标用 `SideEffect` 每重组维持，压过 MeloXTheme 默认把图标设成「!darkTheme」
 * 的行为（否则浅色主题下深图标在深色播放器上不可见）。
 */
@Composable
fun MeloXImmersivePlaybackEffect(
    enabled: Boolean,
    type: Int = WindowInsetsCompat.Type.statusBars(),
) {
    val context = LocalContext.current
    val view = LocalView.current

    // 1) 离场还原：仅当 effect 永久离开 composition 时把系统栏全部还原。
    //    旋转(config 变化)不会重跑此 effect，因此旋转途中不会 show 闪一下。
    DisposableEffect(Unit) {
        onDispose {
            val activity = context.findActivity() ?: return@onDispose
            val controller = WindowCompat.getInsetsController(activity.window, view)
            controller.show(WindowInsetsCompat.Type.systemBars())
        }
    }

    // 2) 状态应用：仅在 enabled / type 变化时重跑，且**没有**会 show 旧栏的 onDispose。
    //    旋转时从 hide(statusBars) 平滑升级到 hide(systemBars)，不闪。
    LaunchedEffect(enabled, type) {
        val activity = context.findActivity() ?: return@LaunchedEffect
        val controller = WindowCompat.getInsetsController(activity.window, view)
        if (enabled) {
            controller.hide(type)
            controller.systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        } else {
            controller.show(type)
        }
    }

    // 沉浸播放期间强制亮色图标（深色播放器底）。用 SideEffect 每重组维持，
    // 确保压过 MeloXTheme 的 SideEffect（它在更外层、先执行）。
    if (enabled) {
        SideEffect {
            val activity = context.findActivity() ?: return@SideEffect
            val controller = WindowCompat.getInsetsController(activity.window, view)
            controller.isAppearanceLightStatusBars = false
            controller.isAppearanceLightNavigationBars = false
        }
    }
}

private fun Context.findActivity(): Activity? {
    var current = this
    while (current is ContextWrapper) {
        if (current is Activity) return current
        current = current.baseContext
    }
    return null
}
