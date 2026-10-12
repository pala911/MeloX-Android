package com.lladlam.melox.ui.layout

import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

enum class MeloXWindowWidthClass { Compact, Medium, Expanded }

/** 播放页「平板双栏」的最小边长门槛（宽度 / 高度都要过，见 [MeloXWindowInfo.supportsPlayerTwoPane]）。 */
val MeloXPlayerTwoPaneMinWidth = 840.dp
val MeloXPlayerTwoPaneMinHeight = 600.dp

data class MeloXWindowInfo(
    val widthClass: MeloXWindowWidthClass,
    val isLandscape: Boolean,
    val gutter: Dp,
    val maxContentWidth: Dp,
    val gridColumns: Int,
    /**
     * 播放页「左播放器 / 右歌词」平板双栏是否可用（**只有播放页用它**）。
     *
     * ⚠ 与 [supportsTwoPane] **不是一回事**，别合并：那个是「宽一点就多给点留白 / 多排一列」
     * 的宽松判据（搜索页间距、资料库列数、发现页宽度在用），本字段则是播放页**能不能塞下
     * 一整列播放器**的硬尺寸判据。
     *
     * ⚠ 必须叠加高度：手机横屏是 792×360dp，`widthClass` 也是 `Medium`、[supportsTwoPane]
     * 也为 true，但它只有 360dp 高 —— 左列「封面 + 曲名行 + 控制条」整块约 435dp，必然溢出。
     * 所以判据是「够宽（≥ [MeloXPlayerTwoPaneMinWidth]）**且**够高（≥ [MeloXPlayerTwoPaneMinHeight]）」。
     * 手机横屏 / 竖屏 / 折叠屏半开一律 false ⇒ 播放页逐像素不变。
     */
    val supportsPlayerTwoPane: Boolean,
) {
    val supportsTwoPane: Boolean get() = widthClass != MeloXWindowWidthClass.Compact
}

/** One shared window policy for phone, foldable and tablet page layouts. */
@Composable
fun rememberMeloXWindowInfo(): MeloXWindowInfo {
    val containerSize = LocalWindowInfo.current.containerSize
    val density = LocalDensity.current
    val width = with(density) { containerSize.width.toDp().value.toInt() }
    val height = with(density) { containerSize.height.toDp().value.toInt() }
    val widthClass = when {
        width >= 840 -> MeloXWindowWidthClass.Expanded
        width >= 600 -> MeloXWindowWidthClass.Medium
        else -> MeloXWindowWidthClass.Compact
    }
    // 见字段注释：宽度档位不足以判定播放页双栏，必须叠加高度门槛。
    val playerTwoPane = width >= MeloXPlayerTwoPaneMinWidth.value.toInt() &&
        height >= MeloXPlayerTwoPaneMinHeight.value.toInt()
    return when (widthClass) {
        MeloXWindowWidthClass.Compact ->
            MeloXWindowInfo(widthClass, width > height, 20.dp, 600.dp, 2, false)
        MeloXWindowWidthClass.Medium ->
            MeloXWindowInfo(widthClass, width > height, 28.dp, 920.dp, 3, playerTwoPane)
        MeloXWindowWidthClass.Expanded ->
            MeloXWindowInfo(widthClass, width > height, 36.dp, 1180.dp, 4, playerTwoPane)
    }
}
