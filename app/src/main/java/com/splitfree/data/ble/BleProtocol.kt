package com.splitfree.data.ble

import com.splitfree.data.util.CompressionUtil
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID

/**
 * Compact binary protocol for BLE transfers.
 * Replaces JSON-over-BLE for ~60% bandwidth savings.
 *
 * Header (14 bytes):
 * ┌─────────┬──────┬─────┬───────────┬───────┬──────────────┐
 * │ Version │ Type │ TTL │ Timestamp │ Flags │ PayloadLength│
 * │ 1 byte  │1 byte│1 byte│  8 bytes │1 byte │   2 bytes    │
 * └─────────┴──────┴─────┴───────────┴───────┴──────────────┘
 *
 * Variable:
 * ┌──────────┬────────────┬─────────┐
 * │ SenderID │ GroupID    │ Payload │
 * │ 8 bytes  │ 16 bytes?  │ Variable│
 * └──────────┴────────────┴─────────┘
 *
 * GroupID is the group UUID as its 16 raw bytes (most-significant 8, then least-significant 8),
 * never the 36-char string, which would not fit in the header.
 */
object BleProtocol {
    const val VERSION: Byte = 1
    const val HEADER_SIZE = 14
    const val SENDER_ID_SIZE = 8
    const val GROUP_ID_SIZE = 16
    const val BLE_MTU = 512

    // Flags
    const val FLAG_HAS_GROUP_ID: Int = 0x01
    const val FLAG_IS_COMPRESSED: Int = 0x04

    /**
     * @param groupId group UUID string; must parse with [UUID.fromString]
     * @throws IllegalArgumentException if [payload] is too large or [groupId] is not a UUID
     */
    fun encode(
        type: MessageType,
        payload: ByteArray,
        senderPubkey: String,
        groupId: String? = null,
        ttl: Byte = 7
    ): ByteArray {
        require(payload.size <= 65535) { "Payload too large for BLE protocol: ${payload.size}" }
        val groupUuid = groupId?.let { parseGroupId(it) }

        // Compress if beneficial
        val (data, compressed) =
            if (payload.size > 100) {
                val c = CompressionUtil.compress(payload)
                if (c != null && c.size < payload.size) c to true else payload to false
            } else {
                payload to false
            }

        var flags = 0
        if (groupUuid != null) flags = flags or FLAG_HAS_GROUP_ID
        if (compressed) flags = flags or FLAG_IS_COMPRESSED

        val variableSize = SENDER_ID_SIZE + (if (groupUuid != null) GROUP_ID_SIZE else 0) + data.size
        val buf = ByteBuffer.allocate(HEADER_SIZE + variableSize).order(ByteOrder.BIG_ENDIAN)

        // Header
        buf.put(VERSION)
        buf.put(type.value)
        buf.put(ttl)
        buf.putLong(System.currentTimeMillis() / 60_000 * 60_000)
        buf.put(flags.toByte())
        buf.putShort((data.size and 0xFFFF).toShort())

        // SenderID: first 8 bytes of hex pubkey decoded
        buf.put(senderPubkey.take(16).hexToBytes8())

        // GroupID: 16 raw UUID bytes
        if (groupUuid != null) {
            buf.putLong(groupUuid.mostSignificantBits)
            buf.putLong(groupUuid.leastSignificantBits)
        }

        // Payload
        buf.put(data)
        return buf.array()
    }

