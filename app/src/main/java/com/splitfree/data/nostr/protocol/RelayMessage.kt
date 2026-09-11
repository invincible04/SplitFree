package com.splitfree.data.nostr.protocol

import com.splitfree.domain.crypto.NostrEvent
import org.json.JSONArray
import org.json.JSONException
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

        /**
         * Shape check only. A wrong-length id/pubkey/sig can never pass [NostrEvent.verify], so
         * failing here (which [parse] turns into `null`) drops obviously malformed events before
         * they cost a SHA-256 and a Schnorr verification. `getString` still coerces non-string
         * JSON values, which is fine: a number is never 64 hex chars long.
         */
        private fun parseEvent(obj: JSONObject): NostrEvent {
            val id = obj.getString("id")
            val pubkey = obj.getString("pubkey")
            val sig = obj.getString("sig")
            if (id.length != ID_HEX_LENGTH || pubkey.length != PUBKEY_HEX_LENGTH || sig.length != SIG_HEX_LENGTH) {
                throw JSONException("Malformed event: id/pubkey/sig length")
            }
            val tagsArr = obj.getJSONArray("tags")
            val tags =
                (0 until tagsArr.length()).map { i ->
                    val t = tagsArr.getJSONArray(i)
                    (0 until t.length()).map { j -> t.getString(j) }
                }
            return NostrEvent(
                id = id,
                pubkey = pubkey,
                createdAt = obj.getLong("created_at"),
                kind = obj.getInt("kind"),
                tags = tags,
                content = obj.getString("content"),
                sig = sig
            )
        }

        /** Hex length of a SHA-256 event id and of an x-only secp256k1 pubkey. */
        private const val ID_HEX_LENGTH = 64
        private const val PUBKEY_HEX_LENGTH = 64

        /** Hex length of a BIP-340 Schnorr signature. */
        private const val SIG_HEX_LENGTH = 128
    }
}
