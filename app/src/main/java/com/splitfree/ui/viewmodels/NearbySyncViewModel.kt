package com.splitfree.ui.viewmodels

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.splitfree.R
import com.splitfree.data.ble.BleEvent
import com.splitfree.data.ble.NearbyPeer
import com.splitfree.data.ble.NearbySync
import com.splitfree.sync.nearby.NearbySessionCoordinator
import com.splitfree.sync.nearby.NearbySessionsState
import com.splitfree.sync.nearby.NearbyWire
import com.splitfree.sync.nearby.PeerPhase
import com.splitfree.sync.nearby.PeerProgress
import com.splitfree.sync.worker.PowerManager
import com.splitfree.ui.util.UiMessage
import com.splitfree.util.DebugLog as Log
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * UI state for the nearby sync screen.
 *
 * @property status what the screen should say about the current sync step, or null for nothing.
 * @property progress the most relevant peer session, for screens that want counts.
 */
data class NearbySyncUiState(
    val scanning: Boolean = false,
    val peers: List<NearbyPeer> = emptyList(),
    val syncing: Boolean = false,
    val status: UiMessage? = null,
    val progress: PeerProgress? = null
)

/**
 * Drives discovery for the nearby screen and projects [NearbySessionCoordinator] progress into text.
 * Handshake, authorization and reconciliation live in the coordinator; this class owns no protocol state.
 */
