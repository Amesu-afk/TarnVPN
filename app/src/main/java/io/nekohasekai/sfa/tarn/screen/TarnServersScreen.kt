package io.nekohasekai.sfa.tarn.screen

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.CloudSync
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import io.nekohasekai.sfa.R
import io.nekohasekai.sfa.tarn.component.TarnDialogTextButton
import io.nekohasekai.sfa.tarn.component.TarnImportDialog
import io.nekohasekai.sfa.tarn.component.TarnLabelTab
import io.nekohasekai.sfa.tarn.component.TarnLatencyReadout
import io.nekohasekai.sfa.tarn.component.cornerBrackets
import io.nekohasekai.sfa.tarn.component.hairlineBorder
import io.nekohasekai.sfa.tarn.data.Latency
import io.nekohasekai.sfa.tarn.data.ServerEntry
import io.nekohasekai.sfa.tarn.data.TarnFullTestResult
import io.nekohasekai.sfa.tarn.theme.TarnColors
import io.nekohasekai.sfa.tarn.theme.TarnLabelStyle
import io.nekohasekai.sfa.tarn.theme.TarnMetaStyle

@Composable
fun TarnServersScreen(
    state: TarnServersUiState,
    selectedProfileId: Long,
    onBack: () -> Unit,
    onSelect: (Long) -> Unit,
    onToggleFavourite: (Long) -> Unit,
    onQueryChange: (String) -> Unit,
    onRecommendedOnlyChange: (Boolean) -> Unit,
    onImport: (String) -> Unit,
    onDismissImportError: () -> Unit,
    onDelete: (Long) -> Unit = {},
    onOpenSubscriptions: () -> Unit = {},
    onCountryFilterChange: (String?) -> Unit = {},
    modifier: Modifier = Modifier,
    serviceStarted: Boolean = false,
    onRunFullTest: (profileId: Long, serviceStarted: Boolean) -> Unit = { _, _ -> },
    onCancelFullTest: () -> Unit = {},
    onClearFullTest: () -> Unit = {},
    onSuggestedFailover: (Long) -> Unit = onSelect,
) {
    var searching by remember { mutableStateOf(false) }
    var showImportDialog by remember { mutableStateOf(false) }
    var importInput by remember { mutableStateOf("") }
    var pendingDelete by remember { mutableStateOf<ServerEntry?>(null) }
    val currentCancelFullTest by rememberUpdatedState(onCancelFullTest)

    // A URL-test is tied to the running core instance. Stop it when the service goes down
    // or this screen leaves composition; cancelling only the Compose coroutine would not
    // interrupt the synchronous gomobile call in progress.
    LaunchedEffect(serviceStarted, state.fullTestRunning) {
        if (!serviceStarted && state.fullTestRunning) onCancelFullTest()
    }
    DisposableEffect(Unit) {
        onDispose { currentCancelFullTest() }
    }

    // The dialog stays open through "importing" so the spinner is visible, then closes
    // itself only once a run finishes with no error to show.
    if (showImportDialog) {
        val importNotice = state.importNotice?.let { notice ->
            stringResource(
                R.string.tarn_servers_import_partial,
                notice.serverCount,
                notice.rejectedCount,
            )
        }
        TarnImportDialog(
            value = importInput,
            onValueChange = { importInput = it },
            onImport = { onImport(importInput) },
            onDismiss = {
                showImportDialog = false
                importInput = ""
                onDismissImportError()
            },
            importing = state.importing,
            errorMessage = state.importError,
            noticeMessage = importNotice,
        )
    }
    LaunchedEffect(state.importing, state.importError, state.importNotice) {
        if (
            showImportDialog && !state.importing && state.importError == null &&
            state.importNotice == null && importInput.isNotBlank()
        ) {
            showImportDialog = false
            importInput = ""
        }
    }

    pendingDelete?.let { target ->
        TarnDeleteConfirmDialog(
            serverName = target.displayName,
            onConfirm = {
                onDelete(target.profileId)
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
        // ---- Top bar ----------------------------------------------------------
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
            if (searching) {
                BasicTextField(
                    value = state.query,
                    onValueChange = onQueryChange,
                    singleLine = true,
                    textStyle = MaterialTheme.typography.titleSmall.copy(color = TarnColors.TextPrimary),
                    cursorBrush = SolidColor(TarnColors.Accent),
                    modifier = Modifier
                        .weight(1f)
                        .padding(horizontal = 8.dp),
                    decorationBox = { inner ->
                        Box {
                            if (state.query.isEmpty()) {
                                Text(
                                    text = stringResource(R.string.tarn_servers_search_hint),
                                    style = MaterialTheme.typography.titleSmall,
                                    color = TarnColors.TextDim,
                                )
                            }
                            inner()
                        }
                    },
                )
            } else {
                Text(
                    text = stringResource(R.string.tarn_title_servers).uppercase(),
                    style = MaterialTheme.typography.titleLarge,
                    color = TarnColors.TextPrimary,
                    modifier = Modifier.weight(1f),
                    textAlign = TextAlign.Center,
                )
            }
            val fastest = state.fastestReachable
            IconButton(
                onClick = { fastest?.let { onSelect(it.profileId) } },
                enabled = fastest != null,
            ) {
                Icon(
                    imageVector = Icons.Default.Bolt,
                    contentDescription = stringResource(R.string.tarn_servers_fastest),
                    tint = if (fastest != null) TarnColors.Accent else TarnColors.TextDim,
                    modifier = Modifier.size(20.dp),
                )
            }
            IconButton(onClick = onOpenSubscriptions) {
                Icon(
                    imageVector = Icons.Default.CloudSync,
                    contentDescription = stringResource(R.string.tarn_subscriptions_title),
                    tint = TarnColors.TextSecondary,
                    modifier = Modifier.size(20.dp),
                )
            }
            IconButton(
                onClick = {
                    if (searching) onQueryChange("")
                    searching = !searching
                },
            ) {
                Icon(
                    imageVector = Icons.Default.Search,
                    contentDescription = stringResource(R.string.tarn_servers_search_hint),
                    tint = if (searching) TarnColors.Accent else TarnColors.TextSecondary,
                    modifier = Modifier.size(18.dp),
                )
            }
            IconButton(onClick = { showImportDialog = true }) {
                Icon(
                    imageVector = Icons.Default.Add,
                    contentDescription = stringResource(R.string.tarn_servers_add_title),
                    tint = TarnColors.TextSecondary,
                    modifier = Modifier.size(20.dp),
                )
            }
        }

        // ---- Tabs -------------------------------------------------------------
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp),
            horizontalArrangement = Arrangement.spacedBy(28.dp),
        ) {
            TarnLabelTab(
                text = stringResource(R.string.tarn_servers_tab_recommended),
                selected = state.recommendedOnly,
                onClick = { onRecommendedOnlyChange(true) },
            )
            TarnLabelTab(
                text = stringResource(R.string.tarn_servers_tab_all),
                selected = !state.recommendedOnly,
                onClick = { onRecommendedOnlyChange(false) },
            )
        }

        // ---- Country filter ---------------------------------------------------
        // Only worth showing once there is more than one country to pick between, and it steps
        // aside while searching so the two filters don't fight over the same list.
        if (!searching && state.countryOptions.size >= 2) {
            Spacer(Modifier.height(14.dp))
            CountryFilterRow(
                options = state.countryOptions,
                selected = state.countryFilter,
                onSelect = onCountryFilterChange,
            )
        }

        Spacer(Modifier.height(16.dp))

        FullTestPanel(
            serviceStarted = serviceStarted,
            selectedProfile = state.servers.firstOrNull { it.profileId == selectedProfileId },
            result = state.fullTests[selectedProfileId],
            running = state.fullTestRunning,
            notice = state.fullTestNotice,
            suggestedFailover = state.suggestedTcpFailover,
            onRun = { onRunFullTest(selectedProfileId, serviceStarted) },
            onCancel = onCancelFullTest,
            onClear = onClearFullTest,
            onSuggestedFailover = onSuggestedFailover,
        )

        Spacer(Modifier.height(12.dp))

        // ---- List -------------------------------------------------------------
        val rows = state.visibleServers
        if (rows.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = stringResource(R.string.tarn_servers_empty),
                        style = TarnMetaStyle,
                        color = TarnColors.TextDim,
                        modifier = Modifier.padding(horizontal = 40.dp),
                        textAlign = TextAlign.Center,
                    )
                    Spacer(Modifier.height(16.dp))
                    Text(
                        text = stringResource(R.string.tarn_servers_add_action).uppercase(),
                        style = TarnLabelStyle,
                        color = TarnColors.Accent,
                        modifier = Modifier.clickable(onClick = { showImportDialog = true }),
                    )
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(
                    horizontal = 20.dp,
                    vertical = 4.dp,
                ),
            ) {
                items(rows, key = { it.profileId }) { server ->
                    ServerRow(
                        server = server,
                        latency = state.latencies[server.profileId] ?: Latency.Unknown,
                        selected = server.profileId == selectedProfileId,
                        favourite = state.favourites.contains(server.profileId),
                        onClick = { onSelect(server.profileId) },
                        onToggleFavourite = { onToggleFavourite(server.profileId) },
                        onDelete = { pendingDelete = server },
                    )
                }
            }
        }
    }
}

