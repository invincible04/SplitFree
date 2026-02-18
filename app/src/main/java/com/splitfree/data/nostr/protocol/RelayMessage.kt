package com.splitfree.data.nostr.protocol

import com.splitfree.domain.crypto.NostrEvent
import org.json.JSONArray
import org.json.JSONObject

/**
 * Relay → Client messages (NIP-01).
 */
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
        } catch (_: Exception) {
            null
        }

        private fun parseEvent(obj: JSONObject): NostrEvent {
            val tagsArr = obj.getJSONArray("tags")
            val tags =
                (0 until tagsArr.length()).map { i ->
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
