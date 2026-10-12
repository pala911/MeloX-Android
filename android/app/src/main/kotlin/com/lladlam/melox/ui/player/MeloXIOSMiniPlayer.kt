package com.lladlam.melox.ui.player

import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.EnterExitState
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import com.lladlam.melox.R
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.zIndex
import kotlinx.coroutines.launch
import kotlin.math.exp
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.kyant.shapes.Capsule
import com.lladlam.melox.ui.glass.bottomGlassSurfaceColor
import com.lladlam.melox.ui.glass.bottomLiquidGlassTint
import com.lladlam.melox.ui.glass.meloXLiquidBottomBar
import com.lladlam.melox.ui.glass.meloXLiquidContentTransform
import com.lladlam.melox.ui.glass.MeloXSymbol
import com.lladlam.melox.ui.glass.MeloXSymbolIcon
import com.lladlam.melox.ui.glass.MeloXSymbolVariant
import com.lladlam.melox.ui.glass.rememberMeloXLiquidInteraction
import com.lladlam.melox.playback.MeloXAudioReactiveRuntime
import com.lladlam.melox.playback.MeloXAudioReactiveSample
import com.lladlam.melox.ui.settings.MeloXSettingsRuntime

internal val MeloXMiniPlayerHeight = 48.dp

@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
fun MeloXIOSMiniPlayer(
    state: MeloXPlaybackUiState,
    onExpand: () -> Unit,
    compactProgress: Float = 0f,
    dynamicGlassEnabled: Boolean = true,
    sharedTransitionScope: SharedTransitionScope? = null,
    animatedVisibilityScope: AnimatedVisibilityScope? = null,
    applyPlayerArtworkScale: Boolean = true,
    /**
     * 「全屏播放页本次主状态推进是**关闭方向**吗」——由 `MeloXApp` 传同一个
     * `playerClosingIntent`（与 host 侧同源）。仅用于下沉回弹的方向判据。
     */
    closingIntent: Boolean = false,
) {
    if (!state.hasMedia) return

    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    val swipeWidth = with(density) { LocalConfiguration.current.screenWidthDp.dp.toPx() }
    val contentOffset = remember { Animatable(0f) }
    val liquidInteraction = rememberMeloXLiquidInteraction()
    var accumulatedDrag by remember { mutableFloatStateOf(0f) }
    var pendingDirection by remember { mutableIntStateOf(0) }
    var pendingOutgoingMediaId by remember { mutableStateOf<String?>(null) }
    // ★★ 2026-10-06 修「横滑切歌后抽搐闪回上一首」——「本次目标」快照。
    //   在 `onDragEnd` 越过阈值那一刻写入（实时读 state 的 queue/currentIndex），
    //   直到本次横滑完全落定才清。
    //
    //   ⚠⚠ **必须快照、不能每帧现算**（上一轮的错误就在这）：hold 期间
    //     `state.currentIndex` 可能因为 `PlaybackCommands.prioritizeManualQueue()`
    //     重排队列、或末位/首位回绕而**漂移**，此时 `neighborOf(pendingDirection)`
    //     会取到「再下一首」甚至「上一首」⇒ 那一帧迷你栏正中被另一首歌占据 ——
    //     用户看到的正是这个（「闪回上一首」）。
    //   ⇒ 快照锁定"用户亲眼看着它滑进来的那一首"，后续 state 怎么变都不影响它。
    var committedEntry by remember { mutableStateOf<MeloXQueueEntry?>(null) }
    var committedDirection by remember { mutableIntStateOf(0) }
    var reactiveSample by remember { mutableStateOf(MeloXAudioReactiveSample.Idle) }

    LaunchedEffect(state.mediaId, pendingDirection) {
        if (pendingDirection != 0 && state.mediaId != pendingOutgoingMediaId) {
            contentOffset.snapTo(0f)
            pendingDirection = 0
            pendingOutgoingMediaId = null
            committedEntry = null
            committedDirection = 0
        } else if (pendingDirection == 0 && committedEntry == null) {
            contentOffset.snapTo(0f)
        }
    }

    LaunchedEffect(pendingOutgoingMediaId) {
        if (pendingOutgoingMediaId == null) return@LaunchedEffect
        kotlinx.coroutines.delay(1_500L)
        contentOffset.animateTo(0f, spring(dampingRatio = .72f, stiffness = 430f))
        pendingDirection = 0
        pendingOutgoingMediaId = null
        committedEntry = null
        committedDirection = 0
    }

    val expansionProgress = if (animatedVisibilityScope != null) {
        val value by animatedVisibilityScope.transition.animateFloat(
            transitionSpec = { meloXPlayerLinearFloatSpec() },
            label = "mini-player-expansion-progress",
        ) { visibility ->
            if (visibility == EnterExitState.Visible) 0f else 1f
        }
        value
    } else {
        0f
    }

    // ── 过渡态判定用的**线性时钟**（2026-10-03）─────────────────────────────────
    //   ★★ 关闭尾段的「迷你栏下沉 → 回弹」**已移入共享元素 bounds 几何**
    //      （`meloXPlayerSharedBoundsTransform`，见 `MeloXPlayerTransitionSpec`）：
    //      morph 期间外壳与封面由**两个独立 overlay** 渲染 —— 加在节点上的位移
    //      （祖先 / 内部 `graphicsLayer`、祖先 `Modifier.offset`）都读不出可靠下沉；
    //      只有 **bounds 几何**才是 overlay 唯一服从的输入，且外壳/封面天然同步。
    //   ⇒ 这里只留一条**与 transition 同源的线性时钟**，用途是判定「是否还在过渡中」
    //     （给按钮/点击门控）。稳态恒 1。
    val avScope = animatedVisibilityScope
    val miniBounceClock by if (avScope != null) {
        avScope.transition.animateFloat(
            transitionSpec = {
                tween(durationMillis = meloXPlayerTweenDurationMillis(), easing = LinearEasing)
            },
            label = "mini-player-settle-clock",
        ) { visibility -> if (visibility == EnterExitState.Visible) 1f else 0f }
    } else {
        remember { mutableFloatStateOf(0f) }
    }
    // ⚠⚠⚠ 修「返回过程中还能点按钮」**且不能把迷你栏点死**（用户报障：「下滑关闭后
    //   点击迷你栏没有任何打开动作」）：
    //   判据**绝不能用 `transition.currentState != targetState`** —— 本页 transition 由
    //   `SeekableTransitionState` 驱动（手势 seek + settle），**下滑关闭后
    //   `currentState` 会长期 != `targetState`** ⇒ 那个判据会把迷你栏**永久禁用**。
    //   ⇒ 改用**与 transition 同源的线性时钟**：关闭方向 0→1，**恰好 1 = 完全就位**；
    //     稳态（迷你栏静止）恒为 1 ⇒ 只有 `< 0.98` 才算「过渡中」。
    val isTransitioning = avScope != null && miniBounceClock < 0.98f

    // The mini visualizer is hidden during expansion. Stop its 20 Hz state
    // updates so the shared-bound transition can spend its frame budget on
    // layout and glass rendering instead.
    LaunchedEffect(state.mediaId, state.isPlaying, expansionProgress > 0.01f) {
        if (expansionProgress > 0.01f || !state.isPlaying) {
            reactiveSample = MeloXAudioReactiveSample.Idle
            return@LaunchedEffect
        }
        while (true) {
            reactiveSample = MeloXAudioReactiveRuntime.sample(state.mediaId)
            kotlinx.coroutines.delay(50L)
        }
    }

    // All source chrome is driven by the same reversible expansion progress as
    // the full-player destination. The source fades as real composited content,
    // rather than only lowering text/icon colors, so the reverse transition is
    // equally visible when returning to the mini player.
    val miniChromeAlpha = 1f - smoothStep(expansionProgress, 0.10f, 0.58f)
    val miniSurfaceAlpha = 1f - smoothStep(expansionProgress, 0.02f, 0.48f)

    val playerArtworkScale = if (
        !applyPlayerArtworkScale || MeloXSettingsRuntime.reduceMotion || !MeloXSettingsRuntime.artworkMotionEnabled || state.isPlaying
    ) 1f else 0.74f
    val sharedArtworkScale = 1f +
        (playerArtworkScale - 1f) * smoothStep(expansionProgress, 0.30f, 0.88f)
    // ★ 缩回期「下沉 → 回弹」由**共享元素 bounds 几何**提供（见 `MeloXPlayerTransitionSpec`）：
    //   morph 期外壳由 overlay 渲染，只有 bounds 几何是 overlay 唯一服从的输入，且外壳/封面
    //   天然同步。这里 shell 的 `BoundsTransform` 在关闭方向叠加尾段一记下沉回弹。
    //
    // ⚠⚠⚠ 2026-10-06 修：此处原来硬编码 `{ true }` —— **实现与上面注释不符**
    //   （注释说「在关闭方向」，代码却对**所有**方向生效）。真机探针（`mxdiag.txt`）
    //   逐帧实证的后果：
    //   · 「下拉一点松手**回弹**」（`settle collapse=false`）时本通道仍 `en=true`，
    //     在 f=0.628→0.901 段施加 **0→87→0 px** 的一整记下沉 ⇒ 用户报障
    //     「播放器会下沉抽搐一下再往上」；
    //   · 点迷你栏**打开**时（`openPlayer`）同样误差 (full→mini) 这一侧 ⇒ 误沉，读成「很难看」。
    //   ⚠ host 侧同一函数用的是 `closingIntent || targetState == PostExit`，两侧判据必须同义，
    //     否则共享元素的两个 overlay 会一个沉一个不沉（探针里同一帧 `en` 一 True 一 False）。
    //
    //   ⚠ 传 **lambda** 且 `remember` 的 key **不得含方向**（同 host 侧的警告）：手势下滑
    //     越过阈值那一刻若实例被重建 ⇒ Compose 重新推导 bounds spec ⇒ **回弹被丢掉**。
    val closingIntentState = rememberUpdatedState(closingIntent)
    val sinkPeakPx = with(LocalDensity.current) { MeloXPlayerSinkPeakDp.dp.toPx() }
    val sharedSinkBoundsTransform = remember(sinkPeakPx) {
        meloXPlayerSharedBoundsTransform(sinkPeakPx) { closingIntentState.value }
    }
    val sharedShellModifier =
        if (sharedTransitionScope != null && animatedVisibilityScope != null) {
            with(sharedTransitionScope) {
                Modifier.sharedBounds(
                    sharedContentState = rememberSharedContentState(key = sharedPlayerShellKey()),
                    animatedVisibilityScope = animatedVisibilityScope,
                    enter = EnterTransition.None,
                    exit = ExitTransition.None,
                    boundsTransform = sharedSinkBoundsTransform,
                    resizeMode = SharedTransitionScope.ResizeMode.RemeasureToBounds,
                    // ⚠⚠⚠ 2026-10-06 **必须为正**：本迷你栏侧（含**液态玻璃**）在 overlay 内
                    //   要浮在目标端（播放页，默认 `0f`）**之上**。
                    //
                    //   为什么：`sharedShellModifier` 这个 sharedBounds 节点**把迷你栏的
                    //   液态玻璃一起包在里面**（见下方 `meloXLiquidBottomBar` 处注释：
                    //   "Keep the glass surface out of the shared-bounds node itself"
                    //   —— 指的是不参与 **bounds 几何**，但**仍在节点内、仍走 overlay 渲染**）。
                    //   ⇒ 转场期间迷你栏这一份被渲进共享元素 overlay（`zIndex` 100f 档，
                    //     浮在播放页 `zIndex(20f)` 之上）。
                    //   若把本侧压到目标端之下（曾经的 `-1f`），则**播放页的副本盖住迷你栏**，
                    //   而播放页副本里含**专辑流光背景** ⇒ 肉眼即「**流光盖住迷你栏液态玻璃**」
                    //   （用户报障：「流光本来在动画执行过程中就有，正确是在迷你栏的下一层级，
                    //     现在是偶尔误盖住」）。
                    //
                    //   ⚠ 历史：`-1f` 是 2026-10-06 为修「轻微下拉下沉」加的，但那个症状的
                    //     真根因后来被定位为 **`expansionProgress` 回弹时先反向**
                    //     （探针实证 `P` 行 0.942→0.777，见 `MeloXIOSNowPlayingSharedHost`
                    //     里 `expansionProgress` 的说明）⇒ 该降序保护**既无效又有害**，已撤销。
                    //   ⚠ 封面是**独立的 sharedElement**（本侧 `99f` / host 侧 `100f`），
                    //     不受此处影响，仍由 host 那一份在上渲染。
                    zIndexInOverlay = 1f,
                )
            }
        } else {
            Modifier
        }

    val compact = compactProgress.coerceIn(0f, 1f)
    // ★ 展开态封面 40dp 太大（用户报障：展开态封面过大）。栏高 48dp、内容区
    //   （48 − 4×2 = 40dp），40dp 封面正好顶满上下边距、显得塞；收至 32dp 后
    //   上下各留 4dp，与 12dp 侧边距形成呼吸。收缩态 26 → 28dp（用户报障「收缩态
    //   的封面小了点」）。
    val artworkSize = lerpDp(40.dp, 30.dp, compact)
    val artworkRadius = 6.dp
    // ⚠ 2026-10-02 23:11 移除「morph 期把封面圆角收为直角」（`morphArtworkRadius`
    //   窗口 0.00~0.18）。这个窗口是第九轮基于**错误根因**加的（当时以为外壳
    //   Lamé 圆角在切封面），真根因是 `renderInOverlayDuringTransition = true`
    //   让**源侧 clip 根本不参与 morph** —— 所以源侧这个窗口从一开始就纯属多余。
    //
    //   它自己反而制造了用户报障的「关闭瞬间封面变直」：关闭时 p: 1→0，
    //   `1 - smoothStep(p, 0, 0.18)` 在 p ≥ 0.18 期间恒为 0（直角），
    //   实测前 **80ms 圆角一直是 0dp**，直到 p<0.18（约 260ms）才突增回 6dp
    //   ⇒ 正是「播放页封面圆角瞬间变直，直到动画结束变成小封面圆角才正常」。
    //
    //   ⇒ 恒定 6dp。morph 期的圆角连续性由 Overlay 副本
    //     （`MeloXIOSNowPlayingSharedHost` 的 `targetRadius`）负责。
    //
    // ★★ 2026-10-03 修正（用户报障「还是直接变」，HUD 读数实证）：
    //   HUD 显示 `p` 在变、`r` 在变、**但 `size` 恒为 296dp** ——
    //   因为播放页那个 Box 的 `targetSize = lerpDp(fullArtworkSize, 72.dp,
    //   pageFrameProgress)`，而 `pageFrameProgress` **完全不受拖拽影响**
    //   ⇒ 真正让封面缩小的不是它，是 `sharedBounds` **外壳**。
    //   于是形态被拆成两半：
    //     · 尺寸 ← 外壳插值（两端 Box 各自声明，sharedBounds 中间值）
    //     · 圆角 ← Overlay 副本那个**恒定 296dp** Box 上的 clip
    //   而两端 shape 必须**逐帧一致**，Compose 才会在 Overlay 里正确合成。
    //   之前迷你侧恒 6dp、播放侧 `lerpDp(6,12,p)` ⇒ 两端不一致 ⇒ 观感突变。
    //   ⇒ 两端统一用**同一个表达式**（下面），任意 p 下两端口径完全相同。
    val artworkClipRadius: Shape? = RoundedCornerShape(
        // 6dp → 12dp，与 `MeloXIOSNowPlayingSharedHost` 的 `targetRadius` 逐字一致。
        (6f + 6f * expansionProgress.coerceIn(0f, 1f)).dp
    )
    val compactNextAlpha = 1f - smoothStep(compact, 0.04f, 0.50f)
    // 收缩态内容区(36dp) 比展开态(30dp) 更高 ⇒ 控件随内容区放大（用户口径）。
    // 用同一根 compact 曲线（与内边距同源），保证「栏高变化 ↔ 控件大小」同步、不会错拍。
    val controlMetric = smoothStep(compact, 0.20f, 0.80f)
    val controlSize = lerpDp(MiniControlSizeExpanded, MiniControlSizeCollapsed, controlMetric)
    val controlIconSize = lerpDp(MiniControlIconSizeExpanded, MiniControlIconSizeCollapsed, controlMetric)
    // 两圆之间的**圆缘**间距。图标间距 = 本值 + 2×圆内边距((size−icon)/2)。
    // 展开 (30−18)/2 = 6 ⇒ 图标间距 = gap + 12。
    val controlGap = lerpDp(MiniControlGapExpanded, MiniControlGapCollapsed, controlMetric)
    // 收缩后仍显示歌手，不再把这一行收成 0。
    val artistHeight = 15.dp
    // ── 排版必须**锁 dp、不锁 sp**（2026-09-25 跨机型修「作者名下沉被裁切」）────────
    //   故障现象：其他机型上「歌曲作者名下沉、下半截被切平」（真机截图实测作者名只画出
    //     约六成高度，切边正好落在 15dp 作者行盒的底沿）。
    //   根因：栏高恒 48dp、内容区恒 30dp（= 48 − 3·2 − 6·2），**而行盒是 dp、字身是 sp**。
    //     `sp` 随系统「字体大小」`fontScale` 等比放大（HyperOS / ColorOS / MIUI 常见
    //     1.15，用户还可能再调到 1.3+），而 dp 的行盒一分不涨 ⇒ 12sp 的作者名在
    //     fontScale=1.15 的机器上字身大 15%，descent 落到 15dp 行盒之外被裁掉。
    //     同一台基准机（fontScale=1.0）只差 1~2px，肉眼看不出来 —— 这正是「只有其他机型
    //     才复现」的原因，**不是玻璃/折射问题，别再往 backdrop 方向查**。
    //   ⚠ **必须是 `Dp.toSp()`，接收者要 `Dp` 而不是 `Float`**（2026-09-25 踩坑实录）：
    //     `Density` 上有两个同名的 `toSp` 重载，语义完全不同 ——
    //       · `Dp.toSp()`        = dp / fontScale  ⇒ 本栏要的「锁 dp」换算；
    //       · `Density.toSp(Float)` = **把入参当作 px**（字节码是 `toDp(value).toSp()`，
    //         即 value / density / fontScale）⇒ 写 `14f.toSp()` 会得到 3.5sp，
    //         在 density=4 的机器上字号直接**缩到 1/4**（真机实测：旧包标题宽 504px，
    //         误用后 126px，正好 ÷4 = ÷density；用户反馈「变得太小了」即此）。
    //     ⇒ 一律用 `with(density) { X.dp.toSp() }`，**不要**用 `Float.toSp()`。
    //   换算结果 = dp / fontScale，再乘回去 ⇒ 任何 fontScale 下本栏排版几何与基准机
    //   逐像素一致。**字号只由下面的 dp 常量决定，不要再写裸 `sp`、也不要用 Float.toSp。**
    val titleFontSize = with(density) { MiniTitleFontDp.dp.toSp() }
    val titleLineHeight = with(density) { MiniLineHeightDp.dp.toSp() }
    val artistFontSize = with(density) { MiniArtistFontDp.dp.toSp() }
    val artistLineHeight = with(density) { MiniLineHeightDp.dp.toSp() }
    // ★★ 2026-10-06 修「迷你栏横滑切歌后抽搐闪回上一首」────────────────────────────
    //   症状：横滑切歌，新歌刚滑到位，迷你栏会「抽搐」一下、**短暂闪出队列里另一首歌**
    //        （站在用户视角往往就是「上一首」），下一帧才回到真正的当前曲目。
    //
    //   根因（两处叠加，都在本段）：
    //   ① 缺「已提交」判据 ⇒ 播放状态切到新歌那一帧，`holdIncoming` 当帧就翻 false，
    //      可把 `contentOffset` 归零的 `snapTo(0f)` 在 `LaunchedEffect` 里、**晚一帧**
    //      才执行。于是有整整一帧：
    //        · 主行 = 新歌（`state.title`），却仍带着 ±swipeWidth 的出场偏移 ⇒ 画到屏外；
    //        · 相邻预览行按**新的** `currentIndex` 取到「新歌的再下一首」，
    //          而它的 `translationX = visualOffset − dragDirection×swipeWidth` 恰好 = 0
    //          ⇒ 迷你栏正中被那一首「再下一首」占据。
    //   ② 原 `pendingIncoming` 是**在 `onDragEnd` 里赋值的普通局部量**
    //      （`pendingIncoming = adjacentEntry`）。但 `onDragEnd` 活在
    //      `Modifier.pointerInput(state.mediaId) { … }` 的块里 —— 该块**只在 key 变化时
    //      重新执行**，闭包捕获的 `adjacentEntry` 是**上一次 key 变化那一帧组合的旧快照**
    //      ⇒ 要么恒为 `null`（`dragDirection` 当时是 0），要么是上一次落定后残留的**错误邻居**。
    //
    //   ⇒ 修法：①「要顶在 0 位的那一首」改成**由当前帧 state 现算**（`neighborOf`），
    //     不再跨协程传值；②新增 `swipeCommitted`：媒体已切走时**当帧**就把整块内容钉回
    //     0 位并显示 `state` 的真实曲目，不再依赖晚一帧的 `snapTo`。
    val swipeCommitted = pendingDirection != 0 && state.mediaId != pendingOutgoingMediaId

    // 「方向 → 队列邻居」的唯一实现。⚠ 必须在组合体里用**当前 state** 现算：
    //   `state.queue` / `state.currentIndex` 是 Compose state 读，任何时刻取到的都是最新值；
    //   而 `onDragEnd` 闭包里那个旧快照取不到（见上面 ②）。索引回绕口径与
    //   `nextFromMiniPlayer()` / `previousFromMiniPlayer()` 的 `playQueueIndex` 一致。
    val neighborOf: (Int) -> MeloXQueueEntry? = { direction ->
        when (direction) {
            -1 -> state.queue.getOrNull(
                if (state.currentIndex + 1 < state.queue.size) state.currentIndex + 1 else 0,
            )
            1 -> state.queue.getOrNull(
                if (state.currentIndex > 0) state.currentIndex - 1 else state.queue.lastIndex,
            )
            else -> null
        }
    }

    // Keep the song that was already sliding in. Replacing that row with the
    // newly current track tears the layout down and flashes the next title.
    //
    // ⚠ 判据用 `pendingDirection != 0` 而**不是**「偏移量 > 0.82×swipeWidth」：
    //   `pendingDirection` 只在 `contentOffset.animateTo(±swipeWidth)` **await 返回之后**
    //   才被置上 ⇒ 它天然表示「出场动画已经正好跑完、相邻预览恰好停在 0」这一刻，
    //   此时把主行切到那一首是**逐像素无缝**的。若改成用偏移量阈值（旧写法 0.82），
    //   切换会发生在预览还差 0.18 屏宽没走完时 ⇒ 那一首会**瞬间横跳 0.18 屏宽**。
    //
    // ★★ 2026-10-06（第二轮）：目标曲目改用 `committedEntry` **快照**（见其声明处），
    //   不再 `neighborOf(pendingDirection)` 现算 —— 现算会在 hold 期间随
    //   `currentIndex` 漂移，取到「再下一首 / 上一首」⇒ 抽搐闪回。
    val holdIncoming = !swipeCommitted && pendingDirection != 0 && committedEntry != null
    val shownEntry = if (holdIncoming) committedEntry else null
    val shownTitle = shownEntry?.title ?: state.title
    val shownArtist = shownEntry?.artist ?: state.artist
    val shownArtwork = shownEntry?.artworkUrl ?: state.artworkUrl
    val shownMediaId = shownEntry?.mediaId ?: state.mediaId
    // ⚠ `swipeCommitted` 必须一起归零（见上面 ①）——少这一条就是那一帧的闪回。
    val visualOffset = if (holdIncoming || swipeCommitted) 0f else contentOffset.value
    val dragDirection = when {
        visualOffset < 0f -> -1
        visualOffset > 0f -> 1
        else -> 0
    }
    // ★ 预览行曲目：松手后有快照 ⇒ 用快照（方向也锁快照）；拖拽中无快照 ⇒ 按当前方向现算。
    //   两条来源在 `currentIndex` 未变时结果相同，因此松手瞬间视觉连续；
    //   而松手之后即使 `currentIndex` 漂移，预览行也**不再跟着变**。
    val previewEntry = committedEntry ?: neighborOf(dragDirection)
    val previewDirection = if (committedEntry != null) committedDirection else dragDirection
    val dragProgress = (kotlin.math.abs(visualOffset) / swipeWidth.coerceAtLeast(1f)).coerceIn(0f, 1f)
    val adjacentAlpha = smoothStep(dragProgress, 0.15f, 0.85f)

    // ── 取证埋点（release 路径 `MeloXPlayerSinkDiag` 未 `bind` ⇒ 全空操作）─────────
    //   ⚠ 只打**离散量**：连续量（offset/visualOffset）每帧都变，会把 mxdiag.txt 刷爆。
    //   判定口径：`cmt` = 快照目标；`swc` = state 是否已追上；`shown` = 实际渲染的曲目索引。
    //   「闪回」若复现，应能在日志里看到 `shown` 出现一次与 `cmt`/`sidx` 都不同的值。
    MeloXPlayerSinkDiag.sample("mini.dir", pendingDirection)
    MeloXPlayerSinkDiag.sample("mini.cmt", committedEntry?.index ?: -1)
    MeloXPlayerSinkDiag.sample("mini.swc", if (swipeCommitted) 1 else 0)
    MeloXPlayerSinkDiag.sample("mini.sidx", state.currentIndex)
    MeloXPlayerSinkDiag.sample("mini.shown", shownEntry?.index ?: -1)
    // ⚠ 2026-10-06 补：上面的**索引**全部自洽也照样会「闪回上一首」——
    //   那次的错配发生在**文字**上（`mediaId` 已是新歌、`title/artist` 还是上一首）。
    //   ⇒ 必须直接观测「实际渲染的标题+歌手」与「state 的标题+歌手」。用 hashCode 而不是
    //     原文，避免长标题把 mxdiag 刷爆；hash 相同 ⇔ 同一首歌。
    //   判据：若 `mini.shownT` 出现一次与「落定前后」都不同的值、且随后恢复到 `mini.stateT`，
    //   就是这里闪了。
    MeloXPlayerSinkDiag.sample("mini.shownT", (shownTitle + "|" + shownArtist).hashCode())
    MeloXPlayerSinkDiag.sample("mini.stateT", (state.title + "|" + state.artist).hashCode())

    // 播放栏高度固定 48dp，与底栏收缩态的两侧按钮/搜索键完全等高。
    // 之前用 lerpDp(45,48) 且外层 Box 未定高，会随 compact 与 sharedBounds
    // 测量出更高/更矮的玻璃壳；这里统一钉死 48dp。
    val miniHeight = 48.dp

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(miniHeight),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(miniHeight)
                .then(sharedShellModifier),
        ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(miniHeight),
        ) {
            // Keep the glass surface out of the shared-bounds node itself.
            // When a capsule-shaped draw modifier is resized to the full
            // player, its fallback/lens can briefly become a dark giant pill.
            // The shared node remains a transparent geometry shell while the
            // actual MiniPlay glass fades before the full-screen scene owns the
            // surface.
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer { alpha = miniSurfaceAlpha }
                    // 与底栏 nav 胶囊用同一套材质（meloXLiquidBottomBar + 同一
                    // tint/surfaceColor），保证深/浅色下观感一致；按压反馈通过
                    // pressProgress 传入，内容层仍走 meloXLiquidContentTransform。
                    .meloXLiquidBottomBar(
                        shape = Capsule(),
                        tint = bottomLiquidGlassTint(),
                        surfaceColor = bottomGlassSurfaceColor(),
                        pressProgress = liquidInteraction.highlight.pressProgress,
                        refractionHeight = 8.dp,
                    ),
            )
        }

        // The glass draws underneath this layer, but both now consume the same
        // press/drag transform. Keeping MiniPlay chrome in the shared layer
        // prevents text, artwork and controls from floating over a moving pill.
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(miniHeight)
                .padding(vertical = 3.dp)
                .meloXLiquidContentTransform(liquidInteraction),
            contentAlignment = Alignment.Center,
        ) {
            // ★ morph 期间取消 `.clip(Capsule())`：`Capsule` 半径 = 行高/2，而 morph
            //   期间 sharedBounds 用 `RemeasureToBounds`（L169）把整行从 48dp 重新测量
            //   成全屏高 ⇒ 半径同步暴涨（实测 90ms 已 ~125dp、末段 ~450dp），
            //   整行内容会被切成一个巨大的圆。**这个 bug 是真实的**，修的是它。
            //   ⚠ 注意：它与「封面圆角」无关——封面的 `sharedElement` 带
            //     `renderInOverlayDuringTransition = true`，morph 期间由
            //     `MeloXIOSNowPlayingSharedHost` 的副本渲染，**源侧 clip 不参与**。
            val miniContentShape: Shape? =
                if (expansionProgress > 0.001f) null else Capsule()

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(miniHeight)
                    .then(miniContentShape?.let { Modifier.clip(it) } ?: Modifier)
                    .padding(
                        horizontal = lerpDp(12.dp, 8.dp, compact),
                        // Expanded content was exactly 30dp (title 15 + artist 15)
                        // with no slack, so the last line's descenders were clipped.
                        // Keep 4dp instead of 6dp to leave room for both lines.
                        vertical = lerpDp(4.dp, 3.dp, compact),
                    ),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .pointerInput(state.mediaId) {
                            detectHorizontalDragGestures(
                                onDragStart = {
                                    accumulatedDrag = 0f
                                    // ★ 新拖拽一律从 0 位起算（2026-10-06）：上一次横滑若还没落定，
                                    //   `contentOffset` 可能正停在 ±swipeWidth（新歌由相邻预览顶在
                                    //   0 位、等 mediaId 落地才归零）。此时继续累加会让整块信息
                                    //   「沾手」跟跑、第一次 move 还会瞬跳 0.14 屏宽。先结算。
                                    //   副作用（可接受）：上一次的 `animateTo` 若还在跑会被 `stop()`
                                    //   取消 ⇒ 协程抛 CancellationException ⇒ 那一次切歌被吞。
                                    // ⚠ 清 `pendingDirection` 同时会立刻释放上面的 `holdIncoming` /
                                    //   `swipeCommitted` 锁定位（否则新拖拽期间 `visualOffset` 被钉死 0）。
                                    pendingDirection = 0
                                    pendingOutgoingMediaId = null
                                    committedEntry = null
                                    committedDirection = 0
                                    scope.launch {
                                        contentOffset.stop()
                                        contentOffset.snapTo(0f)
                                    }
                                },
                                onHorizontalDrag = { change, dragAmount ->
                                    change.consume()
                                    accumulatedDrag += dragAmount
                                    scope.launch {
                                        contentOffset.snapTo((contentOffset.value + dragAmount).coerceIn(-swipeWidth * .86f, swipeWidth * .86f))
                                    }
                                },
                                onDragEnd = {
                                    val direction = when {
                                        accumulatedDrag <= -28f -> -1
                                        accumulatedDrag >= 28f -> 1
                                        else -> 0
                                    }
                                    // ★★ 2026-10-06（第二轮）「真的会换曲」预判 ─────────────────
                                    //   单曲队列、或命中「上一首 = 已播 ≥5s 时重播当前曲」
                                    //   （`previousRestartsAfterFiveSeconds`，**默认开**）这两条路径
                                    //   **曲目根本不变**。若照旧播切歌动画，`holdIncoming` 会一直顶着
                                    //   「上一首」不放（因为判据是"mediaId 变了"，而它永远不会变），
                                    //   直到 1.5s 超时才滑回 ⇒ 用户看到的就是
                                    //   「横滑后抽搐闪回上一首」。
                                    //   ⇒ 不换曲时**不做切歌视觉**：只弹回原位，命令照发（重播/回开头）。
                                    if (direction != 0 && state.miniSwipeChangesTrack(direction)) {
                                        // 快照本次目标（实时读 state 的 queue/currentIndex）⇒
                                        // hold 期间不再现算，避免 currentIndex 漂移取到错歌。
                                        committedEntry = neighborOf(direction)
                                        committedDirection = direction
                                        scope.launch {
                                            contentOffset.animateTo(direction * swipeWidth, spring(dampingRatio = .68f, stiffness = 360f))
                                            // ⚠ 这里**不再**写 `pendingIncoming`（已由 `committedEntry`
                                            //   取代）：该值在 `onDragEnd` 时已快照，本闭包活在
                                            //   `pointerInput(state.mediaId)` 块里，早期版本的
                                            //   `adjacentEntry` 是旧组合快照。
                                            pendingDirection = direction
                                            pendingOutgoingMediaId = state.mediaId
                                            if (direction < 0) state.nextFromMiniPlayer() else state.previousFromMiniPlayer()
                                        }
                                    } else {
                                        // 不换曲（含 `direction == 0` 未过阈值）⇒ 只弹回原位、不播切歌视觉。
                                        // ⚠⚠ 2026-10-06 修上一轮引入的小回归：脚本原意是「命令两个方向都发」，
                                        //   我只写了 `direction > 0` ⇒ **单曲队列时左滑（下一首）完全无响应**
                                        //   （`miniSwipeChangesTrack` 判 false，而 `nextFromMiniPlayer()`
                                        //   里的 `playQueueIndex(0)` 才是用户期望的"重播当前曲"语义）。
                                        //   `direction == 0`（没过 28px 阈值）不发命令，保持原样。
                                        when {
                                            direction < 0 -> state.nextFromMiniPlayer()
                                            direction > 0 -> state.previousFromMiniPlayer()
                                        }
                                        scope.launch { contentOffset.animateTo(0f, spring(dampingRatio = .72f, stiffness = 430f)) }
                                    }
                                    accumulatedDrag = 0f
                                },
                                onDragCancel = {
                                    accumulatedDrag = 0f
                                    scope.launch { contentOffset.animateTo(0f, spring(dampingRatio = .72f, stiffness = 430f)) }
                                },
                            )
                        }
                        // ⚠ 过渡进行中（收回/展开）禁用整条点击 —— 修「返回过程中还能点到
                        //   播放器按钮」。稳态（`isTransitioning=false`）行为不变。
                        .clickable(
                            interactionSource = null,
                            indication = null,
                            enabled = !isTransitioning,
                            onClick = onExpand,
                        ),
                ) {
                Row(
                    modifier = Modifier.fillMaxSize().graphicsLayer { translationX = visualOffset },
                    verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(lerpDp(10.dp, 8.dp, compact)),
                ) {
                val sharedArtworkModifier =
                    if (sharedTransitionScope != null && animatedVisibilityScope != null) {
                        with(sharedTransitionScope) {
                            Modifier.sharedElement(
                                sharedContentState = rememberSharedContentState(
                                    key = sharedPlayerArtworkKey(shownMediaId),
                                ),
                                animatedVisibilityScope = animatedVisibilityScope,
                                boundsTransform = sharedSinkBoundsTransform,
                                renderInOverlayDuringTransition = true,
                                // ⚠ 与上面 shell 同理（2026-10-06 下沉加固）：源端封面必须排在
                                //   目标端封面（host 侧同为 overlay 层，`100f`）**之下**，
                                //   否则回弹时它会盖着播放页从全屏封面缩成迷你封面。
                                zIndexInOverlay = 99f,
                            )
                        }
                    } else {
                        Modifier
                    }
                Artwork(
                    url = shownArtwork,
                    modifier = Modifier
                        // `size` / `aspectRatio` 都会服从父级最大高度，封面被压扁后再被 Crop 裁成
                        // 长方形。`requiredSize` 忽略那条高度上限，封面保持正方形，行本身垂直居中。
                        .requiredSize(artworkSize)
                        .then(sharedArtworkModifier)
                        .graphicsLayer {
                            scaleX = sharedArtworkScale
                            scaleY = sharedArtworkScale
                        }
                        .then(artworkClipRadius?.let { Modifier.clip(it) } ?: Modifier),
                )

                Column(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .wrapContentHeight(Alignment.CenterVertically)
                        .graphicsLayer { alpha = miniChromeAlpha },
                    verticalArrangement = Arrangement.Center,
                ) {
                    Text(
                        text = shownTitle.ifBlank { stringResource(R.string.player_now_playing) },
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        fontSize = titleFontSize,
                        // ⚠ `lineHeight` 必须与播放栏的高度预算对齐（2026-09-25 修作者名裁切）：
                        //   内容区只有 30dp，title + artist 两行必须 ≤ 30dp，否则底部被裁。
                        //   两行各 15dp（= `MiniLineHeightDp`），14dp 字配 15dp 行距 = 1.07×，不挤。
                        //   ⚠ 这里**只能**用 `titleLineHeight`（dp→sp 换算），不能写裸 `15.sp`：
                        //     裸 sp 会随 fontScale 放大、撑破 30dp 预算 —— 跨机型裁切的成因，
                        //     见上面 `titleFontSize` 处的长注释。
                        lineHeight = titleLineHeight,
                        softWrap = false,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface,
                        // ⚠ `includeFontPadding = false` 是**必须的**（2026-09-25 修作者名裁切）：
                        //   Android 默认 `includeFontPadding = true` 会在行高之外额外加
                        //   ascent/descent 空白，标题+作者两行的**实际布局高 > 行高之和**，
                        //   而播放栏内容区只有 30dp（48 − 3·2 − 6·2），于是底部的作者名被裁。
                        //   BiliNext `GlassTabItem` 也是显式关掉它（`PlatformTextStyle(includeFontPadding = false)`）。
                        style = LocalTextStyle.current.merge(
                            TextStyle(platformStyle = PlatformTextStyle(includeFontPadding = false)),
                        ),
                    )
                    Text(
                        text = shownArtist,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        fontSize = artistFontSize,
                        lineHeight = artistLineHeight,
                        softWrap = false,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.64f),
                        modifier = Modifier.height(artistHeight),
                        style = LocalTextStyle.current.merge(
                            TextStyle(platformStyle = PlatformTextStyle(includeFontPadding = false)),
                        ),
                    )
                }
                }
                // ★ 预览行：`previewEntry` 松手后是**快照**（见其声明处）。
                //   `!holdIncoming` 时主行已顶着目标曲（0 位），此时预览行必然在
                //   ±整屏宽处、`adjacentAlpha = 0` ⇒ 直接不挂载，省一层（含封面图）合成。
                if (!holdIncoming) previewEntry?.let { entry ->
                    Row(
                        modifier = Modifier
                            .fillMaxSize()
                            .graphicsLayer {
                                alpha = adjacentAlpha
                                translationX = visualOffset - previewDirection * swipeWidth
                            },
                        verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(lerpDp(10.dp, 8.dp, compact)),
                    ) {
                        Artwork(
                            entry.artworkUrl,
                            Modifier
                                .requiredSize(artworkSize)
                                .then(artworkClipRadius?.let { Modifier.clip(it) } ?: Modifier),
                        )
                        Column(
                            Modifier
                                .weight(1f)
                                .fillMaxHeight()
                                .wrapContentHeight(Alignment.CenterVertically),
                            verticalArrangement = Arrangement.Center,
                        ) {
                            // ⚠ 与主条目同样的 `includeFontPadding = false`（见主条目长注释）：
                            //   否则横滑切入的这一份也会在 30dp 内容区里把作者名裁掉，
                            //   而它恰好是**滑动过程中用户正在看的那一份**。
                            val noFontPadding = LocalTextStyle.current.merge(
                                TextStyle(platformStyle = PlatformTextStyle(includeFontPadding = false)),
                            )
                            Text(
                                entry.title, maxLines = 1, overflow = TextOverflow.Ellipsis,
                                fontSize = titleFontSize, lineHeight = titleLineHeight, softWrap = false,
                                fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface,
                                style = noFontPadding,
                            )
                            Text(
                                entry.artist, maxLines = 1, overflow = TextOverflow.Ellipsis,
                                fontSize = artistFontSize, lineHeight = artistLineHeight, softWrap = false,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = .64f),
                                modifier = Modifier.height(artistHeight),
                                style = noFontPadding,
                            )
                        }
                    }
                }
            }

            MiniDancingBars(
                active = miniChromeAlpha > 0.05f,
                isPlaying = state.isPlaying,
                sample = { reactiveSample },
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = miniChromeAlpha * 0.72f),
                modifier = Modifier
                    .width(15.dp)
                    .height(18.dp)
                    .graphicsLayer { alpha = miniChromeAlpha },
            )

            // ── 控件舞台（内容驱动宽度）──────────────────────────────────────
            // ⚠ 关键教训（2026-09-25 v0.9.7→0.9.9）：舞台**不能**写成固定宽度。
            //   父 Row 里 title 是 weight(1f)，会吞掉所有剩余空间 ⇒ 舞台宽一改，
            //   title 宽度反向变化，**整块舞台被推着往右滑**，把「下一首」顶到玻璃右缘
            //   （v0.9.9 实测 stage 右移 ~11dp、play 右移 ~22dp）。
            //   正解：舞台宽度由内容决定（两个圆 + 显式 gap），Row 尺寸**恒定**，
            //        这样调间距就只是调间距，不会牵动任何别的元素。
            Row(
                modifier = Modifier
                    .height(controlSize)
                    .zIndex(8f),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(controlGap),
            ) {
                MiniVectorButton(
                    kind = if (state.isPlaying) MiniGlyph.Pause else MiniGlyph.Play,
                    // ⚠ 过渡进行中禁用（修「返回过程中还能点到播放器按钮」）。
                    enabled = !isTransitioning,
                    onClick = state::togglePlayPause,
                    size = controlSize,
                    iconSize = controlIconSize,
                    modifier = Modifier
                        .offset(x = MiniControlStartPull)
                        .zIndex(10f),
                    visualAlpha = miniChromeAlpha,
                )
                if (compactNextAlpha > 0.05f) {
                    MiniVectorButton(
                        kind = MiniGlyph.Forward,
                        enabled = !isTransitioning && (state.hasNext || state.repeatMode != 0),
                        onClick = state::next,
                        size = controlSize,
                        iconSize = controlIconSize,
                        modifier = Modifier.zIndex(9f),
                        visualAlpha = miniChromeAlpha * compactNextAlpha,
                    )
                }
            }
        }
        }
    }
}
}

