package com.splitfree.sync.nearby

import kotlinx.coroutines.flow.Flow

/**
 * Durable, group-scoped record exchange for the session engine.
 * Incoming records require verification and authorization before application; opaque carriage is a separate outcome.
 * Pending work and retained records survive session closure and participate in subsequent reconciliation.
 */
interface ReconciliationStore {
    /**
     * Lists offerable signed events, including pending records, and available recipient-encrypted envelopes.
     * Failed events and rumor-only rows with `seal:` signatures are excluded; recipients verify event evidence.
     */
    suspend fun inventory(groupId: String): List<InventoryItem>

    /**
     * Loads an advertised record in [groupId], or null if unavailable or out of scope.
     * Ledger events require third-party-verifiable evidence; delivery payloads remain recipient-encrypted.
     */
    suspend fun loadRecord(groupId: String, item: InventoryItem): LoadedRecord?

    /**
     * Selects unknown records, signature upgrades for local rumors, and envelopes eligible for receipt or carriage.
     * Carriage requests are bounded per snapshot; ingestion enforces durable storage quotas.
     */
    suspend fun selectWanted(groupId: String, items: List<InventoryItem>, peerPubkey: String): List<InventoryItem>

    /**
     * Validates and applies or retains a received record, returning the receipt for the peer.
     * Invalid records are rejected; callers must handle storage failures and cancellation.
     */
    suspend fun ingest(groupId: String, item: InventoryItem, recordJson: String, peerPubkey: String): IngestReport

    /** Returns whether the key is a member or creator of a locally known group. */
    suspend fun isAuthorizedForGroup(groupId: String, peerPubkey: String): Boolean

    /**
     * Processes signed `group_meta` from [peerPubkey] in [groupId] under the event processor's membership rules.
     * Returns whether the key is authorized after processing; malformed or mismatched input returns false.
     */
    suspend fun admitJoin(groupId: String, peerPubkey: String, joinEventJson: String): Boolean

    /** Retries durable records awaiting dependencies and returns the number applied. */
    suspend fun retryDeferred(groupId: String): Int

    /** Counts durable records awaiting application; any pending work prevents [PeerPhase.UP_TO_DATE]. */
    suspend fun pendingCount(groupId: String): Int

    /**
     * Emits the current group revision and updates for inventory, membership or apply-state changes.
     * Revisions detect changes even when row counts stay equal; collectors can coalesce intermediate updates.
     */
    fun observeChanges(groupId: String): Flow<StoreVersion>

    /**
     * Returns signed local `group_meta` for admission to the peer's group scope.
     * Returns null for the creator, a non-member, or when no verifiable local event JSON is available.
     */
    suspend fun ownJoinEvent(groupId: String): String?

    /** Prunes envelopes by local receipt time: authored retention is 90 days, carried retention is 30 days. */
    suspend fun prune()
}

/** Record JSON and its inventory kind, ready for transport chunking. */
data class LoadedRecord(val kind: String, val json: String)

/** Durable group revision for detecting inventory, membership and apply-state changes independently of row counts. */
data class StoreVersion(val revision: Long)

/**
 * Processing receipt and local changes that require further reconciliation.
 *
 * @property upgraded true when an existing rumor row gains a third-party-verifiable signature
 * @property controlApplied true when a key or membership record is applied, so deferred rows should be retried
 */
data class IngestReport(val outcome: RecordOutcome, val upgraded: Boolean = false, val controlApplied: Boolean = false)
