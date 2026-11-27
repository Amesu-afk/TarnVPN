package io.nekohasekai.sfa.utils

import android.net.Uri
import android.util.Base64
import io.nekohasekai.sfa.database.Settings
import io.nekohasekai.sfa.tarn.data.DnsOption
import io.nekohasekai.sfa.tarn.data.TarnDns
import org.json.JSONArray
import org.json.JSONObject

object VlessImporter {

    /**
     * Bumped whenever [buildConfig] starts emitting something a previously stored profile does
     * not have. Profiles are files written once at import time, so a new generator alone changes
     * nothing for servers the user already added — until this number moves past the one stamped
     * in [io.nekohasekai.sfa.database.Settings.tarnConfigGeneration] and the shell repatches.
     *
     * Without it, a shipped fix reaches existing profiles only if the user happens to flip some
     * unrelated toggle, and looks like it does not work at all if they don't.
     *
     * 1 — the DNS rules that keep cold lookups off the video path (AAAA answered locally for the
     *     phone-resolved hosts, media-CDN names resolved off the tunnel).
     * 2 — the same two rules widened past YouTube to every short-video CDN in
     *     [MEDIA_CDN_SUFFIXES].
     * 3 — Russian services kept off the tunnel ([RU_DIRECT_SUFFIXES]), routed direct and
     *     resolved off-tunnel so they are not handed a foreign edge.
     * 4 — TCP keep-alive on the proxy outbound ([applyKeepAlive]), and `record_fragment` split
     *     off the fragmentation toggle onto its own setting.
     * 5 — the uTLS fingerprint is a setting ([applyTlsFingerprint]) rather than whatever the
     *     share link happened to ask for.
     * 6 — the direct list takes the user's own domains ([directSuffixes]) on top of the
     *     built-in Russian one.
     * 7 — the maintained Russian-services database joins the built-in direct list. The current
     *     copy is refreshed before a Tarn-managed VPN session starts and is safely cached.
     * 8 — imported links preserve their protocol-specific fields (including VLESS encryption,
     *     XHTTP extras and HTTP/h2 transport); source-backed profiles are regenerated once.
     * 9 — XHTTP + VLESS encryption keeps its valid Vision flow, and canonical Xray
     *     sessionID* fields from current 3x-ui links survive import.
     * 10 — the TUN excludes the profile's own server IP(s), so the proxy
     *     transport (Cloudflare, direct WS, WireGuard peer) is not captured by
     *     the VPN route. On Android 13+ VpnService.excludeRoute is used; this
     *     is what keeps a large Cloudflare-WS stream out of the TUN.
     * 14 — legacy plain `ss://method:password@host` links no longer run through a lenient
     *     base64 decode (it produced an unreadable method and the core refused the profile
     *     with "unknown method"); oversized subscriptions now import their first
     *     [MAX_SUBSCRIPTION_SERVERS] entries instead of failing outright.
     */
    const val CONFIG_GENERATION = 14

    private data class ConnectionConfigSettings(
        val dnsOption: DnsOption,
        val dnsProtection: Boolean,
        val legacyIpv6Enabled: Boolean,
        val fragmentEnabled: Boolean,
        val quicPolicy: String,
        val tunMtu: Int,
        val tunStack: String,
        val ipStrategy: String,
        val dnsRoute: String,
        val logLevel: String,
        val testUrl: String,
        val sendHostname: Boolean,
        val ruDirect: Boolean,
        val remoteDirectDomains: Set<String>,
        val directDomains: Set<String>,
        val recordFragment: Boolean,
        val tlsFingerprint: String,
    )

    private fun currentConnectionConfigSettings() = ConnectionConfigSettings(
        dnsOption = TarnDns.byId(Settings.tarnDnsProvider),
        dnsProtection = Settings.tarnDnsProtection,
        legacyIpv6Enabled = Settings.tarnIpv6Enabled,
        fragmentEnabled = Settings.tarnFragmentEnabled,
        quicPolicy = Settings.tarnQuicPolicy,
        tunMtu = Settings.tarnTunMtu,
        tunStack = Settings.tarnTunStack,
        ipStrategy = Settings.tarnIpStrategy,
        dnsRoute = Settings.tarnDnsRoute,
        logLevel = Settings.tarnLogLevel,
        testUrl = Settings.tarnTestUrl,
        sendHostname = Settings.tarnSendHostname,
        ruDirect = Settings.tarnRuDirect,
        remoteDirectDomains = Settings.tarnRuDirectRemoteDomains,
        directDomains = Settings.tarnDirectDomains,
        recordFragment = Settings.tarnRecordFragment,
        tlsFingerprint = Settings.tarnTlsFingerprint,
    )

    /**
     * Kept under its original name because the import dialog and its tests call it; it now
     * accepts every share link [ProxyUriParser] understands, not just vless.
     */
    fun isVlessUri(input: String): Boolean = ProxyUriParser.isSupportedUri(input)

    fun isHttpsUrl(input: String): Boolean {
        val uri = runCatching { Uri.parse(input.trim().removePrefix("\uFEFF").trim()) }.getOrNull() ?: return false
        return uri.scheme?.equals("https", ignoreCase = true) == true && !uri.host.isNullOrBlank()
    }

    /**
     * True if the pasted input is either a single supported share link or a subscription URL
     * we can expand.
     */
    fun isSupported(input: String): Boolean {
        val t = input.trim()
        return isVlessUri(t) || isHttpsUrl(t) || AwgConfParser.isAwgInput(t)
    }

    /**
     * Build sing-box JSON from a single vless URI OR from an HTTPS subscription URL.
     * For URLs, [fetch] is called to download the subscription body — always call
     * from an IO dispatcher.
     */
    fun toSingBoxJson(input: String, fetch: (String) -> String): String {
        val settings = currentConnectionConfigSettings()
        return buildConfig(parseServers(input, fetch, settings.fragmentEnabled).servers, settings)
    }

    /**
     * One profile per server, so each shows up as its own row on the servers screen.
     * [sourceUri] is the single share link this server came from; the repository persists
     * it next to the config so a later repatch can regenerate the config from source instead
     * of surgically patching (see [rebuildConfig]).
     */
    data class ImportedServer(val name: String, val config: String, val sourceUri: String)

    /** One rejected link is reported rather than silently discarded during a subscription refresh. */
    data class ImportIssue(
        val source: String,
        val reason: String,
    )

    /**
     * The parsed subscription together with the entries the app could not safely materialise.
     *
     * Callers that only need the old all-or-nothing API can use [toSingBoxConfigs]. The
     * repository uses this richer form to keep existing servers if a provider responds with an
     * incomplete or newly unsupported list instead of treating the omission as a deletion.
     */
    data class ImportedServerBatch(
        val servers: List<ImportedServer>,
        val totalEntries: Int,
        val issues: List<ImportIssue>,
        val isSubscription: Boolean,
        val truncatedCount: Int = 0,
    ) {
        val rejectedCount: Int get() = issues.size
    }

    private data class ParsedServerBatch(
        val servers: List<ProxyUriParser.ParsedOutbound>,
        val totalEntries: Int,
        val issues: List<ImportIssue>,
        val isSubscription: Boolean,
        val truncatedCount: Int = 0,
    )

    /** Lightweight parser report for tests and future import UI; it does not touch Settings. */
    internal data class SubscriptionParsePreview(
        val parsedCount: Int,
        val totalEntries: Int,
        val issues: List<ImportIssue>,
        val truncatedCount: Int = 0,
    )

    /**
     * Same inputs as [toSingBoxJson], but a subscription is split into one standalone
     * config per server instead of a single config with an urltest group. The shell treats
     * a profile as a server, so a group would collapse the whole subscription into one row.
     */
    fun toSingBoxConfigs(input: String, fetch: (String) -> String): List<ImportedServer> = toSingBoxImportBatch(input, fetch).servers

    /** Parses a link or subscription without hiding entries that failed to convert. */
    fun toSingBoxImportBatch(input: String, fetch: (String) -> String): ImportedServerBatch {
        // Read once for the whole batch: buildConfig() used to re-read both settings on
        // every call, which for an N-server subscription meant 2N redundant DataStore
        // queries for values that can't change mid-import.
        val settings = currentConnectionConfigSettings()
        val parsed = parseServers(input, fetch, settings.fragmentEnabled)
        return ImportedServerBatch(
            servers = parsed.servers.map {
                ImportedServer(it.tag, buildConfig(listOf(it), settings), it.sourceUri)
            },
            totalEntries = parsed.totalEntries,
            issues = parsed.issues,
            isSubscription = parsed.isSubscription,
            truncatedCount = parsed.truncatedCount,
        )
    }

    /**
     * Rebuilds one server's config from its original `vless://` URI using the *current*
     * settings. Repatch uses this so a stored profile regenerates to today's config shape
     * instead of relying on [applySettings], which surgically patches and gives up (returns
     * null) on any profile whose structure predates the current [isTarnManagedConfig]
     * signature — that is what used to make a toggle silently no-op on older profiles and
     * force a delete-and-re-add. Returns null if the URI no longer parses.
     */
    fun rebuildConfig(sourceUri: String): String? = runCatching {
        val settings = currentConnectionConfigSettings()
        if (AwgConfParser.isAwgInput(sourceUri)) {
            buildConfig(AwgConfParser.parse(sourceUri), settings)
        } else {
            buildConfig(listOf(ProxyUriParser.parse(sourceUri, settings.fragmentEnabled)), settings)
        }
    }.getOrNull()

