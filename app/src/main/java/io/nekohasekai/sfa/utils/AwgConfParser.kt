package io.nekohasekai.sfa.utils

import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.util.zip.Inflater

/**
 * Imports WireGuard / AmneziaWG client configurations.
 *
 * Two inputs are accepted:
 *
 *  - a wg-quick `.conf` file (`[Interface]` + `[Peer]`, including the
 *    AmneziaWG 1.x/2.x/3.x keys), and
 *  - an Amnezia `vpn://` link. The link is base64url(qCompress(JSON)), where
 *    qCompress is a 4-byte big-endian length followed by a zlib stream. The
 *    JSON carries one `awg`/`wireguard` container per profile and the real
 *    wg-quick text under `last_config.config`. A bare `.conf` wrapped in
 *    `vpn://` (some panels do this) and an uncompressed JSON payload are
 *    accepted too.
 *
 * The result is a sing-box `wireguard` **endpoint** (sing-box 1.13 removed
 * the old wireguard outbound), with the AWG parameters at the endpoint root:
 * `jc`/`jmin`/`jmax`, `s1`..`s4`, `h1`..`h4`, `i1`..`i5`, plus the AWG 3.x
 * `header_protection_key`, `content_padding_addition`, timing ranges,
 * `random_trailers` and `disable_cookies`.
 */
object AwgConfParser {

    private const val MAX_VPN_LINK = 512 * 1024
    private const val MAX_INFLATED = 4 * 1024 * 1024
    private const val DEFAULT_WG_PORT = 51820
    private const val AWG_MTU_CEILING = 1280

    private val CONFIG_SCHEMES = listOf("awg://", "amneziawg://", "wireguard://", "wg://")

    fun isAwgInput(input: String): Boolean {
        val text = input.trim()
        if (text.isEmpty()) return false
        if (text.startsWith("vpn://", ignoreCase = true)) return true
        if (looksLikeConfigLink(text)) return true
        return looksLikeIni(text)
    }

    /**
     * Parses one input into one or more server entries. A `vpn://` bundle may
     * contain several WG/AWG containers; each becomes its own profile, with
     * the extracted `.conf` text as its `sourceUri` so a later repatch can
     * rebuild it without re-downloading anything.
     */
    fun parse(input: String): List<ProxyUriParser.ParsedOutbound> {
        val text = input.trim()
        return when {
            text.startsWith("vpn://", ignoreCase = true) -> parseVpnLink(text)
            looksLikeConfigLink(text) -> parseConfigLink(text)
            looksLikeIni(text) -> listOf(parseIni(text, ""))
            else -> throw IllegalArgumentException("Not a WireGuard/AmneziaWG config")
        }
    }

    private fun looksLikeIni(text: String): Boolean {
        var hasInterface = false
        var hasPeer = false
        for (raw in text.lineSequence()) {
            val line = raw.trim()
            if (line.isEmpty() || line.startsWith("#") || line.startsWith(";")) continue
            when (line.lowercase()) {
                "[interface]" -> hasInterface = true
                "[peer]" -> hasPeer = true
            }
            if (hasInterface && hasPeer) return true
        }
        return false
    }

    private fun looksLikeConfigLink(text: String): Boolean {
        val scheme = CONFIG_SCHEMES.firstOrNull { text.startsWith(it, ignoreCase = true) } ?: return false
        val body = text.substring(scheme.length).substringBefore('#').trim()
        // The key@host:port forms carry '@' or '?' and are not handled here.
        if (body.isEmpty() || body.contains('@') || body.contains('?')) return false
        return body.all { it.isLetterOrDigit() || it == '+' || it == '/' || it == '=' || it == '_' || it == '-' }
    }

    private fun parseConfigLink(text: String): List<ProxyUriParser.ParsedOutbound> {
        val scheme = CONFIG_SCHEMES.firstOrNull { text.startsWith(it, ignoreCase = true) }!!
        val body = text.substring(scheme.length)
        val fragment = body.substringAfter('#', "").let(::decodeName)
        val encoded = body.substringBefore('#')
        val decoded = decodeBase64Safe(encoded)
            ?: throw IllegalArgumentException("WireGuard config link is not valid base64")
        val ini = String(decoded, Charsets.UTF_8)
        if (!looksLikeIni(ini)) {
            throw IllegalArgumentException("WireGuard config link does not contain a .conf")
        }
        return listOf(parseIni(ini, fragment))
    }

