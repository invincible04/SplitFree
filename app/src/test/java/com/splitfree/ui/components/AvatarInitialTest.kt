package com.splitfree.ui.components

import org.junit.Assert.assertEquals
import org.junit.Test

class AvatarInitialTest {
    @Test
    fun `plain names use their upper-cased first letter`() {
        assertEquals("G", avatarInitial("goa trip"))
        assertEquals("Ü", avatarInitial("übernachtung"))
    }

    @Test
    fun `a leading emoji is kept whole instead of being split into a lone surrogate`() {
        assertEquals("🏖", avatarInitial("🏖 Beach"))
        assertEquals("🎉", avatarInitial("🎉"))
    }

    @Test
    fun `empty name yields an empty initial`() {
        assertEquals("", avatarInitial(""))
    }
}
