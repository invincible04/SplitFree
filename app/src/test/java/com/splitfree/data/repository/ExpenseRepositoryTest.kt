package com.splitfree.data.repository

import com.splitfree.data.local.EventDao
import com.splitfree.data.local.OutboxDao
import com.splitfree.data.local.entities.EventEntity
import com.splitfree.data.nostr.EventThrottler
import com.splitfree.domain.crypto.*
import com.splitfree.domain.model.Expense
import com.splitfree.domain.model.Settlement
import com.splitfree.domain.model.SplitEntry
import com.splitfree.domain.model.SplitType
import io.mockk.*
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class ExpenseRepositoryTest {
    private val eventDao = mockk<EventDao>(relaxed = true)
    private val outboxDao = mockk<OutboxDao>(relaxed = true)
    private val groupRepo = mockk<GroupRepository>(relaxed = true)
    private val encryption = mockk<GroupEncryption>(relaxed = true)
    private val identity = mockk<IdentityManager>(relaxed = true)
    private val signer = mockk<EventSigner>(relaxed = true)
    private val throttler = mockk<EventThrottler>(relaxed = true)
    private val giftWrap = mockk<GiftWrapService>(relaxed = true)

    private fun repo() = ExpenseRepository(eventDao, outboxDao, groupRepo, encryption, identity, signer, throttler, giftWrap)

    private val testEvent = NostrEvent("eid", "pub", 1, 30078, listOf(listOf("g", "g1")), "enc", "sig")

    init {
        coEvery { groupRepo.getGroupKey(any()) } returns "testkey"
        every { encryption.encrypt(any(), any()) } returns "encrypted"
        every { signer.createSignedEvent(any(), any(), any(), any()) } returns testEvent
        every { giftWrap.enabled } returns false
        coEvery { outboxDao.count() } returns 0
    }

    // --- addExpense ---

    @Test
    fun `addExpense encrypts and stores event`() =
        runTest {
            val expense =
                Expense(
                    "u1",
                    100,
                    "INR",
                    "test",
                    "alice",
                    SplitType.EQUAL,
                    listOf(SplitEntry("alice", 50), SplitEntry("bob", 50)),
                    1,
                )
            repo().addExpense(expense, "g1")

            coVerify { encryption.encrypt(any(), "testkey") }
            coVerify { signer.createSignedEvent("g1", "expense", "encrypted", "u1") }
            coVerify { eventDao.insert(match { it.eventType == "expense" && it.groupId == "g1" }) }
            verify { throttler.enqueue(testEvent) }
        }

    @Test(expected = IllegalStateException::class)
    fun `addExpense throws when no group key`() =
        runTest {
            coEvery { groupRepo.getGroupKey("g1") } returns null
            repo().addExpense(
                Expense("u1", 100, "INR", "t", "a", SplitType.EQUAL, listOf(SplitEntry("a", 100)), 1),
                "g1",
            )
        }

    // --- addSettlement ---

    @Test
    fun `addSettlement encrypts and stores`() =
        runTest {
            every { identity.getPublicKeyHex() } returns "bob"
            val settlement = Settlement("s1", "bob", "alice", 50, "INR", timestamp = 1)
            repo().addSettlement(settlement, "g1")

            coVerify { eventDao.insert(match { it.eventType == "settlement" }) }
        }

    @Test(expected = IllegalArgumentException::class)
    fun `addSettlement rejects non-party`() =
        runTest {
            every { identity.getPublicKeyHex() } returns "charlie"
            repo().addSettlement(Settlement("s1", "bob", "alice", 50, "INR", timestamp = 1), "g1")
        }

    @Test(expected = IllegalArgumentException::class)
    fun `addSettlement rejects zero amount`() =
        runTest {
            every { identity.getPublicKeyHex() } returns "bob"
            repo().addSettlement(Settlement("s1", "bob", "alice", 0, "INR", timestamp = 1), "g1")
        }

    @Test(expected = IllegalArgumentException::class)
    fun `addSettlement rejects amount exceeding max`() =
        runTest {
            every { identity.getPublicKeyHex() } returns "bob"
            repo().addSettlement(Settlement("s1", "bob", "alice", 10_000_000_000_01L, "INR", timestamp = 1), "g1")
        }

    // --- deleteExpense ---

    @Test
    fun `deleteExpense stores delete event`() =
        runTest {
            every { identity.getPublicKeyHex() } returns "alice"
            coEvery { eventDao.getExpenseByUuid("u1") } returns
                EventEntity(
                    "e1",
                    "g1",
                    "alice",
                    1,
                    30078,
                    "enc",
                    "dec",
                    "expense",
                    "u1",
                    "sig",
                    receivedAt = 1,
                )
            repo().deleteExpense("u1", "g1")

            coVerify { signer.createSignedEvent("g1", "expense_delete", "encrypted", "u1") }
            coVerify { eventDao.insert(match { it.eventType == "expense_delete" }) }
        }

    @Test(expected = IllegalStateException::class)
    fun `deleteExpense throws when expense not found`() =
        runTest {
            coEvery { eventDao.getExpenseByUuid("u1") } returns null
            repo().deleteExpense("u1", "g1")
        }

    @Test(expected = IllegalStateException::class)
    fun `deleteExpense throws when not creator`() =
        runTest {
            every { identity.getPublicKeyHex() } returns "bob"
            coEvery { eventDao.getExpenseByUuid("u1") } returns
                EventEntity(
                    "e1",
                    "g1",
                    "alice",
                    1,
                    30078,
                    "enc",
                    "dec",
                    "expense",
                    "u1",
                    "sig",
                    receivedAt = 1,
                )
            repo().deleteExpense("u1", "g1")
        }

    // --- correctExpense ---

    @Test
    fun `correctExpense stores correction event`() =
        runTest {
            every { identity.getPublicKeyHex() } returns "alice"
            coEvery { eventDao.getExpenseByUuid("u1") } returns
                EventEntity(
                    "e1",
                    "g1",
                    "alice",
                    1,
                    30078,
                    "enc",
                    "dec",
                    "expense",
                    "u1",
                    "sig",
                    receivedAt = 1,
                )
            val corrected =
                Expense(
                    "u1c",
                    200,
                    "INR",
                    "corrected",
                    "alice",
                    SplitType.EQUAL,
                    listOf(SplitEntry("alice", 100), SplitEntry("bob", 100)),
                    2,
                )
            repo().correctExpense("u1", corrected, "g1")

            coVerify { signer.createSignedEvent("g1", "expense_correction", "encrypted", "u1") }
        }

    @Test(expected = IllegalStateException::class)
    fun `correctExpense throws when not creator`() =
        runTest {
            every { identity.getPublicKeyHex() } returns "bob"
            coEvery { eventDao.getExpenseByUuid("u1") } returns
                EventEntity(
                    "e1",
                    "g1",
                    "alice",
                    1,
                    30078,
                    "enc",
                    "dec",
                    "expense",
                    "u1",
                    "sig",
                    receivedAt = 1,
                )
            repo().correctExpense("u1", mockk(), "g1")
        }

    // --- gift wrap path ---

    @Test
    fun `addExpense with gift wrap wraps for each member`() =
        runTest {
            every { giftWrap.enabled } returns true
            every { identity.getPublicKeyHex() } returns "alice"
            coEvery { groupRepo.getMembers("g1") } returns listOf("alice", "bob", "charlie")
            every { giftWrap.wrapIfEnabled(any(), any()) } returns testEvent

            val expense =
                Expense(
                    "u1",
                    100,
                    "INR",
                    "t",
                    "alice",
                    SplitType.EQUAL,
                    listOf(SplitEntry("alice", 50), SplitEntry("bob", 50)),
                    1,
                )
            repo().addExpense(expense, "g1")

            // Should wrap for bob and charlie (not alice/self)
            verify(exactly = 2) { giftWrap.wrapIfEnabled(any(), any()) }
            verify(exactly = 2) { throttler.enqueue(any()) }
        }

    @Test
    fun `addSettlement accepts when to is myPubkey`() =
        runTest {
            every { identity.getPublicKeyHex() } returns "alice"
            val settlement = Settlement("s1", "bob", "alice", 50, "INR", timestamp = 1)
            repo().addSettlement(settlement, "g1")
            coVerify { eventDao.insert(match { it.eventType == "settlement" }) }
        }

    @Test(expected = IllegalArgumentException::class)
    fun `addSettlement rejects negative amount`() =
        runTest {
            every { identity.getPublicKeyHex() } returns "bob"
            repo().addSettlement(Settlement("s1", "bob", "alice", -1, "INR", timestamp = 1), "g1")
        }

    @Test(expected = IllegalStateException::class)
    fun `correctExpense throws when no group key`() =
        runTest {
            every { identity.getPublicKeyHex() } returns "alice"
            coEvery { eventDao.getExpenseByUuid("u1") } returns
                EventEntity(
                    "e1",
                    "g1",
                    "alice",
                    1,
                    30078,
                    "enc",
                    "dec",
                    "expense",
                    "u1",
                    "sig",
                    receivedAt = 1,
                )
            coEvery { groupRepo.getGroupKey("g1") } returns null
            repo().correctExpense("u1", mockk(), "g1")
        }

    @Test(expected = IllegalStateException::class)
    fun `correctExpense throws when expense not found`() =
        runTest {
            coEvery { eventDao.getExpenseByUuid("u1") } returns null
            repo().correctExpense("u1", mockk(), "g1")
        }

    @Test
    fun `addExpense skips outbox when full`() =
        runTest {
            coEvery { outboxDao.count() } returns 10_000
            val expense =
                Expense(
                    "u1",
                    100,
                    "INR",
                    "t",
                    "a",
                    SplitType.EQUAL,
                    listOf(SplitEntry("a", 100)),
                    1,
                )
            repo().addExpense(expense, "g1")
            coVerify(exactly = 0) { outboxDao.insert(any()) }
            verify { throttler.enqueue(testEvent) }
        }

    @Test
    fun `addExpense with gift wrap skips outbox when full`() =
        runTest {
            every { giftWrap.enabled } returns true
            every { identity.getPublicKeyHex() } returns "alice"
            coEvery { groupRepo.getMembers("g1") } returns listOf("alice", "bob")
            every { giftWrap.wrapIfEnabled(any(), any()) } returns testEvent
            coEvery { outboxDao.count() } returns 10_000

            val expense =
                Expense(
                    "u1",
                    100,
                    "INR",
                    "t",
                    "alice",
                    SplitType.EQUAL,
                    listOf(SplitEntry("alice", 50), SplitEntry("bob", 50)),
                    1,
                )
            repo().addExpense(expense, "g1")
            coVerify(exactly = 0) { outboxDao.insert(any()) }
        }
}
