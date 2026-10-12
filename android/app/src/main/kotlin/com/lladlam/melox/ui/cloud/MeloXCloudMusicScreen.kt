package com.lladlam.melox.ui.cloud

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import com.lladlam.melox.R
import com.lladlam.melox.core.account.NeteaseSessionStore
import com.lladlam.melox.core.network.MeloXCloudSong
import com.lladlam.melox.core.network.NeteaseUniversalSearchClient
import com.lladlam.melox.playback.PlaybackCommands
import com.lladlam.melox.ui.MeloXBottomContentClearance
import com.lladlam.melox.ui.glass.meloXLiquidButton
import com.lladlam.melox.ui.glass.MeloXIosTopBar
import com.lladlam.melox.ui.glass.MeloXShapes
import com.lladlam.melox.ui.glass.MeloXGlassButton
import com.lladlam.melox.ui.glass.MeloXGlassButtonStyle
import com.lladlam.melox.ui.glass.MeloXGlassDialog
import com.lladlam.melox.ui.glass.MeloXTypography
import com.lladlam.melox.ui.glass.meloXContentSurface
import com.lladlam.melox.ui.meloXEdgeOverscrollHaptic
import kotlinx.coroutines.launch

@Composable
fun MeloXCloudMusicScreen(
    modifier: Modifier = Modifier,
    embedded: Boolean = false,
) {
    val context = LocalContext.current
    val app = context.applicationContext
    val client = remember(app) {
        NeteaseUniversalSearchClient(cookieProvider = { NeteaseSessionStore.readCookie(app) })
    }
    val scope = rememberCoroutineScope()
    var values by remember { mutableStateOf<List<MeloXCloudSong>>(emptyList()) }
    var query by remember { mutableStateOf("") }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var quota by remember { mutableStateOf("") }
    var pendingDelete by remember { mutableStateOf<MeloXCloudSong?>(null) }
    var uploading by remember { mutableStateOf(false) }

    suspend fun refresh() {
        loading = true
        error = null
        runCatching {
            val first = client.cloudSongs()
            if (!first.hasMore) first else {
                var page = first
                val all = first.values.toMutableList()
                var offset = all.size
                while (page.hasMore && offset < first.totalCount) {
                    page = client.cloudSongs(offset = offset)
                    if (page.values.isEmpty()) break
                    all += page.values
                    offset += page.values.size
                }
                first.copy(values = all.distinctBy(MeloXCloudSong::id), hasMore = page.hasMore)
            }
        }
            .onSuccess { page ->
                values = page.values
                quota = if (page.maxBytes > 0L) {
                    app.getString(R.string.cloud_song_count, formatBytes(page.usedBytes), formatBytes(page.maxBytes), page.totalCount)
                } else app.getString(R.string.cloud_song_total, page.totalCount)
            }
            .onFailure { error = it.message ?: app.getString(R.string.cloud_load_failed) }
        loading = false
    }

    val uploadLauncher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        scope.launch {
            uploading = true
            error = null
            runCatching { client.uploadCloudSong(app, uri) }
                .onSuccess { refresh() }
                .onFailure { error = it.message ?: app.getString(R.string.cloud_upload_failed) }
            uploading = false
        }
    }

    LaunchedEffect(Unit) { refresh() }

    pendingDelete?.let { target ->
        MeloXGlassDialog(
            visible = true,
            onDismiss = { pendingDelete = null },
        ) {
            Text(stringResource(R.string.cloud_delete_title), style = MeloXTypography.title2)
            Text(
                stringResource(R.string.cloud_delete_body, target.song.name),
                modifier = Modifier.padding(top = 8.dp),
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = .62f),
            )
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 18.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                MeloXGlassButton(
                    onClick = { pendingDelete = null },
                    modifier = Modifier.weight(1f),
                    style = MeloXGlassButtonStyle.Plain,
                ) { Text(stringResource(R.string.action_cancel)) }
                MeloXGlassButton(
                    onClick = {
                    pendingDelete = null
                    scope.launch {
                        runCatching { client.deleteCloudSong(target.id) }
                            .onSuccess { values = values.filterNot { it.id == target.id } }
                            .onFailure { error = it.message ?: app.getString(R.string.cloud_delete_failed) }
                    }
                    },
                    modifier = Modifier.weight(1f),
                    style = MeloXGlassButtonStyle.Destructive,
                ) { Text(stringResource(R.string.provider_delete)) }
            }
        }
    }

    val displayed = remember(values, query) {
        val normalized = query.trim()
        if (normalized.isBlank()) values else values.filter {
            listOf(it.song.name, it.song.artists, it.song.album).any { text ->
                text.contains(normalized, ignoreCase = true)
            }
        }
    }

    Column(if (embedded) modifier.fillMaxWidth() else modifier.fillMaxSize()) {
        if (embedded) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 2.dp),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    if (uploading) stringResource(R.string.cloud_uploading) else stringResource(R.string.cloud_upload),
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.clickable(enabled = !uploading) { uploadLauncher.launch("audio/*") }.padding(10.dp),
                )
                Text(stringResource(R.string.action_refresh), color = MaterialTheme.colorScheme.primary, modifier = Modifier.clickable { scope.launch { refresh() } }.padding(10.dp))
            }
        } else {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    MeloXIosTopBar(
                        title = stringResource(R.string.cloud_title),
                        subtitle = quota,
                        modifier = Modifier.padding(horizontal = 0.dp),
                        contentPadding = PaddingValues(horizontal = 0.dp),
                    )
                }
                Text(
                    if (uploading) stringResource(R.string.cloud_uploading) else stringResource(R.string.cloud_upload),
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.clickable(enabled = !uploading) { uploadLauncher.launch("audio/*") }.padding(10.dp),
                )
                Text(stringResource(R.string.action_refresh), color = MaterialTheme.colorScheme.primary, modifier = Modifier.clickable { scope.launch { refresh() } }.padding(10.dp))
            }
        }
        Box(
            Modifier.padding(horizontal = 18.dp).fillMaxWidth().meloXLiquidButton(
                shape = RoundedCornerShape(22.dp),
                surfaceColor = MaterialTheme.colorScheme.onBackground.copy(alpha = .055f),
            ).padding(horizontal = 14.dp, vertical = 11.dp),
        ) {
            if (query.isBlank()) Text(stringResource(R.string.cloud_search), color = MaterialTheme.colorScheme.onSurface.copy(alpha = .4f))
            BasicTextField(query, { query = it }, singleLine = true, modifier = Modifier.fillMaxWidth())
        }
        val cloudListState = rememberLazyListState()
        when {
            loading && values.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            error != null && values.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(error.orEmpty(), color = MaterialTheme.colorScheme.error)
                    Text(
                        stringResource(R.string.account_retry),
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(top = 12.dp).clickable { scope.launch { refresh() } },
                    )
                }
            }
            else -> LazyColumn(
                state = cloudListState,
                // 触顶/触底橡胶回弹触感。
                modifier = Modifier.meloXEdgeOverscrollHaptic(cloudListState),
                contentPadding = PaddingValues(start = 18.dp, end = 18.dp, top = 14.dp, bottom = MeloXBottomContentClearance),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(displayed, key = { it.id }) { item ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .meloXContentSurface(
                                shape = MeloXShapes.card,
                                surfaceColor = MaterialTheme.colorScheme.onBackground.copy(alpha = .035f),
                            )
                            .clickable {
                            PlaybackCommands.playQueue(context, displayed.map(MeloXCloudSong::song), item.song.id)
                        }
                            .padding(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        AsyncImage(item.song.artworkUrl, null, contentScale = ContentScale.Crop, modifier = Modifier.size(52.dp).clip(RoundedCornerShape(8.dp)))
                        Spacer(Modifier.size(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(item.song.name, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.SemiBold)
                            Text("${item.song.artists} · ${formatBytes(item.fileSize)}", maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = .5f))
                        }
                        Text(stringResource(R.string.provider_delete), color = MaterialTheme.colorScheme.error, fontSize = 12.sp, modifier = Modifier.clickable { pendingDelete = item }.padding(10.dp))
                    }
                }
                if (displayed.isEmpty()) item { Text(stringResource(R.string.cloud_empty), modifier = Modifier.fillMaxWidth().padding(36.dp), color = MaterialTheme.colorScheme.onSurface.copy(alpha = .5f)) }
                error?.let { item { Text(it, color = MaterialTheme.colorScheme.error, fontSize = 12.sp) } }
            }
        }
    }
}

private fun formatBytes(bytes: Long): String = when {
    bytes >= 1_073_741_824L -> "%.2f GB".format(bytes / 1_073_741_824.0)
    bytes >= 1_048_576L -> "%.1f MB".format(bytes / 1_048_576.0)
    bytes >= 1_024L -> "%.1f KB".format(bytes / 1_024.0)
    else -> "$bytes B"
}
