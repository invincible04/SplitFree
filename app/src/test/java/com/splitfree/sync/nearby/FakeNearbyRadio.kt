package com.splitfree.sync.nearby

import com.splitfree.data.ble.BleEvent
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * Controller test radio with test-settled start/request deferreds and ordered synchronous-call records.
 *
 * - [preSubmitGate] holds capability starts before submission.
 * - [advertisingOn] and [discoveringOn] conservatively stay true after submission regardless of outcome, until a stop;
 *   they are cleanup obligations, not SDK state.
 * - Tests inject lifecycle events explicitly: this fake does not enforce SDK callback ordering or the adapter's
 *   retirement policy, and disconnect/stop do not synthesize Disconnected events.
 */
class FakeNearbyRadio : NearbyRadio {
    /** One recorded synchronous call; [sequence] is shared across every kind of call. */
    data class Call(val sequence: Int, val operation: String, val endpointId: String? = null)

    override val events = MutableSharedFlow<BleEvent>(extraBufferCapacity = 1024)
    override val fault = MutableStateFlow<TransportFault?>(null)

    /** Recorded synchronous side effects in invocation order; ignored stale-capability calls are omitted. */
    val calls = mutableListOf<Call>()
    val stopAdvertisingCalls = mutableListOf<Call>()
    val stopDiscoveryCalls = mutableListOf<Call>()
    val stopAllEndpointsCalls = mutableListOf<Call>()
    val disconnectCalls = mutableListOf<Call>()
    val sendPayloadCalls = mutableListOf<Call>()

    /** Every start or request task in submission order, settled or not. */
    val advertisingTasks = mutableListOf<CompletableDeferred<RadioOutcome>>()
    val discoveryTasks = mutableListOf<CompletableDeferred<RadioOutcome>>()
    val requestTasks = mutableListOf<Pair<String, CompletableDeferred<RadioOutcome>>>()

    /** Outcomes queued ahead of time; a submission takes the head, or creates an unsettled task when empty. */
    val queuedAdvertising = ArrayDeque<CompletableDeferred<RadioOutcome>>()
    val queuedDiscovery = ArrayDeque<CompletableDeferred<RadioOutcome>>()
    val queuedRequests = ArrayDeque<CompletableDeferred<RadioOutcome>>()

    /** Invoked synchronously when the matching operation is submitted, before it suspends. */
    var onStartAdvertising: (() -> Unit)? = null
    var onStartDiscovery: (() -> Unit)? = null
    var onRequestConnection: ((String) -> Unit)? = null

    /** Conservative submitted-until-stopped flags, unaffected by the scripted task outcome. */
    var advertisingOn = false
    var discoveringOn = false

    /** When set, start operations suspend on it before they count as submitted. */
    var preSubmitGate: CompletableDeferred<Unit>? = null

    private var sequence = 0
    private val connections = mutableMapOf<String, NearbyConnection>()
    private val cancelledAttempts = mutableSetOf<NearbyConnectionAttempt>()
    private val submittedAttempts = mutableSetOf<NearbyConnectionAttempt>()
    private val attempts = mutableMapOf<String, NearbyConnectionAttempt>()

    fun connection(endpointId: String): NearbyConnection = checkNotNull(connections[endpointId])

    val pendingAdvertising: CompletableDeferred<RadioOutcome>
        get() = advertisingTasks.last { !it.isCompleted }

    val pendingDiscovery: CompletableDeferred<RadioOutcome>
        get() = discoveryTasks.last { !it.isCompleted }

    val pendingRequest: CompletableDeferred<RadioOutcome>
        get() = requestTasks.last { !it.second.isCompleted }.second

    fun pendingRequest(endpointId: String): CompletableDeferred<RadioOutcome> =
        requestTasks.last { it.first == endpointId && !it.second.isCompleted }.second

    fun requestCount(endpointId: String): Int = requestTasks.count { it.first == endpointId }

    fun emit(event: BleEvent) {
        if (event is BleEvent.Connected) connections[event.endpointId] = event.connection
        if (event is BleEvent.Disconnected) connections.remove(event.endpointId, event.connection)
        check(events.tryEmit(event)) { "event buffer full" }
    }

    override suspend fun startAdvertising(): RadioOutcome {
        preSubmitGate?.await()
        onStartAdvertising?.invoke()
        advertisingOn = true
        val task = queuedAdvertising.removeFirstOrNull() ?: CompletableDeferred()
        advertisingTasks += task
        return task.await()
    }

    override suspend fun startDiscovery(): RadioOutcome {
        preSubmitGate?.await()
        onStartDiscovery?.invoke()
        discoveringOn = true
        val task = queuedDiscovery.removeFirstOrNull() ?: CompletableDeferred()
        discoveryTasks += task
        return task.await()
    }

    override suspend fun requestConnection(attempt: NearbyConnectionAttempt): RadioOutcome {
        if (attempt in cancelledAttempts || !submittedAttempts.add(attempt)) return RadioOutcome.Cancelled
        val endpointId = attempt.endpointId
        attempts[endpointId] = attempt
        onRequestConnection?.invoke(endpointId)
        val task = queuedRequests.removeFirstOrNull() ?: CompletableDeferred()
        requestTasks += endpointId to task
        return task.await()
    }

    override fun stopAdvertising() {
        advertisingOn = false
        stopAdvertisingCalls += record("stopAdvertising")
    }

    override fun stopDiscovery() {
        discoveringOn = false
        stopDiscoveryCalls += record("stopDiscovery")
    }

    override fun stopAllEndpoints() {
        advertisingOn = false
        discoveringOn = false
        stopAllEndpointsCalls += record("stopAllEndpoints")
        fault.value = null
        cancelledAttempts += attempts.values
        attempts.clear()
        connections.clear()
    }

    override fun sendPayload(connection: NearbyConnection, data: ByteArray) {
        if (connections[connection.endpointId] !== connection) return
        sendPayloadCalls += record("sendPayload", connection.endpointId)
    }

    override fun disconnect(connection: NearbyConnection) {
        if (!connections.remove(connection.endpointId, connection)) return
        disconnectCalls += record("disconnect", connection.endpointId)
    }

    override fun cancelConnectionAttempt(attempt: NearbyConnectionAttempt) {
        cancelledAttempts += attempt
        val endpointId = attempt.endpointId
        if (attempts[endpointId] !== attempt || connections.containsKey(endpointId)) return
        attempts.remove(endpointId)
        disconnectCalls += record("disconnect", endpointId)
    }

    private fun record(operation: String, endpointId: String? = null): Call {
        val call = Call(++sequence, operation, endpointId)
        calls += call
        return call
    }
}
