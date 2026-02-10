package com.splitfree.sync

import org.junit.Assert.*
import org.junit.Test

class ExpenseNotifierFormatTest {

    @Test
    fun `formatAmount INR`() {
        // Access via reflection since formatAmount is private
        val method = ExpenseNotifier::class.java.getDeclaredMethod("formatAmount", Long::class.java, String::class.java)
        method.isAccessible = true
        assertEquals("₹500", method.invoke(ExpenseNotifier, 50000L, "INR"))
        assertEquals("₹1", method.invoke(ExpenseNotifier, 100L, "INR"))
        assertEquals("₹0", method.invoke(ExpenseNotifier, 0L, "INR"))
    }

    @Test
    fun `formatAmount USD`() {
        val method = ExpenseNotifier::class.java.getDeclaredMethod("formatAmount", Long::class.java, String::class.java)
        method.isAccessible = true
        assertEquals("$5.00", method.invoke(ExpenseNotifier, 500L, "USD"))
        assertEquals("$0.01", method.invoke(ExpenseNotifier, 1L, "USD"))
    }

    @Test
    fun `formatAmount EUR`() {
        val method = ExpenseNotifier::class.java.getDeclaredMethod("formatAmount", Long::class.java, String::class.java)
        method.isAccessible = true
        assertEquals("€10.50", method.invoke(ExpenseNotifier, 1050L, "EUR"))
    }

    @Test
    fun `formatAmount GBP`() {
        val method = ExpenseNotifier::class.java.getDeclaredMethod("formatAmount", Long::class.java, String::class.java)
        method.isAccessible = true
        assertEquals("£25.99", method.invoke(ExpenseNotifier, 2599L, "GBP"))
    }

    @Test
    fun `formatAmount unknown currency`() {
        val method = ExpenseNotifier::class.java.getDeclaredMethod("formatAmount", Long::class.java, String::class.java)
        method.isAccessible = true
        assertEquals("5.00 JPY", method.invoke(ExpenseNotifier, 500L, "JPY"))
    }
}
