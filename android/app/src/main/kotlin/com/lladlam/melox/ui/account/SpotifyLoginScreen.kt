package com.lladlam.melox.ui.account

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lladlam.melox.R
import com.lladlam.melox.core.network.MeloXHttpClient
import com.lladlam.melox.core.provider.spotify.SpotifyClientConfig
import com.lladlam.melox.core.provider.spotify.SpotifyOAuth
import com.lladlam.melox.core.provider.spotify.SpotifySessionStore
import com.lladlam.melox.core.remoteconfig.MeloXRemoteConfigPolicy
import com.lladlam.melox.ui.glass.MeloXGlassButton
import com.lladlam.melox.ui.glass.MeloXGlassButtonStyle
import com.lladlam.melox.ui.glass.MeloXGlassTextField
import com.lladlam.melox.ui.glass.MeloXSystemColors
import com.lladlam.melox.ui.legal.MeloXLegalLinks
import kotlinx.coroutines.launch

@Composable
fun SpotifyLoginScreen(onDismiss: () -> Unit, onLoggedIn: () -> Unit) {
    val activityContext = LocalContext.current
    val context = activityContext.applicationContext
    if (!MeloXRemoteConfigPolicy.capabilityEnabled(context, "spotify_oauth")) {
        BackHandler(onBack = onDismiss)
        Column(
            Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)
                .statusBarsPadding().padding(horizontal = 20.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            MeloXGlassButton(onClick = onDismiss, style = MeloXGlassButtonStyle.Plain) { Text(stringResource(R.string.action_close)) }
            Text(stringResource(R.string.account_spotify_unavailable), fontSize = 20.sp, fontWeight = FontWeight.Bold)
            Text(
                stringResource(R.string.account_spotify_disabled),
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = .64f),
            )
            MeloXLegalLinks()
        }
        return
    }
    var error by remember { mutableStateOf<String?>(null) }
    var authorizing by remember { mutableStateOf(false) }
    var clientIdInput by remember { mutableStateOf(SpotifyClientConfig.read(context)) }
    var configured by remember { mutableStateOf(SpotifyClientConfig.isConfigured(context)) }
    val scope = rememberCoroutineScope()
    val doneMessage = stringResource(R.string.account_spotify_return)

    BackHandler(onBack = onDismiss)

    Column(
        Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)
            .statusBarsPadding().padding(horizontal = 20.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            MeloXGlassButton(onClick = onDismiss, style = MeloXGlassButtonStyle.Plain) {
                Text(stringResource(R.string.action_cancel), color = MeloXSystemColors.Red)
            }
            Text(stringResource(R.string.account_login_spotify), fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier)
        }
        Text(
            stringResource(R.string.account_spotify_hint),
            color = MaterialTheme.colorScheme.onBackground.copy(alpha = .62f),
            fontSize = 13.sp,
            lineHeight = 19.sp,
        )
        if (!configured) {
            Text(
                stringResource(R.string.account_spotify_client_hint, SpotifyOAuth.RedirectUri),
                color = MaterialTheme.colorScheme.onBackground.copy(alpha = .62f),
                fontSize = 13.sp,
                lineHeight = 19.sp,
            )
            MeloXGlassTextField(
                value = clientIdInput,
                onValueChange = { clientIdInput = it },
                modifier = Modifier.fillMaxWidth(),
                placeholder = { Text(stringResource(R.string.account_spotify_client_placeholder), maxLines = 1) },
                singleLine = true,
            )
            if (clientIdInput.isNotBlank()) {
                MeloXGlassButton(
                    onClick = {
                        SpotifyClientConfig.write(context, clientIdInput)
                        configured = true
                    },
                    modifier = Modifier.fillMaxWidth(),
                    style = MeloXGlassButtonStyle.Plain,
                ) { Text(stringResource(R.string.account_save_client_id)) }
            }
        }
        error?.let { Text(it, color = MaterialTheme.colorScheme.error, fontSize = 13.sp) }
        // 上游 0.6.2（22ebcb3）：改走 loopback 授权，本地起一个回环端口接回调，
        // 不再依赖 SpotifyCallbackActivity。
        MeloXGlassButton(
            onClick = {
                if (authorizing) return@MeloXGlassButton
                authorizing = true
                error = null
                scope.launch {
                    runCatching {
                        SpotifyOAuth(context, SpotifyClientConfig.effective(context), MeloXHttpClient.shared)
                            .authorize(doneMessage)
                    }.onSuccess {
                        authorizing = false
                        onLoggedIn()
                    }.onFailure {
                        authorizing = false
                        error = it.message ?: activityContext.getString(R.string.account_spotify_auth_failed)
                    }
                }
            },
            modifier = Modifier.fillMaxWidth(),
            enabled = configured && !authorizing,
            style = MeloXGlassButtonStyle.BorderedProminent,
        ) { Text(stringResource(if (authorizing) R.string.account_spotify_waiting else R.string.account_spotify_browser)) }
        Spacer(Modifier.weight(1f))
        MeloXLegalLinks(tint = androidx.compose.ui.graphics.Color(0xFF1DB954))
    }
}
