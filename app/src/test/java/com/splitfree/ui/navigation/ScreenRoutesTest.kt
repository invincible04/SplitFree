package com.splitfree.ui.navigation

import android.app.Application
import android.net.Uri
import com.splitfree.domain.model.expense.ExpenseIdentity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
class ScreenRoutesTest {
    @Test
    fun `add expense route has no expense id`() {
        val route = Screen.AddExpense.withGroupId("g1")

        assertEquals("group/g1/expense", route)
        assertNull(Uri.parse(route).getQueryParameter("expenseId"))
        assertNull(Uri.parse(route).getQueryParameter("authorPubkey"))
    }

    @Test
    fun `edit expense route carries the expense id as a query argument`() {
        val route = Screen.EditExpense.createRoute("g1", ExpenseIdentity("alice", "exp-1"))

        assertEquals("group/g1/expense?expenseId=exp-1&authorPubkey=alice", route)
        assertEquals("exp-1", Uri.parse(route).getQueryParameter("expenseId"))
        assertEquals("alice", Uri.parse(route).getQueryParameter("authorPubkey"))
    }

    @Test
    fun `edit expense route escapes ids that are not URL safe`() {
        val route = Screen.EditExpense.createRoute("g1", ExpenseIdentity("a+b&c=d", "a b&c=d"))

        assertEquals("a b&c=d", Uri.parse(route).getQueryParameter("expenseId"))
        assertEquals("a+b&c=d", Uri.parse(route).getQueryParameter("authorPubkey"))
    }

    @Test
    fun `both editor routes resolve to the single editor destination pattern`() {
        assertEquals(Screen.AddExpense.route, Screen.EditExpense.route)
        assertEquals(
            "group/{groupId}/expense?expenseId={expenseId}&authorPubkey={authorPubkey}",
            Screen.AddExpense.route
        )
    }
}