@HiltViewModel
class NearbySyncViewModel
@Inject
constructor(
    savedStateHandle: SavedStateHandle,
    private val nearbySync: NearbySync,
    private val coordinator: NearbySessionCoordinator,
    private val powerManager: PowerManager
) : ViewModel() {
    private val groupId: String = savedStateHandle["groupId"] ?: ""
    private val _uiState = MutableStateFlow(NearbySyncUiState())
    val uiState: StateFlow<NearbySyncUiState> = _uiState.asStateFlow()

    private var dutyCycleJob: Job? = null

    init {
        viewModelScope.launch {
            nearbySync.events.collect { event ->
                try {
                    handleEvent(event)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.w(TAG, "Dropped BLE event ${event::class.simpleName}: ${e.message}")
                }
            }
        }
        viewModelScope.launch { coordinator.state.collect { project(it) } }
    }

    private fun handleEvent(event: BleEvent) {
        when (event) {
            is BleEvent.PeerFound -> {
                val current = _uiState.value.peers
                if (current.none { it.endpointId == event.peer.endpointId }) {
                    _uiState.value = _uiState.value.copy(peers = current + event.peer)
                }
            }

            is BleEvent.PeerLost ->
                _uiState.value =
                    _uiState.value.copy(peers = _uiState.value.peers.filter { it.endpointId != event.endpointId })

            is BleEvent.Error -> {
                val scanFatal = event.operation == "advertise" || event.operation == "discovery"
                _uiState.value =
                    _uiState.value.copy(
                        scanning = if (scanFatal) false else _uiState.value.scanning,
                        syncing = if (scanFatal) false else _uiState.value.syncing,
                        status = UiMessage.Res(R.string.nearby_ble_error, event.operation, event.reason)
                    )
                if (scanFatal) {
                    dutyCycleJob?.cancel()
                    dutyCycleJob = null
                    coordinator.deactivate()
                    nearbySync.stop()
                }
            }

            // Connection and payload events belong to the coordinator.
            is BleEvent.Connected, is BleEvent.Disconnected, is BleEvent.PayloadReceived -> Unit
        }
    }

    /** Map coordinator state onto the screen: one line of status for the most relevant peer. */
    private fun project(state: NearbySessionsState) {
        val live = state.peers.values.filter { !it.phase.isTerminal() }
        val primary = live.maxByOrNull { it.phase.ordinal } ?: state.peers.values.maxByOrNull { it.phase.ordinal }
        val current = _uiState.value
        if (primary == null) {
            if (current.progress != null) _uiState.value = current.copy(syncing = false, progress = null)
            return
        }
        val syncing =
            live.isNotEmpty() && !live.all { it.phase == PeerPhase.UP_TO_DATE || it.phase == PeerPhase.INCOMPLETE }
        _uiState.value = current.copy(syncing = syncing, status = statusFor(primary), progress = primary)
    }

    private fun statusFor(p: PeerProgress): UiMessage = when (p.phase) {
        PeerPhase.AUTHENTICATING -> UiMessage.Res(R.string.nearby_authenticating)
        PeerPhase.OPENING_GROUP -> UiMessage.Res(R.string.nearby_opening_group)
        PeerPhase.COMPARING -> UiMessage.Res(R.string.nearby_comparing)
        PeerPhase.TRANSFERRING -> UiMessage.Res(R.string.nearby_transferring, p.stats.applied, p.stats.sent)
        PeerPhase.WAITING_DEPENDENCY ->
            UiMessage.Plural(R.plurals.nearby_waiting_dependency, p.stats.deferred, p.stats.deferred)
        PeerPhase.UP_TO_DATE -> UiMessage.Res(R.string.nearby_up_to_date, p.stats.applied, p.stats.sent)
        PeerPhase.INCOMPLETE -> {
            val failed = p.stats.rejected + p.stats.busy + p.stats.unresolved + p.stats.held
            UiMessage.Plural(R.plurals.nearby_incomplete, failed, failed)
        }
        PeerPhase.UNSUPPORTED_PEER -> UiMessage.Res(R.string.nearby_unsupported_peer)
        PeerPhase.AUTH_FAILED -> UiMessage.Res(R.string.nearby_peer_auth_failed)
        PeerPhase.UNAUTHORIZED -> UiMessage.Res(R.string.nearby_unauthorized)
        PeerPhase.INTERRUPTED ->
            if (p.closeReason == NearbyWire.CLOSE_TIMEOUT) {
                UiMessage.Res(R.string.nearby_handshake_timeout)
            } else {
                UiMessage.Plural(R.plurals.nearby_interrupted, p.stats.applied, p.stats.applied)
            }
        PeerPhase.CLOSED -> UiMessage.Res(R.string.nearby_sync_complete)
    }

    private fun PeerPhase.isTerminal(): Boolean = this == PeerPhase.UNSUPPORTED_PEER ||
        this == PeerPhase.AUTH_FAILED ||
        this == PeerPhase.UNAUTHORIZED ||
        this == PeerPhase.INTERRUPTED ||
        this == PeerPhase.CLOSED

    fun startScan() {
        dutyCycleJob?.cancel()
        _uiState.value =
            _uiState.value.copy(scanning = true, peers = emptyList(), status = UiMessage.Res(R.string.nearby_scanning))
        coordinator.activate(groupId)
        dutyCycleJob =
            viewModelScope.launch {
                // A throw here (missing permission, unreadable identity) would otherwise
                // reach the ViewModel scope with no handler and kill the process.
                try {
                    nearbySync.startAdvertising()
                    while (true) {
                        val (scanMs, pauseMs) = powerManager.bleScanDuty()
                        nearbySync.startDiscovery()
                        delay(scanMs)
                        nearbySync.stopDiscovery()
                        delay(pauseMs)
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.w(TAG, "BLE scan duty cycle stopped: ${e.message}")
                    coordinator.deactivate()
                    nearbySync.stop()
                    _uiState.value =
                        _uiState.value.copy(
                            scanning = false,
                            syncing = false,
                            status = UiMessage.Res(R.string.nearby_scan_failed, e.message.orEmpty())
                        )
                }
            }
    }

    fun stopScan() {
        dutyCycleJob?.cancel()
        dutyCycleJob = null
        coordinator.deactivate()
        nearbySync.stop()
        _uiState.value = _uiState.value.copy(scanning = false, syncing = false)
    }

    fun connectToPeer(endpointId: String) {
        _uiState.value = _uiState.value.copy(syncing = true, status = UiMessage.Res(R.string.nearby_connecting))
        nearbySync.requestConnection(endpointId)
    }

    override fun onCleared() {
        coordinator.deactivate()
        nearbySync.stop()
    }

    companion object {
        private const val TAG = "NearbySyncVM"
    }
}
