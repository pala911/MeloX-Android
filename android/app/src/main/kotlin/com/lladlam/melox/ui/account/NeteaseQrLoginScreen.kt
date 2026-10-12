package com.lladlam.melox.ui.account

import android.Manifest
import android.annotation.SuppressLint
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.media.MediaScannerConnection
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import com.lladlam.melox.R
import com.lladlam.melox.core.account.NeteaseSessionStore
import com.lladlam.melox.core.music.provider.PlaybackAccountSlot
import com.lladlam.melox.core.network.NeteaseQrDiag
import com.lladlam.melox.ui.glass.MeloXGlassIconButton
import com.lladlam.melox.ui.glass.MeloXSymbol
import com.lladlam.melox.ui.glass.MeloXSymbolIcon
import com.lladlam.melox.ui.glass.MeloXShapes
import com.lladlam.melox.ui.glass.MeloXTypography
import com.lladlam.melox.ui.glass.MeloXIosTopBar
import com.lladlam.melox.ui.legal.MeloXLegalLinks
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject

/** 网易云品牌红，与登录页按钮一致。 */
private val NETEASE_ACCENT = Color(0xFFE60026)

/**
 * 网易云扫码登录独立页：**二维码本地原生渲染，登录流程交给真实网页**。
 *
 * 用户看到的部分全是原生 Compose：二维码用 ZXing 以 1024px 源图绘制，`fillMaxWidth()`
 * 铺满整个框，右下角「刷新 / 保存到相册」，与 QQ 音乐登录页完全同构。
 *
 * 背后挂一个 **1dp 见方的透明 WebView**，它加载真实登录页，页面自己申请 unikey、
 * 自己按 1s 轮询 `client/login`，并把每拍状态写进 `document.title`；App 只读这些
 * 上报，用 ZXing 重绘同一张码（同一个 codekey，文本完全一致），分辨率更高。
 *
 * 状态一律**页面推、App 收**，不反向 `evaluateJavascript` 取状态 —— 详见
 * [NeteaseQrBridge] 的 KDoc，那里记着上一版两条读取通道同时静默失效的完整证据。
 *
 * 这不是 workaround，而是当前唯一能过网易云渠道风控的通道 —— 详见 [NeteaseQrBridge]
 * 的 KDoc（8821 / `anquanhuanjingfengxian` 的完整证据链）。
 */