    private fun parseVpnLink(text: String): List<ProxyUriParser.ParsedOutbound> {
        if (text.length > MAX_VPN_LINK) {
            throw IllegalArgumentException("vpn:// link is too long (max $MAX_VPN_LINK characters)")
        }
        val payload = text.substring(6).trim()
        val bytes = decodeBase64Safe(payload)
            ?: throw IllegalArgumentException("vpn:// payload is not valid base64")

        // A bare .conf wrapped in vpn:// is a real export form. Judge it by
        // its first non-comment line, exactly like a pasted .conf.
        val bare = runCatching { String(bytes, Charsets.UTF_8) }.getOrNull()
        if (bare != null && looksLikeIni(bare)) {
            return listOf(parseIni(bare, ""))
        }

        val jsonText = inflate(bytes)
            ?: throw IllegalArgumentException("vpn:// payload is neither qCompress nor JSON")
        val root = runCatching { JSONObject(jsonText) }.getOrNull()
            ?: throw IllegalArgumentException("vpn:// payload is not a JSON object")
        val containers = root.optJSONArray("containers")
            ?: throw IllegalArgumentException("vpn:// payload has no containers[]")

        val servers = mutableListOf<ProxyUriParser.ParsedOutbound>()
        for (index in 0 until containers.length()) {
            val container = containers.optJSONObject(index) ?: continue
            val protocol = container.optJSONObject("awg") ?: container.optJSONObject("wireguard") ?: continue
            val lastConfig = protocol.opt("last_config")
            val pairs = mutableListOf<Pair<String, JSONObject>>()
            when (lastConfig) {
                is String -> runCatching { JSONObject(lastConfig) }.getOrNull()?.let { pairs += "" to it }
                is JSONObject -> pairs += "" to lastConfig
            }
            for ((_, configObject) in pairs) {
                var ini = configObject.optString("config")
                if (ini.isBlank()) ini = configFromStructured(configObject) ?: continue
                if (!looksLikeIni(ini)) continue
                ini = withLastConfigMtu(ini, configObject.opt("mtu"))
                ini = substituteDns(ini, root)
                val label = profileLabel(root, container, index)
                servers += parseIni(ini, label)
            }
        }
        if (servers.isEmpty()) {
            throw IllegalArgumentException("vpn:// contains no WireGuard/AmneziaWG container")
        }
        return servers
    }

    private fun profileLabel(root: JSONObject, container: JSONObject, index: Int): String {
        root.optString("description").takeIf { it.isNotBlank() }?.let { return it }
        root.optString("hostName").takeIf { it.isNotBlank() }?.let { return it }
        container.optString("container").takeIf { it.isNotBlank() }?.let { return it }
        return "AmneziaWG $index"
    }

    private fun configFromStructured(config: JSONObject): String? {
        fun key(name: String): String = config.optString(name).takeIf { it.isNotBlank() }.orEmpty()
        val privateKey = key("client_priv_key")
        val publicKey = key("server_pub_key")
        if (privateKey.isBlank() || publicKey.isBlank()) return null
        val host = key("hostName")
        if (host.isBlank()) return null
        val port = config.optInt("port", DEFAULT_WG_PORT).takeIf { it in 1..65535 } ?: DEFAULT_WG_PORT
        val lines = mutableListOf("[Interface]", "PrivateKey = $privateKey")
        val address = key("client_ip")
        if (address.isNotBlank()) lines += "Address = $address"
        key("mtu").takeIf { it.isNotBlank() }?.let { lines += "MTU = $it" }
        for (name in listOf(
            "Jc", "Jmin", "Jmax", "S1", "S2", "S3", "S4", "H1", "H2", "H3", "H4",
            "I1", "I2", "I3", "I4", "I5", "HeaderProtectionKey", "ContentPaddingAddition",
            "RekeyAfterTime", "RekeyTimeout", "RejectAfterTime", "KeepaliveTimeout",
            "MaxHandshakeAttempts", "RandomTrailers", "DisableCookies",
        )) {
            key(name).takeIf { it.isNotBlank() }?.let { lines += "$name = $it" }
        }
        lines += ""
        lines += "[Peer]"
        lines += "PublicKey = $publicKey"
        key("psk_key").takeIf { it.isNotBlank() }?.let { lines += "PresharedKey = $it" }
        val allowed = config.optJSONArray("allowed_ips")
            ?.let { array ->
                (0 until array.length()).mapNotNull { index ->
                    array.optString(index).takeIf { it.isNotBlank() }
                }
            }
            ?.takeIf { it.isNotEmpty() }
            ?: listOf("0.0.0.0/0", "::/0")
        lines += "AllowedIPs = ${allowed.joinToString(", ")}"
        lines += "Endpoint = $host:$port"
        key("persistent_keep_alive").takeIf { it.isNotBlank() }?.let { lines += "PersistentKeepalive = $it" }
        return lines.joinToString("\n")
    }

