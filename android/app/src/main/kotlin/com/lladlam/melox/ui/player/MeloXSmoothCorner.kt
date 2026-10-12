package com.lladlam.melox.ui.player

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import com.kyant.shapes.Capsule
import kotlin.math.cos
import kotlin.math.log2
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * **平滑圆角**（连续曲率 / squircle）：Lamé 超椭圆角补丁替代正圆弧。
 *
 * ### 为什么正圆弧「生硬」
 * 正圆弧的曲率**恒为 1/R**，与直边（曲率 0）相接时曲率**跳变**（G1 连续、**G2 不连续**），
 * 肉眼就是「直线突然开始拐」那道硬边。把指数提到 n>2 后，切点处曲率 → 0，是连续长起来的。
 *
 * ### ⚠ 关键一：**尺寸补偿**——不补就是「小圆角」（2026-09-28 用户报障）
 * 用户原话：「平滑了，但修复小圆角而不是实装成系统大圆角的问题」。
 * 根因是**几何尺度**而不是半径写错：超椭圆角在**同一个外接尺寸**下，向外「切掉」的深度
 * 比正圆弧小得多：
 *
 * | 形状 | 对角切深（× 半径） |
 * |---|---|
 * | 正圆弧（系统屏幕圆角） | √2·(1−2^(−1/2)) = **0.4142** |
 * | 超椭圆 n=4（首版） | √2·(1−2^(−1/4)) = **0.2250** |
 *
 * ⇒ 同半径下超椭圆**少切一半**，页面四角显得「方」、读成小圆角（真机实测：角轮廓与
 * `rx=207` 的超椭圆逐点吻合，说明半径没错，是**形状的切深**不对）。
 * 现在把外接半径放大到 `半径 × spanFactor`，使**对角切深 = 同半径正圆弧** ⇒ 观感尺寸
 * 与系统圆角一致（数值见 [MeloXSmoothCorner.spanFactor]，n=2.5 时 span ≈ 222.6px）。
 *
 * ### ⚠ 关键二：指数只取到 **n=2.5**，不再用 n=4
 * n 越大越「方」——要维持同样的切深就得把外接尺寸撑得越大（n=4 需要 338.7px = 84.7dp，
 * 页面顶边只剩 1/3 是直的，读成"大圆弧卡片"而不是"系统圆角"）。n=2.5 时轮廓在
 * `dy ≥ 20px` 区间与系统正圆弧**误差 ≤3px**，同时切点曲率仍为 0（保持平滑）。
 *
 * ### 缩放补偿
 * 页面层挂着非等比缩放（sx≠sy），outline 是在**层内坐标**裁剪后再整体缩放的
 * ⇒ 半径按轴补偿（rx = span/sx、ry = span/sy），保证**可见外接尺寸在 x/y 上都是 span**。
 */
