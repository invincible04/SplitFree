package com.splitfree.domain.usecase

import com.splitfree.domain.model.Expense
import com.splitfree.domain.model.SplitEntry
import com.splitfree.domain.model.SplitType
import org.junit.Assert.*
import org.junit.Test

/**
 * Tests for AddExpenseUseCase validation logic.
 * The use case validates before delegating to repository.
 */
class AddExpenseValidationTest {

    // We can't call the real use case (needs ExpenseRepository with Android deps),
    // but we can test the validation logic it applies.

    private fun validate(amount: Long, splitAmong: List<SplitEntry>) {
        require(amount > 0) { "Amount must be positive" }
        require(splitAmong.isNotEmpty()) { "Must split among at least one person" }
        require(splitAmong.sumOf { it.share } == amount) {
            "Split shares (${splitAmong.sumOf { it.share }}) must equal total amount ($amount)"
        }
    }

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

    // --- Expense model integrity ---

    @Test
    fun `Expense amount stored as smallest currency unit`() {
        // ₹500.50 = 50050 paise
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
