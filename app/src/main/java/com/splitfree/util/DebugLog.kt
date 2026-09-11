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
 * In-app log buffer that mirrors android.util.Log API.
 * Stores last [MAX_ENTRIES] log lines in a ring buffer for the debug screen.
 * Also forwards to Android logcat so adb logcat still works.
 *
 * Release hygiene: the in-app buffer and `d`/`i` logcat output are debug-only. `w`/`e` still reach
 * logcat in release builds (they are what a crash report needs) but go through [sanitize] first so
 * that 64-hex pubkeys / event ids and invite-link payloads never land in a world-readable log.
 */
object DebugLog {
    private const val MAX_ENTRIES = 500

    /** Number of leading hex characters kept from a redacted pubkey / event id. */
    private const val HEX_PREFIX_KEPT = 8

    /**
     * A run of 64 or more hex characters: a secp256k1 pubkey, Nostr event id, raw private key, or
     * (at 128) a Schnorr signature. Shorter runs such as the `take(8)` prefixes the code already logs
     * are left untouched.
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

    /** Incremented on every write — collect as StateFlow trigger. */
    @Volatile
    var revision: Long = 0L
        private set

    /**
     * Redacts identifiers that should never reach a release logcat: every 64-hex pubkey / event id
     * is cut to its first [HEX_PREFIX_KEPT] chars plus `…`, and every `splitfree://join?d=…` invite
     * link (a bearer credential carrying the group key) becomes [INVITE_LINK_REDACTED].
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
