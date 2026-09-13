package com.splitfree.sync.nearby

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * In-memory [ReconciliationStore] for protocol tests. Events are opaque JSON strings keyed by id;
 * deliveries are opaque envelopes addressed to a recipient. Behaviour can be scripted per id through
 * [outcomes] and [rejectIfUnauthorized].
 */
class FakeReconciliationStore(val me: String, val members: MutableSet<String>, val creator: String = "") :
    ReconciliationStore {
    class Event(val json: String, val verifiable: Boolean, var applied: Boolean = true)

    class Delivery(val recipient: String, val json: String, val eventHint: String?, var consumed: Boolean = false)

    val events = LinkedHashMap<String, Event>()
    val deliveries = LinkedHashMap<String, Delivery>()
    val ingested = mutableListOf<String>()
    val outcomes = HashMap<String, RecordOutcome>()

    /** Scripted rejections in here are malformed or unauthorized, not waiting for a dependency. */
    val permanentRejects = HashSet<String>()
    var controlIds = HashSet<String>()

    /** Durable pending rows: grows on a scripted DEFERRED ingest, shrinks by what [retryDeferred] reports. */
    var deferredPending = 0
    var pendingReadFails = false
    var retryReturns = 0
    var retryCalls = 0

    /** Stand-in for the store's change stream; tests bump it to simulate a local write. */
    val changes = MutableStateFlow(StoreVersion(0))
    var loadFailures = HashSet<String>()
    var pruned = 0
    var ownJoin: String? = null
    var admitJoinResult = false
    var authorizedOverride: ((String) -> Boolean)? = null

    fun putEvent(id: String, json: String = """{"id":"$id"}""", verifiable: Boolean = true) {
        events[id] = Event(json, verifiable)
    }

    fun putDelivery(id: String, recipient: String, json: String = """{"env":"$id"}""", hint: String? = null) {
        deliveries[id] = Delivery(recipient, json, hint)
    }

    override suspend fun inventory(groupId: String): List<InventoryItem> =
        events.filter { it.value.verifiable && it.value.applied }.map { InventoryItem(it.key, NearbyWire.KIND_EVENT) } +
            deliveries.filter { !it.value.consumed }.map {
                InventoryItem(it.key, NearbyWire.KIND_DELIVERY, r = it.value.recipient, e = it.value.eventHint)
            } +
            events.filter {
                !it.value.verifiable && it.value.applied
            }.map { InventoryItem(it.key, NearbyWire.KIND_HELD) }

    override suspend fun loadRecord(groupId: String, item: InventoryItem): LoadedRecord? {
        if (item.id in loadFailures) return null
        return when (item.t) {
            NearbyWire.KIND_EVENT -> events[item.id]?.takeIf { it.verifiable }?.let { LoadedRecord(item.t, it.json) }
            NearbyWire.KIND_DELIVERY -> deliveries[item.id]?.takeIf {
                !it.consumed
            }?.let { LoadedRecord(item.t, it.json) }
            else -> null
        }
    }

    override suspend fun selectWanted(groupId: String, items: List<InventoryItem>, peerPubkey: String) =
        items.filter { item ->
            when (item.t) {
                NearbyWire.KIND_EVENT -> {
                    val local = events[item.id]
                    (local == null || !local.verifiable) && item.id !in deliveries
                }

                NearbyWire.KIND_DELIVERY -> {
                    val r = item.r ?: return@filter false
                    if (item.id in deliveries || item.id in events) return@filter false
                    if (r == me) item.e == null || item.e !in events else r in members
                }

                else -> false
            }
        }

    override suspend fun ingest(
        groupId: String,
        item: InventoryItem,
        recordJson: String,
        peerPubkey: String
    ): IngestReport {
        ingested += item.id
        outcomes[item.id]?.let {
            if (it == RecordOutcome.DEFERRED) deferredPending++
            // A scripted rejection stands for a missing key or join unless the test says it is permanent.
            return IngestReport(it, retryable = it == RecordOutcome.REJECTED && item.id !in permanentRejects)
        }
        return when (item.t) {
            NearbyWire.KIND_EVENT -> {
                val existing = events[item.id]
                if (existing == null) {
                    events[item.id] = Event(recordJson, verifiable = true)
                    IngestReport(RecordOutcome.APPLIED, controlApplied = item.id in controlIds)
                } else if (!existing.verifiable) {
                    events[item.id] = Event(recordJson, verifiable = true)
                    IngestReport(RecordOutcome.ALREADY_APPLIED, upgraded = true)
                } else {
                    IngestReport(RecordOutcome.ALREADY_APPLIED)
                }
            }

            NearbyWire.KIND_DELIVERY -> {
                val r = item.r ?: return IngestReport(RecordOutcome.REJECTED)
                if (r == me) {
                    deliveries[item.id] = Delivery(r, "", item.e, consumed = true)
                    item.e?.let { events.putIfAbsent(it, Event("{}", verifiable = false)) }
                    IngestReport(RecordOutcome.APPLIED)
                } else if (r in members) {
                    deliveries[item.id] = Delivery(r, recordJson, item.e)
                    IngestReport(RecordOutcome.CARRIED)
                } else {
                    IngestReport(RecordOutcome.REJECTED)
                }
            }

            else -> IngestReport(RecordOutcome.REJECTED)
        }
    }

    override suspend fun isAuthorizedForGroup(groupId: String, peerPubkey: String): Boolean =
        authorizedOverride?.invoke(peerPubkey)
            ?: (peerPubkey in members || (creator.isNotEmpty() && peerPubkey == creator))

    override suspend fun admitJoin(groupId: String, peerPubkey: String, joinEventJson: String): Boolean {
        if (admitJoinResult) members += peerPubkey
        return admitJoinResult
    }

    override suspend fun retryDeferred(groupId: String): Int {
        retryCalls++
        val n = retryReturns
        retryReturns = 0
        deferredPending = (deferredPending - n).coerceAtLeast(0)
        return n
    }

    override suspend fun countMissing(groupId: String, ids: Collection<String>): Int = ids.count { it !in events }

    override suspend fun pendingCount(groupId: String): Int {
        check(!pendingReadFails) { "pending state unavailable" }
        return deferredPending
    }

    override fun observeChanges(groupId: String): Flow<StoreVersion> = changes

    override suspend fun ownJoinEvent(groupId: String): String? = ownJoin

    override suspend fun prune() {
        pruned++
    }
}
