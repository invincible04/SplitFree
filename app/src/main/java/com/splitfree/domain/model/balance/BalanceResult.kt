package com.splitfree.domain.model.balance

/**
 * Result of balance computation, including which expense UUIDs were excluded
 * due to corrections or deletions.
 */
data class BalanceResult(val balances: List<Balance>, val excludedExpenseUuids: Set<String>)
