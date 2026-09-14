package com.splitfree.domain.usecase.group

import com.splitfree.domain.invite.InviteLinkCodec
import com.splitfree.domain.model.group.Group
import com.splitfree.domain.repository.GroupRepositoryContract
import javax.inject.Inject

/**
 * Generates a shareable invite link carrying the group's current-epoch key.
 *
 * The link is a bearer token (see [InviteLinkCodec]); it does not need the inviter's private key.
 * Any member may invite, but the link always names the real creator because the group id is
 * derived from `(createdBy, createdAt)`.
 */
class CreateInviteLinkUseCase
@Inject
constructor(private val groupRepo: GroupRepositoryContract) {
    /**
     * Generate an invite link for a group.
     *
     * @param groupId target group UUID
     * @return `splitfree://join?d=...` deep link
     * @throws IllegalStateException if the group is not found, has no known creator (imported from a
     *   backup and not yet given one by a creator-signed `group_meta`), or has no key for its current epoch
     */
    suspend operator fun invoke(groupId: String): String {
        val group = groupRepo.getById(groupId) ?: throw IllegalStateException("Group $groupId not found")
        return invoke(group)
    }

    /** Encode one observed snapshot; epoch keys are immutable, so a concurrent rotation cannot relabel its key. */
    suspend operator fun invoke(group: Group): String {
        val groupId = group.id
        check(group.createdBy.isNotEmpty()) { "Cannot invite to a group with unknown creator" }
        val key = groupRepo.getGroupKeyForEpoch(groupId, group.keyEpoch)
            ?: throw IllegalStateException("No key for group $groupId at epoch ${group.keyEpoch}")
        return InviteLinkCodec.encode(group, key)
    }
}
