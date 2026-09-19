package com.splitfree.domain.usecase.group

import com.splitfree.domain.crypto.EventSigner
import com.splitfree.domain.crypto.GroupEncryption
import com.splitfree.domain.crypto.NostrEvent
import com.splitfree.domain.model.group.Group
import com.splitfree.domain.model.group.GroupMeta
import com.splitfree.domain.model.group.KeyRevocation
import com.splitfree.domain.repository.ControlOperation
import com.splitfree.domain.repository.EventPublisherContract
import com.splitfree.domain.repository.GroupRepositoryContract
import com.splitfree.domain.repository.IdentityContract
import com.splitfree.test.FakeControlOperationJournal
import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.coVerifyOrder
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import io.mockk.verify
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class RevokeKeyUseCaseTest {
    private val identity = mockk<IdentityContract>()
    private val groupRepo = mockk<GroupRepositoryContract>(relaxed = true)
    private val encryption = mockk<GroupEncryption>()
    private val signer = mockk<EventSigner>()
    private val publisher = mockk<EventPublisherContract>(relaxed = true)
    private val journal = FakeControlOperationJournal()
    private lateinit var useCase: RevokeKeyUseCase
    private val oldPrivateKey = ByteArray(32) { 1 }
    private val oldPubkey = NostrEvent.pubkeyFromPrivkey(oldPrivateKey)
    private val signedEvents = mutableMapOf<String, NostrEvent>()
    private val newPrivateKey = ByteArray(32) { 2 }
    private val newPubkey = NostrEvent.pubkeyFromPrivkey(newPrivateKey)
    private val peer = "cc".repeat(32)
    private var pending: String? = null
    private val group = Group(
        "g",
        "Trip",
        createdBy = oldPubkey,
        createdAt = 1000,
        members = listOf(oldPubkey, peer),
        relays = emptyList(),
        memberNames = mapOf(oldPubkey to "Alice", peer to "Bob")
    )
    private val json = Json { ignoreUnknownKeys = true }

    @Before
    fun setup() {
        mockkStatic(android.util.Log::class)
        every { android.util.Log.w(any<String>(), any<String>()) } returns 0
        every { identity.getPublicKeyHex() } returns oldPubkey
        every { identity.stagedIdentitySwitch() } returns null
        every { identity.hasPendingKeyPair() } answers { pending != null }
        every { identity.getPendingPublicKeyHex() } answers { pending }
        every { identity.generatePendingKeyPair() } answers {
            pending = newPubkey
            newPubkey
        }
        every { identity.getPendingPrivateKeyBytes() } answers { pending?.let { newPrivateKey.copyOf() } }
        every { identity.getPrivateKeyBytes() } throws IllegalStateException("active key must not sign the proof")
        every { identity.markRevocationStarted() } just Runs
        every { identity.setRevocationEventIds(any()) } just Runs
        every { identity.finishPendingKeyPair(any()) } just Runs
        every { identity.discardPendingKeyPair() } just Runs
        every { encryption.encrypt(any(), any()) } answers { firstArg() }
        every { encryption.decrypt(any(), any()) } answers { firstArg() }
        every { signer.createSignedEvent(any(), any(), any(), any(), any()) } answers {
            signedEvent(firstArg(), secondArg(), thirdArg()).also { signedEvents[secondArg()] = it }
        }
        coEvery { groupRepo.getAll() } returns listOf(group)
        coEvery { groupRepo.getById("g") } returns group
        coEvery { groupRepo.getGroupKeyForEpoch(any(), any()) } returns "group-key"
        coEvery { groupRepo.applyIdentityRevocation(any(), any(), any(), any(), any(), any(), any()) } returns true
        coEvery { groupRepo.applyAuthenticatedRevocation(any(), any(), any(), any(), any(), any(), any()) } coAnswers {
            groupRepo.applyIdentityRevocation(firstArg(), secondArg(), thirdArg(), arg(3), arg(4), true, arg(6))
        }
        useCase = service()
    }

    @After
    fun teardown() = unmockkStatic(android.util.Log::class)

    private fun service() = RevokeKeyUseCase(
        identity, groupRepo, encryption, signer, publisher, journal, ControlOperationLock(),
        mockk(relaxed = true), mockk(relaxed = true)
    )

    @Test
    fun `invoke publishes then projects atomic revocation and promotes the journaled replacement`() = runBlocking {
        assertEquals(newPubkey, useCase())
        coVerifyOrder {
            identity.setRevocationEventIds(
                listOf(signedEvents.getValue("key_revocation").id, signedEvents.getValue("group_meta").id)
            )
            publisher.publishDirect(any(), "g", any(), "key_revocation", any())
            publisher.publishDirect(any(), "g", any(), "group_meta", any())
            groupRepo.applyIdentityRevocation(
                "g",
                oldPubkey,
                newPubkey,
                1000,
                signedEvents.getValue("key_revocation").id,
                allowAbsent = true,
                successorProven = true
            )
            identity.finishPendingKeyPair(newPubkey)
        }
        assertTrue(journal.getAll("revocation").isEmpty())
        verify(exactly = 0) { identity.discardPendingKeyPair() }
    }

    @Test
    fun `every pre-signed event is journaled before any publish`() = runBlocking {
        coEvery { publisher.publishDirect(any(), any(), any(), any(), any()) } coAnswers {
            val prepared = json.decodeFromString<PreparedRevocation>(
                journal.getAll("revocation").single().preparedJson!!
            )
            assertEquals(listOf("key_revocation", "group_meta"), prepared.events.map { it.eventType })
            assertEquals(newPubkey, prepared.newPubkey)
        }
        useCase()
        Unit
    }

    @Test
    fun `preparation failure keeps recoverable intent and replacement and never publishes`() = runBlocking {
        every { encryption.encrypt(any(), any()) } throws IllegalStateException("crypto unavailable")
        assertTrue(runCatching { useCase() }.isFailure)
        assertNotNull(journal.getAll("revocation").single())
        assertEquals(newPubkey, pending)
        coVerify(exactly = 0) { publisher.publishDirect(any(), any(), any(), any(), any()) }
        verify(exactly = 0) { identity.discardPendingKeyPair() }
    }

    @Test
    fun `second signer failure cannot publish an incomplete event plan`() = runBlocking {
        every { signer.createSignedEvent(any(), "group_meta", any(), any(), any()) } throws
            IllegalStateException("signer")
        assertTrue(runCatching { useCase() }.isFailure)
        coVerify(exactly = 0) { publisher.publishDirect(any(), any(), any(), any(), any()) }
        verify(exactly = 0) { identity.finishPendingKeyPair(any()) }
    }

    @Test
    fun `publish failure retains replacement and retry cannot generate a second identity`() = runBlocking {
        coEvery { publisher.publishDirect(any(), any(), any(), any(), any()) } throws IllegalStateException("publish")
        assertTrue(runCatching { useCase() }.isFailure)
        val prepared = journal.getAll("revocation").single().preparedJson
        coEvery { publisher.publishDirect(any(), any(), any(), any(), any()) } just Runs
        assertEquals(newPubkey, service()())
        assertNotNull(prepared)
        verify(exactly = 1) { identity.generatePendingKeyPair() }
        verify(exactly = 2) { signer.createSignedEvent(any(), any(), any(), any(), any()) }
        verify(exactly = 0) { identity.discardPendingKeyPair() }
    }

    @Test
    fun `projection failure must not promote or discard pending key`() = runBlocking {
        coEvery { groupRepo.applyIdentityRevocation(any(), any(), any(), any(), any(), any(), any()) } returns false
        assertTrue(runCatching { useCase() }.isFailure)
        verify(exactly = 0) { identity.finishPendingKeyPair(any()) }
        verify(exactly = 0) { identity.discardPendingKeyPair() }
        assertNotNull(journal.getAll("revocation").single().preparedJson)
    }

    @Test
    fun `missing epoch key fails closed before publication to every group`() = runBlocking {
        coEvery { groupRepo.getAll() } returns listOf(group, group.copy(id = "missing"))
        coEvery { groupRepo.getGroupKeyForEpoch("missing", any()) } returns null
        assertTrue(runCatching { useCase() }.isFailure)
        coVerify(exactly = 0) { publisher.publishDirect(any(), any(), any(), any(), any()) }
        verify(exactly = 0) { identity.finishPendingKeyPair(any()) }
        // Refusal leaves neither a journal entry nor a pending replacement identity.
        assertTrue(journal.getAll("revocation").isEmpty())
        verify(exactly = 0) { identity.generatePendingKeyPair() }
    }

    @Test
    fun `projection of a group deleted locally after publication is tolerated`() = runBlocking {
        coEvery { groupRepo.applyIdentityRevocation(any(), any(), any(), any(), any(), any(), any()) } returns false
        coEvery { groupRepo.getById("g") } returns null
        assertEquals(newPubkey, useCase())
        verify { identity.finishPendingKeyPair(newPubkey) }
        assertTrue(journal.getAll("revocation").isEmpty())
    }

    @Test
    fun `own projection passes the revocation event's clock and tolerates an absent identity`() = runBlocking {
        useCase()
        coVerify(exactly = 1) {
            groupRepo.applyIdentityRevocation(
                "g",
                oldPubkey,
                newPubkey,
                1000,
                signedEvents.getValue("key_revocation").id,
                allowAbsent = true,
                successorProven = true
            )
        }
        coVerify(exactly = 0) {
            groupRepo.applyIdentityRevocation(any(), any(), any(), any(), "group_meta", any(), any())
        }
    }

    @Test
    fun `creator replacement already in roster is unique and retains its announced name in prepared metadata`() =
        runBlocking {
            coEvery { groupRepo.getAll() } returns listOf(
                group.copy(
                    members = group.members + newPubkey,
                    memberNames = group.memberNames + (newPubkey to "Alicia")
                )
            )
            val metas = mutableListOf<GroupMeta>()
            every { encryption.encrypt(any(), any()) } answers {
                val text = firstArg<String>()
                if ("created_by" in text) metas += json.decodeFromString<GroupMeta>(text)
                text
            }
            useCase()
            assertEquals(newPubkey, metas.single().createdBy)
            assertEquals(listOf(newPubkey, peer), metas.single().members)
            assertEquals("Alicia", metas.single().memberNames[newPubkey])
            assertFalse(oldPubkey in metas.single().memberNames)
        }

    @Test
    fun `no-group revocation still promotes after durable empty prepared plan`() = runBlocking {
        coEvery { groupRepo.getAll() } returns emptyList()
        assertEquals(newPubkey, useCase())
        verify { identity.finishPendingKeyPair(newPubkey) }
        coVerify(exactly = 0) { publisher.publishDirect(any(), any(), any(), any(), any()) }
    }

    @Test
    fun `unjournaled pending identity cannot be discarded because IDs are empty or untracked`() = runBlocking {
        pending = newPubkey
        useCase.resumeIfNeeded()
        assertTrue(runCatching { useCase() }.isFailure)
        verify(exactly = 0) { identity.discardPendingKeyPair() }
        verify(exactly = 0) { identity.finishPendingKeyPair(any()) }
        verify(exactly = 0) { identity.generatePendingKeyPair() }
    }

    @Test
    fun `incoming validated revocation delegates live roster and creator handover atomically`() = runBlocking {
        assertTrue(useCase.handleRevocation(payload(newPubkey), oldPubkey, "g", 2000, "rev"))
        coVerify(exactly = 1) {
            groupRepo.applyIdentityRevocation(
                "g",
                oldPubkey,
                newPubkey,
                2000,
                "rev",
                allowAbsent = false,
                successorProven = false
            )
        }
        coVerify(exactly = 0) { groupRepo.getById(any()) }
    }

    @Test
    fun `incoming no-replacement revocation delegates empty replacement`() = runBlocking {
        assertTrue(useCase.handleRevocation(payload(""), oldPubkey, "g", 2000, "rev"))
        coVerify {
            groupRepo.applyIdentityRevocation(
                "g",
                oldPubkey,
                "",
                2000,
                "rev",
                allowAbsent = false,
                successorProven = false
            )
        }
    }

    @Test
    fun `incoming unknown or absent-old revocation propagates repository rejection`() = runBlocking {
        coEvery { groupRepo.applyIdentityRevocation(any(), any(), any(), any(), any(), any(), any()) } returns false
        assertFalse(useCase.handleRevocation(payload(newPubkey), oldPubkey, "g", 2000, "rev"))
    }

    @Test
    fun `incoming malformed payload author and replacement are rejected without projection`() = runBlocking {
        assertFalse(useCase.handleRevocation("bad json", oldPubkey, "g", 1, "rev"))
        assertFalse(useCase.handleRevocation(payload(newPubkey), peer, "g", 1, "rev"))
        for (replacement in listOf("not-a-key", "BB".repeat(32), oldPubkey)) {
            assertFalse(useCase.handleRevocation(payload(replacement), oldPubkey, "g", 1, "rev"))
        }
        coVerify(exactly = 0) { groupRepo.applyIdentityRevocation(any(), any(), any(), any(), any(), any(), any()) }
    }

    @Test
    fun `published revocation proves possession of the replacement key to every group`() = runBlocking {
        val revocations = mutableListOf<KeyRevocation>()
        every { encryption.encrypt(any(), any()) } answers {
            val text = firstArg<String>()
            if ("oldPubkey" in json.parseToJsonElement(text).jsonObject) {
                revocations += json.decodeFromString<KeyRevocation>(text)
            }
            text
        }
        useCase()
        val revocation = revocations.single()
        val published = signedEvents.getValue("key_revocation")
        assertEquals(revocation, json.decodeFromString<KeyRevocation>(published.content))
        coVerify(exactly = 1) {
            publisher.publishDirect(published, "g", published.content, "key_revocation", any())
        }
        assertEquals(oldPubkey, revocation.oldPubkey)
        assertEquals(newPubkey, revocation.newPubkey)
        assertTrue(revocation.provesSuccessor("g"))
        assertFalse(revocation.provesSuccessor("other-group"))
    }

    @Test
    fun `incoming revocation with a valid successor proof is projected as proven`() = runBlocking {
        val proof = KeyRevocation.proveSuccessor("g", oldPubkey, newPubkey, newPrivateKey)
        val proven = json.encodeToString(KeyRevocation(oldPubkey, newPubkey, "test", successorProof = proof))
        assertTrue(useCase.handleRevocation(proven, oldPubkey, "g", 2000, "rev"))
        coVerify(exactly = 1) {
            groupRepo.applyIdentityRevocation(
                "g",
                oldPubkey,
                newPubkey,
                2000,
                "rev",
                allowAbsent = false,
                successorProven = true
            )
        }
        // A proof for g must not authorize successor attribution in h.
        assertTrue(useCase.handleRevocation(proven, oldPubkey, "h", 2000, "rev2"))
        coVerify(exactly = 1) {
            groupRepo.applyIdentityRevocation(
                "h",
                oldPubkey,
                newPubkey,
                2000,
                "rev2",
                allowAbsent = false,
                successorProven = false
            )
        }
    }

    /**
     * A prepared payload without a successor proof must remain unproven during recovery.
     * Regenerating the proof would authorize a local transfer absent from the published event.
     */
    @Test
    fun `resuming a journal prepared without a successor proof projects it as unproven`() = runBlocking {
        pending = newPubkey
        journal.insert(legacyOperation())
        useCase.resumeIfNeeded()
        coVerify(exactly = 1) {
            groupRepo.applyIdentityRevocation(
                "g",
                oldPubkey,
                newPubkey,
                1000,
                NostrEvent.fromJson(legacyEvent("legacy-revocation", legacyPayload()))!!.id,
                allowAbsent = true,
                successorProven = false
            )
        }
        coVerify(exactly = 0) {
            groupRepo.applyIdentityRevocation(any(), any(), any(), any(), any(), any(), successorProven = true)
        }
        verify(exactly = 1) { encryption.decrypt(legacyPayload(), "group-key") }
        verify(exactly = 2) { encryption.decrypt(any(), "group-key") }
        verify { identity.finishPendingKeyPair(newPubkey) }
        assertTrue(journal.getAll("revocation").isEmpty())
    }

    @Test
    fun `a freshly prepared revocation projects the proven payload it publishes`() = runBlocking {
        val publishedContents = mutableListOf<String>()
        coEvery { publisher.publishDirect(any(), "g", any(), "key_revocation", any()) } coAnswers {
            publishedContents += firstArg<NostrEvent>().content
        }
        assertEquals(newPubkey, useCase())
        val content = publishedContents.single()
        assertTrue(json.decodeFromString<KeyRevocation>(content).provesSuccessor("g"))
        // Fresh projection uses authenticated journal plaintext without decrypting event content.
        verify(exactly = 0) { encryption.decrypt(any(), any()) }
        coVerify(exactly = 1) {
            groupRepo.applyIdentityRevocation(
                "g",
                oldPubkey,
                newPubkey,
                1000,
                signedEvents.getValue("key_revocation").id,
                allowAbsent = true,
                successorProven = true
            )
        }
    }

    @Test
    fun `projection fails closed when the epoch key is missing but the group exists`() = runBlocking {
        pending = newPubkey
        journal.insert(legacyOperation())
        coEvery { groupRepo.getGroupKeyForEpoch("g", any()) } returns null
        coEvery { groupRepo.getGroupKey("g") } returns null
        assertTrue(runCatching { useCase.resumeIfNeeded() }.isFailure)
        verify(exactly = 0) { identity.finishPendingKeyPair(any()) }
        verify(exactly = 0) { identity.discardPendingKeyPair() }
        coVerify(exactly = 0) { groupRepo.applyIdentityRevocation(any(), any(), any(), any(), any(), any(), any()) }
        // Keep the prepared plan for retry; key restoration is not exercised here.
        assertNotNull(journal.getAll("revocation").single().preparedJson)
    }

    private fun payload(new: String) = json.encodeToString(KeyRevocation(oldPubkey, new, "test"))

    /**
     * Legacy-shaped intent and plan without successor proof or embedded projection.
     * The encryption mock passes plaintext through unchanged.
     */
    private fun legacyOperation(): ControlOperation {
        val meta = GroupMeta(
            group.name,
            group.description,
            newPubkey,
            group.createdAt,
            listOf(newPubkey, peer),
            group.relays,
            mapOf(newPubkey to "Alice", peer to "Bob")
        )
        val events = listOf(
            PreparedControlEvent("g", "key_revocation", legacyEvent("legacy-revocation", legacyPayload())),
            PreparedControlEvent("g", "group_meta", legacyEvent("legacy-meta", json.encodeToString(meta)))
        )
        return ControlOperation(
            RotateGroupKeyUseCase.REVOCATION_ID,
            "revocation",
            json.encodeToString(RevocationIntent(oldPubkey, listOf(group))),
            json.encodeToString(PreparedRevocation(newPubkey, events))
        )
    }

    /** Revocation fixture without a successor proof. */
    private fun legacyPayload() = json.encodeToString(KeyRevocation(oldPubkey, newPubkey, "Key compromised"))

    private fun legacyEvent(id: String, content: String) =
        signedEvent("g", if (id == "legacy-revocation") "key_revocation" else "group_meta", content).toJson()

    private fun signedEvent(groupId: String, type: String, content: String): NostrEvent = NostrEvent(
        pubkey = oldPubkey,
        createdAt = 1000,
        kind = 30078,
        tags = listOf(listOf("g", groupId), listOf("t", type)),
        content = content
    ).sign(oldPrivateKey)
}
