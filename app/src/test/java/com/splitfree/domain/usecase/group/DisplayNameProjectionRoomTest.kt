package com.splitfree.domain.usecase.group

import android.app.Application
import androidx.room.Room
import com.splitfree.data.local.AppDatabase
import com.splitfree.data.local.entities.GroupEntity
import com.splitfree.data.repository.ControlOperationJournal
import com.splitfree.data.repository.DisplayNameRepository
import com.splitfree.data.repository.EventRepository
import com.splitfree.data.repository.GroupRepository
import com.splitfree.data.settings.UserPreferences
import com.splitfree.data.util.CompressionUtil
import com.splitfree.domain.crypto.EventSigner
import com.splitfree.domain.crypto.NostrEvent
import com.splitfree.domain.model.group.Group
import com.splitfree.domain.model.group.GroupIdentity
import com.splitfree.domain.model.group.GroupMeta
import com.splitfree.domain.model.group.GroupProjection
import com.splitfree.domain.repository.IdentityContract
import com.splitfree.domain.usecase.export.ExportGroupUseCase
import com.splitfree.domain.usecase.export.ImportGroupUseCase
import com.splitfree.domain.util.hexToBytes
import com.splitfree.domain.validation.EventValidator
import com.splitfree.sync.event.EventPublisher
import com.splitfree.test.FakeSecureStorage
import io.mockk.every
import io.mockk.mockk
import java.util.Base64
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
class DisplayNameProjectionRoomTest {
    private val databases = mutableListOf<AppDatabase>()
    private val encryption = com.splitfree.domain.crypto.GroupEncryption(CompressionUtil)
    private val creatorKey = ByteArray(32) { 1 }
    private val memberKey = ByteArray(32) { 2 }
    private val creator = NostrEvent.pubkeyFromPrivkey(creatorKey)
    private val member = NostrEvent.pubkeyFromPrivkey(memberKey)
    private val createdAt = System.currentTimeMillis() / 1000 - 100
    private val groupId = GroupIdentity.derive(creator, createdAt)
    private val key = Base64.getEncoder().encodeToString(ByteArray(32) { 7 })
    private val group = Group(
        groupId,
        "Trip",
        "Description",
        creator,
        createdAt,
        listOf(creator, member),
        emptyList(),
        mapOf(creator to "Creator", member to "Member")
    )

    @After fun cleanup() = databases.forEach { it.close() }

    @Test fun `creator name facts and clocks survive reimport and fresh restore`() = runBlocking {
        assertRoundTrip(creatorKey)
    }

    @Test fun `member name facts and clocks survive reimport and fresh restore`() = runBlocking {
        assertRoundTrip(memberKey)
    }

    private fun database(): AppDatabase = Room.inMemoryDatabaseBuilder(
        RuntimeEnvironment.getApplication(),
        AppDatabase::class.java
    ).allowMainThreadQueries().build().also { databases += it }

    private fun identity(privateKey: ByteArray): IdentityContract {
        val pubkey = NostrEvent.pubkeyFromPrivkey(privateKey)
        return mockk<IdentityContract>(relaxed = true) {
            every { hasIdentity() } returns true
            every { hasPendingKeyPair() } returns false
            every { stagedIdentitySwitch() } returns null
            every { getPublicKeyHex() } returns pubkey
            every { getPublicKeyBytes() } returns pubkey.hexToBytes()
            every { getPrivateKeyBytes() } answers { privateKey.copyOf() }
        }
    }

