package com.splitfree.ui.viewmodels

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import com.splitfree.R
import com.splitfree.domain.model.expense.Expense
import com.splitfree.domain.model.expense.SplitEntry
import com.splitfree.domain.model.expense.SplitType
import com.splitfree.domain.model.group.Group
import com.splitfree.domain.repository.ExpenseRepositoryContract
import com.splitfree.domain.repository.GroupRepositoryContract
import com.splitfree.domain.repository.IdentityContract
import com.splitfree.domain.usecase.expense.AddExpenseUseCase
import com.splitfree.domain.usecase.expense.AuthoredExpense
import com.splitfree.domain.usecase.expense.CorrectExpenseUseCase
import com.splitfree.domain.usecase.expense.GetExpensesUseCase
import com.splitfree.ui.util.UiMessage
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import java.io.IOException
import java.util.Locale
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
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
class AddExpenseViewModelTest {
    private val groupRepo = mockk<GroupRepositoryContract>()
    private val expenseRepo = mockk<ExpenseRepositoryContract>()
    private val getExpenses = mockk<GetExpensesUseCase>()
    private val identity = mockk<IdentityContract>()
    private val instances = mutableListOf<AddExpenseViewModel>()
    private val commands = mutableListOf<Expense>()
    private val corrections = mutableListOf<Pair<String, Expense>>()
    private val saved = mutableMapOf<String, Expense>()
    private val dispatcher = UnconfinedTestDispatcher()
    private val group =
        Group(
            "g1",
            "Trip",
            createdBy = "a",
            createdAt = 1L,
            members = listOf("b", "a"),
            relays = emptyList(),
            memberNames = mapOf(
                "a" to "Alice",
                "b" to "Bob"
            )
        )
    private val groups = MutableStateFlow<Group?>(group)
    private lateinit var initialLocale: Locale
    private var writeExpense: suspend (Expense) -> Unit = { saved[it.id] = it }

    @Before
    fun setup() {
        initialLocale = Locale.getDefault()
        Locale.setDefault(Locale.US)
        Dispatchers.setMain(dispatcher)
        every { identity.getPublicKeyHex() } returns "a"
        coEvery { groupRepo.getById("g1") } coAnswers { groups.value }
        every { groupRepo.observeById("g1") } returns groups
        coEvery { expenseRepo.getSavedExpense("g1", any(), any()) } coAnswers { saved[secondArg<String>()] }
        coEvery { expenseRepo.addExpense(any(), "g1", "a") } coAnswers {
            val expense = firstArg<Expense>()
            commands += expense
            writeExpense(expense)
        }
        coEvery { expenseRepo.correctExpense(any(), any(), "g1") } coAnswers {
            corrections += firstArg<String>() to secondArg<Expense>()
        }
    }

    @After
    fun teardown() {
        instances.forEach { it.viewModelScope.cancel() }
        Dispatchers.resetMain()
        Locale.setDefault(initialLocale)
    }

    @Test
    fun `defaults are self payer all members equal and INR with no dirty draft`() {
        val state = create().uiState.value
        assertFalse(state.loading)
        assertTrue(state.editable)
        assertEquals("Trip", state.groupName)
        assertEquals("a", state.myPubkey)
        assertEquals("a", state.paidBy)
        assertEquals(setOf("a", "b"), state.participants)
        assertEquals("Alice", state.memberNames["a"])
        assertEquals("INR", state.currency)
        assertEquals(SplitType.EQUAL, state.splitType)
        assertFalse(state.dirty)
        assertNull(state.amountError)
        assertNull(state.descriptionError)
    }

    @Test
    fun `currency input is capped at three characters and uppercased`() {
        val vm = create()
        vm.updateCurrency("usdx")
        assertEquals("USD", vm.uiState.value.currency)
        vm.updateCurrency("eur")
        assertEquals("EUR", vm.uiState.value.currency)
        vm.updateCurrency("kwd")
        assertEquals("KWD", vm.uiState.value.currency)
    }

