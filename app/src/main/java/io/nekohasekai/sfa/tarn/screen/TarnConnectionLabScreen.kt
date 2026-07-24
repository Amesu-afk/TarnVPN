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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import io.nekohasekai.sfa.R
import io.nekohasekai.sfa.tarn.component.TarnDialogTextButton
import io.nekohasekai.sfa.tarn.component.TarnNavRow
import io.nekohasekai.sfa.tarn.component.TarnPanel
import io.nekohasekai.sfa.tarn.component.TarnRowDivider
import io.nekohasekai.sfa.tarn.component.TarnSectionLabel
import io.nekohasekai.sfa.tarn.component.TarnToggleRow
import io.nekohasekai.sfa.tarn.component.hairlineBorder
import io.nekohasekai.sfa.tarn.theme.TarnColors
import io.nekohasekai.sfa.tarn.theme.TarnLabelStyle
import io.nekohasekai.sfa.tarn.theme.TarnMetaStyle
import java.net.URI

/** Stable values shared by the UI and its settings-backed host. */
object TarnConnectionLabValue {
    const val AUTO = "auto"
    const val QUIC_ALLOW = "allow"
    const val QUIC_BLOCK = "block"
    const val IP_IPV4_ONLY = "ipv4_only"
    const val IP_PREFER_IPV4 = "prefer_ipv4"
    const val IP_PREFER_IPV6 = "prefer_ipv6"
    const val DNS_DIRECT = "direct"
    const val DNS_TUNNEL = "tunnel"
    const val LOG_WARN = "warn"
    const val LOG_INFO = "info"
    const val LOG_DEBUG = "debug"
    const val LOG_TRACE = "trace"
    const val MTU_AUTO = 0
    const val DEFAULT_TEST_URL = "https://www.gstatic.com/generate_204"
}

/** Presentation state only; persistence and service reloads remain in the Tarn shell. */
data class TarnConnectionLabState(
    val quicPolicy: String = TarnConnectionLabValue.AUTO,
    val mtu: Int = TarnConnectionLabValue.MTU_AUTO,
    val ipStrategy: String = TarnConnectionLabValue.AUTO,
    val dnsRoute: String = TarnConnectionLabValue.AUTO,
    val dnsProtection: Boolean = true,
    val logLevel: String = TarnConnectionLabValue.LOG_WARN,
    val sendHostname: Boolean = false,
    val testUrl: String = TarnConnectionLabValue.DEFAULT_TEST_URL,
    val testTimeoutSeconds: Int = 5,
    val testRetries: Int = 2,
    val networkRecovery: Boolean = false,
    val failover: Boolean = false,
)

