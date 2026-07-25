package io.nekohasekai.sfa.tarn.data

import android.util.AtomicFile
import io.nekohasekai.libbox.Libbox
import io.nekohasekai.sfa.Application
import io.nekohasekai.sfa.database.Profile
import io.nekohasekai.sfa.database.ProfileManager
import io.nekohasekai.sfa.database.Settings
import io.nekohasekai.sfa.database.TypedProfile
import io.nekohasekai.sfa.utils.VlessImporter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import org.json.JSONObject
import java.io.File

/**
 * Turns stored profiles into server rows and measures how far away they are.
 *
 * Latency here is a plain TCP handshake to the outbound endpoint, measured from the
 * device without going through the tunnel. It is not the same number sing-box's own
 * urltest reports (that one is a full proxied request), but it needs no running service
 * and is what the servers screen shows before you connect.
 */
object TarnServerRepository {

    /** Outbound types that are plumbing rather than an actual remote server. */
    private val NON_SERVER_TYPES = setOf("selector", "urltest", "direct", "block", "dns")

    /**
     * How stale an auto-update subscription may get before [refreshDueSubscriptions] re-fetches
     * it. A server list is not fast-moving, so 12h keeps it current without turning every app
     * open into a network round for each subscription.
     */
    private const val AUTO_UPDATE_INTERVAL_MS = 12L * 60 * 60 * 1000

    // One lock for every operation that mutates profile files or rows — repatch, standalone
    // import, subscription refresh, delete. It serialises writers so two of them never race on
    // the same config file or on the predicted-next file id. Network fetches happen *before*
    // the lock is taken so a slow subscription server never blocks a settings toggle.
    private val mutex = Mutex()

    suspend fun load(): List<ServerEntry> = withContext(Dispatchers.IO) {
        ProfileManager.list().map { profile -> toEntry(profile) }
    }

    /**
     * Imports a share link or a subscription URL as one profile per server, so each one shows up
     * as its own row — a subscription bundled into a single urltest-group profile (what the
     * advanced "new profile" screen does) would instead collapse it into one row.
     *
     * A subscription URL is *tracked*: it becomes (or reuses) a [Subscription], and each server
     * it produced is tagged with a `.sub` sidecar so the set can later be refreshed and diffed.
     * Re-importing the same URL therefore refreshes in place instead of duplicating everything.
     * A bare pasted link has nothing to poll, so it stays a standalone, untracked server.
     *
     * @return how many server profiles the subscription/link now accounts for.
     */
    suspend fun import(input: String, fetch: (String) -> String): Int = withContext(Dispatchers.IO) {
        val trimmed = input.trim()
        if (VlessImporter.isHttpsUrl(trimmed)) {
            importSubscription(trimmed, fetch)
        } else {
            val configs = VlessImporter.toSingBoxConfigs(trimmed, fetch)
            mutex.withLock { writeServerProfiles(configs, subscriptionId = null) }
            configs.size
        }
    }

    /**
     * Finds or creates the [Subscription] for [url] (dedup is by URL), then refreshes it. The
     * initial import is just a refresh against an empty existing set — every server is an add.
     */
    private suspend fun importSubscription(url: String, fetch: (String) -> String): Int {
        val subscription = TarnSubscriptionStore.findByUrl(url)
            ?: Subscription(
                id = Subscription.newId(),
                name = subscriptionName(url),
                url = url,
                lastUpdated = 0L,
                autoUpdate = false,
            ).also { TarnSubscriptionStore.upsert(it) }
        return refreshSubscription(subscription.id, fetch)
    }

    /** Every stored subscription, each with a live count of the profiles still tagged to it. */
    suspend fun loadSubscriptions(): List<SubscriptionView> = withContext(Dispatchers.IO) {
        val subscriptions = TarnSubscriptionStore.load()
        if (subscriptions.isEmpty()) return@withContext emptyList()
        val counts = HashMap<String, Int>()
        ProfileManager.list().forEach { profile ->
            subscriptionIdOf(File(profile.typed.path))?.let { id ->
                counts[id] = (counts[id] ?: 0) + 1
            }
        }
        subscriptions.map { SubscriptionView(it, counts[it.id] ?: 0) }
    }

