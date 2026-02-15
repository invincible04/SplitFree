package com.splitfree.util

import android.util.Log as AndroidLog
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.ConcurrentLinkedDeque

/**
 * In-app log buffer that mirrors android.util.Log API.
 * Stores last [MAX_ENTRIES] log lines in a ring buffer for the debug screen.
 * Also forwards to Android logcat so adb logcat still works.
 */
object DebugLog {
    private const val MAX_ENTRIES = 500

    data class Entry(
        val timestamp: Long = System.currentTimeMillis(),
        val level: Char,
        val tag: String,
        val message: String,
    ) {
        private val fmt = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

        fun format(): String = "${fmt.format(Date(timestamp))} $level/$tag: $message"
    }

    private val buffer = ConcurrentLinkedDeque<Entry>()

    /** Snapshot of current entries (newest last). */
    val entries: List<Entry> get() = buffer.toList()

    /** Incremented on every write — collect as StateFlow trigger. */
    @Volatile
    var revision: Long = 0L
        private set

    private fun add(level: Char, tag: String, msg: String) {
        buffer.addLast(Entry(level = level, tag = tag, message = msg))
        while (buffer.size > MAX_ENTRIES) buffer.pollFirst()
        revision++
    }

    fun d(tag: String, msg: String): Int { add('D', tag, msg); return AndroidLog.d(tag, msg) }
    fun i(tag: String, msg: String): Int { add('I', tag, msg); return AndroidLog.i(tag, msg) }
    fun w(tag: String, msg: String): Int { add('W', tag, msg); return AndroidLog.w(tag, msg) }
    fun e(tag: String, msg: String): Int { add('E', tag, msg); return AndroidLog.e(tag, msg) }
    fun e(tag: String, msg: String, tr: Throwable?): Int {
        add('E', tag, "$msg: ${tr?.message}")
        return AndroidLog.e(tag, msg, tr)
    }
    fun w(tag: String, msg: String, tr: Throwable?): Int {
        add('W', tag, "$msg: ${tr?.message}")
        return AndroidLog.w(tag, msg, tr)
    }

    fun clear() { buffer.clear(); revision++ }
}
