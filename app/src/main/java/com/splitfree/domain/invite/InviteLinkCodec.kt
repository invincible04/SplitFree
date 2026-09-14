package com.splitfree.domain.invite

import com.splitfree.domain.model.group.Group
import com.splitfree.domain.model.group.GroupIdentity
import com.splitfree.domain.util.RelayDefaults
import com.splitfree.domain.util.TextSanitizer
import com.splitfree.domain.util.hexToBytes
import com.splitfree.domain.util.toHex
import java.io.ByteArrayOutputStream
import java.util.Base64
import java.util.UUID

/**
 * Decoded invite link parameters.
 *
 * @property groupId UUID of the group to join; verified to equal
 *   [GroupIdentity.derive]`(creatorPubkey, createdAt)`
 * @property groupKey group symmetric key for [keyEpoch] (base64)
 * @property relays list of Nostr relay URLs for this group
 * @property name human-readable group name (sanitised, at most 100 UTF-8 bytes)
 * @property expiry unix timestamp (seconds) after which the link should be rejected
 * @property creatorPubkey hex pubkey of the group creator, bound to [groupId]
 * @property createdAt group creation time (unix seconds), bound to [groupId]
 * @property keyEpoch epoch that [groupKey] belongs to
 */
data class InviteParams(
    val groupId: String,
    val groupKey: String,
    val relays: List<String>,
    val name: String,
    val expiry: Long,
    val creatorPubkey: String,
    val createdAt: Long,
    val keyEpoch: Int
)

/**
 * Encodes/decodes compact invite links for group sharing.
 *
 * SECURITY MODEL: Bearer-token invite; the URL itself is the credential.
 *
 * - **Confidentiality** of the link is the user's responsibility. The group key travels in the
 *   clear inside the payload; anyone holding the link can join and read the group. Mitigated by
 *   the 24h expiry and the confirmation dialog shown before joining. (Encrypting the key with
 *   material that is itself carried in the same link adds bytes, not secrecy.)
 * - **Creator authenticity** is verifiable. The group id is
 *   [GroupIdentity.derive]`(creatorPubkey, createdAt)`, so [decode] rejects any link whose
 *   `(groupId, creatorPubkey, createdAt)` triple does not agree. A member cannot forge a link that
 *   makes the joiner believe someone else created the group, and every joiner of a given group
 *   ends up with the same `createdBy` regardless of who invited them.
 * - **Epoch correctness**: the link names the epoch its key belongs to, so a joiner stores the key
 *   under the right epoch and accepts the creator's next `key_rotation` (which must be exactly
 *   `epoch + 1`).
 *
 * Format: `splitfree://join?d=<base64url>`
 *
 * Binary layout (version 3):
 * ```
 * [version:1 = 0x03][groupId:16][creatorPub:32][createdAt:8][keyEpoch:2][groupKey:32]
 * [relayBitmap:2][customRelayLen:1][customRelays:customRelayLen][expiry:4][name:rest]
 * ```
 *
 * Relay URLs in [RelayDefaults.KNOWN_RELAYS] are encoded as bits of the big-endian 16-bit bitmap (bit `i`
 * = index `i`). Any other relay travels inside the custom-relay region as `[len:1][utf8:len]`, one entry after
 * another with no separator, so a URL may contain any byte (`,` included). Each entry is a `wss://` URL of at
 * most [MAX_RELAY_URL_LENGTH] UTF-8 bytes ([relayFits]) and the region as a whole at most
 * [MAX_CUSTOM_RELAY_BYTES] bytes. [fitsInviteLink] tells whether a relay list satisfies those budgets and
 * [MAX_RELAYS].
 */
object InviteLinkCodec {
    private val KNOWN_RELAYS = RelayDefaults.KNOWN_RELAYS
    private const val INVITE_EXPIRY_SECS = 24 * 3600L

    /** Upper bound on relays per group; the invite link refuses to carry more. */
    const val MAX_RELAYS = 10

