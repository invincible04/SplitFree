package com.splitfree.domain.crypto.integration

import com.splitfree.domain.crypto.NostrEvent
import com.splitfree.domain.crypto.nip.Nip44
import com.splitfree.domain.util.hexToBytes
import com.splitfree.domain.util.toHex
import com.splitfree.test.RelayProbeAssertions.assertAccepted
import com.splitfree.test.RelayProbeAssertions.requireEvents
import fr.acinq.secp256k1.Secp256k1
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import java.io.Closeable
import java.security.SecureRandom
import java.util.UUID
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Assume
import org.junit.Before
import org.junit.Test

/** Raw WebSocket probes deliberately bypass NostrClient and require explicit network opt-in. */
class NostrRelayIntegrationTest {
    private val relays = listOf("wss://nos.lol", "wss://offchain.pub", "wss://relay.primal.net")
    private lateinit var privKey: ByteArray
    private lateinit var pubKeyHex: String
    private lateinit var httpClient: OkHttpClient

    @Before
    fun setup() {
        Assume.assumeTrue("Skipped: set -DREAL_RELAY_TEST=true", System.getProperty("REAL_RELAY_TEST") == "true")
        privKey = generateValidPrivateKey()
        pubKeyHex = NostrEvent.pubkeyFromPrivkey(privKey)
        httpClient = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build()
    }

    @After
    fun teardown() {
        if (::privKey.isInitialized) privKey.fill(0)
        if (::httpClient.isInitialized) {
            httpClient.dispatcher.cancelAll()
            httpClient.connectionPool.evictAll()
        }
    }

    private fun generateValidPrivateKey(): ByteArray {
        val key = ByteArray(32)
        val random = SecureRandom()
        do {
            random.nextBytes(key)
        } while (!Secp256k1.secKeyVerify(key))
        return key
    }

    @Test
    fun `publish encrypted event to real relay and read it back`() {
        encryptedRoundTrip(kind = 1)
    }

    @Test
    fun `relay accepts and stores encrypted kind 30078 NIP-78 events`() {
        encryptedRoundTrip(kind = 30078)
    }

    @Test
    fun `NIP-44 recipient decrypts independently fetched relay event`() {
        encryptedRoundTrip(kind = 30078)
    }

    private fun encryptedRoundTrip(kind: Int) {
        val recipientPriv = generateValidPrivateKey()
        try {
            val recipientPub = NostrEvent.pubkeyFromPrivkey(recipientPriv).hexToBytes()
            val senderKey = Nip44.getConversationKey(privKey, recipientPub)
            try {
                val plaintext = "disposable expense probe ${UUID.randomUUID()}"
                val event = NostrEvent(
                    pubkey = pubKeyHex,
                    createdAt = System.currentTimeMillis() / 1000,
                    kind = kind,
                    tags = listOf(listOf("d", UUID.randomUUID().toString()), listOf("p", recipientPub.toHex())),
                    content = Nip44.encrypt(plaintext, senderKey)
                ).sign(privKey)
                val recipientKey = Nip44.getConversationKey(recipientPriv, pubKeyHex.hexToBytes())
                try {
                    relays.forEach { url ->
                        val received = publishAndReadBack(url, event)
                        assertEquals(
                            "Recipient must decrypt the remotely fetched event on $url",
                            plaintext,
                            Nip44.decrypt(received.content, recipientKey)
                        )
                    }
                } finally {
                    recipientKey.fill(0)
                }
            } finally {
                senderKey.fill(0)
            }
        } finally {
            recipientPriv.fill(0)
        }
    }

    private fun publishAndReadBack(relayUrl: String, event: NostrEvent): NostrEvent {
        assertTrue("Published fixture must verify", event.verify())
        openWs(relayUrl).use { publisher ->
            publisher.send("""["EVENT",${event.toJson()}]""")
            publisher.requireAccepted(event.id)
        }

        // Fetch on a new socket after acceptance to distinguish stored history from a live echo.
        return openWs(relayUrl).use { reader ->
            val subId = UUID.randomUUID().toString()
            val received = mutableListOf<NostrEvent>()
            try {
                reader.send("""["REQ","$subId",{"ids":["${event.id}"]}]""")
                reader.awaitMessage { message ->
                    when (message) {
                        is Msg.Event -> {
                            if (message.subId == subId) received.add(message.event)
                            false
                        }
                        is Msg.Eose -> message.subId == subId
                        is Msg.Closed -> {
                            if (message.subId == subId) {
                                throw AssertionError("$relayUrl closed subscription: ${message.reason}")
                            }
                            false
                        }
                        else -> false
                    }
                }
                requireEvents(listOf(event), received).single()
            } finally {
                reader.socket.send("""["CLOSE","$subId"]""")
            }
        }
    }

    internal sealed class Frame {
        object Opened : Frame()
        data class Text(val text: String) : Frame()
        data class Failed(val cause: Throwable) : Frame()
    }

