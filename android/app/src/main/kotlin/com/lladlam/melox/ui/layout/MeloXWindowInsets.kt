package com.lladlam.melox.ui.layout

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.statusBars
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat

/**
 * **稳定**的状态栏高度（dp）：与系统栏当前隐/显无关，沉浸切换时不会跳。
 *
 * 用途：给需要**自己**加顶部 padding 的地方取稳定值（尤其是 `statusBarsPadding()` 被消费掉、
 * 或手动读 insets 的页面）。
 *
 * ⚠⚠⚠ **必须以 ViewCompat 为主源，不能只靠 Compose 的 `WindowInsets`（2026-10-13 修）**
 *
 * 真机逐帧取证（用户 110s 录屏，30fps 追踪首页「首页」大标题上沿，单位半px）：
 *
 * | 帧 | 状态 | 标题上沿 |
 * |---|---|---|
 * | 2894–2905 | 沉浸中，状态栏隐藏 | **66** |
 * | 2906–2912 | 恢复过程（约 200ms 逐帧下移） | 68→75→87→96→101→104→106 |
 * | 2913 之后 | 稳定，**不回弹** | **106** |
 *
 * ⇒ 每次「进播放页 → 返回」都把根页面**整体下移 40 半px = 80 实px 并永久保持**。
 *
 * 80px 的来历：沉浸隐藏期间本函数返回 **80px**，恢复后返回 **161px**（= 状态栏真高，
 * density 3.75 × 43dp）。**差值 161−80 = 81px，与实测 80px 吻合。**
 *
 * 而 80px 正是 **Compose 的 `WindowInsets.displayCutout` 在本位置读到的值**；
 * 同一时刻 ViewCompat 链路读到的是 **161px**（设备自带埋点 `mxdiag.txt` 里
 * `inset.top=stable=161 cutout=161 cache=161` 可证 —— 它用的就是 ViewCompat）。
 *
 * ⇒ **两条链路在本机返回不同的数**。之前那版兜底用 Compose 的 `displayCutout`，
 * 于是「隐藏时 80 / 可见时 161」，正好制造了这个位移。
 *
 * 现在：
 * - 主源 = `ViewCompat.getRootWindowInsets(...).getInsetsIgnoringVisibility(statusBars)`
 *   （与 `MeloXPlayerInsets` 同一取数路径，实测准）；
 * - 与 Compose 的 `statusBars` / `displayCutout` 取 **max** 兜底；
 * - 结果写进**进程级** [StatusBarTopCache]，且**只增不减** ⇒ 隐藏期间返回的必然还是
 *   隐藏前缓存的真值，恒定不跳。
 */
@Composable
fun rememberMeloXStableStatusBarHeight(): Dp {
    val view = LocalView.current
    val density = LocalDensity.current

    val liveDp = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    val cutoutDp = WindowInsets.displayCutout.asPaddingValues().calculateTopPadding()

    // ⚠ 每次组合都读（不 remember）：该 Composable 会随 inset 变化重组，读一次可能正好
    //   落在状态栏被 hide 的那一帧而拿到 0。getRootWindowInsets 只是取值，开销可忽略。
    val viewPx = ViewCompat.getRootWindowInsets(view)?.let { insets ->
        maxOf(
            insets.getInsetsIgnoringVisibility(WindowInsetsCompat.Type.statusBars()).top,
            insets.getInsets(WindowInsetsCompat.Type.statusBars()).top,
            insets.getInsets(WindowInsetsCompat.Type.displayCutout()).top,
        )
    } ?: 0
    val viewDp = with(density) { viewPx.toDp() }

    val best = maxOf(liveDp, cutoutDp, viewDp, StatusBarTopCache.value)
    if (best > StatusBarTopCache.value) StatusBarTopCache.value = best
    return StatusBarTopCache.value
}

/**
 * 进程级状态栏顶部高度缓存，**只增不减**。
 *
 * ⚠ 两个"不能改回去"的点（都踩过）：
 * 1. 不能用 `remember {}` —— 组合作用域重建会在沉浸隐藏期间用 0 重新初始化；
 * 2. 不能用「读到 0 就回落挖孔」—— Compose 的 `displayCutout` 在本机只给 80px，
 *    而真实状态栏高 161px，两者不一致 ⇒ 每次沉浸切换位移 80px。
 */
private object StatusBarTopCache {
    var value: Dp = 0.dp
}