@Composable
fun NeteaseQrLoginScreen(
    session: NeteaseSessionStore,
    onDismiss: () -> Unit,
    onLoggedIn: () -> Unit,
    targetSlot: PlaybackAccountSlot = PlaybackAccountSlot.Main,
) {
    val activityContext = LocalContext.current
    val context = activityContext.applicationContext
    val scope = rememberCoroutineScope()
    NeteaseQrDiag.install(context)

    val loadingText = stringResource(R.string.account_netease_qr_preparing)
    val waitingText = stringResource(R.string.account_netease_qr_waiting)
    val verifyingText = stringResource(R.string.account_verifying_login)
    val successText = stringResource(R.string.account_login_success)
    val netEaseName = stringResource(R.string.account_netease)

    var qrSession by remember { mutableStateOf<NeteaseQrSession?>(null) }
    var qrBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var stateText by remember { mutableStateOf(loadingText) }
    var error by remember { mutableStateOf<String?>(null) }
    var verifying by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }
    var actionMessage by remember { mutableStateOf<String?>(null) }
    var actionFailed by remember { mutableStateOf(false) }
    var refreshToken by remember { mutableStateOf(0) }
    /** 网页兜底：把隐藏 WebView 放出来让用户手动登录（风控 / 取不到码时的自救路径）。 */
    var webFallback by remember { mutableStateOf(false) }
    /**
     * UA 档位：0 = 桌面 UA（默认），1 = WebView 真实 UA（兜底）。
     *
     * **顺序是实测定死的，不能反。** 真机日志（2026-10-05）：
     * 用 WebView 真实 UA（含 `Android` / `Mobile`）时，`music.163.com/#/login`
     * 会被**分流到移动版 `y.music.163.com/m/login`**，在那里
     * `/api/login/qrcode/unikey` 返回的东西里**没有 unikey**（`stage=unikeyFail log=noKey`）
     * —— 移动版走的是另一套接口路径。换桌面 UA 后页面留在 `music.163.com/#/login`，
     * 立刻拿到 key。
     *
     * 所以桌面 UA 不是「为了绕风控随便伪造」，而是**让服务端返回桌面版页面**的必要手段；
     * 真 UA 反倒是失败后才值得一试的兜底（万一某些网络下移动版接口可用）。
     */
    var uaStage by remember { mutableStateOf(0) }
    /** 最近一次收到网页上报的时间戳。心跳看门狗据此判断页面状态机是否还活着。 */
    val lastPushAt = remember { java.util.concurrent.atomic.AtomicLong(0L) }
    /**
     * 看门狗已经重载过几次。**必须设上限**：页面被踢到风控页时重载也救不回来，
     * 而每次重载都会再申请一个 unikey —— 撞上登录频控的情况下，这等于每 30s
     * 给风控多递一条异常记录，把「24 小时」的冷却越拖越长。
     */
    var watchdogTrips by remember { mutableIntStateOf(0) }
    /** 上次收到风控码（88xx）的时刻。冷静期内停止一切自动重试。 */
    val lastRiskAt = remember { java.util.concurrent.atomic.AtomicLong(0L) }
    /** 上次真正重载页面的时刻，用于给「刷新」节流（连点会连出多个 unikey）。 */
    val lastRefreshAt = remember { java.util.concurrent.atomic.AtomicLong(0L) }

    /** 是否处在风控冷静期内：此时只让用户手动决定，不再自动刷新/重载。 */
    fun inRiskCooldown(): Boolean =
        System.currentTimeMillis() - lastRiskAt.get() < RISK_COOLDOWN_MS

    /** 授权通道：1dp 见方的透明 WebView。 */
    var webView by remember { mutableStateOf<WebView?>(null) }
    var webReady by remember { mutableStateOf(false) }
    /**
     * 页面推上来的状态。网页自己申请 unikey、自己轮询，每拍把结果写进
     * `document.title`（带时间戳），由 `onReceivedTitle` 落到这里。
     *
     * 主流程**只读**这个流，不再主动 `evaluateJavascript` 取状态 ——
     * 上一版正是靠主动读，而两条通道在轮询阶段同时静默失效，才出现「扫码没反应」。
     */
    val snapshot = remember { kotlinx.coroutines.flow.MutableStateFlow<QrSnapshot?>(null) }

    BackHandler(onBack = onDismiss)

    fun saveCurrentQr() {
        val content = qrSession?.qrContent ?: return
        saving = true
        actionMessage = null
        scope.launch {
            runCatchingQr {
                withContext(Dispatchers.IO) { saveNeteaseQrToGallery(context, content) }
            }.onSuccess {
                actionFailed = false
                actionMessage = activityContext.getString(R.string.account_netease_qr_saved)
            }.onFailure { failure ->
                actionFailed = true
                actionMessage = failure.message
                    ?: activityContext.getString(R.string.account_netease_qr_save_failed)
            }
            saving = false
        }
    }

    /**
     * 重新取一张码。
     *
     * ⚠ **必须重载页面**：注入脚本靠 `window.__meloxQrBoot` 幂等，终态还会
     * `clearInterval`，不重载就永远不会再申请 unikey —— 只把协程循环重启一遍，
     * 结果就是干等 45s 然后报「准备失败」。
     */
    fun refresh() {
        // 节流：连点刷新 = 连着申请 unikey = 连着给风控递异常记录（实测已吃过一次
        // 「操作繁忙，请 24 小时后重试」）。正常用户不会在几秒内反复换码。
        val now = System.currentTimeMillis()
        val delta = now - lastRefreshAt.get()
        if (delta < REFRESH_THROTTLE_MS) {
            NeteaseQrDiag.log("refreshThrottled", "deltaMs=$delta")
            return
        }
        lastRefreshAt.set(now)
        snapshot.value = null
        lastPushAt.set(0L)
        webFallback = false
        webReady = false
        webView?.reload()
        refreshToken += 1
    }

    /** 桌面 UA 拿不到码时的兜底：换回 WebView 真实 UA 再试一次（`null` = 用系统默认）。 */
    fun escalateUa() {
        uaStage = 1
        // `null` 让 WebView 回到自己的默认 UA（含 Android / Mobile → 移动版页面）。
        webView?.settings?.userAgentString = null
        NeteaseQrDiag.log("uaEscalate", "stage=1 realUa")
        refresh()
    }

    /** 网页兜底：用户手动在页面里登录完，点这里回收 cookie。 */
    fun finishWebLogin() {
        verifying = true
        error = null
        scope.launch {
            val cookie = collectNeteaseCookieHeader()
            NeteaseQrDiag.log(
                "webFallbackDone",
                "cookieKeys=${NeteaseQrDiag.cookieKeys(cookie)} slot=$targetSlot",
            )
            val verified = session.acceptAuthenticatedCookie(
                candidate = cookie,
                persist = targetSlot == PlaybackAccountSlot.Main,
            )
            verifying = false
            verified.fold(
                onSuccess = {
                    if (targetSlot == PlaybackAccountSlot.Playback) {
                        NeteaseSessionStore.writePlaybackCookie(context, cookie)
                    }
                    CookieManager.getInstance().flush()
                    // 凭证已经存进加密 store，WebView 里那份明文必须清掉。
                    clearNeteaseWebViewCookies()
                    stateText = successText
                    onLoggedIn()
                },
                onFailure = { failure ->
                    NeteaseQrDiag.log("webFallbackFailed", "message=${failure.message}")
                    error = failure.message.toUserFacingError(activityContext)
                },
            )
        }
    }

    val storagePermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) {
            saveCurrentQr()
        } else {
            actionFailed = true
            actionMessage = activityContext.getString(R.string.account_storage_denied)
        }
    }

    // 主流程：解析网页登录流程的 unikey → 本地渲染 → 继续解析网页轮询状态码 → 授权成功即离场。
    LaunchedEffect(refreshToken, webReady) {
        NeteaseQrDiag.log("enter", "webReady=$webReady token=$refreshToken")
        if (!webReady) return@LaunchedEffect

        while (true) {
            qrSession = null
            qrBitmap = null
            error = null
            actionMessage = null
            stateText = loadingText

            if (webView == null) return@LaunchedEffect

            // ① 等网页自己申请到 unikey。网页每拍主动推，这里只等，不主动读。
            var key = ""
            var tick = 0
            // 桌面 UA 是主档，给足耐心；真 UA 只是兜底，失败了就该尽快给用户出路。
            val unikeyTimeoutTicks =
                if (uaStage == 0) UNIKEY_TIMEOUT_TICKS else UA_PROBE_TICKS
            while (key.isBlank()) {
                tick++
                val parsed = withTimeoutOrNull(POLL_INTERVAL_MS) {
                    snapshot.first { !it?.unikey.isNullOrBlank() }
                }?.unikey.orEmpty()
                if (tick <= 3 || tick % 5 == 0) {
                    NeteaseQrDiag.log(
                        "waitKey",
                        "tick=$tick latest=${snapshot.value?.probe ?: "<null>"}",
                    )
                }
                if (parsed.isNotBlank()) {
                    key = parsed
                    break
                }
                if (tick > unikeyTimeoutTicks) {
                    NeteaseQrDiag.log(
                        "unikeyTimeout",
                        "tick=$tick uaStage=$uaStage latest=${snapshot.value?.probe ?: "<null>"}",
                    )
                    // 桌面 UA 拿不到码：换真 UA（移动版）再试一次，不急着判死。
                    if (uaStage == 0) {
                        // refresh() 会改 refreshToken，本协程随之重启，直接退出即可。
                        escalateUa()
                        return@LaunchedEffect
                    }
                    error = activityContext.getString(R.string.account_netease_qr_prepare_failed)
                    return@LaunchedEffect
                }
            }
            // ② 用网页那个 key 本地重绘。内容就是网页 QRCode.js 渲染的那串文本，
            //    所以画出来的是同一张码，只是分辨率更高、能存相册。
            val content = NeteaseQrBridge.QR_CONTENT_PREFIX + key
            val created = NeteaseQrSession(key, content)
            qrSession = created
            qrBitmap = runCatchingQr { createNeteaseQrBitmap(content, QR_PIXELS) }
                .onFailure { failure -> error = failure.message ?: loadingText }
                .getOrNull()
            stateText = waitingText
            NeteaseQrDiag.log("qrParsed", "key=${key.take(8)}…")

            // ③ 跟着网页推上来的状态码走。网页每拍写 title，这里每秒看一次最新值。
            var lastCode = -1
            // 兜底上限：万一服务端不返回 800（过期码）而是继续回 801，我们也不能
            // 无限轮询下去 —— 一张码撑不了这么久，继续问只是纯消耗风控额度。
            val pollDeadline = System.currentTimeMillis() + QR_MAX_POLL_MS
            while (true) {
                delay(POLL_INTERVAL_MS)
                if (qrSession?.key != key) break // 已被刷新/取消
                if (System.currentTimeMillis() > pollDeadline) {
                    NeteaseQrDiag.log("pollDeadline", "key=${key.take(8)}…")
                    error = activityContext.getString(R.string.account_netease_qr_expired)
                    return@LaunchedEffect
                }

                val snap = snapshot.value ?: continue
                // 心跳看门狗：页面被导航去了风控页 / 下载页时状态机就停了，
                // 而 App 侧毫无感知 —— 只能靠「多久没收到上报」发现，然后重载。
                val silentMs = System.currentTimeMillis() - lastPushAt.get()
                if (silentMs > STALE_TIMEOUT_MS) {
                    NeteaseQrDiag.log(
                        "heartbeatLost",
                        "silentMs=$silentMs lastCode=$lastCode trip=${watchdogTrips + 1}",
                    )
                    if (watchdogTrips >= MAX_WATCHDOG_TRIPS || inRiskCooldown()) {
                        // ① 重载过两次还是一片静默 —— 再重载只是继续申请 unikey。
                        // ② 或者刚收到过风控码：对方已经说「操作繁忙」了，还每 30s
                        // 去申请一个新 key，等于把「等几分钟」亲手拖成「等 24 小时」。
                        // 两种情况都必须停手报错，出路交给常驻的「使用网页登录」
                        //（那条通道不受扫码冷却影响）；用户主动点刷新不受此限。
                        NeteaseQrDiag.log(
                            "watchdogExhausted",
                            "trips=$watchdogTrips riskCooldown=${inRiskCooldown()}",
                        )
                        error = activityContext.getString(R.string.account_netease_qr_prepare_failed)
                        return@LaunchedEffect
                    }
                    watchdogTrips += 1
                    refresh()
                    return@LaunchedEffect
                }
                // 页面重新导航会重置 window，于是换一张新码：回到外层重画。
                if (snap.unikey.isNotBlank() && snap.unikey != key) {
                    NeteaseQrDiag.log("keyRotated", "old=${key.take(8)}… new=${snap.unikey.take(8)}…")
                    break
                }
                if (snap.code == lastCode) continue
                lastCode = snap.code
                NeteaseQrDiag.log("poll", "code=${snap.code} ${snap.probe}")
                when (snap.code) {
                    0, 801 -> stateText = waitingText
                    802 -> stateText = activityContext.getString(
                        R.string.account_qr_scanned_confirm,
                        netEaseName,
                    )
                    800 -> {
                        // ⚠ 码过期后必须**彻底停下**，不能 break 回外层重画：
                        // 外层等到的还是同一个 key，于是会无限循环地轮询一张死码
                        // （每 2.5s 一次，一小时 1400+ 次，而用户完全无感知）。
                        // 这是把账号推进「24 小时冷却」最可能的一条路径 ——
                        // 比连点刷新严重得多，因为它不需要用户做任何事。
                        NeteaseQrDiag.log("expired", "key=${key.take(8)}…")
                        error = activityContext.getString(R.string.account_netease_qr_expired)
                        return@LaunchedEffect
                    }
                    803 -> {
                        stateText = verifyingText
                        verifying = true
                        val cookie = collectNeteaseCookieHeader()
                        NeteaseQrDiag.log(
                            "authorized",
                            "cookieKeys=${NeteaseQrDiag.cookieKeys(cookie)} slot=$targetSlot",
                        )
                        val verified = session.acceptAuthenticatedCookie(
                            candidate = cookie,
                            persist = targetSlot == PlaybackAccountSlot.Main,
                        )
                        verifying = false
                        // 授权成功与校验失败都要终止整个轮询：前者离场，后者留给用户手动刷新。
                        val finished = verified.fold(
                            onSuccess = {
                                if (targetSlot == PlaybackAccountSlot.Playback) {
                                    NeteaseSessionStore.writePlaybackCookie(context, cookie)
                                }
                                CookieManager.getInstance().flush()
                                // 凭证已存进加密 store，WebView 里那份明文 cookie（含 MUSIC_U）
                                // 就不再有存在理由 —— 留着等于多一份可被 root / 备份取走的凭证。
                                clearNeteaseWebViewCookies()
                                stateText = successText
                                onLoggedIn()
                                true
                            },
                            onFailure = { failure ->
                                NeteaseQrDiag.log("verifyFailed", "message=${failure.message}")
                                error = failure.message.toUserFacingError(activityContext)
                                true
                            },
                        )
                        if (finished) return@LaunchedEffect
                    }
                    else -> {
                        // 80x 之外一律是风控码（8821 等）。这里不是请求失败而是**被拒绝授权**，
                        // 继续轮询只会每秒读到同一个码，所以直接停。
                        NeteaseQrDiag.log(
                            "pollError",
                            "code=${snap.code} stage=${snap.stage} err=${snap.err.take(160)}",
                        )
                        lastRiskAt.set(System.currentTimeMillis())
                        error = activityContext.getString(R.string.account_netease_qr_risk_controlled)
                        return@LaunchedEffect
                    }
                }
            }
        }
    }

    LaunchedEffect(actionMessage) {
        if (actionMessage == null) return@LaunchedEffect
        delay(4_000)
        actionMessage = null
    }

    DisposableEffect(Unit) {
        onDispose {
            webView?.let { view ->
                view.stopLoading()
                (view.parent as? ViewGroup)?.removeView(view)
                view.destroy()
            }
            webView = null
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
                .statusBarsPadding(),
        ) {
            MeloXIosTopBar(
                title = stringResource(R.string.account_netease_qr_title),
                contentPadding = PaddingValues(horizontal = 10.dp),
                // 34sp 的大标题在这页太抢戏（标题本身也偏长会挤到返回键），
                // 降到 22sp：与返回键同一视觉层级，二维码才是这页的主角。
                titleStyle = MeloXTypography.title2,
                navigation = {
                    // 与全站返回键统一：液态玻璃圆钮（艺人详情 / 置顶页同款），
                    // 不再自己排 44dp 靶 + 28dp 图标。
                    MeloXGlassIconButton(
                        symbol = MeloXSymbol.ChevronLeft,
                        onClick = onDismiss,
                        contentDescription = stringResource(R.string.player_back),
                    )
                },
            )

            // 与 QQ 音乐登录页同一套骨架：外层 Box 吃掉剩余高度，内容列可滚动并居中，
            // 这样「二维码 + 按钮 + 文案」在任意屏高下都不会被挤出去。
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 28.dp, vertical = 16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    val bitmap = qrBitmap
                    if (bitmap == null) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .sizeIn(maxWidth = 320.dp)
                                .aspectRatio(1f)
                                .clip(MeloXShapes.largeCard)
                                .background(MaterialTheme.colorScheme.surfaceVariant),
                            contentAlignment = Alignment.Center,
                        ) {
                            CircularProgressIndicator(color = NETEASE_ACCENT)
                        }
                    } else {
                        // 二维码本体正方形，宽度撑满可用宽度 ⇒ 视觉上「铺满框」。
                        // 形状用项目的连续曲率圆角（MeloXShapes.largeCard）而不是正圆弧：
                        // 全站卡片都是这套几何，正圆弧会显得是从别处搬来的。
                        // 白底是扫码必需的（对比度），但它原来是硬贴上去的 ——
                        // 加一道 7% 的描边，让它在浅色背景和深色模式下都有个边界。
                        Image(
                            bitmap = bitmap.asImageBitmap(),
                            contentDescription = stringResource(R.string.account_netease_qr_hint2),
                            modifier = Modifier
                                .fillMaxWidth()
                                .sizeIn(maxWidth = 320.dp)
                                .aspectRatio(1f)
                                .clip(MeloXShapes.largeCard)
                                .background(Color.White)
                                .border(
                                    width = 1.dp,
                                    color = Color.Black.copy(alpha = 0.07f),
                                    shape = MeloXShapes.largeCard,
                                )
                                .padding(12.dp),
                        )
                    }

                Spacer(Modifier.size(16.dp))
                val actionsEnabled = qrSession != null && qrBitmap != null
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    QrActionButton(
                        onClick = { refresh() },
                        icon = MeloXSymbol.Refresh,
                        label = stringResource(R.string.account_refresh_qr),
                        tint = NETEASE_ACCENT,
                        enabled = actionsEnabled,
                        modifier = Modifier.weight(1f),
                    )
                    QrActionButton(
                        onClick = {
                            if (
                                Build.VERSION.SDK_INT <= Build.VERSION_CODES.P &&
                                ContextCompat.checkSelfPermission(
                                    context,
                                    Manifest.permission.WRITE_EXTERNAL_STORAGE,
                                ) != PackageManager.PERMISSION_GRANTED
                            ) {
                                storagePermissionLauncher.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                            } else {
                                saveCurrentQr()
                            }
                        },
                        icon = MeloXSymbol.Download,
                        label = if (saving) {
                            stringResource(R.string.account_saving)
                        } else {
                            stringResource(R.string.account_save_gallery)
                        },
                        tint = NETEASE_ACCENT,
                        enabled = actionsEnabled && !saving,
                        modifier = Modifier.weight(1f),
                    )
                }

                Spacer(Modifier.size(20.dp))
                // ⚠ 高度必须恒定。外层列是「居中 + 可滚动」，内容总高一变，
                // 整块（含二维码与按钮）就会重新居中 ⇒ 表现为「一出现文字画面就抖」。
                // 所以：行数全部封顶、消息槽占固定高度、错误态不再插入新按钮。
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = QR_STATUS_BLOCK_HEIGHT),
                    contentAlignment = Alignment.TopCenter,
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            text = stateText,
                            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                            color = MaterialTheme.colorScheme.onBackground,
                            style = MeloXTypography.headline,
                            textAlign = TextAlign.Center,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Spacer(Modifier.size(8.dp))
                        Text(
                            text = stringResource(R.string.account_netease_qr_hint),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MeloXTypography.subheadline,
                            textAlign = TextAlign.Center,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Spacer(Modifier.size(8.dp))
                        Text(
                            text = stringResource(R.string.account_credentials_local),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MeloXTypography.caption,
                            textAlign = TextAlign.Center,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Spacer(Modifier.size(10.dp))
                        // 「操作结果 / 错误」共用同一个槽位：有没有内容都占同样高度，
                        // 于是二维码和按钮不会因为一条提示出现而上下跳。
                        Box(
                            modifier = Modifier.height(QR_MESSAGE_SLOT_HEIGHT),
                            contentAlignment = Alignment.TopCenter,
                        ) {
                            val message = error ?: actionMessage
                            if (message != null) {
                                Text(
                                    text = message,
                                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                                    color = when {
                                        error != null -> MaterialTheme.colorScheme.error
                                        actionFailed -> MaterialTheme.colorScheme.error
                                        else -> Color(0xFF168A4A)
                                    },
                                    style = MeloXTypography.caption,
                                    textAlign = TextAlign.Center,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                    }
                }
            }
            }

            MeloXLegalLinks(
                modifier = Modifier.padding(vertical = 6.dp),
                tint = NETEASE_ACCENT,
            )
        }

        if (verifying) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.18f)),
                contentAlignment = Alignment.Center,
            ) {
                CircularProgressIndicator(color = NETEASE_ACCENT)
            }
        }

        // 授权通道：平时是 1dp 见方的透明 WebView，只是让真实 Chrome 引擎在同源环境里
        // 替我们走登录流程；[webFallback] 打开时同一实例放大成全屏，交还给用户手动操作。
        // ⚠ 必须始终留在视图树里 —— detach 后 WebView 会被暂停，fetch 不再发出；
        //    也绝不能放进 if/else 分支里（切换会导致 AndroidView 重建，页面状态全丢）。
        NeteaseQrWebEngine(
            visible = webFallback,
            modifier = if (webFallback) {
                Modifier
                    .fillMaxSize()
                    .statusBarsPadding()
            } else {
                Modifier
                    .width(1.dp)
                    .height(1.dp)
            },
            onReady = { webReady = true },
            onCreated = { webView = it },
            onReported = {
                snapshot.value = it
                lastPushAt.set(System.currentTimeMillis())
            },
        )

        // 网页兜底的操作条：用户在页面里登录完，点这里回收 cookie。
        if (webFallback) {
            Column(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.background)
                    .padding(horizontal = 20.dp, vertical = 14.dp)
                    .navigationBarsPadding(),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    text = stringResource(R.string.account_netease_qr_hint),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 13.sp,
                    lineHeight = 20.sp,
                    textAlign = TextAlign.Center,
                )
                Spacer(Modifier.size(12.dp))
                QrActionButton(
                    onClick = { finishWebLogin() },
                    icon = MeloXSymbol.CheckCircle,
                    label = stringResource(R.string.account_web_login_done),
                    tint = NETEASE_ACCENT,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.size(10.dp))
                Text(
                    text = stringResource(R.string.action_cancel),
                    modifier = Modifier.clickable(onClick = { refresh() }),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                )
            }
        }
    }
}

