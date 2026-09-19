package com.splitfree.domain.usecase.group

import com.splitfree.data.identity.IdentityManager
import com.splitfree.data.nostr.NostrClient
import com.splitfree.data.nostr.relay.RelayHealthMonitor
import com.splitfree.data.util.CompressionUtil
import com.splitfree.domain.crypto.EventSigner
import com.splitfree.domain.crypto.GroupEncryption
import com.splitfree.domain.crypto.NostrEvent
import com.splitfree.domain.model.group.GroupMeta
import com.splitfree.test.RelayProbeAssertions.assertAccepted
import com.splitfree.test.RelayProbeAssertions.requireEvents
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume
import org.junit.Before
import org.junit.Test

/**
 * Live NIP-11 checks, encrypted metadata round-trip and manual expense-payload transfer.
 * Transfer is explicit test-driven republishing, not UpdateGroupRelaysUseCase or self-heal.
 * Opt in with `-DREAL_RELAY_TEST=true`; rejection, unavailable relays and incomplete fetches fail.
 */
class CustomRelayIntegrationTest {
    private lateinit var relayScope: CoroutineScope
    private var logMocked = false

    private val relayA = "wss://nos.lol"
    private val relayB = "wss://purplerelay.com"

    private lateinit var client: NostrClient
    private val encryption = GroupEncryption(CompressionUtil)
    private val json = Json { ignoreUnknownKeys = true }

    private lateinit var privKey: ByteArray
    private lateinit var pubKey: String
    private lateinit var signer: EventSigner

    private val identity = mockk<IdentityManager>()
    private lateinit var healthMonitor: RelayHealthMonitor

    @Before
    fun setup() {
        Assume.assumeTrue("Skipped: set -DREAL_RELAY_TEST=true", System.getProperty("REAL_RELAY_TEST") == "true")

        mockkStatic(android.util.Log::class)
        logMocked = true
        relayScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        every { android.util.Log.i(any<String>(), any<String>()) } returns 0
        every { android.util.Log.w(any<String>(), any<String>()) } returns 0
        every { android.util.Log.d(any<String>(), any<String>()) } returns 0
        every { android.util.Log.e(any<String>(), any<String>()) } returns 0
        every { android.util.Log.e(any<String>(), any<String>(), any()) } returns 0
        every { android.util.Log.w(any<String>(), any<String>(), any()) } returns 0

        privKey = generateValidPrivateKey()
        pubKey = NostrEvent.pubkeyFromPrivkey(privKey)

        every { identity.getPublicKeyHex() } returns pubKey
        every { identity.getPrivateKeyBytes() } answers { privKey.copyOf() }
        every { identity.hasIdentity() } returns true

        healthMonitor = RelayHealthMonitor(okhttp3.OkHttpClient())
        signer = EventSigner(identity)
        client = NostrClient(relayScope)
        client.authSigner = { c, r -> signer.createAuthEvent(c, r) }
    }

    @After
    fun teardown() {
        if (::client.isInitialized) client.disconnect()
        if (::relayScope.isInitialized) relayScope.cancel()
        if (::privKey.isInitialized) privKey.fill(0)
        if (logMocked) unmockkStatic(android.util.Log::class)
    }

    private fun generateValidPrivateKey(): ByteArray {
        val random = java.security.SecureRandom()
        val key = ByteArray(32)
        do {
            random.nextBytes(key)
        } while (!fr.acinq.secp256k1.Secp256k1.secKeyVerify(key))
        return key
    }

    private suspend fun freshClient(relays: List<String>): NostrClient {
        client.disconnect()
        client = NostrClient(relayScope)
        client.authSigner = { c, r -> signer.createAuthEvent(c, r) }
        client.connect(relays)
        withTimeout(15_000) { client.connectionState.first { it } }
        return client
    }

    private suspend fun fetchComplete(relay: String, groupId: String): List<NostrEvent> {
        val result = freshClient(listOf(relay)).fetchEvents(groupId, 0, pubKey)
        assertTrue("Fetch must complete on $relay", result.complete)
        return result.events
    }

