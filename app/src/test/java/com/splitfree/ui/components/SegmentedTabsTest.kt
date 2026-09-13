package com.splitfree.ui.components

import android.app.Application
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.splitfree.ui.theme.SplitFreeTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35], qualifiers = "en-rUS-w390dp-h844dp-mdpi")
class SegmentedTabsTest {
    @get:Rule
    val compose = createComposeRule()

    private val labels = listOf("Summary", "Expenses", "People")

    /** Labels that overflow a 360dp track at 200 percent text when given equal widths. */
    private val wideLabels = listOf("Balances", "Expense history", "Group members", "Settlements")

    @Test
    fun `segments keep tab semantics and a 48dp target while the pill animates between them`() {
        var selected by mutableIntStateOf(0)
        compose.setContent {
            SplitFreeTheme {
                SegmentedTabs(
                    options = labels,
                    selectedIndex = selected,
                    onSelect = { selected = it },
                    optionModifier = { Modifier.testTag("tab_$it") }
                )
            }
        }

        labels.indices.forEach { index ->
            compose.onNodeWithTag("tab_$index")
                .assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Tab))
                .assertHeightIsAtLeast(48.dp)
        }
        compose.onNodeWithTag("tab_0").assertIsSelected()

        compose.onNodeWithTag("tab_2").performClick()

        compose.runOnIdle { assertEquals(2, selected) }
        compose.onNodeWithTag("tab_2").assertIsSelected()
        compose.onNodeWithTag("tab_0").assertIsNotSelected()
        compose.onAllNodes(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Tab)).assertCountEquals(3)
    }

    @Test
    fun `segments lay out mirrored in right to left and still select on click`() {
        var selected by mutableIntStateOf(0)
        compose.setContent {
            CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
                SplitFreeTheme {
                    SegmentedTabs(
                        options = labels,
                        selectedIndex = selected,
                        onSelect = { selected = it },
                        optionModifier = { Modifier.testTag("tab_$it") }
                    )
                }
            }
        }

        val first = compose.onNodeWithTag("tab_0").fetchSemanticsNode().positionInRoot.x
        val last = compose.onNodeWithTag("tab_2").fetchSemanticsNode().positionInRoot.x
        assertTrue("First segment must sit on the right in RTL", first > last)

        compose.onNodeWithTag("tab_1").performClick()

        compose.runOnIdle { assertEquals(1, selected) }
        compose.onNodeWithTag("tab_1").assertIsSelected()
    }

    @Test
    @Config(qualifiers = "en-rUS-w360dp-h640dp-mdpi")
    fun `large text keeps every label on one untruncated line and scrolls each segment into view`() {
        assertLargeTextTabs(LayoutDirection.Ltr)
    }

    @Test
    @Config(qualifiers = "en-rUS-w360dp-h640dp-mdpi")
    fun `large text scrollable segments keep RTL order and the selected segment visible`() {
        assertLargeTextTabs(LayoutDirection.Rtl)
    }

    private fun assertLargeTextTabs(direction: LayoutDirection) {
        RuntimeEnvironment.setFontScale(2f)
        var selected by mutableIntStateOf(0)
        compose.setContent {
            CompositionLocalProvider(LocalLayoutDirection provides direction) {
                SplitFreeTheme {
                    SegmentedTabs(
                        options = wideLabels,
                        selectedIndex = selected,
                        onSelect = { selected = it },
                        optionModifier = { Modifier.testTag("tab_$it") }
                    )
                }
            }
        }

        val first = compose.onNodeWithTag("tab_0").fetchSemanticsNode().positionInRoot.x
        val second = compose.onNodeWithTag("tab_1").fetchSemanticsNode().positionInRoot.x
        assertEquals("Segments must follow the reading direction", direction == LayoutDirection.Rtl, first > second)
        wideLabels.forEachIndexed { index, label ->
            compose.onNodeWithTag("tab_$index").performScrollTo().assertIsDisplayed().performClick()
            compose.onNodeWithTag("tab_$index").assertIsSelected()
            val layouts = mutableListOf<TextLayoutResult>()
            compose.onNodeWithText(label, useUnmergedTree = true)
                .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
            assertEquals("$label must stay on one line", 1, layouts.single().lineCount)
            assertFalse("$label must not be ellipsized", layouts.single().isLineEllipsized(0))
        }
        selected = 0
        compose.onNodeWithTag("tab_0").assertIsDisplayed().assertIsSelected()
    }

    @Test
    fun `an out of range selection renders without a selected segment and without crashing`() {
        compose.setContent {
            SplitFreeTheme { SegmentedTabs(options = labels, selectedIndex = -1, onSelect = {}) }
        }

        labels.forEach { compose.onNodeWithText(it).assertIsNotSelected() }
    }
}
