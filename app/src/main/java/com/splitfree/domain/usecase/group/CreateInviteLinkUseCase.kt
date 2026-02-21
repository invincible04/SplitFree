package com.splitfree.domain.usecase.group

import com.splitfree.domain.invite.InviteLinkCodec
import com.splitfree.domain.repository.GroupRepositoryContract
import com.splitfree.domain.repository.IdentityContract
import javax.inject.Inject

/**
 * Generates a shareable invite link with NIP-44 encrypted group key.
 * No relay involvement — the encrypted key is embedded directly in the URL.
 */
class CreateInviteLinkUseCase
@Inject
constructor(
    private val groupRepo: GroupRepositoryContract,
    private val identity: IdentityContract
) {
    /**
     * Generate an invite link for a group.
     *
     * @param groupId target group UUID
     * @return `splitfree://join?d=...` deep link
     * @throws IllegalStateException if the group or key is not found
     */
    suspend operator fun invoke(groupId: String): String {
        val group = groupRepo.getById(groupId) ?: throw IllegalStateException("Group $groupId not found")
        val myPubkey = identity.getPublicKeyHex()
        check(group.createdBy.isNotEmpty() && group.createdBy == myPubkey) {
            "Only the group creator can generate invite links"
        }
        val key = groupRepo.getGroupKey(groupId) ?: throw IllegalStateException("No key for group $groupId")
        val privKey = identity.getPrivateKeyBytes()
        try {
            return InviteLinkCodec.encode(group, key, privKey)
        } finally {
            privKey.fill(0)
        }
    }
}
