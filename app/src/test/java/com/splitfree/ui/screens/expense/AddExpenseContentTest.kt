package com.splitfree.ui.screens.expense

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import android.view.View
import androidx.activity.OnBackPressedDispatcher
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.ViewRootForTest
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.assertWidthIsEqualTo
import androidx.compose.ui.test.click
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.splitfree.R
import com.splitfree.domain.model.expense.SplitEntry
import com.splitfree.domain.model.expense.SplitType
import com.splitfree.ui.theme.SplitFreeTheme
import com.splitfree.ui.viewmodels.AddExpenseUiState
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35], qualifiers = "en-rUS-w390dp-h844dp-mdpi")
class AddExpenseContentTest {
    @get:Rule
    val compose = createComposeRule()

    private var state by mutableStateOf(readyState())
    private lateinit var backDispatcher: OnBackPressedDispatcher
    private lateinit var contentView: View
    private var renderedFontScale = 1f
    private var renderedLayoutDirection = LayoutDirection.Ltr

    @Test
    fun `amount forwards raw decimal input without changing supplied state`() {
        val amounts = mutableListOf<String>()
        render(ExpenseEditorActions(amount = { amounts += it }))

        formNode("expense_amount").assertTextContains("1200").performTextReplacement("12.50")

        compose.runOnIdle {
            assertEquals(listOf("12.50"), amounts)
            assertEquals("1200", state.amount)
        }
    }

    @Test
    fun `description forwards edited text`() {
        val descriptions = mutableListOf<String>()
        render(ExpenseEditorActions(description = { descriptions += it }))

        formNode("expense_description").assertTextContains("Lunch").performTextReplacement("Dinner with friends")

        compose.runOnIdle { assertEquals(listOf("Dinner with friends"), descriptions) }
    }

    @Test
    fun `currency search selects three decimal currency and dismisses sheet`() {
        val currencies = mutableListOf<String>()
        render(ExpenseEditorActions(currency = { currencies += it }))

        formNode("expense_currency").performClick()
        compose.onNodeWithTag("expense_search").performTextReplacement("kwd")
        compose.onNodeWithTag("currency_INR").assertDoesNotExist()
        sheetNode("currency_KWD").assertTextContains("KWD", substring = true).performAccessibleClick()

        compose.runOnIdle { assertEquals(listOf("KWD"), currencies) }
        compose.onNodeWithTag("expense_sheet_list").assertDoesNotExist()
    }

    @Test
    fun `unknown currency search shows empty result without dispatching selection`() {
        val currencies = mutableListOf<String>()
        render(ExpenseEditorActions(currency = { currencies += it }))

        formNode("expense_currency").performClick()
        compose.onNodeWithTag("expense_search").performTextReplacement("not-a-currency")

        compose.onNodeWithText(text(R.string.expense_no_currencies_found)).assertIsDisplayed()
        compose.onAllNodes(
            SemanticsMatcher("Currency option") {
                it.config.getOrNull(SemanticsProperties.TestTag)?.startsWith("currency_") == true
            }
        ).assertCountEquals(0)
        compose.runOnIdle {
            assertTrue("Searching must not change currency", currencies.isEmpty())
            assertEquals("INR", state.currency)
        }
    }

    @Test
    fun `currency display name search selects CHF beyond the former shortlist`() {
        val currencies = mutableListOf<String>()
        render(
            ExpenseEditorActions(currency = {
                currencies += it
                state = state.copy(currency = it)
            })
        )

        formNode("expense_currency").performClick()
        compose.onNodeWithTag("expense_search").performTextReplacement("  sWiSs FrAnC  ")
        sheetNode("currency_CHF").assertTextContains("CHF", substring = true).performAccessibleClick()

        compose.runOnIdle { assertEquals(listOf("CHF"), currencies) }
        compose.onNodeWithTag("expense_sheet_list").assertDoesNotExist()
        formNode("expense_currency").assertTextContains("CHF")
    }

