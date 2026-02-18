package com.splitfree.domain.validation

import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Validates event timestamps, payload authenticity, and rate limits.
 * Rejects events with timestamps too far in the future (>1 hour)
 * to prevent timestamp manipulation attacks on event ordering.
 */
@Singleton
class EventValidator
@Inject
constructor() {
    private val rateCounts = ConcurrentHashMap<String, RateEntry>()
    private val groupRateCounts = ConcurrentHashMap<String, RateEntry>()

    companion object {
        private const val MAX_FUTURE_SECS = 3600L
        private const val MAX_AGE_SECS = 30L * 86400L
        private const val MAX_CONTENT_BYTES = 65_536
        private const val MAX_JSON_DEPTH = 32
        private const val RATE_LIMIT_PER_MINUTE = 30
        private const val RATE_WINDOW_MS = 60_000L
        private const val GROUP_RATE_LIMIT_PER_MINUTE = 60
    }

    private class RateEntry {
        @Volatile var count: Int = 0

        @Volatile var windowStart: Long = 0L
    }

    /**
     * Standard timestamp validation: rejects future (>1h) and stale (>30d) events.
     *
     * @param createdAtSecs event's `created_at` in unix seconds
     * @return true if within the acceptable window
     */
    fun isTimestampValid(createdAtSecs: Long): Boolean {
        val now = System.currentTimeMillis() / 1000
        return createdAtSecs in (now - MAX_AGE_SECS)..(now + MAX_FUTURE_SECS)
    }

    /**
     * Lenient timestamp validation: only rejects future (>1h) events, allows any age.
     * Used for initial sync and full midnight sync where historical events are expected.
     *
     * @param createdAtSecs event's `created_at` in unix seconds
     * @return true if not too far in the future
     */
    fun isTimestampValidLenient(createdAtSecs: Long): Boolean {
        if (createdAtSecs <= 0) return false
        val now = System.currentTimeMillis() / 1000
        return createdAtSecs <= now + MAX_FUTURE_SECS
    }

    /**
     * Verify that a correction/deletion author matches the original expense creator.
     *
     * @param eventType `expense_correction` or `expense_delete`
     * @param eventPubkey pubkey of the correction/deletion event author
     * @param originalCreatorPubkey pubkey of the original expense creator, or null if not found
     * @return true if the author is allowed to modify this expense
     */
    fun isCorrectionAuthorValid(eventType: String, eventPubkey: String, originalCreatorPubkey: String?): Boolean =
        when (eventType) {
            "expense_correction", "expense_delete" -> {
                if (originalCreatorPubkey == null) {
                    false
                } else {
                    eventPubkey == originalCreatorPubkey
                }
            }

            else -> {
                true
            }
        }

    /**
     * Per-pubkey rate limit: max 30 events per minute.
     *
     * @param pubkey author's public key
     * @return true if within the rate limit
     */
    fun isWithinRateLimit(pubkey: String): Boolean {
        val now = System.currentTimeMillis()
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

    /**
     * Per-group rate limit: max 60 events per minute.
     *
     * @param groupId target group UUID
     * @return true if within the rate limit
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
     * @return true if the expense has been soft-deleted
     */
    fun isDeletedExpense(eventType: String, expenseUuid: String?, deletedUuids: Set<String>): Boolean {
        if (eventType != "expense" || expenseUuid == null) return false
        return expenseUuid in deletedUuids
    }

    fun isGroupMetaAuthorValid(authorPubkey: String, groupCreator: String?): Boolean {
        if (groupCreator == null) return true
        return authorPubkey == groupCreator
    }

    /**
     * Reject expenses/corrections backdated before the last settlement.
     *
     * @param eventCreatedAt timestamp of the incoming event
     * @param lastSettlementTimestamp timestamp of the most recent settlement, or null
     * @return true if the event is not backdated
     */
    fun isNotBackdatedBeforeSettlement(eventCreatedAt: Long, lastSettlementTimestamp: Long?): Boolean {
        if (lastSettlementTimestamp == null) return true
        return eventCreatedAt >= lastSettlementTimestamp
    }

    /**
     * Reject content exceeding 65,536 characters or with JSON nesting deeper than 32 levels.
     *
     * @param content decrypted event content
     * @return true if content passes safety checks
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
