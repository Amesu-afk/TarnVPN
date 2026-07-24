package io.nekohasekai.sfa.tarn.screen

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.nekohasekai.sfa.tarn.data.DnsOption
import io.nekohasekai.sfa.tarn.data.Latency
import io.nekohasekai.sfa.tarn.data.TarnDns
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class TarnDnsUiState(
    val options: List<DnsOption> = TarnDns.OPTIONS,
    val pings: Map<String, Latency> = emptyMap(),
)

/**
 * Measures the same TCP-connect latency the servers screen uses, but against each
 * resolver's DoH port — so the two numbers on screen mean the same thing and a user can
 * compare "how far is my server" against "how far is my DNS" directly.
 */
class TarnDnsViewModel : ViewModel() {
    private val _uiState = MutableStateFlow(TarnDnsUiState())
    val uiState: StateFlow<TarnDnsUiState> = _uiState.asStateFlow()

    init {
        probeAll()
    }

    fun probeAll() {
        val options = _uiState.value.options
        // byId() falls back to DEFAULT when no valid custom address is saved — the id
        // check is what tells "a real custom entry" apart from that fallback.
        val custom = TarnDns.byId(TarnDns.CUSTOM_ID).takeIf { it.id == TarnDns.CUSTOM_ID }

        val probing = options.associate { o -> o.id to Latency.Probing } +
            listOfNotNull(custom?.let { it.id to Latency.Probing })
        _uiState.update { it.copy(pings = probing) }

        (options + listOfNotNull(custom)).forEach { option ->
            viewModelScope.launch {
                val millis = TarnDns.probe(option)
                val latency = millis?.let { Latency.Ok(it) } ?: Latency.Unreachable
                _uiState.update { it.copy(pings = it.pings + (option.id to latency)) }
            }
        }
    }
}
