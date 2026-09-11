package com.splitfree

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.NavHostController
import androidx.navigation.compose.rememberNavController
import com.splitfree.data.identity.IdentityManager
import com.splitfree.data.settings.UserPreferences
import com.splitfree.domain.invite.InviteLinkCodec
import com.splitfree.domain.usecase.group.JoinGroupUseCase
import com.splitfree.sync.worker.ForegroundSyncService
import com.splitfree.ui.navigation.Screen
import com.splitfree.ui.navigation.SplitFreeNavGraph
import com.splitfree.ui.theme.CircularRevealTheme
import com.splitfree.ui.theme.SplitFreeTheme
import com.splitfree.ui.theme.ThemeMode
import com.splitfree.ui.theme.ThemePreference
import com.splitfree.ui.theme.ThemeTransitionState
import com.splitfree.util.DebugLog as Log
import com.splitfree.util.ProcessHealthTracker
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Main activity. Handles deep link parsing for invite links and starts the foreground sync service.
 *
 * Declared `singleTask` so an external `ACTION_VIEW` invite arrives through [onNewIntent] on the
 * existing instance instead of stacking a second copy of the app.
 */
@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    @Inject lateinit var identity: IdentityManager

    @Inject lateinit var joinGroup: JoinGroupUseCase

    @Inject lateinit var prefs: UserPreferences

    /** A decoded invite waiting for the user's Join / Cancel decision. */
    private data class PendingInvite(val link: String, val groupName: String, val relayHosts: List<String>)

    private var isJoining by mutableStateOf(false)
    private var pendingInvite by mutableStateOf<PendingInvite?>(null)
    private var navController: NavHostController? = null

    private val notificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            Log.i(TAG, "POST_NOTIFICATIONS ${if (granted) "granted" else "denied"}")
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ProcessHealthTracker.heartbeat(this, "main_activity_create")
        ThemePreference.init(this)
        sanitizeIntent(intent)
        if (savedInstanceState == null) {
            handleDeepLink(intent)
        } else {
            // The launch intent was consumed by the previous instance; only the unanswered prompt survives.
            savedInstanceState.getString(STATE_PENDING_INVITE)?.let(::offerInvite)
        }

        // Start only while visible; repeat when returning after Android stops a timed-out service.
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                // Suspends until onboarding generates or imports a key; returns at once if one exists.
                identity.observeHasIdentity().first { it }
                startSyncService()
                maybeRequestNotificationPermission()
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
                            onScanResult = ::offerInvite
                        )

                        pendingInvite?.let { invite ->
                            InviteConfirmDialog(
                                invite = invite,
                                onJoin = {
                                    pendingInvite = null
                                    processJoin(invite.link)
                                },
                                onDismiss = { pendingInvite = null }
                            )
                        }

                        if (isJoining) {
                            // Swallow every pointer event so the screen underneath cannot be used
                            // (or a second join started) while this one is in flight.
                            Box(
                                modifier =
                                Modifier
                                    .fillMaxSize()
                                    .background(MaterialTheme.colorScheme.scrim.copy(alpha = 0.32f))
                                    .pointerInput(Unit) {
                                        awaitPointerEventScope {
                                            while (true) awaitPointerEvent()
                                        }
                                    },
                                contentAlignment = Alignment.Center
                            ) {
                                CircularProgressIndicator()
                            }
                        }
                    }
                }
            }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        // Compose state on the Activity does not survive recreation; keep the open prompt alive.
        outState.putString(STATE_PENDING_INVITE, pendingInvite?.link)
    }

    private fun startSyncService() {
        try {
            startForegroundService(Intent(this, ForegroundSyncService::class.java))
        } catch (e: Exception) {
            Log.w(TAG, "Could not start sync service: ${e.message}")
        }
    }

    /**
     * Ask for `POST_NOTIFICATIONS` (API 33+) exactly once per install. Without it every expense and
     * settlement notification is silently dropped. Runs after onboarding so the first thing a new
     * user sees is not a permission prompt.
     */
    private fun maybeRequestNotificationPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        val granted =
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) ==
                PackageManager.PERMISSION_GRANTED
        if (granted || prefs.notificationsPrompted) return
        prefs.notificationsPrompted = true
        notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
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
                    Log.e(TAG, "Join failed: ${e.message}", e)
                    Toast
                        .makeText(this@MainActivity, getString(R.string.join_failed, e.message), Toast.LENGTH_LONG)
                        .show()
                    null
                } finally {
                    isJoining = false
                }
            if (group != null) {
                Log.i(TAG, "Joined group: ${group.id} (${group.name})")
                navController?.navigate(Screen.GroupDetail.withId(group.id))
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        ProcessHealthTracker.heartbeat(this, "main_activity_new_intent")
        sanitizeIntent(intent)
        setIntent(intent)
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
        // Consume the link: a configuration change re-delivers this same Intent, and the bearer
        // payload has no business lingering on the Activity once it has been offered to the user.
        intent.data = null
        Log.i(TAG, "handleDeepLink: ${uri.scheme}://${uri.host}${uri.path}?d=[REDACTED]")
        offerInvite(uri.toString())
    }

    /**
     * Decodes [link] and, if it is a well-formed invite, asks the user whether to join. Shows the
     * group name and relay hosts so they can see what they are accepting. Never auto-joins from a
     * deep link (CVE-2025-4957, USENIX 2017).
     */
    private fun offerInvite(link: String) {
        if (!link.startsWith("splitfree://join")) return
        // Compact invite links use ?d= parameter
        if (link.toUri().getQueryParameter("d") == null) return
        val invite =
            runCatching { InviteLinkCodec.decode(link) }.getOrElse { e ->
                Log.w(TAG, "Rejected invite link: ${e.message}")
                Toast.makeText(this, R.string.invalid_invite_link, Toast.LENGTH_LONG).show()
                return
            }
        val hosts = invite.relays.map { relay -> relay.toUri().host ?: relay }
        pendingInvite = PendingInvite(link = link, groupName = invite.name, relayHosts = hosts)
    }

    @Composable
    private fun InviteConfirmDialog(invite: PendingInvite, onJoin: () -> Unit, onDismiss: () -> Unit) {
        AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text(stringResource(R.string.join_group_title)) },
            text = {
                Text(
                    stringResource(
                        R.string.join_group_body,
                        invite.groupName,
                        invite.relayHosts.joinToString(", ")
                    )
                )
            },
            confirmButton = { TextButton(onClick = onJoin) { Text(stringResource(R.string.join)) } },
            dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } }
        )
    }

    companion object {
        private const val TAG = "MainActivity"
        private const val STATE_PENDING_INVITE = "pending_invite"
    }
}
