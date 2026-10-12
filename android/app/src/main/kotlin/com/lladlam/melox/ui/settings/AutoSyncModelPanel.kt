package com.lladlam.melox.ui.settings

import android.content.Context
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lladlam.melox.R
import com.lladlam.melox.core.autosync.AutoSyncModelRepository
import com.lladlam.melox.core.autosync.AutoSyncModelStore
import com.lladlam.melox.core.network.MeloXGitHubRouting
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * Cloud model management for AutoSync, inside「测试功能尝鲜」.
 *
 * Nothing is downloaded until the user confirms a specific language, and every
 * byte goes through the same GitHub mirror routing the app update check uses.
 * A download in flight is cancelled when the page goes away.
 */
@Composable
internal fun AutoSyncModelPanel(context: Context) {
    val scope = rememberCoroutineScope()
    val repository = remember(context) {
        AutoSyncModelRepository(context.applicationContext, MeloXGitHubRouting(context.applicationContext))
    }
    var index by remember { mutableStateOf<AutoSyncModelRepository.Index?>(null) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var installed by remember { mutableStateOf(AutoSyncModelStore.installedLanguages(context)) }
    var busyLanguage by remember { mutableStateOf<String?>(null) }
    var progress by remember { mutableFloatStateOf(0f) }
    var downloadJob by remember { mutableStateOf<Job?>(null) }
    var confirmLanguage by remember { mutableStateOf<String?>(null) }

    DisposableEffect(Unit) { onDispose { downloadJob?.cancel() } }

    fun refresh() {
        loading = true
        error = null
        scope.launch {
            repository.fetchIndex()
                .onSuccess { index = it; loading = false }
                .onFailure { error = it.message ?: it::class.java.simpleName; loading = false }
        }
    }

    LaunchedEffect(repository) { refresh() }

    Column(Modifier.fillMaxWidth()) {
        Text(
            stringResource(R.string.autosync_models_title),
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            fontWeight = FontWeight.SemiBold,
            fontSize = 15.sp,
        )
        val packs = index?.packs?.values?.toList().orEmpty()
        when {
            loading -> HintText(stringResource(R.string.autosync_models_loading))
            error != null -> {
                HintText(stringResource(R.string.autosync_model_failed, error.orEmpty()))
                PanelButton(stringResource(R.string.autosync_models_retry)) { refresh() }
            }
            else -> packs.forEach { pack ->
                ModelRow(
                    pack = pack,
                    installed = pack.language in installed,
                    busy = busyLanguage == pack.language,
                    confirming = confirmLanguage == pack.language,
                    progress = progress,
                    onDownload = { confirmLanguage = pack.language },
                    onCancelConfirm = { confirmLanguage = null },
                    onConfirm = {
                        confirmLanguage = null
                        val snapshot = index ?: return@ModelRow
                        busyLanguage = pack.language
                        progress = 0f
                        downloadJob = scope.launch {
                            repository.install(pack, snapshot) { fraction -> progress = fraction }
                                .onSuccess { installed = AutoSyncModelStore.installedLanguages(context) }
                                .onFailure { error = it.message }
                            busyLanguage = null
                        }
                    },
                    onDelete = {
                        confirmLanguage = null
                        repository.delete(pack.language)
                        installed = AutoSyncModelStore.installedLanguages(context)
                    },
                )
            }
        }
    }
}

@Composable
private fun HintText(value: String) {
    Text(
        value,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        color = MaterialTheme.colorScheme.onSurface.copy(alpha = .62f),
        fontSize = 13.sp,
    )
}

@Composable
private fun ModelRow(
    pack: AutoSyncModelRepository.Pack,
    installed: Boolean,
    busy: Boolean,
    confirming: Boolean,
    progress: Float,
    onDownload: () -> Unit,
    onCancelConfirm: () -> Unit,
    onConfirm: () -> Unit,
    onDelete: () -> Unit,
) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(languageLabel(pack.language), fontWeight = FontWeight.Medium, fontSize = 14.sp)
                Spacer(Modifier.height(2.dp))
                Text(
                    when {
                        busy -> stringResource(R.string.autosync_model_downloading, (progress * 100).toInt())
                        installed -> stringResource(R.string.autosync_model_installed, pack.packVersion)
                        else -> stringResource(R.string.autosync_model_not_installed)
                    } + " · " + formatMegabytes(pack.totalBytes),
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = .58f),
                    fontSize = 12.sp,
                )
            }
            when {
                busy -> Text(
                    stringResource(R.string.autosync_model_downloading, (progress * 100).toInt()),
                    color = MaterialTheme.colorScheme.primary,
                    fontSize = 13.sp,
                )
                installed -> PanelButton(stringResource(R.string.autosync_model_delete), onClick = onDelete)
                else -> PanelButton(stringResource(R.string.autosync_model_download), onClick = onDownload)
            }
        }
        if (confirming && !busy) {
            Text(
                stringResource(R.string.autosync_model_confirm, formatMegabytes(pack.totalBytes)),
                modifier = Modifier.padding(top = 6.dp),
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = .72f),
                fontSize = 12.sp,
            )
            Row(Modifier.padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                PanelButton(stringResource(R.string.action_cancel), onClick = onCancelConfirm)
                PanelButton(stringResource(R.string.autosync_model_download), onClick = onConfirm)
            }
        }
    }
}

private fun formatMegabytes(bytes: Long): String = String.format("%.0f MB", bytes / 1024.0 / 1024.0)

private fun languageLabel(language: String): String = when (language) {
    "zh" -> "中文"
    "ja" -> "日本語（G2P 未就绪）"
    "en" -> "English"
    else -> language
}

/** Local action button: the settings-wide helper is private to its own file. */
@Composable
private fun PanelButton(title: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Text(
        title,
        modifier = modifier
            .padding(horizontal = 12.dp, vertical = 8.dp)
            .clickable(onClick = onClick),
        color = MaterialTheme.colorScheme.primary,
        fontWeight = FontWeight.Medium,
        fontSize = 14.sp,
    )
}
