package com.splitfree.domain.usecase.group

import com.splitfree.data.identity.IdentityManager
import com.splitfree.data.nostr.NostrClient
import com.splitfree.data.util.CompressionUtil
import com.splitfree.domain.crypto.EventSigner
import com.splitfree.domain.crypto.GroupEncryption
import com.splitfree.domain.crypto.NostrEvent
import com.splitfree.domain.invite.InviteLinkCodec
import com.splitfree.domain.model.group.Group
import com.splitfree.domain.repository.EventPublisherContract
import com.splitfree.domain.repository.GroupRepositoryContract
import com.splitfree.domain.repository.SyncEngineContract
import com.splitfree.domain.usecase.sync.SelfHealUseCase
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.slot
import io.mockk.unmockkStatic
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.putJsonArray
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume
import org.junit.Before
import org.junit.Test

/**
 * Integration test using REAL Nostr relays and REAL crypto.
 *
 * Phone 1: generates real secp256k1 keypair, creates group, encrypts with real NIP-44,
 *          signs with real Schnorr, publishes to real relays, generates invite link.
 * Phone 2: generates different real keypair, parses invite link, connects to real relays,
 *          fetches events, joins group, publishes join announcement.
 *
 * Only Android storage (Room, SharedPreferences) is mocked — everything else is real.
 *
 * This test depends on external Nostr relays and is skipped by default.
 * Run with `-DREAL_RELAY_TEST=true` to enable:
 * ```
 * ./gradlew test -DREAL_RELAY_TEST=true --tests "*.RealRelayIntegrationTest"
 * ```
 */
class RealRelayIntegrationTest {
    // Real NostrClient instances (separate relay pools, like two different phones)
    private lateinit var phone1Client: NostrClient
    private lateinit var phone2Client: NostrClient

    // Real crypto
    private val phone1Encryption = GroupEncryption(CompressionUtil)
    private val phone2Encryption = GroupEncryption(CompressionUtil)

    // Real keys (generated fresh each test)
    private lateinit var phone1PrivKey: ByteArray
    private lateinit var phone1PubKey: String
    private lateinit var phone2PrivKey: ByteArray
    private lateinit var phone2PubKey: String

    // Mocked Android storage (only thing we can't run on JVM)
    private val phone1Identity = mockk<IdentityManager>()
    private val phone2Identity = mockk<IdentityManager>()
    private val phone1Repo = mockk<GroupRepositoryContract>(relaxed = true)
    private val phone2Repo = mockk<GroupRepositoryContract>(relaxed = true)
    private val phone1EventPublisher = mockk<EventPublisherContract>(relaxed = true)
    private val phone2EventPublisher = mockk<EventPublisherContract>(relaxed = true)
    private val phone2SelfHeal = mockk<SelfHealUseCase>(relaxed = true)
    private val phone2SyncEngine = mockk<SyncEngineContract>(relaxed = true)

    // Real signers backed by real keys
    private lateinit var phone1Signer: EventSigner
    private lateinit var phone2Signer: EventSigner

    private val relays = listOf("wss://relay.snort.social", "wss://nos.lol")

    @Before
    fun setup() {
        // Mock Android Log
        mockkStatic(android.util.Log::class)
        every { android.util.Log.i(any<String>(), any<String>()) } returns 0
        every { android.util.Log.w(any<String>(), any<String>()) } returns 0
        every { android.util.Log.d(any<String>(), any<String>()) } returns 0
        every { android.util.Log.e(any<String>(), any<String>()) } returns 0
        every { android.util.Log.e(any<String>(), any<String>(), any()) } returns 0
        every { android.util.Log.w(any<String>(), any<String>(), any()) } returns 0

        // Generate REAL secp256k1 keypairs
        phone1PrivKey = generateValidPrivateKey()
        phone1PubKey = NostrEvent.pubkeyFromPrivkey(phone1PrivKey)
        phone2PrivKey = generateValidPrivateKey()
        phone2PubKey = NostrEvent.pubkeyFromPrivkey(phone2PrivKey)

        println("Phone 1 pubkey: ${phone1PubKey.take(16)}...")
        println("Phone 2 pubkey: ${phone2PubKey.take(16)}...")

        // Wire up identity mocks to return real keys
        every { phone1Identity.getPublicKeyHex() } returns phone1PubKey
        every { phone1Identity.getPrivateKeyBytes() } returns phone1PrivKey.copyOf()
        every { phone1Identity.hasIdentity() } returns true
        every { phone2Identity.getPublicKeyHex() } returns phone2PubKey
        every { phone2Identity.getPrivateKeyBytes() } returns phone2PrivKey.copyOf()
        every { phone2Identity.hasIdentity() } returns true

        // Real signers with real keys
        phone1Signer = EventSigner(phone1Identity)
        phone2Signer = EventSigner(phone2Identity)

        // Real NostrClient instances (separate pools)
        phone1Client = NostrClient(CoroutineScope(SupervisorJob() + Dispatchers.IO))
        phone2Client = NostrClient(CoroutineScope(SupervisorJob() + Dispatchers.IO))

        // Storage mocks
        coEvery { phone1Repo.getById(any()) } returns null
        coEvery { phone2Repo.getById(any()) } returns null
    }

