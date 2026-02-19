package com.splitfree.domain.crypto

import com.splitfree.domain.util.hexToBytes
import com.splitfree.domain.util.toHex
import fr.acinq.secp256k1.Secp256k1
import java.security.MessageDigest
import java.security.SecureRandom
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long

/**
 * Core Nostr event data model (NIP-01).
 * Pure data + serialization — no business logic.
 */
data class NostrEvent(
    val id: String = "",
    val pubkey: String,
    val createdAt: Long,
    val kind: Int,
    val tags: List<List<String>> = emptyList(),
    val content: String,
    val sig: String = ""
) {
    /**
     * Compute event ID per NIP-01: SHA-256 of the canonical JSON serialization.
     */
    fun computeId(): ByteArray {
        val serialized =
            buildString {
                append("[0,\"")
                append(pubkey)
                append("\",")
                append(createdAt)
                append(",")
                append(kind)
                append(",[")
                tags.forEachIndexed { i, tag ->
                    if (i > 0) append(",")
                    append("[")
                    tag.forEachIndexed { j, v ->
                        if (j > 0) append(",")
                        append("\"")
                        append(escapeJson(v))
                        append("\"")
                    }
                    append("]")
                }
                append("],\"")
                append(escapeJson(content))
                append("\"]")
            }
        return MessageDigest
            .getInstance("SHA-256")
            .digest(serialized.toByteArray(Charsets.UTF_8))
    }

    /** Sign this event with a private key. Returns a new event with id and sig set. */
    fun sign(privateKey: ByteArray): NostrEvent {
        val idBytes = computeId()
        val auxRand = ByteArray(32).also { SecureRandom().nextBytes(it) }
        val signature = Secp256k1.signSchnorr(idBytes, privateKey, auxRand)
        return copy(id = idBytes.toHex(), sig = signature.toHex())
    }

    /** Verify this event's signature per NIP-01 + BIP-340. */
    fun verify(): Boolean {
        if (id.isEmpty() || sig.isEmpty() || pubkey.isEmpty()) return false
        val idBytes = computeId()
        if (idBytes.toHex() != id) return false
        return try {
            Secp256k1.verifySchnorr(sig.hexToBytes(), idBytes, pubkey.hexToBytes())
        } catch (_: Exception) {
            false
        }
    }

    /** Serialize to JSON (wire format). */
    fun toJson(): String = buildString {
        append("{\"id\":\"")
        append(id)
        append("\",\"pubkey\":\"")
        append(pubkey)
        append("\",\"created_at\":")
        append(createdAt)
        append(",\"kind\":")
        append(kind)
        append(",\"tags\":[")
        tags.forEachIndexed { i, tag ->
            if (i > 0) append(",")
            append("[")
            tag.forEachIndexed { j, v ->
                if (j > 0) append(",")
                append("\"")
                append(escapeJson(v))
                append("\"")
            }
            append("]")
        }
        append("],\"content\":\"")
        append(escapeJson(content))
        append("\",\"sig\":\"")
        append(sig)
        append("\"}")
    }

    companion object {
        /** Parse a NostrEvent from JSON string. */
        fun fromJson(json: String): NostrEvent? = try {
            val obj = Json.parseToJsonElement(json).jsonObject
            NostrEvent(
                id = obj["id"]?.jsonPrimitive?.content ?: "",
                pubkey = obj["pubkey"]?.jsonPrimitive?.content ?: "",
                createdAt = obj["created_at"]?.jsonPrimitive?.long ?: 0L,
                kind = obj["kind"]?.jsonPrimitive?.int ?: 0,
                tags =
                obj["tags"]?.jsonArray?.map { tagArr ->
                    tagArr.jsonArray.map { it.jsonPrimitive.content }
                } ?: emptyList(),
                content = obj["content"]?.jsonPrimitive?.content ?: "",
                sig = obj["sig"]?.jsonPrimitive?.content ?: ""
            )
        } catch (_: Exception) {
            null
        }

        /** Create a pubkey hex from a 32-byte private key. */
        fun pubkeyFromPrivkey(privateKey: ByteArray): String {
            val uncompressed = Secp256k1.pubkeyCreate(privateKey)
            return uncompressed.copyOfRange(1, 33).toHex()
        }
    }
}

/**
 * Escape a string per NIP-01 JSON serialization rules.
 */
internal fun escapeJson(s: String): String = buildString(s.length) {
    for (c in s) {
        when {
            c == '\n' -> append("\\n")
            c == '\r' -> append("\\r")
            c == '\t' -> append("\\t")
            c == '\b' -> append("\\b")
            c == '\u000C' -> append("\\f")
            c == '"' -> append("\\\"")
            c == '\\' -> append("\\\\")
            c.code in 0..0x1F -> append("\\u%04x".format(c.code))
            else -> append(c)
        }
    }
}
