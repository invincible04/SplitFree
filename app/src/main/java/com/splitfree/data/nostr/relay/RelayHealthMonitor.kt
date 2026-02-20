package com.splitfree.data.nostr.relay

import com.splitfree.util.DebugLog as Log
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * Relay health check result from a NIP-11 info document probe.
 */
data class RelayStatus(
    val url: String,
    /** Whether the relay responded to a NIP-11 info document request. */
    val online: Boolean,
    /** Round-trip time in milliseconds for the NIP-11 check. */
    val latencyMs: Long = 0,
    val checkedAt: Long = System.currentTimeMillis()
)

/**
 * Monitors relay health by probing NIP-11 info documents.
 * Tests relays via HTTP GET with `Accept: application/nostr+json` header.
 * Shares the app-wide [OkHttpClient] connection pool and TLS session cache.
 */
@Singleton
class RelayHealthMonitor
@Inject
constructor(private val httpClient: OkHttpClient) {
    private val _statuses = java.util.concurrent.ConcurrentHashMap<String, RelayStatus>()
    val statuses: Map<String, RelayStatus> get() = _statuses.toMap()

    /**
     * Test all relays concurrently via NIP-11 info document.
     *
     * @param relayUrls list of `wss://` relay URLs to check
     */
    suspend fun checkRelays(relayUrls: List<String>) {
        coroutineScope {
            relayUrls
                .map { url ->
                    async { testRelay(url) }
                }.forEach { deferred ->
                    val status = deferred.await()
                    _statuses[status.url] = status
                }
        }
    }

    /**
     * @param relayUrls candidate URLs
     * @return subset of [relayUrls] that were online at last check
     */
    fun getOnlineRelays(relayUrls: List<String>): List<String> = relayUrls.filter { _statuses[it]?.online == true }

    /**
     * Test relay by requesting NIP-11 info document.
     * Nostr relays respond to HTTP GET with Accept: application/nostr+json header.
     */
    private suspend fun testRelay(url: String): RelayStatus = try {
        withTimeout(5000L) {
            val start = System.currentTimeMillis()
            val httpUrl = url.replace("wss://", "https://").replace("ws://", "http://")
            val request = Request.Builder()
                .url(httpUrl)
                .header("Accept", "application/nostr+json")
                .build()
            httpClient.newCall(request).execute().use { response ->
                val latency = System.currentTimeMillis() - start
                RelayStatus(url, response.isSuccessful, latency)
            }
        }
    } catch (e: Exception) {
        Log.w(TAG, "Relay $url offline: ${e.message}")
        RelayStatus(url, online = false)
    }

    companion object {
        private const val TAG = "RelayHealthMonitor"
    }
}
