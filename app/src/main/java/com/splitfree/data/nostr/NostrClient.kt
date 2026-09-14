package com.splitfree.data.nostr

import com.splitfree.data.nostr.protocol.NostrFilter
import com.splitfree.data.nostr.protocol.RelayMessage
import com.splitfree.data.nostr.relay.Relay
import com.splitfree.di.ApplicationScope
import com.splitfree.domain.crypto.NostrEvent
import com.splitfree.domain.crypto.NostrKind
import com.splitfree.domain.model.sync.ConnectionStatus
import com.splitfree.domain.model.sync.FetchResult
import com.splitfree.domain.repository.NostrClientContract
import com.splitfree.util.DebugLog as Log
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
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
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.onSubscription
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Nostr relay pool: manages multiple WebSocket connections, subscriptions,
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

    /** Child job for relay collectors, cancelled on [disconnect] to stop stale coroutines. */
    private var sessionJob = SupervisorJob(appScope.coroutineContext[Job])
    private val scope get() = CoroutineScope(appScope.coroutineContext + sessionJob)
    private val subIdCounter = AtomicLong(0)

    // Bounded dedup set; evicts oldest entries beyond 10K to prevent memory leak.
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

    /** Latched once [connect] or [addRelay] has asked any relay to open; never reset while the process lives. */
    @Volatile
    private var connectRequested = false

    /** Reactive connection status; refreshed whenever any relay changes state. */
    private val _connectionStatus = MutableStateFlow(ConnectionStatus.Connecting)
    override val connectionStatus: StateFlow<ConnectionStatus> = _connectionStatus.asStateFlow()

    /** Boolean view of [connectionStatus]: true only while at least one relay is connected. */
    private val _connectionState = MutableStateFlow(false)
    override val connectionState: StateFlow<Boolean> = _connectionState.asStateFlow()

    private fun refreshConnectionState() {
        val states = relays.values.map { it.state.value }
        val status =
            when {
                states.any { it == Relay.State.CONNECTED } -> ConnectionStatus.Connected
                states.any { it == Relay.State.CONNECTING } -> ConnectionStatus.Connecting
                states.isEmpty() && !connectRequested -> ConnectionStatus.Connecting
                else -> ConnectionStatus.Offline
            }
        _connectionStatus.value = status
        _connectionState.value = status == ConnectionStatus.Connected
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
            connectRequested = true
            val safeUrls = relayUrls.filter { it.startsWith("wss://") }
            if (safeUrls.isEmpty() && relayUrls.isNotEmpty()) {
                Log.w(TAG, "All relay URLs rejected; only wss:// is allowed")
            }
            // Remove stale relays no longer in the new list
            val stale = relays.keys - safeUrls.toSet()
            stale.forEach { url -> relays.remove(url)?.disconnect() }
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
                                        // Duplicate from another relay, skip silently
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
            // Relays are now connecting (or none survived the filter); either way the status must say so.
            refreshConnectionState()
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
        // Filter 2: kind 1059 by #p tag; NIP-59 relays route gift wraps by recipient.
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
     * Internal: subscribe with filters on every connected relay, collect events until each of
     * them answers EOSE (or [timeoutMs] elapses), then cleanup.
     *
     * Collectors are caller-scoped, so the returned list is a snapshot no coroutine can still
     * append to. Subscriptions are always closed, including on cancellation.
     *
     * @return the collected events; `complete` is true only if every queried relay sent EOSE
     *   before the timeout and stayed connected meanwhile; false with no connected relay
     * @throws IOException if the collectors do not attach
     *   within [READY_TIMEOUT_MS]; the fetch is failed rather than returning partial history
     */
    private suspend fun fetchWithFilters(
        subId: String,
        filters: List<NostrFilter>,
        timeoutMs: Long = 15_000,
        dedup: (NostrEvent, MutableList<NostrEvent>) -> Boolean = { event, list ->
            list.none { it.id == event.id }
        }
    ): FetchResult {
        // ensureConnected returns once ANY relay is up. Querying right then would let a lone fallback
        // EOSE and certify history that only the still-handshaking primary holds, so handshakes get a
        // moment to finish (disconnected relays complete `first` at once). Relays still not connected
        // are left out because waiting on one that may never connect only burns the timeout; a REQ
        // itself would not be lost, OkHttp queues frames and Relay re-sends its subs on open.
        withTimeoutOrNull(SETTLE_TIMEOUT_MS) {
            relays.values.toList().forEach { relay -> relay.state.first { it != Relay.State.CONNECTING } }
        }
        // Snapshot the relay set once. connect()/disconnect() can change it concurrently, and a
        // count taken separately from the relays actually collected would leave collectorsReady
        // and allEose permanently unreachable.
        val live = relays.values.toList().filter { it.state.value == Relay.State.CONNECTED }
        if (live.isEmpty()) return FetchResult(emptyList(), complete = false)
        val events = mutableListOf<NostrEvent>()
        val eoseFrom = ConcurrentHashMap.newKeySet<String>()
        val allEose = CompletableDeferred<Unit>()
        val dropped = AtomicBoolean(false)
        val collectorsReady = CompletableDeferred<Unit>()
        val readyCount = AtomicInteger(0)
        try {
            // Caller-scoped: collectors cannot outlive this call, so the caller never receives a
            // list that a collector is still appending to.
            coroutineScope {
                val collectJob =
                    launch {
                        live.forEach { relay ->
                            launch {
                                // A relay that reconnects mid-fetch re-REQs from its last delivered event, so
                                // its EOSE no longer vouches for the older history it had not sent yet.
                                relay.state.first { it != Relay.State.CONNECTED }
                                dropped.set(true)
                            }
                            launch {
                                relay.messages
                                    .onSubscription {
                                        if (readyCount.incrementAndGet() >= live.size) {
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
                                                // Keyed by relay: one that repeats EOSE must not be able to
                                                // satisfy the barrier on behalf of relays still sending history.
                                                if (msg.subId == subId &&
                                                    eoseFrom.add(relay.url) &&
                                                    eoseFrom.size >= live.size
                                                ) {
                                                    allEose.complete(Unit)
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
                    live.forEach { it.subscribe(subId, filters) }
                    if (withTimeoutOrNull(timeoutMs) { allEose.await() } == null) {
                        val silent = live.map { it.url }.filterNot { it in eoseFrom }
                        Log.w(TAG, "Fetch $subId timed out after ${timeoutMs}ms without EOSE from $silent")
                    }
                } finally {
                    // NonCancellable so the join still happens when the caller is cancelled;
                    // returning before collectors stop is what allows concurrent mutation.
                    withContext(NonCancellable) { collectJob.cancelAndJoin() }
                }
            }
        } finally {
            live.forEach { it.closeSubscription(subId) }
        }
        return FetchResult(
            synchronized(events) { events.toList() },
            complete = allEose.isCompleted && !dropped.get()
        )
    }

    /**
     * Fetch events matching a group filter. Lets relays still handshaking settle briefly, then
     * subscribes temporarily on the connected ones, collects until each of them answers EOSE (or the
     * timeout elapses), then closes the subscription.
     *
     * @param groupId target group UUID
     * @param since unix timestamp; 0 to fetch all history
     * @param myPubkey if non-null, also fetches kind-1059 gift wraps addressed to this pubkey
     * @return deduplicated verified events plus whether the queried relays all finished sending
     *   history and stayed connected while doing so
     */
    override suspend fun fetchEvents(groupId: String, since: Long, myPubkey: String?): FetchResult {
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
        }.events
        return events.map { it.id }.toSet()
    }

    /**
     * Fetch kind-1059 gift wrap events addressed to a specific pubkey (last 24h).
     *
     * @param recipientPubHex 64-char hex public key of the recipient
     * @return list of gift-wrapped events; completeness is not reported
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
        }.events
    }

    override fun addRelay(url: String) {
        if (!url.startsWith("wss://")) {
            Log.w(TAG, "Rejecting non-wss:// relay URL: $url")
            return
        }
        if (relays.containsKey(url)) return
        connectRequested = true
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
        scope.launch { relay.state.collect { refreshConnectionState() } }
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

        /** How long a fetch waits for relays still handshaking before deciding which ones to query. */
        const val SETTLE_TIMEOUT_MS = 3_000L
    }
}
