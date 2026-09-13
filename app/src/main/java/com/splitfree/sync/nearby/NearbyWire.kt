package com.splitfree.sync.nearby

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Nearby wire protocol, version 2.
 *
 * Every frame is `[0x7F][type byte][UTF-8 JSON body]`. The leading magic byte is deliberately
 * outside the legacy `0x01..0x04` discriminator range so a v1 peer ignores our frames and we can
 * recognise theirs and close with an explicit "unsupported peer" reason instead of silently
 * degrading.
 *
 * Message vocabulary (see `docs/nearby-sync-protocol.md`):
 *
 * | Type | Message | Purpose |
 * |---|---|---|
 * | 0x01 | [Hello] | version, capabilities, identity, nonce, connection role |
 * | 0x02 | [Auth] | Schnorr signature over the channel-bound transcript |
 * | 0x03 | [OpenGroup] | authorise the intended group scope |
 * | 0x04 | [OpenGroupResult] | accept or refuse the scope (one reason for every refusal) |
 * | 0x05 | [InventoryPage] | bounded page of record/evidence availability for one snapshot |
 * | 0x06 | [Want] | request missing records or delivery forms from a snapshot |
 * | 0x07 | [Record] | bounded chunk of one record |
 * | 0x08 | [Result] | applied / duplicate / deferred / rejected / busy / carried receipt |
 * | 0x09 | [ReconcileResult] | the consumer is done with a snapshot; counts and unresolved |
 * | 0x0A | [Close] | explicit terminal reason |
 *
 * Framing limits are implementation defaults chosen well inside the pinned
 * `ConnectionsClient.MAX_BYTES_DATA_SIZE` (1,047,552 bytes); they are validated by tests, not
 * inherited from the obsolete 32 KiB `Connections` constant.
 */
object NearbyWire {
    const val PROTOCOL_VERSION = 2
    const val FRAME_MAGIC: Byte = 0x7F

    const val TYPE_HELLO: Byte = 0x01
    const val TYPE_AUTH: Byte = 0x02
    const val TYPE_OPEN_GROUP: Byte = 0x03
    const val TYPE_OPEN_GROUP_RESULT: Byte = 0x04
    const val TYPE_INVENTORY_PAGE: Byte = 0x05
    const val TYPE_WANT: Byte = 0x06
    const val TYPE_RECORD: Byte = 0x07
    const val TYPE_RESULT: Byte = 0x08
    const val TYPE_RECONCILE_RESULT: Byte = 0x09
    const val TYPE_CLOSE: Byte = 0x0A

    /** Upper bound on one encoded frame including the two header bytes. */
    const val MAX_FRAME_BYTES = 16 * 1024

    /** Upper bound on inventory entries per page; byte size is checked as well. */
    const val MAX_INVENTORY_PAGE_ITEMS = 256

    /** Record JSON is split into chunks of at most this many characters. */
    const val MAX_RECORD_CHUNK_CHARS = 12_000

    /** Largest record we will reassemble: MAX_RECORD_PARTS * MAX_RECORD_CHUNK_CHARS chars. */
    const val MAX_RECORD_PARTS = 64

    /** Records requested but not yet resolved, per peer. This is the receiver's transfer credit. */
    const val MAX_INFLIGHT_RECORDS = 16

    /** Records with partially received chunks, per peer. */
    const val MAX_PARTIAL_RECORDS = 4

    /** Total inventory entries we accept from one peer snapshot. */
    const val MAX_INVENTORY_ITEMS = 200_000

    const val KIND_EVENT = "e"
    const val KIND_DELIVERY = "d"

    const val CAP_RECONCILE_V2 = "reconcile-v2"
    const val CAP_DELIVERIES = "deliveries"

    /** Close reasons. Kept short; they are protocol constants, not user-facing text. */
    const val CLOSE_UNSUPPORTED_VERSION = "unsupported_version"
    const val CLOSE_AUTH_FAILED = "auth_failed"
    const val CLOSE_PROTOCOL_VIOLATION = "protocol_violation"
    const val CLOSE_TIMEOUT = "timeout"
    const val CLOSE_UNAUTHORIZED = "unauthorized"
    const val CLOSE_STOPPED = "stopped"
    const val CLOSE_PEER_DISCONNECTED = "peer_disconnected"
    const val CLOSE_TRANSPORT_ERROR = "transport_error"

