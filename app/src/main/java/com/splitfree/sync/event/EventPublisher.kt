package com.splitfree.sync.event

import androidx.room.withTransaction
import com.splitfree.data.local.AppDatabase
import com.splitfree.data.local.dao.DeliveryDao
import com.splitfree.data.local.dao.EventDao
import com.splitfree.data.local.dao.OutboxDao
import com.splitfree.data.local.entities.DeliveryEntity
import com.splitfree.data.local.entities.EventEntity
import com.splitfree.data.local.entities.OutboxEntity
import com.splitfree.data.nostr.EventThrottler
import com.splitfree.data.repository.ExpenseEventHistory
import com.splitfree.domain.crypto.GiftWrapService
import com.splitfree.domain.crypto.NostrEvent
import com.splitfree.domain.crypto.NostrKind
import com.splitfree.domain.model.expense.ExpenseIdentity
import com.splitfree.domain.model.group.Group
import com.splitfree.domain.repository.EventPublisherContract
import com.splitfree.domain.repository.EventSnapshot
import com.splitfree.domain.repository.GroupRepositoryContract
import com.splitfree.domain.repository.IdentityContract
import com.splitfree.domain.repository.OutboxFullException
import com.splitfree.sync.worker.OutboxDrainScheduler
import com.splitfree.util.DebugLog as Log
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Persists signed events and their complete delivery batch before attempting relay publication;
 * see [dispatch] for how a committed batch reaches the relays.
 *
 * Gift-wrapped deliveries are additionally retained in the delivery store so a member met over a
 * nearby session can carry another member's envelope when the author is offline. The stored envelope
 * is the exact ciphertext handed to relays; couriers cannot open it.
 */
