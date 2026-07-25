package io.nekohasekai.sfa.utils

import android.net.Uri
import android.util.Base64
import io.nekohasekai.sfa.database.Settings
import io.nekohasekai.sfa.tarn.data.DnsOption
import io.nekohasekai.sfa.tarn.data.TarnDns
import org.json.JSONArray
import org.json.JSONObject

object VlessImporter {

    private data class ConnectionConfigSettings(
        val dnsOption: DnsOption,
        val dnsProtection: Boolean,
        val legacyIpv6Enabled: Boolean,
        val fragmentEnabled: Boolean,
        val quicPolicy: String,
        val tunMtu: Int,
        val ipStrategy: String,
        val dnsRoute: String,
        val logLevel: String,
        val testUrl: String,
        val sendHostname: Boolean,
    )

    private fun currentConnectionConfigSettings() = ConnectionConfigSettings(
        dnsOption = TarnDns.byId(Settings.tarnDnsProvider),
        dnsProtection = Settings.tarnDnsProtection,
        legacyIpv6Enabled = Settings.tarnIpv6Enabled,
        fragmentEnabled = Settings.tarnFragmentEnabled,
        quicPolicy = Settings.tarnQuicPolicy,
        tunMtu = Settings.tarnTunMtu,
        ipStrategy = Settings.tarnIpStrategy,
        dnsRoute = Settings.tarnDnsRoute,
        logLevel = Settings.tarnLogLevel,
        testUrl = Settings.tarnTestUrl,
        sendHostname = Settings.tarnSendHostname,
    )

    /**
     * Kept under its original name because the import dialog and its tests call it; it now
     * accepts every share link [ProxyUriParser] understands, not just vless.
     */
    fun isVlessUri(input: String): Boolean = ProxyUriParser.isSupportedUri(input)

    fun isHttpsUrl(input: String): Boolean {
        val uri = runCatching { Uri.parse(input.trim()) }.getOrNull() ?: return false
        return uri.scheme?.equals("https", ignoreCase = true) == true && !uri.host.isNullOrBlank()
    }

    /**
     * True if the pasted input is either a single supported share link or a subscription URL
     * we can expand.
     */
    fun isSupported(input: String): Boolean {
        val t = input.trim()
        return isVlessUri(t) || isHttpsUrl(t)
    }

    /**
     * Build sing-box JSON from a single vless URI OR from an HTTPS subscription URL.
     * For URLs, [fetch] is called to download the subscription body — always call
     * from an IO dispatcher.
     */
    fun toSingBoxJson(input: String, fetch: (String) -> String): String {
        val settings = currentConnectionConfigSettings()
        return buildConfig(parseServers(input, fetch, settings.fragmentEnabled), settings)
    }

    /**
     * One profile per server, so each shows up as its own row on the servers screen.
     * [sourceUri] is the single `vless://` link this server came from; the repository persists
     * it next to the config so a later repatch can regenerate the config from source instead
     * of surgically patching (see [rebuildConfig]).
     */
    data class ImportedServer(val name: String, val config: String, val sourceUri: String)

    /**
     * Same inputs as [toSingBoxJson], but a subscription is split into one standalone
     * config per server instead of a single config with an urltest group. The shell treats
     * a profile as a server, so a group would collapse the whole subscription into one row.
     */
    fun toSingBoxConfigs(input: String, fetch: (String) -> String): List<ImportedServer> {
        // Read once for the whole batch: buildConfig() used to re-read both settings on
        // every call, which for an N-server subscription meant 2N redundant DataStore
        // queries for values that can't change mid-import.
        val settings = currentConnectionConfigSettings()
        return parseServers(input, fetch, settings.fragmentEnabled).map {
            ImportedServer(it.tag, buildConfig(listOf(it), settings), it.sourceUri)
        }
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
        buildConfig(listOf(ProxyUriParser.parse(sourceUri, settings.fragmentEnabled)), settings)
    }.getOrNull()

