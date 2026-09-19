package com.splitfree.data.ble

import android.content.Context
import com.google.android.gms.common.api.ApiException
import com.google.android.gms.nearby.Nearby
import com.google.android.gms.nearby.connection.AdvertisingOptions
import com.google.android.gms.nearby.connection.ConnectionInfo
import com.google.android.gms.nearby.connection.ConnectionLifecycleCallback
import com.google.android.gms.nearby.connection.ConnectionResolution
import com.google.android.gms.nearby.connection.ConnectionsClient
import com.google.android.gms.nearby.connection.ConnectionsStatusCodes
import com.google.android.gms.nearby.connection.DiscoveredEndpointInfo
import com.google.android.gms.nearby.connection.DiscoveryOptions
import com.google.android.gms.nearby.connection.EndpointDiscoveryCallback
import com.google.android.gms.nearby.connection.Payload
import com.google.android.gms.nearby.connection.PayloadCallback
import com.google.android.gms.nearby.connection.PayloadTransferUpdate
import com.google.android.gms.nearby.connection.Strategy
import com.google.android.gms.tasks.Task
import com.splitfree.data.identity.IdentityManager
import com.splitfree.sync.nearby.NearbyConnection
import com.splitfree.sync.nearby.NearbyConnectionAttempt
import com.splitfree.sync.nearby.NearbyRadio
import com.splitfree.sync.nearby.RadioFailureKind
import com.splitfree.sync.nearby.RadioOutcome
import com.splitfree.sync.nearby.TransportFault
import com.splitfree.util.DebugLog as Log
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.suspendCancellableCoroutine

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
     * @property connection local object-identity capability for this link, not the SDK channel token
     * @property isIncoming local Nearby connection role used to select the protocol initiator
     * @property authToken SDK channel-binding bytes captured at initiation, or null; not proof of peer identity
     * @property endpointName the unverified name presented at initiation, or null when unavailable
     */
    data class Connected(
        val connection: NearbyConnection,
        val isIncoming: Boolean = false,
        val authToken: ByteArray? = null,
        val endpointName: String? = null
    ) : BleEvent() {
        val endpointId: String get() = connection.endpointId
    }

    /** A connection attempt to or from [endpointId] ended without a connection. */
    data class ConnectionFailed(
        val endpointId: String,
        val kind: RadioFailureKind,
        val statusCode: Int?,
        val reason: String
    ) : BleEvent()

    /** The link identified by [connection] ended; its session requires cleanup. */
    data class Disconnected(val connection: NearbyConnection) : BleEvent() {
        val endpointId: String get() = connection.endpointId
    }

    /** Raw byte payload awaiting protocol decoding and validation. */
    data class PayloadReceived(val connection: NearbyConnection, val data: ByteArray) : BleEvent() {
        val endpointId: String get() = connection.endpointId
    }

    /**
     * Operation failure with diagnostic text and optional endpoint id.
     *
     * - This event carries no link capability, so the endpoint id alone does not identify a connection incarnation.
     * - [kind] is classified when known.
     */
    data class Error(
        val operation: String,
        val reason: String,
        val endpointId: String? = null,
        val kind: RadioFailureKind? = null
    ) : BleEvent()
}

