package com.splitfree.sync.event

/**
 * Why an event is being handed to [EventProcessor]. The context selects which admission rules apply;
 * it never changes signature, membership or payload verification.
 *
 * - [LIVE]: unsolicited traffic (relay subscription, push). Per-author and per-group rate limits apply
 *   and the event must be recent (30-day window).
 * - [RECONCILIATION]: an authenticated, explicitly requested catch-up with a peer or relay history
 *   pull. The caller bounds admission (credits, bytes, outstanding records), so the in-memory rate
 *   counters are not charged, and historical timestamps are accepted (malformed and future-skewed
 *   timestamps are still rejected).
 */
enum class IngestionContext { LIVE, RECONCILIATION }

/**
 * Terminal result of applying one record locally. This is what a receiver reports back to the peer
 * that transferred the record, so the vocabulary is shared with the nearby wire protocol.
 *
 * - [APPLIED]: verified, durably stored and reflected in local state.
 * - [ALREADY_APPLIED]: safe duplicate (possibly upgraded with stronger evidence).
 * - [DEFERRED]: verified and persisted, but its effect could not be applied yet because a dependency
 *   (typically a prior key epoch) is missing. It stays retryable and is excluded from projections.
 * - [REJECTED]: invalid, unauthorized or undecryptable; never applied. A record whose side effect was
 *   permanently rejected after storage keeps a failed row for dedup but is reported here too.
 */
enum class IngestOutcome { APPLIED, ALREADY_APPLIED, DEFERRED, REJECTED }

/** Outcome of running an event's side effects after the row has been stored. */
enum class PostProcessOutcome {
    /** Side effects ran (or the event type has none). */
    APPLIED,

    /** A dependency is missing (epoch gap, unseen join); the caller must keep the row pending and retry later. */
    DEFERRED,

    /**
     * The side effect threw (storage, transient); the row stays pending and is retried. Receipt of a
     * control record never reports success until its durable effect has landed.
     */
    FAILED,

    /**
     * The side effect can never apply on this device (malformed payload, key material this device
     * cannot open, conflicting epoch key). The row is marked failed and is not retried.
     */
    REJECTED
}

/** Result of [com.splitfree.domain.usecase.group.RotateGroupKeyUseCase.handleKeyRotation]. */
enum class RotationOutcome {
    /** New epoch key installed (or membership updated for a removed self). */
    APPLIED,

    /** Rotation is for a future epoch; the previous rotation has not been applied yet. */
    DEFERRED_EPOCH_GAP,

    /** Rotation names a member this device has not seen join yet; retried once the join lands. */
    DEFERRED_MEMBERSHIP,

    /** Already at or past this epoch; a harmless replay. */
    IGNORED,

    /** Malformed, unauthorized or inconsistent with existing key material. */
    REJECTED
}
