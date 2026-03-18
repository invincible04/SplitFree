package com.splitfree.util

/**
 * Formats smallest-unit amounts (paisa, cents) into display strings with currency symbols.
 *
 * All monetary values in SplitFree are stored as `Long` in the smallest currency unit
 * to avoid floating-point rounding errors.
 */
object CurrencyFormatter {
    /** Number of decimal (minor unit) digits for a currency. */
    fun minorDigits(currency: String): Int = when (currency.uppercase()) {
        "JPY", "KRW", "VND" -> 0
        "KWD", "BHD", "OMR" -> 3
        else -> 2
    }

    /** Multiplier to convert major units to smallest units. */
    fun minorMultiplier(currency: String): Long {
        val digits = minorDigits(currency)
        var m = 1L
        repeat(digits) { m *= 10 }
        return m
    }

    fun format(amountSmallest: Long, currency: String): String {
        val digits = minorDigits(currency)
        val divisor = minorMultiplier(currency).toDouble()
        val major = amountSmallest / divisor
        val symbol =
            when (currency.uppercase()) {
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
