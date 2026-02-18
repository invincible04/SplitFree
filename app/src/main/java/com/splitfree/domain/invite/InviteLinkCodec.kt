package com.splitfree.domain.invite

import android.util.Base64
import com.splitfree.data.nostr.RelayConfig
import com.splitfree.domain.crypto.NostrEvent
import com.splitfree.domain.crypto.nip.Nip59
import com.splitfree.domain.model.group.Group
import com.splitfree.util.hexToBytes
import com.splitfree.util.toHex
import java.io.ByteArrayOutputStream
import java.net.URLDecoder
import java.net.URLEncoder
import java.security.SecureRandom
import java.util.UUID

/**
 * Decoded invite link parameters.
 *
 * @property groupKeyBase64 present in v2 links (group key embedded in URL)
 * @property ephemeralPrivHex present in v3 links (ephemeral key for relay-based key exchange)
 * @property expiry unix timestamp after which the link should be rejected
 */
data class InviteParams(
    val groupId: String,
    val groupKeyBase64: String?,
    val ephemeralPrivHex: String?,
    val relays: List<String>,
    val name: String,
    val expiry: Long?
)

/**
 * Encodes/decodes compact invite links for group sharing.
 *
 * Supports three formats:
 * - v3: `splitfree://join?d=<base64>` with ephemeral key exchange (group key never in URL)
 * - v2: `splitfree://join?d=<base64>` with embedded group key (legacy compact)
 * - v1: `splitfree://join?g=...&k=...&r=...` (legacy query params)
 *
 * Relay URLs are bitmap-encoded against [RelayConfig.KNOWN_RELAYS] for compactness.
 */
object InviteLinkCodec {
    private val KNOWN_RELAYS = RelayConfig.KNOWN_RELAYS
    private const val INVITE_EXPIRY_SECS = 24 * 3600L
    private const val MAX_RELAYS = 10
    private const val MAX_RELAY_URL_LENGTH = 256

    /**
     * Encode a group into a compact invite link.
     *
     * @param group the group to create an invite for
     * @param groupKey base64-encoded symmetric group key
     * @param senderPrivKey if non-null, produces a v3 link with ephemeral key exchange;
     *                      otherwise produces a v2 link with the group key embedded
     * @return pair of (invite URL, optional key_delivery gift wrap event for v3)
     */
    fun encode(group: Group, groupKey: String, senderPrivKey: ByteArray? = null): Pair<String, NostrEvent?> {
        val uuid = UUID.fromString(group.id)
        val nameBytes = group.name.toByteArray(Charsets.UTF_8)
        val exp = (System.currentTimeMillis() / 1000 + INVITE_EXPIRY_SECS)

        var relayBitmap = 0
        val customRelays = mutableListOf<String>()
        for (relay in group.relays) {
            val idx = KNOWN_RELAYS.indexOf(relay)
            if (idx >= 0) {
                relayBitmap = relayBitmap or (1 shl idx)
            } else {
                customRelays.add(relay)
            }
        }
        val customRelayBytes = customRelays.joinToString(",").toByteArray(Charsets.UTF_8)

        val buf = ByteArrayOutputStream()
        var keyDeliveryEvent: NostrEvent? = null

        if (senderPrivKey != null) {
            val ephPriv = ByteArray(32).also { SecureRandom().nextBytes(it) }
            while (!fr.acinq.secp256k1.Secp256k1
                    .secKeyVerify(ephPriv)
            ) {
                SecureRandom().nextBytes(ephPriv)
            }
            val ephPubHex = NostrEvent.pubkeyFromPrivkey(ephPriv)

            val rumor =
                NostrEvent(
                    pubkey = NostrEvent.pubkeyFromPrivkey(senderPrivKey),
                    createdAt = System.currentTimeMillis() / 1000,
                    kind = 30078,
                    tags = listOf(listOf("g", group.id), listOf("t", "key_delivery")),
                    content = groupKey
                )
            keyDeliveryEvent =
                Nip59.giftWrap(
                    rumor = rumor.copy(sig = ""),
                    senderPrivKey = senderPrivKey,
                    recipientPubKey = ephPubHex.hexToBytes()
                )

            buf.write(3)
            writeUuid(buf, uuid)
            buf.write(ephPriv)
            ephPriv.fill(0)
        } else {
            val keyBytes = groupKey.toByteArray(Charsets.UTF_8)
            buf.write(2)
            writeUuid(buf, uuid)
            buf.write(keyBytes.size.coerceAtMost(255))
            buf.write(keyBytes, 0, keyBytes.size.coerceAtMost(255))
        }

        buf.write(relayBitmap and 0xFF)
        buf.write(customRelayBytes.size.coerceAtMost(255))
        if (customRelayBytes.isNotEmpty()) buf.write(customRelayBytes, 0, customRelayBytes.size.coerceAtMost(255))
        val expInt = (exp and 0xFFFFFFFFL).toInt()
        buf.write((expInt ushr 24) and 0xFF)
        buf.write((expInt ushr 16) and 0xFF)
        buf.write((expInt ushr 8) and 0xFF)
        buf.write(expInt and 0xFF)
        buf.write(nameBytes, 0, nameBytes.size.coerceAtMost(100))

        val payload = Base64.encodeToString(buf.toByteArray(), Base64.URL_SAFE or Base64.NO_WRAP)
        return "splitfree://join?d=$payload" to keyDeliveryEvent
    }

