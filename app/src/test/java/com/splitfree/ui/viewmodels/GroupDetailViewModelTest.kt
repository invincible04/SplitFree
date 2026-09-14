package com.splitfree.ui.viewmodels

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import com.splitfree.R
import com.splitfree.data.nostr.relay.RelayHealthMonitor
import com.splitfree.data.nostr.relay.RelayStatus
import com.splitfree.domain.crypto.EventSigner
import com.splitfree.domain.invite.InviteLinkCodec
import com.splitfree.domain.model.balance.Balance
import com.splitfree.domain.model.balance.BalanceResult
import com.splitfree.domain.model.expense.DebtTransaction
import com.splitfree.domain.model.expense.Expense
import com.splitfree.domain.model.expense.ExpenseIdentity
import com.splitfree.domain.model.expense.Settlement
import com.splitfree.domain.model.expense.SplitEntry
import com.splitfree.domain.model.expense.SplitType
import com.splitfree.domain.model.group.Group
import com.splitfree.domain.repository.ExpenseRepositoryContract
import com.splitfree.domain.repository.GroupRepositoryContract
import com.splitfree.domain.repository.IdentityContract
import com.splitfree.domain.usecase.expense.AuthoredExpense
import com.splitfree.domain.usecase.expense.BalanceUnavailableException
import com.splitfree.domain.usecase.expense.ComputeBalancesUseCase
import com.splitfree.domain.usecase.expense.DeleteExpenseUseCase
import com.splitfree.domain.usecase.expense.GetExpensesUseCase
import com.splitfree.domain.usecase.expense.SimplifyDebtsUseCase
import com.splitfree.domain.usecase.group.CreateInviteLinkUseCase
import com.splitfree.domain.usecase.group.RotateGroupKeyUseCase
import com.splitfree.domain.usecase.group.UpdateGroupRelaysUseCase
import com.splitfree.domain.util.RelayDefaults
import com.splitfree.ui.components.RelayCheckStatus
import com.splitfree.ui.components.RelayInfo
import com.splitfree.ui.util.UiMessage
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.slot
import io.mockk.unmockkStatic
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
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
    private val deleteExpense = mockk<DeleteExpenseUseCase>(relaxed = true)
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
        every { getExpenses.observeWithAuthors("g1") } returns flowOf(emptyList())
        coEvery { computeBalances.computeWithExclusions("g1") } returns
            BalanceResult(emptyList(), emptySet())
        every { simplifyDebts(any()) } returns emptyList()

        vm = GroupDetailViewModel(
            SavedStateHandle(mapOf("groupId" to "g1")),
            groupRepo, expenseRepo, computeBalances, simplifyDebts,
            rotateGroupKey, identity, getExpenses, deleteExpense,
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
    fun `isCreator is false until both keys are known`() {
        assertFalse(GroupDetailUiState().isCreator)
        assertFalse(GroupDetailUiState(createdBy = pubkey).isCreator)
        assertTrue(vm.uiState.value.isCreator)
    }

    // --- relay editing happens on a draft; the saved list only changes on save ---

    @Test
    fun `addRelay appends to the draft and leaves saved relays unchanged`() {
        vm.beginRelayEdit()
        vm.addRelay("wss://new.relay")
        assertEquals(listOf("wss://relay.one", "wss://relay.two", "wss://new.relay"), vm.uiState.value.draftRelays)
        assertEquals(listOf("wss://relay.one", "wss://relay.two"), vm.uiState.value.relays)
    }

    @Test
    fun `addRelay deduplicates`() {
        vm.beginRelayEdit()
        vm.addRelay("wss://relay.one")
        assertEquals(2, vm.uiState.value.draftRelays!!.size)
    }

    @Test
    fun `removeRelay removes from the draft`() {
        vm.beginRelayEdit()
        vm.removeRelay("wss://relay.one")
        assertEquals(listOf("wss://relay.two"), vm.uiState.value.draftRelays)
        assertEquals(listOf("wss://relay.one", "wss://relay.two"), vm.uiState.value.relays)
    }

    @Test
    fun `removeRelay does not remove last relay`() {
        vm.beginRelayEdit()
        vm.removeRelay("wss://relay.one")
        assertEquals(1, vm.uiState.value.draftRelays!!.size)
        vm.removeRelay("wss://relay.two")
        assertEquals(1, vm.uiState.value.draftRelays!!.size)
    }

    @Test
    fun `beginRelayEdit copies saved relays and does not reset an open draft`() {
        vm.beginRelayEdit()
        assertEquals(vm.uiState.value.relays, vm.uiState.value.draftRelays)
        vm.addRelay("wss://new.relay")
        vm.beginRelayEdit()
        assertTrue("wss://new.relay" in vm.uiState.value.draftRelays!!)
    }

    @Test
    fun `cancelRelayEdit discards the draft and reverts to saved relays`() = runTest {
        vm.beginRelayEdit()
        vm.addRelay("wss://new.relay")
        vm.removeRelay("wss://relay.one")

        vm.cancelRelayEdit()

        assertNull(vm.uiState.value.draftRelays)
        assertEquals(listOf("wss://relay.one", "wss://relay.two"), vm.uiState.value.relays)
        coVerify(exactly = 0) { updateGroupRelays(any(), any()) }
    }

    @Test
    fun `saveRelays persists the draft and closes it`() = runTest {
        vm.beginRelayEdit()
        vm.addRelay("wss://new.relay")
        var done = false

        vm.saveRelays { done = true }

        coVerify { updateGroupRelays("g1", listOf("wss://relay.one", "wss://relay.two", "wss://new.relay")) }
        assertTrue(done)
        assertNull(vm.uiState.value.draftRelays)
    }

    @Test
    fun `a failed save keeps the draft open for another attempt`() = runTest {
        coEvery { updateGroupRelays(any(), any()) } throws RuntimeException("network")
        vm.beginRelayEdit()
        vm.addRelay("wss://new.relay")

        vm.saveRelays {}

        assertEquals(UiMessage.Raw("network"), vm.error.value)
        assertEquals(listOf("wss://relay.one", "wss://relay.two", "wss://new.relay"), vm.uiState.value.draftRelays)
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

        vm.beginRelayEdit()
        vm.addRelay(url)
        vm.checkRelay(url)

        assertEquals(RelayCheckStatus.OFFLINE, vm.relayStatuses.value[url])
    }

    @Test
    fun `saveRelays without an open draft persists the saved list`() = runTest {
        var done = false

        vm.saveRelays { done = true }

        coVerify { updateGroupRelays("g1", listOf("wss://relay.one", "wss://relay.two")) }
        assertTrue(done)
    }

    @Test
    fun `saveRelays sets error on failure`() = runTest {
        coEvery { updateGroupRelays(any(), any()) } throws RuntimeException("network")

        vm.saveRelays {}

        assertEquals(UiMessage.Raw("network"), vm.error.value)
    }

    // --- settlements are validated against freshly computed debts ---

    private val other = "bb".repeat(32)
    private val debt = DebtTransaction(from = other, to = pubkey, amount = 5000, currency = "INR")

    private fun currentDebts(vararg debts: DebtTransaction) {
        val balances = listOf(Balance(pubkey, 5000, "INR"), Balance(other, -5000, "INR"))
        coEvery { computeBalances.computeWithExclusions("g1") } returns BalanceResult(balances, emptySet())
        every { simplifyDebts(balances) } returns debts.toList()
    }

    @Test
    fun `recordSettlement stores a settlement for a debt that still exists`() = runTest {
        currentDebts(debt)
        coEvery { groupRepo.getById("g1") } returns group
        val stored = slot<Settlement>()
        coEvery { expenseRepo.addSettlement(capture(stored), "g1") } returns Unit

        vm.recordSettlement(debt)

        assertEquals(other, stored.captured.from)
        assertEquals(pubkey, stored.captured.to)
        assertEquals(5000L, stored.captured.amount)
        assertEquals("INR", stored.captured.currency)
        assertNull(vm.error.value)
    }

    @Test
    fun `recordSettlement rejects a debt whose amount changed since the dialog opened`() = runTest {
        currentDebts(debt.copy(amount = 4000))
        coEvery { groupRepo.getById("g1") } returns group

        vm.recordSettlement(debt)

        coVerify(exactly = 0) { expenseRepo.addSettlement(any(), any()) }
        assertEquals(UiMessage.Res(R.string.settlement_stale), vm.error.value)
        assertEquals(listOf(debt.copy(amount = 4000)), vm.uiState.value.debts)
    }

    @Test
    fun `recordSettlement rejects a debt that no longer exists`() = runTest {
        currentDebts()
        coEvery { groupRepo.getById("g1") } returns group

        vm.recordSettlement(debt)

        coVerify(exactly = 0) { expenseRepo.addSettlement(any(), any()) }
        assertEquals(UiMessage.Res(R.string.settlement_stale), vm.error.value)
        // The guard is released: a valid settlement afterwards still goes through.
        currentDebts(debt)
        vm.recordSettlement(debt)
        coVerify(exactly = 1) { expenseRepo.addSettlement(any(), "g1") }
    }

    @Test
    fun `error is null initially`() {
        assertNull(vm.error.value)
    }

    // --- removeMember in-flight guard ---

    @Test
    fun `removeMember ignores a second tap while a rotation is in flight`() = runTest {
        val gate = CompletableDeferred<Unit>()
        coEvery { rotateGroupKey("g1", "bb".repeat(32)) } coAnswers { gate.await() }

        vm.removeMember("bb".repeat(32))
        vm.removeMember("bb".repeat(32))
        coVerify(exactly = 1) { rotateGroupKey("g1", any()) }

        gate.complete(Unit)
        // Once the first rotation finished, removals are accepted again.
        vm.removeMember("cc".repeat(32))
        coVerify(exactly = 1) { rotateGroupKey("g1", "cc".repeat(32)) }
    }

    @Test
    fun `removeMember surfaces the use case error and releases the guard`() = runTest {
        coEvery { rotateGroupKey(any(), any()) } throws IllegalStateException("Group changed during rotation")

        vm.removeMember("bb".repeat(32))

        assertEquals(UiMessage.Raw("Group changed during rotation"), vm.error.value)
        vm.removeMember("bb".repeat(32))
        coVerify(exactly = 2) { rotateGroupKey("g1", "bb".repeat(32)) }
    }

    // --- deleting an expense ---

    private fun expense(id: String, paidBy: String = pubkey) = Expense(
        id = id,
        amount = 1000,
        currency = "INR",
        description = id,
        paidBy = paidBy,
        splitType = SplitType.EQUAL,
        splitAmong = listOf(SplitEntry(pubkey, 500), SplitEntry(other, 500)),
        timestamp = 1000
    )

    @Test
    fun `visible expenses carry the author of their original event`() = runTest {
        every { getExpenses.observeWithAuthors("g1") } returns flowOf(
            listOf(AuthoredExpense(expense("mine"), pubkey), AuthoredExpense(expense("theirs", paidBy = other), other))
        )
        coEvery { computeBalances.computeWithExclusions("g1") } returns
            BalanceResult(emptyList(), setOf(ExpenseIdentity(pubkey, "excluded")))

        val fresh = newViewModel()

        assertEquals(listOf("mine", "theirs"), fresh.uiState.value.expenses.map { it.expense.id })
        assertEquals(listOf(pubkey, other), fresh.uiState.value.expenses.map { it.authorPubkey })
        assertTrue(fresh.uiState.value.authoredByMe(ExpenseIdentity(pubkey, "mine")))
        assertFalse(fresh.uiState.value.authoredByMe(ExpenseIdentity(other, "theirs")))
        assertFalse(fresh.uiState.value.authoredByMe(ExpenseIdentity(pubkey, "unknown")))
        fresh.viewModelScope.cancel()
    }

    @Test
    fun `excluded expenses are dropped from the authored ledger`() = runTest {
        every { getExpenses.observeWithAuthors("g1") } returns flowOf(
            listOf(AuthoredExpense(expense("kept"), pubkey), AuthoredExpense(expense("excluded"), pubkey))
        )
        coEvery { computeBalances.computeWithExclusions("g1") } returns
            BalanceResult(emptyList(), setOf(ExpenseIdentity(pubkey, "excluded")))

        val fresh = newViewModel()

        assertEquals(listOf("kept"), fresh.uiState.value.expenses.map { it.expense.id })
        assertEquals(listOf(ExpenseIdentity(pubkey, "kept")), fresh.uiState.value.expenses.map { it.identity })
        fresh.viewModelScope.cancel()
    }

    @Test
    fun `authoredByMe is false while my key is unknown`() {
        assertFalse(
            GroupDetailUiState(
                expenses = listOf(AuthoredExpense(expense("e"), ""))
            ).authoredByMe(ExpenseIdentity("", "e"))
        )
    }

    @Test
    fun `deleteExpense publishes the deletion and confirms with a message`() = runTest {
        vm.deleteExpense(ExpenseIdentity(pubkey, "exp1"))

        coVerify(exactly = 1) { deleteExpense("g1", "exp1", expectedAuthorPubkey = pubkey) }
        assertEquals(UiMessage.Res(R.string.expense_deleted), vm.message.value)
        assertNull(vm.error.value)

        vm.clearMessage()
        assertNull(vm.message.value)
    }

    @Test
    fun `deleteExpense surfaces the use case error and releases the guard`() = runTest {
        coEvery { deleteExpense("g1", "exp1", expectedAuthorPubkey = pubkey) } throws
            IllegalStateException("Only the creator can delete this expense")

        vm.deleteExpense(ExpenseIdentity(pubkey, "exp1"))

        assertEquals(UiMessage.Raw("Only the creator can delete this expense"), vm.error.value)
        assertNull(vm.message.value)
        vm.deleteExpense(ExpenseIdentity(pubkey, "exp1"))
        coVerify(exactly = 2) { deleteExpense("g1", "exp1", expectedAuthorPubkey = pubkey) }
    }

    @Test
    fun `deleteExpense ignores a second tap while the first is in flight`() = runTest {
        val gate = CompletableDeferred<Unit>()
        coEvery { deleteExpense("g1", "exp1", expectedAuthorPubkey = pubkey) } coAnswers { gate.await() }

        vm.deleteExpense(ExpenseIdentity(pubkey, "exp1"))
        vm.deleteExpense(ExpenseIdentity(pubkey, "exp1"))
        coVerify(exactly = 1) { deleteExpense("g1", any(), expectedAuthorPubkey = pubkey) }

        gate.complete(Unit)
        vm.deleteExpense(ExpenseIdentity(pubkey, "exp2"))
        coVerify(exactly = 1) { deleteExpense("g1", "exp2", expectedAuthorPubkey = pubkey) }
    }

    @Test
    fun `colliding expenses keep distinct identities and delete only the selected own record`() = runTest {
        val theirs = AuthoredExpense(expense("shared", paidBy = pubkey), other)
        val mine = AuthoredExpense(expense("shared", paidBy = other), pubkey)
        every { getExpenses.observeWithAuthors("g1") } returns flowOf(listOf(theirs, mine))
        val fresh = newViewModel()

        assertEquals(listOf(theirs, mine), fresh.uiState.value.expenses)
        assertFalse(fresh.uiState.value.authoredByMe(theirs.identity))
        assertTrue(fresh.uiState.value.authoredByMe(mine.identity))
        fresh.deleteExpense(theirs.identity)
        coVerify(exactly = 0) { deleteExpense(any(), any(), any(), any()) }
        fresh.deleteExpense(mine.identity)
        coVerify(exactly = 1) { deleteExpense("g1", "shared", expectedAuthorPubkey = pubkey) }
        fresh.viewModelScope.cancel()
    }

    @Test
    fun `exclusion removes only the matching author even when another record shares its uuid`() = runTest {
        val theirs = AuthoredExpense(expense("shared"), other)
        val mine = AuthoredExpense(expense("shared"), pubkey)
        every { getExpenses.observeWithAuthors("g1") } returns flowOf(listOf(theirs, mine))
        coEvery { computeBalances.computeWithExclusions("g1") } returns
            BalanceResult(emptyList(), setOf(theirs.identity))
        val fresh = newViewModel()

        assertEquals(listOf(mine), fresh.uiState.value.expenses)
        assertTrue(fresh.uiState.value.authoredByMe(mine.identity))
        fresh.viewModelScope.cancel()
    }

    @Test
    fun `delete rechecks the active signer rather than trusting cached ui ownership`() = runTest {
        every { identity.getPublicKeyHex() } returns other

        vm.deleteExpense(ExpenseIdentity(pubkey, "shared"))

        coVerify(exactly = 0) { deleteExpense(any(), any(), any(), any()) }
        assertNotNull(vm.error.value)
    }

    // --- observation failure boundaries ---

    private fun newViewModel() = GroupDetailViewModel(
        SavedStateHandle(mapOf("groupId" to "g1")),
        groupRepo, expenseRepo, computeBalances, simplifyDebts,
        rotateGroupKey, identity, getExpenses, deleteExpense,
        createInviteLink, updateGroupRelays, relayHealthMonitor, eventSigner
    )

    @Test
    fun `failing group query surfaces an error instead of killing the scope`() = runTest {
        every { groupRepo.observeById("g1") } returns flow { throw IllegalStateException("database corrupt") }

        val failing = newViewModel()

        assertEquals(UiMessage.Raw("database corrupt"), failing.error.value)
        failing.viewModelScope.cancel()
    }

    @Test
    fun `unreadable identity while applying a group update surfaces an error and withholds balances`() = runTest {
        every { identity.getPublicKeyHex() } throws IllegalStateException("keystore unavailable")

        val failing = newViewModel()

        assertEquals(UiMessage.Raw("keystore unavailable"), failing.error.value)
        // Debts cannot be attributed without my key, so a successful computation does not make them available.
        assertFalse(failing.uiState.value.balancesAvailable)
        failing.viewModelScope.cancel()
    }

    @Test
    fun `failing expense query surfaces an error instead of killing the scope`() = runTest {
        every { getExpenses.observeWithAuthors("g1") } returns flow { throw IllegalStateException("disk io error") }

        val failing = newViewModel()

        assertEquals(UiMessage.Raw("disk io error"), failing.error.value)
        assertFalse(failing.uiState.value.balancesAvailable)
        failing.viewModelScope.cancel()
    }

    // --- unavailable balances and retry ---

    private val openDebt = DebtTransaction(pubkey, other, 100L, "INR")

    @Test
    fun `balances start available and stay available after a successful computation`() = runTest {
        every { simplifyDebts(any()) } returns listOf(openDebt)

        val fresh = newViewModel()

        assertTrue(fresh.uiState.value.balancesAvailable)
        assertEquals(listOf(openDebt), fresh.uiState.value.debts)
        fresh.viewModelScope.cancel()
    }

    @Test
    fun `balance failure withholds debts, keeps the history and explicit retry restores them`() = runTest {
        val history = listOf(AuthoredExpense(expense("kept"), pubkey))
        every { getExpenses.observeWithAuthors("g1") } returns flowOf(history)
        every { simplifyDebts(any()) } returns listOf(openDebt)
        coEvery { computeBalances.computeWithExclusions("g1") } throws BalanceUnavailableException("missing key")

        val recovering = newViewModel()

        assertFalse(recovering.uiState.value.balancesAvailable)
        assertTrue(recovering.uiState.value.debts.isEmpty())
        assertEquals(history, recovering.uiState.value.expenses)
        assertEquals(UiMessage.Raw("missing key"), recovering.error.value)

        coEvery { computeBalances.computeWithExclusions("g1") } returns BalanceResult(emptyList(), emptySet())
        recovering.retryBalances()

        assertNull(recovering.error.value)
        assertTrue(recovering.uiState.value.balancesAvailable)
        assertEquals(listOf(openDebt), recovering.uiState.value.debts)
        recovering.viewModelScope.cancel()
    }

    @Test
    fun `retry after a persisting failure stays unavailable`() = runTest {
        coEvery { computeBalances.computeWithExclusions("g1") } throws BalanceUnavailableException("missing key")
        val failing = newViewModel()

        failing.retryBalances()

        assertFalse(failing.uiState.value.balancesAvailable)
        assertEquals(UiMessage.Raw("missing key"), failing.error.value)
        failing.viewModelScope.cancel()
    }

    @Test
    fun `retry resubscribes a dead expense observation`() = runTest {
        every { getExpenses.observeWithAuthors("g1") } returns flow { throw IllegalStateException("disk io error") }
        every { simplifyDebts(any()) } returns listOf(openDebt)
        val recovering = newViewModel()
        assertFalse(recovering.uiState.value.balancesAvailable)

        every { getExpenses.observeWithAuthors("g1") } returns flowOf(emptyList())
        recovering.retryBalances()

        assertTrue(recovering.uiState.value.balancesAvailable)
        assertEquals(listOf(openDebt), recovering.uiState.value.debts)
        recovering.viewModelScope.cancel()
    }

    @Test
    fun `group observation recovery restores hidden debts rather than a false zero`() = runTest {
        every { simplifyDebts(any()) } returns listOf(openDebt)
        val groups = kotlinx.coroutines.flow.MutableStateFlow<Group?>(group)
        every { groupRepo.observeById("g1") } returns groups
        val observed = newViewModel()
        assertTrue(observed.uiState.value.balancesAvailable)

        every { identity.getPublicKeyHex() } throws IllegalStateException("key unavailable")
        groups.value = group.copy(name = "renamed")
        assertFalse(observed.uiState.value.balancesAvailable)
        assertEquals("Debts stay in state, hidden by the flag", listOf(openDebt), observed.uiState.value.debts)

        every { identity.getPublicKeyHex() } returns pubkey
        groups.value = group.copy(name = "recovered")
        assertTrue(observed.uiState.value.balancesAvailable)
        assertEquals(listOf(openDebt), observed.uiState.value.debts)
        observed.viewModelScope.cancel()
    }

    @Test
    fun `retry cancels an in-flight balance computation`() = runTest {
        var cancelled = false
        val computing = CompletableDeferred<Unit>()
        coEvery { computeBalances.computeWithExclusions("g1") } coAnswers {
            computing.complete(Unit)
            try {
                CompletableDeferred<Unit>().await()
                BalanceResult(emptyList(), emptySet())
            } finally {
                cancelled = true
            }
        }
        val observed = newViewModel()
        computing.await()
        every { getExpenses.observeWithAuthors("g1") } returns flow { kotlinx.coroutines.awaitCancellation() }

        observed.retryBalances()

        assertTrue(cancelled)
        observed.viewModelScope.cancel()
    }

    @Test
    fun `relay check failure marks unknown relay offline and surfaces an error`() = runTest {
        val url = "wss://custom.bad.relay"
        coEvery { relayHealthMonitor.checkRelays(any()) } throws java.io.IOException("socket closed")

        vm.beginRelayEdit()
        vm.addRelay(url)
        vm.checkRelay(url)

        assertEquals(RelayCheckStatus.OFFLINE, vm.relayStatuses.value[url])
        assertEquals(UiMessage.Res(R.string.relay_check_failed, "custom.bad.relay"), vm.error.value)
        assertTrue(url in vm.uiState.value.draftRelays!!)
    }

    @Test
    fun `relay check failure marks a default relay offline and surfaces an error`() = runTest {
        val url = RelayDefaults.DEFAULT_RELAYS.first()
        coEvery { relayHealthMonitor.checkRelays(any()) } throws java.io.IOException("socket closed")

        vm.checkRelay(url)

        assertEquals(RelayCheckStatus.OFFLINE, vm.relayStatuses.value[url])
        assertEquals(UiMessage.Res(R.string.relay_check_failed, url.removePrefix("wss://")), vm.error.value)
    }

    // --- statuses are informational; only the user changes the list ---

    @Test
    fun `an unreachable default relay is OFFLINE without an error`() = runTest {
        val url = RelayDefaults.DEFAULT_RELAYS.first()
        every { relayHealthMonitor.statuses } returns mapOf(url to RelayStatus(url, online = false))

        vm.checkRelay(url)

        assertEquals(RelayCheckStatus.OFFLINE, vm.relayStatuses.value[url])
        assertNull(vm.error.value)
    }

    @Test
    fun `a known relay that answers NIP-11 is ONLINE without a write round-trip`() = runTest {
        val url = RelayDefaults.KNOWN_RELAYS.last()
        every { relayHealthMonitor.statuses } returns mapOf(url to RelayStatus(url, online = true, latencyMs = 30))

        vm.checkRelay(url)

        assertEquals(RelayCheckStatus.ONLINE, vm.relayStatuses.value[url])
        assertEquals(RelayInfo(latencyMs = 30), vm.relayInfo.value[url])
        coVerify(exactly = 0) { relayHealthMonitor.verifyRelayRoundTrip(any(), any()) }
    }

    @Test
    fun `a custom relay that fails the round-trip is REJECTED and stays in the draft without an error`() = runTest {
        val url = "wss://custom.relay"
        every { relayHealthMonitor.statuses } returns mapOf(url to RelayStatus(url, online = true, latencyMs = 30))
        coEvery { relayHealthMonitor.verifyRelayRoundTrip(url, any()) } returns false

        vm.beginRelayEdit()
        vm.addRelay(url)
        vm.checkRelay(url)

        assertEquals(RelayCheckStatus.REJECTED, vm.relayStatuses.value[url])
        assertNull(vm.error.value)
        assertTrue(url in vm.uiState.value.draftRelays!!)
    }

    @Test
    fun `a custom relay that passes the round-trip is ONLINE`() = runTest {
        val url = "wss://custom.relay"
        every { relayHealthMonitor.statuses } returns mapOf(url to RelayStatus(url, online = true, latencyMs = 30))
        coEvery { relayHealthMonitor.verifyRelayRoundTrip(url, any()) } returns true

        vm.checkRelay(url)

        assertEquals(RelayCheckStatus.ONLINE, vm.relayStatuses.value[url])
    }

    // --- invite-link budget and reset ---

    @Test
    fun `addRelay ignores the eleventh relay`() {
        vm.beginRelayEdit()
        (1..8).forEach { vm.addRelay("wss://custom$it.relay") }
        assertEquals(InviteLinkCodec.MAX_RELAYS, vm.uiState.value.draftRelays!!.size)

        vm.addRelay("wss://one.too.many")

        assertEquals(InviteLinkCodec.MAX_RELAYS, vm.uiState.value.draftRelays!!.size)
        assertFalse("wss://one.too.many" in vm.uiState.value.draftRelays!!)
    }

    @Test
    fun `addRelay ignores a relay that overflows the custom byte budget`() {
        vm.beginRelayEdit()
        vm.addRelay("wss://" + "a".repeat(200) + ".example")
        vm.addRelay("wss://" + "b".repeat(60) + ".example")

        assertEquals(3, vm.uiState.value.draftRelays!!.size)
    }

    @Test
    fun `resetRelays makes the draft the defaults and checks the ones not yet online`() = runTest {
        val online = RelayDefaults.DEFAULT_RELAYS.first()
        every { relayHealthMonitor.statuses } returns mapOf(online to RelayStatus(online, online = true, latencyMs = 5))
        vm.checkRelay(online)

        vm.resetRelays()

        assertEquals(RelayDefaults.DEFAULT_RELAYS, vm.uiState.value.draftRelays)
        assertEquals(listOf("wss://relay.one", "wss://relay.two"), vm.uiState.value.relays)
        coVerify(exactly = 1) { relayHealthMonitor.checkRelays(listOf(online)) }
        RelayDefaults.DEFAULT_RELAYS.drop(1).forEach { url ->
            coVerify(exactly = 1) { relayHealthMonitor.checkRelays(listOf(url)) }
        }
    }

    @Test
    fun `resetRelays replaces an open draft and cancel still reverts`() = runTest {
        vm.beginRelayEdit()
        vm.addRelay("wss://new.relay")

        vm.resetRelays()
        assertEquals(RelayDefaults.DEFAULT_RELAYS, vm.uiState.value.draftRelays)

        vm.cancelRelayEdit()
        assertNull(vm.uiState.value.draftRelays)
        assertEquals(listOf("wss://relay.one", "wss://relay.two"), vm.uiState.value.relays)
        coVerify(exactly = 0) { updateGroupRelays(any(), any()) }
    }
}