    fun decode(packet: ByteArray): BlePacket? {
        if (packet.size < HEADER_SIZE + SENDER_ID_SIZE) return null
        val buf = ByteBuffer.wrap(packet).order(ByteOrder.BIG_ENDIAN)

        val version = buf.get()
        if (version != VERSION) return null

        val typeByte = buf.get()
        val type = MessageType.fromValue(typeByte) ?: return null
        val ttl = buf.get()
        val timestamp = buf.getLong()
        val flags = buf.get().toInt() and 0xFF
        val payloadLen = buf.getShort().toInt() and 0xFFFF

        val hasGroupId = (flags and FLAG_HAS_GROUP_ID) != 0
        val isCompressed = (flags and FLAG_IS_COMPRESSED) != 0

        val senderId = ByteArray(SENDER_ID_SIZE)
        buf.get(senderId)

        val groupId =
            if (hasGroupId) {
                // The size check above only covers header + senderId; a packet that claims a
                // group ID without carrying one would underflow the buffer.
                if (buf.remaining() < GROUP_ID_SIZE) return null
                UUID(buf.getLong(), buf.getLong()).toString()
            } else {
                null
            }

        if (buf.remaining() < payloadLen) return null
        val rawPayload = ByteArray(payloadLen)
        buf.get(rawPayload)

        val payload =
            if (isCompressed) {
                CompressionUtil.decompress(rawPayload) ?: return null
            } else {
                rawPayload
            }

        return BlePacket(
            type = type,
            ttl = ttl,
            timestamp = timestamp,
            senderId = senderId,
            groupId = groupId,
            payload = payload
        )
    }

    /** Convert first 8 hex chars (16 nibbles → 8 bytes). Pads if shorter. */
    private fun String.hexToBytes8(): ByteArray {
        val padded = this.padEnd(16, '0')
        return ByteArray(8) { i ->
            ((Character.digit(padded[i * 2], 16) shl 4) + Character.digit(padded[i * 2 + 1], 16)).toByte()
        }
    }

    /**
     * Parse [groupId] as a canonical 36-char UUID. [UUID.fromString] alone is lenient (it accepts
     * short hex groups such as `1-2-3-4-5`), so we also require that the parsed value prints back
     * to the input; otherwise the receiver would decode a different id than the sender meant.
     *
     * @throws IllegalArgumentException if [groupId] is not a canonical UUID
     */
    private fun parseGroupId(groupId: String): UUID {
        val parsed =
            try {
                UUID.fromString(groupId)
            } catch (e: IllegalArgumentException) {
                throw IllegalArgumentException("groupId is not a UUID: $groupId", e)
            }
        require(parsed.toString().equals(groupId, ignoreCase = true)) { "groupId is not a canonical UUID: $groupId" }
        return parsed
    }
}

/**
 * BLE binary protocol message types.
 */
enum class MessageType(val value: Byte) {
    ANNOUNCE(0x01),
    EXPENSE(0x02),
    SETTLEMENT(0x03),
    EXPENSE_CORRECTION(0x04),
    EXPENSE_DELETE(0x05),
    SNAPSHOT(0x06),
    GROUP_META(0x07),
    SYNC_REQUEST(0x20),
    FRAGMENT(0x21)
    ;

    companion object {
        fun fromValue(v: Byte): MessageType? = entries.find { it.value == v }

        fun fromEventType(eventType: String): MessageType? = when (eventType) {
            "expense" -> EXPENSE
            "settlement" -> SETTLEMENT
            "expense_correction" -> EXPENSE_CORRECTION
            "expense_delete" -> EXPENSE_DELETE
            "snapshot" -> SNAPSHOT
            "group_meta" -> GROUP_META
            else -> null
        }
    }
}

/**
 * Decoded BLE binary protocol packet.
 */
data class BlePacket(
    val type: MessageType,
    val ttl: Byte,
    val timestamp: Long,
    val senderId: ByteArray,
    val groupId: String?,
    val payload: ByteArray
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is BlePacket) return false
        return type == other.type &&
            timestamp == other.timestamp &&
            senderId.contentEquals(other.senderId) &&
            payload.contentEquals(other.payload)
    }

    override fun hashCode(): Int = type.hashCode() * 31 + timestamp.hashCode()
}

/**
 * Fragments large messages into BLE_MTU-sized chunks and reassembles them.
 * Fragments are keyed by (endpointId, UUID) to isolate reassembly per peer.
 */
object FragmentManager {
    private const val FRAGMENT_HEADER_SIZE = 20 // 16 (UUID) + 2 (index) + 2 (total)

