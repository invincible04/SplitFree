package com.splitfree.sync.nearby

/**
 * Observer-facing phase controlled by the session engine.
 * [UP_TO_DATE], [WAITING_DEPENDENCY] and [INCOMPLETE] keep the session open for further reconciliation.
 * [UNSUPPORTED_PEER], [AUTH_FAILED], [UNAUTHORIZED], [INTERRUPTED] and [CLOSED] are terminal.
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

    /** Both directions finished without failures; at least one side reports durable pending work. */
    WAITING_DEPENDENCY,

    /** Both directions finished without failures or pending work, and the local pending count is readable. */
    UP_TO_DATE,

    /** Reconciliation finished with failures on either side or an unreadable local pending count. */
    INCOMPLETE,

    /** Protocol versions are incompatible. */
    UNSUPPORTED_PEER,

    /** Identity or transcript verification failed. */
    AUTH_FAILED,

    /** Group authorization failed on either side, or the local identity changed. */
    UNAUTHORIZED,

    /** Transport dropped or timed out mid-session; durable progress is kept. */
    INTERRUPTED,

    /** Session closed for another reason, including an explicit stop or protocol violation. */
    CLOSED
}

/**
 * Transfer and outcome counters for one connection; retries can count a record more than once.
 * [deferred] is the last readable durable pending count for the group, including work across sessions.
 */
data class TransferStats(
    val sent: Int = 0,
    val received: Int = 0,
    val applied: Int = 0,
    val alreadyApplied: Int = 0,
    val upgraded: Int = 0,
    val deferred: Int = 0,
    val rejected: Int = 0,
    val carried: Int = 0,
    val busy: Int = 0,
    /** Unresolved requests and snapshot timeouts; a snapshot timeout contributes at least one. */
    val unresolved: Int = 0,
    /** Applied records the peer holds as rumors only, which it cannot forward and this phone lacks. */
    val held: Int = 0
) {
    val hasFailures: Boolean get() = rejected > 0 || busy > 0 || unresolved > 0 || held > 0
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

/** Coordinator snapshot containing live peers and retained terminal progress, keyed by transport endpoint id. */
data class NearbySessionsState(
    val active: Boolean = false,
    val groupId: String? = null,
    val peers: Map<String, PeerProgress> = emptyMap()
)
