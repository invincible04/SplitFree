package com.splitfree.sync

import android.app.NotificationManager
import android.content.Context
import io.mockk.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class ExpenseNotifierTest {

    private lateinit var context: Context
    private val notifManager = mockk<NotificationManager>(relaxed = true)

    @Before
    fun setup() {
        val realCtx = RuntimeEnvironment.getApplication()
        context = mockk<Context>(relaxed = true)
        every { context.getSystemService(NotificationManager::class.java) } returns notifManager
        every { context.applicationInfo } returns realCtx.applicationInfo
        every { context.packageName } returns realCtx.packageName
        every { context.resources } returns realCtx.resources
        val field = ExpenseNotifier::class.java.getDeclaredField("channelCreated")
        field.isAccessible = true
        field.set(null, false)
    }

    @After
    fun teardown() { unmockkAll() }

    private val expenseJson = """{"id":"e1","amount":1500,"currency":"USD","description":"Lunch","paid_by":"alice","split_type":"equal","split_among":[{"pubkey":"bob","share":750}],"timestamp":1000}"""
    private val settlementJson = """{"id":"s1","from":"alice","to":"bob","amount":2000,"currency":"EUR","method":"cash","timestamp":1000}"""

    @Test
    fun `skips self-authored`() {
        ExpenseNotifier.notifyIfNeeded(context, "expense", expenseJson, "me", "me", "Group")
        verify(exactly = 0) { notifManager.notify(any(), any()) }
    }

    @Test
    fun `skips null content`() {
        ExpenseNotifier.notifyIfNeeded(context, "expense", null, "other", "me", "Group")
        verify(exactly = 0) { notifManager.notify(any(), any()) }
    }

    @Test
    fun `skips unknown event type`() {
        ExpenseNotifier.notifyIfNeeded(context, "group_meta", "{}", "other", "me", "Group")
        verify(exactly = 0) { notifManager.notify(any(), any()) }
    }

    @Test
    fun `shows expense notification USD`() {
        ExpenseNotifier.notifyIfNeeded(context, "expense", expenseJson, "other", "me", "TestGroup")
        verify(exactly = 1) { notifManager.notify(any(), any()) }
    }

    @Test
    fun `shows settlement notification EUR`() {
        ExpenseNotifier.notifyIfNeeded(context, "settlement", settlementJson, "other", "me", "TestGroup")
        verify(exactly = 1) { notifManager.notify(any(), any()) }
    }

    @Test
    fun `skips malformed expense JSON`() {
        ExpenseNotifier.notifyIfNeeded(context, "expense", "not json", "other", "me", "Group")
        verify(exactly = 0) { notifManager.notify(any(), any()) }
    }

    @Test
    fun `skips malformed settlement JSON`() {
        ExpenseNotifier.notifyIfNeeded(context, "settlement", "bad", "other", "me", "Group")
        verify(exactly = 0) { notifManager.notify(any(), any()) }
    }

    @Test
    fun `ensureChannel creates channel only once`() {
        ExpenseNotifier.ensureChannel(context)
        ExpenseNotifier.ensureChannel(context)
        verify(exactly = 1) { notifManager.createNotificationChannel(any()) }
    }

    @Test
    fun `expense INR currency`() {
        val json = """{"id":"e1","amount":1500,"currency":"INR","description":"Tea","paid_by":"a","split_type":"equal","split_among":[],"timestamp":1}"""
        ExpenseNotifier.notifyIfNeeded(context, "expense", json, "other", "me", "G")
        verify(exactly = 1) { notifManager.notify(any(), any()) }
    }

    @Test
    fun `expense GBP currency`() {
        val json = """{"id":"e1","amount":999,"currency":"GBP","description":"Pub","paid_by":"a","split_type":"equal","split_among":[],"timestamp":1}"""
        ExpenseNotifier.notifyIfNeeded(context, "expense", json, "other", "me", "G")
        verify(exactly = 1) { notifManager.notify(any(), any()) }
    }

    @Test
    fun `expense unknown currency`() {
        val json = """{"id":"e1","amount":500,"currency":"JPY","description":"Sushi","paid_by":"a","split_type":"equal","split_among":[],"timestamp":1}"""
        ExpenseNotifier.notifyIfNeeded(context, "expense", json, "other", "me", "G")
        verify(exactly = 1) { notifManager.notify(any(), any()) }
    }
}
