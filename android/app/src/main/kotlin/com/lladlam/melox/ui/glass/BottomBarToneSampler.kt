package com.lladlam.melox.ui.glass

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.PixelCopy
import android.view.Window
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import com.lladlam.melox.ui.theme.DarkColors
import com.lladlam.melox.ui.theme.LightColors
import kotlin.coroutines.resume
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.pow

/**
 * 底部 chrome（nav 胶囊 / 搜索键 / 播放栏）的**暗度**控制器。
 *
 * `d = 0` 亮式、`d = 1` 暗式。Apple 对 navbars / tabbars 这一档的定义就是
 * 「按背后内容在明暗之间整体翻转」（WWDC25 Session 219 @15:16），所以这里是
 * 一个**离散的目标值**，平滑交给过渡动画做，而不是逐帧连续微调。
 *
 * ### 为什么一定要由单一标量驱动
 * 涂层、高光、阴影、前景色全部 `lerp(亮端点, 暗端点, d)`。任何一层自己再去读
 * `isMeloXDarkTheme()`，都会在 d 处于中间态时错位 ⇒ 读起来是涂抹不是切换。
 *
 * ### 采样为什么必须避开自己的玻璃（2026-09-25 返工）
 *
 * 第一版直接抓**底栏自己的矩形**。但 PixelCopy 抓的是窗口合成结果，**里面已经混进
 * 我们自己的涂层**，于是判据只能靠
 *
 *     captured = content·(1 − A) + coat·A        ⇒      T = 0.5(1 − A) + coat·A
 *
 * 去预测「content 是中灰时应该采到多少」。这里有个**结构性**的坑：判据以为
 * 我们的涂层往 captured 里注入了 `A·coat`（浅式高达 `+0.578` 线性光），一旦真实
 * 注入量不是这个数，两个方向的阈值就**互相不一致**。
 *
 * 令真实有效遮盖度为 `a`、content 亮度为 `c`，两个方向的判据写出来是
 *
 *     亮式 → 转暗：  (1−a)·c + a        < 0.7290
 *     暗式 → 转亮：  (1−a)·c + 0.006a   > 0.2745
 *
 * 两式**同时成立**（= 背景纹丝不动却无限来回翻）⇔ **`a < 0.457`**。
 * 而 ① 采样矩形里胶囊↔搜索键之间**根本没有玻璃**，② `vibrancy/lens/highlight`
 * 都不是仿射 —— 面积加权后的真实 `a` 远低于名义值，稳稳落在那个区间里。
 * ⇒ **这是自反馈环路，调死区、调阈值都救不回来**，只会把「会翻掉的内容亮度」
 * 从一个带挪到另一个带。仿真见 `tmp/sim_tone_decision.py`：扫遍 `c × a`，
 * 旧判据在 `a ∈ [0, 0.45]` 上有一整片格子会无限翻转。
 *
 * 所以判据改成：**只采底栏上沿往上的一条背景带**（不含我们任何一寸玻璃），
 * 于是 `a = 0` 而且**是真的 0** —— 方程退化成 content 本身，配对称迟滞
 * ⇒ 背景静止时候选值是常数 ⇒ **最多只可能翻一次就锁定**（同一份仿真里新判据
 * 441 个格子翻转数为 0）。
 *
 * 注意：阈值落在哪个绝对值上**不影响**这个结论 —— `Y → L*` 是单调变换，
 * 不改变「候选值是否越界」这件事，所以换成感知标度不会把自反馈引回来。
 */
internal class BottomBarToneState internal constructor() {

    /** 窗口坐标系里底栏的矩形；由 `onGloballyPositioned` 喂进来。 */
    internal var bounds: Rect? = null

    /** 目标暗度。`NaN` = 还没决出来，退化为系统主题。 */
    internal var rawTarget: Float = Float.NaN

    /** 对外消费的动画目标（mutableStateOf，驱动 recomposition）。 */
    internal var target by mutableFloatStateOf(Float.NaN)
        private set

    private var bitmap: Bitmap? = null

    /** 已经释放 —— 采样循环看到它就必须退出，别再去 `ensureBitmap()` 造新的。 */
    internal var released: Boolean = false

    internal fun publishTarget(v: Float) {
        target = v
        rawTarget = v
    }

