package com.splitfree.util

import java.math.BigDecimal
import java.util.Currency
import java.util.Locale

/**
 * Formats smallest-unit amounts (paisa, cents) into display strings with currency symbols.
 *
 * All monetary values in SplitFree are stored as `Long` in the smallest currency unit
 * to avoid floating-point rounding errors.
 */
object CurrencyFormatter {
    /** Number of decimal (minor unit) digits for a currency. */
    fun minorDigits(currency: String): Int = try {
        Currency.getInstance(currency.trim().uppercase(Locale.ROOT)).defaultFractionDigits.takeIf { it >= 0 } ?: 2
    } catch (_: IllegalArgumentException) {
        2
    }

    /** Multiplier to convert major units to smallest units. */
    fun minorMultiplier(currency: String): Long {
        val digits = minorDigits(currency)
        var m = 1L
        repeat(digits) { m *= 10 }
        return m
    }

    fun format(amountSmallest: Long, currency: String): String =
        formatMajor(BigDecimal.valueOf(amountSmallest, minorDigits(currency)), currency)

    fun formatMagnitude(amountSmallest: Long, currency: String): String =
        formatMajor(BigDecimal.valueOf(amountSmallest, minorDigits(currency)).abs(), currency)

    private fun formatMajor(major: BigDecimal, currency: String): String {
        val digits = minorDigits(currency)
        val symbol =
            when (currency.uppercase(Locale.ROOT)) {
                "INR" -> "₹"
                "USD" -> "$"
                "EUR" -> "€"
                "GBP" -> "£"
                "JPY" -> "¥"
                else -> currency
            }
        return "$symbol${"%,.${digits}f".format(major)}"
    }
}
