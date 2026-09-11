package com.splitfree.sync.event

import com.splitfree.data.repository.GroupRepository
import com.splitfree.domain.model.group.Group
import com.splitfree.domain.model.group.GroupIdentity
import com.splitfree.domain.repository.EventPublisherContract
import com.splitfree.domain.repository.IdentityContract
import com.splitfree.domain.usecase.group.RevokeKeyUseCase
import com.splitfree.domain.usecase.group.RotateGroupKeyUseCase
import com.splitfree.domain.usecase.sync.SelfHealUseCase
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.coVerifyOrder
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test

class EventPostProcessorTest {
    private val groupRepo = mockk<GroupRepository>(relaxed = true)
    private val rotateGroupKey = mockk<RotateGroupKeyUseCase>(relaxed = true)
    private val revokeKey = mockk<RevokeKeyUseCase>(relaxed = true)
    private val selfHeal = mockk<SelfHealUseCase>(relaxed = true)
    private val eventPublisher = mockk<EventPublisherContract>(relaxed = true)
    private val identity = mockk<IdentityContract>()
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    private lateinit var processor: EventPostProcessor

    private val groupId = "group-1"
    private val pubkey = "aa".repeat(32)
    private val group = Group(groupId, "Test", "", pubkey, 1000, listOf(pubkey), listOf("wss://r"))

    @Before
    fun setup() {
        mockkStatic(android.util.Log::class)
        every { android.util.Log.i(any(), any()) } returns 0
        every { android.util.Log.w(any(), any<String>()) } returns 0
        every { android.util.Log.e(any(), any(), any()) } returns 0
        coEvery { groupRepo.getById(groupId) } returns group
        every { identity.getPublicKeyHex() } returns pubkey
        processor =
            EventPostProcessor(groupRepo, rotateGroupKey, revokeKey, selfHeal, eventPublisher, identity, appScope)
    }

    @After
    fun teardown() = unmockkAll()

    @Test
    fun `handle null decrypted is no-op`() = runBlocking {
        processor.handle("group_meta", null, pubkey, groupId, 1000, false)
        coVerify(exactly = 0) { groupRepo.updateFromMeta(any(), any(), any(), any(), any(), any()) }
    }

    @Test
    fun `handle unknown event type is no-op`() = runBlocking {
        processor.handle("expense", """{"data":"x"}""", pubkey, groupId, 1000, false)
        coVerify(exactly = 0) { groupRepo.updateFromMeta(any(), any(), any(), any(), any(), any()) }
        coVerify(exactly = 0) { rotateGroupKey.handleKeyRotation(any(), any(), any(), any()) }
        coVerify(exactly = 0) { revokeKey.handleRevocation(any(), any(), any()) }
    }

    @Test
    fun `handle group_meta updates group from creator`() = runBlocking {
        val meta =
            """{"name":"New","description":"","created_by":"$pubkey",""" +
                """"created_at":1000,"members":["$pubkey"],"relays":["wss://r"]}"""
        processor.handle("group_meta", meta, pubkey, groupId, 2000, false)
        coVerify {
            groupRepo.updateFromMeta(groupId, "New", listOf(pubkey), listOf("wss://r"), 2000, pubkey, emptyMap(), "")
        }
    }

    @Test
    fun `handle group_meta from creator applies the description`() = runBlocking {
        val meta =
            """{"name":"Trip","description":"Ski week","created_by":"$pubkey",""" +
                """"created_at":1000,"members":["$pubkey"],"relays":["wss://r"]}"""
        processor.handle("group_meta", meta, pubkey, groupId, 2000, false)
        coVerify {
            groupRepo.updateFromMeta(
                groupId,
                "Trip",
                listOf(pubkey),
                listOf("wss://r"),
                2000,
                pubkey,
                any(),
                "Ski week"
            )
        }
    }

    @Test
    fun `handle group_meta from a non-creator leaves the description alone`() = runBlocking {
        val stranger = "bb".repeat(32)
        val meta =
            """{"name":"Hijack","description":"Spoofed","members":["$pubkey","$stranger"],"relays":["wss://r"]}"""
        processor.handle("group_meta", meta, stranger, groupId, 2000, false)
        coVerify { groupRepo.updateFromMeta(groupId, "Test", any(), listOf("wss://r"), 2000, "", any(), null) }
    }

    @Test
    fun `handle group_meta with empty members skips update`() = runBlocking {
        val meta = """{"name":"X","members":[],"relays":["wss://r"]}"""
        processor.handle("group_meta", meta, pubkey, groupId, 1000, false)
        coVerify(exactly = 0) { groupRepo.updateFromMeta(any(), any(), any(), any(), any(), any()) }
    }

