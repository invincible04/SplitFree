package com.splitfree.sync.worker

import android.app.Application
import android.content.Context
import android.content.Intent
import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.spyk
import io.mockk.unmockkAll
import io.mockk.verify
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [28, 35])
class BootReceiverTest {
    private val receiver = BootReceiver()

    @Before
    fun setup() {
        mockkObject(SyncScheduler)
        every { SyncScheduler.scheduleImmediateSync(any()) } just Runs
    }

    @After
    fun teardown() {
        unmockkAll()
    }

    @Test
    fun `ignores non-boot intents`() {
        val context = mockk<Context>(relaxed = true)
        val intent = mockk<Intent>()
        every { intent.action } returns "some.other.action"
        receiver.onReceive(context, intent)
        verify(exactly = 0) { context.startService(any()) }
        verify(exactly = 0) { context.startForegroundService(any()) }
    }

    @Test
    fun `does nothing when no identity flag`() {
        val context = spyk(RuntimeEnvironment.getApplication() as Context)
        // Don't set the flag — default is false
        val intent = Intent(Intent.ACTION_BOOT_COMPLETED)
        receiver.onReceive(context, intent)
        verify(exactly = 0) { context.startForegroundService(any()) }
        verify(exactly = 0) { context.startService(any()) }
        verify(exactly = 0) { SyncScheduler.scheduleImmediateSync(any()) }
    }

    @Test
    fun `does nothing when identity flag is explicitly false`() {
        val context = spyk(RuntimeEnvironment.getApplication() as Context)
        context.getSharedPreferences("splitfree_boot", Context.MODE_PRIVATE)
            .edit().putBoolean("identity_created", false).commit()
        val intent = Intent(Intent.ACTION_BOOT_COMPLETED)
        receiver.onReceive(context, intent)
        verify(exactly = 0) { context.startForegroundService(any()) }
        context.getSharedPreferences("splitfree_boot", Context.MODE_PRIVATE)
            .edit().clear().commit()
    }

    @Test
    fun `schedules work instead of foreground service when identity flag is set`() {
        val context = spyk(RuntimeEnvironment.getApplication() as Context)
        // Set the boot flag like IdentityManager would
        context.getSharedPreferences("splitfree_boot", Context.MODE_PRIVATE)
            .edit()
            .putBoolean("identity_created", true)
            .commit()
        val intent = Intent(Intent.ACTION_BOOT_COMPLETED)
        receiver.onReceive(context, intent)
        verify(exactly = 0) { context.startForegroundService(any()) }
        verify(exactly = 1) { SyncScheduler.scheduleImmediateSync(context) }
        // Cleanup
        context.getSharedPreferences("splitfree_boot", Context.MODE_PRIVATE)
            .edit().clear().commit()
    }

    @Test
    fun `handles exception when checking identity flag`() {
        val context = mockk<Context>(relaxed = true)
        every { context.getSharedPreferences(any(), any()) } throws RuntimeException("no access")
        val intent = mockk<Intent>()
        every { intent.action } returns Intent.ACTION_BOOT_COMPLETED
        receiver.onReceive(context, intent)
        verify(exactly = 0) { context.startForegroundService(any()) }
    }
}
