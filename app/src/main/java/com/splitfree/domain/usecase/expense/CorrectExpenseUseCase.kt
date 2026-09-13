package com.splitfree.domain.usecase.expense

import com.splitfree.domain.model.expense.Expense
import com.splitfree.domain.model.expense.SplitEntry
import com.splitfree.domain.model.expense.SplitType
import com.splitfree.domain.repository.ExpenseRepositoryContract
import javax.inject.Inject

/**
 * Validates and publishes a corrected version of an existing expense. The correction keeps the original
 * id and timestamp so it replaces the expense in place instead of appearing as a new one.
 */
class CorrectExpenseUseCase
@Inject
constructor(private val expenseRepo: ExpenseRepositoryContract) {
    /**
     * @param groupId target group UUID
     * @param originalId id of the expense being corrected
     * @param amount total in smallest currency unit, must be positive
     * @param currency 3-letter ISO 4217 code
     * @param description human-readable label
     * @param paidBy pubkey of the member who paid
     * @param splitType how the expense is divided
     * @param splitAmong per-member shares; sum must equal [amount]
     * @param timestamp the original expense's timestamp, kept so ordering does not change
     * @param category optional category tag
     * @throws IllegalArgumentException on invalid amount, currency, or mismatched shares
     * @throws IllegalStateException if the original is unknown or the caller did not author it
     */
    suspend operator fun invoke(
        groupId: String,
        originalId: String,
        amount: Long,
        currency: String,
        description: String,
        paidBy: String,
        splitType: SplitType,
        splitAmong: List<SplitEntry>,
        timestamp: Long,
        category: String = "",
        expectedAuthorPubkey: String
    ) {
        require(expectedAuthorPubkey.isNotBlank()) { "Expense author must not be blank" }
        require(originalId.isNotBlank()) { "Expense ID must not be blank" }
        require(timestamp >= 0) { "Expense timestamp must not be negative" }
        require(amount > 0) { "Amount must be positive" }
        require(amount <= MAX_AMOUNT) { "Amount exceeds maximum ($10B)" }
        require(splitAmong.isNotEmpty()) { "Must split among at least one person" }
        require(splitAmong.all { it.share > 0 }) { "All split shares must be positive" }
        val shareSum = splitAmong.fold(0L) { acc, entry -> Math.addExact(acc, entry.share) }
        require(shareSum == amount) { "Split shares ($shareSum) must equal total amount ($amount)" }

        val normalizedCurrency = currency.uppercase().trim()
        require(normalizedCurrency.length == 3 && normalizedCurrency.all { it.isLetter() }) {
            "Currency must be a 3-letter ISO code"
        }

        val corrected =
            Expense(
                id = originalId,
                amount = amount,
                currency = normalizedCurrency,
                description = description,
                paidBy = paidBy,
                splitType = splitType,
                splitAmong = splitAmong,
                timestamp = timestamp,
                category = category
            )
        expenseRepo.correctExpense(originalId, corrected, groupId, expectedAuthorPubkey)
    }

    private companion object {
        const val MAX_AMOUNT = 1_000_000_000_000L
    }
}
