package io.nekohasekai.sfa.bg

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.Locale

/**
 * Crash and OOM reports keep a copy of the running config next to the logs — the most useful
 * artifact for a support thread, and the one that carries live credentials: VLESS UUIDs,
 * TLS/obfs passwords, WireGuard private keys. Those copies live on app-external storage and
 * are reachable through the Documents provider and USB tooling, so a report must not keep
 * anything that can still authenticate.
 *
 * Every structural field (tags, ports, hosts, transport shape — the parts actually needed to
 * reproduce a bug) stays; the values that log in are replaced with [REDACTED]. The marker
 * file makes the rewrite one-shot, so the files keep their original formatting when nothing
 * had to be masked.
 */
object ConfigRedaction {
    const val MARKER_FILE_NAME = ".config-redacted"
    private const val REDACTED = "redacted"

    /**
     * Field names whose string values authenticate a session or a device. Matched
     * case-insensitively at every nesting level, so `tls.headers.Authorization` is covered
     * as well as a top-level `password`.
     */
    private val SECRET_KEYS = setOf(
        "uuid",
        "password",
        "psk",
        "pre_shared_key",
        "private_key",
        "private_key_passphrase",
        "short_id",
        "obfs_password",
        "auth",
        "auth_str",
        "auth_key",
        "encryption",
        "token",
        "secret",
        "api_key",
        "x_api_key",
        "x_auth_token",
        "authorization",
        "proxy_authorization",
        "authentication",
        "cookie",
        "username",
    )

    fun redactConfig(json: String): String {
        val root = JSONObject(json)
        redactObject(root)
        return root.toString(2)
    }

    /** Writes only the redacted JSON; callers must never copy the raw config first. */
    fun writeRedactedConfig(reportDir: File, json: String) {
        val redacted = redactConfig(json)
        File(reportDir, "configuration.json").writeText(redacted)
        File(reportDir, MARKER_FILE_NAME).createNewFile()
    }

    fun redactReport(reportDir: File) {
        val config = File(reportDir, "configuration.json")
        if (!config.isFile) return
        val marker = File(reportDir, MARKER_FILE_NAME)
        if (marker.exists()) return
        runCatching {
            writeRedactedConfig(reportDir, config.readText())
        }.onFailure {
            // A malformed snapshot must not survive merely because it could not be parsed.
            config.delete()
        }
    }

    /** Covers reports written by the core itself, which never pass through the app. */
    fun redactReports(reportsDir: File) {
        val directories = reportsDir.listFiles { file -> file.isDirectory } ?: return
        directories.forEach(::redactReport)
    }

    private fun redactObject(obj: JSONObject, redactAllValues: Boolean = false) {
        val keys = obj.keys().asSequence().toList()
        for (key in keys) {
            val normalizedKey = normalizeKey(key)
            when (val value = obj.opt(key)) {
                is JSONObject -> redactObject(
                    value,
                    redactAllValues || normalizedKey == "header" || normalizedKey == "headers" || normalizedKey.endsWith("_headers"),
                )
                is JSONArray -> redactArray(value, redactAllValues)
                is String -> if (value.isNotEmpty() && (redactAllValues || normalizedKey in SECRET_KEYS)) {
                    obj.put(key, REDACTED)
                }
            }
        }
    }

    private fun redactArray(array: JSONArray, redactAllValues: Boolean = false) {
        for (index in 0 until array.length()) {
            when (val value = array.opt(index)) {
                is JSONObject -> redactObject(value, redactAllValues)
                is JSONArray -> redactArray(value, redactAllValues)
            }
        }
    }

    private fun normalizeKey(key: String): String = key.lowercase(Locale.US).replace('-', '_')
}
