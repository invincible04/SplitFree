package com.splitfree.data.nostr

import com.splitfree.data.nostr.protocol.NostrFilter
import com.splitfree.data.nostr.relay.Relay
import com.splitfree.domain.crypto.NostrEvent
import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.slot
import io.mockk.unmockkStatic
import io.mockk.verify
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class NostrClientTest {
    @Before
    fun setup() {
        mockkStatic(android.util.Log::class)
        every { android.util.Log.w(any<String>(), any<String>()) } returns 0
        every { android.util.Log.i(any<String>(), any<String>()) } returns 0
        every { android.util.Log.d(any<String>(), any<String>()) } returns 0
    }

    @After
    fun teardown() {
        unmockkStatic(android.util.Log::class)
    }

    @Test
    fun `isConnected returns false initially`() {
        val client = NostrClient(CoroutineScope(SupervisorJob() + Dispatchers.IO))
        assertFalse(client.isConnected)
    }

    @Test
    fun `acquireConnection and releaseConnection manage ref count`() {
        val client = NostrClient(CoroutineScope(SupervisorJob() + Dispatchers.IO))
        client.acquireConnection()
        client.acquireConnection()
        client.releaseConnection()
        // Still one user, so no disconnect
        client.releaseConnection()
        // Now zero: disconnect called internally
    }

    @Test
    fun `disconnect clears state`() {
        val client = NostrClient(CoroutineScope(SupervisorJob() + Dispatchers.IO))
        client.disconnect()
        assertFalse(client.isConnected)
    }

    @Test
    fun `addRelay rejects non-wss URL`() {
        val client = NostrClient(CoroutineScope(SupervisorJob() + Dispatchers.IO))
        client.addRelay("ws://insecure.relay")
        assertFalse(client.isConnected)
    }

    @Test
    fun `startListening is a no-op`() {
        val client = NostrClient(CoroutineScope(SupervisorJob() + Dispatchers.IO))
        client.startListening() // should not throw
    }

    @Test
    fun `subscribe uses wider since window for gift wrap p-tag filter`() = runBlocking {
        val client = NostrClient(CoroutineScope(SupervisorJob() + Dispatchers.IO))
        val relay = mockk<Relay>(relaxed = true)
        val capturedFilters = slot<List<NostrFilter>>()
        every { relay.subscribe(any(), capture(capturedFilters)) } just Runs

        // Inject mock relay via reflection
        val relaysField = NostrClient::class.java.getDeclaredField("relays")
        relaysField.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        (relaysField.get(client) as MutableMap<String, Relay>)["wss://test"] = relay

        val now = System.currentTimeMillis() / 1000
        val since = now - 3600 // 1 hour ago

        client.subscribe("test-group", since, "recipient-pubkey")

        assertTrue("subscribe should have been called", capturedFilters.isCaptured)
        val filters = capturedFilters.captured
        assertEquals("Should have 2 filters", 2, filters.size)

        // Filter 1: group filter uses original since
        assertEquals(since, filters[0].since)
        assertTrue(filters[0].tags!!.containsKey("#g"))

        // Filter 2: p-tag filter uses since - 48h for NIP-59 timestamp randomization
        val expectedGiftWrapSince = since - 2 * 86400
        assertEquals(expectedGiftWrapSince, filters[1].since)
        assertTrue(filters[1].tags!!.containsKey("#p"))
        assertEquals(listOf(1059), filters[1].kinds)
    }

    @Test
    fun `subscribe closes previous subscription for same group`() = runBlocking {
        val client = NostrClient(CoroutineScope(SupervisorJob() + Dispatchers.IO))
        val relay = mockk<Relay>(relaxed = true)
        val subIds = mutableListOf<String>()
        every { relay.subscribe(any(), any()) } answers {
            subIds += firstArg<String>()
        }

        val relaysField = NostrClient::class.java.getDeclaredField("relays")
        relaysField.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        (relaysField.get(client) as MutableMap<String, Relay>)["wss://test"] = relay

        client.subscribe("group-1", 0, null)
        client.subscribe("group-1", 0, null)

        verify(exactly = 1) { relay.closeSubscription(subIds.first()) }
    }

    @Test
    fun `connect filters out non-wss URLs`() = runBlocking {
        val client = NostrClient(CoroutineScope(SupervisorJob() + Dispatchers.IO))
        client.connect(listOf("ws://insecure.relay", "http://bad.relay"))
        assertEquals(emptyList<String>(), client.currentRelayUrls())
        assertFalse(client.isConnected)
    }

    @Test
    fun `publish returns false when no relays`() = runBlocking {
        val client = NostrClient(CoroutineScope(SupervisorJob() + Dispatchers.IO))
        val event = mockk<NostrEvent>(relaxed = true)
        every { event.id } returns "abc12345"
        assertFalse(client.publish(event))
    }

    @Test
    fun `publishJson returns false for invalid JSON`() = runBlocking {
        val client = NostrClient(CoroutineScope(SupervisorJob() + Dispatchers.IO))
        assertFalse(client.publishJson("not valid json"))
    }

    @Test
    fun `unsubscribe with unknown groupId is no-op`() = runBlocking {
        val client = NostrClient(CoroutineScope(SupervisorJob() + Dispatchers.IO))
        client.unsubscribe("nonexistent-group") // should not throw
    }

    @Test
    fun `unsubscribeAll on empty client is no-op`() = runBlocking {
        val client = NostrClient(CoroutineScope(SupervisorJob() + Dispatchers.IO))
        client.unsubscribeAll() // should not throw
    }

    @Test
    fun `currentRelayUrls returns empty initially`() {
        val client = NostrClient(CoroutineScope(SupervisorJob() + Dispatchers.IO))
        assertEquals(emptyList<String>(), client.currentRelayUrls())
    }

    @Test
    fun `connectionState is false initially`() {
        val client = NostrClient(CoroutineScope(SupervisorJob() + Dispatchers.IO))
        assertFalse(client.connectionState.value)
    }

    @Test
    fun `addRelay ignores duplicate URL`() {
        val client = NostrClient(CoroutineScope(SupervisorJob() + Dispatchers.IO))
        client.addRelay("wss://relay.test.io")
        client.addRelay("wss://relay.test.io") // should not create a second relay
        client.disconnect()
    }
}
