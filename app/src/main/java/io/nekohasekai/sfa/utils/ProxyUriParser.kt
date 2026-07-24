package io.nekohasekai.sfa.utils

import android.net.Uri
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject

/**
 * Turns a share link into one sing-box outbound.
 *
 * The core is built with every protocol below registered (`include/registry.go`, plus
 * `with_quic` in the AAR tags for hysteria2/tuic), but the import dialog only ever understood
 * `vless://` — so servers the core could happily run were simply unreachable from the app.
 *
 * Field names are taken from the core's own option structs rather than from memory: see
 * `option/shadowsocks.go`, `option/trojan.go`, `option/vmess.go`, `option/hysteria2.go`,
 * `option/tuic.go`, `option/anytls.go` and `option/tls.go`.
 */
object ProxyUriParser {

    data class ParsedOutbound(val tag: String, val json: JSONObject, val sourceUri: String)

    private val SCHEMES = setOf("vless", "trojan", "ss", "vmess", "hysteria2", "hy2", "tuic", "anytls")

    fun isSupportedUri(input: String): Boolean {
        val scheme = input.trim().substringBefore("://", "").lowercase()
        return scheme in SCHEMES
    }

    /** Every scheme [parse] accepts, for the "did the subscription contain anything" check. */
    fun schemePrefixes(): List<String> = SCHEMES.map { "$it://" }

    fun parse(rawUri: String, fragmentEnabled: Boolean): ParsedOutbound {
        val trimmed = rawUri.trim()
        return when (trimmed.substringBefore("://", "").lowercase()) {
            "vless" -> parseVless(trimmed, fragmentEnabled)
            "trojan" -> parseTrojan(trimmed, fragmentEnabled)
            "ss" -> parseShadowsocks(trimmed)
            "vmess" -> parseVMess(trimmed, fragmentEnabled)
            "hysteria2", "hy2" -> parseHysteria2(trimmed)
            "tuic" -> parseTuic(trimmed)
            "anytls" -> parseAnyTLS(trimmed)
            else -> throw IllegalArgumentException("Unsupported scheme in: ${trimmed.take(24)}…")
        }
    }

    /**
     * Applies TCP-layer TLS fragmentation.
     *
     * This used to skip REALITY: the core built its uTLS connection straight on the raw socket
     * and never reached the wrapper that installs the fragmenter, so the setting was inert
     * exactly where it matters most. The lx core patch in `common/tls/reality_client.go`
     * installs it on that path too, so REALITY is no longer an exception here.
     */
    fun applyFragment(tls: JSONObject, enabled: Boolean) {
        if (enabled) {
            tls.put("fragment", true)
            tls.put("record_fragment", true)
        } else {
            tls.remove("fragment")
            tls.remove("record_fragment")
        }
    }

    // ---- vless ------------------------------------------------------------------------

    private fun parseVless(rawUri: String, fragmentEnabled: Boolean): ParsedOutbound {
        val uri = Uri.parse(rawUri)
        val userInfo = uri.userInfo ?: throw IllegalArgumentException("Missing UUID in vless URL")
        val host = uri.host ?: throw IllegalArgumentException("Missing host in vless URL")
        val port = if (uri.port > 0) uri.port else 443
        val tag = displayTag(uri, host, port)
        val q = parseQuery(uri.query.orEmpty())

        val transportType = q["type"] ?: "tcp"
        // xtls-rprx-vision flow is only valid over raw TCP+Reality. With a stream transport
        // (xhttp/ws/grpc) it must be empty, otherwise the tunnel connects but silently passes
        // no data.
        val hasTransport = transportType != "tcp" && transportType != "raw" && transportType.isNotEmpty()

        val outbound = JSONObject().apply {
            put("type", "vless")
            put("tag", tag)
            put("server", host)
            put("server_port", port)
            put("uuid", userInfo)
            put("flow", if (hasTransport) "" else q["flow"].orEmpty())
        }
        streamTls(q, host, q["security"] ?: "none", fragmentEnabled)?.let { outbound.put("tls", it) }
        streamTransport(q, transportType)?.let { outbound.put("transport", it) }
        return ParsedOutbound(tag, outbound, rawUri)
    }

