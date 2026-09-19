package com.splitfree.sync.event

import android.app.Application
import androidx.room.Room
import com.splitfree.data.local.AppDatabase
import com.splitfree.data.local.entities.EventEntity
import com.splitfree.data.repository.ControlOperationJournal
import com.splitfree.data.repository.EventRepository
import com.splitfree.data.repository.GroupRepository
import com.splitfree.data.util.CompressionUtil
import com.splitfree.domain.crypto.EventSigner
import com.splitfree.domain.crypto.GiftWrapService
import com.splitfree.domain.crypto.GroupEncryption
import com.splitfree.domain.crypto.NostrEvent
import com.splitfree.domain.crypto.nip.Nip44
import com.splitfree.domain.model.export.SplitFreeExport
import com.splitfree.domain.model.group.Group
import com.splitfree.domain.model.group.GroupIdentity
import com.splitfree.domain.model.group.GroupMeta
import com.splitfree.domain.model.group.GroupProjection
import com.splitfree.domain.model.group.KeyRevocation
import com.splitfree.domain.model.group.KeyRotation
import com.splitfree.domain.repository.GroupRepositoryContract
import com.splitfree.domain.repository.IdentityContract
import com.splitfree.domain.repository.NostrClientContract
import com.splitfree.domain.repository.SettingsContract
import com.splitfree.domain.usecase.export.ExportGroupUseCase
import com.splitfree.domain.usecase.export.ImportGroupUseCase
import com.splitfree.domain.usecase.group.ControlOperationLock
import com.splitfree.domain.usecase.group.RotateGroupKeyUseCase
import com.splitfree.domain.usecase.group.UpdateGroupRelaysUseCase
import com.splitfree.domain.usecase.sync.SelfHealUseCase
import com.splitfree.domain.util.hexToBytes
import com.splitfree.domain.validation.EventValidator
import com.splitfree.test.FakeSecureStorage
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import java.util.concurrent.Executor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
class CanonicalControlIntegrationRoomTest {
    private val json = Json { ignoreUnknownKeys = true }
    private val encryption = GroupEncryption(CompressionUtil)
    private val keys = listOf(encryption.generateGroupKey(), encryption.generateGroupKey())
    private val creatorKey = ByteArray(32) { 1 }
    private val memberKey = ByteArray(32) { 2 }
    private val laterSuccessorKey = ByteArray(32) { 3 }
    private val earlierSuccessorKey = ByteArray(32) { 4 }
    private val removedKey = ByteArray(32) { 5 }
    private val creator = NostrEvent.pubkeyFromPrivkey(creatorKey)
    private val member = NostrEvent.pubkeyFromPrivkey(memberKey)
    private val laterSuccessor = NostrEvent.pubkeyFromPrivkey(laterSuccessorKey)
    private val earlierSuccessor = NostrEvent.pubkeyFromPrivkey(earlierSuccessorKey)
    private val removed = NostrEvent.pubkeyFromPrivkey(removedKey)
    private val createdAt = System.currentTimeMillis() / 1000 - 1_000
    private val groupId = GroupIdentity.derive(creator, createdAt)
    private val baseline = Group(
        groupId,
        "Trip",
        "Shared history",
        creator,
        createdAt,
        listOf(creator, member, removed),
        listOf("wss://nos.lol"),
        mapOf(creator to "Creator", member to "Member", removed to "Removed")
    )
    private val migratedRelays = listOf("wss://relay.damus.io")
    private val devices = mutableListOf<Device>()
    private val direct = Executor { it.run() }
    private val identity = mockk<IdentityContract> {
        every { getPublicKeyHex() } returns creator
        every { getPublicKeyBytes() } returns creator.hexToBytes()
        every { getPrivateKeyBytes() } answers { creatorKey.copyOf() }
        every { stagedIdentitySwitch() } returns null
        every { hasPendingKeyPair() } returns false
    }

