package com.splitfree.domain.usecase.export

import android.app.Application
import androidx.room.Room
import com.splitfree.data.local.AppDatabase
import com.splitfree.data.repository.EventRepository
import com.splitfree.data.repository.GroupRepository
import com.splitfree.data.util.CompressionUtil
import com.splitfree.domain.crypto.GroupEncryption
import com.splitfree.domain.crypto.NostrEvent
import com.splitfree.domain.model.export.SplitFreeExport
import com.splitfree.domain.model.group.CreatorTransition
import com.splitfree.domain.model.group.Group
import com.splitfree.domain.model.group.GroupIdentity
import com.splitfree.domain.model.group.GroupMeta
import com.splitfree.domain.model.group.GroupProjection
import com.splitfree.domain.model.group.KeyRevocation
import com.splitfree.domain.repository.IdentityContract
import com.splitfree.domain.util.RelayDefaults
import com.splitfree.domain.util.hexToBytes
import com.splitfree.domain.util.toHex
import com.splitfree.domain.validation.EventValidator
import com.splitfree.test.FakeSecureStorage
import io.mockk.every
import io.mockk.mockk
import java.util.Base64
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
class CreatorBootstrapBackupRoomTest {
    private val databases = mutableListOf<AppDatabase>()
    private val rootKey = ByteArray(32) { 1 }
    private val successorKey = ByteArray(32) { 2 }
    private val memberKey = ByteArray(32) { 3 }
    private val root = NostrEvent.pubkeyFromPrivkey(rootKey)
    private val successor = NostrEvent.pubkeyFromPrivkey(successorKey)
    private val member = NostrEvent.pubkeyFromPrivkey(memberKey)
    private val createdAt = 1_700_000_000L
    private val groupId = GroupIdentity.derive(root, createdAt)
    private val key = Base64.getEncoder().encodeToString(ByteArray(32) { 7 })
    private val json = Json
    private val identity = mockk<IdentityContract> {
        every { getPrivateKeyBytes() } answers { memberKey.copyOf() }
        every { getPublicKeyBytes() } returns member.hexToBytes()
        every { getPublicKeyHex() } returns member
    }