    /**
     * **本次采样应该抓的矩形**：底栏**上沿再往上** [TONE_BAND_DP] 的一条带。
     *
     * ⚠ 绝不能用 [bounds] 本身 —— 那里面有自己的玻璃，见上面那段。
     * 左右各内缩 [TONE_BAND_INSET_DP] 是为了躲开屏幕圆角/边缘那圈不均匀的区域，
     * 它们的亮度不代表内容。
     *
     * @return `null` = 这一轮没法判（没布局 / 太窄 / 顶到了屏幕上沿），调用方跳过。
     */
    internal fun sampleRect(bandPx: Float, insetPx: Float): Rect? {
        val b = bounds ?: return null
        if (b.width() <= 0 || b.height() <= 0) return null
        val left = b.left + insetPx.toInt()
        val right = b.right - insetPx.toInt()
        if (right - left <= 0) return null
        val bottom = b.top
        val top = bottom - bandPx.toInt()
        if (top < 0) return null
        return Rect(left, top, right, bottom)
    }

    internal fun updateBounds(coords: LayoutCoordinates) {
        // ⚠ 不用 `coords.positionInWindow()` —— 该 API 在部分 Compose 版本上不存在。
        //   `localToWindow(Offset.Zero)` 是每个版本都有的等价写法。
        val pos = coords.localToWindow(Offset.Zero)
        bounds = Rect(
            pos.x.toInt(),
            pos.y.toInt(),
            (pos.x + coords.size.width).toInt(),
            (pos.y + coords.size.height).toInt(),
        )
    }

    internal fun ensureBitmap(): Bitmap =
        bitmap ?: Bitmap.createBitmap(SAMPLE_W, SAMPLE_H, Bitmap.Config.ARGB_8888).also { bitmap = it }

    internal fun release() {
        released = true
        // Do not recycle: a PixelCopy may still be writing into this bitmap on
        // another thread. It is only 32x8 px, so let the GC reclaim it.
        bitmap = null
    }
}

/** 采样位图尺寸：只需要出均值，足够小就能把分配和读像素成本压住。 */
private const val SAMPLE_W = 32
private const val SAMPLE_H = 8

/** 采样间隔。滚动时逐帧采样只会放大功耗与抖动，没有收益。 */
private const val SAMPLE_INTERVAL_MS = 120L

/** 单次 PixelCopy 的超时兜底 —— 拿不到回调也不能让循环卡死。 */
private const val SAMPLE_TIMEOUT_MS = 900L

/** 采样带高度：底栏上沿往上这一条，宽同底栏（再扣两侧 inset）。 */
private const val TONE_BAND_DP = 8f

/** 采样带左右各内缩，躲开屏幕圆角/边缘那圈不代表内容的像素。 */
private const val TONE_BAND_INSET_DP = 8f

/**
 * 切换判据的**中点**，单位是 CIELAB L*（感知亮度，0–100），不是线性光亮度 Y。
 *
 * ⚠ **不要用 Y 当中灰。** `meanLinearLuminance()` 返回的 Y 是物理量，
 * 它的感知等距点在 L* 上，而 **Y = 0.5 对应 L* ≈ 72** —— 也就是肉眼明显偏亮
 * 的灰。拿 Y 0.44 当门槛，等于把「该不该切暗式」判给了一片本来挺亮的内容，
 * 于是「不该切黑的都切了」（2026-09-25 用户反馈）。
 *
 * L* 才是按感知等距设计的标度：**L* = 50 才是字面意义的中灰**。
 * 想更宽松（更晚才切暗式）就往下调，比如 42；想更积极就往上。
 */
private const val TONE_FLIP_MID_LSTAR = 50.0f

/**
 * 围绕 [TONE_FLIP_MID_LSTAR] 的双侧迟滞宽度（L* 单位）。
 *
 * 现在是净采样（不含自己的玻璃），所以门槛就是 content 自己的感知亮度，
 * 不需要任何仿射换算：
 * - 当前亮式 ⇒ 要 `L* < 50 − Δ` 才转暗
 * - 当前暗式 ⇒ 要 `L* > 50 + Δ` 才转亮
 *
 * ⇒ 落在中间 2Δ 窗口里的内容**保持现状不变**，而背景静止时候选值是常数
 *  ⇒ **数学上不可能来回翻**（第一版的自反馈环路正是这么来的）。
 */
