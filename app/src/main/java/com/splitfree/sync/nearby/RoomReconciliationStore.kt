package com.splitfree.sync.nearby

import com.splitfree.data.local.dao.DeliveryDao
import com.splitfree.data.local.dao.EventDao
import com.splitfree.data.local.dao.SyncRevisionDao
import com.splitfree.data.local.entities.DeliveryEntity
import com.splitfree.data.local.entities.EventEntity
import com.splitfree.domain.crypto.NostrEvent
import com.splitfree.domain.crypto.NostrKind
import com.splitfree.domain.repository.EventSnapshot
import com.splitfree.domain.repository.GroupRepositoryContract
import com.splitfree.domain.repository.IdentityContract
import com.splitfree.sync.event.EventProcessor
import com.splitfree.sync.event.IngestOutcome
import com.splitfree.sync.event.IngestionContext
import com.splitfree.util.DebugLog as Log
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * Room-backed reconciliation using [EventProcessor] in [IngestionContext.RECONCILIATION].
 * Local recipients' records are processed within the session's group scope. Envelopes for other members
 * are retained opaque under per-group quotas; carriage confirms retention only, not recipient application.
 * Signed evidence can replace a rumor's placeholder signature without reapplying its effect.
 */
@Singleton
class RoomReconciliationStore
@Inject
constructor(
    private val eventDao: EventDao,
    private val deliveryDao: DeliveryDao,
    private val eventProcessor: EventProcessor,
    private val groupRepo: GroupRepositoryContract,
    private val identity: IdentityContract,
    private val syncRevisionDao: SyncRevisionDao
) : ReconciliationStore {
    override suspend fun inventory(groupId: String): List<InventoryItem> {
        val controlIds =
            CONTROL_TYPES.flatMapTo(HashSet()) { type -> eventDao.getEventsByType(groupId, type).map { it.eventId } }
        val (control, ledger) =
            eventDao.getEventEvidence(groupId)
                .filter { EventSnapshot.isThirdPartyVerifiable(it.sig) }
                .map { InventoryItem(it.eventId, NearbyWire.KIND_EVENT) }
                .partition { it.id in controlIds }
        val (keyEnvelopes, otherEnvelopes) =
            deliveryDao.getAvailable(groupId)
                .map { InventoryItem(it.envelopeId, NearbyWire.KIND_DELIVERY, r = it.recipient, e = it.eventId) to it }
                .partition { (_, row) -> row.eventType == DeliveryEntity.TYPE_KEY_ROTATION }
        // Applied records this phone cannot vouch for to anyone else, money and control alike; listed so
        // the peer can tell its ledger differs. Nothing else on the wire compares rosters or epochs. A
        // rumor-only key_rotation is the exception (see EventDao.getHeldEventIds): another recipient's
        // copy of the same epoch is a different event id, so it would never hold this row.
        val held = eventDao.getHeldEventIds(groupId).map { InventoryItem(it, NearbyWire.KIND_HELD) }
        // Prioritize key and membership records so consumers can resolve dependencies early.
        return control + keyEnvelopes.map { it.first } + ledger + otherEnvelopes.map { it.first } + held
    }

    override suspend fun countMissing(groupId: String, ids: Collection<String>): Int {
        if (ids.isEmpty()) return 0
        val known = eventDao.getEventIds(groupId).toHashSet()
        return ids.count { it !in known }
    }

    override suspend fun loadRecord(groupId: String, item: InventoryItem): LoadedRecord? = when (item.t) {
        NearbyWire.KIND_EVENT -> {
            val row = eventDao.getEvent(item.id) ?: return null
            val json = row.originalEventJson ?: return null
            if (row.groupId != groupId || row.applyState == EventEntity.APPLY_STATE_FAILED) return null
            if (!EventSnapshot.isThirdPartyVerifiable(row.sig)) return null
            LoadedRecord(NearbyWire.KIND_EVENT, json)
        }

        NearbyWire.KIND_DELIVERY -> {
            val row = deliveryDao.get(item.id) ?: return null
            val json = row.envelopeJson ?: return null
            if (row.groupId != groupId || row.state != DeliveryEntity.STATE_AVAILABLE) return null
            LoadedRecord(NearbyWire.KIND_DELIVERY, json)
        }

        else -> null
    }

    override suspend fun selectWanted(
        groupId: String,
        items: List<InventoryItem>,
        peerPubkey: String
    ): List<InventoryItem> {
        val evidence = eventDao.getEventEvidence(groupId).associate { it.eventId to it.sig }
        val knownIds = eventDao.getEventIds(groupId).toHashSet()
        val envelopes = deliveryDao.getEnvelopeIds(groupId).toHashSet()
        val me = identity.getPublicKeyHex()
        val members = groupRepo.getById(groupId)?.members?.toSet() ?: emptySet()
        // Bound carriage requests per snapshot; carry() enforces cache quotas with oldest-first eviction.
        var newCarried = 0
        val wanted = ArrayList<InventoryItem>()
        for (item in items) {
            when (item.t) {
                NearbyWire.KIND_EVENT -> {
                    if (item.id in envelopes) continue
                    val sig = evidence[item.id]
                    when {
                        sig == null -> if (item.id !in knownIds) wanted += item
                        !EventSnapshot.isThirdPartyVerifiable(sig) -> wanted += item // proof upgrade
                    }
                }

                // KIND_HELD is never wanted: the advertiser cannot serve it. Its count is taken separately.
                NearbyWire.KIND_DELIVERY -> {
                    val recipient = item.r ?: continue
                    if (item.id in envelopes || item.id in knownIds) continue
                    if (recipient == me) {
                        if (item.e != null && item.e in knownIds) continue
                        wanted += item
                    } else if (recipient in members && newCarried < MAX_CARRIED_PER_GROUP) {
                        newCarried++
                        wanted += item
                    }
                }
            }
        }
        return wanted
    }

    override suspend fun ingest(
        groupId: String,
        item: InventoryItem,
        recordJson: String,
        peerPubkey: String
    ): IngestReport {
        val event = NostrEvent.fromJson(recordJson) ?: return rejected("unparseable")
        if (event.id != item.id) return rejected("id mismatch")
        if (!event.verify()) return rejected("bad signature")
        val me = identity.getPublicKeyHex()
        val gTag = event.tag("g")
        val pTag = event.tag("p")
        val tTag = event.tag("t")
        return when (item.t) {
            NearbyWire.KIND_EVENT -> {
                if (event.kind != NostrKind.APP_SPECIFIC || gTag != groupId) return rejected("out of scope")
                if (tTag == "key_rotation" && pTag != null && pTag != me) {
                    // A recipient-addressed rotation remains opaque even when advertised as a ledger event.
                    return carry(event, recordJson, groupId, pTag, DeliveryEntity.TYPE_KEY_ROTATION)
                }
                applyOwn(event, recordJson, groupId, tTag)
            }

            NearbyWire.KIND_DELIVERY -> {
                val recipient = pTag ?: return rejected("no recipient")
                if (recipient != item.r) return rejected("recipient mismatch")
                val type =
                    when {
                        event.kind == NostrKind.GIFT_WRAP -> DeliveryEntity.TYPE_GIFT_WRAP
                        event.kind == NostrKind.APP_SPECIFIC && tTag == "key_rotation" ->
                            DeliveryEntity.TYPE_KEY_ROTATION
                        else -> return rejected("not an envelope")
                    }
                // The outer group tag is signed (by the creator for a rotation, by the wrap key for a gift
                // wrap that copies it from the rumor); an envelope for another group is never stored, nor
                // advertised, under this session's group.
                if (gTag != groupId) return rejected("out of scope")
                if (recipient == me) {
                    // The processor checks the unwrapped event's group before accessing group-scoped records.
                    val report =
                        applyOwn(
                            event,
                            recordJson,
                            groupId,
                            tTag,
                            knownGroupId = if (type == DeliveryEntity.TYPE_KEY_ROTATION) groupId else null
                        )
                    if (report.outcome != RecordOutcome.REJECTED) {
                        deliveryDao.insertIfNew(
                            DeliveryEntity(
                                envelopeId = event.id, groupId = groupId, recipient = recipient, eventId = null,
                                envelopeJson = null, eventType = type, state = DeliveryEntity.STATE_CONSUMED,
                                source = DeliveryEntity.SOURCE_CARRIED, createdAt = event.createdAt,
                                receivedAt = now(), sizeBytes = 0
                            )
                        )
                    }
                    report
                } else {
                    carry(event, recordJson, groupId, recipient, type)
                }
            }

            else -> rejected("unknown kind")
        }
    }

    private suspend fun applyOwn(
        event: NostrEvent,
        recordJson: String,
        groupId: String,
        eventType: String?,
        knownGroupId: String? = groupId
    ): IngestReport {
        val result = eventProcessor.process(
            event,
            knownGroupId = knownGroupId,
            context = IngestionContext.RECONCILIATION,
            expectedGroupId = groupId
        )
        return when (result.outcome) {
            IngestOutcome.APPLIED -> IngestReport(
                RecordOutcome.APPLIED,
                controlApplied =
                result.eventType in CONTROL_TYPES
            )
            IngestOutcome.DEFERRED -> IngestReport(RecordOutcome.DEFERRED)
            IngestOutcome.REJECTED -> rejected(result.reason ?: "rejected", retryable = result.retryable)
            IngestOutcome.ALREADY_APPLIED -> {
                val id = result.eventId ?: event.id
                val existing = eventDao.getEvent(id)
                val upgraded =
                    existing != null &&
                        !EventSnapshot.isThirdPartyVerifiable(existing.sig) &&
                        EventSnapshot.isThirdPartyVerifiable(event.sig) &&
                        event.kind == NostrKind.APP_SPECIFIC &&
                        // The verified content hash binds this signature upgrade to the stored event's identity.
                        eventDao.upgradeEvidence(id, event.sig, recordJson) == 1
                if (upgraded) Log.i(TAG, "Upgraded evidence for ${id.take(8)} in $groupId")
                IngestReport(RecordOutcome.ALREADY_APPLIED, upgraded = upgraded)
            }
        }
    }

    private suspend fun carry(
        event: NostrEvent,
        recordJson: String,
        groupId: String,
        recipient: String,
        type: String
    ): IngestReport {
        val group = groupRepo.getById(groupId) ?: return rejected("unknown group")
        if (recipient !in group.members) return rejected("recipient not a member")
        val size = recordJson.toByteArray(Charsets.UTF_8).size
        if (size > MAX_ENVELOPE_BYTES) return rejected("envelope too large")
        val existing = deliveryDao.get(event.id)
        if (existing != null) {
            // A receipt says this group's inventory holds the envelope for this recipient. A row that
            // says otherwise is filed under the wrong group; the signed tags are the proof of scope,
            // so it is refiled rather than vouched for.
            if (existing.groupId == groupId &&
                existing.recipient == recipient
            ) {
                return IngestReport(RecordOutcome.CARRIED)
            }
            Log.w(
                TAG,
                "Refiling envelope ${event.id.take(8)} from group ${existing.groupId.take(8)} to ${groupId.take(8)}"
            )
            deliveryDao.delete(event.id)
        }
        var evictions = 0
        while (
            deliveryDao.countCarried(groupId) >= MAX_CARRIED_PER_GROUP ||
            deliveryDao.carriedBytes(groupId) + size > MAX_CARRIED_BYTES_PER_GROUP
        ) {
            if (evictions++ >= MAX_EVICTIONS_PER_INSERT) return IngestReport(RecordOutcome.BUSY)
            deliveryDao.evictOldestCarried(groupId, 1)
        }
        deliveryDao.insertIfNew(
            DeliveryEntity(
                envelopeId = event.id, groupId = groupId, recipient = recipient, eventId = null,
                envelopeJson = recordJson, eventType = type, state = DeliveryEntity.STATE_AVAILABLE,
                source = DeliveryEntity.SOURCE_CARRIED, createdAt = event.createdAt, receivedAt = now(),
                sizeBytes = size
            )
        )
        return IngestReport(RecordOutcome.CARRIED)
    }

    override suspend fun isAuthorizedForGroup(groupId: String, peerPubkey: String): Boolean {
        val group = groupRepo.getById(groupId) ?: return false
        return peerPubkey in group.members || (group.createdBy.isNotEmpty() && peerPubkey == group.createdBy)
    }

    override suspend fun admitJoin(groupId: String, peerPubkey: String, joinEventJson: String): Boolean {
        val event = NostrEvent.fromJson(joinEventJson) ?: return false
        if (event.pubkey != peerPubkey || event.kind != NostrKind.APP_SPECIFIC) return false
        if (event.tag("g") != groupId || event.tag("t") != "group_meta") return false
        if (!event.verify()) return false
        eventProcessor.process(
            event,
            knownGroupId = groupId,
            context = IngestionContext.RECONCILIATION,
            expectedGroupId = groupId
        )
        return isAuthorizedForGroup(groupId, peerPubkey)
    }

    override suspend fun retryDeferred(groupId: String): Int = eventProcessor.retryDeferred(groupId)

    override suspend fun pendingCount(groupId: String): Int = eventDao.countPending(groupId)

    override fun observeChanges(groupId: String): Flow<StoreVersion> =
        syncRevisionDao.observeRevision(groupId).map { StoreVersion(it) }

    override suspend fun ownJoinEvent(groupId: String): String? {
        val me = identity.getPublicKeyHex()
        val group = groupRepo.getById(groupId) ?: return null
        if (group.createdBy == me || me !in group.members) return null
        return eventDao.getEventsByType(groupId, "group_meta")
            .lastOrNull {
                it.pubkey == me &&
                    EventSnapshot.isThirdPartyVerifiable(it.sig) &&
                    it.originalEventJson != null
            }
            ?.originalEventJson
    }

    override suspend fun prune() {
        val now = now()
        deliveryDao.deleteOlderThan(now - AUTHORED_RETENTION_SECS, DeliveryEntity.SOURCE_AUTHORED)
        deliveryDao.deleteOlderThan(now - CARRIED_RETENTION_SECS, DeliveryEntity.SOURCE_CARRIED)
    }

    private fun rejected(reason: String, retryable: Boolean = false): IngestReport {
        Log.w(TAG, "Rejected record: $reason")
        return IngestReport(RecordOutcome.REJECTED, retryable = retryable)
    }

    private fun NostrEvent.tag(name: String): String? = tags.firstOrNull { it.size >= 2 && it[0] == name }?.get(1)

    private fun now(): Long = System.currentTimeMillis() / 1000

    companion object {
        private const val TAG = "NearbyStore"
        private val CONTROL_TYPES = setOf("key_rotation", "group_meta", "key_revocation")

        // Per-group quotas apply to available carried envelopes; MAX_ENVELOPE_BYTES limits one UTF-8 JSON payload.
        // MAX_EVICTIONS_PER_INSERT bounds eviction work; exceeding it reports RecordOutcome.BUSY.
        const val MAX_CARRIED_PER_GROUP = 512
        const val MAX_CARRIED_BYTES_PER_GROUP = 4L * 1024 * 1024
        const val MAX_ENVELOPE_BYTES = 64 * 1024
        private const val MAX_EVICTIONS_PER_INSERT = 16

        // Retention uses local receipt time: authored envelopes 90 days, carried envelopes 30 days.
        const val AUTHORED_RETENTION_SECS = 90L * 86_400
        const val CARRIED_RETENTION_SECS = 30L * 86_400
    }
}