    @Test
    fun `exact 100 rupees 50 each and category reach real usecase`() = runTest {
        val vm = create().apply {
            validExpense()
            exact("50", "50")
            updateCategory("Food")
        }
        vm.submit()
        val expense = commands.single()
        assertEquals(10000L, expense.amount)
        assertEquals(listOf(SplitEntry("a", 5000), SplitEntry("b", 5000)), expense.splitAmong)
        assertEquals("Food", expense.category)
        assertEquals("Lunch", expense.description)
        assertEquals(expense.id, UUID.fromString(expense.id).toString())
        assertTrue(expense.timestamp > 0)
        assertTrue(vm.uiState.value.saved)
        assertFalse(vm.uiState.value.dirty)
        assertFalse(vm.uiState.value.saving)
    }

    @Test
    fun `draft restoration retains every mode input selections and stable command`() = runTest {
        val handle = handle()
        val first = create(handle).apply {
            validExpense()
            updateCurrency("KWD")
            updateAmount("100.125")
            updatePayer("b")
            updateCategory("Food")
            exact("50.001", "50.124")
            updateSplitType(SplitType.PERCENTAGE)
            updateMemberInput("a", "12.5")
            updateMemberInput("b", "87.5")
            updateSplitType(SplitType.SHARES)
            updateMemberInput("a", "0.5")
            updateMemberInput("b", "1.5")
            toggleParticipant("b")
        }
        val before = first.uiState.value
        val serialized = handle.get<String>("expenseDraft")!!
        assertTrue(serialized.contains("expenseId"))
        assertTrue(serialized.contains("createdAt"))
        assertTrue(serialized.contains("localeTag"))
        first.viewModelScope.cancel()
        val restored = create(restore(handle))
        assertEquals(before, restored.uiState.value)
        restored.updateSplitType(SplitType.EXACT)
        assertEquals(mapOf("a" to "50.001", "b" to "50.124"), restored.uiState.value.memberInputs)
        restored.updateSplitType(SplitType.PERCENTAGE)
        assertEquals(mapOf("a" to "12.5", "b" to "87.5"), restored.uiState.value.memberInputs)
        restored.updateSplitType(SplitType.SHARES)
        assertEquals(mapOf("a" to "0.5", "b" to "1.5"), restored.uiState.value.memberInputs)
        restored.toggleParticipant("b")
        restored.submit()
        assertEquals(100125L, commands.single().amount)
        assertTrue(serialized.contains(commands.single().id))
        assertTrue(serialized.contains(commands.single().timestamp.toString()))
    }

    @Test
    fun `restored decimal meaning stays pinned across locale change`() = runTest {
        Locale.setDefault(Locale.GERMANY)
        val handle = handle()
        val first = create(handle).apply {
            validExpense()
            updateAmount("100,12")
        }
        first.viewModelScope.cancel()
        Locale.setDefault(Locale.US)
        val restored = create(restore(handle))
        restored.submit()
        assertEquals(10012L, commands.single().amount)
    }

    @Test
    fun `restore checks durable save before exposing editing or loading group`() = runTest {
        val handle = handle()
        create(handle).apply {
            validExpense()
            viewModelScope.cancel()
        }
        val status = CompletableDeferred<Expense?>()
        coEvery { expenseRepo.getSavedExpense("g1", any(), any()) } coAnswers { status.await() }
        val restored = create(restore(handle))
        assertTrue(restored.uiState.value.loading)
        assertFalse(restored.uiState.value.editable)
        restored.updateAmount("900")
        restored.submit()
        assertEquals("100", restored.uiState.value.amount)
        assertTrue(commands.isEmpty())
        coVerify(exactly = 1) { groupRepo.getById("g1") }
        status.complete(null)
        assertTrue(restored.uiState.value.editable)
    }

    @Test
    fun `process death after commit recognizes saved draft without repeat command`() = runTest {
        val handle = handle()
        val first = create(handle).apply { validExpense() }
        writeExpense = {
            saved[it.id] = it
            throw CancellationException("process ended after commit")
        }
        first.submit()
        first.viewModelScope.cancel()
        val restored = create(restore(handle))
        assertTrue(restored.uiState.value.saved)
        assertFalse(restored.uiState.value.editable)
        restored.submit()
        assertEquals(1, commands.size)
    }