    @Test
    fun `handle group_meta non-creator only adds self`() = runBlocking {
        val stranger = "bb".repeat(32)
        val meta = """{"name":"Test","members":["$pubkey","$stranger"],"relays":["wss://r"]}"""
        processor.handle("group_meta", meta, stranger, groupId, 2000, false)
        coVerify {
            groupRepo.updateFromMeta(
                groupId,
                "Test",
                match {
                    stranger in it && pubkey in it
                },
                listOf("wss://r"),
                2000,
                ""
            )
        }
    }

    @Test
    fun `handle group_meta non-creator cannot overwrite other member names`() = runBlocking {
        val stranger = "bb".repeat(32)
        val namedGroup = group.copy(memberNames = mapOf(pubkey to "Creator"))
        coEvery { groupRepo.getById(groupId) } returns namedGroup
        val meta =
            """{"name":"Test","members":["$pubkey","$stranger"],""" +
                """"relays":["wss://r"],"member_names":{"$pubkey":"Spoofed","$stranger":"Joiner"}}"""
        processor.handle("group_meta", meta, stranger, groupId, 2000, false)
        coVerify {
            groupRepo.updateFromMeta(
                groupId,
                "Test",
                match { stranger in it && pubkey in it },
                listOf("wss://r"),
                2000,
                "",
                match { names ->
                    names[pubkey] == "Creator" &&
                        names[stranger] == "Joiner"
                }
            )
        }
    }

    @Test
    fun `handle group_meta non-creator clearing name removes it`() = runBlocking {
        val stranger = "bb".repeat(32)
        val namedGroup = group.copy(
            members = listOf(pubkey, stranger),
            memberNames = mapOf(pubkey to "Creator", stranger to "OldName")
        )
        coEvery { groupRepo.getById(groupId) } returns namedGroup
        val meta =
            """{"name":"Test","members":["$pubkey","$stranger"],""" +
                """"relays":["wss://r"],"member_names":{}}"""
        processor.handle("group_meta", meta, stranger, groupId, 2000, false)
        coVerify {
            groupRepo.updateFromMeta(
                groupId,
                "Test",
                match { stranger in it && pubkey in it },
                listOf("wss://r"),
                2000,
                "",
                match { names ->
                    names[pubkey] == "Creator" &&
                        stranger !in names
                }
            )
        }
    }

    @Test
    fun `handle group_meta creator event with empty member_names clears names`() = runBlocking {
        val namedGroup = group.copy(memberNames = mapOf(pubkey to "Creator"))
        coEvery { groupRepo.getById(groupId) } returns namedGroup
        val meta =
            """{"name":"New","description":"","created_by":"$pubkey",""" +
                """"created_at":1000,"members":["$pubkey"],"relays":["wss://r"]}"""
        processor.handle("group_meta", meta, pubkey, groupId, 2000, false)
        coVerify {
            groupRepo.updateFromMeta(
                groupId,
                "New",
                listOf(pubkey),
                listOf("wss://r"),
                2000,
                pubkey,
                emptyMap(),
                ""
            )
        }
    }

    @Test
    fun `handle key_rotation delegates to RotateGroupKeyUseCase with the event timestamp`() = runBlocking {
        processor.handle("key_rotation", """{"data":"x"}""", pubkey, groupId, 4321, false)
        coVerify { rotateGroupKey.handleKeyRotation("""{"data":"x"}""", pubkey, groupId, 4321) }
    }

    @Test
    fun `handle key_revocation delegates to RevokeKeyUseCase`() = runBlocking {
        processor.handle("key_revocation", """{"data":"x"}""", pubkey, groupId, 1000, false)
        coVerify { revokeKey.handleRevocation("""{"data":"x"}""", pubkey, groupId) }
    }

    @Test
    fun `handle catches exception from rotateGroupKey`() = runBlocking {
        coEvery { rotateGroupKey.handleKeyRotation(any(), any(), any(), any()) } throws RuntimeException("boom")
        processor.handle("key_rotation", """{"data":"x"}""", pubkey, groupId, 1000, false)
        // Should not throw — exception is caught internally
    }

    @Test
    fun `handle rethrows CancellationException`() {
        coEvery { rotateGroupKey.handleKeyRotation(any(), any(), any(), any()) } throws
            kotlinx.coroutines.CancellationException("cancel")
        try {
            runBlocking { processor.handle("key_rotation", """{"data":"x"}""", pubkey, groupId, 1000, false) }
            org.junit.Assert.fail("Expected CancellationException")
        } catch (_: kotlinx.coroutines.CancellationException) { /* expected */ }
    }

