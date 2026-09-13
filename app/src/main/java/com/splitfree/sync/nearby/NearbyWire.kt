package com.splitfree.sync.nearby

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Version 2 framing: `[0x7F][type byte][UTF-8 JSON body]`, with type bytes defined by the `TYPE_*` constants.
 *
 * [decode] limits version 2 frames to [MAX_FRAME_BYTES] and classifies v1 type prefixes `0x01..0x04`
 * as incompatible. Decoding validates serialization only; [PeerSession] enforces protocol state and fields.
 * [encode] and [chunk] do not enforce encoded byte size; callers must respect the frame limit.
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

    /** Upper bound on chunks per record; the provider reports larger records as unavailable. */
    const val MAX_RECORD_PARTS = 64

    /** Upper bound on records requested but not yet resolved, per peer; also the largest [Want] a provider accepts. */
    const val MAX_INFLIGHT_RECORDS = 16

    /** Per-peer partial-record limit; starting another record at capacity produces [RecordOutcome.BUSY]. */
    const val MAX_PARTIAL_RECORDS = 4

    /** Upper bound on inventory entries accepted from one peer snapshot. */
    const val MAX_INVENTORY_ITEMS = 200_000

    // Inventory item kinds (InventoryItem.t).
    const val KIND_EVENT = "e"
    const val KIND_DELIVERY = "d"

    // Capabilities negotiated in Hello; the agreed set is bound into the auth transcript.
    const val CAP_RECONCILE_V2 = "reconcile-v2"
    const val CAP_DELIVERIES = "deliveries"

    // Close reasons: protocol constants, not user-facing text.
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

    /** Encodes a frame without checking [MAX_FRAME_BYTES]; the caller is responsible for its size. */
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

    /** Classifies input as a parsed message, an incompatible v1 prefix, or an invalid version 2 frame. */
    sealed class Decoded {
        /** Parsed message whose protocol fields and session ordering still require validation. */
        data class Message(val message: NearbyMessage) : Decoded()

        /** Input beginning with a v1 type byte (0x01..0x04), classified as incompatible. */
        object LegacyPeer : Decoded()

        /** Input that cannot be decoded as a supported frame and has no recognized v1 prefix. */
        object Invalid : Decoded()
    }

    /** Classifies a frame without throwing for malformed JSON; unknown JSON fields are ignored. */
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
     * Paginates [items] using entry-count and encoded-byte limits; empty inventory produces one final page.
     * Each item must fit in a page by itself. [pending] carries the provider's durable pending count.
     */
    fun paginate(snap: Int, delta: Boolean, items: List<InventoryItem>, pending: Int = 0): List<InventoryPage> {
        if (items.isEmpty()) {
            return listOf(
                InventoryPage(snap, 0, last = true, delta = delta, items = emptyList(), pending = pending)
            )
        }
        val pages = mutableListOf<MutableList<InventoryItem>>(mutableListOf())
        for (item in items) {
            val current = pages.last()
            current.add(item)
            if (current.size > MAX_INVENTORY_PAGE_ITEMS ||
                !fits(InventoryPage(snap, MAX_INVENTORY_ITEMS, false, delta, current, pending))
            ) {
                current.removeAt(current.size - 1)
                pages.add(mutableListOf(item))
            }
        }
        return pages.mapIndexed { index, page ->
            InventoryPage(snap, index, last = index == pages.lastIndex, delta = delta, items = page, pending = pending)
        }
    }

    /**
     * Splits record JSON by character count; UTF-8 encoding and JSON escaping can increase frame size.
     * Empty input produces no frames.
     *
     * @throws IllegalArgumentException if the record exceeds [MAX_RECORD_PARTS] chunks
     */
    fun chunk(snap: Int, id: String, kind: String, recordJson: String): List<Record> {
        val parts = recordJson.chunked(MAX_RECORD_CHUNK_CHARS)
        require(parts.size <= MAX_RECORD_PARTS) { "record too large: ${parts.size} parts" }
        return parts.mapIndexed { index, data -> Record(snap, id, kind, index, parts.size, data) }
    }

    private fun fits(message: NearbyMessage): Boolean = encode(message).size <= MAX_FRAME_BYTES
}

