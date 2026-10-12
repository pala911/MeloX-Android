package com.lladlam.melox.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.lladlam.melox.ui.animation.MeloXMotion

/**
 * Chrome-level UI state that a page publishes and the app shell reads.
 *
 * The dock lives in the app shell while the playlist / collection detail pages live deep in
 * the tab content, so a small process-level holder avoids threading a callback through every
 * screen. A holder counter keeps overlapping detail pages stack-safe.
 */
object MeloXChromeRuntime {
    /** True while a playlist / collection detail page is the top-level page. */
    var playlistDetailOpen by mutableStateOf(false)
        private set

    var transitionMillis by mutableStateOf(MeloXMotion.ContentEnterMillis)
        private set

    private var playlistDetailHolders = 0

    fun holdPlaylistDetail(enterMillis: Int) {
        transitionMillis = enterMillis
        playlistDetailHolders += 1
        playlistDetailOpen = true
    }

    fun releasePlaylistDetail(exitMillis: Int) {
        transitionMillis = exitMillis
        playlistDetailHolders = (playlistDetailHolders - 1).coerceAtLeast(0)
        playlistDetailOpen = playlistDetailHolders > 0
    }
}

/**
 * Publishes [open] to [MeloXChromeRuntime] for as long as the calling page is composed, so the
 * bottom dock can slide away while a playlist detail page is on screen.
 */
@Composable
fun PlaylistDetailChromeEffect(
    open: Boolean,
    enterMillis: Int = MeloXMotion.ContentEnterMillis,
    exitMillis: Int = MeloXMotion.ContentExitMillis,
) {
    DisposableEffect(open, enterMillis, exitMillis) {
        if (open) MeloXChromeRuntime.holdPlaylistDetail(enterMillis)
        onDispose { if (open) MeloXChromeRuntime.releasePlaylistDetail(exitMillis) }
    }
}