@Composable
private fun FullTestPanel(
    serviceStarted: Boolean,
    selectedProfile: ServerEntry?,
    result: TarnFullTestResult?,
    running: Boolean,
    notice: String?,
    suggestedFailover: ServerEntry?,
    onRun: () -> Unit,
    onCancel: () -> Unit,
    onClear: () -> Unit,
    onSuggestedFailover: (Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    val canRun = serviceStarted && selectedProfile != null
    val statusText = when {
        !serviceStarted -> stringResource(R.string.tarn_full_test_requires_vpn)
        selectedProfile == null -> stringResource(R.string.tarn_full_test_select_running)
        result is TarnFullTestResult.Testing -> stringResource(R.string.tarn_full_test_running)
        result is TarnFullTestResult.Success -> {
            stringResource(R.string.tarn_full_test_success, result.millis, result.attempts)
        }
        result is TarnFullTestResult.Failed -> {
            stringResource(R.string.tarn_full_test_failed, result.message)
        }
        result is TarnFullTestResult.Cancelled -> stringResource(R.string.tarn_full_test_cancelled)
        else -> stringResource(R.string.tarn_full_test_active_only)
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp)
            .background(TarnColors.Surface)
            .hairlineBorder(TarnColors.BorderDim)
            .padding(horizontal = 14.dp, vertical = 12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.tarn_full_test_title),
                    style = TarnLabelStyle,
                    color = TarnColors.TextPrimary,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = statusText,
                    style = TarnMetaStyle,
                    color = when (result) {
                        is TarnFullTestResult.Success -> TarnColors.Accent
                        is TarnFullTestResult.Failed -> TarnColors.Danger
                        else -> TarnColors.TextDim
                    },
                )
                if (!notice.isNullOrBlank() && result !is TarnFullTestResult.Failed) {
                    Spacer(Modifier.height(4.dp))
                    Text(text = notice, style = TarnMetaStyle, color = TarnColors.TextDim)
                }
            }
            Spacer(Modifier.width(12.dp))
            if (running) {
                CircularProgressIndicator(
                    modifier = Modifier.size(18.dp),
                    color = TarnColors.Accent,
                    strokeWidth = 2.dp,
                )
                Spacer(Modifier.width(10.dp))
            }
            Text(
                text = if (running) {
                    stringResource(R.string.tarn_full_test_cancel)
                } else {
                    stringResource(R.string.tarn_full_test_run)
                },
                style = TarnLabelStyle,
                color = when {
                    running -> TarnColors.Danger
                    canRun -> TarnColors.Accent
                    else -> TarnColors.TextDim
                },
                modifier = Modifier
                    .clickable(enabled = running || canRun) {
                        if (running) onCancel() else onRun()
                    }
                    .padding(vertical = 6.dp),
            )
        }

        if (!running && result != null) {
            Spacer(Modifier.height(8.dp))
            Text(
                text = stringResource(R.string.tarn_full_test_clear),
                style = TarnLabelStyle,
                color = TarnColors.TextSecondary,
                modifier = Modifier.clickable(onClick = onClear),
            )
        }

        if (suggestedFailover != null) {
            Spacer(Modifier.height(10.dp))
            Text(
                text = stringResource(R.string.tarn_full_test_tcp_fallback, suggestedFailover.displayName),
                style = TarnMetaStyle,
                color = TarnColors.TextDim,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                text = stringResource(R.string.tarn_full_test_select_tcp_fallback),
                style = TarnLabelStyle,
                color = TarnColors.Accent,
                modifier = Modifier.clickable {
                    onSuggestedFailover(suggestedFailover.profileId)
                },
            )
        }
    }
}