    private fun parseServers(
        input: String,
        fetch: (String) -> String,
        fragmentEnabled: Boolean,
    ): ParsedServerBatch {
        val t = input.trim()
        return when {
            // A single share link is one line; a multi-line blob that starts with
            // `vless://` is a link list and must go through the subscription parser
            // (issue #14 follow-up: such a file used to import its first server only).
            isVlessUri(t) && !t.contains('\n') && !t.contains('\r') -> ParsedServerBatch(
                servers = listOf(ProxyUriParser.parse(t, fragmentEnabled)),
                totalEntries = 1,
                issues = emptyList(),
                isSubscription = false,
            )
            AwgConfParser.isAwgInput(t) -> {
                val allServers = AwgConfParser.parse(t)
                val servers = allServers.take(MAX_SUBSCRIPTION_SERVERS)
                ParsedServerBatch(
                    servers = servers,
                    totalEntries = allServers.size,
                    issues = emptyList(),
                    isSubscription = servers.size > 1,
                    truncatedCount = maxOf(0, allServers.size - servers.size),
                )
            }
            isHttpsUrl(t) -> {
                val body = fetch(t)
                parseSubscriptionBody(body, fragmentEnabled)
            }
            else -> parseSubscriptionBody(t, fragmentEnabled)
        }
    }

    private const val MAX_SUBSCRIPTION_CHARS = 8_388_608

    /** Visible to tests and the import UI copy: subscriptions are truncated, not refused. */
    internal const val MAX_SUBSCRIPTION_SERVERS = 128

    internal fun inspectSubscriptionBody(
        body: String,
        fragmentEnabled: Boolean = false,
    ): SubscriptionParsePreview {
        val parsed = parseSubscriptionBody(body, fragmentEnabled)
        return SubscriptionParsePreview(parsed.servers.size, parsed.totalEntries, parsed.issues, parsed.truncatedCount)
    }

    private fun parseSubscriptionBody(body: String, fragmentEnabled: Boolean): ParsedServerBatch {
        require(body.length <= MAX_SUBSCRIPTION_CHARS) {
            "Subscription is too large (max $MAX_SUBSCRIPTION_CHARS characters)"
        }
        val allEntries = expandSubscriptionBody(body)
        // Parse first, then cap the successfully parsed unique servers. Capping raw entries
        // let 128 duplicates (or 128 broken lines) crowd out real servers further down the
        // list. Truncation is a notice, not a parse issue: it must not make a later refresh
        // refuse to remove a genuinely missing server.
        val parsedByKey = LinkedHashMap<String, ProxyUriParser.ParsedOutbound>()
        val issues = mutableListOf<ImportIssue>()
        allEntries.forEach { entry ->
            val awg = AwgConfParser.isAwgInput(entry)
            if (!ProxyUriParser.isSupportedUri(entry) && !awg) {
                issues += ImportIssue(entry, "Unsupported share-link scheme")
            } else {
                runCatching {
                    if (awg) AwgConfParser.parse(entry) else listOf(ProxyUriParser.parse(entry, fragmentEnabled))
                }
                    .onSuccess { parsed ->
                        parsed.forEach { server ->
                            // A vpn:// bundle can expand one entry into several peers; keep
                            // them distinct while still deduplicating repeated identical links.
                            parsedByKey["${server.sourceUri}\u0000${server.tag}"] = server
                        }
                    }
                    .onFailure { error ->
                        issues += ImportIssue(entry, importIssueReason(error))
                    }
            }
        }
        val parsedServers = parsedByKey.values.toList()
        val truncatedCount = maxOf(0, parsedServers.size - MAX_SUBSCRIPTION_SERVERS)
        val servers = parsedServers.take(MAX_SUBSCRIPTION_SERVERS)
        if (servers.isEmpty() && SubscriptionFetcher.isSingBoxConfig(body)) {
            throw UnsupportedSubscriptionFormatException()
        }
        if (servers.isEmpty()) {
            throw IllegalArgumentException(
                "Subscription contained no server links we could read. " +
                    "Response was ${body.length} chars; rejected ${issues.size}/${allEntries.size} entries.",
            )
        }
        return ParsedServerBatch(
            servers = servers,
            totalEntries = allEntries.size,
            issues = issues,
            isSubscription = true,
            truncatedCount = truncatedCount,
        )
    }

    private fun importIssueReason(error: Throwable): String = error.message?.lineSequence()?.firstOrNull()?.take(180) ?: error.javaClass.simpleName

    /**
     * Rewrites every Tarn-owned connection setting of an already-stored config. The legacy
     * arguments stay in the signature so existing repository callers keep source and behaviour
     * compatibility; connection-lab arguments default to their persisted values.
     *
     * Configs that do not carry our dns block (hand-written or imported as raw JSON) are
     * returned untouched rather than rewritten into a shape their author did not ask for.
     */
    fun applySettings(
        configJson: String,
        option: DnsOption,
        dnsProtection: Boolean,
        ipv6Enabled: Boolean,
        fragmentEnabled: Boolean,
        quicPolicy: String = Settings.tarnQuicPolicy,
        tunMtu: Int = Settings.tarnTunMtu,
        tunStack: String = Settings.tarnTunStack,
        ipStrategy: String = Settings.tarnIpStrategy,
        dnsRoute: String = Settings.tarnDnsRoute,
        logLevel: String = Settings.tarnLogLevel,
        testUrl: String = Settings.tarnTestUrl,
        sendHostname: Boolean = Settings.tarnSendHostname,
        ruDirect: Boolean = Settings.tarnRuDirect,
        remoteDirectDomains: Set<String> = Settings.tarnRuDirectRemoteDomains,
        directDomains: Set<String> = Settings.tarnDirectDomains,
        recordFragment: Boolean = Settings.tarnRecordFragment,
        tlsFingerprint: String = Settings.tarnTlsFingerprint,
    ): String? {
        val config = runCatching { JSONObject(configJson) }.getOrNull() ?: return null
        if (!isTarnManagedConfig(config)) return null
        val dns = config.optJSONObject("dns") ?: return null
        val servers = dns.optJSONArray("servers") ?: return null
        val hasDoh = (0 until servers.length()).any {
            servers.optJSONObject(it)?.optString("tag") == "doh"
        }
        if (!hasDoh) return null

        val oldDohServer = findDohServer(dns)
        val route = config.optJSONObject("route")
        val routeFinal = route?.optString("final")?.takeIf { it.isNotBlank() } ?: "direct"
        val effectiveIpStrategy = Settings.effectiveTarnIpStrategy(ipStrategy, ipv6Enabled)
        val effectiveTunStack = normalizeTunStack(tunStack)
        val blockQuic = shouldBlockQuic(quicPolicy)
        val directSuffixes = directSuffixes(ruDirect, remoteDirectDomains, directDomains)
        config.put(
            "dns",
            dnsBlock(
                option,
                dnsProtection,
                effectiveIpStrategy,
                dnsRoute,
                routeFinal,
                blockQuic,
                directSuffixes,
            ),
        )
        config.put("experimental", experimentalBlock())
        config.put(
            "log",
            (config.optJSONObject("log") ?: JSONObject()).put("level", normalizeLogLevel(logLevel)),
        )

        route?.let {
            val existingRules = route.optJSONArray("rules") ?: JSONArray()
            route.put(
                "rules",
                rebuildRouteRules(
                    rules = existingRules,
                    oldDohServer = oldDohServer,
                    dnsOption = option,
                    blockQuic = blockQuic,
                    routeDnsDirect = shouldRouteDnsDirect(dnsRoute, dnsProtection, routeFinal),
                    sendHostname = sendHostname,
                    directSuffixes = directSuffixes,
                ),
            )
            route.put(
                "default_domain_resolver",
                if (usesTunnelDns(dnsRoute, dnsProtection, routeFinal)) "bootstrap" else "doh",
            )
        }

        val wireguardMtu = wireguardEndpointMtu(config)
        val inbounds = config.optJSONArray("inbounds")
        if (inbounds != null) {
            for (i in 0 until inbounds.length()) {
                val inbound = inbounds.optJSONObject(i) ?: continue
                if (inbound.optString("type") == "tun") {
                    inbound.put("address", tunAddress())
                    inbound.put("stack", effectiveTunStack)
                    val normalizedMtu = normalizeTunMtu(tunMtu)
                    val effectiveMtu = if (wireguardMtu != null) {
                        minOf(normalizedMtu.takeIf { it != 0 } ?: wireguardMtu, wireguardMtu)
                    } else {
                        normalizedMtu
                    }
                    if (effectiveMtu == 0) {
                        inbound.remove("mtu")
                    } else {
                        inbound.put("mtu", effectiveMtu)
                    }
                }
            }
        }

        applyAppProxyInbound(config)

        val outbounds = config.optJSONArray("outbounds")
        if (outbounds != null) {
            for (i in 0 until outbounds.length()) {
                val outbound = outbounds.optJSONObject(i) ?: continue
                outbound.optJSONObject("tls")?.let {
                    ProxyUriParser.applyFragment(it, fragmentEnabled, recordFragment)
                    applyTlsFingerprint(it, tlsFingerprint)
                }
                if (outbound.optString("type") in SERVER_OUTBOUND_TYPES &&
                    outbound.optString("type") != "wireguard"
                ) {
                    applyKeepAlive(outbound)
                }
                applyMultiplex(outbound)
                applyXhttpPool(outbound)
            }
            val normalizedTestUrl = normalizeTestUrl(testUrl)
            for (i in 0 until outbounds.length()) {
                val outbound = outbounds.optJSONObject(i) ?: continue
                if (outbound.optString("type") == "urltest") {
                    outbound.put("url", normalizedTestUrl)
                }
            }
        }

        return config.toString(2)
    }

