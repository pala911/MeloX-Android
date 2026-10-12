package com.lladlam.melox.ui.library

import android.content.Context
import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lladlam.melox.R
import com.lladlam.melox.core.account.NeteaseSessionStore
import com.lladlam.melox.core.library.NeteaseLibraryClient
import com.lladlam.melox.core.library.NeteasePlaylistSummary
import com.lladlam.melox.core.network.NeteaseMusicOperationsClient
import com.lladlam.melox.core.network.NeteaseSearchClient
import com.lladlam.melox.ui.glass.MeloXIosGroupedList
import com.lladlam.melox.ui.glass.MeloXIosListRow
import com.lladlam.melox.ui.glass.MeloXSymbol
import com.lladlam.melox.ui.glass.MeloXSymbolIcon
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import androidx.compose.ui.res.stringResource

internal enum class MeloXPlaylistSortMode(val titleRes: Int) {
    Original(R.string.playlist_sort_original),
    Title(R.string.playlist_sort_title),
    Artist(R.string.playlist_sort_artist),
    Album(R.string.playlist_sort_album),
}

@Composable
internal fun MeloXPlaylistActionsOverlay(
    playlist: NeteasePlaylistSummary,
    visible: Boolean,
    onDismiss: () -> Unit,
    onRefresh: () -> Unit,
    onBatchDownload: () -> Unit,
    onAnalyzePlaylist: () -> Unit,
    sortMode: MeloXPlaylistSortMode = MeloXPlaylistSortMode.Original,
    onSortModeChanged: (MeloXPlaylistSortMode) -> Unit = {},
) {
    val context = LocalContext.current
    val app = context.applicationContext
    val scope = rememberCoroutineScope()
    val client = remember(app) { NeteaseLibraryClient(cookieProvider = { NeteaseSessionStore.readCookie(app) }) }
    val operations = remember(app) { NeteaseMusicOperationsClient(cookieProvider = { NeteaseSessionStore.readCookie(app) }) }
    val account = remember(app) { NeteaseSearchClient(cookieProvider = { NeteaseSessionStore.readCookie(app) }) }
    var subscribed by remember(playlist.id, visible) { mutableStateOf<Boolean?>(null) }
    var busy by remember(playlist.id, visible) { mutableStateOf(false) }
    var message by remember(playlist.id, visible) { mutableStateOf<String?>(null) }
    var sortMenuVisible by remember(playlist.id, visible) { mutableStateOf(false) }

    LaunchedEffect(visible, playlist.id) {
        if (!visible) return@LaunchedEffect
        runCatching {
            val profile = account.accountProfile()
            withContext(Dispatchers.IO) { client.userPlaylistsBlocking(profile.userId) }.any { it.id == playlist.id }
        }.onSuccess { subscribed = it }
    }
    BackHandler(enabled = visible, onBack = onDismiss)

    if (visible) ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = MaterialTheme.colorScheme.surface,
        contentColor = MaterialTheme.colorScheme.onSurface,
        shape = RoundedCornerShape(topStart = 38.dp, topEnd = 38.dp),
        dragHandle = {
            Box(Modifier.fillMaxWidth().height(18.dp), contentAlignment = Alignment.Center) {
                Box(Modifier.size(width = 58.dp, height = 4.dp).background(MaterialTheme.colorScheme.onSurface.copy(alpha = .24f), RoundedCornerShape(99.dp)))
            }
        },
    ) {
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).navigationBarsPadding().padding(horizontal = 18.dp, vertical = 18.dp),
        ) {
            Text(stringResource(R.string.playlist_actions), color = MaterialTheme.colorScheme.onSurface.copy(alpha = .58f), fontSize = 13.sp)
            Text(playlist.name, color = MaterialTheme.colorScheme.onSurface, fontSize = 20.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 3.dp, bottom = 10.dp))
            message?.let { Text(it, color = MaterialTheme.colorScheme.error, fontSize = 12.sp, modifier = Modifier.padding(bottom = 6.dp)) }
            MeloXIosGroupedList(surfaceColor = MaterialTheme.colorScheme.surfaceContainerHigh) {
                PlaylistActionRow(stringResource(R.string.playlist_share), MeloXSymbol.Share, false) { sharePlaylist(context, playlist); onDismiss() }
                PlaylistActionRow(stringResource(R.string.playlist_batch_download), MeloXSymbol.Download, true) { onDismiss(); onBatchDownload() }
                PlaylistActionRow(stringResource(R.string.playlist_analyze), MeloXSymbol.Refresh, true) { onDismiss(); onAnalyzePlaylist() }
                PlaylistActionRow(stringResource(if (subscribed == true) R.string.playlist_unsubscribe else R.string.playlist_subscribe), if (subscribed == true) MeloXSymbol.Check else MeloXSymbol.Plus, true) {
                    if (busy) return@PlaylistActionRow
                    val desired = subscribed != true
                    busy = true
                    scope.launch {
                        try {
                            // 超时保护 + finally 复位（2026-10-09）：此前 busy 无 finally，
                            // eapi 挂起时永久 true ⇒ 之后点不动。
                            withTimeout(15_000) { operations.setPlaylistSubscribed(playlist.id, desired) }
                            subscribed = desired
                        } catch (error: Exception) {
                            message = if (error is TimeoutCancellationException) "操作超时，请重试"
                            else error.message ?: "订阅操作失败"
                        } finally {
                            busy = false
                        }
                    }
                }
                PlaylistActionRow(stringResource(R.string.action_refresh), MeloXSymbol.Refresh, true) { onRefresh(); onDismiss() }
                PlaylistActionRow(stringResource(R.string.playlist_sort, stringResource(sortMode.titleRes)), MeloXSymbol.ArrowDown, true) { sortMenuVisible = true }
            }
            if (busy) Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.size(18.dp), color = MaterialTheme.colorScheme.primary, strokeWidth = 2.dp)
                Spacer(Modifier.size(10.dp))
                Text(stringResource(R.string.playlist_working), color = MaterialTheme.colorScheme.onSurface.copy(alpha = .6f))
            }
            DropdownMenu(expanded = sortMenuVisible, onDismissRequest = { sortMenuVisible = false }) {
                MeloXPlaylistSortMode.entries.forEach { candidate ->
                    DropdownMenuItem(
                        text = { Text(stringResource(candidate.titleRes)) },
                        onClick = {
                            onSortModeChanged(candidate)
                            sortMenuVisible = false
                            onDismiss()
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun PlaylistActionRow(title: String, symbol: MeloXSymbol, separator: Boolean, onClick: () -> Unit) {
    MeloXIosListRow(
        title = title,
        leading = { MeloXSymbolIcon(symbol, Modifier.size(22.dp), MaterialTheme.colorScheme.onSurface, iconSize = 21.sp) },
        showTopSeparator = separator,
        onClick = onClick,
    )
}

private fun sharePlaylist(context: Context, playlist: NeteasePlaylistSummary) {
    runCatching {
        context.startActivity(
            Intent.createChooser(
                Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, "${playlist.name}\nhttps://music.163.com/playlist?id=${playlist.id}"),
                context.getString(R.string.library_share_playlist),
            ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }
}

@Composable
internal fun MeloXProviderPlaylistSyncSheet(
    title: String,
    trackName: String?,
    canRename: Boolean,
    canDelete: Boolean,
    canRemove: Boolean,
    canMoveUp: Boolean,
    canMoveDown: Boolean,
    busy: Boolean,
    error: String?,
    onRename: (String) -> Unit,
    onDelete: () -> Unit,
    onRemove: () -> Unit,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
    onDismiss: () -> Unit,
) {
    var name by remember(title) { mutableStateOf(title) }
    BackHandler(onBack = onDismiss)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = MaterialTheme.colorScheme.surface,
        contentColor = MaterialTheme.colorScheme.onSurface,
        shape = RoundedCornerShape(topStart = 38.dp, topEnd = 38.dp),
    ) {
        Column(
            Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 18.dp, vertical = 18.dp),
        ) {
            Text(
                trackName ?: title,
                color = MaterialTheme.colorScheme.onSurface,
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold,
            )
            error?.let { message ->
                Text(
                    message,
                    color = MaterialTheme.colorScheme.error,
                    fontSize = 12.sp,
                    modifier = Modifier.padding(top = 6.dp),
                )
            }
            if (canRename) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                    singleLine = true,
                    label = { Text(stringResource(R.string.player_playlist_name)) },
                )
            }
            Spacer(Modifier.height(12.dp))
            MeloXIosGroupedList(surfaceColor = MaterialTheme.colorScheme.surfaceContainerHigh) {
                if (canRename) {
                    PlaylistActionRow(stringResource(R.string.player_rename_playlist), MeloXSymbol.Check, false) {
                        if (!busy && name.isNotBlank()) onRename(name.trim())
                    }
                }
                if (canDelete) {
                    PlaylistActionRow(stringResource(R.string.player_delete_playlist), MeloXSymbol.Trash, false) {
                        if (!busy) onDelete()
                    }
                }
                if (canMoveUp) {
                    PlaylistActionRow(stringResource(R.string.player_move_up), MeloXSymbol.ArrowDown, false) {
                        if (!busy) onMoveUp()
                    }
                }
                if (canMoveDown) {
                    PlaylistActionRow(stringResource(R.string.player_move_down), MeloXSymbol.ArrowDown, false) {
                        if (!busy) onMoveDown()
                    }
                }
                if (canRemove) {
                    PlaylistActionRow(stringResource(R.string.player_remove_from_playlist), MeloXSymbol.Trash, false) {
                        if (!busy) onRemove()
                    }
                }
            }
            if (busy) {
                CircularProgressIndicator(
                    Modifier.padding(top = 12.dp).size(18.dp),
                    color = MaterialTheme.colorScheme.primary,
                    strokeWidth = 2.dp,
                )
            }
        }
    }
}
