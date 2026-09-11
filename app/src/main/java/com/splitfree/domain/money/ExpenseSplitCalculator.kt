package com.splitfree.domain.money

import com.splitfree.domain.model.expense.SplitEntry
import com.splitfree.domain.model.expense.SplitType
import java.math.BigDecimal
import java.math.BigInteger

data class ExpenseSplitPreview(
    val splits: List<SplitEntry> = emptyList(),
    val remaining: Long? = null,
    val error: String? = null
)

class ExpenseSplitCalculator(private val parser: ExpenseInputParser = ExpenseInputParser()) {
    fun calculate(
        amount: Long,
        currency: String,
        type: SplitType,
        participants: Set<String>,
        inputs: Map<String, String>
    ): ExpenseSplitPreview {
        if (amount <= 0) return ExpenseSplitPreview(error = "Enter a positive amount")
        if (participants.isEmpty()) return ExpenseSplitPreview(error = "Select at least one participant")
        val members = participants.sorted()
        return try {
            when (type) {
                SplitType.EQUAL -> allocate(amount, members, members.map { BigDecimal.ONE })
                SplitType.EXACT -> exact(amount, currency, members, inputs)
                SplitType.PERCENTAGE, SplitType.SHARES -> {
                    val weights = members.map { parser.weight(inputs[it].orEmpty()) }
                    require(weights.all { it.signum() > 0 }) { "Enter a positive value for every selected participant" }
                    if (type == SplitType.PERCENTAGE) {
                        require(weights.fold(BigDecimal.ZERO, BigDecimal::add).compareTo(BigDecimal(100)) == 0) {
                            "Percentages must add up to 100"
                        }
                    }
                    allocate(amount, members, weights)
                }
            }
        } catch (e: IllegalArgumentException) {
            ExpenseSplitPreview(error = e.message)
        }
    }

    private fun exact(
        amount: Long,
        currency: String,
        members: List<String>,
        inputs: Map<String, String>
    ): ExpenseSplitPreview {
        val splits = members.map {
            val input = inputs[it].orEmpty()
            SplitEntry(it, if (input.isEmpty()) 0 else parser.money(input, currency))
        }
        val sum = splits.fold(BigInteger.ZERO) { total, split -> total + BigInteger.valueOf(split.share) }
        val difference = BigInteger.valueOf(amount) - sum
        val remaining = difference.takeIf { it >= LONG_MIN && it <= LONG_MAX }?.toLong()
        val error = when {
            splits.any { it.share <= 0 } -> "Enter a positive amount for every selected participant"
            sum != BigInteger.valueOf(amount) -> "Split amounts must add up to the total"
            else -> null
        }
        return ExpenseSplitPreview(splits, remaining, error)
    }

    private fun allocate(amount: Long, members: List<String>, weights: List<BigDecimal>): ExpenseSplitPreview {
        val scale = weights.maxOf { it.scale() }
        val units = weights.map { it.setScale(scale).unscaledValue() }
        val total = units.fold(BigInteger.ZERO, BigInteger::add)
        val quotas = units.map { (BigInteger.valueOf(amount) * it).divideAndRemainder(total) }
        val allocations = quotas.map { quota ->
            val allocation = quota[0]
            require(allocation >= BigInteger.ZERO && allocation <= LONG_MAX) { "Split amount is too large" }
            allocation.toLong()
        }
        val remainder = amount - allocations.sum()
        val roundedUp = members.indices
            .sortedWith(compareByDescending<Int> { quotas[it][1] }.thenBy { members[it] })
            .filterIndexed { rank, _ -> rank.toLong() < remainder }
            .toSet()
        val splits = members.mapIndexed { index, member ->
            SplitEntry(member, allocations[index] + if (index in roundedUp) 1 else 0)
        }
        val error = if (splits.any { it.share == 0L }) {
            "Amount is too small to give every selected participant a positive share"
        } else {
            null
        }
        return ExpenseSplitPreview(splits, 0, error)
    }

    companion object {
        private val LONG_MIN = BigInteger.valueOf(Long.MIN_VALUE)
        private val LONG_MAX = BigInteger.valueOf(Long.MAX_VALUE)
    }
}
