package com.splitfree.domain.usecase.export

import android.app.Application
import androidx.room.Room
import com.splitfree.data.local.AppDatabase
import com.splitfree.data.repository.EventRepository
import com.splitfree.data.repository.GroupRepository
import com.splitfree.data.util.CompressionUtil
import com.splitfree.domain.crypto.EventSigner
import com.splitfree.domain.crypto.GroupEncryption
import com.splitfree.domain.crypto.NostrEvent
import com.splitfree.domain.invite.InviteLinkCodec
import com.splitfree.domain.model.group.Group
import com.splitfree.domain.model.group.GroupIdentity
import com.splitfree.domain.model.group.GroupMeta
import com.splitfree.domain.model.group.GroupProjection
import com.splitfree.domain.model.group.KeyRotation
import com.splitfree.domain.model.sync.PullResult
import com.splitfree.domain.repository.EventPublisherContract
import com.splitfree.domain.repository.IdentityContract
import com.splitfree.domain.repository.SettingsContract
import com.splitfree.domain.repository.SyncEngineContract
import com.splitfree.domain.usecase.group.JoinGroupUseCase
import com.splitfree.domain.util.hexToBytes
import com.splitfree.domain.validation.EventValidator
import com.splitfree.sync.event.EventPublisher
import com.splitfree.test.FakeSecureStorage
import io.mockk.coEvery
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

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
class JoinGroupBackupRoomTest {
    private val databases = mutableListOf<AppDatabase>()
    private lateinit var db: AppDatabase
    private lateinit var groups: GroupRepository
    private lateinit var events: EventRepository
    private lateinit var publisher: EventPublisher
    private val keys = FakeSecureStorage()
    private val encryption = GroupEncryption(CompressionUtil)
    private val creatorKey = ByteArray(32) { 1 }
    private val memberKey = ByteArray(32) { 2 }
    private val creator = NostrEvent.pubkeyFromPrivkey(creatorKey)
    private val member = NostrEvent.pubkeyFromPrivkey(memberKey)
    private val createdAt = System.currentTimeMillis() / 1000 - 1000
    private val groupId = GroupIdentity.derive(creator, createdAt)
    private val key = Base64.getEncoder().encodeToString(ByteArray(32) { 7 })
    private val group = Group(
        id = groupId,
        name = "Trip",
        createdBy = creator,
        createdAt = createdAt,
        members = listOf(creator),
        relays = listOf("wss://purplerelay.com")
    )
    private val active = identity(memberKey)
    private val settings = mockk<SettingsContract>(relaxed = true) {
        every { displayNameFor(member) } returns "Member"
    }
    private val sync = mockk<SyncEngineContract>(relaxed = true)

    private fun identity(privateKey: ByteArray): IdentityContract = mockk(relaxed = true) {
        val pubkey = NostrEvent.pubkeyFromPrivkey(privateKey)
        every { identityState() } returns com.splitfree.domain.repository.IdentityState.READY
        every { hasPendingKeyPair() } returns false
        every { stagedIdentitySwitch() } returns null
        every { getPublicKeyHex() } returns pubkey
        every { getPublicKeyBytes() } returns pubkey.hexToBytes()
        every { getPrivateKeyBytes() } answers { privateKey.copyOf() }
    }

    private fun database(): AppDatabase = Room.inMemoryDatabaseBuilder(
        RuntimeEnvironment.getApplication(),
        AppDatabase::class.java
    ).allowMainThreadQueries().build().also { databases += it }

    @Before fun setup() {
        db = database()
        groups = GroupRepository(db.groupDao(), keys)
        events = EventRepository(db, db.eventDao())
        publisher = EventPublisher(
            db.eventDao(), db.outboxDao(), db.deliveryDao(), mockk(relaxed = true), mockk(relaxed = true),
            mockk(relaxed = true), groups, active, db, settings
        )
    }

    @After fun cleanup() = databases.forEach { it.close() }

    private fun join(publication: EventPublisherContract = publisher) = JoinGroupUseCase(
        groups, active, mockk(relaxed = true), EventSigner(active), encryption, publication,
        mockk(relaxed = true), sync, settings, events
    )

    private fun metadata(current: Group, members: List<String> = current.members) = GroupMeta(
        current.name, current.description, current.createdBy, current.createdAt, members, current.relays,
        current.memberNames, current.keyEpoch, current.originalCreator, current.creatorTransitions
    )

    private suspend fun receiveGenesis() {
        val meta = metadata(group)
        val event = EventSigner(identity(creatorKey)).createSignedEvent(
            groupId,
            "group_meta",
            encryption.encrypt(Json.encodeToString(meta), key),
            createdAt = createdAt
        )
        assertTrue(groups.applyAuthenticatedMeta(groupId, meta, creator, event.createdAt, event.id, 0))
        publisher.publishDirect(event, groupId, event.content, "group_meta")
    }

    private suspend fun joined(): Group {
        coEvery { sync.pullEvents(groupId, 0, key, true) } coAnswers {
            receiveGenesis()
            PullResult(stored = 1, complete = true)
        }
        return join()(InviteLinkCodec.encode(group, key))
    }

