package com.splitfree.domain.validation

import com.splitfree.domain.model.expense.Expense
import com.splitfree.domain.model.expense.Settlement
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Validates event timestamps, payload authenticity, rate limits, and remote
 * expense/settlement payloads.
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
        private const val MAX_EXPENSE_AMOUNT = 1_000_000_000_000L
        private const val RATE_LIMIT_PER_MINUTE = 30
        private const val RATE_WINDOW_MS = 60_000L
        private const val GROUP_RATE_LIMIT_PER_MINUTE = 60
    }

    private class RateEntry {
        var count: Int = 0
        var windowStart: Long = 0L
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
        val entry = rateCounts.computeIfAbsent(pubkey) { RateEntry() }
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
        val entry = groupRateCounts.computeIfAbsent(groupId) { RateEntry() }
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
     * Reject content exceeding 65,536 characters or with JSON nesting deeper than 32 levels.
     * Brackets inside `"`-delimited strings (honoring `\` escapes) do not count toward depth.
     *
     * @param content decrypted event content
     * @return true if content passes safety checks
     */
    fun isContentSafe(content: String?): Boolean {
        if (content == null) return false
        if (content.length > MAX_CONTENT_BYTES) return false
        var depth = 0
        var inString = false
        var escaped = false
        for (c in content) {
            if (inString) {
                when {
                    escaped -> escaped = false
                    c == '\\' -> escaped = true
                    c == '"' -> inString = false
                }
                continue
            }
            when (c) {
                '"' -> inString = true
                '{', '[' -> if (++depth > MAX_JSON_DEPTH) return false
                '}', ']' -> depth--
            }
        }
        return true
    }

    /**
     * Validate a remote expense (or expense correction) payload against group membership.
     *
     * @param expense parsed expense payload
     * @param members current group member pubkeys
     * @return true if the expense is structurally and semantically valid
     */
    fun isExpenseValid(expense: Expense, members: Set<String>): Boolean {
        if (expense.id.isBlank()) return false
        if (expense.amount !in 1..MAX_EXPENSE_AMOUNT) return false
        if (!isCurrencyValid(expense.currency)) return false
        if (expense.splitAmong.isEmpty()) return false
        if (expense.splitAmong.any { it.share <= 0 }) return false
        if (expense.splitAmong.map { it.pubkey }.distinct().size != expense.splitAmong.size) return false
        if (expense.paidBy !in members) return false
        if (expense.splitAmong.any { it.pubkey !in members }) return false
        val total =
            try {
                expense.splitAmong.fold(0L) { acc, entry -> Math.addExact(acc, entry.share) }
            } catch (_: ArithmeticException) {
                return false
            }
        return total == expense.amount
    }

    /**
     * Validate a remote settlement payload against its author and group membership.
     *
     * @param settlement parsed settlement payload
     * @param authorHex pubkey of the event author
     * @param members current group member pubkeys
     * @return true if the settlement is structurally and semantically valid
     */
    fun isSettlementValid(settlement: Settlement, authorHex: String, members: Set<String>): Boolean {
        if (settlement.id.isBlank()) return false
        if (settlement.amount !in 1..MAX_EXPENSE_AMOUNT) return false
        if (!isCurrencyValid(settlement.currency)) return false
        if (settlement.from == settlement.to) return false
        if (settlement.from !in members || settlement.to !in members) return false
        return authorHex == settlement.from || authorHex == settlement.to
    }

    private fun isCurrencyValid(currency: String): Boolean = currency.length == 3 && currency.all { it in 'A'..'Z' }
}
