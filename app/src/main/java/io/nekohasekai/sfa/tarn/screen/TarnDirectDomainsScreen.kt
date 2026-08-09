package io.nekohasekai.sfa.tarn.screen

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import io.nekohasekai.sfa.R
import io.nekohasekai.sfa.database.Settings
import io.nekohasekai.sfa.tarn.component.TarnDialogTextButton
import io.nekohasekai.sfa.tarn.component.TarnPanel
import io.nekohasekai.sfa.tarn.component.TarnRowDivider
import io.nekohasekai.sfa.tarn.component.hairlineBorder
import io.nekohasekai.sfa.tarn.theme.TarnColors
import io.nekohasekai.sfa.tarn.theme.TarnLabelStyle
import io.nekohasekai.sfa.tarn.theme.TarnMetaStyle

/**
 * The user's own additions to the direct list.
 *
 * The built-in Russian list covers the country TLDs and the services outside them, but it can
 * never be complete — somebody's bank, employer portal or regional service will be missing, and
 * before this screen the only thing they could do about it was turn the whole feature off.
 *
 * Matching is by suffix, so one entry covers the subdomains under it; that is stated on the
 * screen rather than left for the user to discover by adding a name and finding half the site
 * still on the tunnel.
 */
@Composable
fun TarnDirectDomainsScreen(
    domains: List<String>,
    onAdd: (String) -> Boolean,
    onRemove: (String) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var showAddDialog by remember { mutableStateOf(false) }

    if (showAddDialog) {
        AddDomainDialog(
            onAdd = { onAdd(it) },
            onDismiss = { showAddDialog = false },
        )
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(TarnColors.Background)
            .statusBarsPadding()
            .navigationBarsPadding(),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = stringResource(R.string.tarn_action_back),
                    tint = TarnColors.TextSecondary,
                    modifier = Modifier.size(18.dp),
                )
            }
            Text(
                text = stringResource(R.string.tarn_direct_domains_title).uppercase(),
                style = MaterialTheme.typography.titleLarge,
                color = TarnColors.TextPrimary,
                textAlign = TextAlign.Center,
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = { showAddDialog = true }) {
                Icon(
                    imageVector = Icons.Filled.Add,
                    contentDescription = stringResource(R.string.tarn_direct_domains_add),
                    tint = TarnColors.Accent,
                    modifier = Modifier.size(20.dp),
                )
            }
        }

        Spacer(Modifier.height(8.dp))
        Text(
            text = stringResource(R.string.tarn_direct_domains_hint),
            style = TarnMetaStyle,
            color = TarnColors.TextDim,
            modifier = Modifier.padding(horizontal = 20.dp),
        )
        Spacer(Modifier.height(16.dp))

        if (domains.isEmpty()) {
            Text(
                text = stringResource(R.string.tarn_direct_domains_empty),
                style = MaterialTheme.typography.bodyMedium,
                color = TarnColors.TextDim,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 32.dp),
            )
        } else {
            LazyColumn(modifier = Modifier.padding(horizontal = 20.dp)) {
                items(domains, key = { it }) { domain ->
                    TarnPanel {
                        DomainRow(domain = domain, onRemove = { onRemove(domain) })
                    }
                    TarnRowDivider()
                }
            }
        }
    }
}

@Composable
private fun DomainRow(domain: String, onRemove: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = domain,
            style = MaterialTheme.typography.bodyMedium,
            color = TarnColors.TextPrimary,
            modifier = Modifier.weight(1f),
        )
        IconButton(onClick = onRemove, modifier = Modifier.size(32.dp)) {
            Icon(
                imageVector = Icons.Filled.Delete,
                contentDescription = stringResource(R.string.tarn_direct_domains_remove),
                tint = TarnColors.TextDim,
                modifier = Modifier.size(16.dp),
            )
        }
    }
}

/**
 * Validation happens on save through [Settings.normalizeDirectDomain], which accepts a pasted
 * URL or a leading dot rather than rejecting input whose intent is obvious. Only something with
 * no domain in it at all fails, and the dialog stays open so the text can be fixed instead of
 * retyped.
 */
@Composable
private fun AddDomainDialog(onAdd: (String) -> Boolean, onDismiss: () -> Unit) {
    var value by remember { mutableStateOf("") }
    var error by remember { mutableStateOf(false) }

    Dialog(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(TarnColors.Surface)
                .hairlineBorder(color = TarnColors.Border)
                .padding(20.dp),
        ) {
            Text(
                text = stringResource(R.string.tarn_direct_domains_add).uppercase(),
                style = TarnLabelStyle,
                color = TarnColors.TextPrimary,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = stringResource(R.string.tarn_direct_domains_add_hint),
                style = TarnMetaStyle,
                color = TarnColors.TextDim,
            )
            Spacer(Modifier.height(16.dp))

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp)
                    .hairlineBorder(color = if (error) TarnColors.Danger else TarnColors.BorderDim)
                    .padding(12.dp),
            ) {
                BasicTextField(
                    value = value,
                    onValueChange = { value = it; error = false },
                    singleLine = true,
                    textStyle = MaterialTheme.typography.bodyMedium.copy(color = TarnColors.TextPrimary),
                    cursorBrush = SolidColor(TarnColors.Accent),
                    modifier = Modifier.fillMaxWidth(),
                    decorationBox = { inner ->
                        if (value.isEmpty()) {
                            Text(
                                text = stringResource(R.string.tarn_direct_domains_placeholder),
                                style = MaterialTheme.typography.bodyMedium,
                                color = TarnColors.TextDim,
                            )
                        }
                        inner()
                    },
                )
            }

            if (error) {
                Spacer(Modifier.height(12.dp))
                Text(
                    text = stringResource(R.string.tarn_direct_domains_invalid),
                    style = TarnMetaStyle,
                    color = TarnColors.Danger,
                )
            }

            Spacer(Modifier.height(20.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
            ) {
                TarnDialogTextButton(
                    text = stringResource(R.string.tarn_action_cancel),
                    onClick = onDismiss,
                )
                Spacer(Modifier.width(12.dp))
                TarnDialogTextButton(
                    text = stringResource(R.string.tarn_direct_domains_add_action),
                    onClick = { if (onAdd(value)) onDismiss() else error = true },
                    enabled = value.isNotBlank(),
                    emphasised = true,
                )
            }
        }
    }
}
