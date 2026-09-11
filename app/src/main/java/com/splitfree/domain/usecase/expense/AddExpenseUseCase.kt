package com.splitfree.domain.usecase.expense

import com.splitfree.domain.model.expense.Expense
import com.splitfree.domain.model.expense.SplitEntry
import com.splitfree.domain.model.expense.SplitType
import com.splitfree.domain.repository.ExpenseRepositoryContract
import java.util.UUID
import javax.inject.Inject

/**
 * Validates, encrypts, and publishes a new expense to a group.
 */
class AddExpenseUseCase
@Inject
constructor(private val expenseRepo: ExpenseRepositoryContract) {
    /**
     * Validates and persists a new expense to a group.
     *
     * @param groupId target group UUID
     * @param amount total in smallest currency unit (e.g. paisa), must be positive
     * @param currency 3-letter ISO 4217 code
     * @param description human-readable label
     * @param paidBy pubkey of the member who paid
     * @param splitType how the expense is divided
     * @param splitAmong per-member shares; sum must equal [amount]
     * @param category optional category tag
     * @throws IllegalArgumentException on invalid amount, currency, or mismatched shares
     */
    suspend operator fun invoke(
        groupId: String,
        amount: Long,
        currency: String,
        description: String,
        paidBy: String,
        splitType: SplitType,
        splitAmong: List<SplitEntry>,
        category: String = "",
        expenseId: String = UUID.randomUUID().toString(),
        createdAt: Long = System.currentTimeMillis() / 1000,
        expectedAuthorPubkey: String? = null
    ) {
        require(expenseId.isNotBlank()) { "Expense ID must not be blank" }
        require(createdAt >= 0) { "Expense timestamp must not be negative" }
        require(amount > 0) { "Amount must be positive" }
        require(amount <= 1_000_000_000_000L) { "Amount exceeds maximum ($10B)" }
        require(splitAmong.isNotEmpty()) { "Must split among at least one person" }
        require(splitAmong.all { it.share > 0 }) { "All split shares must be positive" }
        // Use Math.addExact to detect overflow in share summation (CWE-190)
        val shareSum = splitAmong.fold(0L) { acc, entry -> Math.addExact(acc, entry.share) }
        require(shareSum == amount) {
            "Split shares ($shareSum) must equal total amount ($amount)"
        }

        val normalizedCurrency = currency.uppercase().trim()
        require(normalizedCurrency.length == 3 && normalizedCurrency.all { it.isLetter() }) {
            "Currency must be a 3-letter ISO code"
        }

        val expense =
            Expense(
                id = expenseId,
                amount = amount,
                currency = normalizedCurrency,
                description = description,
                paidBy = paidBy,
                splitType = splitType,
                splitAmong = splitAmong,
                timestamp = createdAt,
                category = category
            )
        expenseRepo.addExpense(expense, groupId, expectedAuthorPubkey)
    }

    suspend fun getSavedExpense(groupId: String, expenseId: String, expectedAuthorPubkey: String? = null): Expense? =
        expenseRepo.getSavedExpense(groupId, expenseId, expectedAuthorPubkey)

    suspend fun isSaved(groupId: String, expenseId: String, expectedAuthorPubkey: String? = null): Boolean =
        getSavedExpense(groupId, expenseId, expectedAuthorPubkey) != null
}
