package com.splitfree.data.nostr

import android.util.Log
import rust.nostr.sdk.Client
import rust.nostr.sdk.Event
import rust.nostr.sdk.Filter
import rust.nostr.sdk.HandleNotification
import rust.nostr.sdk.Kind
import rust.nostr.sdk.Keys
import rust.nostr.sdk.NostrSigner
import rust.nostr.sdk.RelayMessage
import rust.nostr.sdk.RelayUrl
import rust.nostr.sdk.SubscriptionId
import rust.nostr.sdk.Timestamp
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.Duration
import java.util.concurrent.atomic.AtomicInteger
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class NostrClient @Inject constructor() {

    @Volatile
    private var client: Client? = null
    private val connectionMutex = Mutex()
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private val _incomingEvents = MutableSharedFlow<Event>(extraBufferCapacity = 64)
    val incomingEvents: SharedFlow<Event> = _incomingEvents

    /** Track how many components are actively using this connection. */
    private val activeUsers = AtomicInteger(0)

    /** Track active subscription IDs per group for cleanup. */
    private val activeSubscriptions = java.util.concurrent.ConcurrentHashMap<String, String>()

    val isConnected: Boolean get() = client != null

    @Volatile
    private var currentKeys: Keys? = null
    @Volatile
    private var currentRelays: List<String> = emptyList()
    private val reconnectAttempts = AtomicInteger(0)
    private var reconnectJob: Job? = null

    fun acquireConnection() { activeUsers.incrementAndGet() }
    fun releaseConnection() {
        if (activeUsers.decrementAndGet() <= 0) {
            activeUsers.set(0)
            disconnect()
        }
    }

    suspend fun connect(keys: Keys, relayUrls: List<String>) {
        connectionMutex.withLock {
            if (client != null) return
            currentKeys = keys
            currentRelays = relayUrls
            try {
                val signer = NostrSigner.keys(keys)
                val c = Client(signer = signer)
                relayUrls.forEach { url ->
                    try { c.addRelay(RelayUrl.parse(url)) } catch (e: Exception) {
                        Log.w(TAG, "Failed to add relay $url: ${e.message}")
                    }
                }
                c.connect()
                client = c
                reconnectAttempts.set(0)
                Log.i(TAG, "Connected to ${relayUrls.size} relays")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to connect: ${e.message}")
                scheduleReconnect()
            }
        }
    }

    private fun scheduleReconnect() {
        reconnectJob?.cancel()
        val keys = currentKeys ?: return
        val relays = currentRelays.ifEmpty { return }
        val attempt = reconnectAttempts.getAndIncrement()
        val delayMs = minOf(1000L * (1L shl minOf(attempt, 6)), 60_000L) // 1s..60s

        reconnectJob = scope.launch {
            Log.i(TAG, "Reconnecting in ${delayMs}ms (attempt ${attempt + 1})")
            delay(delayMs)
            client = null
            connect(keys, relays)
        }
    }

    suspend fun subscribe(groupId: String, since: Long) {
        val c = client ?: return
        try {
            val filter = Filter()
                .kind(Kind(30078u))
                .customTag(
                    rust.nostr.sdk.SingleLetterTag.lowercase(rust.nostr.sdk.Alphabet.D),
                    listOf(groupId)
                )
                .since(Timestamp.fromSecs(since.toULong()))
            val output = c.subscribe(filter, null)
            activeSubscriptions[groupId] = output.`val`.toString()
        } catch (e: Exception) {
            Log.e(TAG, "Subscribe failed for group $groupId: ${e.message}")
        }
    }

    suspend fun unsubscribe(groupId: String) {
        val c = client ?: return
        val subId = activeSubscriptions.remove(groupId) ?: return
        try {
            c.unsubscribe(SubscriptionId(subId))
        } catch (e: Exception) {
            Log.w(TAG, "Unsubscribe failed for group $groupId: ${e.message}")
        }
    }

    suspend fun unsubscribeAll() {
        val c = client ?: return
        for ((_, subId) in activeSubscriptions.toMap()) {
            try {
                c.unsubscribe(SubscriptionId(subId))
            } catch (_: Exception) {}
        }
        activeSubscriptions.clear()
    }

    fun startListening() {
        val c = client ?: return
        scope.launch {
            try {
                c.handleNotifications(object : HandleNotification {
                    override fun handleMsg(relayUrl: String, msg: RelayMessage) {}
                    override fun handle(relayUrl: String, subscriptionId: String, event: Event) {
                        _incomingEvents.tryEmit(event)
                    }
                })
            } catch (e: Exception) {
                Log.e(TAG, "Notification handler error: ${e.message}")
                scheduleReconnect()
            }
        }
    }

    suspend fun publish(event: Event): Boolean {
        return try {
            client?.sendEvent(event)
            true
        } catch (e: Exception) {
            Log.e(TAG, "Publish failed: ${e.message}")
            false
        }
    }

    suspend fun publishJson(eventJson: String): Boolean {
        return try {
            publish(Event.fromJson(eventJson))
        } catch (e: Exception) {
            Log.e(TAG, "Publish JSON failed: ${e.message}")
            false
        }
    }

    suspend fun fetchEvents(groupId: String, since: Long): List<Event> {
        val c = client ?: return emptyList()
        return try {
            val filter = Filter()
                .kind(Kind(30078u))
                .customTag(
                    rust.nostr.sdk.SingleLetterTag.lowercase(rust.nostr.sdk.Alphabet.D),
                    listOf(groupId)
                )
            val sinceFilter = if (since > 0) filter.since(Timestamp.fromSecs(since.toULong())) else filter
            c.fetchEvents(filter = sinceFilter, timeout = Duration.ofSeconds(15)).toVec()
        } catch (e: Exception) {
            Log.e(TAG, "Fetch failed for group $groupId: ${e.message}")
            emptyList()
        }
    }

    fun addRelay(url: String) {
        scope.launch {
            try { client?.addRelay(RelayUrl.parse(url)) } catch (e: Exception) {
                Log.w(TAG, "Failed to add relay $url: ${e.message}")
            }
        }
    }

    fun disconnect() {
        reconnectJob?.cancel()
        activeSubscriptions.clear()
        scope.launch {
            connectionMutex.withLock {
                try { client?.disconnect() } catch (_: Exception) {}
                client = null
            }
        }
    }

    companion object {
        private const val TAG = "NostrClient"
    }
}
