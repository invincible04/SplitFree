package com.splitfree.domain.repository

/** Public intent and encrypted, pre-signed envelopes only; secret keys remain in secure storage. */
data class ControlOperation(val id: String, val kind: String, val intentJson: String, val preparedJson: String? = null)

interface ControlOperationJournalContract {
    suspend fun get(id: String): ControlOperation?

    suspend fun getAll(kind: String): List<ControlOperation>

    /** Insert-only: an existing intent may never be retargeted. */
    suspend fun insert(operation: ControlOperation)

    /** Compare-and-set from unprepared; every retry must use the stored signed envelopes. */
    suspend fun prepare(id: String, preparedJson: String)

    /**
     * Re-snapshots the source state of an operation that has prepared nothing: nothing has been signed
     * or published, so following the live state cannot retarget anything already sent. Refused once a
     * plan exists. The intent's target (removed member, epoch) is the caller's responsibility to keep.
     */
    suspend fun rebase(id: String, expectedIntentJson: String, intentJson: String)

    /**
     * Compare-and-set of a prepared plan. Callers only append envelopes (for a recipient the snapshot
     * did not know) or a newer metadata event; the events already in the plan are never replaced.
     */
    suspend fun amend(id: String, expectedPreparedJson: String, preparedJson: String)

    suspend fun complete(id: String)
}
