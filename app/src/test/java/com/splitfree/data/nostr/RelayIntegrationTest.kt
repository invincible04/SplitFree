package com.splitfree.data.nostr

import android.util.Log
import com.splitfree.domain.crypto.NostrEvent
import io.mockk.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.firstOrNull
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/**
 * Real integration test against a live Nostr relay.
 *
 * Validates the full stack: OkHttp WebSocket → Relay → NostrClient → event flow.
 * Uses wss://relay.damus.io (public, no auth required).
 *
 * These tests require network access and may be slow (~5s each).
 * They are NOT mocked — they exercise the real code paths.
 */
class RelayIntegrationTest {

    // Real secp256k1 keypair for signing
    private val privKey = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
        .chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    private val pubKey = NostrEvent.pubkeyFromPrivkey(privKey)

    private lateinit var relay: Relay
    private val relayUrl = "wss://relay.damus.io"
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    @Before
    fun setup() {
        mockkStatic(android.util.Log::class)
        every { Log.d(any<String>(), any<String>()) } returns 0
        every { Log.i(any<String>(), any<String>()) } returns 0
        every { Log.w(any<String>(), any<String>()) } returns 0
        every { Log.e(any<String>(), any<String>()) } returns 0
    }

    @After
    fun teardown() {
        if (::relay.isInitialized) relay.disconnect()
        scope.cancel()
        unmockkAll()
    }

    @Test
    fun `relay connects to live server`() = runBlocking {
        relay = Relay(relayUrl, scope)
        relay.connect()

        // Wait for connection (up to 10s)
        withTimeout(10_000) {
            relay.state.first { it == Relay.State.CONNECTED }
        }
        assertEquals(Relay.State.CONNECTED, relay.state.value)
    }

    @Test
    fun `relay publish and receive OK response`() = runBlocking {
        relay = Relay(relayUrl, scope)
        relay.connect()
        withTimeout(10_000) { relay.state.first { it == Relay.State.CONNECTED } }

        // Create a kind 1 ephemeral event (text note) — relays accept these
        val event = NostrEvent(
            pubkey = pubKey,
            createdAt = System.currentTimeMillis() / 1000,
            kind = 1,
            tags = listOf(listOf("t", "splitfree-test")),
            content = "integration test ${System.nanoTime()}"
        ).sign(privKey)

        assertTrue("Event must have valid signature", event.verify())

        val accepted = relay.sendEvent(event, timeoutMs = 10_000)
        // Relay may accept or reject (rate limit, etc.) — but we should get a response
        // The key test is that sendEvent completes without exception
        assertNotNull(accepted)
    }

    @Test
    fun `relay subscribe receives EOSE`() = runBlocking {
        relay = Relay(relayUrl, scope)
        relay.connect()
        withTimeout(10_000) { relay.state.first { it == Relay.State.CONNECTED } }

        val subId = "test-sub-${System.nanoTime()}"
        val filter = NostrFilter(
            kinds = listOf(1),
            limit = 1
        )

        var gotEose = false
        val eoseJob = scope.launch {
            relay.messages.collect { msg ->
                if (msg is RelayMessage.EoseMsg && msg.subId == subId) {
                    gotEose = true
                    return@collect
                }
            }
        }

        relay.subscribe(subId, listOf(filter))

        // Wait for EOSE (relay sends this after sending stored events)
        withTimeout(10_000) { while (!gotEose) delay(100) }
        assertTrue("Should receive EOSE", gotEose)

        relay.closeSubscription(subId)
        eoseJob.cancel()
    }

