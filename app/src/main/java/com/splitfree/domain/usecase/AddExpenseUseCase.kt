package com.splitfree.domain.usecase

import com.splitfree.data.repository.ExpenseRepository
import com.splitfree.domain.model.Expense
import com.splitfree.domain.model.SplitEntry
import com.splitfree.domain.model.SplitType
import java.util.UUID
import javax.inject.Inject

class AddExpenseUseCase @Inject constructor(
    private val expenseRepo: ExpenseRepository
) {
    suspend operator fun invoke(
        groupId: String,
        amount: Long,
        currency: String,
        description: String,
        paidBy: String,
        splitType: SplitType,
        splitAmong: List<SplitEntry>,
        category: String = ""
    ) {
        require(amount > 0) { "Amount must be positive" }
        require(amount <= 10_000_000_000_00L) { "Amount exceeds maximum ($10B)" }
        require(splitAmong.isNotEmpty()) { "Must split among at least one person" }
        // Use Math.addExact to detect overflow in share summation (CWE-190)
        val shareSum = splitAmong.fold(0L) { acc, entry -> Math.addExact(acc, entry.share) }
        require(shareSum == amount) {
            "Split shares ($shareSum) must equal total amount ($amount)"
        }

        val expense = Expense(
            id = UUID.randomUUID().toString(),
            amount = amount,
            currency = currency,
            description = description,
            paidBy = paidBy,
            splitType = splitType,
            splitAmong = splitAmong,
            timestamp = System.currentTimeMillis() / 1000,
            category = category
        )
        expenseRepo.addExpense(expense, groupId)
    }
}