internal class MeloSmoothCornerShape(
    /** 目标**可见**圆角半径（px，= 系统屏幕圆角真值，如 iQOO 15 的 184px）。 */
    private val radiusPx: Float,
    private val scaleX: Float = 1f,
    private val scaleY: Float = 1f,
    private val smoothing: Float = MeloXSmoothCorner.DefaultSmoothing,
) : Shape {

    override fun createOutline(
        size: Size,
        layoutDirection: LayoutDirection,
        density: Density,
    ): Outline {
        val sx = scaleX.coerceAtLeast(0.01f)
        val sy = scaleY.coerceAtLeast(0.01f)
        val fit = minOf(size.width, size.height) / 2f
        if (radiusPx <= 0.5f || fit <= 0.5f) return Outline.Rectangle(Rect(Offset.Zero, size))

        // ⓪ 角吃满半高 ⇒ **就是胶囊**（外壳 morph 到迷你端时 h = 48dp、半径 = 24dp）。
        //    ⚠⚠ 这里必须委托给迷你播放器 / 底栏胶囊**同一个** Shape，不能用超椭圆去凑：
        //       kyant `Capsule()` 是 **Apple 连续曲率**（Bezier，「延伸段」= 2/3·r），
        //       Lamé 超椭圆是另一族曲线 —— 同半径下轮廓仍有硬偏差（2026-09-29 实测：
        //       对角处相差 5.87px ≈ 1.47dp @density 4，真机表现为「缩回迷你播放器时
        //       圆角对不上、边缘一圈暗轮廓」）。委托后是**逐点相同**（0 误差）。
        //    ⚠ 容差 0.5px：共享元素 remeasure 出来的半高与 24dp 目标可能差零点几像素，
        //      不留容差就会漏进超椭圆分支、把那 5.87px 的偏差放回来。
        //    ⚠ sx/sy ≠ 1 只出现在**后页面**（挂非等比缩放），它半径 = 系统圆角、离吃满
        //      半高很远，永远走不到这里；真走到也退回下面的分轴补偿分支，不丢补偿。
        if (radiusPx >= fit - SaturationTolerancePx && sx == 1f && sy == 1f) {
            return Capsule().createOutline(size, layoutDirection, density)
        }

        // ① 平滑度取「外接尺寸不超过半高」允许的**最大值**（反解，不是近似）。
        //    ⚠ 首版是「按 room 降平滑度 → 再把 span 夹到 fit」，夹过之后尺寸补偿就失效了
        //      ⇒ 角比正圆还「满」。反解保证 span 恰好落在 fit 上；radiusPx → fit 时
        //      平滑度 → 0，形状**连续**退化成正圆（而不是跳变）。
        val smoothingEff = minOf(
            smoothing.coerceIn(0f, 1f),
            MeloXSmoothCorner.inverseSpanFactor(fit / radiusPx),
        )
        val exponent = MeloXSmoothCorner.exponent(smoothingEff)
        val e = 2f / exponent

        // ② 尺寸补偿：外接半径 = 目标半径 × spanFactor ⇒ 对角切深与同半径正圆弧相同。
        //    经 ① 反解后 span 恒 ≤ fit，coerceAtMost 只作浮点兜底。
        val span = (radiusPx * MeloXSmoothCorner.spanFactor(smoothingEff)).coerceAtMost(fit)
        if (span <= 0.5f) return Outline.Rectangle(Rect(Offset.Zero, size))
        val rx = (span / sx).coerceIn(0f, size.width / 2f)
        val ry = (span / sy).coerceIn(0f, size.height / 2f)

        val w = size.width
        val h = size.height
        val path = Path()

        // 每个角 32 段直线逼近（222px 角上弦误差 < 0.05px，肉眼读不出）
        fun corner(cx: Float, cy: Float, dirX: Float, dirY: Float, tFrom: Double, tTo: Double) {
            for (i in 0..Steps) {
                val t = tFrom + (tTo - tFrom) * i / Steps
                val cxv = cos(t).toFloat().coerceAtLeast(0f).pow(e)
                val cyv = sin(t).toFloat().coerceAtLeast(0f).pow(e)
                path.lineTo(cx + dirX * rx * cxv, cy + dirY * ry * cyv)
            }
        }

        // ── 顺时针遍历 ──────────────────────────────────────────────────────
        // ⚠ 方向必须与「沿边顺时针」一致：起点取**左上角的上边切点 (rx, 0)**，
        //   沿上边往右走 ⇒ **左上角补丁只能放在最后一段**（(0,ry) → (rx,0)）。
        //   （首版把左上角当第一段画，紧接着 lineTo(w-rx, 0) 就横跨顶部拉出一条斜切边
        //     —— 真机表现为「背景页被画成四边形」。）
        path.moveTo(rx, 0f)
        path.lineTo(w - rx, 0f)
        // 右上角：(w-rx,0) → (w,ry)，中心 (w-rx, ry)
        corner(w - rx, ry, dirX = 1f, dirY = -1f, tFrom = Math.PI / 2, tTo = 0.0)
        path.lineTo(w, h - ry)
        // 右下角：(w,h-ry) → (w-rx,h)，中心 (w-rx, h-ry)
        corner(w - rx, h - ry, dirX = 1f, dirY = 1f, tFrom = 0.0, tTo = Math.PI / 2)
        path.lineTo(rx, h)
        // 左下角：(rx,h) → (0,h-ry)，中心 (rx, h-ry)
        corner(rx, h - ry, dirX = -1f, dirY = 1f, tFrom = Math.PI / 2, tTo = 0.0)
        path.lineTo(0f, ry)
        // 左上角：(0,ry) → (rx,0)，中心 (rx, ry)
        corner(rx, ry, dirX = -1f, dirY = -1f, tFrom = 0.0, tTo = Math.PI / 2)
        path.close()

        // ⚠ 路径必须是**凸**的：Android 的 RenderNode.setOutline 只接受凸路径裁剪。
        return Outline.Generic(path)
    }

    private companion object {
        const val Steps = 32

        /**
         * 「角已吃满半高」的判定容差（px）。见 `createOutline` 里 ⓪ 的说明：
         * 共享元素 remeasure 出的半高与 24dp 目标可能差零点几像素。
         */
        const val SaturationTolerancePx = 0.5f
    }
}

