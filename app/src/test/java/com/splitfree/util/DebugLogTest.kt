package com.splitfree.util

import android.util.Log
import com.splitfree.BuildConfig
import io.mockk.every
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import io.mockk.verify
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test

class DebugLogTest {
    private val pubkey = "7f7ff03d123792d6ac594bfa67bf6d0c0ab55b6b1fdb6249303fe861f1ccba9a"
    private val eventId = "ABCDEF0123456789ABCDEF0123456789ABCDEF0123456789ABCDEF0123456789"
    private val inviteLink = "splitfree://join?d=AgESNFZ4kKvN7wESNFZ4kKvN7wAAAAAAAGeAAAAB-_x0"

    @Before
    fun setup() {
        mockkStatic(Log::class)
        every { Log.d(any(), any<String>()) } returns 0
        every { Log.i(any(), any<String>()) } returns 0
        every { Log.w(any(), any<String>()) } returns 0
        every { Log.w(any(), any<String>(), any()) } returns 0
        every { Log.e(any(), any<String>()) } returns 0
        every { Log.e(any(), any<String>(), any()) } returns 0
        DebugLog.clear()
    }

    @After
    fun teardown() {
        DebugLog.clear()
        unmockkAll()
    }

    // --- sanitize ---

    @Test
    fun `sanitize redacts a 64-hex pubkey to its first 8 chars`() {
        assertEquals("author=7f7ff03d…", DebugLog.sanitize("author=$pubkey"))
    }

    @Test
    fun `sanitize redacts uppercase hex event ids`() {
        assertEquals("event ABCDEF01… stored", DebugLog.sanitize("event $eventId stored"))
    }

    @Test
    fun `sanitize redacts every hex identifier in the message`() {
        val out = DebugLog.sanitize("from $pubkey to $eventId")
        assertEquals("from 7f7ff03d… to ABCDEF01…", out)
        assertFalse(out.contains(pubkey))
        assertFalse(out.contains(eventId))
    }

    @Test
    fun `sanitize collapses a 128-hex signature instead of leaking half of it`() {
        val sig = pubkey + eventId.lowercase()
        assertEquals("sig=7f7ff03d…", DebugLog.sanitize("sig=$sig"))
    }

    @Test
    fun `sanitize leaves short hex prefixes and ordinary text alone`() {
        val msg = "Parsed invite: creator=7f7ff03d epoch=2 relays=3 name=Goa Trip"
        assertEquals(msg, DebugLog.sanitize(msg))
    }

    @Test
    fun `sanitize leaves UUIDs alone`() {
        val msg = "Already in group 123e4567-e89b-12d3-a456-426614174000"
        assertEquals(msg, DebugLog.sanitize(msg))
    }

    @Test
    fun `sanitize redacts the invite link payload`() {
        assertEquals(
            "Joining via link: splitfree://join?d=[REDACTED]",
            DebugLog.sanitize("Joining via link: $inviteLink")
        )
    }

    @Test
    fun `sanitize redacts an invite link embedded in a longer message and keeps the tail`() {
        val out = DebugLog.sanitize("QR scanned: $inviteLink then more")
        assertEquals("QR scanned: splitfree://join?d=[REDACTED] then more", out)
    }

    @Test
    fun `sanitize redacts a truncated invite link with trailing ellipsis`() {
        val out = DebugLog.sanitize("Pasted invite: ${inviteLink.take(30)}...")
        assertEquals("Pasted invite: splitfree://join?d=[REDACTED]", out)
    }

    @Test
    fun `sanitize is idempotent`() {
        val once = DebugLog.sanitize("x $pubkey $inviteLink")
        assertEquals(once, DebugLog.sanitize(once))
    }

    @Test
    fun `sanitize passes empty string through`() {
        assertEquals("", DebugLog.sanitize(""))
    }

    // --- logcat forwarding ---

    @Test
    fun `w forwards a sanitized message to logcat`() {
        DebugLog.w("T", "bad author $pubkey via $inviteLink")
        verify(exactly = 1) { Log.w("T", "bad author 7f7ff03d… via splitfree://join?d=[REDACTED]") }
        verify(exactly = 0) { Log.w("T", match<String> { it.contains(pubkey) }) }
    }

    @Test
    fun `e forwards a sanitized message to logcat`() {
        DebugLog.e("T", "Join failed for $eventId")
        verify(exactly = 1) { Log.e("T", "Join failed for ABCDEF01…") }
    }

    @Test
    fun `e with throwable sanitizes the message and keeps the throwable`() {
        val tr = IllegalStateException("boom")
        DebugLog.e("T", "Join failed: $inviteLink", tr)
        verify(exactly = 1) { Log.e("T", "Join failed: splitfree://join?d=[REDACTED]", tr) }
    }

    @Test
    fun `w with throwable sanitizes the message and keeps the throwable`() {
        val tr = IllegalStateException("boom")
        DebugLog.w("T", "Retry for $pubkey", tr)
        verify(exactly = 1) { Log.w("T", "Retry for 7f7ff03d…", tr) }
    }

    @Test
    fun `d and i only reach logcat in debug builds`() {
        // Unit tests run against the debug variant, so this pins the DEBUG branch; the release
        // branch (return 0 without touching android.util.Log) is unreachable from a debug test.
        DebugLog.d("T", "debug $pubkey")
        DebugLog.i("T", "info $pubkey")
        if (BuildConfig.DEBUG) {
            verify(exactly = 1) { Log.d("T", "debug $pubkey") }
            verify(exactly = 1) { Log.i("T", "info $pubkey") }
        } else {
            verify(exactly = 0) { Log.d(any(), any<String>()) }
            verify(exactly = 0) { Log.i(any(), any<String>()) }
        }
    }

    // --- in-app buffer ---

    @Test
    fun `buffer keeps the unsanitized message for the debug screen`() {
        assumeTrue(BuildConfig.DEBUG)
        DebugLog.w("T", "author $pubkey")
        val entry = DebugLog.entries.last()
        assertEquals('W', entry.level)
        assertEquals("T", entry.tag)
        assertEquals("author $pubkey", entry.message)
    }

    @Test
    fun `buffer records all levels and bumps revision`() {
        assumeTrue(BuildConfig.DEBUG)
        val before = DebugLog.revision
        DebugLog.d("T", "d")
        DebugLog.i("T", "i")
        DebugLog.w("T", "w")
        DebugLog.e("T", "e")
        assertEquals(listOf('D', 'I', 'W', 'E'), DebugLog.entries.map { it.level })
        assertTrue(DebugLog.revision >= before + 4)
    }

    @Test
    fun `clear empties the buffer`() {
        DebugLog.i("T", "x")
        DebugLog.clear()
        assertTrue(DebugLog.entries.isEmpty())
    }
}
