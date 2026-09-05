package io.nekohasekai.sfa.compose.screen.log

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.viewmodel.compose.viewModel
import io.nekohasekai.sfa.R
import io.nekohasekai.sfa.constant.Status

@Composable
fun HookLogScreen(onBack: () -> Unit) {
    val viewModel: HookLogViewModel = viewModel()
    val context = LocalContext.current
    val title = stringResource(R.string.title_log)
    val emptyMessage = stringResource(R.string.privilege_settings_hook_logs_empty)

    LaunchedEffect(Unit) {
        viewModel.loadLogs(context)
    }

    LogScreen(
        serviceStatus = Status.Stopped,
        showStartFab = false,
        showStatusBar = false,
        title = title,
        viewModel = viewModel,
        showPause = false,
        showClear = false,
        showStatusInfo = false,
        emptyMessage = emptyMessage,
        saveFilePrefix = "hook_logs",
        onBack = onBack,
    )
}
