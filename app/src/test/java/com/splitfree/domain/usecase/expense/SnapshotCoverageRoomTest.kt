package com.splitfree.domain.usecase.expense

import android.app.Application
import androidx.room.Room
import com.splitfree.data.local.AppDatabase
import com.splitfree.data.repository.EventRepository
import com.splitfree.data.repository.GroupRepository
import com.splitfree.data.util.CompressionUtil
import com.splitfree.domain.crypto.EventSigner
import com.splitfree.domain.crypto.GiftWrapService
import com.splitfree.domain.crypto.GroupEncryption
import com.splitfree.domain.crypto.NostrEvent
import com.splitfree.domain.model.balance.BalanceSnapshot
import com.splitfree.domain.model.balance.SnapshotBalance
import com.splitfree.domain.model.expense.Expense
import com.splitfree.domain.model.expense.Settlement
import com.splitfree.domain.model.expense.SplitEntry
import com.splitfree.domain.model.expense.SplitType
import com.splitfree.domain.model.group.Group
import com.splitfree.domain.repository.IdentityContract
import com.splitfree.domain.repository.SettingsContract
import com.splitfree.domain.util.HashUtil
import com.splitfree.domain.validation.EventValidator
import com.splitfree.sync.event.EventPostProcessor
import com.splitfree.sync.event.EventProcessor
import com.splitfree.sync.event.IngestOutcome
import com.splitfree.sync.event.IngestionContext
import com.splitfree.sync.event.MembershipHistory
import com.splitfree.test.FakeSecureStorage
import io.mockk.clearMocks
import io.mockk.every
import io.mockk.mockk
import io.mockk.spyk
import io.mockk.verify
import java.util.concurrent.Executor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** Real Room repositories, signed Nostr ingestion, and NIP-44 crypto on two independently stored ledgers. */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
class SnapshotCoverageRoomTest {
    private val groupId = "snapshot-coverage-room"
    private val key = GroupEncryption(CompressionUtil).generateGroupKey()
    private val devices = mutableListOf<Device>()

