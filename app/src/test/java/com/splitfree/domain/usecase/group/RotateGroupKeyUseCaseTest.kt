package com.splitfree.domain.usecase.group

import com.splitfree.domain.crypto.EventSigner
import com.splitfree.domain.crypto.GroupEncryption
import com.splitfree.domain.crypto.NostrEvent
import com.splitfree.domain.crypto.nip.Nip44
import com.splitfree.domain.model.group.Group
import com.splitfree.domain.model.group.GroupMeta
import com.splitfree.domain.model.group.KeyRotation
import com.splitfree.domain.repository.ControlOperation
import com.splitfree.domain.repository.EventPublisherContract
import com.splitfree.domain.repository.EventRepositoryContract
import com.splitfree.domain.repository.EventSnapshot
import com.splitfree.domain.repository.GroupRepositoryContract
import com.splitfree.domain.repository.IdentityContract
import com.splitfree.domain.repository.SecureStorageException
import com.splitfree.domain.util.hexToBytes
import com.splitfree.sync.event.RotationOutcome
import com.splitfree.test.FakeControlOperationJournal
import io.mockk.MockKMatcherScope
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.coVerifyOrder
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.slot
import io.mockk.unmockkStatic
import io.mockk.verify
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class RotateGroupKeyUseCaseTest {
    private val groupRepo = mockk<GroupRepositoryContract>(relaxed = true)
    private val encryption = mockk<GroupEncryption>()
    private val identity = mockk<IdentityContract>()
    private val signer = mockk<EventSigner>()
    private val eventPublisher = mockk<EventPublisherContract>(relaxed = true)
    private val eventRepo = mockk<EventRepositoryContract>(relaxed = true)

    private val journal = FakeControlOperationJournal()
    private val keys = mutableMapOf<Int, String>()
    private lateinit var useCase: RotateGroupKeyUseCase

    // Real secp256k1 keys so NIP-44 conversation-key derivation works end to end.
    private val creatorPriv = ByteArray(32).also { it[31] = 1 }
    private val creatorPub = NostrEvent.pubkeyFromPrivkey(creatorPriv)
    private val peerPriv = ByteArray(32).also { it[31] = 2 }
    private val peerPub = NostrEvent.pubkeyFromPrivkey(peerPriv)
    private val removedPub = NostrEvent.pubkeyFromPrivkey(ByteArray(32).also { it[31] = 3 })
    private val otherPub = NostrEvent.pubkeyFromPrivkey(ByteArray(32).also { it[31] = 4 })

    private val groupId = "group-1"
    private val newKey = "new-epoch-key"
    private val group =
        Group(
            groupId,
            "Trip",
            "",
            creatorPub,
            1000,
            listOf(creatorPub, peerPub, removedPub),
            listOf("wss://r"),
            memberNames = mapOf(creatorPub to "Alice", peerPub to "Bob", removedPub to "Mallory"),
            keyEpoch = 0
        )
    private val fakeEvent = NostrEvent("evt1", creatorPub, 1000, 30078, emptyList(), "enc", "sig")
    private val json = Json { ignoreUnknownKeys = true }

    @Before
    fun setup() {
        mockkStatic(android.util.Log::class)
        every { android.util.Log.i(any<String>(), any<String>()) } returns 0
        every { android.util.Log.w(any<String>(), any<String>()) } returns 0

        every { identity.hasPendingKeyPair() } returns false
        every { identity.getPublicKeyHex() } returns creatorPub
        every { identity.getPrivateKeyBytes() } answers { creatorPriv.copyOf() }
        every { encryption.generateGroupKey() } returns newKey
        every { encryption.encrypt(any(), any()) } returns "meta-enc"
        // Like the real signer: an addressed envelope carries its `p` tag and an explicit created_at sticks.
        every { signer.createSignedEvent(any(), any(), any(), any(), any(), any()) } answers {
            fakeEvent.copy(
                content = thirdArg(),
                tags = listOfNotNull(arg<String?>(4)?.let { listOf("p", it) }),
                createdAt = arg<Long?>(5) ?: fakeEvent.createdAt
            )
        }
        coEvery { groupRepo.getById(groupId) } returns group
        // A relaxed mock would answer "" here, which reads as conflicting key material (receiver) or as
        // an interrupted rotation to resume (creator).
        coEvery { groupRepo.getGroupKeyForEpoch(any(), any()) } answers { keys[secondArg()] }
        coEvery { groupRepo.saveGroupKeyForEpoch(any(), any(), any()) } answers { keys[secondArg()] = thirdArg() }
        coEvery { groupRepo.applyKeyRotation(any(), any(), any(), any(), any()) } returns true
        coEvery { groupRepo.resolveRoster(any(), any()) } answers { secondArg() }

        useCase =
            RotateGroupKeyUseCase(
                groupRepo,
                encryption,
                identity,
                signer,
                eventPublisher,
                eventRepo,
                journal,
                ControlOperationLock()
            )
    }

    @After
    fun teardown() {
        unmockkStatic(android.util.Log::class)
    }

    /** Switch the mocked identity to the peer so the receiver path can be exercised. */
    private fun actAsPeer() {
        every { identity.getPublicKeyHex() } returns peerPub
        every { identity.getPrivateKeyBytes() } answers { peerPriv.copyOf() }
    }

    /** Builds the rotation payload the creator would send to the peer, with a real NIP-44 wrapped key. */
    private fun rotationFor(
        epoch: Int,
        members: List<String> = listOf(creatorPub, peerPub),
        removedMember: String = removedPub,
        keyFor: List<String> = members,
        key: String = newKey
    ): String {
        val encryptedKeys = keyFor.associateWith { member ->
            val convKey = Nip44.getConversationKey(creatorPriv, member.hexToBytes())
            Nip44.encrypt(key, convKey)
        }
        return json.encodeToString(
            KeyRotation.serializer(),
            KeyRotation(epoch = epoch, encryptedKeys = encryptedKeys, members = members, removedMember = removedMember)
        )
    }

    /**
     * A rotation lands epoch and roster together through `applyKeyRotation`; it must never be written
     * as a separate `updateKeyEpoch` followed by a last-writer-wins `updateFromMeta`.
     */
    private fun assertNoLegacyTwoStepWrite() {
        coVerify(exactly = 0) { groupRepo.updateKeyEpoch(any(), any()) }
        coVerify(exactly = 0) { anyUpdateFromMeta() }
    }

    private suspend fun MockKMatcherScope.anyUpdateFromMeta(): Boolean =
        groupRepo.updateFromMeta(any(), any(), any(), any(), any(), any(), any(), any(), any(), any())

    // --- creator path ---

    @Test
    fun `persists new epoch key before publishing the first rotation event`() = runBlocking {
        useCase(groupId, removedPub)

        coVerifyOrder {
            groupRepo.saveGroupKeyForEpoch(groupId, 1, newKey)
            eventPublisher.publishDirect(any(), groupId, any(), "key_rotation", any())
        }
    }

    @Test
    fun `publishes one rotation event per remaining member and advances epoch after publishing`() = runBlocking {
        useCase(groupId, removedPub)

        coVerify(exactly = 2) { eventPublisher.publishDirect(any(), groupId, any(), "key_rotation", any()) }
        coVerifyOrder {
            eventPublisher.publishDirect(any(), groupId, any(), "key_rotation", any())
            groupRepo.applyKeyRotation(groupId, 1, any(), any(), any())
        }
        coVerify(exactly = 1) {
            groupRepo.applyKeyRotation(
                groupId,
                1,
                match { removedPub !in it && creatorPub in it && peerPub in it },
                match { removedPub !in it && it[creatorPub] == "Alice" && it[peerPub] == "Bob" },
                any()
            )
        }
        assertNoLegacyTwoStepWrite()
    }

    @Test
    fun `invoke reuses the stored key for the next epoch instead of generating a new one`() = runBlocking {
        journal.insert(
            ControlOperation("rotation:$groupId", "rotation", json.encodeToString(RotationIntent(group, removedPub, 1)))
        )
        // Intent belongs to the same removal even before any envelope exists.
        coEvery { groupRepo.getGroupKeyForEpoch(groupId, 1) } returns "K1"
        val peerEnvelope = slot<String>()
        every {
            signer.createSignedEvent(groupId, "key_rotation", capture(peerEnvelope), null, recipientPubkey = peerPub)
        } returns fakeEvent

        useCase(groupId, removedPub)

        verify(exactly = 0) { encryption.generateGroupKey() }
        coVerify(exactly = 0) { groupRepo.saveGroupKeyForEpoch(any(), any(), any()) }
        coVerify(exactly = 1) { groupRepo.applyKeyRotation(groupId, 1, listOf(creatorPub, peerPub), any(), any()) }
        // Every envelope carries K1: open the peer's one with the peer's conversation key.
        val convKey = Nip44.getConversationKey(peerPriv, creatorPub.hexToBytes())
        val rotation = json.decodeFromString<KeyRotation>(Nip44.decrypt(peerEnvelope.captured, convKey))
        assertEquals(1, rotation.epoch)
        assertEquals("K1", Nip44.decrypt(rotation.encryptedKeys.getValue(peerPub), convKey))
        // ...and the post-rotation group_meta is sealed under K1 as well.
        verify(exactly = 1) { encryption.encrypt(any(), "K1") }
        verify(exactly = 0) { encryption.encrypt(any(), newKey) }
    }

    @Test
    fun `each per-member rotation event is addressed to that member with a p tag`() = runBlocking {
        useCase(groupId, removedPub)

        verify(exactly = 1) {
            signer.createSignedEvent(groupId, "key_rotation", any(), null, recipientPubkey = creatorPub)
        }
        verify(exactly = 1) {
            signer.createSignedEvent(groupId, "key_rotation", any(), null, recipientPubkey = peerPub)
        }
        // The removed member never gets an envelope, and the group_meta is not addressed to anyone.
        verify(exactly = 0) {
            signer.createSignedEvent(any(), "key_rotation", any(), any(), recipientPubkey = removedPub)
        }
        verify(exactly = 1) { signer.createSignedEvent(groupId, "group_meta", any(), null, null) }
    }

    @Test
    fun `publishes a group_meta encrypted with the new epoch key after rotating`() = runBlocking {
        val metaPlaintext = slot<String>()
        every { encryption.encrypt(capture(metaPlaintext), newKey) } returns "meta-enc"

        useCase(groupId, removedPub)

        coVerify(exactly = 1) { eventPublisher.publishDirect(any(), groupId, "meta-enc", "group_meta", any()) }
        // The meta must go out only once the local epoch has moved on, and under the NEW key.
        coVerifyOrder {
            groupRepo.applyKeyRotation(groupId, 1, any(), any(), any())
            eventPublisher.publishDirect(any(), groupId, "meta-enc", "group_meta", any())
        }
        verify(exactly = 1) { encryption.encrypt(any(), newKey) }
        val meta = json.decodeFromString<GroupMeta>(metaPlaintext.captured)
        assertEquals("Trip", meta.name)
        assertEquals(creatorPub, meta.createdBy)
        assertEquals(1000L, meta.createdAt)
        assertEquals(listOf(creatorPub, peerPub), meta.members)
        assertEquals(listOf("wss://r"), meta.relays)
        assertEquals(mapOf(creatorPub to "Alice", peerPub to "Bob"), meta.memberNames)
    }

    @Test
    fun `aborts without advancing the epoch when the group epoch changed during rotation`() = runBlocking {
        coEvery { groupRepo.getById(groupId) } returnsMany listOf(group, group, group.copy(keyEpoch = 2))

        val failure = runCatching { useCase(groupId, removedPub) }.exceptionOrNull()

        assertTrue("expected IllegalStateException, got $failure", failure is IllegalStateException)
        assertEquals("Group group-1 is at epoch 2, rotation targets 1", failure?.message)
        coVerify(exactly = 0) { groupRepo.applyKeyRotation(any(), any(), any(), any(), any()) }
        assertNoLegacyTwoStepWrite()
        coVerify(exactly = 0) { eventPublisher.publishDirect(any(), any(), any(), any(), any()) }
    }

    @Test
    fun `failed key write aborts before anything is published or the epoch changes`() = runBlocking {
        coEvery { groupRepo.saveGroupKeyForEpoch(groupId, 1, newKey) } throws
            SecureStorageException("keystore unavailable")

        val failure = runCatching { useCase(groupId, removedPub) }.exceptionOrNull()

        assertTrue("expected SecureStorageException, got $failure", failure is SecureStorageException)
        coVerify(exactly = 0) { eventPublisher.publishDirect(any(), any(), any(), any(), any()) }
        coVerify(exactly = 0) { groupRepo.applyKeyRotation(any(), any(), any(), any(), any()) }
        assertNoLegacyTwoStepWrite()
    }

    @Test
    fun `rejects rotation by non-creator`() = runBlocking {
        actAsPeer()

        val failure = runCatching { useCase(groupId, removedPub) }.exceptionOrNull()

        assertTrue(failure is IllegalStateException)
        coVerify(exactly = 0) { groupRepo.saveGroupKeyForEpoch(any(), any(), any()) }
        coVerify(exactly = 0) { eventPublisher.publishDirect(any(), any(), any(), any(), any()) }
    }

    @Test
    fun `rejects removing self`() = runBlocking {
        val failure = runCatching { useCase(groupId, creatorPub) }.exceptionOrNull()
        assertTrue(failure is IllegalStateException)
        coVerify(exactly = 0) { groupRepo.saveGroupKeyForEpoch(any(), any(), any()) }
    }

    @Test
    fun `rejects removing non-member`() = runBlocking {
        val outsider = NostrEvent.pubkeyFromPrivkey(ByteArray(32).also { it[31] = 9 })
        val failure = runCatching { useCase(groupId, outsider) }.exceptionOrNull()
        assertTrue(failure is IllegalStateException)
        coVerify(exactly = 0) { groupRepo.saveGroupKeyForEpoch(any(), any(), any()) }
    }

    // --- resumeIfNeeded ---

    /** A real, signed `key_rotation` envelope from the creator to [recipient], as stored after publishing. */
    private fun storedRotationRow(rotation: KeyRotation, recipient: String, createdAt: Long): EventSnapshot {
        val convKey = Nip44.getConversationKey(creatorPriv, recipient.hexToBytes())
        val envelope = Nip44.encrypt(json.encodeToString(KeyRotation.serializer(), rotation), convKey)
        val event =
            NostrEvent(
                pubkey = creatorPub,
                createdAt = createdAt,
                kind = 30078,
                tags = listOf(
                    listOf("d", "$groupId:${recipient.take(8)}"),
                    listOf("g", groupId),
                    listOf("t", "key_rotation"),
                    listOf("p", recipient)
                ),
                content = envelope
            ).sign(creatorPriv)
        return EventSnapshot(
            eventId = event.id,
            groupId = groupId,
            pubkey = creatorPub,
            createdAt = createdAt,
            contentEncrypted = envelope,
            eventType = "key_rotation",
            sig = event.sig,
            originalEventJson = event.toJson()
        )
    }

    private fun wrappedFor(key: String, vararg members: String): Map<String, String> =
        members.associateWith { Nip44.encrypt(key, Nip44.getConversationKey(creatorPriv, it.hexToBytes())) }

    @Test
    fun `resumeIfNeeded finishes an interrupted rotation from the stored key and my own key_rotation rows`() =
        runBlocking {
            coEvery { groupRepo.getAll() } returns listOf(group)
            coEvery { groupRepo.getGroupKeyForEpoch(groupId, 1) } returns "K1"
            val rotation =
                KeyRotation(
                    epoch = 1,
                    encryptedKeys = wrappedFor("K1", creatorPub, peerPub),
                    members = listOf(creatorPub, peerPub),
                    removedMember = removedPub
                )
            // Every envelope (the creator addresses one to itself too) left before the crash.
            coEvery { eventRepo.getEventsByType(groupId, "key_rotation") } returns
                listOf(
                    storedRotationRow(rotation, creatorPub, createdAt = 2000),
                    storedRotationRow(rotation, peerPub, createdAt = 2000)
                )
            val metaPlaintext = slot<String>()
            every { encryption.encrypt(capture(metaPlaintext), "K1") } returns "meta-enc"
            every { encryption.decrypt("meta-enc", "K1") } answers { metaPlaintext.captured }

            useCase.resumeIfNeeded()

            coVerify(exactly = 1) {
                groupRepo.applyKeyRotation(
                    groupId,
                    1,
                    listOf(creatorPub, peerPub),
                    mapOf(
                        creatorPub to "Alice",
                        peerPub to "Bob"
                    ),
                    any()
                )
            }
            coVerify(exactly = 1) { eventPublisher.publishDirect(any(), groupId, "meta-enc", "group_meta", any()) }
            val meta = json.decodeFromString<GroupMeta>(metaPlaintext.captured)
            assertEquals(listOf(creatorPub, peerPub), meta.members)
            // Every envelope already left: nothing to re-publish.
            coVerify(exactly = 2) { eventPublisher.publishDirect(any(), groupId, any(), "key_rotation", any()) }
            // The stored key is reused as-is.
            verify(exactly = 0) { encryption.generateGroupKey() }
            coVerify(exactly = 0) { groupRepo.saveGroupKeyForEpoch(any(), any(), any()) }
            assertNoLegacyTwoStepWrite()
        }

    @Test
    fun `resumeIfNeeded re-publishes envelopes that never left before finishing`() = runBlocking {
        val bigger = group.copy(members = listOf(creatorPub, peerPub, otherPub, removedPub))
        coEvery { groupRepo.getAll() } returns listOf(bigger)
        coEvery { groupRepo.getById(groupId) } returns bigger
        coEvery { groupRepo.getGroupKeyForEpoch(groupId, 1) } returns "K1"
        val metaPlaintext = slot<String>()
        every { encryption.encrypt(capture(metaPlaintext), "K1") } returns "meta-enc"
        every { encryption.decrypt("meta-enc", "K1") } answers { metaPlaintext.captured }
        val rotation =
            KeyRotation(
                epoch = 1,
                encryptedKeys = wrappedFor("K1", creatorPub, peerPub, otherPub),
                members = listOf(creatorPub, peerPub, otherPub),
                removedMember = removedPub
            )
        // Only my own and the peer's envelopes made it to the outbox before the crash.
        coEvery { eventRepo.getEventsByType(groupId, "key_rotation") } returns
            listOf(
                storedRotationRow(rotation, creatorPub, createdAt = 2000),
                storedRotationRow(rotation, peerPub, createdAt = 2000)
            )

        useCase.resumeIfNeeded()

        verify(exactly = 1) {
            signer.createSignedEvent(groupId, "key_rotation", any(), null, recipientPubkey = otherPub)
        }
        verify(exactly = 0) {
            signer.createSignedEvent(groupId, "key_rotation", any(), null, recipientPubkey = peerPub)
        }
        verify(exactly = 0) {
            signer.createSignedEvent(groupId, "key_rotation", any(), null, recipientPubkey = creatorPub)
        }
        coVerifyOrder {
            eventPublisher.publishDirect(any(), groupId, any(), "key_rotation", any())
            groupRepo.applyKeyRotation(groupId, 1, listOf(creatorPub, peerPub, otherPub), any(), any())
            eventPublisher.publishDirect(any(), groupId, any(), "group_meta", any())
        }
    }

    @Test
    fun `resumeIfNeeded leaves a stored key alone when no key_rotation was ever published`() = runBlocking {
        coEvery { groupRepo.getAll() } returns listOf(group)
        coEvery { groupRepo.getGroupKeyForEpoch(groupId, 1) } returns "K1"
        coEvery { eventRepo.getEventsByType(groupId, "key_rotation") } returns emptyList()

        useCase.resumeIfNeeded()

        coVerify(exactly = 0) { groupRepo.applyKeyRotation(any(), any(), any(), any(), any()) }
        coVerify(exactly = 0) { eventPublisher.publishDirect(any(), any(), any(), any(), any()) }
        assertNoLegacyTwoStepWrite()
    }

    @Test
    fun `resumeIfNeeded ignores rows for other epochs and rows I did not author`() = runBlocking {
        coEvery { groupRepo.getAll() } returns listOf(group)
        coEvery { groupRepo.getGroupKeyForEpoch(groupId, 1) } returns "K1"
        val stale =
            KeyRotation(
                epoch = 0,
                encryptedKeys = wrappedFor("K0", peerPub),
                members = listOf(creatorPub, peerPub),
                removedMember = ""
            )
        val foreign = storedRotationRow(
            KeyRotation(
                epoch = 1,
                encryptedKeys = wrappedFor("K1", peerPub),
                members = listOf(creatorPub, peerPub),
                removedMember = removedPub
            ),
            peerPub,
            createdAt = 2000
        ).copy(pubkey = peerPub)
        coEvery { eventRepo.getEventsByType(groupId, "key_rotation") } returns
            listOf(storedRotationRow(stale, peerPub, createdAt = 1500), foreign)

        useCase.resumeIfNeeded()

        coVerify(exactly = 0) { groupRepo.applyKeyRotation(any(), any(), any(), any(), any()) }
        coVerify(exactly = 0) { eventPublisher.publishDirect(any(), any(), any(), any(), any()) }
    }

    @Test
    fun `resumeIfNeeded does nothing when no key is stored for the next epoch`() = runBlocking {
        coEvery { groupRepo.getAll() } returns listOf(group)

        useCase.resumeIfNeeded()

        coVerify(exactly = 0) { eventRepo.getEventsByType(any(), any()) }
        coVerify(exactly = 0) { groupRepo.applyKeyRotation(any(), any(), any(), any(), any()) }
    }

    @Test
    fun `resumeIfNeeded skips groups this device did not create`() = runBlocking {
        actAsPeer()
        coEvery { groupRepo.getAll() } returns listOf(group)
        coEvery { groupRepo.getGroupKeyForEpoch(groupId, 1) } returns "K1"

        useCase.resumeIfNeeded()

        coVerify(exactly = 0) { eventRepo.getEventsByType(any(), any()) }
        coVerify(exactly = 0) { groupRepo.applyKeyRotation(any(), any(), any(), any(), any()) }
    }

    // --- receiver path ---

    @Test
    fun `handleKeyRotation applies epoch and roster through the single atomic repository call`() = runBlocking {
        actAsPeer()

        val outcome = useCase.handleKeyRotation(rotationFor(epoch = 1), creatorPub, groupId, createdAt = 5000)

        assertEquals(RotationOutcome.APPLIED, outcome)
        coVerifyOrder {
            groupRepo.saveGroupKeyForEpoch(groupId, 1, newKey)
            groupRepo.applyKeyRotation(
                groupId,
                1,
                listOf(creatorPub, peerPub),
                match { removedPub !in it && it[creatorPub] == "Alice" && it[peerPub] == "Bob" },
                any()
            )
        }
        coVerify(exactly = 1) { groupRepo.applyKeyRotation(any(), any(), any(), any(), any()) }
        assertNoLegacyTwoStepWrite()
    }

    @Test
    fun `handleKeyRotation ignores a rotation for the current epoch even when I am not in its members`() = runBlocking {
        actAsPeer()
        // I was removed at epoch 1 and later re-invited; the group is at epoch 1 with me back in.
        coEvery { groupRepo.getById(groupId) } returns group.copy(keyEpoch = 1)

        val outcome = useCase.handleKeyRotation(
            rotationFor(epoch = 1, members = listOf(creatorPub, removedPub), removedMember = peerPub),
            creatorPub,
            groupId,
            createdAt = 4000
        )

        assertEquals(RotationOutcome.IGNORED, outcome)
        coVerify(exactly = 0) { groupRepo.applyKeyRotation(any(), any(), any(), any(), any()) }
        coVerify(exactly = 0) { groupRepo.saveGroupKeyForEpoch(any(), any(), any()) }
        assertNoLegacyTwoStepWrite()
    }

    @Test
    fun `handleKeyRotation ignores a rotation for an older epoch`() = runBlocking {
        actAsPeer()
        coEvery { groupRepo.getById(groupId) } returns group.copy(keyEpoch = 3)

        val outcome = useCase.handleKeyRotation(rotationFor(epoch = 2), creatorPub, groupId, createdAt = 4000)

        assertEquals(RotationOutcome.IGNORED, outcome)
        coVerify(exactly = 0) { groupRepo.saveGroupKeyForEpoch(any(), any(), any()) }
        coVerify(exactly = 0) { groupRepo.applyKeyRotation(any(), any(), any(), any(), any()) }
    }

    @Test
    fun `handleKeyRotation defers a rotation that skips an epoch`() = runBlocking {
        actAsPeer()

        val outcome = useCase.handleKeyRotation(rotationFor(epoch = 2), creatorPub, groupId, createdAt = 5000)

        assertEquals(RotationOutcome.DEFERRED_EPOCH_GAP, outcome)
        coVerify(exactly = 0) { groupRepo.saveGroupKeyForEpoch(any(), any(), any()) }
        coVerify(exactly = 0) { groupRepo.applyKeyRotation(any(), any(), any(), any(), any()) }
        assertNoLegacyTwoStepWrite()
    }

    @Test
    fun `handleKeyRotation applies epoch 2 once epoch 1 has landed`() = runBlocking {
        actAsPeer()
        // The repository behaves like Room: applyKeyRotation is reflected by the next getById.
        var epoch = 0
        coEvery { groupRepo.getById(groupId) } answers { group.copy(keyEpoch = epoch) }
        coEvery { groupRepo.applyKeyRotation(groupId, any(), any(), any(), any()) } answers {
            epoch = secondArg()
            true
        }

        val first = useCase.handleKeyRotation(rotationFor(epoch = 2), creatorPub, groupId, createdAt = 5000)
        val second = useCase.handleKeyRotation(rotationFor(epoch = 1), creatorPub, groupId, createdAt = 4000)
        val third = useCase.handleKeyRotation(rotationFor(epoch = 2), creatorPub, groupId, createdAt = 5000)

        assertEquals(
            listOf(RotationOutcome.DEFERRED_EPOCH_GAP, RotationOutcome.APPLIED, RotationOutcome.APPLIED),
            listOf(first, second, third)
        )
        coVerify(exactly = 1) { groupRepo.saveGroupKeyForEpoch(groupId, 1, newKey) }
        coVerify(exactly = 1) { groupRepo.saveGroupKeyForEpoch(groupId, 2, newKey) }
        assertEquals(2, epoch)
    }

    @Test
    fun `handleKeyRotation applies a rotation removing someone this device never saw join`() = runBlocking {
        actAsPeer()
        val outsider = NostrEvent.pubkeyFromPrivkey(ByteArray(32).also { it[31] = 9 })

        // The removal of an unknown member is a missing earlier join on this side, not an invalid
        // rotation: the roster it defines only names members we know.
        val outcome = useCase.handleKeyRotation(
            rotationFor(epoch = 1, members = listOf(creatorPub, peerPub, removedPub), removedMember = outsider),
            creatorPub,
            groupId,
            createdAt = 5000
        )

        assertEquals(RotationOutcome.APPLIED, outcome)
        coVerify(exactly = 1) { groupRepo.saveGroupKeyForEpoch(groupId, 1, newKey) }
        coVerify(exactly = 1) {
            groupRepo.applyKeyRotation(groupId, 1, listOf(creatorPub, peerPub, removedPub), any(), any())
        }
    }

    @Test
    fun `handleKeyRotation for my own removal advances the epoch without storing a key`() = runBlocking {
        actAsPeer()

        val outcome = useCase.handleKeyRotation(
            rotationFor(epoch = 1, members = listOf(creatorPub, removedPub), removedMember = peerPub),
            creatorPub,
            groupId,
            createdAt = 6000
        )

        assertEquals(RotationOutcome.APPLIED, outcome)
        coVerify(exactly = 1) {
            groupRepo.applyKeyRotation(
                groupId,
                1,
                listOf(creatorPub, removedPub),
                match { peerPub !in it && it[creatorPub] == "Alice" && it[removedPub] == "Mallory" },
                any()
            )
        }
        coVerify(exactly = 0) { groupRepo.saveGroupKeyForEpoch(any(), any(), any()) }
        coVerify(exactly = 0) { groupRepo.getGroupKeyForEpoch(any(), any()) }
        assertNoLegacyTwoStepWrite()
    }

    @Test
    fun `handleKeyRotation rejects rotations not signed by the creator`() = runBlocking {
        actAsPeer()

        val outcome = useCase.handleKeyRotation(rotationFor(epoch = 1), peerPub, groupId, createdAt = 5000)

        assertEquals(RotationOutcome.REJECTED, outcome)
        coVerify(exactly = 0) { groupRepo.saveGroupKeyForEpoch(any(), any(), any()) }
        coVerify(exactly = 0) { groupRepo.applyKeyRotation(any(), any(), any(), any(), any()) }
    }

    @Test
    fun `handleKeyRotation rejects an unparseable payload`() = runBlocking {
        actAsPeer()

        val outcome = useCase.handleKeyRotation("not json", creatorPub, groupId, createdAt = 5000)

        assertEquals(RotationOutcome.REJECTED, outcome)
        coVerify(exactly = 0) { groupRepo.getById(any()) }
    }

    @Test
    fun `handleKeyRotation rejects a rotation for an unknown group`() = runBlocking {
        actAsPeer()
        coEvery { groupRepo.getById(groupId) } returns null

        val outcome = useCase.handleKeyRotation(rotationFor(epoch = 1), creatorPub, groupId, createdAt = 5000)

        assertEquals(RotationOutcome.REJECTED, outcome)
    }

    @Test
    fun `handleKeyRotation defers when the rotation names a member this device has not seen join`() = runBlocking {
        actAsPeer()
        val unseen = NostrEvent.pubkeyFromPrivkey(ByteArray(32).also { it[31] = 9 })

        val outcome = useCase.handleKeyRotation(
            rotationFor(epoch = 1, members = listOf(creatorPub, peerPub, unseen)),
            creatorPub,
            groupId,
            createdAt = 5000
        )

        assertEquals(RotationOutcome.DEFERRED_MEMBERSHIP, outcome)
        coVerify(exactly = 0) { groupRepo.saveGroupKeyForEpoch(any(), any(), any()) }
        coVerify(exactly = 0) { groupRepo.applyKeyRotation(any(), any(), any(), any(), any()) }
        assertNoLegacyTwoStepWrite()
    }

    @Test
    fun `handleKeyRotation applies the deferred rotation once the unseen member's join has landed`() = runBlocking {
        actAsPeer()
        val late = NostrEvent.pubkeyFromPrivkey(ByteArray(32).also { it[31] = 9 })
        val payload = rotationFor(epoch = 1, members = listOf(creatorPub, peerPub, late))

        val before = useCase.handleKeyRotation(payload, creatorPub, groupId, createdAt = 5000)
        coEvery { groupRepo.getById(groupId) } returns group.copy(members = group.members + late)
        val after = useCase.handleKeyRotation(payload, creatorPub, groupId, createdAt = 5000)

        assertEquals(listOf(RotationOutcome.DEFERRED_MEMBERSHIP, RotationOutcome.APPLIED), listOf(before, after))
        coVerify(exactly = 1) {
            groupRepo.applyKeyRotation(groupId, 1, listOf(creatorPub, peerPub, late), any(), any())
        }
    }

    @Test
    fun `handleKeyRotation rejects a rotation that carries no key for me`() = runBlocking {
        actAsPeer()

        val outcome = useCase.handleKeyRotation(
            rotationFor(epoch = 1, keyFor = listOf(creatorPub)),
            creatorPub,
            groupId,
            createdAt = 5000
        )

        assertEquals(RotationOutcome.REJECTED, outcome)
        coVerify(exactly = 0) { groupRepo.saveGroupKeyForEpoch(any(), any(), any()) }
        coVerify(exactly = 0) { groupRepo.applyKeyRotation(any(), any(), any(), any(), any()) }
    }

    @Test
    fun `handleKeyRotation rejects a key I cannot decrypt`() = runBlocking {
        actAsPeer()
        // The creator wrapped the peer's key with the wrong conversation key (here: for the removed member).
        val wrongConvKey = Nip44.getConversationKey(creatorPriv, removedPub.hexToBytes())
        val payload = json.encodeToString(
            KeyRotation.serializer(),
            KeyRotation(
                epoch = 1,
                encryptedKeys = mapOf(peerPub to Nip44.encrypt(newKey, wrongConvKey)),
                members = listOf(creatorPub, peerPub),
                removedMember = removedPub
            )
        )

        val outcome = useCase.handleKeyRotation(payload, creatorPub, groupId, createdAt = 5000)

        assertEquals(RotationOutcome.REJECTED, outcome)
        coVerify(exactly = 0) { groupRepo.saveGroupKeyForEpoch(any(), any(), any()) }
        coVerify(exactly = 0) { groupRepo.applyKeyRotation(any(), any(), any(), any(), any()) }
    }

    // --- key material consistency ---

    @Test
    fun `handleKeyRotation rejects a rotation whose key differs from the one already stored for that epoch`() =
        runBlocking {
            actAsPeer()
            coEvery { groupRepo.getGroupKeyForEpoch(groupId, 1) } returns "some-other-key"

            val outcome = useCase.handleKeyRotation(rotationFor(epoch = 1), creatorPub, groupId, createdAt = 5000)

            assertEquals(RotationOutcome.REJECTED, outcome)
            coVerify(exactly = 0) { groupRepo.saveGroupKeyForEpoch(any(), any(), any()) }
            coVerify(exactly = 0) { groupRepo.applyKeyRotation(any(), any(), any(), any(), any()) }
            assertNoLegacyTwoStepWrite()
        }

    @Test
    fun `handleKeyRotation resumes an interrupted rotation when the stored key is identical`() = runBlocking {
        actAsPeer()
        // Crash between saveGroupKeyForEpoch and applyKeyRotation: the key is on disk, the epoch is not.
        coEvery { groupRepo.getGroupKeyForEpoch(groupId, 1) } returns newKey

        val outcome = useCase.handleKeyRotation(rotationFor(epoch = 1), creatorPub, groupId, createdAt = 5000)

        assertEquals(RotationOutcome.APPLIED, outcome)
        coVerify(exactly = 0) { groupRepo.saveGroupKeyForEpoch(any(), any(), any()) }
        coVerify(exactly = 1) { groupRepo.applyKeyRotation(groupId, 1, listOf(creatorPub, peerPub), any(), any()) }
        assertNoLegacyTwoStepWrite()
    }

    @Test
    fun `receiver rejects when the atomic repository transition is refused`() = runBlocking {
        actAsPeer()
        coEvery { groupRepo.applyKeyRotation(any(), any(), any(), any(), any()) } returns false
        assertEquals(RotationOutcome.REJECTED, useCase.handleKeyRotation(rotationFor(1), creatorPub, groupId, 5000))
    }

    @Test
    fun `receiver own-removal path rejects a refused atomic transition`() = runBlocking {
        actAsPeer()
        coEvery { groupRepo.applyKeyRotation(any(), any(), any(), any(), any()) } returns false
        val payload = rotationFor(1, members = listOf(creatorPub, removedPub), removedMember = peerPub)
        assertEquals(RotationOutcome.REJECTED, useCase.handleKeyRotation(payload, creatorPub, groupId, 5000))
    }

    /**
     * The creator rotated before it saw `other` revoke to `peer`: its roster and envelopes still name
     * `other`. This device recorded that replacement, so the roster resolves to the live members and is
     * neither deferred (nobody unseen) nor rejected (nobody tombstoned); the epoch advances.
     */
    @Test
    fun `receiver resolves a tombstoned identity in the creator's roster through its recorded successor`() =
        runBlocking {
            actAsPeer()
            coEvery { groupRepo.getById(groupId) } returns group.copy(members = listOf(creatorPub, peerPub))
            coEvery { groupRepo.resolveRoster(groupId, any()) } answers {
                secondArg<List<String>>().map { if (it == otherPub) peerPub else it }.distinct()
            }
            // My own envelope is present under my current key: the rotation applies normally.
            val payload =
                rotationFor(1, members = listOf(creatorPub, otherPub, peerPub), keyFor = listOf(creatorPub, peerPub))

            assertEquals(RotationOutcome.APPLIED, useCase.handleKeyRotation(payload, creatorPub, groupId, 5000))

            assertEquals(newKey, keys[1])
            coVerify(exactly = 1) {
                groupRepo.applyKeyRotation(
                    groupId,
                    1,
                    listOf(creatorPub, peerPub),
                    mapOf(
                        creatorPub to "Alice",
                        peerPub to "Bob"
                    )
                )
            }
        }

    /**
     * I am `peer`, the successor of my revoked key `other`; the creator still addressed `other`, whose
     * private half is gone. I cannot read the new epoch, so I advance without key material (as if
     * removed) rather than reject and fall behind for good; the creator's next rotation re-admits me.
     */
    @Test
    fun `receiver addressed only through its revoked key advances the epoch without key material`() = runBlocking {
        actAsPeer()
        coEvery { groupRepo.getById(groupId) } returns group.copy(members = listOf(creatorPub, peerPub))
        coEvery { groupRepo.resolveRoster(groupId, any()) } answers {
            secondArg<List<String>>().map { if (it == otherPub) peerPub else it }.distinct()
        }
        val payload = rotationFor(1, members = listOf(creatorPub, otherPub), keyFor = listOf(creatorPub, otherPub))

        assertEquals(RotationOutcome.APPLIED, useCase.handleKeyRotation(payload, creatorPub, groupId, 5000))

        assertNull(keys[1])
        coVerify(exactly = 0) { groupRepo.saveGroupKeyForEpoch(any(), any(), any()) }
        coVerify(exactly = 1) {
            groupRepo.applyKeyRotation(
                groupId,
                1,
                listOf(creatorPub, peerPub),
                mapOf(
                    creatorPub to "Alice",
                    peerPub to "Bob"
                )
            )
        }
    }
}
