package com.splitfree.ui.navigation

import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import com.splitfree.ui.screens.debug.DebugLogScreen
import com.splitfree.ui.screens.expense.AddExpenseScreen
import com.splitfree.ui.screens.group.CreateGroupScreen
import com.splitfree.ui.screens.group.GroupsListScreen
import com.splitfree.ui.screens.groupdetail.GroupDetailScreen
import com.splitfree.ui.screens.nearby.NearbySyncScreen
import com.splitfree.ui.screens.onboarding.OnboardingScreen
import com.splitfree.ui.screens.settings.SettingsScreen
import com.splitfree.ui.theme.SfMotion

/**
 * Navigation route definitions for the app's screens.
 */
sealed class Screen(val route: String) {
    data object Onboarding : Screen("onboarding")

    data object GroupsList : Screen("groups")

    data object CreateGroup : Screen("create_group")

    data object Settings : Screen("settings")

    data object GroupDetail : Screen("group/{groupId}") {
        fun withId(id: String) = "group/$id"
    }

    /** The expense editor. Without `expenseId` it adds a new expense; with one it edits that expense. */
    data object AddExpense : Screen("group/{groupId}/expense?expenseId={expenseId}") {
        fun withGroupId(id: String) = "group/$id/expense"
    }

    /** Same destination as [AddExpense], opened on an existing expense. */
    data object EditExpense : Screen(AddExpense.route) {
        fun createRoute(groupId: String, expenseId: String) =
            "group/$groupId/expense?expenseId=${Uri.encode(expenseId)}"
    }

    data object NearbySync : Screen("group/{groupId}/nearby_sync") {
        fun withGroupId(id: String) = "group/$id/nearby_sync"
    }

    data object DebugLog : Screen("debug_log")
}

@Composable
fun SplitFreeNavGraph(navController: NavHostController, startDestination: String, onScanResult: (String) -> Unit = {}) {
    NavHost(
        navController = navController,
        startDestination = startDestination,
        enterTransition = { SfMotion.forwardEnter },
        exitTransition = { SfMotion.forwardExit },
        popEnterTransition = { SfMotion.popEnter },
        popExitTransition = { SfMotion.popExit }
    ) {
        composable(Screen.Onboarding.route) {
            OnboardingScreen(onComplete = {
                navController.navigate(Screen.GroupsList.route) {
                    popUpTo(Screen.Onboarding.route) { inclusive = true }
                }
            })
        }
        composable(Screen.GroupsList.route) {
            GroupsListScreen(
                onGroupClick = { navController.navigate(Screen.GroupDetail.withId(it)) },
                onCreateGroup = { navController.navigate(Screen.CreateGroup.route) },
                onSettings = { navController.navigate(Screen.Settings.route) },
                onScanResult = onScanResult
            )
        }
        composable(Screen.CreateGroup.route) {
            CreateGroupScreen(
                onGroupCreated = { groupId ->
                    navController.navigate(Screen.GroupDetail.withId(groupId)) {
                        popUpTo(Screen.GroupsList.route)
                    }
                },
                onBack = { navController.popBackStack() }
            )
        }
        composable(Screen.Settings.route) {
            SettingsScreen(
                onBack = { navController.popBackStack() },
                onDebugLog = { navController.navigate(Screen.DebugLog.route) }
            )
        }
        composable(
            Screen.GroupDetail.route,
            arguments = listOf(navArgument("groupId") { type = NavType.StringType })
        ) { entry ->
            val expenseSaved by entry.savedStateHandle.getStateFlow("expenseSaved", false).collectAsStateWithLifecycle()
            val groupId = requireNotNull(entry.arguments?.getString("groupId"))
            GroupDetailScreen(
                expenseSaved = expenseSaved,
                onExpenseSavedConsumed = { entry.savedStateHandle["expenseSaved"] = false },
                onAddExpense = { navController.navigate(Screen.AddExpense.withGroupId(it)) },
                onEditExpense = { expenseId ->
                    navController.navigate(Screen.EditExpense.createRoute(groupId, expenseId))
                },
                onNearbySync = { groupId ->
                    navController.navigate(Screen.NearbySync.withGroupId(groupId))
                },
                onBack = { navController.popBackStack() }
            )
        }
        composable(
            Screen.AddExpense.route,
            arguments =
            listOf(
                navArgument("groupId") { type = NavType.StringType },
                navArgument("expenseId") {
                    type = NavType.StringType
                    nullable = true
                    defaultValue = null
                }
            )
        ) {
            AddExpenseScreen(
                onExpenseAdded = {
                    navController.previousBackStackEntry?.savedStateHandle?.set("expenseSaved", true)
                    navController.popBackStack()
                },
                onBack = { navController.popBackStack() }
            )
        }
        composable(
            Screen.NearbySync.route,
            arguments = listOf(navArgument("groupId") { type = NavType.StringType })
        ) {
            NearbySyncScreen(onBack = { navController.popBackStack() })
        }
        composable(Screen.DebugLog.route) {
            DebugLogScreen(onBack = { navController.popBackStack() })
        }
    }
}