@Composable
private fun CountryFilterRow(
    options: List<TarnCountryOption>,
    selected: String?,
    onSelect: (String?) -> Unit,
) {
    LazyRow(
        modifier = Modifier.fillMaxWidth(),
        contentPadding = PaddingValues(horizontal = 20.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item(key = "all") {
            CountryChip(
                label = stringResource(R.string.tarn_servers_country_all),
                selected = selected == null,
                onClick = { onSelect(null) },
            )
        }
        items(options, key = { it.code }) { option ->
            CountryChip(
                label = "${option.flag} ${option.count}",
                selected = selected == option.code,
                onClick = { onSelect(option.code) },
            )
        }
    }
}

@Composable
private fun CountryChip(label: String, selected: Boolean, onClick: () -> Unit) {
    val shape = RoundedCornerShape(6.dp)
    // clip() precedes the border, so the rectangular hairline stroke is clipped to the rounded
    // corners — hairlineBorder itself only draws a rectangle.
    val base = if (selected) {
        Modifier.background(TarnColors.Accent)
    } else {
        Modifier
            .background(TarnColors.Surface)
            .hairlineBorder(color = TarnColors.BorderDim)
    }
    Text(
        text = label,
        style = TarnLabelStyle,
        color = if (selected) TarnColors.Background else TarnColors.TextSecondary,
        maxLines = 1,
        modifier = Modifier
            .clip(shape)
            .then(base)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 7.dp),
    )
}

