package com.splitfree.domain.repository

/** Public intent, pre-signed envelopes and authenticated projection payloads; secret keys remain in secure storage. */
data class ControlOperation(val id: String, val kind: String, val intentJson: String, val preparedJson: String? = null)

interface ControlOperationJournalContract {
    suspend fun get(id: String): ControlOperation?

    suspend fun getAll(kind: String): List<ControlOperation>

    /** Insert-only: an existing intent may never be retargeted. */
    suspend fun insert(operation: ControlOperation)

    /** Compare-and-set from unprepared; every retry must use the stored signed envelopes. */
    suspend fun prepare(id: String, preparedJson: String)

    /**
     * Compare-and-set the source snapshot while no prepared plan is stored.
     * Callers must preserve the operation's target and must not have published an unjournaled plan.
     */
    suspend fun rebase(id: String, expectedIntentJson: String, intentJson: String)

    /**
     * Compare-and-set a prepared plan. Callers may append deliveries or projection data but must
     * preserve existing signed envelopes; the journal does not inspect the plan's contents.
     */
    suspend fun amend(id: String, expectedPreparedJson: String, preparedJson: String)

    /** Atomic compare-and-set move; archiving never loses the original intent or signed envelopes. */
    suspend fun move(operation: ControlOperation, id: String, kind: String)

    suspend fun complete(id: String)
}
