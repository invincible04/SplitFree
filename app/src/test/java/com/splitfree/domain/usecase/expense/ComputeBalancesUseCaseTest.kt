package com.splitfree.domain.usecase.expense

import com.splitfree.domain.crypto.GroupEncryption
import com.splitfree.domain.model.group.Group
import com.splitfree.domain.repository.EventRepositoryContract
import com.splitfree.domain.repository.EventSnapshot
import com.splitfree.domain.repository.GroupRepositoryContract
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ComputeBalancesUseCaseTest {
    private val groupKey = "testkey"

    private fun eventDao() = mockk<EventRepositoryContract>(relaxed = true)

    private fun groupRepo() = mockk<GroupRepositoryContract>(relaxed = true).also {
        coEvery { it.getGroupKey("g1") } returns groupKey
        coEvery { it.getGroupKeyForEpoch("g1", any()) } returns groupKey
    }

    /** Mock encryption returns contentEncrypted as-is. */
    private fun encryption() = mockk<GroupEncryption>().also {
        every { it.decrypt(any(), eq(groupKey)) } answers { firstArg() }
    }

    private fun group(creator: String = "alice") = Group(
        id = "g1",
        name = "Test",
        createdBy = creator,
        createdAt = 0,
        relays = emptyList(),
        members = emptyList()
    )

    private fun split(vararg entries: Pair<String, Long>): String = entries.joinToString(",", "[", "]") { (pk, sh) ->
        """{"pubkey":"$pk","share":$sh}"""
    }

    private fun expenseJson(
        id: String,
        amount: Long,
        currency: String = "INR",
        desc: String = "t",
        paidBy: String = "alice",
        splitType: String = "equal",
        splits: String,
        timestamp: Long = 1
    ): String = """{"id":"$id","amount":$amount,""" +
        """"currency":"$currency","description":"$desc",""" +
        """"paid_by":"$paidBy","split_type":"$splitType",""" +
        """"split_among":$splits,"timestamp":$timestamp}"""

    private fun settlementJson(
        id: String,
        from: String,
        to: String,
        amount: Long,
        currency: String = "INR",
        timestamp: Long
    ): String = """{"id":"$id","from":"$from","to":"$to",""" +
        """"amount":$amount,"currency":"$currency",""" +
        """"timestamp":$timestamp}"""

    private fun snapJson(
        id: String = "snap1",
        eventCount: Int = 1,
        asOfTs: Long = 100,
        balances: String,
        hashes: String = "[]"
    ): String = """{"id":"$id","as_of_event_count":$eventCount,""" +
        """"as_of_timestamp":$asOfTs,""" +
        """"balances":$balances,""" +
        """"event_hashes":$hashes}"""

    private fun balEntry(pubkey: String, net: Long, currency: String? = null): String = if (currency != null) {
        """{"pubkey":"$pubkey","net":$net,"currency":"$currency"}"""
    } else {
        """{"pubkey":"$pubkey","net":$net}"""
    }

    private fun makeEvent(
        id: String,
        groupId: String = "g1",
        pubkey: String = "alice",
        type: String,
        content: String,
        uuid: String? = null,
        createdAt: Long = 1
    ) = EventSnapshot(
        eventId = id,
        groupId = groupId,
        pubkey = pubkey,
        createdAt = createdAt,
        kind = 30078,
        contentEncrypted = content, // In tests, contentEncrypted holds plaintext; mock decrypt returns it as-is
        eventType = type,
        expenseUuid = uuid,
        sig = "s",
        receivedAt = 1
    )

    @Test
    fun `computes simple two-person balance`() = runTest {
        val dao = eventDao()
        val repo = groupRepo()
        coEvery { dao.getEventsByGroup("g1") } returns
            listOf(
                makeEvent(
                    "e1",
                    type = "expense",
                    uuid = "u1",
                    content =
                    """{"id":"u1","amount":100,"currency":"INR",""" +
                        """"description":"test","paid_by":"alice",""" +
                        """"split_type":"equal","split_among":""" +
                        """[{"pubkey":"alice","share":50},""" +
                        """{"pubkey":"bob","share":50}],"timestamp":1}"""
                )
            )
        coEvery { dao.getLatestEventByType("g1", "snapshot") } returns null

        val useCase = ComputeBalancesUseCase(dao, repo, encryption())
        val balances = useCase("g1")

        val alice = balances.find { it.pubkey == "alice" }
        val bob = balances.find { it.pubkey == "bob" }
        assertEquals(50L, alice?.net)
        assertEquals(-50L, bob?.net)
    }

    @Test
    fun `deleted expense excluded from balance`() = runTest {
        val dao = eventDao()
        val repo = groupRepo()
        val expenseJson =
            """{"id":"u1","amount":100,"currency":"INR",""" +
                """"description":"t","paid_by":"alice",""" +
                """"split_type":"equal","split_among":""" +
                """[{"pubkey":"alice","share":50},""" +
                """{"pubkey":"bob","share":50}],""" +
                """"timestamp":1}"""
        coEvery { dao.getEventsByGroup("g1") } returns
            listOf(
                makeEvent(
                    "e1",
                    type = "expense",
                    uuid = "u1",
                    content = expenseJson
                ),
                makeEvent(
                    "e2",
                    type = "expense_delete",
                    uuid = "u1",
                    content = "{}"
                )
            )
        coEvery { dao.getLatestEventByType("g1", "snapshot") } returns null

        val useCase = ComputeBalancesUseCase(dao, repo, encryption())
        val result = useCase.computeWithExclusions("g1")

        assertTrue(result.balances.all { it.net == 0L } || result.balances.isEmpty())
        assertTrue("u1" in result.excludedExpenseUuids)
    }

    @Test
    fun `corrected expense uses latest correction`() = runTest {
        val dao = eventDao()
        val repo = groupRepo()
        val ab50 = split("alice" to 50, "bob" to 50)
        val ab100 = split("alice" to 100, "bob" to 100)
        coEvery { dao.getEventsByGroup("g1") } returns
            listOf(
                makeEvent(
                    "e1",
                    type = "expense",
                    uuid = "u1",
                    content = expenseJson(
                        "u1",
                        100,
                        splits = ab50
                    )
                ),
                makeEvent(
                    "e2",
                    type = "expense_correction",
                    uuid = "u1",
                    content = expenseJson(
                        "u1c",
                        200,
                        splits = ab100,
                        timestamp = 2
                    )
                )
            )
        coEvery { dao.getLatestEventByType("g1", "snapshot") } returns null

        val useCase = ComputeBalancesUseCase(dao, repo, encryption())
        val balances = useCase("g1")

        val alice = balances.find { it.pubkey == "alice" }
        assertEquals(100L, alice?.net) // corrected amount, not original
    }

    @Test
    fun `settlement reduces debt`() = runTest {
        val dao = eventDao()
        val repo = groupRepo()
        val ab50 = split("alice" to 50, "bob" to 50)
        coEvery { dao.getEventsByGroup("g1") } returns
            listOf(
                makeEvent(
                    "e1",
                    type = "expense",
                    uuid = "u1",
                    pubkey = "alice",
                    content = expenseJson(
                        "u1",
                        100,
                        splits = ab50
                    )
                ),
                makeEvent(
                    "e2",
                    type = "settlement",
                    pubkey = "bob",
                    content = settlementJson(
                        "s1",
                        "bob",
                        "alice",
                        50,
                        timestamp = 2
                    )
                )
            )
        coEvery { dao.getLatestEventByType("g1", "snapshot") } returns null

        val useCase = ComputeBalancesUseCase(dao, repo, encryption())
        val balances = useCase("g1")

        assertTrue("All balances should be zero", balances.all { it.net == 0L })
    }

    @Test
    fun `settlement from non-party is ignored`() = runTest {
        val dao = eventDao()
        val repo = groupRepo()
        val ab50 = split("alice" to 50, "bob" to 50)
        coEvery { dao.getEventsByGroup("g1") } returns
            listOf(
                makeEvent(
                    "e1",
                    type = "expense",
                    uuid = "u1",
                    pubkey = "alice",
                    content = expenseJson(
                        "u1",
                        100,
                        splits = ab50
                    )
                ),
                makeEvent(
                    "e2",
                    type = "settlement",
                    pubkey = "charlie",
                    content = settlementJson(
                        "s1",
                        "bob",
                        "alice",
                        50,
                        timestamp = 2
                    )
                )
            )
        coEvery { dao.getLatestEventByType("g1", "snapshot") } returns null

        val useCase = ComputeBalancesUseCase(dao, repo, encryption())
        val balances = useCase("g1")

        val alice = balances.find { it.pubkey == "alice" }
        assertEquals(50L, alice?.net) // settlement ignored, debt remains
    }

    @Test
    fun `malformed settlement payload is skipped`() = runTest {
        mockkStatic(android.util.Log::class)
        every { android.util.Log.w(any<String>(), any<String>()) } returns 0
        val dao = eventDao()
        val repo = groupRepo()
        val ab50 = split("alice" to 50, "bob" to 50)
        coEvery { dao.getEventsByGroup("g1") } returns
            listOf(
                makeEvent(
                    "e1",
                    type = "expense",
                    uuid = "u1",
                    pubkey = "alice",
                    content = expenseJson(
                        "u1",
                        100,
                        splits = ab50
                    )
                ),
                makeEvent(
                    "e2",
                    type = "settlement",
                    pubkey = "bob",
                    content = "not-json"
                )
            )
        coEvery { dao.getLatestEventByType("g1", "snapshot") } returns null

        val useCase = ComputeBalancesUseCase(dao, repo, encryption())
        val balances = useCase("g1")

        val alice = balances.find { it.pubkey == "alice" }
        val bob = balances.find { it.pubkey == "bob" }
        assertEquals(50L, alice?.net)
        assertEquals(-50L, bob?.net)
        unmockkStatic(android.util.Log::class)
    }

    @Test
    fun `overflowing settlement is skipped without crashing`() = runTest {
        mockkStatic(android.util.Log::class)
        every { android.util.Log.w(any<String>(), any<String>()) } returns 0
        val dao = eventDao()
        val repo = groupRepo()
        coEvery { dao.getEventsByGroup("g1") } returns
            listOf(
                makeEvent(
                    "e1",
                    type = "settlement",
                    pubkey = "bob",
                    content = settlementJson(
                        "s1",
                        "bob",
                        "alice",
                        Long.MAX_VALUE,
                        timestamp = 1
                    )
                ),
                makeEvent(
                    "e2",
                    type = "settlement",
                    pubkey = "bob",
                    content = settlementJson(
                        "s2",
                        "bob",
                        "alice",
                        Long.MAX_VALUE,
                        timestamp = 2
                    )
                )
            )
        coEvery { dao.getLatestEventByType("g1", "snapshot") } returns null

        val useCase = ComputeBalancesUseCase(dao, repo, encryption())
        val balances = useCase("g1")

        val bob = balances.find { it.pubkey == "bob" }
        val alice = balances.find { it.pubkey == "alice" }
        assertEquals(Long.MAX_VALUE, bob?.net)
        assertEquals(-Long.MAX_VALUE, alice?.net)
        unmockkStatic(android.util.Log::class)
    }

    @Test
    fun `duplicate settlement IDs are deduplicated`() = runTest {
        val dao = eventDao()
        val repo = groupRepo()
        val ab50 = split("alice" to 50, "bob" to 50)
        coEvery { dao.getEventsByGroup("g1") } returns
            listOf(
                makeEvent(
                    "e1",
                    type = "expense",
                    uuid = "u1",
                    pubkey = "alice",
                    content = expenseJson(
                        "u1",
                        100,
                        splits = ab50
                    )
                ),
                makeEvent(
                    "e2",
                    type = "settlement",
                    pubkey = "bob",
                    content = settlementJson(
                        "s1",
                        "bob",
                        "alice",
                        50,
                        timestamp = 2
                    )
                ),
                makeEvent(
                    "e3",
                    type = "settlement",
                    pubkey = "bob",
                    content = settlementJson(
                        "s1",
                        "bob",
                        "alice",
                        50,
                        timestamp = 3
                    )
                )
            )
        coEvery { dao.getLatestEventByType("g1", "snapshot") } returns null

        val useCase = ComputeBalancesUseCase(dao, repo, encryption())
        val balances = useCase("g1")

        // Only one settlement should be applied
        assertTrue("All balances should be zero", balances.all { it.net == 0L })
    }

    @Test
    fun `negative split shares are ignored`() = runTest {
        val dao = eventDao()
        val repo = groupRepo()
        val negSplit = split("alice" to 150, "bob" to -50)
        coEvery { dao.getEventsByGroup("g1") } returns
            listOf(
                makeEvent(
                    "e1",
                    type = "expense",
                    uuid = "u1",
                    content = expenseJson(
                        "u1",
                        100,
                        splitType = "exact",
                        splits = negSplit
                    )
                )
            )
        coEvery { dao.getLatestEventByType("g1", "snapshot") } returns null

        val useCase = ComputeBalancesUseCase(dao, repo, encryption())
        val balances = useCase("g1")

        assertTrue(
            "Negative shares should skip expense",
            balances.isEmpty() || balances.all { it.net == 0L }
        )
    }

    @Test
    fun `events with undecryptable content are skipped`() = runTest {
        val dao = eventDao()
        val repo = groupRepo()
        val enc = mockk<GroupEncryption>()
        every { enc.decrypt(any(), any()) } throws IllegalArgumentException("bad key")
        coEvery { dao.getEventsByGroup("g1") } returns
            listOf(
                EventSnapshot("e1", "g1", "alice", 1, 30078, "enc", "expense", "u1", "s", receivedAt = 1)
            )
        coEvery { dao.getLatestEventByType("g1", "snapshot") } returns null

        val useCase = ComputeBalancesUseCase(dao, repo, enc)
        val balances = useCase("g1")

        assertTrue(balances.isEmpty())
    }

    @Test
    fun `empty group returns empty balances`() = runTest {
        val dao = eventDao()
        val repo = groupRepo()
        coEvery { dao.getEventsByGroup("g1") } returns emptyList()
        coEvery { dao.getLatestEventByType("g1", "snapshot") } returns null

        val useCase = ComputeBalancesUseCase(dao, repo, encryption())
        val balances = useCase("g1")

        assertTrue(balances.isEmpty())
    }

    @Test
    fun `multi-currency balances tracked separately`() = runTest {
        val dao = eventDao()
        val repo = groupRepo()
        val ab50 = split("alice" to 50, "bob" to 50)
        val ab100 = split("alice" to 100, "bob" to 100)
        coEvery { dao.getEventsByGroup("g1") } returns
            listOf(
                makeEvent(
                    "e1",
                    type = "expense",
                    uuid = "u1",
                    content = expenseJson(
                        "u1",
                        100,
                        splits = ab50
                    )
                ),
                makeEvent(
                    "e2",
                    type = "expense",
                    uuid = "u2",
                    content = expenseJson(
                        "u2",
                        200,
                        currency = "USD",
                        splits = ab100,
                        timestamp = 2
                    )
                )
            )
        coEvery { dao.getLatestEventByType("g1", "snapshot") } returns null

        val useCase = ComputeBalancesUseCase(dao, repo, encryption())
        val balances = useCase("g1")

        val aliceInr = balances.find { it.pubkey == "alice" && it.currency == "INR" }
        val aliceUsd = balances.find { it.pubkey == "alice" && it.currency == "USD" }
        assertEquals(50L, aliceInr?.net)
        assertEquals(100L, aliceUsd?.net)
    }

    // --- Snapshot tests ---

    @Test
    fun `snapshot from creator with empty hashes applies balances`() = runTest {
        val dao = eventDao()
        val repo = groupRepo()
        coEvery { repo.getById("g1") } returns group("alice")
        val bals = listOf(
            balEntry("alice", 50, "INR"),
            balEntry("bob", -50, "INR")
        ).joinToString(",", "[", "]")
        val snapContent = snapJson(balances = bals)
        coEvery {
            dao.getLatestEventByType("g1", "snapshot")
        } returns makeEvent(
            "s1",
            type = "snapshot",
            pubkey = "alice",
            content = snapContent
        )
        val ab100 = split("alice" to 100, "bob" to 100)
        coEvery { dao.getEventsByGroup("g1") } returns
            listOf(
                makeEvent(
                    "e1",
                    type = "expense",
                    uuid = "u2",
                    createdAt = 200,
                    content = expenseJson(
                        "u2",
                        200,
                        paidBy = "bob",
                        splits = ab100,
                        timestamp = 200
                    )
                )
            )
        val useCase = ComputeBalancesUseCase(dao, repo, encryption())
        val balances = useCase("g1")
        // Snapshot: alice=50, bob=-50
        // Post-snapshot: alice owes bob 100
        // alice net=50-100=-50, bob net=-50+100=50
        val alice = balances.find { it.pubkey == "alice" }
        val bob = balances.find { it.pubkey == "bob" }
        assertEquals(-50L, alice?.net)
        assertEquals(50L, bob?.net)
    }

    @Test
    fun `snapshot from non-creator is ignored`() = runTest {
        val dao = eventDao()
        val repo = groupRepo()
        coEvery { repo.getById("g1") } returns group("alice")
        val bals = """[${balEntry("alice", 9999)}]"""
        val snapContent = snapJson(balances = bals)
        coEvery {
            dao.getLatestEventByType("g1", "snapshot")
        } returns makeEvent(
            "s1",
            type = "snapshot",
            pubkey = "mallory",
            content = snapContent
        )
        coEvery { dao.getEventsByGroup("g1") } returns emptyList()
        val useCase = ComputeBalancesUseCase(dao, repo, encryption())
        val balances = useCase("g1")
        assertTrue(balances.isEmpty())
    }

    @Test
    fun `snapshot with valid hash match applies balances`() = runTest {
        val dao = eventDao()
        val repo = groupRepo()
        mockkStatic(android.util.Log::class)
        every {
            android.util.Log.w(any<String>(), any<String>())
        } returns 0
        coEvery { repo.getById("g1") } returns group("alice")
        val eventIds = (1..10).map { "event_$it" }
        val hashes = eventIds.map {
            com.splitfree.domain.util.HashUtil.sha256Hex(it)
        }
        val hashArr = hashes.joinToString(",", "[", "]") {
            "\"$it\""
        }
        val bals =
            """[${balEntry("alice", 500, "INR")}]"""
        val snapContent = snapJson(
            eventCount = 10,
            balances = bals,
            hashes = hashArr
        )
        coEvery {
            dao.getLatestEventByType("g1", "snapshot")
        } returns makeEvent(
            "s1",
            type = "snapshot",
            pubkey = "alice",
            content = snapContent
        )
        coEvery { dao.getEventIds("g1") } returns eventIds
        coEvery { dao.getEventsByGroup("g1") } returns emptyList()
        val useCase = ComputeBalancesUseCase(dao, repo, encryption())
        val balances = useCase("g1")
        val alice = balances.find { it.pubkey == "alice" }
        assertEquals(500L, alice?.net)
        unmockkStatic(android.util.Log::class)
    }

    @Test
    fun `snapshot with hash mismatch is ignored`() = runTest {
        val dao = eventDao()
        val repo = groupRepo()
        mockkStatic(android.util.Log::class)
        every {
            android.util.Log.w(any<String>(), any<String>())
        } returns 0
        coEvery { repo.getById("g1") } returns group("alice")
        val fakeHashes = (1..10).map { "fakehash_$it" }
        val hashArr = fakeHashes.joinToString(
            ",",
            "[",
            "]"
        ) { "\"$it\"" }
        val bals = """[${balEntry("alice", 9999)}]"""
        val snapContent = snapJson(
            eventCount = 10,
            balances = bals,
            hashes = hashArr
        )
        coEvery {
            dao.getLatestEventByType("g1", "snapshot")
        } returns makeEvent(
            "s1",
            type = "snapshot",
            pubkey = "alice",
            content = snapContent
        )
        coEvery {
            dao.getEventIds("g1")
        } returns listOf("unrelated_1")
        coEvery { dao.getEventsByGroup("g1") } returns emptyList()
        val useCase = ComputeBalancesUseCase(dao, repo, encryption())
        val balances = useCase("g1")
        assertTrue(
            "Hash mismatch should ignore snapshot",
            balances.isEmpty() || balances.all { it.net == 0L }
        )
        unmockkStatic(android.util.Log::class)
    }

    @Test
    fun `snapshot with fewer than 10 hashes and low match is ignored`() = runTest {
        val dao = eventDao()
        val repo = groupRepo()
        mockkStatic(android.util.Log::class)
        every {
            android.util.Log.w(any<String>(), any<String>())
        } returns 0
        coEvery { repo.getById("g1") } returns group("alice")
        val hashes = (1..5).map { "hash_$it" }
        val hashArr = hashes.joinToString(
            ",",
            "[",
            "]"
        ) { "\"$it\"" }
        val bals = """[${balEntry("alice", 9999)}]"""
        val snapContent = snapJson(
            eventCount = 5,
            balances = bals,
            hashes = hashArr
        )
        coEvery {
            dao.getLatestEventByType("g1", "snapshot")
        } returns makeEvent(
            "s1",
            type = "snapshot",
            pubkey = "alice",
            content = snapContent
        )
        coEvery { dao.getEventIds("g1") } returns listOf("other")
        coEvery { dao.getEventsByGroup("g1") } returns emptyList()
        val useCase = ComputeBalancesUseCase(dao, repo, encryption())
        val balances = useCase("g1")
        assertTrue(
            "Few hashes, no match should skip snapshot",
            balances.isEmpty() || balances.all { it.net == 0L }
        )
        unmockkStatic(android.util.Log::class)
    }

    @Test
    fun `snapshot with null group is ignored`() = runTest {
        val dao = eventDao()
        val repo = groupRepo()
        coEvery { repo.getById("g1") } returns null
        val bals = """[${balEntry("alice", 9999)}]"""
        val snapContent = snapJson(balances = bals)
        coEvery {
            dao.getLatestEventByType("g1", "snapshot")
        } returns makeEvent(
            "s1",
            type = "snapshot",
            pubkey = "alice",
            content = snapContent
        )
        coEvery { dao.getEventsByGroup("g1") } returns emptyList()
        val useCase = ComputeBalancesUseCase(dao, repo, encryption())
        val balances = useCase("g1")
        assertTrue(balances.isEmpty())
    }

    @Test
    fun `snapshot with malformed JSON is ignored`() = runTest {
        val dao = eventDao()
        val repo = groupRepo()
        coEvery { repo.getById("g1") } returns group("alice")
        coEvery { dao.getLatestEventByType("g1", "snapshot") } returns
            makeEvent("s1", type = "snapshot", pubkey = "alice", content = "not json")
        coEvery { dao.getEventsByGroup("g1") } returns emptyList()
        val useCase = ComputeBalancesUseCase(dao, repo, encryption())
        val balances = useCase("g1")
        assertTrue(balances.isEmpty())
    }

    @Test
    fun `snapshot with null decryptedContent is ignored`() = runTest {
        val dao = eventDao()
        val repo = groupRepo()
        coEvery { dao.getLatestEventByType("g1", "snapshot") } returns
            EventSnapshot("s1", "g1", "alice", 1, 30078, "enc", "snapshot", null, "s", receivedAt = 1)
        coEvery { dao.getEventsByGroup("g1") } returns emptyList()
        val useCase = ComputeBalancesUseCase(dao, repo, encryption())
        val balances = useCase("g1")
        assertTrue(balances.isEmpty())
    }

    @Test
    fun `deleted correction is excluded`() = runTest {
        val dao = eventDao()
        val repo = groupRepo()
        val ab50 = split("alice" to 50, "bob" to 50)
        val ab100 = split("alice" to 100, "bob" to 100)
        coEvery { dao.getEventsByGroup("g1") } returns
            listOf(
                makeEvent(
                    "e1",
                    type = "expense",
                    uuid = "u1",
                    content = expenseJson(
                        "u1",
                        100,
                        splits = ab50
                    )
                ),
                makeEvent(
                    "e2",
                    type = "expense_correction",
                    uuid = "u1",
                    content = expenseJson(
                        "u1c",
                        200,
                        splits = ab100,
                        timestamp = 2
                    )
                ),
                makeEvent(
                    "e3",
                    type = "expense_delete",
                    uuid = "u1",
                    content = "{}"
                )
            )
        coEvery {
            dao.getLatestEventByType("g1", "snapshot")
        } returns null
        val useCase = ComputeBalancesUseCase(dao, repo, encryption())
        val balances = useCase("g1")
        assertTrue(
            "Deleted correction should zero out",
            balances.isEmpty() || balances.all { it.net == 0L }
        )
    }

    @Test
    fun `expense_delete with null uuid is ignored`() = runTest {
        val dao = eventDao()
        val repo = groupRepo()
        val ab50 = split("alice" to 50, "bob" to 50)
        coEvery { dao.getEventsByGroup("g1") } returns
            listOf(
                makeEvent(
                    "e1",
                    type = "expense",
                    uuid = "u1",
                    content = expenseJson(
                        "u1",
                        100,
                        splits = ab50
                    )
                ),
                makeEvent(
                    "e2",
                    type = "expense_delete",
                    uuid = null,
                    content = "{}"
                )
            )
        coEvery { dao.getLatestEventByType("g1", "snapshot") } returns null
        val balances = ComputeBalancesUseCase(dao, repo, encryption())("g1")
        assertTrue(balances.isNotEmpty()) // expense not deleted since delete had null uuid
    }

    @Test
    fun `expense with null uuid is skipped`() = runTest {
        val dao = eventDao()
        val repo = groupRepo()
        val ab50 = split("alice" to 50, "bob" to 50)
        coEvery { dao.getEventsByGroup("g1") } returns
            listOf(
                makeEvent(
                    "e1",
                    type = "expense",
                    uuid = null,
                    content = expenseJson(
                        "u1",
                        100,
                        splits = ab50
                    )
                )
            )
        coEvery { dao.getLatestEventByType("g1", "snapshot") } returns null
        val balances = ComputeBalancesUseCase(dao, repo, encryption())("g1")
        assertTrue(balances.isEmpty())
    }

    @Test
    fun `expense_correction with null uuid is skipped`() = runTest {
        val dao = eventDao()
        val repo = groupRepo()
        val ab50 = split("alice" to 50, "bob" to 50)
        coEvery { dao.getEventsByGroup("g1") } returns
            listOf(
                makeEvent(
                    "e1",
                    type = "expense_correction",
                    uuid = null,
                    content = expenseJson(
                        "u1",
                        100,
                        splits = ab50
                    )
                )
            )
        coEvery { dao.getLatestEventByType("g1", "snapshot") } returns null
        val balances = ComputeBalancesUseCase(dao, repo, encryption())("g1")
        assertTrue(balances.isEmpty())
    }

    @Test
    fun `expense_correction for deleted expense is skipped`() = runTest {
        val dao = eventDao()
        val repo = groupRepo()
        val ab50 = split("alice" to 50, "bob" to 50)
        val ab100 = split("alice" to 100, "bob" to 100)
        coEvery { dao.getEventsByGroup("g1") } returns
            listOf(
                makeEvent(
                    "e1",
                    type = "expense",
                    uuid = "u1",
                    content = expenseJson(
                        "u1",
                        100,
                        splits = ab50
                    )
                ),
                makeEvent(
                    "e2",
                    type = "expense_delete",
                    uuid = "u1",
                    content = "{}"
                ),
                makeEvent(
                    "e3",
                    type = "expense_correction",
                    uuid = "u1",
                    content = expenseJson(
                        "u1c",
                        200,
                        splits = ab100,
                        timestamp = 2
                    )
                )
            )
        coEvery {
            dao.getLatestEventByType("g1", "snapshot")
        } returns null
        val balances =
            ComputeBalancesUseCase(dao, repo, encryption())("g1")
        assertTrue(
            "Correction of deleted expense should be ignored",
            balances.isEmpty() || balances.all { it.net == 0L }
        )
    }

    @Test
    fun `snapshot filters events after snapshot timestamp`() = runTest {
        val dao = eventDao()
        val repo = groupRepo()
        coEvery { repo.getById("g1") } returns group("alice")
        val snapshotContent =
            """{"as_of_timestamp":100,""" +
                """"balances":""" +
                """[{"pubkey":"alice",""" +
                """"currency":"INR","net":50}],""" +
                """"event_hashes":[]}"""
        coEvery {
            dao.getLatestEventByType("g1", "snapshot")
        } returns makeEvent(
            "snap",
            type = "snapshot",
            content = snapshotContent,
            pubkey = "alice",
            createdAt = 101
        )
        val ab50 = split("alice" to 50, "bob" to 50)
        val ab100 = split("alice" to 100, "bob" to 100)
        coEvery { dao.getEventsByGroup("g1") } returns
            listOf(
                makeEvent(
                    "e1",
                    type = "expense",
                    uuid = "u1",
                    createdAt = 50,
                    content = expenseJson(
                        "u1",
                        100,
                        splits = ab50
                    )
                ),
                makeEvent(
                    "e2",
                    type = "expense",
                    uuid = "u2",
                    createdAt = 200,
                    content = expenseJson(
                        "u2",
                        200,
                        paidBy = "bob",
                        splits = ab100,
                        timestamp = 2
                    )
                )
            )
        val balances =
            ComputeBalancesUseCase(dao, repo, encryption())("g1")
        // Snapshot: alice +50, e2 (post-snapshot):
        // bob paid 200 split 100 each
        // alice: 50+(-100)=-50, bob: 0+100=100
        val aliceInr = balances.find { it.pubkey == "alice" && it.currency == "INR" }
        assertNotNull(aliceInr)
        assertEquals(-50L, aliceInr!!.net)
    }
}
