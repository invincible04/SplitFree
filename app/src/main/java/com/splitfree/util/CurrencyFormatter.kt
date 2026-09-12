package com.splitfree.util

import java.math.BigDecimal
import java.util.Currency
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/**
 * Formats smallest-unit amounts (paisa, cents) into display strings with currency symbols.
 *
 * All monetary values in SplitFree are stored as `Long` in the smallest currency unit
 * to avoid floating-point rounding errors.
 */
object CurrencyFormatter {
    private const val DEFAULT_MINOR_DIGITS = 2

    /** Minor digits per normalised code; list rows format the same few currencies over and over. */
    private val minorDigitsCache = ConcurrentHashMap<String, Int>()

    /** Number of decimal (minor unit) digits for a currency. */
    fun minorDigits(currency: String): Int {
        val code = currency.trim().uppercase(Locale.ROOT)
        return minorDigitsCache.getOrPut(code) {
            try {
                Currency.getInstance(code).defaultFractionDigits.takeIf { it >= 0 } ?: DEFAULT_MINOR_DIGITS
            } catch (_: IllegalArgumentException) {
                DEFAULT_MINOR_DIGITS
            }
        }
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
