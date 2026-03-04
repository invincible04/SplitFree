package com.splitfree.util

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import android.os.Build
import android.text.format.DateFormat
import java.io.PrintWriter
import java.io.StringWriter
import kotlin.system.exitProcess

/**
 * Lightweight process diagnostics for long-running field debugging on real devices.
 *
 * Stores:
 * - last heartbeat (timestamp/source/detail)
 * - last uncaught exception (timestamp/thread/stacktrace)
 * - latest OS process-exit reason (API 30+)
 *
 * Data is local-only in SharedPreferences and can be copied from Settings.
 */
object ProcessHealthTracker {
    private const val PREFS = "splitfree_diagnostics"
    private const val KEY_HEARTBEAT_TS = "heartbeat_ts"
    private const val KEY_HEARTBEAT_SOURCE = "heartbeat_source"
    private const val KEY_HEARTBEAT_DETAIL = "heartbeat_detail"
    private const val KEY_CRASH_TS = "crash_ts"
    private const val KEY_CRASH_THREAD = "crash_thread"
    private const val KEY_CRASH_STACK = "crash_stack"
    private const val MAX_STACK_CHARS = 16_000

    @Volatile
    private var installed = false

    fun install(context: Context) {
        if (installed) return
        synchronized(this) {
            if (installed) return
            val appContext = context.applicationContext
            val previous = Thread.getDefaultUncaughtExceptionHandler()
            Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
                try {
                    recordCrash(appContext, thread, throwable)
                } catch (_: Exception) {
                }
                if (previous != null) {
                    previous.uncaughtException(thread, throwable)
                } else {
                    android.os.Process.killProcess(android.os.Process.myPid())
                    exitProcess(10)
                }
            }
            installed = true
        }
    }

    fun heartbeat(context: Context, source: String, detail: String? = null) {
        val safeSource = source.take(64)
        val safeDetail = detail?.take(256)
        prefs(context).edit()
            .putLong(KEY_HEARTBEAT_TS, System.currentTimeMillis())
            .putString(KEY_HEARTBEAT_SOURCE, safeSource)
            .putString(KEY_HEARTBEAT_DETAIL, safeDetail)
            .apply()
    }

    fun buildReport(context: Context): String {
        val p = prefs(context)
        val crashTs = p.getLong(KEY_CRASH_TS, 0L)
        val crashThread = p.getString(KEY_CRASH_THREAD, "") ?: ""
        val crashStack = p.getString(KEY_CRASH_STACK, "") ?: ""
        val hbTs = p.getLong(KEY_HEARTBEAT_TS, 0L)
        val hbSource = p.getString(KEY_HEARTBEAT_SOURCE, "") ?: ""
        val hbDetail = p.getString(KEY_HEARTBEAT_DETAIL, "") ?: ""

        val now = System.currentTimeMillis()
        val sb = StringBuilder()
        sb.appendLine("SplitFree Diagnostics")
        sb.appendLine("Generated: ${formatTime(context, now)}")
        sb.appendLine("SDK: ${Build.VERSION.SDK_INT}")
        sb.appendLine("App package: ${context.packageName}")
        sb.appendLine()

        sb.appendLine("Heartbeat")
        if (hbTs > 0L) {
            sb.appendLine("- Last: ${formatTime(context, hbTs)} (${age(now - hbTs)})")
            sb.appendLine("- Source: $hbSource")
            if (hbDetail.isNotBlank()) sb.appendLine("- Detail: $hbDetail")
        } else {
            sb.appendLine("- No heartbeat recorded")
        }
        sb.appendLine()

        sb.appendLine("Uncaught Crash")
        if (crashTs > 0L) {
            sb.appendLine("- Last: ${formatTime(context, crashTs)} (${age(now - crashTs)})")
            sb.appendLine("- Thread: $crashThread")
            if (crashStack.isNotBlank()) {
                sb.appendLine("- Stacktrace:")
                sb.appendLine(crashStack)
            }
        } else {
            sb.appendLine("- No uncaught crash recorded")
        }
        sb.appendLine()

        sb.appendLine("OS Exit Reason")
        sb.appendLine("- ${latestExitReason(context)}")

        return sb.toString()
    }

    private fun recordCrash(context: Context, thread: Thread, throwable: Throwable) {
        val sw = StringWriter()
        throwable.printStackTrace(PrintWriter(sw))
        val stack = sw.toString().take(MAX_STACK_CHARS)
        prefs(context).edit()
            .putLong(KEY_CRASH_TS, System.currentTimeMillis())
            .putString(KEY_CRASH_THREAD, thread.name.take(128))
            .putString(KEY_CRASH_STACK, stack)
            .apply()
    }

    private fun latestExitReason(context: Context): String {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return "Not available on API < 30"
        return try {
            val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            val latest = am.getHistoricalProcessExitReasons(context.packageName, 0, 1).firstOrNull()
                ?: return "No historical exit reason"
            val reasonText = reasonToText(latest.reason)
            val importance = latest.importance
            val pssKb = latest.pss
            val rssKb = latest.rss
            val ts = latest.timestamp
            "reason=$reasonText, status=${latest.status}, importance=$importance, " +
                "pssKb=$pssKb, rssKb=$rssKb, at=${formatTime(context, ts)}"
        } catch (e: Exception) {
            "Failed to read exit reason: ${e.javaClass.simpleName}: ${e.message}"
        }
    }

    private fun reasonToText(reason: Int): String = when (reason) {
        ApplicationExitInfo.REASON_ANR -> "ANR"
        ApplicationExitInfo.REASON_CRASH -> "CRASH"
        ApplicationExitInfo.REASON_CRASH_NATIVE -> "NATIVE_CRASH"
        ApplicationExitInfo.REASON_LOW_MEMORY -> "LOW_MEMORY"
        ApplicationExitInfo.REASON_SIGNALED -> "SIGNALED"
        ApplicationExitInfo.REASON_USER_REQUESTED -> "USER_REQUESTED"
        ApplicationExitInfo.REASON_EXIT_SELF -> "EXIT_SELF"
        ApplicationExitInfo.REASON_INITIALIZATION_FAILURE -> "INIT_FAILURE"
        ApplicationExitInfo.REASON_PERMISSION_CHANGE -> "PERMISSION_CHANGE"
        else -> "OTHER($reason)"
    }

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun formatTime(context: Context, millis: Long): String =
        DateFormat.format("yyyy-MM-dd HH:mm:ss", millis).toString()

    private fun age(deltaMs: Long): String {
        if (deltaMs < 0) return "0s ago"
        val totalSec = deltaMs / 1000
        val days = totalSec / 86_400
        val hours = (totalSec % 86_400) / 3600
        val mins = (totalSec % 3600) / 60
        val secs = totalSec % 60
        return when {
            days > 0 -> "${days}d ${hours}h ago"
            hours > 0 -> "${hours}h ${mins}m ago"
            mins > 0 -> "${mins}m ${secs}s ago"
            else -> "${secs}s ago"
        }
    }
}