    /**
     * Decode an invite link URI into its parameters.
     *
     * @param uri `splitfree://join?...` deep link (v1, v2, or v3 format)
     * @return parsed [InviteParams]
     * @throws IllegalArgumentException if the link is malformed or contains invalid relay URLs
     */
    fun decode(uri: String): InviteParams {
        val params = parseUri(uri)
        require(params.containsKey("g")) { "Invalid invite link: missing group ID" }
        val groupId = String(Base64.decode(params["g"]!!, Base64.URL_SAFE or Base64.NO_WRAP))
        val relays = (params["r"] ?: "").split(",").filter { it.isNotBlank() }
        val name = URLDecoder.decode(params["n"] ?: "Group", "UTF-8")
        val expiry = params["exp"]?.toLongOrNull()

        require(relays.size <= MAX_RELAYS) { "Too many relays in invite link (max $MAX_RELAYS)" }
        for (relay in relays) {
            require(relay.startsWith("wss://") && relay.length <= MAX_RELAY_URL_LENGTH) {
                "Invalid relay URL: must use wss:// scheme and be under $MAX_RELAY_URL_LENGTH chars"
            }
        }

        return InviteParams(
            groupId = groupId,
            groupKeyBase64 = params["k"],
            ephemeralPrivHex = params["eph"],
            relays = relays,
            name = name,
            expiry = expiry
        )
    }

    private fun parseUri(uri: String): Map<String, String> {
        val query = uri.substringAfter("?", "")
        val params =
            query.split("&").filter { it.contains("=") }.associate {
                val (k, v) = it.split("=", limit = 2)
                k to v
            }
        params["d"]?.let { d ->
            val decoded = decodeCompactLink(d)
            if (decoded.isNotEmpty()) return decoded
        }
        val fragment = uri.substringAfter("#", "")
        if (fragment.isNotEmpty() && !fragment.contains("=")) {
            val decoded = decodeCompactLink(fragment)
            if (decoded.isNotEmpty()) return decoded
        }
        return params
    }

    private fun decodeCompactLink(fragment: String): Map<String, String> {
        val data = Base64.decode(fragment, Base64.URL_SAFE or Base64.NO_WRAP)
        if (data.isEmpty()) return emptyMap()
        val version = data[0].toInt() and 0xFF
        if (version != 2 && version != 3) return emptyMap()
        var pos = 1

        if (data.size < pos + 16) return emptyMap()
        var msb = 0L
        for (i in 0 until 8) msb = (msb shl 8) or (data[pos + i].toLong() and 0xFF)
        var lsb = 0L
        for (i in 0 until 8) lsb = (lsb shl 8) or (data[pos + 8 + i].toLong() and 0xFF)
        val groupId = UUID(msb, lsb).toString()
        pos += 16

        val result =
            mutableMapOf(
                "g" to Base64.encodeToString(groupId.toByteArray(), Base64.URL_SAFE or Base64.NO_WRAP)
            )

        if (version == 2) {
            if (data.size < pos + 1) return emptyMap()
            val keyLen = data[pos].toInt() and 0xFF
            pos++
            if (data.size < pos + keyLen) return emptyMap()
            val groupKey = String(data, pos, keyLen, Charsets.UTF_8)
            pos += keyLen
            result["k"] = Base64.encodeToString(groupKey.toByteArray(), Base64.URL_SAFE or Base64.NO_WRAP)
        } else {
            if (data.size < pos + 32) return emptyMap()
            val ephPriv = data.copyOfRange(pos, pos + 32)
            pos += 32
            result["eph"] = ephPriv.toHex()
            ephPriv.fill(0)
        }

        if (data.size < pos + 1) return emptyMap()
        val bitmap = data[pos].toInt() and 0xFF
        pos++
        val relays = mutableListOf<String>()
        for (i in KNOWN_RELAYS.indices) {
            if (bitmap and (1 shl i) != 0) relays.add(KNOWN_RELAYS[i])
        }

        if (data.size < pos + 1) return emptyMap()
        val customLen = data[pos].toInt() and 0xFF
        pos++
        if (customLen > 0 && data.size >= pos + customLen) {
            val custom = String(data, pos, customLen, Charsets.UTF_8)
            relays.addAll(custom.split(",").filter { it.isNotBlank() })
            pos += customLen
        }

        if (data.size < pos + 4) return emptyMap()
        val exp =
            ((data[pos].toLong() and 0xFF) shl 24) or
                ((data[pos + 1].toLong() and 0xFF) shl 16) or
                ((data[pos + 2].toLong() and 0xFF) shl 8) or
                (data[pos + 3].toLong() and 0xFF)
        pos += 4

        val name = if (pos < data.size) String(data, pos, data.size - pos, Charsets.UTF_8) else "Group"

        result["r"] = relays.joinToString(",")
        result["n"] = URLEncoder.encode(name, "UTF-8")
        result["exp"] = exp.toString()
        return result
    }

    private fun writeUuid(buf: ByteArrayOutputStream, uuid: UUID) {
        for (shift in listOf(56, 48, 40, 32, 24, 16, 8, 0)) {
            buf.write((uuid.mostSignificantBits ushr shift).toInt() and 0xFF)
        }
        for (shift in listOf(56, 48, 40, 32, 24, 16, 8, 0)) {
            buf.write((uuid.leastSignificantBits ushr shift).toInt() and 0xFF)
        }
    }
}
