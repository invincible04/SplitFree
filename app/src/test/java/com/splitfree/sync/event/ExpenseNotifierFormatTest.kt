package com.splitfree.sync.event

import android.app.Application
import android.app.Notification
import android.content.Context
import androidx.core.app.NotificationCompat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
class ExpenseNotifierFormatTest {
    private val context: Context get() = RuntimeEnvironment.getApplication()

    private val formatAmount =
        ExpenseNotifier::class.java
            .getDeclaredMethod("formatAmount", Long::class.java, String::class.java)
            .apply { isAccessible = true }

    private val sanitize =
        ExpenseNotifier::class.java
            .getDeclaredMethod("sanitize", String::class.java)
            .apply { isAccessible = true }

    private fun fmt(amount: Long, currency: String) = formatAmount.invoke(ExpenseNotifier, amount, currency)

    private fun san(input: String) = sanitize.invoke(ExpenseNotifier, input) as String

    private fun expense(content: String, group: String) =
        ExpenseNotifier.buildContent(context, "expense", content, group)

    private fun settlement(content: String, group: String) =
        ExpenseNotifier.buildContent(context, "settlement", content, group)

    private val expenseJson =
        """{"id":"1","amount":50000,"currency":"INR",""" +
            """"description":"Dinner","paid_by":"a","split_type":"equal",""" +
            """"split_among":[{"pubkey":"a","share":50000}],"timestamp":1}"""
    private val settlementJson = """{"id":"1","from":"a","to":"b","amount":5000,"currency":"USD","timestamp":1}"""

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
    fun `formatAmount JPY zero decimal currency`() {
        assertEquals("¥500", fmt(500L, "JPY"))
    }

    @Test
    fun `formatAmount lowercase currency treated as uppercase`() {
        assertEquals("₹5.00", fmt(500L, "inr"))
    }

    @Test
    fun `formatAmount large amount`() {
        assertEquals("₹100,000.00", fmt(10000000L, "INR"))
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

    // --- buildContent: expense ---

    @Test
    fun `buildContent valid expense`() {
        val result = expense(expenseJson, "Goa Trip")
        assertNotNull(result)
        assertEquals("New expense in Goa Trip", result!!.title)
        assertEquals("₹500.00 for Dinner", result.text)
    }

    @Test
    fun `buildContent expense public version carries only group name and New expense`() {
        val result = expense(expenseJson, "Goa Trip")!!
        assertEquals("Goa Trip", result.publicTitle)
        assertEquals("New expense", result.publicText)
        assertFalse(result.publicTitle.contains("500"))
        assertFalse(result.publicText.contains("500"))
        assertFalse(result.publicText.contains("Dinner"))
    }

    @Test
    fun `buildContent sanitizes group name in the public version too`() {
        val result = expense(expenseJson, "Goa\u0000 Trip\n")!!
        assertEquals("Goa Trip", result.publicTitle)
    }

    @Test
    fun `buildContent expense invalid JSON returns null`() {
        assertNull(expense("not json", "Group"))
    }

    // --- buildContent: settlement ---

    @Test
    fun `buildContent valid settlement`() {
        val result = settlement(settlementJson, "Trip")
        assertNotNull(result)
        assertEquals("Settlement in Trip", result!!.title)
        assertEquals("$50.00 settled", result.text)
    }

    @Test
    fun `buildContent settlement public version has no amount`() {
        val result = settlement(settlementJson, "Trip")!!
        assertEquals("Trip", result.publicTitle)
        assertEquals("New settlement", result.publicText)
        assertFalse(result.publicText.contains("50"))
    }

    @Test
    fun `buildContent settlement invalid JSON returns null`() {
        assertNull(settlement("bad", "Group"))
    }

    @Test
    fun `buildContent unknown event type returns null`() {
        assertNull(ExpenseNotifier.buildContent(context, "group_meta", "{}", "Group"))
    }

    // --- buildNotification ---

    @Test
    fun `buildNotification is private with a public version`() {
        val notification = ExpenseNotifier.buildNotification(context, expense(expenseJson, "Goa Trip")!!)
        assertEquals(NotificationCompat.VISIBILITY_PRIVATE, notification.visibility)
        assertNotNull(notification.publicVersion)
        assertEquals(NotificationCompat.VISIBILITY_PUBLIC, notification.publicVersion.visibility)
        assertTrue(notification.flags and Notification.FLAG_AUTO_CANCEL != 0)
    }

    @Test
    fun `buildNotification public version shows group name and New expense only`() {
        val notification = ExpenseNotifier.buildNotification(context, expense(expenseJson, "Goa Trip")!!)
        val full = notification.extras
        val public = notification.publicVersion.extras
        assertEquals("New expense in Goa Trip", full.getCharSequence(Notification.EXTRA_TITLE).toString())
        assertEquals("₹500.00 for Dinner", full.getCharSequence(Notification.EXTRA_TEXT).toString())
        assertEquals("Goa Trip", public.getCharSequence(Notification.EXTRA_TITLE).toString())
        assertEquals("New expense", public.getCharSequence(Notification.EXTRA_TEXT).toString())
    }
}
