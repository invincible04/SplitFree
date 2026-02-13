package com.splitfree.sync

import android.app.NotificationManager
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf

@RunWith(RobolectricTestRunner::class)
class ExpenseNotifierTest {

    private val context by lazy { RuntimeEnvironment.getApplication() }
    private val notifManager by lazy { context.getSystemService(NotificationManager::class.java) }

    @Before
    fun setup() {
        val field = ExpenseNotifier::class.java.getDeclaredField("channelCreated")
        field.isAccessible = true
        field.set(null, false)
        notifManager.cancelAll()
    }

    @After
    fun teardown() { notifManager.cancelAll() }

    private val expenseJson = """{"id":"e1","amount":1500,"currency":"USD","description":"Lunch","paid_by":"alice","split_type":"equal","split_among":[{"pubkey":"bob","share":750}],"timestamp":1000}"""
    private val settlementJson = """{"id":"s1","from":"alice","to":"bob","amount":2000,"currency":"EUR","method":"cash","timestamp":1000}"""

    @Test
    fun `skips self-authored`() {
        ExpenseNotifier.notifyIfNeeded(context, "expense", expenseJson, "me", "me", "Group")
        assertEquals(0, shadowOf(notifManager).size())
    }

    @Test
    fun `skips null content`() {
        ExpenseNotifier.notifyIfNeeded(context, "expense", null, "other", "me", "Group")
        assertEquals(0, shadowOf(notifManager).size())
    }

    @Test
    fun `skips unknown event type`() {
        ExpenseNotifier.notifyIfNeeded(context, "group_meta", "{}", "other", "me", "Group")
        assertEquals(0, shadowOf(notifManager).size())
    }

    @Test
    fun `shows expense notification USD`() {
        ExpenseNotifier.notifyIfNeeded(context, "expense", expenseJson, "other", "me", "TestGroup")
        assertEquals(1, shadowOf(notifManager).size())
    }

    @Test
    fun `shows settlement notification EUR`() {
        ExpenseNotifier.notifyIfNeeded(context, "settlement", settlementJson, "other", "me", "TestGroup")
        assertEquals(1, shadowOf(notifManager).size())
    }

    @Test
    fun `skips malformed expense JSON`() {
        ExpenseNotifier.notifyIfNeeded(context, "expense", "not json", "other", "me", "Group")
        assertEquals(0, shadowOf(notifManager).size())
    }

    @Test
    fun `skips malformed settlement JSON`() {
        ExpenseNotifier.notifyIfNeeded(context, "settlement", "bad", "other", "me", "Group")
        assertEquals(0, shadowOf(notifManager).size())
    }

    @Test
    fun `ensureChannel creates channel only once`() {
        ExpenseNotifier.ensureChannel(context)
        ExpenseNotifier.ensureChannel(context)
        assertEquals(1, notifManager.notificationChannels.size)
    }

    @Test
    fun `expense INR currency`() {
        val json = """{"id":"e1","amount":1500,"currency":"INR","description":"Tea","paid_by":"a","split_type":"equal","split_among":[],"timestamp":1}"""
        ExpenseNotifier.notifyIfNeeded(context, "expense", json, "other", "me", "G")
        assertEquals(1, shadowOf(notifManager).size())
    }

    @Test
    fun `expense GBP currency`() {
        val json = """{"id":"e1","amount":999,"currency":"GBP","description":"Pub","paid_by":"a","split_type":"equal","split_among":[],"timestamp":1}"""
        ExpenseNotifier.notifyIfNeeded(context, "expense", json, "other", "me", "G")
        assertEquals(1, shadowOf(notifManager).size())
    }

    @Test
    fun `expense unknown currency`() {
        val json = """{"id":"e1","amount":500,"currency":"JPY","description":"Sushi","paid_by":"a","split_type":"equal","split_among":[],"timestamp":1}"""
        ExpenseNotifier.notifyIfNeeded(context, "expense", json, "other", "me", "G")
        assertEquals(1, shadowOf(notifManager).size())
    }
}
