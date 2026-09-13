package com.splitfree.domain.usecase.group

import com.splitfree.domain.crypto.EventSigner
import com.splitfree.domain.crypto.GroupEncryption
import com.splitfree.domain.crypto.NostrEvent
import com.splitfree.domain.invite.InviteLinkCodec
import com.splitfree.domain.model.group.Group
import com.splitfree.domain.model.group.GroupIdentity
import com.splitfree.domain.repository.EventPublisherContract
import com.splitfree.domain.repository.GroupRepositoryContract
import com.splitfree.domain.repository.IdentityContract
import com.splitfree.domain.repository.NostrClientContract
import com.splitfree.domain.repository.SettingsContract
import com.splitfree.domain.repository.SyncEngineContract
import com.splitfree.domain.usecase.sync.SelfHealUseCase
import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.slot
import io.mockk.unmockkStatic
import io.mockk.verify
import java.util.Base64
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Test

class JoinGroupUseCaseTest {
    private val groupRepo = mockk<GroupRepositoryContract>(relaxed = true)
    private val identity = mockk<IdentityContract>()
    private val nostrClient = mockk<NostrClientContract>(relaxed = true)
    private val signer = mockk<EventSigner>(relaxed = true)
    private val encryption = mockk<GroupEncryption>(relaxed = true)
    private val eventPublisher = mockk<EventPublisherContract>(relaxed = true)
    private val selfHeal = mockk<SelfHealUseCase>(relaxed = true)
    private val syncEngine = mockk<SyncEngineContract>(relaxed = true)
    private val settings = mockk<SettingsContract>(relaxed = true)

    private lateinit var useCase: JoinGroupUseCase

    /** The joiner. */
    private val pubkey = "aa".repeat(32)

    /** The group creator named by the invite (not the joiner, not necessarily the inviter). */
    private val creatorPubkey = "cc".repeat(32)
    private val createdAt = 1_700_000_000L
    private val groupId = GroupIdentity.derive(creatorPubkey, createdAt)
    private val groupKey = Base64.getEncoder().encodeToString(ByteArray(32) { 7 })

    /** Byte offset of the expiry field when the link carries no custom relays. */
    private val expiryPos = 1 + 16 + 32 + 8 + 2 + 32 + 1 + 1

    private fun inviteGroup(
        name: String = "TestGroup",
        relays: List<String> = listOf("wss://relay.damus.io"),
        keyEpoch: Int = 0
    ): Group = Group(groupId, name, "", creatorPubkey, createdAt, listOf(creatorPubkey), relays, keyEpoch = keyEpoch)

    /** Encode an invite link for the creator-bound test group. */
    private fun buildInviteUri(
        name: String = "TestGroup",
        relays: List<String> = listOf("wss://relay.damus.io"),
        keyEpoch: Int = 0,
        key: String = groupKey
    ): String = InviteLinkCodec.encode(inviteGroup(name, relays, keyEpoch), key)

    private fun tamper(link: String, mutate: (ByteArray) -> Unit): String {
        val data = Base64.getUrlDecoder().decode(link.substringAfter("d="))
        mutate(data)
        return "splitfree://join?d=" + Base64.getUrlEncoder().withoutPadding().encodeToString(data)
    }

    @Before
    fun setup() {
        mockkStatic(android.util.Log::class)
        every { android.util.Log.i(any<String>(), any<String>()) } returns 0
        every { android.util.Log.w(any<String>(), any<String>()) } returns 0
        every { android.util.Log.d(any<String>(), any<String>()) } returns 0
        every { android.util.Log.e(any<String>(), any<String>()) } returns 0
        every { android.util.Log.e(any<String>(), any<String>(), any()) } returns 0
        every { android.util.Log.w(any<String>(), any<String>(), any()) } returns 0

        every { identity.getPublicKeyHex() } returns pubkey
        every { settings.displayName } returns ""
        coEvery { groupRepo.getById(any()) } returns null

        useCase =
            JoinGroupUseCase(
                groupRepo, identity, nostrClient, signer, encryption,
                eventPublisher, selfHeal, syncEngine, settings
            )
    }