    /** Size of the whole custom-relay region, which its one-byte length prefix can address. */
    private const val MAX_CUSTOM_RELAY_BYTES = 255

    /** UTF-8 bytes of one custom relay URL: the region minus that entry's own length byte. */
    private const val MAX_RELAY_URL_LENGTH = MAX_CUSTOM_RELAY_BYTES - 1
    private const val MAX_PAYLOAD_LENGTH = 2048
    private const val MAX_NAME_BYTES = 100
    private const val DEFAULT_NAME = "Group"

    private const val VERSION: Int = 0x03
    private const val UUID_SIZE = 16
    private const val PUBKEY_SIZE = 32
    private const val CREATED_AT_SIZE = 8
    private const val EPOCH_SIZE = 2
    private const val GROUP_KEY_SIZE = 32
    private const val RELAY_BITMAP_SIZE = 2
    private const val CUSTOM_RELAY_LEN_SIZE = 1
    private const val MAX_EPOCH = 0xFFFF

    private const val HEADER_SIZE =
        1 + UUID_SIZE + PUBKEY_SIZE + CREATED_AT_SIZE + EPOCH_SIZE + GROUP_KEY_SIZE +
            RELAY_BITMAP_SIZE + CUSTOM_RELAY_LEN_SIZE
    private const val EXPIRY_SIZE = 4

    /**
     * True when [relays] can travel in an invite link: at most [MAX_RELAYS] entries, each satisfying [relayFits],
     * and the length-prefixed encoding of those outside [RelayDefaults.KNOWN_RELAYS] at most
     * [MAX_CUSTOM_RELAY_BYTES] bytes in total.
     */
    fun fitsInviteLink(relays: List<String>): Boolean = fits(relays, customRelays(relays))

    /**
     * True when [relay] can travel in an invite link: a `wss://` URL of 1..[MAX_RELAY_URL_LENGTH] UTF-8 bytes.
     * Known relays always fit.
     */
    fun relayFits(relay: String): Boolean = relay in KNOWN_RELAYS ||
        (relay.startsWith("wss://") && relay.toByteArray(Charsets.UTF_8).size in 1..MAX_RELAY_URL_LENGTH)

    private fun fits(relays: List<String>, custom: List<ByteArray>): Boolean = relays.size <= MAX_RELAYS &&
        relays.all(::relayFits) &&
        custom.sumOf { CUSTOM_RELAY_LEN_SIZE + it.size } <= MAX_CUSTOM_RELAY_BYTES

    /**
     * Encode a group into a compact invite link.
     *
     * @param group the group to create an invite for; its `id` must equal
     *   [GroupIdentity.derive]`(group.createdBy, group.createdAt)`
     * @param groupKey base64-encoded 32-byte symmetric key for `group.keyEpoch`
     * @return invite URL string (`splitfree://join?d=...`)
     * @throws IllegalArgumentException if the group id is not bound to its creator, the key is not
     *   32 bytes, the epoch does not fit in 16 bits, or the relays do not satisfy [fitsInviteLink]
     */
    fun encode(group: Group, groupKey: String): String {
        val uuid = UUID.fromString(group.id)
        require(group.createdBy.length == PUBKEY_SIZE * 2) { "Group creator pubkey must be 64 hex chars" }
        require(GroupIdentity.matches(group.id, group.createdBy, group.createdAt)) {
            "Group id does not match its creator"
        }
        require(group.keyEpoch in 0..MAX_EPOCH) { "Key epoch out of range" }
        val custom = customRelays(group.relays)
        require(fits(group.relays, custom)) {
            "Relays do not fit in an invite link (max $MAX_RELAYS, each custom relay a wss:// URL of up to " +
                "$MAX_RELAY_URL_LENGTH bytes, $MAX_CUSTOM_RELAY_BYTES bytes of custom relays in total)"
        }
        val keyBytes = Base64.getDecoder().decode(groupKey)
        require(keyBytes.size == GROUP_KEY_SIZE) { "Group key must be $GROUP_KEY_SIZE bytes" }

        val nameBytes = truncateUtf8(sanitizeName(group.name), MAX_NAME_BYTES)
        val exp = (System.currentTimeMillis() / 1000 + INVITE_EXPIRY_SECS)
        val customRelayBytes = customRelayBytes(custom)

        val buf = ByteArrayOutputStream()
        buf.write(VERSION)
        writeUuid(buf, uuid)
        buf.write(group.createdBy.hexToBytes())
        writeInt64(buf, group.createdAt)
        writeUint16(buf, group.keyEpoch)
        buf.write(keyBytes)
        keyBytes.fill(0)
        writeUint16(buf, knownRelayBitmap(group.relays))
        buf.write(customRelayBytes.size)
        buf.write(customRelayBytes, 0, customRelayBytes.size)
        writeUint32(buf, exp)
        buf.write(nameBytes)

        val payload = Base64.getUrlEncoder().withoutPadding().encodeToString(buf.toByteArray())
        return "splitfree://join?d=$payload"
    }

