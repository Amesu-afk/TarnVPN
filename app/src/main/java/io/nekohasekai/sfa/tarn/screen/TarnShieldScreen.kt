package io.nekohasekai.sfa.tarn.screen

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import io.nekohasekai.sfa.R
import io.nekohasekai.sfa.database.Settings
import io.nekohasekai.sfa.tarn.component.TarnNavRow
import io.nekohasekai.sfa.tarn.component.TarnPanel
import io.nekohasekai.sfa.tarn.component.TarnRowDivider
import io.nekohasekai.sfa.tarn.component.TarnSectionLabel
import io.nekohasekai.sfa.tarn.component.TarnSegmented
import io.nekohasekai.sfa.tarn.component.TarnToggleRow
import io.nekohasekai.sfa.tarn.theme.TarnColors
import io.nekohasekai.sfa.tarn.theme.TarnMetaStyle

data class TarnShieldState(
    val killSwitch: Boolean = true,
    val dnsProtection: Boolean = true,
    val autoConnect: Boolean = false,
    val splitEnabled: Boolean = false,
    /** [Settings.PER_APP_PROXY_EXCLUDE] or [Settings.PER_APP_PROXY_INCLUDE]. */
    val splitMode: Int = Settings.PER_APP_PROXY_EXCLUDE,
    val splitAppCount: Int = 0,
    val dnsProviderName: String = "",
    /** True when DNS is routed through the tunnel (leak test shows the exit, at some speed cost). */
    val dnsThroughVpn: Boolean = false,
    /** True when Russian services bypass the tunnel — see [Settings.tarnRuDirect]. */
    val ruDirect: Boolean = true,
    /** How many domains the user added to the direct list themselves. */
    val directDomainCount: Int = 0,
    val ipv6Enabled: Boolean = true,
    val fragmentEnabled: Boolean = false,
    /** [Settings.THEME_MODE_SYSTEM], [Settings.THEME_MODE_LIGHT] or [Settings.THEME_MODE_DARK]. */
    val themeMode: String = Settings.THEME_MODE_DARK,
    val protocol: String = "—",
    val versionName: String = "",
    val versionCode: Int = 0,
)

