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
import com.splitfree.domain.model.group.Group
import com.splitfree.domain.model.group.GroupMeta
import com.splitfree.domain.model.group.KeyRevocation
import com.splitfree.domain.repository.EventPublisherContract
import com.splitfree.domain.repository.EventSnapshot
import com.splitfree.domain.repository.SettingsContract
import com.splitfree.domain.usecase.group.ControlOperationLock
import com.splitfree.domain.usecase.group.RevokeKeyUseCase
import com.splitfree.domain.usecase.group.RotateGroupKeyUseCase
import com.splitfree.domain.util.toHex
import com.splitfree.domain.validation.EventValidator
import com.splitfree.test.FakeSecureStorage
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * Deliver the creator's old-key-signed revocation and companion metadata in both orders; neither
 * may let the retired key rejoin. Uses in-memory Room, real crypto and control use cases with fake
 * secure storage, captured publication and mocked self-heal/settings; no relay or courier is exercised.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
class RevocationOrderRoomTest {
    private val json = Json { ignoreUnknownKeys = true }
    private val encryption = GroupEncryption(CompressionUtil)
    private val devices = mutableListOf<Device>()
    private val groupId = "revocation-order-room"
    private val key = encryption.generateGroupKey()

    private inner class Device(seed: Int) {
        val identity = IdentityManager(RuntimeEnvironment.getApplication(), FakeSecureStorage()).apply {
            importKey("00".repeat(31) + seed.toString(16).padStart(2, '0'))
        }
        val pub = identity.getPublicKeyHex()
        val db = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), AppDatabase::class.java)
            .allowMainThreadQueries()
            .setQueryExecutor(Executor { it.run() })
            .setTransactionExecutor(Executor { it.run() })
            .addCallback(AppDatabase.SYNC_REVISION_CALLBACK)
            .build()
        val groups = GroupRepository(db.groupDao(), FakeSecureStorage())
        val journal = ControlOperationJournal(db.controlOperationDao())
        val signer = EventSigner(identity)
        val publisher = mockk<EventPublisherContract>(relaxed = true)
        val sent = mutableListOf<NostrEvent>()
        private val lock = ControlOperationLock()
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val rotation = RotateGroupKeyUseCase(
            groups,
            encryption,
            identity,
            signer,
            publisher,
            EventRepository(db, db.eventDao()),
            journal,
            lock
        )
        val revocation = RevokeKeyUseCase(
            identity, groups, encryption, signer, publisher, journal, lock, mockk(relaxed = true), mockk(relaxed = true)
        )
        private val post =
            EventPostProcessor(groups, rotation, revocation, mockk(relaxed = true), publisher, identity, scope)
        private val settings = mockk<SettingsContract>().also { every { it.giftWrapEnabled } returns false }
        val processor = EventProcessor(
            db.eventDao(), groups, encryption, signer, identity, GiftWrapService(identity, settings),
            EventValidator(), post, MembershipHistory(db.eventDao(), groups, identity)
        )

        init {
            devices += this
            coEvery { publisher.captureRevocationHistory(any(), any(), any()) } coAnswers {
                thirdArg<suspend (Map<String, List<EventSnapshot>>) -> Unit>()(
                    secondArg<List<Group>>().associate { it.id to emptyList() }
                )
            }
            coEvery { publisher.publishDirect(any(), any(), any(), any(), any()) } coAnswers {
                sent += firstArg<NostrEvent>()
            }
        }

        suspend fun join(creator: String, members: List<String>) {
            groups.save(
                Group(groupId, "Trip", createdBy = creator, createdAt = 1, members = members, relays = emptyList()),
                key
            )
        }

        suspend fun ingest(event: NostrEvent): EventProcessor.ProcessResult =
            processor.process(event, context = IngestionContext.RECONCILIATION, expectedGroupId = groupId)

        fun event(type: String, payload: String, groupKey: String = key) =
            signer.createSignedEvent(groupId, type, encryption.encrypt(payload, groupKey))

        suspend fun members() = checkNotNull(groups.getById(groupId)).members

        fun close() {
            scope.cancel()
            db.close()
        }
    }

    @Before
    fun setup() {
        mockkStatic(android.util.Log::class)
        every { android.util.Log.d(any<String>(), any<String>()) } returns 0
        every { android.util.Log.i(any<String>(), any<String>()) } returns 0
        every { android.util.Log.w(any<String>(), any<String>()) } returns 0
        every { android.util.Log.w(any<String>(), any<String>(), any()) } returns 0
        every { android.util.Log.e(any<String>(), any<String>()) } returns 0
        every { android.util.Log.e(any<String>(), any<String>(), any()) } returns 0
    }

    @After
    fun close() {
        devices.forEach { it.close() }
        unmockkStatic(android.util.Log::class)
    }

    /** The creator's real revocation: (replacement pubkey, its key_revocation event, its group_meta event). */
    private suspend fun creatorRevocation(creator: Device): Triple<String, NostrEvent, NostrEvent> {
        val replacement = creator.revocation()
        val revocation = creator.sent.single { it.tags.contains(listOf("t", "key_revocation")) }
        val metadata = creator.sent.single { it.tags.contains(listOf("t", "group_meta")) }
        return Triple(replacement, revocation, metadata)
    }

    private fun rejoin(compromised: Device, roster: List<String>) =
        compromised.event("group_meta", json.encodeToString(GroupMeta(members = roster + compromised.pub)))

    @Test
    fun `metadata arriving before the revocation still tombstones the old key and refuses its rejoin`() = runBlocking {
        val creator = Device(1)
        val receiver = Device(2)
        val compromised = Device(1)
        val roster = listOf(creator.pub, receiver.pub)
        creator.join(creator.pub, roster)
        receiver.join(creator.pub, roster)
        val (replacement, revocation, metadata) = creatorRevocation(creator)

        assertEquals(IngestOutcome.APPLIED, receiver.ingest(metadata).outcome)
        assertEquals(
            "metadata alone cannot transfer creator authority",
            creator.pub,
            receiver.groups.getById(groupId)!!.createdBy
        )
        assertFalse(compromised.pub in receiver.members())

        val revoked = receiver.ingest(revocation)
        assertEquals(
            "the revocation must complete the transition, not be refused as a non-member",
            IngestOutcome.APPLIED,
            revoked.outcome
        )
        assertEquals(replacement, receiver.groups.getById(groupId)!!.createdBy)
        assertEquals(listOf(replacement), receiver.groups.resolveRoster(groupId, listOf(compromised.pub)))

        val rejoined = receiver.ingest(rejoin(compromised, listOf(replacement, receiver.pub)))
        assertEquals(IngestOutcome.REJECTED, rejoined.outcome)
        assertFalse("compromised identity returned after meta-first delivery", compromised.pub in receiver.members())
        assertEquals(listOf(replacement, receiver.pub), receiver.members())
        assertNull(
            "a refused rejoin leaves no row a peer could be offered",
            receiver.db.eventDao().getEvent(rejoined.eventId!!)
        )
    }

    @Test
    fun `revocation arriving first keeps the tombstone and refuses the rejoin`() = runBlocking {
        val creator = Device(1)
        val receiver = Device(2)
        val compromised = Device(1)
        val roster = listOf(creator.pub, receiver.pub)
        creator.join(creator.pub, roster)
        receiver.join(creator.pub, roster)
        val (replacement, revocation, metadata) = creatorRevocation(creator)

        assertEquals(IngestOutcome.APPLIED, receiver.ingest(revocation).outcome)
        receiver.ingest(metadata)
        val rejoined = receiver.ingest(rejoin(compromised, listOf(replacement, receiver.pub)))

        assertEquals(IngestOutcome.REJECTED, rejoined.outcome)
        assertFalse(compromised.pub in receiver.members())
        assertEquals(replacement, receiver.groups.getById(groupId)!!.createdBy)
        assertEquals(listOf(replacement, receiver.pub), receiver.members())
    }

    @Test
    fun `same second smaller ID rootless rejoin is rejected while real creator history remains admissible`() =
        runBlocking {
            val creator = Device(1)
            val receiver = Device(2)
            val roster = listOf(creator.pub, receiver.pub)
            receiver.join(creator.pub, roster)
            val at = System.currentTimeMillis() / 1000 - 100
            fun candidate(type: String, payload: String, index: Int) = NostrEvent(
                pubkey = creator.pub,
                createdAt = at,
                kind = 30078,
                tags = listOf(listOf("g", groupId), listOf("t", type), listOf("d", "$type:$index")),
                content = payload
            )
            val malformedContent = encryption.encrypt(json.encodeToString(GroupMeta(members = roster)), key)
            val retirementContent = encryption.encrypt(json.encodeToString(KeyRevocation(creator.pub)), key)
            val malformedUnsigned = (0 until 256).map { candidate("group_meta", malformedContent, it) }
                .minBy { it.computeId().toHex() }
            val retirementUnsigned = (0 until 256).map { candidate("key_revocation", retirementContent, it) }
                .maxBy { it.computeId().toHex() }
            val privateKey = creator.identity.getPrivateKeyBytes()
            val malformed: NostrEvent
            val retirement: NostrEvent
            try {
                malformed = malformedUnsigned.sign(privateKey)
                retirement = retirementUnsigned.sign(privateKey)
            } finally {
                privateKey.fill(0)
            }
            assertEquals(retirement.createdAt, malformed.createdAt)
            assertTrue("rejoin must sort before retirement in the same second", malformed.id < retirement.id)
            val historical = creator.signer.createSignedEvent(
                groupId,
                "group_meta",
                encryption.encrypt(
                    json.encodeToString(
                        GroupMeta(name = "Historical", createdBy = creator.pub, createdAt = 1, members = roster)
                    ),
                    key
                ),
                createdAt = at - 1
            )
            assertEquals(IngestOutcome.APPLIED, receiver.ingest(retirement).outcome)
            assertTrue(receiver.groups.isHistoricalCreator(groupId, creator.pub, malformed.createdAt, malformed.id))
            assertEquals(IngestOutcome.REJECTED, receiver.ingest(malformed).outcome)
            assertNull(receiver.db.eventDao().getEvent(malformed.id))
            assertEquals(IngestOutcome.APPLIED, receiver.ingest(historical).outcome)
            assertFalse(creator.pub in receiver.members())
            assertEquals("", receiver.groups.getById(groupId)!!.createdBy)
            assertEquals(listOf(receiver.pub), receiver.members())
        }

    @Test
    fun `a former member's revocation under an old key is not admitted and leaves no tombstone`() = runBlocking {
        // Only the current key proves the author was still a member when the transition was published.
        val creator = Device(1)
        val receiver = Device(2)
        val former = Device(3)
        val roster = listOf(creator.pub, receiver.pub, former.pub)
        receiver.join(creator.pub, roster)
        val epochOne = encryption.generateGroupKey()
        receiver.groups.saveGroupKeyForEpoch(groupId, 1, epochOne)
        assertTrue(receiver.groups.applyKeyRotation(groupId, 1, listOf(creator.pub, receiver.pub), emptyMap()))

        val stale = former.event("key_revocation", json.encodeToString(KeyRevocation(former.pub, receiver.pub, "x")))
        val result = receiver.ingest(stale)

        assertEquals(IngestOutcome.REJECTED, result.outcome)
        assertEquals("not a member", result.reason)
        assertNull(receiver.db.eventDao().getEvent(stale.id))
        assertEquals(listOf(former.pub), receiver.groups.resolveRoster(groupId, listOf(former.pub)))
        assertEquals(listOf(creator.pub, receiver.pub), receiver.members())
    }

    @Test
    fun `a non-member revocation naming a non-member successor stays unstored and retryable`() = runBlocking {
        // A member whose join has not landed here yet may revoke; the row must not be parked as failed.
        val creator = Device(1)
        val receiver = Device(2)
        val joiner = Device(3)
        val successor = Device(4)
        receiver.join(creator.pub, listOf(creator.pub, receiver.pub))

        val early = joiner.event("key_revocation", json.encodeToString(KeyRevocation(joiner.pub, successor.pub, "x")))
        val result = receiver.ingest(early)

        assertEquals(IngestOutcome.REJECTED, result.outcome)
        assertEquals("not a member", result.reason)
        assertNull(receiver.db.eventDao().getEvent(early.id))
        assertEquals(listOf(joiner.pub), receiver.groups.resolveRoster(groupId, listOf(joiner.pub)))
    }
}
