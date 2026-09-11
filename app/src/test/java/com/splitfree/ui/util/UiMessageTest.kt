package com.splitfree.ui.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class UiMessageTest {
    @Test
    fun `Res with the same id and arguments is equal and hashes alike`() {
        assertEquals(UiMessage.Res(1, "host", 2), UiMessage.Res(1, "host", 2))
        assertEquals(UiMessage.Res(1, "host", 2).hashCode(), UiMessage.Res(1, "host", 2).hashCode())
        assertEquals(UiMessage.Res(7), UiMessage.Res(7))
    }

    @Test
    fun `Res differs by id or by any argument`() {
        assertNotEquals(UiMessage.Res(1, "a"), UiMessage.Res(2, "a"))
        assertNotEquals(UiMessage.Res(1, "a"), UiMessage.Res(1, "b"))
        assertNotEquals(UiMessage.Res(1, "a"), UiMessage.Res(1))
        assertNotEquals(UiMessage.Res(1) as UiMessage, UiMessage.Raw("1") as UiMessage)
    }

    @Test
    fun `Plural compares id count and arguments`() {
        assertEquals(UiMessage.Plural(3, 5, 5), UiMessage.Plural(3, 5, 5))
        assertNotEquals(UiMessage.Plural(3, 5, 5), UiMessage.Plural(3, 6, 6))
    }

    @Test
    fun `throwable message becomes Raw and a missing message falls back to the resource`() {
        assertEquals(UiMessage.Raw("disk full"), IllegalStateException("disk full").toUiMessage(42))
        assertEquals(UiMessage.Res(42), IllegalStateException().toUiMessage(42))
    }
}
