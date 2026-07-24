package io.nekohasekai.sfa.tarn.screen

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.nekohasekai.sfa.database.ProfileManager
import io.nekohasekai.sfa.tarn.data.SubscriptionView
import io.nekohasekai.sfa.tarn.data.TarnServerRepository
import io.nekohasekai.sfa.utils.AppLifecycleObserver
import io.nekohasekai.sfa.utils.HTTPClient
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class TarnSubscriptionsUiState(
    val subscriptions: List<SubscriptionView> = emptyList(),
    val loading: Boolean = false,
    /** Ids currently being re-fetched, so each row can show its own spinner. */
    val refreshing: Set<String> = emptySet(),
    val error: String? = null,
)

class TarnSubscriptionsViewModel : ViewModel() {
    private val _uiState = MutableStateFlow(TarnSubscriptionsUiState())
    val uiState: StateFlow<TarnSubscriptionsUiState> = _uiState.asStateFlow()

    // Server counts come from the profiles table, so a delete/import elsewhere must re-read.
    private val profilesChanged: () -> Unit = { reload() }

    init {
        reload()
        ProfileManager.registerCallback(profilesChanged)
        observeForegroundAutoUpdate()
    }

    override fun onCleared() {
        ProfileManager.unregisterCallback(profilesChanged)
        super.onCleared()
    }

    /**
     * Drives auto-update off the app coming to the foreground rather than a background worker,
     * so nothing polls the network while the app is closed. The initial `true` the flow replays
     * on subscribe covers the app-open case; [TarnServerRepository.refreshDueSubscriptions] gates
     * each subscription on its own staleness, so a foreground return with nothing due is a no-op.
     * `isForeground` is a StateFlow, so it only re-emits on an actual background→foreground change.
     */
    private fun observeForegroundAutoUpdate() {
        viewModelScope.launch {
            AppLifecycleObserver.isForeground
                .filter { it }
                .collect { refreshDue() }
        }
    }

    private fun refreshDue() {
        viewModelScope.launch {
            val refreshed = runCatching {
                TarnServerRepository.refreshDueSubscriptions { url ->
                    HTTPClient().use { it.getString(url) }
                }
            }.getOrDefault(0)
            if (refreshed > 0) reload()
        }
    }

    fun reload() {
        viewModelScope.launch {
            _uiState.update { it.copy(loading = true) }
            val subscriptions = runCatching { TarnServerRepository.loadSubscriptions() }
                .getOrDefault(emptyList())
            _uiState.update { it.copy(subscriptions = subscriptions, loading = false) }
        }
    }

    fun refresh(id: String) {
        if (_uiState.value.refreshing.contains(id)) return
        _uiState.update { it.copy(refreshing = it.refreshing + id, error = null) }
        viewModelScope.launch {
            val result = runCatching {
                TarnServerRepository.refreshSubscription(id) { url ->
                    HTTPClient().use { it.getString(url) }
                }
            }
            _uiState.update {
                it.copy(
                    refreshing = it.refreshing - id,
                    error = result.exceptionOrNull()?.message,
                )
            }
            // The profiles callback reloads counts on add/remove, but a no-change refresh still
            // bumps lastUpdated with no profile mutation, so pull the new timestamp in directly.
            reload()
        }
    }

    fun delete(id: String) {
        viewModelScope.launch {
            runCatching { TarnServerRepository.deleteSubscription(id) }
            reload()
        }
    }

    fun setAutoUpdate(id: String, enabled: Boolean) {
        // Optimistic: flip the row now, persist underneath.
        _uiState.update { state ->
            state.copy(
                subscriptions = state.subscriptions.map { view ->
                    if (view.subscription.id == id) {
                        view.copy(subscription = view.subscription.copy(autoUpdate = enabled))
                    } else {
                        view
                    }
                },
            )
        }
        viewModelScope.launch {
            runCatching { TarnServerRepository.setSubscriptionAutoUpdate(id, enabled) }
        }
    }

    fun dismissError() = _uiState.update { it.copy(error = null) }
}