    /**
     * Decode an invite link URI and return all parameters.
     *
     * @param uri `splitfree://join?d=...` deep link
     * @return parsed [InviteParams]
     * @throws IllegalArgumentException if the link is malformed, expired, uses an unsupported version,
     *   or its group id is not bound to the claimed creator
     */
    fun decode(uri: String): InviteParams {
        val dParam = extractPayloadParam(uri)
        require(dParam.length <= MAX_PAYLOAD_LENGTH) { "Invalid invite link: payload too large" }
        val data = Base64.getUrlDecoder().decode(dParam)
        require(data.isNotEmpty()) { "Invalid invite link: payload too short" }
        require((data[0].toInt() and 0xFF) == VERSION) { "Unsupported invite link version" }
        require(data.size >= HEADER_SIZE + EXPIRY_SIZE) { "Invalid invite link: payload too short" }

        var pos = 1
        val groupId = readUuid(data, pos)
        pos += UUID_SIZE

        val creatorPubkey = data.copyOfRange(pos, pos + PUBKEY_SIZE).toHex()
        pos += PUBKEY_SIZE

        val createdAt = readInt64(data, pos)
        pos += CREATED_AT_SIZE

        val keyEpoch = readUint16(data, pos)
        pos += EPOCH_SIZE

        val groupKey = Base64.getEncoder().encodeToString(data.copyOfRange(pos, pos + GROUP_KEY_SIZE))
        pos += GROUP_KEY_SIZE

        require(GroupIdentity.matches(groupId, creatorPubkey, createdAt)) {
            "Invite link does not match its claimed creator"
        }

        val (relays, bytesRead) = decodeRelays(data, pos)
        pos += bytesRead

        require(data.size >= pos + EXPIRY_SIZE) { "Invalid invite link: missing expiry" }
        val exp = readUint32(data, pos)
        pos += EXPIRY_SIZE
        require(System.currentTimeMillis() / 1000 <= exp) { "This invite link has expired" }

        val rawName = if (pos < data.size) String(data, pos, data.size - pos, Charsets.UTF_8) else ""
        val name = String(truncateUtf8(sanitizeName(rawName), MAX_NAME_BYTES), Charsets.UTF_8).ifBlank { DEFAULT_NAME }

        return InviteParams(groupId, groupKey, relays, name, exp, creatorPubkey, createdAt, keyEpoch)
    }

    // --- Name helpers ---

    /** Strips control and bidi-override characters and surrounding whitespace. */
    private fun sanitizeName(name: String): String = TextSanitizer.stripControlChars(name)