@Composable
fun TarnConnectionLabScreen(
    state: TarnConnectionLabState,
    onBack: () -> Unit,
    onQuicPolicyChange: (String) -> Unit,
    onMtuChange: (Int) -> Unit,
    onIpStrategyChange: (String) -> Unit,
    onDnsRouteChange: (String) -> Unit,
    onLogLevelChange: (String) -> Unit,
    onOpenLogs: () -> Unit,
    onSendHostnameChange: (Boolean) -> Unit,
    onTestUrlChange: (String) -> Unit,
    onTestTimeoutChange: (Int) -> Unit,
    onTestRetriesChange: (Int) -> Unit,
    onNetworkRecoveryChange: (Boolean) -> Unit,
    onFailoverChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(TarnColors.Background)
            .statusBarsPadding()
            .navigationBarsPadding(),
    ) {
        ConnectionLabHeader(onBack = onBack)
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp),
        ) {
            Text(
                text = stringResource(R.string.tarn_connection_lab_intro),
                style = TarnMetaStyle,
                color = TarnColors.TextDim,
            )

            Spacer(Modifier.height(24.dp))
            TarnSectionLabel(text = stringResource(R.string.tarn_connection_lab_section_network))
            Spacer(Modifier.height(10.dp))
            TarnPanel {
                LabOptionRow(
                    title = stringResource(R.string.tarn_connection_lab_quic),
                    selectedValue = state.quicPolicy,
                    options = listOf(
                        TarnConnectionLabValue.AUTO to stringResource(R.string.tarn_connection_lab_auto),
                        TarnConnectionLabValue.QUIC_ALLOW to stringResource(R.string.tarn_connection_lab_quic_allow),
                        TarnConnectionLabValue.QUIC_BLOCK to stringResource(R.string.tarn_connection_lab_quic_block),
                    ),
                    onSelect = onQuicPolicyChange,
                )
                TarnRowDivider()
                LabOptionRow(
                    title = stringResource(R.string.tarn_connection_lab_mtu),
                    selectedValue = state.mtu,
                    options = listOf(
                        TarnConnectionLabValue.MTU_AUTO to stringResource(R.string.tarn_connection_lab_auto),
                        1280 to "1280",
                        1360 to "1360",
                        1400 to "1400",
                        1500 to "1500",
                    ),
                    onSelect = onMtuChange,
                )
                TarnRowDivider()
                LabOptionRow(
                    title = stringResource(R.string.tarn_connection_lab_ip_strategy),
                    selectedValue = state.ipStrategy,
                    options = listOf(
                        TarnConnectionLabValue.AUTO to stringResource(R.string.tarn_connection_lab_auto),
                        TarnConnectionLabValue.IP_IPV4_ONLY to stringResource(R.string.tarn_connection_lab_ip_ipv4_only),
                        TarnConnectionLabValue.IP_PREFER_IPV4 to stringResource(R.string.tarn_connection_lab_ip_prefer_ipv4),
                        TarnConnectionLabValue.IP_PREFER_IPV6 to stringResource(R.string.tarn_connection_lab_ip_prefer_ipv6),
                    ),
                    onSelect = onIpStrategyChange,
                )
                TarnRowDivider()
                LabOptionRow(
                    title = stringResource(R.string.tarn_connection_lab_dns_route),
                    selectedValue = state.dnsRoute,
                    options = listOf(
                        TarnConnectionLabValue.AUTO to stringResource(R.string.tarn_connection_lab_auto),
                        TarnConnectionLabValue.DNS_DIRECT to stringResource(R.string.tarn_connection_lab_dns_direct),
                        TarnConnectionLabValue.DNS_TUNNEL to stringResource(R.string.tarn_connection_lab_dns_tunnel),
                    ),
                    onSelect = onDnsRouteChange,
                )
                TarnRowDivider()
                LabOptionRow(
                    title = stringResource(R.string.tarn_connection_lab_log_level),
                    selectedValue = state.logLevel,
                    options = listOf(
                        TarnConnectionLabValue.LOG_WARN to stringResource(R.string.tarn_connection_lab_log_warn),
                        TarnConnectionLabValue.LOG_INFO to stringResource(R.string.tarn_connection_lab_log_info),
                        TarnConnectionLabValue.LOG_DEBUG to stringResource(R.string.tarn_connection_lab_log_debug),
                        TarnConnectionLabValue.LOG_TRACE to stringResource(R.string.tarn_connection_lab_log_trace),
                    ),
                    onSelect = onLogLevelChange,
                )
                TarnRowDivider()
                // Choosing a log level is useless without somewhere to read the result. The
                // viewer existed, but only behind an unmarked tap on the version line — put
                // the way in next to the setting that produces the output.
                TarnNavRow(
                    title = stringResource(R.string.tarn_connection_lab_open_logs),
                    value = stringResource(R.string.tarn_connection_lab_open_logs_hint),
                    onClick = onOpenLogs,
                )
                TarnRowDivider()
                TarnToggleRow(
                    title = stringResource(R.string.tarn_connection_lab_send_hostname),
                    description = stringResource(R.string.tarn_connection_lab_send_hostname_desc),
                    checked = state.sendHostname,
                    onCheckedChange = onSendHostnameChange,
                )
            }
            if (!state.dnsProtection) {
                Spacer(Modifier.height(10.dp))
                LabWarning(text = stringResource(R.string.tarn_connection_lab_dns_route_disabled))
            }
            if (state.ipStrategy == TarnConnectionLabValue.IP_IPV4_ONLY) {
                Spacer(Modifier.height(10.dp))
                LabWarning(text = stringResource(R.string.tarn_connection_lab_ipv4_only_warning))
            }
            if (state.logLevel == TarnConnectionLabValue.LOG_DEBUG ||
                state.logLevel == TarnConnectionLabValue.LOG_TRACE
            ) {
                Spacer(Modifier.height(10.dp))
                LabWarning(text = stringResource(R.string.tarn_connection_lab_debug_warning))
            }

            Spacer(Modifier.height(28.dp))
            TarnSectionLabel(text = stringResource(R.string.tarn_connection_lab_section_test))
            Spacer(Modifier.height(10.dp))
            TarnPanel {
                TestUrlEditor(savedUrl = state.testUrl, onSave = onTestUrlChange)
                TarnRowDivider()
                LabOptionRow(
                    title = stringResource(R.string.tarn_connection_lab_timeout),
                    selectedValue = state.testTimeoutSeconds,
                    options = listOf(3, 5, 10, 15).map {
                        it to stringResource(R.string.tarn_connection_lab_timeout_value, it)
                    },
                    onSelect = onTestTimeoutChange,
                )
                TarnRowDivider()
                LabOptionRow(
                    title = stringResource(R.string.tarn_connection_lab_retries),
                    selectedValue = state.testRetries,
                    options = listOf(1, 2, 3, 5).map {
                        it to stringResource(R.string.tarn_connection_lab_retries_value, it)
                    },
                    onSelect = onTestRetriesChange,
                )
            }

            Spacer(Modifier.height(28.dp))
            TarnSectionLabel(text = stringResource(R.string.tarn_connection_lab_section_recovery))
            Spacer(Modifier.height(10.dp))
            TarnPanel {
                TarnToggleRow(
                    title = stringResource(R.string.tarn_connection_lab_network_recovery),
                    description = stringResource(R.string.tarn_connection_lab_network_recovery_desc),
                    checked = state.networkRecovery,
                    onCheckedChange = onNetworkRecoveryChange,
                )
                TarnRowDivider()
                TarnToggleRow(
                    title = stringResource(R.string.tarn_connection_lab_failover),
                    description = stringResource(R.string.tarn_connection_lab_failover_desc),
                    checked = state.failover,
                    onCheckedChange = onFailoverChange,
                )
            }
            if (state.failover) {
                Spacer(Modifier.height(10.dp))
                LabWarning(text = stringResource(R.string.tarn_connection_lab_failover_warning))
            }

            Spacer(Modifier.height(24.dp))
            Text(
                text = stringResource(R.string.tarn_connection_lab_apply_hint),
                style = TarnMetaStyle,
                color = TarnColors.TextDim,
            )
            Spacer(Modifier.height(40.dp))
        }
    }
}