    private fun withLastConfigMtu(ini: String, mtuRaw: Any?): String {
        val mtu = when (mtuRaw) {
            is Number -> mtuRaw.toInt()
            is String -> mtuRaw.trim().toIntOrNull()
            else -> null
        } ?: return ini
        if (mtu <= 0) return ini
        val lines = ini.split('\n').toMutableList()
        var section = ""
        var interfaceIndex = -1
        for (i in lines.indices) {
            val line = lines[i].trim()
            if (line.startsWith("[")) {
                section = line.lowercase()
                if (section == "[interface]" && interfaceIndex < 0) interfaceIndex = i
                continue
            }
            if (section != "[interface]") continue
            val eq = line.indexOf('=')
            if (eq > 0 && line.substring(0, eq).trim().equals("MTU", ignoreCase = true)) return ini
        }
        if (interfaceIndex < 0) return ini
        lines.add(interfaceIndex + 1, "MTU = $mtu")
        return lines.joinToString("\n")
    }

    private fun substituteDns(ini: String, root: JSONObject): String {
        val values = mapOf(
            "\$PRIMARY_DNS" to root.optString("dns1").trim(),
            "\$SECONDARY_DNS" to root.optString("dns2").trim(),
        )
        val out = mutableListOf<String>()
        for (line in ini.split('\n')) {
            val match = Regex("^(\\s*DNS\\s*=\\s*)(.*)$", RegexOption.IGNORE_CASE).find(line)
            if (match == null) {
                out += line
                continue
            }
            val items = match.groupValues[2].split(',').mapNotNull { raw ->
                var item = raw.trim()
                if (item.isEmpty()) return@mapNotNull null
                if (item.startsWith('$')) {
                    item = values[item].orEmpty()
                    if (item.isEmpty()) return@mapNotNull null
                }
                item
            }
            if (items.isNotEmpty()) out += match.groupValues[1] + items.joinToString(", ")
        }
        return out.joinToString("\n")
    }

    private fun decodeName(raw: String): String = runCatching { java.net.URLDecoder.decode(raw, "UTF-8") }
        .getOrDefault(raw).trim()

    private fun inflate(bytes: ByteArray): String? {
        if (bytes.size > 4) {
            val claimed = ((bytes[0].toInt() and 0xFF) shl 24) or
                ((bytes[1].toInt() and 0xFF) shl 16) or
                ((bytes[2].toInt() and 0xFF) shl 8) or
                (bytes[3].toInt() and 0xFF)
            if (claimed in 0..MAX_INFLATED) {
                runCatching {
                    val inflater = Inflater()
                    try {
                        inflater.setInput(bytes, 4, bytes.size - 4)
                        val out = ByteArrayOutputStream(claimed.coerceAtLeast(64))
                        val buffer = ByteArray(8192)
                        while (!inflater.finished()) {
                            val count = inflater.inflate(buffer)
                            if (count == 0) {
                                if (inflater.needsInput() || inflater.needsDictionary()) break
                                continue
                            }
                            out.write(buffer, 0, count)
                            if (out.size() > MAX_INFLATED) {
                                throw IllegalArgumentException("vpn:// payload is too large")
                            }
                        }
                        return String(out.toByteArray(), Charsets.UTF_8)
                    } finally {
                        inflater.end()
                    }
                }.onSuccess { return it }
            }
        }
        return runCatching {
            val text = String(bytes, Charsets.UTF_8).trimStart()
            text.takeIf { it.startsWith('{') }
        }.getOrNull()
    }

