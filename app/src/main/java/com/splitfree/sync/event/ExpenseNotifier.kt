package com.splitfree.sync.event

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import androidx.core.app.NotificationCompat
import com.splitfree.domain.model.expense.Expense
import com.splitfree.domain.model.expense.Settlement
import kotlinx.serialization.json.Json

/**
 * Shows local notifications for incoming expenses and settlements.
 */
object ExpenseNotifier {
    private const val CHANNEL_ID = "splitfree_expenses"
    private const val CHANNEL_NAME = "Expense Updates"
    private var channelCreated = false
    private val json = Json { ignoreUnknownKeys = true }

    fun ensureChannel(context: Context) {
        if (channelCreated) return
        val channel =
            NotificationChannel(CHANNEL_ID, CHANNEL_NAME, NotificationManager.IMPORTANCE_DEFAULT)
                .apply { description = "Notifications for new expenses and settlements" }
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        channelCreated = true
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
        val (title, text) =
            when (eventType) {
                "expense" -> buildExpenseNotification(decryptedContent, groupName) ?: return
                "settlement" -> buildSettlementNotification(decryptedContent, groupName) ?: return
                else -> return
            }
        ensureChannel(context)
        val notifId = (System.currentTimeMillis() % Int.MAX_VALUE).toInt()
        val notification =
            NotificationCompat
                .Builder(context, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentTitle(title)
                .setContentText(text)
                .setAutoCancel(true)
                .build()
        context.getSystemService(NotificationManager::class.java).notify(notifId, notification)
    }

    private fun buildExpenseNotification(content: String, groupName: String): Pair<String, String>? = try {
        val expense = json.decodeFromString<Expense>(content)
        val amount = formatAmount(expense.amount, expense.currency)
        val safeDesc = sanitize(expense.description)
        val safeName = sanitize(groupName)
        "New expense in $safeName" to "$amount for $safeDesc"
    } catch (_: Exception) {
        null
    }

    private fun buildSettlementNotification(content: String, groupName: String): Pair<String, String>? = try {
        val settlement = json.decodeFromString<Settlement>(content)
        val amount = formatAmount(settlement.amount, settlement.currency)
        val safeName = sanitize(groupName)
        "Settlement in $safeName" to "$amount settled"
    } catch (_: Exception) {
        null
    }

    /** Truncate and strip control characters from untrusted strings for notification display. */
    private fun sanitize(input: String): String = input.take(100).replace(Regex("[\\p{Cntrl}]"), "")

    private fun formatAmount(amountSmallest: Long, currency: String): String = com.splitfree.util.CurrencyFormatter
        .format(amountSmallest, currency)
}
