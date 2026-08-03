package io.nekohasekai.sfa.database

import android.os.Build
import android.net.Uri
import androidx.room.Room
import io.nekohasekai.sfa.Application
import io.nekohasekai.sfa.BuildConfig
import io.nekohasekai.sfa.bg.ProxyService
import io.nekohasekai.sfa.bg.VPNService
import io.nekohasekai.sfa.constant.Path
import io.nekohasekai.sfa.constant.ServiceMode
import io.nekohasekai.sfa.constant.SettingsKey
import io.nekohasekai.sfa.database.preference.KeyValueDatabase
import io.nekohasekai.sfa.database.preference.RoomPreferenceDataStore
import io.nekohasekai.sfa.ktx.boolean
import io.nekohasekai.sfa.ktx.int
import io.nekohasekai.sfa.ktx.long
import io.nekohasekai.sfa.ktx.map
import io.nekohasekai.sfa.ktx.string
import io.nekohasekai.sfa.ktx.stringSet
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.io.File

object Settings {
    @OptIn(DelicateCoroutinesApi::class)
    private val instance by lazy {
        Application.application.getDatabasePath(Path.SETTINGS_DATABASE_PATH).parentFile?.mkdirs()
        Room.databaseBuilder(
            Application.application,
            KeyValueDatabase::class.java,
            Path.SETTINGS_DATABASE_PATH,
        ).allowMainThreadQueries()
            .fallbackToDestructiveMigration()
            .enableMultiInstanceInvalidation()
            .setQueryExecutor { GlobalScope.launch { it.run() } }
            .build()
    }
    val dataStore = RoomPreferenceDataStore(instance.keyValuePairDao())
    var selectedProfile by dataStore.long(SettingsKey.SELECTED_PROFILE) { -1L }
    var serviceMode by dataStore.string(SettingsKey.SERVICE_MODE) { ServiceMode.NORMAL }
    var startedByUser by dataStore.boolean(SettingsKey.STARTED_BY_USER)

    var updateSource by dataStore.string(SettingsKey.UPDATE_SOURCE) { "github" }
    // On by default, unlike upstream SFA. TarnVPN is handed to people who install it by
    // sideloading a signed APK: nothing else will ever tell them a fixed build exists, and a
    // stale VPN client is a security problem rather than a missing nicety. It stays a toggle in
    // settings, and the only thing it costs is one unauthenticated request to api.github.com.
    var checkUpdateEnabled by dataStore.boolean(SettingsKey.CHECK_UPDATE_ENABLED) { true }

