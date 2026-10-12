package com.lladlam.melox

import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.hardware.SensorManager
import android.os.Bundle
import android.view.KeyEvent
import android.view.OrientationEventListener
import android.view.ViewTreeObserver
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.lifecycleScope
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import com.lladlam.melox.core.network.parseNeteaseListenTogetherInvitation
import com.lladlam.melox.core.remoteconfig.MeloXRemoteConfigRuntime
import com.lladlam.melox.core.remoteconfig.MeloXRemoteConfigConsent
import com.lladlam.melox.platform.MeloXVolumeKeyBridge
import com.lladlam.melox.platform.lyricon.MeloXLyriconBridge
import com.lladlam.melox.platform.xiaomi.HyperOsFocusBridge
import com.lladlam.melox.ui.player.MeloXListenTogetherInviteActivity
import com.lladlam.melox.ui.MeloXApp
import com.lladlam.melox.ui.settings.MeloXSettingsPreferences
import com.lladlam.melox.ui.theme.MeloXTheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private var openNowPlayingRequest by mutableIntStateOf(0)
    private var clipboardLinkRequest by mutableStateOf<String?>(null)
    private var playbackConnectionEnabled by mutableStateOf(false)
    private var lastClipboardText: String? = null
    private val firstDraw = CompletableDeferred<Unit>()
    private var clipboardScanJob: Job? = null
    private var handledRemoteConfigForegroundSession = -1L

    // ⚠ 传感器提前升帧（2026-10-09）：onConfigurationChanged 触发时旋转动画已开始，
    //   VRR 升帧赶不上动画开头几帧（录屏实测 frame 58-59 间隔 21.7/11.7ms，
    //   后续才归正到 7-8ms）= 用户报的「一顿一顿」。用 OrientationEventListener
    //   监听传感器角度变化（UI 延迟 ~50ms），比配置变更早 50-100ms 触发
    //   markActive ⇒ 旋转动画全程 120Hz。
    // ⚠ 不能在类字段初始化中创建（会引用 applicationContext，而 ContextWrapper
    //   此时尚未完全构造 → NPE 崩溃 178 版重现）。改在 onCreate 里建。
    private var orientationSensor: OrientationEventListener? = null

    // ⚠ 可变刷新率**不再**在这里挂载：已由 `MeloXApplication` 注册的 `MeloXFrameRateHost`
    //   统一接管全部 8 个 Activity（见该类注释）。此处若再建一个 controller，
    //   两个实例会同时向同一窗口重复下发帧率请求。

    // ⚠ 旋转升帧（2026-10-09）：旋转是传感器触发、无触摸事件，高刷窗口可能早已过期。
    //   方向变化时手动 markActive()，让旋转动画全程高刷。否则「旋转飞入」会掉帧卡顿。
    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        com.lladlam.melox.core.display.MeloXFrameRateHost.onConfigurationChanged(this)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 传感器监听器在此处创建（不能在类字段初始化时建 —— ContextWrapper
        // 尚未完全构造，applicationContext 返回 null ⇒ 178 版重现的 NPE 崩溃）。
        orientationSensor = object : OrientationEventListener(
            applicationContext, SensorManager.SENSOR_DELAY_UI
        ) {
            private var lastAngle = ORIENTATION_UNKNOWN
            override fun onOrientationChanged(angle: Int) {
                if (angle == ORIENTATION_UNKNOWN) {
                    lastAngle = ORIENTATION_UNKNOWN
                    return
                }
                val prev = lastAngle
                lastAngle = angle
                // 角度突变 >45°：手机正被旋转。UI 延迟事件可能拆成两次小突变，
                // 阈值 45 既过滤手抖又能捕捉 90°/180° 的真实旋转。
                if (prev != ORIENTATION_UNKNOWN && Math.abs(angle - prev) > 45) {
                    com.lladlam.melox.core.display.MeloXFrameRateHost.onConfigurationChanged(
                        this@MainActivity
                    )
                }
            }
        }
        // ⚠ 不在此处禁系统旋转动画（2026-10-09）：JUMPCUT 确实去掉了「系统旋转 + App
        //   orientTransition」双动画叠加，但代价是整个「旋转飞入」观感也一起没了。
        //   卡顿的真凶不是动画叠加，而是旋转时无触摸事件 ⇒ 高刷窗口过期（见
        //   MeloXFrameRateHost.onActivityConfigurationChanged 的 markActive）。
        val coldStart = savedInstanceState == null
        val returnFromBackground = coldStart &&
            System.currentTimeMillis() - MeloXAppVisibility.lastBackgroundedAtMs(this) <= RETURN_FROM_BACKGROUND_WINDOW_MS
        enableEdgeToEdge()
        consumePlaybackIntent(intent)
        MeloXSettingsPreferences.initializeCritical(this)
        completeAfterFirstDraw()

        setContent {
            MeloXTheme {
                var splashVisible by remember { mutableStateOf(coldStart && !returnFromBackground) }
                val pageAlpha by animateFloatAsState(
                    targetValue = if (splashVisible) 0f else 1f,
                    animationSpec = tween(350),
                    label = "melox-start-page",
                )
                val splashAlpha by animateFloatAsState(
                    targetValue = if (splashVisible) 1f else 0f,
                    animationSpec = tween(350),
                    label = "melox-start-icon",
                )
                LaunchedEffect(coldStart, returnFromBackground) {
                    if (coldStart && !returnFromBackground) delay(1_000L)
                    splashVisible = false
                }
                Box(Modifier.fillMaxSize()) {
                    Box(Modifier.fillMaxSize().alpha(pageAlpha)) {
                        MeloXApp(
                            openNowPlayingRequest = openNowPlayingRequest,
                            clipboardLinkRequest = clipboardLinkRequest,
                            onClipboardLinkConsumed = { clipboardLinkRequest = null },
                            playbackConnectionEnabled = playbackConnectionEnabled,
                        )
                    }
                    if (splashAlpha > 0f) {
                        Image(
                            painter = painterResource(R.drawable.ic_launcher_foreground),
                            contentDescription = null,
                            modifier = Modifier
                                .align(Alignment.Center)
                                .size(104.dp)
                                .clip(RoundedCornerShape(24.dp))
                                .alpha(splashAlpha),
                        )
                    }
                }
            }
        }

        lifecycleScope.launch {
            firstDraw.await()
            delay(100L)
            playbackConnectionEnabled = true
        }

        lifecycleScope.launch {
            firstDraw.await()
            delay(250L)
            MeloXSettingsPreferences.initialize(this@MainActivity)
        }

        // Lyricon registers a cross-process provider. Starting it before
        // setContent delayed the first frame and left a white window on cold
        // launch. Listen Together is intentionally not started here: its UI or
        // an incoming invitation creates the coordinator only when needed.
        lifecycleScope.launch {
            firstDraw.await()
            delay(500L)
            MeloXLyriconBridge.start(applicationContext)
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        consumePlaybackIntent(intent)
    }

    /**
     * 实体音量键**只观察、不消费**（2026-10-07）。
     *
     * vivo 上硬件音量键不发 `VOLUME_CHANGED_ACTION`（本仓库 2026-10-06 实测），
     * 播放页只能靠 `MeloXPlayerUi` 里那条 150ms 轮询得知音量变了 ⇒ 按键后条最多滞后 150ms
     * 才起步，观感就是"顿一下才滑"。这里把按键信号同步推给 UI，把延迟压到 ≈1 帧。
     *
     * ⚠ 一律 `return super.onKeyDown(...)`（**绝不 return true**）：
     *   音量键的真正处理在 system_server 的 `interceptKeyBeforeQueueing`，早于事件投递给 App
     *   ⇒ 我们在这里读到的已是**调完之后**的音量；一旦消费掉事件，反而会打断系统的音量 HUD。
     *   而且这件事**必须在音量调到边界后也有信号**（越界按键在系统侧音量值不变），
     *   只能靠按键事件本身来识别 —— 这也是"过冲拉长"能做到的前提。
     */
    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        //⚠ `repeatCount` 必须透传：长按时系统会自动重复发 onKeyDown（repeatCount 递增），
        //   而物理上只有**一次**手指按下。UI 侧靠它区分"新手势"与"同一手势的重复"，
        //   否则长按到极限会反复累加蓄力 ⇒ 条二次拉伸（iOS 不会，见 Bridge 注释）。
        when (keyCode) {
            KeyEvent.KEYCODE_VOLUME_UP ->
                MeloXVolumeKeyBridge.onVolumeKeyDown(MeloXVolumeKeyBridge.DIRECTION_UP, event.repeatCount)
            KeyEvent.KEYCODE_VOLUME_DOWN ->
                MeloXVolumeKeyBridge.onVolumeKeyDown(MeloXVolumeKeyBridge.DIRECTION_DOWN, event.repeatCount)
        }
        return super.onKeyDown(keyCode, event)
    }

    /**
     * 松开的信号专供"过冲拉长"回弹（iOS 的橡皮筋是抬手即回，不靠超时猜）。
     * 同样只观察、不消费。
     */
    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        when (keyCode) {
            KeyEvent.KEYCODE_VOLUME_UP -> MeloXVolumeKeyBridge.onVolumeKeyUp(MeloXVolumeKeyBridge.DIRECTION_UP)
            KeyEvent.KEYCODE_VOLUME_DOWN -> MeloXVolumeKeyBridge.onVolumeKeyUp(MeloXVolumeKeyBridge.DIRECTION_DOWN)
        }
        return super.onKeyUp(keyCode, event)
    }

    override fun onResume() {
        super.onResume()
        // 传感器可用性检查后启用。无重力/方向传感器设备（极少数车机/模拟器）
        // enable 会抛 IllegalStateException，需先 try/catch 一下。不用 isDetectable()
        // 是因为 Kotlin 对匿名 object 表达式上这个 Java is 前缀方法解析不稳（unresolved）。
        orientationSensor?.let { runCatching { it.enable() } }
        val foregroundSession = MeloXAppVisibility.foregroundSessionId
        if (foregroundSession != handledRemoteConfigForegroundSession) {
            handledRemoteConfigForegroundSession = foregroundSession
            lifecycleScope.launch {
                firstDraw.await()
                delay(750L)
                if (MeloXRemoteConfigConsent.enabled(applicationContext)) {
                    MeloXRemoteConfigRuntime.initializeAndRefresh(
                        applicationContext,
                        BuildConfig.VERSION_CODE,
                        force = true,
                    )
                }
            }
        }
        // Shizuku is optional. On HyperOS 3, request permission only when its
        // service is actually running; permission itself acts as the user's opt-in
        // to the short XMSF compatibility pulse used by some restricted ROM builds.
        lifecycleScope.launch {
            firstDraw.await()
            delay(500L)
            HyperOsFocusBridge.prepareShizukuCompatibility(this@MainActivity)
        }

        clipboardScanJob?.cancel()
        clipboardScanJob = lifecycleScope.launch {
            firstDraw.await()
            delay(100L)
            if (!com.lladlam.melox.ui.settings.MeloXSettingsRuntime.clipboardLinksEnabled) return@launch
            val manager = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val text = manager.primaryClip?.getItemAt(0)?.text?.toString()?.trim().orEmpty()
            if (text.isNotBlank() && text != lastClipboardText) {
                lastClipboardText = text
                val together = parseNeteaseListenTogetherInvitation(text)
                if (together != null) {
                    MeloXListenTogetherInviteActivity.launch(
                        this@MainActivity,
                        together.roomId,
                        together.inviterId,
                    )
                    return@launch
                }
                clipboardLinkRequest = text
            }
        }
    }

    override fun onPause() {
        // 后台不再监听旋转传感器：传感器事件本身有能耗，后台监听只耗电不提效。
        orientationSensor?.disable()
        super.onPause()
    }

    private fun completeAfterFirstDraw() {
        val decorView = window.decorView
        val listener = object : ViewTreeObserver.OnPreDrawListener {
            override fun onPreDraw(): Boolean {
                if (decorView.viewTreeObserver.isAlive) {
                    decorView.viewTreeObserver.removeOnPreDrawListener(this)
                }
                decorView.post { firstDraw.complete(Unit) }
                return true
            }
        }
        decorView.viewTreeObserver.addOnPreDrawListener(listener)
    }

    private fun consumePlaybackIntent(intent: Intent?) {
        if (intent?.action == ACTION_OPEN_NOW_PLAYING) {
            openNowPlayingRequest += 1
        }
    }

    companion object {
        const val ACTION_OPEN_NOW_PLAYING =
            "com.lladlam.melox.action.OPEN_NOW_PLAYING"
        /** Cold starts within this window of the last backgrounding are treated as returns, not fresh launches. */
        private const val RETURN_FROM_BACKGROUND_WINDOW_MS = 15L * 60L * 1000L
    }
}