    // ---- trojan -----------------------------------------------------------------------

    private fun parseTrojan(rawUri: String, fragmentEnabled: Boolean): ParsedOutbound {
        val uri = Uri.parse(rawUri)
        val password = uri.userInfo?.let(Uri::decode)
            ?: throw IllegalArgumentException("Missing password in trojan URL")
        val host = uri.host ?: throw IllegalArgumentException("Missing host in trojan URL")
        val port = if (uri.port > 0) uri.port else 443
        val tag = displayTag(uri, host, port)
        val q = parseQuery(uri.query.orEmpty())

        val outbound = JSONObject().apply {
            put("type", "trojan")
            put("tag", tag)
            put("server", host)
            put("server_port", port)
            put("password", password)
        }
        // Trojan is TLS-only by definition; `security=none` is the odd case, not the default.
        streamTls(q, host, q["security"] ?: "tls", fragmentEnabled)?.let { outbound.put("tls", it) }
        streamTransport(q, q["type"] ?: "tcp")?.let { outbound.put("transport", it) }
        return ParsedOutbound(tag, outbound, rawUri)
    }

    // ---- shadowsocks ------------------------------------------------------------------

    /**
     * Accepts both SIP002 (`ss://base64(method:password)@host:port#tag`) and the older
     * fully-encoded form (`ss://base64(method:password@host:port)#tag`).
     */
    private fun parseShadowsocks(rawUri: String): ParsedOutbound {
        val fragment = rawUri.substringAfter('#', "").let(Uri::decode).orEmpty()
        val body = rawUri.removePrefix("ss://").substringBefore('#')

        val query = body.substringAfter('?', "")
        val withoutQuery = body.substringBefore('?')

        val method: String
        val password: String
        val host: String
        val port: Int

        if (withoutQuery.contains('@')) {
            val credentials = decodeBase64(withoutQuery.substringBeforeLast('@'))
                ?: withoutQuery.substringBeforeLast('@')
            method = credentials.substringBefore(':')
            password = credentials.substringAfter(':', "")
            val endpoint = withoutQuery.substringAfterLast('@')
            host = endpoint.substringBeforeLast(':')
            port = endpoint.substringAfterLast(':').toIntOrNull()
                ?: throw IllegalArgumentException("Missing port in ss URL")
        } else {
            val decoded = decodeBase64(withoutQuery)
                ?: throw IllegalArgumentException("Malformed ss URL")
            method = decoded.substringBefore(':')
            val rest = decoded.substringAfter(':', "")
            password = rest.substringBeforeLast('@')
            val endpoint = rest.substringAfterLast('@')
            host = endpoint.substringBeforeLast(':')
            port = endpoint.substringAfterLast(':').toIntOrNull()
                ?: throw IllegalArgumentException("Missing port in ss URL")
        }
        require(method.isNotBlank()) { "Missing cipher in ss URL" }
        require(host.isNotBlank()) { "Missing host in ss URL" }

        val tag = fragment.ifBlank { "$host:$port" }
        val outbound = JSONObject().apply {
            put("type", "shadowsocks")
            put("tag", tag)
            put("server", host)
            put("server_port", port)
            put("method", method)
            put("password", password)
        }
        // SIP003 plugins pass straight through; the core owns whether it supports one.
        parseQuery(query)["plugin"]?.takeIf { it.isNotBlank() }?.let { plugin ->
            outbound.put("plugin", plugin.substringBefore(';'))
            plugin.substringAfter(';', "").takeIf { it.isNotBlank() }
                ?.let { outbound.put("plugin_opts", it) }
        }
        return ParsedOutbound(tag, outbound, rawUri)
    }

    // ---- vmess ------------------------------------------------------------------------

