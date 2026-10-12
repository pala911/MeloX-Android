/*
 * MeloX Backdrop controls
 *
 * Adapted from the LiquidButton and LiquidBottomTabs examples in
 * Kyant0/AndroidLiquidGlass. Upstream is licensed under Apache-2.0.
 * https://github.com/Kyant0/AndroidLiquidGlass
 */
package com.lladlam.melox.ui.glass

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ClipOp
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.GraphicsLayerScope
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
// `util.lerp` 是 Float 版本，`graphics.lerp` 是 Color 版本 —— 参数类型不同，可共存。
import androidx.compose.ui.util.lerp
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.fastCoerceAtMost
import com.kyant.backdrop.Backdrop
import com.kyant.backdrop.backdrops.LayerBackdrop
import com.kyant.backdrop.backdrops.rememberCombinedBackdrop
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.lens
import com.kyant.backdrop.effects.vibrancy
import com.kyant.backdrop.highlight.Highlight
import com.kyant.backdrop.shadow.InnerShadow
import com.kyant.backdrop.shadow.Shadow
import com.lladlam.melox.ui.theme.isMeloXDarkTheme
import com.lladlam.melox.ui.settings.MeloXSettingsRuntime
import com.lladlam.melox.ui.glass.publicdemo.PublicInteractiveHighlight
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.remember
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.tanh

/** The screen backdrop sampled by all MeloX liquid controls. */
val LocalMeloXBackdrop = staticCompositionLocalOf<Backdrop?> { null }

/**
 * 底部 chrome（底栏 / 播放栏 / 搜索键）共用的**暗度标量 d**：0 = 亮式，1 = 暗式。
 *
 * ⚠ **一切深浅相关的取值都必须由这一个标量推出来**：`lerp(亮端点, 暗端点, d)`。
 *   任何一层再自己去读 `isMeloXDarkTheme()`，都会在 d 停在中间态时错位 ——
 *   「阴影已经变暗、高光还是亮的」读起来是涂抹，不是切换。
 *
 * 未显式 Provide 时退化为 0/1（跟随当前主题），既有调用点行为不变。
 * 生产者见 `BottomBarToneSampler.kt`。
 */
val LocalBottomBarTone = staticCompositionLocalOf<Float> { Float.NaN }

/** 读取当前暗度；未被 Provide 时退回主题二值。 */
@Composable
internal fun bottomBarTone(): Float {
    val provided = LocalBottomBarTone.current
    return if (provided.isNaN()) (if (isMeloXDarkTheme()) 1f else 0f) else provided
}

// 底栏 / 播放栏 / 搜索键共用的玻璃涂层定义（单一真相源，避免各处漂移）。
// 深色下需要一层 Black 0.10 的 tint 压暗，浅色下是白 0.12 提亮。
//
// 2026-09-25：端点抽成常量。原先这两个函数内部直接 `if (isMeloXDarkTheme())` 返回
// **离散二值**，无法参与「明暗两种样式之间平滑过渡」——必须能从外部喂一个 0~1 的 d。
internal val BottomGlassTintLight = Color.White.copy(alpha = 0.12f)
internal val BottomGlassTintDark = Color.Black.copy(alpha = 0.10f)

@Composable
internal fun bottomLiquidGlassTint(): Color =
    lerp(BottomGlassTintLight, BottomGlassTintDark, bottomBarTone())

/**
 * ⚠ 返回值的 alpha 会被调用方 `.copy(alpha = BOTTOM_GLASS_SURFACE_ALPHA)` **覆盖掉**，
 *   所以这里 0.56 / 0.58 实际不生效、只有**基色**（White vs surface）参与最终观感。
 *   （既有行为，本次不动；记一笔，别误以为它是涂层浓度。）
 */
@Composable
internal fun bottomGlassFallbackColor(): Color {
    val d = bottomBarTone()
    return lerp(
        Color.White.copy(alpha = 0.56f),
        MaterialTheme.colorScheme.surface.copy(alpha = 0.58f),
        d,
    )
}

