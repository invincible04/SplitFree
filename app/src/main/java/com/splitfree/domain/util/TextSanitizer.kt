package com.splitfree.domain.util

/**
 * Sanitisation for short human-readable strings (group names) that arrive from untrusted input
 * such as invite links and backup files and are rendered verbatim in the UI.
 */
object TextSanitizer {
    /**
     * Characters that must never appear in a display name: C0/C1 controls, DEL, and the Unicode
     * bidi/embedding controls that can visually reorder or hide text in a confirmation dialog.
     */
    private val CONTROL_CHARS = Regex("[\\u0000-\\u001F\\u007F\\u200E\\u200F\\u202A-\\u202E\\u2066-\\u2069]")

    /** Strips control and bidi-override characters and surrounding whitespace. */
    fun stripControlChars(text: String): String = CONTROL_CHARS.replace(text, "").trim()
}