    /** The widely used v2rayN form: `vmess://` followed by base64 of a JSON object. */
    private fun parseVMess(rawUri: String, fragmentEnabled: Boolean): ParsedOutbound {
        val decoded = decodeBase64(rawUri.removePrefix("vmess://").substringBefore('#'))
            ?: throw IllegalArgumentException("Malformed vmess URL")
        val json = runCatching { JSONObject(decoded) }.getOrNull()
            ?: throw IllegalArgumentException("vmess payload is not JSON")

        val host = json.optString("add").ifBlank { throw IllegalArgumentException("Missing host in vmess URL") }
        val port = json.optString("port").toIntOrNull() ?: json.optInt("port", 443)
        val tag = json.optString("ps").ifBlank { "$host:$port" }

        val outbound = JSONObject().apply {
            put("type", "vmess")
            put("tag", tag)
            put("server", host)
            put("server_port", port)
            put("uuid", json.optString("id"))
            put("security", json.optString("scy").ifBlank { "auto" })
            json.optString("aid").toIntOrNull()?.takeIf { it > 0 }?.let { put("alter_id", it) }
        }

        // vmess share links spell the stream settings differently from the vless query string;
        // normalise them so streamTls/streamTransport stay the single implementation.
        val q = buildMap {
            json.optString("sni").takeIf { it.isNotBlank() }?.let { put("sni", it) }
            json.optString("host").takeIf { it.isNotBlank() }?.let { put("host", it) }
            json.optString("path").takeIf { it.isNotBlank() }?.let { put("path", it) }
            json.optString("alpn").takeIf { it.isNotBlank() }?.let { put("alpn", it) }
            json.optString("fp").takeIf { it.isNotBlank() }?.let { put("fp", it) }
            json.optString("serviceName").takeIf { it.isNotBlank() }?.let { put("serviceName", it) }
        }
        val security = json.optString("tls").ifBlank { "none" }
        streamTls(q, host, security, fragmentEnabled)?.let { outbound.put("tls", it) }
        streamTransport(q, json.optString("net").ifBlank { "tcp" })?.let { outbound.put("transport", it) }
        return ParsedOutbound(tag, outbound, rawUri)
    }

    // ---- hysteria2 --------------------------------------------------------------------

    private fun parseHysteria2(rawUri: String): ParsedOutbound {
        val uri = Uri.parse(rawUri)
        val host = uri.host ?: throw IllegalArgumentException("Missing host in hysteria2 URL")
        val port = if (uri.port > 0) uri.port else 443
        val tag = displayTag(uri, host, port)
        val q = parseQuery(uri.query.orEmpty())
        // Userinfo may carry `password` or the legacy `user:password`; the core wants one field.
        val password = uri.userInfo?.let(Uri::decode).orEmpty()

        val outbound = JSONObject().apply {
            put("type", "hysteria2")
            put("tag", tag)
            put("server", host)
            put("server_port", port)
            if (password.isNotEmpty()) put("password", password)
            q["obfs"]?.takeIf { it.isNotBlank() }?.let { obfs ->
                put(
                    "obfs",
                    JSONObject().put("type", obfs).apply {
                        (q["obfs-password"] ?: q["obfs_password"])?.let { put("password", it) }
                    },
                )
            }
            // QUIC-based: TLS is not optional, so it is built unconditionally.
            put("tls", quicTls(q, host))
        }
        return ParsedOutbound(tag, outbound, rawUri)
    }

    // ---- tuic -------------------------------------------------------------------------

    private fun parseTuic(rawUri: String): ParsedOutbound {
        val uri = Uri.parse(rawUri)
        val host = uri.host ?: throw IllegalArgumentException("Missing host in tuic URL")
        val port = if (uri.port > 0) uri.port else 443
        val tag = displayTag(uri, host, port)
        val q = parseQuery(uri.query.orEmpty())
        val userInfo = uri.userInfo.orEmpty()

        val outbound = JSONObject().apply {
            put("type", "tuic")
            put("tag", tag)
            put("server", host)
            put("server_port", port)
            put("uuid", Uri.decode(userInfo.substringBefore(':')))
            userInfo.substringAfter(':', "").takeIf { it.isNotBlank() }
                ?.let { put("password", Uri.decode(it)) }
            (q["congestion_control"] ?: q["congestion-control"])?.let { put("congestion_control", it) }
            (q["udp_relay_mode"] ?: q["udp-relay-mode"])?.let { put("udp_relay_mode", it) }
            put("tls", quicTls(q, host))
        }
        return ParsedOutbound(tag, outbound, rawUri)
    }

