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

    /** @return every stored row of [eventType] in a group, oldest first, regardless of apply state */
    suspend fun getEventsByType(groupId: String, eventType: String): List<EventSnapshot>

    /** @return total number of events stored for a group */
    suspend fun getEventCount(groupId: String): Int

    /**
     * Insert an event only if its ID is not already stored.
     * @return true if the event was new and inserted
     */
    suspend fun insertIfNew(snapshot: EventSnapshot): Boolean

    /**
     * Insert an event, silently skipping it if its ID is already stored (insert-or-ignore).
     * Use [insertIfNew] when the caller needs to know whether the row was actually written.
     */
    suspend fun insert(snapshot: EventSnapshot)

    /** Run [block] inside a database transaction. */
    suspend fun <T> withTransaction(block: suspend () -> T): T
}

/**
 * Lightweight projection of a stored event; avoids domain depending on Room entities.
 *
 * @property eventId Nostr event ID (SHA-256 hex)
 * @property groupId UUID of the group this event belongs to
 * @property pubkey author's public key hex
 * @property contentEncrypted NIP-44 encrypted payload
 * @property eventType one of `expense`, `settlement`, `group_meta`, `key_rotation`, `key_revocation`, `snapshot`
 * @property expenseUuid optional expense/settlement UUID for dedup and correction tracking
 * @property sig the event's own NIP-01 signature, or `seal:<sig>` when the row is an unsigned NIP-59
 *   rumor whose author was authenticated by the seal signature at receipt time (see [SEAL_SIG_PREFIX])
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
) {
    companion object {
        /**
         * Marker prefix for [sig] when the stored event arrived as a NIP-59 rumor. Rumors are
         * unsigned by design, so the receiver records the seal's signature (which it verified
         * against the author's pubkey) instead. Such a row is trustworthy locally (and in a
         * backup this device produced) but cannot be re-verified by a third party, so it must
         * not be forwarded as if it were a signed event.
         */
        const val SEAL_SIG_PREFIX = "seal:"

        /**
         * True if [sig] is a real NIP-01 signature that any peer can verify against the event's
         * `originalEventJson`. Empty signatures and [SEAL_SIG_PREFIX] markers are not.
         */
        fun isThirdPartyVerifiable(sig: String): Boolean = sig.isNotEmpty() && !sig.startsWith(SEAL_SIG_PREFIX)

        /**
         * Canonical ordering for deciding which of two events is "earlier"/"later": `createdAt`, then
         * `eventId` as a deterministic tie-breaker. Callers must never rely on storage order instead.
         */
        val CANONICAL_ORDER: Comparator<EventSnapshot> = compareBy({ it.createdAt }, { it.eventId })
    }
}
