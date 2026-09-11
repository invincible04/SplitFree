package com.splitfree.domain.model.group

import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GroupIdentityTest {
    private val creator = "ab".repeat(32)
    private val other = "cd".repeat(32)
    private val createdAt = 1_700_000_000L

    @Test
    fun `derive is deterministic`() {
        assertEquals(GroupIdentity.derive(creator, createdAt), GroupIdentity.derive(creator, createdAt))
    }

    @Test
    fun `derive is case-insensitive on the creator hex`() {
        assertEquals(GroupIdentity.derive(creator, createdAt), GroupIdentity.derive(creator.uppercase(), createdAt))
    }

    @Test
    fun `different creator produces different id`() {
        assertNotEquals(GroupIdentity.derive(creator, createdAt), GroupIdentity.derive(other, createdAt))
    }

    @Test
    fun `different createdAt produces different id`() {
        assertNotEquals(GroupIdentity.derive(creator, createdAt), GroupIdentity.derive(creator, createdAt + 1))
    }

    @Test
    fun `output is a valid lowercase UUID string`() {
        val id = GroupIdentity.derive(creator, createdAt)
        assertEquals(36, id.length)
        assertEquals(id, UUID.fromString(id).toString())
        assertEquals(id, id.lowercase())
        // nameUUIDFromBytes yields a version-3 UUID
        assertEquals(3, UUID.fromString(id).version())
    }

    @Test
    fun `matches is true for the derived triple`() {
        val id = GroupIdentity.derive(creator, createdAt)
        assertTrue(GroupIdentity.matches(id, creator, createdAt))
        assertTrue(GroupIdentity.matches(id.uppercase(), creator, createdAt))
    }

    @Test
    fun `matches is false when creator or createdAt differ`() {
        val id = GroupIdentity.derive(creator, createdAt)
        assertFalse(GroupIdentity.matches(id, other, createdAt))
        assertFalse(GroupIdentity.matches(id, creator, createdAt - 1))
        assertFalse(GroupIdentity.matches(UUID.randomUUID().toString(), creator, createdAt))
    }

    @Test
    fun `matches is false rather than throwing for malformed creator`() {
        val id = GroupIdentity.derive(creator, createdAt)
        assertFalse(GroupIdentity.matches(id, "", createdAt))
        assertFalse(GroupIdentity.matches(id, "not-hex", createdAt))
        assertFalse(GroupIdentity.matches(id, "ab".repeat(31), createdAt))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `derive rejects creator that is not 32 bytes`() {
        GroupIdentity.derive("ab".repeat(16), createdAt)
    }
}
