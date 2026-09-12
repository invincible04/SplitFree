package com.splitfree.domain.usecase.group

import com.splitfree.domain.model.balance.Balance
import com.splitfree.domain.model.balance.BalanceResult
import com.splitfree.domain.model.group.Group
import com.splitfree.domain.repository.EventRepositoryContract
import com.splitfree.domain.repository.EventSnapshot
import com.splitfree.domain.repository.GroupRepositoryContract
import com.splitfree.domain.repository.IdentityContract
import com.splitfree.domain.usecase.expense.ComputeBalancesUseCase
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ObserveGroupSummariesUseCaseTest {
    private val groupRepo = mockk<GroupRepositoryContract>()
    private val eventRepo = mockk<EventRepositoryContract>()
    private val computeBalances = mockk<ComputeBalancesUseCase>()
    private val identity = mockk<IdentityContract>()

    private val me = "aa".repeat(32)
    private val other = "bb".repeat(32)
    private val goa = group("goa", "Goa trip")
    private val flat = group("flat", "Flatmates")

    private val groups = MutableStateFlow(listOf(goa, flat))
    private val goaEvents = MutableStateFlow<List<EventSnapshot>>(emptyList())
    private val flatEvents = MutableStateFlow<List<EventSnapshot>>(emptyList())

    private lateinit var useCase: ObserveGroupSummariesUseCase

    @Before
    fun setup() {
        mockkStatic(android.util.Log::class)
        every { android.util.Log.i(any<String>(), any<String>()) } returns 0
        every { android.util.Log.w(any<String>(), any<String>()) } returns 0
        every { android.util.Log.e(any<String>(), any<String>()) } returns 0

        every { identity.getPublicKeyHex() } returns me
        every { groupRepo.observeAll() } returns groups
        every { eventRepo.observeEventsByGroup("goa") } returns goaEvents
        every { eventRepo.observeEventsByGroup("flat") } returns flatEvents
        coEvery { computeBalances.computeWithExclusions("goa") } returns
            result(Balance(me, 240000, "INR"), Balance(other, -240000, "INR"), Balance(me, -1500, "USD"))
        coEvery { computeBalances.computeWithExclusions("flat") } returns
            result(Balance(other, 5000, "INR"), Balance("cc".repeat(32), -5000, "INR"))

        useCase = ObserveGroupSummariesUseCase(groupRepo, eventRepo, computeBalances, identity)
    }

    @After
    fun teardown() = unmockkStatic(android.util.Log::class)

    @Test
    fun `emits my balance per currency for every group`() = runTest {
        val emissions = collect()
        runCurrent()

        val latest = emissions.last()
        assertEquals(listOf("goa", "flat"), latest.map { it.group.id })
        assertEquals(mapOf("INR" to 240000L, "USD" to -1500L), latest[0].myBalances)
        assertTrue(latest[0].hasExpenses)
        assertEquals(setOf("INR", "USD"), latest[0].currencies)
    }

    @Test
    fun `member without a balance entry gets no key but the group still lists its currencies`() = runTest {
        val emissions = collect()
        runCurrent()

        val flatSummary = emissions.last()[1]
        assertTrue(flatSummary.myBalances.isEmpty())
        assertNull(flatSummary.myBalances["INR"])
        assertTrue("Group has INR history even though I am not part of it", flatSummary.hasExpenses)
        assertEquals(setOf("INR"), flatSummary.currencies)
    }

    @Test
    fun `first computation is not delayed by the debounce`() = runTest {
        val emissions = collect()
        runCurrent()

        assertEquals("Balances must be available without waiting for the debounce window", 1, emissions.size)
    }

    @Test
    fun `recomputes a group when its events change`() = runTest {
        val emissions = collect()
        runCurrent()
        coEvery { computeBalances.computeWithExclusions("goa") } returns result(Balance(me, 100, "INR"))

        goaEvents.value = listOf(event("goa", "e1"))
        advanceTimeBy(ObserveGroupSummariesUseCase.DEBOUNCE_MS + 1)
        runCurrent()

        assertEquals(mapOf("INR" to 100L), emissions.last()[0].myBalances)
        // The other group's balances were not recomputed.
        coVerify(exactly = 1) { computeBalances.computeWithExclusions("flat") }
    }

    @Test
    fun `a burst of events collapses into one recomputation`() = runTest {
        collect()
        runCurrent()

        goaEvents.value = listOf(event("goa", "e1"))
        advanceTimeBy(20)
        goaEvents.value = listOf(event("goa", "e1"), event("goa", "e2"))
        advanceTimeBy(20)
        goaEvents.value = listOf(event("goa", "e1"), event("goa", "e2"), event("goa", "e3"))
        advanceTimeBy(ObserveGroupSummariesUseCase.DEBOUNCE_MS + 1)
        runCurrent()

        coVerify(exactly = 2) { computeBalances.computeWithExclusions("goa") }
    }

    @Test
    fun `empty group list emits an empty list immediately`() = runTest {
        groups.value = emptyList()
        val emissions = collect()
        runCurrent()

        assertEquals(listOf(emptyList<GroupSummary>()), emissions)
    }

    @Test
    fun `adding a group extends the list`() = runTest {
        val party = group("party", "Badminton")
        val partyEvents = MutableStateFlow<List<EventSnapshot>>(emptyList())
        every { eventRepo.observeEventsByGroup("party") } returns partyEvents
        coEvery { computeBalances.computeWithExclusions("party") } returns result()
        val emissions = collect()
        runCurrent()

        groups.value = listOf(goa, flat, party)
        runCurrent()

        val latest = emissions.last()
        assertEquals(listOf("goa", "flat", "party"), latest.map { it.group.id })
        assertFalse(latest[2].hasExpenses)
        assertTrue(latest[2].currencies.isEmpty())
    }

    @Test
    fun `a failing balance computation degrades that group only`() = runTest {
        coEvery { computeBalances.computeWithExclusions("goa") } throws IllegalStateException("key missing")
        val emissions = collect()
        runCurrent()

        val latest = emissions.last()
        assertTrue(latest[0].myBalances.isEmpty())
        assertFalse(latest[0].hasExpenses)
        assertEquals(setOf("INR"), latest[1].currencies)
    }

    @Test
    fun `groups whose events never arrive do not block the ones that do`() = runTest {
        // A cold flow that completes without emitting would stall combine; the repository contract emits
        // the current (possibly empty) list first, which is what a Room flow does.
        every { eventRepo.observeEventsByGroup("flat") } returns flowOf(emptyList())
        val emissions = collect()
        runCurrent()

        assertEquals(2, emissions.last().size)
    }

    private fun kotlinx.coroutines.test.TestScope.collect(): List<List<GroupSummary>> {
        val emissions = mutableListOf<List<GroupSummary>>()
        backgroundScope.launch { useCase.observe().collect { emissions += it } }
        return emissions
    }

    private fun group(id: String, name: String) = Group(
        id = id,
        name = name,
        createdBy = me,
        createdAt = 1000L,
        members = listOf(me, other),
        relays = listOf("wss://relay.example")
    )

    private fun result(vararg balances: Balance) = BalanceResult(balances.toList(), emptySet())

    private fun event(groupId: String, id: String) = EventSnapshot(
        eventId = id,
        groupId = groupId,
        pubkey = me,
        createdAt = 1000L,
        contentEncrypted = "enc",
        eventType = "expense",
        expenseUuid = id
    )
}
