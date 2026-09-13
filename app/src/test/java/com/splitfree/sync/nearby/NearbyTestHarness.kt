package com.splitfree.sync.nearby

import com.splitfree.data.ble.BleEvent
import com.splitfree.domain.crypto.NostrEvent
import com.splitfree.domain.repository.IdentityContract
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableSharedFlow

/**
 * Deterministic in-memory transport pair. Frames are queued on [Router] and delivered only when the
 * test calls [Router.pump], so every assertion sees a stable state. Fault injection: [dropIf] drops
 * matching frames on send; [Router.disconnect] simulates a transport drop on both ends.
 */
class FakeTransport(val name: String) : NearbyTransport {
    val flow = MutableSharedFlow<BleEvent>(extraBufferCapacity = 8192)
    override val events = flow
    lateinit var router: Router
    val sentFrames = mutableListOf<Pair<String, ByteArray>>()
    val disconnected = mutableListOf<String>()
    var dropIf: ((NearbyMessage) -> Boolean)? = null

    override fun sendPayload(endpointId: String, data: ByteArray) {
        sentFrames += endpointId to data
        val decoded = NearbyWire.decode(data)
        if (decoded is NearbyWire.Decoded.Message && dropIf?.invoke(decoded.message) == true) return
        router.enqueue(this, endpointId, data)
    }

    override fun disconnect(endpointId: String) {
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
     * Connect [a] and [b]. [aSeesB] is the endpoint id `a` uses for `b` and vice versa. Both sides get
     * the same channel [token]; [aIncoming] is the Nearby role as seen by `a`.
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
        check(a.flow.tryEmit(BleEvent.Connected(aSeesB, aIncoming, token)))
        check(b.flow.tryEmit(BleEvent.Connected(bSeesA, bIncoming, bToken)))
    }

    fun enqueue(from: FakeTransport, endpointId: String, data: ByteArray) {
        val to = links[End(from, endpointId)] ?: return
        queue += {
            delivered++
            check(to.transport.flow.tryEmit(BleEvent.PayloadReceived(to.endpointId, data)))
        }
    }

    fun disconnect(from: FakeTransport, endpointId: String, notifyPeer: Boolean) {
        val me = End(from, endpointId)
        val peer = links.remove(me) ?: return
        links.remove(peer)
        queue += { check(from.flow.tryEmit(BleEvent.Disconnected(endpointId))) }
        if (notifyPeer) queue += { check(peer.transport.flow.tryEmit(BleEvent.Disconnected(peer.endpointId))) }
    }

    /** Deliver queued frames until nothing is left. Returns how many frames moved. */
    fun pump(maxFrames: Int = 1_000_000): Int {
        var n = 0
        while (queue.isNotEmpty() && n < maxFrames) {
            queue.removeFirst()()
            n++
        }
        return n
    }

    /** Deliver exactly one queued frame. */
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
