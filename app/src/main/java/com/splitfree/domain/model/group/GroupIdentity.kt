package com.splitfree.domain.model.group

import com.splitfree.domain.util.hexToBytes
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.util.UUID

/**
 * Binds a group id to the identity of its creator.
 *
 * ```
 * groupId = UUID.nameUUIDFromBytes(SHA256("splitfree-group-v1" || creatorPubkey(32) || createdAt(8, big-endian)))
 * ```
 *
 * Because the id is a pure function of `(creator, createdAt)`, any party that learns those two
 * values from an untrusted source (an invite link, an imported `group_meta`) can check that the
 * claimed creator really is the one this group was minted for. An attacker cannot present an
 * existing group id together with a different creator without breaking SHA-256.
 */
object GroupIdentity {
    private const val DOMAIN = "splitfree-group-v1"
    private const val PUBKEY_BYTES = 32

    /**
     * Derive the canonical group id for a creator and creation time.
     *
     * @param creatorPubHex creator's 32-byte x-only public key as 64 hex chars
     * @param createdAt group creation time (unix seconds)
     * @return lowercase UUID string
     * @throws IllegalArgumentException if [creatorPubHex] is not a 32-byte hex string
     */
    fun derive(creatorPubHex: String, createdAt: Long): String {
        require(creatorPubHex.length == PUBKEY_BYTES * 2) { "Creator pubkey must be 64 hex chars" }
        val creatorBytes = creatorPubHex.hexToBytes()
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update(DOMAIN.toByteArray(Charsets.UTF_8))
        digest.update(creatorBytes)
        digest.update(ByteBuffer.allocate(Long.SIZE_BYTES).putLong(createdAt).array())
        return UUID.nameUUIDFromBytes(digest.digest()).toString()
    }

    /**
     * @return true if [groupId] is exactly the id [derive] produces for `(creatorPubHex, createdAt)`.
     *   Malformed inputs yield `false` rather than throwing.
     */
    fun matches(groupId: String, creatorPubHex: String, createdAt: Long): Boolean = try {
        derive(creatorPubHex, createdAt).equals(groupId, ignoreCase = true)
    } catch (_: IllegalArgumentException) {
        false
    }
}
