package com.splitfree.sync.event

import android.app.Application
import androidx.room.Room
import com.splitfree.data.identity.IdentityManager
import com.splitfree.data.local.AppDatabase
import com.splitfree.data.repository.ControlOperationJournal
import com.splitfree.data.repository.EventRepository
import com.splitfree.data.repository.GroupRepository
import com.splitfree.data.util.CompressionUtil
import com.splitfree.domain.crypto.EventSigner
import com.splitfree.domain.crypto.GiftWrapService
import com.splitfree.domain.crypto.GroupEncryption
import com.splitfree.domain.crypto.NostrEvent
import com.splitfree.domain.invite.InviteLinkCodec
import com.splitfree.domain.model.export.SplitFreeExport
import com.splitfree.domain.model.group.CreatorTransition
import com.splitfree.domain.model.group.Group
import com.splitfree.domain.model.group.GroupIdentity
import com.splitfree.domain.model.group.GroupMeta
import com.splitfree.domain.model.group.GroupProjection
import com.splitfree.domain.model.group.KeyRevocation
import com.splitfree.domain.model.sync.PullResult
import com.splitfree.domain.repository.IdentityContract
import com.splitfree.domain.repository.NostrClientContract
import com.splitfree.domain.repository.SettingsContract
import com.splitfree.domain.repository.SyncEngineContract
import com.splitfree.domain.usecase.export.ExportGroupUseCase
import com.splitfree.domain.usecase.export.ImportGroupUseCase
import com.splitfree.domain.usecase.group.ControlOperationLock
import com.splitfree.domain.usecase.group.CreateGroupUseCase
import com.splitfree.domain.usecase.group.CreateInviteLinkUseCase
import com.splitfree.domain.usecase.group.JoinGroupUseCase
import com.splitfree.domain.usecase.group.RevokeKeyUseCase
import com.splitfree.domain.usecase.group.RotateGroupKeyUseCase
import com.splitfree.domain.usecase.sync.SelfHealUseCase
import com.splitfree.domain.util.hexToBytes
import com.splitfree.domain.validation.EventValidator
import com.splitfree.test.FakeSecureStorage
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import java.util.Base64
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
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * Offline fresh-creation, successor-invite and backup regressions with in-memory Room and real crypto.
 * Network, scheduling and selected control collaborators are doubles; no live relay or device is exercised.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
class PrelaunchFreshFlowRoomTest {
    private val json = Json { ignoreUnknownKeys = true }
    private val encryption = GroupEncryption(CompressionUtil)
    private val creatorKey = ByteArray(32) { 1 }
    private val joinerKey = ByteArray(32) { 2 }
    private val successorKey = ByteArray(32) { 3 }
    private val creator = NostrEvent.pubkeyFromPrivkey(creatorKey)
    private val joiner = NostrEvent.pubkeyFromPrivkey(joinerKey)
    private val successor = NostrEvent.pubkeyFromPrivkey(successorKey)
    private val now = System.currentTimeMillis() / 1000
    private val createdAt = now - 1000
    private val groupId = GroupIdentity.derive(creator, createdAt)
    private val relays = listOf("wss://nos.lol")
    private val epochOneKey = Base64.getEncoder().encodeToString(ByteArray(32) { 7 })
    private val devices = mutableListOf<Device>()
    private val direct = Executor { it.run() }

