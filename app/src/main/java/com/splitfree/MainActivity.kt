package com.splitfree

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CellTower
import androidx.compose.material.icons.outlined.Group
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import androidx.core.view.WindowCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.NavHostController
import androidx.navigation.compose.rememberNavController
import com.splitfree.data.identity.IdentityManager
import com.splitfree.data.settings.UserPreferences
import com.splitfree.domain.usecase.group.JoinGroupUseCase
import com.splitfree.ui.components.HintCard
import com.splitfree.ui.components.SfListCard
import com.splitfree.ui.components.SfPrimaryButton
import com.splitfree.ui.components.SfSecondaryButton
import com.splitfree.ui.components.SfSheet
import com.splitfree.ui.components.SfSheetFooter
import com.splitfree.ui.navigation.Screen
import com.splitfree.ui.navigation.SplitFreeNavGraph
import com.splitfree.ui.theme.SplitFreeTheme
import com.splitfree.ui.theme.ThemeMode
import com.splitfree.ui.theme.ThemePreference
import com.splitfree.ui.util.decodeInviteForConfirmation
import com.splitfree.util.DebugLog as Log
import com.splitfree.util.ProcessHealthTracker
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Main activity. Handles deep link parsing for invite links and asks for notification permission
 * once an identity exists; relay sync is owned by `LiveSync`, bound to the process lifecycle.
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
        // Draw behind the system bars; screens use Scaffold / safeDrawingPadding for their insets.
        enableEdgeToEdge()
        ProcessHealthTracker.heartbeat(this, "main_activity_create")
        ThemePreference.init(this)
        sanitizeIntent(intent)
        if (savedInstanceState == null) {
            handleDeepLink(intent)
        } else {
            // The launch intent was consumed by the previous instance; only the unanswered prompt survives.
            savedInstanceState.getString(STATE_PENDING_INVITE)?.let(::offerInvite)
        }

        // The permission prompt must be launched from a started activity, so the identity wait only
        // runs while STARTED; a stop cancels it and the next start re-arms it.
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                // Suspends until onboarding generates or imports a key; returns at once if one exists.
                identity.observeHasIdentity().first { it }
                maybeRequestNotificationPermission()
            }
        }

        setContent {
            // System bar icon colours follow the app's ThemePreference (not only the OS dark-mode flag):
            // dark icons on the light ivory surface, light icons on the dark one.
            val themeMode by ThemePreference.mode.collectAsState()
            val isDark =
                when (themeMode) {
                    ThemeMode.LIGHT -> false
                    ThemeMode.DARK -> true
                    ThemeMode.SYSTEM -> isSystemInDarkTheme()
                }
            DisposableEffect(isDark) {
                val controller = WindowCompat.getInsetsController(window, window.decorView)
                controller.isAppearanceLightStatusBars = !isDark
                controller.isAppearanceLightNavigationBars = !isDark
                onDispose {}
            }

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
                        InviteConfirmSheet(
                            invite = invite,
                            onJoin = {
                                pendingInvite = null
                                processJoin(invite.link)
                            },
                            onDismiss = { pendingInvite = null }
                        )
                    }

                    if (isJoining) JoiningScrim()
                }
            }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        // Compose state on the Activity does not survive recreation; keep the open prompt alive.
        outState.putString(STATE_PENDING_INVITE, pendingInvite?.link)
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
        val invite = decodeInviteForConfirmation(link)
        if (invite == null) {
            Log.w(TAG, "Rejected invalid invite input")
            Toast.makeText(this, R.string.invalid_invite_link, Toast.LENGTH_LONG).show()
            return
        }
        val hosts = invite.relays.map { relay -> relay.toUri().host ?: relay }
        pendingInvite = PendingInvite(link = link, groupName = invite.name, relayHosts = hosts)
    }

    /**
     * Bottom sheet asking whether to join [invite]: the group name in the title, a trust reminder, the relay
     * hosts the invite points at, then Cancel / Join. Dismissing (swipe, scrim, close) is a cancel.
     */
    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    private fun InviteConfirmSheet(invite: PendingInvite, onJoin: () -> Unit, onDismiss: () -> Unit) {
        SfSheet(
            onDismiss = onDismiss,
            title = stringResource(R.string.join_group_title, invite.groupName),
            scrollable = true,
            footer = {
                SfSheetFooter(
                    secondary = {
                        SfSecondaryButton(
                            text = stringResource(R.string.cancel),
                            onClick = onDismiss,
                            modifier = Modifier.fillMaxWidth()
                        )
                    },
                    primary = { SfPrimaryButton(text = stringResource(R.string.join), onClick = onJoin) }
                )
            }
        ) {
            HintCard(text = stringResource(R.string.join_group_body), icon = Icons.Outlined.Group)
            Spacer(Modifier.height(SHEET_BLOCK_SPACING))
            SfListCard {
                Row(
                    modifier =
                    Modifier
                        .fillMaxWidth()
                        .heightIn(min = RELAY_ROW_MIN_HEIGHT)
                        .padding(horizontal = 13.dp, vertical = 9.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        Icons.Outlined.CellTower,
                        contentDescription = null,
                        modifier = Modifier.size(22.dp),
                        tint = MaterialTheme.colorScheme.primary
                    )
                    Spacer(Modifier.width(11.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            stringResource(R.string.join_group_relays),
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Spacer(Modifier.height(3.dp))
                        Text(
                            invite.relayHosts.joinToString(", "),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }

    /**
     * Full-screen 40% scrim with a small card holding the progress ring. Swallows every pointer event so the
     * screen underneath cannot be used (or a second join started) while the join is in flight.
     */
    @Composable
    private fun JoiningScrim() {
        val label = stringResource(R.string.cd_joining_group)
        Box(
            modifier =
            Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.scrim.copy(alpha = SCRIM_ALPHA))
                .pointerInput(Unit) {
                    awaitPointerEventScope {
                        while (true) awaitPointerEvent()
                    }
                },
            contentAlignment = Alignment.Center
        ) {
            Surface(
                modifier = Modifier.size(JOINING_TILE_SIZE),
                shape = RoundedCornerShape(JOINING_TILE_CORNER),
                color = MaterialTheme.colorScheme.surfaceContainerLowest
            ) {
                Box(contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(JOINING_RING_SIZE).semantics { contentDescription = label },
                        color = MaterialTheme.colorScheme.primary,
                        strokeWidth = 2.dp
                    )
                }
            }
        }
    }

    companion object {
        private const val TAG = "MainActivity"
        private const val STATE_PENDING_INVITE = "pending_invite"
        private const val SCRIM_ALPHA = 0.4f
        private val SHEET_BLOCK_SPACING = 12.dp
        private val JOINING_TILE_SIZE = 40.dp
        private val JOINING_TILE_CORNER = 16.dp
        private val JOINING_RING_SIZE = 22.dp
        private val RELAY_ROW_MIN_HEIGHT = 64.dp
    }
}