private const val TONE_FLIP_DEADBAND_LSTAR = 5.0f

/**
 * 连续几次"同侧越界"才真的切。
 *
 * 采样带紧贴内容底部，进度条、封面旋转这类**局部动画**偶尔会把均值推出边界一帧；
 * 要求连续命中可以过滤掉这种单帧噪声，代价只是 ~120ms 的响应延迟。
 */
private const val TONE_CONFIRM_COUNT = 2

/**
 * 两次翻转之间的最短驻留。
 *
 * 既覆盖 [ToneTransition] 本身的收敛时间（约 350ms），也挡掉快速滑过时的一连串翻转。
 * ⚠ 冷却期间**不要**清零确认计数，否则每次都从头数，滚动时会明显迟钝。
 */
private const val TONE_SWITCH_COOLDOWN_MS = 400L

/**
 * 过渡曲线。**刻意用 critically damped（dampingRatio = 1）**：这是在换材质调子，
 * 不是跟手手势 —— 过冲会被读成「闪一下」，不是在弹。
 */
private val ToneTransition = spring<Float>(dampingRatio = 1f, stiffness = 180f)

@Composable
internal fun rememberBottomBarToneState(
    systemDark: Boolean,
    /**
     * 底栏是否**被全屏播放页完全遮住**（`fullPlayerVisible`）。
     *
     * ⚠⚠⚠ 2026-10-06（用户报障）：「播放页的**专辑流光取色盖住底栏**，没有了液态效果」。
     *   根因：本采样器是**截屏底栏背后那条带**来判定明暗的，而播放页打开时那条带里
     *   只有**播放页的专辑流光像素** —— 它会被当成「底栏背后真实内容」，把 tone 翻到
     *   错误档位（深色流光 ⇒ 底栏整体翻成暗式 ⇒ 深色底栏叠在深色流光上，
     *   折射完全看不见 = 用户说的「没有了液态效果」）。
     *   而翻转本身有**去抖计数 + 400ms 冷却**（[TONE_SWITCH_COOLDOWN_MS]），
     *   关掉播放页后要好几拍才恢复 ⇒ 「有时候」看到。
     *   ⇒ 播放页期间**暂停采样、保持当前档位**（不清零计数、不 publish）；
     *     播放页一关立刻按真实内容重新采样。
     */
    paused: Boolean = false,
    /**
     * 深色模式下**锁死暗色底栏**：底栏不再根据背后内容自适应翻成浅色。
     *
     * ⚠ 只锁深色：仅当 `systemDark && lockDarkInDarkMode` 同时成立时，把 tone 钉死在
     *   `d = 1`（暗式）并**跳过采样**；浅色模式下自适应（暗背景→暗底栏）照常。
     *   用 `rememberUpdatedState` 而非 key：开关切换不该重启采样循环（会丢失 `decided`/
     *   冷却状态，反而制造一次额外翻转）。开关一开，下一轮循环即钉死暗式，无需重建协程。
     */
    lockDarkInDarkMode: Boolean = false,
): BottomBarToneState {
    val context = LocalContext.current
    val density = LocalDensity.current
    val state = remember { BottomBarToneState() }
    val bandPx = remember(density) { with(density) { TONE_BAND_DP.dp.toPx() } }
    val insetPx = remember(density) { with(density) { TONE_BAND_INSET_DP.dp.toPx() } }
    // ⚠ 用 `rememberUpdatedState` 而非 key：暂停开关不该重启整个采样循环
    //   （重启会丢掉 `decided`/冷却状态，反而制造一次额外翻转）。
    val pausedState = rememberUpdatedState(paused)
    // 同 `pausedState`：锁开关切换时只更新值，不重启循环。
    val lockDarkState = rememberUpdatedState(lockDarkInDarkMode)

    LaunchedEffect(state, systemDark) {
        val window = context.findActivity()?.window
        if (window == null) {
            state.publishTarget(if (systemDark) 1f else 0f)
            return@LaunchedEffect
        }
        // 首帧先落到系统主题，避免还没采样就翻一下。
        val fallback = if (systemDark) 1f else 0f
        state.publishTarget(fallback)

        var decided = Float.NaN
        var pending = Float.NaN
        var pendingCount = 0
        var lastSwitchAt = 0L

        while (true) {
            delay(SAMPLE_INTERVAL_MS)
            if (state.released) break
            // ⚠ 深色模式「保持暗色底栏」开关：只锁深色（浅色模式自适应照常）。
            //   锁定时把 tone 钉死在 d=1（暗式）并**跳过采样**，
            //   背后亮内容（如浅色封面）不再把下面的底栏翻成浅色。
            //   `decided` 记为 1f，开关关闭后下一轮自然回到下方采样逻辑；
            //   若背后确为亮内容会按正常去抖/冷却翻回浅式，不会卡死。
            if (lockDarkState.value && systemDark) {
                if (state.target != 1f) state.publishTarget(1f)
                decided = 1f
                pending = Float.NaN
                pendingCount = 0
                continue
            }
            // ⚠⚠ 播放页完全遮住底栏时**跳过采样**（理由见 `paused` 参数说明）：
            //   截到的只会是播放页的专辑流光，会把 tone 翻错，且冷却期让它迟迟不恢复。
            if (pausedState.value) continue
            val rect = state.sampleRect(bandPx, insetPx) ?: continue
            val lum = withTimeoutOrNull(SAMPLE_TIMEOUT_MS) {
                state.capture(window, rect)
            } ?: continue

            // Y 是物理亮度、不是感知亮度，必须先换到 L* 上再比阈值（见
            // [TONE_FLIP_MID_LSTAR] 的注释）。
            val lstar = yToLstar(lum)

            val current = if (decided.isNaN()) fallback else decided
            val want = if (current > 0.5f) {
                // 现在暗式 ⇒ 背后要明显亮于感知中灰才回亮式
                if (lstar > TONE_FLIP_MID_LSTAR + TONE_FLIP_DEADBAND_LSTAR) 0f else 1f
            } else {
                // 现在亮式 ⇒ 背后要明显暗于感知中灰才进暗式
                if (lstar < TONE_FLIP_MID_LSTAR - TONE_FLIP_DEADBAND_LSTAR) 1f else 0f
            }

            if (want == current) {
                pending = Float.NaN
                pendingCount = 0
                continue
            }
            if (pending != want) {
                pending = want
                pendingCount = 1
            } else {
                pendingCount++
            }
            if (pendingCount < TONE_CONFIRM_COUNT) continue

            val now = SystemClock.uptimeMillis()
            if (now - lastSwitchAt < TONE_SWITCH_COOLDOWN_MS) continue

            decided = want
            pendingCount = 0
            lastSwitchAt = now
            state.publishTarget(want)
        }
    }

    return state
}

