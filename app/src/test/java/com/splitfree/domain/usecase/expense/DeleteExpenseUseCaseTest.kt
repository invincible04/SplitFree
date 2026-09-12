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
        useCase("g1", "exp1")

        coVerify(exactly = 1) { repo.deleteExpense("exp1", "g1", "") }
    }

    @Test
    fun `passes a reason through unchanged`() = runTest {
        useCase("g1", "exp1", reason = "duplicate")

        coVerify(exactly = 1) { repo.deleteExpense("exp1", "g1", "duplicate") }
    }

    @Test
    fun `blank expense id is rejected before touching the repository`() = runTest {
        try {
            useCase("g1", " ")
            fail("Expected IllegalArgumentException")
        } catch (_: IllegalArgumentException) {
        }

        coVerify(exactly = 0) { repo.deleteExpense(any(), any(), any()) }
    }

    @Test
    fun `repository refusal propagates to the caller`() = runTest {
        coEvery { repo.deleteExpense("exp1", "g1", "") } throws IllegalStateException("Only the creator can delete")

        try {
            useCase("g1", "exp1")
            fail("Expected IllegalStateException")
        } catch (e: IllegalStateException) {
            assertEquals("Only the creator can delete", e.message)
        }
    }
}
