package com.splitfree.domain.money

import java.util.Currency
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ExpenseCurrencyCatalogTest {
    @Test
    fun `picker includes every platform currency the parser supports`() {
        val expected = Currency.getAvailableCurrencies().filter { it.defaultFractionDigits in setOf(0, 2, 3) }
            .map { it.currencyCode }.sorted()
        assertEquals(expected, ExpenseCurrencyCatalog.codes)
        ExpenseCurrencyCatalog.codes.forEach { code ->
            assertEquals(
                Currency.getInstance(code).defaultFractionDigits,
                ExpenseInputParser(Locale.US).fractionDigits(code)
            )
        }
    }

    @Test
    fun `catalog includes currencies beyond old shortlist without unsupported placeholders`() {
        assertTrue(ExpenseCurrencyCatalog.codes.containsAll(listOf("CHF", "CNY", "JOD", "CLP", "ZAR", "BRL")))
        assertFalse("XXX" in ExpenseCurrencyCatalog.codes)
        assertFalse("XAU" in ExpenseCurrencyCatalog.codes)
        assertEquals(ExpenseCurrencyCatalog.codes.distinct(), ExpenseCurrencyCatalog.codes)
    }
}