    /**
     * Applies the current Tarn settings to one managed config without writing it back to disk.
     *
     * The service uses this at startup after refreshing the Russian-services database: the
     * fetched entries affect this connection immediately, while a failed fetch simply leaves
     * the last valid cached entries in place. Hand-written configs are never changed here.
     */
    fun applyCurrentSettings(configJson: String): String? {
        val settings = currentConnectionConfigSettings()
        return applySettings(
            configJson = configJson,
            option = settings.dnsOption,
            dnsProtection = settings.dnsProtection,
            ipv6Enabled = settings.legacyIpv6Enabled,
            fragmentEnabled = settings.fragmentEnabled,
            quicPolicy = settings.quicPolicy,
            tunMtu = settings.tunMtu,
            tunStack = settings.tunStack,
            ipStrategy = settings.ipStrategy,
            dnsRoute = settings.dnsRoute,
            logLevel = settings.logLevel,
            testUrl = settings.testUrl,
            sendHostname = settings.sendHostname,
            ruDirect = settings.ruDirect,
            remoteDirectDomains = settings.remoteDirectDomains,
            directDomains = settings.directDomains,
            recordFragment = settings.recordFragment,
            tlsFingerprint = settings.tlsFingerprint,
        )
    }

    /**
     * Applies only the app-level TUN stack choice to any config that owns a TUN inbound.
     * Raw/hand-written profiles are never rewritten on disk, but they still run through the
     * stack the user selected for this connection; a config without a TUN is left untouched.
     * Returns null when there is nothing to change.
     */
    fun applyTunStack(configJson: String, tunStack: String = Settings.tarnTunStack): String? {
        val config = runCatching { JSONObject(configJson) }.getOrNull() ?: return null
        val inbounds = config.optJSONArray("inbounds") ?: return null
        val effectiveTunStack = normalizeTunStack(tunStack)
        var changed = false
        for (i in 0 until inbounds.length()) {
            val inbound = inbounds.optJSONObject(i) ?: continue
            if (inbound.optString("type") != "tun") continue
            if (inbound.optString("stack") != effectiveTunStack) {
                inbound.put("stack", effectiveTunStack)
                changed = true
            }
        }
        return if (changed) config.toString(2) else null
    }

    /**
     * Whether [applySettings] would do anything to this config — the same preconditions it
     * checks before touching a byte. Exposed so the servers list can tell the user that a
     * profile is frozen, instead of leaving them to discover it by toggling settings that
     * provably do nothing (which is exactly how this was found).
     */
    fun isManagedConfig(configJson: String): Boolean {
        val config = runCatching { JSONObject(configJson) }.getOrNull() ?: return false
        if (!isTarnManagedConfig(config)) return false
        val servers = config.optJSONObject("dns")?.optJSONArray("servers") ?: return false
        return (0 until servers.length()).any {
            servers.optJSONObject(it)?.optString("tag") == "doh"
        }
    }

    /**
     * A raw profile opened through the advanced UI must never be rewritten merely because
     * its author also used the common `doh` tag. Tarn-owned configs have a deliberately
     * narrow structural signature emitted by [buildConfig].
     */
    private fun isTarnManagedConfig(config: JSONObject): Boolean {
        val inbounds = config.optJSONArray("inbounds") ?: return false
        var hasTarnTun = false
        for (i in 0 until inbounds.length()) {
            val inbound = inbounds.optJSONObject(i) ?: continue
            if (inbound.optString("type") != "tun" || inbound.optString("tag") != "tun-in") continue
            val addresses = inbound.optJSONArray("address") ?: continue
            val values = (0 until addresses.length()).map(addresses::optString).toSet()
            // Pre-hardening Tarn profiles could contain only the IPv4 address when the
            // old IPv6 switch was off. Accept that exact legacy shape for one migration;
            // arbitrary TUN address sets still fail the ownership check.
            val knownAddresses = setOf(TUN_IPV4_ADDRESS, TUN_IPV6_ADDRESS)
            if (TUN_IPV4_ADDRESS in values && values.all(knownAddresses::contains)) {
                hasTarnTun = true
                break
            }
        }
        if (!hasTarnTun) return false

        val resolver = config.optJSONObject("route")?.optString("default_domain_resolver")
        if (resolver != "doh" && resolver != "bootstrap") return false

        val outbounds = config.optJSONArray("outbounds") ?: return false
        // Any protocol the importer can emit counts — the signature is "we generated this",
        // not "this is vless". Checking for vless alone would have frozen every profile
        // imported from a trojan/ss/vmess/hysteria2/tuic/anytls link the moment those became
        // importable, silently reproducing the stale-profile bug this check exists to avoid.
        val serverTypes = SERVER_OUTBOUND_TYPES
        var hasServer = false
        var hasDirect = false
        for (i in 0 until outbounds.length()) {
            val outbound = outbounds.optJSONObject(i) ?: continue
            val type = outbound.optString("type")
            if (type == "direct") {
                hasDirect = hasDirect || outbound.optString("tag") == "direct"
            } else if (type in serverTypes) {
                hasServer = hasServer || (
                    outbound.optString("tag").isNotBlank() &&
                        outbound.optString("server").isNotBlank()
                    )
            }
        }
        if (!hasServer) {
            val endpoints = config.optJSONArray("endpoints")
            if (endpoints != null) {
                for (i in 0 until endpoints.length()) {
                    val endpoint = endpoints.optJSONObject(i) ?: continue
                    if (endpoint.optString("type") != "wireguard") continue
                    val peers = endpoint.optJSONArray("peers") ?: continue
                    var hasPeerAddress = false
                    for (peerIndex in 0 until peers.length()) {
                        val peer = peers.optJSONObject(peerIndex) ?: continue
                        if (peer.optString("address").isNotBlank()) {
                            hasPeerAddress = true
                            break
                        }
                    }
                    if (endpoint.optString("tag").isNotBlank() && hasPeerAddress) {
                        hasServer = true
                        break
                    }
                }
            }
        }
        return hasServer && hasDirect
    }

    /** MTU of the first WireGuard/AmneziaWG endpoint in [config], if any. */
    private fun wireguardEndpointMtu(config: JSONObject): Int? {
        val endpoints = config.optJSONArray("endpoints") ?: return null
        for (i in 0 until endpoints.length()) {
            val endpoint = endpoints.optJSONObject(i) ?: continue
            if (endpoint.optString("type") != "wireguard") continue
            val mtu = endpoint.optInt("mtu")
            if (mtu > 0) return mtu
        }
        return null
    }

