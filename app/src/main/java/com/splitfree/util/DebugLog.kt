package com.splitfree.util

import android.util.Log as AndroidLog
import com.splitfree.BuildConfig
import com.splitfree.util.DebugLog.MAX_ENTRIES
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentLinkedDeque
import java.util.concurrent.atomic.AtomicLong

/**
 * Debug-only in-app buffer of the last [MAX_ENTRIES] entries, with Android logcat forwarding.
 *
 * - Debug/info output is debug-only; warning/error output reaches logcat in both build variants.
 * - Warning/error message strings pass through [sanitize], but the debug buffer retains original text and throwable
 *   overloads pass the throwable to logcat unchanged.
 * - Callers must avoid sensitive data; pattern-based redaction is not a guarantee that logs are safe to share.
 */
object DebugLog {
    private const val MAX_ENTRIES = 500

    /** Number of leading hex characters kept from a redacted pubkey / event id. */
    private const val HEX_PREFIX_KEPT = 8

    /**
     * A run of 64 or more hex characters: a secp256k1 pubkey, Nostr event id, raw private key, or (at 128) a Schnorr
     * signature.
     *
     * - Shorter runs such as the `take(8)` prefixes the code already logs are left untouched.
     */
    private val HEX64 = Regex("[0-9a-fA-F]{64,}")

    /** Compact invite link with its bearer payload. */
    private val INVITE_LINK = Regex("splitfree://join\\?d=[^\\s]+")

    private const val INVITE_LINK_REDACTED = "splitfree://join?d=[REDACTED]"

    private val sequence = AtomicLong()

    data class Entry(
        val timestamp: Long = System.currentTimeMillis(),
        val level: Char,
        val tag: String,
        val message: String,
        /** Monotonically increasing id, stable across [entries] snapshots; the debug screen's LazyColumn key. */
        val seq: Long = sequence.getAndIncrement()
    ) {
        private val fmt = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

        fun format(): String = "${fmt.format(Date(timestamp))} $level/$tag: $message"
    }

    private val buffer = ConcurrentLinkedDeque<Entry>()

    /** Snapshot of current entries (newest last). */
    val entries: List<Entry> get() = buffer.toList()

    /** Incremented on each debug-buffer append and clear; a polling hint, not an observable flow. */
    @Volatile
    var revision: Long = 0L
        private set

    /**
     * Shortens runs of at least 64 hex characters to [HEX_PREFIX_KEPT] characters plus `…`, and replaces compact
     * invite links with [INVITE_LINK_REDACTED].
     *
     * - Other text is unchanged, including recovery phrases, short identifiers and secrets outside those patterns.
     */
    fun sanitize(msg: String): String {
        val noInvites = INVITE_LINK.replace(msg, INVITE_LINK_REDACTED)
        return HEX64.replace(noInvites) { it.value.take(HEX_PREFIX_KEPT) + "…" }
    }

    private fun add(level: Char, tag: String, msg: String) {
        if (!BuildConfig.DEBUG) return
        buffer.addLast(Entry(level = level, tag = tag, message = msg))
        while (buffer.size > MAX_ENTRIES) buffer.pollFirst()
        revision++
    }

    fun d(tag: String, msg: String): Int {
        add('D', tag, msg)
        return if (BuildConfig.DEBUG) AndroidLog.d(tag, msg) else 0
    }

    fun i(tag: String, msg: String): Int {
        add('I', tag, msg)
        return if (BuildConfig.DEBUG) AndroidLog.i(tag, msg) else 0
    }

    fun w(tag: String, msg: String): Int {
        add('W', tag, msg)
        return AndroidLog.w(tag, sanitize(msg))
    }

    fun e(tag: String, msg: String): Int {
        add('E', tag, msg)
        return AndroidLog.e(tag, sanitize(msg))
    }

    fun e(tag: String, msg: String, tr: Throwable?): Int {
        add('E', tag, "$msg: ${tr?.message}")
        return AndroidLog.e(tag, sanitize(msg), tr)
    }

    fun w(tag: String, msg: String, tr: Throwable?): Int {
        add('W', tag, "$msg: ${tr?.message}")
        return AndroidLog.w(tag, sanitize(msg), tr)
    }

    fun clear() {
        buffer.clear()
        revision++
    }
}
