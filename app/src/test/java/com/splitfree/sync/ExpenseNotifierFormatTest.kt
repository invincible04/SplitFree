package com.splitfree.sync

import org.junit.Assert.*
import org.junit.Test

class ExpenseNotifierFormatTest {

    private val formatAmount = ExpenseNotifier::class.java
        .getDeclaredMethod("formatAmount", Long::class.java, String::class.java)
        .apply { isAccessible = true }

    private val sanitize = ExpenseNotifier::class.java
        .getDeclaredMethod("sanitize", String::class.java)
        .apply { isAccessible = true }

    private val buildExpenseNotification = ExpenseNotifier::class.java
        .getDeclaredMethod("buildExpenseNotification", String::class.java, String::class.java)
        .apply { isAccessible = true }

    private val buildSettlementNotification = ExpenseNotifier::class.java
        .getDeclaredMethod("buildSettlementNotification", String::class.java, String::class.java)
        .apply { isAccessible = true }

    private fun fmt(amount: Long, currency: String) = formatAmount.invoke(ExpenseNotifier, amount, currency)
    private fun san(input: String) = sanitize.invoke(ExpenseNotifier, input) as String

    // --- formatAmount ---

    @Test
    fun `formatAmount INR`() {
        assertEquals("₹500.00", fmt(50000L, "INR"))
        assertEquals("₹1.00", fmt(100L, "INR"))
        assertEquals("₹0.00", fmt(0L, "INR"))
    }

    @Test
    fun `formatAmount USD`() {
        assertEquals("$5.00", fmt(500L, "USD"))
        assertEquals("$0.01", fmt(1L, "USD"))
    }

    @Test
    fun `formatAmount EUR`() {
        assertEquals("€10.50", fmt(1050L, "EUR"))
    }

    @Test
    fun `formatAmount GBP`() {
        assertEquals("£25.99", fmt(2599L, "GBP"))
    }

    @Test
    fun `formatAmount unknown currency`() {
        assertEquals("5.00 JPY", fmt(500L, "JPY"))
    }

    @Test
    fun `formatAmount lowercase currency treated as uppercase`() {
        assertEquals("₹5.00", fmt(500L, "inr"))
    }

    @Test
    fun `formatAmount large amount`() {
        assertEquals("₹100000.00", fmt(10000000L, "INR"))
    }

    // --- sanitize ---

    @Test
    fun `sanitize normal string unchanged`() {
        assertEquals("Dinner at cafe", san("Dinner at cafe"))
    }

    @Test
    fun `sanitize strips control characters`() {
        assertEquals("hello world", san("hello\u0000 \u0007world"))
    }

    @Test
    fun `sanitize truncates to 100 chars`() {
        val long = "a".repeat(200)
        assertEquals(100, san(long).length)
    }

    @Test
    fun `sanitize preserves unicode`() {
        assertEquals("🍕 café ₹500", san("🍕 café ₹500"))
    }

    @Test
    fun `sanitize empty string`() {
        assertEquals("", san(""))
    }

    @Test
    fun `sanitize strips newlines and tabs`() {
        assertEquals("line1 line2", san("line1\n \tline2"))
    }

    // --- buildExpenseNotification ---

    @Test
    fun `buildExpenseNotification valid expense`() {
        val content = """{"id":"1","amount":50000,"currency":"INR","description":"Dinner","paid_by":"a","split_type":"equal","split_among":[{"pubkey":"a","share":50000}],"timestamp":1}"""
        @Suppress("UNCHECKED_CAST")
        val result = buildExpenseNotification.invoke(ExpenseNotifier, content, "Goa Trip") as Pair<String, String>?
        assertNotNull(result)
        assertEquals("New expense in Goa Trip", result!!.first)
        assertEquals("₹500.00 for Dinner", result.second)
    }

    @Test
    fun `buildExpenseNotification invalid JSON returns null`() {
        val result = buildExpenseNotification.invoke(ExpenseNotifier, "not json", "Group")
        assertNull(result)
    }

    // --- buildSettlementNotification ---

    @Test
    fun `buildSettlementNotification valid settlement`() {
        val content = """{"id":"1","from":"a","to":"b","amount":5000,"currency":"USD","timestamp":1}"""
        @Suppress("UNCHECKED_CAST")
        val result = buildSettlementNotification.invoke(ExpenseNotifier, content, "Trip") as Pair<String, String>?
        assertNotNull(result)
        assertEquals("Settlement in Trip", result!!.first)
        assertEquals("$50.00 settled", result.second)
    }

    @Test
    fun `buildSettlementNotification invalid JSON returns null`() {
        val result = buildSettlementNotification.invoke(ExpenseNotifier, "bad", "Group")
        assertNull(result)
    }
}
