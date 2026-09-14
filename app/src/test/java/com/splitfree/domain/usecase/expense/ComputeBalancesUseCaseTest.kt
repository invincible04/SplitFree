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
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.mockkStatic
import io.mockk.unmockkObject
import io.mockk.unmockkStatic
import io.mockk.verify
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class ComputeBalancesUseCaseTest {
    private val groupKey = "testkey"

    /** Snapshot fallbacks and identity collisions log through `android.util.Log`, which the JVM cannot run. */
    @Before
    fun silenceLog() = mockLogW()

    @After
    fun restoreLog() = unmockkStatic(android.util.Log::class)

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

        val useCase = ComputeBalancesUseCase(dao, repo, encryption())
        val balances = useCase("g1")

        val alice = balances.find { it.pubkey == "alice" }
        assertEquals(50L, alice?.net) // settlement ignored, debt remains
    }

    @Test
    fun `malformed settlement payload fails instead of returning partial balances`() = runTest {
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

        val useCase = ComputeBalancesUseCase(dao, repo, encryption())

        assertThrows(BalanceUnavailableException::class.java) { runBlocking { useCase("g1") } }
    }

    @Test
    fun `overflowing settlement fails instead of returning partial balances`() = runTest {
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

        val useCase = ComputeBalancesUseCase(dao, repo, encryption())

        assertThrows(BalanceUnavailableException::class.java) { runBlocking { useCase("g1") } }
    }

    @Test
    fun `overflowing expense fails instead of returning partial balances`() = runTest {
        val dao = eventDao()
        val repo = groupRepo()
        val huge = split("alice" to 0L, "bob" to Long.MAX_VALUE)
        coEvery { dao.getEventsByGroup("g1") } returns
            listOf(
                makeEvent(
                    "e1",
                    type = "expense",
                    uuid = "u1",
                    content = expenseJson("u1", Long.MAX_VALUE, splits = huge)
                ),
                makeEvent(
                    "e2",
                    type = "expense",
                    uuid = "u2",
                    createdAt = 2,
                    content = expenseJson("u2", Long.MAX_VALUE, splits = huge, timestamp = 2)
                )
            )

        val useCase = ComputeBalancesUseCase(dao, repo, encryption())

        assertThrows(BalanceUnavailableException::class.java) { runBlocking { useCase("g1") } }
    }

    @Test
    fun `malformed expense payload fails instead of returning partial balances`() = runTest {
        val dao = eventDao()
        val repo = groupRepo()
        coEvery { dao.getEventsByGroup("g1") } returns
            listOf(
                makeEvent(
                    "e1",
                    type = "expense",
                    uuid = "u1",
                    content = expenseJson("u1", 100, splits = split("alice" to 50, "bob" to 50))
                ),
                makeEvent("e2", type = "expense", uuid = "u2", createdAt = 2, content = "not-json")
            )

        val useCase = ComputeBalancesUseCase(dao, repo, encryption())

        assertThrows(BalanceUnavailableException::class.java) { runBlocking { useCase("g1") } }
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

        val useCase = ComputeBalancesUseCase(dao, repo, encryption())
        val balances = useCase("g1")

        assertTrue(
            "Negative shares should skip expense",
            balances.isEmpty() || balances.all { it.net == 0L }
        )
    }

    @Test
    fun `events with undecryptable content fail instead of returning partial balances`() = runTest {
        val dao = eventDao()
        val repo = groupRepo()
        val enc = mockk<GroupEncryption>()
        every { enc.decrypt(any(), any()) } throws IllegalArgumentException("bad key")
        coEvery { dao.getEventsByGroup("g1") } returns
            listOf(
                EventSnapshot("e1", "g1", "alice", 1, 30078, "enc", "expense", "u1", "s", receivedAt = 1)
            )

        val useCase = ComputeBalancesUseCase(dao, repo, enc)

        val failure = assertThrows(BalanceUnavailableException::class.java) { runBlocking { useCase("g1") } }
        // Coroutine stack-trace recovery may wrap the thrown instance once more; the root cause is what matters.
        assertTrue(generateSequence(failure.cause) { it.cause }.any { it is IllegalArgumentException })
    }

    @Test
    fun `empty group returns empty balances`() = runTest {
        val dao = eventDao()
        val repo = groupRepo()
        coEvery { dao.getEventsByGroup("g1") } returns emptyList()

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
        val snapshot = makeEvent("s1", type = "snapshot", pubkey = "alice", content = snapContent)
        val ab100 = split("alice" to 100, "bob" to 100)
        val expense = makeEvent(
            "e1",
            type = "expense",
            uuid = "u2",
            createdAt = 200,
            content = expenseJson("u2", 200, paidBy = "bob", splits = ab100, timestamp = 200)
        )
        installLedger(dao, listOf(expense, snapshot))
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
        val snapshot = makeEvent("s1", type = "snapshot", pubkey = "mallory", content = snapContent)
        installLedger(dao, listOf(snapshot))
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
            "\"${HashUtil.eventHashPrefix(it)}\""
        }
        val snapContent = snapJson(
            eventCount = 10,
            balances = """[${balEntry("alice", 9999, "INR")}]""",
            hashes = hashArr
        )
        val snapshot = makeEvent("s1", type = "snapshot", pubkey = "alice", content = snapContent)
        installLedger(dao, listOf(snapshot), knownIds = eventIds)

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
            "\"${HashUtil.eventHashPrefix(it)}\""
        }
        val snapContent = snapJson(
            eventCount = 10,
            balances = """[${balEntry("alice", 9999, "INR")}]""",
            hashes = hashArr
        )
        val snapshot = makeEvent("s1", type = "snapshot", pubkey = "", content = snapContent)
        installLedger(dao, listOf(snapshot), knownIds = eventIds)

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
            HashUtil.eventHashPrefix(it)
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
        val snapshot = makeEvent("s1", type = "snapshot", pubkey = "alice", content = snapContent)
        installLedger(dao, listOf(snapshot), knownIds = eventIds)
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
        val snapshot = makeEvent("s1", type = "snapshot", pubkey = "alice", content = snapContent)
        installLedger(dao, listOf(snapshot), knownIds = listOf("unrelated_1"))
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
        val snapshot = makeEvent("s1", type = "snapshot", pubkey = "alice", content = snapContent)
        installLedger(dao, listOf(snapshot), knownIds = listOf("other"))
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
        val snapshot = makeEvent("s1", type = "snapshot", pubkey = "alice", content = snapContent)
        installLedger(dao, listOf(snapshot))
        val useCase = ComputeBalancesUseCase(dao, repo, encryption())
        val balances = useCase("g1")
        assertTrue(balances.isEmpty())
    }

    @Test
    fun `snapshot with malformed JSON is ignored`() = runTest {
        val dao = eventDao()
        val repo = groupRepo()
        coEvery { repo.getById("g1") } returns group("alice")
        val snapshot = makeEvent("s1", type = "snapshot", pubkey = "alice", content = "not json")
        installLedger(dao, listOf(snapshot))
        val useCase = ComputeBalancesUseCase(dao, repo, encryption())
        val balances = useCase("g1")
        assertTrue(balances.isEmpty())
    }

    @Test
    fun `snapshot whose ciphertext does not open is ignored and the ledger replayed`() = runTest {
        val dao = eventDao()
        val repo = groupRepo()
        coEvery { repo.getById("g1") } returns group("alice")
        val enc = encryption()
        every { enc.decrypt("sealed", groupKey) } throws IllegalArgumentException("bad key")
        val snapshot = EventSnapshot("s1", "g1", "alice", 1, 30078, "sealed", "snapshot", null, "s", receivedAt = 1)
        val expense = makeEvent(
            "e1",
            type = "expense",
            uuid = "u1",
            content = expenseJson(
                "u1",
                100,
                splits = split(
                    "alice" to 50,
                    "bob" to 50
                )
            )
        )
        installLedger(dao, listOf(snapshot, expense))
        val useCase = ComputeBalancesUseCase(dao, repo, enc)
        val balances = useCase("g1")
        assertEquals(50L, balances.find { it.pubkey == "alice" }?.net)
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
        val snapshot =
            makeEvent("snap", type = "snapshot", content = snapshotContent, pubkey = "alice", createdAt = 101)
        val ab50 = split("alice" to 50, "bob" to 50)
        val ab100 = split("alice" to 100, "bob" to 100)
        val ledger = listOf(
            makeEvent(
                "e1",
                type = "expense",
                uuid = "u1",
                createdAt = 50,
                content = expenseJson("u1", 100, splits = ab50)
            ),
            makeEvent(
                "e2",
                type = "expense",
                uuid = "u2",
                createdAt = 200,
                content = expenseJson("u2", 200, paidBy = "bob", splits = ab100, timestamp = 2)
            )
        )
        installLedger(dao, ledger + snapshot)
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

    private fun hashArr(ids: List<String>): String =
        ids.joinToString(",", "[", "]") { "\"${HashUtil.eventHashPrefix(it)}\"" }

    private fun mockLogW() {
        mockkStatic(android.util.Log::class)
        every { android.util.Log.w(any<String>(), any<String>()) } returns 0
    }

    /**
     * Stubs the one ledger read with [events] plus a non-money `group_meta` row for every id in [knownIds]
     * that [events] does not already contain, so snapshot coverage can be exercised without more payloads.
     */
    private fun installLedger(
        dao: EventRepositoryContract,
        events: List<EventSnapshot>,
        knownIds: List<String> = emptyList()
    ) {
        val filler = knownIds.filter { id -> events.none { it.eventId == id } }.map {
            makeEvent(it, type = "group_meta", content = "{}")
        }
        coEvery { dao.getEventsByGroup("g1") } returns events + filler
    }

    /**
     * Installs a creator snapshot with the given balances that covers [fillerIds] plus [coveredIds].
     * The ledger holds the snapshot, [events] and a filler row for every id in [fillerIds]. A [coveredIds]
     * entry with no row in [events] makes the snapshot unverifiable, so the ledger is replayed in full.
     */
    private fun installSnapshot(
        dao: EventRepositoryContract,
        repo: GroupRepositoryContract,
        events: List<EventSnapshot>,
        coveredIds: List<String>,
        balances: String,
        asOfTs: Long = 100
    ) {
        coEvery { repo.getById("g1") } returns group("alice")
        val snapshot = makeEvent(
            "snap",
            type = "snapshot",
            pubkey = "alice",
            createdAt = asOfTs + 1,
            content = snapJson(
                eventCount = fillerIds.size + coveredIds.size,
                asOfTs = asOfTs,
                balances = balances,
                hashes = hashArr(fillerIds + coveredIds)
            )
        )
        installLedger(dao, events + snapshot, knownIds = fillerIds)
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

    /** A rejected snapshot must replay the real 50, not its claimed 500, including the covered expense. */
    private suspend fun assertCoverageRejected(
        hashes: List<String>,
        count: Int = hashes.size,
        knownIds: List<String> = fillerIds
    ) {
        val dao = eventDao()
        val repo = groupRepo()
        coEvery { repo.getById("g1") } returns group()
        val expense = makeEvent(
            "e1",
            type = "expense",
            uuid = "u1",
            content = expenseJson("u1", 100, splits = split("alice" to 50, "bob" to 50))
        )
        val snapshot = makeEvent(
            "snap",
            type = "snapshot",
            content = snapJson(
                eventCount = count,
                balances = "[${balEntry("alice", 500, "INR")},${balEntry("bob", -500, "INR")}]",
                hashes = hashes.joinToString(",", "[", "]") { "\"$it\"" }
            )
        )
        installLedger(dao, listOf(expense, snapshot), knownIds)
        val compute = ComputeBalancesUseCase(dao, repo, encryption())
        val ledger = dao.getEventsByGroup("g1")
        val replay = compute.computeWithExclusions("g1", ledger, useSnapshots = false)
        assertEquals(mapOf("alice" to 50L, "bob" to -50L), nets(replay))
        for (order in listOf(ledger, ledger.reversed())) {
            val result = compute.computeWithExclusions("g1", order)
            assertEquals("Invalid coverage must fall back regardless of storage order", nets(replay), nets(result))
            assertEquals(replay.excludedExpenses, result.excludedExpenses)
        }
    }

    @Test
    fun `one missing hash cannot be masked by unrelated local events`() = runTest {
        assertCoverageRejected(
            (fillerIds + "missing" + "e1").map(HashUtil::eventHashPrefix),
            knownIds = fillerIds + (1..20).map { "unrelated-$it" }
        )
    }

    @Test
    fun `duplicate hashes cannot inflate coverage`() = runTest {
        val hashes = (fillerIds + "e1").map(HashUtil::eventHashPrefix)
        assertCoverageRejected(hashes + hashes.first())
    }

    @Test
    fun `as_of_event_count must equal the number of hashes`() = runTest {
        val hashes = (fillerIds + "e1").map(HashUtil::eventHashPrefix)
        for (count in listOf(hashes.size - 1, hashes.size + 1)) assertCoverageRejected(hashes, count = count)
    }

    @Test
    fun `snapshot with full-length hashes is rejected and replayed`() = runTest {
        assertCoverageRejected((fillerIds + "e1").map(HashUtil::sha256Hex))
    }

    @Test
    fun `malformed hash cannot hide among otherwise matching hashes`() = runTest {
        val hashes = (fillerIds + "e1").map(HashUtil::eventHashPrefix)
        val first = hashes.first()
        for (bad in listOf(first.dropLast(1), first + "0", first.dropLast(1) + "g")) {
            assertCoverageRejected(listOf(bad) + hashes.drop(1))
        }
    }

    @Test
    fun `one prefix matching two distinct local event ids is ambiguous coverage`() = runTest {
        val hashes = (fillerIds + "e1").map(HashUtil::eventHashPrefix)
        // An actual 96-bit collision is infeasible to generate in a test; model only the hash lookup.
        mockkObject(HashUtil)
        try {
            every { HashUtil.eventHashPrefix("collision") } returns hashes.first()
            assertCoverageRejected(hashes, knownIds = fillerIds + "collision")
        } finally {
            unmockkObject(HashUtil)
        }
    }

    @Test
    fun `rejected partial snapshot does not hide unreadable covered money`() = runTest {
        val dao = eventDao()
        val repo = groupRepo()
        val bad = makeEvent("e1", type = "expense", uuid = "u1", content = "not-json")
        installSnapshot(dao, repo, listOf(bad), listOf("e1", "missing"), "[${balEntry("alice", 500, "INR")}]")
        assertThrows(BalanceUnavailableException::class.java) {
            runBlocking { ComputeBalancesUseCase(dao, repo, encryption())("g1") }
        }
    }

    @Test
    fun `rejected partial snapshot does not hide overflow in covered money`() = runTest {
        val dao = eventDao()
        val repo = groupRepo()
        val money = (1..2).map {
            makeEvent(
                "e$it",
                type = "expense",
                uuid = "u$it",
                content = expenseJson(
                    "u$it",
                    Long.MAX_VALUE,
                    splits = split("alice" to 0L, "bob" to Long.MAX_VALUE)
                )
            )
        }
        installSnapshot(dao, repo, money, listOf("e1", "e2", "missing"), "[${balEntry("alice", 500, "INR")}]")
        assertThrows(BalanceUnavailableException::class.java) {
            runBlocking { ComputeBalancesUseCase(dao, repo, encryption())("g1") }
        }
    }

    // --- Fail closed: unreadable money is an exception, never a partial total ---

    @Test
    fun `missing expense epoch key fails rather than returning zero`() = runTest {
        val dao = eventDao()
        val repo = groupRepo()
        val expense = makeEvent("e1", type = "expense", uuid = "u1", content = "ciphertext").copy(keyEpoch = 2)
        coEvery { dao.getEventsByGroup("g1") } returns listOf(expense)
        coEvery { repo.getGroupKeyForEpoch("g1", 2) } returns null

        assertThrows(BalanceUnavailableException::class.java) {
            runBlocking { ComputeBalancesUseCase(dao, repo, encryption())("g1") }
        }
        coVerify(exactly = 0) { repo.getGroupKey(any()) }
    }

    @Test
    fun `missing settlement epoch key fails rather than returning partial balances`() = runTest {
        val dao = eventDao()
        val repo = groupRepo()
        coEvery { dao.getEventsByGroup("g1") } returns listOf(
            makeEvent("e1", type = "settlement", content = "ciphertext").copy(keyEpoch = 2)
        )
        coEvery { repo.getGroupKeyForEpoch("g1", 2) } returns null

        assertThrows(BalanceUnavailableException::class.java) {
            runBlocking { ComputeBalancesUseCase(dao, repo, encryption())("g1") }
        }
    }

    @Test
    fun `one unreadable expense among readable ones fails the whole computation`() = runTest {
        val dao = eventDao()
        val repo = groupRepo()
        val readable = makeEvent(
            "e1",
            type = "expense",
            uuid = "u1",
            content = expenseJson("u1", 100, splits = split("alice" to 50, "bob" to 50))
        )
        val sealed = makeEvent("e2", type = "expense", uuid = "u2", createdAt = 2, content = "ciphertext")
            .copy(keyEpoch = 1)
        coEvery { dao.getEventsByGroup("g1") } returns listOf(readable, sealed)
        coEvery { repo.getGroupKeyForEpoch("g1", 1) } returns null

        assertThrows(BalanceUnavailableException::class.java) {
            runBlocking { ComputeBalancesUseCase(dao, repo, encryption())("g1") }
        }

        // Once the key is present the same ledger yields the full total.
        coEvery { repo.getGroupKeyForEpoch("g1", 1) } returns groupKey
        val sealedReadable = sealed.copy(
            contentEncrypted = expenseJson(
                "u2",
                100,
                splits = split(
                    "alice" to 50,
                    "bob" to 50
                )
            )
        )
        coEvery { dao.getEventsByGroup("g1") } returns listOf(readable, sealedReadable)
        assertEquals(100L, ComputeBalancesUseCase(dao, repo, encryption())("g1").single { it.pubkey == "alice" }.net)
    }

    @Test
    fun `missing covered expense key fails when reversing a correction`() = runTest {
        val dao = eventDao()
        val repo = groupRepo()
        val old = makeEvent("e1", type = "expense", uuid = "u1", content = "ciphertext").copy(keyEpoch = 2)
        val correction = makeEvent(
            "e2",
            type = "expense_correction",
            uuid = "u1",
            createdAt = 200,
            content = expenseJson("u1", 200, splits = split("alice" to 100, "bob" to 100))
        )
        installSnapshot(dao, repo, listOf(old, correction), listOf("e1"), "[${balEntry("alice", 50, "INR")}]")
        coEvery { repo.getGroupKeyForEpoch("g1", 2) } returns null

        assertThrows(BalanceUnavailableException::class.java) {
            runBlocking { ComputeBalancesUseCase(dao, repo, encryption())("g1") }
        }
    }

    @Test
    fun `cancellation is not turned into an unavailable balance`() = runTest {
        val dao = eventDao()
        val repo = groupRepo()
        val enc = mockk<GroupEncryption>()
        every { enc.decrypt(any(), any()) } throws kotlinx.coroutines.CancellationException("cancelled")
        coEvery { dao.getEventsByGroup("g1") } returns listOf(
            makeEvent("e1", type = "expense", uuid = "u1", content = "ciphertext")
        )

        assertThrows(kotlinx.coroutines.CancellationException::class.java) {
            runBlocking { ComputeBalancesUseCase(dao, repo, enc)("g1") }
        }
    }

    // --- One ledger read: the snapshot, its coverage and the replay all come from the same list ---

    @Test
    fun `snapshot ties are resolved by canonical id instead of storage order`() = runTest {
        val dao = eventDao()
        val repo = groupRepo()
        coEvery { repo.getById("g1") } returns group()
        val older = makeEvent(
            "a",
            type = "snapshot",
            content = snapJson(
                eventCount = fillerIds.size,
                balances = "[${balEntry("alice", 50, "INR")}]",
                hashes = hashArr(fillerIds)
            )
        )
        val newer = older.copy(
            eventId = "z",
            contentEncrypted = snapJson(
                eventCount = fillerIds.size,
                balances = "[${balEntry("alice", 100, "INR")}]",
                hashes = hashArr(fillerIds)
            )
        )
        installLedger(dao, listOf(newer, older), knownIds = fillerIds)

        assertEquals(100L, ComputeBalancesUseCase(dao, repo, encryption())("g1").single().net)
        coVerify(exactly = 0) { dao.getLatestEventByType(any(), any()) }
        coVerify(exactly = 0) { dao.getEventIds(any()) }
    }

    @Test
    fun `explicit ledger list is used without any repository read`() = runTest {
        val dao = eventDao()
        val repo = groupRepo()
        coEvery { dao.getEventsByGroup("g1") } throws AssertionError("must not read the repository")
        val ledger = listOf(
            makeEvent(
                "e1",
                type = "expense",
                uuid = "u1",
                content = expenseJson(
                    "u1",
                    100,
                    splits = split(
                        "alice" to 50,
                        "bob" to 50
                    )
                )
            )
        )

        val result = ComputeBalancesUseCase(dao, repo, encryption()).computeWithExclusions("g1", ledger)

        assertEquals(50L, result.balances.single { it.pubkey == "alice" }.net)
        coVerify(exactly = 0) { dao.getEventsByGroup(any()) }
    }

    @Test
    fun `useSnapshots false replays every event and ignores a trusted snapshot`() = runTest {
        val dao = eventDao()
        val repo = groupRepo()
        val original = makeEvent(
            "e1",
            type = "expense",
            uuid = "u1",
            createdAt = 10,
            content = expenseJson("u1", 100, splits = split("alice" to 50, "bob" to 50))
        )
        // The snapshot claims 500 for e1 and the fillers; a full replay only knows e1's real 50.
        installSnapshot(dao, repo, listOf(original), listOf("e1"), "[${balEntry("alice", 500, "INR")}]")
        val ledger = dao.getEventsByGroup("g1")
        val useCase = ComputeBalancesUseCase(dao, repo, encryption())

        assertEquals(500L, useCase.computeWithExclusions("g1", ledger).balances.single { it.pubkey == "alice" }.net)
        val replayed = useCase.computeWithExclusions("g1", ledger, useSnapshots = false)
        assertEquals(50L, replayed.balances.single { it.pubkey == "alice" }.net)
    }

    // --- Author-bound expense identity: (author, uuid), never uuid alone ---

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

    // --- Author-scoped settlement identity: (author, id), never id alone ---

    /** Bob fronts 100 split evenly with alice, so alice owes bob 50. */
    private val bobFronts = makeEvent(
        "e_bob",
        type = "expense",
        uuid = "x1",
        pubkey = "bob",
        createdAt = 100,
        content = expenseJson("x1", 100, paidBy = "bob", splits = split("bob" to 50, "alice" to 50), timestamp = 100)
    )

    /** Alice repays bob the 50 she owes, signed by alice, under settlement id `S`. */
    private val aliceRepays = makeEvent(
        "s_alice",
        type = "settlement",
        uuid = "S",
        pubkey = "alice",
        createdAt = 102,
        content = settlementJson("S", "alice", "bob", 50, timestamp = 102)
    )

    /**
     * Carol settles 1 with dave under the same id `S`, signed by carol and valid on its own: carol is a
     * party to it. Its clock is older than alice's, so a uuid-only dedup that trusts canonical order
     * would keep this record and drop alice's.
     */
    private val carolSettlesDave = makeEvent(
        "s_carol",
        type = "settlement",
        uuid = "S",
        pubkey = "carol",
        createdAt = 101,
        content = settlementJson("S", "carol", "dave", 1, timestamp = 101)
    )

    @Test
    fun `a settlement id reused by an unrelated pair cannot erase another pair's repayment`() = runTest {
        // Both settlements are stored APPLIED; only the id collides. Whatever the storage or clock order,
        // both must count: two authors under one id are two settlements.
        val result = computeForEveryOrder(listOf(bobFronts, aliceRepays, carolSettlesDave))

        val n = nets(result)
        assertEquals("alice's repayment squares her with bob", 0L, n["bob"])
        assertEquals("alice's repayment squares her with bob", 0L, n["alice"])
        assertEquals("the unrelated pair's own settlement still counts", 1L, n["carol"])
        assertEquals("the unrelated pair's own settlement still counts", -1L, n["dave"])
    }

    @Test
    fun `the same author re-sending one settlement under a new event id counts once`() = runTest {
        // A retry produces a fresh Nostr event id and clock but the same payload id and amount.
        val retry = aliceRepays.copy(eventId = "s_alice_retry", createdAt = 500)

        val result = computeForEveryOrder(listOf(bobFronts, aliceRepays, retry))

        assertEquals(mapOf("alice" to 0L, "bob" to 0L), nets(result))
    }

    @Test
    fun `the same author's retry and another author's same id are told apart alongside each other`() = runTest {
        val retry = aliceRepays.copy(eventId = "s_alice_retry", createdAt = 500)

        val result = computeForEveryOrder(listOf(bobFronts, aliceRepays, retry, carolSettlesDave))

        assertEquals(mapOf("alice" to 0L, "bob" to 0L, "carol" to 1L, "dave" to -1L), nets(result))
    }

    @Test
    fun `snapshot-covered settlement is keyed by author - same author's replay skipped, other author's applied`() =
        runTest {
            val dao = eventDao()
            val repo = groupRepo()
            val aliceReplay = aliceRepays.copy(eventId = "s_alice_replay", createdAt = 500)
            installSnapshot(
                dao,
                repo,
                events = listOf(bobFronts, aliceRepays, aliceReplay, carolSettlesDave),
                coveredIds = listOf("e_bob", "s_alice"),
                // Snapshot already holds bob's expense and alice's repayment: both at zero.
                balances = """[${balEntry("alice", 0, "INR")},${balEntry("bob", 0, "INR")}]"""
            )

            val result = ComputeBalancesUseCase(dao, repo, encryption()).computeWithExclusions("g1")

            // (alice, S) is covered: her uncovered replay must not re-apply. (carol, S) is a different
            // settlement that the snapshot never saw: it must be applied.
            assertEquals(mapOf("alice" to 0L, "bob" to 0L, "carol" to 1L, "dave" to -1L), nets(result))
        }
}
