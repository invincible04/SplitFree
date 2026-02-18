package com.splitfree.data.nostr.protocol

import com.splitfree.domain.crypto.NostrEvent

/**
 * Client → Relay messages (NIP-01).
 */
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