    private fun dnsBlock(
        option: DnsOption,
        protectionEnabled: Boolean,
        ipStrategy: String,
        dnsRoute: String,
        routeFinal: String,
        blockQuic: Boolean,
        directSuffixes: List<String>,
    ): JSONObject = JSONObject().apply {
        val tunnelDns = usesTunnelDns(dnsRoute, protectionEnabled, routeFinal)
        put(
            "servers",
            JSONArray().apply {
                // DoH always connects to a literal resolver IP, so there is no plaintext
                // bootstrap lookup either way. What differs is the path:
                //   tunnelDns → detour through the proxy, so the resolver (and any leak
                //     test) sees the exit region, at the cost of a full round-trip per name.
                //   !tunnelDns → straight out the direct outbound: fast, but the resolver
                //     sees the real region. DoH still hides queries from the ISP and blocks
                //     poisoning; only the fact that this resolver is in use is visible.
                // usesTunnelDns() picks between them (auto = tunnel except on XHTTP, where
                // the over-tunnel round-trip — ~700ms measured — stalls video sessions).
                put(
                    JSONObject().apply {
                        put("tag", "doh")
                        put("type", "https")
                        put("server", option.server)
                        if (tunnelDns) put("detour", routeFinal)
                        option.tlsServerName?.let { serverName ->
                            put("tls", JSONObject().put("server_name", serverName))
                        }
                    },
                )
                if (tunnelDns) {
                    put(
                        JSONObject().apply {
                            put("tag", "bootstrap")
                            put("type", "https")
                            put("server", option.server)
                            // No detour on purpose. bootstrap must dial the resolver IP off-tunnel
                            // (it resolves the proxy's own hostname before the tunnel is up — a
                            // chicken-and-egg otherwise). A detour-less DNS server already dials
                            // straight out the underlying interface via the default dialer, which is
                            // byte-for-byte the same dialer as detouring to a settings-less "direct"
                            // outbound — so the core (rc.18+) rejects that explicit detour as a
                            // no-op: "detour to an empty direct outbound makes no sense". Dropping it
                            // keeps the exact off-tunnel behaviour and passes the check.
                            option.tlsServerName?.let { serverName ->
                                put("tls", JSONObject().put("server_name", serverName))
                            }
                        },
                    )
                }
                if (!protectionEnabled) {
                    put(
                        JSONObject().apply {
                            put("tag", "local")
                            put("type", "local")
                        },
                    )
                }
            },
        )
        val rules = JSONArray()
        // Suppressing HTTPS/SVCB answers is what actually stops HTTP/3: rejecting QUIC in
        // the route table only kills the connection *after* the browser has committed to
        // it, and Chrome learns h3 from this record (alpn="h3") before any packet is sent.
        // Without it every h3-capable host pays an attempt-then-reset-then-fall-back round
        // for each new connection, which is worst exactly where it hurts — Google
        // properties, which advertise h3 everywhere. Cost is losing ECH (also carried in
        // this record); acceptable, since we already refuse the transport it advertises.
        if (blockQuic) {
            rules.put(
                JSONObject().apply {
                    put("query_type", JSONArray().put("HTTPS"))
                    put("action", "predefined")
                    put("rcode", "NOERROR")
                },
            )
        }
        // Must precede the media rule below: an AAAA for a host on both lists is answered
        // here instead of being routed anywhere.
        rules.put(suppressAaaaRule())
        if (tunnelDns && dnsRoute == Settings.DNS_ROUTE_AUTO) rules.put(mediaDnsBootstrapRule())
        // Unconditional on the dns route, unlike the media rule above: a name whose
        // traffic goes direct has to be resolved from here too. Resolving it through
        // the exit would hand a Russian service a foreign edge address — or an address
        // it refuses — and then dial that address direct, which is the worst of both.
        if (tunnelDns && directSuffixes.isNotEmpty()) {
            rules.put(directDnsBootstrapRule(directSuffixes))
        }
        put("rules", rules)
        // DNS protection off means ordinary site lookups use the system resolver, but
        // outbound hostnames continue to use the literal-IP DoH resolver below.
        put("final", if (protectionEnabled) "doh" else "local")
        // The tun always captures IPv6, even for ipv4_only, so native IPv6 cannot leak.
        put("strategy", ipStrategy)
        // Serve-stale: an expired entry is answered from cache immediately and refreshed in
        // the background. This is what makes "DNS through VPN" usable — a cache miss there
        // costs a full round-trip through the proxy (~700ms on XHTTP, and it competes with
        // video traffic for the same transports), and page loads that touch a dozen hosts
        // paid that serially every time a short Google TTL lapsed. Only the very first
        // lookup of a name is still synchronous. Bounded at 24h so a host that has really
        // moved is re-resolved for real rather than pinned to a dead address forever.
        put("optimistic", JSONObject().put("enabled", true).put("timeout", "24h"))
    }

    /**
     * Answers AAAA for [PHONE_RESOLVE_SUFFIXES] locally, with an empty NOERROR, instead of
     * forwarding it upstream.
     *
     * These are exactly the hosts whose destination the route table replaces with a
     * phone-resolved address list ([phoneResolveRule]), and that list is v4-first under every
     * strategy this generator emits (`prefer_ipv4` with the IPv6 switch on, `ipv4_only` with it
     * off — `dns/client.go:sortAddresses`). So the AAAA answer never changes what goes on the
     * wire; what it does change is timing, and badly: the `resolve` action fires the A and the
     * AAAA lookup as a task.Group and **joins both** before the connection can be dialed
     * (`dns/router.go:631-650`). With DNS on the tunnel — the default — that second lookup is a
     * full round-trip through the proxy, paid before the first byte of a clip.
     *
     * That cost is what the IPv6 switch was really being used to avoid: it changes nothing
     * except `dns.strategy`, and `ipv4_only` makes the core answer AAAA locally
     * (`dns/client.go:190`) instead of asking. Suppressing AAAA for these hosts alone gets the
     * same saving on the video path without giving up IPv6 for everything else.
     *
     * Scoped by suffix on purpose: a global AAAA block *is* `ipv4_only`, which is the switch,
     * not this. Emitted regardless of the hostname-override setting: with the override off every
     * destination is phone-resolved through `default_domain_resolver` instead, which is the same
     * v4-first list from the same double lookup, so the argument only gets stronger.
     */
    private fun suppressAaaaRule(): JSONObject = JSONObject()
        .put("query_type", JSONArray().put("AAAA"))
        .put("domain_suffix", JSONArray().apply { AAAA_SUPPRESS_SUFFIXES.forEach(::put) })
        .put("action", "predefined")
        .put("rcode", "NOERROR")

    /**
     * The phone-resolved hosts plus the media CDNs. Two different arguments land on the same
     * answer:
     *
     * - [PHONE_RESOLVE_SUFFIXES]: the destination becomes a v4-first phone-resolved address, so
     *   the AAAA answer cannot reach the wire at all.
     * - [MEDIA_CDN_SUFFIXES]: the destination leaves as a *name* and the server resolves it, so
     *   the address family is the server's choice either way — the phone's AAAA answer only
     *   decides which local socket the app opens, and costs a second upstream lookup to do it.
     */
    private val AAAA_SUPPRESS_SUFFIXES: List<String>
        get() = (PHONE_RESOLVE_SUFFIXES + MEDIA_CDN_SUFFIXES).distinct()

    /**
     * Sends media-CDN lookups to the off-tunnel `bootstrap` resolver (same provider, same DoH,
     * no detour) while the rest of DNS keeps going through the proxy.
     *
     * A through-tunnel lookup costs a full proxy round-trip (~700ms on XHTTP, and it competes
     * with the video stream for the same transports). The optimistic cache cannot absorb it
     * here the way it does for ordinary browsing: a short-video feed mints a *new*
     * `rr*---sn-*.googlevideo.com` per clip, so every clip starts on a cold, synchronous
     * lookup. Those hostnames name one specific edge node — the playback URL already pins it —
     * so the answer does not depend on where it is asked from, which is what makes moving them
     * off the tunnel safe. Region-gated names stay on the tunnel: which resolver Google's
     * authoritative servers see is what picks the front-end (see [PHONE_RESOLVE_SUFFIXES] and
     * [usesTunnelDns]), and `googlevideo.com` is not a gate.
     *
     * Only for `auto`. An explicit `tunnel` means the user asked for no DNS off the tunnel at
     * all, and `direct` already has the `doh` server off it — there is no `bootstrap` then.
     */
    private fun mediaDnsBootstrapRule(): JSONObject = JSONObject()
        .put("domain_suffix", JSONArray().apply { MEDIA_CDN_SUFFIXES.forEach(::put) })
        .put("server", "bootstrap")

    /**
     * Domains that only ever name a CDN edge node, never a region-gated service — the ones whose
     * lookups [mediaDnsBootstrapRule] takes off the tunnel.
     *
     * The membership test is not "is it video", it is: **does the answer depend on where the
     * question is asked from?** These CDNs put the node (and usually its region) in the hostname
     * itself, and the playback URL that carries the hostname is issued by an API call that still
     * goes through the tunnel — so the region decision is already made by the time DNS runs, and
     * asking from the phone returns the same node. A geo-steered *and* gated name resolved off
     * the tunnel would land the session in the wrong region, which is the failure this list
     * exists to stay away from (see [PHONE_RESOLVE_SUFFIXES] for that story).
     *
     * Every short-video feed is covered, not just the one that was reported: they all share the
     * shape that makes the tunnel round-trip hurt — a new, never-seen host per clip, so the
     * optimistic cache never has anything to serve and each clip starts on a cold lookup.
     */
    private val MEDIA_CDN_SUFFIXES = listOf(
        // YouTube / Shorts. `rr*---sn-*.googlevideo.com` — one name per edge node.
        "googlevideo.com",
        // Instagram Reels, Facebook video. `scontent-fra3-1.*` — region is in the name.
        "cdninstagram.com",
        "fbcdn.net",
        // TikTok. Least certain of the set: ByteDance rotates CDN domains more than the others,
        // so treat a missing one as "not covered yet" rather than assuming this is exhaustive.
        "tiktokcdn.com",
        "tiktokcdn-us.com",
        // Twitch. `video-weaver.fra02.hls.ttvnw.net` — the HLS playlist pins the edge.
        "ttvnw.net",
        // Netflix Open Connect appliances.
        "nflxvideo.net",
    )

    /**
     * Every name that bypasses the tunnel: the built-in and refreshed Russian lists when they
     * are on, plus whatever the user added themselves. The user list is independent — it still
     * works with the Russian switch off.
     */
    private fun directSuffixes(
        ruDirect: Boolean,
        remote: Set<String>,
        custom: Set<String>,
    ): List<String> = ((if (ruDirect) RU_DIRECT_SUFFIXES + remote.sorted() else emptyList()) + custom.sorted()).distinct()

