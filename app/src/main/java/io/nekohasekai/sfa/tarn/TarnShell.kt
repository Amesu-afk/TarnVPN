package io.nekohasekai.sfa.tarn

import android.content.Intent
import android.net.Uri
import android.util.Log
import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import io.nekohasekai.libbox.Libbox
import io.nekohasekai.sfa.BuildConfig
import io.nekohasekai.sfa.R
import io.nekohasekai.sfa.compose.base.GlobalEventBus
import io.nekohasekai.sfa.compose.base.UiEvent
import io.nekohasekai.sfa.compose.component.UpdateFlowHost
import io.nekohasekai.sfa.compose.screen.dashboard.DashboardViewModel
import io.nekohasekai.sfa.compose.screen.log.LogScreen
import io.nekohasekai.sfa.compose.screen.profileoverride.PerAppProxyScreen
import io.nekohasekai.sfa.compose.topbar.LocalTopBarController
import io.nekohasekai.sfa.compose.topbar.TopBarController
import io.nekohasekai.sfa.compose.topbar.TopBarEntry
import io.nekohasekai.sfa.constant.Status
import io.nekohasekai.sfa.database.Settings
import io.nekohasekai.sfa.tarn.data.Latency
import io.nekohasekai.sfa.tarn.data.TarnDns
import io.nekohasekai.sfa.tarn.data.TarnServerRepository
import io.nekohasekai.sfa.tarn.screen.TarnDnsScreen
import io.nekohasekai.sfa.tarn.screen.TarnDnsViewModel
import io.nekohasekai.sfa.tarn.screen.TarnConnectionLabScreen
import io.nekohasekai.sfa.tarn.screen.TarnConnectionLabState
import io.nekohasekai.sfa.tarn.screen.TarnHomeScreen
import io.nekohasekai.sfa.tarn.screen.TarnServersScreen
import io.nekohasekai.sfa.tarn.screen.TarnServersViewModel
import io.nekohasekai.sfa.tarn.screen.TarnShieldScreen
import io.nekohasekai.sfa.tarn.screen.TarnShieldState
import io.nekohasekai.sfa.tarn.screen.TarnSubscriptionsScreen
import io.nekohasekai.sfa.tarn.screen.TarnSubscriptionsViewModel
import io.nekohasekai.sfa.tarn.theme.TarnColors
import io.nekohasekai.sfa.update.UpdateChecks
import io.nekohasekai.sfa.utils.VlessImporter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private object TarnRoute {
    const val HOME = "tarn/home"
    const val SERVERS = "tarn/servers"
    const val SUBSCRIPTIONS = "tarn/subscriptions"
    const val SHIELD = "tarn/shield"
    const val SPLIT_TUNNELING = "tarn/split"
    const val DNS = "tarn/dns"
    const val CONNECTION_LAB = "tarn/connection-lab"
    const val LOGS = "tarn/logs"
}

private val slideIn: AnimatedContentTransitionScope<*>.() -> androidx.compose.animation.EnterTransition = {
    slideIntoContainer(AnimatedContentTransitionScope.SlideDirection.Left, animationSpec = tween(280))
}
private val slideOut: AnimatedContentTransitionScope<*>.() -> androidx.compose.animation.ExitTransition = {
    slideOutOfContainer(AnimatedContentTransitionScope.SlideDirection.Left, animationSpec = tween(280))
}
private val popIn: AnimatedContentTransitionScope<*>.() -> androidx.compose.animation.EnterTransition = {
    slideIntoContainer(AnimatedContentTransitionScope.SlideDirection.Right, animationSpec = tween(280))
}
private val popOut: AnimatedContentTransitionScope<*>.() -> androidx.compose.animation.ExitTransition = {
    slideOutOfContainer(AnimatedContentTransitionScope.SlideDirection.Right, animationSpec = tween(280))
}

