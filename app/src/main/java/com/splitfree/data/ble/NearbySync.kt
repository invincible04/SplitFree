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
import com.splitfree.sync.nearby.NearbyTransport
import com.splitfree.util.DebugLog as Log
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow

/** Discovered Nearby endpoint with an unverified, self-reported name. */
data class NearbyPeer(val endpointId: String, val name: String)

/** Transport events from [NearbySync]; a connection event does not imply application authentication. */
sealed class BleEvent {
    /** Endpoint discovered under the application's service id; its name is unverified. */
    data class PeerFound(val peer: NearbyPeer) : BleEvent()

    /** Endpoint disappears from discovery; an existing connection can remain open. */
    data class PeerLost(val endpointId: String) : BleEvent()

    /**
     * Successful transport connection ready for the session handshake.
     *
     * @property isIncoming local Nearby connection role used to select the protocol initiator
     * @property authToken raw Nearby authentication token captured at initiation, or null when unavailable
     */
    data class Connected(val endpointId: String, val isIncoming: Boolean = false, val authToken: ByteArray? = null) :
        BleEvent()

    /** Transport disconnection requiring cleanup of the endpoint's session. */
    data class Disconnected(val endpointId: String) : BleEvent()

    /** Raw byte payload awaiting protocol decoding and validation. */
    data class PayloadReceived(val endpointId: String, val data: ByteArray) : BleEvent()

    /** Operation failure with diagnostic text; no endpoint identity is attached. */
    data class Error(val operation: String, val reason: String) : BleEvent()
}

/**
 * Google Nearby Connections transport using the P2P_CLUSTER strategy.
 * Connection acceptance requires an eight-character lowercase hex name, which is unverified.
 * [com.splitfree.sync.nearby.PeerSession] authenticates identity and authorizes the group after connection.
 * Events are best-effort with no replay: subscribe before starting operations and handle missing progress.
 */
