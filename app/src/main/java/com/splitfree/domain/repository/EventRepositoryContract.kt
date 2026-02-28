package com.splitfree.domain.repository

import kotlinx.coroutines.flow.Flow

/**
 * Domain contract for event storage operations.
 * Provides read/write access to the local event store.
 */
interface EventRepositoryContract {
    /** @return all events for a group, ordered by creation time */
    suspend fun getEventsByGroup(groupId: String): List<EventSnapshot>

    /** @return reactive stream of events for a group */
    fun observeEventsByGroup(groupId: String): Flow<List<EventSnapshot>>

    /** @return list of event IDs stored for a group */
    suspend fun getEventIds(groupId: String): List<String>

    /** @return UUIDs of soft-deleted expenses in a group */
    suspend fun getDeletedExpenseUuids(groupId: String): List<String>

    /**
     * Resolve an original expense by UUID within a specific group.
     *
     * If multiple events reuse the same UUID, the earliest deterministic event is returned.
     */
    suspend fun getExpenseByUuid(uuid: String, groupId: String): EventSnapshot?

    /** @return the most recent event of [eventType] in a group, or null */
    suspend fun getLatestEventByType(groupId: String, eventType: String): EventSnapshot?

    /** @return total number of events stored for a group */
    suspend fun getEventCount(groupId: String): Int

    /**
     * Insert an event only if its ID is not already stored.
     * @return true if the event was new and inserted
     */
    suspend fun insertIfNew(snapshot: EventSnapshot): Boolean

    /** Insert an event unconditionally. */
    suspend fun insert(snapshot: EventSnapshot)

    /** Run [block] inside a database transaction. */
    suspend fun <T> withTransaction(block: suspend () -> T): T
}

/**
 * Lightweight projection of a stored event — avoids domain depending on Room entities.
 *
 * @property eventId Nostr event ID (SHA-256 hex)
 * @property groupId UUID of the group this event belongs to
 * @property pubkey author's public key hex
 * @property contentEncrypted NIP-44 encrypted payload
 * @property eventType one of `expense`, `settlement`, `group_meta`, `key_rotation`, `key_revocation`, `snapshot`
 * @property expenseUuid optional expense/settlement UUID for dedup and correction tracking
 * @property originalEventJson original signed JSON for self-heal republishing
 */
data class EventSnapshot(
    val eventId: String,
    val groupId: String,
    val pubkey: String,
    val createdAt: Long,
    val kind: Int = 30078,
    val contentEncrypted: String,
    val eventType: String,
    val expenseUuid: String? = null,
    val sig: String = "",
    val receivedAt: Long = 0,
    val originalEventJson: String? = null,
    val keyEpoch: Int = 0
)
