package com.splitfree.domain.usecase.group

import com.splitfree.domain.crypto.EventSigner
import com.splitfree.domain.crypto.GroupEncryption
import com.splitfree.domain.crypto.NostrEvent
import com.splitfree.domain.crypto.nip.Nip44
import com.splitfree.domain.model.group.Group
import com.splitfree.domain.model.group.GroupMeta
import com.splitfree.domain.model.group.KeyRotation
import com.splitfree.domain.repository.EventPublisherContract
import com.splitfree.domain.repository.GroupRepositoryContract
import com.splitfree.domain.repository.IdentityContract
import com.splitfree.domain.repository.SecureStorageException
import com.splitfree.domain.util.hexToBytes
import com.splitfree.sync.event.RotationOutcome
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
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class RotateGroupKeyUseCaseTest {
    private val groupRepo = mockk<GroupRepositoryContract>(relaxed = true)
    private val encryption = mockk<GroupEncryption>()
    private val identity = mockk<IdentityContract>()
    private val signer = mockk<EventSigner>()
    private val eventPublisher = mockk<EventPublisherContract>(relaxed = true)

    private lateinit var useCase: RotateGroupKeyUseCase

    // Real secp256k1 keys so NIP-44 conversation-key derivation works end to end.
    private val creatorPriv = ByteArray(32).also { it[31] = 1 }
    private val creatorPub = NostrEvent.pubkeyFromPrivkey(creatorPriv)
    private val peerPriv = ByteArray(32).also { it[31] = 2 }
    private val peerPub = NostrEvent.pubkeyFromPrivkey(peerPriv)
    private val removedPub = NostrEvent.pubkeyFromPrivkey(ByteArray(32).also { it[31] = 3 })

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

        every { identity.getPublicKeyHex() } returns creatorPub
        every { identity.getPrivateKeyBytes() } answers { creatorPriv.copyOf() }
        every { encryption.generateGroupKey() } returns newKey
        every { encryption.encrypt(any(), any()) } returns "meta-enc"
        every { signer.createSignedEvent(any(), any(), any(), any(), any()) } returns fakeEvent
        coEvery { groupRepo.getById(groupId) } returns group
        // A relaxed mock would answer "" here, which reads as conflicting key material.
        coEvery { groupRepo.getGroupKeyForEpoch(any(), any()) } returns null

        useCase = RotateGroupKeyUseCase(groupRepo, encryption, identity, signer, eventPublisher)
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
        keyFor: List<String> = members
    ): String {
        val encryptedKeys = keyFor.associateWith { member ->
            val convKey = Nip44.getConversationKey(creatorPriv, member.hexToBytes())
            Nip44.encrypt(newKey, convKey)
        }
        return json.encodeToString(
            KeyRotation.serializer(),
            KeyRotation(epoch = epoch, encryptedKeys = encryptedKeys, members = members, removedMember = removedMember)
        )
    }

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
            groupRepo.updateKeyEpoch(groupId, 1)
        }
        coVerify {
            groupRepo.updateFromMeta(
                groupId,
                "Trip",
                match { removedPub !in it && creatorPub in it && peerPub in it },
                listOf("wss://r"),
                any(),
                any(),
                match { removedPub !in it && it[creatorPub] == "Alice" && it[peerPub] == "Bob" }
            )
        }
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
            groupRepo.updateKeyEpoch(groupId, 1)
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
        coEvery { groupRepo.getById(groupId) } returnsMany listOf(group, group.copy(keyEpoch = 1))

        val failure = runCatching { useCase(groupId, removedPub) }.exceptionOrNull()

        assertTrue("expected IllegalStateException, got $failure", failure is IllegalStateException)
        assertEquals("Group changed during rotation", failure?.message)
        coVerify(exactly = 0) { groupRepo.updateKeyEpoch(any(), any()) }
        coVerify(exactly = 0) { groupRepo.updateFromMeta(any(), any(), any(), any(), any(), any(), any()) }
        coVerify(exactly = 0) { eventPublisher.publishDirect(any(), any(), any(), "group_meta", any()) }
    }

    @Test
    fun `failed key write aborts before anything is published or the epoch changes`() = runBlocking {
        coEvery { groupRepo.saveGroupKeyForEpoch(groupId, 1, newKey) } throws
            SecureStorageException("keystore unavailable")

        val failure = runCatching { useCase(groupId, removedPub) }.exceptionOrNull()

        assertTrue("expected SecureStorageException, got $failure", failure is SecureStorageException)
        coVerify(exactly = 0) { eventPublisher.publishDirect(any(), any(), any(), any(), any()) }
        coVerify(exactly = 0) { groupRepo.updateKeyEpoch(any(), any()) }
        coVerify(exactly = 0) { groupRepo.updateFromMeta(any(), any(), any(), any(), any(), any(), any()) }
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

    // --- receiver path ---

    @Test
    fun `handleKeyRotation applies the next epoch and stamps the member update with the event time`() = runBlocking {
        actAsPeer()

        val outcome = useCase.handleKeyRotation(rotationFor(epoch = 1), creatorPub, groupId, createdAt = 5000)

        assertEquals(RotationOutcome.APPLIED, outcome)
        coVerify { groupRepo.saveGroupKeyForEpoch(groupId, 1, newKey) }
        coVerify { groupRepo.updateKeyEpoch(groupId, 1) }
        coVerify {
            // Unconditional path (eventTimestamp 0): epoch order is enforced by the rotation itself,
            // so a same-second group_meta cannot keep the removed member in the roster.
            groupRepo.updateFromMeta(
                groupId,
                "Trip",
                listOf(creatorPub, peerPub),
                listOf("wss://r"),
                0,
                "",
                match { removedPub !in it && it[peerPub] == "Bob" }
            )
        }
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
        coVerify(exactly = 0) { groupRepo.updateFromMeta(any(), any(), any(), any(), any(), any(), any()) }
        coVerify(exactly = 0) { groupRepo.updateKeyEpoch(any(), any()) }
        coVerify(exactly = 0) { groupRepo.saveGroupKeyForEpoch(any(), any(), any()) }
    }

    @Test
    fun `handleKeyRotation ignores a rotation for an older epoch`() = runBlocking {
        actAsPeer()
        coEvery { groupRepo.getById(groupId) } returns group.copy(keyEpoch = 3)

        val outcome = useCase.handleKeyRotation(rotationFor(epoch = 2), creatorPub, groupId, createdAt = 4000)

        assertEquals(RotationOutcome.IGNORED, outcome)
        coVerify(exactly = 0) { groupRepo.saveGroupKeyForEpoch(any(), any(), any()) }
    }

    @Test
    fun `handleKeyRotation defers a rotation that skips an epoch`() = runBlocking {
        actAsPeer()

        val outcome = useCase.handleKeyRotation(rotationFor(epoch = 2), creatorPub, groupId, createdAt = 5000)

        assertEquals(RotationOutcome.DEFERRED_EPOCH_GAP, outcome)
        coVerify(exactly = 0) { groupRepo.saveGroupKeyForEpoch(any(), any(), any()) }
        coVerify(exactly = 0) { groupRepo.updateKeyEpoch(any(), any()) }
        coVerify(exactly = 0) { groupRepo.updateFromMeta(any(), any(), any(), any(), any(), any(), any()) }
    }

    @Test
    fun `handleKeyRotation applies epoch 2 once epoch 1 has landed`() = runBlocking {
        actAsPeer()
        // The repository behaves like Room: updateKeyEpoch is reflected by the next getById.
        var epoch = 0
        coEvery { groupRepo.getById(groupId) } answers { group.copy(keyEpoch = epoch) }
        coEvery { groupRepo.updateKeyEpoch(groupId, any()) } answers { epoch = secondArg() }

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
    fun `handleKeyRotation rejects a rotation removing someone who is not a member`() = runBlocking {
        actAsPeer()
        val outsider = NostrEvent.pubkeyFromPrivkey(ByteArray(32).also { it[31] = 9 })

        val outcome = useCase.handleKeyRotation(
            rotationFor(epoch = 1, members = listOf(creatorPub, peerPub, removedPub), removedMember = outsider),
            creatorPub,
            groupId,
            createdAt = 5000
        )

        assertEquals(RotationOutcome.REJECTED, outcome)
        coVerify(exactly = 0) { groupRepo.saveGroupKeyForEpoch(any(), any(), any()) }
        coVerify(exactly = 0) { groupRepo.updateFromMeta(any(), any(), any(), any(), any(), any(), any()) }
    }

    @Test
    fun `handleKeyRotation records my own removal unconditionally`() = runBlocking {
        actAsPeer()

        val outcome = useCase.handleKeyRotation(
            rotationFor(epoch = 1, members = listOf(creatorPub, removedPub), removedMember = peerPub),
            creatorPub,
            groupId,
            createdAt = 6000
        )

        assertEquals(RotationOutcome.APPLIED, outcome)
        coVerify {
            groupRepo.updateFromMeta(
                groupId,
                "Trip",
                listOf(creatorPub, removedPub),
                listOf("wss://r"),
                0,
                "",
                match { peerPub !in it }
            )
        }
        coVerify(exactly = 0) { groupRepo.saveGroupKeyForEpoch(any(), any(), any()) }
        coVerify(exactly = 0) { groupRepo.updateKeyEpoch(any(), any()) }
    }

    @Test
    fun `handleKeyRotation rejects rotations not signed by the creator`() = runBlocking {
        actAsPeer()

        val outcome = useCase.handleKeyRotation(rotationFor(epoch = 1), peerPub, groupId, createdAt = 5000)

        assertEquals(RotationOutcome.REJECTED, outcome)
        coVerify(exactly = 0) { groupRepo.saveGroupKeyForEpoch(any(), any(), any()) }
        coVerify(exactly = 0) { groupRepo.updateFromMeta(any(), any(), any(), any(), any(), any(), any()) }
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
    fun `handleKeyRotation rejects a member list that smuggles in an outsider`() = runBlocking {
        actAsPeer()
        val outsider = NostrEvent.pubkeyFromPrivkey(ByteArray(32).also { it[31] = 9 })

        val outcome = useCase.handleKeyRotation(
            rotationFor(epoch = 1, members = listOf(creatorPub, peerPub, outsider)),
            creatorPub,
            groupId,
            createdAt = 5000
        )

        assertEquals(RotationOutcome.REJECTED, outcome)
        coVerify(exactly = 0) { groupRepo.saveGroupKeyForEpoch(any(), any(), any()) }
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
        coVerify(exactly = 0) { groupRepo.updateKeyEpoch(any(), any()) }
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
            coVerify(exactly = 0) { groupRepo.updateKeyEpoch(any(), any()) }
            coVerify(exactly = 0) { groupRepo.updateFromMeta(any(), any(), any(), any(), any(), any(), any()) }
        }

    @Test
    fun `handleKeyRotation resumes an interrupted rotation when the stored key is identical`() = runBlocking {
        actAsPeer()
        // Crash between saveGroupKeyForEpoch and updateKeyEpoch: the key is on disk, the epoch is not.
        coEvery { groupRepo.getGroupKeyForEpoch(groupId, 1) } returns newKey

        val outcome = useCase.handleKeyRotation(rotationFor(epoch = 1), creatorPub, groupId, createdAt = 5000)

        assertEquals(RotationOutcome.APPLIED, outcome)
        coVerify(exactly = 0) { groupRepo.saveGroupKeyForEpoch(any(), any(), any()) }
        coVerify(exactly = 1) { groupRepo.updateKeyEpoch(groupId, 1) }
        coVerify(exactly = 1) {
            groupRepo.updateFromMeta(groupId, "Trip", listOf(creatorPub, peerPub), listOf("wss://r"), 0, "", any())
        }
    }
}