    // Consequently the "may we check for updates?" prompt has nothing left to ask — defaulting it
    // to "already asked" keeps it from offering to switch on something that is on. It is still
    // honoured if some older install wrote `false` into it.
    var updateCheckPrompted by dataStore.boolean(SettingsKey.UPDATE_CHECK_PROMPTED) { true }
    var updateTrack by dataStore.string(SettingsKey.UPDATE_TRACK) {
        val versionName = BuildConfig.VERSION_NAME.lowercase()
        if (versionName.contains("-alpha") ||
            versionName.contains("-beta") ||
            versionName.contains("-rc")
        ) {
            "beta"
        } else {
            "stable"
        }
    }
    var githubToken by dataStore.string(SettingsKey.GITHUB_TOKEN) { "" }
    var silentInstallEnabled by dataStore.boolean(SettingsKey.SILENT_INSTALL_ENABLED) { false }
    var silentInstallMethod by dataStore.string(SettingsKey.SILENT_INSTALL_METHOD) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            "PACKAGE_INSTALLER"
        } else {
            "SHIZUKU"
        }
    }
    var fdroidMirrorUrl by dataStore.string(SettingsKey.FDROID_MIRROR_URL) { "https://f-droid.org/repo" }
    var fdroidCustomMirrors by dataStore.stringSet(SettingsKey.FDROID_CUSTOM_MIRRORS) { emptySet() }
    var autoUpdateEnabled by dataStore.boolean(SettingsKey.AUTO_UPDATE_ENABLED) { false }
    var dynamicNotification by dataStore.boolean(SettingsKey.DYNAMIC_NOTIFICATION) { true }
    var disableDeprecatedWarnings by dataStore.boolean(SettingsKey.DISABLE_DEPRECATED_WARNINGS) { false }

    const val PER_APP_PROXY_DISABLED = 0
    const val PER_APP_PROXY_EXCLUDE = 1
    const val PER_APP_PROXY_INCLUDE = 2

    var autoRedirect by dataStore.boolean(SettingsKey.AUTO_REDIRECT) { false }
    var perAppProxyEnabled by dataStore.boolean(SettingsKey.PER_APP_PROXY_ENABLED) { false }
    var perAppProxyMode by dataStore.int(SettingsKey.PER_APP_PROXY_MODE) { PER_APP_PROXY_EXCLUDE }
    var perAppProxyList by dataStore.stringSet(SettingsKey.PER_APP_PROXY_LIST) { emptySet() }
    var perAppProxyManagedMode by dataStore.boolean(SettingsKey.PER_APP_PROXY_MANAGED_MODE) { false }
    var perAppProxyManagedList by dataStore.stringSet(SettingsKey.PER_APP_PROXY_MANAGED_LIST) { emptySet() }

    const val PACKAGE_QUERY_MODE_SHIZUKU = "SHIZUKU"
    const val PACKAGE_QUERY_MODE_ROOT = "ROOT"
    var perAppProxyPackageQueryMode by dataStore.string(SettingsKey.PER_APP_PROXY_PACKAGE_QUERY_MODE) { PACKAGE_QUERY_MODE_SHIZUKU }

    fun getEffectivePerAppProxyMode(): Int = if (perAppProxyManagedMode) {
        PER_APP_PROXY_EXCLUDE
    } else {
        perAppProxyMode
    }

    fun getEffectivePerAppProxyList(): Set<String> = if (perAppProxyManagedMode) {
        perAppProxyManagedList
    } else {
        perAppProxyList
    }

    var allowBypass by dataStore.boolean(SettingsKey.ALLOW_BYPASS) { false }
    var systemProxyEnabled by dataStore.boolean(SettingsKey.SYSTEM_PROXY_ENABLED) { true }

    var privilegeSettingsEnabled by dataStore.boolean(SettingsKey.PRIVILEGE_SETTINGS_ENABLED) { false }
    var privilegeSettingsList by dataStore.stringSet(SettingsKey.PRIVILEGE_SETTINGS_LIST) { emptySet() }
    var privilegeSettingsInterfaceRenameEnabled by dataStore.boolean(
        SettingsKey.PRIVILEGE_SETTINGS_INTERFACE_RENAME_ENABLED,
    ) { false }
    var privilegeSettingsInterfacePrefix by dataStore.string(SettingsKey.PRIVILEGE_SETTINGS_INTERFACE_PREFIX) { "wlan" }

    var oomKillerEnabled by dataStore.boolean(SettingsKey.OOM_KILLER_ENABLED) { false }
    var oomKillerDisabled by dataStore.boolean(SettingsKey.OOM_KILLER_DISABLED) { true }
    var oomMemoryLimitMB by dataStore.int(SettingsKey.OOM_MEMORY_LIMIT_MB) { 50 }

    var dashboardItemOrder by dataStore.string(SettingsKey.DASHBOARD_ITEM_ORDER) { "" }
    var dashboardDisabledItems by dataStore.stringSet(SettingsKey.DASHBOARD_DISABLED_ITEMS) { emptySet() }

    var activeRemoteServerId by dataStore.long(SettingsKey.ACTIVE_REMOTE_SERVER_ID) { 0L }

    // TarnVPN shell.
    /** Start the tunnel as soon as the app is opened. */
    var tarnAutoConnect by dataStore.boolean(SettingsKey.TARN_AUTO_CONNECT) { false }

    /**
     * Resolve DNS over HTTPS through [tarnDnsProvider] instead of plain UDP. Read by
     * `VlessImporter` when it writes the dns block of a profile.
     */
    var tarnDnsProtection by dataStore.boolean(SettingsKey.TARN_DNS_PROTECTION) { true }

    /** Id of the chosen resolver — see `TarnDns.OPTIONS`, or `TarnDns.CUSTOM_ID`. */
    var tarnDnsProvider by dataStore.string(SettingsKey.TARN_DNS_PROVIDER) { "cloudflare" }

    /** IPv4 address for the user's own resolver, when [tarnDnsProvider] is `TarnDns.CUSTOM_ID`. */
    var tarnDnsCustomServer by dataStore.string(SettingsKey.TARN_DNS_CUSTOM_SERVER) { "" }

    /**
     * Whether destinations may be reached over IPv6. On → dns `prefer_ipv4` (use IPv6 when a
     * host has no usable A record); off → `ipv4_only` (never resolve AAAA). Either way the tun
     * always carries an IPv6 address so auto_route installs a ::/0 route: native IPv6 can never
     * escape around the IPv4 tunnel. Withholding that address is what used to turn this toggle
     * being off into a real leak, so it no longer gates the address — only the dns strategy.
     */
    var tarnIpv6Enabled by dataStore.boolean(SettingsKey.TARN_IPV6_ENABLED) { true }

    /**
     * Split the TLS handshake at the TCP layer ([common/tlsfragment] in the core) so DPI
     * that blocks on handshake shape rather than SNI content has less to match on. The current
     * REALITY handshake path does not support this wrapper, so the importer omits those flags
     * there instead of presenting this as an extra layer of protection.
     */
    var tarnFragmentEnabled by dataStore.boolean(SettingsKey.TARN_FRAGMENT_ENABLED) { false }

    /** Profile ids starred on the servers screen. */
    var tarnFavouriteProfiles by dataStore.stringSet(SettingsKey.TARN_FAVOURITE_PROFILES) { emptySet() }

    const val THEME_MODE_SYSTEM = "system"
    const val THEME_MODE_LIGHT = "light"
    const val THEME_MODE_DARK = "dark"

    /**
     * Defaults to [THEME_MODE_DARK] rather than [THEME_MODE_SYSTEM]: the shell shipped
     * dark-only for a while, so an existing install should not suddenly go light under
     * anyone who happens to run a light system theme.
     */
    var tarnThemeMode by dataStore.string(SettingsKey.TARN_THEME_MODE) { THEME_MODE_DARK }

    const val QUIC_POLICY_AUTO = "auto"
    const val QUIC_POLICY_ALLOW = "allow"
    const val QUIC_POLICY_BLOCK = "block"

    private var tarnQuicPolicyStored by dataStore.string(SettingsKey.TARN_QUIC_POLICY) { QUIC_POLICY_AUTO }
    var tarnQuicPolicy: String
        get() = normalizeChoice(
            tarnQuicPolicyStored,
            setOf(QUIC_POLICY_AUTO, QUIC_POLICY_ALLOW, QUIC_POLICY_BLOCK),
            QUIC_POLICY_AUTO,
        )
        set(value) {
            tarnQuicPolicyStored = normalizeChoice(
                value,
                setOf(QUIC_POLICY_AUTO, QUIC_POLICY_ALLOW, QUIC_POLICY_BLOCK),
                QUIC_POLICY_AUTO,
            )
        }

    val TARN_TUN_MTU_VALUES = setOf(0, 1280, 1360, 1400, 1500)
    private var tarnTunMtuStored by dataStore.int(SettingsKey.TARN_TUN_MTU) { 0 }
    var tarnTunMtu: Int
        get() = tarnTunMtuStored.takeIf(TARN_TUN_MTU_VALUES::contains) ?: 0
        set(value) {
            tarnTunMtuStored = value.takeIf(TARN_TUN_MTU_VALUES::contains) ?: 0
        }

    const val IP_STRATEGY_AUTO = "auto"
    const val IP_STRATEGY_IPV4_ONLY = "ipv4_only"
    const val IP_STRATEGY_PREFER_IPV4 = "prefer_ipv4"
    const val IP_STRATEGY_PREFER_IPV6 = "prefer_ipv6"

    private val IP_STRATEGY_VALUES = setOf(
        IP_STRATEGY_AUTO,
        IP_STRATEGY_IPV4_ONLY,
        IP_STRATEGY_PREFER_IPV4,
        IP_STRATEGY_PREFER_IPV6,
    )

    private var tarnIpStrategyStored by dataStore.string(SettingsKey.TARN_IP_STRATEGY) { IP_STRATEGY_AUTO }
    var tarnIpStrategy: String
        get() = normalizeChoice(tarnIpStrategyStored, IP_STRATEGY_VALUES, IP_STRATEGY_AUTO)
        set(value) {
            tarnIpStrategyStored = normalizeChoice(value, IP_STRATEGY_VALUES, IP_STRATEGY_AUTO)
        }

    /** Keeps pre-026 installs on their previous IPv6 behaviour while `auto` is selected. */
    fun effectiveTarnIpStrategy(ipStrategy: String = tarnIpStrategy, legacyIpv6Enabled: Boolean = tarnIpv6Enabled): String =
        when (ipStrategy) {
            IP_STRATEGY_IPV4_ONLY, IP_STRATEGY_PREFER_IPV4, IP_STRATEGY_PREFER_IPV6 -> ipStrategy
            else -> if (legacyIpv6Enabled) IP_STRATEGY_PREFER_IPV4 else IP_STRATEGY_IPV4_ONLY
        }

    /**
     * Send the sniffed hostname to the proxy server instead of the address the phone already
     * resolved — what Xray (and therefore v2rayNG) does by default.
     *
     * On by default since 23.07.2026: without it Gemini answered "not available in your region"
     * while every other service worked, because the exit connected to whichever Google
     * front-end the *phone* had picked instead of choosing one itself. Turning this on fixed it
     * outright, and it is the behaviour every Xray-based client already has.
     *
     * Known trade-off: once the destination is a domain rather than an address, `ip_cidr` and
     * `ip_is_private` rules stop matching sniffed connections. In practice that means a LAN
     * service reached by hostname over TLS would go through the tunnel instead of direct.
     * Left as a toggle so it can be turned off if a server mishandles domain destinations.
     */
    var tarnSendHostname by dataStore.boolean(SettingsKey.TARN_SEND_HOSTNAME) { true }

    const val DNS_ROUTE_AUTO = "auto"
    const val DNS_ROUTE_DIRECT = "direct"
    const val DNS_ROUTE_TUNNEL = "tunnel"

    private var tarnDnsRouteStored by dataStore.string(SettingsKey.TARN_DNS_ROUTE) { DNS_ROUTE_AUTO }
    var tarnDnsRoute: String
        get() = normalizeChoice(
            tarnDnsRouteStored,
            setOf(DNS_ROUTE_AUTO, DNS_ROUTE_DIRECT, DNS_ROUTE_TUNNEL),
            DNS_ROUTE_AUTO,
        )
        set(value) {
            tarnDnsRouteStored = normalizeChoice(
                value,
                setOf(DNS_ROUTE_AUTO, DNS_ROUTE_DIRECT, DNS_ROUTE_TUNNEL),
                DNS_ROUTE_AUTO,
            )
        }

    const val LOG_LEVEL_WARN = "warn"
    const val LOG_LEVEL_INFO = "info"
    const val LOG_LEVEL_DEBUG = "debug"

    /**
     * Below debug. Only this level surfaces the XTLS Vision trace ("XtlsFilterTls found tls 1.3",
     * "XtlsWrite writeV", "XtlsRead readV"), which is the one way to see whether Vision negotiated
     * direct mode on a raw-TCP/REALITY profile without changing the server.
     */
    const val LOG_LEVEL_TRACE = "trace"

    private var tarnLogLevelStored by dataStore.string(SettingsKey.TARN_LOG_LEVEL) { LOG_LEVEL_WARN }
    var tarnLogLevel: String
        get() = normalizeChoice(
            tarnLogLevelStored,
            setOf(LOG_LEVEL_WARN, LOG_LEVEL_INFO, LOG_LEVEL_DEBUG, LOG_LEVEL_TRACE),
            LOG_LEVEL_WARN,
        )
        set(value) {
            tarnLogLevelStored = normalizeChoice(
                value,
                setOf(LOG_LEVEL_WARN, LOG_LEVEL_INFO, LOG_LEVEL_DEBUG),
                LOG_LEVEL_WARN,
            )
        }

    const val DEFAULT_TARN_TEST_URL = "https://www.gstatic.com/generate_204"
    private var tarnTestUrlStored by dataStore.string(SettingsKey.TARN_TEST_URL) { DEFAULT_TARN_TEST_URL }
    var tarnTestUrl: String
        get() = tarnTestUrlStored.trim().takeIf(::isValidTarnTestUrl) ?: DEFAULT_TARN_TEST_URL
        set(value) {
            tarnTestUrlStored = value.trim().takeIf(::isValidTarnTestUrl) ?: DEFAULT_TARN_TEST_URL
        }

    fun isValidTarnTestUrl(value: String): Boolean {
        if (value.length !in 1..2048) return false
        val uri = runCatching { Uri.parse(value) }.getOrNull() ?: return false
        return uri.scheme.equals("https", ignoreCase = true) &&
            !uri.host.isNullOrBlank() &&
            uri.userInfo.isNullOrBlank()
    }

    val TARN_TEST_TIMEOUT_VALUES = setOf(3, 5, 10, 15)
    private var tarnTestTimeoutSecondsStored by dataStore.int(SettingsKey.TARN_TEST_TIMEOUT_SECONDS) { 10 }
    var tarnTestTimeoutSeconds: Int
        get() = tarnTestTimeoutSecondsStored.takeIf(TARN_TEST_TIMEOUT_VALUES::contains) ?: 10
        set(value) {
            tarnTestTimeoutSecondsStored = value.takeIf(TARN_TEST_TIMEOUT_VALUES::contains) ?: 10
        }

    val TARN_TEST_RETRY_VALUES = setOf(1, 2, 3, 5)
    private var tarnTestRetriesStored by dataStore.int(SettingsKey.TARN_TEST_RETRIES) { 2 }
    var tarnTestRetries: Int
        get() = tarnTestRetriesStored.takeIf(TARN_TEST_RETRY_VALUES::contains) ?: 2
        set(value) {
            tarnTestRetriesStored = value.takeIf(TARN_TEST_RETRY_VALUES::contains) ?: 2
        }

    var tarnNetworkRecovery by dataStore.boolean(SettingsKey.TARN_NETWORK_RECOVERY) { true }
    var tarnAutoFailover by dataStore.boolean(SettingsKey.TARN_AUTO_FAILOVER) { false }

    /**
     * Which [io.nekohasekai.sfa.utils.VlessImporter.CONFIG_GENERATION] the stored profile files
     * were written by. Zero on a fresh install, which is below every real generation, so the
     * one-off repatch in the shell also covers a profile restored from a backup.
     */
    var tarnConfigGeneration by dataStore.int(SettingsKey.TARN_CONFIG_GENERATION) { 0 }

    /**
     * Prevents applications from routing around an active tunnel. This is not Android's
     * system Lockdown VPN; that protection is configured in Android Settings.
     */
    var tarnKillSwitch: Boolean
        get() = !allowBypass
        set(value) {
            allowBypass = !value
        }

    private fun normalizeChoice(value: String, allowed: Set<String>, fallback: String): String =
        value.takeIf(allowed::contains) ?: fallback

    // Tailscale SSH
    var tailscaleSSHRememberedUsernames by dataStore.map(SettingsKey.TAILSCALE_SSH_REMEMBERED_USERNAMES)
    var tailscaleSSHQuickConnectPeers by dataStore.stringSet(SettingsKey.TAILSCALE_SSH_QUICK_CONNECT_PEERS)
    var tailscaleSSHLightTheme by dataStore.string(SettingsKey.TAILSCALE_SSH_LIGHT_THEME) { "base16-3024-light" }
    var tailscaleSSHDarkTheme by dataStore.string(SettingsKey.TAILSCALE_SSH_DARK_THEME) { "argonaut" }
    var tailscaleSSHFontFamily by dataStore.string(SettingsKey.TAILSCALE_SSH_FONT_FAMILY)
    var tailscaleSSHFontSize by dataStore.int(SettingsKey.TAILSCALE_SSH_FONT_SIZE) { 14 }
    var tailscaleSSHCustomFontPath by dataStore.string(SettingsKey.TAILSCALE_SSH_CUSTOM_FONT_PATH)

    var cachedUpdateInfo by dataStore.string(SettingsKey.CACHED_UPDATE_INFO) { "" }
    var cachedApkPath by dataStore.string(SettingsKey.CACHED_APK_PATH) { "" }
    var lastShownUpdateVersion by dataStore.int(SettingsKey.LAST_SHOWN_UPDATE_VERSION) { 0 }

    /** When the last automatic update check ran, for the interval in [io.nekohasekai.sfa.update.UpdateChecks]. */
    var lastUpdateCheckAt by dataStore.long(SettingsKey.LAST_UPDATE_CHECK_AT) { 0L }

    fun serviceClass(): Class<*> = when (serviceMode) {
        ServiceMode.VPN -> VPNService::class.java
        else -> ProxyService::class.java
    }

    suspend fun rebuildServiceMode(): Boolean {
        var newMode = ServiceMode.NORMAL
        try {
            if (needVPNService()) {
                newMode = ServiceMode.VPN
            }
        } catch (_: Exception) {
        }
        if (serviceMode == newMode) {
            return false
        }
        serviceMode = newMode
        return true
    }

    private suspend fun needVPNService(): Boolean {
        val selectedProfileId = selectedProfile
        if (selectedProfileId == -1L) return false
        val profile = ProfileManager.get(selectedProfile) ?: return false
        val content = JSONObject(File(profile.typed.path).readText())
        val inbounds = content.getJSONArray("inbounds")
        for (index in 0 until inbounds.length()) {
            val inbound = inbounds.getJSONObject(index)
            if (inbound.getString("type") == "tun") {
                return true
            }
        }
        return false
    }
}
