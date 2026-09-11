package com.splitfree.domain.util

import org.junit.Assert.assertEquals
import org.junit.Test

class TextSanitizerTest {
    @Test
    fun `strips C0 controls and DEL`() {
        assertEquals("abc", TextSanitizer.stripControlChars("a\u0000b\u001Fc\u007F"))
        assertEquals("tab andnewline", TextSanitizer.stripControlChars("tab\t and\nnewline"))
    }

    @Test
    fun `strips bidi and embedding controls`() {
        assertEquals("trip", TextSanitizer.stripControlChars("\u202Etrip\u202C"))
        assertEquals("ab", TextSanitizer.stripControlChars("\u200Ea\u200Fb\u2066\u2067\u2068\u2069"))
    }

    @Test
    fun `trims surrounding whitespace`() {
        assertEquals("Ski weekend", TextSanitizer.stripControlChars("  Ski weekend \n"))
    }

    @Test
    fun `leaves ordinary unicode alone`() {
        assertEquals("Café ☕ 旅行", TextSanitizer.stripControlChars("Café ☕ 旅行"))
    }

    @Test
    fun `all-control input becomes empty`() {
        assertEquals("", TextSanitizer.stripControlChars("\u0001\u202A \u2069"))
    }
}
