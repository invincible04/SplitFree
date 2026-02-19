package com.splitfree.domain.usecase.group

import com.splitfree.domain.crypto.EventSigner
import com.splitfree.domain.crypto.GroupEncryption
import com.splitfree.domain.crypto.NostrEvent
import com.splitfree.domain.invite.InviteLinkCodec
import com.splitfree.domain.model.group.Group
import com.splitfree.domain.repository.EventPublisherContract
import com.splitfree.domain.repository.GroupRepositoryContract
import com.splitfree.domain.repository.IdentityContract
import com.splitfree.domain.repository.NostrClientContract
import com.splitfree.domain.repository.SyncEngineContract
import com.splitfree.domain.usecase.sync.SelfHealUseCase
import com.splitfree.domain.util.RelayDefaults
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import java.security.SecureRandom
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Simulates the real-world two-phone flow:
 *   Phone 1: CreateGroupUseCase → createInviteLink → QR code
 *   Phone 2: scans QR → JoinGroupUseCase with that link
 *
 * Verifies the invite link round-trips correctly and both phones
 * end up with the same group data.
 */
class EndToEndJoinFlowTest {
    // --- Phone 1 (creator) mocks ---
    private val phone1Repo = mockk<GroupRepositoryContract>(relaxed = true)
    private val phone1Identity = mockk<IdentityContract>()
    private val phone1Encryption = mockk<GroupEncryption>()
    private val phone1Signer = mockk<EventSigner>()
    private val phone1EventPublisher = mockk<EventPublisherContract>(relaxed = true)

    // --- Phone 2 (joiner) mocks ---
    private val phone2Repo = mockk<GroupRepositoryContract>(relaxed = true)
    private val phone2Identity = mockk<IdentityContract>()
    private val phone2NostrClient = mockk<NostrClientContract>(relaxed = true)
    private val phone2Signer = mockk<EventSigner>(relaxed = true)
    private val phone2Encryption = mockk<GroupEncryption>(relaxed = true)
    private val phone2EventPublisher = mockk<EventPublisherContract>(relaxed = true)
    private val phone2SelfHeal = mockk<SelfHealUseCase>(relaxed = true)
    private val phone2SyncEngine = mockk<SyncEngineContract>(relaxed = true)

    private val phone1Pubkey = "aa".repeat(32) // Phone 1's identity
    private val phone2Pubkey = "bb".repeat(32) // Phone 2's identity
    private val fakeGroupKey = "supersecretgroupkey123"

    private lateinit var createGroupUseCase: CreateGroupUseCase
    private lateinit var joinGroupUseCase: JoinGroupUseCase

    // Capture what Phone 1 saves so Phone 2's repo can return it after sync
    private val savedGroups = mutableMapOf<String, Group>()
    private val savedKeys = mutableMapOf<String, String>()

    private fun validSenderPrivKey(): ByteArray {
        val key = ByteArray(32).also { SecureRandom().nextBytes(it) }
        while (!fr.acinq.secp256k1.Secp256k1.secKeyVerify(key)) {
            SecureRandom().nextBytes(key)
        }
        return key
    }

    /** Encode an invite link — group key is encrypted in the URL, no relay needed. */
    private fun encodeInviteLink(group: Group, groupKey: String): String {
        val senderPriv = validSenderPrivKey()
        return InviteLinkCodec.encode(group, groupKey, senderPriv)
    }

    @Before
    fun setup() {
        // Mock Android APIs
        mockkStatic(android.util.Log::class)
        every { android.util.Log.i(any<String>(), any<String>()) } returns 0
        every { android.util.Log.w(any<String>(), any<String>()) } returns 0
        every { android.util.Log.d(any<String>(), any<String>()) } returns 0
        every { android.util.Log.e(any<String>(), any<String>()) } returns 0
        every { android.util.Log.e(any<String>(), any<String>(), any()) } returns 0
        every { android.util.Log.w(any<String>(), any<String>(), any()) } returns 0

        // --- Phone 1 setup ---
        every { phone1Identity.getPublicKeyHex() } returns phone1Pubkey
        every { phone1Encryption.generateGroupKey() } returns fakeGroupKey
        every { phone1Encryption.encrypt(any(), any()) } returns "encrypted_meta"
        every { phone1Signer.createSignedEvent(any(), any(), any(), any()) } returns
            NostrEvent(
                id = "evt_create",
                pubkey = phone1Pubkey,
                createdAt = 1000L,
                kind = 30078,
                tags = emptyList(),
                content = "encrypted_meta",
                sig = "sig1"
            )
        // Capture saves from Phone 1
        coEvery { phone1Repo.save(any<Group>(), any()) } answers {
            val group = firstArg<Group>()
            val key = secondArg<String>()
            savedGroups[group.id] = group
            savedKeys[group.id] = key
        }

        createGroupUseCase =
            CreateGroupUseCase(
                phone1Repo,
                phone1Encryption,
                phone1Identity,
                phone1Signer,
                phone1EventPublisher
            )

        // --- Phone 2 setup ---
        every { phone2Identity.getPublicKeyHex() } returns phone2Pubkey
        every { phone2NostrClient.isConnected } returns true
        coEvery { phone2NostrClient.publish(any()) } returns true
        // Phone 2 hasn't joined any group yet
        coEvery { phone2Repo.getById(any()) } returns null
        // After Phone 2 saves, return the group on subsequent getById calls
        coEvery { phone2Repo.save(any<Group>(), any()) } answers {
            val group = firstArg<Group>()
            coEvery { phone2Repo.getById(group.id) } returns group
        }

        joinGroupUseCase =
            JoinGroupUseCase(
                phone2Repo,
                phone2Identity,
                phone2NostrClient,
                phone2Signer,
                phone2Encryption,
                phone2EventPublisher,
                phone2SelfHeal,
                phone2SyncEngine
            )
    }

