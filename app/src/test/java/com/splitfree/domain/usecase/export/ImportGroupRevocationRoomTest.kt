package com.splitfree.domain.usecase.export

import android.app.Application
import androidx.room.Room
import com.splitfree.data.local.AppDatabase
import com.splitfree.data.repository.EventRepository
import com.splitfree.data.repository.GroupRepository
import com.splitfree.data.util.CompressionUtil
import com.splitfree.domain.crypto.GroupEncryption
import com.splitfree.domain.crypto.NostrEvent
import com.splitfree.domain.crypto.nip.Nip44
import com.splitfree.domain.model.export.ExportedEvent
import com.splitfree.domain.model.export.SplitFreeExport
import com.splitfree.domain.model.group.GroupIdentity
import com.splitfree.domain.model.group.GroupMeta
import com.splitfree.domain.model.group.GroupProjection
import com.splitfree.domain.model.group.IdentityHistoryPage
import com.splitfree.domain.model.group.KeyRevocation
import com.splitfree.domain.model.group.KeyRotation
import com.splitfree.domain.repository.EventSnapshot
import com.splitfree.domain.repository.IdentityContract
import com.splitfree.domain.usecase.expense.BalanceUnavailableException
import com.splitfree.domain.usecase.expense.ComputeBalancesUseCase
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
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * Offline backup restore into Room with signed, encrypted rows and fake key storage. Fresh and incremental
 * imports must converge on the same roster, creator and balances. Successor proof controls balance transfer;
 * re-import and input order must not change the result. Live ingestion and relay delivery are not exercised.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
class ImportGroupRevocationRoomTest {
    private lateinit var db: AppDatabase
    private lateinit var groups: GroupRepository
    private lateinit var events: EventRepository
    private lateinit var importer: ImportGroupUseCase

    private val encryption = GroupEncryption(CompressionUtil)
    private val groupKey = Base64.getEncoder().encodeToString(ByteArray(32) { 7 })
    private val json = Json { ignoreUnknownKeys = true }

    private val creatorKey = ByteArray(32) { 1 }
    private val creator = NostrEvent.pubkeyFromPrivkey(creatorKey)
    private val memberKey = ByteArray(32) { 2 }
    private val member = NostrEvent.pubkeyFromPrivkey(memberKey)
    private val memberSuccessorKey = ByteArray(32) { 3 }
    private val memberSuccessor = NostrEvent.pubkeyFromPrivkey(memberSuccessorKey)
    private val creatorSuccessorKey = ByteArray(32) { 4 }
    private val creatorSuccessor = NostrEvent.pubkeyFromPrivkey(creatorSuccessorKey)
    private val terminalKey = ByteArray(32) { 5 }
    private val terminal = NostrEvent.pubkeyFromPrivkey(terminalKey)

    private val now = System.currentTimeMillis() / 1000
    private val createdAt = now - 10_000
    private val groupId = GroupIdentity.derive(creator, createdAt)

    /** The restoring device belongs to the creator: MAC and key wrapping are under its key. */
    private val identity = mockk<IdentityContract> {
        every { getPrivateKeyBytes() } answers { creatorKey.copyOf() }
        every { getPublicKeyBytes() } returns creator.hexToBytes()
        every { getPublicKeyHex() } returns creator
    }

    @Before
    fun setup() {
        db = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        groups = GroupRepository(db.groupDao(), FakeSecureStorage())
        events = EventRepository(db, db.eventDao())
        importer = ImportGroupUseCase(events, groups, encryption, EventValidator(), identity)
    }

    @After
    fun teardown() = db.close()

    private fun signed(
        signer: ByteArray,
        type: String,
        plaintext: String,
        at: Long,
        uuid: String? = null
    ): EventSnapshot {
        val pubkey = NostrEvent.pubkeyFromPrivkey(signer)
        val content = encryption.encrypt(plaintext, groupKey)
        val tags = mutableListOf(listOf("g", groupId), listOf("t", type))
        if (uuid != null) tags += listOf("x", uuid)
        val event = NostrEvent(pubkey = pubkey, createdAt = at, kind = 30078, tags = tags, content = content)
            .sign(signer)
        return EventSnapshot(
            event.id, groupId, pubkey, at, contentEncrypted = content, eventType = type, expenseUuid = uuid,
            sig = event.sig, originalEventJson = event.toJson(), keyEpoch = 0
        )
    }

