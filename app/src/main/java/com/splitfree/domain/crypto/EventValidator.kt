package com.splitfree.domain.crypto

import java.util.concurrent.ConcurrentHashMap

/**
 * Validates event timestamps, payload authenticity, and rate limits.
 * Rejects events with timestamps too far in the future (>1 hour)
 * to prevent timestamp manipulation attacks on event ordering.
 */
object EventValidator {
    private const val MAX_FUTURE_SECS = 3600L       // 1 hour
    private const val MAX_AGE_SECS = 30L * 86400L   // 30 days
    private const val MAX_CONTENT_BYTES = 65_536     // 64 KB — reject oversized event content
    private const val MAX_JSON_DEPTH = 32            // reject deeply nested JSON (stack overflow DoS)

    /** Max events per pubkey per minute before rate-limiting kicks in. */
    private const val RATE_LIMIT_PER_MINUTE = 30
    private const val RATE_WINDOW_MS = 60_000L
    private const val GROUP_RATE_LIMIT_PER_MINUTE = 60

    private val rateCounts = ConcurrentHashMap<String, RateEntry>()
    private val groupRateCounts = ConcurrentHashMap<String, RateEntry>()

    private class RateEntry {
        @Volatile var count: Int = 0
        @Volatile var windowStart: Long = 0L
    }

    /**
     * Standard validation: rejects future timestamps and events older than 30 days.
     * Use for incremental sync (SyncWorker, ForegroundSyncService).
     */
    fun isTimestampValid(createdAtSecs: Long): Boolean {
        val now = System.currentTimeMillis() / 1000
        return createdAtSecs in (now - MAX_AGE_SECS)..(now + MAX_FUTURE_SECS)
    }

    /**
     * Lenient validation: only rejects future timestamps, allows any age.
     * Use for initial sync (JoinGroupUseCase) and full sync (MidnightSyncWorker)
     * where we intentionally pull historical events.
     */
    fun isTimestampValidLenient(createdAtSecs: Long): Boolean {
        val now = System.currentTimeMillis() / 1000
        return createdAtSecs <= now + MAX_FUTURE_SECS
    }

    /**
     * Validates that expense_correction and expense_delete events come from the
     * same pubkey that created the original expense. Prevents a malicious member
     * from modifying or deleting someone else's expense.
     *
     * For regular expenses and settlements, any group member can create them
     * on behalf of anyone (the paidBy/from field is the payer, not the author).
     * This is by design — it's a social trust model, same as Splitwise.
     *
     * @param originalCreatorPubkey pubkey of the original expense creator (from DB), or null if not found
     * @return true if the event is valid, false if it should be rejected
     */
    fun isCorrectionAuthorValid(eventType: String, eventPubkey: String, originalCreatorPubkey: String?): Boolean {
        return when (eventType) {
            "expense_correction", "expense_delete" -> {
                // Reject corrections/deletions when the original expense hasn't been synced yet.
                // They will be re-processed on the next sync when the original arrives.
                if (originalCreatorPubkey == null) false
                else eventPubkey == originalCreatorPubkey
            }
            else -> true
        }
    }

    /**
     * Per-pubkey rate limiting. Returns true if the pubkey is within limits.
     * Resets the window every RATE_WINDOW_MS. Evicts stale entries periodically.
     * Uses synchronized blocks to prevent TOCTOU race conditions.
     */
    fun isWithinRateLimit(pubkey: String): Boolean {
        val now = System.currentTimeMillis()
        // Evict stale entries every ~100 calls to bound memory
        if (rateCounts.size > 100) {
            rateCounts.entries.removeIf { entry ->
                synchronized(entry.value) { now - entry.value.windowStart > RATE_WINDOW_MS * 2 }
            }
        }
        val entry = rateCounts.getOrPut(pubkey) { RateEntry() }
        synchronized(entry) {
            if (now - entry.windowStart > RATE_WINDOW_MS) {
                entry.count = 1
                entry.windowStart = now
                return true
            }
            entry.count++
            return entry.count <= RATE_LIMIT_PER_MINUTE
        }
    }

    /** Clear rate limit state (for testing). */
    fun resetRateLimits() {
        rateCounts.clear()
        groupRateCounts.clear()
    }

    /**
     * Per-group rate limiting. Returns true if the group is within limits.
     */
    fun isWithinGroupRateLimit(groupId: String): Boolean {
        val now = System.currentTimeMillis()
        if (groupRateCounts.size > 100) {
            groupRateCounts.entries.removeIf { entry ->
                synchronized(entry.value) { now - entry.value.windowStart > RATE_WINDOW_MS * 2 }
            }
        }
        val entry = groupRateCounts.getOrPut(groupId) { RateEntry() }
        synchronized(entry) {
            if (now - entry.windowStart > RATE_WINDOW_MS) {
                entry.count = 1
                entry.windowStart = now
                return true
            }
            entry.count++
            return entry.count <= GROUP_RATE_LIMIT_PER_MINUTE
        }
    }

    /**
     * Check if an expense event references a UUID that has been deleted.
     * Prevents replay of deleted expenses by malicious relays.
     */
    fun isDeletedExpense(eventType: String, expenseUuid: String?, deletedUuids: Set<String>): Boolean {
        if (eventType != "expense" || expenseUuid == null) return false
        return expenseUuid in deletedUuids
    }

    /**
     * Validate that group_meta updates come from the group creator.
     * Prevents any group member from rewriting the member list or group name.
     * Returns true if the event should be accepted.
     */
    fun isGroupMetaAuthorValid(authorPubkey: String, groupCreator: String?): Boolean {
        if (groupCreator == null) return true // new group, no creator yet
        return authorPubkey == groupCreator
    }

    /**
     * Reject expense events backdated before the last settlement.
     * Prevents balance manipulation via strategic timestamp reordering.
     * Returns true if the event timestamp is acceptable.
     */
    fun isNotBackdatedBeforeSettlement(eventCreatedAt: Long, lastSettlementTimestamp: Long?): Boolean {
        if (lastSettlementTimestamp == null) return true
        return eventCreatedAt >= lastSettlementTimestamp
    }

    /**
     * Validate event content before deserialization.
     * Rejects oversized content (OOM) and deeply nested JSON (stack overflow).
     * Must be called before any Json.decodeFromString on untrusted event content.
     */
    fun isContentSafe(content: String?): Boolean {
        if (content == null) return false
        if (content.length > MAX_CONTENT_BYTES) return false
        var depth = 0
        for (c in content) {
            when (c) {
                '{', '[' -> if (++depth > MAX_JSON_DEPTH) return false
                '}', ']' -> depth--
            }
        }
        return true
    }
}