    fun fragment(data: ByteArray): List<ByteArray> {
        if (data.size <= BleProtocol.BLE_MTU) return listOf(data)

        val chunkSize = BleProtocol.BLE_MTU - FRAGMENT_HEADER_SIZE
        val totalFragments = (data.size + chunkSize - 1) / chunkSize
        val messageId = UUID.randomUUID()
        val msb = messageId.mostSignificantBits
        val lsb = messageId.leastSignificantBits

        return (0 until totalFragments).map { i ->
            val start = i * chunkSize
            val end = minOf(start + chunkSize, data.size)
            val chunk = data.copyOfRange(start, end)

            val buf = ByteBuffer.allocate(FRAGMENT_HEADER_SIZE + chunk.size).order(ByteOrder.BIG_ENDIAN)
            buf.putLong(msb)
            buf.putLong(lsb)
            buf.putShort(i.toShort())
            buf.putShort(totalFragments.toShort())
            buf.put(chunk)
            buf.array()
        }
    }

    /** Partial reassembly state for one (endpoint, message id). */
    private class Reassembly(val total: Int, var lastSeen: Long) {
        val frags = HashMap<Int, ByteArray>()

        /** Running byte total of [frags], so the size bound is enforced before buffering, not after. */
        var size = 0
    }

    // Insertion-ordered so that ties on lastSeen evict the earliest-started message first.
    private val pending = LinkedHashMap<Triple<String, Long, Long>, Reassembly>()
    private const val MAX_PENDING = 20
    private const val MAX_FRAGMENTS = 256
    private const val TIMEOUT_MS = 30_000L
    private const val MAX_REASSEMBLED_SIZE = 131_072 // 128 KB — matches relay max_event_bytes

    /**
     * Buffer one fragment; returns the reassembled message when the last piece arrives.
     *
     * Hardened against a misbehaving peer: an index outside `[0, total)` is rejected outright, a
     * message whose running size would exceed [MAX_REASSEMBLED_SIZE] is discarded as soon as the
     * offending chunk arrives (not after buffering all of it), and at most [MAX_PENDING] messages
     * are held; beyond that the least recently touched one is evicted, stale or not.
     */
    @Synchronized
    fun addFragment(endpointId: String, fragment: ByteArray): ByteArray? {
        if (fragment.size < FRAGMENT_HEADER_SIZE) return null

        val buf = ByteBuffer.wrap(fragment).order(ByteOrder.BIG_ENDIAN)
        val msb = buf.getLong()
        val lsb = buf.getLong()
        val key = Triple(endpointId, msb, lsb)
        val index = buf.getShort().toInt() and 0xFFFF
        val total = buf.getShort().toInt() and 0xFFFF
        if (total == 0 || total > MAX_FRAGMENTS) return null // sanity bound on fragment count
        if (index >= total) return null // can never complete; do not let it occupy a slot
        val chunkSize = fragment.size - FRAGMENT_HEADER_SIZE

        val now = System.currentTimeMillis()
        val entry =
            pending[key] ?: run {
                evictStale(now)
                if (pending.size >= MAX_PENDING) evictOldest()
                Reassembly(total, now).also { pending[key] = it }
            }
        if (entry.total != total) {
            // The peer changed its mind about the fragment count; nothing consistent can be built.
            pending.remove(key)
            return null
        }

        val replacedSize = entry.frags[index]?.size ?: 0 // a retransmit replaces, not adds
        val newSize = entry.size - replacedSize + chunkSize
        if (newSize > MAX_REASSEMBLED_SIZE) {
            pending.remove(key) // reject oversized payloads and free what was buffered so far
            return null
        }

        val chunk = ByteArray(chunkSize)
        buf.get(chunk)
        entry.frags[index] = chunk
        entry.size = newSize
        entry.lastSeen = now

        if (entry.frags.size < entry.total) return null
        pending.remove(key)
        val assembled = ByteArray(entry.size)
        var offset = 0
        for (i in 0 until entry.total) {
            val part = entry.frags[i] ?: return null
            part.copyInto(assembled, offset)
            offset += part.size
        }
        return assembled
    }

    private fun evictStale(now: Long) {
        pending.entries.removeAll { now - it.value.lastSeen > TIMEOUT_MS }
    }

    private fun evictOldest() {
        val oldest = pending.entries.minByOrNull { it.value.lastSeen }?.key ?: return
        pending.remove(oldest)
    }

    @Synchronized
    fun clear() {
        pending.clear()
    }
}