internal object MeloXSmoothCorner {
    /** 默认平滑度 1 → Lamé **n=2.5**（见类注释「关键二」）。 */
    const val DefaultSmoothing = 1f

    /** 最大 Lamé 指数（smoothing=1 时取它）。再大会把外接尺寸撑到不像系统圆角。 */
    const val MaxExponent = 2.5f

    /** 系统正圆弧的「对角切深 / 半径」系数 = √2·(1−2^(−1/2)) ≈ 0.41421。 */
    private val CircleDiagonalCut: Double = sqrt(2.0) * (1.0 - 2.0.pow(-0.5))

    /** Lamé 指数：`smoothing=0 → n=2`（就是正圆弧，对照用），`=1 → n=2.5`。 */
    fun exponent(smoothing: Float): Float =
        2f + (MaxExponent - 2f) * smoothing.coerceIn(0f, 1f)

    /**
     * **外接尺寸倍数**：让超椭圆角的对角切深等于「同半径正圆弧」所需的外接半径倍数。
     * `smoothing=0 → 1.0`（超椭圆退化成圆，无需补偿）；`=1（n=2.5）→ ≈1.2096`。
     */
    fun spanFactor(smoothing: Float): Float {
        val n = exponent(smoothing)
        val cut = sqrt(2.0) * (1.0 - 2.0.pow(-1.0 / n.toDouble()))
        return (CircleDiagonalCut / cut).toFloat()
    }

    /**
     * [spanFactor] 的**反函数**：给定允许的最大外接倍数 `ratio`（= 半高 / 目标半径），
     * 返回不超过它的最大平滑度。用于保证 `radius · spanFactor(sm) ≤ 半高`。
     *
     * - `ratio ≤ 1` ⇒ `0`：正圆（此时角恰好占满半高 = 胶囊体型）。
     * - `ratio ≥ [MaxSpanFactor]` ⇒ `1`：不设上限，保持默认平滑度。
     * - 中间连续可导，因此盒子长高时形状**连续**变化，不会跳变。
     *
     * 推导：`spanFactor = CircleDiagonalCut / cut(n)`，要它为 `ratio` ⇒
     * `2^(−1/n) = 1 − (1 − 2^(−1/2)) / ratio` ⇒ `n = −1 / log2(inner)`。
     */
    fun inverseSpanFactor(ratio: Float): Float {
        if (!(ratio > 1f)) return 0f
        if (ratio >= MaxSpanFactor) return 1f
        val inner = 1.0 - (1.0 - 2.0.pow(-0.5)) / ratio
        if (inner <= 0.0) return 0f
        val n = -1.0 / log2(inner)
        return ((n - 2.0) / (MaxExponent - 2.0)).toFloat().coerceIn(0f, 1f)
    }

    /** `spanFactor(1)` 的缓存值（用于估算盒子放不放得下这个角）。 */
    val MaxSpanFactor: Float = spanFactor(1f)
}
