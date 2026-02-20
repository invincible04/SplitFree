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
 * │ 8 bytes  │ 8 bytes?   │ Variable│
 * └──────────┴────────────┴─────────┘
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

    fun encode(
        type: MessageType,
        payload: ByteArray,
        senderPubkey: String,
        groupId: String? = null,
        ttl: Byte = 7
    ): ByteArray {
        require(payload.size <= 65535) { "Payload too large for BLE protocol: ${payload.size}" }

        // Compress if beneficial
        val (data, compressed) =
            if (payload.size > 100) {
                val c = CompressionUtil.compress(payload)
                if (c != null && c.size < payload.size) c to true else payload to false
            } else {
                payload to false
            }

        var flags = 0
        if (groupId != null) flags = flags or FLAG_HAS_GROUP_ID
        if (compressed) flags = flags or FLAG_IS_COMPRESSED

        val variableSize = SENDER_ID_SIZE + (if (groupId != null) GROUP_ID_SIZE else 0) + data.size
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

        // GroupID
        if (groupId != null) {
            buf.put(groupId.toByteArray(Charsets.UTF_8).copyOf(GROUP_ID_SIZE))
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
                val gid = ByteArray(GROUP_ID_SIZE)
                buf.get(gid)
                String(gid, Charsets.UTF_8).trimEnd('\u0000')
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

    private val pending =
        java.util.concurrent.ConcurrentHashMap<Triple<String, Long, Long>, MutableMap<Int, ByteArray>>()
    private val totalCounts = java.util.concurrent.ConcurrentHashMap<Triple<String, Long, Long>, Int>()
    private val timestamps = java.util.concurrent.ConcurrentHashMap<Triple<String, Long, Long>, Long>()
    private const val MAX_PENDING = 20
    private const val TIMEOUT_MS = 30_000L
    private const val MAX_REASSEMBLED_SIZE = 131_072 // 128 KB — matches relay max_event_bytes

    @Synchronized
    fun addFragment(endpointId: String, fragment: ByteArray): ByteArray? {
        if (fragment.size < FRAGMENT_HEADER_SIZE) return null

        // Evict stale entries
        val now = System.currentTimeMillis()
        if (pending.size > MAX_PENDING) {
            pending.keys.forEach { k ->
                if (now - (timestamps[k] ?: 0) > TIMEOUT_MS) {
                    pending.remove(k)
                    totalCounts.remove(k)
                    timestamps.remove(k)
                }
            }
        }

        val buf = ByteBuffer.wrap(fragment).order(ByteOrder.BIG_ENDIAN)
        val msb = buf.getLong()
        val lsb = buf.getLong()
        val key = Triple(endpointId, msb, lsb)
        val index = buf.getShort().toInt() and 0xFFFF
        val total = buf.getShort().toInt() and 0xFFFF
        if (total == 0 || total > 256) return null // sanity bound on fragment count
        val chunk = ByteArray(fragment.size - FRAGMENT_HEADER_SIZE)
        buf.get(chunk)

        val frags = pending.getOrPut(key) { mutableMapOf() }
        totalCounts[key] = total
        timestamps[key] = System.currentTimeMillis()
        frags[index] = chunk

        if (frags.size == total) {
            pending.remove(key)
            totalCounts.remove(key)
            timestamps.remove(key)
            val totalSize = frags.values.sumOf { it.size }
            if (totalSize > MAX_REASSEMBLED_SIZE) return null // reject oversized payloads
            val assembled = ByteArray(totalSize)
            var offset = 0
            for (i in 0 until total) {
                val part = frags[i] ?: return null
                part.copyInto(assembled, offset)
                offset += part.size
            }
            return assembled
        }
        return null
    }

    @Synchronized
    fun clear() {
        pending.clear()
        totalCounts.clear()
        timestamps.clear()
    }
}