/**
 * 四条音浪柱：随 [MeloXAudioReactiveSample] 的能量/节拍起舞（仅展开态挂载）。
 *
 * ⚠⚠ 卡顿修复（2026-10-06，用户报障「源代码的四条音乐波浪很卡、帧率很低」）——
 *   旧实现两层问题叠加：
 *   ① 采样循环 `delay(50)` = **20Hz 离散跳格**：120Hz 屏上每 50ms 才动一下，
 *      帧间完全静止 ⇒ 观感就是「帧率很低」；
 *   ② `reactiveSample` 在组合层读值、以值参数下传 ⇒ 每 50ms 把整个迷你栏
 *      （标题/按钮）重组一遍（铁律：逐帧状态只能在叶子消费点读）。
 *
 * 新架构：
 *   · 采样值经 [sample] lambda 进来，只在 `withFrameNanos` 帧循环里读——**不进组合订阅**；
 *   · 每帧把四条柱高向目标做**帧率无关的指数趋近**（起 28/s、落 12/s：起拍快、
 *     回落稍拖出「余韵」，音乐感更好），任意刷新率下都连续运动；
 *   · 柱高写进普通 FloatArray，再 bump 一个 IntState——该状态**只被 Canvas draw 块
 *     读取** ⇒ 写入仅触发 draw invalidation（重绘），零重组；
 *   · [active] = false（展开进播放页）直接空转帧循环，不抢共享元素转场的帧预算
 *     （与上方 20Hz 采样循环在 expansion 时的停更策略同源）。
 */
