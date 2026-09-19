package com.splitfree.domain.usecase.group

import com.splitfree.domain.crypto.EventSigner
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Checks live-fixture key reuse offline: signers wipe returned key bytes, so identities must return copies.
 * Runs setup/teardown and signing only, without invoking relay test bodies or connecting.
 */
class GroupRelayFixtureTest {
    private val fixtureClasses = listOf(
        RealRelayIntegrationTest::class.java,
        EndToEndJoinFlowIntegrationTest::class.java,
        CustomRelayIntegrationTest::class.java,
        GiftWrapRelayIntegrationTest::class.java
    )

    @Test
    fun `live fixture identities survive repeated event and auth signing without connecting`() =
        withRelayOptIn("true") {
            fixtureClasses.forEach { fixtureClass ->
                val fixture = fixtureClass.getDeclaredConstructor().newInstance()
                try {
                    fixtureClass.methods.single { it.isAnnotationPresent(Before::class.java) }.invoke(fixture)
                    val signerFields = fixtureClass.declaredFields.filter { it.type == EventSigner::class.java }
                    assertTrue("${fixtureClass.simpleName} must exercise real signers", signerFields.isNotEmpty())
                    signerFields.forEach { field ->
                        field.isAccessible = true
                        val signer = field.get(fixture) as EventSigner
                        repeat(2) {
                            assertTrue(signer.createSignedEvent("offline-fixture", "expense", "payload").verify())
                            assertTrue(signer.createAuthEvent("offline-challenge", "wss://nos.lol").verify())
                        }
                    }
                } finally {
                    fixtureClass.methods.single { it.isAnnotationPresent(After::class.java) }.invoke(fixture)
                }
            }
        }

    private fun withRelayOptIn(value: String, block: () -> Unit) {
        val previous = System.getProperty("REAL_RELAY_TEST")
        try {
            System.setProperty("REAL_RELAY_TEST", value)
            block()
        } finally {
            if (previous == null) {
                System.clearProperty("REAL_RELAY_TEST")
            } else {
                System.setProperty("REAL_RELAY_TEST", previous)
            }
        }
    }
}
