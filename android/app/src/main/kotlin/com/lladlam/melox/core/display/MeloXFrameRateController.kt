package com.lladlam.melox.core.display

import android.app.Activity
import android.app.Application
import android.content.res.Configuration
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Surface
import android.view.View
import android.view.ViewTreeObserver
import java.util.WeakHashMap

/**
 * **可变刷新率（VRR / 自适应刷新率）控制器** —— 交互 / 动画时拉高，静止时交回节能档。
 *
 * ## 为什么需要（2026-10-05 真机取证）
 * iQOO 15（V2505A）屏幕当前渲染 **90Hz**（`dumpsys display` → `renderFrameRate 90.0`，
 * 峰值 144 只在游戏 / 视频这类"高帧率内容"时才启用），而系统按**内容类型分档**：
 * `frameRateCategoryRate {normal=60, high=90}`。**App 不显式声明就落进 `normal` = 60Hz**
 * ⇒ 帧间隔 `16.67ms = 1.5 × 11.11ms` ⇒ **3:2 judder**（相邻帧显示时长 11.1 / 22.2 交替）。
 * 这是「动画偏黏、比 iOS 差一截」的根因；上游 lladlam/MeloX 同样未声明（不是 fork 引入）。
 * 实测声明后：帧间隔 **16.6ms(60fps) → 8.30ms(120fps)**，屏 `renderFrameRate 90 → 120`，
 * 8.30ms 是 120Hz 的整数倍 ⇒ judder 消失。
 *
 * ## 第 0 层：ARR 策略（Android 15+）—— **实测决定：关闭**
 * 官方默认开启 ARR，并称"不建议关闭（会更耗电）"。但真机 A/B 证明它会**压住我们的请求**：
 * 开启后恒为 60fps（平台按内容投票：我们的 decorView 投 90、Compose 节点默认投 NORMAL 60），
 * 关闭后请求才生效（V2505A 实测 60fps → 120fps、屏 90 → 120Hz）。
 * ⇒ 本项目取「**流畅优先**」：显式 `setFrameRatePowerSavingsBalanced(false)`。
 * 触摸 boost 保持开启（只在触摸瞬间提升，不压制常态帧率）。
 * 平台其余内置策略（fling 随速度降频、启动 / 转场 boost、位置尺寸动画自动提升）不受影响。
 *
 * ## 第 1 层：窗口级兜底（本机 `hasArrSupport = false` ⇒ MRR 面板，平台自动策略受限）
 * | 版本 | 入口 | 说明 |
 * |---|---|---|
 * | API 35+（Android 15） | `View.setRequestedFrameRate` | view 级投票，官方新入口 |
 * | API 30+（Android 11） | `Surface.setFrameRate` | 窗口 surface；⚠ Surface 只能**反射**取（见下） |
 * | API 26–29 | `LayoutParams.preferredRefreshRate` | 唯一手段，vivo / OPPO 系常只认它 |
 *
 * ⚠ 三条**共存**是有意的：官方说「Surface 用 `setFrameRate()` 时系统会忽略
 * `preferredRefreshRate`」⇒ 只要 `setFrameRate` 成功，`preferredRefreshRate` 就是无害冗余；
 * 一旦反射失败，`preferredRefreshRate` 就是唯一还能生效的入口。
 * ⚠ `View.getRootSurface()` 是 **@hide**（Kotlin 写 `decorView.rootSurface` 直接
 * `Unresolved reference`）⇒ 只能反射 `ViewRootImpl.mSurface`。
 *
 * ## 第 2 层：按内容分级（[isBusy]，两级门槛）
 * | 输入 | 门槛 | 覆盖 |
 * |---|---|---|
 * | 最近一次**触摸** | [TOUCH_HOLD_MS] | 滑动、点按、fling（对齐官方 touch boost 语义） |
 * | 最近一次**新绘制** | [DRAW_HOLD_MS] | 纯动画、页面转场、播放器 morph、进度条 / 歌词 |
 *
 * 两者都超时才降频。⚠ v1 用「有绘制即忙」单一门槛 ⇒ **播放中界面持续出帧 ⇒ 永远 120Hz**；
 * 现在播放中静置约 2s 会回落到节能档。
 *
 * ## 为什么没有逐 composable 投票（`Modifier.preferredFrameRate`）
 * 方向正确（只在绘制 / 动画时投票，静止不投票 ⇒ 自动降频），但 MeloX 的候选容器
 * （`SharedTransitionLayout`、迷你栏、全屏播放页）都是**长期存在且播放中持续出帧**的节点，
 * 挂上就变成**常驻高刷**，与节能目标直接冲突。⇒ 现阶段只在窗口级表达；
 * 细粒度投票需要逐个转场点接入「仅在动画在飞时挂载」，后续再做。
 *
 * ## ⚠ 状态不变时不下发
 * 官方明确「**避免过于频繁地调用 `setFrameRate()`**」——每次调用都可能触发刷新率切换并丢帧。
 * [applyHigh] 因此有 `high == highApplied` 的早退。
 *
 * 参考：
 * - 自适应刷新率 <https://developer.android.com/develop/ui/views/animations/adaptive-refresh-rate>
 * - 帧率 API <https://developer.android.com/media/optimize/performance/frame-rate>
 * - AOSP ARR <https://source.android.com/docs/core/graphics/arr>
 */
