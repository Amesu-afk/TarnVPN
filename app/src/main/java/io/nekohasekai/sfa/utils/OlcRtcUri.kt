package io.nekohasekai.sfa.utils

import org.json.JSONObject
import java.net.URI
import java.net.URLDecoder
import java.nio.charset.StandardCharsets

/** Parser for the olcRTC URI v1 client convention. */
object OlcRtcUri {
    const val SOCKS_PORT = 18_808

    private val providers = setOf("jitsi", "telemost", "wbstream")
    private val transports = setOf("datachannel", "vp8channel", "seichannel", "videochannel")
    private val mobileTransports = setOf("datachannel", "vp8channel")
    private val hexKey = Regex("[0-9a-fA-F]{64}")
    private val managerParameters = setOf(
        "key", "k",
        "transport", "t",
        "core",
        "vp8_fps", "f",
        "vp8_batch", "b",
        "client_id", "c",
        "dns", "d",
        "keepalive", "ka",
    )

    data class Profile(
        val provider: String,
        val transport: String,
        val room: String,
        val key: String,
        val name: String,
        val options: Map<String, String>,
        val sourceUri: String,
        val clientId: String? = null,
        val dnsServer: String? = null,
        val keepaliveSeconds: Int? = null,
    ) {
        fun outbound(): ProxyUriParser.ParsedOutbound {
            val tag = name.ifBlank { "olcRTC $provider" }.take(96)
            return ProxyUriParser.ParsedOutbound(
                tag = tag,
                json = JSONObject()
                    .put("type", "socks")
                    .put("tag", tag)
                    .put("server", "127.0.0.1")
                    .put("server_port", SOCKS_PORT)
                    .put("version", "5"),
                sourceUri = sourceUri,
            )
        }

        fun encode(): String {
            val payload = if (options.isEmpty()) {
                ""
            } else {
                options.entries.joinToString("&", "<", ">") {
                    "${it.key}=${it.value}"
                }
            }
            return "olcrtc://$provider?$transport$payload@$room#$key\$$name"
        }
    }

    fun isUri(input: String): Boolean = input.trim().startsWith("olcrtc://", ignoreCase = true)

    fun parse(input: String): Profile {
        val value = input.trim()
        require(isUri(value)) { "Not an olcRTC URI" }
        val body = value.substringAfter("://")
        if (body.substringBefore('?').contains('@')) return parseManagerUri(value)

        return parseV1(value, body)
    }

    private fun parseV1(value: String, body: String): Profile {
        val provider = body.substringBefore('?').lowercase()
        require(provider in providers) { "Unsupported olcRTC provider '$provider'" }

        val afterProvider = body.substringAfter('?', "")
        require(afterProvider.isNotEmpty()) { "Missing olcRTC transport" }
        val beforeRoom = afterProvider.substringBeforeLast('@', "")
        val afterRoomSeparator = afterProvider.substringAfterLast('@', "")
        require(beforeRoom.isNotEmpty() && afterRoomSeparator.isNotEmpty()) { "Missing olcRTC room" }

        val transport = beforeRoom.substringBefore('<').lowercase()
        require(transport in transports) { "Unsupported olcRTC transport '$transport'" }
        validateMobileSupport(provider, transport)
        val options = parseOptions(beforeRoom, transport)

        val room = afterRoomSeparator.substringBefore('#').trim()
        require(room.isNotEmpty()) { "Missing olcRTC room" }
        val keyAndName = afterRoomSeparator.substringAfter('#', "")
        val key = keyAndName.substringBefore('$').trim()
        require(hexKey.matches(key)) { "olcRTC key must be 64 hexadecimal characters" }
        val name = keyAndName.substringAfter('$', "").trim().ifEmpty { "olcRTC $provider" }
        return Profile(provider, transport, room, key.lowercase(), name, options, value)
    }

