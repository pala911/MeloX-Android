package com.lladlam.melox.ui.player

import android.os.Build
import android.view.RoundedCorner
import android.view.View
import androidx.annotation.RequiresApi
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.core.view.ViewCompat
import com.lladlam.melox.ui.layout.rememberMeloXWindowInfo
import kotlinx.coroutines.delay

/**
 * 系统屏幕圆角半径（px）。取四角最大值；读不到（API < 31 / 机型不上报）返回 null。
 *
 * ⚠ 只在组合期调用一次、**不注册 insets 监听**：`ViewCompat.setOnApplyWindowInsetsListener`
 *   是**覆盖式**的，再注册一个会把 `meloXPlayerStatusBarsPadding` 里那个监听顶掉
 *   （播放页状态栏内边距会失效）。刷新靠 [rememberMeloXDisplayCornerPx] 的 key。
 *
 * 真值参考（iQOO 15）：`adb shell dumpsys window | grep mRoundedCorners` →
 * `RoundedCorner{position=TopLeft, radius=184, center=Point(184,184)}`（四角同值，= 46dp）。
 */
internal fun meloXDisplayCornerRadiusPx(view: View): Float? =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) meloXDisplayCornerRadiusPxS(view) else null

@RequiresApi(Build.VERSION_CODES.S)
private fun meloXDisplayCornerRadiusPxS(view: View): Float? {
    val insets = ViewCompat.getRootWindowInsets(view) ?: return null
    val radii = listOf(
        RoundedCorner.POSITION_TOP_LEFT,
        RoundedCorner.POSITION_TOP_RIGHT,
        RoundedCorner.POSITION_BOTTOM_LEFT,
        RoundedCorner.POSITION_BOTTOM_RIGHT,
    ).mapNotNull { insets.getRoundedCorner(it)?.radius?.takeIf { radius -> radius > 0 } }
    return radii.maxOrNull()?.toFloat()
}

/**
 * 取系统屏幕圆角的结果，**运行时原值 + 实际采用值都暴露出来**，
 * 供 debug 包在屏幕上打读数用（用户口径：「再次确认获取系统圆角」）。
 *
 * @param runtimePx `WindowInsets` 原始读数（null = 没读到 → 用兜底）
 * @param usedPx    最终渲染采用的值（px）
 */
internal data class MeloXDisplayCornerInfo(val runtimePx: Float?, val usedPx: Float)

/**
 * 取系统屏幕圆角（px），**轮询到拿到为止**。
 *
 * ⚠ 组合期读一次多半拿到 null（insets 还没派发）⇒ 会一直停在兜底值上，等于「根本没取到」
 *   （用户 2026-09-28 真机报障「你现在系统圆角都获取不到」就是这么来的）。
 *   这里最多试 20 次（每次间隔 50ms），拿到**落在合理区间**（屏宽的 8%~20%）的值才采用；
 *   超时/不合理则用 [MeloXPlayerRecedeCompatFallbackDp]（46dp，与 iQOO 15 的系统真值等值）。
 *   **平板档（[MeloXWindowInfo.supportsPlayerTwoPane]）不走这套手机比例判据**，
 *   直接采用 [MeloXTabletDisplayCornerDp]，理由见该常量。
 *
 * 播放器页面（`MeloXApp` 的下沉背板）和外壳卡片（`MeloXIOSNowPlayingSharedHost`）
 * **必须用同一个值** —— 否则同一屏里两个圆角差一点点，肉眼就是「对不上」。
 */
