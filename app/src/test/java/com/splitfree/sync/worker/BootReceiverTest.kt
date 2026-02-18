package com.splitfree.sync.worker

import android.content.Context
import android.content.Intent
import io.mockk.every
import io.mockk.mockk
import io.mockk.spyk
import io.mockk.unmockkAll
import io.mockk.verify
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class BootReceiverTest {
    private val receiver = BootReceiver()

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
    fun `starts foreground service when identity flag is set`() {
        val context = spyk(RuntimeEnvironment.getApplication() as Context)
        // Set the boot flag like IdentityManager would
        context.getSharedPreferences("splitfree_boot", Context.MODE_PRIVATE)
            .edit()
            .putBoolean("identity_created", true)
            .commit()
        val intent = Intent(Intent.ACTION_BOOT_COMPLETED)
        receiver.onReceive(context, intent)
        verify(exactly = 1) { context.startForegroundService(any()) }
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
