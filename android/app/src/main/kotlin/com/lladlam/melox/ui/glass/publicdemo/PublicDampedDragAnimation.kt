package com.lladlam.melox.ui.glass.publicdemo

/* Port of AndroidLiquidGlass' public DampedDragAnimation helper (Apache-2.0). */

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.foundation.MutatorMutex
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.unit.IntSize
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.time.Clock

class PublicDampedDragAnimation(
    private val animationScope: CoroutineScope,
    val initialValue: Float,
    val valueRange: ClosedRange<Float>,
    val visibilityThreshold: Float,
    val initialScale: Float,
    val pressedScale: Float,
    val onDragStarted: PublicDampedDragAnimation.(position: Offset) -> Unit = {},
    val onDragStopped: PublicDampedDragAnimation.() -> Unit = {},
    // ⚠ 带 receiver：onTap 里要用 `value`（水珠当前停靠格）把「水珠局部坐标」换算回
    //   tab 下标 —— 手势挂在 L3 水珠 Box 上，position.x 是水珠局部坐标，不是面板坐标。
    val onTap: PublicDampedDragAnimation.(position: Offset) -> Unit = {},
    val onDrag: PublicDampedDragAnimation.(size: IntSize, dragAmount: Offset) -> Unit,
) {

    private val valueAnimationSpec =
        spring(1f, 1000f, visibilityThreshold)
    // 「呼出」专用：从搜索返回时水珠带可见过冲（Q 弹）地滑到目标格。
    //   临界阻尼那条（上面）保持不动 —— 拖拽跟手/松手回位仍要零过冲。
    private val bouncyValueAnimationSpec =
        spring(0.6f, 380f, visibilityThreshold)
    private val velocityAnimationSpec =
        spring(0.5f, 300f, visibilityThreshold * 10f)
    private val pressProgressAnimationSpec =
        spring(1f, 1000f, 0.001f)
    private val scaleXAnimationSpec =
        spring(0.6f, 250f, 0.001f)
    private val scaleYAnimationSpec =
        spring(0.7f, 250f, 0.001f)

    private val valueAnimation =
        Animatable(initialValue, visibilityThreshold)
    private val velocityAnimation =
        Animatable(0f, 5f)
    private val pressProgressAnimation =
        Animatable(0f, 0.001f)
    private val scaleXAnimation =
        Animatable(initialScale, 0.001f)
    private val scaleYAnimation =
        Animatable(initialScale, 0.001f)

    private val mutatorMutex = MutatorMutex()

    private val velocityTracker = VelocityTracker()
    private var downPosition = Offset.Zero
    private var movedDuringGesture = false

    val value: Float get() = valueAnimation.value
    val progress: Float get() = (value - valueRange.start) / (valueRange.endInclusive - valueRange.start)
    val targetValue: Float get() = valueAnimation.targetValue
    val pressProgress: Float get() = pressProgressAnimation.value
    val scaleX: Float get() = scaleXAnimation.value
    val scaleY: Float get() = scaleYAnimation.value
    val velocity: Float get() = velocityAnimation.value

    val modifier: Modifier = Modifier.pointerInput(Unit) {
        publicInspectDragGestures(
            onDragStart = { down ->
                downPosition = down
                movedDuringGesture = false
                onDragStarted(down)
                press()
            },
            onDragEnd = {
                if (movedDuringGesture) onDragStopped() else onTap(downPosition)
                release()
            },
            onDragCancel = { onDragStopped(); release() },
        ) { change, dragAmount ->
            if (dragAmount != Offset.Zero) movedDuringGesture = true
            onDrag(size, dragAmount)
            change.consume()
        }
    }

    fun press() {
        velocityTracker.resetTracking()
        animationScope.launch {
            launch { pressProgressAnimation.animateTo(1f, pressProgressAnimationSpec) }
            launch { scaleXAnimation.animateTo(pressedScale, scaleXAnimationSpec) }
            launch { scaleYAnimation.animateTo(pressedScale, scaleYAnimationSpec) }
        }
    }

    fun release() {
        animationScope.launch {
            withFrameNanos { }
            if (valueAnimation.value != valueAnimation.targetValue) {
                val threshold = (valueRange.endInclusive - valueRange.start) * 0.025f
                snapshotFlow { valueAnimation.value }
                    .filter { abs(it - valueAnimation.targetValue) < threshold }
                    .first()
            }
            launch { pressProgressAnimation.animateTo(0f, pressProgressAnimationSpec) }
            launch { scaleXAnimation.animateTo(initialScale, scaleXAnimationSpec) }
            launch { scaleYAnimation.animateTo(initialScale, scaleYAnimationSpec) }
        }
    }

    fun updateValue(value: Float) {
        val targetValue = value.coerceIn(valueRange)
        animationScope.launch {
            launch { valueAnimation.animateTo(targetValue, valueAnimationSpec) { updateVelocity() } }
        }
    }

    fun animateToValue(value: Float) {
        animationScope.launch {
            mutatorMutex.mutate {
                press()
                val targetValue = value.coerceIn(valueRange)
                launch { valueAnimation.animateTo(targetValue, valueAnimationSpec) }
                if (velocity != 0f) {
                    launch { velocityAnimation.animateTo(0f, velocityAnimationSpec) }
                }
                release()
            }
        }
    }

    // 与 [animateToValue] 相同，但值弹簧带过冲 —— 只用于「从搜索返回、重新呼出指示器」
    // 这一条路径，让水珠 Q 弹地落格；普通 tab 切换与拖拽松手仍走临界阻尼。
    // ⚠ **不要 press()/release()**：程序化呼出没有手指，press 会把水珠放大到 1.393×，
    //   而水珠采样的是 L2 捕获层的录制缓冲（高仅 navHeight−8dp）——放大后采样区上下
    //   超出缓冲、采不到内容 ⇒ 真机表现为「呼出的水珠上下被裁切、不完整」。
    //   Q 弹只由带过冲的位置弹簧提供，缩放留在真手按压路径（animateToValue/drag）。
    fun animateToValueBouncy(value: Float) {
        animationScope.launch {
            mutatorMutex.mutate {
                val targetValue = value.coerceIn(valueRange)
                launch { valueAnimation.animateTo(targetValue, bouncyValueAnimationSpec) }
                if (velocity != 0f) {
                    launch { velocityAnimation.animateTo(0f, velocityAnimationSpec) }
                }
            }
        }
    }

    private fun updateVelocity() {
        velocityTracker.addPosition(
            Clock.System.now().toEpochMilliseconds(),
            Offset(value, 0f)
        )
        val targetVelocity = velocityTracker.calculateVelocity().x / (valueRange.endInclusive - valueRange.start)
        animationScope.launch { velocityAnimation.animateTo(targetVelocity, velocityAnimationSpec) }
    }
}
