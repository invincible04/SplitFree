package com.splitfree.sync.event

import androidx.room.withTransaction
import com.splitfree.data.local.AppDatabase
import com.splitfree.data.local.dao.EventDao
import com.splitfree.data.local.dao.OutboxDao
import com.splitfree.data.local.entities.EventEntity
import com.splitfree.data.local.entities.OutboxEntity
import com.splitfree.data.nostr.EventThrottler
import com.splitfree.domain.crypto.GiftWrapService
import com.splitfree.domain.crypto.NostrEvent
import com.splitfree.domain.crypto.NostrKind
import com.splitfree.domain.model.group.Group
import com.splitfree.domain.repository.EventPublisherContract
import com.splitfree.domain.repository.GroupRepositoryContract
import com.splitfree.domain.repository.IdentityContract
import com.splitfree.domain.repository.OutboxFullException
import com.splitfree.util.DebugLog as Log
import javax.inject.Inject
import javax.inject.Singleton

/** Persists signed events and their complete delivery batch before attempting relay publication. */
@Singleton
class EventPublisher
@Inject
constructor(
    private val eventDao: EventDao,
    private val outboxDao: OutboxDao,
    private val throttler: EventThrottler,
    private val giftWrap: GiftWrapService,
    private val groupRepo: GroupRepositoryContract,
    private val identity: IdentityContract,
    private val db: AppDatabase
) : EventPublisherContract {
    override suspend fun publishToGroup(
        event: NostrEvent,
        groupId: String,
        encrypted: String,
        eventType: String,
        expenseUuid: String?
    ) {
        val epoch = groupRepo.getById(groupId)?.keyEpoch ?: 0
        val wrapEnabled = giftWrap.enabled
        val members = if (wrapEnabled) groupRepo.getMembers(groupId) else emptyList()
        val deliveries = prepareDeliveries(event, members, wrapEnabled)
        val entity = eventEntity(event, groupId, encrypted, eventType, expenseUuid, epoch)
        if (commit(entity, deliveries)) dispatch(deliveries)
    }

    override suspend fun publishExpense(event: NostrEvent, group: Group, expenseUuid: String): Boolean {
        val deliveries = prepareDeliveries(event, group.members)
        val entity = eventEntity(event, group.id, event.content, "expense", expenseUuid, group.keyEpoch)
        val saved = commit(entity, deliveries, group)
        if (saved) dispatch(deliveries)
        return saved
    }

    override suspend fun publishDirect(
        event: NostrEvent,
        groupId: String,
        encrypted: String,
        eventType: String,
        expenseUuid: String?
    ) {
        val epoch = groupRepo.getById(groupId)?.keyEpoch ?: 0
        val entity = eventEntity(event, groupId, encrypted, eventType, expenseUuid, epoch)
        if (commit(entity, listOf(event))) dispatch(listOf(event))
    }

    override suspend fun saveAndQueue(
        event: NostrEvent,
        groupId: String,
        encrypted: String,
        eventType: String,
        expenseUuid: String?
    ) {
        val epoch = groupRepo.getById(groupId)?.keyEpoch ?: 0
        commit(eventEntity(event, groupId, encrypted, eventType, expenseUuid, epoch), listOf(event))
    }

    private fun prepareDeliveries(
        event: NostrEvent,
        members: List<String>,
        wrapEnabled: Boolean = giftWrap.enabled
    ): List<NostrEvent> {
        if (!wrapEnabled) return listOf(event)
        return members.distinct().filter { it != event.pubkey }.shuffled().map { member ->
            giftWrap.wrapIfEnabled(event, member).also {
                check(it.kind == NostrKind.GIFT_WRAP) { "Gift wrapping changed while preparing delivery" }
            }
        }
    }

    private suspend fun commit(
        entity: EventEntity,
        deliveries: List<NostrEvent>,
        expectedGroup: Group? = null
    ): Boolean {
        val rows = deliveries.map { OutboxEntity(it.id, it.toJson(), it.createdAt, eventType = entity.eventType) }
        check(rows.map { it.eventId }.distinct().size == rows.size) { "Duplicate prepared delivery IDs" }
        return db.withTransaction {
            if (expectedGroup != null) {
                if (eventDao.getExpenseByAuthor(checkNotNull(entity.expenseUuid), entity.groupId, entity.pubkey) !=
                    null
                ) {
                    return@withTransaction false
                }
                val current = groupRepo.getById(entity.groupId) ?: error("Group no longer exists")
                check(
                    current.keyEpoch == expectedGroup.keyEpoch &&
                        current.members.toSet() == expectedGroup.members.toSet()
                ) {
                    "Group membership or key changed while saving. Try again"
                }
                check(identity.getPublicKeyHex() == entity.pubkey && entity.pubkey in current.members) {
                    "Identity or group membership changed while saving"
                }
            }
            if (eventDao.getEvent(entity.eventId) != null) return@withTransaction false
            if (expectedGroup != null) {
                // Legacy multi-event operations cannot safely fail admission after publishing their first event.
                val additionalRows = rows.size - outboxDao.countByEventIds(rows.map { it.eventId })
                if (outboxDao.count() + additionalRows > MAX_EXPENSE_OUTBOX_SIZE) throw OutboxFullException()
            }
            check(eventDao.insert(entity) != -1L) { "Event insertion failed" }
            rows.forEach { outboxDao.insert(it) }
            true
        }
    }

    private fun eventEntity(
        event: NostrEvent,
        groupId: String,
        encrypted: String,
        eventType: String,
        expenseUuid: String?,
        epoch: Int
    ): EventEntity = EventEntity(
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

    private fun dispatch(deliveries: List<NostrEvent>) {
        deliveries.forEach { event ->
            try {
                throttler.enqueue(event)
            } catch (e: Exception) {
                // The outbox owns recovery; an opportunistic wake-up cannot undo a committed save.
                Log.w(TAG, "Immediate dispatch unavailable; delivery remains queued: ${e.javaClass.simpleName}")
            }
        }
    }

    override suspend fun hasOutboxMatching(predicate: (String) -> Boolean): Boolean =
        outboxDao.getAll().any { predicate(it.eventJson) }

    override suspend fun hasOutboxEventsById(eventIds: List<String>): Boolean =
        eventIds.isNotEmpty() && outboxDao.countByEventIds(eventIds) > 0

    companion object {
        private const val TAG = "EventPublisher"
        private const val MAX_EXPENSE_OUTBOX_SIZE = 5000
    }
}
