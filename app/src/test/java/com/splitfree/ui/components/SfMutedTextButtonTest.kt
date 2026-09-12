package com.splitfree.ui.components

import android.app.Application
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import com.splitfree.ui.theme.SplitFreeTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35], qualifiers = "en-rUS-w390dp-h844dp-mdpi")
class SfMutedTextButtonTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun `muted text button keeps a 48dp target and forwards clicks`() {
        var clicks = 0
        compose.setContent {
            SplitFreeTheme { SfMutedTextButton(text = "Restore an existing identity", onClick = { clicks++ }) }
        }

        compose.onNodeWithText("Restore an existing identity")
            .assertIsDisplayed()
            .assertIsEnabled()
            .assertHasClickAction()
            .assertHeightIsAtLeast(48.dp)
            .performClick()

        compose.runOnIdle { assertEquals(1, clicks) }
    }

    @Test
    fun `disabled muted text button does not fire`() {
        var clicks = 0
        compose.setContent {
            SplitFreeTheme { SfMutedTextButton(text = "Later", onClick = { clicks++ }, enabled = false) }
        }

        compose.onNodeWithText("Later").assertIsNotEnabled().performClick()

        compose.runOnIdle { assertEquals(0, clicks) }
    }
}
