package com.splitfree.domain.money

import java.math.BigDecimal
import java.text.DecimalFormatSymbols
import java.util.Currency
import java.util.Locale

class ExpenseInputParser(private val locale: Locale = Locale.getDefault()) {
    fun fractionDigits(currency: String): Int {
        val digits = try {
            Currency.getInstance(currency.trim().uppercase(Locale.ROOT)).defaultFractionDigits
        } catch (_: IllegalArgumentException) {
            throw IllegalArgumentException("Choose a valid currency")
        }
        require(ExpenseCurrencyCatalog.supportsFractionDigits(digits)) { "This currency precision is not supported" }
        return digits
    }

    fun money(input: String, currency: String): Long {
        val digits = fractionDigits(currency)
        val decimal = decimal(input, digits)
        return try {
            decimal.movePointRight(digits).longValueExact()
        } catch (_: ArithmeticException) {
            throw IllegalArgumentException("Amount is too large")
        }
    }

    fun weight(input: String): BigDecimal = decimal(input, MAX_WEIGHT_FRACTION_DIGITS)

    private fun decimal(input: String, fractionDigits: Int): BigDecimal {
        require(input.length in 1..MAX_INPUT_LENGTH) { "Enter a number of at most $MAX_INPUT_LENGTH characters" }
        val symbols = DecimalFormatSymbols.getInstance(locale)
        val separator = symbols.decimalSeparator
        val normalized = input.map { character ->
            when {
                character in '0'..'9' -> character
                character in symbols.zeroDigit..(symbols.zeroDigit + 9) -> '0' + (character - symbols.zeroDigit)
                else -> character
            }
        }.joinToString("")
        val parts = normalized.split(separator)
        require(parts.size <= 2 && parts.all { part -> part.isNotEmpty() && part.all { it in '0'..'9' } }) {
            "Use digits and '$separator' as the decimal separator, without grouping"
        }
        require(parts.size == 1 || (fractionDigits > 0 && parts[1].length <= fractionDigits)) {
            "Use at most $fractionDigits decimal places"
        }
        return BigDecimal(normalized.replace(separator, '.'))
    }

    companion object {
        const val MAX_INPUT_LENGTH = 32
        const val MAX_WEIGHT_FRACTION_DIGITS = 6
        const val MAX_EXPENSE_AMOUNT = 1_000_000_000_000L
    }
}