/**
 * Google Nearby Connections byte transport using P2P_CLUSTER, without application authentication.
 *
 * - Endpoint names must have the eight-character lowercase hex shape; only the session handshake proves identity and
 *   group authorization.
 * - The SDK channel token binds that handshake when available; it is distinct from the local [NearbyConnection]
 *   capability required for sends and disconnections.
 * - Link mutations, capability checks and their SDK calls share [lock].
 * - Payload callbacks and accept/send task listeners retain a link identity; lifecycle callbacks identify an
 *   advertising or request submission.
 * - [stopAllEndpoints] clears local ownership and advances the run generation.
 * - Stale initiations and orphan successes trigger best-effort rejection or disconnection only if another submission
 *   does not own the endpoint.
 * - Incoming links share their advertising submission's endpoint-only terminal callbacks.
 * - Local retirement refuses reuse through that submission until a disconnection or failed result removes the
 *   retirement.
 * - That callback cannot identify which connection incarnation ended: a rejected new initiation's failure can consume
 *   an older retirement.
 * - Same-submission reuse therefore still depends on SDK callback ordering; retirement does not fence arbitrary
 *   ordering.
 * - A new request or advertising submission has a distinct owner.
 * - Start/request outcomes report SDK task settlement, not completion of a connection handshake.
 * - Local stop or attempt cancellation does not settle those tasks; the run owner must await submitted tasks before
 *   allowing the next run.
 * - Cancelling a waiting coroutine does not cancel its SDK task or detach its listener.
 * - Subscribe to [events] before starting: there is no replay.
 * - A full subscriber buffer counts the loss and publishes [fault]; emissions with no subscribers are discarded
 *   without a fault.
 * - [endLink] attempts one [BleEvent.Disconnected] emission for a connected link; this is not a lossless delivery
 *   guarantee.
 */