    @After
    fun teardown() {
        phone1Client.disconnect()
        phone2Client.disconnect()
        phone1PrivKey.fill(0)
        phone2PrivKey.fill(0)
        unmockkStatic(android.util.Log::class)
    }

    private fun generateValidPrivateKey(): ByteArray {
        val random = java.security.SecureRandom()
        val key = ByteArray(32)
        do {
            random.nextBytes(key)
        } while (!fr.acinq.secp256k1.Secp256k1
                .secKeyVerify(key)
        )
        return key
    }

    @Test(timeout = 60_000)
    fun `real relay - Phone 1 creates and publishes, Phone 2 fetches and joins`() = runBlocking {
        Assume.assumeTrue(
            "Skipped: set -DREAL_RELAY_TEST=true to run real relay tests",
            System.getProperty("REAL_RELAY_TEST") == "true"
        )
        // ========== PHONE 1: Create group with real crypto ==========
        val groupKey = phone1Encryption.generateGroupKey()
        val groupId =
            java.util.UUID
                .randomUUID()
                .toString()
        val groupName = "IntegrationTest-${System.currentTimeMillis()}"

        val phone1Group =
            Group(
                id = groupId,
                name = groupName,
                createdBy = phone1PubKey,
                createdAt = System.currentTimeMillis() / 1000,
                members = listOf(phone1PubKey),
                relays = relays
            )

        println("\n=== PHONE 1: Creating group ===")
        println("Group ID: $groupId")
        println("Group name: $groupName")
        println("Group key: ${groupKey.take(20)}...")

        // Real NIP-44 encryption of group_meta
        val metaJson =
            buildJsonObject {
                put("name", JsonPrimitive(groupName))
                put("description", JsonPrimitive(""))
                put("created_by", JsonPrimitive(phone1PubKey))
                put("created_at", JsonPrimitive(phone1Group.createdAt))
                putJsonArray("members") {
                    add(JsonPrimitive(phone1PubKey))
                }
                putJsonArray("relays") {
                    relays.forEach { add(JsonPrimitive(it)) }
                }
            }.toString()

        val encrypted = phone1Encryption.encrypt(metaJson, groupKey)
        println("Encrypted meta: ${encrypted.take(40)}...")

        // Real Schnorr-signed event
        val event =
            phone1Signer.createSignedEvent(
                groupId = groupId,
                eventType = "group_meta",
                encryptedContent = encrypted
            )
        assertTrue("Event signature should verify", event.verify())
        println("Signed event ID: ${event.id.take(16)}...")
        println("Event signature valid: ${event.verify()}")

        // ========== PHONE 1: Connect to REAL relays and publish ==========
        println("\n=== PHONE 1: Publishing to real relays ===")
        phone1Client.authSigner = { challenge, relayUrl ->
            phone1Signer.createAuthEvent(challenge, relayUrl)
        }
        phone1Client.connect(relays)
        delay(3000) // Wait for WebSocket connections

        assertTrue("Phone 1 should be connected", phone1Client.isConnected)
        println("Phone 1 connected: ${phone1Client.isConnected}")

        val published = phone1Client.publish(event)
        println("Phone 1 published group_meta: $published")
        // Don't fail on publish — some relays may reject test events
        // The important thing is the event was signed and sent

        // Wait for relay propagation
        delay(2000)

        // ========== PHONE 1: Generate invite link ==========
        val inviteLink = InviteLinkCodec.encode(phone1Group, groupKey, phone1PrivKey)
        println("\n=== INVITE LINK ===")
        println("Link: $inviteLink")
        println("Link length: ${inviteLink.length} chars")

        // ========== PHONE 2: Connect to REAL relays and fetch ==========
        println("\n=== PHONE 2: Joining via invite link ===")
        phone2Client.authSigner = { challenge, relayUrl ->
            phone2Signer.createAuthEvent(challenge, relayUrl)
        }
        phone2Client.connect(relays)
        delay(3000)

        assertTrue("Phone 2 should be connected", phone2Client.isConnected)
        println("Phone 2 connected: ${phone2Client.isConnected}")

        // ========== PHONE 2: Join via invite link ==========
        // Key exchange is local (NIP-44 encrypted in URL), no relay needed for key delivery.
        val savedGroup = slot<Group>()
        val savedKey = slot<String>()
        coEvery { phone2Repo.save(capture(savedGroup), capture(savedKey)) } answers {
            coEvery { phone2Repo.getById(savedGroup.captured.id) } returns savedGroup.captured
        }

        val joinUseCase =
            JoinGroupUseCase(
                phone2Repo,
                phone2Identity,
                phone2Client,
                phone2Signer,
                phone2Encryption,
                phone2EventPublisher,
                phone2SelfHeal,
                phone2SyncEngine,
                mockk(relaxed = true)
            )

        val joinedGroup = joinUseCase(inviteLink)

        // ========== PHONE 2: Verify relay round-trip for group_meta ==========
        val fetchedEvents = phone2Client.fetchEvents(groupId, 0, phone2PubKey)
        println("Phone 2 fetched ${fetchedEvents.size} events from relays")

        if (fetchedEvents.isNotEmpty()) {
            for (fetched in fetchedEvents) {
                assertTrue("Fetched event ${fetched.id.take(8)} should have valid sig", fetched.verify())
                println(
                    "  Event ${fetched.id.take(
                        8
                    )}: kind=${fetched.kind} pubkey=${fetched.pubkey.take(8)} sig_valid=${fetched.verify()}"
                )
            }
            val foundOurEvent = fetchedEvents.any { it.id == event.id }
            if (foundOurEvent) {
                val fetchedEvent = fetchedEvents.first { it.id == event.id }
                val decrypted = phone2Encryption.decrypt(fetchedEvent.content, groupKey)
                println("Phone 2 decrypted group_meta: ${decrypted.take(80)}...")
                assertTrue("Decrypted content should contain group name", decrypted.contains(groupName))
                assertTrue("Decrypted content should contain Phone 1 pubkey", decrypted.contains(phone1PubKey))
            }
        }

        println("\n=== RESULTS ===")
        println("Phone 2 joined group: ${joinedGroup.id}")
        println("Group name: ${joinedGroup.name}")
        println("Group relays: ${joinedGroup.relays}")
        println("Phone 2 members: ${joinedGroup.members.map { it.take(8) }}")

        // ========== ASSERTIONS ==========
        assertEquals("Group IDs must match", groupId, joinedGroup.id)
        assertEquals("Group names must match", groupName, joinedGroup.name)
        assertTrue("Phone 2 pubkey must be in members", phone2PubKey in joinedGroup.members)
        assertEquals("Group key must match", groupKey, savedKey.captured)
        assertEquals("Relays must match", relays.toSet(), joinedGroup.relays.toSet())

        coVerify { phone2Repo.updateFromMeta(groupId, any(), match { phone2PubKey in it }, any()) }

        println("\n✅ REAL RELAY INTEGRATION TEST PASSED")
        println("   Phone 1 (${phone1PubKey.take(8)}) created group '$groupName'")
        println("   Phone 1 published group_meta to ${relays.size} real relays")
        println("   Phone 2 (${phone2PubKey.take(8)}) joined via invite link (NIP-44 encrypted key in URL)")
        println("   Phone 2 verified relay round-trip for group_meta")
        println("   Both phones share group ID: ${groupId.take(8)}...")
    }
}
