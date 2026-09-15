package com.splitfree.sync.nearby

import com.splitfree.data.ble.BleEvent
import com.splitfree.domain.crypto.NostrEvent
import com.splitfree.domain.repository.IdentityContract
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableSharedFlow

/**
 * In-memory session transport with per-link capabilities.
 *
 * - [Router] queues payload/disconnection events; tests drain them with pump/step and advance collectors separately.
 * - Connected events are emitted immediately.
 * - [dropIf] drops matching decoded frames after recording the send; [failNextSends] throws on current-link sends
 *   before recording or queueing.
 * - This fixture does not model SDK tasks or radio lifecycle fencing.
 */
class FakeTransport(val name: String) : NearbyTransport {
    val flow = MutableSharedFlow<BleEvent>(extraBufferCapacity = 8192)
    override val events = flow
    lateinit var router: Router
    val sentFrames = mutableListOf<Pair<String, ByteArray>>()
    val disconnected = mutableListOf<String>()
    var dropIf: ((NearbyMessage) -> Boolean)? = null
    var failNextSends = 0
    private val connections = mutableMapOf<String, NearbyConnection>()

    fun connection(endpointId: String): NearbyConnection = checkNotNull(connections[endpointId])

    internal fun newConnection(endpointId: String): NearbyConnection = NearbyConnection(endpointId).also {
        connections[endpointId] = it
    }

    override fun sendPayload(connection: NearbyConnection, data: ByteArray) {
        val endpointId = connection.endpointId
        if (connections[endpointId] !== connection) return
        if (failNextSends > 0) {
            failNextSends--
            throw IllegalStateException("send refused for $endpointId")
        }
        sentFrames += endpointId to data
        val decoded = NearbyWire.decode(data)
        if (decoded is NearbyWire.Decoded.Message && dropIf?.invoke(decoded.message) == true) return
        router.enqueue(this, endpointId, data)
    }

    override fun disconnect(connection: NearbyConnection) {
        val endpointId = connection.endpointId
        if (connections[endpointId] !== connection) return
        disconnected += endpointId
        router.disconnect(this, endpointId, notifyPeer = true)
    }

    fun sentMessages(): List<NearbyMessage> =
        sentFrames.mapNotNull { (NearbyWire.decode(it.second) as? NearbyWire.Decoded.Message)?.message }
}

class Router {
    private data class End(val transport: FakeTransport, val endpointId: String)

    private val links = HashMap<End, End>()
    private val queue = ArrayDeque<() -> Unit>()
    var delivered = 0

    /**
     * Connects both sides with fresh local capabilities and immediately emits Connected.
     *
     * - Endpoint ids are local to each side.
     * - [token] and [bToken] supply channel-binding bytes independently; roles default to opposites but can be
     *   overridden to exercise handshake disagreement.
     */
    fun connect(
        a: FakeTransport,
        aSeesB: String,
        b: FakeTransport,
        bSeesA: String,
        token: ByteArray? = "token-$aSeesB-$bSeesA".toByteArray(),
        aIncoming: Boolean = false,
        bIncoming: Boolean = !aIncoming,
        bToken: ByteArray? = token
    ) {
        links[End(a, aSeesB)] = End(b, bSeesA)
        links[End(b, bSeesA)] = End(a, aSeesB)
        // Both ends must exist before an eager collector can send its first Hello.
        val aConnection = a.newConnection(aSeesB)
        val bConnection = b.newConnection(bSeesA)
        check(a.flow.tryEmit(BleEvent.Connected(aConnection, aIncoming, token)))
        check(b.flow.tryEmit(BleEvent.Connected(bConnection, bIncoming, bToken)))
    }

    fun enqueue(from: FakeTransport, endpointId: String, data: ByteArray) {
        val to = links[End(from, endpointId)] ?: return
        val connection = to.transport.connection(to.endpointId)
        queue += {
            delivered++
            check(to.transport.flow.tryEmit(BleEvent.PayloadReceived(connection, data)))
        }
    }

    fun disconnect(from: FakeTransport, endpointId: String, notifyPeer: Boolean) {
        val me = End(from, endpointId)
        val peer = links.remove(me) ?: return
        links.remove(peer)
        val mine = from.connection(endpointId)
        val theirs = peer.transport.connection(peer.endpointId)
        queue += { check(from.flow.tryEmit(BleEvent.Disconnected(mine))) }
        if (notifyPeer) queue += { check(peer.transport.flow.tryEmit(BleEvent.Disconnected(theirs))) }
    }

    /** Emits up to [maxFrames] queued payload/disconnection events; returns the number emitted. */
    fun pump(maxFrames: Int = 1_000_000): Int {
        var n = 0
        while (queue.isNotEmpty() && n < maxFrames) {
            queue.removeFirst()()
            n++
        }
        return n
    }

    /** Emits one queued payload/disconnection event, or returns false when the queue is empty. */
    fun step(): Boolean {
        val next = queue.removeFirstOrNull() ?: return false
        next()
        return true
    }

    fun pending(): Int = queue.size
}

/** Deterministic key pair for tests; `seed` must be 1..255. */
class TestIdentity(seed: Int) {
    val priv: ByteArray = ByteArray(32).also { it[31] = seed.toByte() }
    val pub: String = NostrEvent.pubkeyFromPrivkey(priv)
    val contract: IdentityContract =
        mockk<IdentityContract>().also {
            every { it.getPublicKeyHex() } returns pub
            every { it.hasPendingKeyPair() } returns false
            every { it.getPrivateKeyBytes() } answers { priv.copyOf() }
        }
}
