package com.splitfree.sync.worker

import android.content.Context
import android.content.Intent
import com.splitfree.sync.worker.BootReceiver
import com.splitfree.sync.worker.ForegroundSyncService
import io.mockk.every
import io.mockk.mockk
import io.mockk.spyk
import io.mockk.unmockkAll
import io.mockk.verify
import java.io.File
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