    private inner class Device {
        val db = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), AppDatabase::class.java)
            .allowMainThreadQueries().build().also { databases += it }
        val storage = FakeSecureStorage()
        val groups = GroupRepository(db.groupDao(), storage)
        val events = EventRepository(db, db.eventDao())
        val exporter = ExportGroupUseCase(events, groups, identity)
        val importer = ImportGroupUseCase(events, groups, GroupEncryption(CompressionUtil), EventValidator(), identity)
        suspend fun current() = checkNotNull(groups.getById(groupId))
        suspend fun state() =
            json.decodeFromString<GroupProjection>(checkNotNull(db.groupDao().getById(groupId)).projectionJson)
    }

    @After
    fun closeDatabases() = databases.forEach { it.close() }

    private fun group(epoch: Int = 1) = Group(
        groupId,
        "Trip",
        createdBy = root,
        createdAt = createdAt,
        members = listOf(root, member),
        relays = RelayDefaults.DEFAULT_RELAYS,
        keyEpoch = epoch
    )

    private fun transition(
        oldKey: ByteArray = rootKey,
        newKey: ByteArray = successorKey,
        at: Long = createdAt + 20,
        epoch: Int = 0
    ): CreatorTransition {
        val old = NostrEvent.pubkeyFromPrivkey(oldKey)
        val new = NostrEvent.pubkeyFromPrivkey(newKey)
        val revocation =
            KeyRevocation(old, new, successorProof = KeyRevocation.proveSuccessor(groupId, old, new, newKey))
        val event = NostrEvent(
            pubkey = old,
            createdAt = at,
            kind = 30078,
            tags = listOf(listOf("g", groupId), listOf("t", "key_revocation")),
            content = json.encodeToString(revocation)
        ).sign(oldKey)
        return CreatorTransition.sign(groupId, event, epoch, revocation, oldKey)
    }

    private fun authenticate(export: SplitFreeExport) = export.copy(hmac = ExportMac.compute(export, memberKey).toHex())

    @Test
    fun `empty history current epoch root survives export restore and repository recreation`() = runBlocking {
        val source = Device()
        source.groups.save(group(), key)
        val exported = json.decodeFromString<SplitFreeExport>(source.exporter(groupId))
        assertEquals(3, exported.version)
        assertEquals(root, exported.rootCreator)
        assertEquals(createdAt, exported.rootCreatedAt)
        assertTrue(exported.events.isEmpty())
        val restored = Device()
        assertEquals(0, restored.importer(exported))
        val reread = GroupRepository(restored.db.groupDao(), restored.storage).getById(groupId)!!
        assertEquals(root, reread.createdBy)
        assertEquals(root, reread.originalCreator)
        assertEquals(createdAt, reread.createdAt)
        assertEquals(1, reread.keyEpoch)
        assertNull(restored.groups.getGroupKeyForEpoch(groupId, 0))
    }

    @Test
    fun `successor certificate survives backup with no historical event or old epoch key`() = runBlocking {
        val source = Device()
        val proof = transition()
        source.groups.save(group(), key)
        assertTrue(source.groups.mergeCreatorBootstrap(groupId, root, createdAt, listOf(proof)))
        val restored = Device()
        assertEquals(0, restored.importer(source.exporter(groupId)))
        assertEquals(successor, restored.current().createdBy)
        assertEquals(root, restored.current().originalCreator)
        assertEquals(listOf(proof), restored.current().creatorTransitions)
        assertEquals(root, restored.state().checkpoint.createdBy)
        assertEquals(listOf(proof.fact()), restored.state().facts)
        assertEquals(1, restored.current().keyEpoch)
        assertNull(restored.groups.getGroupKeyForEpoch(groupId, 0))
        assertEquals(successor, restored.groups.retiredIdentities(groupId).resolve(root))
    }

    @Test
    fun `bootstrap never persists a claimed successor as its checkpoint`() = runBlocking {
        val device = Device()
        val proof = transition()
        device.groups.save(group().copy(createdBy = member, creatorTransitions = listOf(proof)), key)
        assertEquals(root, device.state().checkpoint.createdBy)
        assertEquals(successor, device.current().createdBy)
    }

    @Test
    fun `both certificate arrival orders retain losing forks and choose canonical minimum`() = runBlocking {
        val proofs = listOf(transition(), transition(newKey = memberKey))
        val winner = proofs.minBy { it.eventId }
        for (order in listOf(proofs, proofs.reversed())) {
            val source = Device()
            source.groups.save(group(), key)
            for (proof in order) {
                assertTrue(
                    source.groups.mergeCreatorBootstrap(groupId, root, createdAt, listOf(proof))
                )
            }
            assertEquals(winner.newPubkey, source.current().createdBy)
            assertEquals(proofs.toSet(), source.current().creatorTransitions.toSet())
            val restored = Device()
            restored.importer(source.exporter(groupId))
            assertEquals(winner.newPubkey, restored.current().createdBy)
            assertEquals(proofs.toSet(), restored.current().creatorTransitions.toSet())
            assertEquals(root, restored.state().checkpoint.createdBy)
        }
    }

    @Test
    fun `ordinary revocation and matching certificate share one exact fact in either order`() = runBlocking {
        val proof = transition(epoch = 1)
        for (proofFirst in listOf(true, false)) {
            val device = Device()
            device.groups.save(group(), key)
            suspend fun merge() = device.groups.mergeCreatorBootstrap(groupId, root, createdAt, listOf(proof))
            suspend fun revoke() = device.groups.applyAuthenticatedRevocation(
                groupId,
                root,
                successor,
                proof.timestamp,
                proof.eventId,
                proof.epoch,
                true
            )
            if (proofFirst) {
                assertTrue(merge())
                assertTrue(revoke())
            } else {
                assertTrue(revoke())
                assertTrue(merge())
            }
            assertEquals(listOf(proof.fact()), device.state().facts)
            assertEquals(listOf(proof), device.current().creatorTransitions)
        }
    }

    @Test
    fun `preview is read only and cannot resurrect a successor retired in local history`() = runBlocking {
        val device = Device()
        val proof = transition()
        device.groups.save(group(), key)
        device.groups.applyAuthenticatedRevocation(groupId, successor, "", createdAt + 30, "retired", 1, false)
        val before = device.db.groupDao().getById(groupId)
        val preview = device.groups.previewCreatorBootstrap(groupId, root, createdAt, listOf(proof))!!
        assertEquals("", preview.createdBy)
        assertEquals(before, device.db.groupDao().getById(groupId))
        assertTrue(device.groups.mergeCreatorBootstrap(groupId, root, createdAt, listOf(proof)))
        assertEquals("", device.current().createdBy)
        assertTrue(device.groups.mergeCreatorBootstrap(groupId, root, createdAt, emptyList()))
        assertEquals("", device.current().createdBy)
    }

    @Test
    fun `member carried evidence changes creator without granting metadata authority`() = runBlocking {
        val device = Device()
        val proof = transition()
        device.groups.save(group(), key)
        val meta = GroupMeta(
            "Forged name",
            createdBy = member,
            createdAt = createdAt,
            members = listOf(member),
            relays = RelayDefaults.DEFAULT_RELAYS,
            originalCreator = root,
            creatorTransitions = listOf(proof)
        )
        assertTrue(device.groups.applyAuthenticatedMeta(groupId, meta, member, createdAt + 40, "member-meta", 1))
        assertEquals(successor, device.current().createdBy)
        assertEquals("Trip", device.current().name)
        assertEquals(listOf(successor, member), device.current().members)
        assertEquals(listOf(proof), device.current().creatorTransitions)
    }

    @Test
    fun `bootstrap validates root signatures reachability and exact event meaning before mutation`() = runBlocking {
        val device = Device()
        val proof = transition()
        device.groups.save(group(), key)
        val before = device.db.groupDao().getById(groupId)
        val invalid = listOf(
            listOf(proof.copy(timestamp = proof.timestamp + 1)),
            listOf(transition(oldKey = memberKey)),
            List(CreatorTransition.MAX_TRANSITIONS + 1) { proof }
        )
        for (proofs in invalid) {
            assertFalse(device.groups.mergeCreatorBootstrap(groupId, root, createdAt, proofs))
            assertNull(device.groups.previewCreatorBootstrap(groupId, root, createdAt, proofs))
            assertEquals(before, device.db.groupDao().getById(groupId))
        }
        assertFalse(device.groups.mergeCreatorBootstrap(groupId, member, createdAt, emptyList()))
        assertEquals(before, device.db.groupDao().getById(groupId))
        device.groups.applyAuthenticatedRevocation(groupId, root, successor, proof.timestamp, proof.eventId, 1, true)
        val conflicting = device.db.groupDao().getById(groupId)
        assertFalse(device.groups.mergeCreatorBootstrap(groupId, root, createdAt, listOf(proof)))
        assertEquals(conflicting, device.db.groupDao().getById(groupId))
    }

    @Test
    fun `certificate event id cannot collide with a different control kind`() = runBlocking {
        val device = Device()
        val proof = transition()
        device.groups.save(group(), key)
        val meta = GroupMeta(
            "Trip",
            createdBy = root,
            createdAt = createdAt,
            members = listOf(root, member),
            relays = RelayDefaults.DEFAULT_RELAYS
        )
        device.groups.applyAuthenticatedMeta(groupId, meta, root, proof.timestamp, proof.eventId, 0)
        val before = device.db.groupDao().getById(groupId)
        assertFalse(device.groups.mergeCreatorBootstrap(groupId, root, createdAt, listOf(proof)))
        assertEquals(before, device.db.groupDao().getById(groupId))
    }

    @Test
    fun `invalid MAC covered bootstrap is rejected before keys or rows are written`() = runBlocking {
        val source = Device()
        source.groups.save(group(), key)
        val backup = json.decodeFromString<SplitFreeExport>(source.exporter(groupId))
        val proof = transition()
        val invalid = listOf(
            backup.copy(rootCreator = member),
            backup.copy(rootCreatedAt = createdAt + 1),
            backup.copy(rootCreator = ""),
            backup.copy(rootCreator = "", rootCreatedAt = 0, creatorTransitions = listOf(proof)),
            backup.copy(creatorTransitions = listOf(proof.copy(epoch = 1))),
            backup.copy(creatorTransitions = listOf(transition(oldKey = memberKey)))
        )
        for (export in invalid) {
            val target = Device()
            assertThrows(IllegalArgumentException::class.java) { runBlocking { target.importer(authenticate(export)) } }
            assertNull(target.groups.getById(groupId))
            assertNull(target.groups.getGroupKeyForEpoch(groupId, 1))
            assertTrue(target.events.getEventsByGroup(groupId).isEmpty())
        }
    }

    @Test
    fun `validly signed future bootstrap cannot mutate existing projection`() = runBlocking {
        val device = Device()
        device.groups.save(group(), key)
        val before = device.db.groupDao().getById(groupId)
        for (at in listOf(System.currentTimeMillis() / 1000 + 7200, Long.MAX_VALUE)) {
            val proof = transition(at = at)
            assertTrue(proof.verify(groupId))
            assertNull(device.groups.previewCreatorBootstrap(groupId, root, createdAt, listOf(proof)))
            assertFalse(device.groups.mergeCreatorBootstrap(groupId, root, createdAt, listOf(proof)))
            assertEquals(before, device.db.groupDao().getById(groupId))
            assertTrue(device.current().creatorTransitions.isEmpty())
            assertEquals(key, device.groups.getGroupKeyForEpoch(groupId, 1))
        }
    }

    @Test
    fun `MAC authenticated future certificate import rejects before any writes`() = runBlocking {
        val source = Device()
        source.groups.save(group(), key)
        val backup = json.decodeFromString<SplitFreeExport>(source.exporter(groupId))
        for (at in listOf(System.currentTimeMillis() / 1000 + 7200, Long.MAX_VALUE)) {
            val proof = transition(at = at)
            assertTrue(proof.verify(groupId))
            val target = Device()
            val future = authenticate(backup.copy(creatorTransitions = listOf(proof)))
            assertThrows(IllegalArgumentException::class.java) { runBlocking { target.importer(future) } }
            assertNull(target.groups.getById(groupId))
            assertNull(target.groups.getGroupKeyForEpoch(groupId, 0))
            assertNull(target.groups.getGroupKeyForEpoch(groupId, 1))
            assertTrue(target.events.getEventsByGroup(groupId).isEmpty())
        }
    }

    @Test
    fun `fresh bootstrap save rejects future evidence before storing keys`() = runBlocking {
        val device = Device()
        val proof = transition(at = System.currentTimeMillis() / 1000 + 7200)
        assertThrows(IllegalArgumentException::class.java) {
            runBlocking { device.groups.save(group().copy(creatorTransitions = listOf(proof)), key) }
        }
        assertNull(device.groups.getById(groupId))
        assertNull(device.groups.getGroupKeyForEpoch(groupId, 0))
        assertNull(device.groups.getGroupKeyForEpoch(groupId, 1))
    }

    @Test
    fun `MAC authenticates all root and certificate fields`() = runBlocking {
        val source = Device()
        source.groups.save(group(), key)
        val proof = transition()
        source.groups.mergeCreatorBootstrap(groupId, root, createdAt, listOf(proof))
        val backup = json.decodeFromString<SplitFreeExport>(source.exporter(groupId))
        val tampered = listOf(
            backup.copy(rootCreator = member),
            backup.copy(rootCreatedAt = createdAt + 1),
            backup.copy(creatorTransitions = emptyList()),
            backup.copy(creatorTransitions = listOf(proof.copy(signature = "0".repeat(128))))
        )
        for (export in tampered) {
            assertNotEquals(backup.canonicalBody(), export.canonicalBody())
            val target = Device()
            val error = assertThrows(IllegalArgumentException::class.java) { runBlocking { target.importer(export) } }
            assertTrue(error.message!!.contains("integrity check failed"))
            assertNull(target.groups.getById(groupId))
            assertNull(target.groups.getGroupKeyForEpoch(groupId, 1))
        }
    }

    @Test
    fun `older root only backup cannot reset local creator retirement or key epoch`() = runBlocking {
        val source = Device()
        source.groups.save(group(), key)
        val backup = source.exporter(groupId)
        val target = Device()
        target.groups.save(group(epoch = 2), key)
        target.groups.applyAuthenticatedRevocation(groupId, root, "", createdAt + 30, "retired", 2, false)
        target.importer(backup)
        assertEquals("", target.current().createdBy)
        assertEquals(root, target.current().originalCreator)
        assertEquals(2, target.current().keyEpoch)
        assertTrue(root in target.groups.retiredIdentities(groupId).revoked)
    }

    @Test
    fun `certificates cannot override an earlier authenticated competing revocation`() = runBlocking {
        val source = Device()
        val proof = transition()
        source.groups.save(group(), key)
        source.groups.mergeCreatorBootstrap(groupId, root, createdAt, listOf(proof))
        val backup = source.exporter(groupId)
        val target = Device()
        target.groups.save(group(), key)
        target.groups.applyAuthenticatedRevocation(groupId, root, member, createdAt + 10, "earlier", 0, true)
        target.importer(backup)
        assertEquals(member, target.current().createdBy)
        assertEquals(listOf(proof), target.current().creatorTransitions)
        assertEquals(2, target.state().facts.size)
    }

    @Test
    fun `original creator can be recovered from root metadata facts without trusting successor checkpoint`() =
        runBlocking {
            val device = Device()
            device.groups.save(group().copy(createdBy = "", originalCreator = ""), key)
            val meta = GroupMeta(
                "Trip",
                createdBy = root,
                createdAt = createdAt,
                members = listOf(root, member),
                relays = RelayDefaults.DEFAULT_RELAYS
            )
            device.groups.applyAuthenticatedMeta(groupId, meta, root, createdAt + 1, "root-meta", 1)
            device.groups.applyAuthenticatedRevocation(groupId, root, successor, createdAt + 20, "replacement", 1, true)
            val reopened = GroupRepository(device.db.groupDao(), device.storage).getById(groupId)!!
            assertEquals(root, reopened.originalCreator)
            assertEquals(successor, reopened.createdBy)
            assertEquals("", device.state().checkpoint.createdBy)
        }

    @Test
    fun `different valid randomized signatures for one fact are an idempotent bootstrap`() = runBlocking {
        val first = transition()
        val revocation = KeyRevocation(root, successor, successorProof = first.successorProof)
        val event = NostrEvent(
            pubkey = root,
            createdAt = first.timestamp,
            kind = 30078,
            tags = listOf(listOf("g", groupId), listOf("t", "key_revocation")),
            content = json.encodeToString(revocation)
        ).sign(rootKey)
        val second = CreatorTransition.sign(groupId, event, first.epoch, revocation, rootKey)
        assertTrue(first.verify(groupId))
        assertTrue(second.verify(groupId))
        assertNotEquals(first.signature, second.signature)
        assertEquals(first.fact(), second.fact())
        for ((initial, repeated) in listOf(first to second, second to first)) {
            val device = Device()
            device.groups.save(group(), key)
            assertTrue(device.groups.mergeCreatorBootstrap(groupId, root, createdAt, listOf(initial)))
            val before = device.db.groupDao().getById(groupId)
            assertTrue(device.groups.mergeCreatorBootstrap(groupId, root, createdAt, listOf(repeated)))
            assertEquals(before, device.db.groupDao().getById(groupId))
            assertEquals(listOf(initial.fact()), device.state().facts)
            assertEquals(listOf(initial), device.current().creatorTransitions)
        }
    }

    @Test
    fun `existing certificate id never bypasses signature verification`() = runBlocking {
        val device = Device()
        val proof = transition()
        device.groups.save(group(), key)
        assertTrue(device.groups.mergeCreatorBootstrap(groupId, root, createdAt, listOf(proof)))
        val before = device.db.groupDao().getById(groupId)
        val tampered = proof.copy(signature = "0".repeat(128))
        assertEquals(proof.fact(), tampered.fact())
        assertFalse(tampered.verify(groupId))
        assertNull(device.groups.previewCreatorBootstrap(groupId, root, createdAt, listOf(tampered)))
        assertFalse(device.groups.mergeCreatorBootstrap(groupId, root, createdAt, listOf(tampered)))
        assertEquals(before, device.db.groupDao().getById(groupId))
        assertEquals(listOf(proof), device.current().creatorTransitions)
    }

    @Test
    fun `ordinary control collisions after a certificate cannot change its meaning or state`() = runBlocking {
        val device = Device()
        val proof = transition()
        device.groups.save(group(), key)
        assertTrue(device.groups.mergeCreatorBootstrap(groupId, root, createdAt, listOf(proof)))
        val before = device.db.groupDao().getById(groupId)
        assertThrows(IllegalStateException::class.java) {
            runBlocking {
                device.groups.applyAuthenticatedRevocation(
                    groupId,
                    root,
                    successor,
                    proof.timestamp,
                    proof.eventId,
                    proof.epoch + 1,
                    true
                )
            }
        }
        assertEquals(before, device.db.groupDao().getById(groupId))
        val meta = GroupMeta(
            "Conflicting metadata",
            createdBy = successor,
            createdAt = createdAt,
            members = listOf(successor, member),
            relays = RelayDefaults.DEFAULT_RELAYS
        )
        assertThrows(IllegalStateException::class.java) {
            runBlocking {
                device.groups.applyAuthenticatedMeta(
                    groupId,
                    meta,
                    successor,
                    proof.timestamp,
                    proof.eventId,
                    proof.epoch
                )
            }
        }
        assertEquals(before, device.db.groupDao().getById(groupId))
        assertEquals(listOf(proof.fact()), device.state().facts)
        assertEquals(successor, device.current().createdBy)
    }
}
