package com.splitfree.domain.usecase.export

import android.app.Application
import androidx.room.Room
import com.splitfree.data.local.AppDatabase
import com.splitfree.data.repository.EventRepository
import com.splitfree.data.repository.GroupRepository
import com.splitfree.data.util.CompressionUtil
import com.splitfree.domain.crypto.GroupEncryption
import com.splitfree.domain.crypto.NostrEvent
import com.splitfree.domain.crypto.nip.Nip44
import com.splitfree.domain.model.export.ExportedEvent
import com.splitfree.domain.model.export.SplitFreeExport
import com.splitfree.domain.model.group.Group
import com.splitfree.domain.model.group.GroupIdentity
import com.splitfree.domain.model.group.GroupMeta
import com.splitfree.domain.repository.EventSnapshot
import com.splitfree.domain.repository.IdentityContract
import com.splitfree.domain.repository.SecureStorageException
import com.splitfree.domain.usecase.expense.ComputeBalancesUseCase
import com.splitfree.domain.util.hexToBytes
import com.splitfree.domain.util.toHex
import com.splitfree.domain.validation.EventValidator
import com.splitfree.test.FakeSecureStorage
import io.mockk.every
import io.mockk.mockk
import java.util.Base64
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * Restore into an existing group over the real stack: [ImportGroupUseCase] on an in-memory Room database,
 * the real [GroupRepository] (epoch guard, immutable epoch keys, revocation tombstones), the real
 * author-qualified [EventRepository] lookups, real NIP-44 key wrapping and real BIP-340 signatures.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
class ImportGroupEpochRoomTest {
    private lateinit var db: AppDatabase
    private lateinit var groups: GroupRepository
    private lateinit var events: EventRepository
    private lateinit var importer: ImportGroupUseCase

    private val keyStore = FakeSecureStorage()
    private val encryption = GroupEncryption(CompressionUtil)
    private val privateKey = ByteArray(32) { 1 }
    private val author = NostrEvent.pubkeyFromPrivkey(privateKey)
    private val otherPrivateKey = ByteArray(32) { 2 }
    private val other = NostrEvent.pubkeyFromPrivkey(otherPrivateKey)
    private val successor = NostrEvent.pubkeyFromPrivkey(ByteArray(32) { 3 })
    private val now = System.currentTimeMillis() / 1000
    private val createdAt = now - 1000
    private val groupId = GroupIdentity.derive(author, createdAt)
    private val key0 = key(7)
    private val key1 = key(8)
    private val key2 = key(9)
    private val identity = mockk<IdentityContract> {
        every { getPrivateKeyBytes() } answers { privateKey.copyOf() }
        every { getPublicKeyBytes() } returns author.hexToBytes()
        every { getPublicKeyHex() } returns author
    }

    @Before
    fun setup() = runBlocking {
        db = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        groups = GroupRepository(db.groupDao(), keyStore)
        events = EventRepository(db, db.eventDao())
        importer = ImportGroupUseCase(events, groups, encryption, EventValidator(), identity)
        groups.save(
            Group(
                id = groupId,
                name = "Existing",
                createdBy = author,
                createdAt = createdAt,
                members = listOf(author, other),
                relays = emptyList()
            ),
            key0
        )
    }

    @After
    fun teardown() = db.close()

    private fun key(value: Byte) = Base64.getEncoder().encodeToString(ByteArray(32) { value })

    private fun keyFor(epoch: Int) = when (epoch) {
        0 -> key0
        1 -> key1
        else -> key2
    }

    /** A signed `expense` (or [type] mutation of it) by [signer], sealed under the key of [epoch]. */
    private fun expense(
        uuid: String,
        epoch: Int,
        signer: ByteArray = privateKey,
        amount: Long = 100,
        type: String = "expense",
        offset: Int = 0
    ): EventSnapshot {
        val pubkey = NostrEvent.pubkeyFromPrivkey(signer)
        val content = encryption.encrypt(
            """{"id":"$uuid","amount":$amount,"currency":"INR","description":"test","paid_by":"$pubkey",""" +
                """"split_type":"equal","split_among":[{"pubkey":"$author","share":${amount / 2}},""" +
                """{"pubkey":"$other","share":${amount / 2}}],"timestamp":${now - 100 + offset}}""",
            keyFor(epoch)
        )
        val signed = NostrEvent(
            pubkey = pubkey,
            createdAt = now - 100 + offset,
            kind = 30078,
            tags = listOf(listOf("g", groupId), listOf("t", type), listOf("x", uuid)),
            content = content
        ).sign(signer)
        return EventSnapshot(
            signed.id, groupId, pubkey, signed.createdAt, contentEncrypted = content,
            eventType = type, expenseUuid = uuid, sig = signed.sig,
            originalEventJson = signed.toJson(), keyEpoch = epoch
        )
    }

    /** A creator `group_meta` sealed under the key of [epoch]. */
    private fun creatorMeta(members: List<String>, name: String, epoch: Int, timestamp: Long): EventSnapshot {
        val meta = GroupMeta(name = name, createdBy = author, createdAt = createdAt, members = members)
        val content = encryption.encrypt(Json.encodeToString(GroupMeta.serializer(), meta), keyFor(epoch))
        val signed = NostrEvent(
            pubkey = author,
            createdAt = timestamp,
            kind = 30078,
            tags = listOf(listOf("g", groupId), listOf("t", "group_meta")),
            content = content
        ).sign(privateKey)
        return EventSnapshot(
            signed.id, groupId, author, timestamp, contentEncrypted = content,
            eventType = "group_meta", sig = signed.sig, originalEventJson = signed.toJson(), keyEpoch = epoch
        )
    }