    /** Parses the URI emitted by current olcRTC manager/client QR generators. */
    private fun parseManagerUri(value: String): Profile {
        val uri = runCatching { URI(value) }
            .getOrElse { throw IllegalArgumentException("Malformed olcRTC URI", it) }
        val authority = uri.rawAuthority?.split('@')?.takeIf { it.size == 2 }
        val provider = (uri.userInfo ?: authority?.firstOrNull()?.let(::decode))
            ?.lowercase()
            ?: throw IllegalArgumentException("Missing olcRTC provider")
        require(provider in providers) { "Unsupported olcRTC provider '$provider'" }

        val roomHost = uri.host ?: authority?.lastOrNull()?.substringBefore(':')
        require(!roomHost.isNullOrBlank()) { "Missing olcRTC room" }
        val room = uri.rawPath
            ?.takeIf { it.isNotBlank() && it != "/" }
            ?.removePrefix("/")
            ?.let(::decode)
            ?: decode(roomHost)
        require(room.isNotBlank()) { "Missing olcRTC room" }

        val parameters = parseQuery(uri.rawQuery)
        val unknown = parameters.keys - managerParameters
        require(unknown.isEmpty()) {
            "Unsupported olcRTC parameters: ${unknown.sorted().joinToString()}"
        }
        parameter(parameters, "core")?.let {
            require(it.equals("current", ignoreCase = true)) {
                "Unsupported olcRTC core '$it'"
            }
        }

        val transport = parameter(parameters, "transport", "t")
            ?.lowercase()
            ?: "datachannel"
        require(transport in transports) { "Unsupported olcRTC transport '$transport'" }
        validateMobileSupport(provider, transport)
        require(
            (provider == "jitsi" && transport == "datachannel") ||
                (provider != "jitsi" && transport == "vp8channel"),
        ) { "Unsupported olcRTC manager combination '$provider/$transport'" }
        val key = requiredParameter(parameters, "key", "k")
        require(hexKey.matches(key)) { "olcRTC key must be 64 hexadecimal characters" }
        val clientId = requiredParameter(parameters, "client_id", "c")
        val dnsServer = parameter(parameters, "dns", "d")?.also(::validateDnsServer)
        val keepaliveSeconds = integerParameter(parameters, 15, "keepalive", "ka")
        require(keepaliveSeconds in 1..3_600) { "Invalid olcRTC keepalive" }

        val options = linkedMapOf<String, String>()
        parameter(parameters, "vp8_fps", "f")?.let { options["vp8-fps"] = it }
        parameter(parameters, "vp8_batch", "b")?.let { options["vp8-batch"] = it }
        validateOptions(transport, options)

        val name = uri.rawFragment?.let(::decode)?.trim().orEmpty()
            .ifEmpty { "olcRTC $provider" }
        return Profile(
            provider = provider,
            transport = transport,
            room = room,
            key = key.lowercase(),
            name = name,
            options = options,
            sourceUri = value,
            clientId = clientId,
            dnsServer = dnsServer,
            keepaliveSeconds = keepaliveSeconds,
        )
    }

    private fun parseQuery(rawQuery: String?): Map<String, String> {
        if (rawQuery.isNullOrEmpty()) return emptyMap()
        val result = linkedMapOf<String, String>()
        rawQuery.split('&').forEach { item ->
            val pair = item.split('=', limit = 2)
            val key = decode(pair[0])
            require(key.isNotEmpty()) { "Empty olcRTC parameter" }
            require(pair.size == 2) { "Missing value for olcRTC parameter '$key'" }
            require(result.put(key, decode(pair[1])) == null) {
                "Duplicate olcRTC parameter '$key'"
            }
        }
        return result
    }

    private fun validateMobileSupport(provider: String, transport: String) {
        require(provider != "wbstream") { "olcRTC provider 'wbstream' is not available in this Android build" }
        require(transport in mobileTransports) {
            "olcRTC transport '$transport' is not available in this Android build"
        }
    }

