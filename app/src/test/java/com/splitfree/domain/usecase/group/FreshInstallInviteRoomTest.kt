package com.splitfree.domain.usecase.group

import android.app.Application
import androidx.room.Room
import com.splitfree.data.identity.IdentityManager
import com.splitfree.data.local.AppDatabase
import com.splitfree.data.repository.ControlOperationJournal
import com.splitfree.data.repository.EventRepository
import com.splitfree.data.repository.ExpenseRepository
import com.splitfree.data.repository.GroupRepository
import com.splitfree.data.settings.UserPreferences
import com.splitfree.data.util.CompressionUtil
import com.splitfree.domain.crypto.EventSigner
import com.splitfree.domain.crypto.GiftWrapService
import com.splitfree.domain.crypto.GroupEncryption
import com.splitfree.domain.crypto.NostrEvent
import com.splitfree.domain.crypto.NostrKind
import com.splitfree.domain.invite.InviteLinkCodec
import com.splitfree.domain.model.expense.SplitEntry
import com.splitfree.domain.model.expense.SplitType
import com.splitfree.domain.model.group.Group
import com.splitfree.domain.model.group.GroupIdentity
import com.splitfree.domain.model.group.GroupMeta
import com.splitfree.domain.model.sync.PullResult
import com.splitfree.domain.repository.ControlOperation
import com.splitfree.domain.repository.EventPublisherContract
import com.splitfree.domain.repository.GroupRepositoryContract
import com.splitfree.domain.repository.IdentityState
import com.splitfree.domain.repository.NostrClientContract
import com.splitfree.domain.repository.SecureStorageException
import com.splitfree.domain.repository.SyncEngineContract
import com.splitfree.domain.usecase.expense.AddExpenseUseCase
import com.splitfree.domain.usecase.sync.SelfHealUseCase
import com.splitfree.domain.util.RelayDefaults
import com.splitfree.domain.validation.EventValidator
import com.splitfree.sync.event.EventPostProcessor
import com.splitfree.sync.event.EventProcessor
import com.splitfree.sync.event.EventPublisher
import com.splitfree.sync.event.MembershipHistory
import com.splitfree.test.FakeSecureStorage
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import java.util.Base64
import java.util.concurrent.Executor
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
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
 * Real join/storage/crypto with an explicitly empty initial history. Secure storage and transport are
 * substituted; no Android Keystore, device, or external relay is exercised.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
class FreshInstallInviteRoomTest {
    private lateinit var fixture: Fixture

    @Before
    fun setup() {
        fixture = Fixture()
    }

    @After
    fun teardown() {
        fixture.close()
    }

    @Test
    fun absentIdentityFailsWithoutGroupKeysMemberEventOrOutboxResidue() = runBlocking {
        fixture.assertFreshInstall()
        val before = fixture.persistedState()
        assertTrue(fixture.joinThroughCoordinator() is JoinGroupCoordinator.State.Failed)
        assertEquals(before, fixture.persistedState())
        fixture.assertFreshInstall()
    }

    @Test
    fun unreadableIdentityFailsBeforePersistenceAndDoesNotReplaceItsKey() = runBlocking {
        val pubkey = fixture.identity.generateKeyPair()
        for (lost in listOf(false, true)) {
            fixture.identityStorage.keyLost = lost
            fixture.identityStorage.transientFailure =
                if (lost) null else SecureStorageException("temporarily unavailable")
            val before = fixture.persistedState()
            assertEquals(
                if (lost) IdentityState.RECOVERY_REQUIRED else IdentityState.UNAVAILABLE,
                fixture.identity.identityState()
            )
            assertTrue(runCatching { fixture.join(fixture.link) }.exceptionOrNull() is IllegalStateException)
            assertEquals(before, fixture.persistedState())
        }
        fixture.identityStorage.keyLost = false
        fixture.identityStorage.transientFailure = null
        assertEquals(pubkey, fixture.identity.getPublicKeyHex())
        assertEquals(0, fixture.identityStorage.resets)
    }

