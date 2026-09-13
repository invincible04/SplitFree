package com.splitfree.domain.model.balance

import com.splitfree.domain.model.expense.ExpenseIdentity

/** Balances and the exact author-bound expenses that have been soft-deleted. */
data class BalanceResult(val balances: List<Balance>, val excludedExpenses: Set<ExpenseIdentity>)
