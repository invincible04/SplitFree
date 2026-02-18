package com.splitfree.sync.event

import com.splitfree.data.repository.GroupRepository
import com.splitfree.domain.model.group.Group
import com.splitfree.domain.usecase.group.MigrateGroupUseCase
import com.splitfree.domain.usecase.group.RevokeKeyUseCase
import com.splitfree.domain.usecase.sync.SelfHealUseCase
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test

class EventPostProcessorTest {
    private val groupRepo = mockk<GroupRepository>(relaxed = true)
    private val migrateGroup = mockk<MigrateGroupUseCase>(relaxed = true)
    private val revokeKey = mockk<RevokeKeyUseCase>(relaxed = true)
    private val selfHeal = mockk<SelfHealUseCase>(relaxed = true)
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
        processor = EventPostProcessor(groupRepo, migrateGroup, revokeKey, selfHeal)
    }

    @After
    fun teardown() = unmockkAll()

    @Test
    fun `handle null decrypted is no-op`() = runBlocking {
        processor.handle("group_meta", null, pubkey, groupId, 1000, false)
        coVerify(exactly = 0) { groupRepo.updateFromMeta(any(), any(), any(), any(), any()) }
    }

    @Test
    fun `handle unknown event type is no-op`() = runBlocking {
        processor.handle("expense", """{"data":"x"}""", pubkey, groupId, 1000, false)
        coVerify(exactly = 0) { groupRepo.updateFromMeta(any(), any(), any(), any(), any()) }
        coVerify(exactly = 0) { migrateGroup.handleMigration(any(), any(), any()) }
        coVerify(exactly = 0) { revokeKey.handleRevocation(any(), any(), any()) }
    }

    @Test
    fun `handle group_meta updates group from creator`() = runBlocking {
        val meta =
            """{"name":"New","description":"","created_by":"$pubkey",""" +
                """"created_at":1000,"members":["$pubkey"],"relays":["wss://r"]}"""
        processor.handle("group_meta", meta, pubkey, groupId, 2000, false)
        coVerify { groupRepo.updateFromMeta(groupId, "New", listOf(pubkey), listOf("wss://r"), 2000) }
    }

    @Test
    fun `handle group_meta with empty members skips update`() = runBlocking {
        val meta = """{"name":"X","members":[],"relays":["wss://r"]}"""
        processor.handle("group_meta", meta, pubkey, groupId, 1000, false)
        coVerify(exactly = 0) { groupRepo.updateFromMeta(any(), any(), any(), any(), any()) }
    }

    @Test
    fun `handle group_meta non-creator only adds self`() = runBlocking {
        val stranger = "bb".repeat(32)
        val meta = """{"name":"Test","members":["$pubkey","$stranger"],"relays":["wss://r"]}"""
        processor.handle("group_meta", meta, stranger, groupId, 2000, false)
        coVerify {
            groupRepo.updateFromMeta(groupId, "Test", match { stranger in it && pubkey in it }, listOf("wss://r"), 2000)
        }
    }

    @Test
    fun `handle group_migrate delegates to MigrateGroupUseCase`() = runBlocking {
        processor.handle("group_migrate", """{"data":"x"}""", pubkey, groupId, 1000, false)
        coVerify { migrateGroup.handleMigration("""{"data":"x"}""", pubkey, groupId) }
    }

    @Test
    fun `handle key_revocation delegates to RevokeKeyUseCase`() = runBlocking {
        processor.handle("key_revocation", """{"data":"x"}""", pubkey, groupId, 1000, false)
        coVerify { revokeKey.handleRevocation("""{"data":"x"}""", pubkey, groupId) }
    }

    @Test
    fun `handle catches exception from migrateGroup`() = runBlocking {
        coEvery { migrateGroup.handleMigration(any(), any(), any()) } throws RuntimeException("boom")
        processor.handle("group_migrate", """{"data":"x"}""", pubkey, groupId, 1000, false)
        // Should not throw — exception is caught internally
    }

    @Test
    fun `handle rethrows CancellationException`() {
        coEvery { migrateGroup.handleMigration(any(), any(), any()) } throws
            kotlinx.coroutines.CancellationException("cancel")
        try {
            runBlocking { processor.handle("group_migrate", """{"data":"x"}""", pubkey, groupId, 1000, false) }
            org.junit.Assert.fail("Expected CancellationException")
        } catch (_: kotlinx.coroutines.CancellationException) { /* expected */ }
    }

    @Test
    fun `handle group_meta triggers self-heal on relay change`() = runBlocking {
        val newRelayGroup = group.copy(relays = listOf("wss://old"))
        coEvery { groupRepo.getById(groupId) } returns newRelayGroup
        val meta = """{"name":"Test","members":["$pubkey"],"relays":["wss://new"]}"""
        processor.handle("group_meta", meta, pubkey, groupId, 2000, false)
        // selfHeal is launched in a separate scope, give it a moment
        kotlinx.coroutines.delay(100)
        coVerify { selfHeal(groupId) }
    }
}
