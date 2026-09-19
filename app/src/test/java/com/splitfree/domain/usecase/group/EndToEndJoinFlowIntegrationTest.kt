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
import com.splitfree.test.RelayProbeAssertions.assertAccepted
import com.splitfree.test.RelayProbeAssertions.requireEvents
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
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume
import org.junit.Before
import org.junit.Test

/**
 * Join-usecase test with real crypto and a separate live metadata round-trip, not an app E2E test.
 * Repositories, event publishing, sync, self-heal, identity storage and settings are mocked.
 * Opt in with `-DREAL_RELAY_TEST=true`; relay rejection or incomplete/missing history fails.
 */
class EndToEndJoinFlowIntegrationTest {
    private lateinit var relayScope: CoroutineScope
    private var logMocked = false

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
    private val phone2Repo = mockk<GroupRepositoryContract>(relaxed = true)
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
        logMocked = true
        relayScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
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
        every { phone1Identity.getPrivateKeyBytes() } answers { phone1PrivKey.copyOf() }
        every { phone1Identity.hasIdentity() } returns true
        every { phone2Identity.identityState() } returns com.splitfree.domain.repository.IdentityState.READY
        every { phone2Identity.getPublicKeyHex() } returns phone2PubKey
        every { phone2Identity.getPrivateKeyBytes() } answers { phone2PrivKey.copyOf() }
        every { phone2Identity.hasIdentity() } returns true

        phone1Signer = EventSigner(phone1Identity)
        phone2Signer = EventSigner(phone2Identity)

        phone1Client = NostrClient(relayScope)
        phone2Client = NostrClient(relayScope)

        coEvery { phone2Repo.getById(any()) } returns null
    }

    @After
    fun teardown() {
        if (::phone1Client.isInitialized) phone1Client.disconnect()
        if (::phone2Client.isInitialized) phone2Client.disconnect()
        if (::relayScope.isInitialized) relayScope.cancel()
        if (::phone1PrivKey.isInitialized) phone1PrivKey.fill(0)
        if (::phone2PrivKey.isInitialized) phone2PrivKey.fill(0)
        if (logMocked) unmockkStatic(android.util.Log::class)
    }

    private fun generateValidPrivateKey(): ByteArray {
        val key = ByteArray(32)
        do {
            SecureRandom().nextBytes(key)
        } while (!fr.acinq.secp256k1.Secp256k1.secKeyVerify(key))
        return key
    }

    @Test(timeout = 60_000)
    fun `join usecase with mocked boundaries and live metadata round-trip`() = runBlocking {
        // Build the creator fixture directly; group creation is not exercised.
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

        phone1Client.authSigner = { c, r -> phone1Signer.createAuthEvent(c, r) }
        phone1Client.connect(relays)
        withTimeout(15_000) { phone1Client.connectionState.first { it } }
        assertTrue("Phone 1 should be connected", phone1Client.isConnected)
        assertAccepted(phone1Client.publish(event))

        val inviteLink = InviteLinkCodec.encode(group, groupKey)
        assertTrue("Link should be compact for QR", inviteLink.length < 500)
        println("Invite link length: ${inviteLink.length} chars")

        // Run the join with mocked persistence, publishing and initial sync.
        val savedGroup = slot<Group>()
        val savedKey = slot<String>()
        coEvery { phone2Repo.save(capture(savedGroup), capture(savedKey)) } answers {
            coEvery { phone2Repo.getById(savedGroup.captured.id) } returns savedGroup.captured
        }

        val joinUseCase = JoinGroupUseCase(
            phone2Repo, phone2Identity, phone2Client, phone2Signer, encryption,
            phone2EventPublisher, phone2SelfHeal, phone2SyncEngine, mockk(relaxed = true), mockk(relaxed = true)
        )
        val joinedGroup = joinUseCase(inviteLink)

        assertEquals(groupId, joinedGroup.id)
        assertEquals(groupName, joinedGroup.name)
        assertTrue("Phone 2 must be in members", phone2PubKey in joinedGroup.members)
        assertEquals(groupKey, savedKey.captured)
        assertEquals(relays.toSet(), joinedGroup.relays.toSet())

        // Fetch independently of the mocked join and require the exact creator event.
        phone2Client.authSigner = { c, r -> phone2Signer.createAuthEvent(c, r) }
        phone2Client.connect(relays)
        withTimeout(15_000) { phone2Client.connectionState.first { it } }
        val fetched = phone2Client.fetchEvents(groupId, 0, phone2PubKey)
        assertTrue("Metadata fetch must complete on all requested relays", fetched.complete)
        val receivedMeta = requireEvents(listOf(event), fetched.events).single()
        val decrypted = encryption.decrypt(receivedMeta.content, groupKey)
        assertEquals(meta, json.decodeFromString<GroupMeta>(decrypted))

        // The join uses the member self-update API, not the creator metadata update API.
        coVerify {
            phone2Repo.applyMemberSelfUpdate(groupId, phone2PubKey, any(), any(), join = true, displayName = any())
        }
        coVerify(exactly = 0) {
            phone2Repo.updateFromMeta(any(), any(), any(), any(), any(), any(), any(), any(), any(), any())
        }

        println("\n✅ JOIN USECASE + LIVE METADATA ROUND-TRIP PASSED (mocked persistence and sync)")
        println("   Phone 1 (${phone1PubKey.take(8)}) → created group → published to real relays")
        println("   Phone 2 (${phone2PubKey.take(8)}) → mocked-boundary join usecase → separate live metadata fetch")
    }
}
