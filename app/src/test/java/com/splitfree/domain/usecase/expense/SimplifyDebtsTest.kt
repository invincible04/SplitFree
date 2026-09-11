package com.splitfree.domain.usecase.expense

import com.splitfree.domain.model.balance.Balance
import com.splitfree.domain.model.expense.DebtTransaction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for SimplifyDebtsUseCase — the greedy debt simplification algorithm.
 * Design doc Section 11.2.
 */
class SimplifyDebtsTest {
    private val simplify = SimplifyDebtsUseCase()

    @Test
    fun `empty balances produce no transactions`() {
        assertEquals(emptyList<DebtTransaction>(), simplify(emptyList()))
    }

    @Test
    fun `all zero balances produce no transactions`() {
        val balances =
            listOf(
                Balance("alice", 0, "INR"),
                Balance("bob", 0, "INR")
            )
        assertEquals(emptyList<DebtTransaction>(), simplify(balances))
    }

    @Test
    fun `simple two-person debt`() {
        // Alice is owed 100, Bob owes 100
        val balances =
            listOf(
                Balance("alice", 100, "INR"),
                Balance("bob", -100, "INR")
            )
        val result = simplify(balances)
        assertEquals(1, result.size)
        assertEquals("bob", result[0].from)
        assertEquals("alice", result[0].to)
        assertEquals(100L, result[0].amount)
    }

    @Test
    fun `three-person chain simplification`() {
        // Alice owes Bob 100, Bob owes Charlie 100 → Alice pays Charlie 100 directly
        // Net: Alice=-100, Bob=0, Charlie=+100
        val balances =
            listOf(
                Balance("alice", -100, "INR"),
                Balance("bob", 0, "INR"),
                Balance("charlie", 100, "INR")
            )
        val result = simplify(balances)
        assertEquals(1, result.size)
        assertEquals("alice", result[0].from)
        assertEquals("charlie", result[0].to)
        assertEquals(100L, result[0].amount)
    }

    @Test
    fun `design doc example - three person simplification`() {
        // Alice owes Bob 100, Bob owes Charlie 100, Charlie owes Alice 50
        // Net: Alice = -100+50 = -50, Bob = 100-100 = 0, Charlie = 100-50 = 50
        val balances =
            listOf(
                Balance("alice", -50, "INR"),
                Balance("bob", 0, "INR"),
                Balance("charlie", 50, "INR")
            )
        val result = simplify(balances)
        assertEquals(1, result.size)
        assertEquals(50L, result[0].amount)
    }

    @Test
    fun `five-person group produces at most N-1 transactions`() {
        val balances =
            listOf(
                Balance("a", 500, "INR"),
                Balance("b", -200, "INR"),
                Balance("c", -150, "INR"),
                Balance("d", 100, "INR"),
                Balance("e", -250, "INR")
            )
        val result = simplify(balances)
        assertTrue("Should have at most 4 transactions", result.size <= 4)
        // Net should be zero
        val netFrom = result.sumOf { -it.amount }
        val netTo = result.sumOf { it.amount }
        assertEquals("Total paid must equal total received", netTo, -netFrom)
    }

    @Test
    fun `balances sum to zero after simplification`() {
        val balances =
            listOf(
                Balance("a", 300, "INR"),
                Balance("b", -100, "INR"),
                Balance("c", -200, "INR")
            )
        val result = simplify(balances)
        val totalTransferred = result.sumOf { it.amount }
        val totalOwed = balances.filter { it.net > 0 }.sumOf { it.net }
        assertEquals("Total transferred must equal total owed", totalOwed, totalTransferred)
    }

    @Test
    fun `multi-currency keeps currencies separate`() {
        val balances =
            listOf(
                Balance("alice", 100, "INR"),
                Balance("bob", -100, "INR"),
                Balance("alice", 50, "USD"),
                Balance("charlie", -50, "USD")
            )
        val result = simplify(balances)
        assertEquals(2, result.size)
        val inr = result.filter { it.currency == "INR" }
        val usd = result.filter { it.currency == "USD" }
        assertEquals(1, inr.size)
        assertEquals(1, usd.size)
        assertEquals(100L, inr[0].amount)
        assertEquals(50L, usd[0].amount)
    }

    @Test
    fun `single person with balance produces no transactions`() {
        val balances = listOf(Balance("alice", 100, "INR"))
        // No one to pay — this is an inconsistent state but shouldn't crash
        val result = simplify(balances)
        assertEquals(0, result.size)
    }

    @Test
    fun `large group with many small debts`() {
        // 10 people, various balances summing to 0
        val balances =
            listOf(
                Balance("p1", 1000, "INR"),
                Balance("p2", -200, "INR"),
                Balance("p3", -300, "INR"),
                Balance("p4", 500, "INR"),
                Balance("p5", -400, "INR"),
                Balance("p6", -100, "INR"),
                Balance("p7", 200, "INR"),
                Balance("p8", -300, "INR"),
                Balance("p9", -200, "INR"),
                Balance("p10", -200, "INR")
            )
        assertEquals(0L, balances.sumOf { it.net }) // sanity check
        val result = simplify(balances)
        assertTrue("At most 9 transactions for 10 people", result.size <= 9)
        // All amounts positive
        assertTrue(result.all { it.amount > 0 })
    }
}
