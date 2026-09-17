package com.splitfree.ui.navigation

import android.net.Uri
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import com.splitfree.domain.model.expense.ExpenseIdentity
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

    /** Without an expense identity the editor adds a new expense. */
    data object AddExpense : Screen("group/{groupId}/expense?expenseId={expenseId}&authorPubkey={authorPubkey}") {
        fun withGroupId(id: String) = "group/$id/expense"
    }

    /** Same destination as [AddExpense], opened on an existing expense. */
    data object EditExpense : Screen(AddExpense.route) {
        fun createRoute(groupId: String, identity: ExpenseIdentity) =
            "group/$groupId/expense?expenseId=${Uri.encode(identity.expenseUuid)}" +
                "&authorPubkey=${Uri.encode(identity.authorPubkey)}"
    }

    data object NearbySync : Screen("group/{groupId}/nearby_sync") {
        fun withGroupId(id: String) = "group/$id/nearby_sync"
    }

    data object DebugLog : Screen("debug_log")
}

@Composable
fun SplitFreeNavGraph(navController: NavHostController, startDestination: String, onScanResult: (String) -> Unit = {}) {
    val direction = LocalLayoutDirection.current
    NavHost(
        navController = navController,
        startDestination = startDestination,
        enterTransition = { SfMotion.forwardEnter(direction) },
        exitTransition = { SfMotion.forwardExit(direction) },
        popEnterTransition = { EnterTransition.None },
        popExitTransition = { ExitTransition.None },
        // Predictive-back gesture uses separate transitions in Navigation Compose 2.10+.
        // Without explicit None here, the library's default includes scaleOut during edge swipes.
        predictivePopEnterTransition = { EnterTransition.None },
        predictivePopExitTransition = { ExitTransition.None }
    ) {
        composable(Screen.Onboarding.route) { entry ->
            OnboardingScreen(onComplete = {
                navController.navigateFrom(entry) {
                    navigate(Screen.GroupsList.route) {
                        popUpTo(Screen.Onboarding.route) { inclusive = true }
                    }
                }
            })
        }
        composable(Screen.GroupsList.route) { entry ->
            GroupsListScreen(
                onGroupClick = { groupId ->
                    navController.navigateFrom(entry) { navigate(Screen.GroupDetail.withId(groupId)) }
                },
                onCreateGroup = { navController.navigateFrom(entry) { navigate(Screen.CreateGroup.route) } },
                onSettings = { navController.navigateFrom(entry) { navigate(Screen.Settings.route) } },
                onScanResult = onScanResult
            )
        }
        composable(Screen.CreateGroup.route) { entry ->
            CreateGroupScreen(
                onGroupCreated = { groupId ->
                    navController.navigateFrom(entry) {
                        navigate(Screen.GroupDetail.withId(groupId)) {
                            popUpTo(Screen.GroupsList.route)
                        }
                    }
                },
                onBack = { navController.navigateFrom(entry) { popBackStack() } }
            )
        }
        composable(Screen.Settings.route) { entry ->
            SettingsScreen(
                onBack = { navController.navigateFrom(entry) { popBackStack() } },
                onDebugLog = { navController.navigateFrom(entry) { navigate(Screen.DebugLog.route) } }
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
                onAddExpense = { groupId ->
                    navController.navigateFrom(entry) { navigate(Screen.AddExpense.withGroupId(groupId)) }
                },
                onEditExpense = { expenseIdentity ->
                    navController.navigateFrom(entry) {
                        navigate(Screen.EditExpense.createRoute(groupId, expenseIdentity))
                    }
                },
                onNearbySync = { groupId ->
                    navController.navigateFrom(entry) { navigate(Screen.NearbySync.withGroupId(groupId)) }
                },
                onBack = { navController.navigateFrom(entry) { popBackStack() } }
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
                },
                navArgument("authorPubkey") {
                    type = NavType.StringType
                    nullable = true
                    defaultValue = null
                }
            )
        ) { entry ->
            AddExpenseScreen(
                onExpenseAdded = {
                    navController.navigateFrom(entry) {
                        previousBackStackEntry?.savedStateHandle?.set("expenseSaved", true)
                        popBackStack()
                    }
                },
                onBack = { navController.navigateFrom(entry) { popBackStack() } }
            )
        }
        composable(
            Screen.NearbySync.route,
            arguments = listOf(navArgument("groupId") { type = NavType.StringType })
        ) { entry ->
            NearbySyncScreen(onBack = { navController.navigateFrom(entry) { popBackStack() } })
        }
        composable(Screen.DebugLog.route) { entry ->
            DebugLogScreen(onBack = { navController.navigateFrom(entry) { popBackStack() } })
        }
    }
}

// Outgoing destinations remain composed during transitions. Their callbacks must not mutate the new top.
internal inline fun NavHostController.navigateFrom(entry: NavBackStackEntry, action: NavHostController.() -> Unit) {
    if (currentBackStackEntry === entry) action()
}
