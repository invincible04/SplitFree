package com.splitfree.domain.usecase.group

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
import com.splitfree.domain.crypto.nip.Nip44
import com.splitfree.domain.model.group.Group
import com.splitfree.domain.model.group.GroupMeta
import com.splitfree.domain.model.group.KeyRevocation
import com.splitfree.domain.model.group.KeyRotation
import com.splitfree.domain.repository.ControlOperation
import com.splitfree.domain.repository.EventPublisherContract
import com.splitfree.domain.repository.SettingsContract
import com.splitfree.domain.util.hexToBytes
import com.splitfree.domain.validation.EventValidator
import com.splitfree.sync.event.EventPostProcessor
import com.splitfree.sync.event.EventProcessor
import com.splitfree.sync.event.IngestOutcome
import com.splitfree.sync.event.IngestionContext
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
import kotlinx.serialization.encodeToString
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

/**
 * Offline removal recovery with in-memory Room, production repositories and control-event ingestion,
 * plus real NIP-44/BIP-340 crypto. Secure storage is fake; publishing, settings and self-heal are mocked.
 * Publication captures attempts before injected failure; no relay delivery or process restart is tested.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
class RotateGroupKeyRecoveryRoomTest {
    private val json = Json { ignoreUnknownKeys = true }
    private val encryption = GroupEncryption(CompressionUtil)
    private val devices = mutableListOf<Device>()
    private val groupId = "rotation-recovery"
    private val epochZeroKey = encryption.generateGroupKey()

    private inner class Device(seed: Int) {
        val identity = IdentityManager(RuntimeEnvironment.getApplication(), FakeSecureStorage()).apply {
            importKey("00".repeat(31) + seed.toString(16).padStart(2, '0'))
        }
        val pub: String = identity.getPublicKeyHex()
        val groupKeys = FakeSecureStorage()
        private val db = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), AppDatabase::class.java)
            .allowMainThreadQueries()
            .setQueryExecutor(Executor { it.run() })
            .setTransactionExecutor(Executor { it.run() })
            .addCallback(AppDatabase.SYNC_REVISION_CALLBACK)
            .build()
        val groups = GroupRepository(db.groupDao(), groupKeys)
        val journal = ControlOperationJournal(db.controlOperationDao())
        val sent = mutableListOf<NostrEvent>()
        var failPublishAt = 0
        private var publishCalls = 0
        private val signer = EventSigner(identity)
        private val publisher = mockk<EventPublisherContract>(relaxed = true)
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

        init {
            devices += this
            coEvery { publisher.publishDirect(any(), any(), any(), any(), any()) } coAnswers {
                val event = firstArg<NostrEvent>()
                assertTrue(
                    "Every envelope is journaled before it can leave",
                    journal.getAll(RotateGroupKeyUseCase.ROTATION_KIND).any {
                        it.preparedJson?.contains(event.id) ==
                            true
                    }
                )
                sent += event
                publishCalls++
                if (publishCalls == failPublishAt) error("injected interrupted publication")
            }
        }

        suspend fun create(members: List<String>) = groups.save(
            Group(groupId, "Trip", createdBy = pub, createdAt = 1, members = members, relays = emptyList()),
            epochZeroKey
        )

        suspend fun group(): Group = checkNotNull(groups.getById(groupId))

        suspend fun pending(): ControlOperation? = journal.get("rotation:$groupId")

        suspend fun plan(): List<PreparedControlEvent> = json.decodeFromString(checkNotNull(pending()?.preparedJson))

        suspend fun ingest(event: NostrEvent): IngestOutcome =
            processor.process(event, context = IngestionContext.RECONCILIATION, expectedGroupId = groupId).outcome

        /** A control event this device authors under the epoch-0 key, as a member would publish it. */
        fun event(type: String, payload: String, createdAt: Long = System.currentTimeMillis() / 1000): NostrEvent =
            signer.createSignedEvent(groupId, type, encryption.encrypt(payload, epochZeroKey), createdAt = createdAt)

        /** Fails the first publication attempt after journaling; the local epoch remains unchanged. */
        suspend fun interruptRemoval(removed: String) {
            failPublishAt = publishCalls + 1
            assertTrue(runCatching { rotation(groupId, removed) }.isFailure)
            failPublishAt = 0
            assertNotNull(checkNotNull(pending()).preparedJson)
            assertEquals(0, group().keyEpoch)
        }

        /** Captured rotation attempts addressed to [recipient], opened with the recipient's key. */
        fun envelopesFor(recipient: Device): List<KeyRotation> = sent
            .filter { it.tags.contains(listOf("p", recipient.pub)) }
            .map { event ->
                val conversationKey = Nip44.getConversationKey(
                    recipient.identity.getPrivateKeyBytes(),
                    pub.hexToBytes()
                )
                json.decodeFromString<KeyRotation>(Nip44.decrypt(event.content, conversationKey))
            }

        /** Captured metadata with the highest `(created_at, id)` clock; no receiver is run here. */
        fun newestMeta(): NostrEvent = sent
            .filter { it.tags.contains(listOf("t", "group_meta")) }
            .maxWith(compareBy<NostrEvent>({ it.createdAt }, { it.id }))

        suspend fun decryptMeta(event: NostrEvent, epoch: Int): GroupMeta = json.decodeFromString(
            encryption.decrypt(event.content, checkNotNull(groups.getGroupKeyForEpoch(groupId, epoch)))
        )

        fun close() {
            scope.cancel()
            db.close()
        }
    }

    @After
    fun close() {
        devices.forEach { it.close() }
    }

    private fun revocationOf(revoked: Device, successor: Device): NostrEvent =
        revoked.event("key_revocation", json.encodeToString(KeyRevocation(revoked.pub, successor.pub)))

    private fun selfJoinOf(joiner: Device, roster: List<String>): NostrEvent =
        joiner.event("group_meta", json.encodeToString(GroupMeta(members = roster + joiner.pub)))

    /**
     * A join and later creator snapshot change the roster across two publication failures. Recovery must
     * make the newest captured metadata match the final local roster, not an intermediate roster.
     */
    @Test
    fun `resumed rotation after roster oscillation publishes a newest metadata that matches its final roster`() =
        runBlocking {
            val creator = Device(1)
            val removed = Device(2)
            val peer = Device(3)
            val joiner = Device(4)
            val originalRoster = listOf(creator.pub, removed.pub, peer.pub)
            creator.create(originalRoster)
            creator.interruptRemoval(removed.pub)
            val initialMetaTime = creator.plan().single { it.eventType == "group_meta" }.event().createdAt
            assertEquals(IngestOutcome.APPLIED, creator.ingest(selfJoinOf(joiner, originalRoster)))
            creator.failPublishAt = 2
            creator.rotation.resumeIfNeeded()
            assertEquals(2, creator.plan().count { it.eventType == "group_meta" })
            assertEquals(0, creator.group().keyEpoch)
            val newerCreatorMeta = creator.event(
                "group_meta",
                json.encodeToString(
                    GroupMeta("Trip", createdBy = creator.pub, createdAt = 1, members = originalRoster)
                ),
                initialMetaTime + 1
            )
            assertEquals(IngestOutcome.APPLIED, creator.ingest(newerCreatorMeta))
            assertEquals(originalRoster, creator.group().members)

            creator.rotation.resumeIfNeeded()

            val group = creator.group()
            assertNull(creator.pending())
            assertEquals(1, group.keyEpoch)
            assertEquals(listOf(creator.pub, peer.pub), group.members)
            val newest = creator.newestMeta()
            assertEquals(group.members, creator.decryptMeta(newest, 1).members)
            assertEquals(1, creator.decryptMeta(newest, 1).keyEpoch)
            val metas = creator.sent.filter { it.tags.contains(listOf("t", "group_meta")) }
            assertTrue(metas.filter { it.id != newest.id }.all { it.createdAt < newest.createdAt })
        }

    /**
     * Revocation replaces the pending removal target. Its new roster replacement gets no epoch-key
     * envelope or seat; rotation payloads still name the originally selected key.
     */
    @Test
    fun `resumed removal excludes the successor its member revoked to while it was pending`() = runBlocking {
        val creator = Device(1)
        val removed = Device(2)
        val peer = Device(3)
        val successor = Device(4)
        creator.create(listOf(creator.pub, removed.pub, peer.pub))
        creator.interruptRemoval(removed.pub)
        assertEquals(IngestOutcome.APPLIED, creator.ingest(revocationOf(removed, successor)))
        assertEquals(listOf(creator.pub, successor.pub, peer.pub), creator.group().members)

        creator.rotation.resumeIfNeeded()

        val group = creator.group()
        assertNull(creator.pending())
        assertEquals(1, group.keyEpoch)
        assertEquals(listOf(creator.pub, peer.pub), group.members)
        assertFalse(successor.pub in group.members)
        assertFalse(removed.pub in group.members)
        assertTrue(
            "The successor of a removed key must not receive the new epoch",
            creator.envelopesFor(successor).isEmpty()
        )
        assertTrue(creator.envelopesFor(removed).isEmpty())
        val forPeer = creator.envelopesFor(peer).single()
        assertEquals(1, forPeer.epoch)
        assertEquals(listOf(creator.pub, peer.pub), forPeer.members)
        assertEquals(removed.pub, forPeer.removedMember)
        assertFalse(successor.pub in forPeer.encryptedKeys)
        assertEquals(group.members, creator.decryptMeta(creator.newestMeta(), 1).members)
    }

    /**
     * A named successor that already held an independent roster seat must keep it and receive the key.
     * Naming an existing member as successor must not extend the pending removal to that member.
     */
    @Test
    fun `resumed removal keeps a successor who was already a member before the removal`() = runBlocking {
        val creator = Device(1)
        val removed = Device(2)
        val peer = Device(3)
        val successor = Device(4)
        creator.create(listOf(creator.pub, removed.pub, peer.pub, successor.pub))
        creator.interruptRemoval(removed.pub)
        assertEquals(IngestOutcome.APPLIED, creator.ingest(revocationOf(removed, successor)))
        assertEquals(listOf(creator.pub, peer.pub, successor.pub), creator.group().members)

        creator.rotation.resumeIfNeeded()

        val group = creator.group()
        assertNull(creator.pending())
        assertEquals(1, group.keyEpoch)
        assertEquals(listOf(creator.pub, peer.pub, successor.pub), group.members)
        val forSuccessor = creator.envelopesFor(successor).single()
        assertEquals(1, forSuccessor.epoch)
        assertEquals(removed.pub, forSuccessor.removedMember)
        assertEquals(
            creator.groups.getGroupKeyForEpoch(groupId, 1),
            Nip44.decrypt(
                forSuccessor.encryptedKeys.getValue(successor.pub),
                Nip44.getConversationKey(successor.identity.getPrivateKeyBytes(), creator.pub.hexToBytes())
            )
        )
        assertTrue(creator.envelopesFor(removed).isEmpty())
        assertEquals(group.members, creator.decryptMeta(creator.newestMeta(), 1).members)
    }

    /**
     * Rebase an unsigned removal intent after its target revokes. The successor must remain excluded
     * even when a key-write failure interrupts the rebase before plan preparation.
     */
    @Test
    fun `unprepared removal rebased after its member revoked still excludes the successor`() = runBlocking {
        val creator = Device(1)
        val removed = Device(2)
        val peer = Device(3)
        val successor = Device(4)
        creator.create(listOf(creator.pub, removed.pub, peer.pub))
        val intent = RotationIntent(creator.group(), removed.pub, 1)
        creator.journal.insert(
            ControlOperation("rotation:$groupId", RotateGroupKeyUseCase.ROTATION_KIND, json.encodeToString(intent))
        )
        assertEquals(IngestOutcome.APPLIED, creator.ingest(revocationOf(removed, successor)))
        assertEquals(listOf(creator.pub, successor.pub, peer.pub), creator.group().members)

        // Fail the epoch-key write after rebasing but before preparing signed events.
        creator.groupKeys.failNextPut = true
        creator.rotation.resumeIfNeeded()
        val rebased = checkNotNull(creator.pending())
        assertNull(rebased.preparedJson)
        assertFalse(successor.pub in json.decodeFromString<RotationIntent>(rebased.intentJson).group.members)
        assertTrue(creator.sent.isEmpty())

        creator.rotation.resumeIfNeeded()

        val group = creator.group()
        assertNull(creator.pending())
        assertEquals(1, group.keyEpoch)
        assertEquals(listOf(creator.pub, peer.pub), group.members)
        assertTrue(creator.envelopesFor(successor).isEmpty())
        assertEquals(listOf(creator.pub, peer.pub), creator.envelopesFor(peer).single().members)
        assertEquals(group.members, creator.decryptMeta(creator.newestMeta(), 1).members)
    }

    /**
     * Unlike the removal target's replacement, a new joiner receives the epoch key on resume.
     * Corrective metadata must match the final roster and sort after the prepared metadata.
     */
    @Test
    fun `resumed removal after a join grants the joiner an envelope and a newer metadata for the final roster`() =
        runBlocking {
            val creator = Device(1)
            val removed = Device(2)
            val peer = Device(3)
            val joiner = Device(4)
            val originalRoster = listOf(creator.pub, removed.pub, peer.pub)
            creator.create(originalRoster)
            creator.interruptRemoval(removed.pub)
            val planMeta = creator.plan().single { it.eventType == "group_meta" }.event()
            assertEquals(IngestOutcome.APPLIED, creator.ingest(selfJoinOf(joiner, originalRoster)))

            creator.rotation.resumeIfNeeded()

            val group = creator.group()
            assertNull(creator.pending())
            assertEquals(1, group.keyEpoch)
            assertEquals(listOf(creator.pub, peer.pub, joiner.pub), group.members)
            val forJoiner = creator.envelopesFor(joiner).single()
            assertEquals(1, forJoiner.epoch)
            assertEquals(group.members, forJoiner.members)
            assertEquals(removed.pub, forJoiner.removedMember)
            assertEquals(
                creator.groups.getGroupKeyForEpoch(groupId, 1),
                Nip44.decrypt(
                    forJoiner.encryptedKeys.getValue(joiner.pub),
                    Nip44.getConversationKey(joiner.identity.getPrivateKeyBytes(), creator.pub.hexToBytes())
                )
            )
            val newest = creator.newestMeta()
            assertTrue(newest.createdAt > planMeta.createdAt)
            assertEquals(group.members, creator.decryptMeta(newest, 1).members)
            assertEquals(listOf(creator.pub, peer.pub), creator.decryptMeta(planMeta, 1).members)
        }
}
