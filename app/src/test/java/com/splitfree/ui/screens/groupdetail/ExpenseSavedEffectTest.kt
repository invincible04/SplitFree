package com.splitfree.ui.screens.groupdetail

import android.app.Application
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import com.splitfree.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35], qualifiers = "en-rUS-w390dp-h844dp-mdpi")
class ExpenseSavedEffectTest {
    @get:Rule
    val compose = createComposeRule()

    private var expenseSaved by mutableStateOf(false)
    private var revision by mutableStateOf(0)
    private val consumedAtRevision = mutableListOf<Int>()
    private val pageAtConsume = mutableListOf<Int>()
    private lateinit var pagerState: PagerState
    private lateinit var snackbarHostState: SnackbarHostState
    private lateinit var scope: CoroutineScope

    @Test
    fun `no save leaves balances selected without consuming or showing confirmation`() {
        render()

        compose.onNodeWithText(text(R.string.tab_balances)).assertIsDisplayed()
        compose.onNodeWithText(text(R.string.expense_saved_message)).assertDoesNotExist()
        compose.runOnIdle {
            assertEquals(0, pagerState.currentPage)
            assertTrue(consumedAtRevision.isEmpty())
            assertNull(snackbarHostState.currentSnackbarData)
        }
    }

    @Test
    fun `consuming save resets flag but expenses and snackbar survive recomposition`() {
        render(initialSaved = true)

        val confirmation = compose.runOnIdle {
            assertFalse(expenseSaved)
            assertEquals(1, pagerState.currentPage)
            assertEquals(listOf(0), consumedAtRevision)
            requireNotNull(snackbarHostState.currentSnackbarData)
        }
        compose.onNodeWithText(text(R.string.tab_expenses)).assertIsDisplayed()
        compose.onNodeWithText(text(R.string.expense_saved_message)).assertIsDisplayed()

        compose.runOnIdle { revision++ }

        compose.onNodeWithText("Revision 1").assertIsDisplayed()
        compose.onNodeWithText(text(R.string.expense_saved_message)).assertIsDisplayed()
        compose.runOnIdle {
            assertFalse(expenseSaved)
            assertEquals(1, pagerState.currentPage)
            assertEquals(listOf(0), consumedAtRevision)
            assertSame(confirmation, snackbarHostState.currentSnackbarData)
        }
    }

    @Test
    fun `flag is consumed before the pager moves so disposal mid-scroll cannot leave it set`() {
        render(initialSaved = true)

        compose.runOnIdle {
            // Consumption happened while the pager still showed Balances (page 0)…
            assertEquals(listOf(0), pageAtConsume)
            assertFalse(expenseSaved)
            // …and the scroll and confirmation still completed afterwards.
            assertEquals(1, pagerState.currentPage)
            requireNotNull(snackbarHostState.currentSnackbarData)
        }
        compose.onNodeWithText(text(R.string.tab_expenses)).assertIsDisplayed()
    }

    @Test
    fun `unchanged true flag is consumed only once even when callback recomposes`() {
        render(initialSaved = true, resetOnConsume = false)

        val confirmation = compose.runOnIdle {
            assertTrue(expenseSaved)
            assertEquals(listOf(0), consumedAtRevision)
            requireNotNull(snackbarHostState.currentSnackbarData)
        }
        compose.runOnIdle { revision++ }

        compose.onNodeWithText("Revision 1").assertIsDisplayed()
        compose.onNodeWithText(text(R.string.expense_saved_message)).assertIsDisplayed()
        compose.runOnIdle {
            assertTrue(expenseSaved)
            assertEquals(1, pagerState.currentPage)
            assertEquals(listOf(0), consumedAtRevision)
            assertSame(confirmation, snackbarHostState.currentSnackbarData)
        }
    }

    @Test
    fun `later save returns to expenses and shows a new confirmation`() {
        render(initialSaved = true)

        val firstConfirmation = compose.runOnIdle {
            assertFalse(expenseSaved)
            assertEquals(listOf(0), consumedAtRevision)
            requireNotNull(snackbarHostState.currentSnackbarData).also { it.dismiss() }
        }
        compose.runOnIdle {
            revision++
            scope.launch { pagerState.scrollToPage(2) }
        }
        compose.onNodeWithText(text(R.string.tab_members)).assertIsDisplayed()
        compose.onNodeWithText(text(R.string.expense_saved_message)).assertDoesNotExist()
        compose.runOnIdle {
            assertEquals(2, pagerState.currentPage)
            assertNull(snackbarHostState.currentSnackbarData)
            expenseSaved = true
        }

        compose.onNodeWithText(text(R.string.tab_expenses)).assertIsDisplayed()
        compose.onNodeWithText(text(R.string.expense_saved_message)).assertIsDisplayed()
        compose.runOnIdle {
            assertFalse(expenseSaved)
            assertEquals(1, pagerState.currentPage)
            assertEquals(listOf(0, 1), consumedAtRevision)
            val secondConfirmation = requireNotNull(snackbarHostState.currentSnackbarData)
            assertNotSame(firstConfirmation, secondConfirmation)
            assertEquals(text(R.string.expense_saved_message), secondConfirmation.visuals.message)
        }
    }

    private fun render(initialSaved: Boolean = false, resetOnConsume: Boolean = true) {
        expenseSaved = initialSaved
        compose.setContent {
            val renderedRevision = revision
            pagerState = rememberPagerState(pageCount = { 3 })
            snackbarHostState = remember { SnackbarHostState() }
            scope = rememberCoroutineScope()
            MaterialTheme {
                ExpenseSavedEffect(
                    expenseSaved = expenseSaved,
                    onConsumed = {
                        consumedAtRevision += renderedRevision
                        pageAtConsume += pagerState.currentPage
                        if (resetOnConsume) expenseSaved = false
                    },
                    pagerState = pagerState,
                    snackbarHostState = snackbarHostState
                )
                Box(Modifier.fillMaxSize()) {
                    HorizontalPager(state = pagerState, modifier = Modifier.fillMaxSize()) { page ->
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            val title = when (page) {
                                0 -> R.string.tab_balances
                                1 -> R.string.tab_expenses
                                else -> R.string.tab_members
                            }
                            Text(stringResource(title))
                        }
                    }
                    Text("Revision $renderedRevision", modifier = Modifier.align(Alignment.TopEnd))
                    SnackbarHost(snackbarHostState, modifier = Modifier.align(Alignment.BottomCenter))
                }
            }
        }
    }

    private fun text(resource: Int): String = RuntimeEnvironment.getApplication().getString(resource)
}
