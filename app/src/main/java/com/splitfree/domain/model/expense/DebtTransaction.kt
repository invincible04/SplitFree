package com.splitfree.domain.model.expense

/**
 * A single optimized transfer from debtor to creditor,
 * output of [SimplifyDebtsUseCase][com.splitfree.domain.usecase.expense.SimplifyDebtsUseCase].
 *
 * @property currency ISO 4217 currency code; always explicit, inherited from the balances being settled
 */
data class DebtTransaction(val from: String, val to: String, val amount: Long, val currency: String)
