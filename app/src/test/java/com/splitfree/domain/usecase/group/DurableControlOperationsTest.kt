package com.splitfree.domain.usecase.group

import android.app.Application
import androidx.room.Room
import com.splitfree.data.identity.IdentityManager
import com.splitfree.data.local.AppDatabase
import com.splitfree.data.repository.ControlOperationJournal
import com.splitfree.data.repository.GroupRepository
import com.splitfree.domain.crypto.EventSigner
import com.splitfree.domain.crypto.GroupEncryption
import com.splitfree.domain.crypto.NostrEvent
import com.splitfree.domain.crypto.nip.Nip44
import com.splitfree.domain.model.group.Group
import com.splitfree.domain.model.group.GroupMeta
import com.splitfree.domain.model.group.KeyRevocation
import com.splitfree.domain.model.group.KeyRotation
import com.splitfree.domain.repository.ControlOperation
import com.splitfree.domain.repository.ControlOperationJournalContract
import com.splitfree.domain.repository.EventPublisherContract
import com.splitfree.domain.repository.GroupRepositoryContract
import com.splitfree.domain.repository.SecureStorage
import com.splitfree.domain.repository.SecureStorageException
import com.splitfree.domain.util.hexToBytes
import com.splitfree.test.FakeSecureStorage
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import java.util.UUID
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
class DurableControlOperationsTest {
    private val context: Application get() = RuntimeEnvironment.getApplication()
    private val databaseName = "durable-control-${UUID.randomUUID()}.db"
    private val identityStorage = FailingStorage()
    private val groupStorage = FailingStorage()
    private lateinit var db: AppDatabase
    private lateinit var identity: IdentityManager
    private lateinit var groups: GroupRepository
    private lateinit var journal: ControlOperationJournal
    private lateinit var signer: EventSigner
    private lateinit var lock: ControlOperationLock
    private lateinit var rotation: RotateGroupKeyUseCase
    private lateinit var revocation: RevokeKeyUseCase
    private val encryption = GroupEncryption(mockk { every { shouldCompress(any()) } returns false })
    private val publisher = mockk<EventPublisherContract>()
    private val published = mutableListOf<NostrEvent>()
    private var failPublishAt = 0
    private var publishCalls = 0
    private val peer = pubkey(2)
    private val removed = pubkey(3)
    private val secondRemoved = pubkey(4)
    private lateinit var creator: String
    private val json = Json { ignoreUnknownKeys = true }

    @Before
    fun setup() = runBlocking {
        reopen()
        identity.importKey("00".repeat(31) + "01")
        creator = identity.getPublicKeyHex()
        groups.save(
            Group(
                "g",
                "Trip",
                createdBy = creator,
                createdAt = 1,
                members = listOf(creator, peer, removed, secondRemoved),
                relays = emptyList()
            ),
            encryption.generateGroupKey()
        )
        coEvery { publisher.publishDirect(any(), any(), any(), any(), any()) } coAnswers {
            val event = firstArg<NostrEvent>()
            val operations = journal.getAll("rotation") + journal.getAll("revocation")
            assertTrue(
                "Every envelope is journaled before first possible publication",
                operations.any {
                    it.preparedJson?.contains(event.id) == true
                }
            )
            published += event
            publishCalls++
            if (publishCalls == failPublishAt) error("injected publication boundary")
        }
        Unit
    }

    @After
    fun teardown() {
        db.close()
        context.deleteDatabase(databaseName)
    }

    private fun reopen() {
        if (::db.isInitialized) db.close()
        db = Room.databaseBuilder(context, AppDatabase::class.java, databaseName)
            .addCallback(AppDatabase.SYNC_REVISION_CALLBACK).build()
        identity = IdentityManager(context, identityStorage)
        groups = GroupRepository(db.groupDao(), groupStorage)
        journal = ControlOperationJournal(db.controlOperationDao())
        signer = EventSigner(identity)
        lock = ControlOperationLock()
        rotation = newRotation()
        revocation = newRevocation()
    }

    private fun newRotation(journalContract: ControlOperationJournalContract = journal) = RotateGroupKeyUseCase(
        groups,
        encryption,
        identity,
        signer,
        publisher,
        mockk { coEvery { getEventsByType(any(), any()) } returns emptyList() },
        journalContract,
        lock
    )

    private fun newRevocation(repository: GroupRepositoryContract = groups) = RevokeKeyUseCase(
        identity,
        repository,
        encryption,
        signer,
        publisher,
        journal,
        lock,
        mockk(relaxed = true),
        mockk(relaxed = true)
    )

    @Test
    fun `partial rotation restart completes identical envelopes then changed removal gets next epoch`() = runBlocking {
        failPublishAt = 2
        assertTrue(runCatching { rotation("g", removed) }.isFailure)
        val stored = checkNotNull(journal.get("rotation:g"))
        val prepared = json.decodeFromString<List<PreparedControlEvent>>(checkNotNull(stored.preparedJson))
        val epochOneKey = groups.getGroupKeyForEpoch("g", 1)
        assertEquals(0, groups.getById("g")!!.keyEpoch)
        reopen()
        rotation("g", secondRemoved)
        val group = groups.getById("g")!!
        assertEquals(2, group.keyEpoch)
        assertEquals(listOf(creator, peer), group.members)
        assertEquals(epochOneKey, groups.getGroupKeyForEpoch("g", 1))
        assertNotEquals(epochOneKey, groups.getGroupKeyForEpoch("g", 2))
        assertEquals(prepared.map { it.eventJson }, published.drop(2).take(prepared.size).map { it.toJson() })
        val rotations = published.filter { it.tags.contains(listOf("t", "key_rotation")) }.map { event ->
            val recipient = event.tags.first { it[0] == "p" }[1]
            val key = Nip44.getConversationKey(identity.getPrivateKeyBytes(), recipient.hexToBytes())
            json.decodeFromString<KeyRotation>(Nip44.decrypt(event.content, key))
        }
        assertTrue(rotations.filter { it.epoch == 1 }.all { secondRemoved in it.members && removed !in it.members })
        assertTrue(rotations.filter { it.epoch == 2 }.all { removed !in it.members && secondRemoved !in it.members })
        assertNull(journal.get("rotation:g"))
    }

