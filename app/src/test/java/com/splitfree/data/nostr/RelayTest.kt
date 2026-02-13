package com.splitfree.data.nostr

import com.splitfree.domain.crypto.NostrEvent
import io.mockk.*
import org.junit.Assert.*
import org.junit.After
import org.junit.Before
import org.junit.Test

class RelayTest {

    @Before
    fun setup() {
        mockkStatic(android.util.Log::class)
        every { android.util.Log.d(any<String>(), any<String>()) } returns 0
        every { android.util.Log.w(any<String>(), any<String>()) } returns 0
    }

    @After
    fun teardown() {
        unmockkStatic(android.util.Log::class)
    }

    @Test
    fun `initial state is DISCONNECTED`() {
        val relay = Relay("wss://test.relay", kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Unconfined))
        assertEquals(Relay.State.DISCONNECTED, relay.state.value)
    }

    @Test
    fun `send returns false when not connected`() {
        val relay = Relay("wss://test.relay", kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Unconfined))
        assertFalse(relay.send("test"))
    }

    @Test
    fun `disconnect resets state`() {
        val relay = Relay("wss://test.relay", kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Unconfined))
        relay.disconnect()
        assertEquals(Relay.State.DISCONNECTED, relay.state.value)
    }

    @Test
    fun `subscribe tracks active subs`() {
        val relay = Relay("wss://test.relay", kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Unconfined))
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
}
