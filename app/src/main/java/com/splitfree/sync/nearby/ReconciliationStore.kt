package com.splitfree.sync.nearby

import kotlinx.coroutines.flow.Flow

/**
 * What the session engine needs from durable storage. Split from the engine so the state machine is
 * unit-testable against an in-memory store and so nearby ingress uses exactly the same application
 * rules as relay ingress (the Room implementation delegates to [com.splitfree.sync.event.EventProcessor]).
 */
interface ReconciliationStore {
    /**
     * Everything we can offer a peer for [groupId]: third-party-verifiable ledger events and
     * available recipient-encrypted envelopes. Rumor-only rows (a `seal:` signature) are not offered
     * because a peer could not verify them.
     */
    suspend fun inventory(groupId: String): List<InventoryItem>

    /**
     * Load one advertised record. Returns null if it is not (or no longer) available, does not belong
     * to [groupId], or cannot be verified by a third party.
     */
    suspend fun loadRecord(groupId: String, item: InventoryItem): LoadedRecord?

    /**
     * Decide which of a peer's [items] we want: unknown verifiable events, signed originals that
     * upgrade a locally held rumor, envelopes addressed to us that we have not consumed, and envelopes
     * for other members we can carry within quota.
     */
    suspend fun selectWanted(groupId: String, items: List<InventoryItem>, peerPubkey: String): List<InventoryItem>

    /** Apply one received record. Never throws for bad input; returns the outcome to report. */
    suspend fun ingest(groupId: String, item: InventoryItem, recordJson: String, peerPubkey: String): IngestReport

    /** True when [peerPubkey] may open [groupId] with us: a member, or the group's creator. */
    suspend fun isAuthorizedForGroup(groupId: String, peerPubkey: String): Boolean

    /**
     * Admit a signed self-join `group_meta` presented at OpenGroup time. Returns true if [peerPubkey]
     * is a member afterwards.
     */
    suspend fun admitJoin(groupId: String, peerPubkey: String, joinEventJson: String): Boolean

    /** Re-drive rows deferred on a dependency; returns how many were applied. */
    suspend fun retryDeferred(groupId: String): Int

    /**
     * Rows in [groupId] that are stored but whose effect has not landed yet (pending). Durable, so it
     * includes work left over from earlier sessions and process restarts; a session is not "up to
     * date" while this is non-zero.
     */
    suspend fun pendingCount(groupId: String): Int

    /**
     * Emits whenever the offerable inventory of [groupId] may have changed: applied ledger rows or
     * available envelopes (a delivery-only change, such as history re-wrapped for a new member, must
     * also reach connected peers). The first emission is the current state.
     */
    fun observeChanges(groupId: String): Flow<StoreVersion>

    /**
     * Our own signed self-join meta for [groupId], if we joined but the peer may not know yet.
     * Null when we are the creator or have no verifiable join event.
     */
    suspend fun ownJoinEvent(groupId: String): String?

    /** Drop envelopes past their retention window (authored 90 days, carried 30 days). */
    suspend fun prune()
}

data class LoadedRecord(val kind: String, val json: String)

/** Coarse version of a group's offerable inventory; any change in either count re-advertises. */
data class StoreVersion(val appliedEvents: Int, val availableEnvelopes: Int)

/**
 * @property outcome what to report to the sender
 * @property upgraded true when an existing rumor row gained a third-party-verifiable signature
 * @property controlApplied true when a key or membership record was applied, so deferred rows should be retried
 */
data class IngestReport(val outcome: RecordOutcome, val upgraded: Boolean = false, val controlApplied: Boolean = false)