/**
 * 底栏 / 播放栏 / 搜索键三块玻璃**共用的涂层浓度**（单一真相源）。
 *
 * 2026-09-23：浅色下用户要求「以播放栏的偏浓观感为准」整体加浓。
 * 深浅两档独立取值——浅色需要更实的白纱来压住透出来的亮内容，
 * 深色维持原来的克制浓度即可，故分开写。
 *
 * 2026-09-25（v0.10.2）：**深浅两档同时** 0.26 → **0.52**，用户要求「涂层再加厚一点」。
 *   背景：同日先把 blur 从 8dp 降到 2dp（更透、更少奶油感），玻璃因此变得更"清"；
 *   这一步是把浓度补回来做平衡 —— 但注意**不要再用 blur 去补偿**，两者方向相反会互相抵消
 *   （实测 blur 对遮盖度贡献 ≈ 0：8→24px 仅 26.34%→23.21%）。
 *   ⚠ **低浓度区近似线性，小步长必然无感**。实测两点 (0.20→透出 14.8%)、(0.88→2.05%)
 *     拟合得透出率 T ≈ 0.187·(1−α)^1.04：
 *       0.26 → 13.65%（基准）｜0.34 → 12.11%（**仅降 11%，肉眼不可辨**，故弃用）
 *       0.46 → 9.83%（0.72×）｜**0.52 → 8.69%（0.64×，本次取值）**｜0.60 → 7.19%（0.53×）
 *     ⇒ 以后要"看得出变化"，**步长至少 0.12 起**，别再按 0.08 试探。
 *   ⚠ 深浅共用本常量；若要单独调某一档，得像 `bottomLiquidGlassTint()` 那样拆成两个值。
 *   ⚠ `meloXLiquidBottomBar(tint = …)` 的 `tint` 形参**函数体内从未被消费**，传了也无效 ——
 *     玻璃浓度**只**由 `surfaceColor`（即本常量）决定，别去调 `bottomLiquidGlassTint()`。
 */
internal const val BOTTOM_GLASS_SURFACE_ALPHA = 0.52f

/**
 * 水珠 `lens` 的等比换算系数 = MeloX 水珠高 ÷ kyant0 官方基准水珠高。
 *
 * kyant0 `LiquidBottomTabs` 水珠 56dp、`lens(10dp, 14dp, chromatic=true)`；
 * BiliNext 水珠 47dp ⇒ 写死 `lens(8.4dp, 11.8dp)`（= ×0.839）。
 * MeloX 水珠 = `navHeight(展开 57dp) − 8dp` = **49dp** ⇒ **49/56 = 0.875**。
 *
 * ⚠ `refractionHeight` 是「从水珠轮廓向内取样的深度」。水珠越小，同一条「胶囊边 ↔
 *   边界外背景」分界线在相对尺度上越靠外 —— 照搬 56dp 的绝对值会让折射带相对过深。
 *   改 `DropletHeight`（即 `MeloXApp.kt` 里的 `navHeight - 8.dp`）必须同步改这里。
 */
internal const val MeloXDropletLensScale = 49f / 56f

/** 浅色下额外叠的一层白纱浓度（=0 表示不叠），用于整体加浓的 A/B 调档。 */
@Composable
internal fun bottomGlassSurfaceColor(): Color =
    bottomGlassFallbackColor().copy(alpha = BOTTOM_GLASS_SURFACE_ALPHA)

/** Shared interaction state for a liquid surface and the content that rides on it. */
class MeloXLiquidInteraction internal constructor(
    internal val highlight: PublicInteractiveHighlight,
)

@Composable
fun rememberMeloXLiquidInteraction(): MeloXLiquidInteraction {
    val animationScope = rememberCoroutineScope()
    return remember(animationScope) {
        MeloXLiquidInteraction(PublicInteractiveHighlight(animationScope))
    }
}

/**
 * Official LiquidButton-style glass, generalized so existing MeloX controls
 * keep their exact iOS-derived size, shape and content.
 */
@Composable
fun Modifier.meloXLiquidButton(
    shape: Shape,
    material: MeloXGlassMaterial = MeloXGlassMaterial.Regular,
    enabled: Boolean = true,
    tint: Color = Color.Unspecified,
    surfaceColor: Color = Color.Unspecified,
    blurRadius: Dp? = null,
    lensRadius: Dp? = null,
    refractionHeight: Dp? = null,
    interaction: MeloXLiquidInteraction? = null,
): Modifier {
    val baseSpec = MeloXGlassSpec.forMaterial(material)
    val animationScope = rememberCoroutineScope()
    val interactiveHighlight = interaction?.highlight ?: remember(animationScope) {
        PublicInteractiveHighlight(animationScope)
    }
    return meloXGlassSurface(
        shape = shape,
        material = material,
        enabled = enabled,
        tint = tint,
        surfaceColor = surfaceColor,
        spec = baseSpec.copy(
            blurRadius = blurRadius ?: baseSpec.blurRadius,
            lensRadius = lensRadius ?: baseSpec.lensRadius,
            refractionHeight = refractionHeight ?: baseSpec.refractionHeight,
            // Apple reserves the clear variant for visually rich media.
            // Regular controls use blur/vibrancy without the lens distortion.
            useLens = true,
        ),
        pressProgress = if (enabled) interactiveHighlight.pressProgress else 0f,
        dragOffset = if (enabled) interactiveHighlight.offset else Offset.Zero,
    ).then(if (enabled && !MeloXSettingsRuntime.frostedGlassEnabled) interactiveHighlight.modifier else Modifier)
        .then(if (enabled && !MeloXSettingsRuntime.frostedGlassEnabled) interactiveHighlight.gestureModifier else Modifier)
}