    private suspend fun assertRoundTrip(privateKey: ByteArray) {
        val active = identity(privateKey)
        val author = active.getPublicKeyHex()
        val app = RuntimeEnvironment.getApplication()
        app.getSharedPreferences("splitfree_settings", 0).edit().clear().commit()
        val settings = UserPreferences(app)
        val db = database()
        val groups = GroupRepository(db.groupDao(), FakeSecureStorage())
        val events = EventRepository(db, db.eventDao())
        val names = DisplayNameRepository(db.displayNameDao(), db)
        val publisher = EventPublisher(
            db.eventDao(), db.outboxDao(), db.deliveryDao(), mockk(relaxed = true), mockk(relaxed = true),
            mockk(relaxed = true), groups, active, db, settings
        )
        groups.save(group, key)
        val genesisMeta = GroupMeta(
            group.name,
            group.description,
            group.createdBy,
            group.createdAt,
            group.members,
            group.relays,
            group.memberNames
        )
        val genesis = EventSigner(identity(creatorKey)).createSignedEvent(
            groupId,
            "group_meta",
            encryption.encrypt(Json.encodeToString(GroupMeta.serializer(), genesisMeta), key),
            createdAt = createdAt
        )
        assertTrue(groups.applyAuthenticatedMeta(groupId, genesisMeta, creator, genesis.createdAt, genesis.id, 0))
        publisher.publishDirect(genesis, groupId, genesis.content, "group_meta")
        val update = UpdateDisplayNameUseCase(
            groups, active, encryption, EventSigner(active), publisher, settings, names, ControlOperationLock(),
            ControlOperationJournal(db.controlOperationDao())
        )
        val intent = settings.saveDisplayNameIntent(author, "Renamed")
        assertFalse(update(intent).needsRetry)
        val nameEventId = checkNotNull(names.committedEventId(intent, groupId))
        val nameEvent = checkNotNull(db.eventDao().getEvent(nameEventId))
        val before = checkNotNull(db.groupDao().getById(groupId))
        val facts = Json.decodeFromString<GroupProjection>(before.projectionJson).facts
        val fact = facts.single { it.id == nameEventId }
        assertEquals("meta", fact.kind)
        assertEquals("Renamed", fact.meta!!.memberNames[author])
        assertEquals(listOf(creator, member), groups.getById(groupId)!!.members)
        assertEquals(if (author == creator) nameEvent.createdAt else genesis.createdAt, before.lastMetaTimestamp)
        assertEquals(if (author == creator) nameEventId else genesis.id, before.lastMetaEventId)
        assertTrue(checkNotNull(NostrEvent.fromJson(nameEvent.originalEventJson!!)).verify())
        val backup = ExportGroupUseCase(events, groups, active)(groupId)
        val importer = ImportGroupUseCase(events, groups, encryption, EventValidator(), active)
        repeat(2) {
            assertEquals(0, importer(backup))
            assertEquals(before, db.groupDao().getById(groupId))
            assertEquals(2, events.getEventsByGroup(groupId).size)
        }
        val restoredDb = database()
        val restoredGroups = GroupRepository(restoredDb.groupDao(), FakeSecureStorage())
        val restoredEvents = EventRepository(restoredDb, restoredDb.eventDao())
        val restore = ImportGroupUseCase(restoredEvents, restoredGroups, encryption, EventValidator(), active)
        assertEquals(2, restore(backup))
        val restored = checkNotNull(restoredDb.groupDao().getById(groupId))
        assertEquals(groups.getById(groupId), restoredGroups.getById(groupId))
        assertProjectionEquals(before, restored)
        assertEquals(facts.toSet(), Json.decodeFromString<GroupProjection>(restored.projectionJson).facts.toSet())
        assertEquals(0, restore(backup))
        assertEquals(restored, restoredDb.groupDao().getById(groupId))
    }

    private fun assertProjectionEquals(expected: GroupEntity, actual: GroupEntity) {
        assertEquals(expected.lastMetaTimestamp, actual.lastMetaTimestamp)
        assertEquals(expected.lastMetaEventId, actual.lastMetaEventId)
        assertEquals(
            Json.decodeFromString<Map<String, String>>(expected.memberClocks),
            Json.decodeFromString<Map<String, String>>(actual.memberClocks)
        )
    }
}
