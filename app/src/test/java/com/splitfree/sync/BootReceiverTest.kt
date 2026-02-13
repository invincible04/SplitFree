package com.splitfree.sync

import android.content.Context
import android.content.Intent
import io.mockk.*
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import java.io.File

@RunWith(RobolectricTestRunner::class)
class BootReceiverTest {

    private val receiver = BootReceiver()

    @After
    fun teardown() { unmockkAll() }

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
    fun `does nothing when no identity file`() {
        val context = spyk(RuntimeEnvironment.getApplication() as Context)
        val filesDir = File("/tmp/test_boot_no_id_${System.nanoTime()}/files")
        every { context.filesDir } returns filesDir
        val intent = Intent(Intent.ACTION_BOOT_COMPLETED)
        receiver.onReceive(context, intent)
        // No startForegroundService since identity file doesn't exist
    }

    @Test
    fun `starts foreground service when identity exists`() {
        val context = spyk(RuntimeEnvironment.getApplication() as Context)
        val base = "/tmp/test_boot_fg_${System.nanoTime()}"
        val prefsDir = File(base, "shared_prefs")
        prefsDir.mkdirs()
        File(prefsDir, "splitfree_identity.xml").createNewFile()
        every { context.filesDir } returns File(base, "files")
        val intent = Intent(Intent.ACTION_BOOT_COMPLETED)
        receiver.onReceive(context, intent)
        verify(exactly = 1) { context.startForegroundService(any()) }
        File(prefsDir, "splitfree_identity.xml").delete()
        prefsDir.delete()
        File(base).delete()
    }

    @Test
    fun `handles exception when checking identity file`() {
        val context = spyk(RuntimeEnvironment.getApplication() as Context)
        every { context.filesDir } throws RuntimeException("no access")
        val intent = Intent(Intent.ACTION_BOOT_COMPLETED)
        receiver.onReceive(context, intent)
        verify(exactly = 0) { context.startForegroundService(any()) }
    }
}