/** Applies the same optical press/drag transform to content placed over liquid glass. */
fun Modifier.meloXLiquidContentTransform(interaction: MeloXLiquidInteraction): Modifier =
    graphicsLayer {
        val controlHeight = size.height.coerceAtLeast(1f)
        val dragOffset = interaction.highlight.offset
        val pressProgress = safeProgress(interaction.highlight.pressProgress)
        val baseScale = (1f + (4.dp.toPx() / controlHeight) * pressProgress).finiteOrZero()
        val maxOffset = size.minDimension.coerceAtLeast(1f)
        translationX = (maxOffset * tanh(0.05f * dragOffset.x.finiteOrZero() / maxOffset)).finiteOrZero()
        translationY = (maxOffset * tanh(0.05f * dragOffset.y.finiteOrZero() / maxOffset)).finiteOrZero()
        val maxDragScale = 4.dp.toPx() / controlHeight
        val angle = atan2(dragOffset.y.finiteOrZero(), dragOffset.x.finiteOrZero())
        scaleX = baseScale + maxDragScale * abs(cos(angle) * dragOffset.x.finiteOrZero() / size.maxDimension.coerceAtLeast(1f)) *
            (size.width / controlHeight).fastCoerceAtMost(1f)
        scaleY = baseScale + maxDragScale * abs(sin(angle) * dragOffset.y.finiteOrZero() / size.maxDimension.coerceAtLeast(1f)) *
            (controlHeight / size.width.coerceAtLeast(1f)).fastCoerceAtMost(1f)
    }

/** Shared material entry point for all Native Component-style controls. */
@Composable
fun Modifier.meloXGlassSurface(
    shape: Shape,
    material: MeloXGlassMaterial = MeloXGlassMaterial.Regular,
    enabled: Boolean = true,
    tint: Color = Color.Unspecified,
    surfaceColor: Color = Color.Unspecified,
    pressProgress: Float = 0f,
    dragOffset: Offset = Offset.Zero,
    spec: MeloXGlassSpec = MeloXGlassSpec.forMaterial(material),
): Modifier {
    val backdrop = meloXGlassBackdrop()
    val alphaScale = if (enabled) 1f else 0.48f
    // ⚠ 按压全程一律走清洗后的 p（欠阻尼弹簧会过冲出 [0,1]，见 safeProgress 注释）。
    val p = safeProgress(pressProgress)
    val isPlain = surfaceColor == Color.Transparent && tint == Color.Unspecified
    val dark = isMeloXDarkTheme()
    if (isPlain) return this
    if (backdrop == null) {
        // Keep explicit translucency intact. The previous fallback raised a
        // 5% white tint to 72%, turning every regular glass field into a solid
        // gray slab when no sampled backdrop was available.
        val defaultAlpha = if (dark) 0.84f else 0.88f
        val tintAlphaFloor = if (dark) 0.18f else 0.22f
        val stableSurface = when {
            surfaceColor != Color.Unspecified -> surfaceColor.copy(
                alpha = surfaceColor.alpha * alphaScale,
            )
            tint == Color.Unspecified -> MaterialTheme.colorScheme.surface.copy(alpha = defaultAlpha * alphaScale)
            else -> tint.copy(alpha = maxOf(tint.alpha, tintAlphaFloor) * alphaScale)
        }
        return background(stableSurface, shape)
    }
    if (MeloXSettingsRuntime.frostedGlassEnabled) {
        return drawBackdrop(
            backdrop = backdrop,
            shape = { shape },
            effects = { blur(spec.blurRadius.toPx()) },
            highlight = null,
            shadow = null,
            innerShadow = null,
            onDrawSurface = {
                if (tint != Color.Unspecified) drawRect(tint.copy(alpha = tint.alpha * alphaScale))
                if (surfaceColor != Color.Unspecified) drawRect(surfaceColor.copy(alpha = surfaceColor.alpha * alphaScale))
            },
        )
    }
    return this.drawBackdrop(
        backdrop = backdrop,
        shape = { shape },
        effects = {
            vibrancy()
            blur(spec.blurRadius.toPx())
            if (spec.useLens) {
                lens(
                    spec.lensRadius.toPx(),
                    spec.refractionHeight.toPx(),
                    depthEffect = p > 0.01f,
                    chromaticAberration = false,
                )
            }
        },
        highlight = {
            Highlight.Default.copy(
                alpha = ((if (dark) 0.32f else 0.48f) + 0.30f * p)
                    .coerceAtMost(1f),
            )
        },
        shadow = {
            Shadow(
                radius = 24.dp,
                color = Color.Black.copy(alpha = 0.12f),
                alpha = (0.08f + 0.22f * p) * if (enabled) 1f else 0.35f,
            )
        },
        innerShadow = {
            InnerShadow(
                radius = 4.dp + 8.dp * p,
                color = Color.Black.copy(alpha = 0.12f),
                alpha = (0.10f + 0.30f * p) * if (enabled) 1f else 0.35f,
            )
        },
        layerBlock = {
            val controlHeight = size.height.coerceAtLeast(1f)
            val scale = (1f + (4.dp.toPx() / controlHeight) * p).finiteOrZero()
            val maxOffset = size.minDimension.coerceAtLeast(1f)
            translationX = (maxOffset * tanh(0.05f * dragOffset.x.finiteOrZero() / maxOffset)).finiteOrZero()
            translationY = (maxOffset * tanh(0.05f * dragOffset.y.finiteOrZero() / maxOffset)).finiteOrZero()
            val maxDragScale = 4.dp.toPx() / controlHeight
            val angle = atan2(dragOffset.y.finiteOrZero(), dragOffset.x.finiteOrZero())
            scaleX = scale + maxDragScale * abs(cos(angle) * dragOffset.x.finiteOrZero() / size.maxDimension.coerceAtLeast(1f)) *
                (size.width / controlHeight).fastCoerceAtMost(1f)
            scaleY = scale + maxDragScale * abs(sin(angle) * dragOffset.y.finiteOrZero() / size.maxDimension.coerceAtLeast(1f)) *
                (controlHeight / size.width.coerceAtLeast(1f)).fastCoerceAtMost(1f)
        },
        onDrawSurface = {
            drawRect(
                Color.White.copy(alpha = if (dark) 0.045f else 0.12f),
                blendMode = BlendMode.Screen,
            )
            if (p > 0.001f) {
                drawRect(Color.White.copy(alpha = 0.08f * p), blendMode = BlendMode.Plus)
            }
            if (tint != Color.Unspecified && tint.alpha > 0.001f) {
                drawRect(tint.copy(alpha = tint.alpha * alphaScale), blendMode = BlendMode.Hue)
                drawRect(tint.copy(alpha = tint.alpha * 0.75f * alphaScale))
            }
            if (surfaceColor != Color.Unspecified) {
                drawRect(surfaceColor.copy(alpha = surfaceColor.alpha * alphaScale))
            }
        },
    )
}