    private fun creatorMeta(
        members: List<String>,
        at: Long,
        createdBy: String = creator,
        signer: ByteArray = creatorKey
    ) = signed(
        signer,
        "group_meta",
        json.encodeToString(
            GroupMeta.serializer(),
            GroupMeta(name = "Trip", createdBy = createdBy, createdAt = createdAt, members = members)
        ),
        at
    )

    /**
     * Builds a signed revocation claim with optional successor possession proof. Omitting [successorKey]
     * models retirement without balance transfer; callers can also supply an unauthorized [signer].
     */
    private fun revocation(signer: ByteArray, old: String, new: String, at: Long, successorKey: ByteArray? = null) =
        signed(
            signer,
            "key_revocation",
            json.encodeToString(
                KeyRevocation.serializer(),
                KeyRevocation(
                    old,
                    new,
                    "Key compromised",
                    successorProof = successorKey?.let { KeyRevocation.proveSuccessor(groupId, old, new, it) }.orEmpty()
                )
            ),
            at
        )

    /** The identity derived from [payerKey] fronts 100 split evenly with [other]. */
    private fun expense(payerKey: ByteArray, other: String, at: Long): EventSnapshot {
        val payer = NostrEvent.pubkeyFromPrivkey(payerKey)
        val uuid = "u-$at"
        return signed(
            payerKey,
            "expense",
            """{"id":"$uuid","amount":100,"currency":"INR","description":"t","paid_by":"$payer",""" +
                """"split_type":"equal",""" +
                """"split_among":[{"pubkey":"$payer","share":50},{"pubkey":"$other","share":50}],""" +
                """"timestamp":$at}""",
            at,
            uuid
        )
    }

    private fun backup(rows: List<EventSnapshot>): SplitFreeExport {
        val convKey = Nip44.getConversationKey(creatorKey, creator.hexToBytes())
        val export = SplitFreeExport(
            groupId = groupId,
            exportedAt = now,
            keyEpoch = 0,
            encryptedGroupKey = Nip44.encrypt(groupKey, convKey),
            encryptedEpochKeys = mapOf("0" to Nip44.encrypt(groupKey, convKey)),
            events = rows.map {
                ExportedEvent(
                    eventId = it.eventId, pubkey = it.pubkey, createdAt = it.createdAt, kind = it.kind,
                    contentEncrypted = it.contentEncrypted, eventType = it.eventType, expenseUuid = it.expenseUuid,
                    sig = it.sig, originalEventJson = it.originalEventJson, keyEpoch = it.keyEpoch
                )
            }
        )
        return export.copy(hmac = ExportMac.compute(export, creatorKey).toHex())
    }

    private fun balances(): Map<String, Long> = runBlocking {
        ComputeBalancesUseCase(events, groups, encryption)(groupId).associate { it.pubkey to it.net }
    }

    /** Roster [creator, member], the creator fronts 100 with member, then member retires to memberSuccessor. */
    private fun memberReplacementLedger(): List<EventSnapshot> {
        val revoke =
            revocation(memberKey, member, memberSuccessor, at = createdAt + 30, successorKey = memberSuccessorKey)
        return listOf(
            creatorMeta(listOf(creator, member), at = createdAt + 10),
            expense(creatorKey, member, at = createdAt + 20),
            revoke,
            history(revoke, memberSuccessorKey)
        )
    }

