package com.splitfree.sync.worker

import android.app.Application
import androidx.room.Room
import com.splitfree.data.identity.IdentityManager
import com.splitfree.data.local.AppDatabase
import com.splitfree.data.nostr.NostrClient
import com.splitfree.data.repository.ControlOperationJournal
import com.splitfree.data.repository.EventRepository
import com.splitfree.data.repository.GroupRepository
import com.splitfree.data.repository.RelaySyncCursors
import com.splitfree.data.util.CompressionUtil
import com.splitfree.domain.crypto.EventSigner
import com.splitfree.domain.crypto.GiftWrapService
import com.splitfree.domain.crypto.GroupEncryption
import com.splitfree.domain.crypto.NostrEvent
import com.splitfree.domain.crypto.nip.Nip44
import com.splitfree.domain.model.group.Group
import com.splitfree.domain.model.sync.FetchResult
import com.splitfree.domain.model.sync.PullResult
import com.splitfree.domain.repository.EventPublisherContract
import com.splitfree.domain.repository.SettingsContract
import com.splitfree.domain.usecase.group.ControlOperationLock
import com.splitfree.domain.usecase.group.RevokeKeyUseCase
import com.splitfree.domain.usecase.group.RotateGroupKeyUseCase
import com.splitfree.domain.util.hexToBytes
import com.splitfree.domain.validation.EventValidator
import com.splitfree.sync.event.EventPostProcessor
import com.splitfree.sync.event.EventProcessor
import com.splitfree.sync.event.MembershipHistory
import com.splitfree.test.FakeSecureStorage
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import java.util.concurrent.Executor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
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