/**
 * Plain background blur. Unlike Liquid Glass this applies no lens, refraction
 * or vibrancy; it only blurs the recorded scene and optionally lays a tint.
 */
@Composable
fun Modifier.meloXBackdropBlur(
    shape: Shape,
    blurRadius: Dp = 20.dp,
    surfaceColor: Color = Color.Transparent,
): Modifier {
    val backdrop = meloXGlassBackdrop()
    if (backdrop == null) return background(surfaceColor, shape)
    return drawBackdrop(
        backdrop = backdrop,
        shape = { shape },
        effects = { blur(blurRadius.toPx()) },
        highlight = null,
        shadow = null,
        innerShadow = null,
        onDrawSurface = {
            if (surfaceColor != Color.Transparent) drawRect(surfaceColor)
        },
    )
}

/**
 * Standard content-layer material. Apple explicitly separates this from
 * Liquid Glass: lists, settings groups and content cards should provide
 * distinction without becoming another floating functional layer.
 */
@Composable
fun Modifier.meloXContentSurface(
    shape: Shape,
    surfaceColor: Color = Color.Unspecified,
): Modifier {
    val color = if (surfaceColor == Color.Unspecified) {
        MaterialTheme.colorScheme.surface
    } else {
        surfaceColor
    }
    return background(color, shape)
}

/** Official LiquidBottomTabs-style outer panel.
 *
 * [exportedBackdrop] re-exports this panel's own rendered glass as a
 * [LayerBackdrop]. Nested controls (e.g. the moving selection capsule) can then
 * sample the panel *without* re-rendering a second copy of the same glass —
 * kyant0 records the panel body once, per frame, instead of twice.
 */
