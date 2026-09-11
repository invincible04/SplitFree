package com.splitfree.sync.event

import android.Manifest
import android.app.Application
import android.app.Notification
import android.app.NotificationManager
import androidx.core.app.NotificationCompat
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
class ExpenseNotifierTest {
    private val context by lazy { RuntimeEnvironment.getApplication() }
    private val notifManager by lazy { context.getSystemService(NotificationManager::class.java) }

    @Before
    fun setup() {
        val field = ExpenseNotifier::class.java.getDeclaredField("channelCreated")
        field.isAccessible = true
        field.set(null, false)
        // API 33+: notifications are dead without the runtime permission; grant it for the happy path.
        shadowOf(context).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        shadowOf(notifManager).setNotificationsEnabled(true)
        notifManager.cancelAll()
    }

    @After
    fun teardown() {
        notifManager.cancelAll()
    }

    private val expenseJson =
        """{"id":"e1","amount":1500,"currency":"USD",""" +
            """"description":"Lunch","paid_by":"alice","split_type":"equal",""" +
            """"split_among":[{"pubkey":"bob","share":750}],"timestamp":1000}"""
    private val settlementJson =
        """{"id":"s1","from":"alice","to":"bob","amount":2000,""" +
            """"currency":"EUR","method":"cash","timestamp":1000}"""

    private fun posted(): Notification = shadowOf(notifManager).allNotifications.single()

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
    fun `channel uses resource strings`() {
        ExpenseNotifier.ensureChannel(context)
        val channel = notifManager.notificationChannels.single()
        assertEquals("Expense updates", channel.name)
        assertEquals("Notifications for new expenses and settlements", channel.description)
    }

    @Test
    fun `expense INR currency`() {
        val json =
            """{"id":"e1","amount":1500,"currency":"INR",""" +
                """"description":"Tea","paid_by":"a",""" +
                """"split_type":"equal","split_among":[],"timestamp":1}"""
        ExpenseNotifier.notifyIfNeeded(context, "expense", json, "other", "me", "G")
        assertEquals(1, shadowOf(notifManager).size())
    }

    @Test
    fun `expense GBP currency`() {
        val json =
            """{"id":"e1","amount":999,"currency":"GBP",""" +
                """"description":"Pub","paid_by":"a",""" +
                """"split_type":"equal","split_among":[],"timestamp":1}"""
        ExpenseNotifier.notifyIfNeeded(context, "expense", json, "other", "me", "G")
        assertEquals(1, shadowOf(notifManager).size())
    }

    @Test
    fun `expense unknown currency`() {
        val json =
            """{"id":"e1","amount":500,"currency":"JPY",""" +
                """"description":"Sushi","paid_by":"a",""" +
                """"split_type":"equal","split_among":[],"timestamp":1}"""
        ExpenseNotifier.notifyIfNeeded(context, "expense", json, "other", "me", "G")
        assertEquals(1, shadowOf(notifManager).size())
    }

    // --- permission / enabled gating ---

    @Test
    fun `canNotify true when permitted and enabled`() {
        assertTrue(ExpenseNotifier.canNotify(context))
    }

    @Test
    fun `skips when POST_NOTIFICATIONS is not granted`() {
        shadowOf(context).denyPermissions(Manifest.permission.POST_NOTIFICATIONS)
        assertFalse(ExpenseNotifier.canNotify(context))
        ExpenseNotifier.notifyIfNeeded(context, "expense", expenseJson, "other", "me", "TestGroup")
        assertEquals(0, shadowOf(notifManager).size())
    }

    @Test
    fun `skips when the user disabled notifications for the app`() {
        shadowOf(notifManager).setNotificationsEnabled(false)
        assertFalse(ExpenseNotifier.canNotify(context))
        ExpenseNotifier.notifyIfNeeded(context, "expense", expenseJson, "other", "me", "TestGroup")
        assertEquals(0, shadowOf(notifManager).size())
    }

    @Test
    @Config(sdk = [32])
    fun `canNotify does not require POST_NOTIFICATIONS before API 33`() {
        shadowOf(context).denyPermissions(Manifest.permission.POST_NOTIFICATIONS)
        assertTrue(ExpenseNotifier.canNotify(context))
    }

    // --- lock-screen privacy ---

    @Test
    fun `posted expense notification is private with a redacted public version`() {
        ExpenseNotifier.notifyIfNeeded(context, "expense", expenseJson, "other", "me", "TestGroup")
        val notification = posted()
        assertEquals(NotificationCompat.VISIBILITY_PRIVATE, notification.visibility)
        assertNotNull(notification.publicVersion)
        val public = notification.publicVersion.extras
        assertEquals("TestGroup", public.getCharSequence(Notification.EXTRA_TITLE).toString())
        assertEquals("New expense", public.getCharSequence(Notification.EXTRA_TEXT).toString())
        assertFalse(public.getCharSequence(Notification.EXTRA_TEXT).toString().contains("15"))
        assertFalse(public.getCharSequence(Notification.EXTRA_TEXT).toString().contains("Lunch"))
    }

    @Test
    fun `posted expense notification full version still has amount and description`() {
        ExpenseNotifier.notifyIfNeeded(context, "expense", expenseJson, "other", "me", "TestGroup")
        val full = posted().extras
        assertEquals("New expense in TestGroup", full.getCharSequence(Notification.EXTRA_TITLE).toString())
        assertEquals("$15.00 for Lunch", full.getCharSequence(Notification.EXTRA_TEXT).toString())
    }

    @Test
    fun `posted settlement notification public version has no amount`() {
        ExpenseNotifier.notifyIfNeeded(context, "settlement", settlementJson, "other", "me", "TestGroup")
        val notification = posted()
        assertEquals(NotificationCompat.VISIBILITY_PRIVATE, notification.visibility)
        val public = notification.publicVersion.extras
        assertEquals("TestGroup", public.getCharSequence(Notification.EXTRA_TITLE).toString())
        assertEquals("New settlement", public.getCharSequence(Notification.EXTRA_TEXT).toString())
        assertFalse(public.getCharSequence(Notification.EXTRA_TEXT).toString().contains("20"))
    }
}
