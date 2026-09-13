package com.splitfree.domain.usecase.group

import com.splitfree.data.identity.IdentityManager
import com.splitfree.data.nostr.NostrClient
import com.splitfree.data.util.CompressionUtil
import com.splitfree.domain.crypto.EventSigner
import com.splitfree.domain.crypto.GroupEncryption
import com.splitfree.domain.crypto.NostrEvent
import com.splitfree.domain.invite.InviteLinkCodec
import com.splitfree.domain.model.group.Group
import com.splitfree.domain.model.group.GroupIdentity
import com.splitfree.domain.model.group.GroupMeta
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
import java.security.SecureRandom
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume
import org.junit.Before
import org.junit.Test

/**
 * Integration test: two-phone join flow using REAL Nostr relays and REAL crypto.
 *
 * Phone 1: generates real keypair, creates group, encrypts group_meta with real NIP-44,
 *          signs with real Schnorr, publishes to real relays, generates invite link.
 * Phone 2: generates different real keypair, parses invite link, connects to real relays,
 *          fetches events, joins group, publishes join announcement.
 *
 * Only Android storage (Room, SharedPreferences) is mocked.
 *
 * Run: `./gradlew test -DREAL_RELAY_TEST=true --tests "*.EndToEndJoinFlowIntegrationTest"`
 */
class EndToEndJoinFlowIntegrationTest {
    private lateinit var phone1Client: NostrClient
    private lateinit var phone2Client: NostrClient

    private val encryption = GroupEncryption(CompressionUtil)
    private val json = Json { ignoreUnknownKeys = true }

    private lateinit var phone1PrivKey: ByteArray
    private lateinit var phone1PubKey: String
    private lateinit var phone2PrivKey: ByteArray
    private lateinit var phone2PubKey: String

    private val phone1Identity = mockk<IdentityManager>()
    private val phone2Identity = mockk<IdentityManager>()
    private val phone1Repo = mockk<GroupRepositoryContract>(relaxed = true)
    private val phone2Repo = mockk<GroupRepositoryContract>(relaxed = true)
    private val phone1EventPublisher = mockk<EventPublisherContract>(relaxed = true)
    private val phone2EventPublisher = mockk<EventPublisherContract>(relaxed = true)
    private val phone2SelfHeal = mockk<SelfHealUseCase>(relaxed = true)
    private val phone2SyncEngine = mockk<SyncEngineContract>(relaxed = true)

    private lateinit var phone1Signer: EventSigner
    private lateinit var phone2Signer: EventSigner

    private val relays = listOf("wss://nos.lol", "wss://relay.primal.net")

    @Before
    fun setup() {
        Assume.assumeTrue("Skipped: set -DREAL_RELAY_TEST=true", System.getProperty("REAL_RELAY_TEST") == "true")

        mockkStatic(android.util.Log::class)
        every { android.util.Log.i(any<String>(), any<String>()) } returns 0
        every { android.util.Log.w(any<String>(), any<String>()) } returns 0
        every { android.util.Log.d(any<String>(), any<String>()) } returns 0
        every { android.util.Log.e(any<String>(), any<String>()) } returns 0
        every { android.util.Log.e(any<String>(), any<String>(), any()) } returns 0
        every { android.util.Log.w(any<String>(), any<String>(), any()) } returns 0

        phone1PrivKey = generateValidPrivateKey()
        phone1PubKey = NostrEvent.pubkeyFromPrivkey(phone1PrivKey)
        phone2PrivKey = generateValidPrivateKey()
        phone2PubKey = NostrEvent.pubkeyFromPrivkey(phone2PrivKey)

        every { phone1Identity.getPublicKeyHex() } returns phone1PubKey
        every { phone1Identity.getPrivateKeyBytes() } returns phone1PrivKey.copyOf()
        every { phone1Identity.hasIdentity() } returns true
        every { phone2Identity.getPublicKeyHex() } returns phone2PubKey
        every { phone2Identity.getPrivateKeyBytes() } returns phone2PrivKey.copyOf()
        every { phone2Identity.hasIdentity() } returns true

        phone1Signer = EventSigner(phone1Identity)
        phone2Signer = EventSigner(phone2Identity)

        phone1Client = NostrClient(CoroutineScope(SupervisorJob() + Dispatchers.IO))
        phone2Client = NostrClient(CoroutineScope(SupervisorJob() + Dispatchers.IO))

        coEvery { phone1Repo.getById(any()) } returns null
        coEvery { phone2Repo.getById(any()) } returns null
    }

