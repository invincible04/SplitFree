package com.splitfree.ui.screens.expense

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.splitfree.ui.components.SegmentedTabs
import com.splitfree.ui.components.SfTopBar
import com.splitfree.ui.theme.SplitFreeTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Covers the two kit parameters the expense editor relies on for its test tags, plus the symbol helper. */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35], qualifiers = "en-rUS-w390dp-h844dp-mdpi")
class ExpenseEditorKitTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun `segmented tabs apply optionModifier per segment and keep Tab semantics`() {
        var selected by mutableIntStateOf(0)
        val labels = listOf("Equal", "Exact", "Percent", "Shares")
        compose.setContent {
            SplitFreeTheme {
                SegmentedTabs(
                    options = labels,
                    selectedIndex = selected,
                    onSelect = { selected = it },
                    optionModifier = { Modifier.testTag("mode_${labels[it]}") }
                )
            }
        }

        compose.onNodeWithTag("mode_Equal")
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Tab))
            .assertIsSelected()
        compose.onNodeWithTag("mode_Percent").assertIsNotSelected().performClick()

        compose.runOnIdle { assertEquals(2, selected) }
        compose.onNodeWithTag("mode_Percent").assertIsSelected()
        compose.onNodeWithTag("mode_Equal").assertIsNotSelected()
    }

    @Test
    fun `segmented tabs default optionModifier adds no tags`() {
        compose.setContent {
            SplitFreeTheme { SegmentedTabs(options = listOf("Summary", "People"), selectedIndex = 0, onSelect = {}) }
        }

        compose.onAllNodes(
            SemanticsMatcher("has any test tag") { it.config.getOrNull(SemanticsProperties.TestTag) != null }
        ).assertCountEquals(0)
        compose.onNodeWithText("Summary").assertIsSelected()
    }

    @Test
    fun `top bar backModifier tags the back button and honours backEnabled`() {
        var backs = 0
        var enabled by mutableIntStateOf(1)
        compose.setContent {
            SplitFreeTheme {
                SfTopBar(
                    title = "Add expense",
                    onBack = { backs++ },
                    backEnabled = enabled == 1,
                    backModifier = Modifier.testTag("bar_back")
                )
            }
        }

        compose.onNodeWithTag("bar_back").assertIsEnabled().performClick()
        compose.runOnIdle {
            assertEquals(1, backs)
            enabled = 0
        }
        compose.onNodeWithTag("bar_back").assertIsNotEnabled()
    }

    @Test
    fun `currency symbol is the formatter prefix or the code when there is none`() {
        assertEquals("₹", currencySymbol("INR"))
        assertEquals("$", currencySymbol("USD"))
        assertEquals("€", currencySymbol("EUR"))
        assertEquals("KWD", currencySymbol("KWD"))
        assertEquals("Symbol lookup must ignore case", "¥", currencySymbol("jpy"))
    }
}
