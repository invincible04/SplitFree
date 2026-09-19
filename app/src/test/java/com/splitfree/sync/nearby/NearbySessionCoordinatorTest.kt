package com.splitfree.sync.nearby

import com.splitfree.data.ble.BleEvent
import io.mockk.every
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.TestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Real coordinators and peer sessions over [Router] and scripted stores.
 *
 * - Connection roles and tokens are fixture inputs; the real handshake chooses the initiator and verifies signatures.
 * - Protocol tests use an eager dispatcher, while lifecycle tests use queued dispatch and gates to select specific
 *   interleavings.
 * - This models transport events, not SDK callback ordering, Room behavior or arbitrary thread races.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class NearbySessionCoordinatorTest {
    private val scheduler = TestCoroutineScheduler()
    private val dispatcher = UnconfinedTestDispatcher(scheduler)
    private val router = Router()
    private val groupId = "12345678-1234-1234-1234-123456789abc"
    private val scopes = mutableListOf<CoroutineScope>()
    private val failures = mutableListOf<Throwable>()

    inner class Peer(
        seed: Int,
        val members: MutableSet<String>,
        creator: String = "",
        dispatcher: TestDispatcher = this@NearbySessionCoordinatorTest.dispatcher,
        decorateStore: (ReconciliationStore) -> ReconciliationStore = { it }
    ) {
        val identity = TestIdentity(seed)
        val pub = identity.pub
        val transport = FakeTransport("p$seed").also { it.router = router }
        val store = FakeReconciliationStore(pub, members, creator)
        val scope =
            CoroutineScope(SupervisorJob() + dispatcher + CoroutineExceptionHandler { _, error -> failures += error })
                .also { scopes += it }
        val coordinator =
            NearbySessionCoordinator(transport, identity.contract, decorateStore(store), scope) {
                scheduler.currentTime
            }

        /** Simulate a local write the coordinator's change observer would see. */
        fun touchStore() {
            store.changes.value = StoreVersion(store.changes.value.revision + 1)
        }

        fun progress(endpoint: String): PeerProgress? = coordinator.state.value.peers[endpoint]

        fun phase(endpoint: String): PeerPhase? = progress(endpoint)?.phase

        fun activate() = scope.launch { coordinator.activate(groupId) }

        fun deactivate() = scope.launch { coordinator.deactivate() }

        fun sent(): List<NearbyMessage> = transport.sentMessages()
    }

    @Before
    fun setup() {
        mockkStatic(android.util.Log::class)
        every { android.util.Log.i(any<String>(), any<String>()) } returns 0
        every { android.util.Log.w(any<String>(), any<String>()) } returns 0
        every { android.util.Log.e(any<String>(), any<String>()) } returns 0
        every { android.util.Log.d(any<String>(), any<String>()) } returns 0
    }

    @After
    fun teardown() {
        scopes.forEach { it.cancel() }
        unmockkStatic(android.util.Log::class)
        assertTrue("No session failure may escape to the application scope: $failures", failures.isEmpty())
    }

    private fun twoMembers(
        seedA: Int = 1,
        seedB: Int = 2,
        dispatcher: TestDispatcher = this.dispatcher
    ): Pair<Peer, Peer> {
        val a = TestIdentity(seedA).pub
        val b = TestIdentity(seedB).pub
        val members = mutableSetOf(a, b)
        return Peer(seedA, members.toMutableSet(), creator = a, dispatcher = dispatcher) to
            Peer(seedB, members.toMutableSet(), creator = a, dispatcher = dispatcher)
    }

    private fun threeMembers(dispatcher: TestDispatcher = this.dispatcher): Triple<Peer, Peer, Peer> {
        val ids = (1..3).map { TestIdentity(it).pub }
        val members = ids.toMutableSet()
        return Triple(
            Peer(1, members.toMutableSet(), creator = ids[0], dispatcher = dispatcher),
            Peer(2, members.toMutableSet(), creator = ids[0], dispatcher = dispatcher),
            Peer(3, members.toMutableSet(), creator = ids[0], dispatcher = dispatcher)
        )
    }

    private fun connect(
        a: Peer,
        b: Peer,
        aIncoming: Boolean = false,
        bIncoming: Boolean = !aIncoming,
        sameToken: Boolean = true
    ) {
        val token = "chan-${a.pub.take(4)}-${b.pub.take(4)}".toByteArray()
        router.connect(
            a.transport,
            "ep-${b.pub.take(6)}",
            b.transport,
            "ep-${a.pub.take(6)}",
            token = token,
            aIncoming = aIncoming,
            bIncoming = bIncoming,
            bToken = if (sameToken) token else "other".toByteArray()
        )
    }

    private fun ep(of: Peer) = "ep-${of.pub.take(6)}"

    private fun runParent(peer: Peer): Job? = NearbySessionCoordinator::class.java
        .getDeclaredField("sessionParent")
        .apply { isAccessible = true }
        .get(peer.coordinator) as Job?

    /** A transport nobody listens to: connections to it stay open and it never sends a frame. */
    private fun silentTransport(name: String) = FakeTransport(name).also { it.router = router }

    /** Connects [a] to a [silent] remote that [a] sees as [endpointId]. */
    private fun connectSilent(a: Peer, silent: FakeTransport, endpointId: String) {
        router.connect(a.transport, endpointId, silent, ep(a), token = "chan-silent".toByteArray())
    }

    private fun advance(ms: Long) {
        scheduler.advanceTimeBy(ms)
        scheduler.runCurrent()
        router.pump()
    }

    /** For [StandardTestDispatcher] peers: runs due tasks and delivers frames until neither produces more work. */
    private fun settle() {
        do {
            scheduler.runCurrent()
        } while (router.pump() > 0)
    }

    private fun sendError(
        peer: Peer,
        endpointId: String?,
        kind: RadioFailureKind?,
        operation: String = "send_payload"
    ) {
        check(peer.transport.flow.tryEmit(BleEvent.Error(operation, "scripted", endpointId, kind)))
    }

    // ------------------------------------------------------------------ sessions & auth

    @Test
    fun `symmetric Connected callbacks authenticate and reach up to date`() {
        val (a, b) = twoMembers()
        a.activate()
        b.activate()
        connect(a, b)
        router.pump()
        assertEquals(PeerPhase.UP_TO_DATE, a.phase(ep(b)))
        assertEquals(PeerPhase.UP_TO_DATE, b.phase(ep(a)))
        assertEquals(b.pub, a.progress(ep(b))?.peerPubkey)
        assertEquals(a.pub, b.progress(ep(a))?.peerPubkey)
    }

    @Test
    fun `simultaneous connection attempts (both outgoing) still authenticate via pubkey tiebreak`() {
        val (a, b) = twoMembers()
        a.activate()
        b.activate()
        connect(a, b, aIncoming = false, bIncoming = false)
        router.pump()
        assertEquals(PeerPhase.UP_TO_DATE, a.phase(ep(b)))
        assertEquals(PeerPhase.UP_TO_DATE, b.phase(ep(a)))
        // Exactly one OpenGroup crossed the wire.
        val opens =
            a.transport.sentMessages().count { it is OpenGroup } + b.transport.sentMessages().count { it is OpenGroup }
        assertEquals(1, opens)
    }

    @Test
    fun `duplicate handshake frames are idempotent`() {
        val (a, b) = twoMembers()
        a.activate()
        b.activate()
        connect(a, b)
        // Drain the scripted exchange before replaying the recorded Hello/Auth frames below.
        while (router.pending() > 0) {
            val before = b.transport.sentFrames.size + a.transport.sentFrames.size
            router.step()
            val last = a.transport.sentFrames.lastOrNull() ?: b.transport.sentFrames.lastOrNull()
            if (before == 0 && last != null) {
                val msg = (NearbyWire.decode(last.second) as NearbyWire.Decoded.Message).message
                if (msg is Hello || msg is Auth) router.enqueue(a.transport, ep(b), last.second)
            }
        }
        router.pump()
        // Re-inject A's Hello and Auth straight into B again after the fact.
        a.transport.sentFrames.filter {
            val m = (NearbyWire.decode(it.second) as NearbyWire.Decoded.Message).message
            m is Hello || m is Auth
        }.forEach { b.transport.flow.tryEmit(BleEvent.PayloadReceived(b.transport.connection(ep(a)), it.second)) }
        router.pump()
        assertEquals(PeerPhase.UP_TO_DATE, a.phase(ep(b)))
        assertEquals(PeerPhase.UP_TO_DATE, b.phase(ep(a)))
    }

    @Test
    fun `different channel tokens fail authentication on both sides`() {
        val (a, b) = twoMembers()
        a.activate()
        b.activate()
        connect(a, b, sameToken = false)
        router.pump()
        assertEquals(PeerPhase.AUTH_FAILED, a.phase(ep(b)))
        assertEquals(PeerPhase.AUTH_FAILED, b.phase(ep(a)))
        assertTrue(a.transport.sentMessages().none { it is OpenGroup })
        assertTrue(b.transport.sentMessages().none { it is OpenGroup })
    }

    @Test
    fun `handshake copied between two connections fails without the right channel token`() {
        // Mallory relays A's frames to B over a separate connection. The signatures bind the
        // (per-connection) token, so B rejects them even though A's identity proof is genuine.
        val (a, b) = twoMembers()
        val mallory = silentTransport("mallory")
        a.activate()
        b.activate()
        val tokenAM = "tok-a-m".toByteArray()
        val tokenMB = "tok-m-b".toByteArray()
        router.connect(a.transport, "ep-m", mallory, "ep-a", token = tokenAM)
        router.connect(mallory, "ep-b", b.transport, "ep-m", token = tokenMB, aIncoming = false)
        router.pump()
        // Forward newly captured frames in both directions for six scripted rounds.
        var forwardedA = 0
        var forwardedB = 0
        repeat(6) {
            a.transport.sentFrames.drop(forwardedA).forEach {
                b.transport.flow.tryEmit(BleEvent.PayloadReceived(b.transport.connection("ep-m"), it.second))
            }
            forwardedA = a.transport.sentFrames.size
            router.pump()
            b.transport.sentFrames.drop(forwardedB).forEach {
                a.transport.flow.tryEmit(BleEvent.PayloadReceived(a.transport.connection("ep-m"), it.second))
            }
            forwardedB = b.transport.sentFrames.size
            router.pump()
        }
        assertEquals(PeerPhase.AUTH_FAILED, a.phase("ep-m"))
        assertEquals(PeerPhase.AUTH_FAILED, b.phase("ep-m"))
        assertTrue(a.transport.sentMessages().none { it is OpenGroup })
        assertTrue(b.transport.sentMessages().none { it is OpenGroup })
    }

    @Test
    fun `key owning stranger learns no group identifier`() {
        val member = TestIdentity(1).pub
        val a = Peer(1, mutableSetOf(member), creator = member)
        val stranger = Peer(9, mutableSetOf(TestIdentity(9).pub))
        a.activate()
        stranger.activate()
        connect(a, stranger)
        router.pump()
        assertEquals(PeerPhase.UNAUTHORIZED, a.phase(ep(stranger)))
        val leaked = (a.transport.sentFrames + stranger.transport.sentFrames).any {
            String(it.second).contains(groupId)
        }
        assertFalse("group id must not cross the wire to a non-member", leaked)
    }

    @Test
    fun `responder refuses a peer it does not consider a member`() {
        val (a, b) = twoMembers()
        b.store.members.remove(a.pub) // B has not learned A's membership
        b.store.authorizedOverride = { false }
        a.activate()
        b.activate()
        connect(a, b)
        router.pump()
        assertEquals(PeerPhase.UNAUTHORIZED, a.phase(ep(b)))
        assertEquals(PeerPhase.UNAUTHORIZED, b.phase(ep(a)))
        assertTrue(a.transport.sentMessages().none { it is InventoryPage })
    }

    @Test
    fun `invited joiner is admitted through its signed join event`() {
        val (a, b) = twoMembers()
        b.store.members.remove(a.pub)
        b.store.admitJoinResult = true
        a.store.ownJoin = """{"join":"me"}"""
        a.activate()
        b.activate()
        connect(a, b)
        router.pump()
        assertEquals(PeerPhase.UP_TO_DATE, a.phase(ep(b)))
        assertEquals(PeerPhase.UP_TO_DATE, b.phase(ep(a)))
    }

    @Test
    fun `an invited joiner is admitted when the roster holder initiates the connection`() {
        // a is the creator with the roster; b joined offline and its announcement has not reached a.
        val (a, b) = twoMembers()
        a.store.members.remove(b.pub)
        a.store.admitJoinResult = true
        b.store.ownJoin = """{"join":"me"}"""
        a.activate()
        b.activate()
        // a is the outgoing side and therefore the initiator; b can only respond.
        connect(a, b)
        router.pump()
        assertEquals(PeerPhase.UP_TO_DATE, a.phase(ep(b)))
        assertEquals(PeerPhase.UP_TO_DATE, b.phase(ep(a)))
        assertTrue("the responder must have introduced itself", b.transport.sentMessages().any { it is Introduce })
        assertTrue(b.pub in a.store.members)
    }

    @Test
    fun `an initiator still refuses a responder whose introduction does not prove membership`() {
        val (a, b) = twoMembers()
        a.store.members.remove(b.pub)
        a.store.admitJoinResult = false
        b.store.ownJoin = """{"join":"forged"}"""
        a.activate()
        b.activate()
        connect(a, b)
        router.pump()
        assertEquals(PeerPhase.UNAUTHORIZED, a.phase(ep(b)))
        assertEquals(PeerPhase.UNAUTHORIZED, b.phase(ep(a)))
        assertTrue(a.transport.sentMessages().none { it is OpenGroup })
        val leaked = a.transport.sentFrames.any { String(it.second).contains(groupId) }
        assertFalse("group id must not cross the wire to a peer that was never admitted", leaked)
    }

    @Test
    fun `a responder with no join proof is refused promptly by an initiator that does not know it`() {
        val (a, b) = twoMembers()
        a.store.members.remove(b.pub)
        b.store.ownJoin = null
        a.activate()
        b.activate()
        connect(a, b)
        router.pump()
        assertEquals(PeerPhase.UNAUTHORIZED, a.phase(ep(b)))
        assertEquals(PeerPhase.UNAUTHORIZED, b.phase(ep(a)))
    }

    @Test
    fun `a responder never offers its join proof to an initiator it does not authorize`() {
        // Neither side knows the other: the responder's proof, which names the group, must stay home.
        val (a, b) = twoMembers()
        a.store.members.remove(b.pub)
        b.store.authorizedOverride = { false }
        b.store.ownJoin = """{"join":"me"}"""
        a.activate()
        b.activate()
        connect(a, b)
        router.pump()
        val introductions = b.transport.sentMessages().filterIsInstance<Introduce>()
        assertEquals(1, introductions.size)
        assertEquals(null, introductions.single().joinEvent)
        assertEquals(PeerPhase.UNAUTHORIZED, a.phase(ep(b)))
    }

    @Test
    fun `v1 frame closes as unsupported peer`() {
        val (a, b) = twoMembers()
        a.activate()
        connect(a, b)
        a.transport.flow.tryEmit(
            BleEvent.PayloadReceived(
                a.transport.connection(ep(b)),
                byteArrayOf(0x01) + "{}".toByteArray()
            )
        )
        router.pump()
        assertEquals(PeerPhase.UNSUPPORTED_PEER, a.phase(ep(b)))
        assertTrue(a.transport.disconnected.contains(ep(b)))
    }

    @Test
    fun `unknown protocol version closes as unsupported peer`() {
        val (a, b) = twoMembers()
        a.activate()
        connect(a, b)
        val hello = Hello(99, b.pub, NearbyAuth.newNonce(), incoming = true)
        a.transport.flow.tryEmit(BleEvent.PayloadReceived(a.transport.connection(ep(b)), NearbyWire.encode(hello)))
        router.pump()
        assertEquals(PeerPhase.UNSUPPORTED_PEER, a.phase(ep(b)))
    }

    @Test
    fun `handshake times out and cleans up when the peer never answers`() {
        val (a, b) = twoMembers()
        a.activate()
        val silent = silentTransport("silent")
        connectSilent(a, silent, ep(b)) // the remote side never sends Hello
        router.pump()
        assertEquals(PeerPhase.AUTHENTICATING, a.phase(ep(b)))
        advance(PeerSession.HANDSHAKE_TIMEOUT_MS + PeerSession.WATCHDOG_INTERVAL_MS)
        assertEquals(PeerPhase.INTERRUPTED, a.phase(ep(b)))
        assertEquals(NearbyWire.CLOSE_TIMEOUT, a.progress(ep(b))?.closeReason)
        assertTrue(a.transport.disconnected.contains(ep(b)))
    }

    @Test
    fun `stale timeout cannot touch a replacement session on the same endpoint`() {
        val (a, b) = twoMembers()
        a.activate()
        val silent = silentTransport("silent")
        connectSilent(a, silent, ep(b))
        router.pump()
        advance(PeerSession.HANDSHAKE_TIMEOUT_MS - 1_000) // almost timed out
        // Transport reconnects on the same endpoint id; b now participates.
        router.disconnect(a.transport, ep(b), notifyPeer = false)
        router.pump()
        b.activate()
        connect(a, b)
        router.pump()
        assertEquals(PeerPhase.UP_TO_DATE, a.phase(ep(b)))
        advance(PeerSession.HANDSHAKE_TIMEOUT_MS + PeerSession.WATCHDOG_INTERVAL_MS)
        assertEquals("old timer must not close the new session", PeerPhase.UP_TO_DATE, a.phase(ep(b)))
    }

    @Test
    fun `deactivate performs terminal cleanup for every session`() {
        val (a, b) = twoMembers()
        a.activate()
        b.activate()
        connect(a, b)
        router.pump()
        assertEquals(PeerPhase.UP_TO_DATE, a.phase(ep(b)))
        a.deactivate()
        router.pump()
        assertFalse(a.coordinator.state.value.active)
        assertEquals(PeerPhase.CLOSED, a.phase(ep(b)))
        assertTrue(a.transport.disconnected.contains(ep(b)))
        // Frames arriving after stop are ignored: no session, no crash.
        a.transport.flow.tryEmit(
            BleEvent.PayloadReceived(a.transport.connection(ep(b)), NearbyWire.encode(Want(1, listOf("x"))))
        )
        router.pump()
        assertEquals(PeerPhase.CLOSED, a.phase(ep(b)))
    }

    // ------------------------------------------------------------------ lifecycle

    @Test
    fun `activate returns only after maintenance and then handles the first connection`() {
        val std = StandardTestDispatcher(scheduler)
        val (a, b) = twoMembers(dispatcher = std)
        val gate = CompletableDeferred<Unit>()
        a.store.maintenanceGate = gate
        val activation = a.activate()
        b.activate()
        settle()
        assertFalse("activation must wait for maintenance", activation.isCompleted)
        assertEquals(0, a.store.pruned)
        assertFalse(a.coordinator.state.value.active)
        gate.complete(Unit)
        settle()
        assertTrue(activation.isCompleted)
        assertEquals(1, a.store.pruned)
        assertTrue(a.store.retryCalls >= 1)
        assertTrue(a.coordinator.state.value.active)
        assertEquals(groupId, a.coordinator.state.value.groupId)
        connect(a, b)
        settle()
        assertEquals(PeerPhase.UP_TO_DATE, a.phase(ep(b)))
        assertEquals(PeerPhase.UP_TO_DATE, b.phase(ep(a)))
    }

    @Test
    fun `a connection made during maintenance is served once activation completes`() {
        val std = StandardTestDispatcher(scheduler)
        val (a, b) = twoMembers(dispatcher = std)
        val gate = CompletableDeferred<Unit>()
        a.store.maintenanceGate = gate
        val activation = a.activate()
        b.activate()
        settle()
        connect(a, b)
        settle()
        assertFalse(activation.isCompleted)
        assertNull(a.progress(ep(b)))
        gate.complete(Unit)
        settle()
        assertTrue(activation.isCompleted)
        assertEquals(PeerPhase.UP_TO_DATE, a.phase(ep(b)))
        assertEquals(PeerPhase.UP_TO_DATE, b.phase(ep(a)))
    }

    @Test
    fun `a connection queued without a parent during activation runs in the captured run`() {
        val std = StandardTestDispatcher(scheduler)
        val (a, b) = twoMembers(dispatcher = std)
        val gate = CompletableDeferred<Unit>()
        a.store.maintenanceGate = gate
        val activation = a.activate()
        b.activate()
        settle()
        assertNull(runParent(a))
        connect(a, b)
        settle()
        assertFalse(activation.isCompleted)
        assertNull(a.progress(ep(b)))

        var runChildrenAtHello = 0
        a.transport.dropIf = { message ->
            if (message is Hello) runChildrenAtHello = checkNotNull(runParent(a)).children.count()
            false
        }
        gate.complete(Unit)
        settle()
        assertTrue(activation.isCompleted)
        // The run owns both the session's scope and the work opening it, not just its future timers.
        assertEquals(2, runChildrenAtHello)
        assertEquals(PeerPhase.UP_TO_DATE, a.phase(ep(b)))
        assertEquals(PeerPhase.UP_TO_DATE, b.phase(ep(a)))
    }

    @Test
    fun `cancelling activate during maintenance rolls back and later connections are refused`() {
        val (a, b) = twoMembers()
        val gate = CompletableDeferred<Unit>()
        a.store.maintenanceGate = gate
        val activation = a.activate()
        assertFalse(activation.isCompleted)
        activation.cancel()
        scheduler.runCurrent()
        assertTrue(activation.isCancelled)
        assertTrue(activation.isCompleted)
        assertFalse(a.coordinator.state.value.active)
        assertNull(a.coordinator.state.value.groupId)
        assertEquals(0, a.store.pruned)
        // A change in the store reaches no observer.
        a.touchStore()
        scheduler.runCurrent()
        assertEquals(0, a.store.retryCalls)
        // A connection arriving now has no group to serve: it is closed and never becomes a session.
        b.activate()
        connect(a, b)
        router.pump()
        assertNull(a.progress(ep(b)))
        assertTrue(a.transport.sentFrames.isEmpty())
        assertTrue(a.transport.disconnected.contains(ep(b)))
        assertEquals(PeerPhase.INTERRUPTED, b.phase(ep(a)))
        // The next activation is a clean start.
        gate.complete(Unit)
        a.activate()
        connect(a, b)
        router.pump()
        assertEquals(PeerPhase.UP_TO_DATE, a.phase(ep(b)))
        assertEquals(PeerPhase.UP_TO_DATE, b.phase(ep(a)))
    }

    @Test
    fun `deactivate sends Close to every live session and refuses connections afterwards`() {
        val (a, b, c) = threeMembers()
        listOf(a, b, c).forEach { it.activate() }
        connect(a, b)
        connect(a, c)
        router.pump()
        assertEquals(PeerPhase.UP_TO_DATE, a.phase(ep(b)))
        assertEquals(PeerPhase.UP_TO_DATE, a.phase(ep(c)))
        assertTrue(a.sent().none { it is Close })

        val first = a.deactivate()
        val second = a.deactivate()
        assertTrue(first.isCompleted)
        assertTrue(second.isCompleted)
        assertFalse(a.coordinator.state.value.active)
        val closes = a.transport.sentFrames.filter {
            (NearbyWire.decode(it.second) as NearbyWire.Decoded.Message).message is Close
        }
        assertEquals(setOf(ep(b), ep(c)), closes.map { it.first }.toSet())
        assertTrue(a.sent().filterIsInstance<Close>().all { it.reason == NearbyWire.CLOSE_STOPPED })
        assertTrue(a.transport.disconnected.containsAll(listOf(ep(b), ep(c))))
        assertEquals(PeerPhase.CLOSED, a.phase(ep(b)))
        assertEquals(PeerPhase.CLOSED, a.phase(ep(c)))
        router.pump()
        assertEquals(PeerPhase.CLOSED, b.phase(ep(a)))
        assertEquals(NearbyWire.CLOSE_STOPPED, b.progress(ep(a))?.closeReason)

        // A store change after deactivation reaches nothing.
        val retries = a.store.retryCalls
        a.touchStore()
        scheduler.runCurrent()
        assertEquals(retries, a.store.retryCalls)

        // The transport reports a new connection while no group is active.
        val framesBefore = a.transport.sentFrames.size
        connect(a, b)
        router.pump()
        assertEquals(PeerPhase.CLOSED, a.phase(ep(b)))
        assertEquals(framesBefore, a.transport.sentFrames.size)
        assertEquals(2, a.transport.disconnected.count { it == ep(b) })
        assertEquals(PeerPhase.INTERRUPTED, b.phase(ep(a)))
    }

    @Test
    fun `deactivate completes when a watchdog tick falls due at the same instant`() {
        val std = StandardTestDispatcher(scheduler)
        val (a, b) = twoMembers(dispatcher = std)
        a.activate()
        b.activate()
        settle()
        connect(a, b)
        settle()
        assertEquals(PeerPhase.UP_TO_DATE, a.phase(ep(b)))
        // The deactivation is armed for the second tick instant before the watchdog re-arms itself for it,
        // so it runs first and the tick is due while deactivate is joining the session timers.
        val armed = a.scope.launch {
            delay(2 * PeerSession.WATCHDOG_INTERVAL_MS)
            a.coordinator.deactivate()
        }
        // The first tick is due now and runs ahead of a deactivation launched afterwards.
        scheduler.advanceTimeBy(PeerSession.WATCHDOG_INTERVAL_MS)
        val late = b.deactivate()
        scheduler.runCurrent()
        assertTrue(late.isCompleted)
        assertFalse(b.coordinator.state.value.active)
        assertTrue(a.coordinator.state.value.active)

        scheduler.advanceTimeBy(PeerSession.WATCHDOG_INTERVAL_MS)
        scheduler.runCurrent()
        assertTrue(armed.isCompleted)
        assertFalse(a.coordinator.state.value.active)
        assertEquals(PeerPhase.CLOSED, a.phase(ep(b)))
        assertTrue(a.transport.disconnected.contains(ep(b)))
        // No session timer survives; the application-scope transport collector intentionally remains subscribed.
        advance(PeerSession.TRANSFER_TIMEOUT_MS * 3)
        settle()
        assertEquals(PeerPhase.CLOSED, a.phase(ep(b)))
    }

    @Test
    fun `deactivate completes while store work holds the lock by cancelling the observer and the timers`() {
        val std = StandardTestDispatcher(scheduler)
        val (a, b) = twoMembers(dispatcher = std)
        a.activate()
        b.activate()
        settle()
        connect(a, b)
        settle()
        assertEquals(PeerPhase.UP_TO_DATE, a.phase(ep(b)))
        // A cancellable retryDeferred wait holds the coordinator lock while a watchdog tick queues behind it.
        val gate = CompletableDeferred<Unit>()
        a.store.retryGate = gate
        a.touchStore()
        scheduler.runCurrent()
        scheduler.advanceTimeBy(PeerSession.WATCHDOG_INTERVAL_MS)
        scheduler.runCurrent()
        val deactivation = a.deactivate()
        scheduler.runCurrent()
        // Cancellation unwinds the cooperative wait; gate completion is not needed to release the lock.
        assertFalse(gate.isCompleted)
        assertTrue(deactivation.isCompleted)
        assertFalse(a.coordinator.state.value.active)
        assertEquals(PeerPhase.CLOSED, a.phase(ep(b)))
        assertTrue(a.sent().any { it is Close })
        assertTrue(a.transport.disconnected.contains(ep(b)))
        // A late release of the store work changes nothing.
        gate.complete(Unit)
        settle()
        assertFalse(a.coordinator.state.value.active)
        assertEquals(PeerPhase.CLOSED, a.phase(ep(b)))
    }

    @Test
    fun `deactivate completes while a frame's store work holds the lock and the next run is served`() {
        val std = StandardTestDispatcher(scheduler)
        val (a, b) = twoMembers(dispatcher = std)
        a.activate()
        b.activate()
        settle()
        // Group opening on the authenticated session calls retryDeferred, which never returns on its own,
        // so the frame collector's work holds the coordinator lock.
        val gate = CompletableDeferred<Unit>()
        a.store.retryGate = gate
        connect(a, b)
        settle()
        assertEquals(PeerPhase.COMPARING, a.phase(ep(b)))
        val deactivation = a.deactivate()
        scheduler.runCurrent()
        assertFalse(gate.isCompleted)
        assertTrue(deactivation.isCompleted)
        assertFalse(a.coordinator.state.value.active)
        assertEquals(PeerPhase.CLOSED, a.phase(ep(b)))
        assertTrue(a.sent().any { it is Close })
        assertTrue(a.transport.disconnected.contains(ep(b)))
        settle()
        assertEquals(PeerPhase.CLOSED, b.phase(ep(a)))

        // The next run does not depend on the stalled work: the collector serves its connection to completion.
        a.store.retryGate = null
        a.activate()
        settle()
        assertTrue(a.coordinator.state.value.active)
        connect(a, b)
        settle()
        assertEquals(PeerPhase.UP_TO_DATE, a.phase(ep(b)))
        assertEquals(PeerPhase.UP_TO_DATE, b.phase(ep(a)))

        // The stalled work was cancelled with its run; releasing it changes nothing.
        gate.complete(Unit)
        settle()
        assertEquals(PeerPhase.UP_TO_DATE, a.phase(ep(b)))
        assertEquals(1, a.coordinator.state.value.peers.size)
    }

    @Test
    fun `an authenticated open result arriving inside run cancellation cannot take the cleanup lock`() {
        val (a, b) = twoMembers()
        a.activate()
        b.activate()
        b.transport.dropIf = { it is OpenGroupResult }
        connect(a, b)
        router.pump()
        assertEquals(PeerPhase.OPENING_GROUP, a.phase(ep(b)))
        assertEquals(b.pub, a.progress(ep(b))?.peerPubkey)
        val response = b.sent().filterIsInstance<OpenGroupResult>().single()
        assertTrue(response.ok)
        assertEquals(groupId, response.groupId)
        val connection = a.transport.connection(ep(b))
        val parent = checkNotNull(runParent(a))
        val gate = CompletableDeferred<Unit>()
        a.store.retryGate = gate
        var deliveredBeforeCleanup = false
        parent.invokeOnCompletion {
            assertTrue(parent.isCancelled)
            assertTrue("deactivate has not taken the cleanup lock", a.coordinator.state.value.active)
            assertEquals(PeerPhase.OPENING_GROUP, a.phase(ep(b)))
            deliveredBeforeCleanup = true
            check(a.transport.flow.tryEmit(BleEvent.PayloadReceived(connection, NearbyWire.encode(response))))
        }

        // Enter real deactivate without an enclosing Unconfined event loop, so cancellation delivers the
        // response before cancel() returns and before deactivate can take the mutex.
        val deactivation = a.scope.launch(start = CoroutineStart.UNDISPATCHED) { a.coordinator.deactivate() }
        scheduler.runCurrent()
        assertTrue(deliveredBeforeCleanup)
        assertFalse(gate.isCompleted)
        assertTrue("a cancelled parent must never fall back to collector-owned store work", deactivation.isCompleted)
        assertTrue(parent.isCompleted)
        assertFalse(a.coordinator.state.value.active)
        assertEquals(PeerPhase.CLOSED, a.phase(ep(b)))
        assertEquals(NearbyWire.CLOSE_STOPPED, a.progress(ep(b))?.closeReason)
        assertTrue(a.transport.disconnected.contains(ep(b)))
        settle()

        a.store.retryGate = null
        b.transport.dropIf = null
        a.activate()
        connect(a, b)
        router.pump()
        assertEquals(PeerPhase.UP_TO_DATE, a.phase(ep(b)))
        assertEquals(PeerPhase.UP_TO_DATE, b.phase(ep(a)))
        gate.complete(Unit)
        settle()
        assertEquals(PeerPhase.UP_TO_DATE, a.phase(ep(b)))
        assertEquals(1, a.coordinator.state.value.peers.size)
    }

    @Test
    fun `deactivate cancels dirty work already suspended in the store and joins its cleanup`() {
        val std = StandardTestDispatcher(scheduler)
        val members = mutableSetOf(TestIdentity(1).pub, TestIdentity(2).pub)
        val gate = CompletableDeferred<Unit>()
        var holdPendingRead = false
        var pendingReadEntered = false
        var pendingReadCancelled = false
        val a = Peer(1, members.toMutableSet(), dispatcher = std, decorateStore = { delegate ->
            object : ReconciliationStore by delegate {
                override suspend fun pendingCount(groupId: String): Int {
                    if (holdPendingRead) {
                        pendingReadEntered = true
                        try {
                            gate.await()
                        } finally {
                            pendingReadCancelled = !gate.isCompleted
                        }
                    }
                    return delegate.pendingCount(groupId)
                }
            }
        })
        val b = Peer(2, members.toMutableSet(), dispatcher = std)
        a.activate()
        b.activate()
        settle()
        connect(a, b)
        settle()
        assertEquals(PeerPhase.UP_TO_DATE, a.phase(ep(b)))
        val parent = checkNotNull(runParent(a))
        holdPendingRead = true
        a.coordinator.notifyGroupChanged(groupId)
        scheduler.runCurrent()
        assertTrue(pendingReadEntered)

        val deactivation = a.deactivate()
        scheduler.runCurrent()
        assertFalse(gate.isCompleted)
        assertTrue(pendingReadCancelled)
        assertTrue(deactivation.isCompleted)
        assertTrue(parent.isCompleted)
        assertFalse(a.coordinator.state.value.active)
        assertEquals(PeerPhase.CLOSED, a.phase(ep(b)))
        val framesAfterClose = a.transport.sentFrames.size
        gate.complete(Unit)
        settle()
        assertEquals(framesAfterClose, a.transport.sentFrames.size)
        assertFalse(a.coordinator.state.value.active)
    }

    @Test
    fun `notifyGroupChanged work is cancelled by deactivate while the store holds the lock`() {
        val std = StandardTestDispatcher(scheduler)
        val (a, b) = twoMembers(dispatcher = std)
        a.activate()
        b.activate()
        settle()
        connect(a, b)
        settle()
        assertEquals(PeerPhase.UP_TO_DATE, a.phase(ep(b)))
        // A change notification queues behind store work the observer is stalled in.
        val gate = CompletableDeferred<Unit>()
        a.store.retryGate = gate
        a.touchStore()
        scheduler.runCurrent()
        a.coordinator.notifyGroupChanged(groupId)
        scheduler.runCurrent()
        val deactivation = a.deactivate()
        scheduler.runCurrent()
        assertFalse(gate.isCompleted)
        assertTrue(deactivation.isCompleted)
        assertFalse(a.coordinator.state.value.active)
        gate.complete(Unit)
        settle()
        assertFalse(a.coordinator.state.value.active)
        assertEquals(PeerPhase.CLOSED, a.phase(ep(b)))
    }

    @Test
    fun `a connection arriving before activation is closed without a session`() {
        val (a, b) = twoMembers()
        b.activate()
        connect(a, b)
        router.pump()
        assertTrue(a.coordinator.state.value.peers.isEmpty())
        assertTrue(a.transport.sentFrames.isEmpty())
        assertEquals(listOf(ep(b)), a.transport.disconnected)
        assertEquals(PeerPhase.INTERRUPTED, b.phase(ep(a)))
    }

    @Test
    fun `a replacement connection retires the live session silently`() {
        val (a, b) = twoMembers()
        a.activate()
        b.activate()
        connect(a, b)
        router.pump()
        assertEquals(PeerPhase.UP_TO_DATE, a.phase(ep(b)))
        assertEquals(1, a.sent().count { it is Hello })
        // The transport reports another connection for the same endpoint id with no Disconnected before it.
        connect(a, b)
        router.pump()
        assertEquals(2, a.sent().count { it is Hello })
        assertEquals(0, a.sent().count { it is Close })
        assertEquals(0, b.sent().count { it is Close })
        assertTrue(a.transport.disconnected.isEmpty())
        assertTrue(b.transport.disconnected.isEmpty())
        assertEquals(1, a.coordinator.state.value.peers.size)
        assertEquals(PeerPhase.UP_TO_DATE, a.phase(ep(b)))
        assertEquals(PeerPhase.UP_TO_DATE, b.phase(ep(a)))
        // The retired session's timers are gone: nothing closes the replacement later.
        advance(PeerSession.HANDSHAKE_TIMEOUT_MS + PeerSession.WATCHDOG_INTERVAL_MS)
        assertEquals(PeerPhase.UP_TO_DATE, a.phase(ep(b)))
        assertEquals(1, a.coordinator.state.value.peers.size)
        assertTrue(a.transport.disconnected.isEmpty())
    }

    @Test
    fun `stale payload and disconnection cannot affect a replacement connection with the same endpoint id`() {
        val (a, b) = twoMembers()
        a.activate()
        b.activate()
        connect(a, b)
        router.pump()
        assertEquals(PeerPhase.UP_TO_DATE, a.phase(ep(b)))
        val previous = a.transport.connection(ep(b))
        connect(a, b)
        router.pump()
        val replacement = a.transport.connection(ep(b))
        assertTrue(previous !== replacement)
        assertEquals(previous.endpointId, replacement.endpointId)
        val progress = a.progress(ep(b))
        assertEquals(PeerPhase.UP_TO_DATE, progress?.phase)
        val framesBefore = a.transport.sentFrames.size

        val close = NearbyWire.encode(Close(NearbyWire.CLOSE_STOPPED))
        check(a.transport.flow.tryEmit(BleEvent.PayloadReceived(previous, close)))
        check(a.transport.flow.tryEmit(BleEvent.Disconnected(previous)))
        router.pump()
        assertEquals(progress, a.progress(ep(b)))
        assertEquals(framesBefore, a.transport.sentFrames.size)
        assertTrue(a.transport.disconnected.isEmpty())
        assertEquals(PeerPhase.UP_TO_DATE, b.phase(ep(a)))

        seed(a, "replacement", 1)
        a.touchStore()
        router.pump()
        assertTrue(idOf("replacement0") in b.store.events)
        assertEquals(PeerPhase.UP_TO_DATE, a.phase(ep(b)))
        assertEquals(PeerPhase.UP_TO_DATE, b.phase(ep(a)))
        check(a.transport.flow.tryEmit(BleEvent.Disconnected(replacement)))
        router.pump()
        assertEquals(PeerPhase.INTERRUPTED, a.phase(ep(b)))
        assertEquals(NearbyWire.CLOSE_PEER_DISCONNECTED, a.progress(ep(b))?.closeReason)
    }

    @Test
    fun `a replacement connection supersedes a session still waiting for Hello`() {
        val (a, b) = twoMembers()
        a.activate()
        val silent = silentTransport("silent")
        connectSilent(a, silent, ep(b))
        router.pump()
        assertEquals(PeerPhase.AUTHENTICATING, a.phase(ep(b)))
        advance(PeerSession.HANDSHAKE_TIMEOUT_MS - 1_000)
        b.activate()
        connect(a, b)
        router.pump()
        assertEquals(PeerPhase.UP_TO_DATE, a.phase(ep(b)))
        assertEquals(0, a.sent().count { it is Close })
        assertTrue(a.transport.disconnected.isEmpty())
        assertEquals(1, a.coordinator.state.value.peers.size)
        advance(PeerSession.HANDSHAKE_TIMEOUT_MS + PeerSession.WATCHDOG_INTERVAL_MS)
        assertEquals(PeerPhase.UP_TO_DATE, a.phase(ep(b)))
    }

    @Test
    fun `a session whose Hello cannot be sent ends interrupted and leaves nothing running`() {
        val (a, b) = twoMembers()
        a.activate()
        b.activate()
        a.transport.failNextSends = 1
        connect(a, b)
        router.pump()
        val progress = checkNotNull(a.progress(ep(b)))
        assertEquals(PeerPhase.INTERRUPTED, progress.phase)
        assertEquals(NearbyWire.CLOSE_TRANSPORT_ERROR, progress.closeReason)
        assertTrue(a.transport.sentFrames.isEmpty())
        assertEquals(listOf(ep(b)), a.transport.disconnected)
        assertEquals(PeerPhase.INTERRUPTED, b.phase(ep(a)))
        // No watchdog survives: the handshake timeout passes without any further effect.
        advance(PeerSession.HANDSHAKE_TIMEOUT_MS + PeerSession.WATCHDOG_INTERVAL_MS)
        assertTrue(a.transport.sentFrames.isEmpty())
        assertEquals(listOf(ep(b)), a.transport.disconnected)
        assertEquals(NearbyWire.CLOSE_TRANSPORT_ERROR, a.progress(ep(b))?.closeReason)
        // The coordinator is still active and the next connection works.
        assertTrue(a.coordinator.state.value.active)
        connect(a, b)
        router.pump()
        assertEquals(PeerPhase.UP_TO_DATE, a.phase(ep(b)))
        assertEquals(PeerPhase.UP_TO_DATE, b.phase(ep(a)))
    }

    @Test
    fun `transport errors never end a session and a disconnection ends only its own session`() {
        val (a, b, c) = threeMembers()
        seed(b, "eb", 3)
        listOf(a, b, c).forEach { it.activate() }
        connect(a, b)
        connect(a, c)
        router.pump()
        assertEquals(PeerPhase.UP_TO_DATE, a.phase(ep(b)))
        assertEquals(PeerPhase.UP_TO_DATE, a.phase(ep(c)))
        assertEquals(3, a.progress(ep(b))?.stats?.applied)

        // Every error is left to receipts and timeouts: the transport reports a lost link as Disconnected.
        sendError(a, ep(b), RadioFailureKind.PAYLOAD)
        sendError(a, ep(b), null)
        sendError(a, null, RadioFailureKind.ENDPOINT)
        sendError(a, ep(b), RadioFailureKind.ENDPOINT)
        sendError(a, ep(b), RadioFailureKind.ENDPOINT, operation = "connection_result")
        router.pump()
        assertEquals(PeerPhase.UP_TO_DATE, a.phase(ep(b)))
        assertEquals(PeerPhase.UP_TO_DATE, a.phase(ep(c)))

        check(a.transport.flow.tryEmit(BleEvent.Disconnected(a.transport.connection(ep(b)))))
        router.pump()
        val lost = checkNotNull(a.progress(ep(b)))
        assertEquals(PeerPhase.INTERRUPTED, lost.phase)
        assertEquals(NearbyWire.CLOSE_PEER_DISCONNECTED, lost.closeReason)
        assertEquals(3, lost.stats.applied)
        assertEquals(PeerPhase.UP_TO_DATE, a.phase(ep(c)))
        assertEquals(PeerPhase.UP_TO_DATE, c.phase(ep(a)))
        assertTrue(a.sent().none { it is Close })
        // The surviving session still reconciles.
        val appliedBefore = checkNotNull(c.progress(ep(a))).stats.applied
        seed(a, "after", 1)
        a.touchStore()
        router.pump()
        assertTrue(idOf("after0") in c.store.events)
        assertEquals(appliedBefore + 1, c.progress(ep(a))?.stats?.applied)
        assertEquals(PeerPhase.UP_TO_DATE, a.phase(ep(c)))
    }

    @Test
    fun `peer key is published only after its signature verifies`() {
        val (a, b) = twoMembers()
        a.transport.dropIf = { it is Auth }
        b.transport.dropIf = { it is Auth }
        a.activate()
        b.activate()
        connect(a, b)
        router.pump()
        // Both sides processed the peer's Hello (each answered with Auth) but neither Auth arrived.
        assertTrue(a.sent().any { it is Auth })
        assertTrue(b.sent().any { it is Auth })
        assertEquals(PeerPhase.AUTHENTICATING, a.phase(ep(b)))
        assertNull(a.progress(ep(b))?.peerPubkey)
        assertNull(b.progress(ep(a))?.peerPubkey)

        val (c, d) = twoMembers(3, 4)
        c.activate()
        d.activate()
        connect(c, d, sameToken = false)
        router.pump()
        assertEquals(PeerPhase.AUTH_FAILED, c.phase(ep(d)))
        assertNull(c.progress(ep(d))?.peerPubkey)
        assertNull(d.progress(ep(c))?.peerPubkey)
    }

    @Test
    fun `verified peer key stays visible after the session ends`() {
        val (a, b) = twoMembers()
        a.activate()
        b.activate()
        connect(a, b)
        router.pump()
        assertEquals(b.pub, a.progress(ep(b))?.peerPubkey)
        a.deactivate()
        router.pump()
        assertEquals(PeerPhase.CLOSED, a.phase(ep(b)))
        assertEquals(b.pub, a.progress(ep(b))?.peerPubkey)
    }

    // ------------------------------------------------------------------ transfer

    private fun seed(peer: Peer, prefix: String, n: Int) {
        repeat(n) { i -> peer.store.putEvent(idOf("$prefix$i")) }
    }

    private fun idOf(seed: String): String {
        val md = java.security.MessageDigest.getInstance("SHA-256").digest(seed.toByteArray())
        return md.joinToString("") { "%02x".format(it) }
    }

    @Test
    fun `31 events from one author and 61 across authors all apply`() {
        val (a, b) = twoMembers()
        seed(a, "x", 31)
        a.activate()
        b.activate()
        connect(a, b)
        router.pump()
        assertEquals(31, b.store.events.size)
        assertEquals(31, b.progress(ep(a))?.stats?.applied)
        assertEquals(31, a.progress(ep(b))?.stats?.sent)
        assertEquals(PeerPhase.UP_TO_DATE, a.phase(ep(b)))
        assertEquals(PeerPhase.UP_TO_DATE, b.phase(ep(a)))

        val (c, d) = twoMembers(3, 4)
        seed(c, "y", 61)
        c.activate()
        d.activate()
        connect(c, d)
        router.pump()
        assertEquals(61, d.store.events.size)
        assertEquals(PeerPhase.UP_TO_DATE, d.phase(ep(c)))
    }

    @Test
    fun `both directions converge and nothing is offered twice`() {
        val (a, b) = twoMembers()
        seed(a, "a", 40)
        seed(b, "b", 25)
        a.activate()
        b.activate()
        connect(a, b)
        router.pump()
        assertEquals(65, a.store.events.size)
        assertEquals(65, b.store.events.size)
        assertEquals(a.store.events.keys, b.store.events.keys)
        assertEquals(25, a.progress(ep(b))?.stats?.applied)
        assertEquals(40, b.progress(ep(a))?.stats?.applied)
        assertEquals(0, a.progress(ep(b))?.stats?.alreadyApplied)
    }

    @Test
    fun `inventory beyond the single payload bound is paged and every frame fits`() {
        val (a, b) = twoMembers()
        seed(a, "big", 16_000)
        a.activate()
        b.activate()
        connect(a, b)
        router.pump()
        assertEquals(16_000, b.store.events.size)
        assertEquals(PeerPhase.UP_TO_DATE, b.phase(ep(a)))
        val frames = a.transport.sentFrames + b.transport.sentFrames
        assertTrue(frames.all { it.second.size <= NearbyWire.MAX_FRAME_BYTES })
        val pages = a.transport.sentMessages().filterIsInstance<InventoryPage>()
        assertTrue("expected many pages, got ${pages.size}", pages.size >= 16_000 / NearbyWire.MAX_INVENTORY_PAGE_ITEMS)
        assertTrue(pages.all { it.items.size <= NearbyWire.MAX_INVENTORY_PAGE_ITEMS })
    }

    @Test
    fun `signed original upgrades a rumor without a second ledger entry`() {
        val (a, b) = twoMembers()
        val id = idOf("shared")
        a.store.putEvent(id, verifiable = true)
        b.store.putEvent(id, verifiable = false) // B holds only a gift-wrapped rumor
        a.activate()
        b.activate()
        connect(a, b)
        router.pump()
        assertEquals(1, b.store.events.size)
        assertTrue(b.store.events[id]!!.verifiable)
        assertEquals(1, b.progress(ep(a))?.stats?.upgraded)
        assertEquals(1, b.progress(ep(a))?.stats?.alreadyApplied)
        assertEquals(PeerPhase.UP_TO_DATE, b.phase(ep(a)))
    }

    @Test
    fun `a dropped record frame is retried after the transfer timeout`() {
        val (a, b) = twoMembers()
        seed(a, "d", 5)
        var dropped = false
        a.transport.dropIf = { m ->
            m is Record &&
                !dropped &&
                m.part == 0 &&
                run {
                    dropped = true
                    true
                }
        }
        a.activate()
        b.activate()
        connect(a, b)
        router.pump()
        assertEquals(4, b.store.events.size)
        assertEquals(PeerPhase.TRANSFERRING, b.phase(ep(a)))
        advance(PeerSession.TRANSFER_TIMEOUT_MS + PeerSession.WATCHDOG_INTERVAL_MS)
        assertEquals(5, b.store.events.size)
        assertEquals(PeerPhase.UP_TO_DATE, b.phase(ep(a)))
        assertEquals(PeerPhase.UP_TO_DATE, a.phase(ep(b)))
    }

    @Test
    fun `dropped receipts do not break completion`() {
        val (a, b) = twoMembers()
        seed(a, "r", 10)
        b.transport.dropIf = { it is Result }
        a.activate()
        b.activate()
        connect(a, b)
        router.pump()
        assertEquals(10, b.store.events.size)
        assertEquals(PeerPhase.UP_TO_DATE, a.phase(ep(b)))
        assertEquals(PeerPhase.UP_TO_DATE, b.phase(ep(a)))
    }

    @Test
    fun `a lost ReconcileResult is recovered by re-advertising`() {
        val (a, b) = twoMembers()
        seed(a, "rr", 3)
        var drops = 1
        b.transport.dropIf = { m -> m is ReconcileResult && drops-- > 0 }
        a.activate()
        b.activate()
        connect(a, b)
        router.pump()
        assertEquals(PeerPhase.COMPARING, a.phase(ep(b)))
        advance(PeerSession.TRANSFER_TIMEOUT_MS + PeerSession.WATCHDOG_INTERVAL_MS)
        assertEquals(PeerPhase.UP_TO_DATE, a.phase(ep(b)))
        assertEquals(PeerPhase.UP_TO_DATE, b.phase(ep(a)))
    }

    @Test
    fun `rejected and unavailable records leave the round incomplete, never up to date`() {
        val (a, b) = twoMembers()
        seed(a, "q", 4)
        val bad = a.store.events.keys.first()
        val gone = a.store.events.keys.last()
        b.store.outcomes[bad] = RecordOutcome.REJECTED
        a.store.loadFailures += gone
        a.activate()
        b.activate()
        connect(a, b)
        router.pump()
        assertEquals(PeerPhase.INCOMPLETE, b.phase(ep(a)))
        assertEquals(PeerPhase.INCOMPLETE, a.phase(ep(b)))
        val s = b.progress(ep(a))!!.stats
        assertEquals(1, s.rejected)
        assertEquals(1, s.unresolved)
        assertEquals(2, s.applied)
    }

    @Test
    fun `deferred records surface as waiting for a dependency and retry when the key lands`() {
        val (a, b) = twoMembers()
        seed(a, "def", 3)
        val rotation = a.store.events.keys.first()
        b.store.outcomes[rotation] = RecordOutcome.DEFERRED
        a.activate()
        b.activate()
        connect(a, b)
        router.pump()
        assertEquals(PeerPhase.WAITING_DEPENDENCY, b.phase(ep(a)))
        assertEquals(1, b.progress(ep(a))?.stats?.deferred)
        // Now a control record applies and the store reports the deferred row caught up.
        val ctl = idOf("control")
        a.store.putEvent(ctl)
        b.store.controlIds += ctl
        b.store.retryReturns = 1
        a.touchStore() // local change observed on A -> re-advertise
        router.pump()
        assertEquals(PeerPhase.UP_TO_DATE, b.phase(ep(a)))
        assertEquals(0, b.progress(ep(a))?.stats?.deferred)
    }

    @Test
    fun `records rejected before their key arrives are re-requested once the key lands`() {
        val (a, b) = twoMembers()
        // A advertises an expense (encrypted under a key B lacks) and then the key.
        val expense = idOf("epoch1-expense")
        val key = idOf("rotation")
        a.store.putEvent(expense)
        a.store.putEvent(key)
        b.store.controlIds += key
        var rejections = 0
        b.store.outcomes[expense] = RecordOutcome.REJECTED
        // Once the key is ingested the expense becomes decryptable: clear the scripted rejection.
        val realIngest = b.store
        a.activate()
        b.activate()
        connect(a, b)
        // Pump frame by frame; when the key is ingested, drop the scripted rejection.
        while (router.step()) {
            if (key in realIngest.ingested && expense in realIngest.outcomes) {
                rejections = realIngest.ingested.count { it == expense }
                realIngest.outcomes.remove(expense)
            }
        }
        assertTrue("expense must have been rejected once before the key", rejections >= 1)
        assertTrue(expense in b.store.events)
        assertEquals(PeerPhase.UP_TO_DATE, b.phase(ep(a)))
        assertEquals(0, b.progress(ep(a))?.stats?.rejected)
        assertEquals(2, b.progress(ep(a))?.stats?.applied)
    }

    @Test
    fun `a record refused for a missing key is re-requested when the key arrives in a later snapshot`() {
        val (a, b) = twoMembers()
        val expense = idOf("epoch1-expense")
        val key = idOf("late-rotation")
        a.store.putEvent(expense)
        b.store.controlIds += key
        b.store.outcomes[expense] = RecordOutcome.REJECTED
        a.activate()
        b.activate()
        connect(a, b)
        router.pump()
        assertEquals(PeerPhase.INCOMPLETE, b.phase(ep(a)))
        assertEquals(1, b.progress(ep(a))?.stats?.rejected)
        val requestedBefore = b.transport.sentMessages().filterIsInstance<Want>().flatMap { it.ids }.count {
            it ==
                expense
        }
        assertEquals(1, requestedBefore)

        // The key lands on A only now, in a new snapshot; once B applies it the expense becomes readable.
        a.store.putEvent(key)
        b.store.outcomes.remove(expense)
        a.touchStore()
        router.pump()

        val requestedAfter = b.transport.sentMessages().filterIsInstance<Want>().flatMap { it.ids }.count {
            it ==
                expense
        }
        assertEquals(2, requestedAfter)
        assertTrue(expense in b.store.events)
        assertEquals(0, b.progress(ep(a))?.stats?.rejected)
        assertEquals(PeerPhase.UP_TO_DATE, b.phase(ep(a)))
        assertEquals(PeerPhase.UP_TO_DATE, a.phase(ep(b)))
    }

    @Test
    fun `a permanently rejected record is not re-requested when a key lands`() {
        val (a, b) = twoMembers()
        val bad = idOf("malformed")
        val key = idOf("rotation")
        a.store.putEvent(bad)
        a.store.putEvent(key)
        b.store.controlIds += key
        b.store.outcomes[bad] = RecordOutcome.REJECTED
        b.store.permanentRejects += bad
        a.activate()
        b.activate()
        connect(a, b)
        router.pump()

        assertEquals(1, b.transport.sentMessages().filterIsInstance<Want>().flatMap { it.ids }.count { it == bad })
        assertEquals(1, b.progress(ep(a))?.stats?.rejected)
        assertEquals(PeerPhase.INCOMPLETE, b.phase(ep(a)))
    }

    @Test
    fun `disconnect after zero applied records is interrupted, not up to date`() {
        val (a, b) = twoMembers()
        seed(a, "z", 30)
        b.transport.dropIf = { it is Record } // nothing ever arrives at A from B (B has nothing anyway)
        a.transport.dropIf = { it is Record } // and A's records never reach B
        a.activate()
        b.activate()
        connect(a, b)
        router.pump()
        assertEquals(PeerPhase.TRANSFERRING, b.phase(ep(a)))
        router.disconnect(b.transport, ep(a), notifyPeer = true)
        router.pump()
        assertEquals(PeerPhase.INTERRUPTED, b.phase(ep(a)))
        assertEquals(PeerPhase.INTERRUPTED, a.phase(ep(b)))
        assertEquals(0, b.progress(ep(a))?.stats?.applied)
        assertTrue(b.progress(ep(a))!!.stats.unresolved > 0)
        assertTrue(a.coordinator.state.value.peers.values.none { !it.phase.isTerminalForTest() })
    }

    @Test
    fun `reconnect resumes from durable progress without duplicates`() {
        val (a, b) = twoMembers()
        seed(a, "res", 40)
        a.activate()
        b.activate()
        connect(a, b)
        // Cut the scripted exchange after partial delivery. Router queues already-enqueued frames before
        // its Disconnected event; this is one tested order, not an SDK ordering guarantee.
        var frames = 0
        while (router.step()) {
            if (++frames == 60) break
        }
        router.disconnect(a.transport, ep(b), notifyPeer = true)
        router.pump()
        assertEquals(PeerPhase.INTERRUPTED, b.phase(ep(a)))
        val partial = b.store.events.size
        assertTrue("partial=$partial", partial in 1 until 40)
        connect(a, b)
        router.pump()
        assertEquals(40, b.store.events.size)
        assertEquals(PeerPhase.UP_TO_DATE, b.phase(ep(a)))
        assertEquals(40 - partial, b.progress(ep(a))?.stats?.applied)
        assertEquals(0, b.progress(ep(a))?.stats?.alreadyApplied)
    }

    // ------------------------------------------------------------------ forwarding & propagation

    @Test
    fun `B carries C's envelope from A and delivers it later with A offline`() {
        val ids = (1..3).map { TestIdentity(it).pub }
        val members = ids.toMutableSet()
        val a = Peer(1, members.toMutableSet(), creator = ids[0])
        val b = Peer(2, members.toMutableSet(), creator = ids[0])
        val c = Peer(3, members.toMutableSet(), creator = ids[0])
        val env = idOf("envelope-for-c")
        a.store.putDelivery(env, recipient = c.pub, hint = idOf("inner"))
        a.activate()
        b.activate()
        connect(a, b)
        router.pump()
        assertEquals(PeerPhase.UP_TO_DATE, b.phase(ep(a)))
        assertEquals(1, b.progress(ep(a))?.stats?.carried)
        assertEquals(0, b.progress(ep(a))?.stats?.applied)
        assertTrue(b.store.deliveries[env]!!.recipient == c.pub && !b.store.deliveries[env]!!.consumed)
        a.deactivate()
        router.pump()

        c.activate()
        connect(b, c)
        router.pump()
        assertEquals(PeerPhase.UP_TO_DATE, c.phase(ep(b)))
        assertEquals(1, c.progress(ep(b))?.stats?.applied)
        assertTrue(c.store.deliveries[env]!!.consumed)
        assertTrue(idOf("inner") in c.store.events)
    }

    @Test
    fun `already connected B forwards A's new data to C without a reconnect`() {
        val ids = (1..3).map { TestIdentity(it).pub }
        val members = ids.toMutableSet()
        val a = Peer(1, members.toMutableSet(), creator = ids[0])
        val b = Peer(2, members.toMutableSet(), creator = ids[0])
        val c = Peer(3, members.toMutableSet(), creator = ids[0])
        b.activate()
        c.activate()
        connect(b, c)
        router.pump()
        assertEquals(PeerPhase.UP_TO_DATE, c.phase(ep(b)))

        val fresh = idOf("fresh-from-a")
        a.store.putEvent(fresh)
        a.activate()
        connect(a, b)
        router.pump()
        assertTrue(fresh in b.store.events)
        assertTrue("C must receive A's record through B while staying connected", fresh in c.store.events)
        assertEquals(PeerPhase.UP_TO_DATE, c.phase(ep(b)))
        assertEquals(PeerPhase.UP_TO_DATE, b.phase(ep(c)))
        assertEquals(1, c.progress(ep(b))?.stats?.applied)
    }

    @Test
    fun `a cycle converges with bounded traffic`() {
        val ids = (1..3).map { TestIdentity(it).pub }
        val members = ids.toMutableSet()
        val a = Peer(1, members.toMutableSet(), creator = ids[0])
        val b = Peer(2, members.toMutableSet(), creator = ids[0])
        val c = Peer(3, members.toMutableSet(), creator = ids[0])
        seed(a, "cyc", 12)
        listOf(a, b, c).forEach { it.activate() }
        connect(a, b)
        connect(b, c)
        connect(c, a)
        val moved = router.pump(maxFrames = 20_000)
        assertTrue("traffic must terminate, moved $moved", moved < 20_000)
        assertEquals(0, router.pending())
        listOf(a, b, c).forEach { assertEquals(12, it.store.events.size) }
        listOf(a, b, c).forEach { p ->
            p.coordinator.state.value.peers.values.forEach { assertEquals(PeerPhase.UP_TO_DATE, it.phase) }
        }
    }

    @Test
    fun `envelopes for non-members and wrong recipients are rejected and quota bounds carrying`() {
        val (a, b) = twoMembers()
        val outsider = TestIdentity(7).pub
        a.store.putDelivery(idOf("for-outsider"), recipient = outsider)
        a.activate()
        b.activate()
        connect(a, b)
        router.pump()
        // This fixture covers selection only: B does not request an outsider's envelope. It does not test quota.
        assertEquals(0, b.progress(ep(a))?.stats?.carried)
        assertEquals(PeerPhase.UP_TO_DATE, b.phase(ep(a)))
        assertFalse(idOf("for-outsider") in b.store.deliveries)
    }

    @Test
    fun `progress state distinguishes transferred, applied, carried and unresolved`() {
        val (a, b) = twoMembers()
        seed(a, "st", 3)
        val c = TestIdentity(3).pub
        a.store.members += c
        b.store.members += c
        a.store.putDelivery(idOf("env"), recipient = c)
        a.activate()
        b.activate()
        connect(a, b)
        router.pump()
        val s = b.progress(ep(a))!!.stats
        assertEquals(4, s.received)
        assertEquals(3, s.applied)
        assertEquals(1, s.carried)
        assertEquals(0, s.unresolved)
        assertEquals(4, a.progress(ep(b))!!.stats.sent)
        assertNotNull(a.progress(ep(b)))
        assertNull(a.progress("nope"))
    }

    // ------------------------------------------------------------ recovery regressions

    @Test
    fun `watchdog inventory failure interrupts only its session and reconnects without leaking timers`() {
        var failInventory = false
        var failedReads = 0
        val members = (1..3).map { TestIdentity(it).pub }.toMutableSet()
        val a = Peer(1, members.toMutableSet(), decorateStore = { delegate ->
            object : ReconciliationStore by delegate {
                override suspend fun inventory(groupId: String): List<InventoryItem> {
                    if (failInventory) {
                        failedReads++
                        throw IllegalStateException("inventory unavailable")
                    }
                    return delegate.inventory(groupId)
                }
            }
        })
        val b = Peer(2, members.toMutableSet())
        val c = Peer(3, members.toMutableSet())
        listOf(a, b, c).forEach { it.activate() }
        b.transport.dropIf = { it is ReconcileResult }
        connect(a, b)
        connect(a, c)
        router.pump()
        assertEquals(PeerPhase.COMPARING, a.phase(ep(b)))
        assertEquals(PeerPhase.UP_TO_DATE, a.phase(ep(c)))
        val parent = checkNotNull(runParent(a))
        val sessions = parent.children.toList()
        assertEquals(2, sessions.size)
        val oldConnection = a.transport.connection(ep(b))

        failInventory = true
        advance(PeerSession.TRANSFER_TIMEOUT_MS + PeerSession.WATCHDOG_INTERVAL_MS)
        assertSessionError(a, b)
        assertEquals(1, failedReads)
        assertEquals(1, sessions.count { it.isCompleted })
        assertEquals(1, parent.children.count())
        assertEquals(PeerPhase.UP_TO_DATE, a.phase(ep(c)))
        val frameCount = a.transport.sentFrames.size
        advance(PeerSession.TRANSFER_TIMEOUT_MS * 4)
        assertEquals(1, failedReads)
        assertEquals(frameCount, a.transport.sentFrames.size)

        failInventory = false
        b.transport.dropIf = null
        connect(a, b)
        router.pump()
        check(a.transport.flow.tryEmit(BleEvent.Disconnected(oldConnection)))
        check(
            a.transport.flow.tryEmit(
                BleEvent.PayloadReceived(oldConnection, NearbyWire.encode(Close(NearbyWire.CLOSE_STOPPED)))
            )
        )
        seed(a, "after-watchdog-failure", 1)
        a.touchStore()
        router.pump()
        assertEquals(PeerPhase.UP_TO_DATE, a.phase(ep(b)))
        assertEquals(PeerPhase.UP_TO_DATE, a.phase(ep(c)))
        assertTrue(idOf("after-watchdog-failure0") in b.store.events)
        assertTrue(idOf("after-watchdog-failure0") in c.store.events)
        assertEquals(2, parent.children.count())
        assertEquals(listOf(ep(b)), a.transport.disconnected)
    }

    @Test
    fun `notifyGroupChanged inventory failure terminates the session rather than escaping or retrying forever`() {
        var failInventory = false
        var failedReads = 0
        val members = mutableSetOf(TestIdentity(1).pub, TestIdentity(2).pub)
        val a = Peer(1, members.toMutableSet(), decorateStore = { delegate ->
            object : ReconciliationStore by delegate {
                override suspend fun inventory(groupId: String): List<InventoryItem> {
                    if (failInventory) {
                        failedReads++
                        throw IllegalStateException("inventory unavailable")
                    }
                    return delegate.inventory(groupId)
                }
            }
        })
        val b = Peer(2, members.toMutableSet())
        a.activate()
        b.activate()
        connect(a, b)
        router.pump()
        assertEquals(PeerPhase.UP_TO_DATE, a.phase(ep(b)))
        val parent = checkNotNull(runParent(a))
        val session = parent.children.single()

        failInventory = true
        a.coordinator.notifyGroupChanged(groupId)
        settle()
        assertSessionError(a, b)
        assertEquals(1, failedReads)
        assertTrue(session.isCompleted)
        assertFalse(parent.children.any())
        val frameCount = a.transport.sentFrames.size
        a.coordinator.notifyGroupChanged(groupId)
        a.touchStore()
        advance(PeerSession.TRANSFER_TIMEOUT_MS * 4)
        assertEquals(1, failedReads)
        assertEquals(frameCount, a.transport.sentFrames.size)
        assertTrue(a.deactivate().isCompleted)
        assertTrue(parent.isCompleted)
    }

    @Test
    fun `notifyGroupChanged isolates a failing peer and still refreshes healthy peers`() {
        dirtyFailureIsIsolated(retryFails = false, notifyExplicitly = true)
    }

    @Test
    fun `store observer isolates a failing peer without a second dirty retry`() {
        dirtyFailureIsIsolated(retryFails = false, notifyExplicitly = false)
    }

    @Test
    fun `store retry failure still refreshes healthy peers when fallback dirty work also fails`() {
        dirtyFailureIsIsolated(retryFails = true, notifyExplicitly = false)
    }

    private fun dirtyFailureIsIsolated(retryFails: Boolean, notifyExplicitly: Boolean) {
        var failReads = false
        var failedReads = 0
        var failedRetries = 0
        val members = (1..3).map { TestIdentity(it).pub }.toMutableSet()
        val bKey = TestIdentity(2).pub
        val a = Peer(1, members.toMutableSet(), decorateStore = { delegate ->
            object : ReconciliationStore by delegate {
                override suspend fun isAuthorizedForGroup(groupId: String, peerPubkey: String): Boolean {
                    if (failReads && peerPubkey == bKey) {
                        failedReads++
                        throw IllegalStateException("membership unavailable for peer")
                    }
                    return delegate.isAuthorizedForGroup(groupId, peerPubkey)
                }

                override suspend fun retryDeferred(groupId: String): Int {
                    if (failReads && retryFails) {
                        failedRetries++
                        throw IllegalStateException("dependency recovery unavailable")
                    }
                    return delegate.retryDeferred(groupId)
                }
            }
        })
        val b = Peer(2, members.toMutableSet())
        val c = Peer(3, members.toMutableSet())
        listOf(a, b, c).forEach { it.activate() }
        connect(a, b)
        connect(a, c)
        router.pump()
        assertEquals(PeerPhase.UP_TO_DATE, a.phase(ep(b)))
        assertEquals(PeerPhase.UP_TO_DATE, a.phase(ep(c)))
        val parent = checkNotNull(runParent(a))
        val sessions = parent.children.toList()
        assertEquals(2, sessions.size)

        failReads = true
        seed(a, "healthy-peer", 1)
        if (notifyExplicitly) a.coordinator.notifyGroupChanged(groupId) else a.touchStore()
        router.pump()
        assertSessionError(a, b)
        assertEquals(1, failedReads)
        assertEquals(if (retryFails) 1 else 0, failedRetries)
        assertEquals(1, sessions.count { it.isCompleted })
        assertEquals(1, parent.children.count())
        assertEquals(PeerPhase.UP_TO_DATE, a.phase(ep(c)))
        assertTrue(idOf("healthy-peer0") in c.store.events)
        assertFalse(idOf("healthy-peer0") in b.store.events)

        seed(a, "observer-still-alive", 1)
        a.touchStore()
        router.pump()
        assertEquals(1, failedReads)
        assertEquals(if (retryFails) 2 else 0, failedRetries)
        assertTrue(idOf("observer-still-alive0") in c.store.events)
        assertEquals(PeerPhase.UP_TO_DATE, a.phase(ep(c)))
        assertEquals(listOf(ep(b)), a.transport.disconnected)
    }

    @Test
    fun `data changed callback contains another peers dirty failure without interrupting the source session`() {
        var failReads = false
        val members = (1..3).map { TestIdentity(it).pub }.toMutableSet()
        val bKey = TestIdentity(2).pub
        val a = Peer(1, members.toMutableSet(), decorateStore = { delegate ->
            object : ReconciliationStore by delegate {
                override suspend fun isAuthorizedForGroup(groupId: String, peerPubkey: String): Boolean {
                    check(!failReads || peerPubkey != bKey) { "membership unavailable for peer" }
                    return delegate.isAuthorizedForGroup(groupId, peerPubkey)
                }
            }
        })
        val b = Peer(2, members.toMutableSet())
        val c = Peer(3, members.toMutableSet())
        listOf(a, b, c).forEach { it.activate() }
        connect(a, b)
        connect(a, c)
        router.pump()
        assertEquals(PeerPhase.UP_TO_DATE, a.phase(ep(b)))
        assertEquals(PeerPhase.UP_TO_DATE, a.phase(ep(c)))

        failReads = true
        seed(c, "source-peer", 1)
        c.touchStore()
        router.pump()
        assertSessionError(a, b)
        assertTrue(idOf("source-peer0") in a.store.events)
        assertEquals(PeerPhase.UP_TO_DATE, a.phase(ep(c)))
        assertEquals(PeerPhase.UP_TO_DATE, c.phase(ep(a)))
        assertEquals(1, checkNotNull(runParent(a)).children.count())
    }

    @Test
    fun `frame inventory failure interrupts the affected session instead of only logging it`() {
        var failInventory = true
        val members = mutableSetOf(TestIdentity(1).pub, TestIdentity(2).pub)
        val a = Peer(1, members.toMutableSet(), decorateStore = { delegate ->
            object : ReconciliationStore by delegate {
                override suspend fun inventory(groupId: String): List<InventoryItem> {
                    check(!failInventory) { "inventory unavailable" }
                    return delegate.inventory(groupId)
                }
            }
        })
        val b = Peer(2, members.toMutableSet())
        a.activate()
        b.activate()
        connect(a, b)
        router.pump()
        assertSessionError(a, b)
        assertFalse(checkNotNull(runParent(a)).children.any())

        failInventory = false
        connect(a, b)
        router.pump()
        assertEquals(PeerPhase.UP_TO_DATE, a.phase(ep(b)))
        assertEquals(PeerPhase.UP_TO_DATE, b.phase(ep(a)))
    }

    @Test
    fun `deactivate cancels watchdog inventory work and joins cleanup without a session error`() {
        val std = StandardTestDispatcher(scheduler)
        val members = mutableSetOf(TestIdentity(1).pub, TestIdentity(2).pub)
        val gate = CompletableDeferred<Unit>()
        var holdInventory = false
        var entered = false
        var cancelled = false
        val a = Peer(1, members.toMutableSet(), dispatcher = std, decorateStore = { delegate ->
            object : ReconciliationStore by delegate {
                override suspend fun inventory(groupId: String): List<InventoryItem> {
                    if (holdInventory) {
                        entered = true
                        try {
                            gate.await()
                        } finally {
                            cancelled = !gate.isCompleted
                        }
                    }
                    return delegate.inventory(groupId)
                }
            }
        })
        val b = Peer(2, members.toMutableSet(), dispatcher = std)
        a.activate()
        b.activate()
        settle()
        b.transport.dropIf = { it is ReconcileResult }
        connect(a, b)
        settle()
        assertEquals(PeerPhase.COMPARING, a.phase(ep(b)))
        val parent = checkNotNull(runParent(a))
        val session = parent.children.single()
        holdInventory = true
        advance(PeerSession.TRANSFER_TIMEOUT_MS + PeerSession.WATCHDOG_INTERVAL_MS)
        assertTrue(entered)
        assertFalse(gate.isCompleted)

        val deactivation = a.deactivate()
        settle()
        assertTrue(cancelled)
        assertTrue(deactivation.isCompleted)
        assertTrue(session.isCompleted)
        assertTrue(parent.isCompleted)
        assertFalse(a.coordinator.state.value.active)
        assertEquals(PeerPhase.CLOSED, a.phase(ep(b)))
        assertEquals(NearbyWire.CLOSE_STOPPED, a.progress(ep(b))?.closeReason)
        assertFalse(a.sent().filterIsInstance<Close>().any { it.reason == NearbyWire.CLOSE_SESSION_ERROR })
        val frames = a.transport.sentFrames.size
        gate.complete(Unit)
        settle()
        advance(PeerSession.TRANSFER_TIMEOUT_MS * 4)
        assertEquals(frames, a.transport.sentFrames.size)
        assertEquals(listOf(ep(b)), a.transport.disconnected)
    }

    private fun assertSessionError(peer: Peer, other: Peer) {
        val progress = checkNotNull(peer.progress(ep(other)))
        assertEquals(PeerPhase.INTERRUPTED, progress.phase)
        assertEquals(NearbyWire.CLOSE_SESSION_ERROR, progress.closeReason)
        assertEquals(other.pub, progress.peerPubkey)
        assertEquals(listOf(ep(other)), peer.transport.disconnected)
        assertEquals(1, peer.sent().filterIsInstance<Close>().count { it.reason == NearbyWire.CLOSE_SESSION_ERROR })
        assertTrue(peer.coordinator.state.value.active)
        assertTrue(checkNotNull(runParent(peer)).isActive)
        assertTrue(checkNotNull(peer.scope.coroutineContext[Job]).isActive)
        assertTrue("Operational failure escaped: $failures", failures.isEmpty())
    }

    @Test
    fun `a lost first inventory page is resent after the timeout, never an empty delta`() {
        val (a, b) = twoMembers()
        seed(a, "lost", 5)
        var drops = 1
        a.transport.dropIf = { m -> m is InventoryPage && drops-- > 0 }
        a.activate()
        b.activate()
        connect(a, b)
        router.pump()
        // B never saw A's inventory; neither side may claim to be done.
        assertEquals(0, b.store.events.size)
        assertEquals(PeerPhase.COMPARING, a.phase(ep(b)))
        assertEquals(PeerPhase.COMPARING, b.phase(ep(a)))
        advance(PeerSession.TRANSFER_TIMEOUT_MS + PeerSession.WATCHDOG_INTERVAL_MS)
        assertEquals(5, b.store.events.size)
        assertEquals(PeerPhase.UP_TO_DATE, a.phase(ep(b)))
        assertEquals(PeerPhase.UP_TO_DATE, b.phase(ep(a)))
        // The retry carried the records, not an empty snapshot.
        val resent = a.transport.sentMessages().filterIsInstance<InventoryPage>().last()
        assertEquals(5, resent.items.size)
    }

    @Test
    fun `a lost last page of a multi page inventory is recovered without loss`() {
        val (a, b) = twoMembers()
        val n = NearbyWire.MAX_INVENTORY_PAGE_ITEMS + 40
        seed(a, "pg", n)
        var drops = 1
        a.transport.dropIf = { m -> m is InventoryPage && m.last && drops-- > 0 }
        a.activate()
        b.activate()
        connect(a, b)
        router.pump()
        assertEquals(0, b.store.events.size)
        assertFalse(a.phase(ep(b)) == PeerPhase.UP_TO_DATE)
        assertFalse(b.phase(ep(a)) == PeerPhase.UP_TO_DATE)
        advance(PeerSession.TRANSFER_TIMEOUT_MS + PeerSession.WATCHDOG_INTERVAL_MS)
        assertEquals(n, b.store.events.size)
        assertEquals(PeerPhase.UP_TO_DATE, a.phase(ep(b)))
        assertEquals(PeerPhase.UP_TO_DATE, b.phase(ep(a)))
    }

    @Test
    fun `a lost page after an acknowledged baseline only resends the unacknowledged delta`() {
        val (a, b) = twoMembers()
        seed(a, "base", 3)
        a.activate()
        b.activate()
        connect(a, b)
        router.pump()
        assertEquals(PeerPhase.UP_TO_DATE, b.phase(ep(a)))
        // New local data whose page is lost on the way.
        var drops = 1
        a.transport.dropIf = { m -> m is InventoryPage && drops-- > 0 }
        seed(a, "more", 2)
        a.touchStore()
        router.pump()
        assertEquals(3, b.store.events.size)
        assertEquals(PeerPhase.COMPARING, a.phase(ep(b)))
        advance(PeerSession.TRANSFER_TIMEOUT_MS + PeerSession.WATCHDOG_INTERVAL_MS)
        assertEquals(5, b.store.events.size)
        assertEquals(PeerPhase.UP_TO_DATE, a.phase(ep(b)))
        val resent = a.transport.sentMessages().filterIsInstance<InventoryPage>().last()
        assertEquals("only the two unacknowledged ids are resent", 2, resent.items.size)
    }

    @Test
    fun `pending work from an earlier session is retried at activation and keeps both sides waiting`() {
        val (a, b) = twoMembers()
        seed(a, "pw", 2)
        // Model pending work from a prior run; no process or database restart occurs.
        b.store.deferredPending = 1
        a.activate()
        b.activate()
        router.pump()
        assertTrue("activation must re-drive pending rows", b.store.retryCalls >= 1)
        connect(a, b)
        router.pump()
        assertEquals(2, b.store.events.size)
        assertEquals(PeerPhase.WAITING_DEPENDENCY, b.phase(ep(a)))
        assertEquals(1, b.progress(ep(a))?.stats?.deferred)
        assertEquals("A learns from B's report that B is still waiting", PeerPhase.WAITING_DEPENDENCY, a.phase(ep(b)))
        assertFalse(a.progress(ep(b))?.phase == PeerPhase.UP_TO_DATE)
        // The dependency arrives; the pending row applies and both sides converge.
        val ctl = idOf("the-missing-epoch")
        a.store.putEvent(ctl)
        b.store.controlIds += ctl
        b.store.retryReturns = 1
        a.touchStore()
        router.pump()
        assertEquals(0, b.progress(ep(a))?.stats?.deferred)
        assertEquals(PeerPhase.UP_TO_DATE, b.phase(ep(a)))
        assertEquals(PeerPhase.UP_TO_DATE, a.phase(ep(b)))
    }

    @Test
    fun `a delivery only change is advertised to connected peers`() {
        val (a, b) = twoMembers()
        a.activate()
        b.activate()
        connect(a, b)
        router.pump()
        assertEquals(PeerPhase.UP_TO_DATE, b.phase(ep(a)))
        // History re-wrapped for B lands on A as an envelope, with no new ledger row.
        val env = idOf("rewrapped-for-b")
        a.store.putDelivery(env, recipient = b.pub)
        a.touchStore()
        router.pump()
        assertTrue("B must receive the envelope without a reconnect", env in b.store.deliveries)
        assertTrue(b.store.deliveries[env]!!.consumed)
        assertEquals(PeerPhase.UP_TO_DATE, b.phase(ep(a)))
        assertEquals(PeerPhase.UP_TO_DATE, a.phase(ep(b)))
    }

    @Test
    fun `pending-only changes refresh both connected peers without new inventory`() {
        val (a, b) = twoMembers()
        a.activate()
        b.activate()
        connect(a, b)
        router.pump()
        assertEquals(PeerPhase.UP_TO_DATE, a.phase(ep(b)))
        b.store.deferredPending = 1
        b.touchStore()
        router.pump()
        assertEquals(PeerPhase.WAITING_DEPENDENCY, b.phase(ep(a)))
        assertEquals(PeerPhase.WAITING_DEPENDENCY, a.phase(ep(b)))
        b.store.retryReturns = 1
        b.touchStore()
        router.pump()
        assertEquals(0, b.store.deferredPending)
        assertEquals(PeerPhase.UP_TO_DATE, b.phase(ep(a)))
        assertEquals(PeerPhase.UP_TO_DATE, a.phase(ep(b)))
    }

    @Test
    fun `unreadable durable pending state fails closed on both peers and recovers`() {
        val (a, b) = twoMembers()
        b.store.pendingReadFails = true
        a.activate()
        b.activate()
        connect(a, b)
        router.pump()
        assertEquals(PeerPhase.INCOMPLETE, b.phase(ep(a)))
        assertEquals(PeerPhase.INCOMPLETE, a.phase(ep(b)))
        assertTrue(b.transport.sentMessages().filterIsInstance<ReconcileResult>().last().unresolved > 0)
        assertTrue(b.transport.sentMessages().filterIsInstance<InventoryPage>().last().pending > 0)
        b.store.pendingReadFails = false
        b.touchStore()
        router.pump()
        assertEquals(PeerPhase.UP_TO_DATE, b.phase(ep(a)))
        assertEquals(PeerPhase.UP_TO_DATE, a.phase(ep(b)))
        assertEquals(0, b.progress(ep(a))?.stats?.unresolved)
    }

    @Test
    fun `pending readability changes invalidate a clean pair even when count stays zero`() {
        val (a, b) = twoMembers()
        a.activate()
        b.activate()
        connect(a, b)
        router.pump()
        assertEquals(PeerPhase.UP_TO_DATE, a.phase(ep(b)))
        b.store.pendingReadFails = true
        b.touchStore()
        router.pump()
        assertEquals(PeerPhase.INCOMPLETE, b.phase(ep(a)))
        assertEquals(PeerPhase.INCOMPLETE, a.phase(ep(b)))
        b.store.pendingReadFails = false
        b.touchStore()
        router.pump()
        assertEquals(PeerPhase.UP_TO_DATE, b.phase(ep(a)))
        assertEquals(PeerPhase.UP_TO_DATE, a.phase(ep(b)))
    }

    @Test
    fun `lost record receipts and round receipt terminate after bounded retry`() {
        val (a, b) = twoMembers()
        seed(a, "lost-all-receipts", 2)
        b.transport.dropIf = { it is Result || it is ReconcileResult }
        a.activate()
        b.activate()
        connect(a, b)
        router.pump()
        repeat(4) { advance(PeerSession.TRANSFER_TIMEOUT_MS + PeerSession.WATCHDOG_INTERVAL_MS) }
        assertEquals(PeerPhase.INTERRUPTED, a.phase(ep(b)))
        assertEquals(2, b.store.events.size)
    }

    private fun PeerPhase.isTerminalForTest() = this == PeerPhase.UNSUPPORTED_PEER ||
        this == PeerPhase.AUTH_FAILED ||
        this == PeerPhase.UNAUTHORIZED ||
        this == PeerPhase.INTERRUPTED ||
        this == PeerPhase.CLOSED
}