    internal inner class RelaySocket(
        val socket: WebSocket,
        private val inbox: LinkedBlockingQueue<Frame>,
        private val url: String
    ) : Closeable {
        fun send(text: String) {
            assertTrue("WebSocket send failed on $url", socket.send(text))
        }

        fun awaitOpen(timeoutNanos: Long = TimeUnit.SECONDS.toNanos(10)): RelaySocket {
            try {
                awaitFrame(timeoutNanos) { it is Frame.Opened }
                return this
            } catch (failure: Throwable) {
                close()
                throw failure
            }
        }

        fun requireAccepted(eventId: String) {
            val ok = awaitMessage { it is Msg.Ok && it.eventId == eventId } as Msg.Ok
            assertAccepted(ok.accepted)
        }

        fun awaitFrame(timeoutNanos: Long = TimeUnit.SECONDS.toNanos(10), predicate: (Frame) -> Boolean): Frame {
            val deadline = System.nanoTime() + timeoutNanos
            while (true) {
                val remaining = deadline - System.nanoTime()
                assertTrue("Timed out waiting for relay response on $url", remaining > 0)
                val frame = inbox.poll(remaining, TimeUnit.NANOSECONDS)
                    ?: throw AssertionError("Timed out waiting for relay response on $url")
                if (frame is Frame.Failed) throw AssertionError("Relay connection failed on $url", frame.cause)
                if (predicate(frame)) return frame
            }
        }

        fun awaitMessage(predicate: (Msg) -> Boolean): Msg {
            val frame = awaitFrame { it is Frame.Text && predicate(parseMsg(it.text)) } as Frame.Text
            return parseMsg(frame.text)
        }

        override fun close() {
            socket.close(1000, "done")
            socket.cancel()
        }
    }

    private fun openWs(url: String): RelaySocket {
        val inbox = LinkedBlockingQueue<Frame>()
        val socket = httpClient.newWebSocket(
            Request.Builder().url(url).build(),
            object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    inbox.offer(Frame.Opened)
                }

                override fun onMessage(webSocket: WebSocket, text: String) {
                    inbox.offer(Frame.Text(text))
                }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    inbox.offer(Frame.Failed(t))
                }

                override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                    inbox.offer(Frame.Failed(IllegalStateException("Relay closed socket: $code $reason")))
                    webSocket.close(code, reason)
                }
            }
        )
        return RelaySocket(socket, inbox, url).awaitOpen()
    }

    internal sealed class Msg {
        data class Ok(val eventId: String, val accepted: Boolean) : Msg()
        data class Event(val subId: String, val event: NostrEvent) : Msg()
        data class Eose(val subId: String) : Msg()
        data class Closed(val subId: String, val reason: String) : Msg()
        object Other : Msg()
    }

    private fun parseMsg(json: String): Msg {
        val arr = Json.parseToJsonElement(json.trim()).jsonArray
        return when (arr[0].jsonPrimitive.content) {
            "OK" -> {
                Msg.Ok(arr[1].jsonPrimitive.content, arr[2].jsonPrimitive.boolean)
            }

            "EVENT" -> {
                val o = arr[2].jsonObject
                val tags =
                    o["tags"]?.jsonArray?.map { t ->
                        t.jsonArray.map { it.jsonPrimitive.content }
                    } ?: emptyList()
                Msg.Event(
                    arr[1].jsonPrimitive.content,
                    NostrEvent(
                        id = o["id"]!!.jsonPrimitive.content,
                        pubkey = o["pubkey"]!!.jsonPrimitive.content,
                        createdAt = o["created_at"]!!.jsonPrimitive.long,
                        kind = o["kind"]!!.jsonPrimitive.int,
                        tags = tags,
                        content = o["content"]!!.jsonPrimitive.content,
                        sig = o["sig"]!!.jsonPrimitive.content
                    )
                )
            }

            "EOSE" -> Msg.Eose(arr[1].jsonPrimitive.content)
            "CLOSED" -> Msg.Closed(arr[1].jsonPrimitive.content, arr[2].jsonPrimitive.content)
            else -> Msg.Other
        }
    }
}

class RawRelayProtocolOfflineTest {
    private val probe = NostrRelayIntegrationTest()
    private val socket = mockk<WebSocket>(relaxed = true)
    private val inbox = LinkedBlockingQueue<NostrRelayIntegrationTest.Frame>()
    private val connection = probe.RelaySocket(socket, inbox, "offline relay fixture")

    @Test
    fun `unreachable relay fails and disposes the opening socket`() {
        inbox.add(NostrRelayIntegrationTest.Frame.Failed(IllegalStateException("offline")))
        assertThrows(AssertionError::class.java) { connection.awaitOpen() }
        verify(exactly = 1) { socket.close(1000, "done") }
        verify(exactly = 1) { socket.cancel() }
    }

    @Test
    fun `no open response fails instead of returning null or skipping`() {
        assertThrows(AssertionError::class.java) { connection.awaitOpen(timeoutNanos = 0) }
        verify(exactly = 1) { socket.cancel() }
    }

    @Test
    fun `raw relay rejection fails`() {
        inbox.add(NostrRelayIntegrationTest.Frame.Text("""["OK","expected",false,"rejected"]"""))
        assertThrows(AssertionError::class.java) { connection.requireAccepted("expected") }
    }

    @Test
    fun `unrelated OK cannot make the raw publish pass`() {
        inbox.add(NostrRelayIntegrationTest.Frame.Text("""["OK","unrelated",true,""]"""))
        inbox.add(NostrRelayIntegrationTest.Frame.Failed(IllegalStateException("connection lost")))
        assertThrows(AssertionError::class.java) { connection.requireAccepted("expected") }
    }

    @Test
    fun `raw send failure cannot pass`() {
        every { socket.send(any<String>()) } returns false
        assertThrows(AssertionError::class.java) { connection.send("offline fixture") }
    }
}