@Composable
fun Modifier.meloXLiquidBottomBar(
    shape: Shape,
    tint: Color,
    surfaceColor: Color,
    pressProgress: Float = 0f,
    // 官方面板 lens 是固定 24dp；此参数仅为兼容既有调用点保留，不再参与 lens。
    // （照搬官方之后 lens 值由本函数内部固定，不由调用方注入。）
    refractionHeight: Dp = 24.dp,
    exportedBackdrop: LayerBackdrop? = null,
): Modifier {
    val backdrop = meloXGlassBackdrop()
    if (backdrop == null) {
        // Flatten the requested translucent material over the current page
        // color. Raising a dark tint to a fixed 48% made light segmented
        // controls look charcoal instead of iOS's subtle neutral fill.
        val stableSurface = surfaceColor.compositeOver(MaterialTheme.colorScheme.background)
        return background(stableSurface, shape)
    }
    if (MeloXSettingsRuntime.frostedGlassEnabled) {
        return drawBackdrop(
            backdrop = backdrop,
            shape = { shape },
            effects = { blur(8.dp.toPx()) },
            highlight = null,
            shadow = null,
            innerShadow = null,
            exportedBackdrop = exportedBackdrop,
            onDrawSurface = { drawRect(surfaceColor) },
        )
    }
    // ⚠ **藏在函数内部的第二处深浅分支，也必须同一个 d**（原先直接读
    //   `isMeloXDarkTheme()`）。它外部看不见，是最容易漏的一处 —— 漏了的结果就是
    //   涂层已经在过渡、高光还停在旧档，观感上是「切换了一半」。
    val tone = bottomBarTone()
    val p = safeProgress(pressProgress)
    return drawBackdrop(
        backdrop = backdrop,
        shape = { shape },
        effects = {
            vibrancy()
            // ── 逐项照搬 kyant0 官方 `LiquidBottomTabs` 面板层（2026-09-25）────────
            //   官方源 `app/src/commonMain/.../components/LiquidBottomTabs.kt` 第 1 层：
            //     effects = { vibrancy(); blur(8f.dp.toPx()); lens(24f.dp.toPx(), 24f.dp.toPx()) }
            //     onDrawSurface = { drawRect(containerColor) }
            // ── blur = 2dp（2026-09-25 **用户拍板**，偏离官方 8dp）──────────────────
            //   官方 kyant0 `LiquidBottomTabs` 面板层 = vibrancy + blur(8dp) + lens(24,24)。
            //   历史故障：本项目曾在「极端折射诊断」期间把 blur 写成 2dp 并遗留，真机症状
            //     「指示器颜色和水珠折射都很混乱」—— 成因是锐利的页面内容穿进 lens，
            //     被 5× 放大链撕成碎片。
            //   本次用户**明确要求**偏离官方值（观感：更少奶油感、更透），且**面板与捕获层
            //     同步取值**，以维持下面这条约束。
            //   取值沿革：8dp(官方) → 2dp → 3dp → 2.5dp → **2dp**（2026-09-25 回调：2.5dp 偏糊，回到更透的 2dp）。
            //   ⚠ 不变约束：面板 blur 必须 ≥ 捕获层 blur（现二者相等，均为 2dp）。
            //   ⚠ 若真机再现「指示器 / 折射混乱」，**第一嫌疑就是本行的低 blur** ——
            //     排查第一步应是把它改回 8dp 验一次，别先去动 lens。
            blur(2.dp.toPx())
            lens(24.dp.toPx(), 24.dp.toPx())
        },
        highlight = {
            Highlight.Default.copy(
                alpha = (lerp(0.48f, 0.32f, tone) + 0.30f * p).coerceAtMost(1f)
            )
        },
        shadow = {
            Shadow(
                radius = 24.dp,
                color = Color.Black.copy(alpha = 0.12f),
                alpha = 0.08f + 0.22f * p,
            )
        },
        innerShadow = {
            InnerShadow(
                radius = 4.dp + 8.dp * p,
                color = Color.Black.copy(alpha = 0.12f),
                alpha = 0.10f + 0.30f * p,
            )
        },
        layerBlock = {
            val scale = (1f + 16.dp.toPx() / size.width.coerceAtLeast(1f) * p).finiteOrZero()
            scaleX = scale
            scaleY = scale
        },
        exportedBackdrop = exportedBackdrop,
        onDrawSurface = {
            drawRect(surfaceColor)
        },
    )
}

/**
 * 播放页三键「选中态玻璃圆盘」材质（2026-10-01）。
 *
 * ⚠ **绝不能复用 `meloXLiquidBottomBar`** —— 那个材质是为底栏**胶囊**调的，带
 *   `lens(24dp, 24dp)` + `Highlight.Default` + `InnerShadow(4dp)` + `Shadow(24dp)`。
 *   照搬到 40dp 的小圆盘上，真机症状正是「一个圆里有白环加图标」：
 *   ① `lens(24dp, 24dp)` 的折射半径接近圆盘半径，圆周被放大弯折成一圈；
 *   ② `Highlight.Default` 沿圆形边缘再补一圈亮环 ⇒ 那道「白环」；
 *   ③ `InnerShadow` 默认 offset `(0, radius)` 在顶沿压出暗带。
 *
 *   所以这里只要 **vibrancy + blur 的纯模糊玻璃**，无任何边缘装饰；
 *   中间的图标由 `DST_OUT` 镂空（见 `ScenePageButton`）。
 */
