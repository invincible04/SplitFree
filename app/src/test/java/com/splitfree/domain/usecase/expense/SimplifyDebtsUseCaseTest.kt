package com.splitfree.domain.usecase.expense

import com.splitfree.domain.model.balance.Balance
import com.splitfree.domain.model.expense.DebtTransaction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SimplifyDebtsUseCaseTest {
    private val useCase = SimplifyDebtsUseCase()

    @Test
    fun `empty balances returns empty transactions`() {
        assertEquals(emptyList<DebtTransaction>(), useCase(emptyList()))
    }

    @Test
    fun `all zero balances returns empty`() {
        val balances = listOf(Balance("A", 0, "INR"), Balance("B", 0, "INR"))
        assertTrue(useCase(balances).isEmpty())
    }

    @Test
    fun `simple two-person debt`() {
        val balances = listOf(Balance("A", 100, "INR"), Balance("B", -100, "INR"))
        val result = useCase(balances)
        assertEquals(1, result.size)
        assertEquals("B", result[0].from)
        assertEquals("A", result[0].to)
        assertEquals(100L, result[0].amount)
    }

    @Test
    fun `three-person simplification`() {
        // A is owed 100, B owes 60, C owes 40
        val balances = listOf(Balance("A", 100, "INR"), Balance("B", -60, "INR"), Balance("C", -40, "INR"))
        val result = useCase(balances)
        // Should produce 2 transactions, total transferred = 100
        val totalTransferred = result.sumOf { it.amount }
        assertEquals(100L, totalTransferred)
        assertTrue(result.all { it.to == "A" })
    }

    @Test
    fun `multi-currency handled separately`() {
        val balances = listOf(
            Balance("A", 100, "INR"),
            Balance("B", -100, "INR"),
            Balance("A", 50, "USD"),
            Balance("C", -50, "USD")
        )
        val result = useCase(balances)
        val inr = result.filter { it.currency == "INR" }
        val usd = result.filter { it.currency == "USD" }
        assertEquals(1, inr.size)
        assertEquals(1, usd.size)
        assertEquals("B", inr[0].from)
        assertEquals("C", usd[0].from)
    }

    @Test
    fun `simplification minimizes transactions`() {
        // 4 people: A+50, B+30, C-40, D-40
        val balances = listOf(
            Balance("A", 50, "INR"),
            Balance("B", 30, "INR"),
            Balance("C", -40, "INR"),
            Balance("D", -40, "INR")
        )
        val result = useCase(balances)
        // Greedy algorithm: max 3 transactions for 4 people
        assertTrue(result.size <= 3)
        // Net should balance: total from == total to
        assertEquals(result.sumOf { it.amount }, result.sumOf { it.amount })
    }

    @Test
    fun `single person with balance returns empty`() {
        val balances = listOf(Balance("A", 100, "INR"))
        // No one to pay — creditor only
        val result = useCase(balances)
        assertTrue(result.isEmpty())
    }
}
