package com.splitfree.domain.usecase.expense

import com.splitfree.domain.repository.ExpenseRepositoryContract
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test

class DeleteExpenseUseCaseTest {
    private val repo = mockk<ExpenseRepositoryContract>(relaxed = true)
    private val useCase = DeleteExpenseUseCase(repo)

    @Test
    fun `forwards the expense and group to the repository with an empty reason by default`() = runTest {
        useCase("g1", "exp1", expectedAuthorPubkey = "alice")

        coVerify(exactly = 1) { repo.deleteExpense("exp1", "g1", "", "alice") }
    }

    @Test
    fun `passes a reason through unchanged`() = runTest {
        useCase("g1", "exp1", reason = "duplicate", expectedAuthorPubkey = "alice")

        coVerify(exactly = 1) { repo.deleteExpense("exp1", "g1", "duplicate", "alice") }
    }

    @Test
    fun `blank expense id is rejected before touching the repository`() = runTest {
        try {
            useCase("g1", " ", expectedAuthorPubkey = "alice")
            fail("Expected IllegalArgumentException")
        } catch (_: IllegalArgumentException) {
        }

        coVerify(exactly = 0) { repo.deleteExpense(any(), any(), any(), any()) }
    }

    @Test
    fun `blank expected author is rejected before touching the repository`() = runTest {
        val result = runCatching { useCase("g1", "exp1", expectedAuthorPubkey = " ") }

        assertEquals(IllegalArgumentException::class, result.exceptionOrNull()!!::class)
        coVerify(exactly = 0) { repo.deleteExpense(any(), any(), any(), any()) }
    }

    @Test
    fun `repository refusal propagates to the caller`() = runTest {
        coEvery { repo.deleteExpense("exp1", "g1", "", "alice") } throws
            IllegalStateException("Only the creator can delete")

        try {
            useCase("g1", "exp1", expectedAuthorPubkey = "alice")
            fail("Expected IllegalStateException")
        } catch (e: IllegalStateException) {
            assertEquals("Only the creator can delete", e.message)
        }
    }
}