/**
 * A relay catch-up over the real stack: in-memory Room, the real [GroupRepository], [RelaySyncCursors],
 * [EventProcessor] and [RotateGroupKeyUseCase], real NIP-44 envelopes and BIP-340 signatures. Only the
 * relay is canned: [NostrClient.fetchEventsByRelay] returns what the creator published.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
class SyncEngineRotationCatchUpRoomTest {
    private val encryption = GroupEncryption(CompressionUtil)
    private val devices = mutableListOf<Device>()
    private val groupId = "rotation-catch-up"
    private val relay = "wss://relay.test"
    private val epochZeroKey = encryption.generateGroupKey()

    private inner class Device(seed: Int) {
        val identity = IdentityManager(RuntimeEnvironment.getApplication(), FakeSecureStorage()).apply {
            importKey("00".repeat(31) + seed.toString(16).padStart(2, '0'))
        }
        val pub: String = identity.getPublicKeyHex()
        private val db = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), AppDatabase::class.java)
            .allowMainThreadQueries()
            .setQueryExecutor(Executor { it.run() })
            .setTransactionExecutor(Executor { it.run() })
            .addCallback(AppDatabase.SYNC_REVISION_CALLBACK)
            .build()
        val groups = GroupRepository(db.groupDao(), FakeSecureStorage())
        val signer = EventSigner(identity)
        val sent = mutableListOf<NostrEvent>()
        private val publisher = mockk<EventPublisherContract>(relaxed = true)
        private val journal = ControlOperationJournal(db.controlOperationDao())
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
        private val revocation = RevokeKeyUseCase(
            identity, groups, encryption, signer, publisher, journal, lock, mockk(relaxed = true), mockk(relaxed = true)
        )
        private val post =
            EventPostProcessor(groups, rotation, revocation, mockk(relaxed = true), publisher, identity, scope)
        private val settings = mockk<SettingsContract> { every { giftWrapEnabled } returns false }
        private val processor = EventProcessor(
            db.eventDao(),
            groups,
            encryption,
            signer,
            identity,
            GiftWrapService(identity, settings),
            EventValidator(),
            post,
            MembershipHistory(db.eventDao(), groups, identity)
        )
        private val cursors = RelaySyncCursors(db)
        private val nostrClient = mockk<NostrClient>()
        private val engine =
            SyncEngine(db.eventDao(), db.outboxDao(), groups, nostrClient, identity, processor, cursors)

        init {
            devices += this
            coEvery { publisher.publishDirect(any(), any(), any(), any(), any()) } coAnswers {
                sent += firstArg<NostrEvent>()
            }
            every { nostrClient.currentRelayUrls() } returns emptyList()
        }

        suspend fun join(creator: String, members: List<String>) = groups.save(
            Group(groupId, "Trip", createdBy = creator, createdAt = 1, members = members, relays = listOf(relay)),
            epochZeroKey
        )

        /** All requested relay fixtures return [events] and reach EOSE. */
        suspend fun pull(events: List<NostrEvent>): PullResult {
            coEvery { nostrClient.fetchEventsByRelay(groupId, any(), pub) } answers {
                FetchResult(events, complete = true, completedRelays = secondArg<Map<String, Long>>().keys)
            }
            return engine.pullEvents(groupId, 0, epochZeroKey)
        }

        suspend fun group(): Group = checkNotNull(groups.getById(groupId))

        suspend fun cursor(): Long? = cursors.cursors(groupId, pub)[relay]

        suspend fun lastSync(): Long = checkNotNull(groups.getGroupEntity(groupId)).lastSyncTimestamp

        suspend fun holds(event: NostrEvent): Boolean = db.eventDao().getEvent(event.id) != null

        fun close() {
            scope.cancel()
            db.close()
        }
    }

    @After
    fun close() {
        devices.forEach { it.close() }
    }

    private fun NostrEvent.recipient(): String? = tags.firstOrNull { it.size >= 2 && it[0] == "p" }?.get(1)

    private fun NostrEvent.isType(type: String): Boolean = tags.contains(listOf("t", type))

    /**
     * The creator removes one of three members. The relay hands the remaining member both per-recipient
     * `key_rotation` envelopes plus the post-rotation `group_meta`. The creator's own envelope is sealed
     * to the creator, so it can never be opened here; it must be skipped, not held as missing history.
     */
    @Test
    fun `three member removal leaves the remaining member's catch-up complete`() = runBlocking {
        val creator = Device(1)
        val remaining = Device(2)
        val removed = Device(3)
        val roster = listOf(creator.pub, remaining.pub, removed.pub)
        creator.join(creator.pub, roster)
        remaining.join(creator.pub, roster)
        creator.rotation(groupId, removed.pub)
        val rotations = creator.sent.filter { it.isType("key_rotation") }
        assertEquals(setOf(creator.pub, remaining.pub), rotations.mapTo(HashSet()) { it.recipient() })
        val creatorsCopy = rotations.single { it.recipient() == creator.pub }
        val ownCopy = rotations.single { it.recipient() == remaining.pub }
        val meta = creator.sent.single { it.isType("group_meta") }

        val before = System.currentTimeMillis() / 1000
        val result = remaining.pull(creator.sent)
        val after = System.currentTimeMillis() / 1000

        assertEquals(PullResult(stored = 2, complete = true), result)
        val cursor = checkNotNull(remaining.cursor()) { "relay coverage must be recorded" }
        assertTrue("cursor $cursor must be the fetch start, in $before..$after", cursor in before..after)
        assertEquals(cursor, remaining.lastSync())
        val group = remaining.group()
        assertEquals(1, group.keyEpoch)
        assertEquals(listOf(creator.pub, remaining.pub), group.members)
        assertNotNull(remaining.groups.getGroupKeyForEpoch(groupId, 1))
        assertEquals(
            creator.groups.getGroupKeyForEpoch(groupId, 1),
            remaining.groups.getGroupKeyForEpoch(groupId, 1)
        )
        assertTrue(remaining.holds(ownCopy))
        assertTrue(remaining.holds(meta))
        assertFalse("the creator's own envelope is not this member's history", remaining.holds(creatorsCopy))
    }

    /**
     * Control: an envelope that names this member but was sealed under a conversation key it does not hold
     * is genuinely undecryptable here. It stays retryable and must keep the coverage window open.
     */
    @Test
    fun `an undecryptable rotation addressed to this member still keeps the catch-up incomplete`() = runBlocking {
        val creator = Device(1)
        val remaining = Device(2)
        val removed = Device(3)
        remaining.join(creator.pub, listOf(creator.pub, remaining.pub, removed.pub))
        val wrongConversationKey =
            Nip44.getConversationKey(creator.identity.getPrivateKeyBytes(), removed.pub.hexToBytes())
        val undecryptable = creator.signer.createSignedEvent(
            groupId,
            "key_rotation",
            Nip44.encrypt("""{"epoch":1}""", wrongConversationKey),
            recipientPubkey = remaining.pub
        )

        val result = remaining.pull(listOf(undecryptable))

        assertEquals(PullResult(stored = 0, complete = false), result)
        assertNull("an unopened envelope for this member must hold the cursor", remaining.cursor())
        assertEquals(0L, remaining.lastSync())
        assertEquals(0, remaining.group().keyEpoch)
        assertFalse(remaining.holds(undecryptable))
    }
}
