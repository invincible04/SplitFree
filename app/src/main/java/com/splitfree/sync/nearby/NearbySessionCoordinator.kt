package com.splitfree.sync.nearby

import com.splitfree.data.ble.BleEvent
import com.splitfree.di.ApplicationScope
import com.splitfree.domain.repository.IdentityContract
import com.splitfree.util.DebugLog as Log
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.onSubscription
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Owns peer sessions for one active group.
 *
 * - Session entry points, timers and store-change handling share one mutex; durable records survive cleanup.
 * - [NearbySessionController] owns radio startup and stop order.
 * - The application-scope transport collector awaits each event's run-owned work before reading the next event.
 * - [deactivate] cancels that work before acquiring the mutex, allowing cooperative store waits to unwind without
 *   cancelling the lifetime collector.
 * - Non-cooperative work can still delay cleanup.
 * - One live session owns each endpoint id.
 * - Replacement retires the old session silently, and payloads and disconnections are matched by [NearbyConnection]
 *   identity.
 * - The transport must also fence sends and disconnects so an old session cannot affect a link whose replacement
 *   events have not yet been collected.
 */
@Singleton
class NearbySessionCoordinator(
    private val transport: NearbyTransport,
    private val identity: IdentityContract,
    private val store: ReconciliationStore,
    private val appScope: CoroutineScope,
    private val clock: () -> Long
) : PeerSession.Listener {
    @Inject
    constructor(
        transport: NearbyTransport,
        identity: IdentityContract,
        store: ReconciliationStore,
        @ApplicationScope appScope: CoroutineScope
    ) : this(transport, identity, store, appScope, System::currentTimeMillis)

    private val mutex = Mutex()
    private val sessions = LinkedHashMap<String, PeerSession>()
    private var generation = 0L
    private var activeGroupId: String? = null

    @Volatile
    private var changeObserver: Job? = null

    @Volatile
    private var sessionParent: Job? = null

    private val _state = MutableStateFlow(NearbySessionsState())
    val state: StateFlow<NearbySessionsState> = _state.asStateFlow()

    /** Completes once the transport collector is subscribed; fails if the collector ends before that. */
    private val eventsSubscribed = CompletableDeferred<Unit>()

    init {
        val collector =
            appScope.launch {
                transport.events
                    .onSubscription { eventsSubscribed.complete(Unit) }
                    .collect { event -> onTransportEvent(event) }
            }
        collector.invokeOnCompletion {
            eventsSubscribed.completeExceptionally(
                IllegalStateException("Transport collector ended before subscribing")
            )
        }
    }

    /**
     * Prepares [groupId] after closing any previous run.
     *
     * - Returns after best-effort maintenance and transport subscription readiness; starting radios afterwards avoids
     *   a no-subscriber startup gap.
     * - An already-active same-group run is reused.
     * - Failure or cancellation after preparation begins rolls it back; cancellation while waiting to acquire the
     *   mutex leaves the existing run alone.
     */
    suspend fun activate(groupId: String) {
        mutex.withLock {
            if (activeGroupId == groupId && sessionParent?.isActive == true) return
            try {
                closeAllLocked(NearbyWire.CLOSE_STOPPED)
                maintainLocked(groupId)
                activeGroupId = groupId
                sessionParent = SupervisorJob(appScope.coroutineContext[Job])
                changeObserver = appScope.launch { observeStore(groupId) }
                _state.value = NearbySessionsState(active = true, groupId = groupId)
                eventsSubscribed.await()
            } catch (e: Throwable) {
                withContext(NonCancellable) { closeAllLocked(NearbyWire.CLOSE_STOPPED) }
                throw e
            }
        }
    }

    /**
     * Cancels run work and the store observer before acquiring the mutex, then attempts [Close] frames and joins
     * cleanup with caller cancellation suppressed.
     *
     * - Cooperative store waits release the mutex; blocking or non-cancellable work can still delay return.
     * - Repeated calls are idempotent while inactive.
     * - Lifecycle callers must sequence the next [activate] after this returns, as the controller's cleanup boundary
     *   does.
     */
    suspend fun deactivate() {
        changeObserver?.cancel()
        sessionParent?.cancel()
        withContext(NonCancellable) {
            mutex.withLock { closeAllLocked(NearbyWire.CLOSE_STOPPED) }
        }
    }

    /**
     * Schedules re-advertisement after an external data change; ignored unless [groupId] is active.
     *
     * - The work belongs to the active run, so [deactivate] cancels it.
     */
    fun notifyGroupChanged(groupId: String) {
        val parent = sessionParent?.takeIf { it.isActive } ?: return
        CoroutineScope(appScope.coroutineContext + parent).launch {
            mutex.withLock {
                if (activeGroupId == groupId) sessions.values.toList().forEach { it.markDirty() }
            }
        }
    }

    /** Best-effort housekeeping before peers are admitted; ordinary failures are logged, cancellation propagates. */
    private suspend fun maintainLocked(groupId: String) {
        try {
            store.prune()
            // Dependencies can arrive through other sync paths; retry durable work before opening peers.
            val retried = store.retryDeferred(groupId)
            if (retried > 0) Log.i(TAG, "Applied $retried pending record(s) for $groupId at activation")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Activation maintenance failed: ${e.message}")
        }
    }

    private suspend fun observeStore(groupId: String) {
        // Inventory, membership and apply-state changes must reach every active session.
        store.observeChanges(groupId).distinctUntilChanged().collect {
            mutex.withLock {
                try {
                    store.retryDeferred(groupId)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.w(TAG, "Store change recovery failed: ${e.message}")
                }
                // Each session contains its own failures; recovery failure must not skip pending-state refresh.
                sessions.values.toList().forEach { it.markDirty() }
            }
        }
    }

    private suspend fun onTransportEvent(event: BleEvent) {
        when (event) {
            is BleEvent.Connected -> {
                // A connection the active run does not take, or stops taking, is closed so no endpoint
                // stays connected without a session to drive it.
                if (!runOwned { openSessionLocked(event) }) disconnectQuietly(event.connection)
            }

            is BleEvent.Disconnected -> runOwned {
                sessions[event.endpointId]?.takeIf { it.connection === event.connection }?.onTransportDisconnected()
            }
            is BleEvent.PayloadReceived -> runOwned {
                sessions[event.endpointId]?.takeIf { it.connection === event.connection }?.onFrame(event.data)
            }
            // The transport owns the connection-ended transition and reports it as Disconnected, including
            // for a send it refuses as not connected; errors are left to receipts and timeouts.
            is BleEvent.Error -> Unit

            // Attempt outcomes belong to the run owner; no session exists before a connection.
            is BleEvent.PeerFound, is BleEvent.PeerLost, is BleEvent.ConnectionFailed -> Unit
        }
    }

    /**
     * Keeps event work ordered but cancellable with the captured run, not the lifetime collector.
     *
     * - When activation has not installed a parent yet, capture it under the mutex.
     * - A cancelled parent must remain cancelled, never fall back to collector-owned work.
     * - False means no parent or cancelled work; true does not certify success because [lockedQuietly] contains
     *   ordinary exceptions.
     */
    private suspend fun runOwned(block: suspend () -> Unit): Boolean {
        val parent = sessionParent ?: mutex.withLock { sessionParent } ?: return false
        val work = CoroutineScope(appScope.coroutineContext + parent).launch { lockedQuietly(block) }
        work.join()
        return !work.isCancelled
    }

    private suspend fun lockedQuietly(block: suspend () -> Unit) {
        try {
            mutex.withLock { block() }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Session work failed: ${e.message}")
        }
    }

    private suspend fun openSessionLocked(event: BleEvent.Connected) {
        val groupId = activeGroupId
        val parent = sessionParent
        if (groupId == null || parent == null || !parent.isActive) {
            Log.i(TAG, "Connection ${event.endpointId} arrived with no active group; disconnecting")
            disconnectQuietly(event.connection)
            return
        }
        // Remove before retiring so the retiring session is not the endpoint's owner when it reports closed.
        sessions.remove(event.endpointId)?.let { superseded ->
            Log.i(TAG, "Connection ${event.endpointId} replaces session ${superseded.generation}")
            superseded.retire()
        }
        val scope = CoroutineScope(appScope.coroutineContext + SupervisorJob(parent))
        val session =
            PeerSession(
                connection = event.connection,
                generation = ++generation,
                incoming = event.isIncoming,
                channelToken = event.authToken,
                groupId = groupId,
                identity = identity,
                store = store,
                transport = transport,
                scope = scope,
                listener = this,
                runSerialized = { block -> mutex.withLock { block() } },
                clock = clock
            )
        sessions[event.endpointId] = session
        // A failed Hello ends the session through onClosed, which removes and disconnects it.
        session.start()
        publishLocked()
    }

    private suspend fun closeAllLocked(reason: String) {
        // Snapshot the sessions because onClosed removes entries during iteration.
        sessions.values.toList().forEach { it.close(reason) }
        sessions.clear()
        val stopping = listOfNotNull(changeObserver, sessionParent)
        changeObserver = null
        sessionParent = null
        activeGroupId = null
        stopping.forEach { it.cancel() }
        publishLocked()
        // Mutex acquisition is cancellable: children queued on this lock can exit before the joins below.
        // Child cleanup must not reacquire this mutex in a non-cancellable context.
        stopping.forEach { it.join() }
    }

    private fun disconnectQuietly(connection: NearbyConnection) {
        try {
            transport.disconnect(connection)
        } catch (e: Exception) {
            // Best effort; the transport may already be stopped.
            Log.w(TAG, "Disconnect of ${connection.endpointId} failed: ${e.message}")
        }
    }

    // ------------------------------------------------------------ PeerSession.Listener

    override fun onProgress(session: PeerSession) {
        publishLocked()
    }

    override suspend fun onDataChanged(session: PeerSession) {
        // Listener callbacks execute under the non-reentrant session lock.
        sessions.values.filter { it !== session && !it.closed }.forEach { it.markDirty() }
    }

    override fun onClosed(session: PeerSession, reason: String) {
        // Ownership is decided before any side effect: a session that is not the endpoint's current one
        // (it retired for a replacement) leaves the transport and the published state alone.
        val current = sessions[session.endpointId] === session
        if (!current) return
        sessions.remove(session.endpointId)
        // Retain the terminal reason for UI observers after removing the live session.
        _state.value = _state.value.copy(peers = _state.value.peers + (session.endpointId to session.progress()))
        if (reason != NearbyWire.CLOSE_PEER_DISCONNECTED) disconnectQuietly(session.connection)
        publishLocked()
    }

    private fun publishLocked() {
        val live = sessions.values.associate { it.endpointId to it.progress() }
        // A replacement session supersedes the endpoint's terminal progress.
        val terminal = _state.value.peers.filter { (id, p) -> id !in live && p.phase.isTerminal() }
        _state.value =
            NearbySessionsState(active = activeGroupId != null, groupId = activeGroupId, peers = terminal + live)
    }

    private fun PeerPhase.isTerminal(): Boolean = this == PeerPhase.UNSUPPORTED_PEER ||
        this == PeerPhase.AUTH_FAILED ||
        this == PeerPhase.UNAUTHORIZED ||
        this == PeerPhase.INTERRUPTED ||
        this == PeerPhase.CLOSED

    companion object {
        private const val TAG = "NearbyCoordinator"
    }
}