/**
 * 用「插值后的配色」包住底部 chrome 这一棵子树。
 *
 * ⚠ **typography / shapes 必须从外层 MaterialTheme 原样传下来** —— `MaterialTheme()`
 *   是按参数重构整套 theme，不传就退回 Compose 默认值，兰亭 Pro 字体会丢。
 */
@Composable
internal fun BottomBarToneTheme(
    darkness: Float,
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = lerpColorScheme(LightColors, DarkColors, darkness),
        typography = MaterialTheme.typography,
        shapes = MaterialTheme.shapes,
        content = content,
    )
}

/**
 * 读取当前暗度；无论采样是否可用都返回一个稳定值。
 *
 * @param systemDark 系统主题（打底值）。用户口径：「默认根据系统，然后同时真实采样背后像素」。
 */
@Composable
internal fun BottomBarToneState.darkness(systemDark: Boolean): Float {
    val fallback = if (systemDark) 1f else 0f
    val targetValue = if (target.isNaN()) fallback else target
    return animateFloatAsState(
        targetValue = targetValue,
        animationSpec = ToneTransition,
        label = "melox-bottom-bar-tone",
    ).value
}

/** 对窗口做一次 PixelCopy，返回**线性光**平均亮度；失败返回 null。 */
private suspend fun BottomBarToneState.capture(window: Window, rect: Rect): Float? =
    suspendCancellableCoroutine { cont ->
        if (released) {
            cont.resume(null)
            return@suspendCancellableCoroutine
        }
        val bmp = ensureBitmap()
        // ⚠ 用 suspendCancellableCoroutine，不是 suspendCoroutine：外层套了
        //   withTimeoutOrNull，超时会取消；若回调再来 resume 一次会直接抛
        //   IllegalStateException（"Already resumed"）。
        // ⚠ 窗口还没有 backing surface 时（启动首帧 / 窗口重建 / 刚从后台回来），
        //   PixelCopy.request 会**同步抛** IllegalArgumentException
        //   ("Window doesn't have a backing surface!")。withTimeoutOrNull 只接
        //   CancellationException，兜不住它 ⇒ 直接打崩 App。采样只是「锦上添花」，
        //   任何失败都必须退化成 null，让上层 `?: continue` 跳过这一拍。
        // ⚠⚠ 抓 `Throwable`，不是 `IllegalArgumentException`。
        //   已知会同步抛的：
        //   - IllegalArgumentException "Window doesn't have a backing surface!"（文档写明）
        //   - IllegalStateException：部分 ROM（尤其 vivo/OPPO 的多窗口/自由窗口分支）在
        //     surface 已销毁时抛的不是 IAE。
        //   - SecurityException：个别 OEM 对 `PixelCopy` 加了调用方校验。
        //   采样只是「锦上添花」，任何失败都必须退化成 null 让上层 `?: continue` 跳过这一拍。
        val launched = try {
            PixelCopy.request(
                window,
                rect,
                bmp,
                { result ->
                    if (cont.isActive && !bmp.isRecycled) {
                        cont.resume(
                            if (result == PixelCopy.SUCCESS) {
                                // ⚠ 竞态：`release()` 可能在 request 之后、回调之前回收了这张图
                                //   （`DisposableEffect.onDispose` → `toneState.release()`）。
                                //   对已回收的 Bitmap 调 `getPixels()` 会抛
                                //   IllegalStateException("Can't call getPixels() on a recycled bitmap")。
                                if (bmp.isRecycled || released) null else meanLinearLuminance(bmp)
                            } else {
                                null
                            }
                        )
                    }
                },
                Handler(Looper.getMainLooper()),
            )
            true
        } catch (t: Throwable) {
            // CancellationException 必须放行，否则会破坏协程取消语义。
            if (t is kotlinx.coroutines.CancellationException) throw t
            false
        }
        if (launched) {
            cont.invokeOnCancellation { /* 位图由 GC 回收，这里不动 */ }
        } else {
            cont.resume(null)
        }
    }

