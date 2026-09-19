package com.splitfree.data.nostr

import com.splitfree.data.nostr.protocol.NostrFilter
import com.splitfree.data.nostr.protocol.RelayMessage
import com.splitfree.data.nostr.relay.Relay
import com.splitfree.domain.crypto.NostrEvent
import com.splitfree.domain.model.sync.FetchResult
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import io.mockk.verify
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Tests for the [NostrClient.fetchEvents] collector lifecycle and completeness flag.
 *
 * The collectors append to the list that is handed back to the caller, so they must not outlive
 * the fetch: a collector still running after the return would race the caller's
 * `for (event in events)` loop into ConcurrentModificationException.
 */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class NostrClientFetchTest {
    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setup() {
        mockkStatic(android.util.Log::class)
        every { android.util.Log.w(any<String>(), any<String>()) } returns 0
        every { android.util.Log.i(any<String>(), any<String>()) } returns 0
        every { android.util.Log.d(any<String>(), any<String>()) } returns 0
        every { android.util.Log.e(any<String>(), any<String>()) } returns 0
        every { android.util.Log.e(any<String>(), any<String>(), any()) } returns 0
        every { android.util.Log.w(any<String>(), any<String>(), any()) } returns 0
    }

    @After
    fun teardown() {
        unmockkStatic(android.util.Log::class)
    }

    private fun event(id: String): NostrEvent {
        val e = mockk<NostrEvent>(relaxed = true)
        every { e.id } returns id
        every { e.verify() } returns true
        every { e.kind } returns 30078
        every { e.createdAt } returns 1000L
        every { e.tags } returns listOf(listOf("g", "group-1"), listOf("g", "g"))
        every { e.toJson() } returns "{}"
        return e
    }

    /** A relay whose message flow and connection state are driven by the test. */
    private class FakeRelay(val url: String, initialState: Relay.State = Relay.State.CONNECTED) {
        val messages = MutableSharedFlow<RelayMessage>(extraBufferCapacity = 64)
        val state = MutableStateFlow(initialState)
        val relay: Relay = mockk(relaxed = true)
        var subId: String? = null
        val closed = mutableListOf<String>()

        init {
            every { relay.connectionEpoch } returns java.util.concurrent.atomic.AtomicLong(1)
            every { relay.droppedMessages } returns java.util.concurrent.atomic.AtomicLong(0)
            every { relay.url } returns url
            every { relay.state } returns state
            every { relay.messages } returns messages
            every { relay.subscribe(any(), any()) } answers { subId = firstArg() }
            every { relay.closeSubscription(any()) } answers { closed += firstArg<String>() }
        }
    }

    private fun clientWith(vararg fakes: FakeRelay): NostrClient {
        val client = NostrClient(CoroutineScope(SupervisorJob() + dispatcher))
        fakes.forEach { injectRelay(client, it) }
        return client
    }

    private fun injectRelay(client: NostrClient, fake: FakeRelay) {
        val field = NostrClient::class.java.getDeclaredField("relays")
        field.isAccessible = true

        @Suppress("UNCHECKED_CAST")
        (field.get(client) as MutableMap<String, Relay>)[fake.url] = fake.relay
    }

    @Test
    fun `returned list is a snapshot that later relay emissions cannot mutate`() = runTest(dispatcher) {
        val fake = FakeRelay("wss://a")
        val client = clientWith(fake)

        val fetch = async { client.fetchEvents("group-1", 0, null) }
        runCurrent()
        val subId = requireNotNull(fake.subId) { "collectors never subscribed" }
        fake.messages.emit(RelayMessage.EventMsg(subId, event("e1")))
        fake.messages.emit(RelayMessage.EoseMsg(subId))
        val result = fetch.await()
        assertEquals(1, result.events.size)
        assertTrue(result.complete)

        repeat(20) { fake.messages.emit(RelayMessage.EventMsg(subId, event("late-$it"))) }
        runCurrent()

        assertEquals(1, result.events.size)
        for (e in result.events) assertEquals("e1", e.id)
    }

    @Test
    fun `subscription is closed and collectors detach after a completed fetch`() = runTest(dispatcher) {
        val fake = FakeRelay("wss://a")
        val client = clientWith(fake)

        val fetch = async { client.fetchEvents("group-1", 0, null) }
        runCurrent()
        val subId = requireNotNull(fake.subId)
        fake.messages.emit(RelayMessage.EoseMsg(subId))
        fetch.await()
        runCurrent()

        assertEquals(listOf(subId), fake.closed)
        assertEquals("collector should have detached", 0, fake.messages.subscriptionCount.value)
    }

    @Test
    fun `cancelling the caller closes the subscription and leaves no collector`() = runTest(dispatcher) {
        val fake = FakeRelay("wss://a")
        val client = clientWith(fake)

        val job = launch { client.fetchEvents("group-1", 0, null) }
        runCurrent()
        val subId = requireNotNull(fake.subId)
        assertEquals(1, fake.messages.subscriptionCount.value)

        job.cancelAndJoin()
        runCurrent()

        assertEquals(listOf(subId), fake.closed)
        assertEquals("cancellation must not leak a collector", 0, fake.messages.subscriptionCount.value)
    }

    @Test
    fun `duplicate EOSE from one relay does not end the fetch while another is still sending`() = runTest(dispatcher) {
        val a = FakeRelay("wss://a")
        val b = FakeRelay("wss://b")
        val client = clientWith(a, b)

        val fetch = async { client.fetchEvents("group-1", 0, null) }
        runCurrent()
        val subId = requireNotNull(a.subId)

        a.messages.emit(RelayMessage.EoseMsg(subId))
        a.messages.emit(RelayMessage.EoseMsg(subId))
        runCurrent()
        assertTrue("fetch must still be waiting for relay B", fetch.isActive)

        b.messages.emit(RelayMessage.EventMsg(subId, event("from-b")))
        b.messages.emit(RelayMessage.EoseMsg(subId))
        val result = fetch.await()

        assertEquals(listOf("from-b"), result.events.map { it.id })
        assertTrue(result.complete)
    }

    @Test
    fun `readiness timeout is an operation failure and cleans up without subscribing`() = runTest(dispatcher) {
        val fake = FakeRelay("wss://a")
        val client = clientWith(fake)
        val neverReady = mockk<kotlinx.coroutines.flow.SharedFlow<RelayMessage>>()
        io.mockk.coEvery { neverReady.collect(any()) } coAnswers { kotlinx.coroutines.awaitCancellation() }
        every { fake.relay.messages } returns neverReady

        val result = async { runCatching { client.fetchEvents("group-1", 0, null) } }.await()

        assertTrue(result.exceptionOrNull() is java.io.IOException)
        assertTrue(result.exceptionOrNull() !is kotlinx.coroutines.CancellationException)
        verify(exactly = 0) { fake.relay.subscribe(any(), any()) }
        assertEquals(1, fake.closed.size)
    }

    @Test
    fun `fetch with no relays is empty and incomplete without subscribing`() = runTest(dispatcher) {
        val client = NostrClient(CoroutineScope(SupervisorJob() + dispatcher))
        assertEquals(FetchResult(emptyList(), complete = false), client.fetchEvents("group-1", 0, null))
    }

    @Test
    fun `fetch with only disconnected relays is empty and incomplete without subscribing or waiting`() =
        runTest(dispatcher) {
            val down = FakeRelay("wss://down", Relay.State.DISCONNECTED)
            val alsoDown = FakeRelay("wss://also-down", Relay.State.DISCONNECTED)
            val client = clientWith(down, alsoDown)

            val result = client.fetchEvents("group-1", 0, null)

            assertEquals(FetchResult(emptyList(), complete = false), result.copy(pendingByRelay = emptyMap()))
            assertEquals("a relay that is not handshaking is not waited for", 0L, currentTime)
            verify(exactly = 0) { down.relay.subscribe(any(), any()) }
            verify(exactly = 0) { alsoDown.relay.subscribe(any(), any()) }
            assertEquals(emptyList<String>(), down.closed + alsoDown.closed)
        }

    @Test
    fun `a relay stuck connecting is waited for until the settle window ends then left out`() = runTest(dispatcher) {
        val stuck = FakeRelay("wss://stuck", Relay.State.CONNECTING)
        val client = clientWith(stuck)

        val result = client.fetchEvents("group-1", 0, null)

        assertEquals(FetchResult(emptyList(), complete = false), result.copy(pendingByRelay = emptyMap()))
        assertEquals(NostrClient.SETTLE_TIMEOUT_MS, currentTime)
        verify(exactly = 0) { stuck.relay.subscribe(any(), any()) }
        assertEquals(emptyList<String>(), stuck.closed)
    }

    @Test
    fun `a relay that finishes its handshake inside the settle window is queried and gates completeness`() =
        runTest(dispatcher) {
            val live = FakeRelay("wss://live")
            val primary = FakeRelay("wss://primary", Relay.State.CONNECTING)
            val client = clientWith(live, primary)

            val fetch = async { client.fetchEvents("group-1", 0, null) }
            advanceTimeBy(500)
            primary.state.value = Relay.State.CONNECTED
            runCurrent()
            val subId = requireNotNull(primary.subId) { "primary must be subscribed once connected" }
            assertEquals(subId, live.subId)

            live.messages.emit(RelayMessage.EoseMsg(subId))
            runCurrent()
            assertTrue("fetch must still wait for the primary's EOSE", fetch.isActive)
            primary.messages.emit(RelayMessage.EventMsg(subId, event("from-primary")))
            primary.messages.emit(RelayMessage.EoseMsg(subId))
            val result = fetch.await()

            assertEquals(listOf("from-primary"), result.events.map { it.id })
            assertTrue(result.complete)
        }

    @Test
    fun `a relay that drops mid-fetch poisons completeness but its events are kept`() = runTest(dispatcher) {
        val a = FakeRelay("wss://a")
        val b = FakeRelay("wss://b")
        val client = clientWith(a, b)

        val fetch = async { client.fetchEvents("group-1", 0, null) }
        runCurrent()
        val subId = requireNotNull(a.subId)
        a.messages.emit(RelayMessage.EventMsg(subId, event("from-a")))
        a.messages.emit(RelayMessage.EoseMsg(subId))
        b.messages.emit(RelayMessage.EventMsg(subId, event("from-b")))
        b.state.value = Relay.State.DISCONNECTED
        runCurrent()
        b.state.value = Relay.State.CONNECTED
        b.messages.emit(RelayMessage.EoseMsg(subId))
        val result = fetch.await()

        assertEquals(setOf("from-a", "from-b"), result.events.map { it.id }.toSet())
        assertFalse("a relay that dropped during the fetch cannot certify its history", result.complete)
        assertEquals(setOf(a.url), result.completedRelays)
    }

    @Test
    fun `excluded relay is not waited for but remains incomplete coverage`() = runTest(dispatcher) {
        val live = FakeRelay("wss://live")
        val down = FakeRelay("wss://down", Relay.State.DISCONNECTED)
        val client = clientWith(live, down)

        val fetch = async { client.fetchEvents("group-1", 0, null) }
        runCurrent()
        val subId = requireNotNull(live.subId)
        verify(exactly = 0) { down.relay.subscribe(any(), any()) }

        live.messages.emit(RelayMessage.EventMsg(subId, event("e1")))
        live.messages.emit(RelayMessage.EoseMsg(subId))
        val result = fetch.await()

        assertEquals(listOf("e1"), result.events.map { it.id })
        assertFalse("fallback cannot certify excluded history", result.complete)
        assertEquals(setOf(live.url), result.completedRelays)
        assertEquals(listOf(subId), live.closed)
        assertEquals(emptyList<String>(), down.closed)
    }

    @Test
    fun `EOSE timeout returns what was collected flagged incomplete and names the silent relay`() =
        runTest(dispatcher) {
            val a = FakeRelay("wss://a")
            val silent = FakeRelay("wss://silent")
            val client = clientWith(a, silent)

            val fetch = async { client.fetchEvents("group-1", 0, null) }
            runCurrent()
            val subId = requireNotNull(a.subId)
            a.messages.emit(RelayMessage.EventMsg(subId, event("e1")))
            a.messages.emit(RelayMessage.EoseMsg(subId))
            runCurrent()
            assertTrue("fetch must still be waiting for the silent relay", fetch.isActive)

            val result = fetch.await()

            assertEquals(listOf("e1"), result.events.map { it.id })
            assertFalse("a relay that never sent EOSE makes the fetch incomplete", result.complete)
            assertEquals(listOf(subId), silent.closed)
            verify { android.util.Log.w("NostrClient", match<String> { "wss://silent" in it && "wss://a" !in it }) }
        }

    @Test
    fun `a relay added after the snapshot is neither subscribed nor closed`() = runTest(dispatcher) {
        val a = FakeRelay("wss://a")
        val late = FakeRelay("wss://late")
        val client = clientWith(a)

        // Enter fetch now, but leave collector launches queued. Changing the pool in this gap
        // must not split the relay count from the set the collectors/subscriptions use.
        val fetch = async(start = CoroutineStart.UNDISPATCHED) { client.fetchEvents("group-1", 0, null) }
        injectRelay(client, late)
        runCurrent()
        val subId = requireNotNull(a.subId)

        a.messages.emit(RelayMessage.EoseMsg(subId))
        val result = withTimeoutOrNull(30_000) { fetch.await() }

        assertEquals(
            "fetch must complete on its own snapshot",
            FetchResult(emptyList(), complete = true, completedRelays = setOf(a.url)),
            result
        )
        verify(exactly = 0) { late.relay.subscribe(any(), any<List<NostrFilter>>()) }
        assertEquals(emptyList<String>(), late.closed)
    }

    @Test
    fun `per-relay windows retain old debt and independently widen gift-wrap timestamps`() = runTest(dispatcher) {
        val a = FakeRelay("wss://a")
        val b = FakeRelay("wss://b")
        val client = clientWith(a, b)
        val filtersA = io.mockk.slot<List<NostrFilter>>()
        val filtersB = io.mockk.slot<List<NostrFilter>>()
        every { a.relay.subscribe(any(), capture(filtersA)) } answers { a.subId = firstArg() }
        every { b.relay.subscribe(any(), capture(filtersB)) } answers { b.subId = firstArg() }
        val fetch = async { client.fetchEventsByRelay("g", mapOf(a.url to 0L, b.url to 500_000L), "pub") }
        runCurrent()
        assertEquals(null, filtersA.captured[0].since)
        assertEquals(null, filtersA.captured[1].since)
        assertEquals(327_200L, filtersB.captured[0].since)
        assertEquals(327_200L, filtersB.captured[1].since)
        a.messages.emit(RelayMessage.EoseMsg(requireNotNull(a.subId)))
        b.messages.emit(RelayMessage.EoseMsg(requireNotNull(b.subId)))
        assertTrue(fetch.await().complete)
    }

    @Test
    fun `requested relay absent from socket pool is not certified by fallback EOSE`() = runTest(dispatcher) {
        val a = FakeRelay("wss://a")
        val client = clientWith(a)
        val fetch = async { client.fetchEventsByRelay("g", mapOf(a.url to 100L, "wss://missing" to 0L), null) }
        runCurrent()
        a.messages.emit(RelayMessage.EoseMsg(requireNotNull(a.subId)))
        val result = fetch.await()
        assertFalse(result.complete)
        assertEquals(setOf(a.url), result.completedRelays)
    }

    @Test
    fun `rapid reconnect hidden by conflated state cannot certify narrowed historical request`() = runTest(dispatcher) {
        val a = FakeRelay("wss://a")
        val client = clientWith(a)
        val fetch = async { client.fetchEvents("g", 0, null) }
        runCurrent()
        a.state.value = Relay.State.DISCONNECTED
        a.relay.connectionEpoch.incrementAndGet()
        a.state.value = Relay.State.CONNECTED
        a.messages.emit(RelayMessage.EoseMsg(requireNotNull(a.subId)))
        val result = fetch.await()
        assertFalse(result.complete)
        assertTrue(result.completedRelays.isEmpty())
    }

    @Test
    fun `dropped buffered frame prevents coverage advancement even with EOSE`() = runTest(dispatcher) {
        val a = FakeRelay("wss://a")
        val client = clientWith(a)
        val fetch = async { client.fetchEvents("g", 0, null) }
        runCurrent()
        a.relay.droppedMessages.incrementAndGet()
        a.messages.emit(RelayMessage.EoseMsg(requireNotNull(a.subId)))
        assertTrue(fetch.await().completedRelays.isEmpty())
    }
}