    /**
     * Resolves [directSuffixes] through the off-tunnel `bootstrap` resolver, so a service routed
     * direct is also *named* from here. Paired with [directRule]; one without the other is
     * broken in both directions.
     */
    private fun directDnsBootstrapRule(suffixes: List<String>): JSONObject = JSONObject()
        .put("domain_suffix", JSONArray().apply { suffixes.forEach(::put) })
        .put("server", "bootstrap")

    /**
     * Overrides which browser the TLS ClientHello imitates, unless the setting is `auto` — in
     * which case whatever the share link asked for is left exactly as it is.
     *
     * Only touches a `utls` block the parser already built, and only its `fingerprint`: the
     * import path always emits one, so a missing block means this is not a TLS outbound (or not
     * ours) and inventing uTLS for it is not this setting's job.
     */
    private fun applyTlsFingerprint(tls: JSONObject, fingerprint: String) {
        if (fingerprint == Settings.TLS_FINGERPRINT_AUTO) return
        tls.optJSONObject("utls")?.put("fingerprint", fingerprint)
    }

    /**
     * Keeps the TCP connections under the proxy alive through short idle periods.
     *
     * The core's dialer defaults are 5 minutes idle before the first probe, then one every 75s
     * (`constant/timeout.go`). Carrier NAT routinely drops an idle TCP mapping sooner than that,
     * and nothing tells either end — which is the root of the stale-pool problem the transport
     * currently treats after the fact: XHTTP holds several long-lived connections, they all go
     * quiet together while the phone is idle, and the next dial discovers they are dead by
     * timing out on it. Probing well inside the usual NAT window keeps the mapping instead.
     *
     * Cheaper than the connection-level HTTP/2 PING that was tried and removed: this is a bare
     * kernel packet that does not wake the app, and a failed probe kills only the socket that
     * is genuinely dead rather than aborting every stream multiplexed over it.
     *
     * Not a cure for deep Doze — there the radio is off and no timer of ours runs at all; that
     * case stays with the wake-triggered reload and the dial-time staleness check.
     *
     * Applied to the proxy outbounds only. `direct` is deliberately left settings-less: the core
     * treats an empty direct outbound as a special case (a DNS server detouring to one is
     * rejected as a no-op), and there is nothing to gain from holding it open.
     */
    private fun applyKeepAlive(outbound: JSONObject) {
        outbound.put("tcp_keep_alive", KEEP_ALIVE_IDLE)
        outbound.put("tcp_keep_alive_interval", KEEP_ALIVE_INTERVAL)
    }

    private const val KEEP_ALIVE_IDLE = "90s"
    private const val KEEP_ALIVE_INTERVAL = "45s"

    /** Outbound types sing-box multiplexes; whether mux fits the transport is [muxEligible]'s call. */
    private val MUX_OUTBOUND_TYPES = setOf("vless", "vmess", "trojan", "shadowsocks")

    /**
     * h2mux only fits a plain-TCP carrier: XHTTP and gRPC have their own connection pooling
     * and reject multiplex, and QUIC-based outbounds are multiplexed by design. Anything else
     * keeps whatever the share link asked for — usually nothing.
     */
    private fun muxEligible(outbound: JSONObject): Boolean {
        if (outbound.optString("type") !in MUX_OUTBOUND_TYPES) return false
        val transport = outbound.optJSONObject("transport") ?: return true
        val type = transport.optString("type")
        return type.isEmpty() || type == "tcp"
    }

    private fun applyMultiplex(outbound: JSONObject) {
        if (!muxEligible(outbound)) return
        if (Settings.tarnMuxEnabled) {
            outbound.put(
                "multiplex",
                JSONObject().apply {
                    put("enabled", true)
                    put("protocol", "h2mux")
                    put("max_streams", 8)
                    put("padding", true)
                },
            )
        } else {
            outbound.remove("multiplex")
        }
    }

    /** Tag of the loopback SOCKS inbound the app's own HTTP client dials — see [AppProxy]. */
    private const val APP_PROXY_TAG = "app-proxy"

    /**
     * Writes the configured XHTTP pool width as `xmux.max_connections` (the core's
     * lx(xhttp) pool tuning) onto XHTTP transports only: the block is part of the
     * transport options, not the outbound, and every other transport either ignores
     * it or uses a different pooling model. The connection muxing toggle above
     * covers plain TCP.
     */
    private fun applyXhttpPool(outbound: JSONObject) {
        val transport = outbound.optJSONObject("transport") ?: return
        if (transport.optString("type") != "xhttp") return
        // Older builds wrote the block one level too high; the core rejects an
        // unknown outbound field, so a patched-in-place config must lose it too.
        outbound.remove("xmux")
        transport.put(
            "xmux",
            JSONObject().put("max_connections", Settings.tarnXhttpPool),
        )
    }

    /**
     * The app's own way back into the tunnel. The app is excluded from its own TUN so its
     * transport cannot loop, which also used to mean its HTTP (update checks, profile
     * fetches while connected) bypassed the VPN and resolved through the system DNS. A
     * loopback SOCKS listener with per-install credentials gives that HTTP a path through
     * the core without opening anything to other apps: random port, mandatory auth.
     */
    private fun appProxyInbound(): JSONObject? {
        val proxy = AppProxy.descriptor() ?: return null
        return JSONObject().apply {
            put("type", "socks")
            put("tag", APP_PROXY_TAG)
            put("listen", "127.0.0.1")
            put("listen_port", proxy.port)
            put(
                "users",
                JSONArray().put(
                    JSONObject()
                        .put("username", proxy.username)
                        .put("password", proxy.password),
                ),
            )
        }
    }

    /** Replaces whatever app-proxy inbound an older generator wrote, or adds the current one. */
    private fun applyAppProxyInbound(config: JSONObject) {
        val inbounds = config.optJSONArray("inbounds") ?: return
        for (index in inbounds.length() - 1 downTo 0) {
            if (inbounds.optJSONObject(index)?.optString("tag") == APP_PROXY_TAG) {
                inbounds.remove(index)
            }
        }
        appProxyInbound()?.let { inbound ->
            inbounds.put(inbound)
        }
    }

    /** The outbound types [ProxyUriParser] can emit for a server — everything except `direct`. */
    private val SERVER_OUTBOUND_TYPES =
        setOf("vless", "trojan", "shadowsocks", "vmess", "hysteria2", "tuic", "anytls", "socks", "wireguard")

    /** Sends [directSuffixes] out the real interface instead of through the proxy. */
    private fun directRule(suffixes: List<String>): JSONObject = JSONObject()
        .put("domain_suffix", JSONArray().apply { suffixes.forEach(::put) })
        .put("outbound", "direct")

    /**
     * Services that work *worse*, or not at all, when reached from a foreign address — the
     * traffic a user in Russia needs to keep off the tunnel.
     *
     * The membership test is not "is it Russian", it is: **would routing it through the exit
     * break it or slow it down with nothing gained?** Banks and state services refuse foreign
     * addresses outright (often with a generic error, so it reads as "the app is broken");
     * marketplaces, maps and video serve a distant edge or a foreign catalogue. None of it is
     * traffic the user is trying to hide from the local network — that is what makes sending
     * it direct a free win rather than a leak.
     *
     * Country TLDs carry most of it, which is why they lead the list: a domain-suffix match
     * on `.ru`/`.su`/`.xn--p1ai` sweeps in every bank, ministry, shop and operator without
     * enumerating them. The named entries below are the ones that sit on a neutral TLD, plus
     * the CDNs those services load their content from — a page whose HTML comes direct and
     * whose images come through Frankfurt is not fixed.
     *
     * Deliberately NOT here: Telegram, and anything else a user turns a VPN on *for*. The
     * list is for services that reject the exit, not for services blocked at home.
     *
     * The gap this leaves is connections made to a literal address with no name to match —
     * an app pinning IPs still goes through the tunnel. Closing that needs a geoip database,
     * which is a megabytes-and-updates decision, not a list.
     */
    private val RU_DIRECT_SUFFIXES = listOf(
        // Country TLDs — the bulk of it, banks and state services included.
        ".ru",
        ".su",
        ".xn--p1ai", // .рф, as it appears on the wire
        // VK / Mail.ru group and their content delivery.
        "vk.com",
        "vk.me",
        "vk-cdn.net",
        "vkuservideo.net",
        "vkuseraudio.net",
        "vkuser.net",
        "userapi.com",
        "mycdn.me",
        // Yandex: the search, maps, taxi, Kinopoisk and the static/CDN hosts they load.
        "yandex.net",
        "yandex.com",
        "yastatic.net",
        "yandexcloud.net",
        // Sber, Alfa — the two big banks whose public hosts are not on .ru.
        "sberbank.com",
        "alfabank.st",
        // Marketplaces and classifieds: static hosts on neutral TLDs.
        "avito.st",
        "ozonru.me",
        "wbstatic.net",
        // Maps and video services with non-.ru domains.
        "2gis.com",
        "okko.tv",
        "ivi.tv",
        // CDN a large share of Russian media sits behind.
        "ngenix.net",
    )

