package com.splitfree.data.nostr.relay

import com.splitfree.data.nostr.protocol.ClientMessage
import com.splitfree.data.nostr.protocol.NostrFilter
import com.splitfree.data.nostr.protocol.RelayMessage
import com.splitfree.domain.crypto.NostrEvent
import com.splitfree.util.DebugLog as Log
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
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
    private val authSigner: ((challenge: String, relayUrl: String) -> NostrEvent)? = null
) {
    enum class State { DISCONNECTED, CONNECTING, CONNECTED }

    private var ws: WebSocket? = null
    private val _state = MutableStateFlow(State.DISCONNECTED)
    val state: StateFlow<State> = _state.asStateFlow()

    private val _messages = MutableSharedFlow<RelayMessage>(extraBufferCapacity = 256)
    val messages: SharedFlow<RelayMessage> = _messages.asSharedFlow()

    // OK callbacks: eventId → deferred result
    private val okCallbacks = ConcurrentHashMap<String, CompletableDeferred<RelayMessage.OkMsg>>()

    // Active subscriptions for re-send on reconnect
    private val activeSubs = ConcurrentHashMap<String, List<NostrFilter>>()

    // Track last-seen event timestamp per subscription for reconnect gap prevention
    private val lastEventTimestamp = ConcurrentHashMap<String, Long>()

    // Reconnect
    private var reconnectAttempt = 0
    private var reconnectJob: Job? = null

    fun connect() {
        if (_state.value != State.DISCONNECTED) return
        _state.value = State.CONNECTING

        val request = Request.Builder().url(url).build()
        ws =
            okHttpClient.newWebSocket(
                request,
                object : WebSocketListener() {
                    override fun onOpen(webSocket: WebSocket, response: Response) {
                        _state.value = State.CONNECTED
                        reconnectAttempt = 0
                        authAttempts = 0
                        // Re-send active subscriptions with updated since to cover reconnect gap
                        activeSubs.forEach { (subId, filters) ->
                            val lastSeen = lastEventTimestamp[subId]
                            val updatedFilters =
                                if (lastSeen != null) {
                                    filters.map { f -> f.copy(since = lastSeen - 60) } // 60s buffer
                                } else {
                                    filters
                                }
                            webSocket.send(ClientMessage.Req(subId, updatedFilters).toJson())
                        }
                        Log.d(TAG, "Connected to $url")
                    }

                    override fun onMessage(webSocket: WebSocket, text: String) {
                        try {
                            val msg = RelayMessage.parse(text) ?: return
                            when (msg) {
                                is RelayMessage.OkMsg -> {
                                    okCallbacks.remove(msg.eventId)?.complete(msg)
                                }

                                is RelayMessage.AuthMsg -> {
                                    handleAuth(msg.challenge)
                                }

                                else -> {
                                    // Track last-seen event timestamp per subscription for reconnect
                                    if (msg is RelayMessage.EventMsg) {
                                        val ts = msg.event.createdAt
                                        lastEventTimestamp.merge(msg.subId, ts) { old, new -> maxOf(old, new) }
                                    }
                                    _messages.tryEmit(msg)
                                }
                            }
                        } catch (e: Exception) {
                            Log.w(TAG, "onMessage error from $url: ${e.message}")
                        }
                    }

                    override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                        webSocket.close(1000, null)
                    }

                    override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                        _state.value = State.DISCONNECTED
                        if (code != 1000) scheduleReconnect() // reconnect unless we closed intentionally
                    }

                    override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                        Log.w(TAG, "Connection failed to $url: ${t.message}")
                        _state.value = State.DISCONNECTED
                        scheduleReconnect()
                    }
                }
            )
    }

    fun send(text: String): Boolean = ws?.send(text) ?: false

    /**
     * Publish an event and wait for the relay's OK response.
     *
     * @param event signed Nostr event to publish
     * @param timeoutMs max time to wait for OK response
     * @return true if the relay accepted the event
     */
    suspend fun sendEvent(event: NostrEvent, timeoutMs: Long = 7000): Boolean {
        val deferred = CompletableDeferred<RelayMessage.OkMsg>()
        okCallbacks[event.id] = deferred
        if (!send(ClientMessage.Event(event).toJson())) {
            okCallbacks.remove(event.id)
            Log.w(TAG, "sendEvent ${event.id.take(8)} to $url: send failed (not connected?)")
            return false
        }
        return try {
            val ok = withTimeout(timeoutMs) { deferred.await() }
            if (!ok.accepted) Log.w(TAG, "sendEvent ${event.id.take(8)} to $url: rejected: ${ok.message}")
            ok.accepted
        } catch (_: Exception) {
            okCallbacks.remove(event.id)
            Log.w(TAG, "sendEvent ${event.id.take(8)} to $url: timeout after ${timeoutMs}ms")
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
        reconnectJob?.cancel()
        okCallbacks.forEach { (_, d) -> d.cancel() }
        okCallbacks.clear()
        lastEventTimestamp.clear()
        ws?.close(1000, "disconnect")
        ws = null
        _state.value = State.DISCONNECTED
    }

    /** NIP-42: respond to relay AUTH challenge. */
    private var authAttempts = 0

    private fun handleAuth(challenge: String) {
        if (authAttempts++ >= 3) return // give up after 3 failed attempts
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
        // Exponential backoff: 1s, 2s, 4s, 8s, 16s, 32s, 60s cap
        val delayMs = minOf(1000L * (1L shl minOf(attempt, 6)), 60_000L)
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
        val sharedClient: OkHttpClient =
            OkHttpClient
                .Builder()
                .pingInterval(30, TimeUnit.SECONDS)
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(0, TimeUnit.MINUTES)
                .build()
    }
}
