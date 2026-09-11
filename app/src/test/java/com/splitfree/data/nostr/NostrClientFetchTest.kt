package com.splitfree.data.nostr

import com.splitfree.data.nostr.protocol.NostrFilter
import com.splitfree.data.nostr.protocol.RelayMessage
import com.splitfree.data.nostr.relay.Relay
import com.splitfree.domain.crypto.NostrEvent
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
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Regression tests for the [NostrClient.fetchEvents] collector lifecycle.
 *
 * The collectors append to the list that is handed back to the caller, so they must not outlive
 * the fetch. A collector still running after the return is what let the caller's
 * `for (event in events)` loop hit ConcurrentModificationException.
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
        return e
    }

    /** A relay whose message flow is driven by the test. */
    private class FakeRelay(val url: String) {
        val messages = MutableSharedFlow<RelayMessage>(extraBufferCapacity = 64)
        val relay: Relay = mockk(relaxed = true)
        var subId: String? = null
        val closed = mutableListOf<String>()

        init {
            every { relay.url } returns url
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
        assertEquals(1, result.size)

        repeat(20) { fake.messages.emit(RelayMessage.EventMsg(subId, event("late-$it"))) }
        runCurrent()

        assertEquals(1, result.size)
        for (e in result) assertEquals("e1", e.id)
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

        assertEquals(listOf("from-b"), result.map { it.id })
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
    fun `fetch with no relays returns empty without subscribing`() = runTest(dispatcher) {
        val client = NostrClient(CoroutineScope(SupervisorJob() + dispatcher))
        assertEquals(emptyList<NostrEvent>(), client.fetchEvents("group-1", 0, null))
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

        assertEquals("fetch must complete on its own snapshot", emptyList<NostrEvent>(), result)
        verify(exactly = 0) { late.relay.subscribe(any(), any<List<NostrFilter>>()) }
        assertEquals(emptyList<String>(), late.closed)
    }
}
