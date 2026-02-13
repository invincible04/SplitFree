package com.splitfree.sync

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import io.mockk.*
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.io.File

class BootReceiverTest {

    private val context = mockk<Context>(relaxed = true)
    private val intent = mockk<Intent>()
    private val receiver = BootReceiver()

    @Before
    fun setup() {
        mockkStatic(Build.VERSION::class)
    }

    @After
    fun teardown() {
        unmockkAll()
    }

    @Test
    fun `ignores non-boot intents`() {
        every { intent.action } returns "some.other.action"
        receiver.onReceive(context, intent)
        verify(exactly = 0) { context.startService(any()) }
        verify(exactly = 0) { context.startForegroundService(any()) }
    }

    @Test
    fun `does nothing when no identity file exists`() {
        every { intent.action } returns Intent.ACTION_BOOT_COMPLETED
        val filesDir = mockk<File>()
        every { context.filesDir } returns filesDir
        every { filesDir.parent } returns "/data/data/com.splitfree"
        // The identity file won't exist in test
        receiver.onReceive(context, intent)
    }
}
