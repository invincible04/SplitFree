package com.splitfree.data.ble

import android.content.Context
import com.google.android.gms.nearby.Nearby
import com.google.android.gms.nearby.connection.AdvertisingOptions
import com.google.android.gms.nearby.connection.ConnectionInfo
import com.google.android.gms.nearby.connection.ConnectionLifecycleCallback
import com.google.android.gms.nearby.connection.ConnectionResolution
import com.google.android.gms.nearby.connection.ConnectionsClient
import com.google.android.gms.nearby.connection.DiscoveredEndpointInfo
import com.google.android.gms.nearby.connection.DiscoveryOptions
import com.google.android.gms.nearby.connection.EndpointDiscoveryCallback
import com.google.android.gms.nearby.connection.Payload
import com.google.android.gms.nearby.connection.PayloadCallback
import com.google.android.gms.nearby.connection.PayloadTransferUpdate
import com.google.android.gms.nearby.connection.Strategy
import com.splitfree.data.identity.IdentityManager
import com.splitfree.util.DebugLog as Log
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow

/**
 * Discovered BLE peer with its Nearby Connections endpoint ID and pubkey prefix.
 */
data class NearbyPeer(val endpointId: String, val name: String)

/**
 * Events emitted by [NearbySync] for peer discovery, connection, and data transfer.
 */
sealed class BleEvent {
    data class PeerFound(val peer: NearbyPeer) : BleEvent()

    data class PeerLost(val endpointId: String) : BleEvent()

    data class Connected(val endpointId: String) : BleEvent()

    data class Disconnected(val endpointId: String) : BleEvent()

    data class PayloadReceived(val endpointId: String, val data: ByteArray) : BleEvent()
}

/**
 * Google Nearby Connections wrapper for peer-to-peer BLE sync.
 *
 * Advertises and discovers peers using P2P_CLUSTER strategy. Connections are accepted
 * only if the endpoint name is a valid 8-char hex pubkey prefix. Actual authentication
 * happens via Schnorr challenge-response in [BleTransfer] after connection.
 */
@Singleton
class NearbySync
@Inject
constructor(
    @ApplicationContext private val context: Context,
    private val identity: IdentityManager
) {
    private val client: ConnectionsClient by lazy { Nearby.getConnectionsClient(context) }
    private val _events = MutableSharedFlow<BleEvent>(extraBufferCapacity = 32)
    val events: SharedFlow<BleEvent> = _events

    private val connectedEndpoints = ConcurrentHashMap.newKeySet<String>()

    fun startAdvertising() {
        val options = AdvertisingOptions.Builder().setStrategy(Strategy.P2P_CLUSTER).build()
        client
            .startAdvertising(
                identity.getPublicKeyHex().take(8),
                SERVICE_ID,
                connectionLifecycleCallback,
                options
            ).addOnFailureListener { Log.w(TAG, "Advertise failed: ${it.message}") }
    }

    fun startDiscovery() {
        val options = DiscoveryOptions.Builder().setStrategy(Strategy.P2P_CLUSTER).build()
        client
            .startDiscovery(SERVICE_ID, endpointDiscoveryCallback, options)
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

    fun disconnect(endpointId: String) {
        client.disconnectFromEndpoint(endpointId)
        connectedEndpoints.remove(endpointId)
    }

    fun stop() {
        client.stopAdvertising()
        client.stopDiscovery()
        client.stopAllEndpoints()
        connectedEndpoints.clear()
    }

    private val endpointDiscoveryCallback =
        object : EndpointDiscoveryCallback() {
            override fun onEndpointFound(endpointId: String, info: DiscoveredEndpointInfo) {
                if (info.serviceId == SERVICE_ID) {
                    _events.tryEmit(BleEvent.PeerFound(NearbyPeer(endpointId, info.endpointName)))
                }
            }

            override fun onEndpointLost(endpointId: String) {
                _events.tryEmit(BleEvent.PeerLost(endpointId))
            }
        }

    private val connectionLifecycleCallback =
        object : ConnectionLifecycleCallback() {
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

    private val payloadCallback =
        object : PayloadCallback() {
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
