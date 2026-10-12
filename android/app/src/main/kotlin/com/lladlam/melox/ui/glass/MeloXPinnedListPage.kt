package com.lladlam.melox.ui.glass

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kyant.backdrop.backdrops.layerBackdrop
import com.lladlam.melox.ui.layout.rememberMeloXStableStatusBarHeight
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import com.kyant.backdrop.drawPlainBackdrop
import com.lladlam.melox.ui.meloXEdgeOverscrollHaptic
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.runtimeShaderEffect

private const val TopFadeShader = """
uniform shader content;
uniform float2 size;
layout(color) uniform half4 tint;
uniform float tintIntensity;
half4 main(float2 coord) {
    float mask = smoothstep(size.y, size.y * 0.40, coord.y);
    return mix(content.eval(coord) * mask, tint * mask, tintIntensity);
}
"""

@Composable
fun MeloXPinnedListPage(
    title: String,
    onNavigateBack: () -> Unit,
    bottomPadding: Dp,
    modifier: Modifier = Modifier,
    listState: LazyListState = rememberLazyListState(),
    subtitle: String? = null,
    horizontalPadding: Dp = 20.dp,
    verticalArrangement: Arrangement.Vertical = Arrangement.spacedBy(10.dp),
    actions: (@Composable RowScope.() -> Unit)? = null,
    content: LazyListScope.() -> Unit,
) {
    val collapseDistancePx = with(LocalDensity.current) { 56.dp.toPx() }
    val collapseProgress by remember(listState, collapseDistancePx) {
        derivedStateOf {
            if (listState.firstVisibleItemIndex > 0) 1f
            else (listState.firstVisibleItemScrollOffset / collapseDistancePx).coerceIn(0f, 1f)
        }
    }
    val pageBackdrop = rememberLayerBackdrop()
    val background = MaterialTheme.colorScheme.background
    // ⚠ 用**稳定**值（不随系统栏隐/显变化）。手动读 `WindowInsets.statusBars.asPaddingValues()`
    //   **不受** `Modifier.consumeWindowInsets` 约束：沉浸 `hide()` 时归 0、退出 `show()` 时弹回
    //   真实高度 ⇒ 内容顶部 padding 跳变 =「退出时页面下移」。见 rememberMeloXStableStatusBarHeight。
    val statusBarHeight = rememberMeloXStableStatusBarHeight()
    val toolbarHeight = statusBarHeight + 62.dp

    Box(modifier.fillMaxSize().background(background)) {
        Box(Modifier.fillMaxSize().layerBackdrop(pageBackdrop)) {
            LazyColumn(
                state = listState,
                // 触顶/触底橡胶回弹触感：列表到头、手指继续推时给一下轻 tick。
                modifier = Modifier.fillMaxSize().meloXEdgeOverscrollHaptic(listState),
                contentPadding = PaddingValues(
                    start = horizontalPadding,
                    top = toolbarHeight + 12.dp,
                    end = horizontalPadding,
                    bottom = bottomPadding,
                ),
                verticalArrangement = verticalArrangement,
            ) {
                item(key = "large-title:$title") {
                    Text(
                        title,
                        modifier = Modifier
                            .fillMaxWidth()
                            .graphicsLayer {
                                alpha = 1f - collapseProgress
                                val scale = 1f - .04f * collapseProgress
                                scaleX = scale
                                scaleY = scale
                                transformOrigin = TransformOrigin(0f, .5f)
                            }
                            .blur(6.dp * collapseProgress)
                            .offset(y = (-8).dp)
                            .padding(vertical = 8.dp),
                        style = MeloXTypography.largeTitle,
                        fontWeight = FontWeight.Bold,
                    )
                }
                content()
            }
        }
        Box(
            Modifier
                .fillMaxWidth()
                .height(toolbarHeight + 38.dp)
                .align(Alignment.TopCenter)
                .drawPlainBackdrop(
                    backdrop = pageBackdrop,
                    shape = { RectangleShape },
                    effects = {
                        blur(10.dp.toPx())
                        runtimeShaderEffect("MeloXTopFade", TopFadeShader, "content") {
                            setFloatUniform("size", size.width, size.height)
                            setColorUniform("tint", background)
                            setFloatUniform("tintIntensity", .76f)
                        }
                    },
                ),
        )
        CompositionLocalProvider(LocalMeloXBackdrop provides pageBackdrop) {
            Row(
                // ⚠ 与内容 padding 用**同一个**稳定值（不是 `statusBarsPadding()`），
                //   否则工具栏吃可见值、内容吃稳定值 ⇒ 沉浸切换时两者错位、工具栏上下跳。
                Modifier.fillMaxWidth().padding(top = statusBarHeight).height(62.dp).padding(horizontal = 20.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                MeloXGlassIconButton(MeloXSymbol.ChevronLeft, onNavigateBack, contentDescription = "返回")
                Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        title,
                        modifier = Modifier.graphicsLayer {
                            alpha = collapseProgress
                            val scale = .92f + .08f * collapseProgress
                            scaleX = scale
                            scaleY = scale
                        }.blur(8.dp * (1f - collapseProgress)),
                        fontSize = 17.sp,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        color = MaterialTheme.colorScheme.onBackground,
                    )
                    subtitle?.takeIf(String::isNotBlank)?.let {
                        Text(
                            it,
                            modifier = Modifier.graphicsLayer { alpha = collapseProgress },
                            fontSize = 11.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            color = MaterialTheme.colorScheme.onBackground.copy(alpha = .56f),
                        )
                    }
                }
                if (actions == null) {
                    Spacer(Modifier.size(44.dp))
                } else {
                    Row(content = actions)
                }
            }
        }
    }
}
