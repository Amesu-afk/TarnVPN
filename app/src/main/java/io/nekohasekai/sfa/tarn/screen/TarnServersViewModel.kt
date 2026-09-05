package io.nekohasekai.sfa.tarn.screen

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.nekohasekai.sfa.database.ProfileManager
import io.nekohasekai.sfa.database.Settings
import io.nekohasekai.sfa.tarn.data.Latency
import io.nekohasekai.sfa.tarn.data.ServerEntry
import io.nekohasekai.sfa.tarn.data.TarnFullTestResult
import io.nekohasekai.sfa.tarn.data.TarnFullTestSession
import io.nekohasekai.sfa.tarn.data.TarnServerRepository
import io.nekohasekai.sfa.tarn.data.regionalFlag
import io.nekohasekai.sfa.utils.HTTPClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** One country chip: its code, flag, and how many loaded servers carry it. */
data class TarnCountryOption(val code: String, val flag: String, val count: Int)

/** Successful-but-partial subscription imports stay visible instead of looking like a clean run. */
data class TarnImportNotice(val serverCount: Int, val rejectedCount: Int)

data class TarnServersUiState(
    val servers: List<ServerEntry> = emptyList(),
    val latencies: Map<Long, Latency> = emptyMap(),
    val favourites: Set<Long> = emptySet(),
    val query: String = "",
    val recommendedOnly: Boolean = true,
    /** ISO code the country chip row has narrowed to, or null for "all countries". */
    val countryFilter: String? = null,
    val loading: Boolean = false,
    val importing: Boolean = false,
    val importError: String? = null,
    val importNotice: TarnImportNotice? = null,
    val fullTests: Map<Long, TarnFullTestResult> = emptyMap(),
    val fullTestRunning: Boolean = false,
    val fullTestNotice: String? = null,
    val suggestedTcpFailoverProfileId: Long? = null,
) {
    /**
     * The countries present across all loaded servers, most-populated first, for the chip row.
     * Independent of the search box and the recommended toggle so the chips don't shuffle as the
     * user types or stars a server.
     */
    val countryOptions: List<TarnCountryOption>
        get() = servers
            .mapNotNull { it.countryCode }
            .groupingBy { it }
            .eachCount()
            .map { (code, count) -> TarnCountryOption(code, regionalFlag(code), count) }
            .sortedWith(compareByDescending<TarnCountryOption> { it.count }.thenBy { it.code })

    /** Rows after the tab, the country chip, the search box and the sort have all had their say. */
    val visibleServers: List<ServerEntry>
        get() {
            val trimmed = query.trim()
            val matched = servers.filter { server ->
                trimmed.isEmpty() ||
                    server.displayName.contains(trimmed, ignoreCase = true) ||
                    server.tag.contains(trimmed, ignoreCase = true)
            }
            val scoped = when {
                // A chosen country shows every server there, sorted by speed — the recommended
                // shortlist would otherwise hide the slower ones the user is trying to browse.
                countryFilter != null -> matched.filter { it.countryCode == countryFilter }
                // "Recommended" means starred or known-reachable. While probes are still in
                // flight that set can be empty, so fall back to everything rather than
                // flashing an empty screen at the user.
                recommendedOnly && trimmed.isEmpty() -> {
                    val shortlist = matched.filter {
                        favourites.contains(it.profileId) || latencyOf(it) != null
                    }
                    shortlist.ifEmpty { matched }
                }
                else -> matched
            }
            // Starred first, then quickest, then unmeasured, then alphabetical.
            return scoped.sortedWith(
                compareByDescending<ServerEntry> { favourites.contains(it.profileId) }
                    .thenBy { latencyOf(it) ?: Int.MAX_VALUE }
                    .thenBy { it.displayName },
            )
        }

    /** The reachable server with the lowest measured ping, or null while nothing is known yet. */
    val fastestReachable: ServerEntry?
        get() = servers.filter { latencyOf(it) != null }.minByOrNull { latencyOf(it)!! }

    val suggestedTcpFailover: ServerEntry?
        get() = suggestedTcpFailoverProfileId?.let { id -> servers.firstOrNull { it.profileId == id } }

    private fun latencyOf(server: ServerEntry): Int? = (latencies[server.profileId] as? Latency.Ok)?.millis
}

class TarnServersViewModel : ViewModel() {
    private val _uiState = MutableStateFlow(TarnServersUiState())
    val uiState: StateFlow<TarnServersUiState> = _uiState.asStateFlow()

    private val profilesChanged: () -> Unit = { refresh() }
    private var probeJob: Job? = null
    private var fullTestJob: Job? = null
    private var fullTestSession: TarnFullTestSession? = null
    private var fullTestGeneration = 0L

