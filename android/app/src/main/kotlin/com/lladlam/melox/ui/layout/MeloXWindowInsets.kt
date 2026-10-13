package com.lladlam.melox.ui.layout

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.statusBars
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * **稳定**的状态栏高度（dp）：与系统栏当前隐/显无关，沉浸切换时不会跳。
 *
 * 用途：给需要**自己**加顶部 padding 的地方取稳定值（尤其是 `statusBarsPadding()` 被消费掉、
 * 或手动读 insets 的页面）。
 *
 * ⚠⚠ **取数走 Compose 的 `WindowInsets.statusBars`（可见值），不走 ViewCompat 的
 * `getInsetsIgnoringVisibility`**：实测本机上后者在背景页那条链路**恒读到 0**
 * （缓存一次都没被写入 ⇒ innerPadding 恒 0 ⇒ 内容贴顶，真机症状：歌单页按钮顶到最上面）。
 * 而 Compose 这条链路实测是准的 —— 改动前各页面靠 `statusBarsPadding()` 定位一直正确。
 *
 * 在可靠原始值之上做缓存：
 * - 原始值 > 0（系统栏可见）⇒ 直接用，并**刷新缓存**；
 * - 原始值 = 0（`hide()` 后归零）⇒ **沿用缓存的上一次真实高度** ⇒ 位置不变、不跳。
 *
 * ⚠ 手动读（`asPaddingValues()`）**不受** `Modifier.consumeWindowInsets` 约束，
 *   所以在已消费的子树里也能读到真实原始值 —— 这正是它能兜住的原因。
 *
 * ⚠⚠⚠ **缓存必须是进程级、不能是 `remember`（2026-10-13 修，用户报障
 *   「打开沉浸播放后，从播放页返回时首页 / 发现 / 音乐库 / 设置都会往下拉一下，
 *   整屏一起往下然后回弹」）**：
 *
 *   原实现是 `remember { StatusBarTopCache(raw) }` —— 缓存的生命周期 = **组合作用域**。
 *   沉浸播放 `hide(statusBars)` 之后，可见 inset 恒为 0；只要此刻该屏的组合作用域被
 *   重建（切 tab 回前台、`SaveableStateProvider` 换 key、配置变化…），`remember` 就会
 *   用 `raw = 0` 重新初始化缓存 —— 缓存在这一轮里**恒为 0**。于是：
 *
 *   | 时刻 | 状态栏 | raw | 返回值 |
 *   |---|---|---|---|
 *   | 平时 | 可见 | 161px | 161px |
 *   | 进播放页（沉浸隐藏） | 隐藏 | 0 | **0**（缓存已被重置） ← 内容顶上去 |
 *   | 返回播放页 | 恢复 | 161px | 161px ← **整屏内容下移 161px = 「下拉一下」** |
 *
 *   161px ≈ 屏高 5%，肉眼就是"整屏往下拽一下再回弹"；因为底部 chrome 每个 tab 共享，
 *   四个 tab 全都复现 ⇒ 与用户观察到的现象逐字吻合。
 *
 *   ⇒ 改成 `object` 进程级缓存：**只在读到真实值时写、任何组合重建都不再重置**。
 *   这与 `MeloXPlayerInsets.MeloXPlayerInsetCache` 是同一种做法（同代码库既有模式）。
 *
 *   另外补一条兜底：万一首次进入时状态栏就已经是隐藏的（缓存从未拿到过真实值），
 *   用 `displayCutout` 的顶部 inset 顶上 —— 挖孔/摄像头区域**不随状态栏显隐变化**
 *   （`MeloXPlayerInsets` 里已实测记录该性质），至少不会退化成 0。
 */
@Composable
fun rememberMeloXStableStatusBarHeight(): Dp {
    val raw = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    if (raw > 0.dp) StatusBarTopCache.value = raw
    val stable = StatusBarTopCache.value
    if (stable > 0.dp) return stable
    // ⚠ 兜底：displayCutout 的顶部 inset 与 statusBars 的可见性无关。
    return WindowInsets.displayCutout.asPaddingValues().calculateTopPadding()
}

/**
 * 进程级状态栏顶部高度缓存。
 *
 * ⚠ **不能改回 `remember`** —— 见 [rememberMeloXStableStatusBarHeight] 的 KDoc：
 * 组合作用域重建会在沉浸隐藏期间用 0 重新初始化，导致状态栏恢复瞬间整屏下移。
 */
private object StatusBarTopCache {
    var value: Dp = 0.dp
}