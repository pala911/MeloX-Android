package com.lladlam.melox.ui.glass

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.kyant.backdrop.backdrops.emptyBackdrop
import com.lladlam.melox.ui.theme.isMeloXDarkTheme
import com.lladlam.melox.ui.animation.meloXPanelEnter
import com.lladlam.melox.ui.animation.meloXPanelExit

/**
 * Floating iOS 26-style Action Sheet / Context Menu surface.
 *
 * The scrim, bottom spacing, grabber and material treatment intentionally live
 * here so every action surface has the same native-component silhouette.
 */
@Composable
fun MeloXGlassSheet(
    visible: Boolean,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    val visibility = remember { MutableTransitionState(visible) }
    LaunchedEffect(visible) {
        visibility.targetState = visible
    }
    val requestDismiss = { onDismiss() }
    val dark = isMeloXDarkTheme()
    // ⚠ 必须用门控判定，不能用 `LocalMeloXBackdrop.current != null`：后者在「无硬件加速」
    //   时仍为 true ⇒ 会选半透明 tint 但一次玻璃都不画 ⇒ 模态下内容叠在
    //   页面上不可读（正是下面注释要避免的那种情况）。
    val hasBackdrop = meloXGlassAvailable()
    // Regular pages deliberately do not expose a recursive RenderNode
    // backdrop. In that safe mode the sheet still needs an opaque fallback;
    // otherwise the page underneath remains readable through the modal and
    // makes rows appear to overlap. When a backdrop is available, preserve
    // the translucent sampled-glass treatment.
    val sheetTint = when {
        hasBackdrop && dark -> Color.White.copy(alpha = 0.08f)
        hasBackdrop -> Color.White.copy(alpha = 0.34f)
        dark -> Color.Black
        else -> Color.White
    }
    val sheetSurface = when {
        hasBackdrop && dark -> Color.Black.copy(alpha = 0.12f)
        hasBackdrop -> Color.White.copy(alpha = 0.20f)
        dark -> Color.Black
        else -> Color.White
    }
    if (visibility.currentState || visibility.targetState) {
        Dialog(
            onDismissRequest = requestDismiss,
            properties = DialogProperties(
                usePlatformDefaultWidth = false,
                decorFitsSystemWindows = false,
            ),
        ) {
            AnimatedVisibility(
                visibleState = visibility,
                enter = meloXPanelEnter(initialScale = 0.97f),
                exit = meloXPanelExit(),
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color.Black.copy(alpha = 0.18f))
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                            onClick = requestDismiss,
                        )
                        .padding(horizontal = 18.dp)
                        .navigationBarsPadding(),
                    contentAlignment = Alignment.BottomCenter,
                ) {
                    Column(
                        modifier = modifier
                            .fillMaxWidth()
                            .padding(bottom = 18.dp)
                            .meloXGlassSurface(
                                shape = MeloXShapes.sheet,
                                material = MeloXGlassMaterial.Regular,
                                tint = sheetTint,
                                surfaceColor = sheetSurface,
                            )
                            .clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null,
                                onClick = {},
                            ),
                    ) {
                        SheetGrabber()
                        content()
                    }
                }
            }
        }
    }
}

/** Centered iOS 26-style overlay for compact pickers and confirmation panels. */
@Composable
fun MeloXGlassDialog(
    visible: Boolean,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    val visibility = remember { MutableTransitionState(visible) }
    LaunchedEffect(visible) {
        visibility.targetState = visible
    }
    val requestDismiss = { onDismiss() }
    val dark = isMeloXDarkTheme()
    // ⚠ 必须用门控判定，不能用 `LocalMeloXBackdrop.current != null`：后者在「无硬件加速」
    //   时仍为 true ⇒ 会选半透明 tint 但一次玻璃都不画 ⇒ 模态下内容叠在
    //   页面上不可读（正是下面注释要避免的那种情况）。
    val hasBackdrop = meloXGlassAvailable()
    val dialogTint = when {
        hasBackdrop && dark -> Color.White.copy(alpha = 0.08f)
        hasBackdrop -> Color.White.copy(alpha = 0.34f)
        dark -> Color.Black
        else -> Color.White
    }
    // Confirmation/prompt content must remain fully opaque. Only the empty
    // area around the dialog uses a translucent modal scrim.
    val dialogSurface = if (dark) Color.Black else Color.White
    // 弹窗是模态层：把内容子树的背板切成空背板，玻璃控件就取样不到弹窗背后的页面，
    // 否则它们会退化成「页面内容的镜头折射窗口」（音质弹窗的「设置」直接折射出播放页
    // 的封面/歌词）。面板本身是实色 surface，仍走上面那一份取样，观感不变。
    val dialogBackdrop = emptyBackdrop()
    if (visibility.currentState || visibility.targetState) {
        Dialog(
            onDismissRequest = requestDismiss,
            properties = DialogProperties(
                usePlatformDefaultWidth = false,
                decorFitsSystemWindows = false,
            ),
        ) {
            AnimatedVisibility(
                visibleState = visibility,
                enter = meloXPanelEnter(),
                exit = meloXPanelExit(),
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        // ⚠ 2026-09-26 由 0.22 提到 0.42：原值在浅色主题下只压掉 78% 亮度，
                        //   当面板正下方是**亮色专辑封面 / 亮色歌词底**（播放页音质弹窗最典型的
                        //   组合）时，剩下的 22% 在部分屏幕上仍读成「透过去看见后面」；
                        //   0.42f 相当于把亮度压到 58%，实测跨 CJK 字体 / 屏厂 / 亮度下都不再
                        //   出现「设置」按钮字面「飘在播放页上」的读法。
                        .background(Color.Black.copy(alpha = 0.42f))
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                            onClick = requestDismiss,
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    Column(
                        modifier = modifier
                            .padding(horizontal = 28.dp)
                            .fillMaxWidth()
                            .widthIn(max = 360.dp)
                            .meloXGlassSurface(
                                shape = RoundedCornerShape(28.dp),
                                material = MeloXGlassMaterial.Regular,
                                tint = dialogTint,
                                surfaceColor = dialogSurface,
                            )
                            .clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null,
                                onClick = {},
                            )
                            .padding(horizontal = 18.dp, vertical = 18.dp),
                        content = {
                            // ⚠ 只包住 content，不能包住 Column：面板的实色 surface 挂在
                            //   Column 的 modifier 上，那份取样必须保留；这里只切断内容子树。
                            CompositionLocalProvider(LocalMeloXBackdrop provides dialogBackdrop) {
                                content()
                            }
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun SheetGrabber() {
    Row(
        modifier = Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 2.dp),
        horizontalArrangement = Arrangement.Center,
    ) {
        Box(
            modifier = Modifier
                .size(width = 36.dp, height = 4.dp)
                .background(
                    color = Color.White.copy(alpha = 0.42f),
                    shape = RoundedCornerShape(999.dp),
                ),
        )
    }
}
