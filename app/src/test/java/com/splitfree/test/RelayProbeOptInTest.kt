package com.splitfree.test

import com.splitfree.data.nostr.relay.RelayIntegrationTest
import com.splitfree.domain.crypto.integration.EndToEndExpenseIntegrationTest
import com.splitfree.domain.crypto.integration.NostrRelayIntegrationTest
import com.splitfree.domain.usecase.expense.RealRelayExpenseFlowIntegrationTest
import com.splitfree.domain.usecase.group.CustomRelayIntegrationTest
import com.splitfree.domain.usecase.group.EndToEndJoinFlowIntegrationTest
import com.splitfree.domain.usecase.group.GiftWrapRelayIntegrationTest
import com.splitfree.domain.usecase.group.RealRelayIntegrationTest
import com.splitfree.domain.usecase.integration.FullRealWorldSimulationIntegrationTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.JUnitCore

/** Without opt-in, direct JUnit selection must report assumption skips and no setup/teardown failures. */
class RelayProbeOptInTest {
    @Test
    fun `every live suite is an actual JUnit assumption skip without explicit opt in`() {
        assumeTrue("This guard check runs only without live opt-in", System.getProperty("REAL_RELAY_TEST") != "true")
        val suites = listOf(
            RelayIntegrationTest::class.java,
            EndToEndExpenseIntegrationTest::class.java,
            NostrRelayIntegrationTest::class.java,
            RealRelayExpenseFlowIntegrationTest::class.java,
            CustomRelayIntegrationTest::class.java,
            EndToEndJoinFlowIntegrationTest::class.java,
            GiftWrapRelayIntegrationTest::class.java,
            RealRelayIntegrationTest::class.java,
            FullRealWorldSimulationIntegrationTest::class.java
        )
        for (suite in suites) {
            val result = JUnitCore.runClasses(suite)
            assertTrue("${suite.simpleName}: ${result.failures}", result.wasSuccessful())
            assertTrue("${suite.simpleName} must contain live tests", result.runCount > 0)
            assertEquals("${suite.simpleName}: every test must skip", result.runCount, result.assumptionFailureCount)
        }
    }
}