@Singleton
class NearbySync
@Inject
constructor(
    @ApplicationContext private val context: Context,
    private val identity: IdentityManager
) : NearbyRadio {
    private val client: ConnectionsClient by lazy { Nearby.getConnectionsClient(context) }
    private val _events = MutableSharedFlow<BleEvent>(extraBufferCapacity = EVENT_BUFFER_CAPACITY)
    override val events: SharedFlow<BleEvent> = _events

    private val _fault = MutableStateFlow<TransportFault?>(null)
    override val fault: StateFlow<TransportFault?> = _fault

    /** Counts full-buffer drops only; events emitted without subscribers are not counted. */
    val droppedEvents = AtomicLong(0)

    /** Set by start or request submission and cleared by [stopAllEndpoints]; permits current initiations. */
    @Volatile
    private var active = false

    /** Enables discovery callbacks until a non-ALREADY_ACTIVE start failure, SDK task cancellation, or stop. */
    @Volatile
    private var discovering = false

    /** Local start/stop bookkeeping, not observed hardware state; initiations use [active] instead. */
    @Volatile
    private var advertising = false

    /** Number of run boundaries so far; callbacks compare it with the value at their submission. */
    private val generation = AtomicLong(0)

    /** Identifies advertising and request submissions; discovery callbacks use only the run generation. */
    private val submissions = AtomicLong(0)

    /** Serializes link changes and their SDK calls; not held while suspending for task completion. */
    private val lock = Any()

    /**
     * Per-endpoint count of local disconnections.
     *
     * - A request callback remembers the value at submission and treats an initiation delivered after
     *   [cancelConnectionAttempt] or [disconnect] advanced it as abandoned.
     * - Guarded by [lock].
     */
    private val epochs = HashMap<String, Long>()

    /** Current submitted request per endpoint, including before initiation installs a link; guarded by [lock]. */
    private val requests = HashMap<String, NearbyConnectionAttempt>()

    /**
     * Local ownership from accepted initiation until removal, with a fresh capability per incarnation.
     *
     * - [submission] distinguishes lifecycle callback owners, not successive links on one advertising callback.
     * - [viaAdvertising] selects the retirement policy for locally ended incoming links.
     */
    private class Link(
        val endpointId: String,
        val submission: Long,
        val viaAdvertising: Boolean,
        val isIncoming: Boolean,
        val authToken: ByteArray?,
        val name: String
    ) {
        val connection = NearbyConnection(endpointId)

        @Volatile
        var connected = false
    }

    /** Written under [lock]; every link side effect validates its connection identity under the same lock. */
    private val links = ConcurrentHashMap<String, Link>()

    /** Same-submission reuse barrier after a local end; removed by the next matching terminal callback. */
    private data class Retirement(val submission: Long, val endpointId: String)

    /** Guarded by [lock]; endpoint-only terminal callbacks cannot prove which incarnation clears a barrier. */
    private val retired = HashSet<Retirement>()

    /**
     * Keeps callbacks nonblocking.
     *
     * - A full buffer counts the drop, logs with rate limiting and publishes a [TransportFault] so the run owner can
     *   end the run explicitly instead of continuing with lost events.
     */
    private fun emitOrDrop(event: BleEvent) {
        if (_events.tryEmit(event)) return
        val dropped = droppedEvents.incrementAndGet()
        if (dropped % DROP_LOG_INTERVAL == 1L) {
            Log.w(TAG, "Event buffer full: $dropped dropped so far (last: ${event::class.simpleName})")
        }
        _fault.value = TransportFault("event_overflow", event::class.simpleName ?: "event", dropped)
    }

    override suspend fun startAdvertising(): RadioOutcome = awaitTask(
        operation = "advertise",
        onSettled = { if (!keepsCapability(it)) advertising = false }
    ) {
        active = true
        advertising = true
        val options = AdvertisingOptions.Builder().setStrategy(Strategy.P2P_CLUSTER).build()
        val callback = lifecycleCallback(submissions.incrementAndGet(), outgoing = null)
        client.startAdvertising(identity.getPublicKeyHex().take(8), SERVICE_ID, callback, options)
    }

    override suspend fun startDiscovery(): RadioOutcome = awaitTask(
        operation = "discovery",
        onSettled = { if (!keepsCapability(it)) discovering = false }
    ) {
        active = true
        discovering = true
        val options = DiscoveryOptions.Builder().setStrategy(Strategy.P2P_CLUSTER).build()
        client.startDiscovery(SERVICE_ID, discoveryCallback(generation.get()), options)
    }

    override suspend fun requestConnection(attempt: NearbyConnectionAttempt): RadioOutcome {
        var submitted = false
        return awaitTask(
            operation = "request_connection",
            onSettled = {
                if (submitted && it !is RadioOutcome.Success) {
                    try {
                        cancelConnectionAttempt(attempt)
                    } catch (e: Exception) {
                        // Local ownership is revoked before SDK cleanup; its failure must not lose the Task outcome.
                        Log.w(TAG, "Request cleanup failed for ${attempt.endpointId}: ${e.message}")
                    }
                }
            }
        ) {
            synchronized(lock) {
                if (attempt.cancelled || attempt.submission != null) return@synchronized null
                active = true
                val submission = submissions.incrementAndGet()
                attempt.submission = submission
                submitted = true
                requests[attempt.endpointId] = attempt
                val outgoing = Outgoing(attempt, epochOf(attempt.endpointId))
                val callback = lifecycleCallback(submission, outgoing)
                try {
                    client.requestConnection(identity.getPublicKeyHex().take(8), attempt.endpointId, callback)
                } catch (e: Exception) {
                    val link = links[attempt.endpointId]
                    if (link == null || link.submission != submission || !link.connected) {
                        attempt.cancelled = true
                        requests.remove(attempt.endpointId, attempt)
                        if (link?.submission == submission) endLink(link, "request submission failed")
                    }
                    throw e
                }
            }
        }
    }

    /** Outcomes that preserve local capability bookkeeping; not a check of continued SDK activity. */
    private fun keepsCapability(outcome: RadioOutcome): Boolean = outcome is RadioOutcome.Success ||
        (outcome is RadioOutcome.Failure && outcome.kind == RadioFailureKind.ALREADY_ACTIVE)

    /**
     * Awaits a submitted SDK task; a null submission returns Cancelled without a task.
     *
     * - Submission-time SecurityException becomes a permission failure; other submission exceptions propagate.
     * - [onSettled] runs before returning an outcome, or on the SDK completion callback even if the waiter was
     *   cancelled.
     * - Cancellation does not cancel the SDK task.
     * - The run owner keeps waiters alive through cleanup to observe actual settlement.
     * - Repeated listener invocation still runs [onSettled]; inactive waiters are not resumed.
     */
    private suspend fun awaitTask(
        operation: String,
        onSettled: (RadioOutcome) -> Unit = {},
        submit: () -> Task<Void>?
    ): RadioOutcome {
        val task =
            try {
                submit()
            } catch (e: SecurityException) {
                Log.w(TAG, "$operation refused: ${e.message}")
                val outcome = RadioOutcome.Failure(RadioFailureKind.PERMISSION, null, e.message ?: "SecurityException")
                onSettled(outcome)
                return outcome
            }
        if (task == null) {
            onSettled(RadioOutcome.Cancelled)
            return RadioOutcome.Cancelled
        }
        return suspendCancellableCoroutine { continuation ->
            task.addOnCompleteListener { settled ->
                val outcome =
                    when {
                        settled.isSuccessful -> RadioOutcome.Success
                        settled.isCanceled -> RadioOutcome.Cancelled
                        else -> failure(settled.exception)
                    }
                if (outcome !is RadioOutcome.Success) Log.w(TAG, "$operation failed: $outcome")
                onSettled(outcome)
                if (continuation.isActive) continuation.resume(outcome)
            }
        }
    }

    private fun failure(throwable: Throwable?): RadioOutcome.Failure {
        val code = (throwable as? ApiException)?.statusCode
        val message = throwable?.message ?: throwable?.javaClass?.simpleName ?: "unknown"
        return RadioOutcome.Failure(classify(code, throwable), code, message)
    }

    override fun sendPayload(connection: NearbyConnection, data: ByteArray) {
        synchronized(lock) {
            val endpointId = connection.endpointId
            val link = links[endpointId]
            if (link == null || link.connection !== connection || !link.connected) return
            try {
                client.sendPayload(endpointId, Payload.fromBytes(data)).addOnFailureListener { throwable ->
                    synchronized(lock) {
                        if (links[endpointId] !== link) {
                            Log.d(TAG, "Dropping send failure for an ended link with $endpointId")
                            return@addOnFailureListener
                        }
                        val kind = emitError("send_payload", throwable, endpointId)
                        if (kind == RadioFailureKind.ENDPOINT) {
                            Log.w(TAG, "Send to $endpointId refused as not connected; ending the link")
                            endLocally(link, "send failure")
                            quietly("disconnect", endpointId) { client.disconnectFromEndpoint(endpointId) }
                        }
                    }
                }
            } catch (e: SecurityException) {
                emitError("send_payload", e, endpointId)
            }
        }
    }

    override fun stopAdvertising() {
        advertising = false
        guarded("stop_advertising") { client.stopAdvertising() }
    }

    /** Stops discovery while leaving advertising and connected endpoints active. */
    override fun stopDiscovery() {
        discovering = false
        guarded("stop_discovery") { client.stopDiscovery() }
    }

    /**
     * Advances the run boundary and clears local link, request and retirement ownership before SDK cleanup.
     *
     * - Connected links attempt a Disconnected emission; pending links are removed silently.
     * - This does not await outstanding SDK tasks or stop advertising/discovery at the SDK; the run owner handles
     *   those steps.
     * - [fault] is cleared after the SDK call returns, including when a SecurityException was reported as an event.
     */
    override fun stopAllEndpoints() {
        synchronized(lock) {
            active = false
            advertising = false
            discovering = false
            val boundary = generation.incrementAndGet()
            links.values.toList().forEach { endLink(it, "run boundary") }
            retired.clear()
            epochs.clear()
            requests.clear()
            Log.d(TAG, "Run boundary $boundary: endpoints forgotten")
            guarded("stop_all_endpoints") { client.stopAllEndpoints() }
        }
        _fault.value = null
    }

    /** Validates the session's link identity atomically with local retirement and the SDK disconnect. */
    override fun disconnect(connection: NearbyConnection) {
        synchronized(lock) {
            val link = links[connection.endpointId] ?: return
            if (link.connection !== connection) return
            disconnectLocked(link.endpointId)
        }
    }

    /** An attempt timeout/cancel cannot disconnect a link whose Connected event is still queued. */
    override fun cancelConnectionAttempt(attempt: NearbyConnectionAttempt) {
        synchronized(lock) {
            val endpointId = attempt.endpointId
            val link = links[endpointId]
            if (link != null && link.submission == attempt.submission && link.connected) return
            // A cancelled attempt never keeps a link: its pending link ends here and its callback refuses new ones.
            attempt.cancelled = true
            if (link?.submission == attempt.submission && link != null) endLink(link, "cancelled attempt")
            if (!requests.remove(endpointId, attempt)) return
            if (link != null && link.submission != attempt.submission) return
            disconnectLocked(endpointId)
        }
    }

    private fun disconnectLocked(endpointId: String) {
        epochs.merge(endpointId, 1L, Long::plus)
        links[endpointId]?.let { endLocally(it, "local disconnect") }
        guarded("disconnect", endpointId) { client.disconnectFromEndpoint(endpointId) }
    }

    /**
     * Requests advertising, discovery and endpoint cleanup in order.
     *
     * - Each SecurityException is reported independently so later steps still run; other exceptions propagate.
     * - Outstanding SDK tasks are not awaited.
     */
    fun stop() {
        stopAdvertising()
        stopDiscovery()
        stopAllEndpoints()
    }

    private fun epochOf(endpointId: String): Long = epochs[endpointId] ?: 0L

    /**
     * Removes only the current [link], attempting one Disconnected emission if it had connected.
     *
     * - Pending links are removed silently; callers or attempt timeouts supply any remaining outcome.
     */
    private fun endLink(link: Link, cause: String) {
        if (links.remove(link.endpointId, link)) {
            Log.d(TAG, "Link with ${link.endpointId} ended: $cause")
            if (link.connected) emitOrDrop(BleEvent.Disconnected(link.connection))
        }
    }

    /**
     * Ends [link] locally and blocks same-advertising-submission reuse until a terminal callback arrives.
     *
     * - The barrier records local policy, not proof that the SDK has finished disconnecting this incarnation.
     */
    private fun endLocally(link: Link, cause: String) {
        endLink(link, cause)
        if (link.viaAdvertising) retired += Retirement(link.submission, link.endpointId)
    }

    /**
     * Consumes a matching retirement on a disconnection or failed result.
     *
     * - The callback has no incarnation id: a rejected successor's failure can remove an older link's barrier before
     *   that older link's terminal event.
     */
    private fun confirmsRetirement(submission: Long, endpointId: String, what: String): Boolean {
        if (!retired.remove(Retirement(submission, endpointId))) return false
        Log.d(TAG, "Platform $what confirms the locally ended link with $endpointId")
        return true
    }

    /** Converts permission failures of a locally requested operation into [BleEvent.Error] events. */
    private inline fun guarded(operation: String, endpointId: String? = null, block: () -> Unit) {
        try {
            block()
        } catch (e: SecurityException) {
            emitError(operation, e, endpointId)
        }
    }

    /**
     * Runs best-effort SDK cleanup without emitting an event.
     *
     * - SecurityException and asynchronous task failures are logged; other synchronous exceptions propagate.
     * - Callers decide whether cleanup is safe.
     */
    private inline fun quietly(operation: String, endpointId: String, block: () -> Any?) {
        try {
            (block() as? Task<*>)?.addOnFailureListener {
                Log.w(TAG, "$operation for $endpointId failed: ${it.message}")
            }
        } catch (e: SecurityException) {
            Log.w(TAG, "$operation for $endpointId refused: ${e.message}")
        }
    }

    /** Discovery callback for one [startDiscovery] submission; callbacks from an earlier generation are dropped. */
    private fun discoveryCallback(boundary: Long) = object : EndpointDiscoveryCallback() {
        override fun onEndpointFound(endpointId: String, info: DiscoveredEndpointInfo) {
            if (!discovering || boundary != generation.get()) {
                Log.d(TAG, "Dropping endpoint found for $endpointId: discovery is off")
                return
            }
            if (info.serviceId == SERVICE_ID) {
                emitOrDrop(BleEvent.PeerFound(NearbyPeer(endpointId, info.endpointName)))
            }
        }

        override fun onEndpointLost(endpointId: String) {
            if (!discovering || boundary != generation.get()) {
                Log.d(TAG, "Dropping endpoint lost for $endpointId: discovery is off")
                return
            }
            emitOrDrop(BleEvent.PeerLost(endpointId))
        }
    }

    /** The endpoint a [requestConnection] submission targets and the epoch the endpoint had at that time. */
    private class Outgoing(val attempt: NearbyConnectionAttempt, val epoch: Long)

    /**
     * Lifecycle owner for one advertising or request submission, serialized under [lock].
     *
     * - Results and disconnections cannot settle another submission's link.
     * - Successive incoming links share this callback, so its endpoint-only terminal events cannot distinguish their
     *   incarnations.
     */
    private fun lifecycleCallback(submission: Long, outgoing: Outgoing?) = object : ConnectionLifecycleCallback() {
        private val boundary = generation.get()

        private fun stale(): Boolean = !active || boundary != generation.get()

        private fun hasOtherOwner(endpointId: String): Boolean =
            links[endpointId]?.let { it.submission != submission } == true ||
                requests[endpointId]?.let { it.submission != submission } == true

        private fun rejectIfUnowned(endpointId: String) {
            if (!hasOtherOwner(endpointId)) {
                quietly("reject_connection", endpointId) { client.rejectConnection(endpointId) }
            }
        }

        override fun onConnectionInitiated(endpointId: String, info: ConnectionInfo) {
            synchronized(lock) {
                if (stale()) {
                    Log.d(TAG, "Rejecting connection with $endpointId: no active run (boundary ${generation.get()})")
                    rejectIfUnowned(endpointId)
                    return
                }
                if (outgoing != null &&
                    (
                        outgoing.attempt.endpointId != endpointId ||
                            outgoing.attempt.cancelled ||
                            requests[endpointId] !== outgoing.attempt ||
                            outgoing.epoch != epochOf(endpointId)
                        )
                ) {
                    Log.d(TAG, "Rejecting connection with $endpointId: the request was abandoned")
                    rejectIfUnowned(endpointId)
                    return
                }
                if (outgoing == null && Retirement(submission, endpointId) in retired) {
                    Log.i(TAG, "Rejecting connection with $endpointId: its earlier link ended locally, unconfirmed")
                    rejectIfUnowned(endpointId)
                    return
                }
                val name = info.endpointName
                if (!isAcceptableName(name)) {
                    Log.w(TAG, "Rejecting connection with $endpointId: invalid endpoint name (length ${name.length})")
                    // Permission revocation must remain contained within the transport callback.
                    try {
                        client.rejectConnection(endpointId).addOnFailureListener {
                            emitError("reject_connection", it, endpointId)
                        }
                    } catch (e: SecurityException) {
                        emitError("reject_connection", e, endpointId)
                    }
                    emitOrDrop(
                        BleEvent.ConnectionFailed(endpointId, RadioFailureKind.ENDPOINT, null, "invalid endpoint name")
                    )
                    return
                }
                links[endpointId]?.let { endLink(it, "replaced by a new initiation") }
                val link =
                    Link(
                        endpointId,
                        submission,
                        viaAdvertising = outgoing == null,
                        isIncoming = info.isIncomingConnection,
                        authToken = info.rawAuthenticationToken,
                        name = name
                    )
                links[endpointId] = link
                try {
                    client.acceptConnection(endpointId, payloadCallback(link)).addOnFailureListener {
                        synchronized(lock) {
                            if (links.remove(endpointId, link)) emitConnectionFailed(endpointId, it)
                        }
                    }
                } catch (e: SecurityException) {
                    links.remove(endpointId, link)
                    Log.w(TAG, "connection_initiated refused for $endpointId: ${e.message}")
                    emitOrDrop(
                        BleEvent.ConnectionFailed(
                            endpointId,
                            RadioFailureKind.PERMISSION,
                            null,
                            e.message ?: "SecurityException"
                        )
                    )
                }
            }
        }

        override fun onConnectionResult(endpointId: String, result: ConnectionResolution) {
            synchronized(lock) {
                val status = result.status
                val confirms = outgoing == null && !status.isSuccess
                if (confirms && confirmsRetirement(submission, endpointId, "result")) {
                    return
                }
                val link = links[endpointId]
                if (link == null) {
                    if (status.isSuccess && !hasOtherOwner(endpointId)) {
                        Log.w(TAG, "Closing orphan connection with $endpointId: no link for it")
                        quietly("disconnect", endpointId) { client.disconnectFromEndpoint(endpointId) }
                    } else {
                        Log.d(
                            TAG,
                            "Dropping connection result for unknown endpoint $endpointId: status=${status.statusCode}"
                        )
                    }
                    return
                }
                if (link.submission != submission) {
                    Log.d(TAG, "Dropping connection result from an earlier submission for $endpointId")
                    return
                }
                if (!status.isSuccess) {
                    val code = status.statusCode
                    val reason = status.statusMessage ?: "status=$code"
                    Log.w(TAG, "Connection failed for $endpointId: $reason")
                    if (links.remove(endpointId, link)) {
                        emitOrDrop(BleEvent.ConnectionFailed(endpointId, classify(code, null), code, reason))
                    }
                    return
                }
                if (link.connected) {
                    Log.w(TAG, "Ignoring repeated connection result for connected endpoint $endpointId")
                    return
                }
                link.connected = true
                emitOrDrop(BleEvent.Connected(link.connection, link.isIncoming, link.authToken, link.name))
            }
        }

        override fun onDisconnected(endpointId: String) {
            synchronized(lock) {
                if (outgoing == null && confirmsRetirement(submission, endpointId, "disconnection")) return
                val link = links[endpointId]
                if (link == null || link.submission != submission) {
                    Log.d(TAG, "Dropping disconnection of $endpointId: not this submission's link")
                    return
                }
                endLink(link, "platform disconnection")
            }
        }
    }

    /** Payload callback for one accepted [link]; frames for any other link with the endpoint are dropped. */
    private fun payloadCallback(link: Link) = object : PayloadCallback() {
        private fun current(endpointId: String): Boolean =
            endpointId == link.endpointId && links[endpointId] === link && link.connected

        override fun onPayloadReceived(endpointId: String, payload: Payload) {
            synchronized(lock) {
                if (!current(endpointId)) {
                    Log.d(TAG, "Dropping payload from $endpointId: not the connected link")
                    return
                }
                payload.asBytes()?.let { emitOrDrop(BleEvent.PayloadReceived(link.connection, it)) }
            }
        }

        override fun onPayloadTransferUpdate(endpointId: String, update: PayloadTransferUpdate) {
            val statusName =
                when (update.status) {
                    PayloadTransferUpdate.Status.FAILURE -> "FAILURE"
                    PayloadTransferUpdate.Status.CANCELED -> "CANCELED"
                    else -> return
                }
            synchronized(lock) {
                if (!current(endpointId)) {
                    Log.d(TAG, "Dropping payload transfer $statusName for $endpointId: not the connected link")
                    return
                }
                Log.w(TAG, "Payload transfer with $endpointId ended: $statusName")
                emitOrDrop(
                    BleEvent.Error("payload_transfer", "status=$statusName", endpointId, RadioFailureKind.PAYLOAD)
                )
            }
        }
    }

    /** Reports a connection attempt that ended because the platform refused or failed the accept call. */
    private fun emitConnectionFailed(endpointId: String, throwable: Throwable) {
        val code = (throwable as? ApiException)?.statusCode
        val reason = throwable.message ?: throwable.javaClass.simpleName
        Log.w(TAG, "Connection attempt with $endpointId failed: $reason")
        emitOrDrop(BleEvent.ConnectionFailed(endpointId, classify(code, throwable), code, reason))
    }

    companion object {
        private const val TAG = "NearbySync"

        /** Nearby service id shared by advertising and discovery; endpoints under other ids are ignored. */
        internal const val SERVICE_ID = "com.splitfree.ble"

        /** Checks the advertised key-prefix shape only; a remote name is not authenticated here. */
        private fun isAcceptableName(name: String): Boolean = name.length == 8 && name.all { it in "0123456789abcdef" }

        /** Buffer capacity for slow subscribers; events have no replay when no subscriber is present. */
        const val EVENT_BUFFER_CAPACITY = 1024

        /** Number of drops between diagnostics, starting with the first drop. */
        private const val DROP_LOG_INTERVAL = 100L

        // Google no longer returns these codes and offers no replacements. Keep their existing
        // classifications if received; suppress deprecation only at these compatibility aliases.
        @Suppress("DEPRECATION")
        private const val LEGACY_LOCATION_SETTING_REQUIRED = ConnectionsStatusCodes.MISSING_SETTING_LOCATION_MUST_BE_ON

        @Suppress("DEPRECATION")
        private const val LEGACY_NETWORK_NOT_CONNECTED = ConnectionsStatusCodes.STATUS_NETWORK_NOT_CONNECTED

        /** Maps a Nearby Connections status code, or the exception carrying it, onto a failure category. */
        fun classify(statusCode: Int?, throwable: Throwable?): RadioFailureKind = when (statusCode) {
            ConnectionsStatusCodes.STATUS_ALREADY_ADVERTISING,
            ConnectionsStatusCodes.STATUS_ALREADY_DISCOVERING,
            ConnectionsStatusCodes.STATUS_ALREADY_CONNECTED_TO_ENDPOINT -> RadioFailureKind.ALREADY_ACTIVE

            ConnectionsStatusCodes.MISSING_PERMISSION_NEARBY_WIFI_DEVICES,
            ConnectionsStatusCodes.MISSING_PERMISSION_BLUETOOTH,
            ConnectionsStatusCodes.MISSING_PERMISSION_BLUETOOTH_ADMIN,
            ConnectionsStatusCodes.MISSING_PERMISSION_ACCESS_WIFI_STATE,
            ConnectionsStatusCodes.MISSING_PERMISSION_CHANGE_WIFI_STATE,
            ConnectionsStatusCodes.MISSING_PERMISSION_ACCESS_COARSE_LOCATION,
            ConnectionsStatusCodes.MISSING_PERMISSION_ACCESS_FINE_LOCATION,
            ConnectionsStatusCodes.MISSING_PERMISSION_BLUETOOTH_SCAN,
            ConnectionsStatusCodes.MISSING_PERMISSION_BLUETOOTH_ADVERTISE,
            ConnectionsStatusCodes.MISSING_PERMISSION_BLUETOOTH_CONNECT -> RadioFailureKind.PERMISSION

            LEGACY_LOCATION_SETTING_REQUIRED -> RadioFailureKind.LOCATION_SETTING

            ConnectionsStatusCodes.STATUS_RADIO_ERROR,
            ConnectionsStatusCodes.STATUS_ALREADY_HAVE_ACTIVE_STRATEGY,
            ConnectionsStatusCodes.STATUS_OUT_OF_ORDER_API_CALL,
            ConnectionsStatusCodes.STATUS_ERROR -> RadioFailureKind.RADIO

            ConnectionsStatusCodes.STATUS_CONNECTION_REJECTED,
            ConnectionsStatusCodes.STATUS_NOT_CONNECTED_TO_ENDPOINT,
            ConnectionsStatusCodes.STATUS_ENDPOINT_UNKNOWN,
            ConnectionsStatusCodes.STATUS_ENDPOINT_IO_ERROR -> RadioFailureKind.ENDPOINT

            ConnectionsStatusCodes.STATUS_PAYLOAD_IO_ERROR -> RadioFailureKind.PAYLOAD

            LEGACY_NETWORK_NOT_CONNECTED,
            ConnectionsStatusCodes.API_CONNECTION_FAILED_ALREADY_IN_USE -> RadioFailureKind.SERVICE

            else -> if (throwable is SecurityException) RadioFailureKind.PERMISSION else RadioFailureKind.UNKNOWN
        }
    }

    /** Emits [BleEvent.Error] for a locally requested operation and returns the failure's classification. */
    private fun emitError(operation: String, throwable: Throwable, endpointId: String? = null): RadioFailureKind {
        val reason = throwable.message ?: throwable.javaClass.simpleName
        Log.w(TAG, "$operation failed: $reason")
        val code = (throwable as? ApiException)?.statusCode
        val kind = classify(code, throwable)
        emitOrDrop(BleEvent.Error(operation, reason, endpointId, kind))
        return kind
    }
}
