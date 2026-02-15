package com.splitfree.data.nostr

import com.splitfree.util.DebugLog as Log
import com.splitfree.domain.crypto.NostrEvent
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Nostr relay pool client — from scratch using OkHttp WebSocket, no SDK.
 * Manages multiple relay connections, subscriptions, publishing, deduplication.
 *
 * Keeps the same public API surface as the old SDK-based NostrClient so callers
 * need minimal changes.
 */
@Singleton
class NostrClient
    @Inject
    constructor() {
        private val relays = ConcurrentHashMap<String, Relay>()
        private val connectionMutex = Mutex()
        private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        private val subIdCounter = AtomicLong(0)

        // Bounded dedup set — evicts oldest entries beyond 10K to prevent memory leak.
        // All access MUST go through seenLock.
        private val seenEventIds = LinkedHashSet<String>()
        private val seenLock = Any()

        /** Add an event ID to the dedup set. Returns true if it was new. */
        private fun addSeen(eventId: String): Boolean {
            synchronized(seenLock) {
                val added = seenEventIds.add(eventId)
                if (seenEventIds.size > 10_000) {
                    seenEventIds.iterator().let {
                        it.next()
                        it.remove()
                    }
                }
                return added
            }
        }

        private val _incomingEvents = MutableSharedFlow<NostrEvent>(extraBufferCapacity = 64)
        val incomingEvents: SharedFlow<NostrEvent> = _incomingEvents

        private val activeUsers = AtomicInteger(0)

        // groupId → subId mapping
        private val activeSubscriptions = ConcurrentHashMap<String, String>()

        /** Set this before connect() to enable NIP-42 AUTH on relays that require it. */
        var authSigner: ((challenge: String, relayUrl: String) -> NostrEvent)? = null

        @Volatile
        private var currentRelays: List<String> = emptyList()

        val isConnected: Boolean get() = relays.values.any { it.state.value == Relay.State.CONNECTED }

        /** Reactive connection state — emits whenever any relay connects/disconnects. */
        private val _connectionState = MutableStateFlow(false)
        val connectionState: StateFlow<Boolean> = _connectionState.asStateFlow()

        private fun refreshConnectionState() {
            _connectionState.value = relays.values.any { it.state.value == Relay.State.CONNECTED }
        }

        fun acquireConnection() {
            activeUsers.incrementAndGet()
        }

        fun releaseConnection() {
            if (activeUsers.decrementAndGet() <= 0) {
                activeUsers.set(0)
                disconnect()
            }
        }

        /**
         * Connect to relay URLs. No SDK keys needed — signing is handled by our Nip01 layer.
         * Only wss:// URLs are accepted to prevent unencrypted relay connections.
         */
        suspend fun connect(relayUrls: List<String>) {
            connectionMutex.withLock {
                val safeUrls = relayUrls.filter { it.startsWith("wss://") }
                if (safeUrls.isEmpty() && relayUrls.isNotEmpty()) {
                    Log.w(TAG, "All relay URLs rejected — only wss:// is allowed")
                }
                // Remove stale relays no longer in the new list
                val stale = relays.keys - safeUrls.toSet()
                stale.forEach { url -> relays.remove(url)?.disconnect() }
                if (stale.isNotEmpty()) refreshConnectionState()
                currentRelays = safeUrls
                safeUrls.forEach { url ->
                    if (!relays.containsKey(url)) {
                        val relay = Relay(url, scope, authSigner = authSigner)
                        relays[url] = relay
                        // Collect messages from this relay, verify signatures, and deduplicate
                        scope.launch {
                            relay.messages.collect { msg ->
                                when (msg) {
                                    is RelayMessage.EventMsg -> {
                                        // Validate event kind matches expected kind (relay filter bypass defense)
                                        if (msg.event.kind != 30078 && msg.event.kind != 1059) {
                                            Log.w(TAG, "Rejecting unexpected event kind ${msg.event.kind} from ${relay.url}")
                                        } else if (msg.event.verify() &&
                                            addSeen(msg.event.id)
                                        ) {
                                            _incomingEvents.emit(msg.event)
                                        }
                                    }

                                    is RelayMessage.EoseMsg -> { /* subscription EOSE — no action needed */ }

                                    is RelayMessage.ClosedMsg -> {
                                        Log.w(TAG, "Sub ${msg.subId} closed by $url: ${msg.message}")
                                    }

                                    is RelayMessage.NoticeMsg -> {
                                        Log.i(TAG, "Notice from $url: ${msg.message}")
                                    }

                                    is RelayMessage.AuthMsg -> { /* handled in Relay.handleAuth() */ }

                                    else -> {}
                                }
                            }
                        }
                        relay.connect()
                        // Track relay state changes for live connection indicator
                        scope.launch { relay.state.collect { refreshConnectionState() } }
                    }
                }
                Log.i(TAG, "Connected to ${safeUrls.size} relays")
            }
        }

        suspend fun subscribe(
            groupId: String,
            since: Long,
        ) {
            val subId = "${subIdCounter.incrementAndGet()}:$groupId"
            activeSubscriptions[groupId] = subId
            val filter =
                NostrFilter(
                    kinds = listOf(30078, 1059),
                    tags = mapOf("#g" to listOf(groupId)),
                    since = if (since > 0) since else null,
                )
            relays.values.forEach { it.subscribe(subId, listOf(filter)) }
        }

        suspend fun unsubscribe(groupId: String) {
            val subId = activeSubscriptions.remove(groupId) ?: return
            relays.values.forEach { it.closeSubscription(subId) }
        }

        suspend fun unsubscribeAll() {
            activeSubscriptions.forEach { (_, subId) ->
                relays.values.forEach { it.closeSubscription(subId) }
            }
            activeSubscriptions.clear()
        }

        /** Start listening is now a no-op — messages flow automatically via SharedFlow. */
        fun startListening() { /* messages already flowing via relay.messages collectors */ }

        suspend fun publish(event: NostrEvent): Boolean {
            if (relays.isEmpty()) {
                Log.w(TAG, "publish: no relays connected, event ${event.id.take(8)} will be lost")
                return false
            }
            var anySuccess = false
            relays.values
                .map { relay ->
                    scope.async {
                        try {
                            relay.sendEvent(event)
                        } catch (e: Exception) {
                            Log.w(TAG, "publish to ${relay.url} failed: ${e.message}")
                            false
                        }
                    }
                }.forEach { if (it.await()) anySuccess = true }
            Log.i(TAG, "publish event ${event.id.take(8)}: ${if (anySuccess) "OK" else "FAILED"} (${relays.size} relays)")
            return anySuccess
        }

        suspend fun publishJson(eventJson: String): Boolean {
            val event = NostrEvent.fromJson(eventJson) ?: return false
            return publish(event)
        }

        /**
         * Fetch events matching a group filter. Subscribes temporarily, collects until EOSE,
         * then closes the subscription.
         */
        suspend fun fetchEvents(
            groupId: String,
            since: Long,
        ): List<NostrEvent> {
            val subId = "${subIdCounter.incrementAndGet()}:fetch:$groupId"
            // Query both new #g tag and old #d tag format for backward compatibility
            val filterNew =
                NostrFilter(
                    kinds = listOf(30078, 1059),
                    tags = mapOf("#g" to listOf(groupId)),
                    since = if (since > 0) since else null,
                )
            val filterOld =
                NostrFilter(
                    kinds = listOf(30078, 1059),
                    tags = mapOf("#d" to listOf(groupId)),
                    since = if (since > 0) since else null,
                )

            val events = mutableListOf<NostrEvent>()
            val relayCount = relays.size.coerceAtLeast(1)
            val eoseCount =
                java.util.concurrent.atomic
                    .AtomicInteger(0)
            val allEose = CompletableDeferred<Unit>()

            // Collect events from all relays for this subscription
            val collectJob =
                scope.launch {
                    relays.values.forEach { relay ->
                        launch {
                            relay.messages.collect { msg ->
                                when (msg) {
                                    is RelayMessage.EventMsg -> {
                                        if (msg.subId == subId && msg.event.verify() &&
                                            addSeen(msg.event.id)
                                        ) {
                                            synchronized(events) { events.add(msg.event) }
                                        }
                                    }

                                    is RelayMessage.EoseMsg -> {
                                        if (msg.subId == subId && eoseCount.incrementAndGet() >= relayCount) {
                                            allEose.complete(Unit)
                                        }
                                    }

                                    else -> {}
                                }
                            }
                        }
                    }
                }

            // Subscribe on all relays with both filters (OR'd per NIP-01)
            relays.values.forEach { it.subscribe(subId, listOf(filterNew, filterOld)) }

            // Wait for EOSE or timeout
            try {
                withTimeout(15_000) { allEose.await() }
            } catch (_: Exception) {
                // timeout — return what we have
            }

            // Cleanup
            relays.values.forEach { it.closeSubscription(subId) }
            collectJob.cancel()

            return events
        }

        fun addRelay(url: String) {
            if (!url.startsWith("wss://")) {
                Log.w(TAG, "Rejecting non-wss:// relay URL: $url")
                return
            }
            if (relays.containsKey(url)) return
            val relay = Relay(url, scope, authSigner = authSigner)
            relays[url] = relay
            scope.launch {
                relay.messages.collect { msg ->
                    if (msg is RelayMessage.EventMsg &&
                        (msg.event.kind == 30078 || msg.event.kind == 1059) &&
                        msg.event.verify() &&
                        addSeen(msg.event.id)
                    ) {
                        _incomingEvents.emit(msg.event)
                    }
                }
            }
            relay.connect()
        }

        fun disconnect() {
            activeSubscriptions.clear()
            relays.values.forEach { it.disconnect() }
            relays.clear()
            synchronized(seenLock) { seenEventIds.clear() }
            refreshConnectionState()
        }

        companion object {
            private const val TAG = "NostrClient"
        }
    }
