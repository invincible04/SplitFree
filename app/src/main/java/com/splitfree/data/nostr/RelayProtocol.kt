package com.splitfree.data.nostr

import com.splitfree.domain.crypto.NostrEvent
import org.json.JSONArray
import org.json.JSONObject

/**
 * NIP-01 relay protocol messages — from scratch, no SDK.
 */

// --- Relay → Client ---

sealed class RelayMessage {
    data class EventMsg(val subId: String, val event: NostrEvent) : RelayMessage()
    data class OkMsg(val eventId: String, val accepted: Boolean, val message: String) : RelayMessage()
    data class EoseMsg(val subId: String) : RelayMessage()
    data class ClosedMsg(val subId: String, val message: String) : RelayMessage()
    data class NoticeMsg(val message: String) : RelayMessage()
    data class AuthMsg(val challenge: String) : RelayMessage()

    companion object {
        fun parse(json: String): RelayMessage? = try {
            val arr = JSONArray(json)
            when (arr.getString(0)) {
                "EVENT" -> EventMsg(arr.getString(1), parseEvent(arr.getJSONObject(2)))
                "OK" -> OkMsg(arr.getString(1), arr.getBoolean(2), arr.optString(3, ""))
                "EOSE" -> EoseMsg(arr.getString(1))
                "CLOSED" -> ClosedMsg(arr.getString(1), arr.getString(2))
                "NOTICE" -> NoticeMsg(arr.getString(1))
                "AUTH" -> AuthMsg(arr.getString(1))
                else -> null
            }
        } catch (_: Exception) { null }

        private fun parseEvent(obj: JSONObject): NostrEvent {
            val tagsArr = obj.getJSONArray("tags")
            val tags = (0 until tagsArr.length()).map { i ->
                val t = tagsArr.getJSONArray(i)
                (0 until t.length()).map { j -> t.getString(j) }
            }
            return NostrEvent(
                id = obj.getString("id"),
                pubkey = obj.getString("pubkey"),
                createdAt = obj.getLong("created_at"),
                kind = obj.getInt("kind"),
                tags = tags,
                content = obj.getString("content"),
                sig = obj.getString("sig")
            )
        }
    }
}

// --- Client → Relay ---

sealed class ClientMessage {
    abstract fun toJson(): String

    data class Event(val event: NostrEvent) : ClientMessage() {
        override fun toJson() = """["EVENT",${event.toJson()}]"""
    }

    data class Req(val subId: String, val filters: List<NostrFilter>) : ClientMessage() {
        override fun toJson(): String {
            val f = filters.joinToString(",") { it.toJson() }
            val escaped = subId.replace("\\", "\\\\").replace("\"", "\\\"")
            return """["REQ","$escaped",$f]"""
        }
    }

    data class Close(val subId: String) : ClientMessage() {
        override fun toJson(): String {
            val escaped = subId.replace("\\", "\\\\").replace("\"", "\\\"")
            return """["CLOSE","$escaped"]"""
        }
    }

    data class Auth(val event: NostrEvent) : ClientMessage() {
        override fun toJson() = """["AUTH",${event.toJson()}]"""
    }
}

// --- Filter ---

data class NostrFilter(
    val kinds: List<Int>? = null,
    val authors: List<String>? = null,
    val ids: List<String>? = null,
    val tags: Map<String, List<String>>? = null, // "#d" -> ["value"]
    val since: Long? = null,
    val until: Long? = null,
    val limit: Int? = null,
) {
    fun toJson(): String {
        val obj = JSONObject()
        kinds?.let { obj.put("kinds", JSONArray(it)) }
        authors?.let { obj.put("authors", JSONArray(it)) }
        ids?.let { obj.put("ids", JSONArray(it)) }
        tags?.forEach { (k, v) -> obj.put(k, JSONArray(v)) }
        since?.let { obj.put("since", it) }
        until?.let { obj.put("until", it) }
        limit?.let { obj.put("limit", it) }
        return obj.toString()
    }
}