    /**
     * `store_dns` persists the DNS cache to the cache file, so the optimistic cache above
     * survives a service restart or a server switch: reconnecting no longer re-pays a
     * through-tunnel lookup for every host the user had already visited.
     */
    private fun experimentalBlock(): JSONObject = JSONObject().put(
        "cache_file",
        JSONObject().put("enabled", true).put("store_dns", true),
    )

    private fun dnsDirectRule(option: DnsOption): JSONObject = JSONObject().apply {
        put(
            "ip_cidr",
            JSONArray()
                .put("${option.server}/32"),
        )
        put("outbound", "direct")
    }

    private fun findDohServer(dns: JSONObject): String? {
        val servers = dns.optJSONArray("servers") ?: return null
        for (i in 0 until servers.length()) {
            val server = servers.optJSONObject(i) ?: continue
            if (server.optString("tag") == "doh") return server.optString("server").ifBlank { null }
        }
        return null
    }

    /**
     * `auto` leaves QUIC alone. Android's YouTube client stays on HTTP/3: when the TUN rejects
     * QUIC it keeps retrying the handshake per host and never opens the TCP stream, so the feed
     * stays empty. The browser symptom that once looked like QUIC was the TCP path: the default
     * gVisor TUN stack keeps TCP in userspace instead of the system-stack NAT. `block` stays for
     * an exit where QUIC-over-XUDP really does stall.
     */
    private fun shouldBlockQuic(policy: String): Boolean = policy == Settings.QUIC_POLICY_BLOCK

    /**
     * Whether DoH is sent through the proxy (so the resolver sees the exit region) or straight
     * out to a literal resolver IP (fast, but reveals the real region).
     *
     * `auto` means tunnel. It used to make an exception for XHTTP, where an over-tunnel lookup
     * pays a full round-trip (~700ms measured) and stalled video sessions — but that exception
     * cost more than it saved. Which resolver Google's authoritative servers see is what picks
     * the regional front-end you land on, so resolving from the phone while connecting from the
     * exit makes region-gated services (Gemini) answer "not available in your country" even
     * though the exit itself is in a supported one. The latency argument is also mostly gone:
     * `optimistic` in [dnsBlock] serves repeat lookups from cache and refreshes them in the
     * background, so only a name's very first lookup is synchronous. `tunnel` / `direct` still
     * force the choice.
     *
     * Either way requires DNS protection on and a real proxy final — with `final: "direct"` there
     * is no tunnel to send DNS through.
     */
    private fun usesTunnelDns(
        route: String,
        protectionEnabled: Boolean,
        routeFinal: String,
    ): Boolean {
        if (!protectionEnabled || routeFinal == "direct") return false
        return route != Settings.DNS_ROUTE_DIRECT
    }

    private fun shouldRouteDnsDirect(
        route: String,
        protectionEnabled: Boolean,
        routeFinal: String,
    ): Boolean = !usesTunnelDns(route, protectionEnabled, routeFinal)

    private fun normalizeTunMtu(mtu: Int): Int = mtu.takeIf(Settings.TARN_TUN_MTU_VALUES::contains) ?: 0

    private fun normalizeTunStack(stack: String): String = stack.takeIf(Settings.TARN_TUN_STACK_VALUES::contains) ?: Settings.TUN_STACK_GVISOR

    private fun normalizeLogLevel(level: String): String = when (level) {
        Settings.LOG_LEVEL_INFO, Settings.LOG_LEVEL_DEBUG, Settings.LOG_LEVEL_TRACE -> level
        else -> Settings.LOG_LEVEL_WARN
    }

    private fun normalizeTestUrl(url: String): String = url.trim().takeIf(Settings::isValidTarnTestUrl) ?: Settings.DEFAULT_TARN_TEST_URL

    private fun isQuicRejectRule(rule: JSONObject): Boolean = rule.optString("action") == "reject" && (
        rule.optString("protocol").equals("quic", ignoreCase = true) ||
            (rule.optInt("port") == 443 && rule.optString("network") == "udp")
        )

    /**
     * A sniff-independent half of the same policy: QUIC always rides on UDP/443, so this
     * catches it even when the protocol sniffer cannot classify the packet (short-header
     * QUIC, fragmented datagrams). Without it a single sniff miss sends the flow down the
     * fragile XUDP path the reject exists to avoid.
     *
     * Emitted BEFORE the UDP sniff rule: PreMatch stops at the first rule whose packet
     * sniffer needs more data, so a reject placed after sniff never reaches the TUN's
     * immediate ICMP-unreachable path. There the packet becomes a normal route-level
     * reject, and YouTube waits out its own QUIC timeout before trying TCP.
     */
    private fun quicUdpRejectRule(): JSONObject = JSONObject()
        .put("network", "udp")
        .put("port", 443)
        .put("action", "reject")
        .put("no_drop", true)

    /**
     * Rejects QUIC so browsers fall back to TCP (which tunnels cleanly, unlike a
     * loss-based transport wrapped in XUDP over XHTTP). `no_drop` is the important
     * bit for video: the core's default reject flips to a *silent* drop after 50
     * rejects in 30s (route/rule RuleActionReject flood guard). Scrolling YouTube
     * Shorts blows past that instantly — every new googlevideo host re-attempts
     * QUIC — and once rejects go silent the browser sits on each QUIC connection
     * until its own timeout before falling back, which is exactly the per-clip
     * stutter. no_drop keeps every QUIC attempt getting a prompt reset so the TCP
     * fallback stays immediate under load.
     */
    private fun quicRejectRule(): JSONObject = JSONObject().put("protocol", "quic").put("action", "reject").put("no_drop", true)

    /**
     * `override_destination` (TCP only) makes the outbound send the sniffed hostname upstream
     * instead of the address the phone resolved — what Xray, and so v2rayNG, does by default.
     * This is the fix for Gemini answering "not available in your region": the exit was
     * connecting to whichever Google front-end the *phone* had picked, rather than choosing
     * one itself.
     *
     * Requires the lx core patch that re-exposes the flag on the sniff action; an older core
     * rejects the unknown field, which the repository catches through `Libbox.checkConfig`
     * before it can write a config the running service cannot load.
     *
     * Sniffing is split by transport because the destination override must not apply to UDP.
     * TCP keeps the hostname (the server resolves the name in its own region). UDP must keep
     * the address the app dialed — if the domain is sent upstream, the server resolves it
     * itself and may pick IPv6 on a dual-stack VPS while the app's flow is IPv4; the gVisor
     * stack then refuses the return packet ("send IPv6 packet to IPv4 connection") and QUIC
     * dies. The protocol sniff stays enabled on UDP so the QUIC reject rule still matches.
     */
    private fun sniffRules(sendHostname: Boolean): List<JSONObject> = buildList {
        add(
            JSONObject()
                .put("network", "tcp")
                .put("action", "sniff")
                .apply { if (sendHostname) put("override_destination", true) },
        )
        add(JSONObject().put("network", "udp").put("action", "sniff"))
    }

    private fun isSniffRule(rule: JSONObject): Boolean = rule.optString("action") == "sniff"

    /**
     * The one exception to sending hostnames: these are resolved on the phone and handed to the
     * server as an address instead. Everything else travels as a name.
     *
     * The default has to be the name, not the address, and enumerating the exceptions the other
     * way round does not work. A region-gated service reached through this proxy — Gemini is the
     * proven case — must have its whole session leave as hostnames so the *server* resolves them
     * (its resolver, its region); handing it a phone-resolved IP is exactly what made it answer
     * "not available in your region" (see [tarnvpn-gemini-region-block]: sing-box shipped an IP,
     * Xray a name, and only the name worked — regardless of address family). But that service
     * loads dozens of Google hosts (apis, gstatic, googleusercontent, play, …), so exempting it
     * host-by-host is a losing game: one un-listed companion left as an IP re-trips the gate.
     * Defaulting to names sweeps every companion in for free.
     *
     * YouTube Music is the opposite: as a name the server resolves it — on a dual-stack VPS,
     * to IPv6, whose geolocation routinely disagrees with the IPv4 one — and it region-blocks.
     * Phone-resolving it (prefer_ipv4 by default) hands the server a v4 address and it works. Its
     * host set is small and stable, so *this* is the list that stays enumerated.
     *
     * Cost of names-by-default: general traffic exits via whatever the server resolves to — the
     * same thing Xray/v2rayNG do out of the box (and which the user already runs working on this
     * server), so it needs no server-side configuration to be correct. It does mean a server
     * whose only working exit region sits on the family we don't select can't be fixed from the
     * client — but no client setting can conjure a region the exit doesn't route to.
     */
    private val PHONE_RESOLVE_SUFFIXES = listOf(
        // music.youtube.com and www./m. are covered by the youtube.com suffix.
        "youtube.com",
        "youtu.be",
        // The InnerTube API the app talks to; the region gate answers here, and it is not under
        // youtube.com. googleapis.com is NOT listed as a bare suffix on purpose — that would drag
        // generativelanguage/aistudio in with it and send Gemini's API as an IP.
        "youtubei.googleapis.com",
        // Media and images for the same session; a region split between the API and the streams
        // is its own failure.
        "googlevideo.com",
        "ytimg.com",
        "ggpht.com",
    )

