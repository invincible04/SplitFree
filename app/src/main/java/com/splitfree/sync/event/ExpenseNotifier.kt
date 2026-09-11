package com.splitfree.sync.event

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.splitfree.R
import com.splitfree.domain.model.expense.Expense
import com.splitfree.domain.model.expense.Settlement
import com.splitfree.util.DebugLog as Log
import kotlinx.serialization.json.Json

/**
 * Shows local notifications for incoming expenses and settlements.
 *
 * Privacy: the full notification (amount, description) is [NotificationCompat.VISIBILITY_PRIVATE], so
 * on a locked device Android shows the [Content.publicTitle] / [Content.publicText] redacted
 * version instead: group name plus "New expense", never money or descriptions.
 */
object ExpenseNotifier {
    private const val TAG = "ExpenseNotifier"
    private const val CHANNEL_ID = "splitfree_expenses"
    private var channelCreated = false
    private val json = Json { ignoreUnknownKeys = true }

    /** Full and lock-screen (public) text for one notification. */
    internal data class Content(val title: String, val text: String, val publicTitle: String, val publicText: String)

    fun ensureChannel(context: Context) {
        if (channelCreated) return
        val channel =
            NotificationChannel(
                CHANNEL_ID,
                context.getString(R.string.notif_channel_expenses_name),
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply { description = context.getString(R.string.notif_channel_expenses_description) }
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        channelCreated = true
    }

    /**
     * True when we are allowed to post: the user has not disabled notifications for the app and,
     * on API 33+, `POST_NOTIFICATIONS` has been granted. [NotificationManagerCompat.notify] silently
     * drops the notification otherwise; checking first keeps the intent explicit and avoids the
     * runtime `SecurityException` some OEM builds throw.
     */
    fun canNotify(context: Context): Boolean {
        if (!NotificationManagerCompat.from(context).areNotificationsEnabled()) return false
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return true
        return ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
    }

    /**
     * Show a notification if the event is an expense or settlement from someone else.
     *
     * @param context Android context for NotificationManager
     * @param eventType event type tag (only `expense` and `settlement` trigger notifications)
     * @param decryptedContent decrypted JSON content of the event
     * @param authorPubkey pubkey of the event author
     * @param myPubkey current user's pubkey (self-authored events are skipped)
     * @param groupName display name of the group
     */
    fun notifyIfNeeded(
        context: Context,
        eventType: String,
        decryptedContent: String?,
        authorPubkey: String,
        myPubkey: String,
        groupName: String
    ) {
        if (authorPubkey == myPubkey || decryptedContent == null) return
        val content = buildContent(context, eventType, decryptedContent, groupName) ?: return
        if (!canNotify(context)) {
            Log.i(TAG, "Notifications disabled or not permitted; skipping $eventType notification")
            return
        }
        ensureChannel(context)
        val notifId = (System.currentTimeMillis() % Int.MAX_VALUE).toInt()
        try {
            NotificationManagerCompat.from(context).notify(notifId, buildNotification(context, content))
        } catch (e: SecurityException) {
            // canNotify() already checked POST_NOTIFICATIONS; a few OEM builds still throw here.
            Log.w(TAG, "Notification rejected: ${e.message}")
        }
    }

    /** Builds the notification for [content]; `null` when [eventType] is not one we surface. */
    internal fun buildContent(
        context: Context,
        eventType: String,
        decryptedContent: String,
        groupName: String
    ): Content? = when (eventType) {
        "expense" -> buildExpenseNotification(context, decryptedContent, groupName)
        "settlement" -> buildSettlementNotification(context, decryptedContent, groupName)
        else -> null
    }

    internal fun buildNotification(context: Context, content: Content): android.app.Notification {
        val publicVersion =
            NotificationCompat
                .Builder(context, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentTitle(content.publicTitle)
                .setContentText(content.publicText)
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                .build()
        return NotificationCompat
            .Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(content.title)
            .setContentText(content.text)
            .setAutoCancel(true)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setPublicVersion(publicVersion)
            .build()
    }

    private fun buildExpenseNotification(context: Context, content: String, groupName: String): Content? = try {
        val expense = json.decodeFromString<Expense>(content)
        val amount = formatAmount(expense.amount, expense.currency)
        val safeDesc = sanitize(expense.description)
        val safeName = sanitize(groupName)
        Content(
            title = context.getString(R.string.notif_expense_title, safeName),
            text = context.getString(R.string.notif_expense_text, amount, safeDesc),
            publicTitle = safeName,
            publicText = context.getString(R.string.notif_public_new_expense)
        )
    } catch (_: Exception) {
        null
    }

    private fun buildSettlementNotification(context: Context, content: String, groupName: String): Content? = try {
        val settlement = json.decodeFromString<Settlement>(content)
        val amount = formatAmount(settlement.amount, settlement.currency)
        val safeName = sanitize(groupName)
        Content(
            title = context.getString(R.string.notif_settlement_title, safeName),
            text = context.getString(R.string.notif_settlement_text, amount),
            publicTitle = safeName,
            publicText = context.getString(R.string.notif_public_new_settlement)
        )
    } catch (_: Exception) {
        null
    }

    /** Truncate and strip control characters from untrusted strings for notification display. */
    private fun sanitize(input: String): String = input.take(100).replace(Regex("[\\p{Cntrl}]"), "")

    private fun formatAmount(amountSmallest: Long, currency: String): String = com.splitfree.util.CurrencyFormatter
        .format(amountSmallest, currency)
}