@Composable
private fun ServerRow(
    server: ServerEntry,
    latency: Latency,
    selected: Boolean,
    favourite: Boolean,
    onClick: () -> Unit,
    onToggleFavourite: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val rowModifier = if (selected) {
        modifier
            .fillMaxWidth()
            .background(TarnColors.Surface)
            .hairlineBorder(color = TarnColors.Accent)
            .cornerBrackets(color = TarnColors.Accent, length = 8.dp)
    } else {
        modifier.fillMaxWidth()
    }

    Column {
        Row(
            modifier = rowModifier
                .clickable(onClick = onClick)
                .padding(horizontal = 14.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Reachability dot
            Box(
                modifier = Modifier
                    .size(6.dp)
                    .clip(CircleShape)
                    .background(
                        when (latency) {
                            is Latency.Ok -> TarnColors.Accent
                            Latency.Unreachable -> TarnColors.Danger
                            else -> TarnColors.Offline
                        },
                    ),
            )
            Spacer(Modifier.width(14.dp))

            if (server.flag.isNotEmpty()) {
                Text(text = server.flag, style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.width(12.dp))
            }

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = server.displayName,
                    style = MaterialTheme.typography.titleSmall,
                    color = TarnColors.TextPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = server.tag,
                    style = TarnMetaStyle,
                    color = TarnColors.TextDim,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                // A frozen profile is visually identical to a working one, and every setting
                // the user changes is a silent no-op on it. Say so on the row itself.
                if (!server.managed) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = stringResource(R.string.tarn_servers_unmanaged),
                        style = TarnMetaStyle,
                        color = TarnColors.Danger,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                if (server.insecureTls) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = stringResource(R.string.tarn_servers_insecure_tls),
                        style = TarnMetaStyle,
                        color = TarnColors.Danger,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }

            Spacer(Modifier.width(12.dp))
            TarnLatencyReadout(latency)

            if (selected || favourite) {
                Spacer(Modifier.width(10.dp))
                IconButton(onClick = onToggleFavourite, modifier = Modifier.size(24.dp)) {
                    Icon(
                        imageVector = if (favourite) Icons.Default.Star else Icons.Default.StarBorder,
                        contentDescription = stringResource(R.string.tarn_servers_favourite),
                        tint = if (favourite) TarnColors.Accent else TarnColors.TextDim,
                        modifier = Modifier.size(16.dp),
                    )
                }
            }

            Spacer(Modifier.width(4.dp))
            IconButton(onClick = onDelete, modifier = Modifier.size(24.dp)) {
                Icon(
                    imageVector = Icons.Default.Delete,
                    contentDescription = stringResource(R.string.tarn_servers_delete),
                    tint = TarnColors.TextDim,
                    modifier = Modifier.size(16.dp),
                )
            }
        }
        if (!selected) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 34.dp)
                    .height(1.dp)
                    .background(TarnColors.BorderDim),
            )
        }
    }
}

@Composable
private fun TarnDeleteConfirmDialog(
    serverName: String,
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
                text = stringResource(R.string.tarn_servers_delete_confirm_title).uppercase(),
                style = TarnLabelStyle,
                color = TarnColors.TextPrimary,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = stringResource(R.string.tarn_servers_delete_confirm_message, serverName),
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
                    text = stringResource(R.string.tarn_servers_delete_confirm_action),
                    onClick = onConfirm,
                    emphasised = true,
                )
            }
        }
    }
}