    @Test(timeout = 30_000)
    fun `NIP-11 health check returns latency and paid status`() = runBlocking {
        healthMonitor.checkRelays(listOf(relayA, relayB))

        listOf(relayA, relayB).forEach { url ->
            val s = healthMonitor.statuses[url]
            assertNotNull("Status should exist for $url", s)
            assertTrue("$url should be online", s!!.online)
            assertTrue("Latency should be positive", s.latencyMs > 0)
            println("$url: ${s.latencyMs}ms, paid=${s.paid}, nips=${s.supportedNips.take(5)}")
        }
    }

    @Test(timeout = 60_000)
    fun `publish group_meta to relay and verify signature round-trip`() = runBlocking {
        val groupKey = encryption.generateGroupKey()
        val groupId = "custom-${System.currentTimeMillis()}"
        val groupName = "CustomRelayTest-${System.currentTimeMillis()}"

        val meta = GroupMeta(
            name = groupName,
            createdBy = pubKey,
            createdAt = System.currentTimeMillis() / 1000,
            members = listOf(pubKey),
            relays = listOf(relayA)
        )
        val encrypted = encryption.encrypt(json.encodeToString(GroupMeta.serializer(), meta), groupKey)
        val event = signer.createSignedEvent(groupId = groupId, eventType = "group_meta", encryptedContent = encrypted)
        assertTrue("Event must verify locally", event.verify())

        client.connect(listOf(relayA))
        withTimeout(15_000) { client.connectionState.first { it } }
        assertTrue("Should be connected", client.isConnected)
        assertAccepted(client.publish(event))

        val receivedMeta = requireEvents(listOf(event), fetchComplete(relayA, groupId)).single()
        val decrypted = encryption.decrypt(receivedMeta.content, groupKey)
        assertEquals(meta, json.decodeFromString<GroupMeta>(decrypted))
    }

    @Test(timeout = 120_000)
    fun `manual relay transfer republishes all three fetched events from A to B`() = runBlocking {
        val groupKey = encryption.generateGroupKey()
        val groupId = "transfer-${java.util.UUID.randomUUID()}"
        freshClient(listOf(relayA))

        val payloads = (1..3).map { i -> """{"id":"exp-$i","amount":${i * 1000}}""" }
        val events = payloads.map { payload ->
            signer.createSignedEvent(
                groupId = groupId,
                eventType = "expense",
                encryptedContent = encryption.encrypt(payload, groupKey)
            ).also { assertAccepted(client.publish(it)) }
        }
        val onA = requireEvents(events, fetchComplete(relayA, groupId))
        val onBBefore = fetchComplete(relayB, groupId)
        assertTrue("New transfer group must not already exist on relay B", onBBefore.isEmpty())

        // Publish to B alone: an acceptance from A cannot mask rejection by B.
        onA.forEach { assertAccepted(client.publish(it)) }
        val onBAfter = requireEvents(events, fetchComplete(relayB, groupId))
        events.zip(payloads).forEach { (expected, payload) ->
            val received = onBAfter.single { it.id == expected.id }
            assertEquals(payload, encryption.decrypt(received.content, groupKey))
        }
        println("Manual relay transfer verified: all three exact events fetched from A and B")
    }

    @Test(timeout = 30_000)
    fun `NIP-11 detects paid relay vs free relay`() = runBlocking {
        healthMonitor.checkRelays(listOf(relayA, "wss://nostr.wine"))

        val free = healthMonitor.statuses[relayA]
        assertNotNull("Free relay status must exist", free)
        assertTrue("$relayA must be online", free!!.online)
        assertFalse("$relayA should be free", free.paid)

        val paid = healthMonitor.statuses["wss://nostr.wine"]
        assertNotNull("Paid relay status must exist", paid)
        assertTrue("nostr.wine must be online", paid!!.online)
        assertTrue("nostr.wine should be paid", paid.paid)
    }
}
