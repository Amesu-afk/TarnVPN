package io.nekohasekai.sfa.tarn.screen

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import io.nekohasekai.sfa.R
import io.nekohasekai.sfa.tarn.component.TarnDialogTextButton
import io.nekohasekai.sfa.tarn.component.TarnSwitch
import io.nekohasekai.sfa.tarn.component.hairlineBorder
import io.nekohasekai.sfa.tarn.data.SubscriptionView
import io.nekohasekai.sfa.tarn.theme.TarnColors
import io.nekohasekai.sfa.tarn.theme.TarnLabelStyle
import io.nekohasekai.sfa.tarn.theme.TarnMetaStyle

@Composable
fun TarnSubscriptionsScreen(
    state: TarnSubscriptionsUiState,
    onBack: () -> Unit,
    onRefresh: (String) -> Unit,
    onDelete: (String) -> Unit,
    onToggleAutoUpdate: (String, Boolean) -> Unit,
    onDismissError: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var pendingDelete by remember { mutableStateOf<SubscriptionView?>(null) }

    pendingDelete?.let { target ->
        SubscriptionDeleteDialog(
            name = target.subscription.name,
            serverCount = target.serverCount,
            onConfirm = {
                onDelete(target.subscription.id)
                pendingDelete = null
            },
            onDismiss = { pendingDelete = null },
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
                text = stringResource(R.string.tarn_subscriptions_title).uppercase(),
                style = MaterialTheme.typography.titleLarge,
                color = TarnColors.TextPrimary,
                modifier = Modifier.weight(1f),
                textAlign = TextAlign.Center,
            )
            // Balances the back button so the title stays centred.
            Spacer(Modifier.width(48.dp))
        }

        if (state.error != null) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp)
                    .hairlineBorder(color = TarnColors.Danger)
                    .padding(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = state.error,
                    style = TarnMetaStyle,
                    color = TarnColors.Danger,
                    modifier = Modifier.weight(1f),
                )
                TarnDialogTextButton(
                    text = stringResource(R.string.tarn_action_dismiss),
                    onClick = onDismissError,
                )
            }
        }

        if (state.subscriptions.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    text = stringResource(R.string.tarn_subscriptions_empty),
                    style = TarnMetaStyle,
                    color = TarnColors.TextDim,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(horizontal = 40.dp),
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                items(state.subscriptions, key = { it.subscription.id }) { view ->
                    SubscriptionRow(
                        view = view,
                        refreshing = state.refreshing.contains(view.subscription.id),
                        onRefresh = { onRefresh(view.subscription.id) },
                        onDelete = { pendingDelete = view },
                        onToggleAutoUpdate = { onToggleAutoUpdate(view.subscription.id, it) },
                    )
                }
            }
        }
    }
}

@Composable
private fun SubscriptionRow(
    view: SubscriptionView,
    refreshing: Boolean,
    onRefresh: () -> Unit,
    onDelete: () -> Unit,
    onToggleAutoUpdate: (Boolean) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .hairlineBorder(color = TarnColors.BorderDim)
            .padding(14.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = view.subscription.name,
                    style = TarnLabelStyle,
                    color = TarnColors.TextPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = view.subscription.url,
                    style = TarnMetaStyle,
                    color = TarnColors.TextDim,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (refreshing) {
                CircularProgressIndicator(
                    color = TarnColors.Accent,
                    strokeWidth = 2.dp,
                    modifier = Modifier
                        .padding(horizontal = 8.dp)
                        .size(18.dp),
                )
            } else {
                IconButton(onClick = onRefresh, modifier = Modifier.size(28.dp)) {
                    Icon(
                        imageVector = Icons.Default.Refresh,
                        contentDescription = stringResource(R.string.tarn_subscriptions_refresh),
                        tint = TarnColors.Accent,
                        modifier = Modifier.size(18.dp),
                    )
                }
            }
            Spacer(Modifier.width(4.dp))
            IconButton(onClick = onDelete, modifier = Modifier.size(28.dp)) {
                Icon(
                    imageVector = Icons.Default.Delete,
                    contentDescription = stringResource(R.string.tarn_action_delete),
                    tint = TarnColors.TextDim,
                    modifier = Modifier.size(18.dp),
                )
            }
        }

        Spacer(Modifier.height(10.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            val serverCountText = pluralStringResource(
                R.plurals.tarn_subscriptions_server_count,
                view.serverCount,
                view.serverCount,
            )
            Text(
                text = "$serverCountText  ·  ${updatedAgoText(view.subscription.lastUpdated)}",
                style = TarnMetaStyle,
                color = TarnColors.TextSecondary,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = stringResource(R.string.tarn_subscriptions_auto_update),
                style = TarnMetaStyle,
                color = TarnColors.TextDim,
            )
            Spacer(Modifier.width(10.dp))
            TarnSwitch(
                checked = view.subscription.autoUpdate,
                onCheckedChange = onToggleAutoUpdate,
            )
        }
    }
}

@Composable
private fun updatedAgoText(lastUpdated: Long): String {
    if (lastUpdated <= 0L) return stringResource(R.string.tarn_subscriptions_never)
    val minutes = ((System.currentTimeMillis() - lastUpdated) / 60_000L).coerceAtLeast(0).toInt()
    return when {
        minutes < 1 -> stringResource(R.string.tarn_subscriptions_just_now)
        minutes < 60 ->
            pluralStringResource(R.plurals.tarn_subscriptions_minutes_ago, minutes, minutes)
        minutes < 60 * 24 -> {
            val hours = minutes / 60
            pluralStringResource(R.plurals.tarn_subscriptions_hours_ago, hours, hours)
        }
        else -> {
            val days = minutes / (60 * 24)
            pluralStringResource(R.plurals.tarn_subscriptions_days_ago, days, days)
        }
    }
}

@Composable
private fun SubscriptionDeleteDialog(
    name: String,
    serverCount: Int,
    onConfirm: () -> Unit,
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
                text = stringResource(R.string.tarn_subscriptions_delete_title).uppercase(),
                style = TarnLabelStyle,
                color = TarnColors.TextPrimary,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = stringResource(R.string.tarn_subscriptions_delete_message, name, serverCount),
                style = TarnMetaStyle,
                color = TarnColors.TextDim,
            )
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
                    text = stringResource(R.string.tarn_subscriptions_delete_action),
                    onClick = onConfirm,
                    emphasised = true,
                )
            }
        }
    }
}