    private fun history(
        revoke: EventSnapshot,
        successorKey: ByteArray,
        ids: List<String> = emptyList()
    ): EventSnapshot {
        val page = IdentityHistoryPage.create(
            groupId,
            revoke.pubkey,
            NostrEvent.pubkeyFromPrivkey(successorKey),
            checkNotNull(NostrEvent.fromJson(checkNotNull(revoke.originalEventJson))),
            ids
        ).single()
        return signed(
            successorKey,
            IdentityHistoryPage.TYPE,
            json.encodeToString(IdentityHistoryPage.serializer(), page),
            revoke.createdAt + 1
        )
    }

    private fun assertUnavailable() = assertThrows(BalanceUnavailableException::class.java) { balances() }

    @Test
    fun `fresh restore rebuilds the tombstone, the successor link, the roster and the balances`() = runBlocking {
        assertEquals(4, importer(backup(memberReplacementLedger())))

        val group = groups.getById(groupId)!!
        assertEquals(listOf(creator, memberSuccessor), group.members)
        assertEquals(creator, group.createdBy)
        val retired = groups.retiredIdentities(groupId)
        assertEquals(setOf(member), retired.revoked)
        assertEquals(mapOf(member to memberSuccessor), retired.successors)
        // Resolving a stale roster replaces the retired key with its successor.
        assertEquals(listOf(memberSuccessor), groups.resolveRoster(groupId, listOf(member)))
        assertEquals(mapOf(creator to 50L, memberSuccessor to -50L), balances())
    }

    @Test
    fun `a creator revocation hands the creator role to the successor on a fresh device`() = runBlocking {
        val rows = listOf(
            creatorMeta(listOf(creator, member), at = createdAt + 10),
            revocation(creatorKey, creator, creatorSuccessor, at = createdAt + 20, successorKey = creatorSuccessorKey),
            // Companion metadata names the successor but is still signed by the retired creator.
            creatorMeta(listOf(creatorSuccessor, member), at = createdAt + 21, createdBy = creatorSuccessor)
        )

        assertEquals(3, importer(backup(rows)))

        val group = groups.getById(groupId)!!
        assertEquals(creatorSuccessor, group.createdBy)
        assertEquals(listOf(creatorSuccessor, member), group.members)
        assertTrue(groups.retiredIdentities(groupId).isRetired(creator))
        assertEquals(creatorSuccessor, groups.retiredIdentities(groupId).resolve(creator))
    }

    @Test
    fun `a creator meta authored before the revocation cannot resurrect the revoked key`() = runBlocking {
        val rows = listOf(
            creatorMeta(listOf(creator, member), at = createdAt + 10),
            revocation(memberKey, member, memberSuccessor, at = createdAt + 30, successorKey = memberSuccessorKey),
            // Later metadata still carries the pre-revocation roster.
            creatorMeta(listOf(creator, member), at = createdAt + 40)
        )

        importer(backup(rows))

        val group = groups.getById(groupId)!!
        assertEquals(listOf(creator, memberSuccessor), group.members)
        assertFalse(member in group.members)
    }

    @Test
    fun `record order in the file does not change the restored state`() = runBlocking {
        val rows = memberReplacementLedger()
        importer(backup(rows.reversed()))
        val shuffled = groups.getById(groupId)!! to groups.retiredIdentities(groupId) to balances()

        db.close()
        setup()
        importer(backup(rows))
        val ordered = groups.getById(groupId)!! to groups.retiredIdentities(groupId) to balances()

        assertEquals(ordered.first.first.members, shuffled.first.first.members)
        assertEquals(ordered.first.first.createdBy, shuffled.first.first.createdBy)
        assertEquals(ordered.first.second, shuffled.first.second)
        assertEquals(ordered.second, shuffled.second)
    }

    @Test
    fun `re-importing the same backup is idempotent`() = runBlocking {
        val rows = memberReplacementLedger()
        assertEquals(4, importer(backup(rows)))
        val first = groups.getById(groupId)!!

        assertEquals(0, importer(backup(rows)))

        val second = groups.getById(groupId)!!
        assertEquals(first.members, second.members)
        assertEquals(first.createdBy, second.createdBy)
        assertEquals(mapOf(member to memberSuccessor), groups.retiredIdentities(groupId).successors)
        assertEquals(4, events.getEventCount(groupId))
    }

