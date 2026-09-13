package com.splitfree.ui.components

import android.app.Application
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToIndex
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
import org.robolectric.annotation.GraphicsMode

/** Layout contract of [SfSheetContent]: the footer keeps its height and the body yields to it. */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35], qualifiers = "en-rUS-w640dp-h360dp-land-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SfSheetLayoutTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun `footer keeps its full height under large text while a long body scrolls`() {
        RuntimeEnvironment.setFontScale(2f)
        var clicks = 0
        compose.setContent {
            SplitFreeTheme {
                Box(Modifier.fillMaxSize()) {
                    SfSheetContent("Profile", {}, scrollable = true, footer = {
                        SfSheetFooter(secondary = null) {
                            SfPrimaryButton("Save", { clicks++ }, modifier = Modifier.testTag("save"))
                        }
                    }) {
                        repeat(20) { Text("Body line $it", modifier = Modifier.testTag("line_$it")) }
                    }
                }
            }
        }

        val save = compose.onNodeWithTag("save").assertIsDisplayed().fetchSemanticsNode()
        assertTrue("Footer button must keep a full touch target", save.boundsInRoot.height >= 48f)
        assertEquals("Footer button must not be clipped", save.size.height.toFloat(), save.boundsInRoot.height, 1f)
        var ancestor = save.parent
        while (ancestor != null) {
            assertFalse(
                "Footer must sit outside the scrolling body",
                ancestor.config.contains(SemanticsActions.ScrollBy)
            )
            ancestor = ancestor.parent
        }
        compose.onNodeWithTag("line_19").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("save").assertIsDisplayed().performClick()
        compose.runOnIdle { assertEquals(1, clicks) }
    }

    @Test
    fun `a bottom inset shrinks the body and moves the footer up with it`() {
        RuntimeEnvironment.setFontScale(2f)
        var inset by mutableStateOf(0)
        compose.setContent {
            SplitFreeTheme {
                Box(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets(bottom = inset))) {
                    SfSheetContent("Profile", {}, scrollable = true, footer = {
                        SfSheetFooter(secondary = null) {
                            SfPrimaryButton("Save", {}, modifier = Modifier.testTag("save"))
                        }
                    }) {
                        repeat(20) { Text("Body line $it") }
                    }
                }
            }
        }

        val before = compose.onNodeWithTag("save").fetchSemanticsNode().boundsInRoot.bottom
        inset = 120
        val save = compose.onNodeWithTag("save").assertIsDisplayed().fetchSemanticsNode()
        assertTrue("Footer must rise by the inset", save.boundsInRoot.bottom <= before - 120f)
        assertEquals("Footer must not be clipped", save.size.height.toFloat(), save.boundsInRoot.height, 1f)
    }

    @Test
    fun `a non scrollable body gives a caller owned lazy list finite height`() {
        compose.setContent {
            SplitFreeTheme {
                SfSheetContent("Picker", {}, scrollable = false, footer = null) {
                    LazyColumn(Modifier.weight(1f, fill = false).testTag("picker")) {
                        items(500, key = { it }) { Text("Choice $it", modifier = Modifier.testTag("choice_$it")) }
                    }
                }
            }
        }

        compose.onNodeWithTag("choice_499").assertDoesNotExist()
        compose.onNodeWithTag("picker").performScrollToIndex(499)
        compose.onNodeWithTag("choice_499").assertIsDisplayed()
    }
}
