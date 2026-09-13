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

    /**
     * @property isIncoming the Nearby connection role for this endpoint ([ConnectionInfo.isIncomingConnection]);
     *   the session engine derives the protocol initiator from it
     * @property authToken Nearby's raw authentication token for this connection, identical on both ends
     *   and different per connection; bound into the authentication transcript
     */
    data class Connected(val endpointId: String, val isIncoming: Boolean = false, val authToken: ByteArray? = null) :
        BleEvent()

    data class Disconnected(val endpointId: String) : BleEvent()

    data class PayloadReceived(val endpointId: String, val data: ByteArray) : BleEvent()

    data class Error(val operation: String, val reason: String) : BleEvent()
}

/**
 * Google Nearby Connections wrapper for peer-to-peer BLE sync.
 *
 * Advertises and discovers peers using P2P_CLUSTER strategy. Connections are accepted
 * only if the endpoint name is a valid 8-char hex pubkey prefix. Actual authentication
 * happens in [com.splitfree.sync.nearby.PeerSession] after connection, bound to the connection's
 * raw authentication token which this class captures at [ConnectionLifecycleCallback.onConnectionInitiated].
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

    /**
     * Events that could not be handed to [events] because the buffer was full: a burst of
     * payloads arrived faster than the collector processed them. Diagnostics only.
     */
    val droppedEvents = AtomicLong(0)

    private val connectedEndpoints = ConcurrentHashMap.newKeySet<String>()

    /** Role and channel token captured at initiation, consumed on a successful result. */
    private data class PendingConnection(val isIncoming: Boolean, val authToken: ByteArray?)

    private val pendingConnections = ConcurrentHashMap<String, PendingConnection>()

    /**
     * Hand [event] to [events]. Nearby callbacks run on a binder thread and must not block, so a
     * full buffer means the event is dropped; count it and log at most once per
     * [DROP_LOG_INTERVAL] drops so a burst cannot flood logcat.
     */
    private fun emitOrDrop(event: BleEvent) {
        if (_events.tryEmit(event)) return
        val dropped = droppedEvents.incrementAndGet()
        if (dropped % DROP_LOG_INTERVAL == 1L) {
            Log.w(TAG, "Event buffer full: $dropped dropped so far (last: ${event::class.simpleName})")
        }
    }

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

    fun stopDiscovery() {
        guarded("stop_discovery") { client.stopDiscovery() }
    }

    override fun disconnect(endpointId: String) {
        guarded("disconnect") { client.disconnectFromEndpoint(endpointId) }
        connectedEndpoints.remove(endpointId)
        pendingConnections.remove(endpointId)
    }

    /**
     * Tear down advertising, discovery and all endpoints. Each call is guarded separately so
     * that one refusal (permissions revoked while connected) cannot skip the remaining
     * teardown, and local state is cleared either way.
     */
    fun stop() {
        guarded("stop_advertising") { client.stopAdvertising() }
        guarded("stop_discovery") { client.stopDiscovery() }
        guarded("stop_all_endpoints") { client.stopAllEndpoints() }
        connectedEndpoints.clear()
        pendingConnections.clear()
    }

    /** Run one Nearby call, reporting a revoked-permission refusal instead of propagating it. */
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
            /**
             * Auto-accepts any endpoint whose advertised name is an 8-char hex pubkey prefix.
             *
             * This is a UX decision, not an authentication step: the prefix is self-reported and
             * an attacker can advertise any prefix. Accepting here only opens a transport so the
             * channel-bound Schnorr handshake in [com.splitfree.sync.nearby.PeerSession] can run;
             * nothing is disclosed until mutual authentication and group authorization complete.
             * The connection role and raw authentication token are captured here so the session
             * can bind its signatures to this specific channel.
             */
            override fun onConnectionInitiated(endpointId: String, info: ConnectionInfo) {
                val name = info.endpointName
                // This runs on a Nearby callback thread; an escaping SecurityException
                // (permission revoked while connected) would kill the process.
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

        /** Buffered events before [MutableSharedFlow.tryEmit] starts failing; MSG_EVENT bursts can be large. */
        const val EVENT_BUFFER_CAPACITY = 1024

        /** Log every Nth drop rather than every drop. */
        private const val DROP_LOG_INTERVAL = 100L
    }

    private fun emitError(operation: String, throwable: Throwable) {
        val reason = throwable.message ?: throwable.javaClass.simpleName
        Log.w(TAG, "$operation failed: $reason")
        emitOrDrop(BleEvent.Error(operation, reason))
    }
}
