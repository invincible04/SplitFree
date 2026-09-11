package com.splitfree.domain.model.balance

/**
 * Result of balance computation.
 *
 * @property excludedExpenseUuids UUIDs of expenses that must not be shown: those soft-deleted via
 *   `expense_delete`. Corrected expenses are not excluded; the UI shows the latest correction's payload instead.
 */
data class BalanceResult(val balances: List<Balance>, val excludedExpenseUuids: Set<String>)