    @Test
    fun `failed save can retry with same command ID and timestamp`() = runTest {
        val vm = create().apply { validExpense() }
        writeExpense = { throw IOException("disk full") }
        vm.submit()
        assertEquals(UiMessage.Raw("disk full"), vm.uiState.value.error)
        assertFalse(vm.uiState.value.saved)
        assertTrue(vm.uiState.value.editable)
        writeExpense = { saved[it.id] = it }
        vm.submit()
        assertEquals(2, commands.size)
        assertEquals(commands[0], commands[1])
        assertTrue(vm.uiState.value.saved)
    }

    @Test
    fun `committed expense despite thrown response becomes saved instead of retry`() = runTest {
        val vm = create().apply { validExpense() }
        writeExpense = {
            saved[it.id] = it
            throw IOException("response lost")
        }
        vm.submit()
        assertTrue(vm.uiState.value.saved)
        assertNull(vm.uiState.value.error)
        vm.submit()
        assertEquals(1, commands.size)
    }

    @Test
    fun `ambiguous failure locks all edits until durable status retry succeeds`() = runTest {
        val vm = create().apply { validExpense() }
        writeExpense = { throw IOException("write interrupted") }
        coEvery { expenseRepo.getSavedExpense("g1", any(), any()) } throws IOException("read unavailable")
        vm.submit()
        val before = vm.uiState.value
        assertFalse(before.editable)
        assertNotNull(before.loadingError)
        vm.updateAmount("500")
        vm.updateDescription("Changed")
        vm.updateCategory("Other")
        vm.submit()
        assertEquals(before, vm.uiState.value)
        coEvery { expenseRepo.getSavedExpense("g1", any(), any()) } returns null
        vm.retryLoad()
        assertTrue(vm.uiState.value.editable)
        vm.updateAmount("500")
        assertEquals("500", vm.uiState.value.amount)
        assertEquals(1, commands.size)
    }

    @Test
    fun `restored unknown save status is retryable and never discards draft`() = runTest {
        val handle = handle()
        create(handle).apply {
            validExpense()
            viewModelScope.cancel()
        }
        coEvery { expenseRepo.getSavedExpense("g1", any(), any()) } throws IOException("offline storage")
        val restored = create(restore(handle))
        assertFalse(restored.uiState.value.loading)
        assertFalse(restored.uiState.value.editable)
        assertNotNull(restored.uiState.value.loadingError)
        restored.updateAmount("500")
        assertEquals("100", restored.uiState.value.amount)
        coEvery { expenseRepo.getSavedExpense("g1", any(), any()) } returns null
        restored.retryLoad()
        assertTrue(restored.uiState.value.editable)
        assertNull(restored.uiState.value.loadingError)
    }

    @Test
    fun `saving freezes every financial field and prevents double submit`() = runTest {
        val completion = CompletableDeferred<Unit>()
        writeExpense = {
            completion.await()
            saved[it.id] = it
        }
        val vm = create().apply { validExpense() }
        vm.submit()
        val before = vm.uiState.value
        assertTrue(before.saving)
        assertFalse(before.editable)
        vm.updateAmount("500")
        vm.updateDescription("Changed")
        vm.updateCurrency("USD")
        vm.updatePayer("b")
        vm.updateCategory("Food")
        vm.updateSplitType(SplitType.EXACT)
        vm.updateMemberInput("a", "50")
        vm.toggleParticipant("b")
        vm.retryLoad()
        vm.submit()
        assertEquals(before, vm.uiState.value)
        assertEquals(1, commands.size)
        completion.complete(Unit)
        assertTrue(vm.uiState.value.saved)
    }

    @Test
    fun `cancellation is not displayed as save failure and requires recovery`() = runTest {
        writeExpense = { throw CancellationException("cancelled") }
        val vm = create().apply { validExpense() }
        vm.submit()
        assertNull(vm.uiState.value.error)
        assertNotNull(vm.uiState.value.loadingError)
        assertFalse(vm.uiState.value.saving)
        assertFalse(vm.uiState.value.saved)
        assertFalse(vm.uiState.value.editable)
        vm.retryLoad()
        assertTrue(vm.uiState.value.editable)
    }

