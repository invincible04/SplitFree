package com.splitfree.domain.usecase.expense

import android.app.Application
import androidx.room.Room
import com.splitfree.data.local.AppDatabase
import com.splitfree.data.local.entities.EventEntity
import com.splitfree.data.repository.EventRepository
import com.splitfree.data.util.CompressionUtil
import com.splitfree.domain.crypto.EventSigner
import com.splitfree.domain.crypto.GroupEncryption
import com.splitfree.domain.crypto.NostrEvent
import com.splitfree.domain.model.balance.BalanceSnapshot
import com.splitfree.domain.model.group.Group
import com.splitfree.domain.model.group.RetiredIdentities
import com.splitfree.domain.repository.EventPublisherContract
import com.splitfree.domain.repository.EventRepositoryContract
import com.splitfree.domain.repository.GroupRepositoryContract
import com.splitfree.domain.repository.IdentityContract
import com.splitfree.domain.util.HashUtil
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * Room, signing and NIP-44 encryption with a mocked publisher. A scripted insert after the ledger read
 * must remain outside snapshot coverage; snapshot-seeded and full replay must both count the late expense.
 * This exercises a deterministic interleaving, not concurrent writers or relay publication.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
class SnapshotLedgerRoomTest {
    private val secret = ByteArray(32) { 1 }
    private val author = NostrEvent.pubkeyFromPrivkey(secret)
    private val peer = NostrEvent.pubkeyFromPrivkey(ByteArray(32) { 2 })
    private val groupId = "snapshot-ledger-room"
    private val encryption = GroupEncryption(CompressionUtil)
    private val key = encryption.generateGroupKey()

    private lateinit var db: AppDatabase
    private lateinit var realRepo: EventRepository
    private lateinit var signer: EventSigner
    private val identity = mockk<IdentityContract>()
    private val groups = mockk<GroupRepositoryContract>()

    /** Row that the scripted repository commits right before the snapshot transaction opens. */
    private var arriving: EventEntity? = null

    /** Delegates to Room after inserting [arriving], if set, before the next transaction. */
    private lateinit var repo: EventRepositoryContract

    @Before
    fun setup() {
        db = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        realRepo = EventRepository(db, db.eventDao())
        repo = object : EventRepositoryContract by realRepo {
            override suspend fun <T> withTransaction(block: suspend () -> T): T {
                arriving?.let {
                    db.eventDao().insert(it)
                    arriving = null
                }
                return realRepo.withTransaction(block)
            }
        }
        every { identity.getPublicKeyHex() } returns author
        every { identity.getPrivateKeyBytes() } answers { secret.copyOf() }
        coEvery { groups.getById(groupId) } returns Group(
            groupId,
            "Room",
            createdBy = author,
            createdAt = 1,
            members = listOf(author, peer),
            relays = emptyList()
        )
        coEvery { groups.getGroupKeyForEpoch(groupId, 0) } returns key
        coEvery { groups.retiredIdentities(groupId) } returns RetiredIdentities.NONE
        signer = EventSigner(identity)
    }

    @After
    fun tearDown() = db.close()

    private fun expenseRow(index: Int): EventEntity {
        val id = "expense-$index"
        val content = """{"id":"$id","amount":100,"currency":"INR","description":"t","paid_by":"$author",""" +
            """"split_type":"equal","split_among":[{"pubkey":"$author","share":50},{"pubkey":"$peer","share":50}],""" +
            """"timestamp":100}"""
        val event = signer.createSignedEvent(groupId, "expense", encryption.encrypt(content, key), id)
        return EventEntity(
            eventId = event.id,
            groupId = groupId,
            pubkey = author,
            createdAt = event.createdAt,
            kind = event.kind,
            contentEncrypted = event.content,
            eventType = "expense",
            expenseUuid = id,
            sig = event.sig,
            receivedAt = 100,
            originalEventJson = event.toJson()
        )
    }

    @Test
    fun `snapshot cannot cover an expense whose amount it lacks`() = runBlocking {
        repeat(10) { db.eventDao().insert(expenseRow(it)) }
        val late = expenseRow(10)
        arriving = late
        val compute = ComputeBalancesUseCase(repo, groups, encryption)
        assertEquals(10, repo.getEventsByGroup(groupId).size)

        val publisher = mockk<EventPublisherContract>()
        coEvery { publisher.saveAndQueue(any(), groupId, any(), "snapshot", any()) } coAnswers {
            val event = firstArg<NostrEvent>()
            db.eventDao().insert(
                EventEntity(
                    eventId = event.id,
                    groupId = groupId,
                    pubkey = author,
                    createdAt = event.createdAt,
                    kind = event.kind,
                    contentEncrypted = event.content,
                    eventType = "snapshot",
                    expenseUuid = arg(4),
                    sig = event.sig,
                    receivedAt = 100,
                    originalEventJson = event.toJson()
                )
            )
            Unit
        }

        assertTrue(CreateSnapshotUseCase(repo, groups, compute, encryption, signer, publisher, identity)(groupId))

        val snapshot = checkNotNull(repo.getLatestEventByType(groupId, "snapshot"))
        val payload = Json.decodeFromString<BalanceSnapshot>(encryption.decrypt(snapshot.contentEncrypted, key))
        assertEquals(500L, payload.balances.single { it.pubkey == author }.net)
        assertEquals(10, payload.event_hashes.size)
        assertFalse(
            "The late expense landed after the ledger read and must not be hashed",
            HashUtil.eventHashPrefix(late.eventId) in payload.event_hashes
        )
        assertEquals(12, repo.getEventsByGroup(groupId).size)
        assertEquals("Snapshot-seeded balance counts the late expense", 550L, authorNet(compute))

        db.openHelper.writableDatabase.execSQL("DELETE FROM events WHERE eventType = 'snapshot'")
        assertEquals("Full replay agrees", 550L, authorNet(compute))
    }

    @Test
    fun `snapshot after the ledger settles covers everything and seeds the same balance`() = runBlocking {
        repeat(11) { db.eventDao().insert(expenseRow(it)) }
        val compute = ComputeBalancesUseCase(repo, groups, encryption)
        val publisher = mockk<EventPublisherContract>()
        coEvery { publisher.saveAndQueue(any(), groupId, any(), "snapshot", any()) } coAnswers {
            val event = firstArg<NostrEvent>()
            db.eventDao().insert(
                EventEntity(
                    eventId = event.id,
                    groupId = groupId,
                    pubkey = author,
                    createdAt = event.createdAt,
                    kind = event.kind,
                    contentEncrypted = event.content,
                    eventType = "snapshot",
                    expenseUuid = arg(4),
                    sig = event.sig,
                    receivedAt = 100,
                    originalEventJson = event.toJson()
                )
            )
            Unit
        }

        assertTrue(CreateSnapshotUseCase(repo, groups, compute, encryption, signer, publisher, identity)(groupId))

        val snapshot = checkNotNull(repo.getLatestEventByType(groupId, "snapshot"))
        val payload = Json.decodeFromString<BalanceSnapshot>(encryption.decrypt(snapshot.contentEncrypted, key))
        assertEquals(550L, payload.balances.single { it.pubkey == author }.net)
        assertEquals(11, payload.event_hashes.size)
        assertEquals(550L, authorNet(compute))
    }

    private suspend fun authorNet(compute: ComputeBalancesUseCase): Long =
        compute(groupId).single { it.pubkey == author }.net
}