    @Test
    fun `a revocation row stored by an older app without its effect is repaired by the next import`() = runBlocking {
        val rows = memberReplacementLedger()
        // What an older restore left behind: every row stored as applied, group rebuilt from the meta only.
        importer(backup(rows.filter { it.eventType !in setOf("key_revocation", IdentityHistoryPage.TYPE) }))
        events.insert(rows.single { it.eventType == "key_revocation" })
        assertEquals(listOf(creator, member), groups.getById(groupId)!!.members)
        assertTrue(groups.retiredIdentities(groupId).revoked.isEmpty())

        // The checkpoint arrives while the stored revocation's effect is repaired.
        assertEquals(1, importer(backup(rows)))

        assertEquals(listOf(creator, memberSuccessor), groups.getById(groupId)!!.members)
        assertEquals(mapOf(member to memberSuccessor), groups.retiredIdentities(groupId).successors)
        assertEquals(mapOf(creator to 50L, memberSuccessor to -50L), balances())
    }

    @Test
    fun `a revocation not signed by the identity it retires is stored as history but never applied`() = runBlocking {
        val rows = listOf(
            creatorMeta(listOf(creator, member), at = createdAt + 10),
            // The member tries to retire the creator.
            revocation(memberKey, creator, memberSuccessor, at = createdAt + 30)
        )

        importer(backup(rows))

        val group = groups.getById(groupId)!!
        assertEquals(listOf(creator, member), group.members)
        assertEquals(creator, group.createdBy)
        assertTrue(groups.retiredIdentities(groupId).revoked.isEmpty())
    }

    @Test
    fun `a debtor cannot cancel a debt by naming the creditor as successor`() = runBlocking {
        val rows = listOf(
            creatorMeta(listOf(creator, member), at = createdAt + 10),
            expense(creatorKey, member, at = createdAt + 20),
            // The debtor names the creditor as successor without proof of possession of the creditor's key.
            revocation(memberKey, member, creator, at = createdAt + 30)
        )

        assertEquals(3, importer(backup(rows)))

        assertUnavailable()
        val group = groups.getById(groupId)!!
        assertEquals(listOf(creator), group.members)
        assertEquals(creator, group.createdBy)
        val retired = groups.retiredIdentities(groupId)
        assertTrue(retired.isRetired(member))
        assertEquals(member, retired.resolve(member))
    }

    @Test
    fun `a replacement without successor proof retires the key but moves no balance`() = runBlocking {
        val rows = listOf(
            creatorMeta(listOf(creator, member), at = createdAt + 10),
            expense(creatorKey, member, at = createdAt + 20),
            revocation(memberKey, member, memberSuccessor, at = createdAt + 30)
        )

        assertEquals(3, importer(backup(rows)))

        // Roster semantics are unchanged: the successor takes the seat.
        assertEquals(listOf(creator, memberSuccessor), groups.getById(groupId)!!.members)
        // Without successor proof, the debt stays on the original split participant, not the expense signer.
        assertUnavailable()
        assertEquals(emptyMap<String, String>(), groups.retiredIdentities(groupId).successors)
    }

    @Test
    fun `a successor proof signed by the wrong key is ignored`() = runBlocking {
        val forged = KeyRevocation.proveSuccessor(groupId, member, memberSuccessor, memberKey)
        val rows = listOf(
            creatorMeta(listOf(creator, member), at = createdAt + 10),
            expense(creatorKey, member, at = createdAt + 20),
            signed(
                memberKey,
                "key_revocation",
                json.encodeToString(
                    KeyRevocation.serializer(),
                    KeyRevocation(member, memberSuccessor, "Key compromised", successorProof = forged)
                ),
                createdAt + 30
            )
        )

        assertEquals(3, importer(backup(rows)))

        assertUnavailable()
        assertEquals(emptyMap<String, String>(), groups.retiredIdentities(groupId).successors)
    }