/**
 * 授权浏览器：平时 1dp 见方且全透明，[visible] 打开时放大成全屏交给用户手动登录。
 *
 * 尺寸 1dp + `alpha = 0f`：AndroidView 必须处在已附加状态才会继续跑 JS 与网络，
 * 但完全不占视觉空间。若设成 0dp 或直接不挂载，`evaluateJavascript` 不会执行。
 *
 * ⚠ 可见性切换必须走 [update] 改 `view.alpha` —— `alpha` 是 View 的属性，
 * Compose 的 `Modifier.alpha` 盖不住它被设成 0 的那一层。
 */
@Composable
private fun NeteaseQrWebEngine(
    visible: Boolean,
    modifier: Modifier,
    onReady: () -> Unit,
    onCreated: (WebView) -> Unit,
    onReported: (QrSnapshot?) -> Unit,
) {
    AndroidView(
        modifier = modifier,
        factory = { context ->
            @SuppressLint("SetJavaScriptEnabled")
            WebView(context).apply {
                layoutParams = ViewGroup.LayoutParams(1, 1)
                alpha = 0f
                // 隐藏态不参与焦点：页面脚本 focus 输入框时，WebView 一旦拿到焦点
                // 就会把输入法弹出来，把整页 UI 顶得乱跳（真机截图实测）。
                // 网页兜底全屏时由 [update] 恢复焦点能力，用户仍可正常输入。
                isFocusable = false
                isFocusableInTouchMode = false
                onCreated(this)
                with(CookieManager.getInstance()) {
                    setAcceptCookie(true)
                    // 登录流程只用到 music.163.com 自己的 cookie。放开第三方等于把
                    // 页面里那些统计脚本的追踪面一起放进 App，没必要。
                    setAcceptThirdPartyCookies(this@apply, false)
                }
                settings.apply {
                    javaScriptEnabled = true
                    domStorageEnabled = true
                    databaseEnabled = true
                    // 默认用桌面 UA：服务端据此返回桌面版页面，而我们依赖的
                    // 扫码接口只在桌面版路径上可用。失败后由主流程降级回真 UA 再试。
                    userAgentString = DESKTOP_UA
                }
                webViewClient = object : WebViewClient() {
                    override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
                        super.onPageStarted(view, url, favicon)
                        NeteaseQrDiag.log("pageStarted", "url=$url")
                        // 越早越好：脚本内部会等 `load`，装早了也不会打在半死的 document 上。
                        if (view != null && isNeteaseOrigin(url)) {
                            evaluate(view, NeteaseQrBridge.BOOTSTRAP_JS)
                        }
                    }

                    override fun onPageFinished(view: WebView?, url: String?) {
                        NeteaseQrDiag.log("pageFinished", "url=$url view=${view != null}")
                        // 脚本幂等：页面 `#/login → /` 会导航两次，第二次 window 已重置，
                        // 重跑一次只是换一张新码（App 侧按 unikey 变化重画）。
                        if (view != null && isNeteaseOrigin(url)) {
                            evaluate(view, NeteaseQrBridge.BOOTSTRAP_JS)
                        }
                        super.onPageFinished(view, url)
                        if (isNeteaseOrigin(url)) onReady()
                    }
                }
                /**
                 * 主回传通道：`onReceivedTitle` 属于 WebChromeClient，不是 WebViewClient。
                 *
                 * 脚本每拍改写 `document.title`（内容带时间戳，必然变化），
                 * 所以这里每拍都会被回调 —— 不依赖任何 JS 回调，也就不会挂起。
                 */
                webChromeClient = object : android.webkit.WebChromeClient() {
                    override fun onReceivedTitle(view: WebView?, title: String?) {
                        super.onReceivedTitle(view, title)
                        val raw = title ?: return
                        if (!raw.startsWith(NeteaseQrBridge.REPORT_PREFIX)) return
                        val json = runCatching {
                            JSONObject(raw.removePrefix(NeteaseQrBridge.REPORT_PREFIX))
                        }.getOrNull() ?: return
                        onReported(
                            QrSnapshot(
                                unikey = json.optString("unikey"),
                                code = json.optInt("code"),
                                stage = json.optString("stage"),
                                err = json.optString("err"),
                                probe = "seen=${json.optInt("seen")} " +
                                    "stage=${json.optString("stage")} " +
                                    "href=${json.optString("href")} " +
                                    "log=${json.optString("log")}",
                            ),
                        )
                    }
                }
                loadUrl(NeteaseQrBridge.PAGE_URL)
            }
        },
        update = { view ->
            // `alpha` 是 View 属性，只能在这里改；Compose 的 Modifier.alpha 盖不住。
            view.alpha = if (visible) 1f else 0f
            // 全屏兜底时恢复焦点能力，否则用户点输入框弹不出键盘。
            // factory 里默认关掉，是为了隐藏态不让页面脚本把键盘顶出来。
            if (visible && !view.isFocusableInTouchMode) {
                view.isFocusable = true
                view.isFocusableInTouchMode = true
                view.requestFocus()
            }
        },
    )
}

