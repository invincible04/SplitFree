package com.splitfree.ui.components

import android.app.Application
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
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
class DangerRowTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun `danger row is a 64dp button that merges its title and subtitle`() {
        var clicks = 0
        compose.setContent {
            SplitFreeTheme {
                SfListCard {
                    DangerRow(
                        icon = Icons.Outlined.Lock,
                        title = "Replace identity",
                        subtitle = "Advanced security action",
                        onClick = { clicks++ },
                        modifier = Modifier.testTag("danger")
                    )
                }
            }
        }

        compose.onNodeWithTag("danger")
            .assertIsDisplayed()
            .assertHasClickAction()
            .assertHeightIsAtLeast(64.dp)
            .assert(hasText("Replace identity"))
            .assert(hasText("Advanced security action"))
            .performClick()
        compose.runOnIdle { assertEquals(1, clicks) }
    }

    @Test
    fun `disabled danger row does not fire`() {
        var clicks = 0
        compose.setContent {
            SplitFreeTheme {
                DangerRow(
                    icon = Icons.Outlined.Lock,
                    title = "Replace identity",
                    subtitle = null,
                    onClick = { clicks++ },
                    enabled = false,
                    modifier = Modifier.testTag("danger")
                )
            }
        }

        compose.onNodeWithTag("danger").assertIsNotEnabled().performClick()
        compose.runOnIdle { assertEquals(0, clicks) }
    }
}