    /**
     * Re-fetches a subscription and reconciles it against the profiles already tagged to it,
     * keyed by [connectionKey] so a server that only got renamed keeps its identity — and with
     * it its favourite star, its order, and its measured latency. New keys are created, missing
     * keys are deleted, and a key whose config actually changed is rewritten in place.
     *
     * @return the number of servers the subscription now contains, or 0 if it no longer exists.
     */
    suspend fun refreshSubscription(subscriptionId: String, fetch: (String) -> String): Int =
        withContext(Dispatchers.IO) {
            val subscription = TarnSubscriptionStore.get(subscriptionId) ?: return@withContext 0
            // Network first, outside the lock.
            val fetched = VlessImporter.toSingBoxConfigs(subscription.url, fetch)
            mutex.withLock { applyRefresh(subscription, fetched) }
        }

    private suspend fun applyRefresh(
        subscription: Subscription,
        fetched: List<VlessImporter.ImportedServer>,
    ): Int {
        // Dedup within the fetched batch; a subscription that lists the same endpoint twice
        // must not create two rows. Last occurrence wins, matching a plain overwrite.
        val newByKey = LinkedHashMap<String, VlessImporter.ImportedServer>()
        fetched.forEach { newByKey[connectionKey(it.sourceUri)] = it }

        val existingByKey = LinkedHashMap<String, Profile>()
        ProfileManager.list().forEach { profile ->
            val file = File(profile.typed.path)
            if (subscriptionIdOf(file) != subscription.id) return@forEach
            val source = runCatching { sidecarFile(file).takeIf { it.isFile }?.readText()?.trim() }
                .getOrNull() ?: return@forEach
            existingByKey[connectionKey(source)] = profile
        }

        // Additions first, so the predicted-next file id still sits above the rows about to be
        // removed (ids only ever climb, but this keeps the prediction honest either way).
        val toAdd = newByKey.filterKeys { it !in existingByKey }.values.toList()
        if (toAdd.isNotEmpty()) writeServerProfiles(toAdd, subscription.id)

        val toRemove = existingByKey.filterKeys { it !in newByKey }.values.toList()
        if (toRemove.isNotEmpty()) deleteProfilesLocked(toRemove)

        // Kept servers: rewrite the config only when it actually changed, and update the row
        // name only when the subscription renamed it. Both keep the profile id, so nothing the
        // user attached to this row is lost.
        val renamed = mutableListOf<Profile>()
        newByKey.forEach { (key, server) ->
            val profile = existingByKey[key] ?: return@forEach
            val file = File(profile.typed.path)
            val currentConfig = runCatching { file.readText() }.getOrNull()
            if (currentConfig != server.config) {
                Libbox.checkConfig(server.config)
                writeAtomically(file, server.config)
                runCatching { sidecarFile(file).writeText(server.sourceUri) }
            } else if (profile.name != server.name) {
                runCatching { sidecarFile(file).writeText(server.sourceUri) }
            }
            if (profile.name != server.name) {
                profile.name = server.name
                renamed += profile
            }
        }
        if (renamed.isNotEmpty()) ProfileManager.update(renamed)

        TarnSubscriptionStore.upsert(subscription.copy(lastUpdated = System.currentTimeMillis()))
        return newByKey.size
    }

    /** Flips a subscription's auto-update flag. Actual refreshing is driven by the caller. */
    suspend fun setSubscriptionAutoUpdate(id: String, enabled: Boolean): Unit =
        withContext(Dispatchers.IO) {
            TarnSubscriptionStore.get(id)?.let {
                TarnSubscriptionStore.upsert(it.copy(autoUpdate = enabled))
            }
        }

