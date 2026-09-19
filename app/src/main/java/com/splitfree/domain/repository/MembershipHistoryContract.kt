package com.splitfree.domain.repository

/**
 * Known former members reconstructed from locally available authenticated history.
 */
interface MembershipHistoryContract {
    /**
     * Known non-current identities from admitted rotation facts, decryptable applied legacy rows and local
     * revocation tombstones. Excludes current members; missing history can make the result incomplete.
     *
     * Used to retain departed identities as settlement counterparties, not to authorize new activity by them.
     */
    suspend fun formerMembers(groupId: String): Set<String>

    /** Preserve readable applied legacy rotation evidence before the active personal key is discarded. */
    suspend fun retainRotationHistory(groupId: String)
}