    /** The single refusal reason for [OpenGroupResult]; never distinguishes unknown from unauthorized. */
    const val OPEN_REFUSED = "unauthorized"

    val json: Json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        explicitNulls = false
    }

    fun encode(message: NearbyMessage): ByteArray {
        val (type, body) =
            when (message) {
                is Hello -> TYPE_HELLO to json.encodeToString(Hello.serializer(), message)
                is Auth -> TYPE_AUTH to json.encodeToString(Auth.serializer(), message)
                is OpenGroup -> TYPE_OPEN_GROUP to json.encodeToString(OpenGroup.serializer(), message)
                is OpenGroupResult ->
                    TYPE_OPEN_GROUP_RESULT to json.encodeToString(OpenGroupResult.serializer(), message)
                is InventoryPage -> TYPE_INVENTORY_PAGE to json.encodeToString(InventoryPage.serializer(), message)
                is Want -> TYPE_WANT to json.encodeToString(Want.serializer(), message)
                is Record -> TYPE_RECORD to json.encodeToString(Record.serializer(), message)
                is Result -> TYPE_RESULT to json.encodeToString(Result.serializer(), message)
                is ReconcileResult ->
                    TYPE_RECONCILE_RESULT to json.encodeToString(ReconcileResult.serializer(), message)
                is Close -> TYPE_CLOSE to json.encodeToString(Close.serializer(), message)
            }
        val bytes = body.toByteArray(Charsets.UTF_8)
        return ByteArray(bytes.size + 2).also {
            it[0] = FRAME_MAGIC
            it[1] = type
            bytes.copyInto(it, 2)
        }
    }

    /** Result of [decode]: a message, a recognisable legacy frame, or garbage. */
    sealed class Decoded {
        data class Message(val message: NearbyMessage) : Decoded()

        /** A v1 `BleTransfer` frame (type bytes 0x01..0x04): an incompatible peer. */
        object LegacyPeer : Decoded()

        object Invalid : Decoded()
    }

    fun decode(frame: ByteArray): Decoded {
        if (frame.isEmpty()) return Decoded.Invalid
        if (frame[0] != FRAME_MAGIC) {
            return if (frame[0] in 0x01..0x04) Decoded.LegacyPeer else Decoded.Invalid
        }
        if (frame.size < 2 || frame.size > MAX_FRAME_BYTES) return Decoded.Invalid
        val body = String(frame, 2, frame.size - 2, Charsets.UTF_8)
        val message: NearbyMessage =
            try {
                when (frame[1]) {
                    TYPE_HELLO -> json.decodeFromString(Hello.serializer(), body)
                    TYPE_AUTH -> json.decodeFromString(Auth.serializer(), body)
                    TYPE_OPEN_GROUP -> json.decodeFromString(OpenGroup.serializer(), body)
                    TYPE_OPEN_GROUP_RESULT -> json.decodeFromString(OpenGroupResult.serializer(), body)
                    TYPE_INVENTORY_PAGE -> json.decodeFromString(InventoryPage.serializer(), body)
                    TYPE_WANT -> json.decodeFromString(Want.serializer(), body)
                    TYPE_RECORD -> json.decodeFromString(Record.serializer(), body)
                    TYPE_RESULT -> json.decodeFromString(Result.serializer(), body)
                    TYPE_RECONCILE_RESULT -> json.decodeFromString(ReconcileResult.serializer(), body)
                    TYPE_CLOSE -> json.decodeFromString(Close.serializer(), body)
                    else -> return Decoded.Invalid
                }
            } catch (_: IllegalArgumentException) {
                return Decoded.Invalid
            }
        return Decoded.Message(message)
    }

    /**
     * Split [pageItems] into pages that respect both [MAX_INVENTORY_PAGE_ITEMS] and
     * [MAX_FRAME_BYTES]. Pure so it can be unit-tested against the SDK contract.
     */
    fun paginate(snap: Int, delta: Boolean, items: List<InventoryItem>): List<InventoryPage> {
        if (items.isEmpty()) return listOf(InventoryPage(snap, 0, last = true, delta = delta, items = emptyList()))
        val pages = mutableListOf<MutableList<InventoryItem>>(mutableListOf())
        for (item in items) {
            val current = pages.last()
            current.add(item)
            if (current.size > MAX_INVENTORY_PAGE_ITEMS || !fits(InventoryPage(snap, 0, false, delta, current))) {
                current.removeAt(current.size - 1)
                pages.add(mutableListOf(item))
            }
        }
        return pages.mapIndexed { index, page ->
            InventoryPage(snap, index, last = index == pages.lastIndex, delta = delta, items = page)
        }
    }

    /** Split one record's JSON into [Record] frames. */
    fun chunk(snap: Int, id: String, kind: String, recordJson: String): List<Record> {
        val parts = recordJson.chunked(MAX_RECORD_CHUNK_CHARS)
        require(parts.size <= MAX_RECORD_PARTS) { "record too large: ${parts.size} parts" }
        return parts.mapIndexed { index, data -> Record(snap, id, kind, index, parts.size, data) }
    }

    private fun fits(message: NearbyMessage): Boolean = encode(message).size <= MAX_FRAME_BYTES
}