    @Test
    fun `identical payer display names retain separately selectable member keys`() {
        state = state.copy(
            memberNames = state.memberNames + mapOf(
                "member-1" to "Example member",
                "member-2" to "Example member"
            )
        )
        val payers = mutableListOf<String>()
        render(
            ExpenseEditorActions(payer = {
                payers += it
                state = state.copy(paidBy = it)
            })
        )

        listOf("member-1", "member-2").forEach { key ->
            formNode("expense_payer").performClick()
            compose.onNodeWithTag("expense_search").performTextReplacement("Example member")
            sheetNode("payer_member-1").assertTextContains("Example member · member-1")
            sheetNode("payer_member-2").assertTextContains("Example member · member-2")
            if (key == "member-2") {
                compose.onNodeWithTag("expense_search").performTextReplacement("  MEMBER-2  ")
                compose.onNodeWithTag("payer_member-1").assertDoesNotExist()
                sheetNode("payer_member-2").assertTextContains("Example member · member-2")
            }
            sheetNode("payer_$key").performAccessibleClick()
            compose.onNodeWithTag("expense_sheet_list").assertDoesNotExist()
            compose.runOnIdle { assertEquals(key, state.paidBy) }
            formNode("expense_payer").assertTextContains("Example member · $key")
        }

        compose.runOnIdle { assertEquals(listOf("member-1", "member-2"), payers) }
    }

    @Test
    fun `duplicate payer names lengthen colliding key suffixes until distinct`() {
        val keys = listOf("member-a-12345678", "member-b-12345678")
        state = state.copy(
            members = listOf("you") + keys,
            participants = (listOf("you") + keys).toSet(),
            memberNames = keys.associateWith { "Example member" }
        )
        val payers = mutableListOf<String>()
        render(ExpenseEditorActions(payer = { payers += it }))

        formNode("expense_payer").performClick()
        compose.onNodeWithTag("expense_search").performTextReplacement("Example member")
        sheetNode("payer_${keys[0]}").assertTextContains("Example member · a-12345678")
        sheetNode("payer_${keys[1]}").assertTextContains("Example member · b-12345678").performAccessibleClick()

        compose.runOnIdle { assertEquals(listOf(keys[1]), payers) }
    }