    @Test
    fun `group read failure shows explicit loading error then retries`() = runTest {
        coEvery { groupRepo.getById("g1") } throws IOException("cannot read group")
        val vm = create()
        assertFalse(vm.uiState.value.loading)
        assertFalse(vm.uiState.value.editable)
        assertEquals(UiMessage.Raw("cannot read group"), vm.uiState.value.loadingError)
        vm.submit()
        assertTrue(commands.isEmpty())
        coEvery { groupRepo.getById("g1") } returns group
        vm.retryLoad()
        assertTrue(vm.uiState.value.editable)
        assertEquals("a", vm.uiState.value.paidBy)
    }

    @Test
    fun `missing group and empty membership do not look ready to save`() = runTest {
        groups.value = null
        val missing = create()
        assertEquals(UiMessage.Res(R.string.expense_group_unavailable), missing.uiState.value.loadingError)
        assertFalse(missing.uiState.value.editable)
        groups.value = group.copy(members = emptyList())
        missing.retryLoad()
        missing.validExpense()
        assertNotNull(missing.uiState.value.splitError)
        missing.submit()
        assertTrue(commands.isEmpty())
    }

    @Test
    fun `removed member remains selected until explicitly removed with no changed allocation`() = runTest {
        val vm = create().apply { validExpense() }
        val splits = vm.uiState.value.previewSplits
        groups.value = group.copy(members = listOf("a"))
        assertEquals(setOf("a", "b"), vm.uiState.value.participants)
        assertEquals(splits, vm.uiState.value.previewSplits)
        assertNotNull(vm.uiState.value.splitError)
        vm.submit()
        assertTrue(commands.isEmpty())
        vm.toggleParticipant("b")
        assertEquals(listOf(SplitEntry("a", 10000)), vm.uiState.value.previewSplits)
        vm.submit()
        assertTrue(vm.uiState.value.saved)
    }

    @Test
    fun `restored removed payer and participant are not silently rewritten`() = runTest {
        val handle = handle()
        create(handle).apply {
            validExpense()
            updatePayer("b")
            viewModelScope.cancel()
        }
        groups.value = group.copy(members = listOf("a"))
        val restored = create(restore(handle))
        assertEquals("b", restored.uiState.value.paidBy)
        assertEquals(setOf("a", "b"), restored.uiState.value.participants)
        assertNotNull(restored.uiState.value.splitError)
        restored.updatePayer("a")
        restored.toggleParticipant("b")
        assertNull(restored.uiState.value.splitError)
    }

    @Test
    fun `new members do not silently change initialized split`() = runTest {
        val vm = create().apply { validExpense() }
        groups.value = group.copy(members = listOf("a", "b", "c"))
        assertEquals(listOf("a", "b", "c"), vm.uiState.value.members)
        assertEquals(setOf("a", "b"), vm.uiState.value.participants)
        assertEquals(listOf(SplitEntry("a", 5000), SplitEntry("b", 5000)), vm.uiState.value.previewSplits)
    }

    @Test
    fun `submit refresh catches removed membership without waiting for observer`() = runTest {
        val vm = create().apply { validExpense() }
        coEvery { groupRepo.getById("g1") } returns group.copy(members = listOf("a"))
        vm.submit()
        assertNotNull(vm.uiState.value.splitError)
        assertFalse(vm.uiState.value.saving)
        assertTrue(commands.isEmpty())
    }

    @Test
    fun `required fields precision maximum and exact split errors prevent command`() = runTest {
        val vm = create()
        vm.submit()
        assertNotNull(vm.uiState.value.amountError)
        assertEquals(UiMessage.Res(R.string.expense_description_required), vm.uiState.value.descriptionError)
        vm.validExpense()
        vm.updateAmount("0.001")
        assertEquals(UiMessage.Raw("Use at most 2 decimal places"), vm.uiState.value.amountError)
        vm.updateAmount("0")
        assertEquals(UiMessage.Res(R.string.expense_amount_positive), vm.uiState.value.amountError)
        vm.submit()
        vm.updateAmount("10000000000.01")
        assertEquals(UiMessage.Res(R.string.expense_amount_too_large), vm.uiState.value.amountError)
        vm.submit()
        vm.updateAmount("100")
        vm.exact("50", "49.99")
        assertEquals(1L, vm.uiState.value.remaining)
        assertNotNull(vm.uiState.value.splitError)
        vm.submit()
        assertTrue(commands.isEmpty())
    }