@Serializable
sealed class NearbyMessage

/**
 * @property v protocol version
 * @property pubkey 64-char hex Nostr public key
 * @property nonce 64-char hex random challenge, generated once per session
 * @property incoming the Nearby connection role as this side sees it ([ConnectionInfo.isIncomingConnection])
 * @property caps capabilities; unknown ones are ignored
 */
@Serializable
data class Hello(
    val v: Int,
    val pubkey: String,
    val nonce: String,
    val incoming: Boolean,
    val caps: List<String> = emptyList()
) : NearbyMessage()

/** @property sig 128-char hex BIP-340 signature over [NearbyAuth.transcriptHash]. */
@Serializable
data class Auth(val sig: String) : NearbyMessage()

/**
 * @property groupId the one group this session may sync
 * @property joinEvent optional signed self-join `group_meta` event JSON so a freshly invited member
 *   is not blocked merely because this phone has not yet applied its join
 */
@Serializable
data class OpenGroup(val groupId: String, val joinEvent: String? = null) : NearbyMessage()

@Serializable
data class OpenGroupResult(val groupId: String, val ok: Boolean, val reason: String? = null) : NearbyMessage()

/**
 * One entry of a peer's inventory.
 *
 * @property id event id (for `t = "e"`) or envelope id (for `t = "d"`)
 * @property t [NearbyWire.KIND_EVENT] for a third-party-verifiable ledger event, [NearbyWire.KIND_DELIVERY]
 *   for a recipient-encrypted envelope the advertiser holds
 * @property r recipient pubkey of a delivery
 * @property e inner event id hint for a delivery, known to the author; a courier passes it on
 *   untrusted so the recipient can skip an envelope for an event it already applied
 */
@Serializable
data class InventoryItem(val id: String, val t: String, val r: String? = null, val e: String? = null)

@Serializable
data class InventoryPage(
    val snap: Int,
    val page: Int,
    val last: Boolean,
    val delta: Boolean,
    val items: List<InventoryItem>
) : NearbyMessage()

@Serializable
data class Want(val snap: Int, val ids: List<String>) : NearbyMessage()

@Serializable
data class Record(val snap: Int, val id: String, val kind: String, val part: Int, val parts: Int, val data: String) :
    NearbyMessage()

@Serializable
data class Result(val snap: Int, val id: String, val outcome: RecordOutcome) : NearbyMessage()

@Serializable
data class ReconcileResult(
    val snap: Int,
    val applied: Int = 0,
    @SerialName("already") val alreadyApplied: Int = 0,
    val deferred: Int = 0,
    val rejected: Int = 0,
    val carried: Int = 0,
    val busy: Int = 0,
    val unresolved: Int = 0
) : NearbyMessage()

@Serializable
data class Close(val reason: String) : NearbyMessage()

/**
 * Terminal result of one transferred record, reported back to the sender.
 * [CARRIED] means an opaque envelope for another recipient is durably retained; it must never be
 * shown as recipient delivery.
 */
@Serializable
enum class RecordOutcome {
    APPLIED,
    ALREADY_APPLIED,
    DEFERRED,
    REJECTED,
    BUSY,
    CARRIED
}