    private fun parameter(parameters: Map<String, String>, vararg names: String): String? {
        val values = names.mapNotNull(parameters::get)
        require(values.size <= 1) { "Duplicate aliases for olcRTC parameter '${names.first()}'" }
        return values.singleOrNull()
    }

    private fun requiredParameter(parameters: Map<String, String>, vararg names: String): String = parameter(parameters, *names)?.takeIf(String::isNotBlank)
        ?: throw IllegalArgumentException("Missing olcRTC parameter '${names.first()}'")

    private fun integerParameter(
        parameters: Map<String, String>,
        default: Int,
        vararg names: String,
    ): Int {
        val value = parameter(parameters, *names) ?: return default
        return value.toIntOrNull()
            ?: throw IllegalArgumentException("olcRTC parameter '${names.first()}' must be an integer")
    }

    private fun validateDnsServer(value: String) {
        val endpoint = runCatching { URI("dns://$value") }.getOrNull()
        require(endpoint?.host?.isNotBlank() == true && endpoint.port in 1..65_535) {
            "Invalid olcRTC DNS server"
        }
    }

    private fun decode(value: String): String = URLDecoder.decode(value, StandardCharsets.UTF_8.name())

    private fun parseOptions(value: String, transport: String): Map<String, String> {
        if (!value.contains('<')) return emptyMap()
        require(value.endsWith('>')) { "Malformed olcRTC transport parameters" }
        val payload = value.substringAfter('<').dropLast(1)
        if (payload.isBlank()) return emptyMap()
        val allowed = when (transport) {
            "vp8channel" -> setOf("vp8-fps", "vp8-batch")
            "seichannel" -> setOf("fps", "batch", "frag", "ack-ms")
            "videochannel" -> setOf(
                "video-w",
                "video-h",
                "video-fps",
                "video-codec",
                "video-qr-size",
                "video-qr-recovery",
                "video-tile-module",
                "video-tile-rs",
            )
            else -> emptySet()
        }
        val result = linkedMapOf<String, String>()
        payload.split('&').forEach { pair ->
            val key = pair.substringBefore('=').trim()
            val option = pair.substringAfter('=', "").trim()
            require(key in allowed && option.isNotEmpty()) { "Unsupported olcRTC parameter '$key'" }
            require(result.put(key, option) == null) { "Duplicate olcRTC parameter '$key'" }
        }
        validateOptions(transport, result)
        return result
    }

    private fun validateOptions(transport: String, options: Map<String, String>) {
        fun positive(key: String, max: Int = Int.MAX_VALUE) {
            options[key]?.let {
                val value = it.toIntOrNull()
                require(value != null && value in 1..max) { "Invalid olcRTC parameter '$key'" }
            }
        }
        when (transport) {
            "vp8channel" -> {
                positive("vp8-fps", 120)
                positive("vp8-batch")
            }
            "seichannel" -> {
                positive("fps", 120)
                positive("batch")
                positive("frag")
                positive("ack-ms")
            }
            "videochannel" -> {
                positive("video-w")
                positive("video-h")
                positive("video-fps", 120)
                positive("video-qr-size")
                positive("video-tile-module", 270)
                options["video-tile-rs"]?.let {
                    val value = it.toIntOrNull()
                    require(value != null && value in 0..200) { "Invalid olcRTC parameter 'video-tile-rs'" }
                }
                options["video-codec"]?.let { require(it == "qrcode" || it == "tile") { "Invalid olcRTC video codec" } }
                options["video-qr-recovery"]?.let {
                    require(it in setOf("low", "medium", "high", "highest")) { "Invalid olcRTC QR recovery" }
                }
                if (options["video-codec"] == "tile") {
                    require(options["video-w"]?.toIntOrNull() == 1080 && options["video-h"]?.toIntOrNull() == 1080) {
                        "olcRTC tile codec requires 1080x1080"
                    }
                }
            }
        }
    }
}
