package com.splitfree.ui.screens.groupdetail

import androidx.compose.foundation.pager.PagerState
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.res.stringResource
import com.splitfree.R
import kotlinx.coroutines.launch

/**
 * Reacts to a freshly saved expense: consumes the flag, switches to the Expenses tab, and shows a
 * confirmation snackbar.
 *
 * [onConsumed] is called FIRST and synchronously. Consuming flips [expenseSaved] (the effect's
 * key), which cancels this effect's coroutine on the next recomposition — so the scroll and the
 * snackbar run on the composable's own scope, where that cancellation cannot leave the pager on
 * the wrong tab or the flag stuck at `true` if the effect is disposed mid-scroll.
 */
@Composable
internal fun ExpenseSavedEffect(
    expenseSaved: Boolean,
    onConsumed: () -> Unit,
    pagerState: PagerState,
    snackbarHostState: SnackbarHostState
) {
    val savedMessage = stringResource(R.string.expense_saved_message)
    val scope = rememberCoroutineScope()
    LaunchedEffect(expenseSaved) {
        if (expenseSaved) {
            onConsumed()
            scope.launch { pagerState.scrollToPage(1) }
            scope.launch { snackbarHostState.showSnackbar(savedMessage) }
        }
    }
}
