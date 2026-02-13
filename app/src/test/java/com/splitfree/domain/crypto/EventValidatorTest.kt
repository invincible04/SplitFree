package com.splitfree.domain.crypto

import org.junit.After
import org.junit.Assert.*
import org.junit.Test

/**
 * Tests for EventValidator — timestamp, correction auth, rate limiting,
 * content safety, deletion, group meta auth, and backdating checks.
 */
class EventValidatorTest {

    private fun nowSecs() = System.currentTimeMillis() / 1000

    @After
    fun tearDown() {
        EventValidator.resetRateLimits()
    }

    // --- Standard validation (isTimestampValid) ---

    @Test
    fun `accepts current timestamp`() {
        assertTrue(EventValidator.isTimestampValid(nowSecs()))
    }

    @Test
    fun `accepts timestamp 1 minute ago`() {
        assertTrue(EventValidator.isTimestampValid(nowSecs() - 60))
    }

    @Test
    fun `accepts timestamp 29 days ago`() {
        assertTrue(EventValidator.isTimestampValid(nowSecs() - 29 * 86400))
    }

    @Test
    fun `rejects timestamp 31 days ago`() {
        assertFalse(EventValidator.isTimestampValid(nowSecs() - 31 * 86400))
    }

    @Test
    fun `accepts timestamp 30 minutes in future`() {
        assertTrue(EventValidator.isTimestampValid(nowSecs() + 1800))
    }

    @Test
    fun `rejects timestamp 2 hours in future`() {
        assertFalse(EventValidator.isTimestampValid(nowSecs() + 7200))
    }

    @Test
    fun `rejects year 2030 timestamp`() {
        assertFalse(EventValidator.isTimestampValid(1893456000L))
    }

    @Test
    fun `rejects epoch 0`() {
        assertFalse(EventValidator.isTimestampValid(0L))
    }

    @Test
    fun `rejects negative timestamp`() {
        assertFalse(EventValidator.isTimestampValid(-1L))
    }

    // --- Lenient validation (isTimestampValidLenient) ---

    @Test
    fun `lenient accepts current timestamp`() {
        assertTrue(EventValidator.isTimestampValidLenient(nowSecs()))
    }

    @Test
    fun `lenient accepts 1 year old timestamp`() {
        assertTrue(EventValidator.isTimestampValidLenient(nowSecs() - 365 * 86400))
    }

    @Test
    fun `lenient accepts epoch 0`() {
        assertTrue(EventValidator.isTimestampValidLenient(0L))
    }

    @Test
    fun `lenient rejects 2 hours in future`() {
        assertFalse(EventValidator.isTimestampValidLenient(nowSecs() + 7200))
    }

    @Test
    fun `lenient accepts 30 minutes in future`() {
        assertTrue(EventValidator.isTimestampValidLenient(nowSecs() + 1800))
    }

    // --- Boundary: exactly at the 1-hour future limit ---

    @Test
    fun `boundary - exactly 1 hour future is accepted`() {
        assertTrue(EventValidator.isTimestampValid(nowSecs() + 3600))
    }

    @Test
    fun `boundary - 1 hour + 1 second future is rejected`() {
        assertFalse(EventValidator.isTimestampValid(nowSecs() + 3601))
    }

    // --- isCorrectionAuthorValid ---

    @Test
    fun `correction by same author is valid`() {
        assertTrue(EventValidator.isCorrectionAuthorValid("expense_correction", "alice", "alice"))
    }

    @Test
    fun `correction by different author is rejected`() {
        assertFalse(EventValidator.isCorrectionAuthorValid("expense_correction", "bob", "alice"))
    }

    @Test
    fun `deletion by same author is valid`() {
        assertTrue(EventValidator.isCorrectionAuthorValid("expense_delete", "alice", "alice"))
    }

    @Test
    fun `deletion by different author is rejected`() {
        assertFalse(EventValidator.isCorrectionAuthorValid("expense_delete", "bob", "alice"))
    }

    @Test
    fun `correction with null original creator is rejected`() {
        assertFalse(EventValidator.isCorrectionAuthorValid("expense_correction", "alice", null))
    }

    @Test
    fun `deletion with null original creator is rejected`() {
        assertFalse(EventValidator.isCorrectionAuthorValid("expense_delete", "alice", null))
    }

    @Test
    fun `regular expense type always valid regardless of author`() {
        assertTrue(EventValidator.isCorrectionAuthorValid("expense", "bob", "alice"))
    }

    @Test
    fun `settlement type always valid regardless of author`() {
        assertTrue(EventValidator.isCorrectionAuthorValid("settlement", "bob", "alice"))
    }

    @Test
    fun `unknown event type always valid`() {
        assertTrue(EventValidator.isCorrectionAuthorValid("group_meta", "bob", "alice"))
    }

    // --- isWithinRateLimit ---

    @Test
    fun `first event within rate limit`() {
        assertTrue(EventValidator.isWithinRateLimit("pubkey1"))
    }

    @Test
    fun `30 events within rate limit`() {
        repeat(30) { assertTrue(EventValidator.isWithinRateLimit("pubkey-rate")) }
    }