@Singleton
class NearbySync
@Inject
constructor(
    @ApplicationContext private val context: Context,
    private val identity: IdentityManager
) : NearbyTransport {
    private val client: ConnectionsClient by lazy { Nearby.getConnectionsClient(context) }
    private val _events = MutableSharedFlow<BleEvent>(extraBufferCapacity = EVENT_BUFFER_CAPACITY)
    override val events: SharedFlow<BleEvent> = _events

    /** Counts full-buffer drops only; events emitted without subscribers are not counted. */
    val droppedEvents = AtomicLong(0)

    private val connectedEndpoints = ConcurrentHashMap.newKeySet<String>()

    /** Role and channel token captured at initiation, consumed on a successful result. */
    private data class PendingConnection(val isIncoming: Boolean, val authToken: ByteArray?)

    private val pendingConnections = ConcurrentHashMap<String, PendingConnection>()

    /** Keeps callbacks nonblocking; full-buffer drops are counted with rate-limited diagnostics. */
    private fun emitOrDrop(event: BleEvent) {
        if (_events.tryEmit(event)) return
        val dropped = droppedEvents.incrementAndGet()
        if (dropped % DROP_LOG_INTERVAL == 1L) {
            Log.w(TAG, "Event buffer full: $dropped dropped so far (last: ${event::class.simpleName})")
        }
    }

    /** Starts asynchronous advertising under the local key prefix; startup failures are emitted as [BleEvent.Error]. */
    fun startAdvertising() {
        try {
            val options = AdvertisingOptions.Builder().setStrategy(Strategy.P2P_CLUSTER).build()
            client
                .startAdvertising(
                    identity.getPublicKeyHex().take(8),
                    SERVICE_ID,
                    connectionLifecycleCallback,
                    options
                ).addOnFailureListener { emitError("advertise", it) }
        } catch (e: SecurityException) {
            emitError("advertise", e)
        }
    }

    /** Starts asynchronous discovery for this application's service; failures are emitted as [BleEvent.Error]. */
    fun startDiscovery() {
        try {
            val options = DiscoveryOptions.Builder().setStrategy(Strategy.P2P_CLUSTER).build()
            client
                .startDiscovery(SERVICE_ID, endpointDiscoveryCallback, options)
                .addOnFailureListener { emitError("discovery", it) }
        } catch (e: SecurityException) {
            emitError("discovery", e)
        }
    }

    /** Requests a transport connection asynchronously; successful connections still require session authentication. */
    fun requestConnection(endpointId: String) {
        try {
            client
                .requestConnection(identity.getPublicKeyHex().take(8), endpointId, connectionLifecycleCallback)
                .addOnFailureListener { emitError("request_connection", it) }
        } catch (e: SecurityException) {
            emitError("request_connection", e)
        }
    }

    override fun sendPayload(endpointId: String, data: ByteArray) {
        try {
            client
                .sendPayload(endpointId, Payload.fromBytes(data))
                .addOnFailureListener { emitError("send_payload", it) }
        } catch (e: SecurityException) {
            emitError("send_payload", e)
        }
    }

    /** Stops discovery while leaving advertising and connected endpoints active. */
    fun stopDiscovery() {
        guarded("stop_discovery") { client.stopDiscovery() }
    }

    override fun disconnect(endpointId: String) {
        guarded("disconnect") { client.disconnectFromEndpoint(endpointId) }
        connectedEndpoints.remove(endpointId)
        pendingConnections.remove(endpointId)
    }

    /**
     * Stops advertising, discovery and endpoints, then clears local connection state.
     * Permission failures are reported independently so the remaining cleanup can proceed.
     */
    fun stop() {
        guarded("stop_advertising") { client.stopAdvertising() }
        guarded("stop_discovery") { client.stopDiscovery() }
        guarded("stop_all_endpoints") { client.stopAllEndpoints() }
        connectedEndpoints.clear()
        pendingConnections.clear()
    }

    /** Converts permission failures into [BleEvent.Error] events. */
    private inline fun guarded(operation: String, block: () -> Unit) {
        try {
            block()
        } catch (e: SecurityException) {
            emitError(operation, e)
        }
    }

    private val endpointDiscoveryCallback =
        object : EndpointDiscoveryCallback() {
            override fun onEndpointFound(endpointId: String, info: DiscoveredEndpointInfo) {
                if (info.serviceId == SERVICE_ID) {
                    emitOrDrop(BleEvent.PeerFound(NearbyPeer(endpointId, info.endpointName)))
                }
            }

            override fun onEndpointLost(endpointId: String) {
                emitOrDrop(BleEvent.PeerLost(endpointId))
            }
        }

    private val connectionLifecycleCallback =
        object : ConnectionLifecycleCallback() {
            override fun onConnectionInitiated(endpointId: String, info: ConnectionInfo) {
                val name = info.endpointName
                // Permission revocation must remain contained within the transport callback.
                try {
                    if (name.length == 8 && name.all { it in "0123456789abcdef" }) {
                        pendingConnections[endpointId] =
                            PendingConnection(info.isIncomingConnection, info.rawAuthenticationToken)
                        client
                            .acceptConnection(endpointId, payloadCallback)
                            .addOnFailureListener { emitError("accept_connection", it) }
                    } else {
                        Log.w(TAG, "Rejecting connection from endpoint with invalid name: $name")
                        client.rejectConnection(endpointId).addOnFailureListener { emitError("reject_connection", it) }
                    }
                } catch (e: SecurityException) {
                    emitError("connection_initiated", e)
                }
            }

            override fun onConnectionResult(endpointId: String, result: ConnectionResolution) {
                val pending = pendingConnections.remove(endpointId)
                if (result.status.isSuccess) {
                    connectedEndpoints.add(endpointId)
                    emitOrDrop(BleEvent.Connected(endpointId, pending?.isIncoming ?: false, pending?.authToken))
                } else {
                    val reason = result.status.statusMessage ?: "status=${result.status.statusCode}"
                    Log.w(TAG, "Connection failed for $endpointId: $reason")
                    emitOrDrop(BleEvent.Error("connection_result", reason))
                }
            }

            override fun onDisconnected(endpointId: String) {
                connectedEndpoints.remove(endpointId)
                pendingConnections.remove(endpointId)
                emitOrDrop(BleEvent.Disconnected(endpointId))
            }
        }

    private val payloadCallback =
        object : PayloadCallback() {
            override fun onPayloadReceived(endpointId: String, payload: Payload) {
                payload.asBytes()?.let { emitOrDrop(BleEvent.PayloadReceived(endpointId, it)) }
            }

            override fun onPayloadTransferUpdate(endpointId: String, update: PayloadTransferUpdate) {}
        }

    companion object {
        private const val TAG = "NearbySync"
        private const val SERVICE_ID = "com.splitfree.ble"

        /** Buffer capacity for slow subscribers; events have no replay when no subscriber is present. */
        const val EVENT_BUFFER_CAPACITY = 1024

        /** Number of drops between diagnostics, starting with the first drop. */
        private const val DROP_LOG_INTERVAL = 100L
    }

    private fun emitError(operation: String, throwable: Throwable) {
        val reason = throwable.message ?: throwable.javaClass.simpleName
        Log.w(TAG, "$operation failed: $reason")
        emitOrDrop(BleEvent.Error(operation, reason))
    }
}