    @Test
    fun `selected participants and decimal percentages define submitted shares`() = runTest {
        groups.value = group.copy(members = listOf("a", "b", "c"))
        val vm = create().apply {
            validExpense()
            toggleParticipant("c")
            updateSplitType(SplitType.PERCENTAGE)
            updateMemberInput("a", "12.5")
            updateMemberInput("b", "87.5")
            updateMemberInput("c", "99")
        }
        vm.submit()
        assertEquals(listOf(SplitEntry("a", 1250), SplitEntry("b", 8750)), commands.single().splitAmong)
    }

    @Test
    fun `malformed restored state stays blocked without replacing command identity`() {
        val handle = SavedStateHandle(mapOf("groupId" to "g1", "expenseDraft" to "not json"))
        val vm = create(handle)
        assertFalse(vm.uiState.value.loading)
        assertFalse(vm.uiState.value.editable)
        assertNotNull(vm.uiState.value.loadingError)
        assertEquals("not json", handle.get<String>("expenseDraft"))
        vm.updateAmount("100")
        vm.retryLoad()
        vm.submit()
        assertTrue(commands.isEmpty())
        assertEquals("not json", handle.get<String>("expenseDraft"))
    }

    @Test
    fun `identity change on restore cannot submit previous author draft`() = runTest {
        val handle = handle()
        create(handle).apply {
            validExpense()
            viewModelScope.cancel()
        }
        every { identity.getPublicKeyHex() } returns "b"
        val vm = create(restore(handle))
        assertFalse(vm.uiState.value.editable)
        assertNotNull(vm.uiState.value.loadingError)
        vm.submit()
        assertTrue(commands.isEmpty())
        assertEquals("a", vm.uiState.value.paidBy)
        coVerify(exactly = 0) { expenseRepo.getSavedExpense(any(), any(), any()) }
    }

    @Test
    fun `membership updates cannot hide unresolved recovery error`() = runTest {
        val vm = create().apply { validExpense() }
        writeExpense = { throw IOException("ambiguous") }
        coEvery { expenseRepo.getSavedExpense("g1", any(), any()) } throws IOException("read unavailable")
        vm.submit()
        groups.value = group.copy(name = "Renamed")
        assertNotNull(vm.uiState.value.loadingError)
        assertFalse(vm.uiState.value.editable)
    }

    @Test
    fun `valid JSON missing stable command fields is blocked not regenerated`() {
        for (payload in listOf("{}", "null")) {
            val handle = SavedStateHandle(mapOf("groupId" to "g1", "expenseDraft" to payload))
            val vm = create(handle)
            assertFalse(vm.uiState.value.editable)
            assertNotNull(vm.uiState.value.loadingError)
            assertEquals(payload, handle.get<String>("expenseDraft"))
        }
    }

    @Test
    fun `submit rechecks author identity even without recreation`() = runTest {
        val vm = create().apply { validExpense() }
        every { identity.getPublicKeyHex() } returns "b"
        vm.submit()
        assertTrue(commands.isEmpty())
        assertEquals(UiMessage.Res(R.string.expense_identity_changed), vm.uiState.value.loadingError)
        assertFalse(vm.uiState.value.editable)
    }