    private fun parseIni(text: String, nameHint: String): ProxyUriParser.ParsedOutbound {
        val parsed = readIni(text)
        val iface = parsed.interfaceValues
        val privateKey = normalizeWireGuardKey(
            iface["privatekey"]?.takeIf { it.isNotBlank() }
                ?: throw IllegalArgumentException("WireGuard config is missing [Interface] PrivateKey"),
            "PrivateKey",
        )
        val addresses = splitList(iface["address"])
        if (addresses.isEmpty()) {
            throw IllegalArgumentException("WireGuard config is missing [Interface] Address")
        }
        val peers = parsed.peers
        if (peers.isEmpty()) {
            throw IllegalArgumentException("WireGuard config has no [Peer] section")
        }

        val mtu = clampMtu(iface["mtu"])
        val endpoint = JSONObject().apply {
            put("type", "wireguard")
            put("address", JSONArray(addresses.map(::withPrefix)))
            put("private_key", privateKey)
            put("mtu", mtu)
        }
        iface["listenport"]?.trim()?.toIntOrNull()?.takeIf { it in 1..65535 }?.let {
            endpoint.put("listen_port", it)
        }

        val peerArray = JSONArray()
        var firstNameCandidate: String? = null
        var firstHost = ""
        var firstPort = DEFAULT_WG_PORT
        peers.forEachIndexed { index, peer ->
            val publicKey = normalizeWireGuardKey(
                peer["publickey"]?.takeIf { it.isNotBlank() }
                    ?: throw IllegalArgumentException("WireGuard config [Peer] is missing PublicKey"),
                "PublicKey",
            )
            val allowed = splitList(peer["allowedips"]).ifEmpty { listOf("0.0.0.0/0", "::/0") }
            val (host, port) = splitHostPort(peer["endpoint"].orEmpty())
            if (host.isBlank()) {
                throw IllegalArgumentException("WireGuard config [Peer] is missing Endpoint")
            }
            if (index == 0) {
                firstHost = host
                firstPort = port
                firstNameCandidate = parsed.peerComments.getOrNull(index)
            }
            val peerJson = JSONObject().apply {
                put("address", host)
                put("port", port)
                put("public_key", publicKey)
                put("allowed_ips", JSONArray(allowed.map(::withPrefix)))
                peer["presharedkey"]?.takeIf { it.isNotBlank() }?.let {
                    put("pre_shared_key", normalizeWireGuardKey(it, "PresharedKey"))
                }
                putKeepalive(this, peer["persistentkeepalive"])
            }
            peerArray.put(peerJson)
        }
        endpoint.put("peers", peerArray)

        putAwgFields(endpoint, iface)

        val name = sequenceOf(
            nameHint.trim(),
            firstNameCandidate?.trim().orEmpty(),
            "$firstHost:$firstPort",
        ).firstOrNull { it.isNotBlank() }!!.replace('\n', ' ').replace('\r', ' ').take(96)
        endpoint.put("tag", name)

        return ProxyUriParser.ParsedOutbound(name, endpoint, text.trim())
    }

    private data class IniValues(
        val interfaceValues: Map<String, String>,
        val peers: List<Map<String, String>>,
        val peerComments: List<String>,
    )

    private fun readIni(text: String): IniValues {
        val iface = LinkedHashMap<String, String>()
        val peers = mutableListOf<Map<String, String>>()
        val comments = mutableListOf<String>()
        var section = 0 // 0 none, 1 interface, 2 peer
        var current: LinkedHashMap<String, String>? = null
        var peerHasKey = false
        var pendingInterfaceComment = ""
        for (raw in text.lineSequence()) {
            val line = stripInlineComment(raw).trim()
            if (line.isEmpty()) continue
            if (line.startsWith("[") && line.endsWith("]")) {
                when (line.lowercase()) {
                    "[interface]" -> {
                        section = 1
                        current = iface
                    }
                    "[peer]" -> {
                        section = 2
                        current = LinkedHashMap()
                        peers += current
                        // 3x-ui writes its `# remark` right before [Peer]
                        // rather than under it; Proton writes it under.
                        comments += pendingInterfaceComment
                        pendingInterfaceComment = ""
                        peerHasKey = false
                    }
                    else -> {
                        section = 0
                        current = null
                    }
                }
                continue
            }
            if (line.startsWith("#") || line.startsWith(";")) {
                val comment = line.removePrefix("#").removePrefix(";").trim()
                if (comment.isNotEmpty() && !comment.contains('=')) {
                    if (section == 2 && !peerHasKey && comments.isNotEmpty()) {
                        if (comments.last().isBlank()) comments[comments.lastIndex] = comment
                    } else if (section == 1) {
                        pendingInterfaceComment = comment
                    }
                }
                continue
            }
            val eq = line.indexOf('=')
            if (eq <= 0 || current == null) continue
            val key = normalizeKey(line.substring(0, eq))
            val value = line.substring(eq + 1).trim()
            if (section == 2) peerHasKey = true
            if (key == "address" || key == "allowedips") {
                current[key] = current[key]?.let { "$it, $value" } ?: value
            } else {
                current[key] = value
            }
        }
        return IniValues(iface, peers, comments)
    }

