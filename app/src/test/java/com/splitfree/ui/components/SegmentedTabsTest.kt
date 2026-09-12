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
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.splitfree.ui.theme.SplitFreeTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35], qualifiers = "en-rUS-w390dp-h844dp-mdpi")
class SegmentedTabsTest {
    @get:Rule
    val compose = createComposeRule()

    private val labels = listOf("Summary", "Expenses", "People")

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
    fun `an out of range selection renders without a selected segment and without crashing`() {
        compose.setContent {
            SplitFreeTheme { SegmentedTabs(options = labels, selectedIndex = -1, onSelect = {}) }
        }

        labels.forEach { compose.onNodeWithText(it).assertIsNotSelected() }
    }
}