@Composable
private fun ConnectionLabHeader(onBack: () -> Unit) {
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
            text = stringResource(R.string.tarn_connection_lab_title).uppercase(),
            style = MaterialTheme.typography.titleLarge,
            color = TarnColors.TextPrimary,
            textAlign = TextAlign.Center,
            modifier = Modifier.weight(1f),
        )
        Spacer(Modifier.size(48.dp))
    }
}

@Composable
private fun <T> LabOptionRow(
    title: String,
    selectedValue: T,
    options: List<Pair<T, String>>,
    onSelect: (T) -> Unit,
) {
    var showDialog by remember { mutableStateOf(false) }
    val selectedLabel = options.firstOrNull { it.first == selectedValue }?.second
        ?: options.firstOrNull()?.second.orEmpty()

    TarnNavRow(
        title = title,
        value = selectedLabel,
        onClick = { showDialog = true },
        chevron = Icons.AutoMirrored.Filled.KeyboardArrowRight,
    )
    if (showDialog) {
        Dialog(onDismissRequest = { showDialog = false }) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(TarnColors.Surface)
                    .hairlineBorder(color = TarnColors.Border)
                    .padding(vertical = 12.dp),
            ) {
                Text(
                    text = title.uppercase(),
                    style = TarnLabelStyle,
                    color = TarnColors.TextPrimary,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
                )
                options.forEachIndexed { index, (value, label) ->
                    val selected = value == selectedValue
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                onSelect(value)
                                showDialog = false
                            }
                            .padding(horizontal = 20.dp, vertical = 14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = label,
                            style = MaterialTheme.typography.bodyMedium,
                            color = if (selected) TarnColors.Accent else TarnColors.TextSecondary,
                            modifier = Modifier.weight(1f),
                        )
                        if (selected) {
                            Icon(
                                imageVector = Icons.Default.Check,
                                contentDescription = null,
                                tint = TarnColors.Accent,
                                modifier = Modifier.size(16.dp),
                            )
                        }
                    }
                    if (index != options.lastIndex) TarnRowDivider()
                }
            }
        }
    }
}