    @Test
    fun `two restored editors cannot report changed draft saved when original wins`() = runTest {
        val handle = handle()
        create(handle).apply {
            validExpense()
            viewModelScope.cancel()
        }
        val first = create(restore(handle))
        val second = create(restore(handle))
        second.updateAmount("200")
        second.updateDescription("Changed")
        second.updateCategory("Food")
        first.submit()
        writeExpense = { throw com.splitfree.domain.repository.ExpenseSaveConflictException() }
        second.submit()
        assertFalse(second.uiState.value.saved)
        assertFalse(second.uiState.value.editable)
        assertTrue(second.uiState.value.dirty)
        assertEquals("200", second.uiState.value.amount)
        assertEquals("Changed", second.uiState.value.description)
        assertEquals("Food", second.uiState.value.category)
        assertEquals(UiMessage.Res(R.string.expense_saved_differently), second.uiState.value.loadingError)
        assertEquals(10000L, saved.values.single().amount)
    }

    @Test
    fun `restored stale snapshot cannot claim differently saved command`() = runTest {
        val handle = handle()
        val vm = create(handle).apply { validExpense() }
        val stale = restore(handle)
        vm.updateCategory("Food")
        vm.submit()
        vm.viewModelScope.cancel()
        val restored = create(stale)
        assertFalse(restored.uiState.value.saved)
        assertFalse(restored.uiState.value.editable)
        assertEquals("", restored.uiState.value.category)
        assertEquals(UiMessage.Res(R.string.expense_saved_differently), restored.uiState.value.loadingError)
    }

    @Test
    fun `matching recovered expense ignores split ordering but compares timestamp`() = runTest {
        val handle = handle()
        val vm = create(handle).apply { validExpense() }
        vm.submit()
        val expense = saved.values.single()
        saved[expense.id] = expense.copy(splitAmong = expense.splitAmong.asReversed())
        assertTrue(create(restore(handle)).uiState.value.saved)
        saved[expense.id] = expense.copy(timestamp = expense.timestamp + 1)
        val conflict = create(restore(handle))
        assertFalse(conflict.uiState.value.saved)
        assertNotNull(conflict.uiState.value.loadingError)
    }

    @Test
    fun `cancelled initial group load stops spinner and permits explicit retry`() = runTest {
        coEvery { groupRepo.getById("g1") } throws CancellationException("interrupted")
        val vm = create()
        assertFalse(vm.uiState.value.loading)
        assertFalse(vm.uiState.value.editable)
        assertNotNull(vm.uiState.value.loadingError)
        assertNull(vm.uiState.value.error)
        assertTrue(commands.isEmpty())
        coEvery { groupRepo.getById("g1") } returns group
        vm.retryLoad()
        assertFalse(vm.uiState.value.loading)
        assertTrue(vm.uiState.value.editable)
        assertNull(vm.uiState.value.loadingError)
    }

    // --- editing an existing expense ---

    private val storedExpense =
        Expense(
            id = "exp-1",
            amount = 12_050,
            currency = "INR",
            description = "Boat trip",
            paidBy = "b",
            splitType = SplitType.EXACT,
            splitAmong = listOf(SplitEntry("a", 5_000), SplitEntry("b", 7_050)),
            timestamp = 1_700_000_000L,
            category = "transport"
        )

    private fun editHandle(expenseId: String = "exp-1") =
        SavedStateHandle(mapOf("groupId" to "g1", "expenseId" to expenseId))

    private fun stored(expense: Expense = storedExpense, author: String = "a") {
        // The edit screen asks for the current user's own entry first (expense identity is (author, uuid)).
        coEvery { getExpenses.get("g1", expense.id, preferAuthor = "a") } returns AuthoredExpense(expense, author)
    }

    @Test
    fun `editing seeds the draft from the stored expense without marking it dirty`() = runTest {
        stored()
        val state = create(editHandle()).uiState.value
        assertTrue(state.editing)
        assertFalse(state.loading)
        assertTrue(state.editable)
        assertEquals("120.50", state.amount)
        assertEquals("Boat trip", state.description)
        assertEquals("INR", state.currency)
        assertEquals("b", state.paidBy)
        assertEquals("transport", state.category)
        assertEquals(SplitType.EXACT, state.splitType)
        assertEquals(setOf("a", "b"), state.participants)
        assertEquals(mapOf("a" to "50", "b" to "70.50"), state.memberInputs)
        assertEquals(listOf(SplitEntry("a", 5_000), SplitEntry("b", 7_050)), state.previewSplits)
        assertFalse(state.dirty)
        assertNull(state.splitError)
    }