    @Test
    fun identityReplacementDuringInitialLookupFailsBeforeAnyJoinPersistence() = runBlocking {
        val oldPubkey = fixture.initialize()
        var replacement = ""
        val before = fixture.persistedState()
        val repository = object : GroupRepositoryContract by fixture.groups {
            override suspend fun getById(groupId: String): Group? {
                replacement = fixture.identity.generateKeyPair()
                return fixture.groups.getById(groupId)
            }
        }
        val failure = runCatching { fixture.joinWithRepository(repository)(fixture.link) }.exceptionOrNull()
        assertEquals("Identity changed while joining", failure?.message)
        assertTrue(replacement != oldPubkey)
        assertEquals(replacement, fixture.identity.getPublicKeyHex())
        assertEquals(before, fixture.persistedState())
    }

    @Test
    fun identityStorageLossDuringInitialLookupFailsBeforeAnyJoinPersistence() = runBlocking {
        fixture.initialize()
        val before = fixture.persistedState()
        val repository = object : GroupRepositoryContract by fixture.groups {
            override suspend fun getById(groupId: String): Group? {
                fixture.identityStorage.transientFailure = SecureStorageException("Identity became unavailable")
                return fixture.groups.getById(groupId)
            }
        }
        assertTrue(
            runCatching {
                fixture.joinWithRepository(repository)(fixture.link)
            }.exceptionOrNull() is IllegalStateException
        )
        assertEquals(before, fixture.persistedState())
    }

    @Test
    fun readyJoinAfterRefusalHasNoBlankMemberAndPeerAcceptsTheOnlyAnnouncement() = runBlocking {
        assertTrue(fixture.joinThroughCoordinator() is JoinGroupCoordinator.State.Failed)
        fixture.coordinator.acknowledge()
        val pubkey = fixture.initialize()
        val joined = fixture.join(fixture.link)
        assertEquals(listOf(pubkey), joined.members)
        val eventId = fixture.assertQueuedAnnouncement(pubkey, joined.members)
        withCreatorPeer { peer ->
            assertTrue(peer.processor.process(fixture.queuedAnnouncement()).stored)
            assertEquals(setOf(peer.group.createdBy, pubkey), peer.groups.getById(joined.id)?.members?.toSet())
        }
        assertEquals(joined, fixture.join(fixture.link))
        assertEquals(eventId, fixture.assertQueuedAnnouncement(pubkey, joined.members))
        coVerify(exactly = 1) { fixture.sync.pullEvents(fixture.group.id, 0, fixture.groupKey, true) }
    }

    @Test
    fun cancellationDuringEmptyInitialHistoryResumesWithoutDuplicateAnnouncement() = runBlocking {
        val pubkey = fixture.initialize()
        coEvery { fixture.sync.pullEvents(any(), any(), any(), any()) } throws
            CancellationException("interrupted history")
        assertTrue(runCatching { fixture.join(fixture.link) }.exceptionOrNull() is CancellationException)
        assertEquals(listOf(pubkey), fixture.groups.getById(fixture.group.id)?.members)
        assertTrue(fixture.events.getEventsByGroup(fixture.group.id).isEmpty())
        assertEquals(0, fixture.db.outboxDao().count())
        coEvery { fixture.sync.pullEvents(any(), any(), any(), any()) } returns PullResult(0, complete = true)
        fixture.join(fixture.link)
        val eventId = fixture.assertQueuedAnnouncement(pubkey, listOf(pubkey))
        fixture.join(fixture.link)
        assertEquals(eventId, fixture.assertQueuedAnnouncement(pubkey, listOf(pubkey)))
    }

    @Test
    fun persistenceFailureRetainsRetryAndCancellationAfterDurableQueueDoesNotPublishAgain() = runBlocking {
        val pubkey = fixture.initialize()
        fixture.failBeforePublish = true
        assertTrue(fixture.joinThroughCoordinator() is JoinGroupCoordinator.State.Failed)
        assertEquals(0, fixture.db.outboxDao().count())
        assertTrue(fixture.events.getEventsByGroup(fixture.group.id).isEmpty())
        fixture.coordinator.acknowledge()
        fixture.failBeforePublish = false
        fixture.cancelAfterPublish = true
        assertTrue(fixture.joinThroughCoordinator() is JoinGroupCoordinator.State.Failed)
        val eventId = fixture.assertQueuedAnnouncement(pubkey, listOf(pubkey))
        fixture.coordinator.acknowledge()
        fixture.cancelAfterPublish = false
        assertTrue(fixture.joinThroughCoordinator() is JoinGroupCoordinator.State.Joined)
        assertEquals(eventId, fixture.assertQueuedAnnouncement(pubkey, listOf(pubkey)))
        assertEquals(2, fixture.publishAttempts)
    }

