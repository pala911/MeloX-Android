package com.lladlam.melox.ui.recognition

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.lladlam.melox.ui.glass.MeloXIosTopBar
import com.lladlam.melox.ui.glass.MeloXSymbol
import com.lladlam.melox.ui.glass.MeloXSymbolIcon
import com.lladlam.melox.ui.settings.MeloXRecognitionPanel

/**
 * 全屏「听歌识曲」弹层。
 *
 * 挂在 MeloXApp 顶层（与私信同一层），由 [onBack] 关闭 —— **不改动当前 tab**，
 * 所以从哪个页面点开就退回哪个页面。识别本体与设置页共用 [MeloXRecognitionPanel]，
 * 两处永远同一套状态与样式。
 */
@Composable
fun MeloXRecognitionOverlay(onBack: () -> Unit) {
    val context = LocalContext.current.applicationContext
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .statusBarsPadding(),
    ) {
        MeloXIosTopBar(
            title = "听歌识曲",
            contentPadding = PaddingValues(horizontal = 12.dp),
            navigation = {
                MeloXSymbolIcon(
                    symbol = MeloXSymbol.Xmark,
                    modifier = Modifier
                        .size(22.dp)
                        .clickable(role = Role.Button, onClick = onBack),
                    color = MaterialTheme.colorScheme.onBackground,
                    contentDescription = "关闭听歌识曲",
                )
            },
        )
        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp),
        ) {
            MeloXRecognitionPanel(context)
            Spacer(Modifier.height(48.dp))
        }
    }
}
