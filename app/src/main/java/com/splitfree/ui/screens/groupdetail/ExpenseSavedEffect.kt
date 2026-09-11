package com.splitfree.ui.screens.groupdetail

import androidx.compose.foundation.pager.PagerState
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.res.stringResource
import com.splitfree.R
import kotlinx.coroutines.launch

@Composable
internal fun ExpenseSavedEffect(
    expenseSaved: Boolean,
    onConsumed: () -> Unit,
    pagerState: PagerState,
    snackbarHostState: SnackbarHostState
) {
    val savedMessage = stringResource(R.string.expense_saved_message)
    val snackbarScope = rememberCoroutineScope()
    LaunchedEffect(expenseSaved) {
        if (expenseSaved) {
            pagerState.scrollToPage(1)
            snackbarScope.launch { snackbarHostState.showSnackbar(savedMessage) }
            onConsumed()
        }
    }
}
