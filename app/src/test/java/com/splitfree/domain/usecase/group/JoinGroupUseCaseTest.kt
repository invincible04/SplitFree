package com.splitfree.domain.usecase.group

import com.splitfree.domain.crypto.EventSigner
import com.splitfree.domain.crypto.GroupEncryption
import com.splitfree.domain.crypto.NostrEvent
import com.splitfree.domain.invite.InviteLinkCodec
import com.splitfree.domain.model.group.Group
import com.splitfree.domain.model.group.GroupIdentity
import com.splitfree.domain.model.group.GroupMeta
import com.splitfree.domain.model.sync.PullResult
import com.splitfree.domain.repository.EventPublisherContract
import com.splitfree.domain.repository.EventRepositoryContract
import com.splitfree.domain.repository.EventSnapshot
import com.splitfree.domain.repository.GroupRepositoryContract
import com.splitfree.domain.repository.IdentityContract
import com.splitfree.domain.repository.IdentityState
import com.splitfree.domain.repository.NostrClientContract
import com.splitfree.domain.repository.SettingsContract
import com.splitfree.domain.repository.SyncEngineContract
import com.splitfree.domain.usecase.sync.SelfHealUseCase
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.coVerifyOrder
import io.mockk.every
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
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
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
    private val eventRepo = mockk<EventRepositoryContract>(relaxed = true)

    private lateinit var useCase: JoinGroupUseCase
    private var savedGroup: Group? = null

    /** The joiner. */
    private val pubkey = "aa".repeat(32)

    /** Immutable creator root carried by the invite, distinct from the joiner. */
    private val creatorPubkey = "cc".repeat(32)
    private val createdAt = 1_700_000_000L
    private val groupId = GroupIdentity.derive(creatorPubkey, createdAt)
    private val groupKey = Base64.getEncoder().encodeToString(ByteArray(32) { 7 })

    /** Byte offset of the expiry field when the link carries no custom relays. */
    private val expiryPos = 1 + 16 + 32 + 8 + 2 + 32 + 2 + 1

    private fun inviteGroup(
        name: String = "TestGroup",
        relays: List<String> = listOf("wss://purplerelay.com"),
        keyEpoch: Int = 0
    ): Group = Group(groupId, name, "", creatorPubkey, createdAt, listOf(creatorPubkey), relays, keyEpoch = keyEpoch)

    /** Encode an invite link for the creator-bound test group. */
    private fun buildInviteUri(
        name: String = "TestGroup",
        relays: List<String> = listOf("wss://purplerelay.com"),
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

        every { identity.identityState() } returns IdentityState.READY
        every { identity.getPublicKeyHex() } returns pubkey
        every { signer.createSignedEvent(any(), any(), any(), any(), any(), any()) } answers {
            NostrEvent("join-event", identity.getPublicKeyHex(), 1, 30078, emptyList(), "enc", "sig")
        }
        every { settings.displayNameFor(pubkey) } returns ""
        coEvery { groupRepo.getById(any()) } answers { savedGroup }
        coEvery { groupRepo.save(any(), any()) } answers { savedGroup = firstArg() }
        coEvery { eventPublisher.prepareJoinedGroup(any(), any(), any()) } coAnswers {
            val candidate = firstArg<Group>()
            groupRepo.getById(candidate.id) ?: candidate.also { groupRepo.save(it, secondArg()) }
        }
        coEvery { eventPublisher.publishJoinedGroup(any(), any(), any()) } coAnswers {
            val event = firstArg<NostrEvent>()
            val current = secondArg<Group>()
            groupRepo.applyAuthenticatedMeta(
                current.id,
                thirdArg(),
                event.pubkey,
                event.createdAt,
                event.id,
                current.keyEpoch,
                current
            )
        }
        coEvery { groupRepo.getGroupKeyForEpoch(any(), any()) } returns groupKey
        coEvery { groupRepo.nameClockFloor(any(), any()) } returns 0
        coEvery { groupRepo.applyAuthenticatedMeta(any(), any(), any(), any(), any(), any(), any()) } returns true

        useCase =
            JoinGroupUseCase(
                groupRepo, identity, nostrClient, signer, encryption,
                eventPublisher, selfHeal, syncEngine, settings, eventRepo
            )
    }

    @After
    fun teardown() {
        unmockkStatic(android.util.Log::class)
    }

    @Test
    fun `all non-ready identities fail before any persistence or network call`() = runBlocking {
        for (state in IdentityState.entries.filter { it != IdentityState.READY }) {
            every { identity.identityState() } returns state
            assertTrue(runCatching { useCase(buildInviteUri()) }.exceptionOrNull() is IllegalStateException)
        }
        coVerify(exactly = 0) { groupRepo.save(any(), any()) }
        coVerify(exactly = 0) { groupRepo.saveGroupKeyForEpoch(any(), any(), any()) }
        coVerify(exactly = 0) { eventPublisher.publishJoinedGroup(any(), any(), any()) }
        coVerify(exactly = 0) { syncEngine.pullEvents(any(), any(), any(), any()) }
        verify(exactly = 0) { identity.getPublicKeyHex() }
        verify(exactly = 0) { identity.generateKeyPair() }
    }

    @Test
    fun `ready identity still requires canonical public key before saving`() = runBlocking {
        for (key in listOf("", " ", "AA".repeat(32), "ab".repeat(31), "gg".repeat(32))) {
            every { identity.getPublicKeyHex() } returns key
            assertTrue(runCatching { useCase(buildInviteUri()) }.exceptionOrNull() is IllegalStateException)
        }
        coVerify(exactly = 0) { groupRepo.save(any(), any()) }
        coVerify(exactly = 0) { groupRepo.saveGroupKeyForEpoch(any(), any(), any()) }
        coVerify(exactly = 0) { eventPublisher.publishJoinedGroup(any(), any(), any()) }
    }

    @Test
    fun `publication failure is retryable rather than a successful join`() = runBlocking {
        coEvery { eventPublisher.publishJoinedGroup(any(), any(), any()) } throws IllegalStateException("disk full")
        assertEquals("disk full", runCatching { useCase(buildInviteUri()) }.exceptionOrNull()?.message)
        coEvery { eventPublisher.publishJoinedGroup(any(), any(), any()) } returns true
        assertEquals(groupId, useCase(buildInviteUri()).id)
    }

    @Test
    fun `identity loss during initial history fails before signing and applying membership`() = runBlocking {
        coEvery { syncEngine.pullEvents(any(), any(), any(), any()) } answers {
            every { identity.identityState() } returns IdentityState.UNAVAILABLE
            PullResult(0, complete = true)
        }
        assertTrue(runCatching { useCase(buildInviteUri()) }.exceptionOrNull() is IllegalStateException)
        verify(exactly = 0) { signer.createSignedEvent(any(), any(), any(), any(), any(), any()) }
        coVerify(exactly = 0) { groupRepo.applyAuthenticatedMeta(any(), any(), any(), any(), any(), any(), any()) }
        coVerify(exactly = 0) { eventPublisher.publishJoinedGroup(any(), any(), any()) }
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
        coEvery { groupRepo.save(capture(saved), capture(savedKey)) } answers { savedGroup = firstArg() }

        useCase(buildInviteUri(keyEpoch = 3))

        assertEquals(groupId, saved.captured.id)
        assertEquals(creatorPubkey, saved.captured.createdBy)
        assertEquals(createdAt, saved.captured.createdAt)
        assertEquals(3, saved.captured.keyEpoch)
        assertEquals(listOf(pubkey), saved.captured.members)
        // The invite key reaches the repository with epoch 3; storage internals are mocked.
        assertEquals(groupKey, savedKey.captured)
    }

    @Test
    fun `invoke publishes join announcement with the verified creator and createdAt`() = runBlocking {
        useCase(buildInviteUri())
        // Check the saved creator binding and publication request; payload contents are not inspected here.
        coVerify { groupRepo.save(match { it.createdBy == creatorPubkey && it.createdAt == createdAt }, groupKey) }
        coVerify { eventPublisher.publishJoinedGroup(any(), match { it.id == groupId }, any()) }
    }

    @Test
    fun `invoke returns existing group if already joined`() = runBlocking {
        val existing = Group(groupId, "Existing", "", creatorPubkey, createdAt, listOf(pubkey), listOf("wss://r"))
        coEvery { groupRepo.getById(groupId) } returns existing
        coEvery { eventRepo.getEventsByType(groupId, "group_meta") } returns listOf(storedMeta(pubkey))
        val result = useCase(buildInviteUri())
        assertEquals("Existing", result.name)
        coVerify(exactly = 0) { groupRepo.save(any(), any()) }
        coVerify(exactly = 0) { eventPublisher.publishJoinedGroup(any(), any(), any()) }
        coVerify(exactly = 0) { syncEngine.pullEvents(any(), any(), any(), any()) }
    }

    /** Synthetic stored metadata attributed to [author]; no signature is generated. */
    private fun storedMeta(author: String) = EventSnapshot(
        eventId = "meta-$author",
        groupId = groupId,
        pubkey = author,
        createdAt = createdAt + 5,
        contentEncrypted = "",
        eventType = "group_meta"
    )

    // --- interrupted joins resume instead of reporting "already joined" ---

    @Test
    fun `a saved group without a join announcement is resumed, not reported as already joined`() = runBlocking {
        // Model a saved row and key with no joiner-authored announcement.
        val saved = Group(groupId, "TestGroup", "", creatorPubkey, createdAt, listOf(pubkey), listOf("wss://r"))
        coEvery { groupRepo.getById(groupId) } returns saved
        coEvery { groupRepo.getGroupKeyForEpoch(groupId, 0) } returns groupKey
        // Only the creator's meta is stored; nothing by the joiner.
        coEvery { eventRepo.getEventsByType(groupId, "group_meta") } returns listOf(storedMeta(creatorPubkey))
        coEvery { syncEngine.pullEvents(any(), any(), any(), any()) } returns PullResult(stored = 0, complete = true)

        useCase(buildInviteUri())

        coVerify(exactly = 0) { groupRepo.save(any(), any()) }
        coVerify { syncEngine.pullEvents(groupId, 0, groupKey, true) }
        coVerify { groupRepo.applyAuthenticatedMeta(groupId, any(), pubkey, any(), any(), 0, saved) }
        coVerify(exactly = 1) { eventPublisher.publishJoinedGroup(any(), match { it.id == groupId }, any()) }
    }

    @Test
    fun `a cancellation during initial sync leaves the group saved and the retry completes the join`() = runBlocking {
        var stored: Group? = null
        coEvery { groupRepo.save(any(), any()) } answers { stored = firstArg() }
        coEvery { groupRepo.getById(groupId) } answers { stored }
        coEvery { groupRepo.getGroupKeyForEpoch(groupId, 0) } returns groupKey
        val announced = mutableListOf<EventSnapshot>()
        coEvery { eventRepo.getEventsByType(groupId, "group_meta") } answers { announced.toList() }
        coEvery { eventPublisher.publishJoinedGroup(any(), match { it.id == groupId }, any()) } answers {
            announced += storedMeta(pubkey)
            true
        }
        // Inject cancellation during initial sync; no Activity lifecycle runs here.
        coEvery { syncEngine.pullEvents(any(), any(), any(), any()) } throws
            kotlinx.coroutines.CancellationException("activity destroyed")
        try {
            useCase(buildInviteUri())
            error("expected the first attempt to be cancelled")
        } catch (_: kotlinx.coroutines.CancellationException) { }
        assertNotNull(stored)
        coVerify(exactly = 0) { eventPublisher.publishJoinedGroup(any(), any(), any()) }

        // Retry the saved group without saving a second copy.
        coEvery { syncEngine.pullEvents(any(), any(), any(), any()) } returns PullResult(stored = 0, complete = true)
        useCase(buildInviteUri())

        coVerify(exactly = 1) { groupRepo.save(any(), any()) }
        coVerify(exactly = 1) { eventPublisher.publishJoinedGroup(any(), match { it.id == groupId }, any()) }

        // An existing join announcement prevents another sync or publication.
        useCase(buildInviteUri())
        coVerify(exactly = 1) { eventPublisher.publishJoinedGroup(any(), match { it.id == groupId }, any()) }
        coVerify(exactly = 2) { syncEngine.pullEvents(any(), any(), any(), any()) }
    }

    @Test
    fun `the creator re-scanning their own invite never announces a join`() = runBlocking {
        every { identity.getPublicKeyHex() } returns creatorPubkey
        val mine = Group(groupId, "Mine", "", creatorPubkey, createdAt, listOf(creatorPubkey), listOf("wss://r"))
        coEvery { groupRepo.getById(groupId) } returns mine

        assertEquals("Mine", useCase(buildInviteUri()).name)

        coVerify(exactly = 0) { eventRepo.getEventsByType(any(), any()) }
        coVerify(exactly = 0) { eventPublisher.publishJoinedGroup(any(), any(), any()) }
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
        coEvery { syncEngine.pullEvents(any(), any(), any(), lenientTimestamp = true) } returns
            PullResult(stored = 3, complete = true)

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
        coVerify { eventPublisher.publishJoinedGroup(any(), match { it.id == groupId }, any()) }
    }

    @Test
    fun `invoke with non-expired link succeeds`() = runBlocking {
        assertNotNull(useCase(buildInviteUri()))
    }

    @Test
    fun `invoke records the signed join as guarded canonical metadata carrying the display name`() = runBlocking {
        every { settings.displayNameFor(pubkey) } returns "Bob"
        useCase(buildInviteUri())
        coVerify(exactly = 1) {
            groupRepo.applyAuthenticatedMeta(
                groupId,
                match { it.memberNames[pubkey] == "Bob" && pubkey in it.members },
                pubkey,
                any(),
                any(),
                0,
                savedGroup
            )
        }
        // A member join must not use the creator metadata update API.
        coVerify(exactly = 0) {
            groupRepo.updateFromMeta(any(), any(), any(), any(), any(), any(), any(), any(), any(), any())
        }
    }

    @Test
    fun `invoke with a blank display name joins without touching the name`() = runBlocking {
        every { settings.displayNameFor(pubkey) } returns "   "
        useCase(buildInviteUri())
        coVerify(exactly = 1) {
            groupRepo.applyAuthenticatedMeta(
                groupId,
                match { pubkey !in it.memberNames && pubkey in it.members },
                pubkey,
                any(),
                any(),
                0,
                savedGroup
            )
        }
    }

    @Test
    fun `invoke orders the local join by the published announcement's own clock`() = runBlocking {
        val joinEvent = NostrEvent("join-evt", pubkey, 4242, 30078, emptyList(), "enc", "sig")
        every { signer.createSignedEvent(any(), any(), any(), any(), any(), any()) } returns joinEvent

        useCase(buildInviteUri())

        coVerify(exactly = 1) {
            groupRepo.applyAuthenticatedMeta(
                groupId,
                any(),
                pubkey,
                4242,
                "join-evt",
                0,
                savedGroup
            )
        }
        coVerify { eventPublisher.publishJoinedGroup(joinEvent, match { it.id == groupId }, any()) }
    }

    @Test
    fun `invoke publishes a join announcement listing the joiner alongside the synced roster`() = runBlocking {
        every { settings.displayNameFor(pubkey) } returns "Bob"
        val synced = Group(groupId, "TestGroup", "", creatorPubkey, createdAt, listOf(creatorPubkey), listOf("wss://r"))
        coEvery { groupRepo.getById(groupId) } returnsMany listOf(null, null, synced, synced)
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
    fun `invoke refuses changed original group identity before announcement`() = runBlocking {
        val hijacked = Group(groupId, "TestGroup", "", "ff".repeat(32), createdAt, listOf(pubkey), listOf("wss://r"))
        // After the initial miss, return a row whose immutable creator root differs from the invite.
        coEvery { groupRepo.getById(groupId) } returnsMany listOf(null, null, hijacked, hijacked)

        val error = runCatching { useCase(buildInviteUri()) }.exceptionOrNull()
        assertTrue(error is IllegalStateException)
        assertEquals("Original group identity changed while joining", error?.message)
        coVerify(exactly = 0) { eventPublisher.publishJoinedGroup(any(), any(), any()) }
    }

    @Test fun `name publication uses current identity scope instead of last global preference`() = runBlocking {
        every { settings.displayName } returns "Other identity"
        every { settings.displayNameFor(pubkey) } returns "Current identity"
        useCase(buildInviteUri())
        coVerify {
            groupRepo.applyAuthenticatedMeta(
                groupId,
                match { it.memberNames[pubkey] == "Current identity" },
                pubkey,
                any(),
                any(),
                0,
                savedGroup
            )
        }
        verify(exactly = 0) { settings.displayName }
    }

    @Test fun `join timestamp sorts strictly after creator watermark even in the same second`() = runBlocking {
        val now = System.currentTimeMillis() / 1000
        coEvery { groupRepo.nameClockFloor(groupId, pubkey) } returns now
        useCase(buildInviteUri())
        verify { signer.createSignedEvent(groupId, "group_meta", any(), any(), any(), now + 1) }
    }

    @Test fun `far future creator watermark defers join instead of weakening receiver bound`() = runBlocking {
        coEvery { groupRepo.nameClockFloor(groupId, pubkey) } returns System.currentTimeMillis() / 1000 + 4000
        org.junit.Assert.assertThrows(IllegalStateException::class.java) { runBlocking { useCase(buildInviteUri()) } }
        coVerify(exactly = 0) { eventPublisher.publishJoinedGroup(any(), any(), any()) }
    }

    @Test fun `rejected local join must not publish an unauthorized announcement`() = runBlocking {
        coEvery { groupRepo.applyAuthenticatedMeta(any(), any(), any(), any(), any(), any(), any()) } returns false
        org.junit.Assert.assertThrows(IllegalStateException::class.java) { runBlocking { useCase(buildInviteUri()) } }
        coVerify(exactly = 1) { eventPublisher.publishJoinedGroup(any(), any(), any()) }
        coVerify(exactly = 0) { eventPublisher.publishDirect(any(), any(), any(), any(), any()) }
    }

    @Test fun `epoch changed by initial sync signs with current epoch key and includes epoch metadata`() = runBlocking {
        val live = inviteGroup(keyEpoch = 2)
        coEvery { groupRepo.getById(groupId) } returnsMany listOf(null, null, live, live)
        coEvery { groupRepo.getGroupKeyForEpoch(groupId, 2) } returns "epoch-two"
        val plaintext = slot<String>()
        every { encryption.encrypt(capture(plaintext), "epoch-two") } returns "enc"
        useCase(buildInviteUri())
        assertEquals(2, kotlinx.serialization.json.Json.decodeFromString<GroupMeta>(plaintext.captured).keyEpoch)
        coVerify { groupRepo.applyAuthenticatedMeta(groupId, any(), pubkey, any(), any(), 2, live) }
    }

    @Test fun `resume persists a missing same epoch key and reads it back before sync`() = runBlocking {
        val saved = inviteGroup(keyEpoch = 2)
        coEvery { groupRepo.getById(groupId) } returns saved
        var storedKey: String? = null
        coEvery { groupRepo.getGroupKeyForEpoch(groupId, 2) } answers { storedKey }
        coEvery { groupRepo.saveGroupKeyForEpoch(groupId, 2, groupKey) } answers { storedKey = thirdArg() }

        useCase(buildInviteUri(keyEpoch = 2))

        assertEquals(groupKey, storedKey)
        coVerifyOrder {
            groupRepo.saveGroupKeyForEpoch(groupId, 2, groupKey)
            groupRepo.getGroupKeyForEpoch(groupId, 2)
            syncEngine.pullEvents(groupId, 0, groupKey, true)
            eventPublisher.publishJoinedGroup(any(), match { it.id == groupId }, any())
        }
    }

    @Test fun `resume rejects a stale invite when the current epoch key is missing`() = runBlocking {
        coEvery { groupRepo.getById(groupId) } returns inviteGroup(keyEpoch = 2)
        coEvery { groupRepo.getGroupKeyForEpoch(groupId, 2) } returns null

        assertThrows(IllegalStateException::class.java) { runBlocking { useCase(buildInviteUri()) } }

        coVerify(exactly = 0) { groupRepo.saveGroupKeyForEpoch(any(), any(), any()) }
        coVerify(exactly = 0) { syncEngine.pullEvents(any(), any(), any(), any()) }
        coVerify(exactly = 0) { eventPublisher.publishJoinedGroup(any(), any(), any()) }
    }

    @Test fun `resume with a stored current key ignores an older invite key`() = runBlocking {
        coEvery { groupRepo.getById(groupId) } returns inviteGroup(keyEpoch = 2)
        coEvery { groupRepo.getGroupKeyForEpoch(groupId, 2) } returns "current-key"

        useCase(buildInviteUri())

        coVerify { syncEngine.pullEvents(groupId, 0, "current-key", true) }
        verify { encryption.encrypt(any(), "current-key") }
        coVerify(exactly = 0) { groupRepo.saveGroupKeyForEpoch(any(), any(), any()) }
    }

    @Test fun `key recovery storage failure stops before sync or announcement`() = runBlocking {
        coEvery { groupRepo.getById(groupId) } returns inviteGroup()
        coEvery { groupRepo.getGroupKeyForEpoch(groupId, 0) } returns null
        coEvery { groupRepo.saveGroupKeyForEpoch(groupId, 0, groupKey) } throws
            com.splitfree.domain.repository.SecureStorageException("write failed")

        assertThrows(com.splitfree.domain.repository.SecureStorageException::class.java) {
            runBlocking { useCase(buildInviteUri()) }
        }

        coVerify(exactly = 0) { syncEngine.pullEvents(any(), any(), any(), any()) }
        coVerify(exactly = 0) { eventPublisher.publishJoinedGroup(any(), any(), any()) }
    }

    @Test fun `key recovery refuses a write that cannot be read back`() = runBlocking {
        coEvery { groupRepo.getById(groupId) } returns inviteGroup()
        coEvery { groupRepo.getGroupKeyForEpoch(groupId, 0) } returns null

        assertThrows(IllegalStateException::class.java) { runBlocking { useCase(buildInviteUri()) } }

        coVerify { groupRepo.saveGroupKeyForEpoch(groupId, 0, groupKey) }
        coVerify(exactly = 0) { syncEngine.pullEvents(any(), any(), any(), any()) }
        coVerify(exactly = 0) { eventPublisher.publishJoinedGroup(any(), any(), any()) }
    }

    @Test fun `key recovery refuses a different readback key`() = runBlocking {
        coEvery { groupRepo.getById(groupId) } returns inviteGroup()
        coEvery { groupRepo.getGroupKeyForEpoch(groupId, 0) } returnsMany listOf(null, "different-key")

        assertThrows(IllegalStateException::class.java) { runBlocking { useCase(buildInviteUri()) } }

        coVerify(exactly = 0) { syncEngine.pullEvents(any(), any(), any(), any()) }
        coVerify(exactly = 0) { eventPublisher.publishJoinedGroup(any(), any(), any()) }
    }

    @Test fun `join refuses a current key lost during initial sync`() = runBlocking {
        coEvery { groupRepo.getGroupKeyForEpoch(groupId, 0) } returnsMany listOf(groupKey, null)

        assertThrows(IllegalStateException::class.java) { runBlocking { useCase(buildInviteUri()) } }

        coVerify(exactly = 0) { eventPublisher.publishJoinedGroup(any(), any(), any()) }
    }

    @Test fun `rotation during recovery cannot announce without its new key`() = runBlocking {
        coEvery { groupRepo.getById(groupId) } returnsMany
            listOf(inviteGroup(), inviteGroup(), inviteGroup(keyEpoch = 2))
        coEvery { groupRepo.getGroupKeyForEpoch(groupId, 0) } returnsMany listOf(null, groupKey)
        coEvery { groupRepo.getGroupKeyForEpoch(groupId, 2) } returns null

        assertThrows(IllegalStateException::class.java) { runBlocking { useCase(buildInviteUri()) } }

        coVerify { groupRepo.saveGroupKeyForEpoch(groupId, 0, groupKey) }
        coVerify(exactly = 0) { eventPublisher.publishJoinedGroup(any(), any(), any()) }
    }

    @Test fun `group deletion during initial sync cannot announce a completed join`() = runBlocking {
        coEvery { groupRepo.getById(groupId) } returns null

        assertThrows(IllegalStateException::class.java) { runBlocking { useCase(buildInviteUri()) } }

        coVerify(exactly = 0) { eventPublisher.publishJoinedGroup(any(), any(), any()) }
    }
}