/** 只在网易云自己的 origin 上注入脚本，避免把 Hook 装到跳转出去的中间页。 */
private fun isNeteaseOrigin(url: String?): Boolean =
    url?.startsWith("https://music.163.com") == true

/** 二维码源图边长（px）。1024 足够铺满全屏还留足余量。 */
private const val QR_PIXELS = 1024

/** 轮询间隔，与官方网页版一致（1s）。 */
private const val POLL_INTERVAL_MS = 1_000L

/** 超过这么多拍还没等到网页推 unikey 就放弃（45s）。 */
private const val UNIKEY_TIMEOUT_TICKS = 45

/**
 * 真 UA 兜底档的耐心上限（20s）。桌面档才是主档，用 [UNIKEY_TIMEOUT_TICKS]。
 *
 * 真 UA 会被引到移动版 `y.music.163.com`，接口路径与桌面版不同，成功率本来就不高，
 * 不值得让用户干等。
 */
private const val UA_PROBE_TICKS = 20

/**
 * 心跳看门狗阈值：超过这么久没收到网页上报，就判定页面状态机已死（被导航去了
 * 风控页 / 下载页，或 JS 被冻结），重载页面。
 */
private const val STALE_TIMEOUT_MS = 30_000L

/**
 * 看门狗最多重载几次。**2 次**而不是无限：重载必然伴生一次 unikey 申请，
 * 在已经被频控的情况下，无限重载就是每 30s 多一条异常记录、把冷却拖得更久。
 */