    @Test
    fun `rotation restart publishes the saved metadata after local epoch already advanced`() = runBlocking {
        failPublishAt = 4
        assertTrue(runCatching { rotation("g", removed) }.isFailure)
        assertEquals(1, groups.getById("g")!!.keyEpoch)
        val metadata = published.last().toJson()
        reopen()
        rotation.resumeIfNeeded()
        assertEquals(metadata, published.last().toJson())
        assertEquals(1, groups.getById("g")!!.keyEpoch)
        assertNull(journal.get("rotation:g"))
    }

    @Test
    fun `crash after secure epoch key write preserves immutable intent across recreation`() = runBlocking {
        groupStorage.failAfterPut = "g:1"
        assertTrue(runCatching { rotation("g", removed) }.isFailure)
        val savedKey = groups.getGroupKeyForEpoch("g", 1)
        assertNotNull(savedKey)
        assertNull(journal.get("rotation:g")!!.preparedJson)
        assertTrue(published.isEmpty())
        reopen()
        rotation("g", secondRemoved)
        assertEquals(savedKey, groups.getGroupKeyForEpoch("g", 1))
        assertEquals(2, groups.getById("g")!!.keyEpoch)
        assertEquals(listOf(creator, peer), groups.getById("g")!!.members)
    }

    @Test
    fun `journal preparation failure cannot publish and retry reuses secure key`() = runBlocking {
        val failingJournal = object : ControlOperationJournalContract by journal {
            override suspend fun prepare(id: String, preparedJson: String) {
                error("injected Room preparation failure")
            }
        }
        assertTrue(runCatching { newRotation(failingJournal)("g", removed) }.isFailure)
        assertTrue(published.isEmpty())
        val key = groups.getGroupKeyForEpoch("g", 1)
        reopen()
        rotation.resumeIfNeeded()
        assertEquals(key, groups.getGroupKeyForEpoch("g", 1))
        assertEquals(1, groups.getById("g")!!.keyEpoch)
    }

    @Test
    fun `partial revocation publication keeps replacement and resumes every original signed event`() = runBlocking {
        failPublishAt = 1
        assertTrue(runCatching { revocation() }.isFailure)
        val replacement = identity.getPendingPublicKeyHex()
        val operation = journal.get(RotateGroupKeyUseCase.REVOCATION_ID)!!
        val prepared = json.decodeFromString<PreparedRevocation>(operation.preparedJson!!)
        assertEquals(2, prepared.events.size)
        assertEquals(creator, identity.getPublicKeyHex())
        assertEquals(creator, groups.getById("g")!!.createdBy)
        assertFalse(operation.intentJson.contains(identity.getPrivateKeyHex()))
        assertFalse(operation.preparedJson!!.contains(identity.getPrivateKeyHex()))
        reopen()
        assertEquals(replacement, revocation())
        assertEquals(prepared.events.map { it.eventJson }, published.drop(1).map { it.toJson() })
        assertEquals(replacement, identity.getPublicKeyHex())
        assertEquals(replacement, groups.getById("g")!!.createdBy)
        assertFalse(creator in groups.getById("g")!!.members)
        assertNull(journal.get(RotateGroupKeyUseCase.REVOCATION_ID))
    }

    @Test
    fun `revocation restart repairs local projection before promoting identity`() = runBlocking {
        val failingGroups = object : GroupRepositoryContract by groups {
            override suspend fun applyAuthenticatedRevocation(
                groupId: String,
                oldPubkey: String,
                newPubkey: String,
                timestamp: Long,
                eventId: String,
                epoch: Int,
                successorProven: Boolean
            ): Boolean {
                error("injected projection failure")
            }
        }
        assertTrue(runCatching { newRevocation(failingGroups)() }.isFailure)
        val replacement = identity.getPendingPublicKeyHex()
        assertEquals(2, published.size)
        assertEquals(creator, identity.getPublicKeyHex())
        reopen()
        revocation.resumeIfNeeded()
        assertEquals(replacement, identity.getPublicKeyHex())
        assertEquals(replacement, groups.getById("g")!!.createdBy)
    }

    @Test
    fun `revocation resumes after active private key write before public mirror`() = runBlocking {
        identityStorage.failBeforePut = "npub"
        assertTrue(runCatching { revocation() }.isFailure)
        val replacement = identity.getPublicKeyHex()
        assertNotEquals(creator, replacement)
        assertNotNull(journal.get(RotateGroupKeyUseCase.REVOCATION_ID))
        reopen()
        revocation.resumeIfNeeded()
        assertEquals(replacement, identity.getPublicKeyHex())
        assertFalse(identity.hasPendingKeyPair())
        assertNull(journal.get(RotateGroupKeyUseCase.REVOCATION_ID))
    }

    @Test
    fun `revocation resumes cleanup after pending private deletion already landed`() = runBlocking {
        identityStorage.failAfterRemove = "nsec_pending"
        assertTrue(runCatching { revocation() }.isFailure)
        val replacement = identity.getPublicKeyHex()
        assertFalse(identity.hasPendingKeyPair())
        assertNotNull(journal.get(RotateGroupKeyUseCase.REVOCATION_ID))
        reopen()
        revocation.resumeIfNeeded()
        assertEquals(replacement, identity.getPublicKeyHex())
        assertNull(journal.get(RotateGroupKeyUseCase.REVOCATION_ID))
        assertTrue(identity.getRevocationEventIds().isEmpty())
    }