    @Test fun `real join export and repeated import retain the exact canonical projection`() = runBlocking {
        val joined = joined()
        assertEquals(listOf(creator, member), joined.members)
        val before = checkNotNull(db.groupDao().getById(groupId))
        val beforeEvents = events.getEventsByGroup(groupId)
        val announcement = beforeEvents.single { it.pubkey == member }
        assertTrue(checkNotNull(NostrEvent.fromJson(announcement.originalEventJson!!)).verify())
        val fact = Json.decodeFromString<GroupProjection>(before.projectionJson).facts.single {
            it.id ==
                announcement.eventId
        }
        assertEquals("meta", fact.kind)
        assertEquals("Member", fact.meta!!.memberNames[member])
        assertEquals(createdAt, before.lastMetaTimestamp)
        val backup = ExportGroupUseCase(events, groups, active)(groupId)
        val importer = ImportGroupUseCase(events, groups, encryption, EventValidator(), active)

        repeat(2) {
            assertEquals(0, importer(backup))
            assertEquals(before, db.groupDao().getById(groupId))
            assertEquals(beforeEvents, events.getEventsByGroup(groupId))
        }

        val restoredDb = database()
        val restoredGroups = GroupRepository(restoredDb.groupDao(), FakeSecureStorage())
        val restoredEvents = EventRepository(restoredDb, restoredDb.eventDao())
        val restore = ImportGroupUseCase(restoredEvents, restoredGroups, encryption, EventValidator(), active)
        assertEquals(2, restore(backup))
        assertEquals(joined, restoredGroups.getById(groupId))
        assertEquals(0, restore(backup))
    }

    @Test fun `same signed join id with different canonical metadata still throws`() = runBlocking {
        joined()
        val before = checkNotNull(db.groupDao().getById(groupId))
        val fact = Json.decodeFromString<GroupProjection>(before.projectionJson).facts.single { it.author == member }

        val failure = assertThrows(IllegalStateException::class.java) {
            runBlocking {
                groups.applyAuthenticatedMeta(
                    groupId,
                    fact.meta!!.copy(memberNames = mapOf(member to "Different")),
                    member,
                    fact.timestamp,
                    fact.id,
                    fact.epoch
                )
            }
        }

        assertEquals("Authenticated control event changed its meaning", failure.message)
        assertEquals(before, db.groupDao().getById(groupId))
    }

    private suspend fun legacyJoin(displayName: String? = "Member", signedName: String? = "Member"): NostrEvent {
        groups.save(group.copy(members = listOf(creator, member)), key)
        receiveGenesis()
        val current = checkNotNull(groups.getById(groupId))
        val meta = metadata(current, current.members + member).copy(
            memberNames = signedName?.let { mapOf(member to it) }.orEmpty()
        )
        val event = EventSigner(active).createSignedEvent(
            groupId,
            "group_meta",
            encryption.encrypt(Json.encodeToString(meta), key),
            createdAt = createdAt + 10
        )
        assertTrue(groups.applyMemberSelfUpdate(groupId, member, event.createdAt, event.id, true, displayName, 0))
        publisher.publishDirect(event, groupId, event.content, "group_meta")
        return event
    }

    @Test fun `backup upgrades an equivalent pre fix signed self join and remains idempotent`() = runBlocking {
        val join = legacyJoin()
        val before = groups.getById(groupId)
        val backup = ExportGroupUseCase(events, groups, active)(groupId)
        val importer = ImportGroupUseCase(events, groups, encryption, EventValidator(), active)

        assertEquals(0, importer(backup))
        assertEquals(before, groups.getById(groupId))
        val row = checkNotNull(db.groupDao().getById(groupId))
        assertEquals(
            "meta",
            Json.decodeFromString<GroupProjection>(row.projectionJson).facts.single {
                it.id == join.id
            }.kind
        )
        assertEquals(0, importer(backup))
        assertEquals(row, db.groupDao().getById(groupId))
    }

    @Test fun `backup upgrades the original blank name pre fix join probe sequence`() = runBlocking {
        val join = legacyJoin(displayName = null, signedName = null)
        val before = groups.getById(groupId)
        val backup = ExportGroupUseCase(events, groups, active)(groupId)
        val importer = ImportGroupUseCase(events, groups, encryption, EventValidator(), active)

        assertEquals(0, importer(backup))
        assertEquals(before, groups.getById(groupId))
        val row = checkNotNull(db.groupDao().getById(groupId))
        assertEquals(
            "meta",
            Json.decodeFromString<GroupProjection>(row.projectionJson).facts.single {
                it.id == join.id
            }.kind
        )
        assertEquals(0, importer(backup))
        assertEquals(row, db.groupDao().getById(groupId))
    }

