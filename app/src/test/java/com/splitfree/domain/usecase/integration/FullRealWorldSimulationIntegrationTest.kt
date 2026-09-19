package com.splitfree.domain.usecase.integration

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
 * Opt-in live direct-event scenario with independent in-memory Room stores and production create/join,
 * ledger mutations, explicit outbox flushes, history ingestion and balance calculation.
 * Secure storage and scheduling are substitutes; no UI, device, Keystore or process-restart acceptance.
 * The historical class name is retained for test selectors.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
class FullRealWorldSimulationIntegrationTest {
    @Test
    fun `live direct ledger applies real correction settlement and deletion on both peers`() = runBlocking {
        assumeTrue("Opt in with -DREAL_RELAY_TEST=true", System.getProperty("REAL_RELAY_TEST") == "true")
        withTimeout(240_000) {
            RelayLedgerScenario(false, listOf("wss://nos.lol"), ::NostrClient).use { it.mutations() }
        }
    }
}
