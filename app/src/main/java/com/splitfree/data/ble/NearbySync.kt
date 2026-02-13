package com.splitfree.data.ble

import android.content.Context
import android.util.Log
import com.google.android.gms.nearby.Nearby
import com.google.android.gms.nearby.connection.*
import com.splitfree.domain.crypto.IdentityManager
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import javax.inject.Inject
import javax.inject.Singleton

data class NearbyPeer(val endpointId: String, val name: String)

sealed class BleEvent {
    data class PeerFound(val peer: NearbyPeer) : BleEvent()
    data class PeerLost(val endpointId: String) : BleEvent()
    data class Connected(val endpointId: String) : BleEvent()
    data class Disconnected(val endpointId: String) : BleEvent()
    data class PayloadReceived(val endpointId: String, val data: ByteArray) : BleEvent()
}

@Singleton
class NearbySync @Inject constructor(
    @ApplicationContext private val context: Context,
    private val identity: IdentityManager
) {
    private val client: ConnectionsClient by lazy { Nearby.getConnectionsClient(context) }
    private val _events = MutableSharedFlow<BleEvent>(extraBufferCapacity = 32)
    val events: SharedFlow<BleEvent> = _events

    private val connectedEndpoints = mutableSetOf<String>()

    fun startAdvertising() {
        val options = AdvertisingOptions.Builder().setStrategy(Strategy.P2P_CLUSTER).build()
        client.startAdvertising(
            identity.getPublicKeyHex().take(8),
            SERVICE_ID,
            connectionLifecycleCallback,
            options
        ).addOnFailureListener { Log.w(TAG, "Advertise failed: ${it.message}") }
    }

    fun startDiscovery() {
        val options = DiscoveryOptions.Builder().setStrategy(Strategy.P2P_CLUSTER).build()
        client.startDiscovery(SERVICE_ID, endpointDiscoveryCallback, options)
            .addOnFailureListener { Log.w(TAG, "Discovery failed: ${it.message}") }
    }

    fun requestConnection(endpointId: String) {
        client.requestConnection(identity.getPublicKeyHex().take(8), endpointId, connectionLifecycleCallback)
    }

    fun sendPayload(endpointId: String, data: ByteArray) {
        client.sendPayload(endpointId, Payload.fromBytes(data))
    }

    fun stopDiscovery() {
        client.stopDiscovery()
    }

    fun stop() {
        client.stopAdvertising()
        client.stopDiscovery()
        client.stopAllEndpoints()
        connectedEndpoints.clear()
    }

    private val endpointDiscoveryCallback = object : EndpointDiscoveryCallback() {
        override fun onEndpointFound(endpointId: String, info: DiscoveredEndpointInfo) {
            if (info.serviceId == SERVICE_ID) {
                _events.tryEmit(BleEvent.PeerFound(NearbyPeer(endpointId, info.endpointName)))
            }
        }
        override fun onEndpointLost(endpointId: String) {
            _events.tryEmit(BleEvent.PeerLost(endpointId))
        }
    }

    private val connectionLifecycleCallback = object : ConnectionLifecycleCallback() {
        override fun onConnectionInitiated(endpointId: String, info: ConnectionInfo) {
            // Accept connection to allow handshake — actual authentication happens
            // via Schnorr challenge-response in BleTransfer after connection.
            // Validate endpoint name is a plausible hex pubkey prefix.
            val name = info.endpointName
            if (name.length == 8 && name.all { it in "0123456789abcdef" }) {
                client.acceptConnection(endpointId, payloadCallback)
            } else {
                Log.w(TAG, "Rejecting connection from endpoint with invalid name: $name")
                client.rejectConnection(endpointId)
            }
        }
        override fun onConnectionResult(endpointId: String, result: ConnectionResolution) {
            if (result.status.isSuccess) {
                connectedEndpoints.add(endpointId)
                _events.tryEmit(BleEvent.Connected(endpointId))
            }
        }
        override fun onDisconnected(endpointId: String) {
            connectedEndpoints.remove(endpointId)
            _events.tryEmit(BleEvent.Disconnected(endpointId))
        }
    }

    private val payloadCallback = object : PayloadCallback() {
        override fun onPayloadReceived(endpointId: String, payload: Payload) {
            payload.asBytes()?.let { _events.tryEmit(BleEvent.PayloadReceived(endpointId, it)) }
        }
        override fun onPayloadTransferUpdate(endpointId: String, update: PayloadTransferUpdate) {}
    }

    companion object {
        private const val TAG = "NearbySync"
        private const val SERVICE_ID = "com.splitfree.ble"
    }
}
