package com.splitfree.domain.usecase.group

import com.splitfree.domain.crypto.EventSigner
import com.splitfree.domain.crypto.GroupEncryption
import com.splitfree.domain.crypto.NostrEvent
import com.splitfree.domain.model.group.Group
import com.splitfree.domain.model.group.GroupMeta
import com.splitfree.domain.model.group.KeyRevocation
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
    private val oldPubkey = "aa".repeat(32)
    private val newPubkey = "bb".repeat(32)
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
        every { identity.hasPendingKeyPair() } answers { pending != null }
        every { identity.getPendingPublicKeyHex() } answers { pending }
        every { identity.generatePendingKeyPair() } answers {
            pending = newPubkey
            newPubkey
        }
        every { identity.markRevocationStarted() } just Runs
        every { identity.setRevocationEventIds(any()) } just Runs
        every { identity.finishPendingKeyPair(any()) } just Runs
        every { identity.discardPendingKeyPair() } just Runs
        every { encryption.encrypt(any(), any()) } answers { firstArg() }
        every { signer.createSignedEvent(any(), any(), any(), any(), any()) } answers {
            NostrEvent(secondArg(), oldPubkey, 1000, 30078, emptyList(), thirdArg(), "sig")
        }
        coEvery { groupRepo.getAll() } returns listOf(group)
        coEvery { groupRepo.getById("g") } returns group
        coEvery { groupRepo.getGroupKeyForEpoch(any(), any()) } returns "group-key"
        coEvery { groupRepo.applyIdentityRevocation(any(), any(), any(), any(), any(), any()) } returns true
        useCase = service()
    }

    @After
    fun teardown() = unmockkStatic(android.util.Log::class)

    private fun service() =
        RevokeKeyUseCase(identity, groupRepo, encryption, signer, publisher, journal, ControlOperationLock())

    @Test
    fun `invoke publishes then projects atomic revocation and promotes the journaled replacement`() = runBlocking {
        assertEquals(newPubkey, useCase())
        coVerifyOrder {
            identity.setRevocationEventIds(listOf("key_revocation", "group_meta"))
            publisher.publishDirect(any(), "g", any(), "key_revocation", any())
            publisher.publishDirect(any(), "g", any(), "group_meta", any())
            groupRepo.applyIdentityRevocation("g", oldPubkey, newPubkey, 1000, "key_revocation", allowAbsent = true)
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
        coEvery { groupRepo.applyIdentityRevocation(any(), any(), any(), any(), any(), any()) } returns false
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
        // Refused before anything durable: no intent to block rotations, no pending identity to preserve.
        assertTrue(journal.getAll("revocation").isEmpty())
        verify(exactly = 0) { identity.generatePendingKeyPair() }
    }

    @Test
    fun `projection of a group deleted locally after publication is tolerated`() = runBlocking {
        coEvery { groupRepo.applyIdentityRevocation(any(), any(), any(), any(), any(), any()) } returns false
        coEvery { groupRepo.getById("g") } returns null
        assertEquals(newPubkey, useCase())
        verify { identity.finishPendingKeyPair(newPubkey) }
        assertTrue(journal.getAll("revocation").isEmpty())
    }

    @Test
    fun `own projection passes the revocation event's clock and tolerates an absent identity`() = runBlocking {
        useCase()
        coVerify(exactly = 1) {
            groupRepo.applyIdentityRevocation("g", oldPubkey, newPubkey, 1000, "key_revocation", allowAbsent = true)
        }
        coVerify(exactly = 0) { groupRepo.applyIdentityRevocation(any(), any(), any(), any(), "group_meta", any()) }
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
            groupRepo.applyIdentityRevocation("g", oldPubkey, newPubkey, 2000, "rev", allowAbsent = false)
        }
        coVerify(exactly = 0) { groupRepo.getById(any()) }
    }

    @Test
    fun `incoming no-replacement revocation delegates empty replacement`() = runBlocking {
        assertTrue(useCase.handleRevocation(payload(""), oldPubkey, "g", 2000, "rev"))
        coVerify { groupRepo.applyIdentityRevocation("g", oldPubkey, "", 2000, "rev", allowAbsent = false) }
    }

    @Test
    fun `incoming unknown or absent-old revocation propagates repository rejection`() = runBlocking {
        coEvery { groupRepo.applyIdentityRevocation(any(), any(), any(), any(), any(), any()) } returns false
        assertFalse(useCase.handleRevocation(payload(newPubkey), oldPubkey, "g", 2000, "rev"))
    }

    @Test
    fun `incoming malformed payload author and replacement are rejected without projection`() = runBlocking {
        assertFalse(useCase.handleRevocation("bad json", oldPubkey, "g", 1, "rev"))
        assertFalse(useCase.handleRevocation(payload(newPubkey), peer, "g", 1, "rev"))
        for (replacement in listOf("not-a-key", "BB".repeat(32), oldPubkey)) {
            assertFalse(useCase.handleRevocation(payload(replacement), oldPubkey, "g", 1, "rev"))
        }
        coVerify(exactly = 0) { groupRepo.applyIdentityRevocation(any(), any(), any(), any(), any(), any()) }
    }

    private fun payload(new: String) = json.encodeToString(KeyRevocation(oldPubkey, new, "test"))
}
