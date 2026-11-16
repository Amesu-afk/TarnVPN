package io.nekohasekai.sfa.tarn.component

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.widget.Toast
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import io.nekohasekai.sfa.R
import io.nekohasekai.sfa.compose.util.QRCodeGenerator
import io.nekohasekai.sfa.ktx.clipboardText
import io.nekohasekai.sfa.tarn.theme.TarnColors
import io.nekohasekai.sfa.tarn.theme.TarnLabelStyle
import io.nekohasekai.sfa.tarn.theme.TarnMetaStyle

/**
 * Shows the original share link of a server as text and a QR code, with copy and share
 * actions. Only Tarn-managed profiles have a link — the `.vless` sidecar is written at import
 * and is what the generator rebuilds configs from, so exporting it hands the recipient the
 * exact thing that was imported, credentials included. That is the point of a share dialog,
 * so it is shown only when the user explicitly asks for it from the server row.
 */
@Composable
fun TarnShareDialog(
    name: String,
    link: String,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    // Deterministic black-on-white: the theme-aware QR helper draws white modules on a
    // transparent background in dark mode, which disappears on the white quiet-zone box.
    val bitmap = remember(link) {
        QRCodeGenerator.generate(
            content = link,
            size = 512,
            foregroundColor = Color.BLACK,
            backgroundColor = Color.WHITE,
        )
    }
    Dialog(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(TarnColors.Surface)
                .hairlineBorder(color = TarnColors.Border)
                .padding(20.dp),
        ) {
            Text(
                text = stringResource(R.string.tarn_export_title).uppercase(),
                style = TarnLabelStyle,
                color = TarnColors.TextPrimary,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = name,
                style = TarnMetaStyle,
                color = TarnColors.TextDim,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(16.dp))
            Image(
                bitmap = bitmap.asImageBitmap(),
                contentDescription = stringResource(R.string.tarn_export_title),
                modifier = Modifier
                    .size(220.dp)
                    .background(androidx.compose.ui.graphics.Color.White, RoundedCornerShape(8.dp))
                    .padding(12.dp),
            )
            Spacer(Modifier.height(16.dp))
            Text(
                text = link,
                style = TarnMetaStyle,
                color = TarnColors.TextSecondary,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(20.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
            ) {
                TarnDialogTextButton(
                    text = stringResource(R.string.tarn_export_copy),
                    onClick = {
                        clipboardText = link
                        Toast.makeText(context, R.string.tarn_export_copied, Toast.LENGTH_SHORT)
                            .show()
                    },
                )
                Spacer(Modifier.width(12.dp))
                TarnDialogTextButton(
                    text = stringResource(R.string.tarn_export_share),
                    onClick = {
                        context.startActivity(
                            Intent.createChooser(
                                Intent(Intent.ACTION_SEND).apply {
                                    type = "text/plain"
                                    putExtra(Intent.EXTRA_TEXT, link)
                                },
                                context.getString(R.string.tarn_export_share),
                            ),
                        )
                    },
                    emphasised = true,
                )
            }
        }
    }
}