@Composable
private fun MiniDancingBars(
    active: Boolean,
    isPlaying: Boolean,
    sample: () -> MeloXAudioReactiveSample,
    color: Color,
    modifier: Modifier = Modifier,
) {
    val smooth = remember { FloatArray(4) }
    val redrawTick = remember { mutableIntStateOf(0) }
    val activeState = rememberUpdatedState(active)
    val playingState = rememberUpdatedState(isPlaying)
    LaunchedEffect(Unit) {
        var lastNanos = 0L
        while (true) {
            withFrameNanos { now ->
                if (!activeState.value) {
                    lastNanos = 0L
                    return@withFrameNanos
                }
                val dt = if (lastNanos == 0L) 1f / 60f
                else ((now - lastNanos) / 1_000_000_000f).coerceIn(1f / 240f, 0.1f)
                lastNanos = now
                val s = sample()
                val playing = playingState.value
                val energy = if (playing) s.energy.coerceIn(0.08f, 1f) else 0.10f
                val beat = if (playing) s.beat else 0f
                val downbeat = if (playing) s.downbeat else 0f
                // 目标柱高（与旧版同一组配方），起/落速率分帧率无关的指数系数。
                val rise = 1f - exp(-dt * 28f)
                val fall = 1f - exp(-dt * 12f)
                val targets = floatArrayOf(
                    energy * (0.58f + downbeat * 0.30f),
                    energy * (0.86f + beat * 0.22f),
                    energy * (0.46f + downbeat * 0.42f),
                    energy * (0.72f + beat * 0.28f),
                )
                for (i in 0..3) {
                    val t = targets[i].coerceIn(0.12f, 1f)
                    val rate = if (t > smooth[i]) rise else fall
                    smooth[i] += (t - smooth[i]) * rate
                }
                redrawTick.intValue++ // bump ⇒ 仅重绘（状态只被下方 draw 块订阅）
            }
        }
    }
    Canvas(modifier) {
        @Suppress("UNUSED_EXPRESSION")
        redrawTick.intValue // draw 层订阅点：此处读状态，写入只走重绘、不走重组
        val gap = size.width * .12f
        val barWidth = (size.width - gap * 3f) / 4f
        smooth.forEachIndexed { index, heightFraction ->
            val barHeight = size.height * heightFraction.coerceIn(.12f, 1f)
            drawRoundRect(
                color = color,
                topLeft = Offset(index * (barWidth + gap), (size.height - barHeight) / 2f),
                size = androidx.compose.ui.geometry.Size(barWidth, barHeight),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(barWidth / 2f),
            )
        }
    }
}

