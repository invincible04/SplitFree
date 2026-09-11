package com.splitfree.ui.components

import android.app.Application
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.DirectionsCar
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Lightbulb
import androidx.compose.material.icons.outlined.MedicalServices
import androidx.compose.material.icons.outlined.Receipt
import androidx.compose.material.icons.outlined.Restaurant
import androidx.compose.material.icons.outlined.ShoppingBag
import androidx.compose.material.icons.outlined.Theaters
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertWidthIsAtLeast
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import com.splitfree.ui.theme.SplitFreeTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35], qualifiers = "en-rUS-w390dp-h844dp-mdpi")
class UiKitComponentsTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun `expense category keys map to outlined icons and fall back to a receipt`() {
        assertSame(Icons.Outlined.Restaurant, expenseCategoryIcon("food"))
        assertSame(Icons.Outlined.DirectionsCar, expenseCategoryIcon("transport"))
        assertSame(Icons.Outlined.DirectionsCar, expenseCategoryIcon("travel"))
        assertSame(Icons.Outlined.ShoppingBag, expenseCategoryIcon("shopping"))
        assertSame(Icons.Outlined.Theaters, expenseCategoryIcon("entertainment"))
        assertSame(Icons.Outlined.Lightbulb, expenseCategoryIcon("utilities"))
        assertSame(Icons.Outlined.Home, expenseCategoryIcon("rent"))
        assertSame(Icons.Outlined.Home, expenseCategoryIcon("housing"))
        assertSame(Icons.Outlined.MedicalServices, expenseCategoryIcon("health"))
        assertSame(Icons.Outlined.Receipt, expenseCategoryIcon("other"))
        assertSame(Icons.Outlined.Receipt, expenseCategoryIcon(""))
        assertSame(Icons.Outlined.Receipt, expenseCategoryIcon("something new"))
        assertSame("Lookup must ignore case and whitespace", Icons.Outlined.Restaurant, expenseCategoryIcon("  FOOD "))
    }

    @Test
    fun `segmented tabs expose Tab roles with one selected segment and switch on click`() {
        var selected by mutableIntStateOf(0)
        compose.setContent {
            SplitFreeTheme {
                SegmentedTabs(options = listOf("Summary", "Expenses", "People"), selectedIndex = selected, onSelect = {
                    selected =
                        it
                })
            }
        }

        listOf("Summary", "Expenses", "People").forEach { label ->
            compose.onNodeWithText(label)
                .assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Tab))
                .assertHeightIsAtLeast(48.dp)
                .assertHasClickAction()
        }
        compose.onNodeWithText("Summary").assertIsSelected()
        compose.onNodeWithText("Expenses").assertIsNotSelected()

        compose.onNodeWithText("Expenses").performClick()

        compose.runOnIdle { assertEquals(1, selected) }
        compose.onNodeWithText("Expenses").assertIsSelected()
        compose.onNodeWithText("Summary").assertIsNotSelected()
    }

    @Test
    fun `choice rows are radio buttons with selected state and a 56dp target`() {
        var selected by mutableIntStateOf(0)
        compose.setContent {
            SplitFreeTheme {
                Column(Modifier.selectableGroup()) {
                    ChoiceRow(
                        title = "Equally",
                        subtitle = "Everyone pays the same",
                        selected = selected == 0,
                        onClick = { selected = 0 }
                    )
                    ChoiceRow(title = "Exact amounts", subtitle = null, selected = selected == 1, onClick = {
                        selected =
                            1
                    })
                    ChoiceRow(title = "Locked", subtitle = null, selected = false, onClick = {
                        selected = 2
                    }, enabled = false)
                }
            }
        }

        compose.onNodeWithText("Equally")
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.RadioButton))
            .assertIsSelected()
            .assertHeightIsAtLeast(56.dp)
        compose.onNodeWithText("Exact amounts")
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.RadioButton))
            .assertIsNotSelected()
        compose.onNodeWithText("Locked").assertIsNotEnabled()

        compose.onNodeWithText("Exact amounts").performClick()

        compose.runOnIdle { assertEquals(1, selected) }
        compose.onNodeWithText("Exact amounts").assertIsSelected()
        compose.onNodeWithText("Equally").assertIsNotSelected()
    }

    @Test
    fun `primary button hides its label while loading and blocks clicks`() {
        var clicks = 0
        compose.setContent {
            SplitFreeTheme {
                Column {
                    SfPrimaryButton(text = "Save expense", onClick = { clicks++ })
                    SfPrimaryButton(text = "Saving", onClick = { clicks++ }, loading = true)
                }
            }
        }

        compose.onNodeWithText("Save expense").assertIsEnabled().assertHeightIsAtLeast(56.dp).performClick()
        compose.onNodeWithText("Saving").assertIsNotEnabled().performClick()
        compose.onNodeWithContentDescription("Loading").assertIsDisplayed()
        compose.runOnIdle { assertEquals(1, clicks) }
    }

    @Test
    fun `member avatar announces the member name and picks a stable palette colour`() {
        compose.setContent {
            SplitFreeTheme {
                Column {
                    MemberAvatar(pubkey = "member-1", name = "Member 2")
                    MemberAvatar(pubkey = "abcdef0123456789", name = null)
                }
            }
        }

        compose.onNodeWithContentDescription("Member 2").assertIsDisplayed()
        compose.onNodeWithContentDescription("abcdef0123456789").assertIsDisplayed()
        val index = avatarPaletteIndex("member-1", 6)
        assertEquals(index, avatarPaletteIndex("member-1", 6))
        assertTrue(index in 0 until 6)
        assertTrue(avatarPaletteIndex("\u0000\u0000\u0000\u0000", 6) in 0 until 6)
    }

    @Test
    fun `icon button and status dot meet touch and description requirements`() {
        compose.setContent {
            SplitFreeTheme {
                Column {
                    SfIconButton(icon = Icons.Outlined.Home, contentDescription = "Home", onClick = {})
                    StatusDot(connected = true, contentDescription = "Connected")
                }
            }
        }

        compose.onNodeWithContentDescription(
            "Home"
        ).assertWidthIsAtLeast(48.dp).assertHeightIsAtLeast(48.dp).assertHasClickAction()
        compose.onNodeWithContentDescription("Connected")
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Image))
            .assertContentDescriptionEquals("Connected")
    }
}