@Composable
fun Modifier.meloXScenePageDisc(
    surfaceColor: Color,
    holePath: Path? = null,
): Modifier {
    val backdrop = meloXGlassBackdrop()
    if (backdrop == null) {
        // 无 backdrop 时退化成一层稳定的半透明圆（避免整枚按钮凭空消失）。
        return background(
            surfaceColor.compositeOver(MaterialTheme.colorScheme.background),
            CircleShape,
        )
    }
    return drawBackdrop(
        backdrop = backdrop,
        shape = { CircleShape },
        effects = {
            vibrancy()
            blur(6.dp.toPx())
        },
        highlight = null,
        shadow = null,
        innerShadow = null,
        onDrawSurface = {
            if (holePath != null) {
                // ★ 选中态「镂空图标」：用 ClipOp.Difference 把图标形状从白色纱上
                //   挖掉，露出其下的模糊背景（专辑封面），形成真正的镂空而非反色实心。
                //   holePath 必须位于圆盘坐标系（0..size），由调用方按 SF 字形轮廓生成。
                clipPath(holePath, ClipOp.Difference) {
                    drawRect(surfaceColor)
                }
            } else {
                drawRect(surfaceColor)
            }
        },
    )
}

/** 水珠的「捕获层」（= 官方 `LiquidBottomTabs` 的隐形第 2 层 / BiliNext 的隐形捕获层）。
 *
 * ⚠ **它和可见面板不是同一种材质，绝不能复用 `meloXLiquidBottomBar`。**
 *
 * 官方 `netease_module/kyant0/.../components/LiquidBottomTabs.kt` L195-228（第 2 层）:
 * ```
 * vibrancy() + blur(8dp) + lens(24dp * p, 24dp * p)      // H、A 都乘 pressProgress
 * highlight = Highlight.Default.copy(alpha = p)          // 也乘 p
 * onDrawSurface = drawRect(containerColor)               // 只有一层半透明白纱
 * // ← 没有 shadow、没有 innerShadow
 * ```
 * BiliNext `LiquidGlassTabsBar.kt` L~395 的隐形捕获层同样：`lens(H*p, A*p)`、
 * `highlight × p`、`drawRect(containerColor)`，**同样没有 shadow / innerShadow**。
 *
 * 而 `meloXLiquidBottomBar` 是**面板材质**，三处都不一样：
 * ① `lens(10dp, 28dp)` **不乘 p** —— 面板恒折（对面板是对的）；
 * ② 带 `Shadow(24dp, dy≈4dp)`；
 * ③ 带 `InnerShadow(4dp + 8dp·p)`，而 `InnerShadow` 的默认 offset 是
 *    `DpOffset(0, radius)`（`shadow/InnerShadow.kt`）⇒ **这条暗带只压顶沿**。
 *
 * 复用面板材质当捕获层的三个后果（正是「只有下沿对 / 折射的是胶囊内容」的根因）：
 * - **①恒折的 lens 先把「页面在胶囊边界处的弯折」吃掉了**。水珠的 lens 折到的是
 *   一份**已经被折过**的图，页面的边界结构消失，只剩图标/文字这种高对比硬结构
 *   ⇒ 读成「折射的是胶囊的内容」，而不是「折射背景」。
 * - **③顶沿暗带（≈+13% 黑）正压在水珠顶沿最需要对比的 0~7dp 折叠区**，下沿没有
 *   ⇒ 上沿糊、下沿清楚，「只有下面是正确的折射」。
 * - ②把一圈外阴影烘进捕获层，水珠边缘会出现重复唇线。
 *
 * ⇒ 捕获层必须是「**静息时只是一扇干净的通光窗（背景直接透出来），按压时才开折**」。
 * 口径与面板对齐（同一组 H/A、同一 blur），唯一区别就是**乘 p** 且**不带 shadow/InnerShadow**。
 */
