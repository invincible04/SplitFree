package com.splitfree.domain.usecase.expense

import com.splitfree.domain.crypto.GroupEncryption
import com.splitfree.domain.model.balance.BalanceResult
import com.splitfree.domain.model.expense.ExpenseIdentity
import com.splitfree.domain.model.group.Group
import com.splitfree.domain.repository.EventRepositoryContract
import com.splitfree.domain.repository.EventSnapshot
import com.splitfree.domain.repository.GroupRepositoryContract
import com.splitfree.domain.util.HashUtil
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import io.mockk.verify
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
        assertTrue(ExpenseIdentity("alice", "u1") in result.excludedExpenses)
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
    fun `expense paid and owed by the same member still reports that currency at zero`() = runTest {
        val dao = eventDao()
        val repo = groupRepo()
        coEvery { dao.getEventsByGroup("g1") } returns
            listOf(
                makeEvent(
                    "e1",
                    type = "expense",
                    uuid = "u1",
                    content = expenseJson("u1", 100, splits = split("alice" to 100L))
                )
            )
        coEvery { dao.getLatestEventByType("g1", "snapshot") } returns null

        val balances = ComputeBalancesUseCase(dao, repo, encryption())("g1")

        assertEquals(1, balances.size)
        assertEquals("alice", balances.single().pubkey)
        assertEquals("INR", balances.single().currency)
        assertEquals(0L, balances.single().net)
    }

    @Test
    fun `participants who owe nothing still appear in the expense currency`() = runTest {
        val dao = eventDao()
        val repo = groupRepo()
        coEvery { dao.getEventsByGroup("g1") } returns
            listOf(
                makeEvent(
                    "e1",
                    type = "expense",
                    uuid = "u1",
                    content = expenseJson("u1", 100, splits = split("alice" to 100L, "bob" to 0L))
                )
            )
        coEvery { dao.getLatestEventByType("g1", "snapshot") } returns null

        val balances = ComputeBalancesUseCase(dao, repo, encryption())("g1")

        assertEquals(setOf("alice" to 0L, "bob" to 0L), balances.mapTo(HashSet()) { it.pubkey to it.net })
    }

    @Test
    fun `a deleted solo expense leaves no currency behind`() = runTest {
        val dao = eventDao()
        val repo = groupRepo()
        coEvery { dao.getEventsByGroup("g1") } returns
            listOf(
                makeEvent(
                    "e1",
                    type = "expense",
                    uuid = "u1",
                    content = expenseJson("u1", 100, splits = split("alice" to 100L))
                ),
                makeEvent("e2", type = "expense_delete", uuid = "u1", content = "{}", createdAt = 2)
            )
        coEvery { dao.getLatestEventByType("g1", "snapshot") } returns null

        val balances = ComputeBalancesUseCase(dao, repo, encryption())("g1")

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
    fun `snapshot from creator with empty hashes is rejected as unverifiable`() = runTest {
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
        // Empty-hash snapshot is rejected; balances computed from events only
        // bob paid 200, split equally: alice owes bob 100
        val alice = balances.find { it.pubkey == "alice" }
        val bob = balances.find { it.pubkey == "bob" }
        assertEquals(-100L, alice?.net)
        assertEquals(100L, bob?.net)
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
    fun `snapshot from a member is ignored when the group has no known creator`() = runTest {
        val dao = eventDao()
        val repo = groupRepo()
        // createdBy empty, and the snapshot author IS a member: still not trusted.
        coEvery { repo.getById("g1") } returns group("").copy(members = listOf("alice", "bob"))
        val eventIds = (1..10).map { "event_$it" }
        val hashArr = eventIds.joinToString(",", "[", "]") {
            "\"${com.splitfree.domain.util.HashUtil.sha256Hex(it)}\""
        }
        val snapContent = snapJson(
            eventCount = 10,
            balances = """[${balEntry("alice", 9999, "INR")}]""",
            hashes = hashArr
        )
        coEvery { dao.getLatestEventByType("g1", "snapshot") } returns
            makeEvent("s1", type = "snapshot", pubkey = "alice", content = snapContent)
        coEvery { dao.getEventIds("g1") } returns eventIds
        coEvery { dao.getEventsByGroup("g1") } returns emptyList()

        val balances = ComputeBalancesUseCase(dao, repo, encryption())("g1")

        assertTrue("Snapshot must not be trusted without a known creator", balances.isEmpty())
    }

    @Test
    fun `snapshot from an empty pubkey is ignored when the group has no known creator`() = runTest {
        val dao = eventDao()
        val repo = groupRepo()
        // Guard against the degenerate equality "" == "" being read as "author is the creator".
        coEvery { repo.getById("g1") } returns group("").copy(members = listOf("alice", "bob"))
        val eventIds = (1..10).map { "event_$it" }
        val hashArr = eventIds.joinToString(",", "[", "]") {
            "\"${com.splitfree.domain.util.HashUtil.sha256Hex(it)}\""
        }
        val snapContent = snapJson(
            eventCount = 10,
            balances = """[${balEntry("alice", 9999, "INR")}]""",
            hashes = hashArr
        )
        coEvery { dao.getLatestEventByType("g1", "snapshot") } returns
            makeEvent("s1", type = "snapshot", pubkey = "", content = snapContent)
        coEvery { dao.getEventIds("g1") } returns eventIds
        coEvery { dao.getEventsByGroup("g1") } returns emptyList()

        val balances = ComputeBalancesUseCase(dao, repo, encryption())("g1")

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
    fun `unverifiable snapshot falls back to a full replay regardless of timestamps`() = runTest {
        val dao = eventDao()
        val repo = groupRepo()
        coEvery { repo.getById("g1") } returns group("alice")
        // Empty event_hashes: the snapshot cannot be verified, so its balances are ignored and every
        // event is replayed, including e1, which predates as_of_timestamp.
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
        // e1: alice +50, bob -50; e2: alice -100, bob +100 => alice -50, bob +50
        val aliceInr = balances.find { it.pubkey == "alice" && it.currency == "INR" }
        assertNotNull(aliceInr)
        assertEquals(-50L, aliceInr!!.net)
        assertEquals(50L, balances.find { it.pubkey == "bob" }?.net)
    }

    // --- Snapshot coverage (replay by event_hashes, not by timestamp) ---

    /** Ten filler ids that are known locally and covered by the snapshot, so the trust checks pass. */
    private val fillerIds = (1..10).map { "filler_$it" }

    private fun hashArr(ids: List<String>, hash: (String) -> String = HashUtil::eventHashPrefix): String =
        ids.joinToString(",", "[", "]") { "\"${hash(it)}\"" }

    private fun mockLogW() {
        mockkStatic(android.util.Log::class)
        every { android.util.Log.w(any<String>(), any<String>()) } returns 0
    }

    /**
     * Installs a trusted creator snapshot with the given balances that covers [fillerIds] plus [coveredIds].
     * `getEventIds` returns filler + every id in [events].
     */
    private fun installSnapshot(
        dao: EventRepositoryContract,
        repo: GroupRepositoryContract,
        events: List<EventSnapshot>,
        coveredIds: List<String>,
        balances: String,
        asOfTs: Long = 100,
        hash: (String) -> String = HashUtil::eventHashPrefix
    ) {
        coEvery { repo.getById("g1") } returns group("alice")
        coEvery { dao.getLatestEventByType("g1", "snapshot") } returns makeEvent(
            "snap",
            type = "snapshot",
            pubkey = "alice",
            createdAt = asOfTs + 1,
            content = snapJson(
                eventCount = fillerIds.size + coveredIds.size,
                asOfTs = asOfTs,
                balances = balances,
                hashes = hashArr(fillerIds + coveredIds, hash)
            )
        )
        coEvery { dao.getEventIds("g1") } returns fillerIds + events.map { it.eventId }
        coEvery { dao.getEventsByGroup("g1") } returns events
    }

    @Test
    fun `event older than as_of_timestamp but not covered by the snapshot is replayed`() = runTest {
        val dao = eventDao()
        val repo = groupRepo()
        val ab100 = split("alice" to 100, "bob" to 100)
        // Bob's expense was created at t=50 (< as_of_timestamp=100) but the creator never received
        // it before snapshotting: it is absent from event_hashes and must still count.
        val late = makeEvent(
            "e_late",
            type = "expense",
            uuid = "u_late",
            pubkey = "bob",
            createdAt = 50,
            content = expenseJson("u_late", 200, paidBy = "bob", splits = ab100, timestamp = 50)
        )
        installSnapshot(
            dao,
            repo,
            events = listOf(late),
            coveredIds = emptyList(),
            balances = """[${balEntry("alice", 50, "INR")},${balEntry("bob", -50, "INR")}]"""
        )

        val balances = ComputeBalancesUseCase(dao, repo, encryption())("g1")

        // Snapshot: alice +50, bob -50; late expense: alice -100, bob +100
        assertEquals(-50L, balances.find { it.pubkey == "alice" }?.net)
        assertEquals(50L, balances.find { it.pubkey == "bob" }?.net)
    }

    @Test
    fun `covered expense deleted after the snapshot is reversed`() = runTest {
        val dao = eventDao()
        val repo = groupRepo()
        val ab50 = split("alice" to 50, "bob" to 50)
        val original = makeEvent(
            "e1",
            type = "expense",
            uuid = "u1",
            createdAt = 10,
            content = expenseJson("u1", 100, splits = ab50)
        )
        val delete = makeEvent("e2", type = "expense_delete", uuid = "u1", createdAt = 200, content = "{}")
        installSnapshot(
            dao,
            repo,
            events = listOf(original, delete),
            coveredIds = listOf("e1"),
            // Snapshot already contains e1's effect.
            balances = """[${balEntry("alice", 50, "INR")},${balEntry("bob", -50, "INR")}]"""
        )

        val result = ComputeBalancesUseCase(dao, repo, encryption()).computeWithExclusions("g1")

        assertTrue("Reversal must zero the covered expense", result.balances.all { it.net == 0L })
        assertTrue(ExpenseIdentity("alice", "u1") in result.excludedExpenses)
    }

    @Test
    fun `covered expense corrected after the snapshot reverses the original and applies the correction`() = runTest {
        val dao = eventDao()
        val repo = groupRepo()
        val ab50 = split("alice" to 50, "bob" to 50)
        val ab100 = split("alice" to 100, "bob" to 100)
        val original = makeEvent(
            "e1",
            type = "expense",
            uuid = "u1",
            createdAt = 10,
            content = expenseJson("u1", 100, splits = ab50)
        )
        val correction = makeEvent(
            "e2",
            type = "expense_correction",
            uuid = "u1",
            createdAt = 200,
            content = expenseJson("u1", 200, splits = ab100, timestamp = 2)
        )
        installSnapshot(
            dao,
            repo,
            events = listOf(original, correction),
            coveredIds = listOf("e1"),
            balances = """[${balEntry("alice", 50, "INR")},${balEntry("bob", -50, "INR")}]"""
        )

        val result = ComputeBalancesUseCase(dao, repo, encryption()).computeWithExclusions("g1")

        // -50 (reverse original) +100 (correction) = 100
        assertEquals(100L, result.balances.find { it.pubkey == "alice" }?.net)
        assertEquals(-100L, result.balances.find { it.pubkey == "bob" }?.net)
        // Corrected expenses are shown (with the corrected payload), so they are not excluded.
        assertTrue(ExpenseIdentity("alice", "u1") !in result.excludedExpenses)
    }

    @Test
    fun `covered correction superseded by a later uncovered correction is reversed`() = runTest {
        val dao = eventDao()
        val repo = groupRepo()
        val ab50 = split("alice" to 50, "bob" to 50)
        val ab100 = split("alice" to 100, "bob" to 100)
        val ab150 = split("alice" to 150, "bob" to 150)
        val original =
            makeEvent(
                "e1",
                type = "expense",
                uuid = "u1",
                createdAt = 10,
                content = expenseJson("u1", 100, splits = ab50)
            )
        val c1 = makeEvent(
            "e2",
            type = "expense_correction",
            uuid = "u1",
            createdAt = 20,
            content = expenseJson("u1", 200, splits = ab100, timestamp = 2)
        )
        val c2 = makeEvent(
            "e3",
            type = "expense_correction",
            uuid = "u1",
            createdAt = 300,
            content = expenseJson("u1", 300, splits = ab150, timestamp = 3)
        )
        installSnapshot(
            dao,
            repo,
            events = listOf(original, c1, c2),
            coveredIds = listOf("e1", "e2"),
            // Snapshot holds c1's effect (alice +100).
            balances = """[${balEntry("alice", 100, "INR")},${balEntry("bob", -100, "INR")}]"""
        )

        val balances = ComputeBalancesUseCase(dao, repo, encryption())("g1")

        // -100 (reverse c1) +150 (c2) = 150
        assertEquals(150L, balances.find { it.pubkey == "alice" }?.net)
        assertEquals(-150L, balances.find { it.pubkey == "bob" }?.net)
    }

    @Test
    fun `covered expense with no later events is not replayed again`() = runTest {
        val dao = eventDao()
        val repo = groupRepo()
        val ab50 = split("alice" to 50, "bob" to 50)
        val original =
            makeEvent(
                "e1",
                type = "expense",
                uuid = "u1",
                createdAt = 10,
                content = expenseJson("u1", 100, splits = ab50)
            )
        installSnapshot(
            dao,
            repo,
            events = listOf(original),
            coveredIds = listOf("e1"),
            balances = """[${balEntry("alice", 50, "INR")},${balEntry("bob", -50, "INR")}]"""
        )

        val balances = ComputeBalancesUseCase(dao, repo, encryption())("g1")

        assertEquals(50L, balances.find { it.pubkey == "alice" }?.net)
        assertEquals(-50L, balances.find { it.pubkey == "bob" }?.net)
    }

    @Test
    fun `covered settlement re-sent after the snapshot is not double counted`() = runTest {
        val dao = eventDao()
        val repo = groupRepo()
        val first = makeEvent(
            "e1",
            type = "settlement",
            uuid = "s1",
            pubkey = "bob",
            createdAt = 10,
            content = settlementJson("s1", "bob", "alice", 50, timestamp = 10)
        )
        val resent = makeEvent(
            "e2",
            type = "settlement",
            uuid = "s1",
            pubkey = "bob",
            createdAt = 200,
            content = settlementJson("s1", "bob", "alice", 50, timestamp = 10)
        )
        installSnapshot(
            dao,
            repo,
            events = listOf(first, resent),
            coveredIds = listOf("e1"),
            // Snapshot already applied s1.
            balances = """[${balEntry("alice", 0, "INR")},${balEntry("bob", 0, "INR")}]"""
        )

        val balances = ComputeBalancesUseCase(dao, repo, encryption())("g1")

        assertTrue("Re-sent settlement must be ignored", balances.all { it.net == 0L })
    }

    @Test
    fun `latest correction is chosen by createdAt regardless of storage order`() = runTest {
        val dao = eventDao()
        val repo = groupRepo()
        val ab50 = split("alice" to 50, "bob" to 50)
        val ab100 = split("alice" to 100, "bob" to 100)
        val ab150 = split("alice" to 150, "bob" to 150)
        coEvery { dao.getEventsByGroup("g1") } returns
            listOf(
                makeEvent(
                    "e1",
                    type = "expense",
                    uuid = "u1",
                    createdAt = 1,
                    content = expenseJson("u1", 100, splits = ab50)
                ),
                // Newer correction stored first...
                makeEvent(
                    "e3",
                    type = "expense_correction",
                    uuid = "u1",
                    createdAt = 3,
                    content = expenseJson("u1", 300, splits = ab150, timestamp = 3)
                ),
                // ...older correction stored last.
                makeEvent(
                    "e2",
                    type = "expense_correction",
                    uuid = "u1",
                    createdAt = 2,
                    content = expenseJson("u1", 200, splits = ab100, timestamp = 2)
                )
            )
        coEvery { dao.getLatestEventByType("g1", "snapshot") } returns null

        val balances = ComputeBalancesUseCase(dao, repo, encryption())("g1")

        assertEquals(150L, balances.find { it.pubkey == "alice" }?.net)
    }

    @Test
    fun `correction ties on createdAt are broken by eventId`() = runTest {
        val dao = eventDao()
        val repo = groupRepo()
        val ab50 = split("alice" to 50, "bob" to 50)
        val ab100 = split("alice" to 100, "bob" to 100)
        val ab150 = split("alice" to 150, "bob" to 150)
        coEvery { dao.getEventsByGroup("g1") } returns
            listOf(
                makeEvent(
                    "e1",
                    type = "expense",
                    uuid = "u1",
                    createdAt = 1,
                    content = expenseJson("u1", 100, splits = ab50)
                ),
                makeEvent(
                    "zz",
                    type = "expense_correction",
                    uuid = "u1",
                    createdAt = 2,
                    content = expenseJson("u1", 300, splits = ab150, timestamp = 2)
                ),
                makeEvent(
                    "aa",
                    type = "expense_correction",
                    uuid = "u1",
                    createdAt = 2,
                    content = expenseJson("u1", 200, splits = ab100, timestamp = 2)
                )
            )
        coEvery { dao.getLatestEventByType("g1", "snapshot") } returns null

        val balances = ComputeBalancesUseCase(dao, repo, encryption())("g1")

        // Same createdAt: the lexicographically greater eventId ("zz") wins.
        assertEquals(150L, balances.find { it.pubkey == "alice" }?.net)
    }

    @Test
    fun `duplicate expense events with the same x tag apply only the earliest`() = runTest {
        mockLogW()
        val dao = eventDao()
        val repo = groupRepo()
        val ab50 = split("alice" to 50, "bob" to 50)
        val ab100 = split("alice" to 100, "bob" to 100)
        coEvery { dao.getEventsByGroup("g1") } returns
            listOf(
                // Later duplicate stored first to prove ordering is by createdAt, not position.
                makeEvent(
                    "e2",
                    type = "expense",
                    uuid = "u1",
                    createdAt = 2,
                    content = expenseJson("u1", 200, splits = ab100)
                ),
                makeEvent(
                    "e1",
                    type = "expense",
                    uuid = "u1",
                    createdAt = 1,
                    content = expenseJson("u1", 100, splits = ab50)
                )
            )
        coEvery { dao.getLatestEventByType("g1", "snapshot") } returns null

        val balances = ComputeBalancesUseCase(dao, repo, encryption())("g1")

        assertEquals(50L, balances.find { it.pubkey == "alice" }?.net)
        assertEquals(-50L, balances.find { it.pubkey == "bob" }?.net)
        verify(atLeast = 1) { android.util.Log.w("ComputeBalances", match<String> { "e2" in it && "u1" in it }) }
        unmockkStatic(android.util.Log::class)
    }

    @Test
    fun `duplicate expense arriving after the snapshot is not applied on top of the covered one`() = runTest {
        mockLogW()
        val dao = eventDao()
        val repo = groupRepo()
        val ab50 = split("alice" to 50, "bob" to 50)
        val covered =
            makeEvent(
                "e1",
                type = "expense",
                uuid = "u1",
                createdAt = 1,
                content = expenseJson("u1", 100, splits = ab50)
            )
        val dup =
            makeEvent(
                "e2",
                type = "expense",
                uuid = "u1",
                createdAt = 500,
                content = expenseJson("u1", 100, splits = ab50)
            )
        installSnapshot(
            dao,
            repo,
            events = listOf(covered, dup),
            coveredIds = listOf("e1"),
            balances = """[${balEntry("alice", 50, "INR")},${balEntry("bob", -50, "INR")}]"""
        )

        val balances = ComputeBalancesUseCase(dao, repo, encryption())("g1")

        assertEquals(50L, balances.find { it.pubkey == "alice" }?.net)
        unmockkStatic(android.util.Log::class)
    }

    @Test
    fun `snapshot with truncated 24-char hashes is matched`() = runTest {
        val dao = eventDao()
        val repo = groupRepo()
        installSnapshot(
            dao,
            repo,
            events = emptyList(),
            coveredIds = emptyList(),
            balances = """[${balEntry("alice", 500, "INR")}]""",
            hash = HashUtil::eventHashPrefix
        )

        val balances = ComputeBalancesUseCase(dao, repo, encryption())("g1")

        assertEquals(500L, balances.find { it.pubkey == "alice" }?.net)
    }

    @Test
    fun `legacy snapshot with full-length hashes is still matched`() = runTest {
        val dao = eventDao()
        val repo = groupRepo()
        val ab50 = split("alice" to 50, "bob" to 50)
        val original =
            makeEvent(
                "e1",
                type = "expense",
                uuid = "u1",
                createdAt = 1,
                content = expenseJson("u1", 100, splits = ab50)
            )
        installSnapshot(
            dao,
            repo,
            events = listOf(original),
            coveredIds = listOf("e1"),
            balances = """[${balEntry("alice", 500, "INR")}]""",
            hash = HashUtil::sha256Hex
        )

        val balances = ComputeBalancesUseCase(dao, repo, encryption())("g1")

        // Snapshot trusted AND e1 recognised as covered (not replayed on top of the 500).
        assertEquals(500L, balances.find { it.pubkey == "alice" }?.net)
    }

    @Test
    fun `snapshot mixing legacy and truncated hashes is matched`() = runTest {
        val dao = eventDao()
        val repo = groupRepo()
        var toggle = false
        installSnapshot(
            dao,
            repo,
            events = emptyList(),
            coveredIds = emptyList(),
            balances = """[${balEntry("alice", 500, "INR")}]""",
            hash = { id ->
                toggle = !toggle
                if (toggle) HashUtil.sha256Hex(id) else HashUtil.eventHashPrefix(id)
            }
        )

        val balances = ComputeBalancesUseCase(dao, repo, encryption())("g1")

        assertEquals(500L, balances.find { it.pubkey == "alice" }?.net)
    }

    // --- Author-bound expense identity (NS-15): (author, uuid), never uuid alone ---

    /** An expense/correction payload for uuid U paid by [payer] and split evenly with [other]. */
    private fun uPayload(payer: String, other: String, amount: Long, ts: Long): String = expenseJson(
        "U",
        amount,
        paidBy = payer,
        splits = split(payer to amount / 2, other to amount / 2),
        timestamp = ts
    )

    /** Alice's genuine expense under uuid U: alice paid 100, split with bob -> alice +50, bob -50. */
    private val aliceExpense = makeEvent(
        "e_alice",
        type = "expense",
        uuid = "U",
        pubkey = "alice",
        createdAt = 100,
        content = uPayload("alice", "bob", 100, ts = 100)
    )

    /**
     * Mallory front-runs the same uuid with an EARLIER created_at so a uuid-only index would pick hers
     * as "the" original. Her record involves only her and carol: mallory +100, carol -100.
     */
    private val malloryExpense = makeEvent(
        "e_mallory",
        type = "expense",
        uuid = "U",
        pubkey = "mallory",
        createdAt = 10,
        content = uPayload("mallory", "carol", 200, ts = 10)
    )

    /** Mallory's correction of U: mallory +200, carol -200. */
    private val malloryCorrection = makeEvent(
        "c_mallory",
        type = "expense_correction",
        uuid = "U",
        pubkey = "mallory",
        createdAt = 20,
        content = uPayload("mallory", "carol", 400, ts = 20)
    )

    private val malloryDelete =
        makeEvent("d_mallory", type = "expense_delete", uuid = "U", pubkey = "mallory", createdAt = 30, content = "{}")

    private val aliceDelete =
        makeEvent("d_alice", type = "expense_delete", uuid = "U", pubkey = "alice", createdAt = 300, content = "{}")

    private val aliceCorrection = makeEvent(
        "c_alice",
        type = "expense_correction",
        uuid = "U",
        pubkey = "alice",
        createdAt = 200,
        content = uPayload("alice", "bob", 300, ts = 200)
    )

    private fun <T> permutations(items: List<T>): List<List<T>> {
        if (items.size <= 1) return listOf(items)
        return items.indices.flatMap { i ->
            permutations(items.take(i) + items.drop(i + 1)).map { rest -> listOf(items[i]) + rest }
        }
    }

    private fun nets(result: BalanceResult): Map<String, Long> = result.balances.associate { it.pubkey to it.net }

    /** Runs the use case once per permutation of [events] and asserts every run yields the same result. */
    private suspend fun computeForEveryOrder(events: List<EventSnapshot>): BalanceResult {
        val results = permutations(events).map { order ->
            val dao = eventDao()
            val repo = groupRepo()
            coEvery { dao.getEventsByGroup("g1") } returns order
            coEvery { dao.getLatestEventByType("g1", "snapshot") } returns null
            ComputeBalancesUseCase(dao, repo, encryption()).computeWithExclusions("g1")
        }
        val first = results.first()
        for (r in results) {
            assertEquals("balances must not depend on arrival order", nets(first), nets(r))
            assertEquals("exclusions must not depend on order", first.excludedExpenses, r.excludedExpenses)
        }
        return first
    }

    @Test
    fun `hostile collision - Mallory's correction of a shared uuid never touches Alice's expense`() = runTest {
        mockLogW()
        val result = computeForEveryOrder(listOf(aliceExpense, malloryExpense, malloryCorrection))

        val n = nets(result)
        // Alice's record: untouched by Mallory's correction, even though Mallory's created_at is earlier.
        assertEquals(50L, n["alice"])
        assertEquals(-50L, n["bob"])
        // Mallory's record is its own expense and her correction applies to it alone.
        assertEquals(200L, n["mallory"])
        assertEquals(-200L, n["carol"])
        assertTrue(result.excludedExpenses.isEmpty())
        verify(atLeast = 1) { android.util.Log.w("ComputeBalances", match<String> { "U" in it && "2 authors" in it }) }
        unmockkStatic(android.util.Log::class)
    }

    @Test
    fun `hostile collision - Mallory's delete of a shared uuid removes only her own record`() = runTest {
        mockLogW()
        val result = computeForEveryOrder(listOf(aliceExpense, malloryExpense, malloryDelete))

        val n = nets(result)
        assertEquals(50L, n["alice"])
        assertEquals(-50L, n["bob"])
        assertTrue("Mallory's own record is gone", "mallory" !in n && "carol" !in n)
        // Alice's record is still live, so the uuid must stay visible in the UI.
        assertEquals(setOf(ExpenseIdentity("mallory", "U")), result.excludedExpenses)
        unmockkStatic(android.util.Log::class)
    }

    @Test
    fun `hostile collision - Mallory's correction then delete leaves Alice intact`() = runTest {
        mockLogW()
        val result = computeForEveryOrder(listOf(aliceExpense, malloryExpense, malloryCorrection, malloryDelete))

        val n = nets(result)
        assertEquals(mapOf("alice" to 50L, "bob" to -50L), n)
        assertEquals(setOf(ExpenseIdentity("mallory", "U")), result.excludedExpenses)
        unmockkStatic(android.util.Log::class)
    }

    @Test
    fun `hostile collision - orphan correction and delete without Mallory's own expense leave Alice alone`() = runTest {
        val result = computeForEveryOrder(listOf(aliceExpense, malloryCorrection, malloryDelete))

        assertEquals(mapOf("alice" to 50L, "bob" to -50L), nets(result))
        assertEquals(setOf(ExpenseIdentity("mallory", "U")), result.excludedExpenses)
    }

    @Test
    fun `hostile collision - Alice deleting her record does not hide Mallory's separate record`() = runTest {
        mockLogW()
        val result = computeForEveryOrder(listOf(aliceExpense, aliceDelete, malloryExpense))

        val n = nets(result)
        assertTrue("alice" !in n && "bob" !in n)
        assertEquals(100L, n["mallory"])
        assertEquals(-100L, n["carol"])
        // Mallory's record is live under the same uuid, so it must not be filtered from the list.
        assertEquals(setOf(ExpenseIdentity("alice", "U")), result.excludedExpenses)
        unmockkStatic(android.util.Log::class)
    }

    @Test
    fun `each author-bound identity is excluded when both records are deleted`() = runTest {
        mockLogW()
        val result = computeForEveryOrder(listOf(aliceExpense, aliceDelete, malloryExpense, malloryDelete))

        assertTrue(result.balances.all { it.net == 0L })
        assertEquals(setOf(ExpenseIdentity("alice", "U"), ExpenseIdentity("mallory", "U")), result.excludedExpenses)
        unmockkStatic(android.util.Log::class)
    }

    @Test
    fun `correction by the original author still applies`() = runTest {
        val result = computeForEveryOrder(listOf(aliceExpense, aliceCorrection))

        assertEquals(mapOf("alice" to 150L, "bob" to -150L), nets(result))
        assertTrue(result.excludedExpenses.isEmpty())
    }

    @Test
    fun `delete by the original author still excludes`() = runTest {
        val result = computeForEveryOrder(listOf(aliceExpense, aliceCorrection, aliceDelete))

        assertTrue(result.balances.all { it.net == 0L })
        assertEquals(setOf(ExpenseIdentity("alice", "U")), result.excludedExpenses)
    }

    @Test
    fun `same-author duplicates collapse but another author's same uuid is kept`() = runTest {
        mockLogW()
        // Alice re-sends her expense (later duplicate, different payload); Mallory's is a distinct record.
        val aliceDup = makeEvent(
            "e_alice_dup",
            type = "expense",
            uuid = "U",
            pubkey = "alice",
            createdAt = 150,
            content = uPayload("alice", "bob", 1000, ts = 150)
        )
        val result = computeForEveryOrder(listOf(aliceExpense, aliceDup, malloryExpense))

        assertEquals(mapOf("alice" to 50L, "bob" to -50L, "mallory" to 100L, "carol" to -100L), nets(result))
        unmockkStatic(android.util.Log::class)
    }

    @Test
    fun `snapshot-covered Alice expense is not reversed by Mallory's delete of the same uuid`() = runTest {
        mockLogW()
        val dao = eventDao()
        val repo = groupRepo()
        installSnapshot(
            dao,
            repo,
            events = listOf(aliceExpense, malloryExpense, malloryDelete),
            coveredIds = listOf("e_alice"),
            // Snapshot already holds Alice's effect.
            balances = """[${balEntry("alice", 50, "INR")},${balEntry("bob", -50, "INR")}]"""
        )

        val result = ComputeBalancesUseCase(dao, repo, encryption()).computeWithExclusions("g1")

        // Alice's covered payload stays; Mallory's uncovered record is deleted before it is ever applied.
        assertEquals(mapOf("alice" to 50L, "bob" to -50L), nets(result))
        assertEquals(setOf(ExpenseIdentity("mallory", "U")), result.excludedExpenses)
        unmockkStatic(android.util.Log::class)
    }
}
