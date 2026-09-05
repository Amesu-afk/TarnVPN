package io.nekohasekai.sfa.tarn.data

import io.nekohasekai.sfa.utils.ProxyUriParser
import org.json.JSONArray
import org.json.JSONObject

/**
 * Stable identity for a share link stored by Tarn subscriptions.
 *
 * Subscription providers regularly rewrite harmless URL details such as the display fragment,
 * query ordering, percent encoding, or an omitted default port. Comparing the raw URI made Tarn
 * delete and recreate the same server, losing its favourite state and measured history. A parsed
 * outbound contains the connection-relevant data only; its tag is deliberately removed before a
 * deterministic JSON form is built.
 */
internal object TarnLinkIdentity {

    fun connectionKey(sourceUri: String): String {
        val parsed = runCatching { ProxyUriParser.parse(sourceUri, fragmentEnabled = false).json }.getOrNull()
            ?: return sourceUri.substringBefore('#').trim()
        parsed.remove("tag")
        return canonicalJson(parsed)
    }

    private fun canonicalJson(value: Any?): String = when (value) {
        null, JSONObject.NULL -> "null"
        is JSONObject -> {
            val keys = mutableListOf<String>()
            val iterator = value.keys()
            while (iterator.hasNext()) keys += iterator.next()
            keys.sort()
            keys.joinToString(prefix = "{", postfix = "}") { key ->
                "${JSONObject.quote(key)}:${canonicalJson(value.opt(key))}"
            }
        }
        is JSONArray -> (0 until value.length()).joinToString(prefix = "[", postfix = "]") { index ->
            canonicalJson(value.opt(index))
        }
        is String -> JSONObject.quote(value)
        is Number, is Boolean -> value.toString()
        else -> JSONObject.quote(value.toString())
    }
}