    @Test
    fun `clock skew inside a replacement chain still folds into the final identity`() = runBlocking {
        val rows = listOf(
            creatorMeta(listOf(creator, member), at = createdAt + 10),
            expense(creatorKey, member, at = createdAt + 20),
            // The second hop has an earlier timestamp; resolution must still reach the terminal identity.
            revocation(memberKey, member, memberSuccessor, at = createdAt + 100, successorKey = memberSuccessorKey),
            revocation(memberSuccessorKey, memberSuccessor, terminal, at = createdAt + 90, successorKey = terminalKey)
        )

        val evidence = listOf(history(rows[2], memberSuccessorKey), history(rows[3], terminalKey))
        assertEquals(6, importer(backup(rows + evidence)))

        assertEquals(mapOf(creator to 50L, terminal to -50L), balances())
        val group = groups.getById(groupId)!!
        assertEquals(listOf(creator, terminal), group.members)
        val retired = groups.retiredIdentities(groupId)
        assertEquals(setOf(member, memberSuccessor), retired.revoked)
        assertEquals(mapOf(member to terminal, memberSuccessor to terminal), retired.successors)
    }

    @Test
    fun `root identity binds successor authority but cannot add members after author retirement`() = runBlocking {
        val rows = listOf(
            creatorMeta(listOf(creator, member), at = createdAt + 20),
            revocation(creatorKey, creator, creatorSuccessor, at = createdAt + 10, successorKey = creatorSuccessorKey)
        )

        assertEquals(2, importer(backup(rows)))

        val group = groups.getById(groupId)!!
        assertEquals(creatorSuccessor, group.createdBy)
        // There is no authenticated pre-retirement roster in this backup. Root identity binding
        // alone must not authorize the retired author to introduce another member at a later clock.
        assertEquals(listOf(creatorSuccessor), group.members)
        assertFalse(member in group.members)
    }

    @Test
    fun `a complete backup repairs the creator after a revocation-only partial restore`() = runBlocking {
        val root = creatorMeta(listOf(creator, member), at = createdAt + 10)
        val replacement =
            revocation(creatorKey, creator, creatorSuccessor, at = createdAt + 20, successorKey = creatorSuccessorKey)

        assertEquals(1, importer(backup(listOf(replacement))))
        assertEquals("", groups.getById(groupId)!!.createdBy)

        assertEquals(1, importer(backup(listOf(root, replacement))))
        // Adding the earlier root metadata must recover both the successor creator and the original member.
        val group = groups.getById(groupId)!!
        assertEquals(creatorSuccessor, group.createdBy)
        assertEquals(listOf(creatorSuccessor, member), group.members)
        assertFalse(creator in group.members)
    }

    @Test
    fun `conflicting proven revocations converge on the canonically earliest one whatever the import order`() =
        runBlocking {
            val root = creatorMeta(listOf(creator, member), at = createdAt + 10)
            val debt = expense(creatorKey, member, at = createdAt + 20)
            // Two signed records prove different successors; canonical timestamp order must choose the earlier one.
            val early =
                revocation(memberKey, member, memberSuccessor, at = createdAt + 30, successorKey = memberSuccessorKey)
            val late = revocation(memberKey, member, terminal, at = createdAt + 40, successorKey = terminalKey)

            // A device that heard the later record first, then restores the complete history.
            assertEquals(3, importer(backup(listOf(root, debt, late))))
            assertEquals(1, importer(backup(listOf(root, debt, early, late))))
            val incremental = Triple(
                assertUnavailable().javaClass,
                groups.getById(groupId)!!.members,
                groups.retiredIdentities(groupId).successors
            )

            db.close()
            setup()
            assertEquals(4, importer(backup(listOf(root, debt, early, late))))
            val fresh =
                Triple(
                    assertUnavailable().javaClass,
                    groups.getById(groupId)!!.members,
                    groups.retiredIdentities(groupId).successors
                )

            assertEquals(BalanceUnavailableException::class.java, fresh.first)
            assertEquals(fresh.first, incremental.first)
            assertEquals(listOf(creator, memberSuccessor), fresh.second)
            assertEquals(fresh.second, incremental.second)
            assertEquals(mapOf(member to memberSuccessor), fresh.third)
            assertEquals(fresh.third, incremental.third)
        }