    /**
     * Refreshes every auto-update subscription that has gone stale past [AUTO_UPDATE_INTERVAL_MS].
     * Meant to be called when the app comes to the foreground: a server list only needs to be
     * fresh when the user is about to pick from it, so there is no background worker draining the
     * battery for it. Each subscription is refreshed independently — one that is offline or gone
     * simply keeps its old `lastUpdated` and stays due for the next foreground.
     *
     * @return how many subscriptions were successfully refreshed.
     */
    suspend fun refreshDueSubscriptions(fetch: (String) -> String): Int = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        val due = TarnSubscriptionStore.load().filter {
            it.autoUpdate && now - it.lastUpdated >= AUTO_UPDATE_INTERVAL_MS
        }
        var refreshed = 0
        due.forEach { subscription ->
            if (runCatching { refreshSubscription(subscription.id, fetch) }.isSuccess) refreshed++
        }
        refreshed
    }

    /** Removes a subscription together with every server it brought in. */
    suspend fun deleteSubscription(id: String): Unit = withContext(Dispatchers.IO) {
        mutex.withLock {
            val owned = ProfileManager.list().filter { subscriptionIdOf(File(it.typed.path)) == id }
            if (owned.isNotEmpty()) deleteProfilesLocked(owned)
            TarnSubscriptionStore.remove(id)
        }
    }

    /**
     * Materialises a batch of parsed servers as profiles, writing the config, the `.vless`
     * source sidecar, and — for subscription members — the `.sub` sidecar that ties the profile
     * back to its subscription. Callers must hold [mutex]; see [import] for the batching rationale.
     */
    private suspend fun writeServerProfiles(
        configs: List<VlessImporter.ImportedServer>,
        subscriptionId: String?,
    ): List<Profile> {
        if (configs.isEmpty()) return emptyList()
        val configDirectory = configDirectory()
        // nextFileID()/nextOrder() predict the row a create() is about to insert by reading
        // MAX(...)+1 — correct for one profile, but not re-queryable mid-batch since none of
        // these rows exist yet. Read the starting point once and count up locally instead,
        // then insert the whole batch through createAll() so listeners fire once, not once
        // per server (see its doc comment for why that matters at subscription size).
        var nextFileId = ProfileManager.nextFileID()
        var nextOrder = ProfileManager.nextOrder()
        val profiles = configs.map { server ->
            Libbox.checkConfig(server.config)
            val configFile = File(configDirectory, "$nextFileId.json")
            configFile.writeText(server.config)
            sidecarFile(configFile).writeText(server.sourceUri)
            subscriptionId?.let { subscriptionSidecarFile(configFile).writeText(it) }
            val profile = Profile(
                name = server.name,
                typed = TypedProfile().apply {
                    type = TypedProfile.Type.Local
                    path = configFile.path
                },
            ).apply { userOrder = nextOrder }
            nextFileId++
            nextOrder++
            profile
        }
        return ProfileManager.createAll(profiles)
    }

    /**
     * Deletes a server profile together with the files behind it. [ProfileManager.delete] only
     * drops the database row, which would leave the config JSON and its sidecars orphaned on disk.
     */
    suspend fun delete(profileId: Long): Unit = withContext(Dispatchers.IO) {
        mutex.withLock {
            val profile = ProfileManager.get(profileId) ?: return@withLock
            deleteProfilesLocked(listOf(profile))
        }
    }

    /** Bulk delete of profiles, their files, and their favourite stars. Caller holds [mutex]. */
    private suspend fun deleteProfilesLocked(profiles: List<Profile>) {
        if (profiles.isEmpty()) return
        profiles.forEach { profile ->
            val configFile = File(profile.typed.path)
            runCatching { sidecarFile(configFile).delete() }
            runCatching { subscriptionSidecarFile(configFile).delete() }
            runCatching { configFile.delete() }
        }
        pruneFavourites(profiles.map { it.id })
        ProfileManager.delete(profiles)
    }

    /** Drops favourite stars for profiles that no longer exist, so the set can't leak forever. */
    private fun pruneFavourites(removedIds: List<Long>) {
        if (removedIds.isEmpty()) return
        val removed = removedIds.map(Long::toString).toSet()
        val current = Settings.tarnFavouriteProfiles
        val pruned = current - removed
        if (pruned.size != current.size) Settings.tarnFavouriteProfiles = pruned
    }

    private fun configDirectory(): File =
        File(Application.application.filesDir, "configs").also { it.mkdirs() }

    /** Fragment-stripped share link: the identity of a server independent of its display name. */
    private fun connectionKey(sourceUri: String): String = sourceUri.substringBefore('#').trim()

    private fun subscriptionName(url: String): String =
        runCatching { android.net.Uri.parse(url).host }.getOrNull()?.takeIf { it.isNotBlank() } ?: url

    /** The subscription id a profile is tagged with, or null for a standalone/imported profile. */
    private fun subscriptionIdOf(configFile: File): String? =
        runCatching { subscriptionSidecarFile(configFile).takeIf { it.isFile }?.readText()?.trim() }
            .getOrNull()
            ?.takeIf { it.isNotBlank() }

    /**
     * The `<id>.vless` file sitting next to a `<id>.json` config, holding the original
     * share link the profile was imported from (see [VlessImporter.rebuildConfig]).
     */
    private fun sidecarFile(configFile: File): File =
        File(configFile.parentFile, configFile.nameWithoutExtension + ".vless")

    /** The `<id>.sub` file naming the subscription a profile belongs to; absent when standalone. */
    private fun subscriptionSidecarFile(configFile: File): File =
        File(configFile.parentFile, configFile.nameWithoutExtension + ".sub")

    /**
     * Rewrites DNS, the tun's IPv6 address, and per-outbound TLS fragmentation of every
     * stored profile to the current settings. All are read once at import time and baked
     * into the file, so a later settings change has to be pushed out explicitly or it would
     * only affect servers added after the change — see [VlessImporter.applySettings] for
     * what does and doesn't get touched.
     */
    suspend fun repatchSettings(): Unit = withContext(Dispatchers.IO) {
        mutex.withLock {
            val option = TarnDns.byId(Settings.tarnDnsProvider)
            val protection = Settings.tarnDnsProtection
            val ipv6 = Settings.tarnIpv6Enabled
            val fragment = Settings.tarnFragmentEnabled
            val quicPolicy = Settings.tarnQuicPolicy
            val tunMtu = Settings.tarnTunMtu
            val ipStrategy = Settings.tarnIpStrategy
            val dnsRoute = Settings.tarnDnsRoute
            val logLevel = Settings.tarnLogLevel
            val testUrl = Settings.tarnTestUrl
            val sendHostname = Settings.tarnSendHostname
            val validationSlots = Semaphore(4)
            // One profile's validation doesn't depend on another's. The outer mutex keeps
            // rapid setting changes from writing two generations to the same files at once.
            coroutineScope {
                ProfileManager.list().forEach { profile ->
                    launch {
                        validationSlots.withPermit {
                            val file = File(profile.typed.path)
                            if (!file.isFile) return@withPermit
                            val current = runCatching { file.readText() }.getOrNull() ?: return@withPermit
                            // Regenerate from the stored source link when we have one: it always
                            // yields today's config shape, so a settings toggle applies even to a
                            // profile imported by an older build. Fall back to in-place patching
                            // for profiles that predate the sidecar and for raw/hand-written
                            // configs (which have none and must not be rewritten).
                            val fromSource = sidecarFile(file).takeIf { it.isFile }
                                ?.let { runCatching { it.readText() }.getOrNull() }
                                ?.let(VlessImporter::rebuildConfig)
                            val patched = fromSource ?: VlessImporter.applySettings(
                                configJson = current,
                                option = option,
                                dnsProtection = protection,
                                ipv6Enabled = ipv6,
                                fragmentEnabled = fragment,
                                quicPolicy = quicPolicy,
                                tunMtu = tunMtu,
                                ipStrategy = ipStrategy,
                                dnsRoute = dnsRoute,
                                logLevel = logLevel,
                                testUrl = testUrl,
                                sendHostname = sendHostname,
                            ) ?: return@withPermit
                            // Never replace a working phone profile with JSON rejected by the
                            // bundled core. AtomicFile also restores the old bytes after a crash.
                            Libbox.checkConfig(patched)
                            writeAtomically(file, patched)
                        }
                    }
                }
            }
        }
    }

    private fun writeAtomically(file: File, content: String) {
        val atomicFile = AtomicFile(file)
        val output = atomicFile.startWrite()
        try {
            output.write(content.toByteArray(Charsets.UTF_8))
            atomicFile.finishWrite(output)
        } catch (exception: Exception) {
            atomicFile.failWrite(output)
            throw exception
        }
    }

    private fun toEntry(profile: Profile): ServerEntry {
        val endpoint = readEndpoint(profile)
        val tag = endpoint?.tag?.takeIf { it.isNotBlank() }
            ?: endpoint?.host
            ?: profile.name.lowercase().replace(' ', '-')
        return ServerEntry(
            profileId = profile.id,
            displayName = profile.name,
            tag = tag,
            countryCode = CountryDetector.detect(profile.name, endpoint?.tag),
            host = endpoint?.host,
            port = endpoint?.port ?: 0,
            protocol = endpoint?.type,
            managed = isManaged(profile),
            insecureTls = hasInsecureTls(profile),
        )
    }

    /** Any outbound whose TLS skips certificate validation — see [ServerEntry.insecureTls]. */
    private fun hasInsecureTls(profile: Profile): Boolean = runCatching {
        val file = File(profile.typed.path)
        if (!file.isFile) return false
        val outbounds = JSONObject(file.readText()).optJSONArray("outbounds") ?: return false
        (0 until outbounds.length()).any { index ->
            outbounds.optJSONObject(index)?.optJSONObject("tls")?.optBoolean("insecure") == true
        }
    }.getOrDefault(false)

    /** Mirrors what [repatchSettings] can actually do with this profile — see [ServerEntry.managed]. */
    private fun isManaged(profile: Profile): Boolean {
        val file = File(profile.typed.path)
        if (!file.isFile) return true
        if (sidecarFile(file).isFile) return true
        val current = runCatching { file.readText() }.getOrNull() ?: return true
        return VlessImporter.isManagedConfig(current)
    }

    private data class Endpoint(val tag: String?, val host: String?, val port: Int, val type: String?)

    /**
     * Pulls the first real outbound out of the profile's sing-box config. Profiles that
     * have never been materialised on disk yet simply yield null.
     */
    private fun readEndpoint(profile: Profile): Endpoint? = runCatching {
        val file = File(profile.typed.path)
        if (!file.isFile) return null
        val outbounds = JSONObject(file.readText()).optJSONArray("outbounds") ?: return null
        for (i in 0 until outbounds.length()) {
            val outbound = outbounds.optJSONObject(i) ?: continue
            val type = outbound.optString("type")
            if (type.isBlank() || type in NON_SERVER_TYPES) continue
            val host = outbound.optString("server").takeIf { it.isNotBlank() } ?: continue
            return Endpoint(
                tag = outbound.optString("tag").takeIf { it.isNotBlank() },
                host = host,
                port = outbound.optInt("server_port"),
                type = type,
            )
        }
        null
    }.getOrNull()

    /** TCP connect time in milliseconds, or null when the endpoint refuses/times out. */
    suspend fun probe(entry: ServerEntry, timeoutMs: Int = 3000): Int? {
        val host = entry.host ?: return null
        if (entry.port <= 0) return null
        return tcpConnectLatencyMs(host, entry.port, timeoutMs)
    }

    /**
     * Returns only tags that can honestly be tested by the current command RPC.
     *
     * URLTestOutbound resolves tags inside the already running sing-box instance. Tarn's
     * server rows are separate profile files, so tags belonging to inactive profiles do
     * not exist in that instance and must not be presented as full-test failures. Switching
     * and reloading every profile here would race DashboardViewModel service ownership.
     */
    fun fullTestTargets(
        entries: List<ServerEntry>,
        runningProfileId: Long,
    ): List<TarnFullTestTarget> =
        entries
            .asSequence()
            .filter { it.profileId == runningProfileId && it.tag.isNotBlank() }
            .map { TarnFullTestTarget(it.profileId, it.tag) }
            .toList()

    fun newFullTestSession(): TarnFullTestSession = TarnFullTestSession()
}
