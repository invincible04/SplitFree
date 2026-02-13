package com.splitfree.ui.viewmodels

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.splitfree.data.ble.BleEvent
import com.splitfree.data.ble.BleHandshake
import com.splitfree.data.ble.BleTransfer
import com.splitfree.data.ble.NearbyPeer
import com.splitfree.data.ble.NearbySync
import com.splitfree.data.local.EventDao
import com.splitfree.data.repository.GroupRepository
import com.splitfree.domain.crypto.IdentityManager
import com.splitfree.sync.PowerManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

data class NearbySyncUiState(
    val scanning: Boolean = false,
    val peers: List<NearbyPeer> = emptyList(),
    val syncing: Boolean = false,
    val status: String = ""
)

@HiltViewModel
class NearbySyncViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val nearbySync: NearbySync,
    private val bleTransfer: BleTransfer,
    private val identity: IdentityManager,
    private val groupRepo: GroupRepository,
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
                when (event) {
                    is BleEvent.PeerFound -> {
                        val current = _uiState.value.peers.toMutableList()
                        if (current.none { it.endpointId == event.peer.endpointId }) {
                            current.add(event.peer)
                            _uiState.value = _uiState.value.copy(peers = current)
                        }
                    }
                    is BleEvent.PeerLost -> {
                        _uiState.value = _uiState.value.copy(
                            peers = _uiState.value.peers.filter { it.endpointId != event.endpointId }
                        )
                    }
                    is BleEvent.Connected -> {
                        val groups = groupRepo.getAll().map { it.id }
                        bleTransfer.sendHandshake(event.endpointId, identity.getPublicKeyHex(), groups)
                        // Enforce handshake timeout
                        viewModelScope.launch {
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

                                if (result.challengeResponse.isNotEmpty()) {
                                    // This is a response — verify their signature
                                    if (bleTransfer.verifyHandshake(event.endpointId, result)) {
                                        // Peer authenticated — send our group IDs
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
                                            event.endpointId, identity.getPublicKeyHex(), groups, result.challenge
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
                }
            }
        }
    }

    private fun startSyncIfReady(endpointId: String, handshake: BleHandshake) {
        if (!bleTransfer.isAuthenticated(endpointId)) return
        // Send our group IDs now that peer is authenticated
        viewModelScope.launch {
            val groups = groupRepo.getAll().map { it.id }
            bleTransfer.sendGroupIds(endpointId, groups)
        }
    }

    private fun onGroupIdsReceived(endpointId: String, groupIds: List<String>) {
        if (!bleTransfer.isAuthenticated(endpointId)) return
        if (groupId in groupIds) {
            viewModelScope.launch {
                val localIds = eventDao.getEventIds(groupId)
                bleTransfer.sendSyncRequest(endpointId, groupId, localIds)
                _uiState.value = _uiState.value.copy(status = "Syncing with peer…")
            }
        }
    }

    fun startScan() {
        dutyCycleJob?.cancel()
        _uiState.value = _uiState.value.copy(scanning = true, peers = emptyList(), status = "Scanning…")
        dutyCycleJob = viewModelScope.launch {
            while (true) {
                val (scanMs, pauseMs) = powerManager.bleScanDuty()
                nearbySync.startAdvertising()
                nearbySync.startDiscovery()
                delay(scanMs)
                nearbySync.stopDiscovery()
                delay(pauseMs)
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
}