    @Test
    fun `handle group_meta triggers self-heal on relay change`() = runBlocking {
        val newRelayGroup = group.copy(relays = listOf("wss://old"))
        coEvery { groupRepo.getById(groupId) } returns newRelayGroup
        val meta = """{"name":"Test","members":["$pubkey"],"relays":["wss://new"]}"""
        processor.handle("group_meta", meta, pubkey, groupId, 2000, false)
        // appScope uses Dispatchers.Unconfined so selfHeal runs eagerly
        coVerify { selfHeal(groupId) }
    }

    @Test
    fun `handle group_meta with nonCancellable true`() = runBlocking {
        val meta =
            """{"name":"NC","members":["$pubkey"],"relays":["wss://r"]}"""
        processor.handle("group_meta", meta, pubkey, groupId, 2000, true)
        coVerify {
            groupRepo.updateFromMeta(groupId, "NC", listOf(pubkey), listOf("wss://r"), 2000, pubkey, emptyMap(), "")
        }
    }

    @Test
    fun `handle group_meta when currentGroup is null creates new group`() = runBlocking {
        coEvery { groupRepo.getById(groupId) } returns null
        val stranger = "bb".repeat(32)
        val meta = """{"name":"New","members":["$stranger"],"relays":["wss://r2"]}"""
        // When currentGroup is null, isCreator is true regardless of author
        processor.handle("group_meta", meta, stranger, groupId, 2000, false)
        coVerify {
            groupRepo.updateFromMeta(groupId, "New", listOf(stranger), listOf("wss://r2"), 2000, "", emptyMap(), "")
        }
    }

    @Test
    fun `handle group_meta when createdBy is empty stays in restricted mode`() = runBlocking {
        val emptyCreatorGroup = group.copy(createdBy = "")
        coEvery { groupRepo.getById(groupId) } returns emptyCreatorGroup
        val stranger = "bb".repeat(32)
        val meta = """{"name":"Updated","members":["$stranger"],"relays":["wss://r"]}"""
        processor.handle("group_meta", meta, stranger, groupId, 2000, false)
        coVerify {
            groupRepo.updateFromMeta(
                groupId,
                "Test",
                match { stranger in it && pubkey in it },
                listOf("wss://r"),
                2000,
                ""
            )
        }
    }

    @Test
    fun `handle group_meta does not bootstrap creator from relay metadata`() = runBlocking {
        val emptyCreatorGroup = group.copy(createdBy = "")
        coEvery { groupRepo.getById(groupId) } returns emptyCreatorGroup
        val stranger = "bb".repeat(32)
        val meta =
            """{"name":"Updated","created_by":"$stranger","members":["$pubkey","$stranger"],"relays":["wss://r"]}"""
        processor.handle("group_meta", meta, stranger, groupId, 2000, false)
        coVerify {
            groupRepo.updateFromMeta(
                groupId,
                "Test",
                match { stranger in it && pubkey in it },
                listOf("wss://r"),
                2000,
                ""
            )
        }
        coVerify(exactly = 0) { groupRepo.updateCreator(any(), any(), any()) }
    }

    @Test
    fun `handle group_meta legacy group adopts creator whose meta is bound to the group id`() = runBlocking {
        val createdAt = 1_700_000_000L
        val boundId = GroupIdentity.derive(pubkey, createdAt)
        val legacy = group.copy(id = boundId, createdBy = "", createdAt = 12345)
        coEvery { groupRepo.getById(boundId) } returns legacy
        val other = "bb".repeat(32)
        val meta =
            """{"name":"Restored","created_by":"$pubkey","created_at":$createdAt,""" +
                """"members":["$pubkey","$other"],"relays":["wss://new"],"member_names":{"$pubkey":"Alice"}}"""

        processor.handle("group_meta", meta, pubkey, boundId, 2000, false)

        coVerify { groupRepo.updateCreator(boundId, pubkey, createdAt) }
        // Treated as the creator: full metadata is applied and createdBy is set.
        coVerify {
            groupRepo.updateFromMeta(
                boundId,
                "Restored",
                listOf(pubkey, other),
                listOf("wss://new"),
                2000,
                pubkey,
                mapOf(pubkey to "Alice"),
                ""
            )
        }
    }

