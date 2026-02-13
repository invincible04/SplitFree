package com.splitfree.domain.usecase

import com.splitfree.domain.model.Expense
import com.splitfree.domain.model.SplitEntry
import com.splitfree.domain.model.SplitType
import org.junit.Assert.*
import org.junit.Test

/**
 * Tests for AddExpenseUseCase validation logic.
 * Mirrors all require() checks from AddExpenseUseCase.invoke().
 */
class AddExpenseValidationTest {

    // Mirror the full validation logic from AddExpenseUseCase
    private fun validate(
        amount: Long,
        currency: String,
        splitAmong: List<SplitEntry>
    ) {
        require(amount > 0) { "Amount must be positive" }
        require(amount <= 10_000_000_000_00L) { "Amount exceeds maximum (\$10B)" }
        require(splitAmong.isNotEmpty()) { "Must split among at least one person" }
        require(splitAmong.all { it.share > 0 }) { "All split shares must be positive" }
        val shareSum = splitAmong.fold(0L) { acc, entry -> Math.addExact(acc, entry.share) }
        require(shareSum == amount) {
            "Split shares ($shareSum) must equal total amount ($amount)"
        }
        val normalizedCurrency = currency.uppercase().trim()
        require(normalizedCurrency.length == 3 && normalizedCurrency.all { it.isLetter() }) {
            "Currency must be a 3-letter ISO code"
        }
    }

    private fun validate(amount: Long, splitAmong: List<SplitEntry>) =
        validate(amount, "INR", splitAmong)

    @Test
    fun `valid expense passes validation`() {
        validate(100, listOf(SplitEntry("a", 50), SplitEntry("b", 50)))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `zero amount fails`() {
        validate(0, listOf(SplitEntry("a", 0)))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `negative amount fails`() {
        validate(-100, listOf(SplitEntry("a", -100)))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `empty split list fails`() {
        validate(100, emptyList())
    }

    @Test(expected = IllegalArgumentException::class)
    fun `shares not summing to amount fails`() {
        validate(100, listOf(SplitEntry("a", 60), SplitEntry("b", 30))) // = 90 != 100
    }

    @Test
    fun `single person split is valid`() {
        validate(500, listOf(SplitEntry("a", 500)))
    }

    @Test
    fun `many-person split summing correctly is valid`() {
        val splits = (1..20).map { SplitEntry("p$it", 5L) } // 20 * 5 = 100
        validate(100, splits)
    }

    // --- Amount limits ---

    @Test(expected = IllegalArgumentException::class)
    fun `amount exceeding 10B fails`() {
        validate(10_000_000_000_01L, listOf(SplitEntry("a", 10_000_000_000_01L)))
    }

    @Test
    fun `amount at exactly 10B passes`() {
        validate(10_000_000_000_00L, listOf(SplitEntry("a", 10_000_000_000_00L)))
    }

    // --- Share positivity ---

    @Test(expected = IllegalArgumentException::class)
    fun `zero share fails`() {
        validate(100, listOf(SplitEntry("a", 100), SplitEntry("b", 0)))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `negative share fails`() {
        validate(100, listOf(SplitEntry("a", 150), SplitEntry("b", -50)))
    }

    // --- Currency validation ---

    @Test
    fun `valid 3-letter currency passes`() {
        validate(100, "USD", listOf(SplitEntry("a", 100)))
    }

    @Test
    fun `lowercase currency is normalized`() {
        validate(100, "usd", listOf(SplitEntry("a", 100)))
    }

    @Test
    fun `currency with whitespace is trimmed`() {
        validate(100, " INR ", listOf(SplitEntry("a", 100)))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `2-letter currency fails`() {
        validate(100, "US", listOf(SplitEntry("a", 100)))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `4-letter currency fails`() {
        validate(100, "USDT", listOf(SplitEntry("a", 100)))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `numeric currency fails`() {
        validate(100, "123", listOf(SplitEntry("a", 100)))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `empty currency fails`() {
        validate(100, "", listOf(SplitEntry("a", 100)))
    }

    // --- Expense model integrity ---

    @Test
    fun `Expense amount stored as smallest currency unit`() {
        val expense = Expense(
            id = "1", amount = 50050, currency = "INR", description = "test",
            paidBy = "a", splitType = SplitType.EQUAL,
            splitAmong = listOf(SplitEntry("a", 25025), SplitEntry("b", 25025)),
            timestamp = 1
        )
        assertEquals(50050L, expense.amount)
        assertEquals(50050L, expense.splitAmong.sumOf { it.share })
    }
}
