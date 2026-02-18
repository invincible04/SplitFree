package com.splitfree.domain.usecase.group

import com.splitfree.data.nostr.NostrClient
import com.splitfree.domain.crypto.IdentityManager
import com.splitfree.domain.invite.InviteLinkCodec
import com.splitfree.domain.repository.GroupRepositoryContract
import com.splitfree.util.DebugLog as Log
import javax.inject.Inject

/**
 * Generates a shareable invite link (v3 with ephemeral key exchange, v2 fallback).
 */
class CreateInviteLinkUseCase
@Inject
constructor(
    private val groupRepo: GroupRepositoryContract,
    private val identity: IdentityManager,
    private val nostrClient: NostrClient
) {
    /**
     * Generate a shareable invite link for a group. Attempts v3 (ephemeral key exchange)
     * first; falls back to v2 (group key embedded in URL) if relay publish fails.
     *
     * @param groupId target group UUID
     * @return `splitfree://join?d=...` deep link
     * @throws IllegalStateException if the group or its key is not found locally
     */
    suspend operator fun invoke(groupId: String): String {
        val group = groupRepo.getById(groupId) ?: throw IllegalStateException("Group $groupId not found")
        val key = groupRepo.getGroupKey(groupId) ?: throw IllegalStateException("No key for group $groupId")
        val privKey = identity.getPrivateKeyBytes()
        try {
            val (v3Link, keyDeliveryEvent) = InviteLinkCodec.encode(group, key, privKey)
            val v3Ok =
                if (keyDeliveryEvent != null) {
                    try {
                        kotlinx.coroutines.withTimeout(5_000) { nostrClient.publish(keyDeliveryEvent) }
                    } catch (_: Exception) {
                        false
                    }
                } else {
                    false
                }
            if (v3Ok) return v3Link
            Log.w("CreateInviteLink", "v3 key delivery failed — falling back to v2 link")
            return InviteLinkCodec.encode(group, key).first
        } finally {
            privKey.fill(0)
        }
    }
}
