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
import org.junit.Assert.fail
import org.junit.Test

class CorrectExpenseUseCaseTest {
    private val repo = mockk<ExpenseRepositoryContract>(relaxed = true)
    private val useCase = CorrectExpenseUseCase(repo)

    private suspend fun correct(
        amount: Long = 10_000,
        currency: String = "inr ",
        splitAmong: List<SplitEntry> = listOf(SplitEntry("alice", 4_000), SplitEntry("bob", 6_000)),
        originalId: String = "exp-1",
        timestamp: Long = 1_234
    ) = useCase(
        groupId = "g1",
        originalId = originalId,
        amount = amount,
        currency = currency,
        description = "Dinner",
        paidBy = "alice",
        splitType = SplitType.EXACT,
        splitAmong = splitAmong,
        timestamp = timestamp,
        category = "food"
    )

    @Test
    fun `publishes a correction that keeps the original id and timestamp`() = runTest {
        val corrected = slot<Expense>()
        coEvery { repo.correctExpense("exp-1", capture(corrected), "g1") } just Runs

        correct()

        val expense = corrected.captured
        assertEquals("exp-1", expense.id)
        assertEquals(1_234L, expense.timestamp)
        assertEquals(10_000L, expense.amount)
        assertEquals("INR", expense.currency)
        assertEquals("Dinner", expense.description)
        assertEquals("alice", expense.paidBy)
        assertEquals(SplitType.EXACT, expense.splitType)
        assertEquals("food", expense.category)
        assertEquals(listOf(SplitEntry("alice", 4_000), SplitEntry("bob", 6_000)), expense.splitAmong)
    }

    @Test
    fun `invalid input never reaches the repository`() = runTest {
        val attempts: List<suspend () -> Unit> =
            listOf(
                { correct(amount = 0, splitAmong = listOf(SplitEntry("alice", 0))) },
                { correct(splitAmong = listOf(SplitEntry("alice", 5_000), SplitEntry("bob", 4_000))) },
                { correct(splitAmong = emptyList()) },
                { correct(currency = "IN") },
                { correct(originalId = " ") },
                { correct(timestamp = -1) }
            )
        attempts.forEach { attempt ->
            try {
                attempt()
                fail("Expected IllegalArgumentException")
            } catch (_: IllegalArgumentException) {
            }
        }

        coVerify(exactly = 0) { repo.correctExpense(any(), any(), any()) }
    }

    @Test
    fun `repository refusal propagates`() = runTest {
        coEvery { repo.correctExpense(any(), any(), any()) } throws
            IllegalStateException("Only the creator can correct")

        try {
            correct()
            fail("Expected IllegalStateException")
        } catch (e: IllegalStateException) {
            assertEquals("Only the creator can correct", e.message)
        }
    }
}
