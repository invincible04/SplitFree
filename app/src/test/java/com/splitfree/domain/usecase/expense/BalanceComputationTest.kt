package com.splitfree.domain.usecase.expense

import com.splitfree.domain.model.expense.Expense
import com.splitfree.domain.model.expense.Settlement
import com.splitfree.domain.model.expense.SplitEntry
import com.splitfree.domain.model.expense.SplitType
import com.splitfree.domain.repository.EventSnapshot
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for balance computation logic — the core business logic of the app.
 * Tests the applyExpense algorithm and balance scenarios without needing Room DB.
 * Design doc Section 11.1.
 */
class BalanceComputationTest {
    private val json = Json { ignoreUnknownKeys = true }

    // Simulate applyExpense (mirrors ComputeBalancesUseCase.applyExpense); sign = -1 reverses a payload
    private fun applyExpense(expense: Expense, balances: MutableMap<Pair<String, String>, Long>, sign: Int = 1) {
        val cur = expense.currency
        balances.getOrPut(expense.paidBy to cur) { 0L }
        for (split in expense.splitAmong) {
            balances.getOrPut(split.pubkey to cur) { 0L }
            if (split.pubkey != expense.paidBy) {
                val delta = if (sign > 0) split.share else -split.share
                balances[expense.paidBy to cur] = (balances[expense.paidBy to cur] ?: 0L) + delta
                balances[split.pubkey to cur] = (balances[split.pubkey to cur] ?: 0L) - delta
            }
        }
    }

    private fun applySettlement(s: Settlement, balances: MutableMap<Pair<String, String>, Long>) {
        balances[s.from to s.currency] = (balances[s.from to s.currency] ?: 0L) + s.amount
        balances[s.to to s.currency] = (balances[s.to to s.currency] ?: 0L) - s.amount
    }

    private fun expense(id: String, amount: Long, paidBy: String, splits: List<SplitEntry>, currency: String = "INR") =
        Expense(id, amount, currency, "test", paidBy, SplitType.EQUAL, splits, 1)

    // --- Basic scenarios ---

    @Test
    fun `single expense between two people`() {
        val balances = mutableMapOf<Pair<String, String>, Long>()
        // Alice pays 100, split equally with Bob
        applyExpense(expense("1", 100, "alice", listOf(SplitEntry("alice", 50), SplitEntry("bob", 50))), balances)
        assertEquals(50L, balances["alice" to "INR"]) // alice is owed 50
        assertEquals(-50L, balances["bob" to "INR"]) // bob owes 50
    }

    @Test
    fun `payer's own share doesn't create self-debt`() {
        val balances = mutableMapOf<Pair<String, String>, Long>()
        // Alice pays 100, split 50/50 — alice's own share is skipped
        applyExpense(expense("1", 100, "alice", listOf(SplitEntry("alice", 50), SplitEntry("bob", 50))), balances)
        // Only bob's share creates a balance entry for alice
        assertEquals(50L, balances["alice" to "INR"])
        assertNull("Self-share should not appear", balances.entries.find { it.key.first == "alice" && it.value < 0 })
    }

    @Test
    fun `expense paid for oneself alone records the currency with a zero net`() {
        val balances = mutableMapOf<Pair<String, String>, Long>()
        applyExpense(expense("1", 100, "alice", listOf(SplitEntry("alice", 100))), balances)
        assertEquals(mapOf(("alice" to "INR") to 0L), balances)
    }

    @Test
    fun `three-person equal split`() {
        val balances = mutableMapOf<Pair<String, String>, Long>()
        // Alice pays 300, split 100 each
        applyExpense(
            expense(
                "1",
                300,
                "alice",
                listOf(SplitEntry("alice", 100), SplitEntry("bob", 100), SplitEntry("charlie", 100))
            ),
            balances
        )
        assertEquals(200L, balances["alice" to "INR"]) // owed 100+100 from bob and charlie
        assertEquals(-100L, balances["bob" to "INR"])
        assertEquals(-100L, balances["charlie" to "INR"])
    }

    @Test
    fun `multiple expenses accumulate`() {
        val balances = mutableMapOf<Pair<String, String>, Long>()
        // Alice pays 100, split with Bob
        applyExpense(expense("1", 100, "alice", listOf(SplitEntry("alice", 50), SplitEntry("bob", 50))), balances)
        // Bob pays 200, split with Alice
        applyExpense(expense("2", 200, "bob", listOf(SplitEntry("alice", 100), SplitEntry("bob", 100))), balances)
        // Net: alice = +50 (from exp1) - 100 (from exp2) = -50
        // Net: bob = -50 (from exp1) + 100 (from exp2) = +50
        assertEquals(-50L, balances["alice" to "INR"])
        assertEquals(50L, balances["bob" to "INR"])
    }