@Composable
fun Modifier.meloXLiquidCaptureLayer(
    shape: Shape,
    surfaceColor: Color,
    pressProgress: Float,
    refractionHeight: Dp = 10.dp,
    refractionAmount: Dp = 28.dp,
): Modifier {
    val backdrop = meloXGlassBackdrop() ?: return this
    val p = safeProgress(pressProgress)
    if (MeloXSettingsRuntime.frostedGlassEnabled) {
        return drawBackdrop(
            backdrop = backdrop,
            shape = { shape },
            effects = { blur(8.dp.toPx()) },
            highlight = null,
            shadow = null,
            innerShadow = null,
            onDrawSurface = { drawRect(surfaceColor) },
        )
    }
    return drawBackdrop(
        backdrop = backdrop,
        shape = { shape },
        effects = {
            vibrancy()
            // ── 逐项照搬 kyant0 官方 `LiquidBottomTabs` 捕获层（2026-09-25）────────
            //   官方源 `app/src/commonMain/.../components/LiquidBottomTabs.kt` 第 2 层：
            //     effects = { val p = pressProgress
            //                 vibrancy(); blur(8f.dp.toPx()); lens(24dp * p, 24dp * p) }
            //     highlight = { Highlight.Default.copy(alpha = p) }
            //     onDrawSurface = { drawRect(containerColor) }
            //     // ← 没有 shadow、没有 innerShadow
            //   ⚠ blur 固定 2dp —— **必须与面板同值**（面板已同步改 2dp），
            //     以满足「面板 blur ≥ 捕获层 blur」。
            //     ⚠ 改一处就必须改另一处：二者一旦不等，就会重现历史上的折射混乱。
            blur(2.dp.toPx())
            // ⚠ **静息（p≈0）必须整段跳过 lens，不能传 lens(0, 0)**。
            //   AGSL 里：`if (-sd >= refractionHeight) return content.eval(coord);`
            //   refractionHeight = 0 时，形状内部（sd ≤ 0）全部提前返回 —— 但**抗锯齿边缘
            //   那些 sd > 0 的像素会落入下一行**：
            //     `float d = circleMap(1.0 - -sd / refractionHeight) * refractionAmount;`
            //   ⇒ 除以 0 ⇒ ±Inf ⇒ `sqrt(1 - Inf²)` ⇒ NaN ⇒ `content.eval(NaN)`，
            //   GLSL ES 下采样 NaN 坐标行为未定义（Adreno 上碰巧无害，Mali/老驱动可能花屏
            //   甚至触发 GPU fault）。
            //   跳过 lens 的输出与「提前返回」逐像素相同 —— 折痕本来就只在按压时出现。
            if (p > 0.001f) {
                lens(24.dp.toPx() * p, 24.dp.toPx() * p)
            }
        },
        highlight = { Highlight.Default.copy(alpha = p) },
        // 官方/BiliNext 的捕获层都没有这两个 —— 见上面注释 ②③。
        shadow = null,
        innerShadow = null,
        onDrawSurface = { drawRect(surfaceColor) },
    )
}

/**
 * Moving/selected tab lens used inside the bottom panel.
 *
 */
