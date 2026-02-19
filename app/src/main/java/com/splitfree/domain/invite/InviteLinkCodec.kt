package com.splitfree.domain.invite

import com.splitfree.domain.crypto.NostrEvent
import com.splitfree.domain.crypto.nip.Nip44
import com.splitfree.domain.model.group.Group
import com.splitfree.domain.util.RelayDefaults
import com.splitfree.domain.util.hexToBytes
import java.io.ByteArrayOutputStream
import java.security.SecureRandom
import java.util.Base64
import java.util.UUID

/**
 * Decoded invite link parameters.
 *
 * @property groupId UUID of the group to join
 * @property groupKey decrypted group symmetric key (base64)
 * @property relays list of Nostr relay URLs for this group
 * @property name human-readable group name
 * @property expiry unix timestamp (seconds) after which the link should be rejected
 */
data class InviteParams(
    val groupId: String,
    val groupKey: String,
    val relays: List<String>,
    val name: String,
    val expiry: Long
)

/**
 * Encodes/decodes compact invite links for group sharing.
 *
 * Format: `splitfree://join?d=<base64>` with direct NIP-44 encrypted key exchange.
 * The group key is NIP-44 encrypted using ECDH between the sender's private key and
 * an ephemeral key, then embedded in the URL. No relay involvement for key delivery.
 *
 * Binary layout:
 * ```
 * [uuid:16][ephPriv:32][senderPub:32][relayBitmap:1][customRelayLen:1]
 * [customRelays:N][expiry:4][encKeyLen:1][encryptedKey:M][name:rest]
 * ```
 *
 * Relay URLs are bitmap-encoded against [RelayDefaults.KNOWN_RELAYS] for compactness.
 */
object InviteLinkCodec {
    private val KNOWN_RELAYS = RelayDefaults.KNOWN_RELAYS
    private const val INVITE_EXPIRY_SECS = 24 * 3600L
    private const val MAX_RELAYS = 10
    private const val MAX_RELAY_URL_LENGTH = 256
    private const val MAX_PAYLOAD_LENGTH = 2048

    /** uuid(16) + ephPriv(32) + senderPub(32) + bitmap(1) + customLen(1) */
    private const val HEADER_SIZE = 16 + 32 + 32 + 1 + 1
    private const val EXPIRY_SIZE = 4

    /**
     * Encode a group into a compact invite link with NIP-44 encrypted group key.
     *
     * @param group the group to create an invite for
     * @param groupKey base64-encoded symmetric group key
     * @param senderPrivKey sender's 32-byte private key for ECDH key agreement
     * @return invite URL string (`splitfree://join?d=...`)
     */
    fun encode(group: Group, groupKey: String, senderPrivKey: ByteArray): String {
        val uuid = UUID.fromString(group.id)
        val nameBytes = group.name.toByteArray(Charsets.UTF_8)
        val exp = (System.currentTimeMillis() / 1000 + INVITE_EXPIRY_SECS)

        val (relayBitmap, customRelayBytes) = encodeRelays(group.relays)
        val (ephPriv, encryptedKeyBytes) = encryptGroupKey(groupKey, senderPrivKey)
        val senderPub = NostrEvent.pubkeyFromPrivkey(senderPrivKey).hexToBytes()

        val buf = ByteArrayOutputStream()
        writeUuid(buf, uuid)
        buf.write(ephPriv)
        ephPriv.fill(0)
        buf.write(senderPub)
        buf.write(relayBitmap and 0xFF)
        buf.write(customRelayBytes.size.coerceAtMost(255))
        if (customRelayBytes.isNotEmpty()) buf.write(customRelayBytes, 0, customRelayBytes.size.coerceAtMost(255))
        writeUint32(buf, exp)
        buf.write(encryptedKeyBytes.size)
        buf.write(encryptedKeyBytes)
        buf.write(nameBytes, 0, nameBytes.size.coerceAtMost(100))

        val payload = Base64.getUrlEncoder().withoutPadding().encodeToString(buf.toByteArray())
        return "splitfree://join?d=$payload"
    }

    /**
     * Decode an invite link URI, decrypt the group key, and return all parameters.
     *
     * @param uri `splitfree://join?d=...` deep link
     * @return parsed [InviteParams] with decrypted group key
     * @throws IllegalArgumentException if the link is malformed or decryption fails
     */
    fun decode(uri: String): InviteParams {
        val dParam = extractPayloadParam(uri)
        require(dParam.length <= MAX_PAYLOAD_LENGTH) { "Invalid invite link: payload too large" }
        val data = Base64.getUrlDecoder().decode(dParam)
        require(data.size >= HEADER_SIZE + EXPIRY_SIZE) { "Invalid invite link: payload too short" }

        var pos = 0
        val groupId = readUuid(data, pos)
        pos += 16

        val ephPriv = data.copyOfRange(pos, pos + 32)
        pos += 32
        val senderPub = data.copyOfRange(pos, pos + 32)
        pos += 32

        val (relays, bytesRead) = decodeRelays(data, pos)
        pos += bytesRead

        require(data.size >= pos + EXPIRY_SIZE) { "Invalid invite link: missing expiry" }
        val exp = readUint32(data, pos)
        pos += EXPIRY_SIZE
        require(System.currentTimeMillis() / 1000 <= exp) { "This invite link has expired" }

        require(data.size >= pos + 1) { "Invalid invite link: missing encrypted key" }
        val encKeyLen = data[pos].toInt() and 0xFF
        pos++
        require(data.size >= pos + encKeyLen) { "Invalid invite link: truncated encrypted key" }
        val encKeyBytes = data.copyOfRange(pos, pos + encKeyLen)
        pos += encKeyLen

        val groupKey = try {
            decryptGroupKey(encKeyBytes, ephPriv, senderPub)
        } finally {
            ephPriv.fill(0)
        }

        val name = if (pos < data.size) String(data, pos, data.size - pos, Charsets.UTF_8) else "Group"

        return InviteParams(groupId, groupKey, relays, name, exp)
    }

