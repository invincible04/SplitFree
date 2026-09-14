package com.splitfree.sync.worker

import com.splitfree.domain.crypto.NostrEvent
import com.splitfree.domain.crypto.isAddressedTo
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NostrEventAddressingTest {
    private val me = "aa".repeat(32)
    private val other = "bb".repeat(32)

    private fun event(vararg tags: List<String>) = NostrEvent(
        id = "e",
        pubkey = other,
        createdAt = 1,
        kind = 30078,
        tags = tags.toList(),
        content = "x",
        sig = "s"
    )

    @Test
    fun `an event without a p tag is addressed to everyone`() {
        assertTrue(event(listOf("g", "group"), listOf("t", "expense")).isAddressedTo(me))
    }

    @Test
    fun `an event whose p tag names me is addressed to me`() {
        assertTrue(event(listOf("g", "group"), listOf("p", me)).isAddressedTo(me))
    }

    @Test
    fun `an event whose p tag names someone else is not addressed to me`() {
        assertFalse(event(listOf("g", "group"), listOf("p", other)).isAddressedTo(me))
    }

    @Test
    fun `an event with several p tags including mine is addressed to me`() {
        assertTrue(event(listOf("p", other), listOf("g", "group"), listOf("p", me)).isAddressedTo(me))
    }

    @Test
    fun `a p tag without a value neither addresses nor excludes anyone`() {
        assertTrue(event(listOf("p")).isAddressedTo(me))
        assertFalse(event(listOf("p"), listOf("p", other)).isAddressedTo(me))
    }
}
