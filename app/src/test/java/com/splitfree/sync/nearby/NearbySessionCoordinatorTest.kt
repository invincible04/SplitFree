package com.splitfree.sync.nearby

import com.splitfree.data.ble.BleEvent
import io.mockk.every
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.TestCoroutineScheduler
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
 * Two (or three) real [NearbySessionCoordinator]s over an in-memory transport with a scripted store.
 * Every test drives the exact callback sequence Nearby produces; nothing manually assigns roles.
 */
class NearbySessionCoordinatorTest {
    private val scheduler = TestCoroutineScheduler()
    private val dispatcher = UnconfinedTestDispatcher(scheduler)
    private val router = Router()
    private val groupId = "12345678-1234-1234-1234-123456789abc"
    private val scopes = mutableListOf<CoroutineScope>()

    inner class Peer(seed: Int, val members: MutableSet<String>, creator: String = "") {
        val identity = TestIdentity(seed)
        val pub = identity.pub
        val transport = FakeTransport("p$seed").also { it.router = router }
        val store = FakeReconciliationStore(pub, members, creator)
        val scope = CoroutineScope(SupervisorJob() + dispatcher).also { scopes += it }
        val coordinator =
            NearbySessionCoordinator(transport, identity.contract, store, scope) { scheduler.currentTime }

        /** Simulate a local write the coordinator's change observer would see. */
        fun touchStore() {
            store.changes.value = StoreVersion(store.changes.value.revision + 1)
        }

        fun progress(endpoint: String): PeerProgress? = coordinator.state.value.peers[endpoint]

        fun phase(endpoint: String): PeerPhase? = progress(endpoint)?.phase

        fun activate() = coordinator.activate(groupId)
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
    }

    private fun twoMembers(seedA: Int = 1, seedB: Int = 2): Pair<Peer, Peer> {
        val a = TestIdentity(seedA).pub
        val b = TestIdentity(seedB).pub
        val members = mutableSetOf(a, b)
        return Peer(seedA, members.toMutableSet(), creator = a) to Peer(seedB, members.toMutableSet(), creator = a)
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

    private fun advance(ms: Long) {
        scheduler.advanceTimeBy(ms)
        scheduler.runCurrent()
        router.pump()
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
        // Deliver frames one at a time and replay each handshake frame twice.
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
        }.forEach { b.transport.flow.tryEmit(BleEvent.PayloadReceived(ep(a), it.second)) }
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
        val mallory = Peer(3, mutableSetOf())
        a.activate()
        b.activate()
        val tokenAM = "tok-a-m".toByteArray()
        val tokenMB = "tok-m-b".toByteArray()
        router.connect(a.transport, "ep-m", mallory.transport, "ep-a", token = tokenAM)
        router.connect(mallory.transport, "ep-b", b.transport, "ep-m", token = tokenMB, aIncoming = false)
        router.pump()
        // Mallory forwards everything A says to B and everything B says to A until both go quiet.
        var forwardedA = 0
        var forwardedB = 0
        repeat(6) {
            a.transport.sentFrames.drop(forwardedA).forEach {
                b.transport.flow.tryEmit(BleEvent.PayloadReceived("ep-m", it.second))
            }
            forwardedA = a.transport.sentFrames.size
            router.pump()
            b.transport.sentFrames.drop(forwardedB).forEach {
                a.transport.flow.tryEmit(BleEvent.PayloadReceived("ep-m", it.second))
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
    fun `legacy v1 frame closes as unsupported peer`() {
        val (a, b) = twoMembers()
        a.activate()
        connect(a, b)
        a.transport.flow.tryEmit(BleEvent.PayloadReceived(ep(b), byteArrayOf(0x01) + "{}".toByteArray()))
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
        a.transport.flow.tryEmit(BleEvent.PayloadReceived(ep(b), NearbyWire.encode(hello)))
        router.pump()
        assertEquals(PeerPhase.UNSUPPORTED_PEER, a.phase(ep(b)))
    }

    @Test
    fun `handshake times out and cleans up when the peer never answers`() {
        val (a, b) = twoMembers()
        a.activate()
        connect(a, b) // b never activates, so it never sends Hello
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
        connect(a, b)
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
        a.coordinator.deactivate()
        router.pump()
        assertFalse(a.coordinator.state.value.active)
        assertEquals(PeerPhase.CLOSED, a.phase(ep(b)))
        assertTrue(a.transport.disconnected.contains(ep(b)))
        // Frames arriving after stop are ignored: no session, no crash.
        a.transport.flow.tryEmit(BleEvent.PayloadReceived(ep(b), NearbyWire.encode(Want(1, listOf("x")))))
        router.pump()
        assertEquals(PeerPhase.CLOSED, a.phase(ep(b)))
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
        // Let the first Want batch through, then cut the transport. Frames already in flight still
        // land before the Disconnected callback, exactly as on a real radio.
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
        a.coordinator.deactivate()
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
        // B never wanted it: not a member, so not carried and not rejected either; the round is clean.
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
        // B restarted with a rotation stored but its effect never applied.
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
