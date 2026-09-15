package com.splitfree

import android.app.Application
import android.database.sqlite.SQLiteException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
class StartupRecoveryTest {
    @Test
    fun `inbox read failure defers recovery and allows remaining startup`() = runTest {
        val steps = mutableListOf<String>()
        recoverPendingAtStartup {
            steps += "read inbox"
            throw SQLiteException("storage unavailable")
        }
        steps += "relay health"
        assertEquals(listOf("read inbox", "relay health"), steps)
    }

    @Test
    fun `successful recovery runs once`() = runTest {
        var calls = 0
        recoverPendingAtStartup { calls++ }
        assertEquals(1, calls)
    }

    @Test
    fun `cancellation is not swallowed`() = runTest {
        val cancelled = CancellationException("owner stopped")
        try {
            recoverPendingAtStartup { throw cancelled }
            fail("Expected cancellation")
        } catch (actual: CancellationException) {
            assertSame(cancelled, actual)
        }
    }

    @Test
    fun `fatal errors remain fatal`() = runTest {
        val fatal = AssertionError("invariant")
        try {
            recoverPendingAtStartup { throw fatal }
            fail("Expected fatal error")
        } catch (actual: AssertionError) {
            assertSame(fatal, actual)
        }
    }
}
