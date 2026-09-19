package com.splitfree.domain.model.group

/**
 * Locally known retirements and pre-resolved, proven successors for balance attribution in one group.
 *
 * The repository resolves successor chains before constructing this value. Missing links stop a proven chain;
 * invalid chains leave the balance on its original identity. Signed events stay unchanged, and successors
 * cannot correct or delete events authored by retired keys.
 *
 * @property revoked every identity with a revocation tombstone, replaced or not
 * @property successors retired identity -> identity that now holds its position; never maps to itself
 */
data class RetiredIdentities(val revoked: Set<String> = emptySet(), val successors: Map<String, String> = emptyMap()) {
    /** The identity that holds [pubkey]'s financial position today: its final successor, or [pubkey] itself. */
    fun resolve(pubkey: String): String = successors[pubkey] ?: pubkey

    /** True if [pubkey] has a local revocation tombstone, with or without a proven successor. */
    fun isRetired(pubkey: String): Boolean = pubkey in revoked

    companion object {
        val NONE = RetiredIdentities()
    }
}
