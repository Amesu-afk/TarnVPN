package io.nekohasekai.sfa.compose.component

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.jeziellago.compose.markdowntext.MarkdownText
import io.nekohasekai.sfa.R
import io.nekohasekai.sfa.database.Settings
import io.nekohasekai.sfa.update.UpdateInfo
import io.nekohasekai.sfa.update.UpdateState
import io.nekohasekai.sfa.vendor.Vendor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.kodein.emoji.Emoji
import org.kodein.emoji.EmojiTemplateCatalog
import org.kodein.emoji.all

/**
 * Shows "a new version exists" and runs the download, for whichever shell hosts it.
 *
 * It exists because the two dialogs used to be written inline in `MainActivity.SFAApp()` — the
 * original sing-box interface — and `TarnShell` never composes `SFAApp()`. So every TarnVPN user
 * who stayed in the TarnVPN interface (that is: all of them) could have an update found, cached
 * and ready, and never be told. Hosting the same composable from both shells is what makes the
 * updater visible; keeping it in one place is what stops the two from drifting apart.
 */
@Composable
fun UpdateFlowHost() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val updateInfo by UpdateState.updateInfo
    val info = updateInfo ?: return

    // Keyed on the version: dismissing hides this update, but a later, newer one must be able to
    // raise the dialog again within the same composition.
    var dismissed by remember(info.versionCode) { mutableStateOf(false) }
    var showDownload by remember { mutableStateOf(false) }
    var downloadJob by remember { mutableStateOf<Job?>(null) }
    var downloadError by remember { mutableStateOf<String?>(null) }

    if (!dismissed && info.versionCode > Settings.lastShownUpdateVersion) {
        UpdateAvailableDialog(
            updateInfo = info,
            onDismiss = {
                Settings.lastShownUpdateVersion = info.versionCode
                dismissed = true
            },
            onUpdate = {
                showDownload = true
                downloadError = null
                downloadJob = scope.launch {
                    try {
                        withContext(Dispatchers.IO) {
                            Vendor.downloadAndInstall(context, info.downloadUrl)
                        }
                        showDownload = false
                    } catch (e: Exception) {
                        downloadError = e.message ?: e.toString()
                    }
                }
            },
        )
    }

    if (showDownload) {
        AlertDialog(
            onDismissRequest = {},
            title = { Text(stringResource(R.string.update)) },
            text = {
                if (downloadError != null) {
                    Text(downloadError!!, color = MaterialTheme.colorScheme.error)
                } else {
                    val progress by UpdateState.downloadProgress
                    val value = progress
                    Column {
                        Text(
                            if (value != null) {
                                "${stringResource(R.string.downloading)} ${(value * 100).toInt()}%"
                            } else {
                                stringResource(R.string.downloading)
                            },
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        if (value != null) {
                            LinearProgressIndicator(
                                progress = { value },
                                modifier = Modifier.fillMaxWidth(),
                            )
                        } else {
                            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    downloadJob?.cancel()
                    downloadJob = null
                    showDownload = false
                    downloadError = null
                    UpdateState.downloadProgress.value = null
                }) {
                    Text(stringResource(if (downloadError != null) R.string.ok else android.R.string.cancel))
                }
            },
        )
    }
}

@Composable
fun UpdateAvailableDialog(updateInfo: UpdateInfo, onDismiss: () -> Unit, onUpdate: () -> Unit) {
    val context = LocalContext.current
    val emojiCatalog = remember { EmojiTemplateCatalog(Emoji.all()) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.check_update)) },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
            ) {
                Text(
                    text = stringResource(R.string.new_version_available, updateInfo.versionName),
                    style = MaterialTheme.typography.bodyMedium,
                )

                if (!updateInfo.releaseNotes.isNullOrBlank()) {
                    val processedNotes = remember(updateInfo.releaseNotes) {
                        emojiCatalog.replaceShortcodes(updateInfo.releaseNotes)
                    }
                    Spacer(modifier = Modifier.height(12.dp))
                    MarkdownText(
                        markdown = processedNotes,
                        style = MaterialTheme.typography.bodySmall.copy(
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        ),
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    onDismiss()
                    onUpdate()
                },
            ) {
                Text(stringResource(R.string.update))
            }
        },
        dismissButton = {
            Row {
                TextButton(onClick = {
                    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(updateInfo.releaseUrl))
                    context.startActivity(intent)
                    onDismiss()
                }) {
                    Text(stringResource(R.string.view_release))
                }
                Spacer(modifier = Modifier.width(8.dp))
                TextButton(onClick = onDismiss) {
                    Text(stringResource(android.R.string.cancel))
                }
            }
        },
    )
}
