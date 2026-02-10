package com.splitfree.ui.navigation

import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.runtime.Composable
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import com.splitfree.ui.screens.*

sealed class Screen(val route: String) {
    data object Onboarding : Screen("onboarding")
    data object GroupsList : Screen("groups")
    data object CreateGroup : Screen("create_group")
    data object Settings : Screen("settings")
    data object GroupDetail : Screen("group/{groupId}") {
        fun withId(id: String) = "group/$id"
    }
    data object AddExpense : Screen("group/{groupId}/add_expense") {
        fun withGroupId(id: String) = "group/$id/add_expense"
    }
    data object NearbySync : Screen("group/{groupId}/nearby_sync") {
        fun withGroupId(id: String) = "group/$id/nearby_sync"
    }
}

@Composable
fun SplitFreeNavGraph(
    navController: NavHostController,
    startDestination: String
) {
    NavHost(
        navController = navController,
        startDestination = startDestination,
        enterTransition = { EnterTransition.None },
        exitTransition = { ExitTransition.None },
        popEnterTransition = { EnterTransition.None },
        popExitTransition = { ExitTransition.None }
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
                onSettings = { navController.navigate(Screen.Settings.route) }
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
            SettingsScreen(onBack = { navController.popBackStack() })
        }
        composable(
            Screen.GroupDetail.route,
            arguments = listOf(navArgument("groupId") { type = NavType.StringType })
        ) {
            GroupDetailScreen(
                onAddExpense = { groupId ->
                    navController.navigate(Screen.AddExpense.withGroupId(groupId))
                },
                onNearbySync = { groupId ->
                    navController.navigate(Screen.NearbySync.withGroupId(groupId))
                },
                onBack = { navController.popBackStack() }
            )
        }
        composable(
            Screen.AddExpense.route,
            arguments = listOf(navArgument("groupId") { type = NavType.StringType })
        ) {
            AddExpenseScreen(
                onExpenseAdded = { navController.popBackStack() },
                onBack = { navController.popBackStack() }
            )
        }
        composable(
            Screen.NearbySync.route,
            arguments = listOf(navArgument("groupId") { type = NavType.StringType })
        ) {
            NearbySyncScreen(onBack = { navController.popBackStack() })
        }
    }
}
