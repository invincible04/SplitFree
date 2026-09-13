package com.splitfree.data.repository

import com.splitfree.data.local.dao.GroupDao
import com.splitfree.data.local.entities.GroupEntity
import com.splitfree.domain.model.group.Group
import com.splitfree.domain.repository.SecureStorageException
import com.splitfree.test.FakeSecureStorage
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.slot
import io.mockk.unmockkStatic
import io.mockk.verify
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class GroupRepositoryTest {
    private val groupDao = mockk<GroupDao>(relaxed = true)
    private lateinit var keyStore: FakeSecureStorage
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
            lastSyncTimestamp = 500,
            lastMetaTimestamp = 400
        )

    @Before
    fun setup() {
        mockkStatic(android.util.Log::class)
        every { android.util.Log.w(any<String>(), any<String>()) } returns 0
        every { android.util.Log.e(any<String>(), any<String>()) } returns 0

        keyStore = FakeSecureStorage()
        repo = GroupRepository(groupDao, keyStore)
    }

    @After
    fun teardown() {
        unmockkStatic(android.util.Log::class)
    }

    @Test
    fun `getAll maps entities to domain`() = runBlocking {
        coEvery { groupDao.getAll() } returns listOf(groupEntity)
        val groups = repo.getAll()
        assertEquals(1, groups.size)
        assertEquals("g1", groups[0].id)
        assertEquals("Trip", groups[0].name)
        assertEquals(listOf("pub1", "pub2"), groups[0].members)
    }

    @Test
    fun `getById returns null when not found`() = runBlocking {
        coEvery { groupDao.getById("missing") } returns null
        assertNull(repo.getById("missing"))
    }

    @Test
    fun `getById maps entity to domain`() = runBlocking {
        coEvery { groupDao.getById("g1") } returns groupEntity
        val group = repo.getById("g1")
        assertNotNull(group)
        assertEquals("Trip", group!!.name)
    }

    @Test
    fun `getGroupKey returns from storage`() = runBlocking {
        keyStore.putString("g1", "secretKey")
        assertEquals("secretKey", repo.getGroupKey("g1"))
    }

    @Test
    fun `getGroupKey returns null when missing`() = runBlocking {
        assertNull(repo.getGroupKey("g1"))
    }

    // --- epoch fallback (P1) ---

    @Test
    fun `getGroupKey at epoch 0 falls back to legacy un-epoched key`() = runBlocking {
        coEvery { groupDao.getById("g1") } returns groupEntity.copy(keyEpoch = 0)
        keyStore.putString("g1", "legacyKey")
        assertEquals("legacyKey", repo.getGroupKey("g1"))
    }

    @Test
    fun `getGroupKey at epoch 2 with only legacy key present returns null`() = runBlocking {
        coEvery { groupDao.getById("g1") } returns groupEntity.copy(keyEpoch = 2)
        keyStore.putString("g1", "legacyKey")
        // Falling back here would encrypt post-rotation traffic with a key the removed member still holds.
        assertNull(repo.getGroupKey("g1"))
    }

    @Test
    fun `getGroupKey at epoch 2 returns the epoch key when present`() = runBlocking {
        coEvery { groupDao.getById("g1") } returns groupEntity.copy(keyEpoch = 2)
        keyStore.putString("g1", "legacyKey")
        keyStore.putString("g1:2", "epoch2Key")
        assertEquals("epoch2Key", repo.getGroupKey("g1"))
    }

    @Test
    fun `getGroupKeyForEpoch 0 still falls back to legacy key`() = runBlocking {
        keyStore.putString("g1", "legacyKey")
        assertEquals("legacyKey", repo.getGroupKeyForEpoch("g1", 0))
    }

    @Test
    fun `getGroupKeyForEpoch non-zero does not fall back to legacy key`() = runBlocking {
        keyStore.putString("g1", "legacyKey")
        assertNull(repo.getGroupKeyForEpoch("g1", 1))
        keyStore.putString("g1:1", "epoch1Key")
        assertEquals("epoch1Key", repo.getGroupKeyForEpoch("g1", 1))
    }

    // --- failed key write must not leave a Room row behind (P3) ---

    @Test
    fun `save does not insert group when key write fails`() = runBlocking {
        val group = Group("g1", "Test", "", "pub", 1000, listOf("pub"), listOf("wss://r"))
        keyStore.failNextPut = true
        val failure = runCatching { repo.save(group, "key123") }.exceptionOrNull()
        assertTrue("expected SecureStorageException, got $failure", failure is SecureStorageException)
        coVerify(exactly = 0) { groupDao.insert(any()) }
        assertNull(keyStore.getString("g1:0", null))
    }

    @Test
    fun `saveGroupKeyForEpoch propagates key write failure`() = runBlocking {
        keyStore.failNextPut = true
        val failure = runCatching { repo.saveGroupKeyForEpoch("g1", 1, "k") }.exceptionOrNull()
        assertTrue(failure is SecureStorageException)
        assertNull(keyStore.getString("g1:1", null))
    }

    @Test
    fun `save stores key in storage and entity in dao`() = runBlocking {
        val group = Group("g1", "Test", "", "pub", 1000, listOf("pub"), listOf("wss://r"))
        repo.save(group, "key123")
        assertEquals("key123", keyStore.getString("g1", null))
        coVerify { groupDao.insert(any()) }
    }

    @Test
    fun `save sanitizes member names before persisting`() = runBlocking {
        val captured = slot<GroupEntity>()
        val group =
            Group(
                "g1",
                "Test",
                "",
                "pub1",
                1000,
                listOf("pub1"),
                listOf("wss://r"),
                memberNames = mapOf("pub1" to "  Alice  ", "pub2" to "Ignored")
            )
        repo.save(group, "key123")
        coVerify { groupDao.insert(capture(captured)) }
        assertEquals("""{"pub1":"Alice"}""", captured.captured.memberNames)
    }

    @Test
    fun `deleteGroupKey removes the legacy key and every epoch key up to the group's epoch`() = runBlocking {
        coEvery { groupDao.getById("g1") } returns groupEntity.copy(keyEpoch = 3)
        keyStore.putString("g1", "legacy")
        for (epoch in 0..3) keyStore.putString("g1:$epoch", "k$epoch")
        keyStore.putString("g2:0", "other-group")

        repo.deleteGroupKey("g1")

        assertNull(keyStore.getString("g1", null))
        for (epoch in 0..3) assertNull("g1:$epoch should be gone", keyStore.getString("g1:$epoch", null))
        assertNull(repo.getGroupKeyForEpoch("g1", 0))
        assertNull(repo.getGroupKeyForEpoch("g1", 3))
        assertEquals("other-group", keyStore.getString("g2:0", null))
    }

    @Test
    fun `deleteGroupKey sweeps a wide epoch range when the group row is already gone`() = runBlocking {
        coEvery { groupDao.getById("gone") } returns null
        keyStore.putString("gone", "legacy")
        keyStore.putString("gone:0", "k0")
        keyStore.putString("gone:7", "k7")
        keyStore.putString("gone:64", "k64")

        repo.deleteGroupKey("gone")

        assertNull(keyStore.getString("gone", null))
        assertNull(keyStore.getString("gone:0", null))
        assertNull(keyStore.getString("gone:7", null))
        assertNull(keyStore.getString("gone:64", null))
    }

    @Test
    fun `getMembers returns empty for unknown group`() = runBlocking {
        coEvery { groupDao.getById("missing") } returns null
        assertEquals(emptyList<String>(), repo.getMembers("missing"))
    }

    @Test
    fun `observeAll maps flow`() = runBlocking {
        every { groupDao.observeAll() } returns flowOf(listOf(groupEntity))
        val groups = repo.observeAll().first()
        assertEquals(1, groups.size)
        assertEquals("g1", groups[0].id)
    }

    @Test
    fun `observeById maps flow`() = runBlocking {
        every { groupDao.observeById("g1") } returns flowOf(groupEntity)
        val group = repo.observeById("g1").first()
        assertNotNull(group)
        assertEquals("Trip", group!!.name)
    }

    @Test
    fun `updateLastSync delegates to dao`() = runBlocking {
        repo.updateLastSync("g1", 999)
        coVerify { groupDao.updateLastSync("g1", 999) }
    }

    @Test
    fun `updateCreator delegates to dao without touching the metadata watermark`() = runBlocking {
        repo.updateCreator("g1", "creator", 1234)
        coVerify { groupDao.updateCreator("g1", "creator", 1234) }
        coVerify(exactly = 0) { groupDao.updateMeta(any(), any(), any(), any(), any(), any(), any()) }
        coVerify(exactly = 0) { groupDao.updateMetaIfNewer(any(), any(), any(), any(), any(), any(), any()) }
    }

    @Test(expected = IllegalArgumentException::class)
    fun `updateCreator rejects an empty creator`() = runBlocking {
        repo.updateCreator("g1", "", 1234)
    }

    @Test
    fun `updateFromMeta rejects too many members`() = runBlocking {
        val bigList = (1..51).map { "pub$it" }
        repo.updateFromMeta("g1", "name", bigList, emptyList())
        coVerify(exactly = 0) { groupDao.updateMeta(any(), any(), any(), any(), any(), any(), any()) }
        coVerify(exactly = 0) { groupDao.updateMetaIfNewer(any(), any(), any(), any(), any(), any(), any()) }
    }

    @Test
    fun `updateFromMeta with timestamp uses the single-statement LWW update`() = runBlocking {
        coEvery { groupDao.updateMetaIfNewer(any(), any(), any(), any(), any(), any(), any()) } returns 1
        repo.updateFromMeta("g1", "name", listOf("pub1"), listOf("wss://r"), eventTimestamp = 500)
        coVerify { groupDao.updateMetaIfNewer("g1", "name", any(), any(), "", 500, any()) }
        coVerify(exactly = 0) { groupDao.updateMeta(any(), any(), any(), any(), any(), any(), any()) }
    }

    @Test
    fun `updateFromMeta without timestamp uses the unconditional update with a positive watermark`() = runBlocking {
        val before = System.currentTimeMillis() / 1000
        repo.updateFromMeta("g1", "name", listOf("pub1"), listOf("wss://r"))
        coVerify {
            groupDao.updateMeta(
                "g1",
                "name",
                any(),
                any(),
                "",
                match { it >= before && it <= System.currentTimeMillis() / 1000 },
                any()
            )
        }
        coVerify(exactly = 0) { groupDao.updateMetaIfNewer(any(), any(), any(), any(), any(), any(), any()) }
    }

    @Test
    fun `updateFromMeta with a stale timestamp does not fall back to the unconditional update`() = runBlocking {
        coEvery { groupDao.updateMetaIfNewer(any(), any(), any(), any(), any(), any(), any()) } returns 0
        repo.updateFromMeta("g1", "name", listOf("pub1"), listOf("wss://r"), eventTimestamp = 500)
        coVerify { groupDao.updateMetaIfNewer("g1", "name", any(), any(), "", 500, any()) }
        coVerify(exactly = 0) { groupDao.updateMeta(any(), any(), any(), any(), any(), any(), any()) }
    }

    @Test
    fun `updateFromMeta passes createdBy when provided`() = runBlocking {
        repo.updateFromMeta("g1", "name", listOf("pub1"), listOf("wss://r"), createdBy = "creator")
        coVerify { groupDao.updateMeta("g1", "name", any(), any(), "creator", any(), any()) }
    }

    @Test
    fun `updateFromMeta sanitizes member names`() = runBlocking {
        val namesJson = slot<String>()
        repo.updateFromMeta(
            "g1",
            "name",
            listOf("pub1"),
            listOf("wss://r"),
            memberNames = mapOf("pub1" to "  Alice  ", "pub2" to "Ignored")
        )
        coVerify { groupDao.updateMeta("g1", "name", any(), any(), "", any(), capture(namesJson)) }
        assertEquals("""{"pub1":"Alice"}""", namesJson.captured)
    }

    @Test
    fun `updateFromMeta passes a null description through so the dao preserves the stored one`() = runBlocking {
        repo.updateFromMeta("g1", "name", listOf("pub1"), listOf("wss://r"))
        coVerify { groupDao.updateMeta("g1", "name", any(), any(), "", any(), any(), null) }

        repo.updateFromMeta("g1", "name", listOf("pub1"), listOf("wss://r"), eventTimestamp = 500)
        coVerify { groupDao.updateMetaIfNewer("g1", "name", any(), any(), "", 500, any(), null) }
    }

    @Test
    fun `updateFromMeta forwards an explicit description on both update paths`() = runBlocking {
        repo.updateFromMeta("g1", "name", listOf("pub1"), listOf("wss://r"), description = "Ski trip")
        coVerify { groupDao.updateMeta("g1", "name", any(), any(), "", any(), any(), "Ski trip") }

        repo.updateFromMeta("g1", "name", listOf("pub1"), listOf("wss://r"), eventTimestamp = 500, description = "")
        coVerify { groupDao.updateMetaIfNewer("g1", "name", any(), any(), "", 500, any(), "") }
    }

    @Test
    fun `updateFromMeta threads the eventId into both dao updates`() = runBlocking {
        repo.updateFromMeta("g1", "name", listOf("pub1"), listOf("wss://r"), eventTimestamp = 500, eventId = "e-500")
        coVerify { groupDao.updateMetaIfNewer("g1", "name", any(), any(), "", 500, any(), null, "e-500") }

        repo.updateFromMeta("g1", "name", listOf("pub1"), listOf("wss://r"), eventId = "e-local")
        coVerify { groupDao.updateMeta("g1", "name", any(), any(), "", any(), any(), null, "e-local") }
    }

    @Test
    fun `updateFromMeta defaults the eventId to empty so existing callers are unchanged`() = runBlocking {
        repo.updateFromMeta("g1", "name", listOf("pub1"), listOf("wss://r"), eventTimestamp = 500)
        coVerify { groupDao.updateMetaIfNewer("g1", "name", any(), any(), "", 500, any(), null, "") }
    }

    // --- applyMemberSelfUpdate ---

    private val selfEntity =
        groupEntity.copy(
            members = """["pub1","pub2"]""",
            memberNames = """{"pub1":"Alice","pub2":"Bob"}""",
            memberClocks = """{"pub2":"500:e5"}"""
        )

    private class SelfWrite(val members: String, val memberNames: String, val memberClocks: String)

    private fun captureSelfWrite(): SelfWrite {
        val members = slot<String>()
        val names = slot<String>()
        val clocks = slot<String>()
        coVerify(exactly = 1) { groupDao.updateMemberSelf("g1", capture(members), capture(names), capture(clocks)) }
        return SelfWrite(members.captured, names.captured, clocks.captured)
    }

    private fun assertNoSelfWrite() {
        coVerify(exactly = 0) { groupDao.updateMemberSelf(any(), any(), any(), any()) }
    }

    @Test
    fun `applyMemberSelfUpdate rejects an older timestamp`() = runBlocking {
        coEvery { groupDao.getById("g1") } returns selfEntity

        assertFalse(repo.applyMemberSelfUpdate("g1", "pub2", 400, "zzz", join = false, displayName = "Bobby"))

        assertNoSelfWrite()
    }

    @Test
    fun `applyMemberSelfUpdate rejects the same timestamp with a lower eventId`() = runBlocking {
        coEvery { groupDao.getById("g1") } returns selfEntity

        assertFalse(repo.applyMemberSelfUpdate("g1", "pub2", 500, "e4", join = false, displayName = "Bobby"))

        assertNoSelfWrite()
    }

    @Test
    fun `applyMemberSelfUpdate treats an exact replay as a no-op`() = runBlocking {
        coEvery { groupDao.getById("g1") } returns selfEntity

        assertFalse(repo.applyMemberSelfUpdate("g1", "pub2", 500, "e5", join = false, displayName = "Bobby"))

        assertNoSelfWrite()
    }

    @Test
    fun `applyMemberSelfUpdate applies the same timestamp with a greater eventId`() = runBlocking {
        coEvery { groupDao.getById("g1") } returns selfEntity

        assertTrue(repo.applyMemberSelfUpdate("g1", "pub2", 500, "e6", join = false, displayName = "Bobby"))

        val write = captureSelfWrite()
        assertEquals("""["pub1","pub2"]""", write.members)
        assertEquals("""{"pub1":"Alice","pub2":"Bobby"}""", write.memberNames)
        assertEquals("""{"pub2":"500:e6"}""", write.memberClocks)
    }

    @Test
    fun `applyMemberSelfUpdate applies a newer timestamp even with a lower eventId`() = runBlocking {
        coEvery { groupDao.getById("g1") } returns selfEntity

        assertTrue(repo.applyMemberSelfUpdate("g1", "pub2", 600, "a", join = false, displayName = "Bobby"))

        assertEquals("""{"pub2":"600:a"}""", captureSelfWrite().memberClocks)
    }

    @Test
    fun `applyMemberSelfUpdate applies when the member has no clock yet and keeps other clocks`() = runBlocking {
        coEvery { groupDao.getById("g1") } returns selfEntity

        assertTrue(repo.applyMemberSelfUpdate("g1", "pub1", 1, "e1", join = false, displayName = "Al"))

        val write = captureSelfWrite()
        assertEquals("""{"pub1":"Al","pub2":"Bob"}""", write.memberNames)
        assertEquals("""{"pub2":"500:e5","pub1":"1:e1"}""", write.memberClocks)
    }

    @Test
    fun `applyMemberSelfUpdate with join adds the author to members`() = runBlocking {
        coEvery { groupDao.getById("g1") } returns selfEntity

        assertTrue(repo.applyMemberSelfUpdate("g1", "pub3", 700, "e7", join = true, displayName = "Carol"))

        val write = captureSelfWrite()
        assertEquals("""["pub1","pub2","pub3"]""", write.members)
        assertEquals("""{"pub1":"Alice","pub2":"Bob","pub3":"Carol"}""", write.memberNames)
        assertEquals("""{"pub2":"500:e5","pub3":"700:e7"}""", write.memberClocks)
    }

    @Test
    fun `applyMemberSelfUpdate with join for an existing member does not duplicate them`() = runBlocking {
        coEvery { groupDao.getById("g1") } returns selfEntity

        assertTrue(repo.applyMemberSelfUpdate("g1", "pub2", 600, "e6", join = true, displayName = null))

        val write = captureSelfWrite()
        assertEquals("""["pub1","pub2"]""", write.members)
        assertEquals("""{"pub1":"Alice","pub2":"Bob"}""", write.memberNames)
        assertEquals("""{"pub2":"600:e6"}""", write.memberClocks)
    }

    @Test
    fun `applyMemberSelfUpdate ignores a name change from a non-member without a join`() = runBlocking {
        coEvery { groupDao.getById("g1") } returns selfEntity

        assertFalse(repo.applyMemberSelfUpdate("g1", "stranger", 900, "e9", join = false, displayName = "Mallory"))

        assertNoSelfWrite()
    }

    @Test
    fun `applyMemberSelfUpdate with an empty displayName clears only the author's own name`() = runBlocking {
        coEvery { groupDao.getById("g1") } returns selfEntity

        assertTrue(repo.applyMemberSelfUpdate("g1", "pub2", 600, "e6", join = false, displayName = ""))

        assertEquals("""{"pub1":"Alice"}""", captureSelfWrite().memberNames)
    }

    @Test
    fun `applyMemberSelfUpdate with a blank displayName clears like an empty one`() = runBlocking {
        coEvery { groupDao.getById("g1") } returns selfEntity

        assertTrue(repo.applyMemberSelfUpdate("g1", "pub2", 600, "e6", join = false, displayName = "   "))

        assertEquals("""{"pub1":"Alice"}""", captureSelfWrite().memberNames)
    }

    @Test
    fun `applyMemberSelfUpdate with a null displayName keeps the stored name`() = runBlocking {
        coEvery { groupDao.getById("g1") } returns selfEntity

        assertTrue(repo.applyMemberSelfUpdate("g1", "pub2", 600, "e6", join = false, displayName = null))

        val write = captureSelfWrite()
        assertEquals("""{"pub1":"Alice","pub2":"Bob"}""", write.memberNames)
        assertEquals("""{"pub2":"600:e6"}""", write.memberClocks)
    }

    @Test
    fun `applyMemberSelfUpdate trims and truncates the display name to 50 characters`() = runBlocking {
        coEvery { groupDao.getById("g1") } returns selfEntity
        val longName = "  " + "x".repeat(60) + "  "

        assertTrue(repo.applyMemberSelfUpdate("g1", "pub2", 600, "e6", join = false, displayName = longName))

        assertEquals("""{"pub1":"Alice","pub2":"${"x".repeat(50)}"}""", captureSelfWrite().memberNames)
    }

    @Test
    fun `applyMemberSelfUpdate never touches the creator watermark`() = runBlocking {
        coEvery { groupDao.getById("g1") } returns selfEntity

        repo.applyMemberSelfUpdate("g1", "pub3", 700, "e7", join = true, displayName = "Carol")

        coVerify(exactly = 0) { groupDao.updateMeta(any(), any(), any(), any(), any(), any(), any(), any(), any()) }
        coVerify(exactly = 0) {
            groupDao.updateMetaIfNewer(any(), any(), any(), any(), any(), any(), any(), any(), any())
        }
        coVerify(exactly = 0) { groupDao.update(any()) }
        coVerify(exactly = 0) { groupDao.insert(any()) }
    }

    @Test
    fun `applyMemberSelfUpdate returns false for an unknown group`() = runBlocking {
        coEvery { groupDao.getById("missing") } returns null

        assertFalse(repo.applyMemberSelfUpdate("missing", "pub1", 1, "e1", join = true, displayName = "x"))

        assertNoSelfWrite()
    }

    @Test
    fun `applyMemberSelfUpdate treats unreadable clocks as empty and repairs them on write`() = runBlocking {
        coEvery { groupDao.getById("g1") } returns selfEntity.copy(memberClocks = "{oops")

        assertTrue(repo.applyMemberSelfUpdate("g1", "pub2", 1, "e1", join = false, displayName = null))

        assertEquals("""{"pub2":"1:e1"}""", captureSelfWrite().memberClocks)
        verify(exactly = 1) { android.util.Log.w("GroupRepository", match<String> { "memberClocks" in it }) }
    }

    @Test
    fun `applyMemberSelfUpdate treats an unparseable clock entry as absent`() = runBlocking {
        coEvery { groupDao.getById("g1") } returns selfEntity.copy(memberClocks = """{"pub2":"garbage"}""")

        assertTrue(repo.applyMemberSelfUpdate("g1", "pub2", 1, "e1", join = false, displayName = null))

        assertEquals("""{"pub2":"1:e1"}""", captureSelfWrite().memberClocks)
    }

    @Test
    fun `applyMemberSelfUpdate rejects a join that would exceed the member cap`() = runBlocking {
        val full = (1..50).map { "pub$it" }
        coEvery { groupDao.getById("g1") } returns
            selfEntity.copy(members = full.joinToString(",", "[", "]") { "\"$it\"" }, memberClocks = "{}")

        assertFalse(repo.applyMemberSelfUpdate("g1", "pub51", 1, "e1", join = true, displayName = null))

        assertNoSelfWrite()
    }

    @Test
    fun `toDomain logs and degrades to empty when stored JSON is unreadable`() = runBlocking {
        coEvery { groupDao.getById("g1") } returns
            groupEntity.copy(members = "not json", relays = "[", memberNames = "{oops")

        val group = repo.getById("g1")!!

        assertEquals(emptyList<String>(), group.members)
        assertEquals(emptyList<String>(), group.relays)
        assertEquals(emptyMap<String, String>(), group.memberNames)
        verify(exactly = 3) { android.util.Log.w("GroupRepository", match<String> { "g1" in it }) }
    }

    @Test
    fun `getGroupEntity delegates to dao`() = runBlocking {
        coEvery { groupDao.getById("g1") } returns groupEntity
        val entity = repo.getGroupEntity("g1")
        assertEquals(groupEntity, entity)
    }
}