    private fun parseServers(
        input: String,
        fetch: (String) -> String,
        fragmentEnabled: Boolean,
    ): List<ProxyUriParser.ParsedOutbound> {
        val t = input.trim()
        return when {
            isVlessUri(t) -> listOf(ProxyUriParser.parse(t, fragmentEnabled))
            isHttpsUrl(t) -> {
                val body = fetch(t)
                require(body.length <= MAX_SUBSCRIPTION_CHARS) {
                    "Subscription is too large (max $MAX_SUBSCRIPTION_CHARS characters)"
                }
                val entries = expandSubscriptionBody(body)
                require(entries.size <= MAX_SUBSCRIPTION_SERVERS) {
                    "Subscription contains too many servers (max $MAX_SUBSCRIPTION_SERVERS)"
                }
                // One unparseable entry must not sink a whole subscription — mixed lists
                // routinely carry a scheme we do not handle yet.
                entries.mapNotNull { entry ->
                    runCatching { ProxyUriParser.parse(entry, fragmentEnabled) }.getOrNull()
                }.ifEmpty {
                    throw IllegalArgumentException(
                        "Subscription contained no server links we could read. " +
                            "Response was ${body.length} chars."
                    )
                }
            }
            else -> throw IllegalArgumentException(
                "Unsupported input: paste a vless/trojan/ss/vmess/hysteria2/tuic/anytls link, " +
                    "or an https:// subscription",
            )
        }
    }