    @Test
    fun `31st event exceeds rate limit`() {
        repeat(30) { EventValidator.isWithinRateLimit("pubkey-exceed") }
        assertFalse(EventValidator.isWithinRateLimit("pubkey-exceed"))
    }

    @Test
    fun `different pubkeys have independent rate limits`() {
        repeat(30) { EventValidator.isWithinRateLimit("pubkey-a") }
        assertTrue(EventValidator.isWithinRateLimit("pubkey-b"))
    }

    @Test
    fun `resetRateLimits clears all state`() {
        repeat(30) { EventValidator.isWithinRateLimit("pubkey-reset") }
        EventValidator.resetRateLimits()
        assertTrue(EventValidator.isWithinRateLimit("pubkey-reset"))
    }

    // --- isWithinGroupRateLimit ---

    @Test
    fun `first group event within rate limit`() {
        assertTrue(EventValidator.isWithinGroupRateLimit("group1"))
    }

    @Test
    fun `60 group events within rate limit`() {
        repeat(60) { assertTrue(EventValidator.isWithinGroupRateLimit("group-rate")) }
    }

    @Test
    fun `61st group event exceeds rate limit`() {
        repeat(60) { EventValidator.isWithinGroupRateLimit("group-exceed") }
        assertFalse(EventValidator.isWithinGroupRateLimit("group-exceed"))
    }

    @Test
    fun `different groups have independent rate limits`() {
        repeat(60) { EventValidator.isWithinGroupRateLimit("group-a") }
        assertTrue(EventValidator.isWithinGroupRateLimit("group-b"))
    }

    // --- isDeletedExpense ---

    @Test
    fun `deleted expense is detected`() {
        assertTrue(EventValidator.isDeletedExpense("expense", "uuid-1", setOf("uuid-1")))
    }

    @Test
    fun `non-deleted expense is not flagged`() {
        assertFalse(EventValidator.isDeletedExpense("expense", "uuid-2", setOf("uuid-1")))
    }

    @Test
    fun `non-expense type is never flagged as deleted`() {
        assertFalse(EventValidator.isDeletedExpense("settlement", "uuid-1", setOf("uuid-1")))
    }

    @Test
    fun `null uuid is never flagged as deleted`() {
        assertFalse(EventValidator.isDeletedExpense("expense", null, setOf("uuid-1")))
    }

    @Test
    fun `empty deleted set means nothing is deleted`() {
        assertFalse(EventValidator.isDeletedExpense("expense", "uuid-1", emptySet()))
    }

    // --- isGroupMetaAuthorValid ---

    @Test
    fun `group meta from creator is valid`() {
        assertTrue(EventValidator.isGroupMetaAuthorValid("alice", "alice"))
    }

    @Test
    fun `group meta from non-creator is rejected`() {
        assertFalse(EventValidator.isGroupMetaAuthorValid("bob", "alice"))
    }

    @Test
    fun `group meta with null creator is accepted (new group)`() {
        assertTrue(EventValidator.isGroupMetaAuthorValid("alice", null))
    }

    // --- isNotBackdatedBeforeSettlement ---

    @Test
    fun `event after settlement is accepted`() {
        assertTrue(EventValidator.isNotBackdatedBeforeSettlement(200, 100))
    }

    @Test
    fun `event at settlement time is accepted`() {
        assertTrue(EventValidator.isNotBackdatedBeforeSettlement(100, 100))
    }

    @Test
    fun `event before settlement is rejected`() {
        assertFalse(EventValidator.isNotBackdatedBeforeSettlement(99, 100))
    }

    @Test
    fun `null settlement timestamp always accepts`() {
        assertTrue(EventValidator.isNotBackdatedBeforeSettlement(1, null))
    }

    // --- isContentSafe ---

    @Test
    fun `normal JSON content is safe`() {
        assertTrue(EventValidator.isContentSafe("""{"amount":100,"currency":"INR"}"""))
    }

    @Test
    fun `null content is unsafe`() {
        assertFalse(EventValidator.isContentSafe(null))
    }

    @Test
    fun `empty string is safe`() {
        assertTrue(EventValidator.isContentSafe(""))
    }

    @Test
    fun `content exceeding 64KB is unsafe`() {
        assertFalse(EventValidator.isContentSafe("x".repeat(65_537)))
    }

    @Test
    fun `content exactly 64KB is safe`() {
        assertTrue(EventValidator.isContentSafe("x".repeat(65_536)))
    }

    @Test
    fun `deeply nested JSON is unsafe`() {
        val deep = "{".repeat(33) + "}" .repeat(33)
        assertFalse(EventValidator.isContentSafe(deep))
    }

    @Test
    fun `32 levels of nesting is safe`() {
        val nested = "{".repeat(32) + "}".repeat(32)
        assertTrue(EventValidator.isContentSafe(nested))
    }

    @Test
    fun `deeply nested arrays are unsafe`() {
        val deep = "[".repeat(33) + "]".repeat(33)
        assertFalse(EventValidator.isContentSafe(deep))
    }

    @Test
    fun `mixed nesting counts together`() {
        // 20 objects + 13 arrays = 33 depth
        val deep = "{".repeat(20) + "[".repeat(13) + "]".repeat(13) + "}".repeat(20)
        assertFalse(EventValidator.isContentSafe(deep))
    }
}