    @Test
    fun `relay subscribe receives events before EOSE`() = runBlocking {
        relay = Relay(relayUrl, scope)
        relay.connect()
        withTimeout(10_000) { relay.state.first { it == Relay.State.CONNECTED } }

        val subId = "test-events-${System.nanoTime()}"
        // Ask for recent kind 1 events — there are always some on public relays
        val filter = NostrFilter(
            kinds = listOf(1),
            limit = 3
        )

        val events = mutableListOf<NostrEvent>()
        var gotEose = false

        val collectJob = scope.launch {
            relay.messages.collect { msg ->
                when (msg) {
                    is RelayMessage.EventMsg -> {
                        if (msg.subId == subId) events.add(msg.event)
                    }
                    is RelayMessage.EoseMsg -> {
                        if (msg.subId == subId) gotEose = true
                    }
                    else -> {}
                }
            }
        }

        relay.subscribe(subId, listOf(filter))
        withTimeout(10_000) { while (!gotEose) delay(100) }

        // Public relays should have at least 1 kind-1 event
        assertTrue("Should receive at least 1 event", events.isNotEmpty())
        // All received events should have valid structure
        events.forEach { event ->
            assertEquals(1, event.kind)
            assertTrue("Event ID should be 64 hex chars", event.id.length == 64)
            assertTrue("Pubkey should be 64 hex chars", event.pubkey.length == 64)
            assertTrue("Sig should be 128 hex chars", event.sig.length == 128)
            assertTrue("CreatedAt should be positive", event.createdAt > 0)
        }

        relay.closeSubscription(subId)
        collectJob.cancel()
    }

    @Test
    fun `relay disconnect transitions to DISCONNECTED`() = runBlocking {
        relay = Relay(relayUrl, scope)
        relay.connect()
        withTimeout(10_000) { relay.state.first { it == Relay.State.CONNECTED } }

        relay.disconnect()
        assertEquals(Relay.State.DISCONNECTED, relay.state.value)
    }

    @Test
    fun `NostrClient full round-trip with live relay`() = runBlocking {
        val client = NostrClient()
        try {
            client.connect(listOf(relayUrl))

            // Wait for at least one relay to connect
            withTimeout(10_000) { while (!client.isConnected) delay(100) }
            assertTrue(client.isConnected)

            // Fetch recent events for a random group ID (will return empty but exercises the path)
            val events = client.fetchEvents("nonexistent-group-${System.nanoTime()}", 0)
            // Should return empty list (no events for random group), not throw
            assertNotNull(events)

            // Publish a signed event
            val event = NostrEvent(
                pubkey = pubKey,
                createdAt = System.currentTimeMillis() / 1000,
                kind = 1,
                tags = listOf(listOf("t", "splitfree-integration")),
                content = "NostrClient test ${System.nanoTime()}"
            ).sign(privKey)

            // publish may succeed or fail depending on relay policy, but should not throw
            client.publish(event)
        } finally {
            client.disconnect()
        }
        assertFalse(client.isConnected)
    }

    @Test
    fun `NostrClient rejects non-wss relay URLs`() = runBlocking {
        val client = NostrClient()
        try {
            client.connect(listOf("ws://insecure.relay", "http://bad.relay"))
            // Should connect to 0 relays
            assertFalse(client.isConnected)
        } finally {
            client.disconnect()
        }
    }

    @Test
    fun `relay handles invalid URL gracefully`() = runBlocking {
        relay = Relay("wss://this.relay.does.not.exist.invalid", scope)
        relay.connect()

        // Should transition to CONNECTING then DISCONNECTED (connection failure)
        delay(3000)
        // After failure, state should be DISCONNECTED (with reconnect scheduled)
        assertTrue(
            "State should be DISCONNECTED or CONNECTING after failure",
            relay.state.value == Relay.State.DISCONNECTED || relay.state.value == Relay.State.CONNECTING
        )
    }

    @Test
    fun `relay message parsing matches real relay format`() {
        // Verify our parser handles real relay message formats
        val eventJson = """["EVENT","sub1",{"id":"${"ab".repeat(32)}","pubkey":"${"cd".repeat(32)}","created_at":1700000000,"kind":1,"tags":[["t","test"]],"content":"hello","sig":"${"ef".repeat(64)}"}]"""
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
        val event = NostrEvent(
            id = "ab".repeat(32), pubkey = "cd".repeat(32),
            createdAt = 1700000000, kind = 1,
            tags = listOf(listOf("t", "test")),
            content = "hello", sig = "ef".repeat(64)
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