private const val MAX_WATCHDOG_TRIPS = 2

/**
 * 一张码最多轮询多久（6 分钟）。
 *
 * 网易云的二维码有效期约 3~5 分钟。正常情况下服务端的 800（已过期）会先到，
 * 这条是兜底：万一它不返回 800 而是继续回 801，我们也不能无限问下去 ——
 * 用户把页面开着不管，一小时就是一千多次无效请求。
 */
private const val QR_MAX_POLL_MS = 6 * 60 * 1_000L

/**
 * 收到风控码后的本地冷静期（15 分钟）。
 *
 * 这段时间内**不再做任何自动重试**（看门狗不再自动重载页面）。
 * 对方已经明确说「操作繁忙」了，还每 30s 去申请一个新 unikey，等于把
 * 「等几分钟」的冷却亲手拖成「等 24 小时」。用户主动点刷新不受此限。
 */
private const val RISK_COOLDOWN_MS = 15 * 60 * 1_000L

/**
 * 两次「刷新」之间的最小间隔（8s）。
 *
 * 每刷新一次就申请一个新 unikey，连点就是连着向登录接口打异常流量。
 */
private const val REFRESH_THROTTLE_MS = 8_000L

/**
 * 状态文案区的最小高度（状态行 22 + 8 + 说明两行 40 + 8 + 隐私行 16 + 10 + 消息槽 32）。
 *
 * 这一块高度恒定是「画面不抖」的前提：外层列居中 + 可滚动，内容总高一变整块就会
 * 重新居中，二维码和按钮跟着上下跳。所以行数全部封顶，高度按满行预留。
 */
