package com.splitfree.sync.nearby

import com.splitfree.data.ble.BleEvent
import com.splitfree.di.ApplicationScope
import com.splitfree.domain.repository.IdentityContract
import com.splitfree.util.DebugLog as Log
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * The one consumer of transport connection callbacks. Owns every [PeerSession], serializes all
 * session work behind one lock, and propagates newly available data to other open sessions on the
 * same group (store-and-forward while peers stay connected).
 *
 * Nearby work is explicitly enabled and foreground only: [activate] starts consuming transport
 * events for one group; [deactivate] performs terminal cleanup for every session. There is no
 * always-on background mesh. Durable records and envelopes stay available for later sessions.
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
    private var collector: Job? = null
    private var changeObserver: Job? = null
    private var sessionParent: Job? = null

    private val _state = MutableStateFlow(NearbySessionsState())
    val state: StateFlow<NearbySessionsState> = _state.asStateFlow()

    /** Start consuming transport events for [groupId]. Re-activating with another group closes everything first. */
    fun activate(groupId: String) {
        appScope.launch {
            mutex.withLock {
                if (activeGroupId == groupId && collector?.isActive == true) return@withLock
                closeAllLocked(NearbyWire.CLOSE_STOPPED)
                activeGroupId = groupId
                _state.value = NearbySessionsState(active = true, groupId = groupId)
                try {
                    store.prune()
                    // Rows left pending by an earlier session or a process restart: their dependency
                    // may have arrived since (relay pull), so re-drive them before the first peer.
                    val retried = store.retryDeferred(groupId)
                    if (retried > 0) Log.i(TAG, "Applied $retried pending record(s) for $groupId at activation")
                } catch (e: Exception) {
                    Log.w(TAG, "Activation maintenance failed: ${e.message}")
                }
                sessionParent = SupervisorJob(appScope.coroutineContext[Job])
                collector =
                    appScope.launch {
                        transport.events.collect { event ->
                            try {
                                onTransportEvent(event)
                            } catch (e: CancellationException) {
                                throw e
                            } catch (e: Exception) {
                                Log.w(TAG, "Transport event ${event::class.simpleName} failed: ${e.message}")
                            }
                        }
                    }
                changeObserver =
                    appScope.launch {
                        // Local data changed (user action, relay, another peer, an envelope re-wrapped
                        // for a new member): re-advertise to everyone. Only while active; this is not
                        // a background mesh.
                        store.observeChanges(groupId).distinctUntilChanged().drop(1).collect {
                            mutex.withLock { sessions.values.forEach { it.markDirty() } }
                        }
                    }
                publishLocked()
            }
        }
    }

    /** Terminal cleanup for every session and stop consuming transport events. Idempotent. */
    fun deactivate() {
        appScope.launch { mutex.withLock { closeAllLocked(NearbyWire.CLOSE_STOPPED) } }
    }

    /** External hint that [groupId] gained data (e.g. after a relay pull); re-advertise to open sessions. */
    fun notifyGroupChanged(groupId: String) {
        appScope.launch {
            mutex.withLock {
                if (activeGroupId == groupId) sessions.values.forEach { it.markDirty() }
            }
        }
    }

    private suspend fun onTransportEvent(event: BleEvent) {
        when (event) {
            is BleEvent.Connected -> mutex.withLock { openSessionLocked(event) }
            is BleEvent.Disconnected -> mutex.withLock { sessions[event.endpointId]?.onTransportDisconnected() }
            is BleEvent.PayloadReceived -> mutex.withLock { sessions[event.endpointId]?.onFrame(event.data) }
            is BleEvent.Error -> {
                if (event.operation == "connection_result" || event.operation == "send_payload") {
                    // A failed send means the peer will never see that frame; the session's timeouts
                    // and receipts handle the rest. Nothing to route.
                    return
                }
                if (event.operation == "advertise" || event.operation == "discovery") {
                    mutex.withLock { closeAllLocked(NearbyWire.CLOSE_TRANSPORT_ERROR) }
                }
            }

            is BleEvent.PeerFound, is BleEvent.PeerLost -> Unit
        }
    }

    private suspend fun openSessionLocked(event: BleEvent.Connected) {
        val groupId = activeGroupId ?: return
        sessions.remove(event.endpointId)?.close(NearbyWire.CLOSE_STOPPED)
        val parent = sessionParent ?: return
        val scope = CoroutineScope(appScope.coroutineContext + SupervisorJob(parent))
        val session =
            PeerSession(
                endpointId = event.endpointId,
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
        session.start()
        publishLocked()
    }

    private suspend fun closeAllLocked(reason: String) {
        // Each close removes its own entry through onClosed and records the terminal progress.
        sessions.values.toList().forEach { it.close(reason) }
        sessions.clear()
        collector?.cancel()
        collector = null
        changeObserver?.cancel()
        changeObserver = null
        sessionParent?.cancel()
        sessionParent = null
        activeGroupId = null
        publishLocked()
    }

    // ------------------------------------------------------------ PeerSession.Listener

    override fun onProgress(session: PeerSession) {
        publishLocked()
    }

    override suspend fun onDataChanged(session: PeerSession) {
        // Already under the lock: called from within a session entry point.
        sessions.values.filter { it !== session && !it.closed }.forEach { it.markDirty() }
    }

    override fun onClosed(session: PeerSession, reason: String) {
        if (sessions[session.endpointId] === session) {
            sessions.remove(session.endpointId)
            // Keep the terminal progress visible so the UI can show why.
            _state.value =
                _state.value.copy(peers = _state.value.peers + (session.endpointId to session.progress()))
        }
        if (reason != NearbyWire.CLOSE_PEER_DISCONNECTED) {
            try {
                transport.disconnect(session.endpointId)
            } catch (_: Exception) {
                // Best effort; the transport may already be stopped.
            }
        }
        publishLocked()
    }

    private fun publishLocked() {
        val live = sessions.values.associate { it.endpointId to it.progress() }
        // Retain terminal entries for endpoints that have no live session, drop the rest.
        val terminal = _state.value.peers.filter { (id, p) -> id !in live && p.phase.isTerminal() }
        _state.value =
            NearbySessionsState(active = activeGroupId != null, groupId = activeGroupId, peers = terminal + live)
    }

    private fun PeerPhase.isTerminal(): Boolean =
        this == PeerPhase.UNSUPPORTED_PEER || this == PeerPhase.AUTH_FAILED || this == PeerPhase.UNAUTHORIZED ||
            this == PeerPhase.INTERRUPTED || this == PeerPhase.CLOSED

    companion object {
        private const val TAG = "NearbyCoordinator"
    }
}