    @Test
    fun concurrentSameLinkCommandsPublishExactlyOnce() = runBlocking {
        val pubkey = fixture.initialize()
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        coEvery { fixture.sync.pullEvents(any(), any(), any(), any()) } coAnswers {
            entered.complete(Unit)
            release.await()
            PullResult(0, complete = true)
        }
        val first = async { fixture.join(fixture.link) }
        entered.await()
        val second = async { fixture.join(fixture.link) }
        release.complete(Unit)
        assertEquals(first.await(), second.await())
        fixture.assertQueuedAnnouncement(pubkey, listOf(pubkey))
        assertEquals(1, fixture.publishAttempts)
    }

    @Test
    fun revocationIntentCommittedDuringInitialHistoryPreventsOldIdentityJoinPublication() = runBlocking {
        val oldPubkey = fixture.initialize()
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        coEvery { fixture.sync.pullEvents(any(), any(), any(), any()) } coAnswers {
            entered.complete(Unit)
            release.await()
            PullResult(0, complete = true)
        }
        val joining = async { runCatching { fixture.join(fixture.link) } }
        entered.await()
        assertEquals(listOf(oldPubkey), fixture.groups.getById(fixture.group.id)?.members)
        try {
            val operation = fixture.captureInterruptedRevocation()
            val prepared = Json.decodeFromString<PreparedRevocation>(checkNotNull(operation.preparedJson))
            val envelope = prepared.events.single { it.eventType == "key_revocation" }
            val event = envelope.event()
            assertTrue(event.verify())
            assertTrue(
                fixture.groups.applyAuthenticatedRevocation(
                    fixture.group.id,
                    oldPubkey,
                    prepared.newPubkey,
                    event.createdAt,
                    event.id,
                    checkNotNull(envelope.projection).epoch,
                    successorProven = true
                )
            )
            assertFalse(oldPubkey in checkNotNull(fixture.groups.getById(fixture.group.id)).members)
        } finally {
            release.complete(Unit)
        }
        val failure = joining.await().exceptionOrNull()
        assertTrue(failure is IllegalStateException)
        assertTrue(failure?.message.orEmpty().contains("Identity replacement is pending"))
        assertEquals(1, fixture.publishAttempts)
        assertFalse(oldPubkey in checkNotNull(fixture.groups.getById(fixture.group.id)).members)
        assertTrue(fixture.events.getEventsByGroup(fixture.group.id).isEmpty())
        assertEquals(0, fixture.db.outboxDao().count())
    }

    @Test
    fun joinCommittedBeforeRevocationCaptureIsIncludedInDurableIdentityIntent() = runBlocking {
        val oldPubkey = fixture.initialize()
        val joined = fixture.join(fixture.link)
        val announcementId = fixture.assertQueuedAnnouncement(oldPubkey, joined.members)
        val operation = fixture.captureInterruptedRevocation()
        val intent = Json.decodeFromString<RevocationIntent>(operation.intentJson)
        assertEquals(oldPubkey, intent.oldPubkey)
        assertEquals(listOf(joined), intent.groups)
        assertEquals(mapOf(joined.id to emptyList<String>()), intent.moneyHistory)
        assertEquals(announcementId, fixture.assertQueuedAnnouncement(oldPubkey, joined.members))
    }