    @Test
    fun `handle group_meta legacy group does not adopt author whose meta is not bound to the group id`() = runBlocking {
        val createdAt = 1_700_000_000L
        val boundId = GroupIdentity.derive(pubkey, createdAt)
        val legacy = group.copy(id = boundId, createdBy = "")
        coEvery { groupRepo.getById(boundId) } returns legacy
        val impostor = "bb".repeat(32)

        // Right createdAt, wrong author: id was derived from `pubkey`, not `impostor`.
        val forged =
            """{"name":"Hijack","created_by":"$impostor","created_at":$createdAt,""" +
                """"members":["$impostor"],"relays":["wss://evil"]}"""
        processor.handle("group_meta", forged, impostor, boundId, 2000, false)

        // Right author, but created_at does not reproduce the id.
        val staleClaim =
            """{"name":"Hijack","created_by":"$pubkey","created_at":${createdAt + 1},""" +
                """"members":["$pubkey"],"relays":["wss://evil"]}"""
        processor.handle("group_meta", staleClaim, pubkey, boundId, 2001, false)

        // Right author and created_at, but created_by names someone else.
        val inconsistent =
            """{"name":"Hijack","created_by":"$impostor","created_at":$createdAt,""" +
                """"members":["$pubkey"],"relays":["wss://evil"]}"""
        processor.handle("group_meta", inconsistent, pubkey, boundId, 2002, false)

        coVerify(exactly = 0) { groupRepo.updateCreator(any(), any(), any()) }
        // Every call stayed in restricted (non-creator) mode: name/relays preserved, no createdBy.
        coVerify(exactly = 3) {
            groupRepo.updateFromMeta(boundId, "Test", any(), listOf("wss://r"), any(), "", any())
        }
    }

    @Test
    fun `handle group_meta same relays does not trigger self-heal`() = runBlocking {
        val meta = """{"name":"Test","members":["$pubkey"],"relays":["wss://r"]}"""
        processor.handle("group_meta", meta, pubkey, groupId, 2000, false)
        coVerify(exactly = 0) { selfHeal(any()) }
    }

    @Test
    fun `handle group_meta self-heal failure is caught`() = runBlocking {
        coEvery { selfHeal(any()) } throws RuntimeException("network error")
        val oldRelayGroup = group.copy(relays = listOf("wss://old"))
        coEvery { groupRepo.getById(groupId) } returns oldRelayGroup
        val meta = """{"name":"Test","members":["$pubkey"],"relays":["wss://new"]}"""
        // Should not throw — self-heal exception is caught inside appScope.launch
        processor.handle("group_meta", meta, pubkey, groupId, 2000, false)
        coVerify { selfHeal(groupId) }
    }

    @Test
    fun `handle key_revocation catches exception`() = runBlocking {
        coEvery { revokeKey.handleRevocation(any(), any(), any()) } throws RuntimeException("revoke failed")
        processor.handle("key_revocation", """{"data":"x"}""", pubkey, groupId, 1000, false)
        // Should not throw — exception is caught internally
    }

    // --- Re-delivery of authored history to new members ---

    private val joiner = "bb".repeat(32)

    /**
     * Make the repository behave like Room: the first read returns [before], every read after the
     * meta has been applied returns [after]. `updateFromMeta` is where the switch happens.
     */
    private fun persistedTransition(before: Group, after: Group) {
        var current = before
        coEvery { groupRepo.getById(groupId) } answers { current }
        coEvery { groupRepo.updateFromMeta(any(), any(), any(), any(), any(), any(), any(), any()) } answers
            { current = after }
    }

    @Test
    fun `handle group_meta self-join adding a new member triggers redelivery to that member only`() = runBlocking {
        val creatorGroup = group.copy(createdBy = pubkey)
        persistedTransition(creatorGroup, creatorGroup.copy(members = listOf(pubkey, joiner)))
        val meta = """{"name":"Test","members":["$pubkey","$joiner"],"relays":["wss://r"]}"""

        processor.handle("group_meta", meta, joiner, groupId, 2000, false)

        // appScope uses Dispatchers.Unconfined so the launch runs eagerly.
        coVerify(exactly = 1) { eventPublisher.redeliverAuthoredEvents(groupId, setOf(joiner)) }
    }

    @Test
    fun `handle group_meta from creator adding several members redelivers to all of them except me`() = runBlocking {
        val third = "cc".repeat(32)
        val creator = "dd".repeat(32)
        val before = group.copy(createdBy = creator, members = listOf(creator, pubkey))
        persistedTransition(before, before.copy(members = listOf(creator, pubkey, joiner, third)))
        val meta =
            """{"name":"Test","created_by":"$creator","members":["$creator","$pubkey","$joiner","$third"],""" +
                """"relays":["wss://r"]}"""

        processor.handle("group_meta", meta, creator, groupId, 2000, false)

        coVerify(exactly = 1) { eventPublisher.redeliverAuthoredEvents(groupId, setOf(joiner, third)) }
    }