    private inner class Device {
        val db = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), AppDatabase::class.java)
            .allowMainThreadQueries()
            .setQueryExecutor(direct)
            .setTransactionExecutor(direct)
            .build()
        val storage = FakeSecureStorage()
        val groups = GroupRepository(db.groupDao(), storage)
        val events = EventRepository(db, db.eventDao())
        val signer = EventSigner(identity)
        val settings = mockk<SettingsContract> { every { giftWrapEnabled } returns false }
        val giftWrap = GiftWrapService(identity, settings)
        val publisher = EventPublisher(
            db.eventDao(), db.outboxDao(), db.deliveryDao(), mockk(relaxed = true), mockk(relaxed = true),
            giftWrap, groups, identity, db, settings
        )
        val selfHeal = mockk<SelfHealUseCase>(relaxed = true)
        val client = mockk<NostrClientContract>(relaxed = true)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val rotation = RotateGroupKeyUseCase(
            groups,
            encryption,
            identity,
            signer,
            publisher,
            events,
            ControlOperationJournal(db.controlOperationDao()),
            ControlOperationLock()
        )
        val post = EventPostProcessor(groups, rotation, mockk(relaxed = true), selfHeal, publisher, identity, scope)
        val processor = EventProcessor(
            db.eventDao(), groups, encryption, signer, identity, giftWrap, EventValidator(), post,
            MembershipHistory(db.eventDao(), groups, identity)
        )
        val importer = ImportGroupUseCase(events, groups, encryption, EventValidator(), identity)

        init {
            devices += this
        }

        suspend fun seed() {
            groups.save(baseline, keys[0])
            assertApplied(processor.process(metaEvent(metadata(baseline), createdAt + 10)))
        }

        fun writer(repository: GroupRepositoryContract = groups) = UpdateGroupRelaysUseCase(
            repository,
            encryption,
            signer,
            publisher,
            client,
            selfHeal,
            identity
        )

        suspend fun projection(): GroupProjection = json.decodeFromString(
            checkNotNull(db.groupDao().getById(groupId)).projectionJson
        )
    }

    @After
    fun teardown() {
        devices.forEach {
            it.scope.cancel()
            it.db.close()
        }
    }

    private fun metadata(group: Group, relays: List<String> = group.relays) = GroupMeta(
        group.name,
        group.description,
        group.createdBy,
        group.createdAt,
        group.members,
        relays,
        group.memberNames,
        group.keyEpoch,
        group.originalCreator,
        group.creatorTransitions
    )

    private fun signed(type: String, plaintext: String, at: Long, privateKey: ByteArray, epoch: Int = 0) = NostrEvent(
        pubkey = NostrEvent.pubkeyFromPrivkey(privateKey),
        createdAt = at,
        kind = 30078,
        tags = listOf(listOf("g", groupId), listOf("t", type)),
        content = encryption.encrypt(plaintext, keys[epoch])
    ).sign(privateKey)

    private fun metaEvent(meta: GroupMeta, at: Long, privateKey: ByteArray = creatorKey) = signed(
        "group_meta",
        json.encodeToString(GroupMeta.serializer(), meta),
        at,
        privateKey,
        meta.keyEpoch
    )

    private fun revocation(
        authorKey: ByteArray,
        successorKey: ByteArray,
        at: Long,
        epoch: Int = 0,
        proven: Boolean = true
    ): NostrEvent {
        val author = NostrEvent.pubkeyFromPrivkey(authorKey)
        val successor = NostrEvent.pubkeyFromPrivkey(successorKey)
        val payload = KeyRevocation(
            author,
            successor,
            "Compromised key",
            successorProof = if (proven) KeyRevocation.proveSuccessor(groupId, author, successor, successorKey) else ""
        )
        return signed("key_revocation", json.encodeToString(KeyRevocation.serializer(), payload), at, authorKey, epoch)
    }

    private fun rotationEvent(): NostrEvent {
        val conversationKey = Nip44.getConversationKey(creatorKey, creator.hexToBytes())
        val payload = KeyRotation(
            1,
            mapOf(creator to Nip44.encrypt(keys[1], conversationKey)),
            listOf(creator, member),
            removed
        )
        return signed(
            "key_rotation",
            json.encodeToString(KeyRotation.serializer(), payload),
            createdAt + 40,
            creatorKey
        )
    }

    private fun assertApplied(result: EventProcessor.ProcessResult) {
        assertTrue(result.toString(), result.stored)
        assertEquals(result.toString(), IngestOutcome.APPLIED, result.outcome)
    }

    @Test
    fun `epoch zero local relay fact survives export and same database reimport`() = runBlocking {
        relayRoundTrip(0)
    }

    @Test
    fun `epoch one local relay fact survives export and same database reimport`() = runBlocking {
        relayRoundTrip(1)
    }

    private suspend fun relayRoundTrip(epoch: Int) {
        val device = Device()
        device.seed()
        if (epoch == 1) assertApplied(device.processor.process(rotationEvent()))
        device.writer()(groupId, migratedRelays)
        val expected = checkNotNull(device.groups.getById(groupId))
        val row = device.events.getEventsByType(groupId, "group_meta").maxBy { it.createdAt }
        val event = checkNotNull(NostrEvent.fromJson(checkNotNull(row.originalEventJson)))
        val payload = json.decodeFromString<GroupMeta>(encryption.decrypt(event.content, keys[epoch]))
        assertTrue(event.verify())
        assertEquals(epoch, row.keyEpoch)
        assertEquals(epoch, payload.keyEpoch)
        assertEquals(metadata(expected), payload)
        val fact = device.projection().facts.single { it.id == event.id && it.kind == "meta" }
        assertFalse(fact.trusted)
        assertEquals(payload, fact.meta)
        assertEquals(epoch, fact.epoch)
        val backup = json.decodeFromString<SplitFreeExport>(
            ExportGroupUseCase(device.events, device.groups, identity)(groupId)
        )
        repeat(2) {
            assertEquals(0, device.importer(backup))
            assertEquals(expected, device.groups.getById(groupId))
            assertEquals(fact, device.projection().facts.single { it.id == event.id && it.kind == "meta" })
        }
        val restored = Device()
        assertEquals(backup.events.size, restored.importer(backup))
        assertEquals(expected, restored.groups.getById(groupId))
        assertEquals(0, restored.importer(backup))
        assertEquals(IngestOutcome.ALREADY_APPLIED, restored.processor.process(event).outcome)
    }

    @Test
    fun `relay signing race with key rotation refuses stale snapshot without publication`() = runBlocking {
        val device = Device()
        device.seed()
        val race = object : GroupRepositoryContract by device.groups {
            override suspend fun applyAuthenticatedMeta(
                groupId: String,
                meta: GroupMeta,
                author: String,
                timestamp: Long,
                eventId: String,
                epoch: Int,
                expectedGroup: Group?
            ): Boolean {
                assertApplied(device.processor.process(rotationEvent()))
                return device.groups.applyAuthenticatedMeta(
                    groupId,
                    meta,
                    author,
                    timestamp,
                    eventId,
                    epoch,
                    expectedGroup
                )
            }
        }
        val failure = runCatching { device.writer(race)(groupId, migratedRelays) }.exceptionOrNull()
        assertTrue(failure.toString(), failure is IllegalStateException)
        val group = checkNotNull(device.groups.getById(groupId))
        assertEquals(1, group.keyEpoch)
        assertEquals(baseline.relays, group.relays)
        assertEquals(1, device.events.getEventsByType(groupId, "group_meta").size)
        coVerify(exactly = 0) { device.client.connect(any()) }
        coVerify(exactly = 0) { device.selfHeal(any()) }
    }

    @Test
    fun `retained epoch admits earlier competing retirement after rotation in both delivery orders`() = runBlocking {
        val earlier = revocation(memberKey, earlierSuccessorKey, createdAt + 20)
        val later = revocation(memberKey, laterSuccessorKey, createdAt + 30)
        val rotation = rotationEvent()
        for (order in listOf(listOf(later, rotation, earlier), listOf(earlier, rotation, later))) {
            val device = Device()
            device.seed()
            order.forEach { assertApplied(device.processor.process(it)) }
            val expectedMembers = listOf(creator, earlierSuccessor)
            assertEquals(expectedMembers, device.groups.getMembers(groupId))
            assertEquals(1, device.groups.getById(groupId)!!.keyEpoch)
            assertEquals(mapOf(member to earlierSuccessor), device.groups.retiredIdentities(groupId).successors)
            assertTrue(device.groups.hasRevocationInEpoch(groupId, member, 0))
            assertFalse(device.groups.hasRevocationInEpoch(groupId, member, 1))
            assertEquals(0, device.db.eventDao().getEvent(earlier.id)!!.keyEpoch)
            assertEquals(EventEntity.APPLY_STATE_APPLIED, device.db.eventDao().getEvent(earlier.id)!!.applyState)
            assertEquals(0, device.processor.retryDeferred(groupId))
            val recreated = GroupRepository(device.db.groupDao(), device.storage)
            assertEquals(expectedMembers, recreated.getMembers(groupId))
            assertEquals(IngestOutcome.ALREADY_APPLIED, device.processor.process(earlier).outcome)
        }
    }

    @Test
    fun `retained epoch does not admit genuinely removed author or expand competing retirement authority`() =
        runBlocking {
            val device = Device()
            device.seed()
            assertApplied(device.processor.process(revocation(memberKey, laterSuccessorKey, createdAt + 30)))
            assertApplied(device.processor.process(rotationEvent()))
            val attempts = listOf(
                revocation(removedKey, earlierSuccessorKey, createdAt + 20),
                revocation(memberKey, earlierSuccessorKey, createdAt + 20, proven = false),
                revocation(memberKey, earlierSuccessorKey, createdAt + 20, epoch = 1)
            )
            for (event in attempts) {
                val result = device.processor.process(event)
                assertEquals(result.toString(), IngestOutcome.REJECTED, result.outcome)
                assertFalse(result.stored)
                assertNull(device.db.eventDao().getEvent(event.id))
            }
            assertEquals(listOf(creator, laterSuccessor), device.groups.getMembers(groupId))
            assertFalse(removed in device.groups.retiredIdentities(groupId).revoked)
        }

    @Test
    fun `canonical postprocessor heals only accepted persisted relay changes`() = runBlocking {
        val device = Device()
        device.seed()
        val migrated = metadata(baseline, migratedRelays)
        val update = metaEvent(migrated, createdAt + 100)
        assertApplied(device.processor.process(update))
        assertEquals(migratedRelays, device.groups.getById(groupId)!!.relays)
        coVerify(exactly = 1) { device.selfHeal(groupId) }
        assertEquals(
            PostProcessOutcome.APPLIED,
            device.post.handle(
                "group_meta",
                json.encodeToString(GroupMeta.serializer(), migrated),
                creator,
                groupId,
                update.createdAt,
                false,
                update.id,
                0
            )
        )
        val stale = metaEvent(metadata(baseline), createdAt + 50)
        assertApplied(device.processor.process(stale))
        val noChange = metaEvent(migrated.copy(name = "Renamed"), createdAt + 110)
        assertApplied(device.processor.process(noChange))
        val memberMeta = metaEvent(
            metadata(baseline).copy(memberNames = mapOf(member to "Renamed member")),
            createdAt + 120,
            memberKey
        )
        assertApplied(device.processor.process(memberMeta))
        assertEquals("Renamed member", device.groups.getById(groupId)!!.memberNames[member])
        assertEquals(migratedRelays, device.groups.getById(groupId)!!.relays)
        val rejected = metaEvent(migrated.copy(members = emptyList(), relays = baseline.relays), createdAt + 130)
        assertEquals(IngestOutcome.REJECTED, device.processor.process(rejected).outcome)
        assertNotNull(device.db.eventDao().getEvent(update.id))
        coVerify(exactly = 1) { device.selfHeal(groupId) }
    }
}
