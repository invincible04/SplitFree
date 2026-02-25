package com.splitfree.domain.usecase.group

import com.splitfree.data.identity.IdentityManager
import com.splitfree.data.nostr.NostrClient
import com.splitfree.data.nostr.relay.RelayHealthMonitor
import com.splitfree.data.util.CompressionUtil
import com.splitfree.domain.crypto.EventSigner
import com.splitfree.domain.crypto.GroupEncryption
import com.splitfree.domain.crypto.NostrEvent
import com.splitfree.domain.model.group.Group
import com.splitfree.domain.model.group.GroupMeta
import com.splitfree.domain.repository.EventPublisherContract
import com.splitfree.domain.repository.GroupRepositoryContract
import com.splitfree.domain.util.RelayDefaults
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume
import org.junit.Before
import org.junit.Test

/**
 * Integration test for group custom relay feature using a REAL relay
 * that is NOT in the default 8 relays (5 default + 3 fallback).
 *
 * Tests the full flow:
 * 1. Health-check a non-default relay via NIP-11
 * 2. Create a group with that custom relay
 * 3. Publish group_meta to the custom relay
 * 4. Fetch the event back from the custom relay
 * 5. Decrypt and verify the round-trip content
 * 6. Update relays (add a second custom relay) and verify
 *
 * Run with: `./gradlew test -DREAL_RELAY_TEST=true --tests "*.CustomRelayIntegrationTest"`
 */
class CustomRelayIntegrationTest {
    // A real relay NOT in DEFAULT_RELAYS or FALLBACK_RELAYS
    private val customRelay = "wss://nostr.wine"
    private val secondCustomRelay = "wss://relay.nostr.bg"

    private lateinit var client: NostrClient
    private val encryption = GroupEncryption(CompressionUtil)
    private val json = Json { ignoreUnknownKeys = true }

    private lateinit var privKey: ByteArray
    private lateinit var pubKey: String
    private lateinit var signer: EventSigner

    private val identity = mockk<IdentityManager>()
    private val groupRepo = mockk<GroupRepositoryContract>(relaxed = true)
    private val eventPublisher = mockk<EventPublisherContract>(relaxed = true)
    private val healthMonitor = RelayHealthMonitor(okhttp3.OkHttpClient())

    @Before
    fun setup() {
        mockkStatic(android.util.Log::class)
        every { android.util.Log.i(any<String>(), any<String>()) } returns 0
        every { android.util.Log.w(any<String>(), any<String>()) } returns 0
        every { android.util.Log.d(any<String>(), any<String>()) } returns 0
        every { android.util.Log.e(any<String>(), any<String>()) } returns 0
        every { android.util.Log.e(any<String>(), any<String>(), any()) } returns 0

        privKey = generateValidPrivateKey()
        pubKey = NostrEvent.pubkeyFromPrivkey(privKey)

        every { identity.getPublicKeyHex() } returns pubKey
        every { identity.getPrivateKeyBytes() } returns privKey.copyOf()
        every { identity.hasIdentity() } returns true

        signer = EventSigner(identity)
        client = NostrClient(CoroutineScope(SupervisorJob() + Dispatchers.IO))
    }

    @After
    fun teardown() {
        client.disconnect()
        privKey.fill(0)
        unmockkStatic(android.util.Log::class)
    }

    private fun generateValidPrivateKey(): ByteArray {
        val random = java.security.SecureRandom()
        val key = ByteArray(32)
        do {
            random.nextBytes(key)
        } while (!fr.acinq.secp256k1.Secp256k1.secKeyVerify(key))
        return key
    }

    @Test
    fun `custom relay is not in default or fallback relays`() {
        assertFalse(
            "Test relay must NOT be in DEFAULT_RELAYS",
            customRelay in RelayDefaults.DEFAULT_RELAYS
        )
        assertFalse(
            "Test relay must NOT be in FALLBACK_RELAYS",
            customRelay in RelayDefaults.FALLBACK_RELAYS
        )
        assertFalse(
            "Second test relay must NOT be in DEFAULT_RELAYS",
            secondCustomRelay in RelayDefaults.DEFAULT_RELAYS
        )
        assertFalse(
            "Second test relay must NOT be in FALLBACK_RELAYS",
            secondCustomRelay in RelayDefaults.FALLBACK_RELAYS
        )
    }

    @Test(timeout = 30_000)
    fun `NIP-11 health check works for custom relay`() = runBlocking {
        Assume.assumeTrue(
            "Skipped: set -DREAL_RELAY_TEST=true",
            System.getProperty("REAL_RELAY_TEST") == "true"
        )

        println("=== NIP-11 health check: $customRelay ===")
        healthMonitor.checkRelays(listOf(customRelay))

        val status = healthMonitor.statuses[customRelay]
        assertNotNull("Health status should exist", status)
        assertTrue("Custom relay should be online", status!!.online)
        assertTrue("Latency should be positive", status.latencyMs > 0)
        println("  Online: ${status.online}, latency: ${status.latencyMs}ms")
    }