    @Test
    fun `handle group_meta that only renames does not trigger redelivery`() = runBlocking {
        val creatorGroup = group.copy(createdBy = pubkey, members = listOf(pubkey, joiner))
        persistedTransition(creatorGroup, creatorGroup.copy(name = "Renamed"))
        val meta = """{"name":"Renamed","created_by":"$pubkey","members":["$pubkey","$joiner"],"relays":["wss://r"]}"""

        processor.handle("group_meta", meta, pubkey, groupId, 2000, false)

        coVerify(exactly = 0) { eventPublisher.redeliverAuthoredEvents(any(), any()) }
    }

    @Test
    fun `handle group_meta for my own join does not trigger redelivery`() = runBlocking {
        // I am the one who just appeared: there is nobody new to deliver my history to.
        val creator = "dd".repeat(32)
        val before = group.copy(createdBy = creator, members = listOf(creator))
        persistedTransition(before, before.copy(members = listOf(creator, pubkey)))
        val meta = """{"name":"Test","members":["$creator","$pubkey"],"relays":["wss://r"]}"""

        processor.handle("group_meta", meta, pubkey, groupId, 2000, false)

        coVerify(exactly = 0) { eventPublisher.redeliverAuthoredEvents(any(), any()) }
    }

    @Test
    fun `handle group_meta that removes a member does not trigger redelivery`() = runBlocking {
        val creatorGroup = group.copy(createdBy = pubkey, members = listOf(pubkey, joiner))
        persistedTransition(creatorGroup, creatorGroup.copy(members = listOf(pubkey)))
        val meta = """{"name":"Test","created_by":"$pubkey","members":["$pubkey"],"relays":["wss://r"]}"""

        processor.handle("group_meta", meta, pubkey, groupId, 2000, false)

        coVerify(exactly = 0) { eventPublisher.redeliverAuthoredEvents(any(), any()) }
    }

    @Test
    fun `handle group_meta rejected by the LWW watermark does not redeliver to a stale member`() = runBlocking {
        // A stale meta replayed from a relay still lists `joiner`, who has since been removed by a
        // rotation. updateFromMeta ignores it (persisted state unchanged), so re-delivery must too,
        // or my history would be wrapped for a non-member.
        val creatorGroup = group.copy(createdBy = pubkey, members = listOf(pubkey))
        persistedTransition(creatorGroup, creatorGroup) // watermark rejects: no change persisted
        val staleMeta =
            """{"name":"Test","created_by":"$pubkey","members":["$pubkey","$joiner"],"relays":["wss://r"]}"""

        processor.handle("group_meta", staleMeta, pubkey, groupId, 500, false)

        coVerify(exactly = 0) { eventPublisher.redeliverAuthoredEvents(any(), any()) }
    }

    @Test
    fun `handle group_meta redelivery failure is caught`() = runBlocking {
        coEvery { eventPublisher.redeliverAuthoredEvents(any(), any()) } throws RuntimeException("outbox exploded")
        val creatorGroup = group.copy(createdBy = pubkey)
        persistedTransition(creatorGroup, creatorGroup.copy(members = listOf(pubkey, joiner)))
        val meta = """{"name":"Test","members":["$pubkey","$joiner"],"relays":["wss://r"]}"""

        // Should not throw: failure is caught inside appScope.launch
        processor.handle("group_meta", meta, joiner, groupId, 2000, false)

        coVerify(exactly = 1) { eventPublisher.redeliverAuthoredEvents(groupId, setOf(joiner)) }
    }

    @Test
    fun `handle group_meta with new member still applies the meta before redelivering`() = runBlocking {
        val creatorGroup = group.copy(createdBy = pubkey)
        persistedTransition(creatorGroup, creatorGroup.copy(members = listOf(pubkey, joiner)))
        val meta = """{"name":"Test","members":["$pubkey","$joiner"],"relays":["wss://r"]}"""

        processor.handle("group_meta", meta, joiner, groupId, 2000, false)

        coVerifyOrder {
            groupRepo.updateFromMeta(groupId, "Test", match { joiner in it && pubkey in it }, any(), 2000, "", any())
            eventPublisher.redeliverAuthoredEvents(groupId, setOf(joiner))
        }
    }
}
