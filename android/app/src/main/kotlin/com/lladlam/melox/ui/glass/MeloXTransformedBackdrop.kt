package com.lladlam.melox.ui.glass

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.GraphicsLayerScope
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.unit.Density
import com.kyant.backdrop.Backdrop

/**
 * `rememberBackdrop` 的 2.0.0 兼容复刻（该 API 在 backdrop 2.1+ 才进库）。
 *
 * 包装 [backdrop]：thumb 的 `drawBackdrop` 折射采样时，先应用 [onDraw]
 * （通常施加 scale 等变换）再绘制原背板内容。
 *
 * 官方 AndroidLiquidGlass `LiquidToggle` 用它实现按压时「轨道被吸起变形」的
 * 液态核心：折射源里的轨道按 `pressProgress` 缩放（scaleX 2/3→0.75、
 * scaleY 0→0.75），**真实轨道 Box 不动**，只有透过 thumb 看到的轨道变形。
 */
@Composable
internal fun rememberTransformedBackdrop(
    backdrop: Backdrop,
    onDraw: DrawScope.(drawBackdrop: DrawScope.() -> Unit) -> Unit
): Backdrop {
    return remember(backdrop, onDraw) {
        TransformedBackdrop(backdrop, onDraw)
    }
}

@Immutable
private class TransformedBackdrop(
    val backdrop: Backdrop,
    val onDraw: DrawScope.(drawBackdrop: DrawScope.() -> Unit) -> Unit
) : Backdrop {

    override val isCoordinatesDependent: Boolean = backdrop.isCoordinatesDependent

    override fun DrawScope.drawBackdrop(
        density: Density,
        coordinates: LayoutCoordinates?,
        layerBlock: (GraphicsLayerScope.() -> Unit)?
    ) {
        onDraw { with(backdrop) { drawBackdrop(density, coordinates, layerBlock) } }
    }
}
