package com.splitfree.domain.validation

import com.splitfree.domain.model.expense.Expense
import com.splitfree.domain.model.expense.Settlement
import com.splitfree.domain.model.expense.SplitEntry
import com.splitfree.domain.model.expense.SplitType
import com.splitfree.domain.validation.EventValidator
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Tests for EventValidator: timestamp, correction auth, rate limiting,
 * content safety, deletion, group meta auth, and remote payload validation.
 */
class EventValidatorTest {
    private lateinit var validator: EventValidator

    private fun nowSecs() = System.currentTimeMillis() / 1000

    private val alice = "aa".repeat(32)
    private val bob = "bb".repeat(32)
    private val carol = "cc".repeat(32)
    private val stranger = "dd".repeat(32)
    private val members = setOf(alice, bob, carol)

    private fun expense(
        id: String = "exp-1",
        amount: Long = 100,
        currency: String = "INR",
        paidBy: String = alice,
        splitAmong: List<SplitEntry> = listOf(SplitEntry(alice, 50), SplitEntry(bob, 50))
    ) = Expense(id, amount, currency, "test", paidBy, SplitType.EQUAL, splitAmong, 1000)

    private fun settlement(
        id: String = "s-1",
        from: String = alice,
        to: String = bob,
        amount: Long = 100,
        currency: String = "INR"
    ) = Settlement(id, from, to, amount, currency, timestamp = 1000)

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
    fun `lenient rejects epoch 0`() {
        assertFalse(validator.isTimestampValidLenient(0L))
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

    @Test
    fun `brackets inside a JSON string do not count toward depth`() {
        val content = """{"a":"${"[".repeat(33)}"}"""
        assertTrue(validator.isContentSafe(content))
    }

    @Test
    fun `braces inside a JSON string do not count toward depth`() {
        val content = """{"description":"${"{".repeat(40)}"}"""
        assertTrue(validator.isContentSafe(content))
    }

    @Test
    fun `escaped quote inside string does not terminate the string`() {
        // The \" keeps us inside the string, so the following brackets must be ignored.
        val content = """{"a":"x\"${"[".repeat(33)}"}"""
        assertTrue(validator.isContentSafe(content))
    }

    @Test
    fun `escaped backslash before closing quote terminates the string`() {
        // "x\\" ends the string; the 33 real brackets that follow must be counted.
        val content = """{"a":"x\\"${"[".repeat(33)}${"]".repeat(33)}}"""
        assertFalse(validator.isContentSafe(content))
    }

    @Test
    fun `33 real nesting levels is unsafe even with strings present`() {
        val content = "{".repeat(32) + """"k":[{"a":1}]""" + "}".repeat(32)
        // 32 objects + 1 array + 1 object = 34 real levels
        assertFalse(validator.isContentSafe(content))
    }

    @Test
    fun `33 real nesting levels around a string is unsafe`() {
        val content = "[".repeat(33) + "\"[[[\"" + "]".repeat(33)
        assertFalse(validator.isContentSafe(content))
    }

    // --- isExpenseValid ---

    @Test
    fun `valid expense is accepted`() {
        assertTrue(validator.isExpenseValid(expense(), members))
    }

    @Test
    fun `valid single-participant expense is accepted`() {
        assertTrue(validator.isExpenseValid(expense(splitAmong = listOf(SplitEntry(alice, 100))), members))
    }

    @Test
    fun `expense with blank id is rejected`() {
        assertFalse(validator.isExpenseValid(expense(id = "  "), members))
    }

    @Test
    fun `expense with zero amount is rejected`() {
        assertFalse(validator.isExpenseValid(expense(amount = 0, splitAmong = listOf(SplitEntry(alice, 0))), members))
    }

    @Test
    fun `expense with negative amount is rejected`() {
        assertFalse(validator.isExpenseValid(expense(amount = -100), members))
    }

    @Test
    fun `expense exceeding max amount is rejected`() {
        val huge = 1_000_000_000_001L
        assertFalse(
            validator.isExpenseValid(expense(amount = huge, splitAmong = listOf(SplitEntry(alice, huge))), members)
        )
    }

    @Test
    fun `expense at max amount is accepted`() {
        val max = 1_000_000_000_000L
        assertTrue(
            validator.isExpenseValid(expense(amount = max, splitAmong = listOf(SplitEntry(alice, max))), members)
        )
    }

    @Test
    fun `expense with share sum not equal to amount is rejected`() {
        assertFalse(validator.isExpenseValid(expense(amount = 101), members))
    }

    @Test
    fun `expense with share sum exceeding amount is rejected`() {
        assertFalse(validator.isExpenseValid(expense(amount = 99), members))
    }

    @Test
    fun `expense with empty splits is rejected`() {
        assertFalse(validator.isExpenseValid(expense(splitAmong = emptyList()), members))
    }

    @Test
    fun `expense with zero share is rejected`() {
        val splits = listOf(SplitEntry(alice, 100), SplitEntry(bob, 0))
        assertFalse(validator.isExpenseValid(expense(splitAmong = splits), members))
    }

    @Test
    fun `expense with negative share is rejected even when sum matches`() {
        val splits = listOf(SplitEntry(alice, 150), SplitEntry(bob, -50))
        assertFalse(validator.isExpenseValid(expense(splitAmong = splits), members))
    }

    @Test
    fun `expense with non-member payer is rejected`() {
        assertFalse(validator.isExpenseValid(expense(paidBy = stranger), members))
    }

    @Test
    fun `expense with non-member split participant is rejected`() {
        val splits = listOf(SplitEntry(alice, 50), SplitEntry(stranger, 50))
        assertFalse(validator.isExpenseValid(expense(splitAmong = splits), members))
    }

    @Test
    fun `expense with duplicate split pubkeys is rejected`() {
        val splits = listOf(SplitEntry(alice, 50), SplitEntry(alice, 50))
        assertFalse(validator.isExpenseValid(expense(splitAmong = splits), members))
    }

    @Test
    fun `expense with overflowing shares is rejected instead of throwing`() {
        val splits = listOf(SplitEntry(alice, Long.MAX_VALUE), SplitEntry(bob, 1))
        assertFalse(validator.isExpenseValid(expense(amount = 100, splitAmong = splits), members))
    }

    @Test
    fun `expense with overflowing shares that wrap to the amount is rejected`() {
        // MAX + MAX wraps to -2; a naive sum could be coerced to match a crafted amount.
        val splits = listOf(SplitEntry(alice, Long.MAX_VALUE), SplitEntry(bob, Long.MAX_VALUE), SplitEntry(carol, 102))
        assertFalse(validator.isExpenseValid(expense(amount = 100, splitAmong = splits), members))
    }

    @Test
    fun `expense with lowercase currency is rejected`() {
        assertFalse(validator.isExpenseValid(expense(currency = "inr"), members))
    }

    @Test
    fun `expense with too-short currency is rejected`() {
        assertFalse(validator.isExpenseValid(expense(currency = "IN"), members))
    }

    @Test
    fun `expense with too-long currency is rejected`() {
        assertFalse(validator.isExpenseValid(expense(currency = "INRR"), members))
    }

    @Test
    fun `expense with non-ASCII currency is rejected`() {
        assertFalse(validator.isExpenseValid(expense(currency = "ÉUR"), members))
    }

    @Test
    fun `expense with digits in currency is rejected`() {
        assertFalse(validator.isExpenseValid(expense(currency = "US1"), members))
    }

    // --- isSettlementValid ---

    @Test
    fun `valid settlement authored by payer is accepted`() {
        assertTrue(validator.isSettlementValid(settlement(), alice, members))
    }

    @Test
    fun `valid settlement authored by recipient is accepted`() {
        assertTrue(validator.isSettlementValid(settlement(), bob, members))
    }

    @Test
    fun `settlement with blank id is rejected`() {
        assertFalse(validator.isSettlementValid(settlement(id = ""), alice, members))
    }

    @Test
    fun `self-settlement is rejected`() {
        assertFalse(validator.isSettlementValid(settlement(from = alice, to = alice), alice, members))
    }

    @Test
    fun `settlement from non-member is rejected`() {
        assertFalse(validator.isSettlementValid(settlement(from = stranger, to = alice), alice, members))
    }

    @Test
    fun `settlement to non-member is rejected`() {
        assertFalse(validator.isSettlementValid(settlement(from = alice, to = stranger), alice, members))
    }

    @Test
    fun `settlement authored by third party is rejected`() {
        assertFalse(validator.isSettlementValid(settlement(from = alice, to = bob), carol, members))
    }

    @Test
    fun `settlement with zero amount is rejected`() {
        assertFalse(validator.isSettlementValid(settlement(amount = 0), alice, members))
    }

    @Test
    fun `settlement with negative amount is rejected`() {
        assertFalse(validator.isSettlementValid(settlement(amount = -1), alice, members))
    }

    @Test
    fun `settlement exceeding max amount is rejected`() {
        assertFalse(validator.isSettlementValid(settlement(amount = 1_000_000_000_001L), alice, members))
    }

    @Test
    fun `settlement with invalid currency is rejected`() {
        assertFalse(validator.isSettlementValid(settlement(currency = "usd"), alice, members))
    }
}