    // ---- anytls -----------------------------------------------------------------------

    private fun parseAnyTLS(rawUri: String): ParsedOutbound {
        val uri = Uri.parse(rawUri)
        val host = uri.host ?: throw IllegalArgumentException("Missing host in anytls URL")
        val port = if (uri.port > 0) uri.port else 443
        val tag = displayTag(uri, host, port)
        val q = parseQuery(uri.query.orEmpty())

        val outbound = JSONObject().apply {
            put("type", "anytls")
            put("tag", tag)
            put("server", host)
            put("server_port", port)
            uri.userInfo?.let(Uri::decode)?.takeIf { it.isNotBlank() }?.let { put("password", it) }
            put("tls", quicTls(q, host))
        }
        return ParsedOutbound(tag, outbound, rawUri)
    }

    // ---- shared pieces ----------------------------------------------------------------

    /** TLS for protocols that are always encrypted and never carry REALITY or a transport. */
    private fun quicTls(q: Map<String, String>, host: String): JSONObject = JSONObject().apply {
        put("enabled", true)
        put("server_name", q["sni"] ?: q["peer"] ?: host)
        if (isTruthy(q["insecure"]) || isTruthy(q["allowInsecure"]) || isTruthy(q["allow_insecure"])) {
            put("insecure", true)
        }
        alpnArray(q["alpn"])?.let { put("alpn", it) }
    }

    private fun streamTls(
        q: Map<String, String>,
        host: String,
        security: String,
        fragmentEnabled: Boolean,
    ): JSONObject? {
        val normalized = security.lowercase()
        if (normalized != "tls" && normalized != "reality" && normalized != "xtls") return null
        val tls = JSONObject().apply {
            put("enabled", true)
            put("server_name", q["sni"] ?: q["host"] ?: host)
            if (isTruthy(q["allowInsecure"]) || isTruthy(q["insecure"])) put("insecure", true)
            alpnArray(q["alpn"])?.let { put("alpn", it) }
            put(
                "utls",
                JSONObject().put("enabled", true).put("fingerprint", q["fp"] ?: "chrome"),
            )
        }
        if (normalized == "reality") {
            val publicKey = q["pbk"] ?: throw IllegalArgumentException("reality requires 'pbk'")
            tls.put(
                "reality",
                JSONObject().apply {
                    put("enabled", true)
                    put("public_key", publicKey)
                    put("short_id", q["sid"].orEmpty())
                },
            )
        }
        applyFragment(tls, fragmentEnabled)
        return tls
    }

    private fun streamTransport(q: Map<String, String>, transportType: String): JSONObject? =
        when (transportType.lowercase()) {
            "", "tcp", "raw", "none" -> null
            "xhttp", "splithttp" -> JSONObject().apply {
                put("type", "xhttp")
                q["host"]?.let { put("host", it) }
                q["path"]?.let { put("path", it) }
                // Pass the server's mode through verbatim: an XHTTP inbound accepts only the
                // mode it is configured for and answers "<mode> is not allowed" for anything
                // else, so substituting a "safer" mode breaks the connection outright.
                put("mode", q["mode"] ?: "auto")
                applyXhttpExtras(this, q)
            }
            "ws", "websocket" -> JSONObject().apply {
                put("type", "ws")
                q["path"]?.let { put("path", it) }
                q["host"]?.let { put("headers", JSONObject().put("Host", it)) }
            }
            "grpc" -> JSONObject().apply {
                put("type", "grpc")
                (q["serviceName"] ?: q["servicename"])?.let { put("service_name", it) }
            }
            "httpupgrade" -> JSONObject().apply {
                put("type", "httpupgrade")
                q["path"]?.let { put("path", it) }
                q["host"]?.let { put("host", it) }
            }
            else -> JSONObject().put("type", transportType)
        }