    private const val MAX_SUBSCRIPTION_CHARS = 1_048_576
    private const val MAX_SUBSCRIPTION_SERVERS = 128

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
        ipStrategy: String = Settings.tarnIpStrategy,
        dnsRoute: String = Settings.tarnDnsRoute,
        logLevel: String = Settings.tarnLogLevel,
        testUrl: String = Settings.tarnTestUrl,
        sendHostname: Boolean = Settings.tarnSendHostname,
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
        val blockQuic = shouldBlockQuic(quicPolicy)
        config.put(
            "dns",
            dnsBlock(option, dnsProtection, effectiveIpStrategy, dnsRoute, routeFinal, blockQuic),
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
                ),
            )
            route.put(
                "default_domain_resolver",
                if (usesTunnelDns(dnsRoute, dnsProtection, routeFinal)) "bootstrap" else "doh",
            )
        }

        val inbounds = config.optJSONArray("inbounds")
        if (inbounds != null) {
            for (i in 0 until inbounds.length()) {
                val inbound = inbounds.optJSONObject(i) ?: continue
                if (inbound.optString("type") == "tun") {
                    inbound.put("address", tunAddress())
                    val normalizedMtu = normalizeTunMtu(tunMtu)
                    if (normalizedMtu == 0) {
                        inbound.remove("mtu")
                    } else {
                        inbound.put("mtu", normalizedMtu)
                    }
                }
            }
        }

        val outbounds = config.optJSONArray("outbounds")
        if (outbounds != null) {
            for (i in 0 until outbounds.length()) {
                val tls = outbounds.optJSONObject(i)?.optJSONObject("tls") ?: continue
                ProxyUriParser.applyFragment(tls, fragmentEnabled)
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
        val serverTypes = setOf("vless", "trojan", "shadowsocks", "vmess", "hysteria2", "tuic", "anytls")
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
        return hasServer && hasDirect
    }

    private fun dnsBlock(
        option: DnsOption,
        protectionEnabled: Boolean,
        ipStrategy: String,
        dnsRoute: String,
        routeFinal: String,
        blockQuic: Boolean,
    ): JSONObject =
        JSONObject().apply {
            val tunnelDns = usesTunnelDns(dnsRoute, protectionEnabled, routeFinal)
            put("servers", JSONArray().apply {
                // DoH always connects to a literal resolver IP, so there is no plaintext
                // bootstrap lookup either way. What differs is the path:
                //   tunnelDns → detour through the proxy, so the resolver (and any leak
                //     test) sees the exit region, at the cost of a full round-trip per name.
                //   !tunnelDns → straight out the direct outbound: fast, but the resolver
                //     sees the real region. DoH still hides queries from the ISP and blocks
                //     poisoning; only the fact that this resolver is in use is visible.
                // usesTunnelDns() picks between them (auto = tunnel except on XHTTP, where
                // the over-tunnel round-trip — ~700ms measured — stalls video sessions).
                put(JSONObject().apply {
                    put("tag", "doh"); put("type", "https")
                    put("server", option.server)
                    if (tunnelDns) put("detour", routeFinal)
                    option.tlsServerName?.let { serverName ->
                        put("tls", JSONObject().put("server_name", serverName))
                    }
                })
                if (tunnelDns) {
                    put(JSONObject().apply {
                        put("tag", "bootstrap"); put("type", "https")
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
                    })
                }
                if (!protectionEnabled) {
                    put(JSONObject().apply {
                        put("tag", "local"); put("type", "local")
                    })
                }
            })
            val rules = JSONArray()
            // Suppressing HTTPS/SVCB answers is what actually stops HTTP/3: rejecting QUIC in
            // the route table only kills the connection *after* the browser has committed to
            // it, and Chrome learns h3 from this record (alpn="h3") before any packet is sent.
            // Without it every h3-capable host pays an attempt-then-reset-then-fall-back round
            // for each new connection, which is worst exactly where it hurts — Google
            // properties, which advertise h3 everywhere. Cost is losing ECH (also carried in
            // this record); acceptable, since we already refuse the transport it advertises.
            if (blockQuic) {
                rules.put(JSONObject().apply {
                    put("query_type", JSONArray().put("HTTPS"))
                    put("action", "predefined")
                    put("rcode", "NOERROR")
                })
            }
            // Must precede the media rule below: an AAAA for a host on both lists is answered
            // here instead of being routed anywhere.
            rules.put(suppressAaaaRule())
            if (tunnelDns && dnsRoute == Settings.DNS_ROUTE_AUTO) rules.put(mediaDnsBootstrapRule())
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
        .put("domain_suffix", JSONArray().apply { PHONE_RESOLVE_SUFFIXES.forEach(::put) })
        .put("action", "predefined")
        .put("rcode", "NOERROR")

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
        .put("domain_suffix", JSONArray().apply { MEDIA_DNS_DIRECT_SUFFIXES.forEach(::put) })
        .put("server", "bootstrap")

    /**
     * Hostnames that only ever name a CDN edge node, never a region-gated service. Kept
     * deliberately short: everything here is resolved off the tunnel by
     * [mediaDnsBootstrapRule], so a name whose answer *does* depend on the asking region does
     * not belong on this list.
     */
    private val MEDIA_DNS_DIRECT_SUFFIXES = listOf("googlevideo.com")

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
     * `auto` blocks QUIC on every transport. It used to block only on XHTTP, on the reasoning
     * that raw-TCP/REALITY carries XUDP cheaply enough to leave HTTP/3 alone — but that split
     * was the one difference between a profile where Gemini loaded and an otherwise identical
     * one where it did not load at all, on the same server and the same exit IP. Whichever half
     * does the damage (QUIC over XUDP, or the `ipv6hint`/`alpn=h3` that survives in the HTTPS
     * record while QUIC is allowed — [dnsBlock] suppresses that record only when QUIC is
     * blocked), both halves rode on `hasXhttp`, so the transport split is what goes. `allow`
     * still exists for anyone who wants HTTP/3 back.
     */
    private fun shouldBlockQuic(policy: String): Boolean = policy != Settings.QUIC_POLICY_ALLOW

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

    private fun normalizeLogLevel(level: String): String = when (level) {
        Settings.LOG_LEVEL_INFO, Settings.LOG_LEVEL_DEBUG, Settings.LOG_LEVEL_TRACE -> level
        else -> Settings.LOG_LEVEL_WARN
    }

    private fun normalizeTestUrl(url: String): String =
        url.trim().takeIf(Settings::isValidTarnTestUrl) ?: Settings.DEFAULT_TARN_TEST_URL

    private fun isQuicRejectRule(rule: JSONObject): Boolean =
        rule.optString("protocol").equals("quic", ignoreCase = true) &&
            rule.optString("action") == "reject"

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
    private fun quicRejectRule(): JSONObject =
        JSONObject().put("protocol", "quic").put("action", "reject").put("no_drop", true)

    /**
     * `override_destination` makes the outbound send the sniffed hostname upstream instead of
     * the address the phone resolved — what Xray, and so v2rayNG, does by default. This is the
     * fix for Gemini answering "not available in your region": the exit was connecting to
     * whichever Google front-end the *phone* had picked, rather than choosing one itself.
     *
     * Requires the lx core patch that re-exposes the flag on the sniff action; an older core
     * rejects the unknown field, which the repository catches through `Libbox.checkConfig`
     * before it can write a config the running service cannot load.
     */
    private fun sniffRule(sendHostname: Boolean): JSONObject = JSONObject()
        .put("action", "sniff")
        .apply { if (sendHostname) put("override_destination", true) }

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
     * No `strategy`: an empty one is AsIs, which defers to `dns.strategy` (`dns/router.go:799`),
     * so the IPv6 toggle keeps governing the address family here as everywhere else — with it on
     * that is prefer_ipv4, the v4 YouTube Music needs. The lookup is nearly free: the app's own
     * query for the same name went through the hijacked resolver moments earlier and sits in the
     * same (optimistic) cache.
     *
     * For the matched hosts this also restores `ip_is_private` matching (they carry addresses
     * again) — a non-issue here since none are private, but it means the LAN-by-name caveat of
     * overriding the destination applies only to the un-exempt majority.
     */
    private fun phoneResolveRule(): JSONObject = JSONObject()
        .put("domain_suffix", JSONArray().apply { PHONE_RESOLVE_SUFFIXES.forEach(::put) })
        .put("action", "resolve")

    /**
     * Strips any generated address-selection rule so repatch re-emits it fresh: the current
     * phone-resolve rule, plus artifacts of earlier builds (a bare global `resolve`, and a
     * `domain_suffix`+`outbound` hostname rule). Generated configs are the only source of a
     * `domain_suffix` rule or a `resolve` action, so matching on either is safe.
     */
    private fun isGeneratedDestinationRule(rule: JSONObject): Boolean =
        rule.optString("action") == "resolve" || rule.has("domain_suffix")

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
    ): JSONArray {
        val retained = mutableListOf<JSONObject>()
        for (i in 0 until rules.length()) {
            val rule = rules.optJSONObject(i) ?: continue
            if (isQuicRejectRule(rule) ||
                isDnsDirectRule(rule, oldDohServer) ||
                isIpv6RejectRule(rule) ||
                isGeneratedDestinationRule(rule)
            ) continue
            // Regenerated in place so the hostname flag tracks the setting; keeping the stored
            // rule would freeze whatever it was imported with.
            retained += if (isSniffRule(rule)) sniffRule(sendHostname) else rule
        }

        val generated = buildList {
            if (blockQuic) add(quicRejectRule())
            if (routeDnsDirect) add(dnsDirectRule(dnsOption))
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

    /** Try base64-decode; fall back to raw. Extract share links of any supported scheme. */
    private fun expandSubscriptionBody(body: String): List<String> {
        val trimmed = body.trim()
        val prefixes = ProxyUriParser.schemePrefixes()
        fun linesOf(text: String) = text.split('\n', '\r')
            .map { it.trim() }
            .filter { line -> prefixes.any { line.startsWith(it, ignoreCase = true) } }

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

        val outboundsArr = JSONArray()
        // If more than one server: put an urltest selector first, name it "proxy".
        val routeFinal: String
        if (servers.size > 1) {
            routeFinal = "proxy"
            outboundsArr.put(JSONObject().apply {
                put("type", "urltest")
                put("tag", "proxy")
                put("outbounds", JSONArray().apply {
                    servers.forEach { put(it.tag) }
                })
                put("url", normalizeTestUrl(settings.testUrl))
                put("interval", "3m")
            })
        } else {
            routeFinal = servers[0].tag
        }
        val tunnelDns = usesTunnelDns(settings.dnsRoute, settings.dnsProtection, routeFinal)
        val routeDnsDirect = !tunnelDns
        servers.forEach { outboundsArr.put(it.json) }
        outboundsArr.put(JSONObject().apply {
            put("type", "direct"); put("tag", "direct")
        })

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
                ),
            )
            put("inbounds", JSONArray().put(JSONObject().apply {
                put("type", "tun")
                put("tag", "tun-in")
                put("address", tunAddress())
                if (normalizedMtu != 0) put("mtu", normalizedMtu)
                put("auto_route", true)
                put("strict_route", true)
            }))
            put("outbounds", outboundsArr)
            put("route", JSONObject().apply {
                put("rules", JSONArray().apply {
                    put(sniffRule(settings.sendHostname))
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
                    // Everything reaches the server as a name (so it resolves in its own region);
                    // YouTube is the one exception, phone-resolved to a v4 address. See
                    // phoneResolveRule() for why the exception list runs this way round.
                    if (settings.sendHostname) put(phoneResolveRule())
                    put(JSONObject().put("ip_is_private", true).put("outbound", "direct"))
                })
                put("final", routeFinal)
                put("auto_detect_interface", true)
                // Resolve the VPN endpoint itself through the literal-IP encrypted resolver;
                // otherwise an endpoint hostname leaks to the system DNS before connect.
                put("default_domain_resolver", if (tunnelDns) "bootstrap" else "doh")
            })
            put("experimental", experimentalBlock())
        }
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
