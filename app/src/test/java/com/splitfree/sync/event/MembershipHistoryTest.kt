package com.splitfree.sync.event

import com.splitfree.data.local.dao.EventDao
import com.splitfree.data.local.entities.EventEntity
import com.splitfree.domain.crypto.NostrEvent
import com.splitfree.domain.crypto.nip.Nip44
import com.splitfree.domain.model.group.Group
import com.splitfree.domain.model.group.KeyRotation
import com.splitfree.domain.model.group.RetiredIdentities
import com.splitfree.domain.repository.GroupRepositoryContract
import com.splitfree.domain.repository.IdentityContract
import com.splitfree.domain.util.hexToBytes
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.unmockkAll
import io.mockk.verify
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class MembershipHistoryTest {
    private val eventDao = mockk<EventDao>()
    private val groupRepo = mockk<GroupRepositoryContract>()
    private val identity = mockk<IdentityContract>()
    private lateinit var history: MembershipHistory

    // Real secp256k1 keys so NIP-44 conversation-key derivation works end to end.
    private val creatorPriv = ByteArray(32).also { it[31] = 1 }
    private val creatorPub = NostrEvent.pubkeyFromPrivkey(creatorPriv)
    private val myPriv = ByteArray(32).also { it[31] = 2 }
    private val myPub = NostrEvent.pubkeyFromPrivkey(myPriv)
    private val removedPub = NostrEvent.pubkeyFromPrivkey(ByteArray(32).also { it[31] = 3 })
    private val otherPriv = ByteArray(32).also { it[31] = 4 }
    private val otherPub = NostrEvent.pubkeyFromPrivkey(otherPriv)

    private val groupId = "group-1"
    private val json = Json { ignoreUnknownKeys = true }
    private val group =
        Group(groupId, "Trip", "", creatorPub, 1000, listOf(creatorPub, myPub), listOf("wss://r"), keyEpoch = 1)

    @Before
    fun setup() {
        every { identity.getPublicKeyHex() } returns myPub
        every { identity.getPrivateKeyBytes() } answers { myPriv.copyOf() }
        coEvery { groupRepo.getById(groupId) } returns group
        coEvery { groupRepo.authenticatedRotations(groupId) } returns emptyMap()
        coEvery { groupRepo.retiredIdentities(groupId) } returns RetiredIdentities.NONE
        coEvery { eventDao.getEventsByType(groupId, "key_rotation") } returns emptyList()
        history = MembershipHistory(eventDao, groupRepo, identity)
    }

    @After
    fun teardown() = unmockkAll()

    /** A stored `key_rotation` row: the creator's envelope to [recipientPriv]'s owner, NIP-44 encrypted. */
    private fun rotationRow(
        epoch: Int,
        members: List<String>,
        removedMember: String,
        recipientPriv: ByteArray = myPriv,
        applyState: Int = EventEntity.APPLY_STATE_APPLIED,
        id: String = "rot-$epoch-${System.nanoTime()}"
    ): EventEntity {
        val recipientPub = NostrEvent.pubkeyFromPrivkey(recipientPriv)
        val convKey = Nip44.getConversationKey(creatorPriv, recipientPub.hexToBytes())
        val payload = json.encodeToString(
            KeyRotation.serializer(),
            KeyRotation(epoch = epoch, encryptedKeys = emptyMap(), members = members, removedMember = removedMember)
        )
        return EventEntity(
            eventId = id,
            groupId = groupId,
            pubkey = creatorPub,
            createdAt = 1000L + epoch,
            kind = 30078,
            contentEncrypted = Nip44.encrypt(payload, convKey),
            eventType = "key_rotation",
            sig = "sig",
            receivedAt = 2000,
            keyEpoch = epoch - 1,
            applyState = applyState
        )
    }

    @Test
    fun `historicalAuthors is the current members when no rotation is stored`() = runBlocking {
        assertEquals(setOf(creatorPub, myPub), history.historicalAuthors(groupId))
    }

    @Test
    fun `historicalAuthors includes a member removed by a rotation I can decrypt`() = runBlocking {
        coEvery { eventDao.getEventsByType(groupId, "key_rotation") } returns
            listOf(rotationRow(epoch = 1, members = listOf(creatorPub, myPub), removedMember = removedPub))

        val authors = history.historicalAuthors(groupId)

        assertTrue(removedPub in authors)
        assertTrue(creatorPub in authors)
        assertTrue(myPub in authors)
        assertEquals(3, authors.size)
    }

    @Test
    fun `historicalAuthors includes members listed by an old rotation who were removed later by one I missed`() =
        runBlocking {
            // otherPub was still a member at epoch 1 and vanished from the current list without a rotation
            // row on this device: the epoch-1 member list is still evidence they were once a member.
            coEvery { eventDao.getEventsByType(groupId, "key_rotation") } returns
                listOf(
                    rotationRow(epoch = 1, members = listOf(creatorPub, myPub, otherPub), removedMember = removedPub)
                )

            val authors = history.historicalAuthors(groupId)

            assertTrue(otherPub in authors)
            assertTrue(removedPub in authors)
        }

    @Test
    fun `historicalAuthors ignores rotation rows this device cannot decrypt`() = runBlocking {
        // An envelope addressed to otherPub: my conversation key with the creator does not open it.
        coEvery { eventDao.getEventsByType(groupId, "key_rotation") } returns
            listOf(
                rotationRow(
                    epoch = 1,
                    members = listOf(creatorPub, otherPub),
                    removedMember = removedPub,
                    recipientPriv = otherPriv
                )
            )

        val authors = history.historicalAuthors(groupId)

        assertEquals(setOf(creatorPub, myPub), authors)
        assertFalse(removedPub in authors)
    }

    @Test
    fun `historicalAuthors ignores pending rotation rows`() = runBlocking {
        coEvery { eventDao.getEventsByType(groupId, "key_rotation") } returns
            listOf(
                rotationRow(
                    epoch = 2,
                    members = listOf(creatorPub, myPub),
                    removedMember = removedPub,
                    applyState = EventEntity.APPLY_STATE_PENDING
                )
            )

        assertEquals(setOf(creatorPub, myPub), history.historicalAuthors(groupId))
    }

    @Test
    fun `historicalAuthors ignores rows with garbage content`() = runBlocking {
        val garbage = rotationRow(epoch = 1, members = listOf(creatorPub, myPub), removedMember = removedPub)
            .copy(contentEncrypted = "not-nip44")
        coEvery { eventDao.getEventsByType(groupId, "key_rotation") } returns listOf(garbage)

        assertEquals(setOf(creatorPub, myPub), history.historicalAuthors(groupId))
    }

    @Test
    fun `historicalAuthors works on the creator's own device via its self-addressed envelope`() = runBlocking {
        every { identity.getPublicKeyHex() } returns creatorPub
        every { identity.getPrivateKeyBytes() } answers { creatorPriv.copyOf() }
        coEvery { eventDao.getEventsByType(groupId, "key_rotation") } returns
            listOf(
                rotationRow(
                    epoch = 1,
                    members = listOf(creatorPub, myPub),
                    removedMember = removedPub,
                    recipientPriv = creatorPriv
                ),
                // The envelope for the peer is not readable by the creator via conv(creator, creator); skipped.
                rotationRow(epoch = 1, members = listOf(creatorPub, myPub), removedMember = removedPub)
            )

        assertTrue(removedPub in history.historicalAuthors(groupId))
    }

    @Test
    fun `removalEpochOf returns the epoch of the rotation that removed the pubkey`() = runBlocking {
        coEvery { eventDao.getEventsByType(groupId, "key_rotation") } returns
            listOf(rotationRow(epoch = 1, members = listOf(creatorPub, myPub), removedMember = removedPub))

        assertEquals(1, history.removalEpochOf(groupId, removedPub))
    }

    @Test
    fun `removalEpochOf is null for a pubkey that was never removed`() = runBlocking {
        coEvery { eventDao.getEventsByType(groupId, "key_rotation") } returns
            listOf(rotationRow(epoch = 1, members = listOf(creatorPub, myPub), removedMember = removedPub))

        assertNull(history.removalEpochOf(groupId, myPub))
        assertNull(history.removalEpochOf(groupId, creatorPub))
    }

    @Test
    fun `removalEpochOf is null when the only removing rotation is undecryptable`() = runBlocking {
        coEvery { eventDao.getEventsByType(groupId, "key_rotation") } returns
            listOf(
                rotationRow(
                    epoch = 1,
                    members = listOf(creatorPub, otherPub),
                    removedMember = removedPub,
                    recipientPriv = otherPriv
                )
            )

        assertNull(history.removalEpochOf(groupId, removedPub))
    }

    @Test
    fun `removalEpochOf uses the latest removal when a member was removed twice`() = runBlocking {
        coEvery { eventDao.getEventsByType(groupId, "key_rotation") } returns
            listOf(
                rotationRow(epoch = 1, members = listOf(creatorPub, myPub), removedMember = removedPub),
                // re-invited in between, then removed again
                rotationRow(epoch = 3, members = listOf(creatorPub, myPub), removedMember = removedPub)
            )

        assertEquals(3, history.removalEpochOf(groupId, removedPub))
    }

    @Test
    fun `private key is zeroed after use`() = runBlocking {
        val handedOut = mutableListOf<ByteArray>()
        every { identity.getPrivateKeyBytes() } answers { myPriv.copyOf().also { handedOut += it } }
        coEvery { eventDao.getEventsByType(groupId, "key_rotation") } returns
            listOf(rotationRow(epoch = 1, members = listOf(creatorPub, myPub), removedMember = removedPub))

        history.historicalAuthors(groupId)

        assertEquals(1, handedOut.size)
        assertTrue(handedOut.single().all { it == 0.toByte() })
    }

    @Test
    fun `admitted rotations remain usable without reading a replaced personal key`() = runBlocking {
        val rotation = KeyRotation(1, emptyMap(), listOf(creatorPub, myPub), removedPub)
        coEvery { groupRepo.authenticatedRotations(groupId) } returns mapOf("retained" to rotation)
        coEvery { eventDao.getEventsByType(groupId, "key_rotation") } returns listOf(
            rotationRow(1, rotation.members, removedPub, id = "retained")
        )
        every { identity.getPrivateKeyBytes() } throws IllegalStateException("Old personal key unavailable")

        assertEquals(setOf(creatorPub, myPub, removedPub), history.historicalAuthors(groupId))
        assertEquals(setOf(removedPub), history.formerMembers(groupId))
        assertEquals(1, history.removalEpochOf(groupId, removedPub))
        verify(exactly = 0) { identity.getPrivateKeyBytes() }
    }

    @Test
    fun `retained and legacy removals are unioned without granting revoked keys author eligibility`() = runBlocking {
        val revoked = NostrEvent.pubkeyFromPrivkey(ByteArray(32).also { it[31] = 5 })
        coEvery { groupRepo.authenticatedRotations(groupId) } returns mapOf(
            "first" to KeyRotation(1, emptyMap(), listOf(creatorPub, myPub), removedPub)
        )
        coEvery { eventDao.getEventsByType(groupId, "key_rotation") } returns listOf(
            rotationRow(2, listOf(creatorPub, myPub), otherPub),
            rotationRow(3, listOf(creatorPub, myPub), removedPub)
        )
        coEvery { groupRepo.retiredIdentities(groupId) } returns RetiredIdentities(setOf(revoked))

        assertEquals(setOf(removedPub, otherPub, revoked), history.formerMembers(groupId))
        assertFalse(revoked in history.historicalAuthors(groupId))
        assertEquals(3, history.removalEpochOf(groupId, removedPub))
        assertNull(history.removalEpochOf(groupId, revoked))
    }

    @Test
    fun `retention stores readable applied history and never pending or invalid signed envelopes`() = runBlocking {
        val applied = rotationRow(1, listOf(creatorPub, myPub), removedPub, id = "applied")
        val pending = rotationRow(
            1,
            listOf(creatorPub, myPub),
            otherPub,
            applyState = EventEntity.APPLY_STATE_PENDING,
            id = "pending"
        )
        val invalid = applied.copy(eventId = "invalid", originalEventJson = "{}")
        coEvery { eventDao.getEventsByType(groupId, "key_rotation") } returns listOf(applied, pending, invalid)
        coEvery { groupRepo.retainAuthenticatedRotationHistory(any(), any(), any(), any(), any()) } returns true

        history.retainRotationHistory(groupId)

        coVerify(exactly = 1) {
            groupRepo.retainAuthenticatedRotationHistory(
                groupId,
                KeyRotation(1, emptyMap(), listOf(creatorPub, myPub), removedPub),
                creatorPub,
                1001L,
                "applied"
            )
        }
        coVerify(exactly = 1) { groupRepo.retainAuthenticatedRotationHistory(any(), any(), any(), any(), any()) }
    }

    @Test
    fun `retention rejects signed envelopes that do not match their applied row or routing`() = runBlocking {
        val row = rotationRow(1, listOf(creatorPub, myPub), removedPub)
        val signed = NostrEvent(
            pubkey = creatorPub,
            createdAt = row.createdAt,
            kind = 30078,
            tags = listOf(listOf("g", groupId), listOf("t", "key_rotation")),
            content = row.contentEncrypted
        ).sign(creatorPriv)
        val valid = row.copy(eventId = signed.id, sig = signed.sig, originalEventJson = signed.toJson())
        fun mismatched(event: NostrEvent) = valid.copy(originalEventJson = event.toJson())
        fun rebound(event: NostrEvent) =
            valid.copy(eventId = event.id, sig = event.sig, originalEventJson = event.toJson())
        val invalid = listOf(
            valid.copy(eventId = "00".repeat(32)),
            valid.copy(createdAt = row.createdAt + 1),
            valid.copy(pubkey = otherPub),
            mismatched(signed.copy(sig = "00".repeat(64))),
            mismatched(signed.copy(content = signed.content + "changed").sign(creatorPriv)),
            rebound(signed.copy(tags = listOf(listOf("g", "other"), listOf("t", "key_rotation"))).sign(creatorPriv)),
            rebound(signed.copy(tags = signed.tags + listOf(listOf("g", groupId))).sign(creatorPriv)),
            rebound(signed.copy(tags = listOf(listOf("g", groupId), listOf("t", "group_meta"))).sign(creatorPriv))
        )
        coEvery { eventDao.getEventsByType(groupId, "key_rotation") } returns invalid + valid
        coEvery { groupRepo.retainAuthenticatedRotationHistory(any(), any(), any(), any(), any()) } returns true

        history.retainRotationHistory(groupId)

        coVerify(exactly = 1) {
            groupRepo.retainAuthenticatedRotationHistory(
                groupId,
                KeyRotation(1, emptyMap(), listOf(creatorPub, myPub), removedPub),
                creatorPub,
                row.createdAt,
                signed.id
            )
        }
        coVerify(exactly = 1) { groupRepo.retainAuthenticatedRotationHistory(any(), any(), any(), any(), any()) }
    }

    @Test
    fun `failed retention is not silently accepted before identity retirement`() = runBlocking {
        coEvery { eventDao.getEventsByType(groupId, "key_rotation") } returns listOf(
            rotationRow(1, listOf(creatorPub, myPub), removedPub)
        )
        coEvery { groupRepo.retainAuthenticatedRotationHistory(any(), any(), any(), any(), any()) } returns false
        val failure = runCatching { history.retainRotationHistory(groupId) }.exceptionOrNull()
        assertTrue(failure is IllegalStateException)
    }
}
