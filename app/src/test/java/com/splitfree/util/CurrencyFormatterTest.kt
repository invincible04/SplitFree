package com.splitfree.util

import com.splitfree.domain.money.ExpenseInputParser
import java.util.Currency
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Test

class CurrencyFormatterTest {
    @Test
    fun `formatter precision agrees with every supported ISO parser currency`() {
        val parser = ExpenseInputParser(Locale.US)
        Currency.getAvailableCurrencies().filter { it.defaultFractionDigits in setOf(0, 2, 3) }.forEach {
            assertEquals(
                it.currencyCode,
                parser.fractionDigits(it.currencyCode),
                CurrencyFormatter.minorDigits(it.currencyCode)
            )
        }
        assertEquals(0, CurrencyFormatter.minorDigits("CLP"))
        assertEquals(3, CurrencyFormatter.minorDigits("JOD"))
    }

    @Test
    fun `large amounts retain exact minor units and sign when formatted`() {
        val previous = Locale.getDefault()
        Locale.setDefault(Locale.US)
        try {
            assertEquals("$92,233,720,368,547,758.07", CurrencyFormatter.format(Long.MAX_VALUE, "USD"))
            assertEquals("$-92,233,720,368,547,758.08", CurrencyFormatter.format(Long.MIN_VALUE, "USD"))
            assertEquals("$92,233,720,368,547,758.08", CurrencyFormatter.formatMagnitude(Long.MIN_VALUE, "USD"))
            assertEquals("JOD1.234", CurrencyFormatter.format(1234, "JOD"))
            assertEquals("CLP1,234", CurrencyFormatter.format(1234, "CLP"))
        } finally {
            Locale.setDefault(previous)
        }
    }
}
