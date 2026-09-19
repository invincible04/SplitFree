package com.splitfree.domain.usecase.export

import android.app.Application
import androidx.room.Room
import com.splitfree.data.identity.IdentityManager
import com.splitfree.data.local.AppDatabase
import com.splitfree.data.repository.EventRepository
import com.splitfree.data.repository.GroupRepository
import com.splitfree.data.util.CompressionUtil
import com.splitfree.domain.crypto.EventSigner
import com.splitfree.domain.crypto.GroupEncryption
import com.splitfree.domain.crypto.NostrEvent
import com.splitfree.domain.crypto.nip.Nip44
import com.splitfree.domain.model.expense.Expense
import com.splitfree.domain.model.expense.SplitEntry
import com.splitfree.domain.model.expense.SplitType
import com.splitfree.domain.model.export.SplitFreeExport
import com.splitfree.domain.model.group.Group
import com.splitfree.domain.model.group.GroupIdentity
import com.splitfree.domain.model.group.GroupMeta
import com.splitfree.domain.repository.EventRepositoryContract
import com.splitfree.domain.repository.EventSnapshot
import com.splitfree.domain.repository.GroupRepositoryContract
import com.splitfree.domain.repository.IdentityContract
import com.splitfree.domain.usecase.export.ExportGroupUseCase
import com.splitfree.domain.usecase.export.ImportGroupUseCase
import com.splitfree.domain.validation.EventValidator
import com.splitfree.test.FakeSecureStorage
import java.util.Base64
import java.util.concurrent.Executor
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
class ExportSnapshotRoomTest {
    private val stores = mutableListOf<ExportRoomStore>()
    private val createdAt = System.currentTimeMillis() / 1000 - 1000
    private val key0 = Base64.getEncoder().encodeToString(ByteArray(32) { 7 })
    private val key1 = Base64.getEncoder().encodeToString(ByteArray(32) { 8 })

    @After
    fun cleanup() = stores.forEach { it.db.close() }

    @Test
    fun stableEpochExportRoundTripsWithRealKeysAndEvents() = runBlocking {
        val source = source()
        val groupId = GroupIdentity.derive(source.pub, createdAt)
        val backup = ExportGroupUseCase(source.events, source.groups, source.identity)(groupId)
        val exported = Json.decodeFromString<SplitFreeExport>(backup)
        assertEquals(0, exported.keyEpoch)
        assertEquals(key0, unwrap(source, exported.encryptedGroupKey))
        assertEquals(key0, unwrap(source, exported.encryptedEpochKeys.getValue("0")))
        assertRoundTrip(source, groupId, backup)
    }

    @Test
    fun pendingControlIsExportedAndReauthenticatedOnRestoreWithoutTrustingLocalApplyState() = runBlocking {
        val source = source()
        val groupId = GroupIdentity.derive(source.pub, createdAt)
        val control = source.events.getEventsByType(groupId, "group_meta").single()
        source.db.eventDao().setApplyState(control.eventId, 1)
        val backup = ExportGroupUseCase(source.events, source.groups, source.identity)(groupId)
        val captured = Json.decodeFromString<SplitFreeExport>(backup)
        assertEquals(2, captured.events.size)
        assertTrue(captured.events.any { it.eventId == control.eventId })
        assertEquals(1, source.db.eventDao().getEvent(control.eventId)!!.applyState)
        val target = store()
        assertEquals(
            2,
            ImportGroupUseCase(
                target.events,
                target.groups,
                target.encryption,
                EventValidator(),
                target.identity
            )(backup)
        )
        assertEquals(0, target.db.eventDao().getEvent(control.eventId)!!.applyState)
        assertEquals(source.groups.getById(groupId)?.members, target.groups.getById(groupId)?.members)
        assertEquals(captured.events.map { it.eventId }.toSet(), target.events.getEventIds(groupId).toSet())
    }

