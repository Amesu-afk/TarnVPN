package io.nekohasekai.sfa.tarn.screen

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.nekohasekai.sfa.R
import io.nekohasekai.sfa.constant.Status
import io.nekohasekai.sfa.tarn.component.GridBackdrop
import io.nekohasekai.sfa.tarn.component.TarnActionButton
import io.nekohasekai.sfa.tarn.component.TarnLatencyReadout
import io.nekohasekai.sfa.tarn.component.TarnSectionLabel
import io.nekohasekai.sfa.tarn.component.TarnStatusPill
import io.nekohasekai.sfa.tarn.component.TarnWordmark
import io.nekohasekai.sfa.tarn.component.WorldMapGlyph
import io.nekohasekai.sfa.tarn.component.hairlineBorder
import io.nekohasekai.sfa.tarn.data.Latency
import io.nekohasekai.sfa.tarn.theme.TarnColors
import io.nekohasekai.sfa.tarn.theme.TarnMetaStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.res.stringResource
import kotlinx.coroutines.delay

@Composable
fun TarnHomeScreen(
    serviceStatus: Status,
    serverName: String?,
    serverTag: String?,
    latency: Latency,
    startTime: Long?,
    onToggleConnection: () -> Unit,
    onOpenServers: () -> Unit,
    onOpenShield: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val connected = serviceStatus == Status.Started
    val transitioning = serviceStatus == Status.Starting || serviceStatus == Status.Stopping

    Box(modifier = modifier.fillMaxSize().background(TarnColors.Background)) {
        GridBackdrop(modifier = Modifier.fillMaxSize())

        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .padding(horizontal = 24.dp),
        ) {
            // ---- Header -------------------------------------------------------
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 20.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TarnWordmark()
                Spacer(Modifier.weight(1f))
                TarnStatusPill(
                    text = stringResource(
                        when (serviceStatus) {
                            Status.Started -> R.string.tarn_state_connected
                            Status.Starting -> R.string.tarn_state_connecting
                            Status.Stopping -> R.string.tarn_state_disconnecting
                            else -> R.string.tarn_state_offline
                        },
                    ),
                    active = connected,
                )
                IconButton(onClick = onOpenShield, modifier = Modifier.size(44.dp)) {
                    Icon(
                        imageVector = Icons.Default.Settings,
                        contentDescription = stringResource(R.string.tarn_title_shield),
                        tint = TarnColors.TextDim,
                        modifier = Modifier.size(26.dp),
                    )
                }
            }

            Spacer(Modifier.height(64.dp))

            // ---- Status -------------------------------------------------------
            TarnSectionLabel(text = stringResource(R.string.tarn_label_status))
            Spacer(Modifier.height(12.dp))
            Text(
                text = stringResource(
                    when (serviceStatus) {
                        Status.Started -> R.string.tarn_status_connected
                        Status.Starting -> R.string.tarn_status_connecting
                        Status.Stopping -> R.string.tarn_status_disconnecting
                        else -> R.string.tarn_status_disconnected
                    },
                ),
                style = MaterialTheme.typography.headlineMedium,
                // Lime in every state — in the mockup "Отключено" and "Подключено" share
                // the accent; the dot beside the wordmark carries the on/off signal.
                color = TarnColors.Accent,
            )
            Spacer(Modifier.height(10.dp))
            Box(
                modifier = Modifier
                    .width(20.dp)
                    .height(1.dp)
                    .background(TarnColors.Border),
            )

            // ---- Uptime -------------------------------------------------------
            AnimatedVisibility(
                visible = connected && startTime != null,
                enter = fadeIn(),
                exit = fadeOut(),
            ) {
                Column(modifier = Modifier.padding(top = 32.dp)) {
                    UptimeReadout(startTime = startTime ?: 0L)
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = stringResource(R.string.tarn_label_uptime),
                        style = TarnMetaStyle,
                        color = TarnColors.TextSecondary,
                    )
                }
            }

            Spacer(Modifier.weight(1f))

            // ---- Primary action ------------------------------------------------
            TarnActionButton(
                text = stringResource(
                    if (connected || transitioning) {
                        R.string.tarn_action_disconnect
                    } else {
                        R.string.tarn_action_connect
                    },
                ),
                onClick = onToggleConnection,
                // Live during a transition too. It reads Disconnect there, and pressing it is
                // how the user says a stuck Connecting or Disconnecting should be taken down —
                // greying it out was exactly what left them with no way out.
                enabled = transitioning || serverName != null,
                trailingIcon = if (connected || transitioning) null else Icons.AutoMirrored.Filled.ArrowForward,
            )

            Spacer(Modifier.height(28.dp))

            // ---- Selected server ------------------------------------------------
            ServerSummaryCard(
                serverName = serverName,
                serverTag = serverTag,
                latency = latency,
                showLatency = connected,
                onClick = onOpenServers,
                modifier = Modifier.padding(bottom = 32.dp),
            )
        }
    }
}

@Composable
private fun UptimeReadout(startTime: Long, modifier: Modifier = Modifier) {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(startTime) {
        while (true) {
            now = System.currentTimeMillis()
            delay(1000)
        }
    }
    val elapsed = ((now - startTime) / 1000).coerceAtLeast(0)
    Text(
        text = String.format(
            "%02d:%02d:%02d",
            elapsed / 3600,
            (elapsed % 3600) / 60,
            elapsed % 60,
        ),
        style = MaterialTheme.typography.headlineSmall,
        color = TarnColors.TextPrimary,
        modifier = modifier,
    )
}

@Composable
private fun ServerSummaryCard(
    serverName: String?,
    serverTag: String?,
    latency: Latency,
    showLatency: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .hairlineBorder(color = TarnColors.BorderDim, dashed = true)
            .clickable(onClick = onClick)
            .padding(horizontal = 18.dp, vertical = 18.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            TarnSectionLabel(text = stringResource(R.string.tarn_label_server))
            Spacer(Modifier.height(10.dp))
            // No flag here: the mockup leaves the geography to the map on the right.
            Text(
                text = serverName ?: stringResource(R.string.tarn_server_none),
                style = MaterialTheme.typography.titleMedium,
                color = if (serverName != null) TarnColors.TextPrimary else TarnColors.TextDim,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (serverTag != null) {
                Spacer(Modifier.height(8.dp))
                Text(text = serverTag, style = TarnMetaStyle, color = TarnColors.TextDim)
            }
            // Only once a reading actually lands — a brief "measuring…"/"n/a" flash right
            // as the tunnel comes up would read as a contradiction next to "Подключено".
            if (showLatency && latency is Latency.Ok) {
                Spacer(Modifier.height(8.dp))
                TarnLatencyReadout(latency)
            }
        }
        Spacer(Modifier.width(16.dp))
        WorldMapGlyph(highlighted = showLatency)
    }
}
