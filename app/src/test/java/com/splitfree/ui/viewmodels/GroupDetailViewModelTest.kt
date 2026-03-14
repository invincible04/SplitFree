package com.splitfree.ui.viewmodels

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import com.splitfree.data.nostr.relay.RelayHealthMonitor
import com.splitfree.data.nostr.relay.RelayStatus
import com.splitfree.domain.crypto.EventSigner
import com.splitfree.domain.model.balance.BalanceResult
import com.splitfree.domain.model.group.Group
import com.splitfree.domain.repository.ExpenseRepositoryContract
import com.splitfree.domain.repository.GroupRepositoryContract
import com.splitfree.domain.repository.IdentityContract
import com.splitfree.domain.usecase.expense.ComputeBalancesUseCase
import com.splitfree.domain.usecase.expense.GetExpensesUseCase
import com.splitfree.domain.usecase.expense.SimplifyDebtsUseCase
import com.splitfree.domain.usecase.group.CreateInviteLinkUseCase
import com.splitfree.domain.usecase.group.RotateGroupKeyUseCase
import com.splitfree.domain.usecase.group.UpdateGroupRelaysUseCase
import com.splitfree.domain.util.RelayDefaults
import com.splitfree.ui.components.RelayCheckStatus
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class GroupDetailViewModelTest {
    private val testDispatcher = UnconfinedTestDispatcher()

    private val groupRepo = mockk<GroupRepositoryContract>(relaxed = true)
    private val expenseRepo = mockk<ExpenseRepositoryContract>(relaxed = true)
    private val computeBalances = mockk<ComputeBalancesUseCase>(relaxed = true)
    private val simplifyDebts = mockk<SimplifyDebtsUseCase>(relaxed = true)
    private val rotateGroupKey = mockk<RotateGroupKeyUseCase>(relaxed = true)
    private val identity = mockk<IdentityContract>()
    private val getExpenses = mockk<GetExpensesUseCase>(relaxed = true)
    private val createInviteLink = mockk<CreateInviteLinkUseCase>(relaxed = true)
    private val updateGroupRelays = mockk<UpdateGroupRelaysUseCase>(relaxed = true)
    private val relayHealthMonitor = mockk<RelayHealthMonitor>(relaxed = true)
    private val eventSigner = mockk<EventSigner>(relaxed = true)

    private val pubkey = "aa".repeat(32)
    private val group = Group(
        id = "g1",
        name = "Trip",
        createdBy = pubkey,
        createdAt = 1000L,
        members = listOf(pubkey),
        relays = listOf("wss://relay.one", "wss://relay.two")
    )

    private lateinit var vm: GroupDetailViewModel

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        mockkStatic(android.util.Log::class)
        every { android.util.Log.i(any<String>(), any<String>()) } returns 0
        every { android.util.Log.w(any<String>(), any<String>()) } returns 0
        every { android.util.Log.d(any<String>(), any<String>()) } returns 0
        every { android.util.Log.e(any<String>(), any<String>(), any()) } returns 0

        every { identity.getPublicKeyHex() } returns pubkey
        every { groupRepo.observeById("g1") } returns flowOf(group)
        every { getExpenses.observe("g1") } returns flowOf(emptyList())
        coEvery { computeBalances.computeWithExclusions("g1") } returns
            BalanceResult(emptyList(), emptySet())
        every { simplifyDebts(any()) } returns emptyList()

        vm = GroupDetailViewModel(
            SavedStateHandle(mapOf("groupId" to "g1")),
            groupRepo, expenseRepo, computeBalances, simplifyDebts,
            rotateGroupKey, identity, getExpenses,
            createInviteLink, updateGroupRelays, relayHealthMonitor, eventSigner
        )
    }

    @After
    fun teardown() {
        vm.viewModelScope.cancel()
        Dispatchers.resetMain()
        unmockkStatic(android.util.Log::class)
    }

    @Test
    fun `init populates relays from group`() {
        assertEquals(listOf("wss://relay.one", "wss://relay.two"), vm.uiState.value.relays)
    }

    @Test
    fun `addRelay appends to ui state`() {
        vm.addRelay("wss://new.relay")
        assertTrue("wss://new.relay" in vm.uiState.value.relays)
        assertEquals(3, vm.uiState.value.relays.size)
    }

    @Test
    fun `addRelay deduplicates`() {
        vm.addRelay("wss://relay.one")
        assertEquals(2, vm.uiState.value.relays.size)
    }

    @Test
    fun `removeRelay removes from ui state`() {
        vm.removeRelay("wss://relay.one")
        assertEquals(listOf("wss://relay.two"), vm.uiState.value.relays)
    }

    @Test
    fun `removeRelay does not remove last relay`() {
        vm.removeRelay("wss://relay.one")
        assertEquals(1, vm.uiState.value.relays.size)
        vm.removeRelay("wss://relay.two")
        assertEquals(1, vm.uiState.value.relays.size)
    }

    @Test
    fun `checkRelay sets ONLINE status`() = runTest {
        val url = RelayDefaults.DEFAULT_RELAYS.first()
        every { relayHealthMonitor.statuses } returns
            mapOf(url to RelayStatus(url, online = true, latencyMs = 42))

        vm.checkRelay(url)

        assertEquals(RelayCheckStatus.ONLINE, vm.relayStatuses.value[url])
    }

    @Test
    fun `checkRelay sets OFFLINE status`() = runTest {
        val url = "wss://custom.bad.relay"
        every { relayHealthMonitor.statuses } returns
            mapOf(url to RelayStatus(url, online = false))

        vm.addRelay(url)
        vm.checkRelay(url)

        assertEquals(RelayCheckStatus.OFFLINE, vm.relayStatuses.value[url])
    }

    @Test
    fun `saveRelays calls use case and invokes callback`() = runTest {
        vm.addRelay("wss://new.relay")
        var done = false

        vm.saveRelays { done = true }

        coVerify { updateGroupRelays("g1", vm.uiState.value.relays) }
        assertTrue(done)
    }

    @Test
    fun `saveRelays sets error on failure`() = runTest {
        coEvery { updateGroupRelays(any(), any()) } throws RuntimeException("network")

        vm.saveRelays {}

        assertEquals("network", vm.error.value)
    }

    @Test
    fun `error is null initially`() {
        assertNull(vm.error.value)
    }
}