/**
 * sRGB 像素 → 线性光 → 相对亮度，取均值。
 *
 * ⚠ **必须在线性光空间算**。直接平均 sRGB 编码值会把暗部压缩，判出来的中点偏低。
 */
private fun meanLinearLuminance(bmp: Bitmap): Float {
    val n = SAMPLE_W * SAMPLE_H
    val px = IntArray(n)
    bmp.getPixels(px, 0, SAMPLE_W, 0, 0, SAMPLE_W, SAMPLE_H)
    var sum = 0f
    for (i in 0 until n) {
        val c = px[i]
        val r = ((c shr 16) and 0xFF) / 255f
        val g = ((c shr 8) and 0xFF) / 255f
        val b = (c and 0xFF) / 255f
        sum += 0.2126f * srgbToLinear(r) + 0.7152f * srgbToLinear(g) + 0.0722f * srgbToLinear(b)
    }
    return sum / n
}

private fun srgbToLinear(v: Float): Float =
    if (v <= 0.04045f) v / 12.92f else ((v + 0.055f) / 1.055f).pow(2.4f)

/**
 * 线性光相对亮度 Y → CIELAB L*（感知亮度，D65 白点，0–100）。
 *
 * 关键性质：**L* 是感知等距的** —— 相邻两点看起来的明暗差距一样大。Y 不是，
 * 它把绝大部分量程压在 0–0.2 的暗部，所以 Y 空间的「中点」在人眼看来是一片
 * 明显偏亮的灰（Y 0.5 ↔ L* 72）。阈值必须放在 L* 上才有直观意义。
 *
 * 分段点在 `216/24389 ≈ 0.008856`，两段在那里严格连续（L* = 8.0），
 * 不会出现跳变把死区顶歪。
 */