/**
 * The TarnVPN interface: three screens over the unchanged sing-box service layer.
 *
 * Service control is delegated to [DashboardViewModel] so profile switching keeps the
 * upstream reload/restart logic; this shell only decides what the user sees.
 *
 * @param onOpenAdvanced hands control back to the original sing-box interface.
 */
@Composable
fun TarnShell(
    serviceStatus: Status,
    dashboardViewModel: DashboardViewModel,
    onOpenAdvanced: () -> Unit,
    themeMode: String,
    onThemeModeChange: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val navController = rememberNavController()
    val serversViewModel: TarnServersViewModel = viewModel()
    val serversState by serversViewModel.uiState.collectAsState()
    val subscriptionsViewModel: TarnSubscriptionsViewModel = viewModel()
    val subscriptionsState by subscriptionsViewModel.uiState.collectAsState()
    val dashboardState by dashboardViewModel.uiState.collectAsState()

    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }
    val applyFailedMessage = stringResource(R.string.tarn_apply_failed)

    var killSwitch by remember { mutableStateOf(Settings.tarnKillSwitch) }
    var dnsProtection by remember { mutableStateOf(Settings.tarnDnsProtection) }
    var autoConnect by remember { mutableStateOf(Settings.tarnAutoConnect) }

    var splitEnabled by remember { mutableStateOf(Settings.perAppProxyEnabled) }
    var splitMode by remember { mutableIntStateOf(Settings.perAppProxyMode) }
    var splitAppCount by remember { mutableIntStateOf(Settings.perAppProxyList.size) }

    var dnsProvider by remember { mutableStateOf(Settings.tarnDnsProvider) }
    var dnsCustomServer by remember { mutableStateOf(Settings.tarnDnsCustomServer) }

    var ipv6Enabled by remember { mutableStateOf(Settings.tarnIpv6Enabled) }
    var fragmentEnabled by remember { mutableStateOf(Settings.tarnFragmentEnabled) }

    var quicPolicy by remember { mutableStateOf(Settings.tarnQuicPolicy) }
    var tunMtu by remember { mutableIntStateOf(Settings.tarnTunMtu) }
    var ipStrategy by remember { mutableStateOf(Settings.tarnIpStrategy) }
    var dnsRoute by remember { mutableStateOf(Settings.tarnDnsRoute) }
    var logLevel by remember { mutableStateOf(Settings.tarnLogLevel) }
    var sendHostname by remember { mutableStateOf(Settings.tarnSendHostname) }
    var testUrl by remember { mutableStateOf(Settings.tarnTestUrl) }
    var testTimeoutSeconds by remember { mutableIntStateOf(Settings.tarnTestTimeoutSeconds) }
    var testRetries by remember { mutableIntStateOf(Settings.tarnTestRetries) }
    var networkRecovery by remember { mutableStateOf(Settings.tarnNetworkRecovery) }
    var autoFailover by remember { mutableStateOf(Settings.tarnAutoFailover) }

    // Per-app rules and the dns block are baked into the config when the service starts, so
    // a running one has to be reloaded for a change to mean anything. Upstream asks first
    // via a snackbar in SFAApp(), which this shell never composes — so apply it directly.
    suspend fun reloadRunningService() {
        if (serviceStatus != Status.Started) return
        val failure = withContext(Dispatchers.IO) {
            runCatching { Libbox.newStandaloneCommandClient().serviceReload() }.exceptionOrNull()
        }
        if (failure != null) {
            Log.w("TarnShell", "service reload failed", failure)
            // A failed reload leaves the toggle showing its new position while the running
            // service keeps the old behaviour. Staying silent about that is indistinguishable
            // from the setting not doing anything, which is how it reads to the user.
            snackbarHostState.showSnackbar(applyFailedMessage)
        }
    }

    fun applyToRunningService() {
        scope.launch { reloadRunningService() }
    }

    // DNS, IPv6 and fragmentation are all baked into a profile's config file once at import
    // time (see VlessImporter), so a change to any of them has to be pushed into every
    // stored profile rather than just the next one created — otherwise flipping a toggle
    // off would do nothing for servers imported before the flip.
    fun repatchSettingsAndReload() {
        scope.launch {
            // repatchSettings runs Libbox.checkConfig on every profile it rewrites and throws
            // when the core rejects one. Unguarded, that exception escaped this launch and
            // took the whole app down on what is a recoverable per-profile problem. Report it
            // and still reload: checkConfig is exactly what stops a rejected config from ever
            // reaching disk, so whatever is on disk remains loadable.
            val failure = withContext(Dispatchers.IO) {
                runCatching { TarnServerRepository.repatchSettings() }.exceptionOrNull()
            }
            if (failure != null) {
                Log.w("TarnShell", "repatch settings failed", failure)
                snackbarHostState.showSnackbar(applyFailedMessage)
                return@launch
            }
            reloadRunningService()
        }
    }

    fun onSelectDns(id: String) {
        dnsProvider = id
        Settings.tarnDnsProvider = id
        repatchSettingsAndReload()
    }

    // Saving a custom address also activates it — a bare "save but don't select" state
    // would be a confusing thing to leave the user in after they just typed an address in.
    fun onSaveCustomDns(address: String) {
        dnsCustomServer = address
        Settings.tarnDnsCustomServer = address
        onSelectDns(TarnDns.CUSTOM_ID)
    }

    // A plain on/off face for the three-way DNS route (Lab keeps the full Auto/Direct/Tunnel
    // choice): on = force tunnel (leak test shows the exit, DNS pays the tunnel round-trip),
    // off = direct (fast, but the resolver sees the real region). Stays in sync with the Lab
    // through the shared dnsRoute state.
    fun onDnsThroughVpnChange(enabled: Boolean) {
        dnsRoute = if (enabled) Settings.DNS_ROUTE_TUNNEL else Settings.DNS_ROUTE_DIRECT
        Settings.tarnDnsRoute = dnsRoute
        repatchSettingsAndReload()
    }

    fun onIpv6Change(enabled: Boolean) {
        ipv6Enabled = enabled
        Settings.tarnIpv6Enabled = enabled
        ipStrategy = if (enabled) Settings.IP_STRATEGY_AUTO else Settings.IP_STRATEGY_IPV4_ONLY
        Settings.tarnIpStrategy = ipStrategy
        repatchSettingsAndReload()
    }

    fun onIpStrategyChange(value: String) {
        ipStrategy = value
        Settings.tarnIpStrategy = value
        // Keep the legacy shield toggle and pre-026 setting aligned with the richer mode.
        // Derived from the effective strategy rather than compared against one named value:
        // every mode that resolves to ipv4_only means "IPv6 off" on the shield, and listing
        // them by hand is what silently turned the toggle back on the first time a new
        // IPv4-flavoured mode was added.
        // legacyIpv6Enabled = true so `auto` keeps reading as IPv6-on, as it always has.
        ipv6Enabled = Settings.effectiveTarnIpStrategy(value, legacyIpv6Enabled = true) !=
            Settings.IP_STRATEGY_IPV4_ONLY
        Settings.tarnIpv6Enabled = ipv6Enabled
        repatchSettingsAndReload()
    }

    fun onFragmentChange(enabled: Boolean) {
        fragmentEnabled = enabled
        Settings.tarnFragmentEnabled = enabled
        repatchSettingsAndReload()
    }

    // The reused upstream app picker announces its edits this way rather than calling back.
    // Keyed on Unit, not serviceStatus: reloadRunningService() already checks the status
    // itself, and re-keying here would cancel-and-restart this collector on every
    // start/stop transition — a real gap, since a toggle applied right as the service
    // finishes starting could land in the moment between the old collector's cancellation
    // and the new one's registration and simply be dropped.
    LaunchedEffect(Unit) {
        GlobalEventBus.events.collect { event ->
            if (event is UiEvent.ApplyServiceChange) {
                applyToRunningService()
            }
        }
    }

    // That same picker writes the app list straight to settings, so re-read it whenever the
    // protection screen comes back into view instead of keeping a copy in sync.
    val currentRoute by navController.currentBackStackEntryAsState()
    LaunchedEffect(currentRoute?.destination?.route) {
        if (currentRoute?.destination?.route == TarnRoute.SHIELD) {
            splitEnabled = Settings.perAppProxyEnabled
            splitMode = Settings.perAppProxyMode
            splitAppCount = Settings.perAppProxyList.size
        }
    }

    val selectedProfileId = dashboardState.selectedProfileId
    val selectedServer = serversState.servers.firstOrNull { it.profileId == selectedProfileId }

    // Re-measure whenever the tunnel comes up or goes down: the numbers shown before and
    // after a connection are not comparable otherwise.
    LaunchedEffect(serviceStatus) {
        if (serviceStatus == Status.Started || serviceStatus == Status.Stopped) {
            serversViewModel.probeAll()
        }
    }

    // Ask about a new version once the tunnel is up. This is the check that can actually
    // succeed: api.github.com is not dependably reachable from the networks this app is built
    // for, so the cold-start check in MainActivity.onCreate() often fails before it asks
    // anything. UpdateChecks keeps its own interval, so reconnecting repeatedly does not
    // hammer the API.
    LaunchedEffect(serviceStatus) {
        if (serviceStatus == Status.Started) {
            UpdateChecks.runIfDue()
        }
    }

    // A profile's config file is generated once, at import. So a build whose generator emits
    // something new leaves every already-added server on the old shape, and the change looks
    // like it does nothing — until the user happens to flip an unrelated toggle, which is the
    // only thing that ever called repatchSettings(). Stamp the generation profiles were
    // written by and repatch once when the build moves past it.
    //
    // Keyed on serviceStatus rather than Unit because of autoconnect: it starts the service
    // from the old file at the same moment this runs, so the reload has to happen when the
    // service is actually up, which may be after the rewrite finishes.
    var repatchedGeneration by remember { mutableStateOf(false) }
    LaunchedEffect(serviceStatus) {
        if (!repatchedGeneration &&
            Settings.tarnConfigGeneration != VlessImporter.CONFIG_GENERATION
        ) {
            val failure = withContext(Dispatchers.IO) {
                runCatching { TarnServerRepository.repatchSettings() }.exceptionOrNull()
            }
            if (failure != null) {
                // Leave the stamp alone so the next launch retries, and stay silent: nobody
                // asked for this, and a snackbar about a setting the user never touched reads
                // as the app being broken rather than one profile being unrewritable.
                Log.w("TarnShell", "config generation repatch failed", failure)
                return@LaunchedEffect
            }
            Settings.tarnConfigGeneration = VlessImporter.CONFIG_GENERATION
            repatchedGeneration = true
        }
        if (repatchedGeneration && serviceStatus == Status.Started) {
            // Once: a running service is now on the new config, and later start/stop cycles
            // read the rewritten file themselves.
            repatchedGeneration = false
            reloadRunningService()
        }
    }

    // A Box, not a Scaffold: every Tarn screen already draws its own chrome, and the only
    // thing missing at shell level is somewhere for a failure to be seen. Overlaying the host
    // leaves the screens untouched.
    Box(modifier = modifier.fillMaxSize()) {
        NavHost(
            navController = navController,
            startDestination = TarnRoute.HOME,
            modifier = Modifier.fillMaxSize(),
        ) {
            composable(TarnRoute.HOME) {
                TarnHomeScreen(
                    serviceStatus = serviceStatus,
                    serverName = selectedServer?.displayName ?: dashboardState.selectedProfileName,
                    serverTag = selectedServer?.tag,
                    latency = serversState.latencies[selectedProfileId] ?: Latency.Unknown,
                    startTime = dashboardState.serviceStartTime,
                    onToggleConnection = { dashboardViewModel.toggleService() },
                    onOpenServers = { navController.navigate(TarnRoute.SERVERS) },
                    onOpenShield = { navController.navigate(TarnRoute.SHIELD) },
                )
            }

            composable(
                route = TarnRoute.SERVERS,
                enterTransition = slideIn,
                exitTransition = slideOut,
                popEnterTransition = popIn,
                popExitTransition = popOut,
            ) {
                TarnServersScreen(
                    state = serversState,
                    selectedProfileId = selectedProfileId,
                    onBack = { navController.navigateUp() },
                    onSelect = { profileId ->
                        dashboardViewModel.selectProfile(profileId)
                        navController.navigateUp()
                    },
                    onToggleFavourite = serversViewModel::toggleFavourite,
                    onQueryChange = serversViewModel::setQuery,
                    onRecommendedOnlyChange = serversViewModel::setRecommendedOnly,
                    onCountryFilterChange = serversViewModel::setCountryFilter,
                    onImport = serversViewModel::importServers,
                    onDismissImportError = serversViewModel::dismissImportError,
                    onDelete = serversViewModel::deleteServer,
                    onOpenSubscriptions = { navController.navigate(TarnRoute.SUBSCRIPTIONS) },
                    serviceStarted = serviceStatus == Status.Started,
                    onRunFullTest = serversViewModel::runFullTest,
                    onCancelFullTest = serversViewModel::cancelFullTest,
                    onClearFullTest = serversViewModel::clearFullTest,
                    onSuggestedFailover = { profileId ->
                        serversViewModel.clearFullTest()
                        dashboardViewModel.selectProfile(profileId)
                    },
                )
            }

            composable(
                route = TarnRoute.SUBSCRIPTIONS,
                enterTransition = slideIn,
                exitTransition = slideOut,
                popEnterTransition = popIn,
                popExitTransition = popOut,
            ) {
                TarnSubscriptionsScreen(
                    state = subscriptionsState,
                    onBack = { navController.navigateUp() },
                    onRefresh = subscriptionsViewModel::refresh,
                    onDelete = subscriptionsViewModel::delete,
                    onToggleAutoUpdate = subscriptionsViewModel::setAutoUpdate,
                    onDismissError = subscriptionsViewModel::dismissError,
                )
            }

            composable(
                route = TarnRoute.SHIELD,
                enterTransition = slideIn,
                exitTransition = slideOut,
                popEnterTransition = popIn,
                popExitTransition = popOut,
            ) {
                TarnShieldScreen(
                    state = TarnShieldState(
                        killSwitch = killSwitch,
                        dnsProtection = dnsProtection,
                        autoConnect = autoConnect,
                        splitEnabled = splitEnabled,
                        splitMode = splitMode,
                        splitAppCount = splitAppCount,
                        dnsProviderName = TarnDns.byId(dnsProvider).title,
                        dnsThroughVpn = dnsRoute == Settings.DNS_ROUTE_TUNNEL,
                        ipv6Enabled = ipv6Enabled,
                        fragmentEnabled = fragmentEnabled,
                        themeMode = themeMode,
                        protocol = selectedServer?.protocol?.uppercase() ?: "—",
                        versionName = BuildConfig.VERSION_NAME,
                        versionCode = BuildConfig.VERSION_CODE,
                    ),
                    onBack = { navController.navigateUp() },
                    onKillSwitchChange = {
                        killSwitch = it
                        // allowBypass is read when the tun device is (re)established, same as
                        // upstream's own equivalent toggle in ServiceSettingsScreen — a plain
                        // reload picks it up, no restart needed.
                        Settings.tarnKillSwitch = it
                        applyToRunningService()
                    },
                    onDnsProtectionChange = {
                        dnsProtection = it
                        Settings.tarnDnsProtection = it
                        repatchSettingsAndReload()
                    },
                    onAutoConnectChange = {
                        autoConnect = it
                        Settings.tarnAutoConnect = it
                    },
                    onOpenDnsPicker = { navController.navigate(TarnRoute.DNS) },
                    onDnsThroughVpnChange = ::onDnsThroughVpnChange,
                    onIpv6Change = ::onIpv6Change,
                    onFragmentChange = ::onFragmentChange,
                    onOpenConnectionLab = { navController.navigate(TarnRoute.CONNECTION_LAB) },
                    onThemeModeChange = onThemeModeChange,
                    onSplitEnabledChange = {
                        splitEnabled = it
                        Settings.perAppProxyEnabled = it
                        applyToRunningService()
                    },
                    onSplitModeChange = {
                        splitMode = it
                        Settings.perAppProxyMode = it
                        applyToRunningService()
                    },
                    onOpenSplitTunneling = { navController.navigate(TarnRoute.SPLIT_TUNNELING) },
                    onOpenVpnSettings = {
                        // ACTION_VPN_SETTINGS is where lockdown lives. It is not guaranteed to
                        // exist on every OEM build, so fall back to the app's own settings page
                        // rather than crash on ActivityNotFoundException.
                        val context = navController.context
                        val opened = runCatching {
                            context.startActivity(
                                Intent(android.provider.Settings.ACTION_VPN_SETTINGS)
                                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                            )
                        }.isSuccess
                        if (!opened) {
                            runCatching {
                                context.startActivity(
                                    Intent(
                                        android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                        Uri.fromParts("package", context.packageName, null),
                                    ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                                )
                            }
                        }
                    },
                    onOpenAdvanced = onOpenAdvanced,
                )
            }

            composable(
                route = TarnRoute.SPLIT_TUNNELING,
                enterTransition = slideIn,
                exitTransition = slideOut,
                popEnterTransition = popIn,
                popExitTransition = popOut,
            ) {
                // Upstream screen, reused as-is — it already does per-app proxy properly. It
                // publishes its toolbar (back, search, mode menu, select-all) through
                // LocalTopBarController instead of drawing it, and that provider normally comes
                // from SFAApp(), which this shell never composes. So host it here: without this
                // the screen crashes on the missing composition local, and without rendering the
                // entry the picker would come up with no toolbar at all.
                val topBarState = remember { mutableStateOf(emptyList<TopBarEntry>()) }
                val topBarController = remember { TopBarController(topBarState) }
                CompositionLocalProvider(LocalTopBarController provides topBarController) {
                    Scaffold(
                        topBar = { topBarState.value.lastOrNull()?.content?.invoke() },
                        containerColor = TarnColors.Background,
                    ) { innerPadding ->
                        Box(modifier = Modifier.padding(innerPadding)) {
                            PerAppProxyScreen(
                                onBack = { navController.navigateUp() },
                                serviceStatus = serviceStatus,
                            )
                        }
                    }
                }
            }

            composable(
                route = TarnRoute.DNS,
                enterTransition = slideIn,
                exitTransition = slideOut,
                popEnterTransition = popIn,
                popExitTransition = popOut,
            ) {
                val dnsViewModel: TarnDnsViewModel = viewModel()
                val dnsState by dnsViewModel.uiState.collectAsState()
                TarnDnsScreen(
                    options = dnsState.options,
                    selectedId = dnsProvider,
                    customServer = dnsCustomServer,
                    pings = dnsState.pings,
                    onSelect = {
                        onSelectDns(it)
                        navController.navigateUp()
                    },
                    onSaveCustomServer = {
                        onSaveCustomDns(it)
                        navController.navigateUp()
                    },
                    onBack = { navController.navigateUp() },
                )
            }

            composable(
                route = TarnRoute.CONNECTION_LAB,
                enterTransition = slideIn,
                exitTransition = slideOut,
                popEnterTransition = popIn,
                popExitTransition = popOut,
            ) {
                TarnConnectionLabScreen(
                    state = TarnConnectionLabState(
                        quicPolicy = quicPolicy,
                        mtu = tunMtu,
                        ipStrategy = ipStrategy,
                        dnsRoute = dnsRoute,
                        dnsProtection = dnsProtection,
                        logLevel = logLevel,
                        sendHostname = sendHostname,
                        testUrl = testUrl,
                        testTimeoutSeconds = testTimeoutSeconds,
                        testRetries = testRetries,
                        networkRecovery = networkRecovery,
                        failover = autoFailover,
                    ),
                    onBack = { navController.navigateUp() },
                    onQuicPolicyChange = {
                        quicPolicy = it
                        Settings.tarnQuicPolicy = it
                        repatchSettingsAndReload()
                    },
                    onMtuChange = {
                        tunMtu = it
                        Settings.tarnTunMtu = it
                        repatchSettingsAndReload()
                    },
                    onIpStrategyChange = ::onIpStrategyChange,
                    onDnsRouteChange = {
                        dnsRoute = it
                        Settings.tarnDnsRoute = it
                        repatchSettingsAndReload()
                    },
                    onLogLevelChange = {
                        logLevel = it
                        Settings.tarnLogLevel = it
                        repatchSettingsAndReload()
                    },
                    onOpenLogs = { navController.navigate(TarnRoute.LOGS) },
                    onSendHostnameChange = {
                        sendHostname = it
                        Settings.tarnSendHostname = it
                        repatchSettingsAndReload()
                    },
                    onTestUrlChange = {
                        testUrl = it
                        Settings.tarnTestUrl = it
                        repatchSettingsAndReload()
                    },
                    onTestTimeoutChange = {
                        testTimeoutSeconds = it
                        Settings.tarnTestTimeoutSeconds = it
                    },
                    onTestRetriesChange = {
                        testRetries = it
                        Settings.tarnTestRetries = it
                    },
                    onNetworkRecoveryChange = {
                        networkRecovery = it
                        Settings.tarnNetworkRecovery = it
                        applyToRunningService()
                    },
                    onFailoverChange = {
                        autoFailover = it
                        Settings.tarnAutoFailover = it
                    },
                )
            }

            composable(
                route = TarnRoute.LOGS,
                enterTransition = slideIn,
                exitTransition = slideOut,
                popEnterTransition = popIn,
                popExitTransition = popOut,
            ) {
                // Upstream screen, same three caveats as PerAppProxyScreen above: it publishes its
                // toolbar through LocalTopBarController rather than drawing one, and that provider
                // lives in SFAApp(), which this shell never composes. Host the controller here and
                // render the entry, or the screen crashes on the missing composition local and
                // loses its back button, pause, search and save menu with it.
                val topBarState = remember { mutableStateOf(emptyList<TopBarEntry>()) }
                val topBarController = remember { TopBarController(topBarState) }
                CompositionLocalProvider(LocalTopBarController provides topBarController) {
                    Scaffold(
                        topBar = { topBarState.value.lastOrNull()?.content?.invoke() },
                        containerColor = TarnColors.Background,
                    ) { innerPadding ->
                        Box(modifier = Modifier.padding(innerPadding)) {
                            LogScreen(
                                serviceStatus = serviceStatus,
                                title = stringResource(R.string.tarn_logs_title),
                                onBack = { navController.navigateUp() },
                            )
                        }
                    }
                }
            }
        }
        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .navigationBarsPadding(),
        )

        // The update dialogs live in SFAApp(), which this shell never composes — so an update
        // could be found and cached and the user never see it. Host them here as well; both
        // shells drive the same UpdateState, so whichever is on screen shows it once.
        UpdateFlowHost()
    }
}