    /**
     * Resolves the sniffed name on the phone and leaves the addresses in `DestinationAddresses`,
     * which is what the outbound then dials (`route/conn.go:101` → `DialSerialNetwork` →
     * `M.SocksaddrFrom`), so the server is handed an address instead of the name. Scoped to
     * [PHONE_RESOLVE_SUFFIXES]; everything else keeps the name from [sniffRule]'s override.
     *
     * No `server`: an empty one defers to the default domain resolver, which is the same
     * off-tunnel bootstrap the media rule uses under the hood — pinning `bootstrap` here by
     * name would break configs where that tag does not exist (protection off / direct route).
     * No `strategy`: an empty one is AsIs, which defers to `dns.strategy` (`dns/router.go:799`),
     * so the IPv6 toggle keeps governing the address family here as everywhere else — with it on
     * that is prefer_ipv4, the v4 YouTube Music needs. The lookup is nearly free: the app's own
     * query for the same name went through the hijacked resolver moments earlier and sits in the
     * same (optimistic) cache.
     *
     * Bounded at 3s anyway: this rule runs synchronously in pre-match, and on error the packet
     * is rejected outright (`route/route.go` pre-match resolve) instead of falling through to
     * the name-based dial that would still connect. A dead default resolver must therefore fail
     * fast — the default 10s DNS timeout would stall every new googlevideo host for the full
     * budget before the player even gets its error to retry on.
     *
     * For the matched hosts this also restores `ip_is_private` matching (they carry addresses
     * again) — a non-issue here since none are private, but it means the LAN-by-name caveat of
     * overriding the destination applies only to the un-exempt majority.
     */
    private fun phoneResolveRule(): JSONObject = JSONObject()
        .put("domain_suffix", JSONArray().apply { PHONE_RESOLVE_SUFFIXES.forEach(::put) })
        .put("action", "resolve")
        .put("timeout", "3s")

    /**
     * Strips any generated address-selection rule so repatch re-emits it fresh: the current
     * phone-resolve rule, plus artifacts of earlier builds (a bare global `resolve`, and a
     * `domain_suffix`+`outbound` hostname rule). Generated configs are the only source of a
     * `domain_suffix` rule or a `resolve` action, so matching on either is safe.
     */
    private fun isGeneratedDestinationRule(rule: JSONObject): Boolean = rule.optString("action") == "resolve" || rule.has("domain_suffix")

    /**
     * Recognises the IPv6 reject rule an interim build could emit, so repatching drops it. The
     * mode behind it is gone: it existed to test whether suppressing AAAA was what broke
     * Gemini, and the cause turned out to be the destination the outbound put on the wire.
     */
    private fun isIpv6RejectRule(rule: JSONObject): Boolean {
        if (rule.optString("action") != "reject") return false
        val cidrs = rule.optJSONArray("ip_cidr") ?: return false
        return (0 until cidrs.length()).any { cidrs.optString(it) == "::/0" }
    }

    private fun isDnsDirectRule(rule: JSONObject, oldDohServer: String?): Boolean {
        if (oldDohServer.isNullOrBlank() || rule.optString("outbound") != "direct") return false
        val cidrs = rule.optJSONArray("ip_cidr") ?: return false
        val expected = "$oldDohServer/32"
        return (0 until cidrs.length()).any { cidrs.optString(it) == expected }
    }

    private fun rebuildRouteRules(
        rules: JSONArray,
        oldDohServer: String?,
        dnsOption: DnsOption,
        blockQuic: Boolean,
        routeDnsDirect: Boolean,
        sendHostname: Boolean,
        directSuffixes: List<String>,
    ): JSONArray {
        val retained = mutableListOf<JSONObject>()
        var sniffEmitted = false
        for (i in 0 until rules.length()) {
            val rule = rules.optJSONObject(i) ?: continue
            if (isQuicRejectRule(rule) ||
                isDnsDirectRule(rule, oldDohServer) ||
                isIpv6RejectRule(rule) ||
                isGeneratedDestinationRule(rule)
            ) {
                continue
            }
            // Regenerated so the hostname flag tracks the setting; keeping the stored rule
            // would freeze whatever it was imported with. The old single sniff rule expands
            // into the TCP/UDP pair, so any further stored copy is dropped.
            if (isSniffRule(rule)) {
                if (!sniffEmitted) {
                    sniffEmitted = true
                    retained += sniffRules(sendHostname)
                }
                continue
            }
            retained += rule
        }

        // PreMatch evaluates rules in order and bails out at a UDP sniff that needs more
        // data. The port-443 reject must therefore be first, so QUIC gets an immediate
        // ICMP unreachable instead of falling through to a route-level reject.
        val earlyRules = buildList {
            if (blockQuic) add(quicUdpRejectRule())
        }
        val generated = buildList {
            if (blockQuic) add(quicRejectRule())
            if (routeDnsDirect) add(dnsDirectRule(dnsOption))
            // Ahead of the phone-resolve rule: both match on domain_suffix and the first
            // hit wins, so a name on both lists must reach its terminal outbound here
            // rather than be resolved for a tunnel it is not going to use.
            if (directSuffixes.isNotEmpty()) add(directRule(directSuffixes))
            // Only meaningful while destinations are names; without the override every
            // destination is already an address the phone resolved, so there is nothing to carve
            // back out. Sends everything to the server as a name except YouTube, which is
            // resolved here to a v4 address.
            if (sendHostname) add(phoneResolveRule())
        }
        val insertAt = retained.indexOfFirst { it.optBoolean("ip_is_private") }.let {
            if (it >= 0) it else retained.size
        }

        return JSONArray().apply {
            earlyRules.forEach(::put)
            retained.forEachIndexed { index, rule ->
                if (index == insertAt) generated.forEach(::put)
                put(rule)
            }
            if (insertAt == retained.size) generated.forEach(::put)
        }
    }

    // A private ULA range — not routable, just needs to be distinct from the IPv4 side.
    private const val TUN_IPV4_ADDRESS = "172.19.0.1/30"
    private const val TUN_IPV6_ADDRESS = "fdfe:dcba:9876::1/126"

    // The tun always carries an IPv6 address, regardless of the IPv6 setting, so auto_route
    // installs a ::/0 route and native IPv6 traffic is forced into the tunnel instead of
    // leaking around an IPv4-only tunnel on a v6-capable network. The IPv6 toggle decides
    // whether IPv6 is *used* for destinations (the dns strategy), not whether it is captured —
    // withholding the address is what used to turn "don't use IPv6" into a real leak.
    //
    // A mode that withheld it was tried once, to test whether Google objected to an interface
    // that advertises IPv6 while every AAAA comes back empty. It changed nothing, so it was
    // removed rather than left as a setting that costs the capture and buys nothing.
    private fun tunAddress(): JSONArray = JSONArray().apply {
        put(TUN_IPV4_ADDRESS)
        put(TUN_IPV6_ADDRESS)
    }

    /**
     * The profile's own proxy endpoint(s), as /32 or /128 prefixes. Excluding
     * them from the TUN keeps the transport socket on the underlying network:
     * the VPN route must not capture the connection that carries the VPN.
     * Hostnames are left to `auto_detect_interface` — resolving them here would
     * make import depend on DNS being up.
     */
    private fun tunExcludeAddresses(servers: List<ProxyUriParser.ParsedOutbound>): List<String> {
        val prefixes = LinkedHashSet<String>()
        fun add(host: String?) {
            val value = host?.trim().orEmpty()
            if (value.isEmpty()) return
            if (value.contains(':')) {
                if (IPV6_LITERAL.matches(value)) prefixes += "$value/128"
                return
            }
            val parts = value.split('.')
            if (parts.size != 4) return
            for (part in parts) {
                val number = part.toIntOrNull() ?: return
                if (number !in 0..255) return
            }
            prefixes += "$value/32"
        }
        servers.forEach { server ->
            if (server.json.optString("type") == "wireguard") {
                val peers = server.json.optJSONArray("peers")
                if (peers != null) {
                    for (i in 0 until peers.length()) {
                        add(peers.optJSONObject(i)?.optString("address"))
                    }
                }
            } else {
                add(server.json.optString("server"))
            }
        }
        return prefixes.toList()
    }

    private val IPV6_LITERAL = Regex("^[0-9a-fA-F:]+$")

    /**
     * Try base64-decode; fall back to raw. Keep every URI-shaped entry, including a scheme the
     * current app cannot parse yet, so a subscription refresh can report it and preserve the
     * previously working list instead of silently interpreting it as a removed server.
     */

    /**
     * True when the fallback body actually contains a link type this build can use (or an
     * AWG/WG config). An unsupported URI such as `socks5://...` is not a usable alternative:
     * choosing it over the original subscription would only turn a working response into an
     * import error. Unsupported entries inside an otherwise usable list are still kept by
     * [expandSubscriptionBody] and reported by the normal per-entry issue path.
     */
    internal fun hasSubscriptionEntries(body: String): Boolean = body.length <= MAX_SUBSCRIPTION_CHARS &&
        (expandSubscriptionBody(body).any(ProxyUriParser::isSupportedUri) || AwgConfParser.isAwgInput(body))

