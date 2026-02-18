package com.splitfree.domain.validation

import com.splitfree.domain.validation.EventValidator
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Tests for EventValidator — timestamp, correction auth, rate limiting,
 * content safety, deletion, group meta auth, and backdating checks.
 */
class EventValidatorTest {
    private lateinit var validator: EventValidator

    private fun nowSecs() = System.currentTimeMillis() / 1000

    @Before
    fun setUp() {
        validator = EventValidator()
    }

    // --- Standard validation (isTimestampValid) ---

    @Test
    fun `accepts current timestamp`() {
        assertTrue(validator.isTimestampValid(nowSecs()))
    }

    @Test
    fun `accepts timestamp 1 minute ago`() {
        assertTrue(validator.isTimestampValid(nowSecs() - 60))
    }

    @Test
    fun `accepts timestamp 29 days ago`() {
        assertTrue(validator.isTimestampValid(nowSecs() - 29 * 86400))
    }

    @Test
    fun `rejects timestamp 31 days ago`() {
        assertFalse(validator.isTimestampValid(nowSecs() - 31 * 86400))
    }

    @Test
    fun `accepts timestamp 30 minutes in future`() {
        assertTrue(validator.isTimestampValid(nowSecs() + 1800))
    }

    @Test
    fun `rejects timestamp 2 hours in future`() {
        assertFalse(validator.isTimestampValid(nowSecs() + 7200))
    }

    @Test
    fun `rejects year 2030 timestamp`() {
        assertFalse(validator.isTimestampValid(1893456000L))
    }

    @Test
    fun `rejects epoch 0`() {
        assertFalse(validator.isTimestampValid(0L))
    }

    @Test
    fun `rejects negative timestamp`() {
        assertFalse(validator.isTimestampValid(-1L))
    }

    // --- Lenient validation (isTimestampValidLenient) ---

    @Test
    fun `lenient accepts current timestamp`() {
        assertTrue(validator.isTimestampValidLenient(nowSecs()))
    }

    @Test
    fun `lenient accepts 1 year old timestamp`() {
        assertTrue(validator.isTimestampValidLenient(nowSecs() - 365 * 86400))
    }

    @Test
    fun `lenient accepts epoch 0`() {
        assertTrue(validator.isTimestampValidLenient(0L))
    }

    @Test
    fun `lenient rejects 2 hours in future`() {
        assertFalse(validator.isTimestampValidLenient(nowSecs() + 7200))
    }

    @Test
    fun `lenient accepts 30 minutes in future`() {
        assertTrue(validator.isTimestampValidLenient(nowSecs() + 1800))
    }

    // --- Boundary: exactly at the 1-hour future limit ---

    @Test
    fun `boundary - exactly 1 hour future is accepted`() {
        assertTrue(validator.isTimestampValid(nowSecs() + 3600))
    }

    @Test
    fun `boundary - 1 hour + 1 second future is rejected`() {
        assertFalse(validator.isTimestampValid(nowSecs() + 3601))
    }

    // --- isCorrectionAuthorValid ---

    @Test
    fun `correction by same author is valid`() {
        assertTrue(validator.isCorrectionAuthorValid("expense_correction", "alice", "alice"))
    }

    @Test
    fun `correction by different author is rejected`() {
        assertFalse(validator.isCorrectionAuthorValid("expense_correction", "bob", "alice"))
    }

    @Test
    fun `deletion by same author is valid`() {
        assertTrue(validator.isCorrectionAuthorValid("expense_delete", "alice", "alice"))
    }

    @Test
    fun `deletion by different author is rejected`() {
        assertFalse(validator.isCorrectionAuthorValid("expense_delete", "bob", "alice"))
    }

    @Test
    fun `correction with null original creator is rejected`() {
        assertFalse(validator.isCorrectionAuthorValid("expense_correction", "alice", null))
    }

    @Test
    fun `deletion with null original creator is rejected`() {
        assertFalse(validator.isCorrectionAuthorValid("expense_delete", "alice", null))
    }

    @Test
    fun `regular expense type always valid regardless of author`() {
        assertTrue(validator.isCorrectionAuthorValid("expense", "bob", "alice"))
    }

    @Test
    fun `settlement type always valid regardless of author`() {
        assertTrue(validator.isCorrectionAuthorValid("settlement", "bob", "alice"))
    }

    @Test
    fun `unknown event type always valid`() {
        assertTrue(validator.isCorrectionAuthorValid("group_meta", "bob", "alice"))
    }

    // --- isWithinRateLimit ---

    @Test
    fun `first event within rate limit`() {
        assertTrue(validator.isWithinRateLimit("pubkey1"))
    }

    @Test
    fun `30 events within rate limit`() {
        repeat(30) { assertTrue(validator.isWithinRateLimit("pubkey-rate")) }
    }

    @Test
    fun `31st event exceeds rate limit`() {
        repeat(30) { validator.isWithinRateLimit("pubkey-exceed") }
        assertFalse(validator.isWithinRateLimit("pubkey-exceed"))
    }

    @Test
    fun `different pubkeys have independent rate limits`() {
        repeat(30) { validator.isWithinRateLimit("pubkey-a") }
        assertTrue(validator.isWithinRateLimit("pubkey-b"))
    }

