package com.splitfree

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.navigation.compose.rememberNavController
import com.splitfree.domain.crypto.IdentityManager
import com.splitfree.domain.usecase.JoinGroupUseCase
import com.splitfree.ui.navigation.Screen
import com.splitfree.ui.navigation.SplitFreeNavGraph
import com.splitfree.ui.theme.SplitFreeTheme
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject lateinit var identity: IdentityManager
    @Inject lateinit var joinGroup: JoinGroupUseCase

    private var pendingDeepLink by mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        handleDeepLink(intent)
        setContent {
            SplitFreeTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    val navController = rememberNavController()
                    val start = if (identity.hasIdentity()) Screen.GroupsList.route else Screen.Onboarding.route
                    SplitFreeNavGraph(navController = navController, startDestination = start)

                    // Process pending deep link once nav is ready
                    val deepLink = pendingDeepLink
                    LaunchedEffect(deepLink) {
                        if (deepLink != null && identity.hasIdentity()) {
                            pendingDeepLink = null
                            try {
                                val group = joinGroup(deepLink)
                                navController.navigate(Screen.GroupDetail.withId(group.id))
                            } catch (e: Exception) {
                                Toast.makeText(this@MainActivity, "Invalid invite link", Toast.LENGTH_SHORT).show()
                            }
                        }
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleDeepLink(intent)
    }

    private fun handleDeepLink(intent: Intent?) {
        val uri = intent?.data?.toString() ?: return
        if (uri.startsWith("splitfree://join")) {
            pendingDeepLink = uri
        }
    }
}