/** Version 2 message body; [NearbyWire] supplies the type discriminator outside the JSON. */
@Serializable
sealed class NearbyMessage

/**
 * Announces an unverified identity and handshake inputs before authentication.
 *
 * @property pubkey 64-character lowercase hex Nostr public key
 * @property nonce 64-character lowercase hex random challenge, fresh for each session
 * @property incoming whether the sender sees this connection as incoming
 * @property caps supported capabilities; only the intersection is included in the authentication transcript
 */
@Serializable
data class Hello(
    val v: Int,
    val pubkey: String,
    val nonce: String,
    val incoming: Boolean,
    val caps: List<String> = emptyList()
) : NearbyMessage()

/** Proves identity with a 128-character lowercase hex BIP-340 signature over [NearbyAuth.transcriptHash]. */
@Serializable
data class Auth(val sig: String) : NearbyMessage()

/**
 * Requests the session's single group scope after authentication.
 *
 * @property joinEvent optional signed self-join `group_meta` JSON used to establish the sender's membership
 */
@Serializable
data class OpenGroup(val groupId: String, val joinEvent: String? = null) : NearbyMessage()

/** Accepts a group scope or refuses with [NearbyWire.OPEN_REFUSED] without revealing whether the group exists. */
@Serializable
data class OpenGroupResult(val groupId: String, val ok: Boolean, val reason: String? = null) : NearbyMessage()

/**
 * One entry of a peer's inventory.
 *
 * @property id event id (for `t = "e"`) or envelope id (for `t = "d"`)
 * @property t [NearbyWire.KIND_EVENT] for a third-party-verifiable ledger event, [NearbyWire.KIND_DELIVERY]
 *   for a recipient-encrypted envelope the advertiser holds
 * @property r recipient pubkey of a delivery
 * @property e untrusted inner event id hint; a recipient can skip an envelope when that id is stored locally
 */
@Serializable
data class InventoryItem(val id: String, val t: String, val r: String? = null, val e: String? = null)

/**
 * Advertises record availability with zero-based, contiguous page numbers within a provider snapshot.
 * [delta] marks additions to acknowledged inventory; [last] completes the snapshot's pages.
 * [pending] reports the provider's durable pending count on the final page; unreadable counts report at least one.
 */
@Serializable
data class InventoryPage(
    val snap: Int,
    val page: Int,
    val last: Boolean,
    val delta: Boolean,
    val items: List<InventoryItem>,
    val pending: Int = 0
) : NearbyMessage()

/** Requests advertised ids from [snap], with at most [NearbyWire.MAX_INFLIGHT_RECORDS] ids per message. */
@Serializable
data class Want(val snap: Int, val ids: List<String>) : NearbyMessage()

/**
 * Transfers one JSON chunk for a requested id; [part] is zero-based within [parts].
 * A zero [parts] value reports an unavailable record. Receivers enforce chunk-count and character limits.
 */
@Serializable
data class Record(val snap: Int, val id: String, val kind: String, val part: Int, val parts: Int, val data: String) :
    NearbyMessage()

/** Reports a record's processing outcome within [snap]; this receipt does not acknowledge the whole snapshot. */
@Serializable
data class Result(val snap: Int, val id: String, val outcome: RecordOutcome) : NearbyMessage()

/**
 * Acknowledges consumption of [snap]; failures and deferred work can remain after this message.
 * Counts describe the connection, except [deferred], which is the group's last readable durable pending count.
 * An unreadable pending count is signaled by a nonzero [unresolved] value.
 */
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

/** Terminates the session with a [NearbyWire] `CLOSE_*` reason. */
@Serializable
data class Close(val reason: String) : NearbyMessage()

/**
 * Processing receipt for one transferred record; [DEFERRED] still requires local application.
 * [CARRIED] acknowledges local retention of an envelope for another recipient, never recipient delivery.
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