private fun yToLstar(y: Float): Float {
    val yn = y.coerceIn(0f, 1f)
    val eps = 216f / 24389f
    return if (yn > eps) {
        116f * yn.pow(1f / 3f) - 16f
    } else {
        // 与上式在 eps 处相切：116 * (841/108) * Y
        116f * 841f / 108f * yn
    }
}

/**
 * 在**两套真实 Material 配色**之间插值 —— 这就是「明暗两种模式的底栏互相切换」的落地方式。
 *
 * `d = 0` 就是货真价实渲染出来的「亮模式底栏」，`d = 1` 就是「暗模式底栏」，
 * 中间态则是整棵子树里所有读 `MaterialTheme.colorScheme.*` 的组件**自动**一起过渡。
 * ⚠ 因此**不要**再在底栏里手写字形色之类的近似端点 —— scheme 一动它们就跟着动了。
 *
 * ### 为什么用 `copy` 而不是构造新的
 * `ColorScheme` 字段近 50 个且版本间会增减。用 `light.copy(字段 = ...)` 的写法：
 * - 只写关心的字段，**未列出的字段自动取 light 的值**（走到 d=1 时它们不插值，也不炸）；
 * - 字段名写错是**编译期错误**，不会静默失效。
 *
 * ### 成本
 * `Color` 是 inline value class（一个 ULong），所以这里每帧只多分配 **1 个 ColorScheme 对象**
 * 加几十次 lerp —— 全也不是 hot loop 的重点。
 */
internal fun lerpColorScheme(light: ColorScheme, dark: ColorScheme, d: Float): ColorScheme =
    light.copy(
        primary = lerp(light.primary, dark.primary, d),
        onPrimary = lerp(light.onPrimary, dark.onPrimary, d),
        primaryContainer = lerp(light.primaryContainer, dark.primaryContainer, d),
        onPrimaryContainer = lerp(light.onPrimaryContainer, dark.onPrimaryContainer, d),
        secondary = lerp(light.secondary, dark.secondary, d),
        onSecondary = lerp(light.onSecondary, dark.onSecondary, d),
        secondaryContainer = lerp(light.secondaryContainer, dark.secondaryContainer, d),
        onSecondaryContainer = lerp(light.onSecondaryContainer, dark.onSecondaryContainer, d),
        tertiary = lerp(light.tertiary, dark.tertiary, d),
        onTertiary = lerp(light.onTertiary, dark.onTertiary, d),
        tertiaryContainer = lerp(light.tertiaryContainer, dark.tertiaryContainer, d),
        onTertiaryContainer = lerp(light.onTertiaryContainer, dark.onTertiaryContainer, d),
        background = lerp(light.background, dark.background, d),
        onBackground = lerp(light.onBackground, dark.onBackground, d),
        surface = lerp(light.surface, dark.surface, d),
        onSurface = lerp(light.onSurface, dark.onSurface, d),
        surfaceVariant = lerp(light.surfaceVariant, dark.surfaceVariant, d),
        onSurfaceVariant = lerp(light.onSurfaceVariant, dark.onSurfaceVariant, d),
        surfaceTint = lerp(light.surfaceTint, dark.surfaceTint, d),
        inverseSurface = lerp(light.inverseSurface, dark.inverseSurface, d),
        inverseOnSurface = lerp(light.inverseOnSurface, dark.inverseOnSurface, d),
        inversePrimary = lerp(light.inversePrimary, dark.inversePrimary, d),
        error = lerp(light.error, dark.error, d),
        onError = lerp(light.onError, dark.onError, d),
        errorContainer = lerp(light.errorContainer, dark.errorContainer, d),
        onErrorContainer = lerp(light.onErrorContainer, dark.onErrorContainer, d),
        outline = lerp(light.outline, dark.outline, d),
        outlineVariant = lerp(light.outlineVariant, dark.outlineVariant, d),
        scrim = lerp(light.scrim, dark.scrim, d),
    )

private fun Context.findActivity(): Activity? {
    var ctx: Context? = this
    while (ctx != null) {
        when (ctx) {
            is Activity -> return ctx
            is ContextWrapper -> ctx = ctx.baseContext
            else -> return null
        }
    }
    return null
}