private val QR_STATUS_BLOCK_HEIGHT = 136.dp

/** 「操作结果 / 错误」槽位高度（caption 两行）。有内容没内容都占这么多。 */
private val QR_MESSAGE_SLOT_HEIGHT = 32.dp

/**
 * 桌面 UA（默认档）。
 *
 * 目的**不是**冒充 Mac —— 而是让服务端返回**桌面版** `music.163.com` 页面：
 * 移动版 `y.music.163.com` 的扫码接口路径与桌面版不同，拿不到 unikey（实测记录见
 * [uaStage] 的 KDoc）。UA 里只要不含 `Android` / `Mobile` 就能达到目的。
 *
 * 版本号**必须跟着 Chrome 稳定版走**：报一个落后几十个版本号的浏览器（曾经的
 * `Chrome/124`）本身就是一条很硬的伪造信号 —— 真实用户不会停在两年前的版本上。
 * 平台选 Windows 桌面而非 Mac，只是因为它的占比最高、最「普通」；
 * 真正起作用的只有一件事：**UA 里不含 `Android` / `Mobile`**。
 *
 * 代价仍然是真实的：UA 与设备 TLS / 指纹不成对，本身就是风控会看的点。
 */
private const val DESKTOP_UA =
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) " +
        "AppleWebKit/537.36 (KHTML, like Gecko) " +
        "Chrome/154.0.8037.98 Safari/537.36"

