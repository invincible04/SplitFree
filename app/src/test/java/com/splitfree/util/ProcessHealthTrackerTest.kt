package com.splitfree.util

import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import com.splitfree.BuildConfig
import io.mockk.every
import io.mockk.mockk
import io.mockk.unmockkAll
import io.mockk.verify
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [28])
class ProcessHealthTrackerTest {
    private val context: Context get() = RuntimeEnvironment.getApplication()

    @After
    fun tearDown() {
        unmockkAll()
    }

    @Test
    fun `fatal handler records original exception before delegating`() {
        val originalHandler = Thread.getDefaultUncaughtExceptionHandler()
        val installed = ProcessHealthTracker::class.java.getDeclaredField("installed").apply { isAccessible = true }
        val wasInstalled = installed.getBoolean(null)
        val failure = IllegalStateException("original crash")
        var delegated = false
        try {
            installed.setBoolean(null, false)
            Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
                assertSame(failure, throwable)
                assertSame(Thread.currentThread(), thread)
                assertTrue(ProcessHealthTracker.buildReport(context).contains("original crash"))
                delegated = true
            }
            ProcessHealthTracker.install(context)
            Thread.getDefaultUncaughtExceptionHandler()!!.uncaughtException(Thread.currentThread(), failure)
            assertTrue(delegated)
        } finally {
            Thread.setDefaultUncaughtExceptionHandler(originalHandler)
            installed.setBoolean(null, wasInstalled)
        }
    }

    @Test
    fun `diagnostic storage error still delegates the original fatal exception`() {
        val originalHandler = Thread.getDefaultUncaughtExceptionHandler()
        val installed = ProcessHealthTracker::class.java.getDeclaredField("installed").apply { isAccessible = true }
        val wasInstalled = installed.getBoolean(null)
        val brokenContext = mockk<Context>()
        every { brokenContext.applicationContext } returns brokenContext
        every { brokenContext.getSharedPreferences(any(), any()) } throws OutOfMemoryError("diagnostics unavailable")
        val failure = IllegalStateException("original crash")
        var delegated = false
        try {
            installed.setBoolean(null, false)
            Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
                assertSame(failure, throwable)
                assertSame(Thread.currentThread(), thread)
                delegated = true
            }
            ProcessHealthTracker.install(brokenContext)
            Thread.getDefaultUncaughtExceptionHandler()!!.uncaughtException(Thread.currentThread(), failure)
            assertTrue(delegated)
        } finally {
            Thread.setDefaultUncaughtExceptionHandler(originalHandler)
            installed.setBoolean(null, wasInstalled)
        }
    }

    @Test
    fun `crash evidence is committed before returning to fatal handler`() {
        val context = mockk<Context>()
        val prefs = mockk<SharedPreferences>(relaxed = true)
        val editor = mockk<SharedPreferences.Editor>()
        every { context.getSharedPreferences("splitfree_diagnostics", Context.MODE_PRIVATE) } returns prefs
        every { prefs.edit() } returns editor
        every { editor.putLong(any(), any()) } returns editor
        every { editor.putString(any(), any()) } returns editor
        every { editor.commit() } returns true

        ProcessHealthTracker.recordCrash(context, Thread.currentThread(), IllegalStateException("sync failed"))

        verify(exactly = 1) { editor.commit() }
        verify(exactly = 0) { editor.apply() }
        verify { editor.putString("crash_stack", match { it?.contains("sync failed") == true }) }
    }

    @Test
    fun `next launch does not erase the operation preceding a crash`() {
        ProcessHealthTracker.heartbeat(context, "fg_connected")
        ProcessHealthTracker.recordCrash(context, Thread("sync-worker"), IllegalStateException("connection failed"))
        ProcessHealthTracker.heartbeat(context, "app_on_create")

        val report = ProcessHealthTracker.buildReport(context)
        assertTrue(report.contains("Source: app_on_create"))
        assertTrue(report.contains("Heartbeat before crash: fg_connected"))
        assertTrue(report.contains("Thread: sync-worker"))
        assertTrue(report.contains("connection failed"))
        assertTrue(report.contains("Version at crash: ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})"))
    }

    @Test
    fun `stored crash stack and thread name are bounded`() {
        ProcessHealthTracker.recordCrash(context, Thread("t".repeat(200)), IllegalStateException("x".repeat(20_000)))

        val prefs = context.getSharedPreferences("splitfree_diagnostics", Context.MODE_PRIVATE)
        assertEquals(16_000, prefs.getString("crash_stack", "")!!.length)
        assertEquals(128, prefs.getString("crash_thread", "")!!.length)
    }

    @Test
    fun `report identifies installed version without a crash`() {
        val report = ProcessHealthTracker.buildReport(context)
        assertTrue(report.contains("App version: ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})"))
        assertTrue(report.contains("No uncaught crash recorded"))
    }
}
