package com.lladlam.melox.ui.layout

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.statusBars
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
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
 * ⚠ 缓存用**普通对象**而非 `mutableStateOf`：避免在 composition 期间写 state 触发重组
 * （本项目已踩过「每帧写 state ⇒ 反复重组」）。
 *
 * ⚠ 手动读（`asPaddingValues()`）**不受** `Modifier.consumeWindowInsets` 约束，
 *   所以在已消费的子树里也能读到真实原始值 —— 这正是它能兜住的原因。
 */
@Composable
fun rememberMeloXStableStatusBarHeight(): Dp {
    val raw = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    val cache = remember { StatusBarTopCache(raw) }
    if (raw > 0.dp) cache.value = raw
    return if (raw > 0.dp) raw else cache.value
}

private class StatusBarTopCache(initial: Dp) {
    var value: Dp = initial
}