    @After
    fun teardown() {
        unmockkStatic(android.util.Log::class)
    }

    @Test
    fun `invoke creates group from valid invite link`() = runBlocking {
        val group = useCase(buildInviteUri())
        assertEquals(groupId, group.id)
        assertEquals("TestGroup", group.name)
        assertEquals(listOf(pubkey), group.members)
        assertEquals(creatorPubkey, group.createdBy)
        coVerify { groupRepo.save(any(), any()) }
    }

    @Test
    fun `invoke saves group with creator, createdAt and key epoch from the invite`() = runBlocking {
        val saved = slot<Group>()
        val savedKey = slot<String>()
        coEvery { groupRepo.save(capture(saved), capture(savedKey)) } just Runs

        useCase(buildInviteUri(keyEpoch = 3))

        assertEquals(groupId, saved.captured.id)
        assertEquals(creatorPubkey, saved.captured.createdBy)
        assertEquals(createdAt, saved.captured.createdAt)
        assertEquals(3, saved.captured.keyEpoch)
        assertEquals(listOf(pubkey), saved.captured.members)
        // GroupRepository.save stores the key under "<id>:<keyEpoch>", i.e. "<id>:3" here.
        assertEquals(groupKey, savedKey.captured)
    }

    @Test
    fun `invoke publishes join announcement with the verified creator and createdAt`() = runBlocking {
        useCase(buildInviteUri())
        // publishGroupMeta reads createdBy/createdAt from the locally saved group, which came from the invite.
        coVerify { groupRepo.save(match { it.createdBy == creatorPubkey && it.createdAt == createdAt }, groupKey) }
        coVerify { eventPublisher.publishDirect(any(), groupId, any(), "group_meta") }
    }

    @Test
    fun `invoke returns existing group if already joined`() = runBlocking {
        val existing = Group(groupId, "Existing", "", creatorPubkey, createdAt, listOf(pubkey), listOf("wss://r"))
        coEvery { groupRepo.getById(groupId) } returns existing
        val result = useCase(buildInviteUri())
        assertEquals("Existing", result.name)
        coVerify(exactly = 0) { groupRepo.save(any(), any()) }
    }

    @Test(expected = IllegalArgumentException::class)
    fun `invoke rejects link whose creator claim does not match the group id`() = runBlocking {
        val forgedCreator = "ee".repeat(32).chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        val link = tamper(buildInviteUri()) { data -> forgedCreator.copyInto(data, destinationOffset = 1 + 16) }
        useCase(link)
        Unit
    }

    @Test(expected = IllegalArgumentException::class)
    fun `invoke rejects expired invite link`() = runBlocking {
        val pastExp = ((System.currentTimeMillis() / 1000 - 3600) and 0xFFFFFFFFL).toInt()
        val link = tamper(buildInviteUri()) { data ->
            data[expiryPos] = ((pastExp ushr 24) and 0xFF).toByte()
            data[expiryPos + 1] = ((pastExp ushr 16) and 0xFF).toByte()
            data[expiryPos + 2] = ((pastExp ushr 8) and 0xFF).toByte()
            data[expiryPos + 3] = (pastExp and 0xFF).toByte()
        }
        useCase(link)
        Unit
    }

    @Test(expected = IllegalArgumentException::class)
    fun `invoke rejects unsupported link version`() = runBlocking {
        useCase(tamper(buildInviteUri()) { data -> data[0] = 0x01 })
        Unit
    }

    @Test
    fun `invoke runs initial sync via syncEngine with the invite key`() = runBlocking {
        every { nostrClient.isConnected } returns false
        coEvery { syncEngine.pullEvents(any(), any(), any(), lenientTimestamp = true) } returns 3

        useCase(buildInviteUri())
        coVerify { syncEngine.pullEvents(groupId, 0, groupKey, lenientTimestamp = true) }
    }

