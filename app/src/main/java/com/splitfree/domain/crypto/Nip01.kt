package com.splitfree.domain.crypto

import fr.acinq.secp256k1.Secp256k1
import kotlinx.serialization.json.*
import java.security.MessageDigest
import java.security.SecureRandom

/**
 * NIP-01 event model and signing — implemented from scratch per spec.
 * https://github.com/nostr-protocol/nips/blob/master/01.md
 *
 * Event ID = SHA-256 of [0, pubkey, created_at, kind, tags, content]
 * Signature = BIP-340 Schnorr over secp256k1
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
     * [0, <pubkey>, <created_at>, <kind>, <tags>, <content>]
     */
    fun computeId(): ByteArray {
        val serialized = buildString {
            append("[0,\"")
            append(pubkey)
            append("\",")
            append(createdAt)
            append(",")
            append(kind)
            append(",")
            // Tags as JSON array
            append("[")
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
            append("]")
            append(",\"")
            append(escapeJson(content))
            append("\"]")
        }
        return MessageDigest.getInstance("SHA-256")
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
        append("{\"id\":\""); append(id)
        append("\",\"pubkey\":\""); append(pubkey)
        append("\",\"created_at\":"); append(createdAt)
        append(",\"kind\":"); append(kind)
        append(",\"tags\":[")
        tags.forEachIndexed { i, tag ->
            if (i > 0) append(",")
            append("[")
            tag.forEachIndexed { j, v ->
                if (j > 0) append(",")
                append("\""); append(escapeJson(v)); append("\"")
            }
            append("]")
        }
        append("],\"content\":\""); append(escapeJson(content))
        append("\",\"sig\":\""); append(sig)
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
                tags = obj["tags"]?.jsonArray?.map { tagArr ->
                    tagArr.jsonArray.map { it.jsonPrimitive.content }
                } ?: emptyList(),
                content = obj["content"]?.jsonPrimitive?.content ?: "",
                sig = obj["sig"]?.jsonPrimitive?.content ?: ""
            )
        } catch (_: Exception) {
            null
        }

        /** Create a pubkey hex from a 32-byte private key using ACINQ secp256k1-kmp. */
        fun pubkeyFromPrivkey(privateKey: ByteArray): String {
            // pubkeyCreate returns 65-byte uncompressed (04||x||y), extract x-only (32 bytes)
            val uncompressed = Secp256k1.pubkeyCreate(privateKey)
            return uncompressed.copyOfRange(1, 33).toHex()
        }
    }
}

/**
 * Escape a string per NIP-01 JSON serialization rules.
 * Must escape: \n \r \t \b \f \" \\
 */
private fun escapeJson(s: String): String = buildString(s.length) {
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

// --- Hex utilities ---

fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

fun String.hexToBytes(): ByteArray {
    require(length % 2 == 0) { "hex string must have even length" }
    return ByteArray(length / 2) { i ->
        ((Character.digit(this[i * 2], 16) shl 4) + Character.digit(this[i * 2 + 1], 16)).toByte()
    }
}
