package com.splitfree.sync.event

import com.splitfree.data.local.dao.EventDao
import com.splitfree.data.local.dao.OutboxDao
import com.splitfree.data.local.entities.EventEntity
import com.splitfree.data.local.entities.OutboxEntity
import com.splitfree.data.nostr.EventThrottler
import com.splitfree.domain.crypto.GiftWrapService
import com.splitfree.domain.crypto.NostrEvent
import com.splitfree.domain.crypto.NostrKind
import com.splitfree.domain.repository.EventPublisherContract
import com.splitfree.domain.repository.GroupRepositoryContract
import com.splitfree.domain.repository.IdentityContract
import com.splitfree.util.DebugLog as Log
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Persists signed events locally and enqueues them for relay publication,
 * with optional per-member NIP-59 gift wrapping.
 */
@Singleton
class EventPublisher
@Inject
constructor(
    private val eventDao: EventDao,
    private val outboxDao: OutboxDao,
    private val throttler: EventThrottler,
    private val giftWrap: GiftWrapService,
    private val groupRepo: GroupRepositoryContract,
    private val identity: IdentityContract
) : EventPublisherContract {
    /**
     * Save event locally and enqueue for publishing with per-member NIP-59 gift wrapping.
     *
     * @param event signed Nostr event
     * @param groupId target group UUID
     * @param encrypted NIP-44 encrypted content (stored in EventEntity)
     * @param eventType event type tag value
     * @param expenseUuid optional expense UUID
     */
    override suspend fun publishToGroup(
        event: NostrEvent,
        groupId: String,
        encrypted: String,
        eventType: String,
        expenseUuid: String?
    ) {
        saveEvent(event, groupId, encrypted, eventType, expenseUuid)

        if (giftWrap.enabled) {
            val members = groupRepo.getMembers(groupId)
            val myPubkey = identity.getPublicKeyHex()
            for (memberPubHex in members.filter { it != myPubkey }.shuffled()) {
                val wrapped = giftWrap.wrapIfEnabled(event, memberPubHex)
                enqueueOutbox(wrapped)
                throttler.enqueue(wrapped)
            }
        } else {
            enqueueOutbox(event)
            throttler.enqueue(event)
        }
    }

    /**
     * Save event locally and enqueue for direct publishing (no gift wrap).
     */
    override suspend fun publishDirect(
        event: NostrEvent,
        groupId: String,
        encrypted: String,
        eventType: String,
        expenseUuid: String?
    ) {
        saveEvent(event, groupId, encrypted, eventType, expenseUuid)
        enqueueOutbox(event)
        throttler.enqueue(event)
    }

    /**
     * Save event locally and enqueue outbox only (no throttler). For snapshots.
     */
    override suspend fun saveAndQueue(
        event: NostrEvent,
        groupId: String,
        encrypted: String,
        eventType: String,
        expenseUuid: String?
    ) {
        saveEvent(event, groupId, encrypted, eventType, expenseUuid)
        enqueueOutbox(event)
    }

    private suspend fun saveEvent(
        event: NostrEvent,
        groupId: String,
        encrypted: String,
        eventType: String,
        expenseUuid: String?
    ) {
        val epoch = groupRepo.getById(groupId)?.keyEpoch ?: 0
        eventDao.insert(
            EventEntity(
                eventId = event.id,
                groupId = groupId,
                pubkey = event.pubkey,
                createdAt = event.createdAt,
                kind = NostrKind.APP_SPECIFIC,
                contentEncrypted = encrypted,
                eventType = eventType,
                expenseUuid = expenseUuid,
                sig = event.sig,
                receivedAt = System.currentTimeMillis() / 1000,
                originalEventJson = event.toJson(),
                keyEpoch = epoch
            )
        )
    }

    private suspend fun enqueueOutbox(event: NostrEvent) {
        if (outboxDao.count() < MAX_OUTBOX_SIZE) {
            outboxDao.insert(
                OutboxEntity(
                    eventId = event.id,
                    eventJson = event.toJson(),
                    createdAt = event.createdAt
                )
            )
        } else {
            Log.w(TAG, "Outbox full ($MAX_OUTBOX_SIZE), event ${event.id.take(8)} deferred to self-heal")
        }
    }

    companion object {
        private const val TAG = "EventPublisher"
        private const val MAX_OUTBOX_SIZE = 5000
    }

    /** Check if outbox contains events matching a predicate on the JSON. */
    override suspend fun hasOutboxMatching(predicate: (String) -> Boolean): Boolean =
        outboxDao.getAll().any { predicate(it.eventJson) }

    override suspend fun hasOutboxEventsById(eventIds: List<String>): Boolean =
        eventIds.isNotEmpty() && outboxDao.countByEventIds(eventIds) > 0
}
