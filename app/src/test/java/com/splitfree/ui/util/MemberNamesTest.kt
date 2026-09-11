package com.splitfree.ui.util

import org.junit.Assert.assertEquals
import org.junit.Test

class MemberNamesTest {
    private val names = mapOf("aaaa-1111-key1" to "Sam", "bbbb-2222-key2" to "Sam", "cccc-3333-key3" to "Priya")
    private val everyone = names.keys

    @Test
    fun `unique display name is returned as is`() {
        assertEquals("Priya", disambiguatedMemberName("cccc-3333-key3", names, everyone))
    }

    @Test
    fun `colliding display names get a pubkey suffix long enough to be unique`() {
        assertEquals("Sam · 111-key1", disambiguatedMemberName("aaaa-1111-key1", names, everyone))
        assertEquals("Sam · 222-key2", disambiguatedMemberName("bbbb-2222-key2", names, everyone))
    }

    @Test
    fun `suffix grows past eight characters when the key tails also collide`() {
        val tails = mapOf("member-a-12345678" to "Sam", "member-b-12345678" to "Sam")
        assertEquals("Sam · a-12345678", disambiguatedMemberName("member-a-12345678", tails, tails.keys))
    }

    @Test
    fun `missing or blank display name falls back to the key prefix`() {
        assertEquals("dddd-444", disambiguatedMemberName("dddd-4444-key4", names, everyone))
        assertEquals("eeee-555", disambiguatedMemberName("eeee-5555-key5", names + ("eeee-5555-key5" to " "), everyone))
    }

    @Test
    fun `you label replaces the current user's own name`() {
        assertEquals(
            "You",
            disambiguatedMemberName("cccc-3333-key3", names, everyone, youLabel = "You", myPubkey = "cccc-3333-key3")
        )
        assertEquals(
            "Priya",
            disambiguatedMemberName("cccc-3333-key3", names, everyone, youLabel = null, myPubkey = "cccc-3333-key3")
        )
    }

    @Test
    fun `a collision is detected even when the member is not yet in everyone`() {
        assertEquals("Sam · 111-key1", disambiguatedMemberName("aaaa-1111-key1", names, setOf("bbbb-2222-key2")))
    }
}
