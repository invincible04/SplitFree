package com.splitfree.data.nostr.relay

import android.util.Log
import com.splitfree.data.nostr.NostrClient
import com.splitfree.data.nostr.protocol.ClientMessage
import com.splitfree.data.nostr.protocol.NostrFilter
import com.splitfree.data.nostr.protocol.RelayMessage
import com.splitfree.data.util.CompressionUtil
import com.splitfree.domain.crypto.GroupEncryption
import com.splitfree.domain.crypto.NostrEvent
import com.splitfree.test.RelayProbeAssertions.assertAccepted
import com.splitfree.test.RelayProbeAssertions.requireEvents
import fr.acinq.secp256k1.Secp256k1
import io.mockk.every
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import java.security.SecureRandom
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume
import org.junit.Before
import org.junit.Test

/** Opt-in live probes: expected connections, publish acceptance and matching read-back must succeed. */
class RelayIntegrationTest {
    private val relayUrl = "wss://nos.lol"
    private val relays = mutableListOf<Relay>()
    private val clients = mutableListOf<NostrClient>()
    private lateinit var scope: CoroutineScope
    private lateinit var privKey: ByteArray
    private lateinit var pubKey: String
    private var logMocked = false

    @Before
    fun setup() {
        Assume.assumeTrue(
            "Skipped: set -DREAL_RELAY_TEST=true to run integration tests",
            System.getProperty("REAL_RELAY_TEST") == "true"
        )
        mockkStatic(Log::class)
        logMocked = true
        every { Log.d(any<String>(), any<String>()) } returns 0
        every { Log.i(any<String>(), any<String>()) } returns 0
        every { Log.w(any<String>(), any<String>()) } returns 0
        every { Log.e(any<String>(), any<String>()) } returns 0
        scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        privKey = ByteArray(32)
        val random = SecureRandom()
        do {
            random.nextBytes(privKey)
        } while (!Secp256k1.secKeyVerify(privKey))
        pubKey = NostrEvent.pubkeyFromPrivkey(privKey)
    }

    @After
    fun teardown() {
        try {
            clients.forEach { it.disconnect() }
            relays.forEach { it.disconnect() }
        } finally {
            if (::scope.isInitialized) scope.cancel()
            if (::privKey.isInitialized) privKey.fill(0)
            if (logMocked) unmockkStatic(Log::class)
        }
    }

    private suspend fun connectedRelay(): Relay {
        val relay = Relay(relayUrl, scope)
        relays.add(relay)
        relay.connect()
        withTimeout(10_000) { relay.state.first { it == Relay.State.CONNECTED } }
        return relay
    }

    private suspend fun connectedClient(): NostrClient {
        val client = NostrClient(scope)
        clients.add(client)
        client.connect(listOf(relayUrl))
        withTimeout(10_000) { client.connectionState.first { it } }
        return client
    }

    private fun encryptedEvent(groupId: String = UUID.randomUUID().toString()): NostrEvent {
        val encryption = GroupEncryption(CompressionUtil)
        return NostrEvent(
            pubkey = pubKey,
            createdAt = System.currentTimeMillis() / 1000,
            kind = 30078,
            tags = listOf(listOf("d", groupId), listOf("g", groupId)),
            content = encryption.encrypt("disposable relay probe $groupId", encryption.generateGroupKey())
        ).sign(privKey)
    }

    private suspend fun fetchFromRelay(reader: Relay, filter: NostrFilter): List<NostrEvent> = coroutineScope {
        val subId = UUID.randomUUID().toString()
        val response = async(start = CoroutineStart.UNDISPATCHED) {
            val events = mutableListOf<NostrEvent>()
            withTimeout(10_000) {
                reader.messages.first { message ->
                    when (message) {
                        is RelayMessage.EventMsg -> {
                            if (message.subId == subId) events.add(message.event)
                            false
                        }
                        is RelayMessage.EoseMsg -> message.subId == subId
                        is RelayMessage.ClosedMsg -> {
                            if (message.subId == subId) throw AssertionError("Relay closed subscription $subId")
                            false
                        }
                        else -> false
                    }
                }
            }
            events.toList()
        }
        try {
            reader.subscribe(subId, listOf(filter))
            response.await()
        } finally {
            reader.closeSubscription(subId)
            response.cancel()
        }
    }

    @Test
    fun `relay connects to live server`() = runBlocking {
        assertEquals(Relay.State.CONNECTED, connectedRelay().state.value)
    }

    @Test
    fun `relay publish requires OK acceptance and independent read back`() = runBlocking {
        val publisher = connectedRelay()
        val event = encryptedEvent()
        assertAccepted(publisher.sendEvent(event, timeoutMs = 10_000))
        publisher.disconnect()
        val received = fetchFromRelay(connectedRelay(), NostrFilter(ids = listOf(event.id)))
        requireEvents(listOf(event), received)
        Unit
    }

    @Test
    fun `relay subscribe receives EOSE for an empty filter`() = runBlocking {
        val absentId = encryptedEvent().id
        val received = fetchFromRelay(connectedRelay(), NostrFilter(ids = listOf(absentId)))
        assertTrue("Unpublished fixture must not exist on relay", received.isEmpty())
    }

    @Test
    fun `relay subscribe receives exact fixtures before EOSE`() = runBlocking {
        val publisher = connectedRelay()
        val expected = listOf(encryptedEvent(), encryptedEvent())
        expected.forEach { assertAccepted(publisher.sendEvent(it, timeoutMs = 10_000)) }
        publisher.disconnect()
        val received = fetchFromRelay(connectedRelay(), NostrFilter(ids = expected.map { it.id }))
        requireEvents(expected, received)
        Unit
    }