@Singleton
class EventPublisher
@Inject
constructor(
    private val eventDao: EventDao,
    private val outboxDao: OutboxDao,
    private val deliveryDao: DeliveryDao,
    private val throttler: EventThrottler,
    private val drainScheduler: OutboxDrainScheduler,
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

    override suspend fun publishMutation(
        event: NostrEvent,
        group: Group,
        eventType: String,
        expenseUuid: String,
        commandId: String,
        expectedRevisionId: String?
    ): Boolean {
        require(eventType in MUTATION_TYPES) { "Not a money mutation: $eventType" }
        require(commandId.isNotBlank()) { "Command ID must not be blank" }
        require(eventType == "settlement" || !expectedRevisionId.isNullOrBlank()) { "Expense revision is required" }
        val deliveries = prepareDeliveries(event, group.members)
        val entity = eventEntity(event, group.id, event.content, eventType, expenseUuid, group.keyEpoch)
        val saved = commit(entity, deliveries, group, commandId, expectedRevisionId)
        if (saved) dispatch(deliveries)
        return saved
    }

    override suspend fun hasCreatedGroupCommand(groupId: String, author: String, commandId: String): Boolean =
        ExpenseEventHistory.command(
            eventDao.getEventsByTypeAndAuthor(groupId, "group_meta", author),
            groupId,
            author,
            "group_meta",
            commandId
        ) != null

    override suspend fun publishCreatedGroup(event: NostrEvent, group: Group, groupKey: String): Boolean {
        val saved = db.withTransaction {
            if (groupRepo.getById(group.id) != null) return@withTransaction false
            check(identity.getPublicKeyHex() == group.createdBy && event.pubkey == group.createdBy) {
                "Identity changed while creating the group"
            }
            groupRepo.save(group, groupKey)
            val entity = eventEntity(event, group.id, event.content, "group_meta", null, group.keyEpoch)
            check(commit(entity, listOf(event))) { "Creation event already exists for another group" }
            true
        }
        if (saved) dispatch(listOf(event))
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
        if (commit(eventEntity(event, groupId, encrypted, eventType, expenseUuid, epoch), listOf(event))) {
            requestDurableDrain()
        }
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

    /**
     * Persists [entity] and its delivery rows in one transaction. With [expectedGroup] the save is a money
     * mutation: it is refused when the group's roster or key epoch moved since [expectedGroup] was read,
     * capped by the outbox limit, and, given a [commandId], skipped (returning false) when this author
     * already saved that command. An [expectedRevisionId] is compared against the author's current revision
     * of the expense inside the transaction, so concurrent edits of one revision admit exactly one.
     */
    private suspend fun commit(
        entity: EventEntity,
        deliveries: List<NostrEvent>,
        expectedGroup: Group? = null,
        commandId: String? = null,
        expectedRevisionId: String? = null
    ): Boolean {
        val rows = deliveries.map { OutboxEntity(it.id, it.toJson(), it.createdAt, eventType = entity.eventType) }
        check(rows.map { it.eventId }.distinct().size == rows.size) { "Duplicate prepared delivery IDs" }
        return db.withTransaction {
            if (expectedGroup != null) {
                if (entity.eventType == "expense" &&
                    eventDao.getExpenseByAuthor(checkNotNull(entity.expenseUuid), entity.groupId, entity.pubkey) != null
                ) {
                    return@withTransaction false
                }
                if (commandId != null && savedCommand(entity, commandId) != null) return@withTransaction false
                if (expectedRevisionId != null) {
                    val uuid = checkNotNull(entity.expenseUuid)
                    ExpenseEventHistory.requireRevision(
                        eventDao.getExpenseHistoryByAuthor(uuid, entity.groupId, entity.pubkey),
                        ExpenseIdentity(entity.pubkey, uuid),
                        expectedRevisionId
                    )
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
                // Only money mutations are capped. A control operation (rotation, revocation, meta) publishes
                // several events and could not safely fail admission after its first one has gone out.
                val additionalRows = rows.size - outboxDao.countByEventIds(rows.map { it.eventId })
                if (outboxDao.count() + additionalRows > MAX_EXPENSE_OUTBOX_SIZE) throw OutboxFullException()
            }
            check(eventDao.insert(entity) != -1L) { "Event insertion failed" }
            rows.forEach { outboxDao.insert(it) }
            retainEnvelopes(entity.groupId, entity.eventId, deliveries)
            true
        }
    }

    private suspend fun savedCommand(entity: EventEntity, commandId: String): EventEntity? =
        ExpenseEventHistory.command(
            eventDao.getEventsByTypeAndAuthor(entity.groupId, entity.eventType, entity.pubkey),
            entity.groupId,
            entity.pubkey,
            entity.eventType,
            commandId
        )

    /** Keep each gift wrap so a nearby courier can hand it to its recipient later. */
    private suspend fun retainEnvelopes(groupId: String, innerEventId: String, deliveries: List<NostrEvent>) {
        val now = System.currentTimeMillis() / 1000
        for (wrap in deliveries) {
            if (wrap.kind != NostrKind.GIFT_WRAP) continue
            val recipient = wrap.tags.firstOrNull { it.size >= 2 && it[0] == "p" }?.get(1) ?: continue
            val json = wrap.toJson()
            deliveryDao.insert(
                DeliveryEntity(
                    envelopeId = wrap.id, groupId = groupId, recipient = recipient, eventId = innerEventId,
                    envelopeJson = json, eventType = DeliveryEntity.TYPE_GIFT_WRAP,
                    state = DeliveryEntity.STATE_AVAILABLE, source = DeliveryEntity.SOURCE_AUTHORED,
                    createdAt = wrap.createdAt, receivedAt = now, sizeBytes = json.toByteArray(Charsets.UTF_8).size
                )
            )
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

    /**
     * Post-commit delivery. The throttler is the immediate attempt; the drain request is what still
     * attempts the rows with a network if this process dies first. Neither can fail the save: the
     * drain retries a bounded number of times and the periodic sync covers whatever is left.
     */
    private fun dispatch(deliveries: List<NostrEvent>) {
        if (deliveries.isEmpty()) return
        deliveries.forEach { event ->
            try {
                throttler.enqueue(event)
            } catch (e: Exception) {
                // The outbox owns recovery; an opportunistic wake-up cannot undo a committed save.
                Log.w(TAG, "Immediate dispatch unavailable; delivery remains queued: ${e.javaClass.simpleName}")
            }
        }
        requestDurableDrain()
    }

    private fun requestDurableDrain() {
        try {
            drainScheduler.requestDrain()
        } catch (e: Exception) {
            Log.w(TAG, "Could not schedule outbox drain; rows remain queued: ${e.javaClass.simpleName}")
        }
    }

    override suspend fun hasOutboxMatching(predicate: (String) -> Boolean): Boolean =
        outboxDao.getAll().any { predicate(it.eventJson) }

    override suspend fun hasOutboxEventsById(eventIds: List<String>): Boolean =
        eventIds.isNotEmpty() && outboxDao.countByEventIds(eventIds) > 0

    override suspend fun redeliverAuthoredEvents(groupId: String, recipients: Collection<String>): Int {
        if (!giftWrap.enabled) return 0
        val me = identity.getPublicKeyHex()
        val targets = recipients.distinct().filter { it != me }
        if (targets.isEmpty()) return 0

        // Only events I signed myself can be re-wrapped: a `seal:` row is someone else's rumor
        // (or one I could not re-authenticate), and direct-published types are already on relays.
        val authored =
            eventDao.getEventsByGroup(groupId).filter {
                it.pubkey == me &&
                    it.eventType in REDELIVERABLE_TYPES &&
                    it.originalEventJson != null &&
                    EventSnapshot.isThirdPartyVerifiable(it.sig)
            }
        if (authored.isEmpty()) return 0

        // Wrap outside the transaction (crypto is slow), exactly as publishToGroup does.
        val deliveries =
            authored.flatMap { entity ->
                val event = NostrEvent.fromJson(checkNotNull(entity.originalEventJson)) ?: return@flatMap emptyList()
                targets.map { recipient ->
                    val wrapped = giftWrap.wrapIfEnabled(event, recipient)
                    check(wrapped.kind == NostrKind.GIFT_WRAP) { "Gift wrapping changed while preparing redelivery" }
                    Redelivery(
                        wrapped,
                        OutboxEntity(wrapped.id, wrapped.toJson(), wrapped.createdAt, eventType = entity.eventType),
                        entity.eventId
                    )
                }
            }.shuffled()

        // Every wrap has a fresh random id, so re-running this is harmless for receivers (they dedup
        // on the inner rumor id). The cap only bounds local outbox growth; the count is read inside
        // the transaction so a concurrent save cannot push us past it.
        val queued =
            db.withTransaction {
                val room = (MAX_EXPENSE_OUTBOX_SIZE - outboxDao.count()).coerceAtLeast(0)
                val admitted = if (deliveries.size <= room) deliveries else deliveries.take(room)
                admitted.forEach { outboxDao.insert(it.row) }
                admitted.forEach { retainEnvelopes(groupId, it.innerEventId, listOf(it.wrapped)) }
                admitted
            }
        if (queued.size < deliveries.size) {
            Log.w(
                TAG,
                "Outbox cap reached: queued ${queued.size}/${deliveries.size} redelivery wraps for $groupId " +
                    "(${authored.size} events x ${targets.size} recipients)"
            )
        } else {
            Log.i(TAG, "Queued ${queued.size} redelivery wraps for $groupId to ${targets.size} new member(s)")
        }
        dispatch(queued.map { it.wrapped })
        return queued.size
    }

    private data class Redelivery(val wrapped: NostrEvent, val row: OutboxEntity, val innerEventId: String)

    companion object {
        private const val TAG = "EventPublisher"
        private const val MAX_EXPENSE_OUTBOX_SIZE = 5000

        /** Money mutations that carry a command id and are admitted against the group snapshot. */
        private val MUTATION_TYPES = setOf("expense_correction", "expense_delete", "settlement")

        /** Event types that are gift-wrapped per member and therefore need re-delivery to late joiners. */
        private val REDELIVERABLE_TYPES =
            setOf("expense", "settlement", "expense_correction", "expense_delete", "snapshot")
    }
}