    @Test
    fun initializedEmptyHistoryJoinCanAdmitPeerRosterThenSendRealWrappedExpense() = runBlocking {
        val pubkey = fixture.initialize()
        fixture.join(fixture.link)
        val announcement = fixture.queuedAnnouncement()
        withCreatorPeer { peer ->
            assertTrue(peer.processor.process(announcement).stored)
            val joinedGroup = checkNotNull(peer.groups.getById(fixture.group.id))
            val meta = GroupMeta(
                name = joinedGroup.name,
                createdBy = joinedGroup.createdBy,
                createdAt = joinedGroup.createdAt,
                members = joinedGroup.members,
                relays = joinedGroup.relays,
                memberNames = joinedGroup.memberNames
            )
            val encrypted = peer.encryption.encrypt(Json.encodeToString(GroupMeta.serializer(), meta), fixture.groupKey)
            val roster = peer.signer.createSignedEvent(fixture.group.id, "group_meta", encrypted)
            assertTrue(fixture.processor.process(roster).stored)
            assertEquals(
                setOf(pubkey, peer.identity.getPublicKeyHex()),
                fixture.groups.getById(fixture.group.id)?.members?.toSet()
            )
            fixture.addExpense(
                fixture.group.id,
                100,
                "INR",
                "First expense after onboarding",
                pubkey,
                SplitType.EQUAL,
                listOf(SplitEntry(pubkey, 50), SplitEntry(peer.identity.getPublicKeyHex(), 50)),
                expectedAuthorPubkey = pubkey
            )
            val expense = fixture.events.getEventsByType(fixture.group.id, "expense").single()
            val wrapped = fixture.db.outboxDao().getAll().map { checkNotNull(NostrEvent.fromJson(it.eventJson)) }
                .single { it.kind == NostrKind.GIFT_WRAP }
            assertTrue(wrapped.verify())
            assertTrue(peer.processor.process(wrapped).stored)
            assertEquals(expense.eventId, peer.events.getEventsByType(fixture.group.id, "expense").single().eventId)
            assertFalse(checkNotNull(fixture.groups.getById(fixture.group.id)).members.any { it.isBlank() })
        }
    }

    private suspend fun withCreatorPeer(block: suspend (Fixture) -> Unit) {
        val peer = Fixture(fixture.group.createdAt)
        try {
            peer.identity.importKey("01".repeat(32))
            peer.groups.save(fixture.group, fixture.groupKey)
            block(peer)
        } finally {
            peer.close()
        }
    }

    private class Fixture(createdAt: Long = System.currentTimeMillis() / 1000 - 1000) : AutoCloseable {
        private val context: Application = RuntimeEnvironment.getApplication()
        private val direct = Executor { it.run() }
        val identityStorage = FakeSecureStorage()
        val identity = IdentityManager(context, identityStorage)
        private val keyStore = FakeSecureStorage()
        val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .addCallback(AppDatabase.SYNC_REVISION_CALLBACK)
            .allowMainThreadQueries()
            .setQueryExecutor(direct)
            .setTransactionExecutor(direct)
            .build()
        val groups = GroupRepository(db.groupDao(), keyStore)
        val events = EventRepository(db, db.eventDao())
        val encryption = GroupEncryption(CompressionUtil)
        val signer = EventSigner(identity)
        val settings = UserPreferences(context)
        private val giftWrap = GiftWrapService(identity, settings)
        private val client = mockk<NostrClientContract>(relaxed = true) {
            every { isConnected } returns false
        }
        val sync = mockk<SyncEngineContract> {
            coEvery { pullEvents(any(), any(), any(), any()) } returns PullResult(0, complete = true)
        }
        private val publisher = EventPublisher(
            db.eventDao(), db.outboxDao(), db.deliveryDao(), mockk(relaxed = true), mockk(relaxed = true),
            giftWrap, groups, identity, db, settings
        )
        private val selfHeal = SelfHealUseCase(events, client, giftWrap, identity, groups)
        var failBeforePublish = false
        var cancelAfterPublish = false
        var publishAttempts = 0
        private val publication = object : EventPublisherContract by publisher {
            override suspend fun publishJoinedGroup(event: NostrEvent, expectedGroup: Group, meta: GroupMeta): Boolean {
                publishAttempts++
                if (failBeforePublish) error("Simulated publication persistence failure")
                val saved = publisher.publishJoinedGroup(event, expectedGroup, meta)
                if (cancelAfterPublish) throw CancellationException("Interrupted after durable queue")
                return saved
            }
        }
        fun joinWithRepository(repository: GroupRepositoryContract) = JoinGroupUseCase(
            repository, identity, client, signer, encryption, publication, selfHeal, sync, settings, events
        )
        val join = joinWithRepository(groups)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val coordinator = JoinGroupCoordinator(join, scope)
        private val history = MembershipHistory(db.eventDao(), groups, identity)
        private val journal = ControlOperationJournal(db.controlOperationDao())
        private val lock = ControlOperationLock()
        private val rotation =
            RotateGroupKeyUseCase(groups, encryption, identity, signer, publisher, events, journal, lock)
        private val revocation =
            RevokeKeyUseCase(identity, groups, encryption, signer, publisher, journal, lock, settings, history)
        private val post = EventPostProcessor(groups, rotation, revocation, selfHeal, publisher, identity, scope)
        suspend fun captureInterruptedRevocation(): ControlOperation {
            val interrupted = object : EventPublisherContract by publisher {
                override suspend fun publishDirect(
                    event: NostrEvent,
                    groupId: String,
                    encrypted: String,
                    eventType: String,
                    expenseUuid: String?
                ) {
                    error("Stop after durable revocation capture")
                }
            }
            val operation =
                RevokeKeyUseCase(identity, groups, encryption, signer, interrupted, journal, lock, settings, history)
            val failure = runCatching { operation() }.exceptionOrNull()
            assertEquals("Stop after durable revocation capture", failure?.message)
            return checkNotNull(journal.get(RotateGroupKeyUseCase.REVOCATION_ID))
        }

