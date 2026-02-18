package com.splitfree

import android.app.AlertDialog
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.lifecycleScope
import androidx.navigation.compose.rememberNavController
import com.splitfree.domain.crypto.IdentityManager
import com.splitfree.domain.usecase.group.JoinGroupUseCase
import com.splitfree.ui.navigation.Screen
import com.splitfree.ui.navigation.SplitFreeNavGraph
import com.splitfree.ui.theme.CircularRevealTheme
import com.splitfree.ui.theme.SplitFreeTheme
import com.splitfree.ui.theme.ThemeMode
import com.splitfree.ui.theme.ThemePreference
import com.splitfree.ui.theme.ThemeTransitionState
import com.splitfree.util.DebugLog as Log
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Main activity. Handles deep link parsing for invite links and starts the foreground sync service.
 */
@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    @Inject lateinit var identity: IdentityManager

    @Inject lateinit var joinGroup: JoinGroupUseCase

    private var isJoining by mutableStateOf(false)
    private var navController: androidx.navigation.NavHostController? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ThemePreference.init(this)
        sanitizeIntent(intent)
        handleDeepLink(intent)

        // Start real-time sync service when identity becomes available
        lifecycleScope.launch {
            while (!identity.hasIdentity()) delay(1000)
            val serviceIntent = Intent(this@MainActivity, com.splitfree.sync.worker.ForegroundSyncService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(serviceIntent)
            } else {
                startService(serviceIntent)
            }
        }

        setContent {
            // Set status bar icon colors to match app theme (dark icons on light bg, light icons on dark bg)
            val themeMode by ThemePreference.mode.collectAsState()
            val isDark =
                when (themeMode) {
                    ThemeMode.LIGHT -> false
                    ThemeMode.DARK -> true
                    ThemeMode.SYSTEM -> isSystemInDarkTheme()
                }
            // Defer status bar icon update until after reveal animation so icons
            // don't become invisible against the old-theme bitmap overlay.
            val revealDone = ThemeTransitionState.animationDone
            LaunchedEffect(isDark, revealDone) {
                if (ThemeTransitionState.overlay == null) {
                    val controller =
                        androidx.core.view.WindowCompat
                            .getInsetsController(window, window.decorView)
                    controller.isAppearanceLightStatusBars = !isDark
                }
            }

            CircularRevealTheme {
                SplitFreeTheme {
                    Surface(
                        modifier = Modifier.fillMaxSize(),
                        color = MaterialTheme.colorScheme.background
                    ) {
                        val nav = rememberNavController()
                        navController = nav
                        val start =
                            remember {
                                if (identity.hasIdentity()) Screen.GroupsList.route else Screen.Onboarding.route
                            }
                        SplitFreeNavGraph(
                            navController = nav,
                            startDestination = start,
                            onScanResult = { scannedUrl ->
                                handleDeepLink(Intent(Intent.ACTION_VIEW, Uri.parse(scannedUrl)))
                            }
                        )

                        if (isJoining) {
                            androidx.compose.foundation.layout.Box(
                                modifier = Modifier.fillMaxSize(),
                                contentAlignment = androidx.compose.ui.Alignment.Center
                            ) {
                                androidx.compose.material3.CircularProgressIndicator()
                            }
                        }
                    }
                }
            }
        }
    }

    private fun processJoin(link: String) {
        isJoining = true
        lifecycleScope.launch {
            val group =
                try {
                    joinGroup(link)
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.e("MainActivity", "Join failed: ${e.message}", e)
                    Toast.makeText(this@MainActivity, "Join failed: ${e.message}", Toast.LENGTH_LONG).show()
                    null
                } finally {
                    isJoining = false
                }
            if (group != null) {
                Log.i("MainActivity", "Joined group: ${group.id} (${group.name})")
                navController?.navigate(Screen.GroupDetail.withId(group.id))
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        sanitizeIntent(intent)
        handleDeepLink(intent)
    }

    /** Strip Jetpack Navigation internal extras from external intents (PT-2024-35 defense). */
    private fun sanitizeIntent(intent: Intent?) {
        intent ?: return
        intent.removeExtra("android-support-nav:controller:deepLinkIds")
        intent.removeExtra("android-support-nav:controller:deepLinkArgs")
    }

    private fun handleDeepLink(intent: Intent?) {
        val uri = intent?.data ?: return
        val uriStr = uri.toString()
        Log.i("MainActivity", "handleDeepLink: $uriStr")
        if (uriStr.startsWith("splitfree://join") ||
            uriStr.startsWith("https://splitfree.app/join")
        ) {
            // v2 compact links use fragment (#), v1 uses query params (?)
            val hasParams =
                uri.getQueryParameter("d") != null ||
                    uri.getQueryParameter("g") != null ||
                    uri.fragment?.isNotEmpty() == true
            if (!hasParams) return
            // Show confirmation dialog — never auto-join from deep links (CVE-2025-4957, USENIX 2017)
            AlertDialog
                .Builder(this)
                .setTitle("Join Group?")
                .setMessage("Join this SplitFree group?\n\nOnly join if you trust the sender of this link.")
                .setPositiveButton("Join") { _, _ -> processJoin(uriStr) }
                .setNegativeButton("Cancel", null)
                .show()
        }
    }
}