    @Test fun `same join event cannot change even metadata fields a member does not control`() = runBlocking {
        joined()
        val before = checkNotNull(db.groupDao().getById(groupId))
        val fact = Json.decodeFromString<GroupProjection>(before.projectionJson).facts.single { it.author == member }

        val failure = assertThrows(IllegalStateException::class.java) {
            runBlocking {
                groups.applyAuthenticatedMeta(
                    groupId,
                    fact.meta!!.copy(name = "Conflicting payload"),
                    member,
                    fact.timestamp,
                    fact.id,
                    fact.epoch
                )
            }
        }

        assertEquals("Authenticated control event changed its meaning", failure.message)
        assertEquals(before, db.groupDao().getById(groupId))
    }

    @Test fun `backup rejects a pre fix self fact whose name conflicts with the signed event`() = runBlocking {
        legacyJoin(displayName = "Different")
        val before = checkNotNull(db.groupDao().getById(groupId))
        val backup = ExportGroupUseCase(events, groups, active)(groupId)
        val importer = ImportGroupUseCase(events, groups, encryption, EventValidator(), active)

        val failure = assertThrows(IllegalStateException::class.java) { runBlocking { importer(backup) } }

        assertEquals("Authenticated control event changed its meaning", failure.message)
        assertEquals(before, db.groupDao().getById(groupId))
    }

    @Test fun `concurrent removal between signing and local admission cannot report join success`() = runBlocking {
        groups.save(group.copy(members = listOf(creator, member)), key)
        val guarded = object : EventPublisherContract by publisher {
            override suspend fun publishJoinedGroup(event: NostrEvent, expectedGroup: Group, meta: GroupMeta): Boolean {
                assertTrue(groups.applyIdentityRevocation(groupId, member, "", event.createdAt, "revocation"))
                return publisher.publishJoinedGroup(event, expectedGroup, meta)
            }
        }

        assertThrows(IllegalStateException::class.java) {
            runBlocking { join(guarded)(InviteLinkCodec.encode(group, key)) }
        }

        assertFalse(member in groups.getMembers(groupId))
        assertTrue(events.getEventsByGroup(groupId).isEmpty())
        assertEquals(0, db.outboxDao().count())
    }

    @Test fun `concurrent rotation between signing and admission cannot apply a stale epoch join`() = runBlocking {
        groups.save(group, key)
        val guarded = object : EventPublisherContract by publisher {
            override suspend fun publishJoinedGroup(event: NostrEvent, expectedGroup: Group, meta: GroupMeta): Boolean {
                assertTrue(groups.applyKeyRotation(groupId, 1, listOf(creator), emptyMap()))
                return publisher.publishJoinedGroup(event, expectedGroup, meta)
            }
        }

        assertThrows(IllegalStateException::class.java) {
            runBlocking { join(guarded)(InviteLinkCodec.encode(group, key)) }
        }

        assertEquals(1, groups.getById(groupId)!!.keyEpoch)
        assertFalse(member in groups.getMembers(groupId))
        assertTrue(events.getEventsByGroup(groupId).isEmpty())
        assertEquals(0, db.outboxDao().count())
    }

    @Test fun `retained historical rotations never downgrade the current epoch or roster`() = runBlocking {
        groups.save(group.copy(keyEpoch = 3), key)
        val before = groups.getById(groupId)
        val old = KeyRotation(1, emptyMap(), listOf(member), creator)

        assertTrue(groups.applyAuthenticatedRotation(groupId, old, creator, createdAt + 10, "old-rotation"))
        assertEquals(before, groups.getById(groupId))
        assertEquals(mapOf("old-rotation" to old), groups.authenticatedRotations(groupId))
        assertTrue(groups.applyAuthenticatedRotation(groupId, old, creator, createdAt + 10, "old-rotation"))
        assertThrows(IllegalStateException::class.java) {
            runBlocking {
                groups.applyAuthenticatedRotation(
                    groupId,
                    old.copy(removedMember = member),
                    creator,
                    createdAt + 10,
                    "old-rotation"
                )
            }
        }
        Unit
    }

    @Test fun `legacy rotation history retention and replay preserve later checkpoint members`() = runBlocking {
        groups.save(group.copy(keyEpoch = 1, members = listOf(creator, member)), key)
        val before = groups.getById(groupId)
        val old = KeyRotation(1, emptyMap(), listOf(creator), member)

        assertTrue(groups.retainAuthenticatedRotationHistory(groupId, old, creator, createdAt + 10, "old-rotation"))
        assertEquals(before, groups.getById(groupId))
        assertEquals(mapOf("old-rotation" to old), groups.authenticatedRotations(groupId))
        assertTrue(groups.applyAuthenticatedRotation(groupId, old, creator, createdAt + 10, "old-rotation"))
        assertTrue(groups.retainAuthenticatedRotationHistory(groupId, old, creator, createdAt + 10, "old-rotation"))
        assertEquals(before, groups.getById(groupId))
        assertThrows(IllegalStateException::class.java) {
            runBlocking {
                groups.retainAuthenticatedRotationHistory(
                    groupId,
                    old.copy(removedMember = creator),
                    creator,
                    createdAt + 10,
                    "old-rotation"
                )
            }
        }
        Unit
    }
}
