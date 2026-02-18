package com.splitfree.util

/**
 * Formats smallest-unit amounts (paisa, cents) into display strings with currency symbols.
 *
 * All monetary values in SplitFree are stored as `Long` in the smallest currency unit
 * to avoid floating-point rounding errors.
 */
object CurrencyFormatter {
    fun format(amountSmallest: Long, currency: String): String {
        val major = amountSmallest / 100.0
        val symbol =
            when (currency.uppercase()) {
                "INR" -> "₹"
                "USD" -> "$"
                "EUR" -> "€"
                "GBP" -> "£"
                "JPY" -> "¥"
                else -> currency
            }
        return "$symbol${"%.2f".format(major)}"
    }
}