    @Test
    fun `revocation intent survives a crash before pending public mirror is written`() = runBlocking {
        identityStorage.failBeforePut = "npub_pending"
        assertTrue(runCatching { revocation() }.isFailure)
        val pending = identity.getPendingPublicKeyHex()
        assertNotNull(pending)
        assertTrue(published.isEmpty())
        reopen()
        revocation.resumeIfNeeded()
        assertEquals(pending, identity.getPublicKeyHex())
    }

    /**
     * Fail after storing the epoch key, then add a member before recovery. Rebase the unsigned intent
     * onto the live roster without changing its removal, epoch or key; later operations must still run.
     */
    @Test
    fun `resuming an unprepared intent rebases onto the live roster then later removal and revocation succeed`() =
        runBlocking {
            groupStorage.failAfterPut = "g:1"
            assertTrue(runCatching { rotation("g", removed) }.isFailure)
            val savedKey = checkNotNull(groups.getGroupKeyForEpoch("g", 1))
            val joiner = pubkey(5)
            assertTrue(groups.applyMemberSelfUpdate("g", joiner, 2, "join", true, "Eve", 0))
            reopen()

            rotation.resumeIfNeeded()

            val group = groups.getById("g")!!
            assertEquals(1, group.keyEpoch)
            assertEquals(listOf(creator, peer, secondRemoved, joiner), group.members)
            assertEquals("Eve", group.memberNames[joiner])
            assertEquals(savedKey, groups.getGroupKeyForEpoch("g", 1))
            assertNull(journal.get("rotation:g"))
            val rotations = decryptedRotations()
            assertEquals(group.members.toSet(), rotations.keys)
            assertTrue(
                rotations.values.all {
                    it.epoch == 1 &&
                        it.members == group.members &&
                        it.removedMember == removed
                }
            )
            assertEquals(listOf(group.members), decryptedMetas(savedKey).map { it.members })

            rotation("g", secondRemoved)
            assertEquals(2, groups.getById("g")!!.keyEpoch)
            assertEquals(listOf(creator, peer, joiner), groups.getById("g")!!.members)
            val replacement = revocation()
            assertEquals(replacement, identity.getPublicKeyHex())
            assertEquals(replacement, groups.getById("g")!!.createdBy)
            assertEquals(listOf(replacement, peer, joiner), groups.getById("g")!!.members)
            assertNull(journal.get(RotateGroupKeyUseCase.REVOCATION_ID))
        }

    /**
     * Interrupt publication, then add a member under the old epoch. Replay the saved events unchanged,
     * adding the joiner's key envelope and newer metadata for the current roster.
     */
    @Test
    fun `resuming a prepared intent after a join appends the joiner's envelope and a corrective meta`() = runBlocking {
        failPublishAt = 2
        assertTrue(runCatching { rotation("g", removed) }.isFailure)
        val plan = json.decodeFromString<List<PreparedControlEvent>>(journal.get("rotation:g")!!.preparedJson!!)
        val key = checkNotNull(groups.getGroupKeyForEpoch("g", 1))
        val joiner = pubkey(5)
        assertTrue(groups.applyMemberSelfUpdate("g", joiner, 2, "join", true, "Eve", 0))
        reopen()

        rotation.resumeIfNeeded()

        val group = groups.getById("g")!!
        assertEquals(1, group.keyEpoch)
        assertEquals(listOf(creator, peer, secondRemoved, joiner), group.members)
        assertEquals(key, groups.getGroupKeyForEpoch("g", 1))
        assertNull(journal.get("rotation:g"))
        val resumed = published.drop(2)
        assertEquals(6, resumed.size)
        val planRotations = plan.filter { it.eventType == "key_rotation" }.map { it.eventJson }
        assertEquals(planRotations, resumed.take(3).map { it.toJson() })
        assertEquals(joiner, resumed[3].tags.first { it[0] == "p" }[1])
        val forJoiner = decryptedRotations().getValue(joiner)
        assertEquals(1, forJoiner.epoch)
        assertEquals(group.members, forJoiner.members)
        assertEquals(removed, forJoiner.removedMember)
        assertEquals(plan.single { it.eventType == "group_meta" }.eventJson, resumed[4].toJson())
        assertTrue(resumed[5].createdAt > resumed[4].createdAt)
        assertEquals(listOf(creator, peer, secondRemoved), decryptMeta(resumed[4], key).members)
        assertEquals(group.members, decryptMeta(resumed[5], key).members)
        assertEquals(1, decryptMeta(resumed[5], key).keyEpoch)
    }

    @Test
    fun `resuming after the epoch landed republishes the saved meta and a corrective one for a later join`() =
        runBlocking {
            failPublishAt = 4
            assertTrue(runCatching { rotation("g", removed) }.isFailure)
            assertEquals(1, groups.getById("g")!!.keyEpoch)
            val savedMeta = published.last()
            val key = checkNotNull(groups.getGroupKeyForEpoch("g", 1))
            val joiner = pubkey(5)
            assertTrue(groups.applyMemberSelfUpdate("g", joiner, 2, "join", true, null, 1))
            reopen()

            rotation.resumeIfNeeded()

            assertEquals(1, groups.getById("g")!!.keyEpoch)
            assertEquals(listOf(creator, peer, secondRemoved, joiner), groups.getById("g")!!.members)
            val metas = published.drop(4).filter { it.tags.contains(listOf("t", "group_meta")) }
            assertEquals(2, metas.size)
            assertEquals(savedMeta.toJson(), metas[0].toJson())
            assertTrue(metas[1].createdAt > savedMeta.createdAt)
            assertEquals(listOf(creator, peer, secondRemoved, joiner), decryptMeta(metas[1], key).members)
            assertNull(journal.get("rotation:g"))
            rotation.resumeIfNeeded()
            assertEquals(metas.size, published.drop(4).count { it.tags.contains(listOf("t", "group_meta")) })
        }