    @Test
    fun `a complete backup after a revocation-only partial restore rebuilds the same roster as a fresh restore`() =
        runBlocking {
            val root = creatorMeta(listOf(creator, member), at = createdAt + 10)
            val replacement =
                revocation(
                    creatorKey,
                    creator,
                    creatorSuccessor,
                    at = createdAt + 20,
                    successorKey = creatorSuccessorKey
                )

            assertEquals(1, importer(backup(listOf(replacement))))
            assertEquals(listOf(creatorSuccessor), groups.getById(groupId)!!.members)
            assertEquals(1, importer(backup(listOf(root, replacement))))
            val incremental = groups.getById(groupId)!!

            db.close()
            setup()
            assertEquals(2, importer(backup(listOf(root, replacement))))
            val fresh = groups.getById(groupId)!!

            assertEquals(listOf(creatorSuccessor, member), fresh.members)
            assertEquals(fresh.members, incremental.members)
            assertEquals(creatorSuccessor, fresh.createdBy)
            assertEquals(fresh.createdBy, incremental.createdBy)
        }

    @Test
    fun `nine proven replacement hops attribute the original debt to the live end of the chain`() = runBlocking {
        // Ten identities starting at `member` (seed 2); each hands over to the next with proof of the new key.
        val keys = (2..11).map { seed -> ByteArray(32) { seed.toByte() } }
        val pubs = keys.map { NostrEvent.pubkeyFromPrivkey(it) }
        assertEquals(member, pubs.first())
        val rows = mutableListOf(
            creatorMeta(listOf(creator, member), at = createdAt + 10),
            expense(creatorKey, member, at = createdAt + 20)
        )
        for (i in 0 until 9) {
            rows += revocation(keys[i], pubs[i], pubs[i + 1], at = createdAt + 30 + i, successorKey = keys[i + 1])
        }

        val evidence = rows.filter { it.eventType == "key_revocation" }.mapIndexed { index, revoke ->
            history(revoke, keys[index + 1])
        }
        assertEquals(20, importer(backup(rows + evidence)))

        assertEquals(listOf(creator, pubs.last()), groups.getById(groupId)!!.members)
        assertEquals(mapOf(creator to 50L, pubs.last() to -50L), balances())
        val retired = groups.retiredIdentities(groupId)
        assertEquals(pubs.dropLast(1).toSet(), retired.revoked)
        assertEquals(pubs.last(), retired.resolve(member))
    }

    @Test
    fun `a replacement cycle resolves to nobody and balances remain unavailable`() = runBlocking {
        val rows = listOf(
            creatorMeta(listOf(creator, member), at = createdAt + 10),
            expense(creatorKey, member, at = createdAt + 20),
            revocation(memberKey, member, memberSuccessor, at = createdAt + 30, successorKey = memberSuccessorKey),
            // The successor hands the seat straight back to the already revoked key: a closed loop.
            revocation(memberSuccessorKey, memberSuccessor, member, at = createdAt + 40, successorKey = memberKey)
        )

        assertEquals(4, importer(backup(rows)))

        // Both identities are retired: drop their roster seat but retain the debt on the original split participant.
        assertUnavailable()
        val group = groups.getById(groupId)!!
        assertEquals(listOf(creator), group.members)
        assertEquals(creator, group.createdBy)
        val retired = groups.retiredIdentities(groupId)
        assertEquals(setOf(member, memberSuccessor), retired.revoked)
        assertEquals(emptyMap<String, String>(), retired.successors)
        assertEquals(emptyList<String>(), groups.resolveRoster(groupId, listOf(member, memberSuccessor)))
    }

    private fun identityFor(key: ByteArray): IdentityContract {
        val pub = NostrEvent.pubkeyFromPrivkey(key)
        return mockk {
            every { getPrivateKeyBytes() } answers { key.copyOf() }
            every { getPublicKeyBytes() } returns pub.hexToBytes()
            every { getPublicKeyHex() } returns pub
        }
    }