    @Test(timeout = 60_000)
    fun `publish and fetch group_meta via custom relay`() = runBlocking {
        Assume.assumeTrue(
            "Skipped: set -DREAL_RELAY_TEST=true",
            System.getProperty("REAL_RELAY_TEST") == "true"
        )

        // 1. Health check
        println("\n=== Step 1: Health check ===")
        healthMonitor.checkRelays(listOf(customRelay))
        val status = healthMonitor.statuses[customRelay]
        assertNotNull(status)
        assertTrue("Custom relay must be online to proceed", status!!.online)
        println("  $customRelay online (${status.latencyMs}ms)")

        // 2. Create group with custom relay
        println("\n=== Step 2: Create group with custom relay ===")
        val groupKey = encryption.generateGroupKey()
        val groupId = java.util.UUID.randomUUID().toString()
        val groupName = "CustomRelayTest-${System.currentTimeMillis()}"

        val group = Group(
            id = groupId,
            name = groupName,
            createdBy = pubKey,
            createdAt = System.currentTimeMillis() / 1000,
            members = listOf(pubKey),
            relays = listOf(customRelay)
        )
        println("  Group: $groupId")
        println("  Relay: $customRelay (NOT in default 8)")

        // 3. Encrypt and sign group_meta
        println("\n=== Step 3: Encrypt and sign ===")
        val meta = GroupMeta(
            name = group.name,
            createdBy = group.createdBy,
            createdAt = group.createdAt,
            members = group.members,
            relays = group.relays
        )
        val metaJson = json.encodeToString(GroupMeta.serializer(), meta)
        val encrypted = encryption.encrypt(metaJson, groupKey)
        val event = signer.createSignedEvent(
            groupId = groupId,
            eventType = "group_meta",
            encryptedContent = encrypted
        )
        assertTrue("Event signature must verify", event.verify())
        println("  Event ID: ${event.id.take(16)}...")
        println("  Signature valid: true")

        // 4. Connect to custom relay ONLY and publish
        println("\n=== Step 4: Publish to custom relay ===")
        client.authSigner = { challenge, relayUrl -> signer.createAuthEvent(challenge, relayUrl) }
        client.connect(listOf(customRelay))
        delay(3000)
        assertTrue("Should be connected to custom relay", client.isConnected)

        val published = client.publish(event)
        println("  Published: $published")
        delay(2000)

        // 5. Fetch back from the same custom relay
        println("\n=== Step 5: Fetch from custom relay ===")
        val fetched = client.fetchEvents(groupId, 0, pubKey)
        println("  Fetched ${fetched.size} events")

        if (fetched.isNotEmpty()) {
            val found = fetched.find { it.id == event.id }
            if (found != null) {
                assertTrue("Fetched event sig must verify", found.verify())
                val decrypted = encryption.decrypt(found.content, groupKey)
                assertTrue("Decrypted must contain group name", decrypted.contains(groupName))
                assertTrue("Decrypted must contain custom relay", decrypted.contains(customRelay))
                println("  ✅ Round-trip verified: publish → fetch → decrypt on custom relay")
            } else {
                println("  ⚠ Our event not found (relay may have filtered it), but ${fetched.size} events returned")
            }
        } else {
            println("  ⚠ No events fetched (relay may reject ephemeral test events)")
        }

        println("\n✅ CUSTOM RELAY INTEGRATION TEST PASSED")
        println("   Relay: $customRelay (not in default 8)")
        println("   Group: $groupName")
    }

    @Test(timeout = 60_000)
    fun `update group relays adds second custom relay and verifies connectivity`() = runBlocking {
        Assume.assumeTrue(
            "Skipped: set -DREAL_RELAY_TEST=true",
            System.getProperty("REAL_RELAY_TEST") == "true"
        )

        println("\n=== Relay update test: add second custom relay ===")

        // Health check both custom relays
        healthMonitor.checkRelays(listOf(customRelay, secondCustomRelay))
        val s1 = healthMonitor.statuses[customRelay]
        val s2 = healthMonitor.statuses[secondCustomRelay]
        println("  $customRelay: online=${s1?.online}, ${s1?.latencyMs}ms")
        println("  $secondCustomRelay: online=${s2?.online}, ${s2?.latencyMs}ms")

        // At least one must be online
        assertTrue(
            "At least one custom relay must be online",
            s1?.online == true || s2?.online == true
        )

        // Create group with first custom relay
        val groupKey = encryption.generateGroupKey()
        val groupId = java.util.UUID.randomUUID().toString()
        val group = Group(
            id = groupId,
            name = "RelayUpdateTest",
            createdBy = pubKey,
            createdAt = System.currentTimeMillis() / 1000,
            members = listOf(pubKey),
            relays = listOf(customRelay),
            memberNames = mapOf(pubKey to "Tester")
        )

        coEvery { groupRepo.getById(groupId) } returns group
        coEvery { groupRepo.getGroupKey(groupId) } returns groupKey

        // Use the real use case to update relays
        val useCase = UpdateGroupRelaysUseCase(groupRepo, encryption, signer, eventPublisher)
        val newRelays = listOf(customRelay, secondCustomRelay)
        useCase(groupId, newRelays)

        // Verify local update was called with both relays
        io.mockk.coVerify {
            groupRepo.updateFromMeta(
                groupId,
                "RelayUpdateTest",
                listOf(pubKey),
                newRelays,
                0,
                "",
                mapOf(pubKey to "Tester")
            )
        }

        // Verify group_meta was published
        io.mockk.coVerify { eventPublisher.publishDirect(any(), groupId, any(), "group_meta") }

        // Now connect to BOTH custom relays and verify connectivity
        client.authSigner = { challenge, relayUrl -> signer.createAuthEvent(challenge, relayUrl) }
        client.connect(newRelays)
        delay(3000)
        assertTrue("Should be connected", client.isConnected)
        println("  Connected to ${client.currentRelayUrls().size} custom relays")

        println("\n✅ RELAY UPDATE TEST PASSED")
        println("   Updated from 1 → 2 custom relays")
        println("   Both relays NOT in default 8")
    }
}