    @Test
    fun `fresh instance has clean rate limits`() {
        repeat(30) { validator.isWithinRateLimit("pubkey-reset") }
        val fresh = EventValidator()
        assertTrue(fresh.isWithinRateLimit("pubkey-reset"))
    }

    // --- isWithinGroupRateLimit ---

    @Test
    fun `first group event within rate limit`() {
        assertTrue(validator.isWithinGroupRateLimit("group1"))
    }

    @Test
    fun `60 group events within rate limit`() {
        repeat(60) { assertTrue(validator.isWithinGroupRateLimit("group-rate")) }
    }

    @Test
    fun `61st group event exceeds rate limit`() {
        repeat(60) { validator.isWithinGroupRateLimit("group-exceed") }
        assertFalse(validator.isWithinGroupRateLimit("group-exceed"))
    }

    @Test
    fun `different groups have independent rate limits`() {
        repeat(60) { validator.isWithinGroupRateLimit("group-a") }
        assertTrue(validator.isWithinGroupRateLimit("group-b"))
    }

    // --- isDeletedExpense ---

    @Test
    fun `deleted expense is detected`() {
        assertTrue(validator.isDeletedExpense("expense", "uuid-1", setOf("uuid-1")))
    }

    @Test
    fun `non-deleted expense is not flagged`() {
        assertFalse(validator.isDeletedExpense("expense", "uuid-2", setOf("uuid-1")))
    }

    @Test
    fun `non-expense type is never flagged as deleted`() {
        assertFalse(validator.isDeletedExpense("settlement", "uuid-1", setOf("uuid-1")))
    }

    @Test
    fun `null uuid is never flagged as deleted`() {
        assertFalse(validator.isDeletedExpense("expense", null, setOf("uuid-1")))
    }

    @Test
    fun `empty deleted set means nothing is deleted`() {
        assertFalse(validator.isDeletedExpense("expense", "uuid-1", emptySet()))
    }

    // --- Rate limit eviction ---

    @Test
    fun `rate limit evicts stale entries when over 100`() {
        repeat(101) { validator.isWithinRateLimit("evict-pubkey-$it") }
        assertTrue(validator.isWithinRateLimit("evict-pubkey-new"))
    }

    @Test
    fun `group rate limit evicts stale entries when over 100`() {
        repeat(101) { validator.isWithinGroupRateLimit("evict-group-$it") }
        assertTrue(validator.isWithinGroupRateLimit("evict-group-new"))
    }

    // --- isGroupMetaAuthorValid ---

    @Test
    fun `group meta from creator is valid`() {
        assertTrue(validator.isGroupMetaAuthorValid("alice", "alice"))
    }

    @Test
    fun `group meta from non-creator is rejected`() {
        assertFalse(validator.isGroupMetaAuthorValid("bob", "alice"))
    }

    @Test
    fun `group meta with null creator is accepted (new group)`() {
        assertTrue(validator.isGroupMetaAuthorValid("alice", null))
    }

    // --- isNotBackdatedBeforeSettlement ---

    @Test
    fun `event after settlement is accepted`() {
        assertTrue(validator.isNotBackdatedBeforeSettlement(200, 100))
    }

    @Test
    fun `event at settlement time is accepted`() {
        assertTrue(validator.isNotBackdatedBeforeSettlement(100, 100))
    }

    @Test
    fun `event before settlement is rejected`() {
        assertFalse(validator.isNotBackdatedBeforeSettlement(99, 100))
    }

    @Test
    fun `null settlement timestamp always accepts`() {
        assertTrue(validator.isNotBackdatedBeforeSettlement(1, null))
    }

    // --- isContentSafe ---

    @Test
    fun `normal JSON content is safe`() {
        assertTrue(validator.isContentSafe("""{"amount":100,"currency":"INR"}"""))
    }

    @Test
    fun `null content is unsafe`() {
        assertFalse(validator.isContentSafe(null))
    }

    @Test
    fun `empty string is safe`() {
        assertTrue(validator.isContentSafe(""))
    }

    @Test
    fun `content exceeding 64KB is unsafe`() {
        assertFalse(validator.isContentSafe("x".repeat(65_537)))
    }

    @Test
    fun `content exactly 64KB is safe`() {
        assertTrue(validator.isContentSafe("x".repeat(65_536)))
    }

    @Test
    fun `deeply nested JSON is unsafe`() {
        val deep = "{".repeat(33) + "}".repeat(33)
        assertFalse(validator.isContentSafe(deep))
    }

    @Test
    fun `32 levels of nesting is safe`() {
        val nested = "{".repeat(32) + "}".repeat(32)
        assertTrue(validator.isContentSafe(nested))
    }

    @Test
    fun `deeply nested arrays are unsafe`() {
        val deep = "[".repeat(33) + "]".repeat(33)
        assertFalse(validator.isContentSafe(deep))
    }

    @Test
    fun `mixed nesting counts together`() {
        val deep = "{".repeat(20) + "[".repeat(13) + "]".repeat(13) + "}".repeat(20)
        assertFalse(validator.isContentSafe(deep))
    }
}
