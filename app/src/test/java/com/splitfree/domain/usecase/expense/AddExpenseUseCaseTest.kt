package com.splitfree.domain.usecase.expense

import com.splitfree.domain.model.expense.Expense
import com.splitfree.domain.model.expense.SplitEntry
import com.splitfree.domain.model.expense.SplitType
import com.splitfree.domain.repository.ExpenseRepositoryContract
import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.just
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AddExpenseUseCaseTest {
    private fun repo() = mockk<ExpenseRepositoryContract>(relaxed = true)

    private fun useCase(repo: ExpenseRepositoryContract = repo()) = AddExpenseUseCase(repo)

    @Test
    fun `valid expense calls repository`() = runTest {
        val repo = repo()
        val uc = useCase(repo)
        uc(
            "g1",
            100,
            "INR",
            "test",
            "alice",
            SplitType.EQUAL,
            listOf(SplitEntry("alice", 50), SplitEntry("bob", 50))
        )
        coVerify(exactly = 1) { repo.addExpense(any(), "g1") }
    }

    @Test
    fun `expense has correct fields passed to repo`() = runTest {
        val repo = repo()
        val slot = slot<Expense>()
        coEvery { repo.addExpense(capture(slot), any()) } just Runs
        val uc = useCase(repo)
        uc(
            "g1",
            500,
            "usd",
            "Dinner",
            "alice",
            SplitType.EXACT,
            listOf(SplitEntry("alice", 200), SplitEntry("bob", 300)),
            "food"
        )
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
        useCase()(
            "g1",
            10_000_000_000_01L,
            "INR",
            "t",
            "a",
            SplitType.EQUAL,
            listOf(SplitEntry("a", 10_000_000_000_01L))
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun `empty splits throws`() = runTest {
        useCase()("g1", 100, "INR", "t", "a", SplitType.EQUAL, emptyList())
    }

    @Test(expected = IllegalArgumentException::class)
    fun `zero share throws`() = runTest {
        useCase()(
            "g1",
            100,
            "INR",
            "t",
            "a",
            SplitType.EQUAL,
            listOf(SplitEntry("a", 100), SplitEntry("b", 0))
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun `shares not summing to amount throws`() = runTest {
        useCase()(
            "g1",
            100,
            "INR",
            "t",
            "a",
            SplitType.EQUAL,
            listOf(SplitEntry("a", 60), SplitEntry("b", 30))
        )
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
        } catch (_: IllegalArgumentException) {
        }
        coVerify(exactly = 0) { repo.addExpense(any(), any()) }
    }

    @Test
    fun `caller supplied operation and timestamp are preserved across retry`() = runTest {
        val repo = repo()
        val expenses = mutableListOf<Expense>()
        coEvery { repo.addExpense(capture(expenses), "g1") } just Runs
        repeat(2) {
            useCase(repo)(
                "g1", 100, "INR", "Lunch", "alice", SplitType.EQUAL,
                listOf(SplitEntry("alice", 100)), category = "food", expenseId = "stable-operation", createdAt = 1234
            )
        }
        assertEquals(2, expenses.size)
        assertEquals(expenses.first(), expenses.last())
        assertEquals("stable-operation", expenses.first().id)
        assertEquals(1234L, expenses.first().timestamp)
        assertEquals("food", expenses.first().category)
    }

    @Test
    fun `isSaved recovers durable operation`() = runTest {
        val repo = repo()
        coEvery { repo.getSavedExpense("g1", "saved") } returns mockk<Expense>()
        coEvery { repo.getSavedExpense("g1", "new") } returns null
        assertTrue(useCase(repo).isSaved("g1", "saved"))
        assertFalse(useCase(repo).isSaved("g1", "new"))
    }

    @Test(expected = IllegalStateException::class)
    fun `recovery failure is not mistaken for unsaved`() = runTest {
        val repo = repo()
        coEvery { repo.getSavedExpense(any(), any()) } throws IllegalStateException("Key unavailable")
        useCase(repo).isSaved("g1", "saved")
    }

    @Test
    fun `save forwards persisted draft author to repository`() = runTest {
        val repo = repo()
        useCase(repo)(
            "g1",
            100,
            "INR",
            "Lunch",
            "alice",
            SplitType.EQUAL,
            listOf(SplitEntry("alice", 100)),
            expenseId = "stable-operation",
            createdAt = 1234,
            expectedAuthorPubkey = "draft-author"
        )
        coVerify { repo.addExpense(match { it.id == "stable-operation" }, "g1", "draft-author") }
    }

    @Test
    fun `recovery returns exact original payload and forwards expected author`() = runTest {
        val repo = repo()
        val expense = Expense(
            "stable-operation",
            100,
            "INR",
            "Original lunch",
            "alice",
            SplitType.EQUAL,
            listOf(SplitEntry("alice", 100)),
            1234,
            "food"
        )
        coEvery { repo.getSavedExpense("g1", "stable-operation", "draft-author") } returns expense
        assertEquals(expense, useCase(repo).getSavedExpense("g1", "stable-operation", "draft-author"))
        coVerify { repo.getSavedExpense("g1", "stable-operation", "draft-author") }
    }

    @Test(expected = IllegalStateException::class)
    fun `author mismatch recovery error propagates without becoming not saved`() = runTest {
        val repo = repo()
        coEvery { repo.getSavedExpense(any(), any(), "draft-author") } throws IllegalStateException("Identity changed")
        useCase(repo).getSavedExpense("g1", "stable-operation", "draft-author")
    }
}