/**
 * 清掉 WebView 里 music.163.com 的 cookie。
 *
 * 凭证已经在 803 那一刻读出来、存进加密 store 了，WebView 里那份明文（含 `MUSIC_U`）
 * 从此只剩风险：root 机 / ADB 备份 / 其他能读 app 私有目录的路径都能拿走它。
 *
 * 逐条写 `Max-Age=0` 而不是 `removeAllCookies()` —— 后者会把**所有**站点的 cookie
 * 一起清掉，可能波及 App 里其他用到 WebView 的登录。
 */
private fun clearNeteaseWebViewCookies() {
    val manager = CookieManager.getInstance()
    val url = "https://music.163.com"
    val raw = runCatching { manager.getCookie(url) }.getOrNull().orEmpty()
    if (raw.isBlank()) return
    raw.split(";").forEach { part ->
        val name = part.substringBefore("=").trim()
        if (name.isEmpty()) return@forEach
        // 两个 domain 变体都要写：`.music.163.com` 与 `music.163.com` 里可能各有一份。
        listOf(".music.163.com", "music.163.com").forEach { domain ->
            manager.setCookie(
                url,
                "$name=; Path=/; Domain=$domain; Max-Age=0; " +
                    "Expires=Thu, 01 Jan 1970 00:00:00 GMT",
            )
        }
    }
    manager.flush()
    NeteaseQrDiag.log("cookiesCleared", "names=${raw.split(";").size}")
}