    private fun normalizeKey(raw: String): String = raw.trim().lowercase().replace("-", "").replace("_", "").replace(" ", "")

    private fun stripInlineComment(line: String): String {
        var cut = line.length
        for (marker in charArrayOf('#', ';')) {
            val index = line.indexOf(marker)
            if (index > 0 && line[index - 1].isWhitespace() && index < cut) cut = index
        }
        return if (cut < line.length) line.substring(0, cut) else line
    }

    private fun splitList(value: String?): List<String> = value.orEmpty().split(',').map { it.trim() }.filter { it.isNotEmpty() }

    private fun withPrefix(address: String): String {
        if (address.contains('/')) return address
        return address + if (address.contains(':')) "/128" else "/32"
    }

    private fun clampMtu(raw: String?): Int {
        val configured = raw?.trim()?.toIntOrNull() ?: 0
        return if (configured in 1..AWG_MTU_CEILING) configured else AWG_MTU_CEILING
    }

    private fun splitHostPort(endpoint: String): Pair<String, Int> {
        val value = endpoint.trim()
        if (value.startsWith("[")) {
            val close = value.indexOf(']')
            if (close > 0) {
                val host = value.substring(1, close)
                val port = if (value.startsWith(":", close + 1)) {
                    value.substring(close + 2).toIntOrNull() ?: DEFAULT_WG_PORT
                } else {
                    DEFAULT_WG_PORT
                }
                return host to port
            }
        }
        val first = value.indexOf(':')
        val last = value.lastIndexOf(':')
        if (first > 0 && first == last) {
            val host = value.substring(0, last)
            val port = value.substring(last + 1).toIntOrNull() ?: DEFAULT_WG_PORT
            return host to port
        }
        return value.removeSurrounding("[", "]") to DEFAULT_WG_PORT
    }

    private fun putKeepalive(target: JSONObject, raw: String?) {
        val value = raw?.trim().orEmpty()
        if (value.isEmpty()) return
        val parts = value.split('-')
        if (parts.size > 2) throw IllegalArgumentException("Invalid PersistentKeepalive: $value")
        val low = parts[0].trim().toUIntOrNull()
            ?: throw IllegalArgumentException("Invalid PersistentKeepalive: $value")
        val high = if (parts.size == 2) {
            parts[1].trim().toUIntOrNull()
                ?: throw IllegalArgumentException("Invalid PersistentKeepalive: $value")
        } else {
            low
        }
        if (high < low) throw IllegalArgumentException("Invalid PersistentKeepalive range: $value")
        if (low == 0u && high == 0u) return
        target.put("persistent_keepalive_interval", if (low == high) low.toLong() else "$low-$high")
    }

    private fun putAwgFields(target: JSONObject, iface: Map<String, String>) {
        putNumber(target, "jc", iface["jc"], "Jc")
        putNumber(target, "jmin", iface["jmin"], "Jmin")
        putNumber(target, "jmax", iface["jmax"], "Jmax")
        for (index in 1..4) {
            putNumber(target, "s$index", iface["s$index"], "S$index", max = 65535)
        }
        for (index in 1..4) {
            putRange(target, "h$index", iface["h$index"], "H$index")
        }
        for (index in 1..5) {
            iface["i$index"]?.takeIf { it.isNotBlank() }?.let { target.put("i$index", it) }
        }

        val headerKey = iface["headerprotectionkey"]?.trim().orEmpty()
        if (headerKey.isNotEmpty()) {
            target.put("header_protection_key", normalizeHeaderKey(headerKey))
            for (index in 1..4) {
                val padding = iface["s$index"]?.trim()?.toIntOrNull() ?: 0
                if (padding < 12) {
                    throw IllegalArgumentException(
                        "HeaderProtectionKey requires S$index >= 12 (got $padding)",
                    )
                }
            }
        }
        val s1 = iface["s1"]?.trim()?.toIntOrNull() ?: 0
        val s2 = iface["s2"]?.trim()?.toIntOrNull() ?: 0
        if (iface["s1"] != null && iface["s2"] != null && s1 + 56 == s2) {
            throw IllegalArgumentException("Invalid S1/S2: S1 + 56 must not equal S2")
        }

        putRange(target, "content_padding_addition", iface["contentpaddingaddition"], "ContentPaddingAddition")
        putRange(target, "rekey_after_time", iface["rekeyaftertime"], "RekeyAfterTime", min = 1)
        putRange(target, "rekey_timeout", iface["rekeytimeout"], "RekeyTimeout", min = 1)
        putRange(target, "reject_after_time", iface["rejectaftertime"], "RejectAfterTime", min = 1)
        putRange(target, "keepalive_timeout", iface["keepalivetimeout"], "KeepaliveTimeout", min = 1)
        putRange(target, "max_handshake_attempts", iface["maxhandshakeattempts"], "MaxHandshakeAttempts", min = 1)
        putBoolean(target, "random_trailers", iface["randomtrailers"], "RandomTrailers")
        putBoolean(target, "disable_cookies", iface["disablecookies"], "DisableCookies")
    }