    private fun encryptedKey(key: String): String =
        Nip44.encrypt(key, Nip44.getConversationKey(privateKey, author.hexToBytes()))

    private fun authenticate(export: SplitFreeExport): SplitFreeExport =
        export.copy(hmac = ExportMac.compute(export, privateKey).toHex())

    /** An authenticated backup at [epoch] carrying every key up to it, as [ExportGroupUseCase] writes one. */
    private fun backup(epoch: Int = 1, rows: List<EventSnapshot> = emptyList()): SplitFreeExport = authenticate(
        SplitFreeExport(
            groupId = groupId,
            exportedAt = now,
            keyEpoch = epoch,
            encryptedGroupKey = encryptedKey(keyFor(epoch)),
            encryptedEpochKeys = (0..epoch).associate { it.toString() to encryptedKey(keyFor(it)) },
            events = rows.map {
                ExportedEvent(
                    eventId = it.eventId, pubkey = it.pubkey, createdAt = it.createdAt, kind = it.kind,
                    contentEncrypted = it.contentEncrypted, eventType = it.eventType, expenseUuid = it.expenseUuid,
                    sig = it.sig, originalEventJson = it.originalEventJson, keyEpoch = it.keyEpoch
                )
            }
        )
    )

    private suspend fun net(pubkey: String): Long =
        ComputeBalancesUseCase(events, groups, encryption)(groupId).single { it.pubkey == pubkey }.net

    // --- epoch and key reconciliation ---

    @Test
    fun `a newer authenticated backup advances the epoch and keeps both epochs readable`() = runBlocking {
        val old = expense("e-old", 0)
        val newer = expense("e-new", 1, offset = 1)
        events.insert(old)

        assertEquals(1, importer(backup(rows = listOf(old, newer))))

        assertEquals(1, groups.getById(groupId)!!.keyEpoch)
        assertEquals(key1, groups.getGroupKey(groupId))
        assertEquals(key0, groups.getGroupKeyForEpoch(groupId, 0))
        assertEquals(listOf(author, other), groups.getMembers(groupId))
        assertEquals(setOf(old.eventId, newer.eventId), events.getEventIds(groupId).toSet())
        assertEquals(100L, net(author))
        // Re-importing the same backup is a no-op.
        assertEquals(0, importer(backup(rows = listOf(old, newer))))
        assertEquals(2, events.getEventCount(groupId))
    }

    @Test
    fun `an older backup restores a missing historical key without downgrading the active epoch`() = runBlocking {
        groups.saveGroupKeyForEpoch(groupId, 2, key2)
        assertTrue(groups.applyKeyRotation(groupId, 2, listOf(author, other), emptyMap()))
        events.insert(expense("e-2", 2))

        assertEquals(1, importer(backup(epoch = 1, rows = listOf(expense("e-1", 1)))))

        assertEquals(2, groups.getById(groupId)!!.keyEpoch)
        assertEquals(key2, groups.getGroupKey(groupId))
        assertEquals(key1, groups.getGroupKeyForEpoch(groupId, 1))
        assertEquals(key0, groups.getGroupKeyForEpoch(groupId, 0))
        assertEquals(100L, net(author))
    }

    @Test
    fun `a conflicting key for a stored epoch rejects the whole merge without overwriting history`() = runBlocking {
        val before = snapshotKeys()
        val export = authenticate(
            backup().copy(encryptedEpochKeys = mapOf("1" to encryptedKey(key1), "0" to encryptedKey(key2)))
        )

        val e = assertThrows(IllegalArgumentException::class.java) { runBlocking { importer(export) } }

        assertTrue(e.message!!.contains("different key for epoch 0"))
        assertEquals(before, snapshotKeys())
        assertEquals(0, groups.getById(groupId)!!.keyEpoch)
        assertEquals(0, events.getEventCount(groupId))
    }

    @Test
    fun `a newer backup without its epoch key never reuses the existing older key`() = runBlocking {
        val export = authenticate(backup().copy(encryptedGroupKey = "", encryptedEpochKeys = emptyMap()))

        assertThrows(IllegalStateException::class.java) { runBlocking { importer(export) } }

        assertEquals(0, groups.getById(groupId)!!.keyEpoch)
        assertEquals(key0, groups.getGroupKey(groupId))
    }

    @Test
    fun `a failed key write cannot advance the epoch or store events`() = runBlocking {
        keyStore.failNextPut = true

        assertThrows(SecureStorageException::class.java) {
            runBlocking { importer(backup(rows = listOf(expense("e-new", 1)))) }
        }

        assertEquals(0, groups.getById(groupId)!!.keyEpoch)
        assertEquals(key0, groups.getGroupKey(groupId))
        assertEquals(0, events.getEventCount(groupId))
    }

    @Test
    fun `a revoked identity named by the backup's roster does not come back`() = runBlocking {
        val revokedAt = now - 500
        assertTrue(groups.applyIdentityRevocation(groupId, other, successor, revokedAt, "revocation-1"))
        assertEquals(listOf(author, successor), groups.getMembers(groupId))
        // The creator's newest roster predates its knowledge of the revocation and still names the old key.
        val meta = creatorMeta(listOf(author, other), "Restored", epoch = 0, timestamp = revokedAt + 10)

        assertEquals(1, importer(backup(epoch = 0, rows = listOf(meta))))

        val group = groups.getById(groupId)!!
        assertEquals("Restored", group.name)
        assertEquals(listOf(author, successor), group.members)
        assertFalse(other in group.members)
    }

    private fun snapshotKeys(): Map<String, String?> =
        (0..2).flatMap { listOf("$groupId:$it") }.plus(groupId).associateWith { keyStore.getString(it, null) }
}