    @Test
    fun `equal percentage and share splits reopen in their own mode when the inputs reproduce the shares`() = runTest {
        groups.value = group.copy(members = listOf("a", "b", "c"))
        val equal = storedExpense.copy(
            amount = 9_000,
            splitType = SplitType.EQUAL,
            splitAmong = listOf(SplitEntry("a", 3_000), SplitEntry("b", 3_000), SplitEntry("c", 3_000))
        )
        stored(equal)
        val equalState = create(editHandle()).uiState.value
        assertEquals(SplitType.EQUAL, equalState.splitType)
        assertEquals(setOf("a", "b", "c"), equalState.participants)
        assertEquals(equal.splitAmong, equalState.previewSplits)

        val percent = storedExpense.copy(
            amount = 10_000,
            splitType = SplitType.PERCENTAGE,
            splitAmong = listOf(SplitEntry("a", 1_250), SplitEntry("b", 8_750))
        )
        stored(percent)
        val percentState = create(editHandle()).uiState.value
        assertEquals(SplitType.PERCENTAGE, percentState.splitType)
        assertEquals(mapOf("a" to "12.5", "b" to "87.5"), percentState.memberInputs)
        assertEquals(percent.splitAmong, percentState.previewSplits)

        val shares = storedExpense.copy(
            amount = 3_000,
            splitType = SplitType.SHARES,
            splitAmong = listOf(SplitEntry("a", 2_000), SplitEntry("b", 1_000))
        )
        stored(shares)
        val sharesState = create(editHandle()).uiState.value
        assertEquals(SplitType.SHARES, sharesState.splitType)
        assertEquals(mapOf("a" to "2", "b" to "1"), sharesState.memberInputs)
        assertEquals(shares.splitAmong, sharesState.previewSplits)
    }

    @Test
    fun `a split whose inputs cannot be reconstructed reopens as exact amounts`() = runTest {
        // Labelled equal, but the stored shares are not an equal split of the total.
        val uneven = storedExpense.copy(
            amount = 10_000,
            splitType = SplitType.EQUAL,
            splitAmong = listOf(SplitEntry("a", 1_000), SplitEntry("b", 9_000))
        )
        stored(uneven)
        val state = create(editHandle()).uiState.value
        assertEquals(SplitType.EXACT, state.splitType)
        assertEquals(mapOf("a" to "10", "b" to "90"), state.memberInputs)
        assertEquals(uneven.splitAmong, state.previewSplits)
        assertNull(state.splitError)
    }

    @Test
    fun `submitting an edit publishes a correction with the original id and timestamp`() = runTest {
        stored()
        val vm = create(editHandle())
        vm.updateDescription("Boat trip and lunch")
        vm.updateAmount("200")
        vm.updateSplitType(SplitType.EQUAL)
        assertTrue(vm.uiState.value.dirty)

        vm.submit()

        val (originalId, corrected) = corrections.single()
        assertEquals("exp-1", originalId)
        assertEquals("exp-1", corrected.id)
        assertEquals(1_700_000_000L, corrected.timestamp)
        assertEquals(20_000L, corrected.amount)
        assertEquals("Boat trip and lunch", corrected.description)
        assertEquals("b", corrected.paidBy)
        assertEquals("transport", corrected.category)
        assertEquals(SplitType.EQUAL, corrected.splitType)
        assertEquals(listOf(SplitEntry("a", 10_000), SplitEntry("b", 10_000)), corrected.splitAmong)
        assertTrue(commands.isEmpty())
        assertTrue(vm.uiState.value.saved)
        assertFalse(vm.uiState.value.dirty)
        assertFalse(vm.uiState.value.editable)
        coVerify(exactly = 0) { expenseRepo.getSavedExpense(any(), any(), any()) }
    }

