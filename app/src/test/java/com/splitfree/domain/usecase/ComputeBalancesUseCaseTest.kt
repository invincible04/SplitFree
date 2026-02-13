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

    private fun makeEvent(
        id: String, groupId: String = "g1", pubkey: String = "alice",
        type: String, content: String, uuid: String? = null, createdAt: Long = 1
    ) = EventEntity(
        eventId = id, groupId = groupId, pubkey = pubkey, createdAt = createdAt,
        kind = 30078, contentEncrypted = "", contentDecrypted = content,
        eventType = type, expenseUuid = uuid, sig = "s", receivedAt = 1
    )

    @Test
    fun `computes simple two-person balance`() = runTest {
        val dao = eventDao()
        val repo = groupRepo()
        coEvery { dao.getEventsByGroup("g1") } returns listOf(
            makeEvent("e1", type = "expense", uuid = "u1", content = """{"id":"u1","amount":100,"currency":"INR","description":"test","paid_by":"alice","split_type":"equal","split_among":[{"pubkey":"alice","share":50},{"pubkey":"bob","share":50}],"timestamp":1}""")
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
    fun `deleted expense excluded from balance`() = runTest {
        val dao = eventDao()
        val repo = groupRepo()
        coEvery { dao.getEventsByGroup("g1") } returns listOf(
            makeEvent("e1", type = "expense", uuid = "u1", content = """{"id":"u1","amount":100,"currency":"INR","description":"t","paid_by":"alice","split_type":"equal","split_among":[{"pubkey":"alice","share":50},{"pubkey":"bob","share":50}],"timestamp":1}"""),
            makeEvent("e2", type = "expense_delete", uuid = "u1", content = "{}")
        )
        coEvery { dao.getLatestEventByType("g1", "snapshot") } returns null

        val useCase = ComputeBalancesUseCase(dao, repo)
        val result = useCase.computeWithExclusions("g1")

        assertTrue(result.balances.all { it.net == 0L } || result.balances.isEmpty())
        assertTrue("u1" in result.excludedExpenseUuids)
    }

    @Test
    fun `corrected expense uses latest correction`() = runTest {
        val dao = eventDao()
        val repo = groupRepo()
        coEvery { dao.getEventsByGroup("g1") } returns listOf(
            makeEvent("e1", type = "expense", uuid = "u1", content = """{"id":"u1","amount":100,"currency":"INR","description":"t","paid_by":"alice","split_type":"equal","split_among":[{"pubkey":"alice","share":50},{"pubkey":"bob","share":50}],"timestamp":1}"""),
            makeEvent("e2", type = "expense_correction", uuid = "u1", content = """{"id":"u1c","amount":200,"currency":"INR","description":"t","paid_by":"alice","split_type":"equal","split_among":[{"pubkey":"alice","share":100},{"pubkey":"bob","share":100}],"timestamp":2}""")
        )
        coEvery { dao.getLatestEventByType("g1", "snapshot") } returns null

        val useCase = ComputeBalancesUseCase(dao, repo)
        val balances = useCase("g1")

        val alice = balances.find { it.pubkey == "alice" }
        assertEquals(100L, alice?.net) // corrected amount, not original
    }

    @Test
    fun `settlement reduces debt`() = runTest {
        val dao = eventDao()
        val repo = groupRepo()
        coEvery { dao.getEventsByGroup("g1") } returns listOf(
            makeEvent("e1", type = "expense", uuid = "u1", pubkey = "alice", content = """{"id":"u1","amount":100,"currency":"INR","description":"t","paid_by":"alice","split_type":"equal","split_among":[{"pubkey":"alice","share":50},{"pubkey":"bob","share":50}],"timestamp":1}"""),
            makeEvent("e2", type = "settlement", pubkey = "bob", content = """{"id":"s1","from":"bob","to":"alice","amount":50,"currency":"INR","timestamp":2}""")
        )
        coEvery { dao.getLatestEventByType("g1", "snapshot") } returns null

        val useCase = ComputeBalancesUseCase(dao, repo)
        val balances = useCase("g1")

        assertTrue("All balances should be zero", balances.all { it.net == 0L })
    }

    @Test
    fun `settlement from non-party is ignored`() = runTest {
        val dao = eventDao()
        val repo = groupRepo()
        coEvery { dao.getEventsByGroup("g1") } returns listOf(
            makeEvent("e1", type = "expense", uuid = "u1", pubkey = "alice", content = """{"id":"u1","amount":100,"currency":"INR","description":"t","paid_by":"alice","split_type":"equal","split_among":[{"pubkey":"alice","share":50},{"pubkey":"bob","share":50}],"timestamp":1}"""),
            makeEvent("e2", type = "settlement", pubkey = "charlie", content = """{"id":"s1","from":"bob","to":"alice","amount":50,"currency":"INR","timestamp":2}""")
        )
        coEvery { dao.getLatestEventByType("g1", "snapshot") } returns null

        val useCase = ComputeBalancesUseCase(dao, repo)
        val balances = useCase("g1")

        val alice = balances.find { it.pubkey == "alice" }
        assertEquals(50L, alice?.net) // settlement ignored, debt remains
    }

    @Test
    fun `duplicate settlement IDs are deduplicated`() = runTest {
        val dao = eventDao()
        val repo = groupRepo()
        coEvery { dao.getEventsByGroup("g1") } returns listOf(
            makeEvent("e1", type = "expense", uuid = "u1", pubkey = "alice", content = """{"id":"u1","amount":100,"currency":"INR","description":"t","paid_by":"alice","split_type":"equal","split_among":[{"pubkey":"alice","share":50},{"pubkey":"bob","share":50}],"timestamp":1}"""),
            makeEvent("e2", type = "settlement", pubkey = "bob", content = """{"id":"s1","from":"bob","to":"alice","amount":50,"currency":"INR","timestamp":2}"""),
            makeEvent("e3", type = "settlement", pubkey = "bob", content = """{"id":"s1","from":"bob","to":"alice","amount":50,"currency":"INR","timestamp":3}""")
        )
        coEvery { dao.getLatestEventByType("g1", "snapshot") } returns null

        val useCase = ComputeBalancesUseCase(dao, repo)
        val balances = useCase("g1")

        // Only one settlement should be applied
        assertTrue("All balances should be zero", balances.all { it.net == 0L })
    }

    @Test
    fun `negative split shares are ignored`() = runTest {
        val dao = eventDao()
        val repo = groupRepo()
        coEvery { dao.getEventsByGroup("g1") } returns listOf(
            makeEvent("e1", type = "expense", uuid = "u1", content = """{"id":"u1","amount":100,"currency":"INR","description":"t","paid_by":"alice","split_type":"exact","split_among":[{"pubkey":"alice","share":150},{"pubkey":"bob","share":-50}],"timestamp":1}""")
        )
        coEvery { dao.getLatestEventByType("g1", "snapshot") } returns null

        val useCase = ComputeBalancesUseCase(dao, repo)
        val balances = useCase("g1")

        assertTrue("Negative shares should cause expense to be skipped", balances.isEmpty() || balances.all { it.net == 0L })
    }

    @Test
    fun `events with null decrypted content are skipped`() = runTest {
        val dao = eventDao()
        val repo = groupRepo()
        coEvery { dao.getEventsByGroup("g1") } returns listOf(
            EventEntity("e1", "g1", "alice", 1, 30078, "enc", null, "expense", "u1", "s", receivedAt = 1)
        )
        coEvery { dao.getLatestEventByType("g1", "snapshot") } returns null

        val useCase = ComputeBalancesUseCase(dao, repo)
        val balances = useCase("g1")

        assertTrue(balances.isEmpty())
    }

    @Test
    fun `empty group returns empty balances`() = runTest {
        val dao = eventDao()
        val repo = groupRepo()
        coEvery { dao.getEventsByGroup("g1") } returns emptyList()
        coEvery { dao.getLatestEventByType("g1", "snapshot") } returns null

        val useCase = ComputeBalancesUseCase(dao, repo)
        val balances = useCase("g1")

        assertTrue(balances.isEmpty())
    }

    @Test
    fun `multi-currency balances tracked separately`() = runTest {
        val dao = eventDao()
        val repo = groupRepo()
        coEvery { dao.getEventsByGroup("g1") } returns listOf(
            makeEvent("e1", type = "expense", uuid = "u1", content = """{"id":"u1","amount":100,"currency":"INR","description":"t","paid_by":"alice","split_type":"equal","split_among":[{"pubkey":"alice","share":50},{"pubkey":"bob","share":50}],"timestamp":1}"""),
            makeEvent("e2", type = "expense", uuid = "u2", content = """{"id":"u2","amount":200,"currency":"USD","description":"t","paid_by":"alice","split_type":"equal","split_among":[{"pubkey":"alice","share":100},{"pubkey":"bob","share":100}],"timestamp":2}""")
        )
        coEvery { dao.getLatestEventByType("g1", "snapshot") } returns null

        val useCase = ComputeBalancesUseCase(dao, repo)
        val balances = useCase("g1")

        val aliceInr = balances.find { it.pubkey == "alice" && it.currency == "INR" }
        val aliceUsd = balances.find { it.pubkey == "alice" && it.currency == "USD" }
        assertEquals(50L, aliceInr?.net)
        assertEquals(100L, aliceUsd?.net)
    }
}
