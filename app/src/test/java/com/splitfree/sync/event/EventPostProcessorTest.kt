package com.splitfree.sync.event

import com.splitfree.data.repository.GroupRepository
import com.splitfree.domain.model.group.Group
import com.splitfree.domain.usecase.group.RevokeKeyUseCase
import com.splitfree.domain.usecase.group.RotateGroupKeyUseCase
import com.splitfree.domain.usecase.sync.SelfHealUseCase
import io.mockk.coEvery
import io.mockk.coVerify
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
        processor = EventPostProcessor(groupRepo, rotateGroupKey, revokeKey, selfHeal, appScope)
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
        coVerify { groupRepo.updateFromMeta(groupId, "New", listOf(pubkey), listOf("wss://r"), 2000, pubkey) }
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
                emptyMap()
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
        coVerify { groupRepo.updateFromMeta(groupId, "NC", listOf(pubkey), listOf("wss://r"), 2000, pubkey) }
    }

    @Test
    fun `handle group_meta when currentGroup is null creates new group`() = runBlocking {
        coEvery { groupRepo.getById(groupId) } returns null
        val stranger = "bb".repeat(32)
        val meta = """{"name":"New","members":["$stranger"],"relays":["wss://r2"]}"""
        // When currentGroup is null, isCreator is true regardless of author
        processor.handle("group_meta", meta, stranger, groupId, 2000, false)
        coVerify { groupRepo.updateFromMeta(groupId, "New", listOf(stranger), listOf("wss://r2"), 2000, "") }
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
}
