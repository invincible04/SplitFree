package com.splitfree.domain.usecase

import com.splitfree.data.repository.ExpenseRepository
import com.splitfree.domain.model.Expense
import com.splitfree.domain.model.SplitEntry
import com.splitfree.domain.model.SplitType
import io.mockk.*
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class AddExpenseUseCaseTest {

    private fun repo() = mockk<ExpenseRepository>(relaxed = true)

    private fun useCase(repo: ExpenseRepository = repo()) = AddExpenseUseCase(repo)

    @Test
    fun `valid expense calls repository`() = runTest {
        val repo = repo()
        val uc = useCase(repo)
        uc("g1", 100, "INR", "test", "alice", SplitType.EQUAL,
            listOf(SplitEntry("alice", 50), SplitEntry("bob", 50)))
        coVerify(exactly = 1) { repo.addExpense(any(), "g1") }
    }

    @Test
    fun `expense has correct fields passed to repo`() = runTest {
        val repo = repo()
        val slot = slot<Expense>()
        coEvery { repo.addExpense(capture(slot), any()) } just Runs
        val uc = useCase(repo)
        uc("g1", 500, "usd", "Dinner", "alice", SplitType.EXACT,
            listOf(SplitEntry("alice", 200), SplitEntry("bob", 300)), "food")
        assertTrue(slot.isCaptured)
        val exp = slot.captured
        assertEquals(500L, exp.amount)
        assertEquals("USD", exp.currency) // normalized
        assertEquals("Dinner", exp.description)
        assertEquals("alice", exp.paidBy)
        assertEquals(SplitType.EXACT, exp.splitType)
        assertEquals("food", exp.category)
        assertEquals(2, exp.splitAmong.size)
        assertTrue(exp.id.isNotEmpty()) // UUID generated
    }

    @Test(expected = IllegalArgumentException::class)
    fun `zero amount throws`() = runTest {
        useCase()("g1", 0, "INR", "t", "a", SplitType.EQUAL, listOf(SplitEntry("a", 0)))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `negative amount throws`() = runTest {
        useCase()("g1", -1, "INR", "t", "a", SplitType.EQUAL, listOf(SplitEntry("a", -1)))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `amount exceeding max throws`() = runTest {
        useCase()("g1", 10_000_000_000_01L, "INR", "t", "a", SplitType.EQUAL,
            listOf(SplitEntry("a", 10_000_000_000_01L)))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `empty splits throws`() = runTest {
        useCase()("g1", 100, "INR", "t", "a", SplitType.EQUAL, emptyList())
    }

    @Test(expected = IllegalArgumentException::class)
    fun `zero share throws`() = runTest {
        useCase()("g1", 100, "INR", "t", "a", SplitType.EQUAL,
            listOf(SplitEntry("a", 100), SplitEntry("b", 0)))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `shares not summing to amount throws`() = runTest {
        useCase()("g1", 100, "INR", "t", "a", SplitType.EQUAL,
            listOf(SplitEntry("a", 60), SplitEntry("b", 30)))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `invalid currency throws`() = runTest {
        useCase()("g1", 100, "US", "t", "a", SplitType.EQUAL, listOf(SplitEntry("a", 100)))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `numeric currency throws`() = runTest {
        useCase()("g1", 100, "123", "t", "a", SplitType.EQUAL, listOf(SplitEntry("a", 100)))
    }

    @Test
    fun `currency is normalized to uppercase trimmed`() = runTest {
        val repo = repo()
        val slot = slot<Expense>()
        coEvery { repo.addExpense(capture(slot), any()) } just Runs
        useCase(repo)("g1", 100, " eur ", "t", "a", SplitType.EQUAL, listOf(SplitEntry("a", 100)))
        assertEquals("EUR", slot.captured.currency)
    }

    @Test
    fun `no repo call on validation failure`() = runTest {
        val repo = repo()
        try {
            useCase(repo)("g1", 0, "INR", "t", "a", SplitType.EQUAL, listOf(SplitEntry("a", 0)))
        } catch (_: IllegalArgumentException) {}
        coVerify(exactly = 0) { repo.addExpense(any(), any()) }
    }
}