    private fun putNumber(
        target: JSONObject,
        key: String,
        raw: String?,
        field: String,
        max: Long = 0xFFFFFFFFL,
    ) {
        val value = raw?.trim().orEmpty()
        if (value.isEmpty()) return
        val parsed = value.toLongOrNull()
            ?: throw IllegalArgumentException("Invalid $field: $value")
        if (parsed < 0 || parsed > max) {
            throw IllegalArgumentException("Invalid $field: $value (must be 0..$max)")
        }
        target.put(key, parsed)
    }

    private fun putRange(
        target: JSONObject,
        key: String,
        raw: String?,
        field: String,
        min: Long = 0,
    ) {
        val value = raw?.trim().orEmpty()
        if (value.isEmpty()) return
        val parts = value.split('-')
        if (parts.size > 2) throw IllegalArgumentException("Invalid $field: $value")
        val low = parts[0].trim().toLongOrNull()
            ?: throw IllegalArgumentException("Invalid $field: $value")
        val high = if (parts.size == 2) {
            parts[1].trim().toLongOrNull()
                ?: throw IllegalArgumentException("Invalid $field: $value")
        } else {
            low
        }
        if (low < min || high > 0xFFFFFFFFL || low > high) {
            throw IllegalArgumentException("Invalid $field: $value")
        }
        target.put(key, if (low == high) low else "$low-$high")
    }

    private fun putBoolean(target: JSONObject, key: String, raw: String?, field: String) {
        val value = raw?.trim()?.lowercase().orEmpty()
        if (value.isEmpty()) return
        when (value) {
            "on", "true", "1", "yes" -> target.put(key, true)
            "off", "false", "0", "no" -> target.put(key, false)
            else -> throw IllegalArgumentException("Invalid $field: $raw")
        }
    }

    /**
     * x-ui (and some other generators) write WireGuard keys in URL-safe base64,
     * while sing-box's wireguard endpoint decodes them with the standard
     * alphabet. WireGuard keys are exactly 32 bytes, so decoding and
     * re-encoding here both validates the key and makes it acceptable to the
     * core — otherwise `_`/`-` in a private key fails at check time with
     * "illegal base64 data".
     */
    private fun normalizeWireGuardKey(raw: String, field: String): String {
        val bytes = decodeBase64Safe(raw)
            ?: throw IllegalArgumentException("$field is not valid base64")
        if (bytes.size != 32) {
            throw IllegalArgumentException("$field must be 32 bytes (got ${bytes.size})")
        }
        return Base64.encodeToString(bytes, Base64.NO_WRAP)
    }

    private fun normalizeHeaderKey(raw: String): String {
        val bytes = decodeBase64Safe(raw)
            ?: throw IllegalArgumentException("HeaderProtectionKey is not valid base64")
        if (bytes.size != 32) {
            throw IllegalArgumentException("HeaderProtectionKey must be 32 bytes (got ${bytes.size})")
        }
        if (bytes.all { it == 0.toByte() }) {
            throw IllegalArgumentException("HeaderProtectionKey must not be all zeroes")
        }
        return Base64.encodeToString(bytes, Base64.NO_WRAP)
    }

    private fun decodeBase64Safe(raw: String): ByteArray? {
        val cleaned = raw.trim().replace(Regex("\\s+"), "")
        if (cleaned.isEmpty()) return null
        // URL-safe and standard alphabets, padded and unpadded. Amnezia uses
        // URL-safe without padding; .conf keys use standard base64 with it.
        val flags = intArrayOf(
            Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING,
            Base64.URL_SAFE or Base64.NO_WRAP,
            Base64.DEFAULT or Base64.NO_WRAP or Base64.NO_PADDING,
            Base64.DEFAULT or Base64.NO_WRAP,
        )
        for (flag in flags) {
            runCatching { return Base64.decode(cleaned, flag) }
        }
        return null
    }
}