private enum class MiniGlyph { Play, Pause, Forward }

@Composable
private fun MiniVectorButton(
    kind: MiniGlyph,
    enabled: Boolean,
    onClick: () -> Unit,
    size: Dp,
    iconSize: Dp,
    modifier: Modifier = Modifier,
    visualAlpha: Float = 1f,
) {
    val baseAlpha = if (enabled) 0.94f else 0.26f
    val drawAlpha = visualAlpha.coerceIn(0f, 1f)
    val color = MaterialTheme.colorScheme.onSurface.copy(alpha = baseAlpha)
    // ⚠ 播放/暂停**视觉重量**要各自补偿（用户报障：2026-10-02「暂停/开始的大小协调点」）。
    //   `play.fill` 是三角形，在同一字号下**视觉面积明显小于** `pause.fill` 的两根竖条
    //   （三角约占字框 ~50%，两竖条约 ~62%），直接同字号看起来就是「开始键偏小、
    //   暂停键偏大」。⇒ 播放三角单独放大 15%，两者视觉直径才接近。
    //   ⚠ 只放大 Play，Pause/Forward 保持 1.0（它们已是"重"图形）。
    val glyphScale = if (kind == MiniGlyph.Play) 1.15f else 1f
    val scaledIconSize = (iconSize.value * glyphScale).dp
    // ⚠ 图标字号也必须**锁 dp**（2026-09-25 跨机型修，同作者名裁切）：
    //   `iconSize` 是 dp（随内容区 30/36dp 收敛），但写 `iconSize.value.sp` 会随
    //   `fontScale` 放大 ⇒ 大字体机型上 22dp 图标画成 25dp+，被外圈
    //   `clip(CircleShape)` 切掉边缘。经 dp→sp 换算后图标恒 = iconSize dp。
    //   ⚠ `iconSize` **本身就是 `Dp`**，直接 `iconSize.toSp()` 即可；
    //     `iconSize.value.toSp()` 会命中把入参当 **px** 的 `Density.toSp(Float)`，
    //     图标会同样缩到 1/density（详见 `titleFontSize` 处的踩坑说明）。
    val iconFontSize = with(LocalDensity.current) { scaledIconSize.toSp() }
    Box(
        modifier = modifier
            .graphicsLayer { alpha = drawAlpha }
            .size(size)
            .clip(CircleShape)
            .clickable(
                enabled = enabled && drawAlpha > 0.05f,
                onClick = onClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        MeloXSymbolIcon(
            symbol = when (kind) {
                MiniGlyph.Play -> MeloXSymbol.Play
                MiniGlyph.Pause -> MeloXSymbol.Pause
                MiniGlyph.Forward -> MeloXSymbol.Next
            },
            // 圆缩小 ⇒ 图标必须同比例缩，否则图标会顶满圆/溢出
            modifier = Modifier.size(scaledIconSize),
            color = color,
            variant = if (kind == MiniGlyph.Play || kind == MiniGlyph.Pause) MeloXSymbolVariant.Fill else MeloXSymbolVariant.Regular,
            iconSize = iconFontSize,
        )
    }
}

// ── 播放栏右侧控件尺寸（2026-09-25 收敛为「随内容区高度」）──────────────────
// 播放栏玻璃壳恒 48dp，但内边距随 compact 变化 ⇒ **内容区高度不同**：
//   · 展开态：48 − 3·2(外) − 6·2(内) = **30dp**
//   · 收缩态：48 − 3·2(外) − 3·2(内) = **36dp**   ⇒ 收缩态内容区**更高**
// 因此控件不能写死一个尺寸，必须跟内容区一起变（用户：收缩态要更大一点）。
// 展开 30dp 圆 / 18dp 图标；收缩 36dp 圆 / 22dp 图标（= v0.9.6 之前的原始尺寸）。
private val MiniControlSizeExpanded = 30.dp
private val MiniControlSizeCollapsed = 36.dp
private val MiniControlIconSizeExpanded = 18.dp
private val MiniControlIconSizeCollapsed = 22.dp
// 两圆之间的圆缘 gap（舞台已改为内容驱动宽度）。
//   图标间距 = gap + 2×圆内边距((size−icon)/2)。展开 (30−18)/2 = 6 ⇒ gap 0dp → 图标间距 12dp。
//   收缩 (36−22)/2 = 7 ⇒ gap 0dp → 图标间距 14dp（收缩态 next 淡出，此值仅过渡期可见）。
//   「三点 ↔ 暂停」= 父 Row spacedBy(10dp) + 圆内边距(6dp) + StartPull(4dp) = 12dp（= 对称）。
private val MiniControlGapExpanded = 0.dp
private val MiniControlGapCollapsed = 0.dp
// 暂停键向左微拉 4dp：把「三点→暂停」从 16dp 收到 12dp，与「暂停→下一首」对齐。
//   ⚠ 只用它压「三点↔暂停」这一侧；**绝不能**用它当「暂停↔下一首」的杠杆（v0.9.7 翻车原因）。
private val MiniControlStartPull = (-4).dp

// ── 播放栏排版（**dp 口径**，单一真相源；2026-09-25 跨机型修作者名裁切）────────
// ⚠ 这三个值是「希望文字**渲染成多少 dp**」，不是 sp。调用点一律用
//   `with(density) { MiniTitleFontDp.dp.toSp() }` 换算（**接收者必须是 `Dp`**，
//   写成 `Float.toSp()` 会命中「把入参当 px」的 `Density.toSp(Float)`，字号缩到
//   1/density —— 详见 `titleFontSize` 处的踩坑说明）—— 直接写 `14.sp` 会随系统
//   fontScale 放大而撑破 30dp 内容预算，导致作者名 descent 被裁（跨机型故障根因，
//   详见 `MeloXIOSMiniPlayer` 里 `titleFontSize` 处的长注释）。
// 预算核对（展开态）：内容区 30dp = 标题行 15dp + 作者行 15dp，两行各 15dp 行高。
private const val MiniTitleFontDp = 13.5f
private const val MiniArtistFontDp = 12f
private const val MiniLineHeightDp = 15f

// 关闭尾段的「下沉 → 回弹」**不再**在此实现：它已移入共享元素 **bounds 几何**
//   （`meloXPlayerSharedBoundsTransform` / `meloXPlayerSinkFraction`，见
//    `MeloXPlayerTransitionSpec`）。原因：morph 期间外壳与封面由两个**独立 overlay**
//   渲染，加在节点上的任何位移都读不出可靠的下沉，只有 bounds 几何是 overlay 唯一
//   服从的输入，且外壳/封面/文字/控件天然同步。

private fun smoothStep(value: Float, start: Float, end: Float): Float {
    if (end <= start) return if (value >= end) 1f else 0f
    val t = ((value - start) / (end - start)).coerceIn(0f, 1f)
    return t * t * (3f - 2f * t)
}

private fun lerpDp(start: Dp, end: Dp, progress: Float): Dp =
    (start.value + (end.value - start.value) * progress.coerceIn(0f, 1f)).dp
