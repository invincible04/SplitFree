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
        require(splitAmong.isNotEmpty()) { "Must split among at least one person" }
        require(splitAmong.sumOf { it.share } == amount) {
            "Split shares (${splitAmong.sumOf { it.share }}) must equal total amount ($amount)"
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
