package com.splitfree.ui.viewmodels

import androidx.annotation.StringRes
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.splitfree.R
import com.splitfree.data.ble.BleEvent
import com.splitfree.data.ble.BleHandshake
import com.splitfree.data.ble.BleTransfer
import com.splitfree.data.ble.NearbyPeer
import com.splitfree.data.ble.NearbySync
import com.splitfree.data.local.dao.EventDao
import com.splitfree.domain.repository.GroupRepositoryContract
import com.splitfree.domain.repository.IdentityContract
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
 * UI state for the BLE nearby sync screen.
 *
 * @property status what the screen should say about the current sync step, or null for nothing.
 */
data class NearbySyncUiState(
    val scanning: Boolean = false,
    val peers: List<NearbyPeer> = emptyList(),
    val syncing: Boolean = false,
    val status: UiMessage? = null
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
                    _uiState.value = _uiState.value.copy(
                        syncing = false,
                        status = UiMessage.Res(R.string.nearby_peer_data_failed)
                    )
                }
            }
        }
    }

    @Suppress("CyclomaticComplexMethod")
    private suspend fun handleEvent(event: BleEvent) {
        val peerAuthFailed = UiMessage.Res(R.string.nearby_peer_auth_failed)
        val handshakeTimedOut = UiMessage.Res(R.string.nearby_handshake_timeout)
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
                launchGuarded("Handshake timeout check", R.string.nearby_handshake_check_failed) {
                    delay(BleTransfer.HANDSHAKE_TIMEOUT_MS)
                    if (!bleTransfer.isAuthenticated(event.endpointId)) {
                        bleTransfer.clearPeer(event.endpointId)
                        nearbySync.disconnect(event.endpointId)
                        _uiState.value = _uiState.value.copy(status = handshakeTimedOut)
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
                                    result.challenge,
                                    result.pubkey
                                )
                                startSyncIfReady(event.endpointId, result)
                            } else {
                                _uiState.value = _uiState.value.copy(status = peerAuthFailed)
                            }
                        } else if (result.challengeResponse.isNotEmpty()) {
                            // Final response (leg 3): verify initiator's sig to complete mutual auth
                            if (bleTransfer.verifyHandshake(event.endpointId, result)) {
                                startSyncIfReady(event.endpointId, result)
                            } else {
                                _uiState.value = _uiState.value.copy(status = peerAuthFailed)
                            }
                        } else if (result.challenge.isNotEmpty()) {
                            // Leg 1 arrives before any authentication. Refuse to sign anything for a
                            // peer whose pubkey or challenge is not 32 bytes of hex: the challenge is
                            // bound into a signature made with the user's long-term Nostr key.
                            if (!isHex32(result.pubkey) || !isHex32(result.challenge)) {
                                Log.w(TAG, "Rejecting malformed handshake from ${event.endpointId}")
                                _uiState.value = _uiState.value.copy(status = peerAuthFailed)
                                bleTransfer.clearPeer(event.endpointId)
                                nearbySync.disconnect(event.endpointId)
                                return
                            }
                            if (bleTransfer.isHandshakeTimedOut(event.endpointId)) {
                                bleTransfer.clearPeer(event.endpointId)
                                _uiState.value = _uiState.value.copy(status = handshakeTimedOut)
                            } else {
                                // Initial handshake with challenge — send our response
                                val groups = groupRepo.getAll().map { it.id }
                                bleTransfer.sendHandshakeResponse(
                                    event.endpointId,
                                    identity.getPublicKeyHex(),
                                    groups,
                                    result.challenge,
                                    result.pubkey
                                )
                            }
                        }
                        _uiState.value = _uiState.value.copy(status = UiMessage.Res(R.string.nearby_authenticating))
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
                _uiState.value =
                    _uiState.value.copy(syncing = false, status = UiMessage.Res(R.string.nearby_sync_complete))
            }

            is BleEvent.Error -> {
                val status = UiMessage.Res(R.string.nearby_ble_error, event.operation, event.reason)
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
        launchGuarded("Group ID exchange", R.string.nearby_group_exchange_failed) {
            val groups = groupRepo.getAll().map { it.id }
            bleTransfer.sendGroupIds(endpointId, groups)
        }
    }

    /** Exactly 32 bytes as lowercase hex, the required shape of a handshake pubkey and challenge. */
    private fun isHex32(s: String) = s.length == 64 && s.all { it in HEX_ALPHABET }

    private fun onGroupIdsReceived(endpointId: String, groupIds: List<String>) {
        if (!bleTransfer.isAuthenticated(endpointId)) return
        if (groupId in groupIds) {
            launchGuarded("Sync request", R.string.nearby_sync_request_failed) {
                val localIds = eventDao.getEventIds(groupId)
                bleTransfer.sendSyncRequest(endpointId, groupId, localIds)
                _uiState.value = _uiState.value.copy(status = UiMessage.Res(R.string.nearby_syncing))
            }
        }
    }

    /**
     * Runs [block] in the ViewModel scope with a failure boundary — a child coroutine
     * that throws would otherwise reach the scope's (absent) exception handler and
     * crash the process. [what] labels the log line; [failure] is what the screen shows.
     */
    private fun launchGuarded(what: String, @StringRes failure: Int, block: suspend () -> Unit): Job =
        viewModelScope.launch {
            try {
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "$what failed: ${e.message}")
                _uiState.value = _uiState.value.copy(syncing = false, status = UiMessage.Res(failure))
            }
        }

    fun startScan() {
        dutyCycleJob?.cancel()
        _uiState.value =
            _uiState.value.copy(scanning = true, peers = emptyList(), status = UiMessage.Res(R.string.nearby_scanning))
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
        nearbySync.stop()
        _uiState.value = _uiState.value.copy(scanning = false)
    }

    fun connectToPeer(endpointId: String) {
        _uiState.value = _uiState.value.copy(syncing = true, status = UiMessage.Res(R.string.nearby_connecting))
        nearbySync.requestConnection(endpointId)
    }

    override fun onCleared() {
        nearbySync.stop()
        super.onCleared()
    }

    companion object {
        private const val TAG = "NearbySyncVM"
        private const val HEX_ALPHABET = "0123456789abcdef"
    }
}
