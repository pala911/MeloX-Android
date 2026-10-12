package com.lladlam.melox.ui.animation

import android.animation.ValueAnimator
import android.content.Context
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.provider.Settings

/**
 * 让 **Compose 侧的一镜到底转场**跟随「与系统 Activity 转场同一个」动画缩放键。
 *
 * ### 问题（2026-10-02 用户报「设置页跟随系统缩放，首页一镜到底没跟」）
 * 系统里三个缩放是**三个独立的键**，两条转场路径各读各的：
 *
 * | 路径 | 页面 | 读的键 |
 * | --- | --- | --- |
 * | 系统 Activity 转场 | 设置详情 / 账号 / 歌单 / 百科 / 分享 / 服务（`MeloXPageHost`） | `TRANSITION_ANIMATION_SCALE` |
 * | Compose 动画 | 首页 / 搜索 / 资料库 / 播客（`MeloXInterruptiblePage` + `sharedElement`） | `ANIMATOR_DURATION_SCALE` |
 *
 * 所以用户在「过渡动画缩放」上调速度时，只有 Activity 页变、Compose 页纹丝不动 ——
 * 两条路径的速度读数越调越远。
 *
 * ### 解法：把两个键的**比值**补进 Compose 的 spec
 * Compose 的动画时长已经被 `MotionDurationScale` 乘过 `ANIMATOR_DURATION_SCALE`
 * （其 Android 实现读的就是 `ValueAnimator.getDurationScale()`）。因此只要把 spec 的
 * **固有时长**再乘 `transition / animator`，净时长就变成 `base × transition`
 * —— 与系统转场**同键、同比例**。
 *
 * - 三个键同值（本机 1.35）⇒ `ratio = 1` ⇒ **零改动**，现有观感一字不变；
 * - 单独调「过渡动画缩放」⇒ `ratio` 跟着变 ⇒ 首页同步变；
 * - 单独调「动画程序时长缩放」⇒ `ratio` 反向抵消 ⇒ 首页仍**只**跟随 transition。
 *
 * ### ⚠ 边界
 * - `getDurationScale()` 是 `@hide`（greylist）：反射被拦时退化为 1（= 假定 Compose 没缩放），
 *   此时 `ratio = transition`，**仍然跟随正确的键**，只在「两键不一致」时略有偏差。
 * - 只作用于**转场 spec**（[meloXPagePushSpec] / [meloXPageRedirectSpec] /
 *   [MeloXPageBoundsTransform]）；底栏、水珠、播放器等其它 Compose 动画一律不受影响。
 * - spec 在**转场开始那一刻**求值，所以这里只需 `@Volatile` 快照，**不需要**触发重组。
 */
internal object MeloXSystemScale {

    private const val MIN_RATIO = 0.05f
    private const val MAX_RATIO = 20f

    /** 补进 Compose spec 的倍率；`1f` = 与现状完全一致。 */
    @Volatile
    var ratio: Float = 1f
        private set

    /**
     * 弹簧刚度要**除**的量。弹簧的收敛时长 ∝ `1/√k`，所以「时长 ×r」等价于「刚度 ÷ r²」。
     */
    val stiffnessDivisor: Float
        get() = (ratio * ratio).coerceIn(MIN_RATIO * MIN_RATIO, MAX_RATIO * MAX_RATIO)

    /** `tween` 的时长倍率。 */
    val durationMultiplier: Float
        get() = ratio.coerceIn(MIN_RATIO, MAX_RATIO)

    /**
     * **Compose 侧任何 `tween` 都会被自动乘上的那个系统缩放**（`ANIMATOR_DURATION_SCALE`）。
     *
     * ⚠ 这不是 `ratio`：它就是 Compose 内部 `AndroidMotionDurationScale` 读的那个值，
     *   也就是说 `tween(D)` 的**墙钟**实际是 `D × animatorScale`。
     *   用户真机实测三键全 1.35 ⇒ 设 360ms 实跑 486ms ⇒「设置值与实际对不上」。
     *
     * ### 用途：让某段动画的**墙钟时长**固定，不随系统缩放漂移
     * 要让墙钟恰好等于设定值 `D`，spec 的固有时长必须写成 `D / animatorScale`
     * （Compose 再乘回来 ⇒ `D / s × s = D`）。
     * 播放器展开/收回就是这个诉求（见 `MeloXPlayerTransitionSpec`）。
     *
     * ⚠ 与 [ratio] 的区别：`ratio` 补的是**两个键之间的差**（设计给「过渡动画缩放」与
     *   「动画程序时长缩放」不一致的场景）；这里要的是**把系统缩放整个抵消掉**。
     *   三键同值时 `ratio == 1`（补不回来），但 `animatorScale != 1`（能抵消）。
     */
    @Volatile
    var animatorScale: Float = 1f
        private set

