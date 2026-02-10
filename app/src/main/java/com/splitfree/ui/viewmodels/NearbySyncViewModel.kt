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
import dagger.hilt.android.lifecycle.HiltViewModel
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
    private val eventDao: EventDao
) : ViewModel() {
    private val groupId: String = savedStateHandle["groupId"] ?: ""
    private val _uiState = MutableStateFlow(NearbySyncUiState())
    val uiState: StateFlow<NearbySyncUiState> = _uiState.asStateFlow()

    private val peerHandshakes = mutableMapOf<String, BleHandshake>()

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
                        bleTransfer.sendHandshake(event.endpointId, identity.getPublicKey(), groups)
                    }
                    is BleEvent.PayloadReceived -> {
                        val result = bleTransfer.processPayload(event.endpointId, event.data)
                        if (result is BleHandshake) {
                            peerHandshakes[event.endpointId] = result
                            // If we share this group, send sync request
                            if (groupId in result.groups) {
                                val localIds = eventDao.getEventIds(groupId)
                                bleTransfer.sendSyncRequest(event.endpointId, groupId, localIds)
                            }
                            _uiState.value = _uiState.value.copy(status = "Syncing with peer…")
                        }
                    }
                    is BleEvent.Disconnected -> {
                        peerHandshakes.remove(event.endpointId)
                        _uiState.value = _uiState.value.copy(syncing = false, status = "Sync complete")
                    }
                }
            }
        }
    }

    fun startScan() {
        _uiState.value = _uiState.value.copy(scanning = true, peers = emptyList(), status = "Scanning…")
        nearbySync.startAdvertising()
        nearbySync.startDiscovery()
    }

    fun stopScan() {
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
