package com.splitfree.ui.viewmodels

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import com.splitfree.R
import com.splitfree.domain.model.expense.Expense
import com.splitfree.domain.model.expense.SplitEntry
import com.splitfree.domain.model.expense.SplitType
import com.splitfree.domain.model.group.Group
import com.splitfree.domain.money.ExpenseInputParser
import com.splitfree.domain.repository.EditableExpense
import com.splitfree.domain.repository.ExpenseCorrectionCommand
import com.splitfree.domain.repository.ExpenseRepositoryContract
import com.splitfree.domain.repository.ExpenseRevisionConflictException
import com.splitfree.domain.repository.GroupRepositoryContract
import com.splitfree.domain.repository.IdentityContract
import com.splitfree.domain.usecase.expense.AddExpenseUseCase
import com.splitfree.domain.usecase.expense.CorrectExpenseUseCase
import com.splitfree.domain.util.RelayDefaults
import com.splitfree.ui.util.UiMessage
import com.splitfree.ui.viewmodels.expense.ExpenseDraft
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import java.io.IOException
import java.math.BigDecimal
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
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AddExpenseViewModelTest {
    private val groupRepo = mockk<GroupRepositoryContract>()
    private val expenseRepo = mockk<ExpenseRepositoryContract>()
    private val identity = mockk<IdentityContract>()
    private val instances = mutableListOf<AddExpenseViewModel>()
    private val commands = mutableListOf<Expense>()
    private val corrections = mutableListOf<Pair<String, Expense>>()
    private val correctionCommands = mutableListOf<ExpenseCorrectionCommand>()
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
        coEvery { expenseRepo.getSavedCorrection("g1", any(), "a", any()) } returns null
        coEvery { expenseRepo.correctExpense(any(), any(), "g1", "a", any()) } coAnswers {
            corrections += firstArg<String>() to secondArg<Expense>()
            correctionCommands += checkNotNull(arg<ExpenseCorrectionCommand?>(4))
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
    fun `non equal modes have usable defaults without typing each participant value`() = runTest {
        for ((type, value) in listOf(SplitType.PERCENTAGE to "50", SplitType.SHARES to "1", SplitType.EXACT to "50")) {
            val vm = create().apply {
                validExpense()
                updateSplitType(type)
            }
            assertEquals(mapOf("a" to value, "b" to value), vm.uiState.value.memberInputs)
            assertNull(vm.uiState.value.splitError)
            vm.submit()
            assertEquals(type, commands.last().splitType)
            assertEquals(listOf(SplitEntry("a", 5000), SplitEntry("b", 5000)), commands.last().splitAmong)
            assertTrue(vm.uiState.value.saved)
        }
        assertEquals(3, commands.size)
    }

    @Test
    fun `percent defaults sum exactly to 100 for every supported participant count`() {
        val parser = ExpenseInputParser(Locale.US)
        for (count in 1..RelayDefaults.MAX_GROUP_MEMBERS) {
            val members = listOf("a") + (1 until count).map { "person-$it" }
            groups.value = group.copy(members = members.reversed())
            val vm = create().apply {
                updateSplitType(SplitType.PERCENTAGE)
            }
            val values = vm.uiState.value.memberInputs.mapValues { parser.weight(it.value) }
            assertEquals(members.toSet(), values.keys)
            assertEquals(0, values.values.fold(BigDecimal.ZERO, BigDecimal::add).compareTo(BigDecimal(100)))
            assertTrue(values.values.all { it.signum() > 0 })
            assertTrue(values.values.maxOrNull()!! - values.values.minOrNull()!! <= BigDecimal("0.01"))
            if (count == 3) {
                assertEquals(
                    mapOf("a" to "33.34", "person-1" to "33.33", "person-2" to "33.33"),
                    vm.uiState.value.memberInputs
                )
            }
            vm.updateAmount("100")
            assertNull(vm.uiState.value.splitError)
            assertEquals(10000L, vm.uiState.value.previewSplits.sumOf { it.share })
        }
    }

    @Test
    fun `percentage and shares defaults do not require a valid total or currency`() {
        val vm = create()
        vm.updateCurrency("")
        vm.updateSplitType(SplitType.PERCENTAGE)
        assertEquals(mapOf("a" to "50", "b" to "50"), vm.uiState.value.memberInputs)
        vm.updateSplitType(SplitType.SHARES)
        assertEquals(mapOf("a" to "1", "b" to "1"), vm.uiState.value.memberInputs)
        vm.updateSplitType(SplitType.EXACT)
        assertTrue(vm.uiState.value.memberInputs.isEmpty())
        vm.updateCurrency("INR")
        vm.updateAmount("100")
        assertEquals(mapOf("a" to "50", "b" to "50"), vm.uiState.value.memberInputs)
    }

    @Test
    fun `exact defaults distribute currency minor units with deterministic remainder`() {
        groups.value = group.copy(members = listOf("c", "b", "a"))
        val vm = create().apply { updateSplitType(SplitType.EXACT) }
        for ((currency, inputs) in listOf(
            "INR" to listOf("33.34", "33.33", "33.33"),
            "JPY" to listOf("34", "33", "33"),
            "KWD" to listOf("33.334", "33.333", "33.333")
        )) {
            vm.updateCurrency(currency)
            vm.updateAmount("100")
            assertEquals(listOf("a", "b", "c").zip(inputs).toMap(), vm.uiState.value.memberInputs)
            assertEquals(0L, vm.uiState.value.remaining)
            assertNull(vm.uiState.value.splitError)
            assertEquals(ExpenseInputParser().money("100", currency), vm.uiState.value.previewSplits.sumOf { it.share })
        }
    }

    @Test
    fun `exact automatic defaults clear invalid totals then resume without retaining stale amounts`() {
        val vm = create().apply {
            validExpense()
            updateSplitType(SplitType.EXACT)
        }
        for (invalid in listOf("", "0", "-1", "1.", "1.001", "10000000000.01", "99999999999999999999999999999999")) {
            vm.updateAmount(invalid)
            assertTrue("Must not seed invalid total $invalid", vm.uiState.value.memberInputs.isEmpty())
            vm.updateAmount("100.01")
            assertEquals(mapOf("a" to "50.01", "b" to "50"), vm.uiState.value.memberInputs)
            assertNull(vm.uiState.value.splitError)
        }
        vm.updateCurrency("ZZZ")
        assertTrue(vm.uiState.value.memberInputs.isEmpty())
        vm.updateCurrency("KWD")
        assertEquals(mapOf("a" to "50.005", "b" to "50.005"), vm.uiState.value.memberInputs)
        vm.updateAmount("1000000000")
        assertEquals(ExpenseInputParser.MAX_EXPENSE_AMOUNT, vm.uiState.value.previewSplits.sumOf { it.share })
        assertNull(vm.uiState.value.splitError)
    }

    @Test
    fun `tiny exact defaults conserve total but cannot bypass positive participant validation`() = runTest {
        val vm = create().apply {
            validExpense()
            updateAmount("0.01")
            updateSplitType(SplitType.EXACT)
        }
        assertEquals(mapOf("a" to "0.01", "b" to "0"), vm.uiState.value.memberInputs)
        assertEquals(0L, vm.uiState.value.remaining)
        assertNotNull(vm.uiState.value.splitError)
        vm.submit()
        assertTrue(commands.isEmpty())
        vm.toggleParticipant("b")
        assertNull(vm.uiState.value.splitError)
        vm.submit()
        assertEquals(listOf(SplitEntry("a", 1)), commands.single().splitAmong)
    }

    @Test
    fun `untouched defaults follow selection and mode changes including empty selection`() {
        val vm = create().apply { validExpense() }
        for (type in listOf(SplitType.PERCENTAGE, SplitType.SHARES, SplitType.EXACT)) {
            vm.updateSplitType(type)
            val defaults = vm.uiState.value.memberInputs
            vm.toggleParticipant("b")
            assertEquals(mapOf("a" to if (type == SplitType.SHARES) "1" else "100"), vm.uiState.value.memberInputs)
            vm.toggleParticipant("a")
            assertTrue(vm.uiState.value.memberInputs.isEmpty())
            assertNotNull(vm.uiState.value.splitError)
            vm.toggleParticipant("b")
            vm.toggleParticipant("a")
            assertEquals(defaults, vm.uiState.value.memberInputs)
            assertNull(vm.uiState.value.splitError)
        }
        vm.updateSplitType(SplitType.EQUAL)
        vm.updateAmount("200")
        vm.toggleParticipant("b")
        vm.updateSplitType(SplitType.EXACT)
        assertEquals(mapOf("a" to "200"), vm.uiState.value.memberInputs)
        vm.updateSplitType(SplitType.PERCENTAGE)
        assertEquals(mapOf("a" to "100"), vm.uiState.value.memberInputs)
    }

    @Test
    fun `group observations never select new members or redistribute departed participants defaults`() {
        val vm = create().apply {
            validExpense()
            updateSplitType(SplitType.PERCENTAGE)
        }
        val before = vm.uiState.value.memberInputs
        groups.value = group.copy(members = listOf("c", "b", "a"))
        assertEquals(before, vm.uiState.value.memberInputs)
        assertEquals(setOf("a", "b"), vm.uiState.value.participants)
        vm.toggleParticipant("c")
        assertEquals(mapOf("a" to "33.34", "b" to "33.33", "c" to "33.33"), vm.uiState.value.memberInputs)
        groups.value = group.copy(members = listOf("a", "c"))
        assertEquals(setOf("a", "b", "c"), vm.uiState.value.participants)
        assertEquals("33.33", vm.uiState.value.memberInputs["b"])
        assertNotNull(vm.uiState.value.splitError)
        vm.toggleParticipant("b")
        assertEquals(mapOf("a" to "50", "c" to "50"), vm.uiState.value.memberInputs)
        assertNull(vm.uiState.value.splitError)
    }

    @Test
    fun `editing even a default looking value freezes that mode across total currency and selection changes`() {
        val vm = create().apply {
            validExpense()
            updateSplitType(SplitType.EXACT)
            updateMemberInput("a", "50")
        }
        val authored = vm.uiState.value.memberInputs
        vm.updateAmount("200")
        vm.updateCurrency("KWD")
        vm.toggleParticipant("b")
        vm.updateSplitType(SplitType.EQUAL)
        vm.updateSplitType(SplitType.EXACT)
        assertEquals(authored, vm.uiState.value.memberInputs)
        assertNotNull(vm.uiState.value.splitError)
        vm.toggleParticipant("b")
        assertEquals(authored, vm.uiState.value.memberInputs)
        assertNotNull(vm.uiState.value.splitError)
    }

    @Test
    fun `intentional blank stays blank across mode changes recreation and participant reselection`() {
        val handle = handle()
        val first = create(handle).apply {
            validExpense()
            updateSplitType(SplitType.PERCENTAGE)
            updateMemberInput("a", "")
            toggleParticipant("a")
            updateSplitType(SplitType.SHARES)
            updateAmount("200")
        }
        first.viewModelScope.cancel()
        val restored = create(restore(handle))
        restored.updateSplitType(SplitType.PERCENTAGE)
        restored.toggleParticipant("a")
        assertEquals(mapOf("a" to "", "b" to "50"), restored.uiState.value.memberInputs)
        assertNotNull(restored.uiState.value.splitError)
    }

    @Test
    fun `custom modes retain existing values and seed only newly selected member entries`() {
        groups.value = group.copy(members = listOf("a", "b", "c"))
        val vm = create().apply {
            validExpense()
            toggleParticipant("c")
            updateSplitType(SplitType.PERCENTAGE)
            updateMemberInput("a", "60")
            updateMemberInput("b", "40")
            toggleParticipant("c")
        }
        assertEquals(mapOf("a" to "60", "b" to "40", "c" to "33.33"), vm.uiState.value.memberInputs)
        assertNotNull(vm.uiState.value.splitError)
        vm.toggleParticipant("c")
        assertNull(vm.uiState.value.splitError)
        vm.updateSplitType(SplitType.SHARES)
        vm.updateMemberInput("a", "2")
        vm.toggleParticipant("c")
        assertEquals(mapOf("a" to "2", "b" to "1", "c" to "1"), vm.uiState.value.memberInputs)
        assertNull(vm.uiState.value.splitError)
    }

    @Test
    fun `automatic mode provenance and localized defaults survive process recreation`() {
        Locale.setDefault(Locale.GERMANY)
        groups.value = group.copy(members = listOf("c", "b", "a"))
        val handle = handle()
        val first = create(handle).apply {
            validExpense()
            updateSplitType(SplitType.PERCENTAGE)
        }
        assertEquals("33,34", first.uiState.value.memberInputs["a"])
        first.updateSplitType(SplitType.EXACT)
        assertEquals("33,34", first.uiState.value.memberInputs["a"])
        val before = first.uiState.value
        first.viewModelScope.cancel()
        Locale.setDefault(Locale.US)
        val restored = create(restore(handle))
        assertEquals(before, restored.uiState.value)
        restored.updateCurrency("KWD")
        assertEquals("33,334", restored.uiState.value.memberInputs["a"])
        restored.updateAmount("200,001")
        assertEquals(mapOf("a" to "66,667", "b" to "66,667", "c" to "66,667"), restored.uiState.value.memberInputs)
        restored.updateSplitType(SplitType.PERCENTAGE)
        restored.toggleParticipant("b")
        assertEquals(mapOf("a" to "50", "c" to "50"), restored.uiState.value.memberInputs)
    }

    @Test
    fun `legacy draft without provenance keeps saved custom and blank values`() {
        val handle = handle()
        create(handle).apply {
            validExpense()
            exact("", "50")
            viewModelScope.cancel()
        }
        val serialized = checkNotNull(handle.get<String>("expenseDraft"))
        val legacy = Json.parseToJsonElement(serialized).jsonObject.filterKeys { it != "automaticInputModes" }
        handle["expenseDraft"] = Json.encodeToString(JsonObject.serializer(), JsonObject(legacy))
        val restored = create(restore(handle))
        restored.updateAmount("200")
        assertEquals(mapOf("a" to "", "b" to "50"), restored.uiState.value.memberInputs)
        restored.updateSplitType(SplitType.PERCENTAGE)
        assertEquals(mapOf("a" to "50", "b" to "50"), restored.uiState.value.memberInputs)
    }

    @Test
    fun `restored authored expense inputs are never treated as automatic defaults`() {
        val equal = storedExpense.copy(
            amount = 10000,
            splitType = SplitType.EQUAL,
            splitAmong = listOf(SplitEntry("a", 5000), SplitEntry("b", 5000))
        )
        stored(equal)
        val vm = create(editHandle())
        assertFalse(vm.uiState.value.dirty)
        vm.updateAmount("200")
        vm.updateSplitType(SplitType.EXACT)
        assertEquals(mapOf("a" to "50", "b" to "50"), vm.uiState.value.memberInputs)
        assertNotNull(vm.uiState.value.splitError)
        vm.updateSplitType(SplitType.PERCENTAGE)
        assertEquals(mapOf("a" to "50", "b" to "50"), vm.uiState.value.memberInputs)
        vm.toggleParticipant("b")
        assertEquals(mapOf("a" to "100"), vm.uiState.value.memberInputs)
        vm.updateSplitType(SplitType.EXACT)
        assertEquals(mapOf("a" to "50", "b" to "50"), vm.uiState.value.memberInputs)
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

    private fun editHandle(expenseId: String = "exp-1", author: String = "a") =
        SavedStateHandle(mapOf("groupId" to "g1", "expenseId" to expenseId, "authorPubkey" to author))

    private fun stored(expense: Expense = storedExpense, author: String = "a", revisionId: String = "revision-1") {
        coEvery { expenseRepo.getEditableExpense("g1", expense.id, author) } returns
            EditableExpense(expense, revisionId, author)
    }

    @Test
    fun `editing seeds the draft from the stored expense without marking it dirty`() = runTest {
        stored()
        val handle = editHandle()
        val state = create(handle).uiState.value
        val draft = Json.decodeFromString<ExpenseDraft>(checkNotNull(handle.get<String>("expenseDraft")))
        assertEquals(storedExpense.id, draft.expenseId)
        assertEquals("a", draft.authorPubkey)
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
        coVerify(exactly = 1) { expenseRepo.correctExpense("exp-1", any(), "g1", "a", any()) }
    }

    @Test
    fun `an edit carries the seeded revision and a command id that is not the expense id`() = runTest {
        stored(revisionId = "revision-7")
        val handle = editHandle()
        val vm = create(handle)
        vm.updateDescription("Changed")
        vm.submit()

        val command = correctionCommands.single()
        assertEquals("revision-7", command.expectedRevisionId)
        assertNotEquals("exp-1", command.id)
        assertEquals(command.id, UUID.fromString(command.id).toString())
        val draft = Json.decodeFromString<ExpenseDraft>(checkNotNull(handle.get<String>("expenseDraft")))
        assertEquals("exp-1", draft.expenseId)
        assertEquals(command.id, draft.commandId)
        assertEquals("revision-7", draft.expectedRevisionId)
    }

    @Test
    fun `a new expense is its own command`() = runTest {
        val handle = handle()
        create(handle).apply { validExpense() }.submit()
        val draft = Json.decodeFromString<ExpenseDraft>(checkNotNull(handle.get<String>("expenseDraft")))
        assertEquals(commands.single().id, draft.expenseId)
        assertEquals(draft.expenseId, draft.commandId)
    }

    @Test
    fun `a failed edit retries under the same command id`() = runTest {
        stored()
        val vm = create(editHandle())
        vm.updateDescription("Changed")
        coEvery { expenseRepo.correctExpense(any(), any(), "g1", "a", any()) } coAnswers {
            correctionCommands += checkNotNull(arg<ExpenseCorrectionCommand?>(4))
            throw IOException("relay down")
        }
        vm.submit()
        assertEquals(UiMessage.Raw("relay down"), vm.uiState.value.error)
        assertTrue(vm.uiState.value.editable)
        vm.submit()
        assertEquals(2, correctionCommands.size)
        assertEquals(correctionCommands[0], correctionCommands[1])
    }

    @Test
    fun `saving an edit ignores a second tap and a failed edit can be retried`() = runTest {
        stored()
        val gate = CompletableDeferred<Unit>()
        coEvery { expenseRepo.correctExpense(any(), any(), "g1", "a", any()) } coAnswers {
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

        coEvery { expenseRepo.correctExpense(any(), any(), "g1", "a", any()) } throws IOException("relay down")
        val failing = create(editHandle())
        failing.submit()
        assertEquals(UiMessage.Raw("relay down"), failing.uiState.value.error)
        assertFalse(failing.uiState.value.saved)
        assertTrue(failing.uiState.value.editable)
        coEvery { expenseRepo.correctExpense(any(), any(), "g1", "a", any()) } coAnswers {
            corrections += firstArg<String>() to secondArg<Expense>()
        }
        failing.submit()
        assertTrue(failing.uiState.value.saved)
        assertEquals(2, corrections.size)
    }

    @Test
    fun `an expense that cannot be loaded shows a loading error and retries`() = runTest {
        coEvery { expenseRepo.getEditableExpense("g1", "exp-1", "a") } returns null
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
        val vm = create(editHandle(author = "b"))
        assertFalse(vm.uiState.value.editable)
        assertEquals(UiMessage.Res(R.string.expense_edit_not_author), vm.uiState.value.loadingError)
    }

    @Test
    fun `a failing expense lookup is reported as a loading error`() = runTest {
        coEvery { expenseRepo.getEditableExpense("g1", "exp-1", "a") } throws IOException("decrypt failed")
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
        // The stored expense is consulted only for its revision; its payload must not replace the draft.
        stored(storedExpense.copy(description = "Reseeded"))

        val restored = create(restore(handle))

        assertTrue(restored.uiState.value.editing)
        assertEquals("Changed", restored.uiState.value.description)
        assertEquals("120.50", restored.uiState.value.amount)
        assertTrue(restored.uiState.value.dirty)
        assertNull(restored.uiState.value.loadingError)
        assertTrue(restored.uiState.value.editable)
        restored.submit()
        assertEquals("exp-1", corrections.single().first)
        assertEquals("Changed", corrections.single().second.description)
        assertEquals(1_700_000_000L, corrections.single().second.timestamp)
    }

    @Test
    fun `selecting another author never edits my colliding expense implicitly`() = runTest {
        stored(storedExpense.copy(description = "Mine"), author = "a")
        stored(storedExpense.copy(description = "Theirs"), author = "b")
        val vm = create(editHandle(author = "b"))

        assertTrue(vm.uiState.value.editing)
        assertFalse(vm.uiState.value.editable)
        assertEquals(UiMessage.Res(R.string.expense_edit_not_author), vm.uiState.value.loadingError)
        vm.submit()
        assertTrue(commands.isEmpty())
        assertTrue(corrections.isEmpty())
        coVerify(exactly = 0) { expenseRepo.getEditableExpense("g1", "exp-1", "a") }
    }

    @Test
    fun `selecting my colliding expense preserves its author through correction`() = runTest {
        stored(storedExpense.copy(description = "Theirs"), author = "b")
        stored(storedExpense.copy(description = "Mine"), author = "a")
        val vm = create(editHandle())

        assertEquals("Mine", vm.uiState.value.description)
        vm.submit()
        assertTrue(vm.uiState.value.saved)
        coVerify(exactly = 1) { expenseRepo.correctExpense("exp-1", any(), "g1", "a", any()) }
        coVerify(exactly = 0) { expenseRepo.getEditableExpense("g1", "exp-1", "b") }
    }

    @Test
    fun `incomplete edit arguments are blocked rather than treated as new expenses`() = runTest {
        val arguments = listOf(
            mapOf("expenseId" to "exp-1"),
            mapOf("authorPubkey" to "a"),
            mapOf("expenseId" to "exp-1", "authorPubkey" to ""),
            mapOf("expenseId" to "", "authorPubkey" to "a")
        )
        for (args in arguments) {
            val vm = create(SavedStateHandle(args + ("groupId" to "g1")))
            assertTrue(vm.uiState.value.editing)
            assertFalse(vm.uiState.value.editable)
            assertEquals(UiMessage.Res(R.string.expense_edit_missing), vm.uiState.value.loadingError)
            vm.submit()
        }
        assertTrue(commands.isEmpty())
        assertTrue(corrections.isEmpty())
        coVerify(exactly = 0) { expenseRepo.getEditableExpense(any(), any(), any()) }
    }

    @Test
    fun `restored draft cannot change the selected expense uuid or author`() = runTest {
        stored()
        val handle = editHandle()
        create(handle).apply {
            updateDescription("Changed")
            viewModelScope.cancel()
        }
        val wrongUuid = restore(handle).apply { this["expenseId"] = "other-expense" }
        val wrongAuthor = restore(handle).apply { this["authorPubkey"] = "b" }
        for (savedState in listOf(wrongUuid, wrongAuthor)) {
            val vm = create(savedState)
            assertFalse(vm.uiState.value.editable)
            assertNotNull(vm.uiState.value.loadingError)
            vm.submit()
        }
        assertTrue(commands.isEmpty())
        assertTrue(corrections.isEmpty())
    }

    @Test
    fun `a new expense is not in editing mode`() {
        assertFalse(create().uiState.value.editing)
    }

    // --- edits are recoverable commands ---

    @Test
    fun `restored unsaved edit cannot apply over a newer revision`() = runTest {
        stored()
        val handle = editHandle()
        val first = create(handle)
        first.updateDescription("Stale edit")
        first.viewModelScope.cancel()
        stored(storedExpense.copy(description = "Newer edit"), revisionId = "revision-2")
        val restored = create(restore(handle))
        assertFalse(restored.uiState.value.editable)
        assertFalse(restored.uiState.value.saved)
        assertEquals(UiMessage.Res(R.string.expense_edit_stale), restored.uiState.value.loadingError)
        assertEquals("Stale edit", restored.uiState.value.description)
        restored.submit()
        assertTrue(corrections.isEmpty())
        restored.retryLoad()
        assertFalse(restored.uiState.value.editable)
    }

    @Test
    fun `restored edit whose revision is unchanged reopens editable`() = runTest {
        stored()
        val handle = editHandle()
        create(handle).apply {
            updateDescription("Kept")
            viewModelScope.cancel()
        }
        val restored = create(restore(handle))
        assertTrue(restored.uiState.value.editable)
        assertNull(restored.uiState.value.loadingError)
        restored.submit()
        assertEquals("revision-1", correctionCommands.single().expectedRevisionId)
    }

    @Test
    fun `committed edit interrupted before response recovers exact command even after newer edit`() = runTest {
        stored()
        val handle = editHandle()
        var committed: Expense? = null
        var command: ExpenseCorrectionCommand? = null
        coEvery { expenseRepo.correctExpense(any(), any(), "g1", "a", any()) } coAnswers {
            committed = secondArg()
            command = arg(4)
            throw CancellationException("process stopped after commit")
        }
        coEvery { expenseRepo.getSavedCorrection("g1", "exp-1", "a", any()) } coAnswers {
            assertEquals(command, arg<ExpenseCorrectionCommand>(3))
            committed
        }
        val first = create(handle)
        first.updateDescription("Saved edit")
        first.submit()
        first.viewModelScope.cancel()
        stored(storedExpense.copy(description = "Newer edit"), revisionId = "revision-2")
        val restored = create(restore(handle))
        assertTrue(restored.uiState.value.saved)
        assertFalse(restored.uiState.value.editable)
        restored.submit()
        coVerify(exactly = 1) { expenseRepo.correctExpense(any(), any(), "g1", "a", any()) }
    }

    @Test
    fun `restored correction with different saved payload remains locked`() = runTest {
        stored()
        val handle = editHandle()
        val first = create(handle)
        first.updateDescription("Draft")
        first.viewModelScope.cancel()
        coEvery { expenseRepo.getSavedCorrection("g1", "exp-1", "a", any()) } returns
            storedExpense.copy(description = "Different")
        val restored = create(restore(handle))
        assertFalse(restored.uiState.value.saved)
        assertFalse(restored.uiState.value.editable)
        assertEquals(UiMessage.Res(R.string.expense_saved_differently), restored.uiState.value.loadingError)
        assertEquals("Draft", restored.uiState.value.description)
    }

    @Test
    fun `an edit refused for a stale revision at save time locks the draft`() = runTest {
        stored()
        val vm = create(editHandle())
        vm.updateDescription("Late edit")
        coEvery { expenseRepo.correctExpense(any(), any(), "g1", "a", any()) } throws ExpenseRevisionConflictException()
        stored(storedExpense.copy(description = "Someone else's edit"), revisionId = "revision-2")
        vm.submit()
        assertFalse(vm.uiState.value.saved)
        assertFalse(vm.uiState.value.editable)
        assertEquals(UiMessage.Res(R.string.expense_edit_stale), vm.uiState.value.loadingError)
        assertEquals("Late edit", vm.uiState.value.description)
    }

    @Test
    fun `an edit whose save check fails stays locked until the check succeeds`() = runTest {
        stored()
        val vm = create(editHandle())
        vm.updateDescription("Changed")
        coEvery { expenseRepo.correctExpense(any(), any(), "g1", "a", any()) } throws IOException("write interrupted")
        coEvery { expenseRepo.getSavedCorrection("g1", "exp-1", "a", any()) } throws IOException("read unavailable")
        vm.submit()
        assertFalse(vm.uiState.value.editable)
        assertEquals(UiMessage.Res(R.string.expense_save_check_failed), vm.uiState.value.loadingError)
        coEvery { expenseRepo.getSavedCorrection("g1", "exp-1", "a", any()) } returns null
        vm.retryLoad()
        assertTrue(vm.uiState.value.editable)
        assertEquals("Changed", vm.uiState.value.description)
    }

    @Test
    fun `a restored draft without a command id is blocked not regenerated`() {
        val handle = handle()
        create(handle).apply { validExpense() }
        val serialized = checkNotNull(handle.get<String>("expenseDraft"))
        val withoutCommand = Json.parseToJsonElement(serialized).jsonObject.filterKeys { it != "commandId" }
        val stripped = Json.encodeToString(JsonObject.serializer(), JsonObject(withoutCommand))
        val restored = create(SavedStateHandle(mapOf("groupId" to "g1", "expenseDraft" to stripped)))
        assertFalse(restored.uiState.value.editable)
        assertEquals(UiMessage.Res(R.string.expense_draft_unrestorable), restored.uiState.value.loadingError)
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