@Composable
fun TarnShieldScreen(
    state: TarnShieldState,
    onBack: () -> Unit,
    onKillSwitchChange: (Boolean) -> Unit,
    onDnsProtectionChange: (Boolean) -> Unit,
    onAutoConnectChange: (Boolean) -> Unit,
    onOpenDnsPicker: () -> Unit,
    onDnsThroughVpnChange: (Boolean) -> Unit,
    onRuDirectChange: (Boolean) -> Unit,
    onOpenDirectDomains: () -> Unit,
    onIpv6Change: (Boolean) -> Unit,
    onFragmentChange: (Boolean) -> Unit,
    onOpenConnectionLab: () -> Unit,
    onThemeModeChange: (String) -> Unit,
    onSplitEnabledChange: (Boolean) -> Unit,
    onSplitModeChange: (Int) -> Unit,
    onOpenSplitTunneling: () -> Unit,
    onOpenVpnSettings: () -> Unit,
    onOpenAdvanced: () -> Unit,
    modifier: Modifier = Modifier,
) {
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
                text = stringResource(R.string.tarn_title_shield).uppercase(),
                style = MaterialTheme.typography.titleLarge,
                color = TarnColors.TextPrimary,
                textAlign = TextAlign.Center,
                modifier = Modifier.weight(1f),
            )
            // Balances the back button so the title stays optically centred.
            Spacer(Modifier.size(48.dp))
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp),
        ) {
            Spacer(Modifier.height(8.dp))
            TarnSectionLabel(text = stringResource(R.string.tarn_shield_section_general))
            Spacer(Modifier.height(10.dp))

            TarnPanel {
                TarnToggleRow(
                    title = stringResource(R.string.tarn_shield_kill_switch),
                    description = stringResource(R.string.tarn_shield_kill_switch_desc),
                    checked = state.killSwitch,
                    onCheckedChange = onKillSwitchChange,
                )
                TarnRowDivider()
                // Our kill switch is an inverted allowBypass: it stops apps going around a
                // *running* tunnel. It cannot cover the service being dead — a crash, a
                // reboot, the system reclaiming memory. Only Android's own lockdown does that,
                // so point at it rather than let the toggle above imply a guarantee it does
                // not make.
                TarnNavRow(
                    title = stringResource(R.string.tarn_shield_always_on),
                    value = stringResource(R.string.tarn_shield_always_on_desc),
                    onClick = onOpenVpnSettings,
                    chevron = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                )
                TarnRowDivider()
                TarnToggleRow(
                    title = stringResource(R.string.tarn_shield_dns),
                    description = stringResource(R.string.tarn_shield_dns_desc),
                    checked = state.dnsProtection,
                    onCheckedChange = onDnsProtectionChange,
                )
                TarnRowDivider()
                TarnNavRow(
                    title = stringResource(R.string.tarn_dns_provider),
                    value = state.dnsProviderName,
                    onClick = onOpenDnsPicker,
                    chevron = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                    enabled = state.dnsProtection,
                )
                TarnRowDivider()
                TarnToggleRow(
                    title = stringResource(R.string.tarn_shield_autoconnect),
                    description = stringResource(R.string.tarn_shield_autoconnect_desc),
                    checked = state.autoConnect,
                    onCheckedChange = onAutoConnectChange,
                )
            }

            Spacer(Modifier.height(28.dp))
            TarnSectionLabel(text = stringResource(R.string.tarn_shield_section_network))
            Spacer(Modifier.height(10.dp))
            TarnPanel {
                TarnToggleRow(
                    title = stringResource(R.string.tarn_shield_ru_direct),
                    description = stringResource(R.string.tarn_shield_ru_direct_desc),
                    checked = state.ruDirect,
                    onCheckedChange = onRuDirectChange,
                )
                TarnRowDivider()
                // Not gated on the toggle above: the user's own names are their own list and
                // apply whether or not the built-in Russian one does.
                TarnNavRow(
                    title = stringResource(R.string.tarn_shield_direct_domains),
                    value = if (state.directDomainCount == 0) {
                        stringResource(R.string.tarn_shield_direct_domains_empty)
                    } else {
                        pluralStringResource(
                            R.plurals.tarn_shield_direct_domains_count,
                            state.directDomainCount,
                            state.directDomainCount,
                        )
                    },
                    onClick = onOpenDirectDomains,
                    chevron = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                )
                TarnRowDivider()
                TarnToggleRow(
                    title = stringResource(R.string.tarn_shield_dns_tunnel),
                    description = stringResource(R.string.tarn_shield_dns_tunnel_desc),
                    checked = state.dnsThroughVpn,
                    onCheckedChange = onDnsThroughVpnChange,
                    enabled = state.dnsProtection,
                )
                TarnRowDivider()
                TarnToggleRow(
                    title = stringResource(R.string.tarn_shield_ipv6),
                    description = stringResource(R.string.tarn_shield_ipv6_desc),
                    checked = state.ipv6Enabled,
                    onCheckedChange = onIpv6Change,
                )
                TarnRowDivider()
                TarnToggleRow(
                    title = stringResource(R.string.tarn_shield_fragment),
                    description = stringResource(R.string.tarn_shield_fragment_desc),
                    checked = state.fragmentEnabled,
                    onCheckedChange = onFragmentChange,
                )
                TarnRowDivider()
                TarnNavRow(
                    title = stringResource(R.string.tarn_connection_lab_entry),
                    value = stringResource(R.string.tarn_connection_lab_entry_desc),
                    onClick = onOpenConnectionLab,
                    chevron = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                )
            }

            Spacer(Modifier.height(28.dp))
            TarnSectionLabel(text = stringResource(R.string.tarn_shield_section_appearance))
            Spacer(Modifier.height(10.dp))
            TarnPanel {
                TarnSegmented(
                    options = listOf(
                        stringResource(R.string.tarn_shield_theme_system),
                        stringResource(R.string.tarn_shield_theme_light),
                        stringResource(R.string.tarn_shield_theme_dark),
                    ),
                    selectedIndex = when (state.themeMode) {
                        Settings.THEME_MODE_LIGHT -> 1
                        Settings.THEME_MODE_DARK -> 2
                        else -> 0
                    },
                    onSelect = { index ->
                        onThemeModeChange(
                            when (index) {
                                1 -> Settings.THEME_MODE_LIGHT
                                2 -> Settings.THEME_MODE_DARK
                                else -> Settings.THEME_MODE_SYSTEM
                            },
                        )
                    },
                )
            }

            Spacer(Modifier.height(28.dp))
            TarnSectionLabel(text = stringResource(R.string.tarn_shield_section_split))
            Spacer(Modifier.height(10.dp))
            TarnPanel {
                // The master switch: without it the core ignores the app list entirely.
                TarnToggleRow(
                    title = stringResource(R.string.tarn_shield_split),
                    description = stringResource(R.string.tarn_shield_split_desc),
                    checked = state.splitEnabled,
                    onCheckedChange = onSplitEnabledChange,
                )
                if (state.splitEnabled) {
                    TarnRowDivider()
                    TarnSegmented(
                        options = listOf(
                            stringResource(R.string.tarn_shield_split_mode_exclude),
                            stringResource(R.string.tarn_shield_split_mode_include),
                        ),
                        selectedIndex = if (state.splitMode == Settings.PER_APP_PROXY_INCLUDE) 1 else 0,
                        onSelect = { index ->
                            onSplitModeChange(
                                if (index == 1) {
                                    Settings.PER_APP_PROXY_INCLUDE
                                } else {
                                    Settings.PER_APP_PROXY_EXCLUDE
                                },
                            )
                        },
                    )
                }
                TarnRowDivider()
                TarnNavRow(
                    title = stringResource(R.string.tarn_shield_split_apps),
                    value = if (state.splitAppCount > 0) {
                        pluralStringResource(
                            R.plurals.tarn_shield_split_apps_count,
                            state.splitAppCount,
                            state.splitAppCount,
                        )
                    } else {
                        stringResource(R.string.tarn_shield_split_apps_none)
                    },
                    onClick = onOpenSplitTunneling,
                    chevron = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                    enabled = state.splitEnabled,
                )
            }

            Spacer(Modifier.height(28.dp))
            TarnSectionLabel(text = stringResource(R.string.tarn_shield_section_protocol))
            Spacer(Modifier.height(10.dp))
            TarnPanel {
                // Read-only: the protocol comes from the selected profile's config.
                TarnNavRow(
                    title = stringResource(R.string.tarn_shield_protocol),
                    value = state.protocol,
                    onClick = {},
                    chevron = null,
                )
            }

            Spacer(Modifier.height(36.dp))
            // Tapping the version line is the way into the original sing-box interface.
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onOpenAdvanced)
                    .padding(vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TarnSectionLabel(
                    text = stringResource(R.string.tarn_shield_version),
                    modifier = Modifier.weight(1f),
                )
                Text(
                    text = "${state.versionName} (${state.versionCode})",
                    style = TarnMetaStyle,
                    color = TarnColors.TextDim,
                )
            }
            Spacer(Modifier.height(40.dp))
        }
    }
}
