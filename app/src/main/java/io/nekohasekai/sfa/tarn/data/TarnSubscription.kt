package io.nekohasekai.sfa.tarn.data

import android.util.AtomicFile
import io.nekohasekai.sfa.Application
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

/**
 * A tracked subscription: an HTTPS URL that expands to many servers, remembered so it can be
 * refreshed later. A standalone pasted link is NOT a subscription — it has no URL to poll and
 * gets no [Subscription] and no `.sub` sidecar.
 *
 * Stored by [TarnSubscriptionStore] as a small JSON file, deliberately outside the SFA Room
 * schema. The shell already keeps its own state that way — favourites in Settings, each
 * profile's source link in a `.vless` sidecar — so subscriptions follow suit: no database
 * migration to get wrong, and nothing upstream to conflict with on a merge.
 */
data class Subscription(
    /** Stable across refreshes; the `.sub` sidecar on each profile points back here. */
    val id: String,
    /** User-facing label; defaults to the URL host at import time. */
    val name: String,
    /** The https:// URL. Also the dedup key: importing the same URL refreshes in place. */
    val url: String,
    /** Epoch millis of the last successful refresh, or 0 when never fetched. */
    val lastUpdated: Long,
    val autoUpdate: Boolean,
) {
    fun toJson(): JSONObject = JSONObject()
        .put("id", id)
        .put("name", name)
        .put("url", url)
        .put("lastUpdated", lastUpdated)
        .put("autoUpdate", autoUpdate)

    companion object {
        fun fromJson(o: JSONObject): Subscription? {
            val id = o.optString("id").takeIf { it.isNotBlank() } ?: return null
            val url = o.optString("url").takeIf { it.isNotBlank() } ?: return null
            return Subscription(
                id = id,
                name = o.optString("name").ifBlank { url },
                url = url,
                lastUpdated = o.optLong("lastUpdated", 0L),
                autoUpdate = o.optBoolean("autoUpdate", false),
            )
        }

        fun newId(): String = UUID.randomUUID().toString()
    }
}

/**
 * Persists the subscription list to a single JSON file under filesDir. Every mutation reads,
 * edits, and writes the whole list — the list is a handful of entries, so this stays simple and
 * atomic rather than clever. Methods are synchronized against each other; the repository still
 * holds its own mutex around the wider profile+file mutation so a refresh and a save never
 * interleave.
 */
object TarnSubscriptionStore {
    private val file: File
        get() = File(Application.application.filesDir, "subscriptions.json")

    @Synchronized
    fun load(): List<Subscription> {
        val f = file
        if (!f.isFile && !File(f.path + ".bak").isFile) return emptyList()
        // Recover AtomicFile backups, and never overwrite unreadable/corrupt data as an empty list.
        val text = AtomicFile(f).openRead().bufferedReader().use { it.readText() }
        val array = JSONArray(text)
        return (0 until array.length()).mapNotNull { i ->
            array.optJSONObject(i)?.let(Subscription::fromJson)
        }
    }

    @Synchronized
    fun save(subscriptions: List<Subscription>) {
        val array = JSONArray()
        subscriptions.forEach { array.put(it.toJson()) }
        val atomic = AtomicFile(file)
        val output = atomic.startWrite()
        try {
            output.write(array.toString().toByteArray(Charsets.UTF_8))
            atomic.finishWrite(output)
        } catch (exception: Exception) {
            atomic.failWrite(output)
            throw exception
        }
    }

    @Synchronized
    fun get(id: String): Subscription? = load().firstOrNull { it.id == id }

    @Synchronized
    fun findByUrl(url: String): Subscription? = load().firstOrNull { it.url == url }

    @Synchronized
    fun upsert(subscription: Subscription) {
        val list = load().toMutableList()
        val index = list.indexOfFirst { it.id == subscription.id }
        if (index >= 0) list[index] = subscription else list.add(subscription)
        save(list)
    }

    @Synchronized
    fun remove(id: String) {
        save(load().filterNot { it.id == id })
    }
}

/** A subscription plus the live count of profiles that still carry its `.sub` sidecar. */
data class SubscriptionView(
    val subscription: Subscription,
    val serverCount: Int,
)