    /**
     * Client-relevant XHTTP knobs the core accepts (`option/v2ray_xhttp.go`). Only `host`,
     * `path` and `mode` used to survive import, so a subscription that had tuned padding or
     * post pacing — the parts that exist precisely to get through filtering — silently lost
     * them. Server-only fields (`sc_max_buffered_posts`, `sc_stream_up_server_secs`,
     * `server_max_header_bytes`, `no_sse_header`) are deliberately not forwarded: the core
     * documents them as ignored by the client, so copying them would only add noise.
     */
    private val XHTTP_STRING_KEYS = listOf(
        "session_placement", "session_key", "seq_placement", "seq_key",
        "uplink_data_placement", "uplink_data_key", "uplink_chunk_size", "uplink_http_method",
        "x_padding_bytes", "x_padding_key", "x_padding_header", "x_padding_placement", "x_padding_method",
        "sc_max_each_post_bytes", "sc_min_posts_interval_ms",
    )

    private val XHTTP_BOOL_KEYS = listOf("x_padding_obfs_mode", "no_grpc_header")

    /**
     * Links spell these either the core's way (`sc_min_posts_interval_ms`) or Xray's
     * (`scMinPostsIntervalMs`), and Xray additionally packs them into an `extra` JSON blob.
     * Folding every key to letters-only makes all three spellings compare equal, which beats
     * maintaining an alias table that would drift from the core's option list.
     */
    private fun applyXhttpExtras(transport: JSONObject, q: Map<String, String>) {
        val flat = mutableMapOf<String, String>()
        q["extra"]?.let { raw ->
            runCatching { JSONObject(raw) }.getOrNull()?.let { extra ->
                extra.keys().forEach { key -> flat[foldKey(key)] = extra.optString(key) }
            }
        }
        // An explicit query parameter beats the same key inside `extra`.
        q.forEach { (key, value) -> flat[foldKey(key)] = value }

        XHTTP_STRING_KEYS.forEach { key ->
            flat[foldKey(key)]?.takeIf { it.isNotBlank() }?.let { transport.put(key, it) }
        }
        XHTTP_BOOL_KEYS.forEach { key ->
            flat[foldKey(key)]?.takeIf { isTruthy(it) }?.let { transport.put(key, true) }
        }
    }

    private fun foldKey(key: String): String =
        key.lowercase().filter { it.isLetterOrDigit() }

    private fun displayTag(uri: Uri, host: String, port: Int): String =
        uri.fragment?.takeIf { it.isNotBlank() } ?: "$host:$port"

    private fun alpnArray(raw: String?): JSONArray? {
        val values = raw?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() }.orEmpty()
        if (values.isEmpty()) return null
        return JSONArray().apply { values.forEach(::put) }
    }

    private fun isTruthy(value: String?): Boolean =
        value != null && (value == "1" || value.equals("true", ignoreCase = true))

    private fun decodeBase64(raw: String): String? {
        if (raw.isBlank()) return null
        for (flags in intArrayOf(
            Base64.URL_SAFE or Base64.NO_PADDING,
            Base64.DEFAULT or Base64.NO_PADDING,
            Base64.URL_SAFE,
            Base64.DEFAULT,
        )) {
            val decoded = runCatching {
                String(Base64.decode(raw, flags or Base64.NO_WRAP))
            }.getOrNull()
            if (!decoded.isNullOrBlank()) return decoded
        }
        return null
    }

    private fun parseQuery(query: String): Map<String, String> {
        if (query.isBlank()) return emptyMap()
        val out = mutableMapOf<String, String>()
        for (pair in query.split('&')) {
            val eq = pair.indexOf('=')
            if (eq < 0) continue
            out[Uri.decode(pair.substring(0, eq))] = Uri.decode(pair.substring(eq + 1))
        }
        return out
    }
}