internal class MeloXFrameRateController(private val activity: Activity) {

    private val handler = Handler(Looper.getMainLooper())
    private var installed = false

    /** 已经处在目标档位时不再下发（见类注释「⚠ 状态不变时不下发」）。 */
    private var highApplied = false

    /** 面板最大刷新率；[ACTIVE_FPS] 会被它截断。 */
    private var maxRefreshRate = DEFAULT_FPS

    private var lastTouchAt = 0L
    private var lastDrawAt = 0L

    private val drawListener = ViewTreeObserver.OnDrawListener {
        // ⚠⚠ 2026-10-06 关键修正：**绘制只能「续期」触摸开启的活跃窗口，不能单独触发高刷**。
        //   原因：全屏播放页在播放中会**持续出帧**（进度条 / 歌词 / 封面）⇒ 若无条件刷新
        //   `lastDrawAt`，[isBusy] 恒真 ⇒ 静置 4s 后仍 100% 跑 120fps（实测）。
        //   ⇒ 只有「距上次触摸 < [TOUCH_WINDOW_MS]」时才把绘制算作活跃，
        //     这样无手势的持续动画最多在触摸后 `TOUCH_WINDOW_MS + DRAW_HOLD_MS` 秒内保持高刷。
        val now = SystemClock.uptimeMillis()
        if (now - lastTouchAt < TOUCH_WINDOW_MS) lastDrawAt = now
    }

    private val ticker = object : Runnable {
        override fun run() {
            if (!installed) return
            applyHigh(isBusy(SystemClock.uptimeMillis()))
            handler.postDelayed(this, POLL_INTERVAL_MS)
        }
    }

    /** Activity `onResume` 时调用（由 [MeloXFrameRateHost] 统一转发，不需要各 Activity 手写）。 */
    fun install() {
        if (installed) return
        installed = true
        val now = SystemClock.uptimeMillis()
        lastTouchAt = now
        lastDrawAt = now
        maxRefreshRate = resolveMaxRefreshRate()
        applyArrPolicy()
        runCatching {
            activity.window.decorView.viewTreeObserver.addOnDrawListener(drawListener)
        }
        // ⚠⚠ 触摸在这里**自己观察**，不依赖 Activity 转发 `onUserInteraction`：
        //   全 App 有 8 个 Activity，逐个写 `override fun onUserInteraction` 既啰嗦又容易漏；
        //   而装饰 View 没有上层拦截时 `dispatchTouchEvent` 必然先经过它的 OnTouchListener。
        //   ⚠ **必须返回 false**（不消费）—— 返回 true 会把整棵 UI 树的手势全吞掉。
        //   ⚠ 与 [markActive] 二选一不冲突：两者都只是刷新 `lastTouchAt`。
        runCatching {
            activity.window.decorView.setOnTouchListener { _, _ ->
                markActive()
                false
            }
        }
        handler.post(ticker)
        applyHigh(true)
    }

    /** Activity `onPause` 时调用：**必须**撤掉请求，否则后台也在耗高刷。 */
    fun uninstall() {
        if (!installed) return
        installed = false
        handler.removeCallbacks(ticker)
        runCatching {
            val observer = activity.window.decorView.viewTreeObserver
            if (observer.isAlive) observer.removeOnDrawListener(drawListener)
        }
        runCatching { activity.window.decorView.setOnTouchListener(null) }
        applyHigh(false)
    }

    /** 任意用户交互（触摸 / 按键 / 滚球）。由 `Activity.onUserInteraction` 转发。 */
    fun markActive() {
        lastTouchAt = SystemClock.uptimeMillis()
        if (!highApplied) applyHigh(true)
    }

    // ── 内部 ────────────────────────────────────────────────────────────────

    private fun isBusy(now: Long): Boolean =
        (now - lastTouchAt) < TOUCH_HOLD_MS || (now - lastDrawAt) < DRAW_HOLD_MS

