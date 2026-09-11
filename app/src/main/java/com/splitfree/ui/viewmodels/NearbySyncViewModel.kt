package com.splitfree.ui.viewmodels

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.splitfree.data.ble.BleEvent
import com.splitfree.data.ble.BleHandshake
import com.splitfree.data.ble.BleTransfer
import com.splitfree.data.ble.NearbyPeer
import com.splitfree.data.ble.NearbySync
import com.splitfree.data.local.dao.EventDao
import com.splitfree.domain.repository.GroupRepositoryContract
import com.splitfree.domain.repository.IdentityContract
import com.splitfree.sync.worker.PowerManager
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
 * UI state for the BLE nearby sync screen.
 */
data class NearbySyncUiState(
    val scanning: Boolean = false,
    val peers: List<NearbyPeer> = emptyList(),
    val syncing: Boolean = false,
    val status: String = ""
)

/**
 * Manages BLE peer discovery, Schnorr-authenticated handshake, and event exchange
 * for offline peer-to-peer sync.
 */
@HiltViewModel
class NearbySyncViewModel
@Inject
constructor(
    savedStateHandle: SavedStateHandle,
    private val nearbySync: NearbySync,
    private val bleTransfer: BleTransfer,
    private val identity: IdentityContract,
    private val groupRepo: GroupRepositoryContract,
    private val eventDao: EventDao,
    private val powerManager: PowerManager
) : ViewModel() {
    private val groupId: String = savedStateHandle["groupId"] ?: ""
    private val _uiState = MutableStateFlow(NearbySyncUiState())
    val uiState: StateFlow<NearbySyncUiState> = _uiState.asStateFlow()

    private val peerHandshakes = mutableMapOf<String, BleHandshake>()
    private var dutyCycleJob: Job? = null

    init {
        viewModelScope.launch {
            nearbySync.events.collect { event ->
                // Per-event DB/identity failures must not terminate collection — this
                // collector is the only consumer of BLE events for the screen.
                try {
                    handleEvent(event)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.w(TAG, "Dropped BLE event ${event::class.simpleName}: ${e.message}")
                    _uiState.value = _uiState.value.copy(syncing = false, status = "Could not process peer data")
                }
            }
        }
    }

    @Suppress("CyclomaticComplexMethod")
    private suspend fun handleEvent(event: BleEvent) {
        when (event) {
            is BleEvent.PeerFound -> {
                val current = _uiState.value.peers.toMutableList()
                if (current.none { it.endpointId == event.peer.endpointId }) {
                    current.add(event.peer)
                    _uiState.value = _uiState.value.copy(peers = current)
                }
            }

            is BleEvent.PeerLost -> {
                _uiState.value =
                    _uiState.value.copy(
                        peers = _uiState.value.peers.filter { it.endpointId != event.endpointId }
                    )
            }

            is BleEvent.Connected -> {
                val groups = groupRepo.getAll().map { it.id }
                bleTransfer.sendHandshake(event.endpointId, identity.getPublicKeyHex(), groups)
                // Enforce handshake timeout
                launchGuarded("Handshake timeout check") {
                    delay(BleTransfer.HANDSHAKE_TIMEOUT_MS)
                    if (!bleTransfer.isAuthenticated(event.endpointId)) {
                        bleTransfer.clearPeer(event.endpointId)
                        nearbySync.disconnect(event.endpointId)
                        _uiState.value = _uiState.value.copy(status = "Handshake timed out")
                    }
                }
            }

            is BleEvent.PayloadReceived -> {
                val result = bleTransfer.processPayload(event.endpointId, event.data)
                when (result) {
                    is BleHandshake -> {
                        peerHandshakes[event.endpointId] = result

                        if (result.challengeResponse.isNotEmpty() && result.challenge.isNotEmpty()) {
                            // Response with counter-challenge (leg 2): verify their sig, then sign their challenge
                            if (bleTransfer.verifyHandshake(event.endpointId, result)) {
                                bleTransfer.sendChallengeResponse(
                                    event.endpointId,
                                    identity.getPublicKeyHex(),
                                    result.challenge
                                )
                                startSyncIfReady(event.endpointId, result)
                            } else {
                                _uiState.value = _uiState.value.copy(status = "Peer authentication failed")
                            }
                        } else if (result.challengeResponse.isNotEmpty()) {
                            // Final response (leg 3): verify initiator's sig to complete mutual auth
                            if (bleTransfer.verifyHandshake(event.endpointId, result)) {
                                startSyncIfReady(event.endpointId, result)
                            } else {
                                _uiState.value = _uiState.value.copy(status = "Peer authentication failed")
                            }
                        } else if (result.challenge.isNotEmpty()) {
                            if (bleTransfer.isHandshakeTimedOut(event.endpointId)) {
                                bleTransfer.clearPeer(event.endpointId)
                                _uiState.value = _uiState.value.copy(status = "Handshake timed out")
                            } else {
                                // Initial handshake with challenge — send our response
                                val groups = groupRepo.getAll().map { it.id }
                                bleTransfer.sendHandshakeResponse(
                                    event.endpointId,
                                    identity.getPublicKeyHex(),
                                    groups,
                                    result.challenge
                                )
                            }
                        }
                        _uiState.value = _uiState.value.copy(status = "Authenticating peer…")
                    }

                    is List<*> -> {
                        // Received group IDs from authenticated peer
                        @Suppress("UNCHECKED_CAST")
                        onGroupIdsReceived(event.endpointId, result as List<String>)
                    }
                }
            }

            is BleEvent.Disconnected -> {
                peerHandshakes.remove(event.endpointId)
                bleTransfer.clearPeer(event.endpointId)
                _uiState.value = _uiState.value.copy(syncing = false, status = "Sync complete")
            }

            is BleEvent.Error -> {
                val status = "BLE ${event.operation} failed: ${event.reason}"
                val scanFatal = event.operation == "advertise" || event.operation == "discovery"
                _uiState.value =
                    _uiState.value.copy(
                        scanning = if (scanFatal) false else _uiState.value.scanning,
                        syncing = false,
                        status = status
                    )
                if (scanFatal) {
                    dutyCycleJob?.cancel()
                    dutyCycleJob = null
                    nearbySync.stop()
                }
            }
        }
    }

    private fun startSyncIfReady(endpointId: String, handshake: BleHandshake) {
        if (!bleTransfer.isAuthenticated(endpointId)) return
        // Send our group IDs now that peer is authenticated
        launchGuarded("Group ID exchange") {
            val groups = groupRepo.getAll().map { it.id }
            bleTransfer.sendGroupIds(endpointId, groups)
        }
    }

    private fun onGroupIdsReceived(endpointId: String, groupIds: List<String>) {
        if (!bleTransfer.isAuthenticated(endpointId)) return
        if (groupId in groupIds) {
            launchGuarded("Sync request") {
                val localIds = eventDao.getEventIds(groupId)
                bleTransfer.sendSyncRequest(endpointId, groupId, localIds)
                _uiState.value = _uiState.value.copy(status = "Syncing with peer…")
            }
        }
    }

    /**
     * Runs [block] in the ViewModel scope with a failure boundary — a child coroutine
     * that throws would otherwise reach the scope's (absent) exception handler and
     * crash the process.
     */
    private fun launchGuarded(what: String, block: suspend () -> Unit): Job = viewModelScope.launch {
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "$what failed: ${e.message}")
            _uiState.value = _uiState.value.copy(syncing = false, status = "$what failed")
        }
    }

    fun startScan() {
        dutyCycleJob?.cancel()
        _uiState.value = _uiState.value.copy(scanning = true, peers = emptyList(), status = "Scanning…")
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
                    // Advertising may already be live — release the radio in the catch only,
                    // so cancelling this job for a fresh scan does not stop the new one.
                    Log.w(TAG, "BLE scan duty cycle stopped: ${e.message}")
                    nearbySync.stop()
                    _uiState.value =
                        _uiState.value.copy(scanning = false, syncing = false, status = "Scan failed: ${e.message}")
                }
            }
    }

    fun stopScan() {
        dutyCycleJob?.cancel()
        dutyCycleJob = null
        nearbySync.stop()
        _uiState.value = _uiState.value.copy(scanning = false)
    }

    fun connectToPeer(endpointId: String) {
        _uiState.value = _uiState.value.copy(syncing = true, status = "Connecting…")
        nearbySync.requestConnection(endpointId)
    }

    override fun onCleared() {
        nearbySync.stop()
        super.onCleared()
    }

    companion object {
        private const val TAG = "NearbySyncVM"
    }
}
