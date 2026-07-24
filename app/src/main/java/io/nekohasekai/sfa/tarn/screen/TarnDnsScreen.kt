package io.nekohasekai.sfa.tarn.screen

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Edit
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import io.nekohasekai.sfa.R
import io.nekohasekai.sfa.tarn.component.TarnDialogTextButton
import io.nekohasekai.sfa.tarn.component.TarnLatencyReadout
import io.nekohasekai.sfa.tarn.component.TarnRowDivider
import io.nekohasekai.sfa.tarn.component.TarnSectionLabel
import io.nekohasekai.sfa.tarn.component.hairlineBorder
import io.nekohasekai.sfa.tarn.data.DnsOption
import io.nekohasekai.sfa.tarn.data.Latency
import io.nekohasekai.sfa.tarn.data.TarnDns
import io.nekohasekai.sfa.tarn.theme.TarnColors
import io.nekohasekai.sfa.tarn.theme.TarnLabelStyle
import io.nekohasekai.sfa.tarn.theme.TarnMetaStyle

@Composable
fun TarnDnsScreen(
    options: List<DnsOption>,
    selectedId: String,
    customServer: String,
    pings: Map<String, Latency>,
    onSelect: (String) -> Unit,
    onSaveCustomServer: (String) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var showCustomDialog by remember { mutableStateOf(false) }

    if (showCustomDialog) {
        CustomDnsDialog(
            initialValue = customServer,
            onSave = {
                onSaveCustomServer(it)
                showCustomDialog = false
            },
            onDismiss = { showCustomDialog = false },
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
                text = stringResource(R.string.tarn_dns_title).uppercase(),
                style = MaterialTheme.typography.titleLarge,
                color = TarnColors.TextPrimary,
                textAlign = TextAlign.Center,
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.size(48.dp))
        }

        Spacer(Modifier.height(8.dp))
        Text(
            text = stringResource(R.string.tarn_dns_hint),
            style = TarnMetaStyle,
            color = TarnColors.TextDim,
            modifier = Modifier.padding(horizontal = 20.dp),
        )
        Spacer(Modifier.height(16.dp))
        TarnSectionLabel(
            text = stringResource(R.string.tarn_dns_section),
            modifier = Modifier.padding(horizontal = 20.dp),
        )
        Spacer(Modifier.height(10.dp))

        Column(modifier = Modifier.padding(horizontal = 20.dp)) {
            options.forEach { option ->
                DnsRow(
                    option = option,
                    selected = option.id == selectedId,
                    latency = pings[option.id] ?: Latency.Probing,
                    onClick = { onSelect(option.id) },
                )
                TarnRowDivider()
            }
            CustomDnsRow(
                server = customServer,
                selected = selectedId == TarnDns.CUSTOM_ID && TarnDns.isValidCustomServer(customServer),
                latency = pings[TarnDns.CUSTOM_ID] ?: Latency.Unknown,
                onClick = { showCustomDialog = true },
            )
        }
    }
}

/**
 * Unlike the presets, tapping this always opens [CustomDnsDialog] rather than selecting
 * directly — there's nothing to select until an address has been entered, and saving one
 * there selects it in the same step (see [TarnShell.onSaveCustomDns]).
 */
@Composable
private fun CustomDnsRow(
    server: String,
    selected: Boolean,
    latency: Latency,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val hasServer = TarnDns.isValidCustomServer(server)
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 4.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(6.dp)
                .clip(CircleShape)
                .background(if (selected) TarnColors.Accent else TarnColors.BorderDim),
        )
        Spacer(Modifier.width(14.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = stringResource(R.string.tarn_dns_custom_title),
                style = MaterialTheme.typography.titleSmall,
                color = if (selected) TarnColors.TextPrimary else TarnColors.TextSecondary,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = if (hasServer) server else stringResource(R.string.tarn_dns_custom_unset),
                style = TarnMetaStyle,
                color = TarnColors.TextDim,
            )
        }
        Spacer(Modifier.width(12.dp))
        if (hasServer) TarnLatencyReadout(latency)
        Spacer(Modifier.width(10.dp))
        Icon(
            imageVector = if (selected) Icons.Default.Check else Icons.Default.Edit,
            contentDescription = stringResource(R.string.tarn_dns_custom_title),
            tint = TarnColors.Accent,
            modifier = Modifier.size(16.dp),
        )
    }
}

@Composable
private fun CustomDnsDialog(
    initialValue: String,
    onSave: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var value by remember { mutableStateOf(initialValue) }
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
                text = stringResource(R.string.tarn_dns_custom_dialog_title).uppercase(),
                style = TarnLabelStyle,
                color = TarnColors.TextPrimary,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = stringResource(R.string.tarn_dns_custom_dialog_hint),
                style = TarnMetaStyle,
                color = TarnColors.TextDim,
            )
            Spacer(Modifier.height(16.dp))

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp)
                    .hairlineBorder(color = TarnColors.BorderDim)
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
                                text = stringResource(R.string.tarn_dns_custom_input_placeholder),
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
                    text = stringResource(R.string.tarn_dns_custom_error),
                    style = TarnMetaStyle,
                    color = TarnColors.Danger,
                )
            }

            Spacer(Modifier.height(20.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
            ) {
                TarnDialogTextButton(text = stringResource(R.string.tarn_action_cancel), onClick = onDismiss)
                Spacer(Modifier.width(12.dp))
                TarnDialogTextButton(
                    text = stringResource(R.string.tarn_action_save),
                    onClick = {
                        val trimmed = value.trim()
                        if (TarnDns.isValidCustomServer(trimmed)) {
                            onSave(trimmed)
                        } else {
                            error = true
                        }
                    },
                    emphasised = true,
                )
            }
        }
    }
}

@Composable
private fun DnsRow(
    option: DnsOption,
    selected: Boolean,
    latency: Latency,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 4.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(6.dp)
                .clip(CircleShape)
                .background(if (selected) TarnColors.Accent else TarnColors.BorderDim),
        )
        Spacer(Modifier.width(14.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = option.title,
                style = MaterialTheme.typography.titleSmall,
                color = if (selected) TarnColors.TextPrimary else TarnColors.TextSecondary,
            )
            Spacer(Modifier.height(4.dp))
            Text(text = option.note, style = TarnMetaStyle, color = TarnColors.TextDim)
        }
        Spacer(Modifier.width(12.dp))
        TarnLatencyReadout(latency)
        if (selected) {
            Spacer(Modifier.width(10.dp))
            Icon(
                imageVector = Icons.Default.Check,
                contentDescription = null,
                tint = TarnColors.Accent,
                modifier = Modifier.size(16.dp),
            )
        }
    }
}
