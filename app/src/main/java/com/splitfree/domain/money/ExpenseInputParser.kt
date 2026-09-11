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

    /**
     * Parse [input] into a [BigDecimal] with at most [fractionDigits] decimal places.
     *
     * Separator rules: the locale decides which separators exist, but keyboards often only offer
     * `.`, so both are accepted:
     * - If exactly one of `.` / `,` / the locale decimal separator appears (once), it is the decimal
     *   separator, regardless of locale.
     * - If both `,` and `.` appear, the LAST one is the decimal separator and the other is grouping.
     *   The locale grouping separator (e.g. a narrow space) is likewise stripped when it is not the
     *   sole separator being used as the decimal.
     * - The decimal separator may appear only once; `1.2.3` and `12,5,0` are rejected.
     */
    private fun decimal(input: String, fractionDigits: Int): BigDecimal {
        require(input.length in 1..MAX_INPUT_LENGTH) { "Enter a number of at most $MAX_INPUT_LENGTH characters" }
        val symbols = DecimalFormatSymbols.getInstance(locale)
        val localeDecimal = symbols.decimalSeparator
        val localeGrouping = symbols.groupingSeparator
        val hint = if (localeDecimal == '.') {
            "Use digits and '.' as the decimal separator"
        } else {
            "Use digits and '$localeDecimal' or '.' as the decimal separator"
        }
        val normalized = input.map { character ->
            when {
                character in '0'..'9' -> character
                character in symbols.zeroDigit..(symbols.zeroDigit + 9) -> '0' + (character - symbols.zeroDigit)
                else -> character
            }
        }.joinToString("")
        val decimalCandidates = setOf('.', ',', localeDecimal)
        val separators = normalized.filter { it in decimalCandidates || it == localeGrouping }
        val distinct = separators.toSet()
        val decimalSeparator: Char? = when (distinct.size) {
            0 -> null
            1 -> {
                val only = distinct.single()
                when {
                    only !in decimalCandidates -> null // locale grouping only, e.g. "1 234"
                    separators.length == 1 -> only
                    else -> throw IllegalArgumentException(hint)
                }
            }
            2 -> {
                val last = separators.last()
                val other = (distinct - last).single()
                val groupingCandidates = setOf('.', ',', localeGrouping) - last
                require(last in decimalCandidates && other in groupingCandidates) { hint }
                require(separators.count { it == last } == 1) { hint }
                last
            }
            else -> throw IllegalArgumentException(hint)
        }
        // Every run between separators must be non-empty digits: rejects ".5", "1.", "1,,234.5", "+1", "1e2".
        val runs = if (distinct.isEmpty()) listOf(normalized) else normalized.split(*distinct.toCharArray())
        require(runs.all { run -> run.isNotEmpty() && run.all { it in '0'..'9' } }) { hint }
        val groupingSeparators = distinct - setOfNotNull(decimalSeparator)
        val digitsAndDecimal = normalized.filterNot { it in groupingSeparators }
        val parts = if (decimalSeparator == null) listOf(digitsAndDecimal) else digitsAndDecimal.split(decimalSeparator)
        require(parts.size == 1 || (fractionDigits > 0 && parts[1].length <= fractionDigits)) {
            "Use at most $fractionDigits decimal places"
        }
        return BigDecimal(parts.joinToString("."))
    }

    companion object {
        const val MAX_INPUT_LENGTH = 32
        const val MAX_WEIGHT_FRACTION_DIGITS = 6
        const val MAX_EXPENSE_AMOUNT = 1_000_000_000_000L
    }
}