        val processor = EventProcessor(
            db.eventDao(), groups, encryption, signer, identity, giftWrap, EventValidator(), post, history
        )
        val addExpense = AddExpenseUseCase(
            ExpenseRepository(db.eventDao(), groups, encryption, identity, signer, publisher, history)
        )
        private val creator = NostrEvent.pubkeyFromPrivkey(ByteArray(32) { 1 })
        val group = Group(
            id = GroupIdentity.derive(creator, createdAt),
            name = "Fresh-install invitation",
            createdBy = creator,
            createdAt = createdAt,
            members = listOf(creator),
            relays = RelayDefaults.DEFAULT_RELAYS.take(1)
        )
        val groupKey = Base64.getEncoder().encodeToString(ByteArray(32) { 7 })
        val link = InviteLinkCodec.encode(group, groupKey)

        suspend fun assertFreshInstall() {
            assertTrue("Fixture must exercise the real default gift-wrap preference", settings.giftWrapEnabled)
            assertFalse(identity.hasIdentity())
            assertEquals(IdentityState.ABSENT, identity.identityState())
            assertEquals("", identity.getPublicKeyHex())
            assertEquals(0, identity.getPrivateKeyBytes().size)
            assertTrue(groups.getAll().isEmpty())
            assertEquals(null, groups.getGroupKey(group.id))
            assertTrue(events.getEventsByGroup(group.id).isEmpty())
            assertEquals(0, db.outboxDao().count())
            assertEquals(group.id, InviteLinkCodec.decode(link).groupId)
        }

        suspend fun persistedState(): Map<String, Any> = mapOf(
            "groupRows" to db.groupDao().getAll().size,
            "epochKeyPresent" to keyStore.contains("${group.id}:0"),
            "legacyKeyPresent" to keyStore.contains(group.id),
            "eventRows" to db.eventDao().getEventIds(group.id).size,
            "outboxRows" to db.outboxDao().count()
        )

        fun initialize(): String = identity.generateKeyPair().also {
            settings.saveDisplayNameIntent(it, "First-run joiner")
        }

        suspend fun joinThroughCoordinator(): JoinGroupCoordinator.State {
            coordinator.join(link)
            return withTimeout(5_000) {
                coordinator.state.first {
                    it is JoinGroupCoordinator.State.Joined || it is JoinGroupCoordinator.State.Failed
                }
            }
        }

        suspend fun assertQueuedAnnouncement(pubkey: String, members: List<String>): String {
            val row = events.getEventsByType(group.id, "group_meta").single()
            val outbox = db.outboxDao().getAll().single()
            val event = queuedAnnouncement()
            assertEquals(
                "Join metadata is direct even with default wrapping enabled",
                NostrKind.APP_SPECIFIC,
                event.kind
            )
            assertEquals(row.eventId, event.id)
            assertEquals(row.originalEventJson, outbox.eventJson)
            assertEquals(pubkey, event.pubkey)
            assertTrue("Queued join must have a real verifiable Schnorr signature", event.verify())
            val meta = Json.decodeFromString<GroupMeta>(encryption.decrypt(event.content, groupKey))
            assertEquals(members, meta.members)
            assertEquals(group.createdBy, meta.createdBy)
            assertEquals("First-run joiner", meta.memberNames[pubkey])
            return event.id
        }

        suspend fun queuedAnnouncement(): NostrEvent =
            checkNotNull(NostrEvent.fromJson(db.outboxDao().getAll().single().eventJson))

        override fun close() {
            scope.cancel()
            db.close()
        }
    }
}
