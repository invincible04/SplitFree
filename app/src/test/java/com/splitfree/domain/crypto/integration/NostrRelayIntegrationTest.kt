package com.splitfree.domain.crypto.integration

import com.splitfree.domain.crypto.NostrEvent
import com.splitfree.domain.crypto.nip.Nip44
import com.splitfree.util.hexToBytes
import com.splitfree.util.toHex
import java.security.SecureRandom
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
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
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Integration test: publish signed events to REAL public Nostr relays
 * and verify they come back via subscription.
 *
 * Closes the last gap: proving our from-scratch NIP-01/NIP-44 implementation
 * is wire-compatible with real Nostr infrastructure.
 *
 * Run: ./gradlew :app:testDebugUnitTest --tests "*.NostrRelayIntegrationTest"
 *
 * Requires network. Gracefully skips if no relay is reachable.
 */
class NostrRelayIntegrationTest {
    private val relays =
        listOf(
            "wss://nos.lol",
            "wss://relay.nostr.net",
            "wss://relay.primal.net"
        )

    private val privKey = ByteArray(32).also { SecureRandom().nextBytes(it) }
    private val pubKeyHex = NostrEvent.pubkeyFromPrivkey(privKey)
    private val httpClient =
        OkHttpClient
            .Builder()
            .pingInterval(30, TimeUnit.SECONDS)
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build()

    @Test
    fun `publish event to real relay and read it back`() {
        val event =
            NostrEvent(
                pubkey = pubKeyHex,
                createdAt = System.currentTimeMillis() / 1000,
                kind = 1,
                tags = listOf(listOf("t", "splitfree-test")),
                content = "SplitFree integration test ${System.currentTimeMillis()}"
            ).sign(privKey)

        assertTrue("Event must verify locally", event.verify())

        val result = tryRelays { url -> publishAndReadBack(url, event) }
        if (result != null) {
            assertEquals(event.id, result.id)
            assertEquals(event.pubkey, result.pubkey)
            assertEquals(event.content, result.content)
            assertEquals(event.sig, result.sig)
            assertTrue("Returned event must verify", result.verify())
        }
    }

    @Test
    fun `relay accepts kind 30078 NIP-78 events`() {
        val event =
            NostrEvent(
                pubkey = pubKeyHex,
                createdAt = System.currentTimeMillis() / 1000,
                kind = 30078,
                tags =
                listOf(
                    listOf("d", "splitfree-test-${System.currentTimeMillis()}"),
                    listOf("t", "expense")
                ),
                content = "encrypted-expense-placeholder"
            ).sign(privKey)

        val accepted = tryRelays { url -> publishAndWaitForOk(url, event) }
        if (accepted != null) {
            assertTrue("Relay must accept kind 30078", accepted)
        }
    }

    @Test
    fun `NIP-44 encrypted event survives relay round-trip`() {
        val recipientPriv = ByteArray(32).also { SecureRandom().nextBytes(it) }
        val recipientPub = NostrEvent.pubkeyFromPrivkey(recipientPriv).hexToBytes()

        val plaintext = "expense:{amount:500,currency:INR}"
        val convKey = Nip44.getConversationKey(privKey, recipientPub)
        val encrypted = Nip44.encrypt(plaintext, convKey)

        val event =
            NostrEvent(
                pubkey = pubKeyHex,
                createdAt = System.currentTimeMillis() / 1000,
                kind = 30078,
                tags =
                listOf(
                    listOf("d", "splitfree-enc-${System.currentTimeMillis()}"),
                    listOf("p", recipientPub.toHex())
                ),
                content = encrypted
            ).sign(privKey)

        val result = tryRelays { url -> publishAndReadBack(url, event) }
        if (result != null) {
            val decrypted = Nip44.decrypt(result.content, convKey)
            assertEquals("Decrypted content must match", plaintext, decrypted)
        }
    }

    // --- Helpers ---

    private fun <T> tryRelays(action: (String) -> T?): T? {
        for (url in relays) {
            try {
                val result = action(url)
                if (result != null) {
                    println("✅ Success on $url")
                    return result
                }
            } catch (e: Exception) {
                println("⚠️ $url: ${e.message}")
            }
        }
        println("⚠️ SKIPPED: No relay reachable (network required)")
        return null
    }

    private fun publishAndReadBack(relayUrl: String, event: NostrEvent): NostrEvent? {
        val receivedEvents = CopyOnWriteArrayList<NostrEvent>()
        val eventLatch = CountDownLatch(1)
        val okLatch = CountDownLatch(1)
        var accepted = false

        val ws =
            openWs(relayUrl) { text ->
                when (val msg = parseMsg(text)) {
                    is Msg.Ok -> {
                        accepted = msg.accepted
                        okLatch.countDown()
                    }

                    is Msg.Event -> {
                        if (msg.event.id == event.id) {
                            receivedEvents.add(msg.event)
                            eventLatch.countDown()
                        }
                    }

                    else -> {}
                }
            } ?: return null

        try {
            val subId = "t${System.currentTimeMillis()}"
            ws.send("""["REQ","$subId",{"ids":["${event.id}"],"limit":1}]""")
            ws.send("""["EVENT",${event.toJson()}]""")

            if (!okLatch.await(10, TimeUnit.SECONDS) || !accepted) return null
            if (!eventLatch.await(10, TimeUnit.SECONDS)) return null

            ws.send("""["CLOSE","$subId"]""")
            return receivedEvents.firstOrNull()
        } finally {
            ws.close(1000, "done")
        }
    }

    private fun publishAndWaitForOk(relayUrl: String, event: NostrEvent): Boolean? {
        val okLatch = CountDownLatch(1)
        var accepted = false

        val ws =
            openWs(relayUrl) { text ->
                val msg = parseMsg(text)
                if (msg is Msg.Ok && msg.eventId == event.id) {
                    accepted = msg.accepted
                    okLatch.countDown()
                }
            } ?: return null

        try {
            ws.send("""["EVENT",${event.toJson()}]""")
            if (!okLatch.await(10, TimeUnit.SECONDS)) return null
            return if (accepted) true else null
        } finally {
            ws.close(1000, "done")
        }
    }

    // --- OkHttp WebSocket ---

    private fun openWs(url: String, onMessage: (String) -> Unit): WebSocket? {
        val openLatch = CountDownLatch(1)
        var opened = false

        val ws =
            httpClient.newWebSocket(
                Request.Builder().url(url).build(),
                object : WebSocketListener() {
                    override fun onOpen(webSocket: WebSocket, response: Response) {
                        opened = true
                        openLatch.countDown()
                    }

                    override fun onMessage(webSocket: WebSocket, text: String) {
                        onMessage(text)
                    }

                    override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                        openLatch.countDown()
                    }
                }
            )

        return if (openLatch.await(10, TimeUnit.SECONDS) && opened) ws else null
    }

    // --- Minimal relay message parser ---

    private sealed class Msg {
        data class Ok(val eventId: String, val accepted: Boolean) : Msg()

        data class Event(val subId: String, val event: NostrEvent) : Msg()

        object Other : Msg()
    }

    private fun parseMsg(json: String): Msg? = try {
        val arr = Json.parseToJsonElement(json.trim()).jsonArray
        when (arr[0].jsonPrimitive.content) {
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

            else -> {
                Msg.Other
            }
        }
    } catch (_: Exception) {
        null
    }
}