    /**
     * A creator snapshot removes the revoking member from `h` before local projection. Recovery must
     * retire the old key without restoring membership, promote the replacement and clear the journal.
     */
    @Test
    fun `revocation completes with a tombstone when a creator snapshot dropped the user before projection`() =
        runBlocking {
            groups.save(
                Group(
                    "h",
                    "Club",
                    createdBy = peer,
                    createdAt = 1,
                    members = listOf(peer, creator),
                    relays = emptyList()
                ),
                encryption.generateGroupKey()
            )
            failPublishAt = 3
            assertTrue(runCatching { revocation() }.isFailure)
            val replacement = checkNotNull(identity.getPendingPublicKeyHex())
            val prepared = json.decodeFromString<PreparedRevocation>(
                journal.get(RotateGroupKeyUseCase.REVOCATION_ID)!!.preparedJson!!
            )
            assertEquals(4, prepared.events.size)
            assertTrue(
                groups.updateFromMeta(
                    "h", "Club", listOf(peer), emptyList(), 5, createdBy = peer, eventId = "snapshot",
                    expectedKeyEpoch = 0, expectedCreator = peer
                )
            )
            assertEquals(listOf(peer), groups.getById("h")!!.members)
            reopen()

            revocation.resumeIfNeeded()

            assertEquals(replacement, identity.getPublicKeyHex())
            assertFalse(identity.hasPendingKeyPair())
            assertNull(journal.get(RotateGroupKeyUseCase.REVOCATION_ID))
            // Every saved event reaches the mocked publisher unchanged; this assertion ignores order.
            assertEquals(prepared.events.map { it.eventJson }.toSet(), published.drop(3).map { it.toJson() }.toSet())
            assertEquals(prepared.events.size, published.drop(3).size)
            val club = groups.getById("h")!!
            assertEquals(listOf(peer), club.members)
            assertEquals(peer, club.createdBy)
            assertFalse(groups.applyMemberSelfUpdate("h", creator, 10, "rejoin-old", true, null, 0))
            assertTrue(groups.applyMemberSelfUpdate("h", replacement, 11, "rejoin-new", true, null, 0))
            assertEquals(listOf(replacement, peer, removed, secondRemoved), groups.getById("g")!!.members)
            assertEquals(replacement, groups.getById("g")!!.createdBy)
            revocation.resumeIfNeeded()
            assertEquals(3 + prepared.events.size, published.size)
        }

