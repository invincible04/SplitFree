package com.splitfree.data.nostr

import com.splitfree.data.nostr.protocol.NostrFilter
import com.splitfree.data.nostr.protocol.RelayMessage
import com.splitfree.data.nostr.relay.Relay
import com.splitfree.di.ApplicationScope
import com.splitfree.domain.crypto.NostrEvent
import com.splitfree.domain.crypto.NostrKind
import com.splitfree.domain.repository.NostrClientContract
import com.splitfree.util.DebugLog as Log
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.onSubscription
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Nostr relay pool — manages multiple WebSocket connections, subscriptions,
 * event publishing, signature verification, and cross-relay deduplication.
 *
 * Only `wss://` URLs are accepted to prevent unencrypted relay connections.
 */
@Singleton
class NostrClient
@Inject
constructor(@ApplicationScope private val appScope: CoroutineScope) : NostrClientContract {
    private val relays = ConcurrentHashMap<String, Relay>()
    private val connectionMutex = Mutex()

    /** Child job for relay collectors — cancelled on [disconnect] to stop stale coroutines. */
    private var sessionJob = SupervisorJob(appScope.coroutineContext[Job])
    private val scope get() = CoroutineScope(appScope.coroutineContext + sessionJob)
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
    override val incomingEvents: SharedFlow<NostrEvent> = _incomingEvents

    private val activeUsers = AtomicInteger(0)

    // groupId → subId mapping
    private val activeSubscriptions = ConcurrentHashMap<String, String>()

    /** Set this before connect() to enable NIP-42 AUTH on relays that require it. */
    override var authSigner: ((challenge: String, relayUrl: String) -> NostrEvent)? = null

    @Volatile
    private var currentRelays: List<String> = emptyList()

    override val isConnected: Boolean get() = relays.values.any { it.state.value == Relay.State.CONNECTED }

    override fun currentRelayUrls(): List<String> = currentRelays.toList()

    /** Reactive connection state — emits whenever any relay connects/disconnects. */
    private val _connectionState = MutableStateFlow(false)
    override val connectionState: StateFlow<Boolean> = _connectionState.asStateFlow()

    private fun refreshConnectionState() {
        _connectionState.value = relays.values.any { it.state.value == Relay.State.CONNECTED }
    }

    override fun acquireConnection() {
        activeUsers.incrementAndGet()
    }

    override fun releaseConnection() {
        if (activeUsers.decrementAndGet() <= 0) {
            activeUsers.set(0)
            disconnect()
        }
    }

    /**
     * Connect to the given relay URLs, disconnecting any stale relays not in the new list.
     *
     * @param relayUrls list of `wss://` relay URLs; non-wss URLs are silently rejected
     */
    override suspend fun connect(relayUrls: List<String>) {
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
                val existing = relays[url]
                if (existing != null) {
                    // Reset reconnect counter so paused relays get another chance
                    existing.resetReconnect()
                    if (existing.state.value == Relay.State.DISCONNECTED) existing.connect()
                } else {
                    val relay = Relay(url, scope, authSigner = authSigner)
                    relays[url] = relay
                    // Collect messages from this relay, verify signatures, and deduplicate
                    scope.launch {
                        relay.messages.collect { msg ->
                            when (msg) {
                                is RelayMessage.EventMsg -> {
                                    // Validate event kind matches expected kind (relay filter bypass defense)
                                    if (msg.event.kind != NostrKind.APP_SPECIFIC &&
                                        msg.event.kind != NostrKind.GIFT_WRAP
                                    ) {
                                        Log.w(
                                            TAG,
                                            "Rejecting unexpected event kind ${msg.event.kind} from ${relay.url}"
                                        )
                                    } else if (msg.event.verify() &&
                                        addSeen(msg.event.id)
                                    ) {
                                        _incomingEvents.emit(msg.event)
                                    } else {
                                        // Duplicate from another relay — skip silently
                                    }
                                }

                                is RelayMessage.EoseMsg -> {
                                    Log.d(TAG, "EOSE for sub ${msg.subId} from ${relay.url}")
                                }

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

    override suspend fun subscribe(groupId: String, since: Long, myPubkey: String?) {
        val oldSubId = activeSubscriptions[groupId]
        if (oldSubId != null) {
            relays.values.forEach { it.closeSubscription(oldSubId) }
        }
        val subId = "${subIdCounter.incrementAndGet()}:$groupId"
        activeSubscriptions[groupId] = subId
        val sinceVal = if (since > 0) since else null
        // Filter 1: kind 30078 (direct) + kind 1059 (gift wrap) by #g tag
        val filterByGroup =
            NostrFilter(
                kinds = listOf(NostrKind.APP_SPECIFIC, NostrKind.GIFT_WRAP),
                tags = mapOf("#g" to listOf(groupId)),
                since = sinceVal
            )
        val filters = mutableListOf(filterByGroup)
        // Filter 2: kind 1059 by #p tag — NIP-59 relays route gift wraps by recipient.
        // NIP-59 randomizes timestamps up to 48h in the past, so widen the window.
        if (myPubkey != null) {
            val giftWrapSince = sinceVal?.let { maxOf(it - 2 * 86400, 0) }
            filters.add(
                NostrFilter(
                    kinds = listOf(NostrKind.GIFT_WRAP),
                    tags = mapOf("#p" to listOf(myPubkey)),
                    since = giftWrapSince
                )
            )
        }
        relays.values.forEach { it.subscribe(subId, filters) }
        Log.d(TAG, "subscribe($subId): ${filters.size} filters, since=$sinceVal, relays=${relays.size}")
    }

    override suspend fun unsubscribe(groupId: String) {
        val subId = activeSubscriptions.remove(groupId) ?: return
        relays.values.forEach { it.closeSubscription(subId) }
    }

    override suspend fun unsubscribeAll() {
        activeSubscriptions.forEach { (_, subId) ->
            relays.values.forEach { it.closeSubscription(subId) }
        }
        activeSubscriptions.clear()
    }

    /** Start listening is now a no-op — messages flow automatically via SharedFlow. */
    override fun startListening() { /* messages already flowing via relay.messages collectors */ }

    override suspend fun publish(event: NostrEvent): Boolean {
        // Snapshot once: disconnect() clears the map concurrently, so an isEmpty() check followed
        // by a second read of relays.values could iterate a set that no longer matches the check.
        val targets = relays.values.toList()
        if (targets.isEmpty()) {
            Log.w(TAG, "publish: no relays connected, event ${event.id.take(8)} will be lost")
            return false
        }
        var anySuccess = false
        targets
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
        Log.i(
            TAG,
            "publish event ${event.id.take(8)}: ${if (anySuccess) "OK" else "FAILED"} (${targets.size} relays)"
        )
        return anySuccess
    }

    override suspend fun publishJson(eventJson: String): Boolean {
        val event = NostrEvent.fromJson(eventJson) ?: return false
        return publish(event)
    }

    /**
     * Internal: subscribe with filters, collect events until EOSE from all relays, then cleanup.
     *
     * Collectors are caller-scoped, so the returned list is a snapshot no coroutine can still
     * append to. Subscriptions are always closed, including on cancellation.
     *
     * @throws IOException if the collectors do not attach
     *   within [READY_TIMEOUT_MS] — the fetch is failed rather than returning partial history
     */
    private suspend fun fetchWithFilters(
        subId: String,
        filters: List<NostrFilter>,
        timeoutMs: Long = 15_000,
        dedup: (NostrEvent, MutableList<NostrEvent>) -> Boolean = { event, list ->
            list.none { it.id == event.id }
        }
    ): List<NostrEvent> {
        // Snapshot the relay set once. connect()/disconnect() can change it concurrently, and a
        // count taken separately from the relays actually collected would leave collectorsReady
        // and allEose permanently unreachable.
        val targets = relays.values.toList()
        if (targets.isEmpty()) return emptyList()
        val events = mutableListOf<NostrEvent>()
        val eoseCount =
            java.util.concurrent.atomic
                .AtomicInteger(0)
        val allEose = CompletableDeferred<Unit>()
        val collectorsReady = CompletableDeferred<Unit>()
        val readyCount =
            java.util.concurrent.atomic
                .AtomicInteger(0)
        try {
            // Caller-scoped: collectors cannot outlive this call, so the caller never receives a
            // list that a collector is still appending to.
            coroutineScope {
                val collectJob =
                    launch {
                        targets.forEach { relay ->
                            launch {
                                // Per-collector: a relay that repeats EOSE must not be able to
                                // satisfy the barrier on behalf of relays still sending history.
                                var eoseSeen = false
                                relay.messages
                                    .onSubscription {
                                        if (readyCount.incrementAndGet() >= targets.size) {
                                            collectorsReady.complete(Unit)
                                        }
                                    }.collect { msg ->
                                        when (msg) {
                                            is RelayMessage.EventMsg -> {
                                                if (msg.subId == subId && msg.event.verify()) {
                                                    synchronized(events) {
                                                        if (dedup(msg.event, events)) events.add(msg.event)
                                                    }
                                                }
                                            }

                                            is RelayMessage.EoseMsg -> {
                                                if (msg.subId == subId && !eoseSeen) {
                                                    eoseSeen = true
                                                    if (eoseCount.incrementAndGet() >= targets.size) {
                                                        allEose.complete(Unit)
                                                    }
                                                }
                                            }

                                            else -> {}
                                        }
                                    }
                            }
                        }
                    }
                try {
                    // Subscribing before the collectors attach would silently drop the history the
                    // relay sends back, so a readiness miss fails the fetch instead of returning a
                    // partial result the caller would trust. Cleanup still runs in the finally blocks.
                    withTimeoutOrNull(READY_TIMEOUT_MS) { collectorsReady.await() }
                        ?: throw IOException("Relay collectors did not become ready")
                    targets.forEach { it.subscribe(subId, filters) }
                    // Timeout waiting for EOSE — proceed with whatever events we collected
                    withTimeoutOrNull(timeoutMs) { allEose.await() }
                } finally {
                    // NonCancellable so the join still happens when the caller is cancelled;
                    // returning before collectors stop is what allows concurrent mutation.
                    withContext(NonCancellable) { collectJob.cancelAndJoin() }
                }
            }
        } finally {
            targets.forEach { it.closeSubscription(subId) }
        }
        return synchronized(events) { events.toList() }
    }

    /**
     * Fetch events matching a group filter. Subscribes temporarily, collects until
     * EOSE from all relays (or timeout), then closes the subscription.
     *
     * @param groupId target group UUID
     * @param since unix timestamp; 0 to fetch all history
     * @param myPubkey if non-null, also fetches kind-1059 gift wraps addressed to this pubkey
     * @return deduplicated list of verified events
     */
    override suspend fun fetchEvents(groupId: String, since: Long, myPubkey: String?): List<NostrEvent> {
        val subId = "${subIdCounter.incrementAndGet()}:fetch:$groupId"
        val sinceVal = if (since > 0) since else null
        val filters =
            mutableListOf(
                NostrFilter(
                    kinds = listOf(NostrKind.APP_SPECIFIC, NostrKind.GIFT_WRAP),
                    tags = mapOf("#g" to listOf(groupId)),
                    since = sinceVal
                )
            )
        if (myPubkey != null) {
            val giftWrapSince = sinceVal?.let { maxOf(it - 2 * 86400, 0) }
            filters.add(
                NostrFilter(
                    kinds = listOf(NostrKind.GIFT_WRAP),
                    tags = mapOf("#p" to listOf(myPubkey)),
                    since = giftWrapSince
                )
            )
        }
        return fetchWithFilters(subId, filters)
    }

    override suspend fun fetchEventIds(groupId: String, since: Long, myPubkey: String?): Set<String> {
        val subId = "${subIdCounter.incrementAndGet()}:heal:$groupId"
        val sinceVal = if (since > 0) since else null
        val filters = mutableListOf(
            NostrFilter(
                kinds = listOf(NostrKind.APP_SPECIFIC),
                tags = mapOf("#g" to listOf(groupId)),
                since = sinceVal
            )
        )
        val events = fetchWithFilters(subId, filters) { event, list ->
            list.none { it.id == event.id }
        }
        return events.map { it.id }.toSet()
    }

    /**
     * Fetch kind-1059 gift wrap events addressed to a specific pubkey (last 24h).
     *
     * @param recipientPubHex 64-char hex public key of the recipient
     * @return list of gift-wrapped events
     */
    override suspend fun fetchGiftWraps(recipientPubHex: String): List<NostrEvent> {
        val subId = "${subIdCounter.incrementAndGet()}:fetch:gw:${recipientPubHex.take(8)}"
        val filters = listOf(
            NostrFilter(
                kinds = listOf(NostrKind.GIFT_WRAP),
                tags = mapOf("#p" to listOf(recipientPubHex)),
                since = System.currentTimeMillis() / 1000 - 86400
            )
        )
        return fetchWithFilters(subId, filters, timeoutMs = 10_000) { event, list ->
            list.none { it.id == event.id }
        }
    }

    override fun addRelay(url: String) {
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
                    (msg.event.kind == NostrKind.APP_SPECIFIC || msg.event.kind == NostrKind.GIFT_WRAP) &&
                    msg.event.verify() &&
                    addSeen(msg.event.id)
                ) {
                    _incomingEvents.emit(msg.event)
                }
            }
        }
        relay.connect()
    }

    override fun disconnect() {
        sessionJob.cancel()
        sessionJob = SupervisorJob(appScope.coroutineContext[Job])
        activeSubscriptions.clear()
        relays.values.forEach { it.disconnect() }
        relays.clear()
        synchronized(seenLock) { seenEventIds.clear() }
        refreshConnectionState()
    }

    companion object {
        private const val TAG = "NostrClient"
        private const val READY_TIMEOUT_MS = 5_000L
    }
}
