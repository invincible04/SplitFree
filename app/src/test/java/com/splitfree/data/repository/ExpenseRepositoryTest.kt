package com.splitfree.data.repository

import com.splitfree.data.local.dao.EventDao
import com.splitfree.data.local.entities.EventEntity
import com.splitfree.domain.crypto.EventSigner
import com.splitfree.domain.crypto.GroupEncryption
import com.splitfree.domain.crypto.NostrEvent
import com.splitfree.domain.model.expense.Expense
import com.splitfree.domain.model.expense.Settlement
import com.splitfree.domain.model.expense.SplitEntry
import com.splitfree.domain.model.expense.SplitType
import com.splitfree.domain.model.group.Group
import com.splitfree.domain.repository.EventPublisherContract
import com.splitfree.domain.repository.ExpenseCorrectionCommand
import com.splitfree.domain.repository.ExpenseRevisionConflictException
import com.splitfree.domain.repository.ExpenseSaveConflictException
import com.splitfree.domain.repository.GroupRepositoryContract
import com.splitfree.domain.repository.IdentityContract
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ExpenseRepositoryTest {
    private val eventDao = mockk<EventDao>(relaxed = true)
    private val groupRepo = mockk<GroupRepositoryContract>(relaxed = true)
    private val encryption = mockk<GroupEncryption>(relaxed = true)
    private val identity = mockk<IdentityContract>(relaxed = true)
    private val signer = mockk<EventSigner>(relaxed = true)
    private val eventPublisher = mockk<EventPublisherContract>(relaxed = true)

    private fun repo() = ExpenseRepository(eventDao, groupRepo, encryption, identity, signer, eventPublisher)

    private val testEvent = NostrEvent("eid", "alice", 1, 30078, listOf(listOf("g", "g1")), "enc", "sig")

    private val group = Group(
        "g1",
        "Group",
        createdBy = "alice",
        createdAt = 1,
        members = listOf("alice", "bob"),
        relays = emptyList()
    )
    private val expense = Expense(
        "u1", 100, "INR", "test", "alice", SplitType.EQUAL,
        listOf(SplitEntry("alice", 50), SplitEntry("bob", 50)), 1, "food"
    )

    init {
        every { identity.getPublicKeyHex() } returns "alice"
        coEvery { groupRepo.getById("g1") } returns group
        coEvery { groupRepo.getGroupKeyForEpoch(any(), any()) } returns "testkey"
        coEvery { eventDao.getExpenseByAuthor(any(), any(), any()) } returns null
        coEvery { eventPublisher.publishExpense(any(), any(), any()) } returns true
        coEvery { groupRepo.getGroupKey(any()) } returns "testkey"
        every { encryption.encrypt(any(), any()) } returns "encrypted"
        every { signer.createSignedEvent(any(), any(), any(), any()) } returns testEvent
        every { signer.createSignedCommandEvent(any(), any(), any(), any(), any(), any(), any()) } returns testEvent
        coEvery { eventPublisher.publishMutation(any(), any(), any(), any(), any(), any()) } returns true
        coEvery { eventDao.getExpenseHistoryByAuthor(any(), any(), any()) } returns emptyList()
        coEvery { eventDao.getEventsByTypeAndAuthor(any(), any(), any()) } returns emptyList()
    }

    /** The current identity signs as [pubkey]; the repository compares the signed event against the pinned author. */
    private fun signsAs(pubkey: String) {
        every { identity.getPublicKeyHex() } returns pubkey
        every { signer.createSignedEvent(any(), any(), any(), any()) } returns testEvent.copy(pubkey = pubkey)
        every { signer.createSignedCommandEvent(any(), any(), any(), any(), any(), any(), any()) } returns
            testEvent.copy(pubkey = pubkey)
    }

    /** Alice's stored history under u1, as the author-bound lookup returns it. */
    private fun aliceHistory(vararg rows: EventEntity = arrayOf(aliceOriginal())) {
        coEvery { eventDao.getExpenseHistoryByAuthor("u1", "g1", "alice") } returns rows.toList()
    }

    // --- addExpense ---

    @Test
    fun `addExpense encrypts and stores event`() = runTest {
        val expense =
            Expense(
                "u1",
                100,
                "INR",
                "test",
                "alice",
                SplitType.EQUAL,
                listOf(SplitEntry("alice", 50), SplitEntry("bob", 50)),
                1
            )
        repo().addExpense(expense, "g1")

        coVerify { encryption.encrypt(any(), "testkey") }
        coVerify { signer.createSignedEvent("g1", "expense", "encrypted", "u1") }
        coVerify { eventPublisher.publishExpense(testEvent, group, "u1") }
    }

    @Test(expected = IllegalStateException::class)
    fun `addExpense throws when no group key`() = runTest {
        coEvery { groupRepo.getGroupKeyForEpoch("g1", 0) } returns null
        repo().addExpense(expense, "g1")
    }

    // --- addSettlement ---

    @Test
    fun `addSettlement encrypts and stores`() = runTest {
        signsAs("bob")
        val settlement = Settlement("s1", "bob", "alice", 50, "INR", timestamp = 1)
        repo().addSettlement(settlement, "g1")

        verify { signer.createSignedCommandEvent("g1", "settlement", "encrypted", "s1", "s1", null, null) }
        coVerify { eventPublisher.publishMutation(match { it.pubkey == "bob" }, group, "settlement", "s1", "s1", null) }
    }

    @Test(expected = IllegalArgumentException::class)
    fun `addSettlement rejects non-party`() = runTest {
        every { identity.getPublicKeyHex() } returns "charlie"
        repo().addSettlement(Settlement("s1", "bob", "alice", 50, "INR", timestamp = 1), "g1")
    }

    @Test(expected = IllegalArgumentException::class)
    fun `addSettlement rejects zero amount`() = runTest {
        every { identity.getPublicKeyHex() } returns "bob"
        repo().addSettlement(Settlement("s1", "bob", "alice", 0, "INR", timestamp = 1), "g1")
    }

    @Test(expected = IllegalArgumentException::class)
    fun `addSettlement rejects amount exceeding max`() = runTest {
        every { identity.getPublicKeyHex() } returns "bob"
        repo().addSettlement(Settlement("s1", "bob", "alice", 10_000_000_000_01L, "INR", timestamp = 1), "g1")
    }

    // --- deleteExpense ---

    @Test
    fun `deleteExpense stores delete event against the current revision`() = runTest {
        every { identity.getPublicKeyHex() } returns "alice"
        aliceHistory()
        repo().deleteExpense("u1", "g1", expectedAuthorPubkey = "alice")

        val commandId = slot<String>()
        verify {
            signer.createSignedCommandEvent("g1", "expense_delete", "encrypted", "u1", capture(commandId), null, null)
        }
        coVerify { eventPublisher.publishMutation(testEvent, group, "expense_delete", "u1", commandId.captured, "e1") }
    }

    @Test
    fun `deleteExpense targets the latest correction when one exists`() = runTest {
        aliceHistory(aliceOriginal(), aliceCorrection("c1", createdAt = 5), aliceCorrection("c2", createdAt = 9))
        repo().deleteExpense("u1", "g1", expectedAuthorPubkey = "alice")
        coVerify { eventPublisher.publishMutation(any(), group, "expense_delete", "u1", any(), "c2") }
    }

    @Test(expected = IllegalStateException::class)
    fun `deleteExpense throws when expense not found`() = runTest {
        aliceHistory(*emptyArray())
        repo().deleteExpense("u1", "g1", expectedAuthorPubkey = "alice")
    }

    @Test
    fun `deleteExpense of an already deleted expense is refused without publishing`() = runTest {
        aliceHistory(aliceOriginal(), aliceDeletion("d1"))
        val failure = runCatching { repo().deleteExpense("u1", "g1", expectedAuthorPubkey = "alice") }.exceptionOrNull()
        assertTrue(failure is IllegalStateException)
        assertEquals("This expense was already deleted", failure?.message)
        coVerify(exactly = 0) { eventPublisher.publishMutation(any(), any(), any(), any(), any(), any()) }
    }

    @Test(expected = IllegalStateException::class)
    fun `deleteExpense throws when not creator`() = runTest {
        // Bob owns no record under u1: the author-bound lookup finds nothing even though Alice's exists.
        signsAs("bob")
        aliceHistory()
        repo().deleteExpense("u1", "g1", expectedAuthorPubkey = "alice")
    }

    @Test
    fun `deleteExpense resolves the original by author, never by uuid alone`() = runTest {
        every { identity.getPublicKeyHex() } returns "alice"
        aliceHistory()
        repo().deleteExpense("u1", "g1", expectedAuthorPubkey = "alice")

        coVerify(exactly = 1) { eventDao.getExpenseHistoryByAuthor("u1", "g1", "alice") }
        coVerify(exactly = 0) { eventDao.getExpenseByUuid(any(), any()) }
    }

    @Test
    fun `deleteExpense of a uuid someone else owns publishes nothing`() = runTest {
        signsAs("bob")
        aliceHistory()
        val failure = runCatching { repo().deleteExpense("u1", "g1", expectedAuthorPubkey = "bob") }.exceptionOrNull()
        assertTrue(failure is IllegalStateException)
        assertEquals("Expense not found or not yours", failure?.message)
        verify(exactly = 0) { encryption.encrypt(any(), any()) }
        coVerify(exactly = 0) { eventPublisher.publishMutation(any(), any(), any(), any(), any(), any()) }
    }

    // --- correctExpense ---

    @Test
    fun `correctExpense stores correction event`() = runTest {
        every { identity.getPublicKeyHex() } returns "alice"
        aliceHistory()
        val corrected =
            Expense(
                "u1",
                200,
                "INR",
                "corrected",
                "alice",
                SplitType.EQUAL,
                listOf(SplitEntry("alice", 100), SplitEntry("bob", 100)),
                2
            )
        repo().correctExpense("u1", corrected, "g1", expectedAuthorPubkey = "alice")

        verify { signer.createSignedCommandEvent("g1", "expense_correction", "encrypted", "u1", any(), any(), null) }
        coVerify { eventPublisher.publishMutation(testEvent, group, "expense_correction", "u1", any(), "e1") }
    }

    @Test(expected = IllegalArgumentException::class)
    fun `correctExpense rejects corrected id that differs from original uuid`() = runTest {
        aliceHistory()
        repo().correctExpense("u1", expense.copy(id = "u1c"), "g1", expectedAuthorPubkey = "alice")
    }

    @Test(expected = IllegalArgumentException::class)
    fun `correctExpense rejects share sum mismatch`() = runTest {
        aliceHistory()
        // shares 50 + 50 = 100 but amount is 200
        repo().correctExpense("u1", expense.copy(amount = 200), "g1", expectedAuthorPubkey = "alice")
    }

    @Test(expected = IllegalArgumentException::class)
    fun `correctExpense rejects zero share`() = runTest {
        aliceHistory()
        repo().correctExpense(
            "u1",
            expense.copy(splitAmong = listOf(SplitEntry("alice", 100), SplitEntry("bob", 0))),
            "g1",
            expectedAuthorPubkey = "alice"
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun `correctExpense rejects duplicate participants`() = runTest {
        aliceHistory()
        repo().correctExpense(
            "u1",
            expense.copy(splitAmong = listOf(SplitEntry("alice", 50), SplitEntry("alice", 50))),
            "g1",
            expectedAuthorPubkey = "alice"
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun `correctExpense rejects non-member payer`() = runTest {
        aliceHistory()
        repo().correctExpense("u1", expense.copy(paidBy = "outsider"), "g1", expectedAuthorPubkey = "alice")
    }

    @Test(expected = IllegalArgumentException::class)
    fun `correctExpense rejects non-member split participant`() = runTest {
        aliceHistory()
        repo().correctExpense(
            "u1",
            expense.copy(splitAmong = listOf(SplitEntry("outsider", 100))),
            "g1",
            expectedAuthorPubkey = "alice"
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun `correctExpense rejects amount exceeding max`() = runTest {
        aliceHistory()
        val huge = 1_000_000_000_001L
        repo().correctExpense(
            "u1",
            expense.copy(amount = huge, splitAmong = listOf(SplitEntry("alice", huge))),
            "g1",
            expectedAuthorPubkey = "alice"
        )
    }

    @Test
    fun `correctExpense rejection happens before any encryption or publish`() = runTest {
        aliceHistory()
        val failure = runCatching {
            repo().correctExpense("u1", expense.copy(amount = 200), "g1", expectedAuthorPubkey = "alice")
        }.exceptionOrNull()
        assertTrue(failure is IllegalArgumentException)
        verify(exactly = 0) { encryption.encrypt(any(), any()) }
        coVerify(exactly = 0) { eventPublisher.publishMutation(any(), any(), any(), any(), any(), any()) }
    }

    @Test
    fun `deleteExpense caps reason at 200 characters`() = runTest {
        aliceHistory()
        val plaintext = slot<String>()
        every { encryption.encrypt(capture(plaintext), any()) } returns "encrypted"

        repo().deleteExpense("u1", "g1", reason = "r".repeat(500), expectedAuthorPubkey = "alice")

        val payload = Json.decodeFromString(MapSerializer(String.serializer(), String.serializer()), plaintext.captured)
        assertEquals(200, payload.getValue("reason").length)
    }

    @Test
    fun `deleteExpense keeps short reason intact`() = runTest {
        aliceHistory()
        val plaintext = slot<String>()
        every { encryption.encrypt(capture(plaintext), any()) } returns "encrypted"

        repo().deleteExpense("u1", "g1", reason = "duplicate", expectedAuthorPubkey = "alice")

        val payload = Json.decodeFromString(MapSerializer(String.serializer(), String.serializer()), plaintext.captured)
        assertEquals("duplicate", payload.getValue("reason"))
    }

    @Test(expected = IllegalStateException::class)
    fun `correctExpense throws when not creator`() = runTest {
        // Bob owns no record under u1: the author-bound lookup finds nothing even though Alice's exists.
        signsAs("bob")
        aliceHistory()
        repo().correctExpense("u1", expense, "g1", expectedAuthorPubkey = "alice")
    }

    @Test
    fun `correctExpense resolves the original by author, never by uuid alone`() = runTest {
        every { identity.getPublicKeyHex() } returns "alice"
        aliceHistory()
        val corrected =
            expense.copy(amount = 200, splitAmong = listOf(SplitEntry("alice", 100), SplitEntry("bob", 100)))
        repo().correctExpense("u1", corrected, "g1", expectedAuthorPubkey = "alice")

        coVerify(exactly = 1) { eventDao.getExpenseHistoryByAuthor("u1", "g1", "alice") }
        coVerify(exactly = 0) { eventDao.getExpenseByUuid(any(), any()) }
        coVerify(exactly = 0) { eventDao.getExpenseByAuthor(any(), any(), any()) }
        coVerify { eventPublisher.publishMutation(any(), group, "expense_correction", "u1", any(), "e1") }
    }

    @Test
    fun `correctExpense of a uuid someone else owns publishes nothing`() = runTest {
        signsAs("bob")
        aliceHistory()
        val failure = runCatching {
            repo().correctExpense("u1", expense, "g1", expectedAuthorPubkey = "bob")
        }.exceptionOrNull()
        assertTrue(failure is IllegalStateException)
        assertEquals("Expense not found or not yours", failure?.message)
        verify(exactly = 0) { encryption.encrypt(any(), any()) }
        coVerify(exactly = 0) { eventPublisher.publishMutation(any(), any(), any(), any(), any(), any()) }
    }

    @Test
    fun `addSettlement accepts when to is myPubkey`() = runTest {
        every { identity.getPublicKeyHex() } returns "alice"
        val settlement = Settlement("s1", "bob", "alice", 50, "INR", timestamp = 1)
        repo().addSettlement(settlement, "g1")
        coVerify { eventPublisher.publishMutation(any(), group, "settlement", "s1", "s1", null) }
    }

    @Test(expected = IllegalArgumentException::class)
    fun `addSettlement rejects negative amount`() = runTest {
        every { identity.getPublicKeyHex() } returns "bob"
        repo().addSettlement(Settlement("s1", "bob", "alice", -1, "INR", timestamp = 1), "g1")
    }

    // --- epoch key usage for new ciphertext ---

    @Test
    fun `addSettlement encrypts with the loaded group's epoch key and never calls getGroupKey`() = runTest {
        coEvery { groupRepo.getById("g1") } returns group.copy(keyEpoch = 3)
        coEvery { groupRepo.getGroupKeyForEpoch("g1", 3) } returns "epoch3key"
        signsAs("bob")
        repo().addSettlement(Settlement("s1", "bob", "alice", 50, "INR", timestamp = 1), "g1")

        coVerify { groupRepo.getGroupKeyForEpoch("g1", 3) }
        coVerify(exactly = 0) { groupRepo.getGroupKey(any()) }
        verify { encryption.encrypt(any(), "epoch3key") }
    }

    @Test
    fun `deleteExpense encrypts with the loaded group's epoch key and never calls getGroupKey`() = runTest {
        coEvery { groupRepo.getById("g1") } returns group.copy(keyEpoch = 3)
        coEvery { groupRepo.getGroupKeyForEpoch("g1", 3) } returns "epoch3key"
        aliceHistory()
        repo().deleteExpense("u1", "g1", expectedAuthorPubkey = "alice")

        coVerify { groupRepo.getGroupKeyForEpoch("g1", 3) }
        coVerify(exactly = 0) { groupRepo.getGroupKey(any()) }
        verify { encryption.encrypt(any(), "epoch3key") }
    }

    @Test
    fun `correctExpense encrypts with the loaded group's epoch key and never calls getGroupKey`() = runTest {
        coEvery { groupRepo.getById("g1") } returns group.copy(keyEpoch = 3)
        coEvery { groupRepo.getGroupKeyForEpoch("g1", 3) } returns "epoch3key"
        aliceHistory()
        val corrected = expense.copy(
            amount = 200,
            splitAmong = listOf(SplitEntry("alice", 100), SplitEntry("bob", 100))
        )
        repo().correctExpense("u1", corrected, "g1", expectedAuthorPubkey = "alice")

        coVerify { groupRepo.getGroupKeyForEpoch("g1", 3) }
        coVerify(exactly = 0) { groupRepo.getGroupKey(any()) }
        verify { encryption.encrypt(any(), "epoch3key") }
    }

    @Test(expected = IllegalStateException::class)
    fun `addSettlement throws when epoch key missing`() = runTest {
        every { identity.getPublicKeyHex() } returns "bob"
        coEvery { groupRepo.getGroupKeyForEpoch("g1", 0) } returns null
        repo().addSettlement(Settlement("s1", "bob", "alice", 50, "INR", timestamp = 1), "g1")
    }

    @Test(expected = IllegalStateException::class)
    fun `deleteExpense throws when epoch key missing`() = runTest {
        aliceHistory()
        coEvery { groupRepo.getGroupKeyForEpoch("g1", 0) } returns null
        repo().deleteExpense("u1", "g1", expectedAuthorPubkey = "alice")
    }

    @Test
    fun `mutations reject a selected other author even when I own the same uuid`() = runTest {
        aliceHistory()
        coEvery { eventDao.getExpenseHistoryByAuthor("u1", "g1", "bob") } returns
            listOf(aliceOriginal().copy(pubkey = "bob"))

        val deletion = runCatching { repo().deleteExpense("u1", "g1", expectedAuthorPubkey = "bob") }
        val correction = runCatching { repo().correctExpense("u1", expense, "g1", expectedAuthorPubkey = "bob") }

        assertTrue(deletion.exceptionOrNull() is IllegalStateException)
        assertTrue(correction.exceptionOrNull() is IllegalStateException)
        coVerify(exactly = 0) { eventDao.getExpenseHistoryByAuthor(any(), any(), any()) }
        verify(exactly = 0) { signer.createSignedCommandEvent(any(), any(), any(), any(), any(), any(), any()) }
        coVerify(exactly = 0) { eventPublisher.publishMutation(any(), any(), any(), any(), any(), any()) }
    }

    @Test
    fun `mutations of my colliding uuid stay bound to the selected author`() = runTest {
        aliceHistory()
        coEvery { eventDao.getExpenseHistoryByAuthor("u1", "g1", "bob") } returns
            listOf(aliceOriginal().copy(pubkey = "bob"))

        repo().deleteExpense("u1", "g1", expectedAuthorPubkey = "alice")
        repo().correctExpense("u1", expense, "g1", expectedAuthorPubkey = "alice")

        coVerify(exactly = 2) { eventDao.getExpenseHistoryByAuthor("u1", "g1", "alice") }
        coVerify(exactly = 0) { eventDao.getExpenseHistoryByAuthor("u1", "g1", "bob") }
        coVerify(exactly = 0) { eventDao.getExpenseByUuid(any(), any()) }
        coVerify(exactly = 2) {
            eventPublisher.publishMutation(match { it.pubkey == "alice" }, group, any(), "u1", any(), "e1")
        }
    }

    @Test
    fun `mutations recheck identity after suspension before publishing`() = runTest {
        aliceHistory()
        coEvery { groupRepo.getById("g1") } coAnswers {
            every { identity.getPublicKeyHex() } returns "bob"
            group
        }

        val deletion = runCatching { repo().deleteExpense("u1", "g1", expectedAuthorPubkey = "alice") }
        every { identity.getPublicKeyHex() } returns "alice"
        val correction = runCatching { repo().correctExpense("u1", expense, "g1", expectedAuthorPubkey = "alice") }

        assertTrue(deletion.exceptionOrNull() is IllegalStateException)
        assertTrue(correction.exceptionOrNull() is IllegalStateException)
        verify(exactly = 2) { signer.createSignedCommandEvent(any(), any(), any(), any(), any(), any(), any()) }
        coVerify(exactly = 0) { eventPublisher.publishMutation(any(), any(), any(), any(), any(), any()) }
    }

    @Test
    fun `mutations reject an event signed by a different author even if identity reads still match`() = runTest {
        aliceHistory()
        every { signer.createSignedCommandEvent(any(), any(), any(), any(), any(), any(), any()) } returns
            testEvent.copy(pubkey = "bob")

        val deletion = runCatching { repo().deleteExpense("u1", "g1", expectedAuthorPubkey = "alice") }
        val correction = runCatching { repo().correctExpense("u1", expense, "g1", expectedAuthorPubkey = "alice") }

        assertTrue(deletion.exceptionOrNull() is IllegalStateException)
        assertTrue(correction.exceptionOrNull() is IllegalStateException)
        coVerify(exactly = 0) { eventPublisher.publishMutation(any(), any(), any(), any(), any(), any()) }
    }

    // --- settlement membership checks ---

    @Test(expected = IllegalArgumentException::class)
    fun `addSettlement rejects non-member to`() = runTest {
        every { identity.getPublicKeyHex() } returns "alice"
        repo().addSettlement(Settlement("s1", "alice", "outsider", 50, "INR", timestamp = 1), "g1")
    }

    @Test(expected = IllegalArgumentException::class)
    fun `addSettlement rejects non-member from`() = runTest {
        every { identity.getPublicKeyHex() } returns "alice"
        repo().addSettlement(Settlement("s1", "outsider", "alice", 50, "INR", timestamp = 1), "g1")
    }

    @Test(expected = IllegalArgumentException::class)
    fun `addSettlement rejects when author was removed from group`() = runTest {
        every { identity.getPublicKeyHex() } returns "alice"
        coEvery { groupRepo.getById("g1") } returns group.copy(members = listOf("bob", "carol"))
        repo().addSettlement(Settlement("s1", "alice", "bob", 50, "INR", timestamp = 1), "g1")
    }

    @Test
    fun `addSettlement rejection happens before any encryption or publish`() = runTest {
        every { identity.getPublicKeyHex() } returns "alice"
        val failure = runCatching {
            repo().addSettlement(Settlement("s1", "alice", "outsider", 50, "INR", timestamp = 1), "g1")
        }.exceptionOrNull()
        assertTrue(failure is IllegalArgumentException)
        verify(exactly = 0) { encryption.encrypt(any(), any()) }
        coVerify(exactly = 0) { eventPublisher.publishMutation(any(), any(), any(), any(), any(), any()) }
    }

    @Test(expected = IllegalStateException::class)
    fun `addSettlement throws when group not found`() = runTest {
        every { identity.getPublicKeyHex() } returns "bob"
        coEvery { groupRepo.getById("g1") } returns null
        repo().addSettlement(Settlement("s1", "bob", "alice", 50, "INR", timestamp = 1), "g1")
    }

    @Test(expected = IllegalArgumentException::class)
    fun `deleteExpense rejects when author was removed from group`() = runTest {
        coEvery { groupRepo.getById("g1") } returns group.copy(members = listOf("bob"))
        aliceHistory()
        repo().deleteExpense("u1", "g1", expectedAuthorPubkey = "alice")
    }

    @Test(expected = IllegalArgumentException::class)
    fun `correctExpense rejects when author was removed from group`() = runTest {
        coEvery { groupRepo.getById("g1") } returns group.copy(members = listOf("bob"))
        aliceHistory()
        repo().correctExpense("u1", expense, "g1", expectedAuthorPubkey = "alice")
    }

    @Test(expected = IllegalStateException::class)
    fun `correctExpense throws when no group key`() = runTest {
        every { identity.getPublicKeyHex() } returns "alice"
        aliceHistory()
        coEvery { groupRepo.getGroupKeyForEpoch("g1", 0) } returns null
        repo().correctExpense("u1", expense, "g1", expectedAuthorPubkey = "alice")
    }

    @Test
    fun `correctExpense throws when expense not found`() = runTest {
        aliceHistory(*emptyArray())
        val failure = runCatching {
            repo().correctExpense("u1", expense, "g1", expectedAuthorPubkey = "alice")
        }.exceptionOrNull()
        assertTrue(failure is IllegalStateException)
        assertEquals("Expense not found or not yours", failure?.message)
        coVerify(exactly = 0) { eventPublisher.publishMutation(any(), any(), any(), any(), any(), any()) }
    }

    @Test
    fun `correctExpense applies to the latest correction and dates the edit after it`() = runTest {
        aliceHistory(aliceOriginal(), aliceCorrection("c1", createdAt = 5), aliceCorrection("c2", createdAt = 9))
        val createdAt = slot<Long>()
        every {
            signer.createSignedCommandEvent("g1", "expense_correction", any(), "u1", any(), capture(createdAt), null)
        } returns testEvent

        repo().correctExpense("u1", expense, "g1", expectedAuthorPubkey = "alice")

        assertTrue(createdAt.captured >= 10)
        coVerify { eventPublisher.publishMutation(testEvent, group, "expense_correction", "u1", any(), "c2") }
    }

    @Test
    fun `correctExpense dates a same-second edit strictly after the revision it replaces`() = runTest {
        val future = System.currentTimeMillis() / 1000 + 100
        aliceHistory(aliceOriginal().copy(createdAt = future))
        val createdAt = slot<Long>()
        every {
            signer.createSignedCommandEvent("g1", "expense_correction", any(), "u1", any(), capture(createdAt), null)
        } returns testEvent

        repo().correctExpense("u1", expense, "g1", expectedAuthorPubkey = "alice")

        assertEquals(future + 1, createdAt.captured)
    }

    @Test
    fun `correctExpense of a deleted expense is refused before encryption`() = runTest {
        aliceHistory(aliceOriginal(), aliceDeletion("d1"))
        val failure = runCatching {
            repo().correctExpense("u1", expense, "g1", expectedAuthorPubkey = "alice")
        }.exceptionOrNull()
        assertTrue(failure is IllegalStateException)
        verify(exactly = 0) { encryption.encrypt(any(), any()) }
        coVerify(exactly = 0) { eventPublisher.publishMutation(any(), any(), any(), any(), any(), any()) }
    }

    // --- correction commands ---

    @Test
    fun `a correction command carries its id and expected revision to the publisher`() = runTest {
        aliceHistory()
        val command = ExpenseCorrectionCommand("command-1", "e1")
        repo().correctExpense("u1", expense, "g1", expectedAuthorPubkey = "alice", command = command)

        verify {
            signer.createSignedCommandEvent("g1", "expense_correction", "encrypted", "u1", "command-1", any(), null)
        }
        coVerify { eventPublisher.publishMutation(testEvent, group, "expense_correction", "u1", "command-1", "e1") }
    }

    @Test
    fun `a correction against a stale revision is refused before encryption`() = runTest {
        aliceHistory(aliceOriginal(), aliceCorrection("c1", createdAt = 5))
        val failure = runCatching {
            repo().correctExpense(
                "u1",
                expense,
                "g1",
                expectedAuthorPubkey = "alice",
                command = ExpenseCorrectionCommand("cmd", "e1")
            )
        }.exceptionOrNull()
        assertTrue(failure is ExpenseRevisionConflictException)
        verify(exactly = 0) { encryption.encrypt(any(), any()) }
        coVerify(exactly = 0) { eventPublisher.publishMutation(any(), any(), any(), any(), any(), any()) }
    }

    @Test
    fun `a correction command against a deleted expense is refused`() = runTest {
        aliceHistory(aliceOriginal(), aliceDeletion("d1"))
        val failure = runCatching {
            repo().correctExpense(
                "u1",
                expense,
                "g1",
                expectedAuthorPubkey = "alice",
                command = ExpenseCorrectionCommand("cmd", "e1")
            )
        }.exceptionOrNull()
        assertTrue(failure is ExpenseRevisionConflictException)
        coVerify(exactly = 0) { eventPublisher.publishMutation(any(), any(), any(), any(), any(), any()) }
    }

    @Test
    fun `a retried correction command compares the saved payload and publishes nothing`() = runTest {
        aliceHistory(aliceOriginal(), aliceCorrection("c1", createdAt = 5))
        stubSavedCorrection("command-1")
        val command = ExpenseCorrectionCommand("command-1", "e1")

        repo().correctExpense("u1", expense.copy(splitAmong = expense.splitAmong.reversed()), "g1", "alice", command)

        verify(exactly = 0) { encryption.encrypt(any(), any()) }
        coVerify(exactly = 0) { eventPublisher.publishMutation(any(), any(), any(), any(), any(), any()) }
        val changed = runCatching {
            repo().correctExpense("u1", expense.copy(description = "Changed"), "g1", "alice", command)
        }.exceptionOrNull()
        assertTrue(changed is ExpenseSaveConflictException)
    }

    @Test
    fun `correction command lookup is bound to author group and logical expense`() = runTest {
        val command = ExpenseCorrectionCommand("command", "e1")
        val correction = savedCorrectionRow("command")
        coEvery { eventDao.getEventsByTypeAndAuthor("g1", "expense_correction", "alice") } returns
            listOf(correction.copy(pubkey = "bob"))
        assertNull(repo().getSavedCorrection("g1", "u1", "alice", command))
        coEvery { eventDao.getEventsByTypeAndAuthor("g1", "expense_correction", "alice") } returns
            listOf(correction.copy(groupId = "other"))
        assertNull(repo().getSavedCorrection("g1", "u1", "alice", command))
        coEvery { eventDao.getEventsByTypeAndAuthor("g1", "expense_correction", "alice") } returns
            listOf(savedCorrectionRow("other-command"))
        assertNull(repo().getSavedCorrection("g1", "u1", "alice", command))
        coEvery { eventDao.getEventsByTypeAndAuthor("g1", "expense_correction", "alice") } returns
            listOf(correction.copy(expenseUuid = "other-expense"))
        assertTrue(
            runCatching {
                repo().getSavedCorrection("g1", "u1", "alice", command)
            }.exceptionOrNull() is ExpenseSaveConflictException
        )
    }

    @Test
    fun `correction command lookup uses saved encryption epoch and checks identity afterward`() = runTest {
        val command = ExpenseCorrectionCommand("command", "e1")
        stubSavedCorrection("command")
        assertEquals(expense, repo().getSavedCorrection("g1", "u1", "alice", command))
        coVerify { groupRepo.getGroupKeyForEpoch("g1", 3) }
        every { encryption.decrypt("saved", "testkey") } answers {
            every { identity.getPublicKeyHex() } returns "bob"
            Json.encodeToString(Expense.serializer(), expense)
        }
        assertTrue(
            runCatching {
                repo().getSavedCorrection("g1", "u1", "alice", command)
            }.exceptionOrNull() is IllegalStateException
        )
    }

    @Test
    fun `correction command lookup rejects a different current author rather than claiming not saved`() = runTest {
        every { identity.getPublicKeyHex() } returns "bob"
        val failure = runCatching {
            repo().getSavedCorrection("g1", "u1", "alice", ExpenseCorrectionCommand("command", "e1"))
        }.exceptionOrNull()
        assertTrue(failure is IllegalStateException)
        coVerify(exactly = 0) { eventDao.getEventsByTypeAndAuthor(any(), any(), any()) }
    }

    @Test
    fun `correction publisher race loser reconciles exact payload and rejects changed payload`() = runTest {
        aliceHistory()
        val command = ExpenseCorrectionCommand("same-command", "e1")
        every { encryption.decrypt("saved", "testkey") } returns Json.encodeToString(Expense.serializer(), expense)
        coEvery { eventPublisher.publishMutation(any(), any(), any(), any(), any(), any()) } returns false
        coEvery { eventDao.getEventsByTypeAndAuthor("g1", "expense_correction", "alice") } returnsMany
            listOf(emptyList(), listOf(savedCorrectionRow("same-command")))
        repo().correctExpense("u1", expense, "g1", "alice", command)

        coEvery { eventDao.getEventsByTypeAndAuthor("g1", "expense_correction", "alice") } returnsMany
            listOf(emptyList(), listOf(savedCorrectionRow("same-command")))
        val failure = runCatching {
            repo().correctExpense("u1", expense.copy(description = "Changed"), "g1", "alice", command)
        }.exceptionOrNull()
        assertTrue(failure is ExpenseSaveConflictException)
    }

    @Test
    fun `a command whose revision moved because its own copy committed reconciles instead of conflicting`() = runTest {
        // First lookup: not saved yet. Revision check: moved on. Second lookup: this command's row exists.
        aliceHistory(aliceOriginal(), aliceCorrection("c1", createdAt = 5))
        every { encryption.decrypt("saved", "testkey") } returns Json.encodeToString(Expense.serializer(), expense)
        coEvery { eventDao.getEventsByTypeAndAuthor("g1", "expense_correction", "alice") } returnsMany
            listOf(emptyList(), listOf(savedCorrectionRow("same-command")))

        repo().correctExpense("u1", expense, "g1", "alice", ExpenseCorrectionCommand("same-command", "e1"))

        coVerify(exactly = 0) { eventPublisher.publishMutation(any(), any(), any(), any(), any(), any()) }
    }

    // --- editable expense ---

    @Test
    fun `getEditableExpense returns the latest correction and its revision id`() = runTest {
        aliceHistory(aliceOriginal(), aliceCorrection("c1", createdAt = 5), aliceCorrection("c2", createdAt = 9))
        every { encryption.decrypt("saved", "testkey") } returns Json.encodeToString(Expense.serializer(), expense)
        val editable = repo().getEditableExpense("g1", "u1", "alice")
        assertNotNull(editable)
        assertEquals("c2", editable?.revisionId)
        assertEquals("alice", editable?.authorPubkey)
        assertEquals(expense, editable?.expense)
        coVerify { groupRepo.getGroupKeyForEpoch("g1", 3) }
    }

    @Test
    fun `getEditableExpense returns the original when no correction exists`() = runTest {
        aliceHistory(aliceOriginal().copy(contentEncrypted = "saved"))
        every { encryption.decrypt("saved", "testkey") } returns Json.encodeToString(Expense.serializer(), expense)
        assertEquals("e1", repo().getEditableExpense("g1", "u1", "alice")?.revisionId)
    }

    @Test
    fun `getEditableExpense is null for a deleted or unknown expense`() = runTest {
        aliceHistory(aliceOriginal(), aliceDeletion("d1"))
        assertNull(repo().getEditableExpense("g1", "u1", "alice"))
        aliceHistory(*emptyArray())
        assertNull(repo().getEditableExpense("g1", "u1", "alice"))
        verify(exactly = 0) { encryption.decrypt(any(), any()) }
    }

    @Test
    fun `getEditableExpense rejects a different current author`() = runTest {
        every { identity.getPublicKeyHex() } returns "bob"
        val failure = runCatching { repo().getEditableExpense("g1", "u1", "alice") }.exceptionOrNull()
        assertTrue(failure is IllegalStateException)
        coVerify(exactly = 0) { eventDao.getExpenseHistoryByAuthor(any(), any(), any()) }
    }

    @Test(expected = IllegalArgumentException::class)
    fun `addExpense rejects removed author`() = runTest {
        coEvery { groupRepo.getById("g1") } returns group.copy(members = listOf("bob"))
        repo().addExpense(expense, "g1")
    }

    @Test(expected = IllegalArgumentException::class)
    fun `addExpense rejects removed payer`() = runTest {
        repo().addExpense(expense.copy(paidBy = "outsider"), "g1")
    }

    @Test(expected = IllegalArgumentException::class)
    fun `addExpense rejects removed participant`() = runTest {
        repo().addExpense(expense.copy(splitAmong = listOf(SplitEntry("outsider", 100))), "g1")
    }

    @Test(expected = IllegalArgumentException::class)
    fun `addExpense rejects duplicate participants`() = runTest {
        repo().addExpense(expense.copy(splitAmong = listOf(SplitEntry("alice", 50), SplitEntry("alice", 50))), "g1")
    }

    @Test(expected = IllegalArgumentException::class)
    fun `addExpense rejects incorrect share total`() = runTest {
        repo().addExpense(expense.copy(amount = 101), "g1")
    }

    @Test
    fun `retry compares canonical payload without publishing`() = runTest {
        stubSavedExpense()
        repo().addExpense(expense.copy(splitAmong = expense.splitAmong.reversed()), "g1")
        coVerify(exactly = 0) { eventPublisher.publishExpense(any(), any(), any()) }
    }

    @Test(expected = ExpenseSaveConflictException::class)
    fun `same operation with changed payload fails explicitly`() = runTest {
        stubSavedExpense()
        repo().addExpense(expense.copy(description = "Changed"), "g1")
    }

    @Test
    fun `race loser compares saved payload`() = runTest {
        val saved = savedEvent()
        coEvery { eventDao.getExpenseByAuthor("u1", "g1", "alice") } returnsMany listOf(null, saved)
        every { encryption.decrypt("saved", "testkey") } returns Json.encodeToString(Expense.serializer(), expense)
        coEvery { eventPublisher.publishExpense(any(), any(), any()) } returns false
        repo().addExpense(expense, "g1")
        coVerify(exactly = 2) { eventDao.getExpenseByAuthor("u1", "g1", "alice") }
    }

    @Test
    fun `saved recovery uses original epoch and current author`() = runTest {
        stubSavedExpense()
        assertEquals(expense, repo().getSavedExpense("g1", "u1"))
        coVerify { groupRepo.getGroupKeyForEpoch("g1", 3) }
        every { identity.getPublicKeyHex() } returns "bob"
        assertNull(repo().getSavedExpense("g1", "u1"))
    }

    @Test
    fun `save rejects changed draft author before any persistence or encryption`() = runTest {
        every { identity.getPublicKeyHex() } returns "bob"
        val failure = runCatching { repo().addExpense(expense, "g1", expectedAuthorPubkey = "alice") }.exceptionOrNull()
        assertTrue(failure is IllegalStateException)
        coVerify(exactly = 0) { eventDao.getExpenseByAuthor(any(), any(), any()) }
        coVerify(exactly = 0) { eventPublisher.publishExpense(any(), any(), any()) }
        verify(exactly = 0) { encryption.encrypt(any(), any()) }
    }

    @Test
    fun `save pins caller author when identity changes during group lookup`() = runTest {
        coEvery { groupRepo.getById("g1") } coAnswers {
            every { identity.getPublicKeyHex() } returns "bob"
            group
        }
        val failure = runCatching { repo().addExpense(expense, "g1", expectedAuthorPubkey = "alice") }.exceptionOrNull()
        assertTrue(failure is IllegalStateException)
        // The single pre-publish re-check catches the swap; nothing reaches the publisher.
        coVerify(exactly = 0) { eventPublisher.publishExpense(any(), any(), any()) }
    }

    @Test
    fun `save rejects identity change during signing even if signed event uses original author`() = runTest {
        every { signer.createSignedEvent(any(), any(), any(), any()) } answers {
            every { identity.getPublicKeyHex() } returns "bob"
            testEvent
        }
        val failure = runCatching { repo().addExpense(expense, "g1", expectedAuthorPubkey = "alice") }.exceptionOrNull()
        assertTrue(failure is IllegalStateException)
        coVerify(exactly = 0) { eventPublisher.publishExpense(any(), any(), any()) }
    }

    @Test
    fun `save rejects a signed event whose pubkey differs from the pinned author`() = runTest {
        every { signer.createSignedEvent(any(), any(), any(), any()) } returns testEvent.copy(pubkey = "bob")
        val failure = runCatching { repo().addExpense(expense, "g1", expectedAuthorPubkey = "alice") }.exceptionOrNull()
        assertTrue(failure is IllegalStateException)
        coVerify(exactly = 0) { eventPublisher.publishExpense(any(), any(), any()) }
    }

    // --- identity lookups are a Keystore round-trip each; keep them to the two that matter ---

    @Test
    fun `addExpense reads the identity exactly twice on the publish path`() = runTest {
        repo().addExpense(expense, "g1")
        // Once to pin the author up front, once right before publish. No intermediate re-checks.
        verify(exactly = 2) { identity.getPublicKeyHex() }
        coVerify(exactly = 1) { eventPublisher.publishExpense(testEvent, group, "u1") }
    }

    @Test
    fun `addExpense reads the identity once when the expense is already saved`() = runTest {
        stubSavedExpense()
        repo().addExpense(expense, "g1")
        verify(exactly = 1) { identity.getPublicKeyHex() }
    }

    @Test
    fun `addExpense reads the identity at most twice when it loses the save race`() = runTest {
        coEvery { eventDao.getExpenseByAuthor("u1", "g1", "alice") } returnsMany listOf(null, savedEvent())
        every { encryption.decrypt("saved", "testkey") } returns Json.encodeToString(Expense.serializer(), expense)
        coEvery { eventPublisher.publishExpense(any(), any(), any()) } returns false
        repo().addExpense(expense, "g1")
        verify(atLeast = 1, atMost = 2) { identity.getPublicKeyHex() }
    }

    @Test
    fun `recovery rejects different current author rather than claiming not saved`() = runTest {
        every { identity.getPublicKeyHex() } returns "bob"
        val failure = runCatching { repo().getSavedExpense("g1", "u1", "alice") }.exceptionOrNull()
        assertTrue(failure is IllegalStateException)
        coVerify(exactly = 0) { eventDao.getExpenseByAuthor(any(), any(), any()) }
    }

    @Test
    fun `recovery rejects identity changed during empty lookup`() = runTest {
        coEvery { eventDao.getExpenseByAuthor("u1", "g1", "alice") } coAnswers {
            every { identity.getPublicKeyHex() } returns "bob"
            null
        }
        val failure = runCatching { repo().getSavedExpense("g1", "u1", "alice") }.exceptionOrNull()
        assertTrue(failure is IllegalStateException)
    }

    @Test
    fun `recovery rejects identity changed during decryption`() = runTest {
        stubSavedExpense()
        every { encryption.decrypt("saved", "testkey") } answers {
            every { identity.getPublicKeyHex() } returns "bob"
            Json.encodeToString(Expense.serializer(), expense)
        }
        val failure = runCatching { repo().getSavedExpense("g1", "u1", "alice") }.exceptionOrNull()
        assertTrue(failure is IllegalStateException)
    }

    /** Alice's stored original `expense` event for u1, as returned by the author-bound lookup. */
    private fun aliceOriginal() =
        EventEntity("e1", "g1", "alice", 1, 30078, "enc", "expense", "u1", "sig", receivedAt = 1)

    private fun aliceCorrection(eventId: String, createdAt: Long) = EventEntity(
        eventId, "g1", "alice", createdAt, 30078, "saved", "expense_correction", "u1", "sig", receivedAt = createdAt,
        keyEpoch = 3
    )

    private fun aliceDeletion(eventId: String) =
        EventEntity(eventId, "g1", "alice", 20, 30078, "enc", "expense_delete", "u1", "sig", receivedAt = 20)

    /** Alice's committed correction row addressed under [commandId], with its signed JSON. */
    private fun savedCorrectionRow(commandId: String) = savedEvent().copy(
        eventId = "saved-$commandId",
        eventType = "expense_correction",
        originalEventJson =
        testEvent.copy(tags = listOf(listOf("d", "g1:expense_correction:$commandId"), listOf("x", "u1"))).toJson()
    )

    private fun stubSavedCorrection(commandId: String) {
        coEvery { eventDao.getEventsByTypeAndAuthor("g1", "expense_correction", "alice") } returns
            listOf(savedCorrectionRow(commandId))
        every { encryption.decrypt("saved", "testkey") } returns Json.encodeToString(Expense.serializer(), expense)
    }

    private fun savedEvent() = EventEntity(
        "saved-event", "g1", "alice", 1, 30078, "saved", "expense", "u1", "sig", 1,
        keyEpoch = 3
    )

    private fun stubSavedExpense() {
        coEvery { eventDao.getExpenseByAuthor("u1", "g1", "alice") } returns savedEvent()
        every { encryption.decrypt("saved", "testkey") } returns Json.encodeToString(Expense.serializer(), expense)
    }
}