    @After
    fun teardown() {
        unmockkStatic(android.util.Log::class)
    }

    @Test
    fun `full flow - Phone 1 creates group, Phone 2 joins via invite link`() = runBlocking {
        // ========== PHONE 1: Create group ==========
        val phone1Group = createGroupUseCase("Weekend Trip")

        assertNotNull(phone1Group)
        assertEquals("Weekend Trip", phone1Group.name)
        assertEquals(phone1Pubkey, phone1Group.createdBy)
        assertEquals(listOf(phone1Pubkey), phone1Group.members)
        assertEquals(RelayDefaults.DEFAULT_RELAYS, phone1Group.relays)
        assertTrue(savedGroups.containsKey(phone1Group.id))
        assertEquals(fakeGroupKey, savedKeys[phone1Group.id])

        // ========== PHONE 1: Generate invite link (QR code content) ==========
        val inviteLink = encodeInviteLink(phone1Group, fakeGroupKey)

        assertNotNull(inviteLink)
        assertTrue("Link should start with splitfree://join?d=", inviteLink.startsWith("splitfree://join?d="))
        // Link should be compact enough for a QR code
        assertTrue("Link too long for QR: ${inviteLink.length}", inviteLink.length < 300)

        println("Phone 1 created group: ${phone1Group.id}")
        println("Phone 1 invite link: $inviteLink")

        // ========== PHONE 2: Scan QR and join ==========
        val phone2Group = joinGroupUseCase(inviteLink)

        assertNotNull(phone2Group)
        // Same group ID
        assertEquals(phone1Group.id, phone2Group.id)
        // Same group name
        assertEquals("Weekend Trip", phone2Group.name)
        // Same relays
        assertEquals(phone1Group.relays, phone2Group.relays)
        // Phone 2 is a member
        assertTrue("Phone 2 should be in members", phone2Pubkey in phone2Group.members)

        println("Phone 2 joined group: ${phone2Group.id} name=${phone2Group.name}")
        println("Phone 2 members: ${phone2Group.members.map { it.take(8) }}")

        // ========== Verify Phone 2 saved the group locally ==========
        coVerify { phone2Repo.save(match { it.id == phone1Group.id && it.name == "Weekend Trip" }, fakeGroupKey) }

        // ========== Verify Phone 2 published group_meta to relays ==========
        coVerify { phone2Repo.updateFromMeta(phone1Group.id, any(), match { phone2Pubkey in it }, any()) }
        coVerify { phone2EventPublisher.publishDirect(any(), any(), any(), eq("group_meta")) }

        println("\n✅ End-to-end flow passed:")
        println("   Phone 1 created group '${phone1Group.name}' (${phone1Group.id.take(8)}...)")
        println("   Phone 1 generated invite link (${inviteLink.length} chars)")
        println("   Phone 2 scanned link and joined successfully")
        println("   Phone 2 saved group locally and published join to relays")
    }

    @Test
    fun `invite link preserves all 5 default relays via bitmap encoding`() = runBlocking {
        val group = createGroupUseCase("Relay Test")
        val link = encodeInviteLink(group, fakeGroupKey)
        val phone2Group = joinGroupUseCase(link)

        assertEquals(
            "All 5 default relays should round-trip",
            RelayDefaults.DEFAULT_RELAYS,
            phone2Group.relays
        )
    }

    @Test
    fun `Phone 2 joining same group twice returns existing group`() = runBlocking {
        val phone1Group = createGroupUseCase("Duplicate Test")
        val link = encodeInviteLink(phone1Group, fakeGroupKey)

        // First join
        val firstJoin = joinGroupUseCase(link)
        // Now getById returns the group (simulating it's already in DB)
        coEvery { phone2Repo.getById(phone1Group.id) } returns firstJoin

        // Second join — should return existing, not create duplicate
        val secondJoin = joinGroupUseCase(link)
        assertEquals(firstJoin.id, secondJoin.id)

        // save() should only be called once (first join)
        coVerify(exactly = 1) { phone2Repo.save(match { it.id == phone1Group.id }, any()) }
    }

    @Test
    fun `invite link with unicode group name round-trips correctly`() = runBlocking {
        val phone1Group = createGroupUseCase("旅行 🏖️ Trip")
        val link = encodeInviteLink(phone1Group, fakeGroupKey)
        val phone2Group = joinGroupUseCase(link)

        assertEquals("旅行 🏖️ Trip", phone2Group.name)
    }

    @Test
    fun `Phone 2 join works even when initial sync fails`() = runBlocking {
        // Simulate sync failure after key retrieval succeeds
        coEvery { phone2SyncEngine.pullEvents(any(), any(), any(), lenientTimestamp = any()) } throws
            RuntimeException("sync failed")

        val phone1Group = createGroupUseCase("Sync Fail Test")
        val link = encodeInviteLink(phone1Group, fakeGroupKey)

        // Should NOT throw — sync failure is caught, group is still saved locally
        val phone2Group = joinGroupUseCase(link)
        assertNotNull(phone2Group)
        assertEquals(phone1Group.id, phone2Group.id)

        // Group was saved even though sync failed
        coVerify { phone2Repo.save(match { it.id == phone1Group.id }, fakeGroupKey) }
    }
}
