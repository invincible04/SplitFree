package com.splitfree.ui.screens.onboarding

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import com.splitfree.R
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
class SecretKeyFieldTest {
    @get:Rule
    val compose = createComposeRule()

    private var value by mutableStateOf("")
    private var error by mutableStateOf<String?>(null)

    private val mnemonic = "abandon ability able about above absent absorb abstract absurd abuse access accident"

    private val isPassword = SemanticsMatcher.keyIsDefined(SemanticsProperties.Password)

    private fun render() {
        compose.setContent {
            SplitFreeTheme { SecretKeyField(value = value, onValueChange = { value = it }, error = error) }
        }
    }

    private fun input() = compose.onNodeWithTag("onboarding_secret_input")

    private fun toggle() = compose.onNodeWithTag("onboarding_secret_toggle")

    /** What is actually rendered in the field (after the visual transformation). */
    private fun displayedText(): String =
        input().fetchSemanticsNode().config.getOrNull(SemanticsProperties.EditableText)?.text ?: ""

    private fun text(resource: Int): String = RuntimeEnvironment.getApplication().getString(resource)

    @Test
    fun `field is a password field`() {
        render()
        input().assertIsDisplayed().assert(isPassword)
    }

    @Test
    fun `typed secret is forwarded to the caller but rendered masked`() {
        render()
        input().performTextInput(mnemonic)

        compose.runOnIdle { assertEquals(mnemonic, value) }
        val shown = displayedText()
        assertEquals("masked text keeps the secret's length", mnemonic.length, shown.length)
        assertTrue("masked text must be all bullets, was '$shown'", shown.all { it == '\u2022' })
        assertFalse(shown.contains("abandon"))
    }

    @Test
    fun `eye toggle reveals and hides the secret`() {
        render()
        input().performTextInput(mnemonic)
        toggle().assertIsDisplayed().assertContentDescriptionIs(text(R.string.cd_show_secret))

        toggle().performClick()
        compose.waitForIdle()
        assertEquals(mnemonic, displayedText())
        toggle().assertContentDescriptionIs(text(R.string.cd_hide_secret))

        toggle().performClick()
        compose.waitForIdle()
        assertTrue(displayedText().all { it == '\u2022' })
        toggle().assertContentDescriptionIs(text(R.string.cd_show_secret))
    }

    @Test
    fun `shows supporting text and swaps it for the error`() {
        render()
        compose.onNodeWithText(text(R.string.key_supporting_text)).assertIsDisplayed()

        error = "Invalid key"
        compose.onNodeWithText("Invalid key").assertIsDisplayed()
        compose.onNodeWithText(text(R.string.key_supporting_text)).assertDoesNotExist()
    }

    private fun SemanticsNodeInteraction.assertContentDescriptionIs(expected: String) =
        assert(SemanticsMatcher.expectValue(SemanticsProperties.ContentDescription, listOf(expected)))
}
