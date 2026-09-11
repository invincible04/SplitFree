package com.splitfree.domain.repository

import com.splitfree.domain.model.expense.Expense
import com.splitfree.domain.model.expense.Settlement

/**
 * Domain contract for expense operations (add, settle, delete, correct).
 */
interface ExpenseRepositoryContract {
    /**
     * Encrypt and publish a new expense event to the group's Nostr relay set.
     *
     * @param expense the expense to publish
     * @param groupId target group UUID
     * @throws IllegalStateException if the group key is not found locally
     */
    suspend fun addExpense(expense: Expense, groupId: String, expectedAuthorPubkey: String? = null)

    /** Returns the original saved expense for the current author, including after process recreation. */
    suspend fun getSavedExpense(groupId: String, expenseId: String, expectedAuthorPubkey: String? = null): Expense?

    /**
     * Record a settlement between two members.
     *
     * @param settlement must involve the current user as payer or payee
     * @param groupId target group UUID
     * @throws IllegalArgumentException if the current user is not a party to the settlement
     */
    suspend fun addSettlement(settlement: Settlement, groupId: String)

    /**
     * Soft-delete an expense by publishing an `expense_delete` event.
     *
     * @param expenseUuid UUID of the expense to delete
     * @param groupId target group UUID
     * @param reason optional human-readable deletion reason
     * @throws IllegalStateException if the expense is not found or the caller is not the creator
     */
    suspend fun deleteExpense(expenseUuid: String, groupId: String, reason: String = "")

    /**
     * Publish a corrected version of an existing expense.
     *
     * @param originalUuid UUID of the expense being corrected
     * @param corrected the replacement expense data
     * @param groupId target group UUID
     * @throws IllegalStateException if the original expense is not found or the caller is not the creator
     */
    suspend fun correctExpense(originalUuid: String, corrected: Expense, groupId: String)
}

class ExpenseSaveConflictException : IllegalStateException("This expense was already saved with different details")