@Composable
fun Modifier.meloXLiquidTabSelection(
    shape: Shape,
    selected: Boolean,
    tint: Color,
    panelBackdrop: Backdrop? = null,
    pressProgress: Float = 0f,
    // 按压放大走的层内缩放（= 官方 `LiquidBottomTabs` 水珠的 layerBlock）。
    layerBlock: (GraphicsLayerScope.() -> Unit)? = null,
): Modifier {
    if (!selected) return this
    val backdrop = meloXGlassBackdrop()
    val surfaceColor = tint
    if (backdrop == null) {
        return background(surfaceColor.copy(alpha = maxOf(surfaceColor.alpha, 0.36f)), shape)
    }
    if (MeloXSettingsRuntime.frostedGlassEnabled) {
        return drawBackdrop(
            backdrop = backdrop,
            shape = { shape },
            effects = { blur(8.dp.toPx()) },
            highlight = null,
            shadow = null,
            innerShadow = null,
            onDrawSurface = { drawRect(surfaceColor) },
        )
    }
    // ── 水珠材质：**逐项照搬 kyant0 官方 `LiquidBottomTabs`**（2026-09-25 用户拍板）──
    //   官方源（kmp 分支）`app/src/commonMain/.../components/LiquidBottomTabs.kt` 第 3 层：
    //     effects = { val p = pressProgress
    //                 lens(10dp * p, 14dp * p, chromaticAberration = true) }
    //     highlight   = { Highlight.Default.copy(alpha = p) }
    //     shadow      = { Shadow(alpha = p) }                  // ← 默认 offset，不自定义
    //     innerShadow = { InnerShadow(radius = 8dp * p, alpha = p) }  // ← 默认 offset
    //     onDrawSurface = { drawRect(Black/White 0.1f, alpha = 1f - p)
    //                       drawRect(Black 0.03f * p) }
    //   历史教训：本项目曾在这些值上做「自标定」（H/A 由边界内缩量 d 反推、
    //   Shadow/InnerShadow 强制 offset=(0,0) 等），结果是把视觉效果推出了官方
    //   已验证的区间，真机表现为「指示器颜色与折射都混乱」。**不再自创公式。**
    //
    //   ⚠ **折痕成立的唯一几何前提（2026-09-25 真机实测补充）**：
    //     `refractionHeight = 10dp·p` 是**从水珠轮廓向内**取样的深度。它要读到一条边，
    //     就必须有「至少一部分取样落在水珠轮廓**外**的真实背景上」。官方之所以成立，
    //     是因为**官方水珠不做缩放**（`scaleX/scaleY` 恒 1，见 MeloXApp.kt 的长注释）⇒
    //     水珠轮廓恒等于 Layer 2 胶囊轮廓 ⇒ 折射带天然横跨「内=着色胶囊 / 外=真实背景」。
    //     **一旦水珠被放大 s**，轮廓就离开胶囊边界 `dropletHeight·(s−1)/2`；当该值 ≥ 10dp
    //     （本项目 `dropletHeight = 51dp`、`s = 1.393` ⇒ 溢出 10.0dp）时，整个折射带
    //     全落在胶囊内部，只剩被放大的着色内容 —— 折痕归零。
    //     ⇒ 调 `lens` 参数**救不回**缩放造成的溢出，唯一办法是不缩放。
    val selectionBackdrop = rememberCombinedBackdrop(backdrop, panelBackdrop ?: backdrop)
    // 水珠 / 选中指示器同样由同一个 d 驱动。指示器色是黑白（不是 accent 蓝）：
    // 暗式翻成白、亮式是黑 —— 端点与 `MeloXApp.kt` 的 `selectionTint` 口径一致。
    val tone = bottomBarTone()
    val p = safeProgress(pressProgress)
    return drawBackdrop(
        backdrop = selectionBackdrop,
        shape = { shape },
        effects = {
            // ── 水珠 lens：按水珠高度**等比换算**（BiliNext L520-527 原样思路）──────────
            //   kyant0 官方水珠高 56dp，`lens(10dp, 14dp, chromatic=true)`。
            //   BiliNext 水珠 47dp ⇒ 系数 47/56 ≈ 0.839 ⇒ `lens(8.4dp, 11.8dp)`（源码写死）。
            //   MeloX 水珠 = `navHeight − 8dp`（展开 49dp）⇒ 系数 49/56 = 0.875
            //     ⇒ 8.75dp / 12.25dp。
            //   ⚠ 之所以要换算：`refractionHeight` 是**从轮廓向内取样的深度**，水滴越小、
            //     同一条边界线在相对尺度上越"靠外"，照搬 56dp 的绝对值会让折射带相对过深。
            //   本函数拿不到水珠高度（modifier 里无 size），故用官方基准 56dp 与 MeloX
            //   展开态 49dp 的比值作为固定系数；`DropletHeight` 若再改，改这一个常量。
            val dropletLensScale = MeloXDropletLensScale
            // ⚠ 同 `meloXLiquidCaptureLayer`：静息（p≈0）**整段跳过**，不传 lens(0, 0)。
            //   这里还是带色散的 `RefractionWithDispersion` shader（uniform 更多），
            //   除零产生的 NaN 波及面比普通 lens 更大。跳过后的输出与原「提前返回」一致。
            if (p > 0.001f) {
                lens(
                    refractionHeight = 10.dp.toPx() * p * dropletLensScale,
                    refractionAmount = 14.dp.toPx() * p * dropletLensScale,
                    chromaticAberration = true,
                )
            }
        },
        // ── 静息态视觉（2026-10-02 终版：换回 kyant0 官方方案）──────────────────
        //
        //   09-25 的 restRim/shadow/innerShadow 静息下限、10-02 的纵向受光渐变、
        //   被否决的霜化模糊——全部撤销。水珠三项**逐字回到官方**：
        //     highlight/shadow/innerShadow 一律 ×p ⇒ 静息 p=0 全零，
        //     onDrawSurface 只剩涂层淡出 + 官方 0.03f·p 黑纱。
        //   静息层次感不再由水珠自身叠加提供；若需调浓度，走胶囊 L2 涂层，别动这三项。
        highlight = { Highlight.Default.copy(alpha = p) },
        shadow = { Shadow(alpha = p) },
        innerShadow = { InnerShadow(radius = 8.dp * p, alpha = p) },
        // ⚠ 调用方注入的是一段**裸变换 lambda**（本项目的调用点里含 `velocity` 除法、
        //   弹簧 scale 等）。这些值最终写进 HWUI `RenderNode` 的变换矩阵：一旦是
        //   NaN / ±Inf，矩阵不可逆、绘制行为未定义 —— 这是「能跑玻璃的机型上因为
        //   **自身数值**出问题」的典型入口。
        //   在**唯一的写出口**统一清洗；正常值恒等，观感零变化。
        layerBlock = layerBlock?.let { userBlock ->
            {
                userBlock(this)
                scaleX = scaleX.finiteOrZero()
                scaleY = scaleY.finiteOrZero()
                translationX = translationX.finiteOrZero()
                translationY = translationY.finiteOrZero()
                alpha = alpha.finiteOrZero()
            }
        },
        onDrawSurface = {
            // 官方口径：静息时选中涂层平涂、按压淡出 + 官方 0.03f·p 黑纱。
            // （2026-10-02「涂料下沉」试验过把 tint 搬进捕获层、真机无差异，已回退——
            //   tint 仍画在这里，即采样图之上；红内容的明度上限 = 1−a，见 MeloXApp.kt。）
            drawRect(surfaceColor, alpha = 1f - p)
            drawRect(Color.Black.copy(alpha = 0.03f * p))
        },
    )
}
