package com.splitfree.data.nostr.relay

import com.splitfree.data.nostr.protocol.NostrFilter
import com.splitfree.data.nostr.protocol.RelayMessage
import com.splitfree.domain.crypto.NostrEvent
import com.splitfree.domain.crypto.NostrKind
import com.splitfree.util.DebugLog as Log
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * Health check result from a NIP-11 info document probe.
 *
 * @property url the `wss://` relay URL that was checked
 * @property online whether the relay responded successfully to a NIP-11 request
 * @property latencyMs round-trip time in milliseconds for the NIP-11 HTTP call
 * @property paid `true` if the relay's NIP-11 `limitation.payment_required` is set
 * @property supportsGiftWrap `true` if `59` appears in `supported_nips` (NIP-59 gift wrap).
 *   Note: most relays accept kind 1059 events without advertising NIP-59.
 * @property supportedNips full list of NIPs the relay advertises
 * @property verified `true` if a kind-30078 write+read round-trip succeeded
 * @property checkedAt epoch millis when this check was performed
 */
data class RelayStatus(
    val url: String,
    val online: Boolean,
    val latencyMs: Long = 0,
    val paid: Boolean = false,
    val supportsGiftWrap: Boolean = false,
    val supportedNips: List<Int> = emptyList(),
    val verified: Boolean = false,
    val checkedAt: Long = System.currentTimeMillis()
)

/**
 * Monitors relay health by probing [NIP-11](https://github.com/nostr-protocol/nips/blob/master/11.md)
 * info documents via HTTP GET with `Accept: application/nostr+json`.
 *
 * Parses the response to extract:
 * - **Online status** — did the relay respond with HTTP 200?
 * - **Latency** — round-trip time for the NIP-11 request
 * - **Payment required** — from `limitation.payment_required`
 * - **Supported NIPs** — from `supported_nips` array (e.g. NIP-59 gift wrap)
 *
 * Shares the app-wide [OkHttpClient] connection pool and TLS session cache.
 * All HTTP calls run on [Dispatchers.IO] to avoid blocking the main thread.
 */