    @Test
    fun `relay disconnect transitions to DISCONNECTED`() = runBlocking {
        val relay = connectedRelay()
        relay.disconnect()
        assertEquals(Relay.State.DISCONNECTED, relay.state.value)
    }

    @Test
    fun `NostrClient full round-trip with independent live reader`() = runBlocking {
        val groupId = UUID.randomUUID().toString()
        val event = encryptedEvent(groupId)
        val publisher = connectedClient()
        assertAccepted(publisher.publish(event))
        publisher.disconnect()
        assertFalse(publisher.isConnected)

        val reader = connectedClient()
        val fetched = reader.fetchEvents(groupId, 0, pubKey)
        assertTrue("Independent relay fetch must reach EOSE without loss", fetched.complete)
        requireEvents(listOf(event), fetched.events)
        Unit
    }

    @Test
    fun `relay handles invalid URL gracefully`() = runBlocking {
        val relay = Relay("wss://this.relay.does.not.exist.invalid", scope)
        relays.add(relay)
        assertEquals(Relay.State.DISCONNECTED, relay.state.value)
        relay.connect()
        withTimeout(10_000) { relay.state.first { it == Relay.State.DISCONNECTED } }
        assertEquals(Relay.State.DISCONNECTED, relay.state.value)
    }
}

// Separate class name keeps protocol-only coverage in the default test gate.
class RelayProtocolOfflineTest {
    @Test
    fun `NostrClient rejects non-wss relay URLs`() = runBlocking {
        mockkStatic(Log::class)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val client = NostrClient(scope)
        try {
            every { Log.d(any<String>(), any<String>()) } returns 0
            every { Log.i(any<String>(), any<String>()) } returns 0
            every { Log.w(any<String>(), any<String>()) } returns 0
            every { Log.e(any<String>(), any<String>()) } returns 0
            client.connect(listOf("ws://insecure.relay", "http://bad.relay"))
            assertFalse(client.isConnected)
            assertTrue(client.currentRelayUrls().isEmpty())
        } finally {
            client.disconnect()
            scope.cancel()
            unmockkStatic(Log::class)
        }
    }

    @Test
    fun `relay message parsing matches real relay format`() {
        // Hand-built wire fixtures exercise parsing, not live relay delivery or signature verification.
        val eventJson = """["EVENT","sub1",{"id":"${"ab".repeat(
            32
        )}","pubkey":"${"cd".repeat(
            32
        )}","created_at":1700000000,"kind":1,"tags":[["t","test"]],"content":"hello","sig":"${"ef".repeat(64)}"}]"""
        val msg = RelayMessage.parse(eventJson)
        assertTrue(msg is RelayMessage.EventMsg)
        val eventMsg = msg as RelayMessage.EventMsg
        assertEquals("sub1", eventMsg.subId)
        assertEquals(1, eventMsg.event.kind)
        assertEquals("hello", eventMsg.event.content)
        assertEquals(1, eventMsg.event.tags.size)
        assertEquals(listOf("t", "test"), eventMsg.event.tags[0])

        // OK message
        val okJson = """["OK","${"ab".repeat(32)}",true,""]"""
        val okMsg = RelayMessage.parse(okJson) as RelayMessage.OkMsg
        assertTrue(okMsg.accepted)

        // OK rejected
        val okReject = """["OK","${"ab".repeat(32)}",false,"rate-limited:"]"""
        val rejectMsg = RelayMessage.parse(okReject) as RelayMessage.OkMsg
        assertFalse(rejectMsg.accepted)
        assertEquals("rate-limited:", rejectMsg.message)

        // EOSE
        val eoseJson = """["EOSE","sub1"]"""
        assertTrue(RelayMessage.parse(eoseJson) is RelayMessage.EoseMsg)

        // NOTICE
        val noticeJson = """["NOTICE","slow down"]"""
        val notice = RelayMessage.parse(noticeJson) as RelayMessage.NoticeMsg
        assertEquals("slow down", notice.message)

        // AUTH
        val authJson = """["AUTH","challenge123"]"""
        val auth = RelayMessage.parse(authJson) as RelayMessage.AuthMsg
        assertEquals("challenge123", auth.challenge)

        // CLOSED
        val closedJson = """["CLOSED","sub1","error: too many subs"]"""
        val closed = RelayMessage.parse(closedJson) as RelayMessage.ClosedMsg
        assertEquals("sub1", closed.subId)

        // Invalid
        assertNull(RelayMessage.parse("not json"))
        assertNull(RelayMessage.parse("""["UNKNOWN","data"]"""))
    }

    @Test
    fun `ClientMessage serialization matches NIP-01 spec`() {
        val event =
            NostrEvent(
                id = "ab".repeat(32),
                pubkey = "cd".repeat(32),
                createdAt = 1700000000,
                kind = 1,
                tags = listOf(listOf("t", "test")),
                content = "hello",
                sig = "ef".repeat(64)
            )

        // EVENT message
        val eventMsg = ClientMessage.Event(event).toJson()
        assertTrue(eventMsg.startsWith("[\"EVENT\","))
        assertTrue(eventMsg.contains("\"kind\":1"))

        // REQ message
        val filter = NostrFilter(kinds = listOf(1, 30078), since = 1700000000)
        val reqMsg = ClientMessage.Req("sub-1", listOf(filter)).toJson()
        assertTrue(reqMsg.startsWith("[\"REQ\",\"sub-1\","))
        assertTrue(reqMsg.contains("\"kinds\""))

        // CLOSE message
        val closeMsg = ClientMessage.Close("sub-1").toJson()
        assertEquals("""["CLOSE","sub-1"]""", closeMsg)

        // AUTH message
        val authMsg = ClientMessage.Auth(event).toJson()
        assertTrue(authMsg.startsWith("[\"AUTH\","))
    }
}
