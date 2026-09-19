package com.splitfree.data.nostr

import com.splitfree.data.nostr.relay.Relay
import com.splitfree.domain.crypto.NostrEvent
import com.splitfree.domain.model.sync.HistoryRange
import com.splitfree.sync.nearby.TestIdentity
import com.splitfree.test.LoopbackNostrRelay
import io.mockk.every
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import java.net.InetAddress
import java.util.concurrent.atomic.AtomicInteger
import javax.net.ssl.SSLHandshakeException
import javax.net.ssl.SSLPeerUnverifiedException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONArray
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class NostrClientLoopbackTest {
    private val identity = TestIdentity(45)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var relay: LoopbackNostrRelay
    private lateinit var client: NostrClient
    private val now = System.currentTimeMillis() / 1000

    @Before
    fun setup() = runBlocking {
        mockkStatic(android.util.Log::class)
        every { android.util.Log.w(any<String>(), any<String>()) } returns 0
        every { android.util.Log.i(any<String>(), any<String>()) } returns 0
        every { android.util.Log.d(any<String>(), any<String>()) } returns 0
        relay = LoopbackNostrRelay()
        client = NostrClient(scope, relay.client)
        client.connect(listOf(relay.url))
        withTimeout(10_000) { client.connectionState.first { it } }
        Unit
    }

    @After
    fun teardown() {
        client.disconnect()
        scope.cancel()
        relay.close()
        assertTrue(relay.failures.toString(), relay.failures.isEmpty())
        unmockkStatic(android.util.Log::class)
    }

    private fun event(index: Int, at: Long = now - index - 1) = NostrEvent(
        pubkey = identity.pub,
        createdAt = at,
        kind = 30078,
        tags = listOf(listOf("g", "group")),
        content = "event-$index"
    ).sign(identity.priv)

    @Test
    fun `real TLS pagination resumes large equal-timestamp history without omissions`() = runBlocking {
        val events = (0 until 600).map { event(it, now - 10) }
        relay.history.addAll(events)
        var pending = mapOf(relay.url to listOf(HistoryRange(now - 10, now - 10)))
        val found = mutableSetOf<String>()
        var passes = 0
        do {
            val result = withTimeout(30_000) { client.fetchHistory("group", pending, null) }
            found += result.events.map { it.id }
            assertTrue(result.events.size <= HistoryPaginator.MAX_EVENTS)
            pending = result.pendingByRelay
            passes++
            assertTrue("frontier must progress", passes < 20)
            if (!result.complete) assertTrue(pending.isNotEmpty())
        } while (pending.isNotEmpty())
        assertEquals(events.map { it.id }.toSet(), found)
        assertTrue("test spans durable-pass budget", passes > 1)
        assertTrue(relay.received.any { it.filters.single().has("ids") })
    }

    @Test
    fun `large signed payload saturation advances instead of repeating the same prefix forever`() = runBlocking {
        val content = "a".repeat(90_000)
        val events = (0 until 128).map { index ->
            NostrEvent(
                pubkey = identity.pub,
                createdAt = now - 10,
                kind = 30078,
                tags = listOf(listOf("g", "group")),
                content = "$index:$content"
            ).sign(identity.priv)
        }
        relay.history.addAll(events)
        var pending = mapOf(relay.url to listOf(HistoryRange(now - 10, now - 10)))
        val found = mutableSetOf<String>()
        var passes = 0
        do {
            val result = withTimeout(30_000) { client.fetchHistory("group", pending, null) }
            found += result.events.map { it.id }
            assertTrue(result.events.sumOf { it.toJson().length.toLong() * 2 } <= HistoryPaginator.MAX_BYTES)
            assertTrue("frontier must not stall at byte capacity", result.pendingByRelay != pending)
            pending = result.pendingByRelay
            passes++
            assertTrue(passes < 20)
        } while (pending.isNotEmpty())
        assertEquals(events.map { it.id }.toSet(), found)
        assertTrue(passes > 1)
    }

    @Test
    fun `closed later page cannot certify initial saturated page`() = runBlocking {
        relay.history.addAll((0 until 200).map { event(it) })
        val pages = AtomicInteger()
        relay.respond = { request ->
            if (pages.incrementAndGet() == 1) {
                relay.sendHistory(request)
            } else {
                request.socket.send(JSONArray(listOf("CLOSED", request.subId, "blocked: test page")).toString())
            }
        }
        val result = withTimeout(10_000) { client.fetchEvents("group", 0) }
        assertFalse(result.complete)
        assertTrue(result.completedRelays.isEmpty())
        assertTrue(result.pendingByRelay.getValue(relay.url).isNotEmpty())
    }

    @Test
    fun `missing later page EOSE retains its partition`() = runBlocking {
        relay.history.addAll((0 until 160).map { event(it) })
        val pages = AtomicInteger()
        relay.respond = { request -> if (pages.incrementAndGet() == 1) relay.sendHistory(request) }
        val result = withTimeout(25_000) { client.fetchEvents("group", 0) }
        assertFalse(result.complete)
        assertTrue(result.pendingByRelay.getValue(relay.url).isNotEmpty())
    }

    @Test
    fun `repeated prior page outside new partition is incomplete not skipped`() = runBlocking {
        relay.history.addAll((0 until 160).map { event(it) })
        relay.respond = { request ->
            relay.history.take(128).forEach { relay.sendEvent(request.socket, request.subId, it) }
            relay.eose(request)
        }
        val result = withTimeout(10_000) { client.fetchEvents("group", 0) }
        assertFalse(result.complete)
        assertTrue(result.pendingByRelay.isNotEmpty())
    }

    @Test
    fun `malformed event followed by EOSE cannot certify requested history`() = runBlocking {
        relay.respond = { request ->
            request.socket.send("[\"EVENT\",${org.json.JSONObject.quote(request.subId)},{\"id\":\"short\"}]")
            relay.eose(request)
        }
        val result = withTimeout(10_000) { client.fetchEvents("group", 0) }
        assertFalse(result.complete)
        assertTrue(result.completedRelays.isEmpty())
        assertTrue(result.pendingByRelay.isNotEmpty())
    }

    @Test
    fun `harmless duplicate frames deduplicate without poisoning history`() = runBlocking {
        val event = event(1)
        relay.respond = { request ->
            repeat(128) { relay.sendEvent(request.socket, request.subId, event) }
            relay.eose(request)
        }
        val result = withTimeout(10_000) { client.fetchEvents("group", 0) }
        assertEquals(listOf(event.id), result.events.map { it.id })
        assertTrue(result.complete)
    }

    @Test
    fun `mid-page reconnect cannot certify history even after replay EOSE`() = runBlocking {
        val event = event(1)
        val pages = AtomicInteger()
        relay.respond = { request ->
            relay.sendEvent(request.socket, request.subId, event)
            if (pages.incrementAndGet() == 1) request.socket.close(1000, "restart") else relay.eose(request)
        }
        val result = withTimeout(25_000) { client.fetchEvents("group", 0) }
        assertFalse(result.complete)
        assertTrue(result.pendingByRelay.isNotEmpty())
    }

    @Test
    fun `live stream receives old authored publication after EOSE without replay flood`() = runBlocking {
        val old = event(1, now - 90 * 86400)
        val incoming =
            async(start = CoroutineStart.UNDISPATCHED) {
                withTimeout(10_000) {
                    client.incomingEvents.first {
                        it.id ==
                            old.id
                    }
                }
            }
        val subscribed = CompletableDeferred<Unit>()
        relay.respond = { request ->
            relay.sendHistory(request)
            if (request.filters.all { !it.has("since") && it.optInt("limit", -1) == 0 }) subscribed.complete(Unit)
        }
        client.subscribe("group", now, identity.pub)
        withTimeout(10_000) { subscribed.await() }
        assertTrue(client.publish(old))
        assertEquals(old, incoming.await())
        val stream = relay.received.last { ":fetch:" !in it.subId }
        assertTrue(stream.filters.all { !it.has("since") && it.getInt("limit") == 0 })
    }

    private suspend fun rejectedHandshake(http: okhttp3.OkHttpClient, url: String): Throwable {
        val failure = CompletableDeferred<Throwable>()
        val socket = http.newWebSocket(
            Request.Builder().url(url).build(),
            object : WebSocketListener() {
                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    failure.complete(t)
                }
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    failure.complete(AssertionError("Unexpected successful TLS handshake"))
                    webSocket.close(1000, null)
                }
            }
        )
        return try {
            withTimeout(10_000) { failure.await() }
        } finally {
            socket.cancel()
        }
    }

    @Test
    fun `default production TLS rejects fixture certificate after an actual handshake`() = runBlocking {
        val failure = rejectedHandshake(Relay.sharedClient, relay.url.replace("localhost", "127.0.0.1"))
        assertTrue(failure.toString(), failure is SSLHandshakeException)
        assertTrue(
            generateSequence(failure) { it.cause }.any {
                it.message.orEmpty().contains("cert", ignoreCase = true) || it.message.orEmpty().contains("PKIX")
            }
        )
    }

    @Test
    fun `test-only CA trust still rejects wrong TLS hostname`() = runBlocking {
        val http = relay.client.newBuilder().dns { host ->
            check(host == "wrong.localhost")
            listOf(InetAddress.getByName("127.0.0.1"))
        }.build()
        val failure = rejectedHandshake(http, relay.url.replace("localhost", "wrong.localhost"))
        assertTrue(failure.toString(), failure is SSLPeerUnverifiedException)
        assertTrue(failure.message.orEmpty().contains("Hostname"))
    }
}