    @Test
    fun `invoke handles initial sync failure gracefully`() = runBlocking {
        every { nostrClient.isConnected } returns false
        coEvery { syncEngine.pullEvents(any(), any(), any(), lenientTimestamp = any()) } throws
            RuntimeException("sync failed")

        val group = useCase(buildInviteUri())
        assertNotNull(group)
    }

    @Test
    fun `invoke still publishes join announcement when offline connect fails`() = runBlocking {
        every { nostrClient.isConnected } returns false
        coEvery { nostrClient.connect(any()) } throws RuntimeException("offline")

        val group = useCase(buildInviteUri())

        assertEquals(groupId, group.id)
        coVerify { eventPublisher.publishDirect(any(), groupId, any(), "group_meta") }
    }

    @Test
    fun `invoke with non-expired link succeeds`() = runBlocking {
        assertNotNull(useCase(buildInviteUri()))
    }

    @Test
    fun `invoke records the local join as a member self-update carrying the display name`() = runBlocking {
        every { settings.displayName } returns "Bob"
        useCase(buildInviteUri())
        coVerify(exactly = 1) {
            groupRepo.applyMemberSelfUpdate(groupId, pubkey, any(), any(), join = true, displayName = "Bob")
        }
        // A join never rides the creator's metadata watermark.
        coVerify(exactly = 0) {
            groupRepo.updateFromMeta(any(), any(), any(), any(), any(), any(), any(), any(), any(), any())
        }
    }

    @Test
    fun `invoke with a blank display name joins without touching the name`() = runBlocking {
        every { settings.displayName } returns "   "
        useCase(buildInviteUri())
        coVerify(exactly = 1) {
            groupRepo.applyMemberSelfUpdate(groupId, pubkey, any(), any(), join = true, displayName = null)
        }
    }

    @Test
    fun `invoke orders the local join by the published announcement's own clock`() = runBlocking {
        val joinEvent = NostrEvent("join-evt", pubkey, 4242, 30078, emptyList(), "enc", "sig")
        every { signer.createSignedEvent(any(), any(), any(), any(), any()) } returns joinEvent

        useCase(buildInviteUri())

        coVerify(exactly = 1) {
            groupRepo.applyMemberSelfUpdate(groupId, pubkey, 4242, "join-evt", join = true, displayName = null)
        }
        coVerify { eventPublisher.publishDirect(joinEvent, groupId, any(), "group_meta") }
    }

    @Test
    fun `invoke publishes a join announcement listing the joiner alongside the synced roster`() = runBlocking {
        every { settings.displayName } returns "Bob"
        val synced = Group(groupId, "TestGroup", "", creatorPubkey, createdAt, listOf(creatorPubkey), listOf("wss://r"))
        coEvery { groupRepo.getById(groupId) } returnsMany listOf(null, synced, synced)
        val metaPlaintext = slot<String>()
        every { encryption.encrypt(capture(metaPlaintext), groupKey) } returns "enc-meta"

        useCase(buildInviteUri())

        val meta = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }
            .decodeFromString<com.splitfree.domain.model.group.GroupMeta>(metaPlaintext.captured)
        assertEquals(listOf(creatorPubkey, pubkey), meta.members)
        assertEquals("Bob", meta.memberNames[pubkey])
        assertEquals(creatorPubkey, meta.createdBy)
    }

    @Test
    fun `invoke warns but continues when synced group disagrees with invite creator`() = runBlocking {
        val hijacked = Group(groupId, "TestGroup", "", "ff".repeat(32), createdAt, listOf(pubkey), listOf("wss://r"))
        // First lookup (dedupe) finds nothing; after save+sync the local row claims another creator.
        coEvery { groupRepo.getById(groupId) } returnsMany listOf(null, hijacked, hijacked)

        val result = useCase(buildInviteUri())

        assertNotNull(result)
        verify {
            android.util.Log.w("JoinGroupUseCase", match<String> { it.contains("Creator mismatch") })
        }
    }
}