@Composable
private fun TestUrlEditor(savedUrl: String, onSave: (String) -> Unit) {
    var draft by rememberSaveable(savedUrl) { mutableStateOf(savedUrl) }
    val trimmed = draft.trim()
    val valid = remember(trimmed) { isValidHttpsUrl(trimmed) }
    val changed = trimmed != savedUrl

    Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp)) {
        Text(
            text = stringResource(R.string.tarn_connection_lab_test_url),
            style = MaterialTheme.typography.bodyMedium,
            color = TarnColors.TextPrimary,
        )
        Spacer(Modifier.height(6.dp))
        Text(
            text = stringResource(R.string.tarn_connection_lab_test_url_hint),
            style = TarnMetaStyle,
            color = TarnColors.TextDim,
        )
        Spacer(Modifier.height(12.dp))
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp)
                .hairlineBorder(color = if (draft.isNotBlank() && !valid) TarnColors.Danger else TarnColors.BorderDim)
                .padding(horizontal = 12.dp, vertical = 13.dp),
            contentAlignment = Alignment.CenterStart,
        ) {
            BasicTextField(
                value = draft,
                onValueChange = { draft = it },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                textStyle = MaterialTheme.typography.bodyMedium.copy(color = TarnColors.TextPrimary),
                cursorBrush = SolidColor(TarnColors.Accent),
                modifier = Modifier.fillMaxWidth(),
            )
        }
        if (draft.isNotBlank() && !valid) {
            Spacer(Modifier.height(8.dp))
            Text(
                text = stringResource(R.string.tarn_connection_lab_test_url_error),
                style = TarnMetaStyle,
                color = TarnColors.Danger,
            )
        }
        Spacer(Modifier.height(8.dp))
        Text(
            text = stringResource(R.string.tarn_connection_lab_test_url_security_hint),
            style = TarnMetaStyle,
            color = TarnColors.TextDim,
        )
        Spacer(Modifier.height(10.dp))
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            TarnDialogTextButton(
                text = stringResource(R.string.tarn_action_save),
                onClick = { onSave(trimmed) },
                enabled = valid && changed,
                emphasised = true,
            )
        }
    }
}

@Composable
private fun LabWarning(text: String) {
    Text(
        text = text,
        style = TarnMetaStyle,
        color = TarnColors.Danger,
        modifier = Modifier
            .fillMaxWidth()
            .background(TarnColors.Danger.copy(alpha = 0.08f))
            .hairlineBorder(color = TarnColors.Danger.copy(alpha = 0.45f))
            .padding(12.dp),
    )
}

private fun isValidHttpsUrl(value: String): Boolean = runCatching {
    val uri = URI(value)
    uri.scheme.equals("https", ignoreCase = true) &&
        !uri.host.isNullOrBlank() &&
        uri.userInfo == null
}.getOrDefault(false)