    private inner class Device(privateKey: ByteArray) {
        val pubkey = NostrEvent.pubkeyFromPrivkey(privateKey)
        val identity = mockk<IdentityContract>(relaxed = true) {
            every { identityState() } returns com.splitfree.domain.repository.IdentityState.READY
            every { getPublicKeyHex() } returns pubkey
            every { getPublicKeyBytes() } returns pubkey.hexToBytes()
            every { getPrivateKeyBytes() } answers { privateKey.copyOf() }
            every { hasIdentity() } returns true
            every { hasPendingKeyPair() } returns false
            every { stagedIdentitySwitch() } returns null
        }
        val db = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), AppDatabase::class.java)
            .allowMainThreadQueries().setQueryExecutor(direct).setTransactionExecutor(direct).build()
        val groups = GroupRepository(db.groupDao(), FakeSecureStorage())
        val events = EventRepository(db, db.eventDao())
        val signer = EventSigner(identity)
        val settings = mockk<SettingsContract>(relaxed = true) {
            every { giftWrapEnabled } returns false
            every { displayNameFor(any()) } returns ""
        }
        val giftWrap = GiftWrapService(identity, settings)
        val publisher = EventPublisher(
            db.eventDao(), db.outboxDao(), db.deliveryDao(), mockk(relaxed = true), mockk(relaxed = true),
            giftWrap, groups, identity, db, settings
        )
        val selfHeal = mockk<SelfHealUseCase>(relaxed = true)
        val client = mockk<NostrClientContract>(relaxed = true)
        val sync = mockk<SyncEngineContract>()
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

        suspend fun create(): Group = CreateGroupUseCase(groups, encryption, identity, signer, publisher, settings)(
            "Trip",
            relays,
            createdAt,
            pubkey,
            "fresh-creation"
        )

        suspend fun export(): SplitFreeExport = json.decodeFromString(
            ExportGroupUseCase(events, groups, identity)(groupId)
        )

        suspend fun projection(): GroupProjection = json.decodeFromString(
            checkNotNull(db.groupDao().getById(groupId)).projectionJson
        )

        suspend fun join(history: List<NostrEvent>, link: String? = null, interrupt: Boolean = false): Group {
            coEvery { sync.pullEvents(groupId, 0, epochOneKey, true) } coAnswers {
                if (interrupt) throw kotlinx.coroutines.CancellationException("Interrupted after durable save")
                history.forEach { assertApplied(processor.process(it)) }
                PullResult(history.size, complete = true)
            }
            val remote = Group(
                groupId,
                "Trip",
                createdBy = creator,
                createdAt = createdAt,
                members = listOf(creator),
                relays = relays,
                keyEpoch = 1
            )
            return JoinGroupUseCase(
                groups, identity, client, signer, encryption, publisher, selfHeal, sync, settings, events
            )(link ?: InviteLinkCodec.encode(remote, epochOneKey))
        }
    }

    @After
    fun teardown() {
        devices.forEach {
            it.scope.cancel()
            it.db.close()
        }
    }

    private fun assertApplied(result: EventProcessor.ProcessResult) {
        assertEquals(result.toString(), IngestOutcome.APPLIED, result.outcome)
        assertTrue(result.toString(), result.stored)
    }

    private fun signed(type: String, payload: String, key: String, at: Long): NostrEvent = NostrEvent(
        pubkey = creator,
        createdAt = at,
        kind = 30078,
        tags = listOf(listOf("g", groupId), listOf("t", type)),
        content = encryption.encrypt(payload, key)
    ).sign(creatorKey)

    @Test
    fun freshCreateExportRestorePreservesGroup() = runBlocking {
        val source = Device(creatorKey)
        val group = source.create()
        assertFalse(source.projection().incomplete)
        val backup = source.export()
        assertEquals(1, backup.events.size)
        val restored = Device(creatorKey)
        assertEquals(1, restored.importer(backup))
        assertEquals(group, restored.groups.getById(groupId))
    }

    @Test
    fun creatorIdentityReplacementMustKeepInvitesWorking() = runBlocking {
        val device = Device(creatorKey)
        device.create()
        val key = checkNotNull(device.groups.getGroupKeyForEpoch(groupId, 0))
        val revocation = KeyRevocation(
            creator,
            successor,
            "Replace identity",
            successorProof = KeyRevocation.proveSuccessor(groupId, creator, successor, successorKey)
        )
        val event = signed("key_revocation", json.encodeToString(KeyRevocation.serializer(), revocation), key, now + 1)
        assertApplied(device.processor.process(event))
        val proof = CreatorTransition.sign(groupId, event, 0, revocation, creatorKey)
        val companion = GroupMeta(
            name = "Trip",
            createdBy = successor,
            createdAt = createdAt,
            members = listOf(successor),
            relays = relays,
            originalCreator = creator,
            creatorTransitions = listOf(proof)
        )
        // The old author's companion can arrive after its retirement; proof is not roster authority.
        assertApplied(
            device.processor.process(
                signed(
                    "group_meta",
                    json.encodeToString(GroupMeta.serializer(), companion),
                    key,
                    now
                )
            )
        )
        assertEquals(successor, device.groups.getById(groupId)!!.createdBy)
        val invite = InviteLinkCodec.decode(CreateInviteLinkUseCase(device.groups)(groupId))
        assertEquals(groupId, invite.groupId)
        assertEquals(creator, invite.creatorPubkey)
    }

    @Test
    fun currentEpochJoinWithCreatorHistoryRestoresExactly() = runBlocking {
        val device = Device(joinerKey)
        val meta = GroupMeta(
            name = "Trip",
            createdBy = creator,
            createdAt = createdAt,
            members = listOf(creator),
            relays = relays,
            keyEpoch = 1
        )
        val group = device.join(
            listOf(
                signed(
                    "group_meta",
                    json.encodeToString(GroupMeta.serializer(), meta),
                    epochOneKey,
                    now - 100
                )
            )
        )
        assertEquals(listOf(creator, joiner), group.members)
        assertTrue(device.projection().incomplete)
        val backup = device.export()
        assertEquals(setOf("1"), backup.encryptedEpochKeys.keys)
        val restored = Device(joinerKey)
        assertEquals(backup.events.size, restored.importer(backup))
        assertEquals(group, restored.groups.getById(groupId))
    }

    @Test
    fun joinedGroupBackupMustNotLoseInviteBoundCreatorWhenRelayHistoryIsEmpty() = runBlocking {
        val device = Device(joinerKey)
        val group = device.join(emptyList())
        assertEquals(creator, group.createdBy)
        assertTrue(device.projection().incomplete)
        val backup = device.export()
        assertEquals(1, backup.events.size)
        val restored = Device(joinerKey)
        assertEquals(1, restored.importer(backup))
        assertEquals(
            "A backup produced by the first-release app loses its invite-bound creator",
            group.createdBy,
            restored.groups.getById(groupId)!!.createdBy
        )
    }

    @Test
    fun successorInviteJoinsWithNoOldKeysAndRestoresOffline() = runBlocking {
        val authority = Device(creatorKey)
        authority.create()
        val revocation = KeyRevocation(
            creator,
            successor,
            successorProof =
            KeyRevocation.proveSuccessor(groupId, creator, successor, successorKey)
        )
        val oldKey = checkNotNull(authority.groups.getGroupKeyForEpoch(groupId, 0))
        val event = signed(
            "key_revocation",
            json.encodeToString(KeyRevocation.serializer(), revocation),
            oldKey,
            now - 20
        )
        val proof = CreatorTransition.sign(groupId, event, 0, revocation, creatorKey)
        assertApplied(authority.processor.process(event))
        assertTrue(authority.groups.mergeCreatorBootstrap(groupId, creator, createdAt, listOf(proof)))
        authority.groups.saveGroupKeyForEpoch(groupId, 1, epochOneKey)
        authority.groups.updateKeyEpoch(groupId, 1)
        val link = CreateInviteLinkUseCase(authority.groups)(groupId)
        val device = Device(joinerKey)
        val joined = device.join(emptyList(), link)
        assertEquals(successor, joined.createdBy)
        assertEquals(creator, joined.originalCreator)
        assertEquals(null, device.groups.getGroupKeyForEpoch(groupId, 0))
        assertEquals(listOf(proof), joined.creatorTransitions)
        val backup = device.export()
        val restored = Device(joinerKey)
        restored.importer(backup)
        assertEquals(joined, restored.groups.getById(groupId))
        assertEquals(creator, InviteLinkCodec.decode(CreateInviteLinkUseCase(restored.groups)(groupId)).creatorPubkey)
        assertEquals(0, restored.importer(backup))
        val meta = GroupMeta(
            name = "Successor update",
            createdBy = successor,
            createdAt = createdAt,
            members = listOf(successor, joiner),
            relays = relays,
            keyEpoch = 1,
            originalCreator = creator,
            creatorTransitions = listOf(proof)
        )
        val successorMeta = NostrEvent(
            pubkey = successor,
            createdAt = now + 2,
            kind = 30078,
            tags = listOf(listOf("g", groupId), listOf("t", "group_meta")),
            content = encryption.encrypt(json.encodeToString(GroupMeta.serializer(), meta), epochOneKey)
        ).sign(successorKey)
        assertApplied(restored.processor.process(successorMeta))
        assertEquals("Successor update", restored.groups.getById(groupId)!!.name)
        val staleRoot = signed(
            "group_meta",
            json.encodeToString(
                GroupMeta.serializer(),
                meta.copy(
                    name = "Retired takeover",
                    createdBy = creator
                )
            ),
            epochOneKey,
            now + 3
        )
        assertFalse(restored.processor.process(staleRoot).stored)
        assertEquals("Successor update", restored.groups.getById(groupId)!!.name)
    }

    @Test
    fun realLocalCreatorReplacementJournalsPortableProofBeforePromotion() = runBlocking {
        val device = Device(creatorKey)
        device.create()
        val localIdentity = IdentityManager(RuntimeEnvironment.getApplication(), FakeSecureStorage())
        localIdentity.importKey(creatorKey.joinToString("") { "%02x".format(it) })
        val realSigner = EventSigner(localIdentity)
        val journal = ControlOperationJournal(device.db.controlOperationDao())
        val replacement = RevokeKeyUseCase(
            localIdentity,
            device.groups,
            encryption,
            realSigner,
            device.publisher,
            journal,
            ControlOperationLock(),
            mockk(relaxed = true),
            mockk(relaxed = true)
        )()
        val group = device.groups.getById(groupId)!!
        assertEquals(replacement, group.createdBy)
        assertEquals(creator, group.originalCreator)
        assertEquals(1, group.creatorTransitions.size)
        assertTrue(group.creatorTransitions.single().verify(groupId))
        assertEquals(replacement, localIdentity.getPublicKeyHex())
        assertEquals(null, journal.get(RotateGroupKeyUseCase.REVOCATION_ID))
        val invite = InviteLinkCodec.decode(CreateInviteLinkUseCase(device.groups)(groupId))
        assertEquals(group.creatorTransitions, invite.creatorTransitions)
        val events = device.events.getEventsByType(groupId, "key_revocation")
        assertEquals(events.single().eventId, group.creatorTransitions.single().eventId)
    }

    @Test
    fun interruptedSuccessorJoinNeverExposesRetiredRootAndCanResume() = runBlocking {
        val revocation = KeyRevocation(
            creator,
            successor,
            successorProof =
            KeyRevocation.proveSuccessor(groupId, creator, successor, successorKey)
        )
        val event = signed(
            "key_revocation",
            json.encodeToString(KeyRevocation.serializer(), revocation),
            epochOneKey,
            now - 20
        )
        val proof = CreatorTransition.sign(groupId, event, 0, revocation, creatorKey)
        val remote = Group(
            groupId, "Trip", createdBy = successor, originalCreator = creator, createdAt = createdAt,
            members = listOf(successor), relays = relays, keyEpoch = 1, creatorTransitions = listOf(proof)
        )
        val link = InviteLinkCodec.encode(remote, epochOneKey)
        val device = Device(joinerKey)
        assertTrue(
            runCatching { device.join(emptyList(), link, interrupt = true) }.exceptionOrNull()
                is kotlinx.coroutines.CancellationException
        )
        assertEquals(successor, device.groups.getById(groupId)!!.createdBy)
        assertEquals(listOf(proof), device.groups.getById(groupId)!!.creatorTransitions)
        assertTrue(device.events.getEventsByGroup(groupId).isEmpty())
        val restored = Device(joinerKey)
        restored.importer(device.export())
        assertEquals(successor, restored.groups.getById(groupId)!!.createdBy)
        assertEquals(successor, device.join(emptyList(), link).createdBy)
        assertEquals(1, device.events.getEventsByGroup(groupId).size)
    }

    @Test
    fun interruptedLocalReplacementReusesJournaledCertificateWithoutOldEpochKey() = runBlocking {
        val device = Device(creatorKey)
        device.create()
        val localIdentity = IdentityManager(RuntimeEnvironment.getApplication(), FakeSecureStorage())
        localIdentity.importKey(creatorKey.joinToString("") { "%02x".format(it) })
        val journal = ControlOperationJournal(device.db.controlOperationDao())
        val blocked = object : com.splitfree.domain.repository.EventPublisherContract by device.publisher {
            override suspend fun publishDirect(
                event: NostrEvent,
                groupId: String,
                encrypted: String,
                eventType: String,
                expenseUuid: String?
            ) {
                error("Publish interrupted")
            }
        }
        val operation = RevokeKeyUseCase(
            localIdentity,
            device.groups,
            encryption,
            EventSigner(localIdentity),
            blocked,
            journal,
            ControlOperationLock(),
            mockk(relaxed = true),
            mockk(relaxed = true)
        )
        assertTrue(runCatching { operation() }.isFailure)
        assertEquals(creator, localIdentity.getPublicKeyHex())
        val prepared = json.decodeFromString<com.splitfree.domain.usecase.group.PreparedRevocation>(
            checkNotNull(journal.get(RotateGroupKeyUseCase.REVOCATION_ID)!!.preparedJson)
        )
        val meta = json.decodeFromString<GroupMeta>(
            prepared.events.single {
                it.eventType == "group_meta"
            }.projection!!.payload
        )
        val certificate = meta.creatorTransitions.single()
        device.groups.deleteGroupKey(groupId)
        val resumed = RevokeKeyUseCase(
            localIdentity,
            device.groups,
            encryption,
            EventSigner(localIdentity),
            device.publisher,
            journal,
            ControlOperationLock(),
            mockk(relaxed = true),
            mockk(relaxed = true)
        )()
        assertEquals(prepared.newPubkey, resumed)
        assertEquals(listOf(certificate), device.groups.getById(groupId)!!.creatorTransitions)
        assertEquals(resumed, localIdentity.getPublicKeyHex())
        val stored = device.events.getEventsByGroup(groupId).associateBy { it.eventId }
        prepared.events.forEach { envelope ->
            assertEquals(envelope.eventJson, stored[envelope.event().id]!!.originalEventJson)
        }
        assertEquals(null, journal.get(RotateGroupKeyUseCase.REVOCATION_ID))
    }

    @Test
    fun certificateCompanionRedeliversHistoryToNewCreatorWithoutGrantingOldMetadataAuthority() = runBlocking {
        val device = Device(joinerKey)
        val group = Group(
            groupId,
            "Trip",
            createdBy = creator,
            createdAt = createdAt,
            members = listOf(creator, joiner),
            relays = relays,
            keyEpoch = 1
        )
        device.groups.save(group, epochOneKey)
        val revocation = KeyRevocation(
            creator,
            successor,
            successorProof =
            KeyRevocation.proveSuccessor(groupId, creator, successor, successorKey)
        )
        val event = signed(
            "key_revocation",
            json.encodeToString(KeyRevocation.serializer(), revocation),
            epochOneKey,
            now - 20
        )
        val proof = CreatorTransition.sign(groupId, event, 0, revocation, creatorKey)
        val publisher = mockk<com.splitfree.domain.repository.EventPublisherContract>(relaxed = true)
        val post = EventPostProcessor(
            device.groups,
            device.rotation,
            mockk(relaxed = true),
            device.selfHeal,
            publisher,
            device.identity,
            device.scope
        )
        val meta = GroupMeta(
            name = "Retired takeover",
            createdBy = successor,
            createdAt = createdAt,
            members = listOf(successor, joiner),
            relays = relays,
            keyEpoch = 1,
            originalCreator = creator,
            creatorTransitions = listOf(proof)
        )
        assertEquals(
            PostProcessOutcome.APPLIED,
            post.handle(
                "group_meta",
                json.encodeToString(GroupMeta.serializer(), meta),
                creator,
                groupId,
                now,
                false,
                "companion",
                1
            )
        )
        assertEquals(successor, device.groups.getById(groupId)!!.createdBy)
        assertEquals("Trip", device.groups.getById(groupId)!!.name)
        io.mockk.coVerify(exactly = 1) { publisher.redeliverAuthoredEvents(groupId, setOf(successor)) }
    }
}