    // --- Crypto helpers ---

    /**
     * Generates an ephemeral keypair and NIP-44 encrypts the group key.
     *
     * @return pair of (ephemeral private key bytes, encrypted key raw bytes)
     */
    private fun encryptGroupKey(groupKey: String, senderPrivKey: ByteArray): Pair<ByteArray, ByteArray> {
        val ephPriv = ByteArray(32).also { SecureRandom().nextBytes(it) }
        while (!fr.acinq.secp256k1.Secp256k1.secKeyVerify(ephPriv)) {
            SecureRandom().nextBytes(ephPriv)
        }
        val ephPub = NostrEvent.pubkeyFromPrivkey(ephPriv).hexToBytes()
        val convKey = Nip44.getConversationKey(senderPrivKey, ephPub)
        val encryptedB64 = Nip44.encrypt(groupKey, convKey)
        return ephPriv to Base64.getDecoder().decode(encryptedB64)
    }

    /**
     * Decrypts the group key using the ephemeral private key and sender's public key.
     *
     * @return decrypted group key string
     */
    private fun decryptGroupKey(encKeyBytes: ByteArray, ephPriv: ByteArray, senderPub: ByteArray): String {
        val convKey = Nip44.getConversationKey(ephPriv, senderPub)
        val encryptedB64 = Base64.getEncoder().encodeToString(encKeyBytes)
        return Nip44.decrypt(encryptedB64, convKey)
    }

    // --- Binary helpers ---

    /** Extracts the `d` query parameter from a `splitfree://join?d=...` URI. */
    private fun extractPayloadParam(uri: String): String {
        val query = uri.substringAfter("?", "")
        return query.split("&").filter { it.contains("=") }.associate {
            val (k, v) = it.split("=", limit = 2)
            k to v
        }["d"] ?: throw IllegalArgumentException("Invalid invite link: missing payload")
    }

    /** Encodes relay list as a bitmap of [KNOWN_RELAYS] plus raw bytes for custom relays. */
    private fun encodeRelays(relays: List<String>): Pair<Int, ByteArray> {
        var bitmap = 0
        val custom = mutableListOf<String>()
        for (relay in relays) {
            val idx = KNOWN_RELAYS.indexOf(relay)
            if (idx >= 0) bitmap = bitmap or (1 shl idx) else custom.add(relay)
        }
        return bitmap to custom.joinToString(",").toByteArray(Charsets.UTF_8)
    }

    /** Decodes relay bitmap + custom relays from binary data. Returns (relays, bytesConsumed). */
    private fun decodeRelays(data: ByteArray, startPos: Int): Pair<List<String>, Int> {
        var pos = startPos
        val bitmap = data[pos].toInt() and 0xFF
        pos++
        val relays = mutableListOf<String>()
        for (i in KNOWN_RELAYS.indices) {
            if (bitmap and (1 shl i) != 0) relays.add(KNOWN_RELAYS[i])
        }

        val customLen = data[pos].toInt() and 0xFF
        pos++
        if (customLen > 0) {
            require(data.size >= pos + customLen) { "Invalid invite link: truncated custom relays" }
            val customList = String(data, pos, customLen, Charsets.UTF_8).split(",").filter { it.isNotBlank() }
            for (r in customList) {
                require(r.startsWith("wss://") && r.length <= MAX_RELAY_URL_LENGTH) {
                    "Invalid relay URL in invite link"
                }
            }
            relays.addAll(customList)
            pos += customLen
        }
        require(relays.size <= MAX_RELAYS) { "Too many relays in invite link (max $MAX_RELAYS)" }
        return relays to (pos - startPos)
    }

    private fun writeUuid(buf: ByteArrayOutputStream, uuid: UUID) {
        for (shift in listOf(56, 48, 40, 32, 24, 16, 8, 0)) {
            buf.write((uuid.mostSignificantBits ushr shift).toInt() and 0xFF)
        }
        for (shift in listOf(56, 48, 40, 32, 24, 16, 8, 0)) {
            buf.write((uuid.leastSignificantBits ushr shift).toInt() and 0xFF)
        }
    }

    private fun readUuid(data: ByteArray, pos: Int): String {
        var msb = 0L
        for (i in 0 until 8) msb = (msb shl 8) or (data[pos + i].toLong() and 0xFF)
        var lsb = 0L
        for (i in 0 until 8) lsb = (lsb shl 8) or (data[pos + 8 + i].toLong() and 0xFF)
        return UUID(msb, lsb).toString()
    }

    private fun writeUint32(buf: ByteArrayOutputStream, value: Long) {
        val v = (value and 0xFFFFFFFFL).toInt()
        buf.write((v ushr 24) and 0xFF)
        buf.write((v ushr 16) and 0xFF)
        buf.write((v ushr 8) and 0xFF)
        buf.write(v and 0xFF)
    }

    private fun readUint32(data: ByteArray, pos: Int): Long = ((data[pos].toLong() and 0xFF) shl 24) or
        ((data[pos + 1].toLong() and 0xFF) shl 16) or
        ((data[pos + 2].toLong() and 0xFF) shl 8) or
        (data[pos + 3].toLong() and 0xFF)
}