    /**
     * 把「期望墙钟毫秒」换算成 `tween` 的**固有时长**（用于抵消系统缩放）。
     *
     * ⚠ `s` 非法或极端时钳到 `[MIN_RATIO, MAX_RATIO]` 的倒数区间，避免 `tween` 拿到
     *   0ms（瞬变、看起来像「没动画」）或极大值（看起来像卡死）。
     */
    fun fixedDurationMillis(desiredWallClockMillis: Int): Int {
        val s = if (animatorScale.isFinite() && animatorScale > 0f) {
            animatorScale.coerceIn(MIN_RATIO, MAX_RATIO)
        } else {
            1f
        }
        return (desiredWallClockMillis / s).toInt().coerceAtLeast(1)
    }

    /** 在 `Application.onCreate()` 调用一次：读初值 + 挂监听（键变化后立即刷新）。 */
    fun install(context: Context) {
        val appContext = context.applicationContext
        val resolver = appContext.contentResolver
        val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) = refresh(appContext)
        }
        for (key in OBSERVED_KEYS) {
            runCatching {
                resolver.registerContentObserver(Settings.Global.getUriFor(key), false, observer)
            }
        }
        refresh(appContext)
    }

    private fun refresh(context: Context) {
        val transition = read(context, Settings.Global.TRANSITION_ANIMATION_SCALE)
            ?: read(context, Settings.Global.WINDOW_ANIMATION_SCALE)
            ?: 1f
        val animator = animatorDurationScale(context)
        // ⚱ 供「固定墙钟时长」用（播放器展开/收回）：必须与 Compose 内部
        //   `AndroidMotionDurationScale` 读的是**同一个值**，否则抵消不掉。
        animatorScale = if (animator > 0f) {
            animator.coerceIn(MIN_RATIO, MAX_RATIO)
        } else {
            1f
        }
        ratio = if (animator > 0f) {
            (transition / animator).coerceIn(MIN_RATIO, MAX_RATIO)
        } else {
            transition.coerceIn(MIN_RATIO, MAX_RATIO)
        }
    }

    /**
     * 与 Compose 内部 `AndroidMotionDurationScale` 读的是同一个值，但**多一条兜底**。
     *
     * ⚠ `ValueAnimator.getDurationScale()` 是 `@hide`（grevelist）：部分 ROM / 新 API 上
     *   反射会被拦（实测 vivo 上曾拿到过异常值）⇒ 退化为直接读
     *   `Settings.Global.ANIMATOR_DURATION_SCALE`，**语义等价**且不依赖隐藏 API。
     * ⚠ 两个来源都取不到才按 1（= 不做抵消，行为退回上游）。
     */
    private fun animatorDurationScale(context: Context): Float {
        val viaReflection = runCatching {
            ValueAnimator::class.java.getMethod("getDurationScale").invoke(null) as Float
        }.getOrNull()
        if (viaReflection != null && viaReflection.isFinite() && viaReflection > 0f) {
            return viaReflection
        }
        return read(context, Settings.Global.ANIMATOR_DURATION_SCALE) ?: 1f
    }

    private fun read(context: Context, key: String): Float? =
        runCatching { Settings.Global.getFloat(context.contentResolver, key, 1f) }.getOrNull()

    private val OBSERVED_KEYS = listOf(
        Settings.Global.TRANSITION_ANIMATION_SCALE,
        Settings.Global.WINDOW_ANIMATION_SCALE,
        Settings.Global.ANIMATOR_DURATION_SCALE,
    )
}
