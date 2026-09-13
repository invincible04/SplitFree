package com.splitfree.sync.nearby

/**
 * Where one peer session is. The UI maps these to text; the engine owns the transitions.
 *
 * Terminal phases: [UP_TO_DATE] and [INCOMPLETE] describe a finished round (the session stays open
 * and starts another round when new data appears); [CLOSED] and its more specific siblings mean the
 * connection is gone.
 */
enum class PeerPhase {
    /** Transport connected, Hello sent, waiting for the peer's Hello/Auth. */
    AUTHENTICATING,

    /** Mutual authentication complete, negotiating the group scope. */
    OPENING_GROUP,

    /** Exchanging inventories. */
    COMPARING,

    /** Records are moving in at least one direction. */
    TRANSFERRING,

    /** All requested records handled; some are deferred on a missing key or dependency. */
    WAITING_DEPENDENCY,

    /** Both snapshots consumed with nothing rejected, busy or unresolved. */
    UP_TO_DATE,

    /** A round finished with rejected, busy or unresolved records; a retry is available. */
    INCOMPLETE,

    /** Peer speaks a protocol version we do not support. */
    UNSUPPORTED_PEER,

    /** Peer failed identity or channel binding. */
    AUTH_FAILED,

    /** Peer is not authorised for this group (or we are not, from their view). */
    UNAUTHORIZED,

    /** Transport dropped or timed out mid-session; durable progress is kept. */
    INTERRUPTED,

    /** Closed for any other reason (we stopped, protocol violation). */
    CLOSED
}

/**
 * Per-record counters for one session. Counts are for the current connection only, except
 * [deferred], which mirrors the store's durable pending count for the group (work left over from an
 * earlier session or a restart included) because completion must be derived from durable state.
 */
data class TransferStats(
    val sent: Int = 0,
    val received: Int = 0,
    val applied: Int = 0,
    val alreadyApplied: Int = 0,
    val upgraded: Int = 0,
    /** Rows stored in this group whose effect has not landed yet (durable, not per-session). */
    val deferred: Int = 0,
    val rejected: Int = 0,
    val carried: Int = 0,
    val busy: Int = 0,
    /** Requested records that never produced a terminal result. */
    val unresolved: Int = 0
) {
    val hasFailures: Boolean get() = rejected > 0 || busy > 0 || unresolved > 0
}

/** Snapshot of one peer session for observers. */
data class PeerProgress(
    val endpointId: String,
    val peerPubkey: String?,
    val phase: PeerPhase,
    val groupId: String?,
    val stats: TransferStats = TransferStats(),
    /** Set on terminal phases: the [NearbyWire] close reason. */
    val closeReason: String? = null
)

data class NearbySessionsState(
    val active: Boolean = false,
    val groupId: String? = null,
    val peers: Map<String, PeerProgress> = emptyMap()
)
