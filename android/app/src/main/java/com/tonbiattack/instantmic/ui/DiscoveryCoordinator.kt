package com.tonbiattack.instantmic.ui

import com.tonbiattack.instantmic.net.DiscoveredReceiver
import com.tonbiattack.instantmic.net.DiscoveryClient
import com.tonbiattack.instantmic.net.ReconnectPolicy
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** What the LAN search is doing. */
sealed interface DiscoveryState {
    /** Search is not running. */
    data object Off : DiscoveryState

    data object Searching : DiscoveryState

    /** At least one receiver is advertising itself. */
    data class Found(val receivers: List<DiscoveredReceiver>) : DiscoveryState

    /** Discovery is not available on this network or device. */
    data class Unavailable(val message: String) : DiscoveryState
}

/**
 * Owns the LAN search loop and decides whether a discovered PC may be reused.
 *
 * Keeping this separate from the main view model makes the rule "never silently
 * connect to a different PC or a changed certificate" testable in isolation.
 */
class DiscoveryCoordinator(
    private val discovery: DiscoveryClient,
    private val scope: CoroutineScope,
    private val onResolved: (host: String, port: Int, fingerprint: String) -> Unit,
    private val onNeedsRepair: (message: String) -> Unit,
    private val onState: (DiscoveryState) -> Unit,
) {

    private var job: Job? = null

    private val _state = MutableStateFlow<DiscoveryState>(DiscoveryState.Off)
    val state: StateFlow<DiscoveryState> = _state.asStateFlow()

    /** Starts searching for [serverId] and reports the first matching address. */
    fun start(serverId: String, fingerprint: String) {
        stop()
        publish(DiscoveryState.Searching)
        job = scope.launch {
            val seen = mutableListOf<DiscoveredReceiver>()
            var resolved = false
            try {
                discovery.discover().collect { receiver ->
                    if (seen.none { it.serverId == receiver.serverId && it.host == receiver.host }) {
                        seen += receiver
                        publish(DiscoveryState.Found(seen.toList()))
                    }
                    when (val verdict = ReconnectPolicy.evaluate(serverId, fingerprint, receiver)) {
                        is ReconnectPolicy.Verdict.Reuse -> {
                            if (!resolved) {
                                resolved = true
                                onResolved(verdict.host, verdict.port, verdict.fingerprint)
                            }
                        }

                        is ReconnectPolicy.Verdict.Repaired -> {
                            // Stop at the first mismatch: continuing could pick a
                            // different PC later and make the failure confusing.
                            stop()
                            onNeedsRepair(verdict.message)
                        }

                        ReconnectPolicy.Verdict.UseStoredAddress -> Unit
                    }
                }
            } catch (e: CancellationException) {
                // stop() cancelled the search on purpose; not an error.
                throw e
            } catch (e: Exception) {
                publish(DiscoveryState.Unavailable(e.message ?: "自動検出を利用できません"))
            }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
        publish(DiscoveryState.Off)
    }

    private fun publish(next: DiscoveryState) {
        _state.value = next
        onState(next)
    }
}