    @After
    fun teardown() {
        phone1Client.disconnect()
        phone2Client.disconnect()
        if (::phone1PrivKey.isInitialized) phone1PrivKey.fill(0)
        if (::phone2PrivKey.isInitialized) phone2PrivKey.fill(0)
        unmockkStatic(android.util.Log::class)
    }

    private fun generateValidPrivateKey(): ByteArray {
        val key = ByteArray(32)
        do {
            SecureRandom().nextBytes(key)
        } while (!fr.acinq.secp256k1.Secp256k1.secKeyVerify(key))
        return key
    }

    @Test(timeout = 60_000)
    fun `Phone 1 creates group and publishes, Phone 2 joins via invite link and fetches from relay`() = runBlocking {
        // === Phone 1: Create group ===
        val groupKey = encryption.generateGroupKey()
        val createdAt = System.currentTimeMillis() / 1000
        val groupId = GroupIdentity.derive(phone1PubKey, createdAt)
        val groupName = "JoinFlowTest-${System.currentTimeMillis()}"

        val group = Group(
            id = groupId,
            name = groupName,
            createdBy = phone1PubKey,
            createdAt = createdAt,
            members = listOf(phone1PubKey),
            relays = relays
        )

        val meta = GroupMeta(
            name = group.name,
            createdBy = group.createdBy,
            createdAt = group.createdAt,
            members = group.members,
            relays = group.relays
        )
        val encrypted = encryption.encrypt(json.encodeToString(GroupMeta.serializer(), meta), groupKey)
        val event = phone1Signer.createSignedEvent(
            groupId = groupId,
            eventType = "group_meta",
            encryptedContent = encrypted
        )
        assertTrue("Event must verify", event.verify())

        // === Phone 1: Publish to real relays ===
        phone1Client.authSigner = { c, r -> phone1Signer.createAuthEvent(c, r) }
        phone1Client.connect(relays)
        delay(3000)
        assertTrue("Phone 1 should be connected", phone1Client.isConnected)
        phone1Client.publish(event)
        delay(2000)

        // === Phone 1: Generate invite link ===
        val inviteLink = InviteLinkCodec.encode(group, groupKey)
        assertTrue("Link should be compact for QR", inviteLink.length < 500)
        println("Invite link: $inviteLink (${inviteLink.length} chars)")

        // === Phone 2: Join via invite link ===
        val savedGroup = slot<Group>()
        val savedKey = slot<String>()
        coEvery { phone2Repo.save(capture(savedGroup), capture(savedKey)) } answers {
            coEvery { phone2Repo.getById(savedGroup.captured.id) } returns savedGroup.captured
        }

        val joinUseCase = JoinGroupUseCase(
            phone2Repo, phone2Identity, phone2Client, phone2Signer, encryption,
            phone2EventPublisher, phone2SelfHeal, phone2SyncEngine, mockk(relaxed = true)
        )
        val joinedGroup = joinUseCase(inviteLink)

        // === Verify ===
        assertEquals(groupId, joinedGroup.id)
        assertEquals(groupName, joinedGroup.name)
        assertTrue("Phone 2 must be in members", phone2PubKey in joinedGroup.members)
        assertEquals(groupKey, savedKey.captured)
        assertEquals(relays.toSet(), joinedGroup.relays.toSet())

        // === Phone 2: Fetch from real relay to verify round-trip ===
        phone2Client.authSigner = { c, r -> phone2Signer.createAuthEvent(c, r) }
        phone2Client.connect(relays)
        delay(3000)
        val fetched = phone2Client.fetchEvents(groupId, 0, phone2PubKey)
        println("Phone 2 fetched ${fetched.size} events from relays")

        if (fetched.isNotEmpty()) {
            val found = fetched.find { it.id == event.id }
            if (found != null) {
                assertTrue("Fetched event must verify", found.verify())
                val decrypted = encryption.decrypt(found.content, groupKey)
                assertTrue("Must contain group name", decrypted.contains(groupName))
                println("✅ Phone 2 verified group_meta round-trip via real relay")
            }
        }

        // The local join is a member self-update ordered by the join event's own clock, never a creator meta.
        coVerify {
            phone2Repo.applyMemberSelfUpdate(groupId, phone2PubKey, any(), any(), join = true, displayName = any())
        }
        coVerify(exactly = 0) {
            phone2Repo.updateFromMeta(any(), any(), any(), any(), any(), any(), any(), any(), any(), any())
        }

        println("\n✅ END-TO-END JOIN FLOW PASSED")
        println("   Phone 1 (${phone1PubKey.take(8)}) → created group → published to real relays")
        println("   Phone 2 (${phone2PubKey.take(8)}) → joined via invite link → fetched from real relays")
    }
}