    @Test
    fun `settlement reduces debt`() {
        val balances = mutableMapOf<Pair<String, String>, Long>()
        // Alice pays 100, Bob owes 50
        applyExpense(expense("1", 100, "alice", listOf(SplitEntry("alice", 50), SplitEntry("bob", 50))), balances)
        assertEquals(-50L, balances["bob" to "INR"])
        // Bob settles 50 with Alice
        applySettlement(Settlement("s1", "bob", "alice", 50, "INR", "cash", 2), balances)
        assertEquals(0L, balances["bob" to "INR"])
        assertEquals(0L, balances["alice" to "INR"])
    }

    @Test
    fun `settlement can overpay`() {
        val balances = mutableMapOf<Pair<String, String>, Long>()
        applyExpense(expense("1", 100, "alice", listOf(SplitEntry("alice", 50), SplitEntry("bob", 50))), balances)
        // Bob pays 100 to Alice (overpays by 50)
        applySettlement(Settlement("s1", "bob", "alice", 100, "INR", "cash", 2), balances)
        assertEquals(50L, balances["bob" to "INR"]) // now alice owes bob
        assertEquals(-50L, balances["alice" to "INR"])
    }

    // --- Multi-currency ---

    @Test
    fun `expenses in different currencies tracked separately`() {
        val balances = mutableMapOf<Pair<String, String>, Long>()
        applyExpense(
            expense("1", 100, "alice", listOf(SplitEntry("alice", 50), SplitEntry("bob", 50)), "INR"),
            balances
        )
        applyExpense(
            expense("2", 200, "alice", listOf(SplitEntry("alice", 100), SplitEntry("bob", 100)), "USD"),
            balances
        )
        assertEquals(50L, balances["alice" to "INR"])
        assertEquals(100L, balances["alice" to "USD"])
        assertEquals(-50L, balances["bob" to "INR"])
        assertEquals(-100L, balances["bob" to "USD"])
    }

    // --- Deletion and correction ---

    @Test
    fun `deleted expense excluded from balance`() {
        val balances = mutableMapOf<Pair<String, String>, Long>()
        val deleted = mutableSetOf("exp-1") // simulate deletion
        // This expense should be skipped
        val exp = expense("exp-1", 100, "alice", listOf(SplitEntry("alice", 50), SplitEntry("bob", 50)))
        if (exp.id !in deleted) applyExpense(exp, balances)
        assertTrue("Balances should be empty after deletion", balances.isEmpty())
    }

    @Test
    fun `corrected expense uses new values`() {
        val balances = mutableMapOf<Pair<String, String>, Long>()
        val corrections = mapOf("exp-1" to "correction-event-id")
        // Original expense (should be skipped because it has a correction)
        val original = expense("exp-1", 100, "alice", listOf(SplitEntry("alice", 50), SplitEntry("bob", 50)))
        if (original.id !in corrections) applyExpense(original, balances)
        // Correction (200 instead of 100)
        val corrected =
            expense("exp-1-corrected", 200, "alice", listOf(SplitEntry("alice", 100), SplitEntry("bob", 100)))
        applyExpense(corrected, balances)
        assertEquals(100L, balances["alice" to "INR"]) // based on corrected amount
        assertEquals(-100L, balances["bob" to "INR"])
    }

    @Test
    fun `deletion takes precedence over correction`() {
        val deleted = mutableSetOf("exp-1")
        val corrections = mapOf("exp-1" to "corr-1")
        // Both deleted and corrected — deletion wins
        val shouldApply = "exp-1" !in deleted && "exp-1" !in corrections
        assertFalse("Deleted expense should not be applied even if corrected", shouldApply)
    }

    // --- Snapshot reversal (covered expense deleted/corrected after the snapshot) ---

    @Test
    fun `reversing an applied expense restores the previous balances`() {
        val balances = mutableMapOf<Pair<String, String>, Long>()
        val exp =
            expense(
                "1",
                300,
                "alice",
                listOf(SplitEntry("alice", 100), SplitEntry("bob", 100), SplitEntry("charlie", 100))
            )
        applyExpense(exp, balances)
        assertEquals(200L, balances["alice" to "INR"])
        // Snapshot contained exp; a later delete must undo exactly its effect.
        applyExpense(exp, balances, sign = -1)
        assertEquals(0L, balances["alice" to "INR"])
        assertEquals(0L, balances["bob" to "INR"])
        assertEquals(0L, balances["charlie" to "INR"])
        assertEquals(0L, balances.values.sum())
    }

    @Test
    fun `reverse original then apply correction equals applying the correction alone`() {
        val original = expense("exp-1", 100, "alice", listOf(SplitEntry("alice", 50), SplitEntry("bob", 50)))
        val corrected = expense("exp-1", 200, "bob", listOf(SplitEntry("alice", 100), SplitEntry("bob", 100)))

        // Path A: snapshot seeded with the original, then reversed and corrected.
        val viaSnapshot = mutableMapOf<Pair<String, String>, Long>()
        applyExpense(original, viaSnapshot)
        applyExpense(original, viaSnapshot, sign = -1)
        applyExpense(corrected, viaSnapshot)

        // Path B: full replay that skips the original and applies only the correction.
        val fullReplay = mutableMapOf<Pair<String, String>, Long>()
        applyExpense(corrected, fullReplay)

        assertEquals(fullReplay, viaSnapshot)
        assertEquals(-100L, viaSnapshot["alice" to "INR"])
        assertEquals(100L, viaSnapshot["bob" to "INR"])
    }

