package com.splitfree.domain.repository

import com.splitfree.domain.model.expense.Expense
import com.splitfree.domain.model.expense.ExpenseIdentity
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
     * @throws ExpenseRevisionConflictException if the expense changes between the lookup and the save
     */
    suspend fun deleteExpense(expenseUuid: String, groupId: String, reason: String = "", expectedAuthorPubkey: String)

    /**
     * Publish a corrected version of an existing expense.
     *
     * With a [command] the correction is a durable, retryable edit: it applies only while the expense is
     * still at [ExpenseCorrectionCommand.expectedRevisionId], and a retry of the same command finds the
     * correction already saved instead of publishing a second one. Without a command the correction
     * applies to whatever revision is current at the time of the call.
     *
     * @param originalUuid UUID of the expense being corrected
     * @param corrected the replacement expense data
     * @param groupId target group UUID
     * @throws IllegalStateException if the original expense is not found or the caller is not the creator
     * @throws ExpenseRevisionConflictException if the expense is not at the command's expected revision
     * @throws ExpenseSaveConflictException if the command was already saved with a different payload
     */
    suspend fun correctExpense(
        originalUuid: String,
        corrected: Expense,
        groupId: String,
        expectedAuthorPubkey: String,
        command: ExpenseCorrectionCommand? = null
    )

    /**
     * The current payload and revision of the caller's own expense [expenseId], or null if unknown or
     * deleted. The revision id is what an edit must present as its expected revision.
     */
    suspend fun getEditableExpense(groupId: String, expenseId: String, expectedAuthorPubkey: String): EditableExpense?

    /**
     * The correction already saved for [command] on [expenseId], or null if the command never committed.
     *
     * @throws ExpenseSaveConflictException if the saved command targets another expense
     */
    suspend fun getSavedCorrection(
        groupId: String,
        expenseId: String,
        expectedAuthorPubkey: String,
        command: ExpenseCorrectionCommand
    ): Expense?
}

class ExpenseSaveConflictException : IllegalStateException("This expense was already saved with different details")

/**
 * Identity of one edit of an expense.
 *
 * @property id stable id of the edit; a retry after an interruption reuses it, a new edit gets a new one
 * @property expectedRevisionId event id of the revision the edit was based on
 */
data class ExpenseCorrectionCommand(val id: String, val expectedRevisionId: String)

/**
 * The current state of an expense as its author may edit it.
 *
 * @property revisionId event id of the row that carries [expense]: the latest correction, else the original
 * @property authorPubkey the signer of the original expense, who alone may edit it
 */
data class EditableExpense(val expense: Expense, val revisionId: String, val authorPubkey: String) {
    val identity: ExpenseIdentity get() = ExpenseIdentity(authorPubkey, expense.id)
}

class ExpenseRevisionConflictException :
    IllegalStateException("This expense changed since it was opened. Reopen it and try again")
