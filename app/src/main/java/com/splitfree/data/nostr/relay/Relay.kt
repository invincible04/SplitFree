package com.splitfree.data.nostr.relay

import com.splitfree.data.nostr.protocol.ClientMessage
import com.splitfree.data.nostr.protocol.NostrFilter
import com.splitfree.data.nostr.protocol.RelayMessage
import com.splitfree.domain.crypto.NostrEvent
import com.splitfree.util.DebugLog as Log
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener

/**
 * Single relay WebSocket connection with automatic reconnect (exponential backoff),
 * NIP-42 AUTH handling, and OK-response tracking for published events.
 *
 * @param url `wss://` relay URL
 * @param scope coroutine scope for reconnect jobs and message collection
 * @param okHttpClient shared OkHttp client with 30s ping interval
 * @param authSigner optional NIP-42 auth callback; if null, AUTH challenges are ignored
 */
class Relay(
    val url: String,
    private val scope: CoroutineScope,
    private val okHttpClient: OkHttpClient = sharedClient,
    private val authSigner: ((challenge: String, relayUrl: String) -> NostrEvent)? = null,
    private val onConnected: (() -> Unit)? = null
) {
    enum class State { DISCONNECTED, CONNECTING, CONNECTED }

    @Volatile
    private var ws: WebSocket? = null
    private val _state = MutableStateFlow(State.DISCONNECTED)
    val state: StateFlow<State> = _state.asStateFlow()

    /** Not conflated like state: a rapid drop/reopen cannot certify a narrowed historical REQ. */
    val connectionEpoch = AtomicLong(0)

    private val _messages = MutableSharedFlow<RelayMessage>(extraBufferCapacity = MESSAGE_BUFFER_CAPACITY)
    val messages: SharedFlow<RelayMessage> = _messages.asSharedFlow()

    /**
     * Messages that could not be handed to [messages] because the buffer was full: the
     * downstream collector (Schnorr verify + DB) fell behind the socket. Diagnostics only.
     */
    val droppedMessages = AtomicLong(0)

    // OK callbacks: eventId → deferred result
    private val okCallbacks = ConcurrentHashMap<String, CompletableDeferred<RelayMessage.OkMsg>>()

    // Active subscriptions for re-send on reconnect
    private val activeSubs = ConcurrentHashMap<String, List<NostrFilter>>()

    // Track last-seen event timestamp per subscription for reconnect gap prevention
    private val lastEventTimestamp = ConcurrentHashMap<String, Long>()

    // Oldest created_at of an EVENT we dropped, per subscription: the window a re-REQ must cover
    private val oldestDroppedTimestamp = ConcurrentHashMap<String, Long>()

    // Reconnect
    @Volatile
    private var reconnectAttempt = 0
    private var reconnectJob: Job? = null

    /**
     * Distinguishes local disconnects from remote closes. Code 1000 can also mean a relay
     * restart or idle timeout, so it must not suppress reconnects by itself.
     */
    @Volatile
    private var closedIntentionally = false

    /**
     * Invalidates old connection-state callbacks before creating or disconnecting a socket.
     * Compare attempts, not [ws]: OkHttp can invoke a callback before `newWebSocket` returns
     * and assigns the new socket.
     */
    @Volatile
    private var generation = 0L

    // At most one pending catch-up re-REQ after a drop; guarded by [resubscribeLock]
    private var resubscribeJob: Job? = null
    private val resubscribeLock = Any()

    fun connect() {
        if (_state.value != State.DISCONNECTED) return
        _state.value = State.CONNECTING
        closedIntentionally = false
        val mine = ++generation

        val request = Request.Builder().url(url).build()
        ws =
            okHttpClient.newWebSocket(
                request,
                object : WebSocketListener() {
                    /** Callbacks from a socket [disconnect] or a later [connect] replaced must not touch the live state. */
                    private fun stale(): Boolean = generation != mine

                    override fun onOpen(webSocket: WebSocket, response: Response) {
                        if (stale()) return
                        connectionEpoch.incrementAndGet()
                        _state.value = State.CONNECTED
                        onConnected?.invoke()
                        reconnectAttempt = 0
                        authAttempts = 0
                        // Re-send active subscriptions with updated since to cover reconnect gap
                        activeSubs.forEach { (subId, filters) ->
                            val updated = catchUpFilters(subId, filters)
                            oldestDroppedTimestamp.remove(subId) // covered by this REQ
                            webSocket.send(ClientMessage.Req(subId, updated).toJson())
                        }
                        Log.d(TAG, "Connected to $url")
                    }

                    override fun onMessage(webSocket: WebSocket, text: String) {
                        if (!stale()) handleIncoming(text)
                    }

                    override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                        webSocket.close(1000, null)
                    }

                    override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                        if (stale()) return
                        _state.value = State.DISCONNECTED
                        if (closedIntentionally) return
                        // The server closed on us (restart, idle timeout, policy), whatever code it chose.
                        Log.i(
                            TAG,
                            "Relay $url closed the connection ($code${if (reason.isEmpty()) "" else ": $reason"})"
                        )
                        scheduleReconnect()
                    }

                    override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                        if (stale()) return
                        Log.w(TAG, "Connection failed to $url: ${t.message}")
                        _state.value = State.DISCONNECTED
                        if (!closedIntentionally) scheduleReconnect()
                    }
                }
            )
    }

    fun send(text: String): Boolean = ws?.send(text) ?: false

    /**
     * Runs on the OkHttp reader thread. Forwarded frames use non-suspending emission so a slow
     * collector cannot stall socket reads; AUTH signing is handled synchronously.
     */
    internal fun handleIncoming(text: String) {
        if (text.length > MAX_FRAME_CHARS) {
            droppedMessages.incrementAndGet()
            return
        }
        try {
            val msg = RelayMessage.parse(text) ?: run {
                // A malformed EVENT followed by EOSE is not a complete historical response.
                droppedMessages.incrementAndGet()
                return
            }
            when (msg) {
                is RelayMessage.OkMsg -> {
                    okCallbacks.remove(msg.eventId)?.complete(msg)
                }

                is RelayMessage.AuthMsg -> {
                    handleAuth(msg.challenge)
                }

                else -> emitOrDrop(msg)
            }
        } catch (e: Exception) {
            Log.w(TAG, "onMessage error from $url: ${e.message}")
        }
    }

    /**
     * Hand [msg] to [messages]. [MutableSharedFlow.tryEmit] fails when the collector is slower
     * than the socket (typically a historical backfill); we count the drop, log at most once per
     * [DROP_LOG_INTERVAL] drops, and for EVENTs remember the gap so it can be re-requested.
     */
    private fun emitOrDrop(msg: RelayMessage) {
        if (_messages.tryEmit(msg)) {
            // Advance on accepted emission, not durable processing; tryEmit also succeeds without collectors.
            if (msg is RelayMessage.EventMsg) {
                lastEventTimestamp.merge(msg.subId, msg.event.createdAt) { old, new -> maxOf(old, new) }
            }
            return
        }
        val dropped = droppedMessages.incrementAndGet()
        if (dropped % DROP_LOG_INTERVAL == 1L) {
            Log.w(TAG, "Message buffer full for $url: $dropped dropped so far (last: ${msg::class.simpleName})")
        }
        if (msg is RelayMessage.EventMsg) {
            oldestDroppedTimestamp.merge(msg.subId, msg.event.createdAt) { old, new -> minOf(old, new) }
            scheduleResubscribe()
        }
    }

    /**
     * Delays re-REQs because [MutableSharedFlow] exposes no buffer occupancy. One pending job
     * batches dropped subscriptions; further drops can request another pass. Recovery depends
     * on relay retention and successful delivery, so this is not a completeness guarantee.
     * Non-suspending emission keeps a slow collector from blocking the OkHttp reader.
     */
    private fun scheduleResubscribe() {
        synchronized(resubscribeLock) {
            if (resubscribeJob?.isActive == true) return
            resubscribeJob =
                scope.launch {
                    delay(RESUBSCRIBE_DELAY_MS)
                    val leftover = resubscribeDroppedSubs()
                    synchronized(resubscribeLock) { resubscribeJob = null }
                    // A drop that raced the re-REQ above saw this job still active and did not
                    // schedule another; pick it up now instead of waiting for the next drop.
                    if (leftover) scheduleResubscribe()
                }
        }
    }

    /** @return true if drop records remain that this pass did not cover and a further pass is needed */
    private fun resubscribeDroppedSubs(): Boolean {
        // Not connected: onOpen will re-REQ every active sub with catch-up filters anyway.
        if (_state.value != State.CONNECTED) return false
        val subIds = oldestDroppedTimestamp.keys.toList()
        for (subId in subIds) {
            val filters = activeSubs[subId]
            if (filters == null) {
                oldestDroppedTimestamp.remove(subId) // subscription was closed meanwhile
                continue
            }
            val updated = catchUpFilters(subId, filters)
            // Clear before sending so a drop that races this call is recorded for the next round.
            oldestDroppedTimestamp.remove(subId)
            Log.i(TAG, "Re-requesting $subId on $url after dropped events (since=${updated.firstOrNull()?.since})")
            send(ClientMessage.Req(subId, updated).toJson())
        }
        return oldestDroppedTimestamp.isNotEmpty()
    }

    /**
     * Filters for re-requesting [subId], narrowed to the window we may have missed: everything
     * since the older of the last delivered event and the oldest dropped event, minus 60s slack.
     */
    private fun catchUpFilters(subId: String, filters: List<NostrFilter>): List<NostrFilter> {
        val since =
            listOfNotNull(lastEventTimestamp[subId], oldestDroppedTimestamp[subId]).minOrNull() ?: return filters
        return filters.map { f ->
            // A live arrival stream (limit=0) and a bounded historical partition must keep their exact scope.
            if (f.limit != null || f.until != null) f else f.copy(since = since - 60)
        }
    }

    /**
     * Publish an event and wait for the relay's OK response.
     *
     * Concurrent publishes of the same event to this relay share one in-flight request: only the
     * first caller sends the EVENT frame, later callers await the same OK.
     *
     * @param event signed Nostr event to publish
     * @param timeoutMs max time to wait for OK response
     * @return true if the relay accepted the event
     * @throws CancellationException if the calling coroutine is cancelled while waiting
     */
    suspend fun sendEvent(event: NostrEvent, timeoutMs: Long = 7000): Boolean {
        val fresh = CompletableDeferred<RelayMessage.OkMsg>()
        val existing = okCallbacks.putIfAbsent(event.id, fresh)
        val deferred = existing ?: fresh
        if (existing == null && !send(ClientMessage.Event(event).toJson())) {
            okCallbacks.remove(event.id, fresh)
            fresh.cancel() // release anyone who attached to this attempt in the meantime
            Log.w(TAG, "sendEvent ${event.id.take(8)} to $url: send failed (not connected?)")
            return false
        }
        return try {
            val ok = withTimeout(timeoutMs) { deferred.await() }
            if (!ok.accepted) Log.w(TAG, "sendEvent ${event.id.take(8)} to $url: rejected: ${ok.message}")
            ok.accepted
        } catch (_: TimeoutCancellationException) {
            okCallbacks.remove(event.id, deferred)
            Log.w(TAG, "sendEvent ${event.id.take(8)} to $url: timeout after ${timeoutMs}ms")
            false
        } catch (e: CancellationException) {
            okCallbacks.remove(event.id, deferred)
            // Our own coroutine was cancelled: propagate. Otherwise the deferred itself was
            // cancelled (disconnect / failed send) and this is just a failed publish.
            if (!currentCoroutineContext().isActive) throw e
            Log.w(TAG, "sendEvent ${event.id.take(8)} to $url: cancelled before OK (disconnected?)")
            false
        } catch (e: Exception) {
            okCallbacks.remove(event.id, deferred)
            Log.w(TAG, "sendEvent ${event.id.take(8)} to $url: failed: ${e.message}")
            false
        }
    }

    fun subscribe(subId: String, filters: List<NostrFilter>) {
        activeSubs[subId] = filters
        send(ClientMessage.Req(subId, filters).toJson())
    }

    fun closeSubscription(subId: String) {
        activeSubs.remove(subId)
        send(ClientMessage.Close(subId).toJson())
    }

    fun disconnect() {
        closedIntentionally = true
        generation++
        reconnectJob?.cancel()
        synchronized(resubscribeLock) {
            resubscribeJob?.cancel()
            resubscribeJob = null
        }
        okCallbacks.forEach { (_, d) -> d.cancel() }
        okCallbacks.clear()
        lastEventTimestamp.clear()
        oldestDroppedTimestamp.clear()
        ws?.close(1000, "disconnect")
        ws = null
        _state.value = State.DISCONNECTED
    }

    /** Limits AUTH challenge responses per connection, regardless of whether the relay accepts them. */
    @Volatile
    private var authAttempts = 0

    private fun handleAuth(challenge: String) {
        if (authAttempts++ >= 3) return // Bound challenge responses, not relay-reported failures.
        val signer =
            authSigner ?: run {
                Log.w(TAG, "AUTH required by $url but no signer configured")
                return
            }
        try {
            val authEvent = signer(challenge, url)
            send(ClientMessage.Auth(authEvent).toJson())
            Log.d(TAG, "Sent AUTH response to $url (attempt $authAttempts)")
            // Re-send subscriptions once after first AUTH
            if (authAttempts == 1) {
                activeSubs.forEach { (subId, filters) ->
                    send(ClientMessage.Req(subId, filters).toJson())
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "AUTH failed for $url: ${e.message}")
        }
    }

    private fun scheduleReconnect() {
        reconnectJob?.cancel()
        val attempt = reconnectAttempt++
        if (attempt >= MAX_RECONNECT_ATTEMPTS) {
            Log.w(TAG, "Pausing reconnect to $url after $MAX_RECONNECT_ATTEMPTS attempts")
            return
        }
        // Exponential backoff with jitter: base * 2^attempt + random 0-25%
        val baseMs = minOf(1000L * (1L shl minOf(attempt, 6)), 60_000L)
        val jitterMs = (baseMs * java.util.concurrent.ThreadLocalRandom.current().nextDouble(0.25)).toLong()
        val delayMs = baseMs + jitterMs
        reconnectJob =
            scope.launch {
                delay(delayMs)
                if (_state.value == State.DISCONNECTED) connect()
            }
    }

    /** Reset reconnect counter. Called when a new sync cycle re-adds this relay. */
    fun resetReconnect() {
        reconnectAttempt = 0
    }

    companion object {
        private const val TAG = "Relay"
        private const val MAX_RECONNECT_ATTEMPTS = 20

        /** Buffer for slow active collectors; with no collectors, SharedFlow drops frames without buffering. */
        const val MESSAGE_BUFFER_CAPACITY = 4096
        private const val MAX_FRAME_CHARS = 1024 * 1024

        /** Log every Nth drop rather than every drop. */
        private const val DROP_LOG_INTERVAL = 100L

        /** Grace period for the collector to drain before re-requesting dropped subscriptions. */
        const val RESUBSCRIBE_DELAY_MS = 2000L
        val sharedClient: OkHttpClient =
            OkHttpClient
                .Builder()
                .pingInterval(30, TimeUnit.SECONDS)
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(0, TimeUnit.MINUTES)
                .build()
    }
}