    private fun backupFor(rows: List<EventSnapshot>, ownerKey: ByteArray): SplitFreeExport {
        val ownerPub = NostrEvent.pubkeyFromPrivkey(ownerKey)
        val convKey = Nip44.getConversationKey(ownerKey, ownerPub.hexToBytes())
        val export = SplitFreeExport(
            groupId = groupId,
            exportedAt = now,
            keyEpoch = 0,
            encryptedGroupKey = Nip44.encrypt(groupKey, convKey),
            encryptedEpochKeys = mapOf("0" to Nip44.encrypt(groupKey, convKey)),
            events = rows.map {
                ExportedEvent(
                    eventId = it.eventId, pubkey = it.pubkey, createdAt = it.createdAt, kind = it.kind,
                    contentEncrypted = it.contentEncrypted, eventType = it.eventType, expenseUuid = it.expenseUuid,
                    sig = it.sig, originalEventJson = it.originalEventJson, keyEpoch = it.keyEpoch
                )
            }
        )
        return export.copy(hmac = ExportMac.compute(export, ownerKey).toHex())
    }

    @Test
    fun `incremental cycle replay must not resurrect the restoring identity`() = runBlocking {
        val a = creator
        val bKey = memberKey
        val b = member
        val partial = listOf(revocation(creatorKey, a, b, at = createdAt + 30, successorKey = bKey))
        val root = creatorMeta(listOf(a), at = createdAt + 10)
        val closesCycle = revocation(bKey, b, a, at = createdAt + 40, successorKey = creatorKey)
        val owner = identityFor(bKey)
        val restore = ImportGroupUseCase(events, groups, encryption, EventValidator(), owner)

        assertEquals(1, restore(backupFor(partial, bKey)))
        assertEquals(listOf(b), groups.getById(groupId)!!.members)
        assertEquals(2, restore(backupFor(listOf(root) + partial + closesCycle, bKey)))
        val incremental = groups.getById(groupId)!!.members
        val incrementalRetired = groups.retiredIdentities(groupId)

        db.close()
        setup()
        val freshOwner = identityFor(bKey)
        val freshRestore = ImportGroupUseCase(events, groups, encryption, EventValidator(), freshOwner)
        assertEquals(3, freshRestore(backupFor(listOf(root) + partial + closesCycle, bKey)))
        val fresh = groups.getById(groupId)!!.members

        assertEquals("Incremental and fresh restore must agree", fresh, incremental)
        assertTrue("No persisted member may be retired", incremental.none(incrementalRetired.revoked::contains))
    }

    @Test
    fun `incremental replay of multiple creator metas must equal fresh canonical replay`() = runBlocking {
        val successor = creatorSuccessor
        val oldMeta = creatorMeta(listOf(creator, member), at = createdAt + 10)
        val newMeta = creatorMeta(listOf(creator, terminal), at = createdAt + 20)
        val replacement = revocation(
            creatorKey,
            creator,
            successor,
            at = createdAt + 30,
            successorKey = creatorSuccessorKey
        )
        assertEquals(2, importer(backup(listOf(newMeta, replacement))))
        assertEquals(listOf(successor, terminal), groups.getById(groupId)!!.members)
        assertEquals(1, importer(backup(listOf(oldMeta, newMeta, replacement))))
        val incremental = groups.getById(groupId)!!

        db.close()
        setup()
        assertEquals(3, importer(backup(listOf(oldMeta, newMeta, replacement))))
        val fresh = groups.getById(groupId)!!

        assertEquals("Roster must be independent of partial restore history", fresh.members, incremental.members)
        assertEquals("Creator metadata must converge", fresh.name, incremental.name)
        assertEquals(fresh.createdBy, incremental.createdBy)
    }

