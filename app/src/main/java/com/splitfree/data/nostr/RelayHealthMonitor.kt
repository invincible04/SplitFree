package com.splitfree.data.nostr

import com.splitfree.util.DebugLog as Log
import kotlinx.coroutines.*
import java.net.URI
import javax.inject.Inject
import javax.inject.Singleton

data class RelayStatus(
    val url: String,
    val online: Boolean,
    val latencyMs: Long = 0,
    val checkedAt: Long = System.currentTimeMillis(),
)

/**
 * Monitors relay health per design doc Section 5.5.
 * Tests relays via NIP-11 info document (HTTP GET with Accept: application/nostr+json).
 */
@Singleton
class RelayHealthMonitor
    @Inject
    constructor() {
        private val _statuses = java.util.concurrent.ConcurrentHashMap<String, RelayStatus>()
        val statuses: Map<String, RelayStatus> get() = _statuses.toMap()

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

        fun getOnlineRelays(relayUrls: List<String>): List<String> = relayUrls.filter { _statuses[it]?.online == true }

        /**
         * Test relay by requesting NIP-11 info document.
         * Nostr relays respond to HTTP GET with Accept: application/nostr+json header.
         */
        private suspend fun testRelay(url: String): RelayStatus =
            try {
                withTimeout(5000L) {
                    val start = System.currentTimeMillis()
                    val httpUrl = url.replace("wss://", "https://").replace("ws://", "http://")
                    val conn = java.net.URL(httpUrl).openConnection() as java.net.HttpURLConnection
                    conn.connectTimeout = 5000
                    conn.readTimeout = 5000
                    conn.requestMethod = "GET"
                    conn.setRequestProperty("Accept", "application/nostr+json")
                    try {
                        conn.connect()
                        val latency = System.currentTimeMillis() - start
                        val online = conn.responseCode == 200
                        RelayStatus(url, online, latency)
                    } finally {
                        conn.disconnect()
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
