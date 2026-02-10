package com.splitfree.sync

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import androidx.core.app.NotificationCompat
import com.splitfree.domain.model.Expense
import com.splitfree.domain.model.Settlement
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
        val channel = NotificationChannel(CHANNEL_ID, CHANNEL_NAME, NotificationManager.IMPORTANCE_DEFAULT)
            .apply { description = "Notifications for new expenses and settlements" }
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        channelCreated = true
    }

    /**
     * Show a notification if the event is an expense or settlement from someone else.
     * @param myPubkey the current user's public key (to skip self-authored events)
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
        val (title, text) = when (eventType) {
            "expense" -> buildExpenseNotification(decryptedContent, groupName) ?: return
            "settlement" -> buildSettlementNotification(decryptedContent, groupName) ?: return
            else -> return
        }
        ensureChannel(context)
        val notifId = (System.currentTimeMillis() % Int.MAX_VALUE).toInt()
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(title)
            .setContentText(text)
            .setAutoCancel(true)
            .build()
        context.getSystemService(NotificationManager::class.java).notify(notifId, notification)
    }

    private fun buildExpenseNotification(content: String, groupName: String): Pair<String, String>? {
        return try {
            val expense = json.decodeFromString<Expense>(content)
            val amount = formatAmount(expense.amount, expense.currency)
            "New expense in $groupName" to "$amount for ${expense.description}"
        } catch (_: Exception) { null }
    }

    private fun buildSettlementNotification(content: String, groupName: String): Pair<String, String>? {
        return try {
            val settlement = json.decodeFromString<Settlement>(content)
            val amount = formatAmount(settlement.amount, settlement.currency)
            "Settlement in $groupName" to "$amount settled"
        } catch (_: Exception) { null }
    }

    private fun formatAmount(amountSmallest: Long, currency: String): String {
        val major = amountSmallest / 100.0
        return when (currency.uppercase()) {
            "INR" -> "₹${"%.0f".format(major)}"
            "USD" -> "${"$"}${"%.2f".format(major)}"
            "EUR" -> "€${"%.2f".format(major)}"
            "GBP" -> "£${"%.2f".format(major)}"
            else -> "${"%.2f".format(major)} $currency"
        }
    }
}