    @Test
    fun `out of order control import must not undo a later epoch roster`() = runBlocking {
        val root = creatorMeta(listOf(creator, member), at = createdAt + 10)
        assertEquals(1, importer(backup(listOf(root))))
        val epochOneKey = Base64.getEncoder().encodeToString(ByteArray(32) { 9 })
        groups.saveGroupKeyForEpoch(groupId, 1, epochOneKey)
        assertTrue(groups.applyKeyRotation(groupId, 1, listOf(creator), emptyMap()))
        assertEquals(1, groups.getById(groupId)!!.keyEpoch)
        assertEquals(listOf(creator), groups.getById(groupId)!!.members)

        val newlyImportedOldControl = revocation(
            memberKey,
            member,
            memberSuccessor,
            at = createdAt + 5,
            successorKey = memberSuccessorKey
        )
        assertEquals(1, importer(backup(listOf(root, newlyImportedOldControl))))
        val after = groups.getById(groupId)!!

        assertEquals(1, after.keyEpoch)
        assertEquals("Epoch-0 history must not re-admit a member removed at epoch 1", listOf(creator), after.members)
    }

    @Test
    fun `backup rotation without companion metadata restores authenticated roster and epoch`() = runBlocking {
        val root = creatorMeta(listOf(creator, member), at = createdAt + 10)
        val epochKey = Base64.getEncoder().encodeToString(ByteArray(32) { 9 })
        val conversationKey = Nip44.getConversationKey(creatorKey, creator.hexToBytes())
        val rotation =
            KeyRotation(1, mapOf(creator to Nip44.encrypt(epochKey, conversationKey)), listOf(creator), member)
        val event = signed(
            creatorKey,
            "key_rotation",
            json.encodeToString(KeyRotation.serializer(), rotation),
            createdAt + 20
        )
        val export = backup(listOf(root, event)).copy(
            keyEpoch = 1,
            encryptedGroupKey = Nip44.encrypt(epochKey, conversationKey),
            encryptedEpochKeys = mapOf(
                "0" to Nip44.encrypt(groupKey, conversationKey),
                "1" to Nip44.encrypt(epochKey, conversationKey)
            )
        )
        val authenticated = export.copy(hmac = ExportMac.compute(export, creatorKey).toHex())
        assertEquals(2, importer(authenticated))
        assertEquals(listOf(creator), groups.getById(groupId)!!.members)
        assertEquals(1, groups.getById(groupId)!!.keyEpoch)
        val state = json.decodeFromString<GroupProjection>(db.groupDao().getById(groupId)!!.projectionJson)
        assertTrue(state.incomplete)
        assertEquals(rotation, state.facts.single { it.kind == "rotation" }.rotation)
        assertEquals(0, importer(authenticated))
        assertEquals(listOf(creator), groups.getById(groupId)!!.members)
    }

    @Test
    fun `backup key checkpoint preserves removals until a signed rotation replaces its conservative roster`() =
        runBlocking {
            val root = creatorMeta(listOf(creator, member), at = createdAt + 10)
            importer(backup(listOf(root)))
            val epochKey = Base64.getEncoder().encodeToString(ByteArray(32) { 9 })
            val conversationKey = Nip44.getConversationKey(creatorKey, creator.hexToBytes())
            val rotation =
                KeyRotation(1, mapOf(creator to Nip44.encrypt(epochKey, conversationKey)), listOf(creator), member)
            val event = signed(
                creatorKey,
                "key_rotation",
                json.encodeToString(KeyRotation.serializer(), rotation),
                createdAt + 20
            )
            val export = backup(listOf(root, event)).copy(
                keyEpoch = 1,
                encryptedGroupKey = Nip44.encrypt(epochKey, conversationKey),
                encryptedEpochKeys = mapOf(
                    "0" to Nip44.encrypt(groupKey, conversationKey),
                    "1" to Nip44.encrypt(epochKey, conversationKey)
                )
            )
            assertEquals(1, importer(export.copy(hmac = ExportMac.compute(export, creatorKey).toHex())))
            assertEquals(listOf(creator), groups.getById(groupId)!!.members)
            assertEquals(1, groups.getById(groupId)!!.keyEpoch)
        }
}