    init {
        _uiState.update { it.copy(favourites = readFavourites()) }
        refresh()
        ProfileManager.registerCallback(profilesChanged)
    }

    override fun onCleared() {
        probeJob?.cancel()
        fullTestGeneration++
        fullTestJob?.cancel()
        fullTestSession?.cancel()
        fullTestJob = null
        fullTestSession = null
        ProfileManager.unregisterCallback(profilesChanged)
        super.onCleared()
    }

    fun refresh(probe: Boolean = true) {
        viewModelScope.launch {
            _uiState.update { it.copy(loading = true) }
            val servers = runCatching { TarnServerRepository.load() }.getOrDefault(emptyList())
            _uiState.update { state ->
                state.copy(
                    servers = servers,
                    loading = false,
                    // Drop readings for profiles that no longer exist.
                    latencies = state.latencies.filterKeys { id -> servers.any { it.profileId == id } },
                    fullTests = state.fullTests.filterKeys { id -> servers.any { it.profileId == id } },
                    suggestedTcpFailoverProfileId = state.suggestedTcpFailoverProfileId
                        ?.takeIf { id -> servers.any { it.profileId == id } },
                    // Clear a country filter whose last server just went away, or it would show
                    // an empty list with no obvious way back.
                    countryFilter = state.countryFilter
                        ?.takeIf { code -> servers.any { it.countryCode == code } },
                )
            }
            if (probe) probeAll()
        }
    }

    /**
     * Probes every server concurrently, publishing each reading the moment it lands so a
     * slow endpoint never holds up the rest of the list.
     *
     * A new probe supersedes any still in flight: without that, a slow reading from an earlier
     * call could land after a fresh one and overwrite it with a stale value. Cancelling the
     * parent job cancels the per-server children, so a superseded probe never reaches its
     * `_uiState.update` — the blocking connect it left in the IO pool just has its result dropped
     * at the `withContext` boundary.
     */
    fun probeAll() {
        val servers = _uiState.value.servers
        probeJob?.cancel()
        if (servers.isEmpty()) {
            probeJob = null
            return
        }
        _uiState.update { state ->
            state.copy(latencies = servers.associate { it.profileId to Latency.Probing })
        }
        probeJob = viewModelScope.launch {
            servers.forEach { server ->
                launch {
                    val millis = TarnServerRepository.probe(server)
                    val latency = millis?.let { Latency.Ok(it) } ?: Latency.Unreachable
                    _uiState.update { it.copy(latencies = it.latencies + (server.profileId to latency)) }
                }
            }
        }
    }

    /**
     * Imports a pasted vless:// link or subscription URL. Each server in it becomes its
     * own profile (see [TarnServerRepository.import]), and [refresh] via the
     * [ProfileManager] callback picks the new rows up and probes them the moment they land.
     */
    fun importServers(input: String) {
        _uiState.update { it.copy(importing = true, importError = null, importNotice = null) }
        viewModelScope.launch {
            val result = runCatching {
                TarnServerRepository.import(input) { url ->
                    HTTPClient().use { it.getString(url) }
                }
            }
            val outcome = result.getOrNull()
            _uiState.update {
                it.copy(
                    importing = false,
                    importError = result.exceptionOrNull()?.message,
                    importNotice = outcome?.takeIf { it.rejectedCount > 0 }?.let {
                        TarnImportNotice(it.serverCount, it.rejectedCount)
                    },
                )
            }
        }
    }

    fun dismissImportError() = _uiState.update { it.copy(importError = null, importNotice = null) }

    /**
     * Deletes a server profile and the files behind it. [ProfileManager] fires its change
     * callback on delete, which [refresh] is subscribed to, so the row disappears on its own.
     */
    fun deleteServer(profileId: Long) {
        viewModelScope.launch {
            runCatching { TarnServerRepository.delete(profileId) }
        }
    }

