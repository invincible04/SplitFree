package com.splitfree.domain.usecase

import com.splitfree.data.local.EventDao
import com.splitfree.data.local.entities.EventEntity
import com.splitfree.data.repository.GroupRepository
import com.splitfree.domain.model.Group
import io.mockk.*
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class ComputeBalancesUseCaseTest {
    private fun eventDao() = mockk<EventDao>(relaxed = true)

    private fun groupRepo() = mockk<GroupRepository>(relaxed = true)

    private fun group(creator: String = "alice") =
        Group(
            id = "g1",
            name = "Test",
            createdBy = creator,
            createdAt = 0,
            relays = emptyList(),
            members = emptyList(),
        )

    private fun makeEvent(
        id: String,
        groupId: String = "g1",
        pubkey: String = "alice",
        type: String,
        content: String,
        uuid: String? = null,
        createdAt: Long = 1,
    ) = EventEntity(
        eventId = id,
        groupId = groupId,
        pubkey = pubkey,
        createdAt = createdAt,
        kind = 30078,
        contentEncrypted = "",
        contentDecrypted = content,
        eventType = type,
        expenseUuid = uuid,
        sig = "s",
        receivedAt = 1,
    )

    @Test
    fun `computes simple two-person balance`() =
        runTest {
            val dao = eventDao()
            val repo = groupRepo()
            coEvery { dao.getEventsByGroup("g1") } returns
                listOf(
                    makeEvent(
                        "e1",
                        type = "expense",
                        uuid = "u1",
                        content = """{"id":"u1","amount":100,"currency":"INR","description":"test","paid_by":"alice","split_type":"equal","split_among":[{"pubkey":"alice","share":50},{"pubkey":"bob","share":50}],"timestamp":1}""",
                    ),
                )
            coEvery { dao.getLatestEventByType("g1", "snapshot") } returns null

            val useCase = ComputeBalancesUseCase(dao, repo)
            val balances = useCase("g1")

            val alice = balances.find { it.pubkey == "alice" }
            val bob = balances.find { it.pubkey == "bob" }
            assertEquals(50L, alice?.net)
            assertEquals(-50L, bob?.net)
        }

    @Test
    fun `deleted expense excluded from balance`() =
        runTest {
            val dao = eventDao()
            val repo = groupRepo()
            coEvery { dao.getEventsByGroup("g1") } returns
                listOf(
                    makeEvent(
                        "e1",
                        type = "expense",
                        uuid = "u1",
                        content = """{"id":"u1","amount":100,"currency":"INR","description":"t","paid_by":"alice","split_type":"equal","split_among":[{"pubkey":"alice","share":50},{"pubkey":"bob","share":50}],"timestamp":1}""",
                    ),
                    makeEvent("e2", type = "expense_delete", uuid = "u1", content = "{}"),
                )
            coEvery { dao.getLatestEventByType("g1", "snapshot") } returns null

            val useCase = ComputeBalancesUseCase(dao, repo)
            val result = useCase.computeWithExclusions("g1")

            assertTrue(result.balances.all { it.net == 0L } || result.balances.isEmpty())
            assertTrue("u1" in result.excludedExpenseUuids)
        }

    @Test
    fun `corrected expense uses latest correction`() =
        runTest {
            val dao = eventDao()
            val repo = groupRepo()
            coEvery { dao.getEventsByGroup("g1") } returns
                listOf(
                    makeEvent(
                        "e1",
                        type = "expense",
                        uuid = "u1",
                        content = """{"id":"u1","amount":100,"currency":"INR","description":"t","paid_by":"alice","split_type":"equal","split_among":[{"pubkey":"alice","share":50},{"pubkey":"bob","share":50}],"timestamp":1}""",
                    ),
                    makeEvent(
                        "e2",
                        type = "expense_correction",
                        uuid = "u1",
                        content = """{"id":"u1c","amount":200,"currency":"INR","description":"t","paid_by":"alice","split_type":"equal","split_among":[{"pubkey":"alice","share":100},{"pubkey":"bob","share":100}],"timestamp":2}""",
                    ),
                )
            coEvery { dao.getLatestEventByType("g1", "snapshot") } returns null

            val useCase = ComputeBalancesUseCase(dao, repo)
            val balances = useCase("g1")

            val alice = balances.find { it.pubkey == "alice" }
            assertEquals(100L, alice?.net) // corrected amount, not original
        }

    @Test
    fun `settlement reduces debt`() =
        runTest {
            val dao = eventDao()
            val repo = groupRepo()
            coEvery { dao.getEventsByGroup("g1") } returns
                listOf(
                    makeEvent(
                        "e1",
                        type = "expense",
                        uuid = "u1",
                        pubkey = "alice",
                        content = """{"id":"u1","amount":100,"currency":"INR","description":"t","paid_by":"alice","split_type":"equal","split_among":[{"pubkey":"alice","share":50},{"pubkey":"bob","share":50}],"timestamp":1}""",
                    ),
                    makeEvent(
                        "e2",
                        type = "settlement",
                        pubkey = "bob",
                        content = """{"id":"s1","from":"bob","to":"alice","amount":50,"currency":"INR","timestamp":2}""",
                    ),
                )
            coEvery { dao.getLatestEventByType("g1", "snapshot") } returns null

            val useCase = ComputeBalancesUseCase(dao, repo)
            val balances = useCase("g1")

            assertTrue("All balances should be zero", balances.all { it.net == 0L })
        }

    @Test
    fun `settlement from non-party is ignored`() =
        runTest {
            val dao = eventDao()
            val repo = groupRepo()
            coEvery { dao.getEventsByGroup("g1") } returns
                listOf(
                    makeEvent(
                        "e1",
                        type = "expense",
                        uuid = "u1",
                        pubkey = "alice",
                        content = """{"id":"u1","amount":100,"currency":"INR","description":"t","paid_by":"alice","split_type":"equal","split_among":[{"pubkey":"alice","share":50},{"pubkey":"bob","share":50}],"timestamp":1}""",
                    ),
                    makeEvent(
                        "e2",
                        type = "settlement",
                        pubkey = "charlie",
                        content = """{"id":"s1","from":"bob","to":"alice","amount":50,"currency":"INR","timestamp":2}""",
                    ),
                )
            coEvery { dao.getLatestEventByType("g1", "snapshot") } returns null

            val useCase = ComputeBalancesUseCase(dao, repo)
            val balances = useCase("g1")

            val alice = balances.find { it.pubkey == "alice" }
            assertEquals(50L, alice?.net) // settlement ignored, debt remains
        }

    @Test
    fun `duplicate settlement IDs are deduplicated`() =
        runTest {
            val dao = eventDao()
            val repo = groupRepo()
            coEvery { dao.getEventsByGroup("g1") } returns
                listOf(
                    makeEvent(
                        "e1",
                        type = "expense",
                        uuid = "u1",
                        pubkey = "alice",
                        content = """{"id":"u1","amount":100,"currency":"INR","description":"t","paid_by":"alice","split_type":"equal","split_among":[{"pubkey":"alice","share":50},{"pubkey":"bob","share":50}],"timestamp":1}""",
                    ),
                    makeEvent(
                        "e2",
                        type = "settlement",
                        pubkey = "bob",
                        content = """{"id":"s1","from":"bob","to":"alice","amount":50,"currency":"INR","timestamp":2}""",
                    ),
                    makeEvent(
                        "e3",
                        type = "settlement",
                        pubkey = "bob",
                        content = """{"id":"s1","from":"bob","to":"alice","amount":50,"currency":"INR","timestamp":3}""",
                    ),
                )
            coEvery { dao.getLatestEventByType("g1", "snapshot") } returns null

            val useCase = ComputeBalancesUseCase(dao, repo)
            val balances = useCase("g1")

            // Only one settlement should be applied
            assertTrue("All balances should be zero", balances.all { it.net == 0L })
        }

    @Test
    fun `negative split shares are ignored`() =
        runTest {
            val dao = eventDao()
            val repo = groupRepo()
            coEvery { dao.getEventsByGroup("g1") } returns
                listOf(
                    makeEvent(
                        "e1",
                        type = "expense",
                        uuid = "u1",
                        content = """{"id":"u1","amount":100,"currency":"INR","description":"t","paid_by":"alice","split_type":"exact","split_among":[{"pubkey":"alice","share":150},{"pubkey":"bob","share":-50}],"timestamp":1}""",
                    ),
                )
            coEvery { dao.getLatestEventByType("g1", "snapshot") } returns null

            val useCase = ComputeBalancesUseCase(dao, repo)
            val balances = useCase("g1")

            assertTrue("Negative shares should cause expense to be skipped", balances.isEmpty() || balances.all { it.net == 0L })
        }

    @Test
    fun `events with null decrypted content are skipped`() =
        runTest {
            val dao = eventDao()
            val repo = groupRepo()
            coEvery { dao.getEventsByGroup("g1") } returns
                listOf(
                    EventEntity("e1", "g1", "alice", 1, 30078, "enc", null, "expense", "u1", "s", receivedAt = 1),
                )
            coEvery { dao.getLatestEventByType("g1", "snapshot") } returns null

            val useCase = ComputeBalancesUseCase(dao, repo)
            val balances = useCase("g1")

            assertTrue(balances.isEmpty())
        }

    @Test
    fun `empty group returns empty balances`() =
        runTest {
            val dao = eventDao()
            val repo = groupRepo()
            coEvery { dao.getEventsByGroup("g1") } returns emptyList()
            coEvery { dao.getLatestEventByType("g1", "snapshot") } returns null

            val useCase = ComputeBalancesUseCase(dao, repo)
            val balances = useCase("g1")

            assertTrue(balances.isEmpty())
        }

    @Test
    fun `multi-currency balances tracked separately`() =
        runTest {
            val dao = eventDao()
            val repo = groupRepo()
            coEvery { dao.getEventsByGroup("g1") } returns
                listOf(
                    makeEvent(
                        "e1",
                        type = "expense",
                        uuid = "u1",
                        content = """{"id":"u1","amount":100,"currency":"INR","description":"t","paid_by":"alice","split_type":"equal","split_among":[{"pubkey":"alice","share":50},{"pubkey":"bob","share":50}],"timestamp":1}""",
                    ),
                    makeEvent(
                        "e2",
                        type = "expense",
                        uuid = "u2",
                        content = """{"id":"u2","amount":200,"currency":"USD","description":"t","paid_by":"alice","split_type":"equal","split_among":[{"pubkey":"alice","share":100},{"pubkey":"bob","share":100}],"timestamp":2}""",
                    ),
                )
            coEvery { dao.getLatestEventByType("g1", "snapshot") } returns null

            val useCase = ComputeBalancesUseCase(dao, repo)
            val balances = useCase("g1")

            val aliceInr = balances.find { it.pubkey == "alice" && it.currency == "INR" }
            val aliceUsd = balances.find { it.pubkey == "alice" && it.currency == "USD" }
            assertEquals(50L, aliceInr?.net)
            assertEquals(100L, aliceUsd?.net)
        }

    // --- Snapshot tests ---

    @Test
    fun `snapshot from creator with empty hashes applies balances`() =
        runTest {
            val dao = eventDao()
            val repo = groupRepo()
            coEvery { repo.getById("g1") } returns group("alice")
            val snapContent = """{"id":"snap1","as_of_event_count":1,"as_of_timestamp":100,"balances":[{"pubkey":"alice","net":50,"currency":"INR"},{"pubkey":"bob","net":-50,"currency":"INR"}],"event_hashes":[]}"""
            coEvery { dao.getLatestEventByType("g1", "snapshot") } returns
                makeEvent("s1", type = "snapshot", pubkey = "alice", content = snapContent)
            // Post-snapshot expense
            coEvery { dao.getEventsByGroup("g1") } returns
                listOf(
                    makeEvent(
                        "e1",
                        type = "expense",
                        uuid = "u2",
                        createdAt = 200,
                        content = """{"id":"u2","amount":200,"currency":"INR","description":"t","paid_by":"bob","split_type":"equal","split_among":[{"pubkey":"alice","share":100},{"pubkey":"bob","share":100}],"timestamp":200}""",
                    ),
                )
            val useCase = ComputeBalancesUseCase(dao, repo)
            val balances = useCase("g1")
            // Snapshot: alice=50, bob=-50. Post-snapshot: alice owes bob 100 → alice net = 50-100=-50, bob net = -50+100=50
            val alice = balances.find { it.pubkey == "alice" }
            val bob = balances.find { it.pubkey == "bob" }
            assertEquals(-50L, alice?.net)
            assertEquals(50L, bob?.net)
        }

    @Test
    fun `snapshot from non-creator is ignored`() =
        runTest {
            val dao = eventDao()
            val repo = groupRepo()
            coEvery { repo.getById("g1") } returns group("alice")
            val snapContent = """{"id":"snap1","as_of_event_count":1,"as_of_timestamp":100,"balances":[{"pubkey":"alice","net":9999}],"event_hashes":[]}"""
            coEvery { dao.getLatestEventByType("g1", "snapshot") } returns
                makeEvent("s1", type = "snapshot", pubkey = "mallory", content = snapContent)
            coEvery { dao.getEventsByGroup("g1") } returns emptyList()
            val useCase = ComputeBalancesUseCase(dao, repo)
            val balances = useCase("g1")
            assertTrue(balances.isEmpty())
        }

    @Test
    fun `snapshot with valid hash match applies balances`() =
        runTest {
            val dao = eventDao()
            val repo = groupRepo()
            mockkStatic(android.util.Log::class)
            every { android.util.Log.w(any<String>(), any<String>()) } returns 0
            coEvery { repo.getById("g1") } returns group("alice")
            // Generate 10 event IDs and their hashes
            val eventIds = (1..10).map { "event_$it" }
            val hashes =
                eventIds.map {
                    com.splitfree.data.util.HashUtil
                        .sha256Hex(it)
                }
            val snapContent = """{"id":"snap1","as_of_event_count":10,"as_of_timestamp":100,"balances":[{"pubkey":"alice","net":500,"currency":"INR"}],"event_hashes":${hashes.joinToString(
                ",",
                "[",
                "]",
            ) { "\"$it\"" }}}"""
            coEvery { dao.getLatestEventByType("g1", "snapshot") } returns
                makeEvent("s1", type = "snapshot", pubkey = "alice", content = snapContent)
            coEvery { dao.getEventIds("g1") } returns eventIds
            coEvery { dao.getEventsByGroup("g1") } returns emptyList()
            val useCase = ComputeBalancesUseCase(dao, repo)
            val balances = useCase("g1")
            val alice = balances.find { it.pubkey == "alice" }
            assertEquals(500L, alice?.net)
            unmockkStatic(android.util.Log::class)
        }

    @Test
    fun `snapshot with hash mismatch is ignored`() =
        runTest {
            val dao = eventDao()
            val repo = groupRepo()
            mockkStatic(android.util.Log::class)
            every { android.util.Log.w(any<String>(), any<String>()) } returns 0
            coEvery { repo.getById("g1") } returns group("alice")
            val fakeHashes = (1..10).map { "fakehash_$it" }
            val snapContent = """{"id":"snap1","as_of_event_count":10,"as_of_timestamp":100,"balances":[{"pubkey":"alice","net":9999}],"event_hashes":${fakeHashes.joinToString(
                ",",
                "[",
                "]",
            ) { "\"$it\"" }}}"""
            coEvery { dao.getLatestEventByType("g1", "snapshot") } returns
                makeEvent("s1", type = "snapshot", pubkey = "alice", content = snapContent)
            coEvery { dao.getEventIds("g1") } returns listOf("unrelated_1")
            coEvery { dao.getEventsByGroup("g1") } returns emptyList()
            val useCase = ComputeBalancesUseCase(dao, repo)
            val balances = useCase("g1")
            assertTrue("Hash mismatch should ignore snapshot", balances.isEmpty() || balances.all { it.net == 0L })
            unmockkStatic(android.util.Log::class)
        }

    @Test
    fun `snapshot with fewer than 10 hashes and low match is ignored`() =
        runTest {
            val dao = eventDao()
            val repo = groupRepo()
            mockkStatic(android.util.Log::class)
            every { android.util.Log.w(any<String>(), any<String>()) } returns 0
            coEvery { repo.getById("g1") } returns group("alice")
            val hashes = (1..5).map { "hash_$it" }
            val snapContent = """{"id":"snap1","as_of_event_count":5,"as_of_timestamp":100,"balances":[{"pubkey":"alice","net":9999}],"event_hashes":${hashes.joinToString(
                ",",
                "[",
                "]",
            ) { "\"$it\"" }}}"""
            coEvery { dao.getLatestEventByType("g1", "snapshot") } returns
                makeEvent("s1", type = "snapshot", pubkey = "alice", content = snapContent)
            coEvery { dao.getEventIds("g1") } returns listOf("other")
            coEvery { dao.getEventsByGroup("g1") } returns emptyList()
            val useCase = ComputeBalancesUseCase(dao, repo)
            val balances = useCase("g1")
            assertTrue("Few hashes with no match should ignore snapshot", balances.isEmpty() || balances.all { it.net == 0L })
            unmockkStatic(android.util.Log::class)
        }

    @Test
    fun `snapshot with null group is ignored`() =
        runTest {
            val dao = eventDao()
            val repo = groupRepo()
            coEvery { repo.getById("g1") } returns null
            val snapContent = """{"id":"snap1","as_of_event_count":1,"as_of_timestamp":100,"balances":[{"pubkey":"alice","net":9999}],"event_hashes":[]}"""
            coEvery { dao.getLatestEventByType("g1", "snapshot") } returns
                makeEvent("s1", type = "snapshot", pubkey = "alice", content = snapContent)
            coEvery { dao.getEventsByGroup("g1") } returns emptyList()
            val useCase = ComputeBalancesUseCase(dao, repo)
            val balances = useCase("g1")
            assertTrue(balances.isEmpty())
        }

    @Test
    fun `snapshot with malformed JSON is ignored`() =
        runTest {
            val dao = eventDao()
            val repo = groupRepo()
            coEvery { repo.getById("g1") } returns group("alice")
            coEvery { dao.getLatestEventByType("g1", "snapshot") } returns
                makeEvent("s1", type = "snapshot", pubkey = "alice", content = "not json")
            coEvery { dao.getEventsByGroup("g1") } returns emptyList()
            val useCase = ComputeBalancesUseCase(dao, repo)
            val balances = useCase("g1")
            assertTrue(balances.isEmpty())
        }

    @Test
    fun `snapshot with null decryptedContent is ignored`() =
        runTest {
            val dao = eventDao()
            val repo = groupRepo()
            coEvery { dao.getLatestEventByType("g1", "snapshot") } returns
                EventEntity("s1", "g1", "alice", 1, 30078, "enc", null, "snapshot", null, "s", receivedAt = 1)
            coEvery { dao.getEventsByGroup("g1") } returns emptyList()
            val useCase = ComputeBalancesUseCase(dao, repo)
            val balances = useCase("g1")
            assertTrue(balances.isEmpty())
        }

    @Test
    fun `deleted correction is excluded`() =
        runTest {
            val dao = eventDao()
            val repo = groupRepo()
            coEvery { dao.getEventsByGroup("g1") } returns
                listOf(
                    makeEvent(
                        "e1",
                        type = "expense",
                        uuid = "u1",
                        content = """{"id":"u1","amount":100,"currency":"INR","description":"t","paid_by":"alice","split_type":"equal","split_among":[{"pubkey":"alice","share":50},{"pubkey":"bob","share":50}],"timestamp":1}""",
                    ),
                    makeEvent(
                        "e2",
                        type = "expense_correction",
                        uuid = "u1",
                        content = """{"id":"u1c","amount":200,"currency":"INR","description":"t","paid_by":"alice","split_type":"equal","split_among":[{"pubkey":"alice","share":100},{"pubkey":"bob","share":100}],"timestamp":2}""",
                    ),
                    makeEvent("e3", type = "expense_delete", uuid = "u1", content = "{}"),
                )
            coEvery { dao.getLatestEventByType("g1", "snapshot") } returns null
            val useCase = ComputeBalancesUseCase(dao, repo)
            val balances = useCase("g1")
            assertTrue("Deleted correction should zero out", balances.isEmpty() || balances.all { it.net == 0L })
        }

    @Test
    fun `expense_delete with null uuid is ignored`() =
        runTest {
            val dao = eventDao()
            val repo = groupRepo()
            coEvery { dao.getEventsByGroup("g1") } returns
                listOf(
                    makeEvent(
                        "e1",
                        type = "expense",
                        uuid = "u1",
                        content = """{"id":"u1","amount":100,"currency":"INR","description":"t","paid_by":"alice","split_type":"equal","split_among":[{"pubkey":"alice","share":50},{"pubkey":"bob","share":50}],"timestamp":1}""",
                    ),
                    makeEvent("e2", type = "expense_delete", uuid = null, content = "{}"),
                )
            coEvery { dao.getLatestEventByType("g1", "snapshot") } returns null
            val balances = ComputeBalancesUseCase(dao, repo)("g1")
            assertTrue(balances.isNotEmpty()) // expense not deleted since delete had null uuid
        }

    @Test
    fun `expense with null uuid is skipped`() =
        runTest {
            val dao = eventDao()
            val repo = groupRepo()
            coEvery { dao.getEventsByGroup("g1") } returns
                listOf(
                    makeEvent(
                        "e1",
                        type = "expense",
                        uuid = null,
                        content = """{"id":"u1","amount":100,"currency":"INR","description":"t","paid_by":"alice","split_type":"equal","split_among":[{"pubkey":"alice","share":50},{"pubkey":"bob","share":50}],"timestamp":1}""",
                    ),
                )
            coEvery { dao.getLatestEventByType("g1", "snapshot") } returns null
            val balances = ComputeBalancesUseCase(dao, repo)("g1")
            assertTrue(balances.isEmpty())
        }

    @Test
    fun `expense_correction with null uuid is skipped`() =
        runTest {
            val dao = eventDao()
            val repo = groupRepo()
            coEvery { dao.getEventsByGroup("g1") } returns
                listOf(
                    makeEvent(
                        "e1",
                        type = "expense_correction",
                        uuid = null,
                        content = """{"id":"u1","amount":100,"currency":"INR","description":"t","paid_by":"alice","split_type":"equal","split_among":[{"pubkey":"alice","share":50},{"pubkey":"bob","share":50}],"timestamp":1}""",
                    ),
                )
            coEvery { dao.getLatestEventByType("g1", "snapshot") } returns null
            val balances = ComputeBalancesUseCase(dao, repo)("g1")
            assertTrue(balances.isEmpty())
        }

    @Test
    fun `expense_correction for deleted expense is skipped`() =
        runTest {
            val dao = eventDao()
            val repo = groupRepo()
            coEvery { dao.getEventsByGroup("g1") } returns
                listOf(
                    makeEvent(
                        "e1",
                        type = "expense",
                        uuid = "u1",
                        content = """{"id":"u1","amount":100,"currency":"INR","description":"t","paid_by":"alice","split_type":"equal","split_among":[{"pubkey":"alice","share":50},{"pubkey":"bob","share":50}],"timestamp":1}""",
                    ),
                    makeEvent("e2", type = "expense_delete", uuid = "u1", content = "{}"),
                    makeEvent(
                        "e3",
                        type = "expense_correction",
                        uuid = "u1",
                        content = """{"id":"u1c","amount":200,"currency":"INR","description":"t","paid_by":"alice","split_type":"equal","split_among":[{"pubkey":"alice","share":100},{"pubkey":"bob","share":100}],"timestamp":2}""",
                    ),
                )
            coEvery { dao.getLatestEventByType("g1", "snapshot") } returns null
            val balances = ComputeBalancesUseCase(dao, repo)("g1")
            assertTrue("Correction of deleted expense should be ignored", balances.isEmpty() || balances.all { it.net == 0L })
        }

    @Test
    fun `snapshot filters events after snapshot timestamp`() =
        runTest {
            val dao = eventDao()
            val repo = groupRepo()
            coEvery { repo.getById("g1") } returns group("alice")
            val snapshotContent = """{"as_of_timestamp":100,"balances":[{"pubkey":"alice","currency":"INR","net":50}],"event_hashes":[]}"""
            coEvery { dao.getLatestEventByType("g1", "snapshot") } returns
                makeEvent("snap", type = "snapshot", content = snapshotContent, pubkey = "alice", createdAt = 101)
            coEvery { dao.getEventsByGroup("g1") } returns
                listOf(
                    makeEvent(
                        "e1",
                        type = "expense",
                        uuid = "u1",
                        createdAt = 50,
                        content = """{"id":"u1","amount":100,"currency":"INR","description":"t","paid_by":"alice","split_type":"equal","split_among":[{"pubkey":"alice","share":50},{"pubkey":"bob","share":50}],"timestamp":1}""",
                    ),
                    makeEvent(
                        "e2",
                        type = "expense",
                        uuid = "u2",
                        createdAt = 200,
                        content = """{"id":"u2","amount":200,"currency":"INR","description":"t","paid_by":"bob","split_type":"equal","split_among":[{"pubkey":"alice","share":100},{"pubkey":"bob","share":100}],"timestamp":2}""",
                    ),
                )
            val balances = ComputeBalancesUseCase(dao, repo)("g1")
            // Snapshot gives alice +50, then e2 (after snapshot) gives bob paid 200, split 100 each
            // alice: 50 + (-100) = -50, bob: 0 + 100 = 100
            val aliceInr = balances.find { it.pubkey == "alice" && it.currency == "INR" }
            assertNotNull(aliceInr)
            assertEquals(-50L, aliceInr!!.net)
        }
}