/** 一次扫码会话：[key] 来自网页，[qrContent] 与网页 QRCode.js 渲染的文本完全一致。 */
private data class NeteaseQrSession(
    val key: String,
    val qrContent: String,
)

/** 网页推上来的登录状态快照。 */
private data class QrSnapshot(
    val unikey: String,
    val code: Int,
    /** 页面状态机的阶段：`unikey` / `ready` / `poll-801` / `pollErr` … */
    val stage: String = "",
    /** 非 80x 终态的响应原文（风控码 8821 之类会整段落在这里）。 */
    val err: String = "",
    /** 原始探针串，用于诊断落盘。 */
    val probe: String = "",
)

/**
 * 执行一段 JS。`evaluateJavascript` 必须在 UI 线程调用，而调用点都在主线程。
 *
 * 返回值一律丢弃：自从改成页面主动推 title 之后，主流程不再依赖任何 JS 回调，
 * 这里的调用只用来装脚本，丢了也不影响状态流转。
 */
private fun evaluate(view: WebView, script: String) {
    view.evaluateJavascript(script, null)
}

private fun createNeteaseQrBitmap(content: String, size: Int): Bitmap {
    val matrix = QRCodeWriter().encode(
        content,
        BarcodeFormat.QR_CODE,
        size,
        size,
        mapOf(
            // 官方 QRCode.js 用的是 M 级纠错 + 1 模块白边，这里保持一致。
            EncodeHintType.MARGIN to 1,
            EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M,
            EncodeHintType.CHARACTER_SET to "UTF-8",
        ),
    )
    val pixels = IntArray(size * size)
    for (y in 0 until size) {
        for (x in 0 until size) {
            pixels[y * size + x] = if (matrix[x, y]) android.graphics.Color.BLACK else android.graphics.Color.WHITE
        }
    }
    return Bitmap.createBitmap(pixels, size, size, Bitmap.Config.ARGB_8888)
}

private fun saveNeteaseQrToGallery(context: Context, content: String) {
    val fileName = "MeloX-Netease-${System.currentTimeMillis()}.png"
    val mime = "image/png"
    // 存盘时再画一次：这里只做一次性动作，没必要把 1024px 位图常驻在扫码页状态里。
    val png = createNeteaseQrBitmap(content, QR_PIXELS)
    val bytes = ByteArrayOutputStream().use { sink ->
        check(png.compress(Bitmap.CompressFormat.PNG, 100, sink)) { "二维码编码失败" }
        sink.toByteArray()
    }
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, fileName)
            put(MediaStore.Images.Media.MIME_TYPE, mime)
            put(MediaStore.Images.Media.RELATIVE_PATH, "${Environment.DIRECTORY_PICTURES}/MeloX")
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
        val resolver = context.contentResolver
        val target = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
            ?: error(context.getString(R.string.account_image_create_failed))
        runCatching {
            resolver.openOutputStream(target)?.use { output -> output.write(bytes) }
                ?: error(context.getString(R.string.account_image_write_failed))
            resolver.update(
                target,
                ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) },
                null,
                null,
            )
        }.getOrElse { failure ->
            resolver.delete(target, null, null)
            throw failure
        }
    } else {
        @Suppress("DEPRECATION")
        val directory = File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES),
            "MeloX",
        )
        check(directory.exists() || directory.mkdirs()) { context.getString(R.string.account_folder_create_failed) }
        val target = File(directory, fileName)
        FileOutputStream(target).use { output -> output.write(bytes) }
        MediaScannerConnection.scanFile(context, arrayOf(target.absolutePath), arrayOf(mime), null)
    }
}

private suspend fun <T> runCatchingQr(block: suspend () -> T): Result<T> = try {
    Result.success(block())
} catch (cancelled: CancellationException) {
    throw cancelled
} catch (failure: Throwable) {
    Result.failure(failure)
}

/**
 * 把服务端的原始报错翻成用户能行动的提示。
 *
 * 网易云在识别到"非官方客户端 / 登录态异常"时会回「请切换登录方式或升级版本」这类
 * 风控文案——它对用户毫无指导意义（用户没法"升级网易云"来修 MeloX 的问题），
 * 直接透出来只会让人以为是 App 坏了。原始 message 已经落进 [NeteaseQrDiag]，
 * 这里只给可执行的指引。
 */
private fun String?.toUserFacingError(context: Context): String {
    val raw = this.orEmpty()
    return when {
        raw.isBlank() -> context.getString(R.string.account_netease_qr_verify_failed)
        raw.contains("切换登录方式") || raw.contains("升级新版本") || raw.contains("升级版本") ->
            context.getString(R.string.account_netease_qr_verify_failed)
        else -> raw
    }
}