    /**
     * Runs a real proxied HTTPS request for the selected profile that is already loaded by
     * the service. Other profile rows keep their independent TCP readings: the command RPC
     * cannot see their outbound tags until those profiles are selected and reloaded.
     */
    fun runFullTest(runningProfileId: Long, serviceStarted: Boolean) {
        if (fullTestJob?.isActive == true) return
        if (!serviceStarted) {
            _uiState.update {
                it.copy(
                    fullTestNotice = null,
                    suggestedTcpFailoverProfileId = null,
                )
            }
            return
        }

        val targets = TarnServerRepository.fullTestTargets(_uiState.value.servers, runningProfileId)
        if (targets.isEmpty()) {
            _uiState.update {
                it.copy(
                    fullTestNotice = null,
                    suggestedTcpFailoverProfileId = null,
                )
            }
            return
        }

        val session = TarnServerRepository.newFullTestSession()
        val generation = ++fullTestGeneration
        fullTestSession = session
        _uiState.update { state ->
            state.copy(
                fullTests = targets.associate { it.profileId to TarnFullTestResult.Testing },
                fullTestRunning = true,
                fullTestNotice = null,
                suggestedTcpFailoverProfileId = null,
            )
        }

        val testUrl = Settings.tarnTestUrl
        val timeoutMs = Settings.tarnTestTimeoutSeconds * 1_000
        val attempts = Settings.tarnTestRetries
        fullTestJob = viewModelScope.launch {
            try {
                val results = withContext(Dispatchers.IO) {
                    session.run(targets, testUrl, timeoutMs, attempts)
                }
                if (generation != fullTestGeneration) return@launch

                val currentPassed = results[runningProfileId] is TarnFullTestResult.Success
                val suggestion = if (!currentPassed && Settings.tarnAutoFailover) {
                    fastestTcpFallback(excludingProfileId = runningProfileId)
                } else {
                    null
                }
                _uiState.update {
                    it.copy(
                        fullTests = results,
                        fullTestRunning = false,
                        fullTestNotice = null,
                        suggestedTcpFailoverProfileId = suggestion?.profileId,
                    )
                }
            } catch (exception: CancellationException) {
                // cancelFullTest owns the visible terminal state and increments generation.
                throw exception
            } catch (exception: Exception) {
                if (generation != fullTestGeneration) return@launch
                val message = exception.message?.lineSequence()?.firstOrNull()?.take(240)
                    ?: exception.javaClass.simpleName
                _uiState.update {
                    it.copy(
                        fullTests = targets.associate { target ->
                            target.profileId to TarnFullTestResult.Failed(message, attempts)
                        },
                        fullTestRunning = false,
                        fullTestNotice = null,
                        suggestedTcpFailoverProfileId = if (Settings.tarnAutoFailover) {
                            fastestTcpFallback(excludingProfileId = runningProfileId)?.profileId
                        } else {
                            null
                        },
                    )
                }
            } finally {
                if (generation == fullTestGeneration) {
                    fullTestSession = null
                    fullTestJob = null
                    _uiState.update { it.copy(fullTestRunning = false) }
                }
            }
        }
    }

    fun cancelFullTest() {
        val job = fullTestJob
        if (job?.isActive != true) return
        val session = fullTestSession
        fullTestGeneration++
        fullTestJob = null
        fullTestSession = null
        job.cancel()
        _uiState.update { state ->
            state.copy(
                fullTests = state.fullTests.mapValues { (_, result) ->
                    if (result is TarnFullTestResult.Testing) TarnFullTestResult.Cancelled else result
                },
                fullTestRunning = false,
                fullTestNotice = null,
                suggestedTcpFailoverProfileId = null,
            )
        }
        // Coroutine cancellation alone cannot interrupt a synchronous gomobile call.
        viewModelScope.launch(Dispatchers.IO) { session?.cancel() }
    }

    fun clearFullTest() {
        if (fullTestJob?.isActive == true) {
            val session = fullTestSession
            fullTestGeneration++
            fullTestJob?.cancel()
            fullTestJob = null
            fullTestSession = null
            viewModelScope.launch(Dispatchers.IO) { session?.cancel() }
        }
        _uiState.update {
            it.copy(
                fullTests = emptyMap(),
                fullTestRunning = false,
                fullTestNotice = null,
                suggestedTcpFailoverProfileId = null,
            )
        }
    }

    fun setQuery(value: String) = _uiState.update { it.copy(query = value) }

    fun setRecommendedOnly(value: Boolean) = _uiState.update { it.copy(recommendedOnly = value) }

    /** Toggles the country chip: tapping the active one clears it back to "all countries". */
    fun setCountryFilter(code: String?) = _uiState.update {
        it.copy(countryFilter = if (it.countryFilter == code) null else code)
    }

    fun toggleFavourite(profileId: Long) {
        val current = _uiState.value.favourites
        val updated = if (current.contains(profileId)) current - profileId else current + profileId
        _uiState.update { it.copy(favourites = updated) }
        Settings.tarnFavouriteProfiles = updated.map(Long::toString).toSet()
    }

    private fun readFavourites(): Set<Long> = Settings.tarnFavouriteProfiles.mapNotNull(String::toLongOrNull).toSet()

    /** TCP-only suggestion; it is never applied automatically as a successful full test. */
    private fun fastestTcpFallback(excludingProfileId: Long): ServerEntry? = _uiState.value.servers
        .asSequence()
        .filter { it.profileId != excludingProfileId }
        .mapNotNull { server ->
            val millis = (_uiState.value.latencies[server.profileId] as? Latency.Ok)?.millis
            millis?.let { server to it }
        }
        .minByOrNull { (_, millis) -> millis }
        ?.first
}