    @Test
    fun `reversal is currency-aware`() {
        val balances = mutableMapOf<Pair<String, String>, Long>()
        applyExpense(
            expense("1", 100, "alice", listOf(SplitEntry("alice", 50), SplitEntry("bob", 50)), "INR"),
            balances
        )
        applyExpense(
            expense("2", 100, "alice", listOf(SplitEntry("alice", 50), SplitEntry("bob", 50)), "USD"),
            balances
        )
        applyExpense(
            expense("2", 100, "alice", listOf(SplitEntry("alice", 50), SplitEntry("bob", 50)), "USD"),
            balances,
            sign = -1
        )
        assertEquals(50L, balances["alice" to "INR"])
        assertEquals(0L, balances["alice" to "USD"])
    }

    // --- Ordering rules (createdAt, then eventId; never storage order) ---

    private fun event(id: String, createdAt: Long, type: String = "expense_correction", uuid: String = "u1") =
        EventSnapshot(
            eventId = id,
            groupId = "g1",
            pubkey = "alice",
            createdAt = createdAt,
            contentEncrypted = "",
            eventType = type,
            expenseUuid = uuid
        )

    @Test
    fun `latest correction is decided by createdAt regardless of storage order`() {
        val stored = listOf(event("e3", 30), event("e1", 10), event("e2", 20))
        val latest = stored.maxWithOrNull(EventSnapshot.CANONICAL_ORDER)
        assertEquals("e3", latest?.eventId)
    }

    @Test
    fun `equal createdAt is broken by eventId`() {
        val stored = listOf(event("zz", 10), event("aa", 10), event("mm", 10))
        assertEquals("zz", stored.maxWithOrNull(EventSnapshot.CANONICAL_ORDER)?.eventId)
        assertEquals("aa", stored.minWithOrNull(EventSnapshot.CANONICAL_ORDER)?.eventId)
    }

    @Test
    fun `duplicate x tag collapses to the earliest expense event`() {
        val stored =
            listOf(
                event("later", 200, type = "expense"),
                event("earliest", 5, type = "expense"),
                event("mid", 50, type = "expense")
            )
        val earliestByUuid = stored.groupBy {
            it.expenseUuid
        }.mapValues { (_, dups) -> dups.minWith(EventSnapshot.CANONICAL_ORDER) }
        assertEquals("earliest", earliestByUuid["u1"]?.eventId)
    }

    // --- Balances sum to zero ---

    @Test
    fun `all balances sum to zero (conservation of money)`() {
        val balances = mutableMapOf<Pair<String, String>, Long>()
        applyExpense(
            expense(
                "1",
                500,
                "alice",
                listOf(SplitEntry("alice", 200), SplitEntry("bob", 150), SplitEntry("charlie", 150))
            ),
            balances
        )
        applyExpense(
            expense(
                "2",
                300,
                "bob",
                listOf(SplitEntry("alice", 100), SplitEntry("bob", 100), SplitEntry("charlie", 100))
            ),
            balances
        )
        applySettlement(Settlement("s1", "charlie", "alice", 50, "INR", "cash", 3), balances)

        val total = balances.values.sum()
        assertEquals("Total of all balances must be zero", 0L, total)
    }

    // --- Expense serialization through JSON (simulates relay storage) ---

    @Test
    fun `expense survives JSON serialization and produces same balance`() {
        val exp =
            expense(
                "1",
                997,
                "alice",
                listOf(SplitEntry("alice", 333), SplitEntry("bob", 332), SplitEntry("charlie", 332))
            )
        val serialized = json.encodeToString(Expense.serializer(), exp)
        val deserialized = json.decodeFromString<Expense>(serialized)

        val b1 = mutableMapOf<Pair<String, String>, Long>()
        val b2 = mutableMapOf<Pair<String, String>, Long>()
        applyExpense(exp, b1)
        applyExpense(deserialized, b2)
        assertEquals(b1, b2)
    }

    // --- Large group scenario ---

    @Test
    fun `20-person group with 100 expenses`() {
        val members = (1..20).map { "member-$it" }
        val balances = mutableMapOf<Pair<String, String>, Long>()

        repeat(100) { i ->
            val payer = members[i % 20]
            val amount = ((i + 1) * 100).toLong()
            val perPerson = amount / 20
            val remainder = (amount % 20).toInt()
            val splits =
                members.mapIndexed { j, pk ->
                    SplitEntry(pk, perPerson + if (j < remainder) 1 else 0)
                }
            assertEquals("Splits must sum to amount", amount, splits.sumOf { it.share })
            applyExpense(expense("exp-$i", amount, payer, splits), balances)
        }

        // All balances must sum to zero
        assertEquals(0L, balances.values.sum())
        // Should have entries for all 20 members
        val memberKeys = balances.keys.map { it.first }.toSet()
        assertEquals(20, memberKeys.size)
    }
}
