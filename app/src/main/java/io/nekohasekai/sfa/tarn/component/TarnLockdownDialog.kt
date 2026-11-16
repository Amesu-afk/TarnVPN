package io.nekohasekai.sfa.tarn.component

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import io.nekohasekai.sfa.R
import io.nekohasekai.sfa.tarn.theme.TarnColors
import io.nekohasekai.sfa.tarn.theme.TarnLabelStyle
import io.nekohasekai.sfa.tarn.theme.TarnMetaStyle

/**
 * One-time offer to turn on Android's own Always-on VPN with "Block connections without VPN",
 * shown after the first successful connection.
 *
 * The in-app "Prevent VPN bypass" switch is an inverted `allowBypass` — it stops apps going
 * around a running tunnel, and nothing more. It cannot cover the tunnel being gone: a crash,
 * a reboot, the user revoking permission. Only the system lockdown does that, and only the
 * user can turn it on, so the honest thing is to say so once and open the right settings page.
 * Either answer counts as seen: the Shield screen keeps the permanent entry point.
 */
@Composable
fun TarnLockdownDialog(
    onOpenSettings: () -> Unit,
    onDismiss: () -> Unit,
) {
    Dialog(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(TarnColors.Surface)
                .hairlineBorder(color = TarnColors.Border)
                .padding(20.dp),
        ) {
            Text(
                text = stringResource(R.string.tarn_lockdown_title).uppercase(),
                style = TarnLabelStyle,
                color = TarnColors.TextPrimary,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = stringResource(R.string.tarn_lockdown_body),
                style = TarnMetaStyle,
                color = TarnColors.TextDim,
            )
            Spacer(Modifier.height(20.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
            ) {
                TarnDialogTextButton(
                    text = stringResource(R.string.tarn_lockdown_later),
                    onClick = onDismiss,
                )
                Spacer(Modifier.width(12.dp))
                TarnDialogTextButton(
                    text = stringResource(R.string.tarn_lockdown_open),
                    onClick = onOpenSettings,
                    emphasised = true,
                )
            }
        }
    }
}
