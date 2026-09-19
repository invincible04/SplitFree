package com.splitfree.test

import com.splitfree.domain.crypto.NostrEvent
import java.net.InetAddress
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.LinkedBlockingQueue
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import okhttp3.OkHttpClient
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import org.json.JSONArray
import org.json.JSONObject

/** Real TLS and WebSocket framing; the private CA is trusted only by clients explicitly created here. */
class LoopbackNostrRelay : AutoCloseable {
    private val certificate = HeldCertificate.Builder().commonName("localhost")
        .addSubjectAlternativeName("localhost").addSubjectAlternativeName("127.0.0.1").build()
    private val serverCertificates = HandshakeCertificates.Builder().heldCertificate(certificate).build()
    private val clientCertificates = HandshakeCertificates.Builder().addTrustedCertificate(
        certificate.certificate
    ).build()
    private val server = MockWebServer()
    val client: OkHttpClient = OkHttpClient.Builder()
        .sslSocketFactory(clientCertificates.sslSocketFactory(), clientCertificates.trustManager)
        .dns { host ->
            check(host == "localhost" || host == "127.0.0.1") { "External relay forbidden in loopback test: $host" }
            listOf(InetAddress.getByName("127.0.0.1"))
        }.build()
    val history = CopyOnWriteArrayList<NostrEvent>()
    data class Request(val subId: String, val filters: List<JSONObject>, val socket: WebSocket)
    val requests = LinkedBlockingQueue<Request>()
    val received = CopyOnWriteArrayList<Request>()
    val closed = CopyOnWriteArrayList<String>()
    val failures = CopyOnWriteArrayList<Throwable>()
    private val connections = CopyOnWriteArrayList<Connection>()

    @Volatile var respond: (Request) -> Unit = ::sendHistory

    @Volatile var deliverLive = true
    val url: String

    init {
        server.useHttps(serverCertificates.sslSocketFactory())
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse =
                MockResponse.Builder().webSocketUpgrade(Connection()).build()
        }
        server.start(InetAddress.getByName("127.0.0.1"), 0)
        url = "wss://localhost:${server.port}"
    }

    private inner class Connection : WebSocketListener() {
        private val subscriptions = ConcurrentHashMap<String, List<JSONObject>>()
        private lateinit var socket: WebSocket
        override fun onOpen(webSocket: WebSocket, response: Response) {
            socket = webSocket
            connections += this
        }
        override fun onMessage(webSocket: WebSocket, text: String) {
            try {
                val frame = JSONArray(text)
                when (frame.getString(0)) {
                    "REQ" -> {
                        val id = frame.getString(1)
                        val filters = (2 until frame.length()).map { frame.getJSONObject(it) }
                        subscriptions[id] = filters
                        val request = Request(id, filters, webSocket)
                        received += request
                        requests.add(request)
                        respond(request)
                    }
                    "EVENT" -> {
                        val event = checkNotNull(NostrEvent.fromJson(frame.getJSONObject(1).toString()))
                        check(event.verify())
                        if (history.none { it.id == event.id }) history += event
                        if (deliverLive) connections.forEach { it.publish(event) }
                        webSocket.send(JSONArray(listOf("OK", event.id, true, "stored")).toString())
                    }
                    "CLOSE" -> {
                        val id = frame.getString(1)
                        subscriptions.remove(id)
                        closed += id
                    }
                }
            } catch (failure: Throwable) {
                failures += failure
                webSocket.close(1011, "fixture failure")
            }
        }
        fun publish(event: NostrEvent) {
            subscriptions.forEach { (id, filters) ->
                if (filters.any { matches(event, it) }) sendEvent(socket, id, event)
            }
        }
        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            webSocket.close(1000, null)
        }
        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            connections.remove(this)
        }
    }

    fun sendHistory(request: Request) {
        val events = request.filters.flatMap { filter ->
            history.filter {
                matches(it, filter)
            }.sortedWith(compareByDescending<NostrEvent> { it.createdAt }.thenBy { it.id })
                .take(filter.optInt("limit", Int.MAX_VALUE))
        }.distinctBy { it.id }.sortedByDescending { it.createdAt }
        events.forEach { sendEvent(request.socket, request.subId, it) }
        eose(request)
    }

    fun eose(request: Request) {
        request.socket.send(JSONArray(listOf("EOSE", request.subId)).toString())
    }

    fun sendEvent(socket: WebSocket, id: String, event: NostrEvent) {
        socket.send("[\"EVENT\",${JSONObject.quote(id)},${event.toJson()}]")
    }

    private fun matches(event: NostrEvent, filter: JSONObject): Boolean {
        if (filter.has("since") && event.createdAt < filter.getLong("since")) return false
        if (filter.has("until") && event.createdAt > filter.getLong("until")) return false
        for ((field, actual) in listOf("ids" to event.id, "authors" to event.pubkey)) {
            filter.optJSONArray(field)?.let { values ->
                if ((0 until values.length()).none { actual.startsWith(values.getString(it)) }) return false
            }
        }
        filter.optJSONArray("kinds")?.let { values ->
            if ((0 until values.length()).none { event.kind == values.getInt(it) }) return false
        }
        for (name in filter.keys()) {
            if (!name.startsWith("#")) continue
            val values = filter.getJSONArray(name)
            if (event.tags.none { tag ->
                    tag.size > 1 &&
                        tag[0] == name.drop(1) &&
                        (0 until values.length()).any { values.getString(it) == tag[1] }
                }
            ) {
                return false
            }
        }
        return true
    }

    override fun close() {
        server.close()
        client.dispatcher.cancelAll()
        client.connectionPool.evictAll()
    }
}