    @Test
    fun `saving an edit ignores a second tap and a failed edit can be retried`() = runTest {
        stored()
        val gate = CompletableDeferred<Unit>()
        coEvery { expenseRepo.correctExpense(any(), any(), "g1") } coAnswers {
            gate.await()
            corrections += firstArg<String>() to secondArg<Expense>()
        }
        val vm = create(editHandle())
        vm.submit()
        assertTrue(vm.uiState.value.saving)
        vm.submit()
        gate.complete(Unit)
        assertEquals(1, corrections.size)
        assertTrue(vm.uiState.value.saved)

        coEvery { expenseRepo.correctExpense(any(), any(), "g1") } throws IOException("relay down")
        val failing = create(editHandle())
        failing.submit()
        assertEquals(UiMessage.Raw("relay down"), failing.uiState.value.error)
        assertFalse(failing.uiState.value.saved)
        assertTrue(failing.uiState.value.editable)
        coEvery { expenseRepo.correctExpense(any(), any(), "g1") } coAnswers {
            corrections += firstArg<String>() to secondArg<Expense>()
        }
        failing.submit()
        assertTrue(failing.uiState.value.saved)
        assertEquals(2, corrections.size)
    }

    @Test
    fun `an expense that cannot be loaded shows a loading error and retries`() = runTest {
        coEvery { getExpenses.get("g1", "exp-1", any()) } returns null
        val vm = create(editHandle())
        assertTrue(vm.uiState.value.editing)
        assertFalse(vm.uiState.value.loading)
        assertFalse(vm.uiState.value.editable)
        assertEquals(UiMessage.Res(R.string.expense_edit_missing), vm.uiState.value.loadingError)
        vm.submit()
        assertTrue(corrections.isEmpty())

        stored()
        vm.retryLoad()
        assertTrue(vm.uiState.value.editable)
        assertEquals("Boat trip", vm.uiState.value.description)
    }

    @Test
    fun `an expense authored by someone else cannot be edited`() = runTest {
        stored(author = "b")
        val vm = create(editHandle())
        assertFalse(vm.uiState.value.editable)
        assertEquals(UiMessage.Res(R.string.expense_edit_not_author), vm.uiState.value.loadingError)
    }

    @Test
    fun `a failing expense lookup is reported as a loading error`() = runTest {
        coEvery { getExpenses.get("g1", "exp-1", any()) } throws IOException("decrypt failed")
        val vm = create(editHandle())
        assertEquals(UiMessage.Raw("decrypt failed"), vm.uiState.value.loadingError)
        assertFalse(vm.uiState.value.editable)
    }

    @Test
    fun `restored edit keeps the user's changes instead of reseeding`() = runTest {
        stored()
        val handle = editHandle()
        val first = create(handle)
        first.updateDescription("Changed")
        first.viewModelScope.cancel()
        coEvery { getExpenses.get("g1", "exp-1", any()) } throws AssertionError("must not reload after restore")

        val restored = create(restore(handle))

        assertTrue(restored.uiState.value.editing)
        assertEquals("Changed", restored.uiState.value.description)
        assertEquals("120.50", restored.uiState.value.amount)
        assertTrue(restored.uiState.value.dirty)
        assertTrue(restored.uiState.value.editable)
        restored.submit()
        assertEquals("Changed", corrections.single().second.description)
        assertEquals(1_700_000_000L, corrections.single().second.timestamp)
    }

    @Test
    fun `a new expense is not in editing mode`() {
        assertFalse(create().uiState.value.editing)
    }

    private fun handle() = SavedStateHandle(mapOf("groupId" to "g1"))

    private fun restore(handle: SavedStateHandle) = SavedStateHandle(
        handle.keys().associateWith {
            handle.get<Any?>(it)
        }
    )

    private fun create(handle: SavedStateHandle = handle()): AddExpenseViewModel = AddExpenseViewModel(
        handle,
        AddExpenseUseCase(expenseRepo),
        CorrectExpenseUseCase(expenseRepo),
        getExpenses,
        groupRepo,
        identity
    ).also { instances += it }

    private fun AddExpenseViewModel.validExpense() {
        updateAmount("100")
        updateDescription("Lunch")
    }

    private fun AddExpenseViewModel.exact(first: String, second: String) {
        updateSplitType(SplitType.EXACT)
        updateMemberInput("a", first)
        updateMemberInput("b", second)
    }
}