    /**
     * ⚠⚠⚠ **2026-10-06 真机 A/B 结论：ARR 开关「一个都别碰」。**
     *
     * 同一台 V2505A（屏支持 144 / 90 / 60）上的三次实测：
     * | 版本 | 对 ARR 开关的做法 | 屏 `renderFrameRate` |
     * |---|---|---|
     * | v1 | **完全不碰**（保持平台默认） | **120.00001** ✅ |
     * | v2 | 显式 `setFrameRatePowerSavingsBalanced(true)` | 60（但该次在 V2304A 上，不可比 ⚠） |
     * | v3 | 显式 `false`（关 ARR） | **90.0** ❌ 反而降档 |
     *
     * ⇒ 显式设定**任一值都会改变平台行为**：`true` 把调度权交回平台（按内容投票压回 60），
     * `false`（关省电平衡）让系统退回普通 MRR 模式选择（落到 90）。
     * 只有「不碰」时，`requestedFrameRate` + `Surface.setFrameRate` +
     * `preferredRefreshRate` 三条请求才能把屏顶到 **120**。
     *
     * ⇒ 本类**故意不再调用**这两个 API；保留本注释以防后人"顺手打开官方推荐的默认值"。
     * ⚠ 注意：v3 那次交互期只抓到 4 帧（tap 未真正触发展开），帧率结论不完整；
     *   但 `renderFrameRate` 是屏侧读数，与帧数无关，可作准。
     */
    private fun applyArrPolicy() {
        // 故意为空：见上方结论，碰任何一个 ARR 开关都会降档。
    }

    private fun applyHigh(high: Boolean) {
        if (high == highApplied) return
        highApplied = high
        val decor = activity.window.decorView
        if (high) {
            // 折叠 / 外屏切换会换面板，进高刷档时重新读一次最大值。
            maxRefreshRate = resolveMaxRefreshRate()
            val target = minOf(ACTIVE_FPS, maxRefreshRate)
            if (target <= 0f) return
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM) {
                runCatching { decor.requestedFrameRate = target }
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                // ⚠ `View.getRootSurface()` 是 **@hide 内部 API**，公开 SDK 里没有
                //   （Kotlin 直接写 `decorView.rootSurface` 会 `Unresolved reference`），
                //   因此只能反射 `ViewRootImpl.mSurface`。实测这条反射**能取到 Surface**
                //   且 `setFrameRate` 生效（真机：16.6ms/60fps → 8.30ms/120fps）。
                windowSurface()?.let {
                    runCatching {
                        it.setFrameRate(target, Surface.FRAME_RATE_COMPATIBILITY_DEFAULT)
                    }
                }
            }
            // 无条件同步 LayoutParams：surface 拿不到时它是唯一还能生效的入口。
            runCatching {
                val lp = activity.window.attributes
                if (lp.preferredRefreshRate != target) {
                    lp.preferredRefreshRate = target
                    activity.window.attributes = lp
                }
            }
        } else {
            // 交回平台默认：**用 DEFAULT 类别而不是 0f** —— 语义是「本 View 没有帧率数据
            // （不投票）」；`setFrameRate(0)` 才是官方文档里「清除回默认值」的写法。
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM) {
                runCatching {
                    decor.requestedFrameRate = View.REQUESTED_FRAME_RATE_CATEGORY_DEFAULT
                }
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                windowSurface()?.let { s ->
                    runCatching { s.setFrameRate(0f, Surface.FRAME_RATE_COMPATIBILITY_DEFAULT) }
                }
            }
            runCatching {
                val lp = activity.window.attributes
                if (lp.preferredRefreshRate != 0f) {
                    lp.preferredRefreshRate = 0f
                    activity.window.attributes = lp
                }
            }
        }
    }

    /**
     * `getRootSurface()` 不可用时的兜底：反射取 `ViewRootImpl.mSurface`。
     * 任何一步失败都返回 null，由调用方整层跳过（`preferredRefreshRate` 仍会兜住）。
     */
    private fun windowSurface(): Surface? {
        val root = activity.window.decorView.parent ?: return null
        return runCatching {
            val field = root.javaClass.getDeclaredField("mSurface")
            field.isAccessible = true
            field.get(root) as? Surface
        }.getOrNull()
    }

    private fun resolveMaxRefreshRate(): Float = runCatching {
        // ⚠ Android 15+ 的 `windowManager.defaultDisplay` 已废弃（返回的可能是过时/默认模式的
        //   信息），API 30+ 用 `Activity.getDisplay()`；更老的系统才回退到它。
        val display = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            activity.display
        } else {
            @Suppress("DEPRECATION")
            activity.windowManager.defaultDisplay
        }
        val modes = display?.supportedModes
        if (modes.isNullOrEmpty()) {
            display?.refreshRate ?: DEFAULT_FPS
        } else {
            modes.maxOf { it.refreshRate }
        }
    }.getOrDefault(DEFAULT_FPS)

    companion object {
        /**
         * 交互 / 动画期的目标刷新率。用户要求「120 或最高」⇒ 这里取 120，
         * 并被 [resolveMaxRefreshRate] 截断（面板只有 90 就给 90）。
         * 想完全吃满可改成 `Float.MAX_VALUE`（会取到面板最大值，功耗也最高）。
         */
        const val ACTIVE_FPS = 120f

        /**
         * 触摸后的高刷保持时长（ms）。对齐官方 touch boost 语义
         * （`ACTION_DOWN` 提升、抬手后仍保持一段时间）。
         */
        const val TOUCH_HOLD_MS = 800L

        /**
         * 「有新绘制」的高刷宽限（ms）。覆盖**无触摸的纯动画**：页面转场、播放器 morph、
         * 进度条 / 歌词滚动等。⚠ 仅在触摸窗口内续期（见 [TOUCH_WINDOW_MS]）。
         */
        const val DRAW_HOLD_MS = 2_000L

        /**
         * 触摸开启的**活跃窗口**（ms）。窗口内新绘制可以不断续期 [DRAW_HOLD_MS]；
         * 窗口过期后绘制不再续期 ⇒ 无手势的持续动画（播放中的进度条 / 歌词）
         * 会在 `TOUCH_WINDOW_MS + DRAW_HOLD_MS` 后回落到节能档。
         * ⇒ 一次交互的最长高刷驻留 ≈ 5s（3s + 2s）。
         */
        const val TOUCH_WINDOW_MS = 3_000L

        /** 轮询间隔（ms）。取 250 ⇒ 状态切换最多延迟 ~0.25s。 */
        const val POLL_INTERVAL_MS = 250L

        private const val DEFAULT_FPS = 60f
    }
}