    @Test
    fun `payer category and currency choices expose selected radio button semantics`() {
        render()

        listOf(
            Triple("expense_payer", "payer_you", "payer_member-1"),
            Triple("expense_category", "category_", "category_food"),
            Triple("expense_currency", "currency_INR", "currency_USD")
        ).forEach { (editor, selected, unselected) ->
            formNode(editor).performClick()
            sheetNode(selected)
                .assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.RadioButton))
                .assertIsSelected()
            sheetNode(unselected)
                .assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.RadioButton))
                .assertIsNotSelected()
            compose.onNodeWithText(text(R.string.expense_split_done)).performAccessibleClick()
            compose.onNodeWithTag("expense_sheet_list").assertDoesNotExist()
        }
    }

    @Test
    fun `percentage editor shows fifth and sixth members computed money and percent input`() {
        assertLaterMemberAllocations(SplitType.PERCENTAGE, "10", "50")
    }

    @Test
    fun `shares editor shows fifth and sixth members computed money and share input`() {
        assertLaterMemberAllocations(SplitType.SHARES, "1", "5")
    }

    @Test
    fun `payer search matches display name case insensitively and returns member key`() {
        val payers = mutableListOf<String>()
        render(ExpenseEditorActions(payer = { payers += it }))

        formNode("expense_payer").performClick()
        compose.onNodeWithTag("expense_search").performTextReplacement("mEmBeR 1")
        compose.onNodeWithTag("payer_you").assertDoesNotExist()
        sheetNode("payer_member-1").assertTextContains("Member 1").performAccessibleClick()

        compose.runOnIdle { assertEquals(listOf("member-1"), payers) }
        compose.onNodeWithTag("expense_sheet_list").assertDoesNotExist()
    }

    @Test
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun `payer sheet handles Android pointer events in its own view coordinates`() {
        val payers = mutableListOf<String>()
        render(ExpenseEditorActions(payer = { payers += it }))
        formNode("expense_payer").performClick()
        val node = sheetNode("payer_member-1").assertTextContains("Member 1").fetchSemanticsNode()
        val view = (requireNotNull(node.root) as ViewRootForTest).view
        val center = node.boundsInRoot.center
        compose.runOnIdle {
            val downTime = SystemClock.uptimeMillis()
            listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP).forEachIndexed { index, action ->
                val event = MotionEvent.obtain(downTime, downTime + index * 16L, action, center.x, center.y, 0)
                try {
                    event.source = InputDevice.SOURCE_TOUCHSCREEN
                    assertTrue("Sheet must consume pointer action $action", view.dispatchTouchEvent(event))
                } finally {
                    event.recycle()
                }
            }
        }
        compose.runOnIdle { assertEquals(listOf("member-1"), payers) }
        compose.onNodeWithTag("expense_sheet_list").assertDoesNotExist()
    }

    @Test
    fun `payer search with no match does not dispatch selection`() {
        val payers = mutableListOf<String>()
        render(ExpenseEditorActions(payer = { payers += it }))

        formNode("expense_payer").performClick()
        compose.onNodeWithTag("expense_search").performTextReplacement("Nobody here")

        compose.onNodeWithText(text(R.string.expense_no_people_found)).assertIsDisplayed()
        compose.runOnIdle { assertTrue(payers.isEmpty()) }
    }

    @Test
    fun `category selection scrolls to option and returns stable category key`() {
        val categories = mutableListOf<String>()
        render(ExpenseEditorActions(category = { categories += it }))

        formNode("expense_category").performClick()
        sheetNode("category_health").performAccessibleClick()

        compose.runOnIdle { assertEquals(listOf("health"), categories) }
        compose.onNodeWithTag("expense_sheet_list").assertDoesNotExist()
    }

    @Test
    fun `exact split dispatches mode and per member raw amount`() {
        val types = mutableListOf<SplitType>()
        val inputs = mutableListOf<Pair<String, String>>()
        render(
            ExpenseEditorActions(
                splitType = {
                    types += it
                    state = state.copy(splitType = it)
                },
                memberInput = { key, value -> inputs += key to value }
            )
        )

        formNode("expense_split").performClick()
        sheetNode("split_EQUAL").assertIsSelected()
        sheetNode("split_EXACT").performAccessibleClick().assertIsSelected()
        sheetNode("split_input_member-1").performTextReplacement("400.25")

        compose.runOnIdle {
            assertEquals(listOf(SplitType.EXACT), types)
            assertEquals(listOf("member-1" to "400.25"), inputs)
        }
        compose.onNodeWithTag("expense_sheet_list").assertIsDisplayed()
    }

    @Test
    fun `member selection toggles participation and removes excluded exact input`() {
        state = state.copy(splitType = SplitType.EXACT, memberInputs = mapOf("member-1" to "300"))
        val members = mutableListOf<String>()
        render(
            ExpenseEditorActions(participant = { key ->
                members += key
                state = state.copy(participants = state.participants - key)
            })
        )

        formNode("expense_split").performClick()
        sheetNode("participant_member-1").assertIsOn().performAccessibleClick().assertIsOff()

        compose.onNodeWithTag("split_input_member-1").assertDoesNotExist()
        sheetNode("participant_you").assertIsOn()
        compose.runOnIdle { assertEquals(listOf("member-1"), members) }
    }

    @Test
    fun `split search toggles matching member without dropping hidden participants`() {
        val toggled = mutableListOf<String>()
        render(
            ExpenseEditorActions(participant = { key ->
                toggled += key
                state = state.copy(participants = state.participants - key)
            })
        )
        formNode("expense_split").performClick()
        compose.onNodeWithTag("expense_search").performTextReplacement("mEmBeR 1")
        compose.onNodeWithTag("participant_you").assertDoesNotExist()
        compose.onNodeWithTag("participant_member-2").assertDoesNotExist()
        compose.runOnIdle { assertEquals(setOf("you", "member-1", "member-2", "member-3"), state.participants) }
        sheetNode("participant_member-1").assertIsOn().performAccessibleClick().assertIsOff()
        compose.runOnIdle {
            assertEquals(listOf("member-1"), toggled)
            assertEquals(setOf("you", "member-2", "member-3"), state.participants)
        }
        compose.onNodeWithTag("expense_search").performTextReplacement("")
        listOf("you", "member-2", "member-3").forEach { sheetNode("participant_$it").assertIsOn() }
        sheetNode("participant_member-1").assertIsOff()
    }

    @Test
    fun `saving disables every edit save and toolbar and system Back`() {
        state = state.copy(saving = true, dirty = true)
        val events = mutableListOf<String>()
        render(
            ExpenseEditorActions(
                amount = { events += "amount" },
                description = { events += "description" },
                currency = { events += "currency" },
                payer = { events += "payer" },
                category = { events += "category" },
                splitType = { events += "split" },
                save = { events += "save" },
                back = { events += "back" }
            )
        )

        listOf(
            "expense_amount",
            "expense_description",
            "expense_currency",
            "expense_payer",
            "expense_split",
            "expense_category"
        )
            .forEach { tag -> formNode(tag).assertIsNotEnabled().performTouchInput { click() } }
        compose.onNodeWithTag("expense_save").assertIsNotEnabled().performTouchInput { click() }
        compose.onNodeWithTag("expense_back").assertIsNotEnabled().performTouchInput { click() }
        compose.onNodeWithText(text(R.string.expense_saving)).assertIsDisplayed()
        compose.runOnIdle { backDispatcher.onBackPressed() }

        compose.onNodeWithText(text(R.string.expense_discard_title)).assertDoesNotExist()
        compose.onNodeWithTag("expense_sheet_list").assertDoesNotExist()
        compose.runOnIdle { assertTrue("Saving must not dispatch edits or navigation", events.isEmpty()) }
    }

    @Test
    fun `entering saving closes an already open editor sheet`() {
        render()
        formNode("expense_split").performClick()
        compose.onNodeWithTag("expense_sheet_list").assertIsDisplayed()

        compose.runOnIdle { state = state.copy(saving = true) }

        compose.onNodeWithTag("expense_sheet_list").assertDoesNotExist()
        compose.onNodeWithTag("expense_save").assertIsNotEnabled()
    }

    @Test
    fun `dirty exit keeps draft until user explicitly confirms discard`() {
        state = state.copy(dirty = true)
        var backs = 0
        render(ExpenseEditorActions(back = { backs++ }))

        compose.onNodeWithTag("expense_back").performClick()
        compose.onNodeWithText(text(R.string.expense_discard_title)).assertIsDisplayed()
        compose.runOnIdle { assertEquals(0, backs) }
        compose.onNodeWithText(text(R.string.expense_keep_editing)).performClick()
        compose.onNodeWithText(text(R.string.expense_discard_title)).assertDoesNotExist()
        formNode("expense_description").assertTextContains("Lunch")
        compose.runOnIdle {
            assertEquals(0, backs)
            backDispatcher.onBackPressed()
        }
        compose.onNodeWithText(text(R.string.expense_discard_title)).assertIsDisplayed()
        compose.onNodeWithText(text(R.string.expense_discard)).performClick()

        compose.onNodeWithText(text(R.string.expense_discard_title)).assertDoesNotExist()
        compose.runOnIdle { assertEquals(1, backs) }
    }

    @Test
    fun `clean Back navigates immediately without discard dialog`() {
        var backs = 0
        render(ExpenseEditorActions(back = { backs++ }))

        compose.onNodeWithTag("expense_back").performClick()

        compose.onNodeWithText(text(R.string.expense_discard_title)).assertDoesNotExist()
        compose.runOnIdle { assertEquals(1, backs) }
    }

    @Test
    fun `loading shows progress copy without exposing form or save`() {
        state = AddExpenseUiState()
        render()

        compose.onNodeWithText(text(R.string.expense_loading)).assertIsDisplayed()
        compose.onNodeWithTag("expense_form").assertDoesNotExist()
        compose.onNodeWithTag("expense_save").assertDoesNotExist()
        compose.onNodeWithTag("expense_back").assertIsEnabled()
    }

    @Test
    fun `loading error displays reason and retries only on request`() {
        state = AddExpenseUiState(loading = false, loadingError = "This group is no longer available")
        var retries = 0
        render(ExpenseEditorActions(retry = { retries++ }))

        compose.onNodeWithText("This group is no longer available").assertIsDisplayed()
        compose.onNodeWithTag("expense_form").assertDoesNotExist()
        compose.onNodeWithTag("expense_save").assertDoesNotExist()
        compose.runOnIdle { assertEquals(0, retries) }
        compose.onNodeWithText(text(R.string.expense_retry)).performClick()

        compose.runOnIdle { assertEquals(1, retries) }
    }

    @Test
    fun `validation messages are visible and save dispatches submission`() {
        state =
            state.copy(
                amountError = "Enter an amount",
                descriptionError = "Add a description",
                error = "Could not save expense"
            )
        var saves = 0
        render(ExpenseEditorActions(save = { saves++ }))

        formNode("expense_amount").assertTextContains("Enter an amount")
        formNode("expense_description").assertTextContains("Add a description")
        compose.onNodeWithTag("expense_save_error").assertIsDisplayed()
        compose.onNodeWithText("Could not save expense").assertIsDisplayed()
        compose.onNodeWithTag("expense_save").assertIsEnabled().performClick()

        compose.runOnIdle { assertEquals(1, saves) }
    }

    @Test
    fun `locked draft exposes recovery retry instead of allowing edits`() {
        state = state.copy(editable = false, error = "Check the previously saved expense before editing")
        var retries = 0
        render(ExpenseEditorActions(retry = { retries++ }))

        formNode("expense_amount").assertIsNotEnabled()
        compose.onNodeWithTag("expense_save").assertIsNotEnabled()
        compose.onNodeWithTag("expense_save_error").assertIsDisplayed()
        compose.onNodeWithText("Check the previously saved expense before editing").assertIsDisplayed()
        compose.onNodeWithText(text(R.string.expense_retry)).performClick()

        compose.runOnIdle { assertEquals(1, retries) }
    }

    @Test
    fun `save error remains visible above Save without scrolling a long form`() {
        state = state.copy(groupName = "Example group with a long description ".repeat(30))
        var saves = 0
        render(ExpenseEditorActions(save = { saves++ }))
        compose.onNodeWithTag("expense_save_error").assertDoesNotExist()

        compose.runOnIdle { state = state.copy(error = "Could not save expense") }

        val errorBounds = compose.onNodeWithTag("expense_save_error").assertIsDisplayed()
            .fetchSemanticsNode().boundsInRoot
        val saveBounds = compose.onNodeWithTag("expense_save").assertIsDisplayed().assertIsEnabled()
            .fetchSemanticsNode().boundsInRoot
        compose.onNodeWithText("Could not save expense").assertIsDisplayed()
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.LiveRegion, LiveRegionMode.Polite))
        assertTrue("Save error must sit directly above Save", saveBounds.top - errorBounds.bottom in 0f..16f)
        compose.onNodeWithTag("expense_save").performClick()

        compose.runOnIdle { assertEquals(1, saves) }
    }

    @Test
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun `light native graphics fixture captures rendered expense screen`() {
        render(dark = false)
        captureFixture("light")
    }

    @Test
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun `dark native graphics fixture captures rendered expense screen`() {
        render(dark = true)
        captureFixture("dark")
    }

    @Test
    @Config(qualifiers = "en-rUS-w360dp-h800dp-mdpi")
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun `360dp at 200 percent text keeps Save visible and every form control reachable`() {
        RuntimeEnvironment.setFontScale(2f)
        var saves = 0
        render(ExpenseEditorActions(save = { saves++ }))
        compose.runOnIdle { assertEquals("Compose must actually use 200 percent text", 2f, renderedFontScale, 0f) }
        compose.onNodeWithTag("expense_save").assertIsDisplayed().assertIsEnabled()
        listOf("expense_amount", "expense_description", "expense_payer", "expense_split", "expense_category")
            .forEach { tag ->
                formNode(tag).assertIsEnabled()
                compose.onNodeWithTag("expense_save").assertIsDisplayed()
            }
        formNode("expense_amount").assertTextContains("1200")
        captureFixture("360dp-font200", expectedWidth = 360)
        compose.onNodeWithTag("expense_save").performClick()
        compose.runOnIdle { assertEquals(1, saves) }
    }

    @Test
    @Config(qualifiers = "en-rUS-w320dp-h740dp-mdpi")
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun `320dp with long names and many members keeps Save and last participant reachable`() {
        val members = (1..24).map { "member_$it" }
        val lastName = "Example participant with a deliberately long display name"
        state = state.copy(
            groupName = "Extended family summer holiday and shared celebration expenses",
            members = members,
            memberNames =
            members.associateWith { "Example participant with a deliberately long display name $it" } +
                (members.last() to lastName),
            myPubkey = members.first(),
            paidBy = members.last(),
            participants = members.toSet(),
            previewSplits = members.map { SplitEntry(it, 5000) }
        )
        render()
        formNode("expense_payer").assertTextContains(lastName)
        compose.onNodeWithTag("expense_save").assertIsDisplayed().assertIsEnabled()
        captureFixture("320dp-long-names", expectedWidth = 320)
        formNode("expense_split").performClick()
        sheetNode("participant_member_24").assertIsOn().assertTextContains(lastName)
        compose.onNodeWithText(text(R.string.expense_split_done)).assertIsDisplayed().performAccessibleClick()
        compose.onNodeWithTag("expense_sheet_list").assertDoesNotExist()
        compose.onNodeWithTag("expense_save").assertIsDisplayed().assertIsEnabled()
    }

    @Test
    @Config(qualifiers = "en-rUS-ldrtl-w390dp-h844dp-mdpi")
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun `RTL fixture mirrors navigation and currency while Save remains usable`() {
        var saves = 0
        render(ExpenseEditorActions(save = { saves++ }), layoutDirection = LayoutDirection.Rtl)

        compose.runOnIdle { assertEquals(LayoutDirection.Rtl, renderedLayoutDirection) }
        val backBounds = compose.onNodeWithTag("expense_back").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        val currencyBounds = formNode("expense_currency").fetchSemanticsNode().boundsInRoot
        assertTrue("RTL Back must be on the right", backBounds.center.x > 195f)
        assertTrue("RTL currency picker must be at the row's left end", currencyBounds.center.x < 195f)
        formNode("expense_description").assertTextContains("Lunch")
        compose.onNodeWithTag("expense_save").assertIsDisplayed().assertIsEnabled()
        captureFixture("rtl")
        compose.onNodeWithTag("expense_save").performClick()

        compose.runOnIdle { assertEquals(1, saves) }
    }

    @Test
    @Config(qualifiers = "en-rUS-w800dp-h1200dp-mdpi")
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun `800dp tablet centers 600dp form and aligns Save with padded fields`() {
        var saves = 0
        render(ExpenseEditorActions(save = { saves++ }))

        val formBounds = compose.onNodeWithTag("expense_form").assertIsDisplayed()
            .assertWidthIsEqualTo(600.dp).fetchSemanticsNode().boundsInRoot
        val amountBounds = formNode("expense_amount").assertWidthIsEqualTo(560.dp).fetchSemanticsNode().boundsInRoot
        val saveBounds = compose.onNodeWithTag("expense_save").assertIsDisplayed().assertIsEnabled()
            .assertWidthIsEqualTo(560.dp).fetchSemanticsNode().boundsInRoot
        assertEquals("Form must be centered on the tablet", 400f, formBounds.center.x, 0.5f)
        assertEquals("Save must align with the amount field's left edge", amountBounds.left, saveBounds.left, 0.5f)
        assertEquals("Save must align with the amount field's right edge", amountBounds.right, saveBounds.right, 0.5f)
        formNode("expense_category").assertIsEnabled()
        captureFixture("800dp-tablet", expectedWidth = 800)
        compose.onNodeWithTag("expense_save").performClick()

        compose.runOnIdle { assertEquals(1, saves) }
    }

    private fun assertLaterMemberAllocations(type: SplitType, regularInput: String, lastInput: String) {
        val keys = (1..6).map { "member-$it" }
        state = state.copy(
            members = keys,
            memberNames = keys.associateWith { "Example $it" },
            myPubkey = keys.first(),
            paidBy = keys.first(),
            participants = keys.toSet(),
            splitType = type,
            memberInputs = keys.associateWith { if (it == keys.last()) lastInput else regularInput },
            previewSplits = keys.map { SplitEntry(it, if (it == keys.last()) 60000 else 12000) }
        )
        render()
        formNode("expense_split").performClick()

        listOf(Triple("member-5", "₹120.00", regularInput), Triple("member-6", "₹600.00", lastInput))
            .forEach { (key, allocation, input) ->
                sheetNode("participant_$key").assertIsOn()
                compose.onNodeWithTag("split_allocation_$key", useUnmergedTree = true)
                    .assertIsDisplayed().assertTextContains(allocation)
                sheetNode("split_input_$key").assertTextContains(input)
            }
    }

    private fun render(
        actions: ExpenseEditorActions = ExpenseEditorActions(),
        dark: Boolean = false,
        layoutDirection: LayoutDirection? = null
    ) {
        compose.setContent {
            backDispatcher = requireNotNull(LocalOnBackPressedDispatcherOwner.current).onBackPressedDispatcher
            renderedFontScale = LocalDensity.current.fontScale
            contentView = LocalView.current
            CompositionLocalProvider(LocalLayoutDirection provides (layoutDirection ?: LocalLayoutDirection.current)) {
                renderedLayoutDirection = LocalLayoutDirection.current
                SplitFreeTheme(darkTheme = dark) { AddExpenseContent(state, actions) }
            }
        }
    }

    private fun formNode(tag: String) = compose.onNodeWithTag("expense_form")
        .performScrollToNode(hasTestTag(tag)).let { compose.onNodeWithTag(tag).assertIsDisplayed() }

    private fun sheetNode(tag: String) = compose.onNodeWithTag("expense_sheet_list")
        .performScrollToNode(hasTestTag(tag)).let { compose.onNodeWithTag(tag).assertIsDisplayed() }

    private fun SemanticsNodeInteraction.performAccessibleClick(): SemanticsNodeInteraction =
        assertIsEnabled().performSemanticsAction(SemanticsActions.OnClick) { action ->
            assertTrue("Accessibility click must be handled", action())
        }

    private fun text(resource: Int): String = RuntimeEnvironment.getApplication().getString(resource)

    private fun captureFixture(mode: String, expectedWidth: Int = 390) {
        compose.onNodeWithTag("expense_save").assertIsDisplayed()
        // Compose captureToImage waits for a window redraw that Robolectric does not schedule.
        val bitmap = compose.runOnIdle {
            val decor = contentView.rootView
            assertTrue("Screenshot view must be attached and laid out", decor.isAttachedToWindow && decor.isLaidOut)
            Bitmap.createBitmap(decor.width, decor.height, Bitmap.Config.ARGB_8888).also {
                decor.draw(Canvas(it))
            }
        }
        assertEquals("Capture must use the configured screen width", expectedWidth, bitmap.width)
        assertTrue("Capture must have screen height", bitmap.height >= 600)
        val pixels = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        assertTrue("Capture must contain rendered content, not a blank bitmap", pixels.toSet().size > 16)
        val output = File("build/outputs/expense-screenshots/expense-render-$mode.png")
        val directory = requireNotNull(output.parentFile)
        assertTrue("Screenshot directory must exist", directory.isDirectory || directory.mkdirs())
        output.outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        assertTrue("Screenshot PNG must be nonempty", output.length() > 0)
    }

    private fun readyState() = AddExpenseUiState(
        loading = false,
        editable = true,
        groupName = "Example group",
        members = listOf("you", "member-1", "member-2", "member-3"),
        memberNames = mapOf("member-1" to "Member 1", "member-2" to "Member 2", "member-3" to "Member 3"),
        myPubkey = "you",
        amount = "1200",
        description = "Lunch",
        currency = "INR",
        paidBy = "you",
        participants = setOf("you", "member-1", "member-2", "member-3"),
        previewSplits = listOf(
            SplitEntry("you", 30000),
            SplitEntry("member-1", 30000),
            SplitEntry("member-2", 30000),
            SplitEntry("member-3", 30000)
        )
    )
}
