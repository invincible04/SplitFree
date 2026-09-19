package com.splitfree.domain.usecase.expense

import android.app.Application
import com.splitfree.data.nostr.NostrClient
import com.splitfree.test.RelayLedgerScenario
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Opt-in real-relay exchange between two Room peers with gift wrapping enabled. Uses explicit outbox
 * flushes and history pulls, not foreground live subscriptions; no physical-phone, UI or Keystore coverage.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
class RealRelayExpenseFlowIntegrationTest {
    @Test
    fun `gift wrapped expenses traverse outbox relay and production balances on independent peers`() = runBlocking {
        assumeTrue("Opt in with -DREAL_RELAY_TEST=true", System.getProperty("REAL_RELAY_TEST") == "true")
        withTimeout(180_000) {
            RelayLedgerScenario(true, listOf("wss://nos.lol"), ::NostrClient).use { it.bidirectionalExpenses() }
        }
    }
}