/**
 * **把 [MeloXFrameRateController] 挂到全 App 的每个 Activity 上**（2026-10-06）。
 *
 * ## 为什么用 lifecycle callbacks 而不是各 Activity 自己写
 * 本 App 有 8 个 Activity（`MainActivity` + 设置详情 / 歌单详情 / 账号 / 歌曲百科 / 分享 /
 * 一起听 / ProviderServices）。第一版只在 `MainActivity` 里挂了 controller
 * ⇒ **其余 7 个页面的滚动与展开动画仍然是 60fps**（真机核对过）。
 * 逐个 Activity 写 `onResume/onPause/onUserInteraction` 既啰嗦又必然漏 ——
 * 这里用 `Application.registerActivityLifecycleCallbacks` 统一接管，**各 Activity 零改动**。
 *
 * ⚠ 因此 `MainActivity` 里**不要**再自己 `MeloXFrameRateController(this)`：
 *   两个实例同时 install 会对同一个窗口重复下发帧率请求（`installed` 标志各算各的）。
 *
 * ⚠ 用 `WeakHashMap` 持有 controller：Activity 销毁后自动回收，不阻止其被 GC。
 *   `onActivityDestroyed` 再显式 `remove` 一次，避免 key 滞留。
 */
internal object MeloXFrameRateHost : Application.ActivityLifecycleCallbacks {

    private val controllers = WeakHashMap<Activity, MeloXFrameRateController>()

    fun install(application: Application) {
        application.registerActivityLifecycleCallbacks(this)
    }

    override fun onActivityResumed(activity: Activity) {
        controllers.getOrPut(activity) { MeloXFrameRateController(activity) }.install()
    }

    override fun onActivityPaused(activity: Activity) {
        controllers[activity]?.uninstall()
    }

    override fun onActivityDestroyed(activity: Activity) {
        controllers.remove(activity)?.uninstall()
    }

    /**
     * ⚠ 旋转升帧（2026-10-09）：旋转是**传感器触发、没有触摸事件**，而高刷窗口
     *   由触摸开启（TOUCH_HOLD_MS=800ms + 3s 内绘制续期）。播放页静置后旋转，
     *   高刷早已过期 ⇒ 旋转动画（系统旋转 + App orientTransition）全程低帧率 =
     *   用户报的「横竖屏切换不丝滑/卡顿」。方向变化时手动 markActive()，
     *   让旋转动画期间保持高刷。
     *   由 Activity.onConfigurationChanged 调用（MainActivity override）。
     */
    fun onConfigurationChanged(activity: Activity) {
        controllers[activity]?.markActive()
    }

    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
    override fun onActivityStarted(activity: Activity) = Unit
    override fun onActivityStopped(activity: Activity) = Unit
    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
}