    private inner class Device(seed: Int) {
        private val secret = ByteArray(32) { seed.toByte() }
        val pubkey = NostrEvent.pubkeyFromPrivkey(secret)
        private val identity = mockk<IdentityContract> {
            every { getPublicKeyHex() } returns pubkey
            every { getPrivateKeyBytes() } answers { secret.copyOf() }
        }
        private val db = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), AppDatabase::class.java)
            .addCallback(AppDatabase.SYNC_REVISION_CALLBACK)
            .allowMainThreadQueries()
            .setQueryExecutor(Executor { it.run() })
            .setTransactionExecutor(Executor { it.run() })
            .build()
        val events = EventRepository(db, db.eventDao())
        private val groups = GroupRepository(db.groupDao(), FakeSecureStorage())
        val encryption = spyk(GroupEncryption(CompressionUtil))
        private val signer = EventSigner(identity)
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        private val giftWrap = GiftWrapService(
            identity,
            mockk<SettingsContract> {
                every { giftWrapEnabled } returns true
            }
        )
        private val postProcessor = EventPostProcessor(
            groups,
            mockk(relaxed = true),
            mockk(relaxed = true),
            mockk(relaxed = true),
            mockk(relaxed = true),
            identity,
            scope
        )
        private val processor = EventProcessor(
            db.eventDao(), groups, encryption, signer, identity, giftWrap, EventValidator(), postProcessor,
            MembershipHistory(db.eventDao(), groups, identity)
        )
        val compute = ComputeBalancesUseCase(events, groups, encryption)

        init {
            devices += this
        }

        suspend fun join(creator: Device, peer: Device) = groups.save(
            Group(
                groupId,
                "Trip",
                createdBy = creator.pubkey,
                createdAt = 1,
                members = listOf(creator.pubkey, peer.pubkey),
                relays = emptyList()
            ),
            key
        )

        suspend fun ingest(event: NostrEvent) {
            val result = processor.process(event, context = IngestionContext.RECONCILIATION, expectedGroupId = groupId)
            assertEquals("${event.id}: ${result.reason}", IngestOutcome.APPLIED, result.outcome)
        }

        fun event(type: String, payload: String, uuid: String, at: Long): NostrEvent = signer.createSignedEvent(
            groupId,
            type,
            encryption.encrypt(payload, key),
            expenseUuid = uuid,
            createdAt = at
        )

        suspend fun snapshot(expectedNet: Long): NostrEvent {
            val ledger = events.getEventsByGroup(groupId)
            assertEquals(10, ledger.size)
            val balances = compute.computeWithExclusions(groupId, ledger, useSnapshots = false).balances
            assertEquals(expectedNet, balances.single { it.pubkey == pubkey }.net)
            return event(
                "snapshot",
                Json.encodeToString(
                    BalanceSnapshot(
                        "snapshot-1",
                        ledger.size,
                        200,
                        balances.map { SnapshotBalance(it.pubkey, it.net, it.currency) },
                        ledger.map { HashUtil.eventHashPrefix(it.eventId) }
                    )
                ),
                "snapshot-1",
                200
            )
        }

        suspend fun assertNet(creator: Device, expected: Long, coveredOriginal: NostrEvent, seeded: Boolean) {
            val ledger = events.getEventsByGroup(groupId)
            val replay = compute.computeWithExclusions(groupId, ledger, useSnapshots = false)
            val expectedNets = mapOf(creator.pubkey to expected, devices.single { it != creator }.pubkey to -expected)
            assertEquals(expectedNets, replay.balances.associate { it.pubkey to it.net })
            clearMocks(encryption, answers = false)
            val incremental = compute.computeWithExclusions(groupId)
            assertEquals(
                "Snapshot-enabled calculation must agree with full local replay",
                expectedNets,
                incremental.balances.associate { it.pubkey to it.net }
            )
            assertEquals(replay.excludedExpenses, incremental.excludedExpenses)
            // Prove the full-coverage control still uses the optimisation, rather than always replaying.
            verify(exactly = if (seeded) 0 else 1) { encryption.decrypt(coveredOriginal.content, key) }
        }

        fun close() {
            scope.cancel()
            db.close()
        }
    }

    @After
    fun cleanup() = devices.forEach { it.close() }

    private fun expense(
        creator: Device,
        peer: Device,
        number: Int,
        amount: Long = 100,
        at: Long = number.toLong(),
        type: String = "expense"
    ): NostrEvent {
        val id = "expense-$number"
        return creator.event(
            type,
            Json.encodeToString(
                Expense(
                    id = id,
                    amount = amount,
                    currency = "USD",
                    description = "Trip",
                    paidBy = creator.pubkey,
                    splitType = SplitType.EQUAL,
                    splitAmong = listOf(SplitEntry(creator.pubkey, amount / 2), SplitEntry(peer.pubkey, amount / 2)),
                    timestamp = at
                )
            ),
            id,
            at
        )
    }

    private suspend fun assertCorrectionReplay(missingCovered: Boolean) {
        val creator = Device(1)
        val receiver = Device(2)
        devices.forEach { it.join(creator, receiver) }
        val originals = (1..9).map { expense(creator, receiver, it) }
        originals.forEach { original -> devices.forEach { it.ingest(original) } }
        val intermediate = expense(creator, receiver, 1, amount = 200, at = 100, type = "expense_correction")
        creator.ingest(intermediate)
        if (!missingCovered) receiver.ingest(intermediate)
        val snapshot = creator.snapshot(500)
        val latest = expense(creator, receiver, 1, amount = 300, at = 300, type = "expense_correction")
        devices.forEach {
            it.ingest(snapshot)
            it.ingest(latest)
        }
        assertEquals(!missingCovered, receiver.events.getEventsByGroup(groupId).any { it.eventId == intermediate.id })
        creator.assertNet(creator, 550, originals.last(), seeded = true)
        receiver.assertNet(creator, 550, originals.last(), seeded = !missingCovered)
    }

    private suspend fun assertSettlementReplay(missingCovered: Boolean) {
        val creator = Device(1)
        val receiver = Device(2)
        devices.forEach { it.join(creator, receiver) }
        val originals = (1..9).map { expense(creator, receiver, it) }
        originals.forEach { original -> devices.forEach { it.ingest(original) } }
        val payload = Json.encodeToString(
            Settlement(
                "settlement-1",
                receiver.pubkey,
                creator.pubkey,
                100,
                "USD",
                timestamp = 100
            )
        )
        val first = receiver.event("settlement", payload, "settlement-1", 100)
        creator.ingest(first)
        if (!missingCovered) receiver.ingest(first)
        val snapshot = creator.snapshot(350)
        val retry = receiver.event("settlement", payload, "settlement-1", 300)
        assertFalse(first.id == retry.id)
        devices.forEach {
            it.ingest(snapshot)
            it.ingest(retry)
        }
        assertEquals(!missingCovered, receiver.events.getEventsByGroup(groupId).any { it.eventId == first.id })
        creator.assertNet(creator, 350, originals.last(), seeded = true)
        receiver.assertNet(creator, 350, originals.last(), seeded = !missingCovered)

        // The same id signed by the other party is a distinct settlement, including on fallback.
        val otherAuthor = creator.event("settlement", payload, "settlement-1", 400)
        devices.forEach { it.ingest(otherAuthor) }
        creator.assertNet(creator, 250, originals.last(), seeded = true)
        receiver.assertNet(creator, 250, originals.last(), seeded = !missingCovered)
    }

    @Test
    fun `missing covered correction replays to 550 instead of reversing the wrong revision`() = runBlocking {
        assertCorrectionReplay(missingCovered = true)
    }

    @Test
    fun `full coverage reverses the intermediate correction and seeds 550`() = runBlocking {
        assertCorrectionReplay(missingCovered = false)
    }

    @Test
    fun `missing covered settlement does not count its same-author retry twice`() = runBlocking {
        assertSettlementReplay(missingCovered = true)
    }

    @Test
    fun `full coverage seeds settlement deduplication by author`() = runBlocking {
        assertSettlementReplay(missingCovered = false)
    }
}
