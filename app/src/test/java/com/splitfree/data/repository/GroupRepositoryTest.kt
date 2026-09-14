package com.splitfree.data.repository

import com.splitfree.data.local.dao.GroupDao
import com.splitfree.data.local.entities.GroupEntity
import com.splitfree.domain.invite.InviteLinkCodec
import com.splitfree.domain.model.group.Group
import com.splitfree.domain.repository.SecureStorage
import com.splitfree.domain.repository.SecureStorageException
import com.splitfree.domain.util.RelayDefaults
import com.splitfree.test.FakeSecureStorage
import io.mockk.Called
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.slot
import io.mockk.unmockkStatic
import io.mockk.verify
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
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
        every { android.util.Log.i(any<String>(), any<String>()) } returns 0
        every { android.util.Log.w(any<String>(), any<String>()) } returns 0
        every { android.util.Log.e(any<String>(), any<String>()) } returns 0

        keyStore = FakeSecureStorage()
        repo = GroupRepository(groupDao, keyStore)
        coEvery {
            groupDao.updateMemberSelf(
                any(),
                any(),
                any(),
                any(),
                any(),
                any(),
                any(),
                any(),
                any(),
                any(),
                any()
            )
        } returns 1
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

    // --- epoch fallback: the un-epoched entry under the plain group id stands in for epoch 0 only ---

    @Test
    fun `getGroupKey at epoch 0 falls back to the un-epoched key under the plain group id`() = runBlocking {
        coEvery { groupDao.getById("g1") } returns groupEntity.copy(keyEpoch = 0)
        keyStore.putString("g1", "legacyKey")
        assertEquals("legacyKey", repo.getGroupKey("g1"))
    }

    @Test
    fun `getGroupKey at epoch 2 with only the un-epoched key present returns null`() = runBlocking {
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
    fun `getGroupKeyForEpoch 0 falls back to the un-epoched key`() = runBlocking {
        keyStore.putString("g1", "legacyKey")
        assertEquals("legacyKey", repo.getGroupKeyForEpoch("g1", 0))
    }

    @Test
    fun `getGroupKeyForEpoch non-zero does not fall back to the un-epoched key`() = runBlocking {
        keyStore.putString("g1", "legacyKey")
        assertNull(repo.getGroupKeyForEpoch("g1", 1))
        keyStore.putString("g1:1", "epoch1Key")
        assertEquals("epoch1Key", repo.getGroupKeyForEpoch("g1", 1))
    }

    // --- failed key write must not leave a Room row behind ---

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
    fun `deleteGroupKey removes the un-epoched key and every epoch key up to the group's epoch`() = runBlocking {
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
        coEvery { groupDao.getById("g1") } returns groupEntity.copy(createdBy = "")
        coEvery { groupDao.updateCreator("g1", "creator", 1234, "{}") } returns 1
        repo.updateCreator("g1", "creator", 1234)
        coVerify { groupDao.updateCreator("g1", "creator", 1234, "{}") }
        assertNoMetaWrite()
        coVerify(exactly = 0) { groupDao.overrideMembership(any(), any(), any(), any(), any(), any()) }
    }

    @Test(expected = IllegalArgumentException::class)
    fun `updateCreator rejects an empty creator`() = runBlocking {
        repo.updateCreator("g1", "", 1234)
    }

    // --- updateFromMeta ---

    private fun assertNoMetaWrite() {
        coVerify(exactly = 0) {
            groupDao.updateMetaIfNewer(
                any(),
                any(),
                any(),
                any(),
                any(),
                any(),
                any(),
                any(),
                any(),
                any(),
                any(),
                any(),
                any(),
                any(),

                any(), any()
            )
        }
    }

    private fun stubMetaWrite(rows: Int = 1) {
        coEvery {
            groupDao.updateMetaIfNewer(
                any(),
                any(),
                any(),
                any(),
                any(),
                any(),
                any(),
                any(),
                any(),
                any(),
                any(),
                any(),
                any(),
                any(),

                any(), any()
            )
        } returns rows
    }

    @Test
    fun `updateFromMeta requires a positive event timestamp`() = runBlocking {
        coEvery { groupDao.getById("g1") } returns groupEntity
        val zero = runCatching { repo.updateFromMeta("g1", "name", listOf("pub1"), listOf("wss://r"), 0) }
        val negative = runCatching {
            repo.updateFromMeta(
                "g1",
                "name",
                listOf("pub1"),
                listOf("wss://r"),
                -5
            )
        }
        assertTrue(zero.exceptionOrNull() is IllegalArgumentException)
        assertTrue(negative.exceptionOrNull() is IllegalArgumentException)
        assertNoMetaWrite()
    }

    @Test
    fun `updateFromMeta rejects too many members`() = runBlocking {
        coEvery { groupDao.getById("g1") } returns groupEntity
        val bigList = (1..51).map { "pub$it" }
        assertFalse(repo.updateFromMeta("g1", "name", bigList, emptyList(), eventTimestamp = 500))
        assertNoMetaWrite()
    }

    @Test
    fun `updateFromMeta returns false for an unknown group`() = runBlocking {
        coEvery { groupDao.getById("missing") } returns null
        assertFalse(
            repo.updateFromMeta(
                "missing",
                "name",
                listOf("pub1"),
                listOf("wss://r"),
                eventTimestamp = 500
            )
        )
        assertNoMetaWrite()
    }

    @Test
    fun `updateFromMeta with a newer timestamp uses the single-statement LWW update`() = runBlocking {
        coEvery { groupDao.getById("g1") } returns groupEntity
        stubMetaWrite(1)
        assertTrue(repo.updateFromMeta("g1", "name", listOf("pub1"), listOf("wss://r"), eventTimestamp = 500))
        coVerify {
            groupDao.updateMetaIfNewer(
                "g1",
                "name",
                """["pub1"]""",
                """["wss://r"]""",
                "",
                500,
                any(),
                null,
                "",
                any(),
                any(),
                any(),

                any(), any(), any(), any()
            )
        }
    }

    @Test
    fun `updateFromMeta is a no-op when the clock is not newer`() = runBlocking {
        coEvery { groupDao.getById("g1") } returns
            groupEntity.copy(lastMetaTimestamp = 3000, lastMetaEventId = "e-3000")

        // Older timestamp.
        assertFalse(
            repo.updateFromMeta(
                "g1",
                "name",
                listOf("pub1"),
                listOf("wss://r"),
                2500,
                eventId = "zzz"
            )
        )
        // Exact replay of the stored clock.
        assertFalse(
            repo.updateFromMeta(
                "g1",
                "name",
                listOf("pub1"),
                listOf("wss://r"),
                3000,
                eventId = "e-3000"
            )
        )
        // Same timestamp, lower eventId.
        assertFalse(
            repo.updateFromMeta(
                "g1",
                "name",
                listOf("pub1"),
                listOf("wss://r"),
                3000,
                eventId = "e-2999"
            )
        )

        assertNoMetaWrite()
    }

    @Test
    fun `updateFromMeta applies the same timestamp with a greater eventId`() = runBlocking {
        coEvery { groupDao.getById("g1") } returns groupEntity.copy(
            lastMetaTimestamp = 3000,
            lastMetaEventId = "aaa"
        )
        stubMetaWrite(1)
        assertTrue(
            repo.updateFromMeta(
                "g1",
                "name",
                listOf("pub1"),
                listOf("wss://r"),
                3000,
                eventId = "bbb"
            )
        )
        coVerify {
            groupDao.updateMetaIfNewer(
                "g1",
                "name",
                any(),
                any(),
                "",
                3000,
                any(),
                null,
                "bbb",
                any(),
                any(),
                any(),
                any(),
                any(),
                any(),
                any()
            )
        }
    }

    @Test
    fun `updateFromMeta reports false when the dao's final guard rejects the write`() = runBlocking {
        coEvery { groupDao.getById("g1") } returns groupEntity
        stubMetaWrite(0)
        assertFalse(
            repo.updateFromMeta(
                "g1",
                "name",
                listOf("pub1"),
                listOf("wss://r"),
                eventTimestamp = 500
            )
        )
        coVerify(exactly = 1) {
            groupDao.updateMetaIfNewer(
                "g1",
                "name",
                any(),
                any(),
                "",
                500,
                any(),
                null,
                "",
                any(),
                any(),
                any(),
                any(),
                any(),
                any(),
                any()
            )
        }
    }

    @Test
    fun `updateFromMeta passes createdBy when provided`() = runBlocking {
        coEvery { groupDao.getById("g1") } returns groupEntity
        stubMetaWrite(1)
        repo.updateFromMeta("g1", "name", listOf("pub1"), listOf("wss://r"), 500, createdBy = "creator")
        coVerify {
            groupDao.updateMetaIfNewer(
                "g1",
                "name",
                any(),
                any(),
                "creator",
                500,
                any(),
                null,
                "",
                any(),
                any(),
                any(),
                any(),
                any(),

                any(), any()
            )
        }
    }

    @Test
    fun `updateFromMeta sanitizes member names`() = runBlocking {
        coEvery { groupDao.getById("g1") } returns groupEntity
        stubMetaWrite(1)
        val namesJson = slot<String>()
        repo.updateFromMeta(
            "g1",
            "name",
            listOf("pub1"),
            listOf("wss://r"),
            eventTimestamp = 500,
            memberNames = mapOf("pub1" to "  Alice  ", "pub2" to "Ignored")
        )
        coVerify {
            groupDao.updateMetaIfNewer(
                "g1", "name", any(), any(), "", 500, capture(namesJson), null, "", any(), any(), any(), any(),
                any(), any(), any()
            )
        }
        assertEquals("""{"pub1":"Alice"}""", namesJson.captured)
    }

    @Test
    fun `updateFromMeta drops relays that are not wss`() = runBlocking {
        coEvery { groupDao.getById("g1") } returns groupEntity
        stubMetaWrite(1)
        val relaysJson = slot<String>()
        repo.updateFromMeta("g1", "name", listOf("pub1"), listOf("wss://ok", "ws://plain", "http://x"), 500)
        coVerify {
            groupDao.updateMetaIfNewer(
                "g1",
                "name",
                any(),
                capture(relaysJson),
                "",
                500,
                any(),
                null,
                "",
                any(),
                any(),
                any(),
                any(),

                any(), any(), any()
            )
        }
        assertEquals("""["wss://ok"]""", relaysJson.captured)
    }

    @Test
    fun `updateFromMeta drops a relay an invite link cannot carry`() = runBlocking {
        coEvery { groupDao.getById("g1") } returns groupEntity
        stubMetaWrite(1)
        val maximal = "wss://" + "a".repeat(240) + ".example"
        val oneByteTooLong = "wss://" + "a".repeat(241) + ".example"
        assertEquals(254, maximal.toByteArray(Charsets.UTF_8).size)
        assertEquals(255, oneByteTooLong.toByteArray(Charsets.UTF_8).size)

        assertEquals(
            listOf(maximal),
            capturedRelays { repo.updateFromMeta("g1", "name", listOf("pub1"), listOf(oneByteTooLong, maximal), 500) }
        )
    }

    @Test
    fun `updateFromMeta accepts ten known relays`() = runBlocking {
        coEvery { groupDao.getById("g1") } returns groupEntity
        stubMetaWrite(1)
        val relays = RelayDefaults.KNOWN_RELAYS.take(InviteLinkCodec.MAX_RELAYS)

        assertEquals(relays, capturedRelays { repo.updateFromMeta("g1", "name", listOf("pub1"), relays, 500) })
    }

    @Test
    fun `updateFromMeta rejects eleven valid relays rather than truncating`() = runBlocking {
        val relays = RelayDefaults.KNOWN_RELAYS.take(InviteLinkCodec.MAX_RELAYS + 1)
        assertTrue(relays.all(InviteLinkCodec::relayFits))

        assertFalse(repo.updateFromMeta("g1", "name", listOf("pub1"), relays, 500))

        coVerify { groupDao wasNot Called }
    }

    @Test
    fun `updateFromMeta accepts exactly 255 custom bytes alongside known relays`() = runBlocking {
        coEvery { groupDao.getById("g1") } returns groupEntity
        stubMetaWrite(1)
        val custom = budgetRelays(secondPathLength = 106)
        val relays = custom + RelayDefaults.DEFAULT_RELAYS
        assertEquals(255, custom.sumOf { 1 + it.toByteArray(Charsets.UTF_8).size })
        assertTrue(InviteLinkCodec.fitsInviteLink(relays))

        val stored = capturedRelays {
            assertTrue(repo.updateFromMeta("g1", "name", listOf("pub1"), relays, 500))
        }

        assertEquals(relays, stored)
    }

    @Test
    fun `overbudget meta leaves all stored fields intact and a valid later meta can repair`() = runBlocking {
        stubMetaPersistence(groupEntity)
        val relays = budgetRelays()
        assertEquals(256, relays.sumOf { 1 + it.toByteArray(Charsets.UTF_8).size })
        assertTrue(relays.all(InviteLinkCodec::relayFits))
        assertFalse(InviteLinkCodec.fitsInviteLink(relays))

        assertFalse(
            repo.updateFromMeta(
                "g1", "Rejected", listOf("pub3"), relays, 500, "pub3",
                mapOf("pub3" to "Changed"), "Changed description", "rejected"
            )
        )

        coVerify { groupDao wasNot Called }
        assertEquals(groupEntity, repo.getGroupEntity("g1"))
        val repairRelays = budgetRelays(secondPathLength = 106)
        assertTrue(
            repo.updateFromMeta(
                "g1", "Repaired", listOf("pub1"), repairRelays, 501, "pub1",
                mapOf("pub1" to "Alice"), "New description", "repair"
            )
        )
        assertEquals(
            groupEntity.copy(
                name = "Repaired",
                members = """["pub1"]""",
                relays = Json.encodeToString(ListSerializer(String.serializer()), repairRelays),
                createdBy = "pub1",
                memberNames = """{"pub1":"Alice"}""",
                description = "New description",
                lastMetaTimestamp = 501,
                lastMetaEventId = "repair"
            ),
            repo.getGroupEntity("g1")
        )
    }

    @Test
    fun `saved overbudget relays remain unchanged until valid replacement metadata arrives`() = runBlocking {
        val savedRelays = budgetRelays()
        val saved = groupEntity.copy(relays = Json.encodeToString(ListSerializer(String.serializer()), savedRelays))
        stubMetaPersistence(saved)

        assertEquals(savedRelays, repo.getById("g1")!!.relays)
        assertEquals(saved, repo.getGroupEntity("g1"))
        val valid = listOf("wss://repaired.example")
        assertTrue(repo.updateFromMeta("g1", "Trip", listOf("pub1", "pub2"), valid, 500))
        assertEquals(valid, repo.getById("g1")!!.relays)
    }

    private fun budgetRelays(secondPathLength: Int = 107): List<String> = listOf(
        "wss://relay.example/" + "a".repeat(107),
        "wss://relay.example/" + "b".repeat(secondPathLength)
    )

    private fun stubMetaPersistence(initial: GroupEntity) {
        var stored = initial
        coEvery { groupDao.getById("g1") } answers { stored }
        coEvery {
            groupDao.updateMetaIfNewer(
                any(), any(), any(), any(), any(), any(), any(), any(),
                any(), any(), any(), any(), any(), any(), any(), any()
            )
        } answers {
            stored = stored.copy(
                name = secondArg(),
                members = thirdArg(),
                relays = arg(3),
                createdBy = arg<String>(4).ifEmpty { stored.createdBy },
                lastMetaTimestamp = arg(5),
                memberNames = arg(6),
                description = arg<String?>(7) ?: stored.description,
                lastMetaEventId = arg(8)
            )
            1
        }
    }

    /** Runs [write] and returns the relay list it handed to `updateMetaIfNewer`. */
    private suspend fun capturedRelays(write: suspend () -> Unit): List<String> {
        write()
        val relaysJson = slot<String>()
        coVerify {
            groupDao.updateMetaIfNewer(
                any(),
                any(),
                any(),
                capture(relaysJson),
                any(),
                any(),
                any(),
                any(),
                any(),
                any(),
                any(),
                any(),
                any(),
                any(),

                any(), any()
            )
        }
        return Json.decodeFromString(ListSerializer(String.serializer()), relaysJson.captured)
    }

    @Test
    fun `updateFromMeta passes a null description through so the dao preserves the stored one`() = runBlocking {
        coEvery { groupDao.getById("g1") } returns groupEntity
        stubMetaWrite(1)
        repo.updateFromMeta("g1", "name", listOf("pub1"), listOf("wss://r"), eventTimestamp = 500)
        coVerify {
            groupDao.updateMetaIfNewer(
                "g1",
                "name",
                any(),
                any(),
                "",
                500,
                any(),
                null,
                "",
                any(),
                any(),
                any(),
                any(),
                any(),
                any(),
                any()
            )
        }
    }

    @Test
    fun `updateFromMeta forwards an explicit description`() = runBlocking {
        coEvery { groupDao.getById("g1") } returns groupEntity
        stubMetaWrite(1)
        repo.updateFromMeta("g1", "name", listOf("pub1"), listOf("wss://r"), 500, description = "Ski trip")
        coVerify {
            groupDao.updateMetaIfNewer(
                "g1", "name", any(), any(), "", 500, any(), "Ski trip", "", any(), any(), any(), any(), any(),
                any(), any()
            )
        }

        repo.updateFromMeta("g1", "name", listOf("pub1"), listOf("wss://r"), 600, description = "")
        coVerify {
            groupDao.updateMetaIfNewer(
                "g1",
                "name",
                any(),
                any(),
                "",
                600,
                any(),
                "",
                "",
                any(),
                any(),
                any(),
                any(),
                any(),
                any(),
                any()
            )
        }
    }

    @Test
    fun `updateFromMeta threads the eventId into the dao update`() = runBlocking {
        coEvery { groupDao.getById("g1") } returns groupEntity
        stubMetaWrite(1)
        repo.updateFromMeta(
            "g1",
            "name",
            listOf("pub1"),
            listOf("wss://r"),
            eventTimestamp = 500,
            eventId = "e-500"
        )
        coVerify {
            groupDao.updateMetaIfNewer(
                "g1",
                "name",
                any(),
                any(),
                "",
                500,
                any(),
                null,
                "e-500",
                any(),
                any(),
                any(),
                any(),
                any(),
                any(),
                any()
            )
        }
    }

    @Test
    fun `updateFromMeta defaults the eventId to empty so existing callers are unchanged`() = runBlocking {
        coEvery { groupDao.getById("g1") } returns groupEntity
        stubMetaWrite(1)
        repo.updateFromMeta("g1", "name", listOf("pub1"), listOf("wss://r"), eventTimestamp = 500)
        coVerify {
            groupDao.updateMetaIfNewer(
                "g1",
                "name",
                any(),
                any(),
                "",
                500,
                any(),
                null,
                "",
                any(),
                any(),
                any(),
                any(),
                any(),
                any(),
                any()
            )
        }
    }

    @Test
    fun `updateFromMeta keeps the stored roster when applyRoster is false but still updates the name`() = runBlocking {
        coEvery { groupDao.getById("g1") } returns
            groupEntity.copy(
                members = """["pub1","pub2"]""",
                memberNames = """{"pub1":"Alice","pub2":"Bob"}"""
            )
        stubMetaWrite(1)
        val membersJson = slot<String>()
        val namesJson = slot<String>()

        assertTrue(
            repo.updateFromMeta(
                "g1",
                "Renamed",
                members = listOf("pub1", "pub3"),
                relays = listOf("wss://new"),
                eventTimestamp = 500,
                memberNames = mapOf("pub1" to "Al", "pub2" to "Bobby", "pub3" to "Carol"),
                description = "fresh",
                eventId = "e-500",
                applyRoster = false
            )
        )

        coVerify {
            groupDao.updateMetaIfNewer(
                "g1",
                "Renamed",
                capture(membersJson),
                """["wss://new"]""",
                "",
                500,
                capture(namesJson),
                "fresh",
                "e-500",
                any(), any(), any(), any(), any(), any(), any()
            )
        }
        // Roster is the stored one, not the meta's; names follow the stored roster.
        assertEquals("""["pub1","pub2"]""", membersJson.captured)
        assertEquals("""{"pub1":"Al","pub2":"Bobby"}""", namesJson.captured)
    }

    @Test
    fun `updateFromMeta applies the meta's roster when applyRoster is true`() = runBlocking {
        coEvery { groupDao.getById("g1") } returns
            groupEntity.copy(
                members = """["pub1","pub2"]""",
                memberNames = """{"pub1":"Alice","pub2":"Bob"}"""
            )
        stubMetaWrite(1)
        val membersJson = slot<String>()
        val namesJson = slot<String>()

        repo.updateFromMeta(
            "g1",
            "name",
            listOf("pub1", "pub3"),
            listOf("wss://r"),
            500,
            memberNames = mapOf("pub1" to "Al", "pub2" to "Bobby", "pub3" to "Carol")
        )

        coVerify {
            groupDao.updateMetaIfNewer(
                "g1",
                "name",
                capture(membersJson),
                any(),
                "",
                500,
                capture(namesJson),
                null,
                "",
                any(),
                any(),

                any(), any(), any(), any(), any()
            )
        }
        assertEquals("""["pub1","pub3"]""", membersJson.captured)
        assertEquals("""{"pub1":"Al","pub3":"Carol"}""", namesJson.captured)
    }

    @Test
    fun `updateFromMeta keeps a member's own newer display name over the creator's map`() = runBlocking {
        coEvery { groupDao.getById("g1") } returns
            groupEntity.copy(
                members = """["pub1","pub2"]""",
                memberNames = """{"pub1":"Alice","pub2":"Bob"}""",
                memberClocks = """{"pub2":"2000:ffff"}"""
            )
        stubMetaWrite(1)
        val namesJson = slot<String>()

        // Creator meta older than pub2's own rename: pub2 keeps their stored name.
        repo.updateFromMeta(
            "g1",
            "name",
            listOf("pub1", "pub2"),
            listOf("wss://r"),
            1500,
            memberNames = mapOf("pub1" to "Al", "pub2" to "Robert"),
            eventId = "e-1500"
        )
        coVerify {
            groupDao.updateMetaIfNewer(
                "g1", "name", any(), any(), "", 1500, capture(namesJson), null, "e-1500", any(), any(), any(),
                any(), any(), any(), any()
            )
        }
        assertEquals("""{"pub1":"Al","pub2":"Bob"}""", namesJson.captured)
    }

    @Test
    fun `updateFromMeta lets the creator's map win over an older member rename`() = runBlocking {
        coEvery { groupDao.getById("g1") } returns
            groupEntity.copy(
                members = """["pub1","pub2"]""",
                memberNames = """{"pub1":"Alice","pub2":"Bob"}""",
                memberClocks = """{"pub2":"2000:ffff"}"""
            )
        stubMetaWrite(1)
        val namesJson = slot<String>()

        repo.updateFromMeta(
            "g1",
            "name",
            listOf("pub1", "pub2"),
            listOf("wss://r"),
            2500,
            memberNames = mapOf("pub1" to "Al", "pub2" to "Robert"),
            eventId = "e-2500"
        )
        coVerify {
            groupDao.updateMetaIfNewer(
                "g1", "name", any(), any(), "", 2500, capture(namesJson), null, "e-2500", any(), any(), any(),
                any(), any(), any(), any()
            )
        }
        assertEquals("""{"pub1":"Al","pub2":"Robert"}""", namesJson.captured)
    }

    @Test
    fun `updateFromMeta with a member clock at the same instant breaks the tie on eventId`() = runBlocking {
        coEvery { groupDao.getById("g1") } returns
            groupEntity.copy(
                members = """["pub1","pub2"]""",
                memberNames = """{"pub2":"Bob"}""",
                memberClocks = """{"pub2":"2000:bbb"}"""
            )
        stubMetaWrite(1)
        val namesJson = slot<String>()

        // Member clock (2000, bbb) > meta clock (2000, aaa): member's name is newer and kept.
        repo.updateFromMeta(
            "g1",
            "n",
            listOf("pub2"),
            listOf("wss://r"),
            2000,
            memberNames = mapOf("pub2" to "R"),
            eventId = "aaa"
        )
        coVerify {
            groupDao.updateMetaIfNewer(
                "g1",
                "n",
                any(),
                any(),
                "",
                2000,
                capture(namesJson),
                null,
                "aaa",
                any(),
                any(),
                any(),
                any(),

                any(), any(), any()
            )
        }
        assertEquals("""{"pub2":"Bob"}""", namesJson.captured)

        // Member clock (2000, bbb) < meta clock (2000, ccc): the creator's map wins.
        repo.updateFromMeta(
            "g1",
            "n",
            listOf("pub2"),
            listOf("wss://r"),
            2000,
            memberNames = mapOf("pub2" to "R"),
            eventId = "ccc"
        )
        coVerify {
            groupDao.updateMetaIfNewer(
                "g1",
                "n",
                any(),
                any(),
                "",
                2000,
                capture(namesJson),
                null,
                "ccc",
                any(),
                any(),
                any(),
                any(),

                any(), any(), any()
            )
        }
        assertEquals("""{"pub2":"R"}""", namesJson.captured)
    }

    // --- saveGroupKeyForEpoch immutability ---

    @Test
    fun `saveGroupKeyForEpoch is a no-op for identical material and throws for conflicting material`() = runBlocking {
        repo.saveGroupKeyForEpoch("g1", 1, "K1")
        assertEquals("K1", keyStore.getString("g1:1", null))

        // Same key again: no write at all (a write would trip failNextPut).
        keyStore.failNextPut = true
        repo.saveGroupKeyForEpoch("g1", 1, "K1")
        assertTrue("identical key must not be re-written", keyStore.failNextPut)
        keyStore.failNextPut = false
        assertEquals("K1", keyStore.getString("g1:1", null))

        // Different key for the same epoch: refused, stored material untouched.
        val failure = runCatching { repo.saveGroupKeyForEpoch("g1", 1, "K2") }.exceptionOrNull()
        assertTrue("expected IllegalStateException, got $failure", failure is IllegalStateException)
        assertEquals("K1", keyStore.getString("g1:1", null))

        // Another epoch is independent.
        repo.saveGroupKeyForEpoch("g1", 2, "K2")
        assertEquals("K2", keyStore.getString("g1:2", null))
    }

    // --- applyKeyRotation / overrideMembership ---

    @Test
    fun `applyKeyRotation delegates to the dao with the sanitized roster and reports whether the epoch advanced`() =
        runBlocking {
            coEvery { groupDao.getById("g1") } returns groupEntity
            coEvery {
                groupDao.applyKeyRotation(
                    "g1",
                    1,
                    any(),
                    any(),
                    any(),
                    any(),
                    any(),
                    any()
                )
            } returns 1 andThen 0

            assertTrue(
                repo.applyKeyRotation(
                    "g1",
                    1,
                    listOf("pub1"),
                    mapOf(
                        "pub1" to "  Alice ",
                        "pub2" to "Gone"
                    )
                )
            )
            coVerify(exactly = 1) {
                groupDao.applyKeyRotation(
                    "g1",
                    1,
                    """["pub1"]""",
                    """{"pub1":"Alice"}""",
                    groupEntity.members,
                    0,
                    "{}",
                    "pub1"
                )
            }

            // Replay: the dao's epoch guard reports no row.
            assertFalse(repo.applyKeyRotation("g1", 1, listOf("pub1"), emptyMap()))

            assertNoMetaWrite()
            coVerify(exactly = 0) { groupDao.updateKeyEpoch(any(), any()) }
        }

    @Test
    fun `applyKeyRotation rejects too many members without touching the dao`() = runBlocking {
        val bigList = (1..51).map { "pub$it" }
        assertFalse(repo.applyKeyRotation("g1", 1, bigList, emptyMap()))
        coVerify(exactly = 0) {
            groupDao.applyKeyRotation(
                any(),
                any(),
                any(),
                any(),
                any(),
                any(),
                any(),
                any()
            )
        }
    }

    @Test
    fun `overrideMembership delegates to the dao with the sanitized roster and the event clock`() = runBlocking {
        repo.overrideMembership(
            "g1",
            listOf("pub1", "pub3"),
            mapOf("pub1" to " Alice ", "pub2" to "Gone"),
            "pub3",
            900,
            "e-900"
        )
        coVerify {
            groupDao.overrideMembership(
                "g1",
                """["pub1","pub3"]""",
                """{"pub1":"Alice"}""",
                "pub3",
                900,
                "e-900"
            )
        }
        assertNoMetaWrite()
    }

    @Test
    fun `overrideMembership rejects too many members without touching the dao`() = runBlocking {
        val bigList = (1..51).map { "pub$it" }
        repo.overrideMembership("g1", bigList, emptyMap(), "", 900, "e-900")
        coVerify(exactly = 0) { groupDao.overrideMembership(any(), any(), any(), any(), any(), any()) }
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
        coVerify(exactly = 1) {
            groupDao.updateMemberSelf(
                "g1",
                capture(members),
                capture(names),
                capture(clocks),
                any(),
                any(),
                any(),
                any(),
                any(),
                any(),
                any()
            )
        }
        return SelfWrite(members.captured, names.captured, clocks.captured)
    }

    private fun assertNoSelfWrite() {
        coVerify(exactly = 0) {
            groupDao.updateMemberSelf(
                any(),
                any(),
                any(),
                any(),
                any(),
                any(),
                any(),
                any(),
                any(),
                any(),
                any()
            )
        }
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
    fun `applyMemberSelfUpdate keeps newer creator name while recording an older explicit self clock`() = runBlocking {
        coEvery { groupDao.getById("g1") } returns selfEntity

        assertTrue(repo.applyMemberSelfUpdate("g1", "pub1", 1, "e1", join = false, displayName = "Al"))

        val write = captureSelfWrite()
        assertEquals("""{"pub1":"Alice","pub2":"Bob"}""", write.memberNames)
        assertEquals("""{"pub2":"500:e5","pub1":"1:e1"}""", write.memberClocks)
    }

    @Test
    fun `applyMemberSelfUpdate with join adds the author to members`() = runBlocking {
        coEvery { groupDao.getById("g1") } returns selfEntity

        assertTrue(repo.applyMemberSelfUpdate("g1", "pub3", 700, "e7", join = true, displayName = "Carol"))

        val write = captureSelfWrite()
        assertEquals("""["pub1","pub2","pub3"]""", write.members)
        assertEquals("""{"pub1":"Alice","pub2":"Bob","pub3":"Carol"}""", write.memberNames)
        assertEquals("""{"pub2":"500:e5","pub3":"700:e7","join:pub3":"700:e7"}""", write.memberClocks)
    }

    @Test
    fun `applyMemberSelfUpdate with join for an existing member does not duplicate them`() = runBlocking {
        coEvery { groupDao.getById("g1") } returns selfEntity

        assertTrue(repo.applyMemberSelfUpdate("g1", "pub2", 600, "e6", join = true, displayName = null))

        val write = captureSelfWrite()
        assertEquals("""["pub1","pub2"]""", write.members)
        assertEquals("""{"pub1":"Alice","pub2":"Bob"}""", write.memberNames)
        assertEquals("""{"pub2":"500:e5","join:pub2":"600:e6"}""", write.memberClocks)
    }

    @Test
    fun `applyMemberSelfUpdate ignores a name change from a non-member without a join`() = runBlocking {
        coEvery { groupDao.getById("g1") } returns selfEntity

        assertFalse(
            repo.applyMemberSelfUpdate(
                "g1",
                "stranger",
                900,
                "e9",
                join = false,
                displayName = "Mallory"
            )
        )

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

        assertFalse(repo.applyMemberSelfUpdate("g1", "pub2", 600, "e6", join = false, displayName = null))

        assertNoSelfWrite()
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

        assertNoMetaWrite()
        coVerify(exactly = 0) { groupDao.overrideMembership(any(), any(), any(), any(), any(), any()) }
        coVerify(exactly = 0) {
            groupDao.applyKeyRotation(
                any(),
                any(),
                any(),
                any(),
                any(),
                any(),
                any(),
                any()
            )
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

        assertTrue(repo.applyMemberSelfUpdate("g1", "pub2", 1, "e1", join = false, displayName = "Bobby"))

        assertEquals("""{"pub2":"1:e1"}""", captureSelfWrite().memberClocks)
        verify(exactly = 1) { android.util.Log.w("GroupRepository", match<String> { "memberClocks" in it }) }
    }

    @Test
    fun `applyMemberSelfUpdate treats an unparseable clock entry as absent`() = runBlocking {
        coEvery { groupDao.getById("g1") } returns selfEntity.copy(memberClocks = """{"pub2":"garbage"}""")

        assertTrue(repo.applyMemberSelfUpdate("g1", "pub2", 1, "e1", join = false, displayName = "Bobby"))

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

    @Test
    fun `competing epoch key writers cannot both pass the immutability check`() = runBlocking {
        lateinit var concurrentRepo: GroupRepository
        lateinit var competing: Deferred<Result<Unit>>
        var startedCompetitor = false
        val interleavedStorage = object : SecureStorage by keyStore {
            override fun getString(key: String, default: String?): String? {
                val snapshot = keyStore.getString(key, default)
                if (key == "g1:1" && !startedCompetitor) {
                    startedCompetitor = true
                    competing = async(start = CoroutineStart.UNDISPATCHED) {
                        runCatching { concurrentRepo.saveGroupKeyForEpoch("g1", 1, "competitor") }
                    }
                }
                return snapshot
            }
        }
        concurrentRepo = GroupRepository(groupDao, interleavedStorage)
        concurrentRepo.saveGroupKeyForEpoch("g1", 1, "first")
        assertTrue(competing.await().exceptionOrNull() is IllegalStateException)
        assertEquals("first", keyStore.getString("g1:1", null))
    }

    @Test
    fun `epoch zero key cannot replace different un-epoched key material`() = runBlocking {
        keyStore.putString("g1", "legacy")
        val failure = runCatching { repo.saveGroupKeyForEpoch("g1", 0, "replacement") }.exceptionOrNull()
        assertTrue(failure is IllegalStateException)
        assertEquals("legacy", repo.getGroupKeyForEpoch("g1", 0))
        assertNull(keyStore.getString("g1:0", null))
    }

    @Test
    fun `save cannot replace immutable epoch material or insert a conflicting row`() = runBlocking {
        repo.saveGroupKeyForEpoch("g1", 0, "first")
        val group = Group("g1", "Test", "", "pub", 1000, listOf("pub"), listOf("wss://r"))
        val failure = runCatching { repo.save(group, "replacement") }.exceptionOrNull()
        assertTrue(failure is IllegalStateException)
        assertEquals("first", repo.getGroupKeyForEpoch("g1", 0))
        coVerify(exactly = 0) { groupDao.insert(any()) }
    }
}
