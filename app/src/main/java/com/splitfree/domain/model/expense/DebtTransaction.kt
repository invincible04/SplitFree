package com.splitfree.domain.model.expense

/**
 * A single optimized transfer from debtor to creditor,
 * output of [SimplifyDebtsUseCase][com.splitfree.domain.usecase.expense.SimplifyDebtsUseCase].
 */
data class DebtTransaction(val from: String, val to: String, val amount: Long, val currency: String = "INR")
