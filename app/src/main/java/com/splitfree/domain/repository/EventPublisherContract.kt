package com.splitfree.domain.repository

import com.splitfree.domain.crypto.NostrEvent

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
}
