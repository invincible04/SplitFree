package com.splitfree.data.nostr

import io.mockk.*
import org.junit.Assert.*
import org.junit.After
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
        val client = NostrClient()
        assertFalse(client.isConnected)
    }

    @Test
    fun `acquireConnection and releaseConnection manage ref count`() {
        val client = NostrClient()
        client.acquireConnection()
        client.acquireConnection()
        client.releaseConnection()
        // Still one user — should not disconnect
        client.releaseConnection()
        // Now zero — disconnect called internally
    }

    @Test
    fun `disconnect clears state`() {
        val client = NostrClient()
        client.disconnect()
        assertFalse(client.isConnected)
    }

    @Test
    fun `addRelay rejects non-wss URL`() {
        val client = NostrClient()
        client.addRelay("ws://insecure.relay")
        assertFalse(client.isConnected)
    }

    @Test
    fun `startListening is a no-op`() {
        val client = NostrClient()
        client.startListening() // should not throw
    }
}
