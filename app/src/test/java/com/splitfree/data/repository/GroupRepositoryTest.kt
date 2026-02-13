package com.splitfree.data.repository

import android.content.Context
import android.content.SharedPreferences
import com.splitfree.data.local.GroupDao
import com.splitfree.data.local.entities.GroupEntity
import com.splitfree.domain.model.Group
import io.mockk.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class GroupRepositoryTest {
    private val groupDao = mockk<GroupDao>(relaxed = true)
    private val context = mockk<Context>(relaxed = true)
    private val prefs = mockk<SharedPreferences>(relaxed = true)
    private val editor = mockk<SharedPreferences.Editor>(relaxed = true)

    private lateinit var repo: GroupRepository

    private val groupEntity =
        GroupEntity(
            groupId = "g1",
            name = "Trip",
            description = "desc",
            createdBy = "pub1",
            createdAt = 1000,
            members = """["pub1","pub2"]""",
            relays = """["wss://r"]""",
            groupKey = "",
            lastSyncTimestamp = 500,
            lastMetaTimestamp = 400,
        )

    @Before
    fun setup() {
        mockkStatic(android.util.Log::class)
        every { android.util.Log.w(any<String>(), any<String>()) } returns 0
        every { android.util.Log.e(any<String>(), any<String>()) } returns 0

        every { prefs.getString(any(), any()) } returns null
        every { prefs.edit() } returns editor
        every { editor.putString(any(), any()) } returns editor
        every { editor.remove(any()) } returns editor
        every { editor.apply() } just Runs

        // Use reflection to inject mocked prefs since constructor needs Android Context
        repo = GroupRepository(groupDao, context)
        val field = GroupRepository::class.java.getDeclaredField("keyStore\$delegate")
        field.isAccessible = true
        field.set(repo, lazy { prefs })
    }

    @After
    fun teardown() {
        unmockkStatic(android.util.Log::class)
    }

    @Test
    fun `getAll maps entities to domain`() =
        runBlocking {
            coEvery { groupDao.getAll() } returns listOf(groupEntity)
            val groups = repo.getAll()
            assertEquals(1, groups.size)
            assertEquals("g1", groups[0].id)
            assertEquals("Trip", groups[0].name)
            assertEquals(listOf("pub1", "pub2"), groups[0].members)
        }

    @Test
    fun `getById returns null when not found`() =
        runBlocking {
            coEvery { groupDao.getById("missing") } returns null
            assertNull(repo.getById("missing"))
        }

    @Test
    fun `getById maps entity to domain`() =
        runBlocking {
            coEvery { groupDao.getById("g1") } returns groupEntity
            val group = repo.getById("g1")
            assertNotNull(group)
            assertEquals("Trip", group!!.name)
        }

    @Test
    fun `getGroupKey returns from prefs`() =
        runBlocking {
            every { prefs.getString("g1", null) } returns "secretKey"
            assertEquals("secretKey", repo.getGroupKey("g1"))
        }

    @Test
    fun `getGroupKey returns null when missing`() =
        runBlocking {
            every { prefs.getString("g1", null) } returns null
            assertNull(repo.getGroupKey("g1"))
        }

    @Test
    fun `save stores key in prefs and entity in dao`() =
        runBlocking {
            val group = Group("g1", "Test", "", "pub", 1000, listOf("pub"), listOf("wss://r"))
            repo.save(group, "key123")
            verify { editor.putString("g1", "key123") }
            coVerify { groupDao.insert(any()) }
        }

    @Test
    fun `deleteGroupKey removes from prefs`() {
        repo.deleteGroupKey("g1")
        verify { editor.remove("g1") }
    }

    @Test
    fun `getMembers returns empty for unknown group`() =
        runBlocking {
            coEvery { groupDao.getById("missing") } returns null
            assertEquals(emptyList<String>(), repo.getMembers("missing"))
        }

    @Test
    fun `observeAll maps flow`() =
        runBlocking {
            every { groupDao.observeAll() } returns flowOf(listOf(groupEntity))
            val groups = repo.observeAll().first()
            assertEquals(1, groups.size)
            assertEquals("g1", groups[0].id)
        }

    @Test
    fun `observeById maps flow`() =
        runBlocking {
            every { groupDao.observeById("g1") } returns flowOf(groupEntity)
            val group = repo.observeById("g1").first()
            assertNotNull(group)
            assertEquals("Trip", group!!.name)
        }

    @Test
    fun `updateLastSync delegates to dao`() =
        runBlocking {
            repo.updateLastSync("g1", 999)
            coVerify { groupDao.updateLastSync("g1", 999) }
        }

    @Test
    fun `updateFromMeta rejects too many members`() =
        runBlocking {
            val bigList = (1..51).map { "pub$it" }
            repo.updateFromMeta("g1", "name", bigList, emptyList())
            coVerify(exactly = 0) { groupDao.updateMeta(any(), any(), any(), any()) }
        }

    @Test
    fun `updateFromMeta with timestamp uses atomic update`() =
        runBlocking {
            coEvery { groupDao.updateMetaIfNewer(any(), any(), any(), any(), any()) } returns 1
            repo.updateFromMeta("g1", "name", listOf("pub1"), listOf("wss://r"), eventTimestamp = 500)
            coVerify { groupDao.updateMetaIfNewer("g1", "name", any(), any(), 500) }
            coVerify { groupDao.updateLastMetaTimestamp("g1", 500) }
        }

    @Test
    fun `updateFromMeta without timestamp uses simple update`() =
        runBlocking {
            repo.updateFromMeta("g1", "name", listOf("pub1"), listOf("wss://r"))
            coVerify { groupDao.updateMeta("g1", "name", any(), any()) }
        }

    @Test
    fun `updateFromMeta with timestamp skips lastMetaTimestamp when not newer`() =
        runBlocking {
            coEvery { groupDao.updateMetaIfNewer(any(), any(), any(), any(), any()) } returns 0
            repo.updateFromMeta("g1", "name", listOf("pub1"), listOf("wss://r"), eventTimestamp = 500)
            coVerify { groupDao.updateMetaIfNewer("g1", "name", any(), any(), 500) }
            coVerify(exactly = 0) { groupDao.updateLastMetaTimestamp(any(), any()) }
        }

    @Test
    fun `getGroupEntity delegates to dao`() =
        runBlocking {
            coEvery { groupDao.getById("g1") } returns groupEntity
            val entity = repo.getGroupEntity("g1")
            assertEquals(groupEntity, entity)
        }
}