    /**
     * UTF-8 encodes [value] and truncates to at most [maxBytes] without splitting a code point,
     * so a truncated name still decodes cleanly on the other side.
     */
    private fun truncateUtf8(value: String, maxBytes: Int): ByteArray {
        val full = value.toByteArray(Charsets.UTF_8)
        if (full.size <= maxBytes) return full
        var end = maxBytes
        // Back up over UTF-8 continuation bytes (10xxxxxx) to the start of a code point.
        while (end > 0 && (full[end].toInt() and 0xC0) == 0x80) end--
        return full.copyOf(end)
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

    /** Bit `i` set for every relay in [relays] that is `KNOWN_RELAYS[i]`. */
    private fun knownRelayBitmap(relays: List<String>): Int = relays.fold(0) { bitmap, relay ->
        val idx = KNOWN_RELAYS.indexOf(relay)
        if (idx >= 0) bitmap or (1 shl idx) else bitmap
    }

    /** The UTF-8 bytes of every relay outside [KNOWN_RELAYS], in list order; the payload carries these verbatim. */
    private fun customRelays(relays: List<String>): List<ByteArray> =
        relays.filter { it !in KNOWN_RELAYS }.map { it.toByteArray(Charsets.UTF_8) }

    /** The custom-relay region: one `[len:1][utf8:len]` entry per relay, no separators. Requires [fits]. */
    private fun customRelayBytes(custom: List<ByteArray>): ByteArray {
        val buf = ByteArrayOutputStream()
        for (relay in custom) {
            buf.write(relay.size)
            buf.write(relay, 0, relay.size)
        }
        return buf.toByteArray()
    }

    /**
     * Decodes relay bitmap + custom relays from binary data. Returns (relays, bytesConsumed).
     *
     * The custom-relay region must be consumed exactly by whole `[len:1][utf8:len]` entries, each non-empty,
     * at most [MAX_RELAY_URL_LENGTH] bytes and a `wss://` URL; anything else is a malformed link.
     */
    private fun decodeRelays(data: ByteArray, startPos: Int): Pair<List<String>, Int> {
        var pos = startPos
        val bitmap = readUint16(data, pos)
        pos += RELAY_BITMAP_SIZE
        val relays = mutableListOf<String>()
        for (i in KNOWN_RELAYS.indices) {
            if (bitmap and (1 shl i) != 0) relays.add(KNOWN_RELAYS[i])
        }

        val customLen = data[pos].toInt() and 0xFF
        pos += CUSTOM_RELAY_LEN_SIZE
        val end = pos + customLen
        require(data.size >= end) { "Invalid invite link: truncated custom relays" }
        while (pos < end) {
            val len = data[pos].toInt() and 0xFF
            pos += CUSTOM_RELAY_LEN_SIZE
            require(len > 0) { "Invalid invite link: empty custom relay" }
            require(len <= MAX_RELAY_URL_LENGTH) { "Invalid invite link: custom relay too long" }
            require(pos + len <= end) { "Invalid invite link: custom relay overruns its region" }
            val relay = String(data, pos, len, Charsets.UTF_8)
            require(relay.startsWith("wss://")) { "Invalid relay URL in invite link" }
            relays.add(relay)
            pos += len
        }
        require(relays.size <= MAX_RELAYS) { "Too many relays in invite link (max $MAX_RELAYS)" }
        return relays to (pos - startPos)
    }

    private fun writeUuid(buf: ByteArrayOutputStream, uuid: UUID) {
        writeInt64(buf, uuid.mostSignificantBits)
        writeInt64(buf, uuid.leastSignificantBits)
    }

    private fun readUuid(data: ByteArray, pos: Int): String =
        UUID(readInt64(data, pos), readInt64(data, pos + 8)).toString()

    private fun writeInt64(buf: ByteArrayOutputStream, value: Long) {
        for (shift in 56 downTo 0 step 8) {
            buf.write((value ushr shift).toInt() and 0xFF)
        }
    }

    private fun readInt64(data: ByteArray, pos: Int): Long {
        var v = 0L
        for (i in 0 until 8) v = (v shl 8) or (data[pos + i].toLong() and 0xFF)
        return v
    }

    private fun writeUint16(buf: ByteArrayOutputStream, value: Int) {
        buf.write((value ushr 8) and 0xFF)
        buf.write(value and 0xFF)
    }

    private fun readUint16(data: ByteArray, pos: Int): Int =
        ((data[pos].toInt() and 0xFF) shl 8) or (data[pos + 1].toInt() and 0xFF)

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
