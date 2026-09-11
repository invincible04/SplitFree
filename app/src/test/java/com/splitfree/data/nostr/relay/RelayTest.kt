package com.splitfree.data.nostr.relay

import com.splitfree.data.nostr.protocol.NostrFilter
import com.splitfree.domain.crypto.NostrEvent
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.slot
import io.mockk.unmockkStatic
import io.mockk.verify
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class RelayTest {
    @Before
    fun setup() {
        mockkStatic(android.util.Log::class)
        every { android.util.Log.d(any<String>(), any<String>()) } returns 0
        every { android.util.Log.i(any<String>(), any<String>()) } returns 0
        every { android.util.Log.w(any<String>(), any<String>()) } returns 0
    }

    @After
    fun teardown() {
        unmockkStatic(android.util.Log::class)
    }

    @Test
    fun `initial state is DISCONNECTED`() {
        val relay =
            Relay("wss://test.relay", kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Unconfined))
        assertEquals(Relay.State.DISCONNECTED, relay.state.value)
    }

    @Test
    fun `send returns false when not connected`() {
        val relay =
            Relay("wss://test.relay", kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Unconfined))
        assertFalse(relay.send("test"))
    }

    @Test
    fun `disconnect resets state`() {
        val relay =
            Relay("wss://test.relay", kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Unconfined))
        relay.disconnect()
        assertEquals(Relay.State.DISCONNECTED, relay.state.value)
    }

    @Test
    fun `subscribe tracks active subs`() {
        val relay =
            Relay("wss://test.relay", kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Unconfined))
        val filter = NostrFilter(kinds = listOf(30078))
        relay.subscribe("sub1", listOf(filter))
        // closeSubscription should not throw
        relay.closeSubscription("sub1")
    }

    @Test
    fun `State enum values`() {
        assertEquals(3, Relay.State.entries.size)
        assertNotNull(Relay.State.DISCONNECTED)
        assertNotNull(Relay.State.CONNECTING)
        assertNotNull(Relay.State.CONNECTED)
    }

    @Test
    fun `resetReconnect does not throw`() {
        val relay =
            Relay("wss://test.relay", kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Unconfined))
        relay.resetReconnect() // should not throw
        assertEquals(Relay.State.DISCONNECTED, relay.state.value)
    }

    @Test
    fun `closeSubscription on unknown subId is no-op`() {
        val relay =
            Relay("wss://test.relay", kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Unconfined))
        relay.closeSubscription("nonexistent") // should not throw
    }

    // --- Backpressure: dropped messages are counted and re-requested ---

    @Test
    fun `messages beyond the buffer are counted as dropped when the collector is stuck`() = runTest {
        val relay = Relay("wss://test.relay", backgroundScope)
        // A collector that never finishes handling its first message, so the buffer cannot drain.
        val collector = launch { relay.messages.collect { awaitCancellation() } }
        runCurrent()

        val extra = 10
        repeat(Relay.MESSAGE_BUFFER_CAPACITY + extra) { relay.handleIncoming("""["EOSE","sub1"]""") }

        // Depending on whether the collector managed to take one value before sticking, either
        // `extra` or `extra - 1` messages had nowhere to go. None may be silently lost.
        val dropped = relay.droppedMessages.get()
        assertTrue("expected ~$extra drops, got $dropped", dropped == extra.toLong() || dropped == extra - 1L)
        collector.cancel()
    }

    @Test
    fun `nothing is dropped while the buffer has room`() = runTest {
        val relay = Relay("wss://test.relay", backgroundScope)
        val collector = launch { relay.messages.collect { awaitCancellation() } }
        runCurrent()

        repeat(Relay.MESSAGE_BUFFER_CAPACITY) { relay.handleIncoming("""["EOSE","sub1"]""") }

        assertEquals(0L, relay.droppedMessages.get())
        collector.cancel()
    }

    @Test
    fun `a dropped EVENT schedules exactly one catch-up REQ reaching back to the oldest dropped event`() = runTest {
        val (relay, ws) = connectedRelay(this)
        relay.subscribe("sub1", listOf(NostrFilter(kinds = listOf(30078))))
        val collector = launch { relay.messages.collect { awaitCancellation() } }
        runCurrent()

        // Two events are delivered (newest-first, as relays send stored history)...
        relay.handleIncoming(eventFrame("sub1", createdAt = 9000))
        relay.handleIncoming(eventFrame("sub1", createdAt = 8000))
        // ...then the buffer fills and three older events are dropped.
        repeat(Relay.MESSAGE_BUFFER_CAPACITY) { relay.handleIncoming("""["EOSE","sub1"]""") }
        for (ts in listOf(5000L, 4000L, 3000L)) relay.handleIncoming(eventFrame("sub1", createdAt = ts))
        assertTrue(relay.droppedMessages.get() >= 3)

        // Nothing is re-requested immediately: the collector needs time to drain first.
        assertEquals(1, reqFrames(ws, "sub1").size)

        advanceTimeBy(Relay.RESUBSCRIBE_DELAY_MS + 1)
        runCurrent()

        val reqs = reqFrames(ws, "sub1")
        assertEquals("original REQ + exactly one catch-up REQ", 2, reqs.size)
        // `since` must reach back past the oldest *dropped* event, not just the last delivered one:
        // in a newest-first backfill the dropped events are older than everything delivered.
        assertTrue(reqs.last(), reqs.last().contains("\"since\":${3000 - 60}"))
        collector.cancel()
    }

    @Test
    fun `dropping a non-EVENT frame does not trigger a catch-up REQ`() = runTest {
        val (relay, ws) = connectedRelay(this)
        relay.subscribe("sub1", listOf(NostrFilter(kinds = listOf(30078))))
        val collector = launch { relay.messages.collect { awaitCancellation() } }
        runCurrent()

        repeat(Relay.MESSAGE_BUFFER_CAPACITY + 5) { relay.handleIncoming("""["EOSE","sub1"]""") }
        assertTrue(relay.droppedMessages.get() > 0)
        advanceTimeBy(Relay.RESUBSCRIBE_DELAY_MS * 2)
        runCurrent()

        assertEquals(1, reqFrames(ws, "sub1").size)
        collector.cancel()
    }

    @Test
    fun `drops for a subscription closed in the meantime are not re-requested`() = runTest {
        val (relay, ws) = connectedRelay(this)
        relay.subscribe("sub1", listOf(NostrFilter(kinds = listOf(30078))))
        val collector = launch { relay.messages.collect { awaitCancellation() } }
        runCurrent()

        repeat(Relay.MESSAGE_BUFFER_CAPACITY + 1) { relay.handleIncoming("""["EOSE","sub1"]""") }
        relay.handleIncoming(eventFrame("sub1", createdAt = 100))
        relay.closeSubscription("sub1")
        advanceTimeBy(Relay.RESUBSCRIBE_DELAY_MS + 1)
        runCurrent()

        assertEquals(1, reqFrames(ws, "sub1").size)
        collector.cancel()
    }

    // --- sendEvent coroutine hygiene ---

    @Test
    fun `sendEvent rethrows CancellationException when the caller is cancelled`() = runTest {
        val (relay, _) = connectedRelay(this)
        var propagated = false
        val job =
            launch {
                try {
                    relay.sendEvent(event("e1"))
                    fail("sendEvent should not return after cancellation")
                } catch (e: CancellationException) {
                    propagated = true
                    throw e
                }
            }
        runCurrent() // now suspended awaiting the OK

        job.cancel()
        runCurrent()

        assertTrue("CancellationException was swallowed", propagated)
        assertTrue(job.isCancelled)
    }

    @Test
    fun `sendEvent returns false on timeout instead of throwing`() = runTest {
        val (relay, _) = connectedRelay(this)
        val result = async { relay.sendEvent(event("e1"), timeoutMs = 1000) }
        advanceTimeBy(1001)
        runCurrent()
        assertFalse(result.await())
    }

    @Test
    fun `sendEvent returns false when the pending OK is cancelled by disconnect`() = runTest {
        val (relay, _) = connectedRelay(this)
        val result = async { relay.sendEvent(event("e1")) }
        runCurrent()
        relay.disconnect()
        runCurrent()
        // The deferred was cancelled, not our coroutine: that is a failed publish, not cancellation.
        assertFalse(result.await())
    }

    @Test
    fun `sendEvent completes when the relay answers OK`() = runTest {
        val (relay, ws) = connectedRelay(this)
        val result = async { relay.sendEvent(event("e1")) }
        runCurrent()
        verify(exactly = 1) { ws.send(match<String> { it.startsWith("[\"EVENT\"") }) }

        relay.handleIncoming("""["OK","e1",true,""]""")
        runCurrent()

        assertTrue(result.await())
    }

    @Test
    fun `concurrent sendEvent for the same event shares one in-flight request`() = runTest {
        val (relay, ws) = connectedRelay(this)
        val first = async { relay.sendEvent(event("e1")) }
        val second = async { relay.sendEvent(event("e1")) }
        runCurrent()

        // Only one EVENT frame goes over the wire...
        verify(exactly = 1) { ws.send(match<String> { it.startsWith("[\"EVENT\"") }) }

        // ...and one OK completes both callers.
        relay.handleIncoming("""["OK","e1",true,""]""")
        runCurrent()
        assertTrue(first.await())
        assertTrue(second.await())
    }

    @Test
    fun `sendEvent returns false when the relay rejects the event`() = runTest {
        val (relay, _) = connectedRelay(this)
        val result = async { relay.sendEvent(event("e1")) }
        runCurrent()
        relay.handleIncoming("""["OK","e1",false,"blocked: spam"]""")
        runCurrent()
        assertFalse(result.await())
    }

    // --- helpers ---

    /** A relay whose WebSocket is a mock that accepts every frame; returns the relay and the socket. */
    private fun connectedRelay(scope: TestScope): Pair<Relay, WebSocket> {
        val ws = mockk<WebSocket>(relaxed = true)
        every { ws.send(any<String>()) } returns true
        val listener = slot<WebSocketListener>()
        val client = mockk<OkHttpClient>()
        every { client.newWebSocket(any(), capture(listener)) } returns ws
        val relay = Relay("wss://test.relay", scope.backgroundScope, client)
        relay.connect()
        listener.captured.onOpen(ws, mockk<Response>())
        assertEquals(Relay.State.CONNECTED, relay.state.value)
        return relay to ws
    }

    private fun reqFrames(ws: WebSocket, subId: String): List<String> {
        val sent = mutableListOf<String>()
        verify { ws.send(capture(sent)) }
        return sent.filter { it.startsWith("[\"REQ\",\"$subId\"") }
    }

    private fun event(id: String, createdAt: Long = 1) =
        NostrEvent(id = id, pubkey = "aa".repeat(32), createdAt = createdAt, kind = 30078, content = "", sig = "ss")

    /**
     * An inbound frame must carry well-formed 64/64/128-hex id/pubkey/sig or `RelayMessage.parse`
     * drops it before it reaches the buffer. The id is derived from [createdAt] so frames stay distinct.
     */
    private fun eventFrame(subId: String, createdAt: Long): String {
        val id = createdAt.toString(16).padStart(64, '0')
        val wellFormed = event(id, createdAt).copy(sig = "ss".repeat(64))
        return """["EVENT","$subId",${wellFormed.toJson()}]"""
    }
}