    @Test
    fun rotationBetweenGroupSnapshotAndLiveKeyReadStillProducesRestorableExport() = runBlocking {
        val source = source()
        val groupId = GroupIdentity.derive(source.pub, createdAt)
        var rotated = false
        val interleaving = object : GroupRepositoryContract by source.groups {
            override suspend fun getById(groupId: String): Group? {
                val snapshot = source.groups.getById(groupId)
                if (!rotated && snapshot != null) {
                    rotated = true
                    assertEquals(0, snapshot.keyEpoch)
                    source.groups.saveGroupKeyForEpoch(groupId, 1, key1)
                    assertTrue(source.groups.applyKeyRotation(groupId, 1, snapshot.members, snapshot.memberNames))
                    assertEquals(1, source.groups.getById(groupId)?.keyEpoch)
                }
                return snapshot
            }
        }

        val backup = ExportGroupUseCase(source.events, interleaving, source.identity)(groupId)
        assertTrue("The controlled real repository rotation must execute", rotated)
        assertEquals(key0, source.groups.getGroupKeyForEpoch(groupId, 0))
        assertEquals(key1, source.groups.getGroupKey(groupId))
        assertRoundTrip(source, groupId, backup)
    }

    @Test
    fun snapshotUsesOneRoomTransactionAndExcludesNewEpochExpenseCommittedAfterCapture() = runBlocking {
        val source = source()
        val groupId = GroupIdentity.derive(source.pub, createdAt)
        val started = CompletableDeferred<Unit>()
        val scope = CoroutineScope(Dispatchers.Default)
        var writer: kotlinx.coroutines.Deferred<Unit>? = null
        val interleaving = object : GroupRepositoryContract by source.groups {
            override suspend fun getById(groupId: String): Group? {
                assertTrue("Metadata read must share the event snapshot transaction", source.db.inTransaction())
                val captured = source.groups.getById(groupId)
                writer = scope.async {
                    started.complete(Unit)
                    rotate(source, groupId, addExpense = true)
                }
                started.await()
                return captured
            }

            override suspend fun getGroupKeyForEpoch(groupId: String, epoch: Int): String? {
                assertFalse("Key reads and encryption must not hold the Room transaction", source.db.inTransaction())
                writer!!.await()
                return source.groups.getGroupKeyForEpoch(groupId, epoch)
            }

            override suspend fun getGroupKey(groupId: String): String? = error("Live-current key read forbidden")
        }
        try {
            val backup = ExportGroupUseCase(source.events, interleaving, source.identity)(groupId)
            val captured = Json.decodeFromString<SplitFreeExport>(backup)
            assertEquals(0, captured.keyEpoch)
            assertEquals(2, captured.events.size)
            assertEquals(3, source.events.getEventCount(groupId))
            assertCapturedRoundTrip(backup)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun rotationAndNewEpochExpenseBeforeCaptureRoundTrip() = runBlocking {
        val source = source()
        val groupId = GroupIdentity.derive(source.pub, createdAt)
        rotate(source, groupId, addExpense = true)
        val backup = ExportGroupUseCase(source.events, source.groups, source.identity)(groupId)
        val captured = Json.decodeFromString<SplitFreeExport>(backup)
        assertEquals(1, captured.keyEpoch)
        assertEquals(3, captured.events.size)
        assertEquals(setOf("0", "1"), captured.encryptedEpochKeys.keys)
        assertCapturedRoundTrip(backup)
    }

    @Test
    fun newEpochEventWithoutMatchingGroupSnapshotIsRefused() = runBlocking {
        val source = source()
        val groupId = GroupIdentity.derive(source.pub, createdAt)
        val interleaving = object : GroupRepositoryContract by source.groups {
            override suspend fun getById(groupId: String): Group? {
                val captured = source.groups.getById(groupId)
                rotate(source, groupId, addExpense = true)
                return captured
            }
        }
        val failure = runCatching { ExportGroupUseCase(source.events, interleaving, source.identity)(groupId) }
        assertTrue(failure.exceptionOrNull() is IllegalArgumentException)
        assertTrue(failure.exceptionOrNull()!!.message!!.contains("key epoch"))
    }

    @Test
    fun missingHistoricalKeyRefusesBackupWithoutMutatingSource() = runBlocking {
        val source = source()
        val groupId = GroupIdentity.derive(source.pub, createdAt)
        rotate(source, groupId, addExpense = true)
        val before = source.events.getEventIds(groupId)
        source.keys.remove("$groupId:0")
        source.keys.remove(groupId)
        val failure = runCatching { ExportGroupUseCase(source.events, source.groups, source.identity)(groupId) }
        assertTrue(failure.exceptionOrNull() is IllegalStateException)
        assertEquals(before, source.events.getEventIds(groupId))
        assertEquals(1, source.groups.getById(groupId)!!.keyEpoch)
    }

    @Test
    fun deletingKeysAfterSnapshotRefusesBackup() = runBlocking {
        val source = source()
        val groupId = GroupIdentity.derive(source.pub, createdAt)
        val interleaving = object : GroupRepositoryContract by source.groups {
            override suspend fun getGroupKeyForEpoch(groupId: String, epoch: Int): String? {
                source.groups.deleteGroupKey(groupId)
                return source.groups.getGroupKeyForEpoch(groupId, epoch)
            }
        }
        assertTrue(
            runCatching {
                ExportGroupUseCase(source.events, interleaving, source.identity)(groupId)
            }.exceptionOrNull() is IllegalStateException
        )
    }

    @Test
    fun deletingGroupBeforeCaptureRefusesBackup() = runBlocking {
        val source = source()
        val groupId = GroupIdentity.derive(source.pub, createdAt)
        source.db.groupDao().delete(checkNotNull(source.db.groupDao().getById(groupId)))
        assertTrue(
            runCatching {
                ExportGroupUseCase(source.events, source.groups, source.identity)(groupId)
            }.exceptionOrNull() is IllegalStateException
        )
    }

    @Test
    fun identityReplacementBetweenCaptureAndEncryptionRefusesAndZeroesBorrowedKey() = runBlocking {
        val source = source()
        val groupId = GroupIdentity.derive(source.pub, createdAt)
        val borrowed = source.identity.getPrivateKeyBytes()
        val identity = object : IdentityContract by source.identity {
            override fun getPrivateKeyBytes(): ByteArray = borrowed
        }
        val interleaving = object : GroupRepositoryContract by source.groups {
            override suspend fun getGroupKeyForEpoch(groupId: String, epoch: Int): String? {
                source.identity.importKey("00".repeat(31) + "02")
                return source.groups.getGroupKeyForEpoch(groupId, epoch)
            }
        }
        val failure = runCatching { ExportGroupUseCase(source.events, interleaving, identity)(groupId) }
        assertTrue(failure.exceptionOrNull()!!.message!!.contains("Identity changed"))
        assertTrue(borrowed.all { it == 0.toByte() })
    }

    @Test
    fun fileSessionRefusesSecondGroupAfterIdentityReplacementAndZeroesOnClose() = runBlocking {
        val source = source()
        val groupId = GroupIdentity.derive(source.pub, createdAt)
        val exporter = ExportGroupUseCase(source.events, source.groups, source.identity)
        val session = exporter.openSession()
        session.use {
            assertCapturedRoundTrip(exporter(groupId, it))
            source.identity.importKey("00".repeat(31) + "02")
            assertTrue(runCatching { exporter(groupId, it) }.exceptionOrNull() is IllegalStateException)
        }
        assertTrue(session.privateKey.all { it == 0.toByte() })
        assertTrue(runCatching { session.requireCurrentIdentity() }.isFailure)
    }

    @Test
    fun cancellationDuringCapturePropagatesAndZeroesIdentityWithoutMutation() = runBlocking {
        val source = source()
        val groupId = GroupIdentity.derive(source.pub, createdAt)
        val borrowed = source.identity.getPrivateKeyBytes()
        val identity = object : IdentityContract by source.identity {
            override fun getPrivateKeyBytes(): ByteArray = borrowed
        }
        val interrupted = object : EventRepositoryContract by source.events {
            override suspend fun getExportableEvents(groupId: String): List<EventSnapshot> =
                throw CancellationException("interrupted capture")
        }
        assertTrue(
            runCatching {
                ExportGroupUseCase(interrupted, source.groups, identity)(groupId)
            }.exceptionOrNull() is CancellationException
        )
        assertTrue(borrowed.all { it == 0.toByte() })
        assertEquals(2, source.events.getEventCount(groupId))
    }

    @Test
    fun capturedPrivateKeyDerivesEncryptionRecipientWithoutIndependentPublicKeyRead() = runBlocking {
        val source = source()
        val groupId = GroupIdentity.derive(source.pub, createdAt)
        val identity = object : IdentityContract by source.identity {
            override fun getPublicKeyBytes(): ByteArray = error("Independent public-key read forbidden")
        }
        assertCapturedRoundTrip(ExportGroupUseCase(source.events, source.groups, identity)(groupId))
    }

    @Test
    fun failedSessionInitializationZeroesKeyWhenIdentityChangedAfterBorrow() = runBlocking {
        val source = source()
        val borrowed = source.identity.getPrivateKeyBytes()
        val identity = object : IdentityContract by source.identity {
            override fun getPrivateKeyBytes(): ByteArray {
                source.identity.importKey("00".repeat(31) + "02")
                return borrowed
            }
        }
        val exporter = ExportGroupUseCase(source.events, source.groups, identity)
        val failure = runCatching { exporter.openSession() }.exceptionOrNull()
        assertTrue(failure is IllegalStateException)
        assertTrue(failure!!.message!!.contains("Identity changed"))
        assertTrue(borrowed.all { it == 0.toByte() })
    }

    @Test
    fun malformedSnapshotEpochKeyRefusesInsteadOfAuthenticatingCorruptBackup() = runBlocking {
        val source = source()
        val groupId = GroupIdentity.derive(source.pub, createdAt)
        source.keys.putString("$groupId:0", "not a group key")
        val failure = runCatching {
            ExportGroupUseCase(source.events, source.groups, source.identity)(groupId)
        }.exceptionOrNull()
        assertTrue(failure is IllegalArgumentException)
        assertEquals(2, source.events.getEventCount(groupId))
    }

    private suspend fun rotate(source: ExportRoomStore, groupId: String, addExpense: Boolean) {
        source.groups.saveGroupKeyForEpoch(groupId, 1, key1)
        source.events.withTransaction {
            val group = checkNotNull(source.groups.getById(groupId))
            assertTrue(source.groups.applyKeyRotation(groupId, 1, group.members, group.memberNames))
            if (addExpense) {
                val expense = Expense(
                    "00000000-0000-4000-8000-000000000002",
                    200,
                    "INR",
                    "New epoch expense",
                    source.pub,
                    SplitType.EQUAL,
                    listOf(SplitEntry(source.pub, 200)),
                    createdAt + 4
                )
                val event = source.signed(
                    groupId,
                    "expense",
                    Json.encodeToString(expense),
                    key1,
                    createdAt + 4,
                    expense.id
                )
                source.events.insert(
                    EventSnapshot(
                        event.id, groupId, source.pub, event.createdAt, contentEncrypted = event.content,
                        eventType = "expense", expenseUuid = expense.id, sig = event.sig,
                        originalEventJson = event.toJson(), keyEpoch = 1
                    )
                )
            }
        }
    }

    private suspend fun assertCapturedRoundTrip(backup: String) {
        val target = store()
        val captured = Json.decodeFromString<SplitFreeExport>(backup)
        val count = ImportGroupUseCase(
            target.events,
            target.groups,
            target.encryption,
            EventValidator(),
            target.identity
        )(backup)
        assertEquals(captured.events.size, count)
        assertEquals(captured.events.map { it.eventId }.toSet(), target.events.getEventIds(captured.groupId).toSet())
        for (row in target.events.getEventsByGroup(captured.groupId)) {
            val key = target.groups.getGroupKeyForEpoch(captured.groupId, row.keyEpoch)
            assertNotNull(key)
            assertTrue(target.encryption.decrypt(row.contentEncrypted, key!!).isNotBlank())
        }
    }

    private fun store() = ExportRoomStore(1).also { stores += it }

    private suspend fun source(): ExportRoomStore {
        val source = store()
        val groupId = GroupIdentity.derive(source.pub, createdAt)
        source.groups.save(
            Group(
                groupId,
                "Backup regression",
                createdBy = source.pub,
                createdAt = createdAt,
                members = listOf(source.pub),
                relays = emptyList()
            ),
            key0
        )
        val meta = GroupMeta(
            "Backup regression",
            createdBy = source.pub,
            createdAt = createdAt,
            members = listOf(source.pub),
            relays = emptyList()
        )
        val expense = Expense(
            "00000000-0000-4000-8000-000000000001",
            100,
            "INR",
            "Backup expense",
            source.pub,
            SplitType.EQUAL,
            listOf(SplitEntry(source.pub, 100)),
            createdAt + 2
        )
        val records = listOf(
            "group_meta" to source.signed(groupId, "group_meta", Json.encodeToString(meta), key0, createdAt + 1),
            "expense" to source.signed(
                groupId,
                "expense",
                Json.encodeToString(expense),
                key0,
                createdAt + 2,
                expense.id
            )
        )
        for ((type, event) in records) {
            assertTrue(event.verify())
            source.events.insert(
                EventSnapshot(
                    eventId = event.id, groupId = groupId, pubkey = event.pubkey, createdAt = event.createdAt,
                    contentEncrypted = event.content, eventType = type,
                    expenseUuid = if (type == "expense") expense.id else null,
                    sig = event.sig, originalEventJson = event.toJson(), keyEpoch = 0
                )
            )
        }
        return source
    }

    private suspend fun assertRoundTrip(source: ExportRoomStore, groupId: String, backup: String) {
        val target = store()
        val exported = Json.decodeFromString<SplitFreeExport>(backup)
        val currentKey = unwrap(source, exported.encryptedGroupKey)
        val listedKey = exported.encryptedEpochKeys[exported.keyEpoch.toString()]?.let { unwrap(source, it) }
        val imported = runCatching {
            ImportGroupUseCase(
                target.events,
                target.groups,
                target.encryption,
                EventValidator(),
                target.identity
            )(backup)
        }
        assertTrue(
            "An app-produced backup must round-trip: snapshotEpoch=${exported.keyEpoch}, " +
                "liveEpoch=${source.groups.getById(groupId)?.keyEpoch}, " +
                "currentKeyIsK1=${currentKey == key1}, listedKeyIsK0=${listedKey == key0}; " +
                "importFailure=${imported.exceptionOrNull()}",
            imported.isSuccess
        )
        assertEquals(2, imported.getOrThrow())
        assertEquals(source.events.getEventIds(groupId).toSet(), target.events.getEventIds(groupId).toSet())
        assertEquals(key0, target.groups.getGroupKeyForEpoch(groupId, 0))
        val restored = target.events.getEventsByType(groupId, "expense").single()
        assertEquals(
            "Backup expense",
            Json.decodeFromString<Expense>(
                target.encryption.decrypt(restored.contentEncrypted, key0)
            ).description
        )
    }

    private fun unwrap(store: ExportRoomStore, ciphertext: String): String {
        val privateKey = store.identity.getPrivateKeyBytes()
        try {
            val conversationKey = Nip44.getConversationKey(privateKey, store.identity.getPublicKeyBytes())
            try {
                return Nip44.decrypt(ciphertext, conversationKey)
            } finally {
                conversationKey.fill(0)
            }
        } finally {
            privateKey.fill(0)
        }
    }
}

// Shared offline fixture: Room and identity/crypto are real; only hardware-backed storage is substituted.
internal class ExportRoomStore(seed: Int) {
    val app: Application = RuntimeEnvironment.getApplication()
    val identity = IdentityManager(app, FakeSecureStorage()).apply {
        importKey("00".repeat(31) + seed.toString(16).padStart(2, '0'))
    }
    val pub get() = identity.getPublicKeyHex()
    val db = Room.inMemoryDatabaseBuilder(app, AppDatabase::class.java)
        .addCallback(AppDatabase.SYNC_REVISION_CALLBACK)
        .allowMainThreadQueries()
        .setQueryExecutor(Executor { it.run() })
        .setTransactionExecutor(Executor { it.run() })
        .build()
    val keys = FakeSecureStorage()
    val groups = GroupRepository(db.groupDao(), keys)
    val events = EventRepository(db, db.eventDao())
    val encryption = GroupEncryption(CompressionUtil)
    val signer = EventSigner(identity)

    fun signed(
        groupId: String,
        type: String,
        plaintext: String,
        key: String,
        at: Long,
        uuid: String? = null
    ): NostrEvent =
        signer.createSignedEvent(groupId, type, encryption.encrypt(plaintext, key), expenseUuid = uuid, createdAt = at)
}
