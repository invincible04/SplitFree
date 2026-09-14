package com.splitfree.ui.components

import android.app.Application
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import com.splitfree.R
import com.splitfree.domain.invite.InviteLinkCodec
import com.splitfree.domain.util.RelayDefaults
import com.splitfree.ui.theme.SplitFreeTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35], qualifiers = "en-rUS-w390dp-h844dp-mdpi")
class RelayEditorTest {
    @get:Rule
    val compose = createComposeRule()

    private val known = RelayDefaults.KNOWN_RELAYS
    private var relays by mutableStateOf(RelayDefaults.DEFAULT_RELAYS)
    private var statuses by mutableStateOf<Map<String, RelayCheckStatus>>(emptyMap())
    private val calls = mutableListOf<String>()

    @Test
    fun `suggestions are the known relays not yet added, in known order`() {
        relays = listOf(known[1], known[3])
        render()

        compose.onNodeWithTag("relay_suggestions").assertDoesNotExist()
        compose.onNodeWithTag("relay_add_toggle").assertIsDisplayed()
            .assertTextContains(text(R.string.relay_add))
            .assertTextContains(plural(R.plurals.relay_suggestions_count, known.size - 2))
            .performClick()

        compose.onNodeWithTag("relay_suggestions").assertExists()
        listOf(known[1], known[3]).forEach { compose.onNodeWithTag(suggestionTag(it)).assertDoesNotExist() }
        val expected = known - listOf(known[1], known[3])
        val tops = expected.map { url ->
            compose.onNodeWithTag(suggestionTag(url)).assertExists().fetchSemanticsNode().positionInRoot.y
        }
        assertEquals("Suggestions must follow KNOWN_RELAYS order", tops.sorted(), tops)
        assertEquals("Each suggestion must occupy its own row", tops.size, tops.distinct().size)
        compose.onNodeWithTag(suggestionTag(known[0]))
            .assert(hasClickAction() and SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Button))
            .assertTextContains(host(known[0]))
            .assertTextContains(text(R.string.relay_status_idle))
        compose.onNodeWithTag("relay_custom_toggle").assertExists()
    }

    @Test
    fun `tapping a suggestion adds then checks it`() {
        relays = listOf(known[0])
        render()
        compose.onNodeWithTag("relay_add_toggle").performClick()
        compose.runOnIdle { calls.clear() }

        compose.onNodeWithTag(suggestionTag(known[5])).performScrollTo().performClick()

        compose.runOnIdle { assertEquals(listOf("add:${known[5]}", "check:${known[5]}"), calls) }
        relays = relays + known[5]
        compose.onNodeWithTag(suggestionTag(known[5])).assertDoesNotExist()
        compose.onNodeWithText(host(known[5])).assertExists()
    }

    @Test
    fun `expanding checks only the suggestions that have not been checked`() {
        relays = listOf(known[0])
        statuses = mapOf(known[1] to RelayCheckStatus.ONLINE, known[2] to RelayCheckStatus.OFFLINE)
        render()
        compose.runOnIdle { assertTrue("Nothing is checked while collapsed", calls.isEmpty()) }

        compose.onNodeWithTag("relay_add_toggle").performClick()

        val expected = (known - listOf(known[0], known[1], known[2])).map { "check:$it" }
        compose.runOnIdle { assertEquals(expected, calls) }
    }

    @Test
    fun `custom entry rejects bad input and accepts a normalised URL`() {
        val longRelay = "wss://" + "a".repeat(200) + ".example"
        relays = listOf(known[1], longRelay)
        render()
        openCustomField()

        submitCustom("ws://plain.example")
        compose.onNodeWithText(text(R.string.relay_must_start_wss)).assertExists()
        submitCustom("wss://a")
        compose.onNodeWithText(text(R.string.relay_url_too_short)).assertExists()
        submitCustom("wss://nos.lol/")
        compose.onNodeWithText(text(R.string.relay_already_added)).assertExists()
        submitCustom("wss://" + "b".repeat(60) + ".example")
        compose.onNodeWithText(text(R.string.relay_invite_too_long)).assertExists()
        compose.runOnIdle { assertTrue("Rejected input must not reach the callbacks", calls.isEmpty()) }

        submitCustom("WSS://Relay.Example.org/")

        compose.runOnIdle {
            assertEquals(listOf("add:wss://relay.example.org", "check:wss://relay.example.org"), calls)
        }
        compose.onNodeWithTag("relay_custom_input").assertTextContains("")
    }

    @Test
    fun `at the relay cap the add row gives way to the hint`() {
        relays = known.take(InviteLinkCodec.MAX_RELAYS)
        render()

        compose.onNodeWithTag("relay_add_toggle").assertDoesNotExist()
        compose.onNodeWithTag("relay_max_hint").performScrollTo()
            .assertTextContains(plural(R.plurals.relay_max_reached, InviteLinkCodec.MAX_RELAYS))
    }

    @Test
    fun `reset appears only when the list differs from the defaults as a set`() {
        relays = RelayDefaults.DEFAULT_RELAYS.reversed()
        render()
        compose.onNodeWithTag("relay_reset").assertDoesNotExist()

        relays = RelayDefaults.DEFAULT_RELAYS + known[7]
        compose.onNodeWithTag("relay_reset").performScrollTo().performClick()

        compose.runOnIdle { assertEquals(listOf("reset"), calls) }
    }

    @Test
    fun `members see the list without add, reset or remove`() {
        relays = RelayDefaults.DEFAULT_RELAYS + known[7]
        render(editable = false)

        compose.onNodeWithText(host(known[7])).assertIsDisplayed()
        compose.onNodeWithTag("relay_add_toggle").assertDoesNotExist()
        compose.onNodeWithTag("relay_reset").assertDoesNotExist()
        compose.onNodeWithTag("relay_max_hint").assertDoesNotExist()
        compose.onAllNodesWithContentDescription(text(R.string.relay_remove)).assertCountEquals(0)
    }

    @Test
    fun `creator can remove a relay while more than one remains`() {
        relays = listOf(known[0], known[1])
        render()

        compose.onAllNodesWithContentDescription(text(R.string.relay_remove)).assertCountEquals(2)
        compose.onNodeWithTag("relay_remove_${host(known[1])}").performClick()
        compose.runOnIdle { assertEquals(listOf("remove:${known[1]}"), calls) }

        relays = listOf(known[0])
        compose.onAllNodesWithContentDescription(text(R.string.relay_remove)).assertCountEquals(0)
    }

    private fun render(editable: Boolean = true) {
        compose.setContent {
            SplitFreeTheme {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    RelayEditor(
                        relays = relays,
                        relayStatuses = statuses,
                        onAdd = { calls += "add:$it" },
                        onRemove = { calls += "remove:$it" },
                        onCheck = { calls += "check:$it" },
                        onReset = { calls += "reset" },
                        editable = editable
                    )
                }
            }
        }
    }

    private fun openCustomField() {
        compose.onNodeWithTag("relay_add_toggle").performClick()
        compose.onNodeWithTag("relay_custom_toggle").performScrollTo().performClick()
        compose.onNodeWithTag("relay_custom_input").performScrollTo().assertIsDisplayed()
        compose.runOnIdle { calls.clear() }
    }

    private fun submitCustom(value: String) {
        compose.onNodeWithTag("relay_custom_input").performTextReplacement(value)
        compose.onNodeWithTag("relay_custom_add").performClick()
    }

    private fun host(url: String) = url.removePrefix("wss://")

    private fun suggestionTag(url: String) = "relay_suggestion_${host(url)}"

    private fun text(resource: Int, vararg args: Any): String =
        RuntimeEnvironment.getApplication().getString(resource, *args)

    private fun plural(resource: Int, count: Int): String =
        RuntimeEnvironment.getApplication().resources.getQuantityString(resource, count, count)
}