    /**
     * A prepared revocation without a successor proof must remain unproven on replay. Compare retired-key
     * attribution and roster in independent repositories; recovery must not invent a local-only transfer.
     */
    @Test
    fun `resuming a legacy prepared journal projects exactly what peers receive`() = runBlocking {
        val group = groups.getById("g")!!
        val key = checkNotNull(groups.getGroupKeyForEpoch("g", 0))
        val successor = identity.generatePendingKeyPair()
        identity.markRevocationStarted()
        val legacyPayload = json.encodeToString(KeyRevocation(creator, successor, "Key compromised"))
        val meta = GroupMeta(
            group.name,
            group.description,
            successor,
            group.createdAt,
            group.members.map { if (it == creator) successor else it },
            group.relays,
            group.memberNames,
            keyEpoch = group.keyEpoch
        )
        val plan = listOf("key_revocation" to legacyPayload, "group_meta" to json.encodeToString(meta))
            .map { (type, payload) ->
                val event = signer.createSignedEvent("g", type, encryption.encrypt(payload, key))
                PreparedControlEvent("g", type, event.toJson())
            }
        journal.insert(
            ControlOperation(
                RotateGroupKeyUseCase.REVOCATION_ID,
                "revocation",
                json.encodeToString(RevocationIntent(creator, listOf(group))),
                json.encodeToString(PreparedRevocation(successor, plan))
            )
        )
        reopen()

        revocation.resumeIfNeeded()

        // Replay preserves the saved event bytes and order.
        assertEquals(plan.map { it.eventJson }, published.map { it.toJson() })
        // Without a successor proof, retirement does not redirect ledger attribution.
        val retired = groups.retiredIdentities("g")
        assertTrue(retired.isRetired(creator))
        assertEquals(creator, retired.resolve(creator))
        assertTrue(retired.successors.isEmpty())
        // Roster replacement is independent of ledger attribution.
        val roster = groups.getById("g")!!.members
        assertFalse(creator in roster)
        assertTrue(successor in roster)
        // Identity promotion completes even though ledger attribution stays with the retired key.
        assertEquals(successor, identity.getPublicKeyHex())
        assertFalse(identity.hasPendingKeyPair())
        assertNull(journal.get(RotateGroupKeyUseCase.REVOCATION_ID))

        // Apply the same decrypted event to an independent repository and compare projections.
        val peerDb = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).allowMainThreadQueries().build()
        try {
            val peerGroups = GroupRepository(peerDb.groupDao(), FakeSecureStorage())
            peerGroups.save(group, key)
            val event = published.single { it.tags.contains(listOf("t", "key_revocation")) }
            assertTrue(
                newRevocation(peerGroups).handleRevocation(
                    encryption.decrypt(event.content, key),
                    event.pubkey,
                    "g",
                    event.createdAt,
                    event.id
                )
            )
            assertEquals(
                groups.retiredIdentities("g").resolve(creator),
                peerGroups.retiredIdentities("g").resolve(creator)
            )
            assertEquals(groups.retiredIdentities("g").successors, peerGroups.retiredIdentities("g").successors)
            assertEquals(groups.getById("g")!!.members, peerGroups.getById("g")!!.members)
        } finally {
            peerDb.close()
        }
    }

    @Test
    fun `revocation refuses before journaling when a group's current key is missing`() = runBlocking {
        groups.save(
            Group("h", "Club", createdBy = peer, createdAt = 1, members = listOf(peer, creator), relays = emptyList()),
            encryption.generateGroupKey()
        )
        groups.updateKeyEpoch("h", 3)
        assertTrue(runCatching { revocation() }.isFailure)
        assertNull(journal.get(RotateGroupKeyUseCase.REVOCATION_ID))
        assertFalse(identity.hasPendingKeyPair())
        assertTrue(published.isEmpty())
        // Refusal leaves no pending revocation to block a rotation in g.
        rotation("g", removed)
        assertEquals(1, groups.getById("g")!!.keyEpoch)
    }

    @Test
    fun `unjournaled ambiguous pending identity cannot be discarded or silently replaced`() = runBlocking {
        val pending = identity.generatePendingKeyPair()
        identity.markRevocationStarted()
        reopen()
        revocation.resumeIfNeeded()
        assertEquals(pending, identity.getPendingPublicKeyHex())
        assertTrue(identity.getRevocationEventIds().isEmpty())
        assertTrue(runCatching { revocation() }.isFailure)
        assertEquals(pending, identity.getPendingPublicKeyHex())
        assertEquals(creator, identity.getPublicKeyHex())
        assertTrue(published.isEmpty())
    }

    @Test
    fun `separate rotation services share serialization and never claim the same epoch`() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var first = true
        coEvery { publisher.publishDirect(any(), any(), any(), any(), any()) } coAnswers {
            if (first) {
                first = false
                entered.complete(Unit)
                release.await()
            }
        }
        val firstService = newRotation()
        val secondService = newRotation()
        val a = async { firstService("g", removed) }
        entered.await()
        val b = async { secondService("g", secondRemoved) }
        release.complete(Unit)
        a.await()
        b.await()
        assertEquals(2, groups.getById("g")!!.keyEpoch)
        assertEquals(listOf(creator, peer), groups.getById("g")!!.members)
    }

    private fun identitySwitch(contract: ControlOperationJournalContract = journal) =
        IdentitySwitchCoordinator(identity, contract, lock, Dispatchers.IO)

    private suspend fun prepareInterruptedRevocation(): ControlOperation {
        failPublishAt = publishCalls + 1
        assertTrue(runCatching { revocation() }.isFailure)
        failPublishAt = 0
        return checkNotNull(journal.get(RotateGroupKeyUseCase.REVOCATION_ID))
    }

    private suspend fun makeLegacy(operation: ControlOperation): PreparedRevocation {
        val prepared = json.decodeFromString<PreparedRevocation>(operation.preparedJson!!)
        val legacy = prepared.copy(events = prepared.events.map { it.copy(projection = null) })
        journal.amend(operation.id, operation.preparedJson, json.encodeToString(legacy))
        return legacy
    }

    @Test
    fun `self contained revocation survives epoch key loss and publishes exact original envelopes`() = runBlocking {
        val operation = prepareInterruptedRevocation()
        val prepared = json.decodeFromString<PreparedRevocation>(operation.preparedJson!!)
        val payload = prepared.events.first().projection!!.payload
        groupStorage.remove("g:0")
        groupStorage.remove("g")
        published.clear()
        reopen()
        revocation.resumeIfNeeded()
        assertEquals(prepared.events.map { it.eventJson }, published.map { it.toJson() })
        assertEquals(prepared.newPubkey, identity.getPublicKeyHex())
        assertTrue(json.decodeFromString<KeyRevocation>(payload).provesSuccessor("g"))
        assertEquals(prepared.newPubkey, groups.retiredIdentities("g").resolve(creator))
        assertNull(journal.get(operation.id))
    }

    @Test
    fun `legacy missing epoch key fails before any resumed publication and keeps original bytes`() = runBlocking {
        val operation = prepareInterruptedRevocation()
        val legacy = makeLegacy(operation)
        groupStorage.remove("g:0")
        groupStorage.remove("g")
        published.clear()
        reopen()
        assertTrue(runCatching { revocation.resumeIfNeeded() }.isFailure)
        assertTrue(published.isEmpty())
        assertEquals(json.encodeToString(legacy), journal.get(operation.id)!!.preparedJson)
        assertEquals(legacy.newPubkey, identity.getPendingPublicKeyHex())
        assertEquals(creator, identity.getPublicKeyHex())
    }

    @Test
    fun `legacy upgrade persists whole batch before publication and no longer needs decryption key`() = runBlocking {
        val legacy = makeLegacy(prepareInterruptedRevocation())
        failPublishAt = publishCalls + 1
        assertTrue(runCatching { revocation.resumeIfNeeded() }.isFailure)
        val upgraded = json.decodeFromString<PreparedRevocation>(
            journal.get(RotateGroupKeyUseCase.REVOCATION_ID)!!.preparedJson!!
        )
        assertEquals(legacy.events.map { it.eventJson }, upgraded.events.map { it.eventJson })
        assertNotNull(upgraded.events.first().projection)
        groupStorage.remove("g:0")
        groupStorage.remove("g")
        published.clear()
        failPublishAt = 0
        reopen()
        revocation.resumeIfNeeded()
        assertEquals(legacy.events.map { it.eventJson }, published.map { it.toJson() })
    }

    @Test
    fun `missing last group legacy key preflights whole batch without publishing earlier groups`() = runBlocking {
        val second = groups.getById("g")!!.copy(id = "z")
        groups.save(second, encryption.generateGroupKey())
        val operation = prepareInterruptedRevocation()
        makeLegacy(operation)
        groupStorage.remove("z:0")
        groupStorage.remove("z")
        published.clear()
        reopen()
        assertTrue(runCatching { revocation.resumeIfNeeded() }.isFailure)
        assertTrue(published.isEmpty())
        val unchanged = json.decodeFromString<PreparedRevocation>(journal.get(operation.id)!!.preparedJson!!)
        assertTrue(unchanged.events.all { it.projection == null })
    }

    @Test
    fun `tampered last projection cannot publish a valid earlier group`() = runBlocking {
        groups.save(groups.getById("g")!!.copy(id = "z"), encryption.generateGroupKey())
        val operation = prepareInterruptedRevocation()
        val prepared = json.decodeFromString<PreparedRevocation>(operation.preparedJson!!)
        val corrupted = prepared.copy(
            events = prepared.events.map {
                if (it.groupId == "z" && it.projection != null) {
                    it.copy(
                        projection = it.projection.copy(
                            payload = it.projection.payload.replace("Key compromised", "forged")
                        )
                    )
                } else {
                    it
                }
            }
        )
        journal.amend(operation.id, operation.preparedJson, json.encodeToString(corrupted))
        published.clear()
        reopen()
        assertTrue(runCatching { revocation.resumeIfNeeded() }.isFailure)
        assertTrue(published.isEmpty())
        assertEquals(creator, identity.getPublicKeyHex())
    }

    @Test
    fun `distinct identity switch archives possibly public successor and unblocks unrelated rotation`() = runBlocking {
        val operation = prepareInterruptedRevocation()
        val replacement = identity.getPendingPublicKeyHex()
        val imported = identitySwitch().importKey("00".repeat(31) + "05")
        assertFalse(identity.hasPendingKeyPair())
        assertEquals(replacement, identity.getArchivedPendingPublicKeyHex(creator))
        assertNull(journal.get(operation.id))
        assertEquals(operation.preparedJson, journal.getAll("archived:revocation").single().preparedJson)
        published.clear()
        reopen()
        identitySwitch().resumeIfNeeded()
        revocation.resumeIfNeeded()
        groups.save(
            Group(
                "other",
                "Other",
                createdBy = imported,
                createdAt = 1,
                members = listOf(imported, peer),
                relays = emptyList()
            ),
            encryption.generateGroupKey()
        )
        rotation("other", peer)
        assertEquals(1, groups.getById("other")!!.keyEpoch)
        assertTrue(published.none { it.tags.contains(listOf("g", "g")) })
        assertEquals(replacement, identity.getArchivedPendingPublicKeyHex(creator))
    }

    @Test
    fun `same identity switch keeps its exact pending journal and successor`() = runBlocking {
        val operation = prepareInterruptedRevocation()
        val replacement = identity.getPendingPublicKeyHex()
        identitySwitch().importKey("00".repeat(31) + "01")
        reopen()
        assertEquals(operation, journal.get(operation.id))
        assertEquals(replacement, identity.getPendingPublicKeyHex())
        assertTrue(journal.getAll("archived:revocation").isEmpty())
    }

    @Test
    fun `returning to archived identity restores pending key and original journal then completes`() = runBlocking {
        val operation = prepareInterruptedRevocation()
        val replacement = identity.getPendingPublicKeyHex()
        identitySwitch().importKey("00".repeat(31) + "05")
        reopen()
        identitySwitch().importKey("00".repeat(31) + "01")
        assertEquals(operation, journal.get(operation.id))
        assertEquals(replacement, identity.getPendingPublicKeyHex())
        published.clear()
        revocation.resumeIfNeeded()
        assertEquals(replacement, identity.getPublicKeyHex())
        assertEquals(
            json.decodeFromString<PreparedRevocation>(operation.preparedJson!!).events.map { it.eventJson },
            published.map { it.toJson() }
        )
    }

    @Test
    fun `identity switch resumes after secure stage landed before Room intent`() = runBlocking {
        val operation = prepareInterruptedRevocation()
        identityStorage.failAfterPut = "identity_switch"
        assertTrue(runCatching { identitySwitch().importKey("00".repeat(31) + "05") }.isFailure)
        val target = identity.stagedIdentitySwitch()!!
        assertNull(journal.get(IdentitySwitchCoordinator.SWITCH_ID))
        assertEquals(creator, identity.getPublicKeyHex())
        reopen()
        identitySwitch().resumeIfNeeded()
        assertEquals(target.newPubkey, identity.getPublicKeyHex())
        assertNull(journal.get(operation.id))
        assertNotNull(identity.getArchivedPendingPublicKeyHex(creator))
    }

    @Test
    fun `identity switch resumes every active write and archive cleanup boundary`() = runBlocking {
        val boundaries = listOf(
            "before:nsec", "after:nsec", "before:npub", "after:npub",
            "after:identity_pending_archive", "remove:npub_pending", "remove:revocation_event_ids",
            "remove:revocation_start", "remove:nsec_pending", "remove:identity_switch"
        )
        for (boundary in boundaries) {
            val originalKey = identity.getPrivateKeyHex()
            val operation = prepareInterruptedRevocation()
            val replacement = identity.getPendingPublicKeyHex()
            when {
                boundary.startsWith("before:") -> identityStorage.failBeforePut = boundary.substringAfter(':')
                boundary.startsWith("after:") ->
                    identityStorage.failAfterPut =
                        if (boundary.endsWith(
                                "identity_pending_archive"
                            )
                        ) {
                            "identity_pending_archive:$creator"
                        } else {
                            boundary.substringAfter(':')
                        }
                else -> identityStorage.failAfterRemove = boundary.substringAfter(':')
            }
            assertTrue(boundary, runCatching { identitySwitch().importKey("00".repeat(31) + "05") }.isFailure)
            reopen()
            identitySwitch().resumeIfNeeded()
            assertEquals(boundary, pubkey(5), identity.getPublicKeyHex())
            assertEquals(boundary, replacement, identity.getArchivedPendingPublicKeyHex(creator))
            assertNull(journal.get(operation.id))
            assertNull(identity.stagedIdentitySwitch())
            assertNull(journal.get(IdentitySwitchCoordinator.SWITCH_ID))
            identitySwitch().importKey(originalKey)
            revocation.resumeIfNeeded()
            creator = identity.getPublicKeyHex()
        }
    }

    @Test
    fun `identity switch journal move and completion failures resume without losing original plan`() = runBlocking {
        val operation = prepareInterruptedRevocation()
        val replacement = identity.getPendingPublicKeyHex()
        var failed = false
        val failing = object : ControlOperationJournalContract by journal {
            override suspend fun move(operation: ControlOperation, id: String, kind: String) {
                journal.move(operation, id, kind)
                if (!failed) {
                    failed = true
                    error("after atomic journal move")
                }
            }
        }
        assertTrue(runCatching { identitySwitch(failing).importKey("00".repeat(31) + "05") }.isFailure)
        reopen()
        identitySwitch().resumeIfNeeded()
        assertEquals(replacement, identity.getArchivedPendingPublicKeyHex(creator))
        assertEquals(operation.preparedJson, journal.getAll("archived:revocation").single().preparedJson)
        assertNull(journal.get(IdentitySwitchCoordinator.SWITCH_ID))
    }

    @Test
    fun `identity switch waits for shared revocation lock before staging an unrelated key`() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        coEvery { publisher.publishDirect(any(), any(), any(), any(), any()) } coAnswers {
            entered.complete(Unit)
            release.await()
        }
        val revoke = async(Dispatchers.IO) { revocation() }
        entered.await()
        val switched = async(Dispatchers.IO) { identitySwitch().importKey("00".repeat(31) + "05") }
        assertEquals(creator, identity.getPublicKeyHex())
        assertNull(identity.stagedIdentitySwitch())
        release.complete(Unit)
        revoke.await()
        assertEquals(pubkey(5), switched.await())
        assertNull(journal.get(RotateGroupKeyUseCase.REVOCATION_ID))
        assertFalse(identity.hasPendingKeyPair())
    }

    @Test
    fun `explicit import after staged wrapping key loss archives obsolete switch and restores access`() = runBlocking {
        val operation = prepareInterruptedRevocation()
        identityStorage.failBeforePut = "nsec"
        assertTrue(runCatching { identitySwitch().importKey("00".repeat(31) + "05") }.isFailure)
        val abandoned = journal.get(IdentitySwitchCoordinator.SWITCH_ID)!!
        identityStorage.keyLost = true
        reopen()
        assertTrue(runCatching { identitySwitch().resumeIfNeeded() }.isFailure)
        assertTrue(runCatching { identitySwitch().importKey("not a private key") }.isFailure)
        assertEquals(abandoned, journal.get(IdentitySwitchCoordinator.SWITCH_ID))
        assertEquals(0, identityStorage.resets)
        assertEquals(pubkey(6), identitySwitch().importKey("00".repeat(31) + "06"))
        assertEquals(1, identityStorage.resets)
        assertEquals(abandoned.intentJson, journal.getAll("archived:identity-switch").single().intentJson)
        assertEquals(operation.preparedJson, journal.getAll("archived:revocation").single().preparedJson)
        assertNull(journal.get(IdentitySwitchCoordinator.SWITCH_ID))
        reopen()
        identitySwitch().resumeIfNeeded()
        assertEquals(pubkey(6), identity.getPublicKeyHex())
    }

    @Test
    fun `transient staged key read failure cannot supersede or reset the switch`() = runBlocking {
        prepareInterruptedRevocation()
        identityStorage.failBeforePut = "nsec"
        assertTrue(runCatching { identitySwitch().importKey("00".repeat(31) + "05") }.isFailure)
        val staged = identity.stagedIdentitySwitch()
        val operation = journal.get(IdentitySwitchCoordinator.SWITCH_ID)
        identityStorage.transientFailure = SecureStorageException("temporary")
        assertTrue(runCatching { identitySwitch().importKey("00".repeat(31) + "06") }.isFailure)
        identityStorage.transientFailure = null
        assertEquals(0, identityStorage.resets)
        assertEquals(staged, identity.stagedIdentitySwitch())
        assertEquals(operation, journal.get(IdentitySwitchCoordinator.SWITCH_ID))
        reopen()
        identitySwitch().resumeIfNeeded()
        assertEquals(pubkey(5), identity.getPublicKeyHex())
    }

    @Test
    fun `unknown previous identity preserves readable pending successor when restoring a key`() = runBlocking {
        val pending = identity.generatePendingKeyPair()
        identity.setRevocationEventIds(listOf("possibly-public"))
        identityStorage.hideActive = true
        reopen()
        identitySwitch().importKey("00".repeat(31) + "05")
        assertEquals(pending, identity.getPendingPublicKeyHex())
        assertEquals(listOf("possibly-public"), identity.getRevocationEventIds())
        assertTrue(journal.getAll("archived:revocation").isEmpty())
        assertEquals(pubkey(5), identity.getPublicKeyHex())
    }

    @Test
    fun `crash after final switch journal deletion does not recreate or retarget completed switch`() = runBlocking {
        prepareInterruptedRevocation()
        var failed = false
        val failing = object : ControlOperationJournalContract by journal {
            override suspend fun complete(id: String) {
                journal.complete(id)
                if (id == IdentitySwitchCoordinator.SWITCH_ID && !failed) {
                    failed = true
                    error("after final journal delete")
                }
            }
        }
        assertTrue(runCatching { identitySwitch(failing).importKey("00".repeat(31) + "05") }.isFailure)
        assertNull(identity.stagedIdentitySwitch())
        assertNull(journal.get(IdentitySwitchCoordinator.SWITCH_ID))
        reopen()
        identitySwitch().resumeIfNeeded()
        assertEquals(pubkey(5), identity.getPublicKeyHex())
        assertEquals(1, journal.getAll("archived:revocation").size)
    }

    @Test
    fun `invalid legacy metadata preflights before publishing an earlier valid revocation`() = runBlocking {
        val operation = prepareInterruptedRevocation()
        val legacy = makeLegacy(operation)
        val key = groups.getGroupKeyForEpoch("g", 0)!!
        val invalid = legacy.copy(
            events = legacy.events.map {
                if (it.eventType == "group_meta") {
                    val meta = json.decodeFromString<GroupMeta>(encryption.decrypt(it.event().content, key))
                    it.copy(
                        eventJson = signer.createSignedEvent(
                            "g",
                            "group_meta",
                            encryption.encrypt(json.encodeToString(meta.copy(keyEpoch = 99)), key)
                        ).toJson()
                    )
                } else {
                    it
                }
            }
        )
        journal.amend(operation.id, json.encodeToString(legacy), json.encodeToString(invalid))
        published.clear()
        reopen()
        assertTrue(runCatching { revocation.resumeIfNeeded() }.isFailure)
        assertTrue(published.isEmpty())
        assertEquals(json.encodeToString(invalid), journal.get(operation.id)!!.preparedJson)
        assertEquals(creator, identity.getPublicKeyHex())
    }

    @Test
    fun `retrying interrupted identity generation returns the staged target without generating another`() =
        runBlocking {
            identityStorage.failBeforePut = "npub"
            assertTrue(runCatching { identitySwitch().generateKeyPair() }.isFailure)
            val staged = identity.stagedIdentitySwitch()!!
            reopen()
            assertEquals(staged.newPubkey, identitySwitch().generateKeyPair())
            assertEquals(staged.newPubkey, identity.getPublicKeyHex())
            assertNull(identity.stagedIdentitySwitch())
            assertNull(journal.get(IdentitySwitchCoordinator.SWITCH_ID))
        }

    @Test
    fun `identity guarded local write refuses stale identity and unfinished switch`() = runBlocking {
        var writes = 0
        identitySwitch().withIdentity(creator) { writes++ }
        assertEquals(1, writes)
        assertTrue(runCatching { identitySwitch().withIdentity(pubkey(5)) { writes++ } }.isFailure)
        identity.stageIdentitySwitch("00".repeat(31) + "05")
        assertTrue(runCatching { identitySwitch().withIdentity(creator) { writes++ } }.isFailure)
        assertEquals(1, writes)
    }

    @Test
    fun `completed local rotation stores original authenticated payloads and exact metadata facts`() = runBlocking {
        rotation("g", removed)
        val row = db.groupDao().getById("g")!!
        val projection = json.decodeFromString<com.splitfree.domain.model.group.GroupProjection>(row.projectionJson)
        val rotations = projection.facts.filter { it.kind == "rotation" }
        val sentRotations = published.filter { it.tags.contains(listOf("t", "key_rotation")) }
        assertEquals(sentRotations.map { it.id }.toSet(), rotations.map { it.id }.toSet())
        for (event in sentRotations) {
            val recipient = event.tags.single { it.first() == "p" }[1]
            val key = Nip44.getConversationKey(identity.getPrivateKeyBytes(), recipient.hexToBytes())
            val raw = json.decodeFromString<KeyRotation>(Nip44.decrypt(event.content, key))
            assertEquals(raw, rotations.single { it.id == event.id }.rotation)
        }
        val metaEvent = published.single { it.tags.contains(listOf("t", "group_meta")) }
        val rawMeta = decryptMeta(metaEvent, groups.getGroupKeyForEpoch("g", 1)!!)
        assertEquals(rawMeta, projection.facts.single { it.id == metaEvent.id }.meta)
    }

    private fun pubkey(value: Byte): String = NostrEvent.pubkeyFromPrivkey(ByteArray(32).also { it[31] = value })

    /** Last captured rotation per recipient, decrypted using the current sender identity. */
    private fun decryptedRotations(): Map<String, KeyRotation> = published
        .filter { it.tags.contains(listOf("t", "key_rotation")) }
        .associate { event ->
            val recipient = event.tags.first { it[0] == "p" }[1]
            val key = Nip44.getConversationKey(identity.getPrivateKeyBytes(), recipient.hexToBytes())
            recipient to json.decodeFromString<KeyRotation>(Nip44.decrypt(event.content, key))
        }

    private fun decryptMeta(event: NostrEvent, key: String): GroupMeta =
        json.decodeFromString(encryption.decrypt(event.content, key))

    private fun decryptedMetas(key: String): List<GroupMeta> =
        published.filter { it.tags.contains(listOf("t", "group_meta")) }.map { decryptMeta(it, key) }

    private class FailingStorage(private val storage: FakeSecureStorage = FakeSecureStorage()) :
        SecureStorage by storage {
        var keyLost: Boolean
            get() = storage.keyLost
            set(value) {
                storage.keyLost = value
            }
        var transientFailure: SecureStorageException?
            get() = storage.transientFailure
            set(value) {
                storage.transientFailure = value
            }
        val resets: Int get() = storage.resets
        var hideActive = false
        override fun getString(key: String, default: String?): String? =
            if (hideActive && (key == "nsec" || key == "npub")) default else storage.getString(key, default)
        var failBeforePut: String? = null
        var failAfterPut: String? = null
        var failAfterRemove: String? = null
        override fun putString(key: String, value: String) {
            if (key == failBeforePut) {
                failBeforePut = null
                throw SecureStorageException("injected before $key")
            }
            storage.putString(key, value)
            if (key == "nsec") hideActive = false
            if (key == failAfterPut) {
                failAfterPut = null
                throw SecureStorageException("injected after $key")
            }
        }
        override fun remove(key: String) {
            storage.remove(key)
            if (key == failAfterRemove) {
                failAfterRemove = null
                throw SecureStorageException("injected after deleting $key")
            }
        }
    }
}