    internal fun expandSubscriptionBody(body: String): List<String> {
        val trimmed = body.trim().removePrefix("\uFEFF")
        fun linesOf(text: String) = text.split('\n', '\r')
            .map { it.trim().removePrefix("\uFEFF") }
            .filter { line -> SHARE_LINK_PREFIX.matches(line) }

        val candidates = mutableListOf(trimmed)
        // Try base64 variants (standard + url-safe, with/without padding).
        for (flags in intArrayOf(
            Base64.DEFAULT,
            Base64.URL_SAFE,
            Base64.DEFAULT or Base64.NO_PADDING,
            Base64.URL_SAFE or Base64.NO_PADDING,
        )) {
            try {
                val decoded = String(Base64.decode(trimmed, flags or Base64.NO_WRAP))
                if (linesOf(decoded).isNotEmpty()) {
                    candidates.add(decoded)
                    break
                }
            } catch (_: Throwable) {
                // ignore, try next
            }
        }
        for (c in candidates) {
            val lines = linesOf(c)
            if (lines.isNotEmpty()) return lines
        }
        return emptyList()
    }

    private val SHARE_LINK_PREFIX = Regex("[A-Za-z][A-Za-z0-9+.-]*://.*")

    private fun buildConfig(
        servers: List<ProxyUriParser.ParsedOutbound>,
        settings: ConnectionConfigSettings,
    ): String {
        require(servers.isNotEmpty())

        val effectiveIpStrategy = Settings.effectiveTarnIpStrategy(
            settings.ipStrategy,
            settings.legacyIpv6Enabled,
        )
        val blockQuic = shouldBlockQuic(settings.quicPolicy)
        val normalizedMtu = normalizeTunMtu(settings.tunMtu)
        val tunStack = normalizeTunStack(settings.tunStack)
        val wireguardMtu = servers
            .filter { it.json.optString("type") == "wireguard" }
            .mapNotNull { it.json.optInt("mtu").takeIf { mtu -> mtu > 0 } }
            .minOrNull()
        val tunMtu = if (wireguardMtu != null) {
            // The TUN must never hand the userspace WireGuard stack a packet
            // larger than the tunnel MTU: TCP would derive its MSS from the
            // TUN value and every full segment would be dropped inside the
            // endpoint. Explicit user values above the tunnel are clamped.
            minOf(normalizedMtu.takeIf { it != 0 } ?: wireguardMtu, wireguardMtu)
        } else {
            normalizedMtu
        }
        val hasWireguard = wireguardMtu != null
        val directSuffixes = directSuffixes(
            settings.ruDirect,
            settings.remoteDirectDomains,
            settings.directDomains,
        )

        val outboundsArr = JSONArray()
        val endpointsArr = JSONArray()
        // If more than one server: put an urltest selector first, name it "proxy".
        val routeFinal: String
        if (servers.size > 1) {
            routeFinal = "proxy"
            outboundsArr.put(
                JSONObject().apply {
                    put("type", "urltest")
                    put("tag", "proxy")
                    put(
                        "outbounds",
                        JSONArray().apply {
                            servers.forEach { put(it.tag) }
                        },
                    )
                    put("url", normalizeTestUrl(settings.testUrl))
                    put("interval", "3m")
                },
            )
        } else {
            routeFinal = servers[0].tag
        }
        val tunnelDns = usesTunnelDns(settings.dnsRoute, settings.dnsProtection, routeFinal)
        val routeDnsDirect = !tunnelDns
        servers.forEach { server ->
            // The parser's job is to translate the link; the connection policy on top of it is
            // ours and is applied in one place, so applySettings can reproduce it exactly on a
            // config that was written by an older generator.
            if (server.json.optString("type") == "wireguard") {
                // sing-box 1.13 removed the wireguard outbound; WireGuard and
                // AmneziaWG live in the top-level endpoints array.
                endpointsArr.put(server.json)
                return@forEach
            }
            server.json.optJSONObject("tls")?.let {
                ProxyUriParser.applyFragment(it, settings.fragmentEnabled, settings.recordFragment)
                applyTlsFingerprint(it, settings.tlsFingerprint)
            }
            applyKeepAlive(server.json)
            applyMultiplex(server.json)
            applyXhttpPool(server.json)
            outboundsArr.put(server.json)
        }
        outboundsArr.put(
            JSONObject().apply {
                put("type", "direct")
                put("tag", "direct")
            },
        )

        val config = JSONObject().apply {
            // "debug" logs every connection and DNS query — real I/O/CPU cost for a session
            // left connected for hours, and not something this app surfaces to the user
            // anyway. release/config/config.json and the lx-test samples all ship at
            // "info"/"warn"; this was the one config left at the noisiest level.
            put("log", JSONObject().put("level", normalizeLogLevel(settings.logLevel)))
            put(
                "dns",
                dnsBlock(
                    settings.dnsOption,
                    settings.dnsProtection,
                    effectiveIpStrategy,
                    settings.dnsRoute,
                    routeFinal,
                    blockQuic,
                    directSuffixes,
                ),
            )
            put(
                "inbounds",
                JSONArray().put(
                    JSONObject().apply {
                        put("type", "tun")
                        put("tag", "tun-in")
                        // gVisor is the default because it keeps TCP in userspace on Android;
                        // `mixed`/`system` stay selectable for device A/B testing.
                        put("stack", tunStack)
                        put("address", tunAddress())
                        if (tunMtu != 0) put("mtu", tunMtu)
                        put("auto_route", true)
                        put("strict_route", true)
                        // Keep the proxy transport out of the TUN. Without
                        // this the outbound socket to Cloudflare/our server can
                        // be route-bound to the VPN interface itself; small
                        // packets survive, large streams stall. Android 13+
                        // turns these into VpnService.excludeRoute entries.
                        tunExcludeAddresses(servers).takeIf { it.isNotEmpty() }?.let { excludes ->
                            put("route_exclude_address", JSONArray().apply { excludes.forEach(::put) })
                        }
                    },
                ),
            )
            put("outbounds", outboundsArr)
            if (endpointsArr.length() > 0) put("endpoints", endpointsArr)
            put(
                "route",
                JSONObject().apply {
                    put(
                        "rules",
                        JSONArray().apply {
                            // PreMatch stops at a packet sniff that needs more data. Put the
                            // sniff-independent UDP/443 reject first so QUIC gets an immediate
                            // ICMP unreachable from the TUN instead of a route-level reject.
                            if (blockQuic) put(quicUdpRejectRule())
                            // A WireGuard endpoint receives raw IP packets;
                            // replacing the destination with a sniffed name
                            // would hand it a hostname it cannot dial. Sniff
                            // without the override so the direct-domain rules
                            // still work.
                            sniffRules(settings.sendHostname && !hasWireguard).forEach(::put)
                            put(JSONObject().put("protocol", "dns").put("action", "hijack-dns"))
                            // Reject QUIC so browsers fall back to TCP: every UDP flow is wrapped
                            // in XUDP and costs a separate stream on the proxy, and QUIC-heavy
                            // sites open them by the dozen. See shouldBlockQuic() for why this no
                            // longer depends on the transport, and quicRejectRule() for why
                            // no_drop matters (silent-drop-under-flood = Shorts stutter).
                            if (blockQuic) put(quicRejectRule())
                            // Keep the literal-IP DoH resolver off the tunnel. Direct DoH avoids a
                            // plaintext bootstrap dependency and is fast enough before XHTTP is up.
                            if (routeDnsDirect) put(dnsDirectRule(settings.dnsOption))
                            // Russian services and the user's own additions out the real interface:
                            // they refuse or degrade a foreign address, and none of it is traffic
                            // being hidden from the local network. Ahead of the phone-resolve rule,
                            // which also matches on domain_suffix — see rebuildRouteRules().
                            if (directSuffixes.isNotEmpty()) put(directRule(directSuffixes))
                            // Everything reaches the server as a name (so it resolves in its own region);
                            // YouTube is the one exception, phone-resolved to a v4 address. See
                            // phoneResolveRule() for why the exception list runs this way round.
                            if (settings.sendHostname && !hasWireguard) put(phoneResolveRule())
                            put(JSONObject().put("ip_is_private", true).put("outbound", "direct"))
                        },
                    )
                    put("final", routeFinal)
                    put("auto_detect_interface", true)
                    // Resolve the VPN endpoint itself through the literal-IP encrypted resolver;
                    // otherwise an endpoint hostname leaks to the system DNS before connect.
                    put("default_domain_resolver", if (tunnelDns) "bootstrap" else "doh")
                },
            )
            put("experimental", experimentalBlock())
        }
        applyAppProxyInbound(config)
        return config.toString(2)
    }

    private fun parseQuery(query: String): Map<String, String> {
        if (query.isBlank()) return emptyMap()
        val out = mutableMapOf<String, String>()
        for (pair in query.split('&')) {
            val eq = pair.indexOf('=')
            if (eq < 0) continue
            val k = Uri.decode(pair.substring(0, eq))
            val v = Uri.decode(pair.substring(eq + 1))
            out[k] = v
        }
        return out
    }
}
