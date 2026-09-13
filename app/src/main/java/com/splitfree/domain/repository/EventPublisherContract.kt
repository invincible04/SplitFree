package com.splitfree.domain.repository

import com.splitfree.domain.crypto.NostrEvent
import com.splitfree.domain.model.group.Group

/**
 * Domain contract for event publishing (local persistence + relay dispatch).
 * Handles local persistence and relay dispatch of signed Nostr events.
 */
interface EventPublisherContract {
    /**
     * Save event locally and publish with per-member NIP-59 gift wrapping (if enabled).
     *
     * @param event signed Nostr event
     * @param groupId target group UUID
     * @param encrypted NIP-44 encrypted content
     * @param eventType event type tag value (e.g. `expense`, `group_meta`)
     * @param expenseUuid optional expense UUID
     */
    suspend fun publishToGroup(
        event: NostrEvent,
        groupId: String,
        encrypted: String,
        eventType: String,
        expenseUuid: String? = null
    )

    /**
     * Atomically saves a new expense and all prepared deliveries against the validated group snapshot.
     * Returns false if this author already saved the logical expense; the caller must compare its payload.
     */
    suspend fun publishExpense(event: NostrEvent, group: Group, expenseUuid: String): Boolean

    /**
     * Atomically saves a money mutation (`expense_correction`, `expense_delete` or `settlement`) and all
     * prepared deliveries against the validated group snapshot.
     *
     * [commandId] is the id under which [event] is addressed on relays; a command this author already saved
     * in [group] is not saved again and the call returns false so the caller can compare payloads. For a
     * correction or deletion [expectedRevisionId] must be the current revision of the author's expense
     * [expenseUuid] at commit time, otherwise the save is refused with [ExpenseRevisionConflictException]
     * and nothing is persisted. A settlement carries no revision.
     */
    suspend fun publishMutation(
        event: NostrEvent,
        group: Group,
        eventType: String,
        expenseUuid: String,
        commandId: String,
        expectedRevisionId: String? = null
    ): Boolean

    /**
     * Persists a newly created [group], its [groupKey], the creation `group_meta` [event] and its delivery
     * in one transaction. Returns false without writing anything when a group with the same id already
     * exists; the caller reconciles against the stored group.
     */
    suspend fun publishCreatedGroup(event: NostrEvent, group: Group, groupKey: String): Boolean

    /** True if [author] has a stored `group_meta` in [groupId] addressed under creation command [commandId]. */
    suspend fun hasCreatedGroupCommand(groupId: String, author: String, commandId: String): Boolean

    /**
     * Save event locally and publish directly (no gift wrap).
     */
    suspend fun publishDirect(
        event: NostrEvent,
        groupId: String,
        encrypted: String,
        eventType: String,
        expenseUuid: String? = null
    )

    /**
     * Save event locally and enqueue in outbox only (no immediate relay publish).
     * Used for snapshots.
     */
    suspend fun saveAndQueue(
        event: NostrEvent,
        groupId: String,
        encrypted: String,
        eventType: String,
        expenseUuid: String? = null
    )

    /** @return true if any outbox event JSON matches [predicate] */
    suspend fun hasOutboxMatching(predicate: (String) -> Boolean): Boolean

    /** @return true if any of the given event IDs are still in the outbox */
    suspend fun hasOutboxEventsById(eventIds: List<String>): Boolean

    /**
     * Re-deliver every gift-wrapped event this identity authored in [groupId] to [recipients].
     *
     * NIP-59 wraps are addressed to the members present at publish time, so anyone who joins
     * later cannot decrypt them and never receives that history. Called when new members appear
     * in a `group_meta`; each of my `expense`/`settlement`/`expense_correction`/`expense_delete`/
     * `snapshot` events is wrapped once per recipient and queued in the outbox. Events published
     * direct (`group_meta`, `key_rotation`, `key_revocation`) are already readable from relays.
     *
     * No-op when gift wrap is disabled or [recipients] is empty. Self is always excluded.
     *
     * @return the number of wraps queued
     */
    suspend fun redeliverAuthoredEvents(groupId: String, recipients: Collection<String>): Int
}

class OutboxFullException : IllegalStateException("Delivery queue is full. Try saving again after syncing")
