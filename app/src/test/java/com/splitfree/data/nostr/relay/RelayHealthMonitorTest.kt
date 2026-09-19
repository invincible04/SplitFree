package com.splitfree.data.nostr.relay

import com.splitfree.data.nostr.relay.RelayHealthMonitor
import com.splitfree.data.nostr.relay.RelayStatus
import com.splitfree.domain.util.RelayDefaults
import io.mockk.every
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class RelayHealthMonitorTest {
    private val monitor = RelayHealthMonitor(okhttp3.OkHttpClient())

    @Before
    fun setup() {
        mockkStatic(android.util.Log::class)
        every { android.util.Log.w(any<String>(), any<String>()) } returns 0
    }

    @After
    fun teardown() {
        unmockkAll()
    }

    @Test
    fun `statuses initially empty`() {
        assertTrue(monitor.statuses.isEmpty())
    }

    @Test
    fun `getOnlineRelays returns empty when no status known`() {
        val relays = listOf("wss://a", "wss://b")
        assertEquals(emptyList<String>(), monitor.getOnlineRelays(relays))
    }

    @Test
    fun `getOnlineRelays filters offline relays`() {
        // Inject a known-offline status via checkRelays with invalid URL
        runBlocking { monitor.checkRelays(listOf("wss://localhost:1")) }
        // localhost:1 should fail → offline
        val status = monitor.statuses["wss://localhost:1"]
        assertNotNull(status)
        assertFalse(status!!.online)
        // getOnlineRelays should exclude it
        assertEquals(emptyList<String>(), monitor.getOnlineRelays(listOf("wss://localhost:1")))
    }

    @Test
    fun `checkRelays stores offline status for unreachable relay`() = runBlocking {
        monitor.checkRelays(listOf("wss://localhost:1"))
        val status = monitor.statuses["wss://localhost:1"]
        assertNotNull(status)
        assertEquals("wss://localhost:1", status!!.url)
        assertFalse(status.online)
    }

    @Test
    fun `checkRelays handles multiple relays`() = runBlocking {
        monitor.checkRelays(listOf("wss://localhost:1", "wss://localhost:2"))
        assertEquals(2, monitor.statuses.size)
        assertFalse(monitor.statuses["wss://localhost:1"]!!.online)
        assertFalse(monitor.statuses["wss://localhost:2"]!!.online)
    }

    @Test
    fun `checkRelays with empty list does nothing`() = runBlocking {
        monitor.checkRelays(emptyList())
        assertTrue(monitor.statuses.isEmpty())
    }

    @Test
    fun `successful response body supplies relay capabilities`() = runBlocking {
        val probe = monitorWithResponse(200, """{"supported_nips":[1,59],"limitation":{"payment_required":true}}""")

        probe.checkRelays(listOf("wss://relay.test"))

        val status = probe.statuses.getValue("wss://relay.test")
        assertTrue(status.online)
        assertTrue(status.paid)
        assertTrue(status.supportsGiftWrap)
        assertEquals(listOf(1, 59), status.supportedNips)
        assertEquals(listOf("wss://relay.test"), probe.getOnlineRelays(listOf("wss://relay.test")))
    }

    @Test
    fun `empty successful response remains online with default capabilities`() = runBlocking {
        for (code in listOf(200, 204)) {
            val probe = monitorWithResponse(code, "")

            probe.checkRelays(listOf("wss://relay.test"))

            val status = probe.statuses.getValue("wss://relay.test")
            assertTrue("HTTP $code", status.online)
            assertFalse(status.paid)
            assertFalse(status.supportsGiftWrap)
            assertTrue(status.supportedNips.isEmpty())
        }
    }

    @Test
    fun `malformed successful response remains online with default capabilities`() = runBlocking {
        val probe = monitorWithResponse(200, "not JSON")

        probe.checkRelays(listOf("wss://relay.test"))

        val status = probe.statuses.getValue("wss://relay.test")
        assertTrue(status.online)
        assertFalse(status.paid)
        assertFalse(status.supportsGiftWrap)
        assertTrue(status.supportedNips.isEmpty())
    }

    @Test
    fun `unsuccessful response stays offline even with a valid info document`() = runBlocking {
        val probe = monitorWithResponse(503, """{"supported_nips":[59]}""")

        probe.checkRelays(listOf("wss://relay.test"))

        val status = probe.statuses.getValue("wss://relay.test")
        assertFalse(status.online)
        assertFalse(status.supportsGiftWrap)
        assertTrue(probe.getOnlineRelays(listOf("wss://relay.test")).isEmpty())
    }

    private fun monitorWithResponse(code: Int, body: String): RelayHealthMonitor = RelayHealthMonitor(
        OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            assertEquals("https://relay.test/", request.url.toString())
            assertEquals("application/nostr+json", request.header("Accept"))
            Response.Builder()
                .request(request)
                .protocol(Protocol.HTTP_1_1)
                .code(code)
                .message("test response")
                .body(body.toResponseBody())
                .build()
        }.build()
    )

    @Test
    fun `FALLBACK_RELAYS are all wss`() {
        assertTrue(RelayDefaults.FALLBACK_RELAYS.all { it.startsWith("wss://") })
        assertTrue(RelayDefaults.FALLBACK_RELAYS.isNotEmpty())
    }

    @Test
    fun `RelayStatus data class fields`() {
        val status = RelayStatus("wss://test", online = true, latencyMs = 42, checkedAt = 1000)
        assertEquals("wss://test", status.url)
        assertTrue(status.online)
        assertEquals(42L, status.latencyMs)
        assertEquals(1000L, status.checkedAt)
    }

    @Test
    fun `RelayStatus defaults`() {
        val status = RelayStatus("wss://test", online = false)
        assertEquals(0L, status.latencyMs)
    }
}