@Singleton
class RelayHealthMonitor
@Inject
constructor(private val httpClient: OkHttpClient) {
    private val _statuses = java.util.concurrent.ConcurrentHashMap<String, RelayStatus>()

    /** Latest check results keyed by relay URL. */
    val statuses: Map<String, RelayStatus> get() = _statuses.toMap()

    private val json = Json { ignoreUnknownKeys = true }

    /**
     * Probe all [relayUrls] concurrently via NIP-11 info document.
     * Results are stored in [statuses] and can be read after this call completes.
     *
     * @param relayUrls list of `wss://` relay URLs to check
     */
    suspend fun checkRelays(relayUrls: List<String>) {
        coroutineScope {
            relayUrls.map { url -> async { testRelay(url) } }
                .forEach { _statuses[it.await().url] = it.await() }
        }
    }

    /**
     * @param relayUrls candidate URLs
     * @return subset of [relayUrls] that were online at last check
     */
    fun getOnlineRelays(relayUrls: List<String>): List<String> = relayUrls.filter { _statuses[it]?.online == true }

    /**
     * Probe a single relay by requesting its NIP-11 info document.
     * Converts `wss://` → `https://` and sends `Accept: application/nostr+json`.
     * Runs on [Dispatchers.IO] since OkHttp `execute()` is blocking.
     */
    private suspend fun testRelay(url: String): RelayStatus = try {
        withTimeout(5000L) {
            withContext(Dispatchers.IO) {
                val start = System.currentTimeMillis()
                val httpUrl = url.replace("wss://", "https://").replace("ws://", "http://")
                val request = Request.Builder()
                    .url(httpUrl)
                    .header("Accept", "application/nostr+json")
                    .build()
                httpClient.newCall(request).execute().use { response ->
                    val latency = System.currentTimeMillis() - start
                    if (!response.isSuccessful) return@use RelayStatus(url, false, latency)
                    parseNip11(url, response.body?.string(), latency)
                }
            }
        }
    } catch (e: Exception) {
        Log.w(TAG, "Relay $url offline: ${e.message}")
        RelayStatus(url, online = false)
    }

    /**
     * Parse a NIP-11 JSON response body to extract relay capabilities.
     * Gracefully handles missing fields and malformed JSON — returns online=true
     * with defaults if parsing fails (the relay responded, so it's reachable).
     */
    private fun parseNip11(url: String, body: String?, latencyMs: Long): RelayStatus {
        if (body.isNullOrBlank()) return RelayStatus(url, true, latencyMs)
        return try {
            val root = json.parseToJsonElement(body).jsonObject
            val nips = root["supported_nips"]?.jsonArray
                ?.mapNotNull { runCatching { it.jsonPrimitive.int }.getOrNull() }
                ?: emptyList()
            val limitation = root["limitation"]?.jsonObject
            val paid = limitation?.get("payment_required")
                ?.jsonPrimitive?.boolean == true
            RelayStatus(
                url = url,
                online = true,
                latencyMs = latencyMs,
                paid = paid,
                supportsGiftWrap = 59 in nips,
                supportedNips = nips
            )
        } catch (e: Exception) {
            Log.w(TAG, "NIP-11 parse failed for $url: ${e.message}")
            RelayStatus(url, true, latencyMs)
        }
    }

    /**
     * Verify a relay can actually store and return kind-30078 events by doing a
     * write → read round-trip with a temporary WebSocket connection.
     *
     * @param url `wss://` relay URL to verify
     * @param testEvent a signed kind-30078 event to publish and fetch back
     * @return `true` if the relay accepted the event AND returned it on fetch
     */
    suspend fun verifyRelayRoundTrip(url: String, testEvent: NostrEvent): Boolean = withContext(Dispatchers.IO) {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val relay = Relay(url, scope, httpClient)
        try {
            relay.connect()
            // Wait for connection
            withTimeout(5_000) {
                while (relay.state.value != Relay.State.CONNECTED) delay(50)
            }
            // Publish and wait for OK
            val accepted = relay.sendEvent(testEvent, timeoutMs = 5_000)
            if (!accepted) {
                Log.w(TAG, "Verify $url: relay rejected test event")
                return@withContext false
            }
            delay(500) // let relay index the event

            // Fetch it back
            val subId = "verify:${testEvent.id.take(8)}"
            val dTag = testEvent.tags.firstOrNull { it.firstOrNull() == "d" }?.getOrNull(1) ?: ""
            val eose = CompletableDeferred<Unit>()
            var found = false
            val collectJob = scope.launch {
                relay.messages.collect { msg ->
                    when (msg) {
                        is RelayMessage.EventMsg ->
                            if (msg.subId == subId && msg.event.id == testEvent.id) found = true
                        is RelayMessage.EoseMsg ->
                            if (msg.subId == subId) eose.complete(Unit)
                        else -> {}
                    }
                }
            }
            delay(50) // let collector start
            relay.subscribe(
                subId,
                listOf(
                    NostrFilter(
                        kinds = listOf(NostrKind.APP_SPECIFIC),
                        tags = mapOf("#d" to listOf(dTag)),
                        authors = listOf(testEvent.pubkey)
                    )
                )
            )
            try {
                withTimeout(5_000) { eose.await() }
            } catch (_: Exception) {}
            collectJob.cancel()
            Log.i(TAG, "Verify $url: round-trip ${if (found) "OK" else "FAILED"}")
            found
        } catch (e: Exception) {
            Log.w(TAG, "Verify $url failed: ${e.message}")
            false
        } finally {
            relay.disconnect()
        }
    }

    companion object {
        private const val TAG = "RelayHealthMonitor"
    }
}