@Composable
internal fun rememberMeloXDisplayCornerInfo(): MeloXDisplayCornerInfo {
    val view = LocalView.current
    val density = LocalDensity.current
    val configuration = LocalConfiguration.current
    // 平板档（双栏大屏）不吃手机那套比例判据，见 [MeloXTabletDisplayCornerDp]。
    val tablet = rememberMeloXWindowInfo().supportsPlayerTwoPane
    val fallbackPx = with(density) { MeloXPlayerRecedeCompatFallbackDp.dp.toPx() }
    val tabletPx = with(density) { MeloXTabletDisplayCornerDp.dp.toPx() }
    var info by remember(
        view,
        configuration.screenWidthDp,
        configuration.screenHeightDp,
        tablet,
    ) {
        mutableStateOf(
            MeloXDisplayCornerInfo(runtimePx = null, usedPx = if (tablet) tabletPx else fallbackPx),
        )
    }
    LaunchedEffect(view, configuration.screenWidthDp, configuration.screenHeightDp, tablet) {
        if (tablet) {
            // 平板档：**不参与**手机比例判据（见 [MeloXTabletDisplayCornerDp]），
            // 但仍把 WindowInsets 原值轮询出来，供 debug HUD 对照。
            var runtime: Float? = null
            for (attempt in 0 until 20) {
                runtime = meloXDisplayCornerRadiusPx(view)
                if (runtime != null) break
                delay(50L)
            }
            info = MeloXDisplayCornerInfo(runtimePx = runtime, usedPx = tabletPx)
            return@LaunchedEffect
        }
        val widthPx = with(density) { configuration.screenWidthDp.dp.toPx() }
        repeat(20) {
            val runtime = meloXDisplayCornerRadiusPx(view)
            if (runtime != null) {
                info = if (runtime >= widthPx * 0.08f && runtime <= widthPx * 0.20f) {
                    MeloXDisplayCornerInfo(runtimePx = runtime, usedPx = runtime)
                } else {
                    // 读到了但落在不合理区间（个别 ROM 会把别的量当 radius 报）
                    MeloXDisplayCornerInfo(runtimePx = runtime, usedPx = fallbackPx)
                }
                return@LaunchedEffect
            }
            delay(50L)
        }
    }
    return info
}

/** 只要最终值时的便捷入口。 */
@Composable
internal fun rememberMeloXDisplayCornerPx(): Float = rememberMeloXDisplayCornerInfo().usedPx

/** [rememberMeloXDisplayCornerPx] 的兜底 dp（= iQOO 15 系统真值 184px @density 4）。 */
internal const val MeloXPlayerRecedeCompatFallbackDp = 46f

/**
 * **平板档**显示圆角（dp）。
 *
 * ⚠ 为什么不能沿用手机那套：[rememberMeloXDisplayCornerInfo] 的两条基准**全是手机的** ——
 *   · 合理性判据 `圆角 ∈ 屏宽 8%~20%`（iQOO 15：184px / 1440px = 12.8%）；
 *   · 兜底 [MeloXPlayerRecedeCompatFallbackDp] = 46dp（= iQOO 15 的系统真值）。
 *   平板屏更宽，同一个物理圆角按**屏宽**算只剩 ~6% ⇒ 被判「不合理」而回落到手机的 46dp
 *   ⇒ **平板套着一个手机尺寸的圆角**（2026-10-07 用户报障：「对手机比例操纵的圆角」）。
 *   而平板自己的屏圆角在 dp 上本来就是**小**的：iPad 12.9" = 18pt / 1024pt 宽 = 1.8%，
 *   折算到 800dp 短边 ≈ 18dp。⇒ 平板档直接吃这个小圆角，不再走手机的比例判据。
 *
 * ⚠ 只挂在 [com.lladlam.melox.ui.layout.MeloXWindowInfo.supportsPlayerTwoPane]
 *   （宽 ≥ 840dp 且高 ≥ 600dp）上 —— 平板**竖屏**仍是手机档，符合「竖屏不改」。
 *
 * ⚠ 与手机档同一条约定：这个值被**播放页下沉背板 / 播放器外壳峰值 / 页面转场裁剪**三处
 *   共用（同一屏里两个圆角差一点点就肉眼对不上），改这里 = 三处同时改。
 */
internal const val MeloXTabletDisplayCornerDp = 18f
