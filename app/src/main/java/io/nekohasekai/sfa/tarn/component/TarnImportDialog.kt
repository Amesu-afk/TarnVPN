package io.nekohasekai.sfa.tarn.component

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import io.nekohasekai.sfa.R
import io.nekohasekai.sfa.tarn.theme.TarnColors
import io.nekohasekai.sfa.tarn.theme.TarnLabelStyle
import io.nekohasekai.sfa.tarn.theme.TarnMetaStyle

/**
 * Paste-a-link dialog used to add servers from the servers screen. Deliberately narrow: it
 * only takes vless:// links and subscription URLs — anything richer (QR, file import, raw
 * JSON) stays behind "Advanced" since it needs UI this shell doesn't have room for.
 */
@Composable
fun TarnImportDialog(
    value: String,
    onValueChange: (String) -> Unit,
    onImport: () -> Unit,
    onDismiss: () -> Unit,
    importing: Boolean,
    errorMessage: String?,
) {
    Dialog(onDismissRequest = { if (!importing) onDismiss() }) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(TarnColors.Surface)
                .hairlineBorder(color = TarnColors.Border)
                .padding(20.dp),
        ) {
            Text(
                text = stringResource(R.string.tarn_servers_add_title).uppercase(),
                style = TarnLabelStyle,
                color = TarnColors.TextPrimary,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = stringResource(R.string.tarn_servers_add_hint),
                style = TarnMetaStyle,
                color = TarnColors.TextDim,
            )
            Spacer(Modifier.height(16.dp))

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 80.dp)
                    .hairlineBorder(color = TarnColors.BorderDim)
                    .padding(12.dp),
            ) {
                BasicTextField(
                    value = value,
                    onValueChange = onValueChange,
                    enabled = !importing,
                    textStyle = MaterialTheme.typography.bodyMedium.copy(color = TarnColors.TextPrimary),
                    cursorBrush = SolidColor(TarnColors.Accent),
                    modifier = Modifier.fillMaxWidth(),
                    decorationBox = { inner ->
                        if (value.isEmpty()) {
                            Text(
                                text = stringResource(R.string.tarn_servers_add_placeholder),
                                style = MaterialTheme.typography.bodyMedium,
                                color = TarnColors.TextDim,
                            )
                        }
                        inner()
                    },
                )
            }

            if (errorMessage != null) {
                Spacer(Modifier.height(12.dp))
                Text(
                    text = errorMessage,
                    style = TarnMetaStyle,
                    color = TarnColors.Danger,
                )
            }

            Spacer(Modifier.height(20.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
            ) {
                if (importing) {
                    CircularProgressIndicator(
                        color = TarnColors.Accent,
                        strokeWidth = 2.dp,
                        modifier = Modifier
                            .padding(end = 20.dp)
                            .height(20.dp)
                            .width(20.dp),
                    )
                }
                TarnDialogTextButton(
                    text = stringResource(R.string.tarn_action_cancel),
                    onClick = onDismiss,
                    enabled = !importing,
                )
                Spacer(Modifier.width(12.dp))
                TarnDialogTextButton(
                    text = stringResource(R.string.tarn_servers_add_action),
                    onClick = onImport,
                    enabled = !importing && value.isNotBlank(),
                    emphasised = true,
                )
            }
        }
    }
}

/** Uppercase text action used at the bottom of Tarn dialogs — cancel/confirm pairs. */
@Composable
fun TarnDialogTextButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    emphasised: Boolean = false,
) {
    val color = when {
        !enabled -> TarnColors.TextDim
        emphasised -> TarnColors.Accent
        else -> TarnColors.TextSecondary
    }
    val interaction = remember { MutableInteractionSource() }
    Text(
        text = text.uppercase(),
        style = TarnLabelStyle,
        color = color,
        textAlign = TextAlign.Center,
        modifier = modifier
            .clickable(
                interactionSource = interaction,
                indication = null,
                enabled = enabled,
                onClick = onClick,
            )
            .padding(vertical = 6.dp, horizontal = 4.dp),
    )
}
