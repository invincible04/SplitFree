package com.splitfree.domain.usecase.expense

import com.splitfree.domain.repository.ExpenseRepositoryContract
import javax.inject.Inject

/**
 * Soft-deletes an expense by publishing an `expense_delete` event for it.
 */
class DeleteExpenseUseCase
@Inject
constructor(private val expenseRepo: ExpenseRepositoryContract) {
    /**
     * Hides [expenseId] from the group's ledger and balances.
     *
     * @param groupId target group UUID
     * @param expenseId UUID of the expense to delete
     * @param reason optional note stored with the deletion
     * @throws IllegalStateException if the expense is unknown or the caller did not author it
     */
    suspend operator fun invoke(groupId: String, expenseId: String, reason: String = "") {
        require(expenseId.isNotBlank()) { "Expense ID must not be blank" }
        expenseRepo.deleteExpense(expenseId, groupId, reason)
    }
}
