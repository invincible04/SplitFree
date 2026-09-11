package com.splitfree.domain.money

import java.util.Currency

/** Uses platform ISO currency data instead of a region-specific hand-maintained picker list. */
object ExpenseCurrencyCatalog {
    fun supportsFractionDigits(digits: Int): Boolean = digits == 0 || digits == 2 || digits == 3

    val codes: List<String> by lazy {
        Currency.getAvailableCurrencies()
            .filter { supportsFractionDigits(it.defaultFractionDigits) }
            .map { it.currencyCode }
            .sorted()
    }
}
