package com.splitfree.ui.viewmodels.expense

import com.splitfree.domain.model.expense.Expense
import com.splitfree.domain.model.expense.SplitType
import com.splitfree.domain.money.ExpenseInputParser
import com.splitfree.domain.money.ExpenseSplitCalculator
import java.math.BigDecimal
import java.math.BigInteger
import java.math.RoundingMode
import java.text.DecimalFormatSymbols
import java.util.Locale

/**
 * Rebuilds editor inputs from a stored [Expense]. The protocol keeps only final shares, so the split mode is
 * kept only when its reconstructed inputs reproduce those shares exactly; otherwise the editor opens in
 * exact mode with the stored amounts, which always round-trips.
 */
internal class ExpenseDraftSeeder(
    private val parser: ExpenseInputParser,
    private val calculator: ExpenseSplitCalculator,
    private val locale: Locale
) {
    fun seed(draft: ExpenseDraft, expense: Expense, authorPubkey: String): ExpenseDraft {
        val digits = parser.fractionDigits(expense.currency)
        val participants = expense.splitAmong.map { it.pubkey }.toSet()
        val exactInputs = expense.splitAmong.associate { it.pubkey to moneyText(BigDecimal.valueOf(it.share, digits)) }
        val (splitType, inputs) = reconstructSplit(expense, participants)
        return draft.copy(
            expenseId = expense.id,
            createdAt = expense.timestamp,
            amount = moneyText(BigDecimal.valueOf(expense.amount, digits)),
            description = expense.description,
            currency = expense.currency,
            paidBy = expense.paidBy,
            authorPubkey = authorPubkey,
            category = expense.category,
            splitType = splitType,
            inputs = mapOf(SplitType.EXACT to exactInputs) + inputs,
            participants = participants,
            initialized = true,
            dirty = false
        )
    }

    private fun reconstructSplit(
        expense: Expense,
        participants: Set<String>
    ): Pair<SplitType, Map<SplitType, Map<String, String>>> {
        val candidate: Map<String, String>? =
            when (expense.splitType) {
                SplitType.EQUAL -> emptyMap()
                SplitType.PERCENTAGE -> percentages(expense)
                SplitType.SHARES -> weights(expense)
                SplitType.EXACT -> null
            }
        if (candidate != null && reproduces(expense, participants, candidate)) {
            return expense.splitType to mapOf(expense.splitType to candidate)
        }
        return SplitType.EXACT to emptyMap()
    }

    private fun reproduces(expense: Expense, participants: Set<String>, inputs: Map<String, String>): Boolean {
        val preview = calculator.calculate(expense.amount, expense.currency, expense.splitType, participants, inputs)
        return preview.error == null &&
            preview.splits.sortedBy { it.pubkey } == expense.splitAmong.sortedBy { it.pubkey }
    }

    /** Percentages with up to the parser's weight precision; null when they cannot sum to exactly 100. */
    private fun percentages(expense: Expense): Map<String, String>? {
        val total = BigDecimal.valueOf(expense.amount)
        val percents = expense.splitAmong.map { entry ->
            entry.pubkey to
                BigDecimal.valueOf(entry.share)
                    .multiply(HUNDRED)
                    .divide(total, ExpenseInputParser.MAX_WEIGHT_FRACTION_DIGITS, RoundingMode.HALF_UP)
        }
        val sum = percents.fold(BigDecimal.ZERO) { acc, (_, percent) -> acc + percent }
        if (sum.compareTo(HUNDRED) != 0) return null
        return percents.associate { (pubkey, percent) -> pubkey to weightText(percent) }
    }

    /** Shares as the smallest whole weights with the same ratio. */
    private fun weights(expense: Expense): Map<String, String> {
        val divisor = expense.splitAmong.fold(BigInteger.ZERO) { acc, entry ->
            acc.gcd(BigInteger.valueOf(entry.share))
        }
        val safeDivisor = if (divisor.signum() == 0) BigInteger.ONE else divisor
        return expense.splitAmong.associate { entry ->
            entry.pubkey to BigInteger.valueOf(entry.share).divide(safeDivisor).toString()
        }
    }

    /** Whole amounts as "50", anything else with the currency's full precision, as "70.50". */
    private fun moneyText(value: BigDecimal): String {
        val stripped = value.stripTrailingZeros()
        return localised(if (stripped.scale() <= 0) stripped.toPlainString() else value.toPlainString())
    }

    /** Weights and percentages without trailing zeros, as "12.5". */
    private fun weightText(value: BigDecimal): String = localised(value.stripTrailingZeros().toPlainString())

    /** Swaps the '.' for the draft locale's decimal separator, which the parser accepts alongside '.'. */
    private fun localised(plain: String): String {
        val separator = DecimalFormatSymbols.getInstance(locale).decimalSeparator
        return if (separator == '.') plain else plain.replace('.', separator)
    }

    private companion object {
        val HUNDRED: BigDecimal = BigDecimal.valueOf(100)
    }
}
