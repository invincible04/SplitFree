package com.splitfree.domain.usecase.group

import com.splitfree.domain.crypto.EventSigner
import com.splitfree.domain.crypto.GroupEncryption
import com.splitfree.domain.invite.InviteLinkCodec
import com.splitfree.domain.model.group.Group
import com.splitfree.domain.model.group.GroupIdentity
import com.splitfree.domain.model.group.GroupMeta
import com.splitfree.domain.repository.EventPublisherContract
import com.splitfree.domain.repository.GroupRepositoryContract
import com.splitfree.domain.repository.IdentityContract
import com.splitfree.domain.repository.SettingsContract
import com.splitfree.domain.util.RelayDefaults
import com.splitfree.util.DebugLog as Log
import java.util.UUID
import javax.inject.Inject
import kotlinx.serialization.json.Json

/**
 * Creates a new expense group, generates its symmetric key, and publishes `group_meta` to relays.
 *
 * [GroupIdentity] binds the group id to `(creator, createdAt)`, allowing invite links and imported
 * metadata to reject a different original creator for the same group.
 *
 * The group row, first `group_meta` and outbox entry commit in one Room transaction. The secure-store
 * key is written first and may survive rollback, so retries reuse it. With the same identity,
 * `(createdAt, commandId)` and group details, a retry returns the earlier creation instead of duplicating it.
 */
class CreateGroupUseCase
@Inject
constructor(
    private val groupRepo: GroupRepositoryContract,
    private val encryption: GroupEncryption,
    private val identity: IdentityContract,
    private val signer: EventSigner,
    private val eventPublisher: EventPublisherContract,
    private val settings: SettingsContract
) {
    private val json = Json { ignoreUnknownKeys = true }

    /**
     * Create a new expense group and publish its `group_meta` to relays.
     *
     * @param name group display name (1 to 100 chars)
     * @param relays Nostr relay URLs for this group
     * @param createdAt creation time (unix seconds); with the creator it determines the group id, so a retry
     *   must pass the value of the interrupted attempt
     * @param expectedAuthorPubkey the identity the command was issued under, or null for the current one
     * @param commandId stable id of this creation; the `group_meta` is addressed under it on relays
     * @return the newly created [Group], or the group an earlier attempt of the same command already created
     * @throws IllegalArgumentException if [name] is blank or exceeds 100 chars, or new relays cannot fit an invite
     * @throws IllegalStateException if the identity changed, or a different group already owns the id
     */
    suspend operator fun invoke(
        name: String,
        relays: List<String> = RelayDefaults.DEFAULT_RELAYS,
        createdAt: Long = System.currentTimeMillis() / 1000,
        expectedAuthorPubkey: String? = null,
        commandId: String = UUID.randomUUID().toString()
    ): Group {
        require(name.isNotBlank() && name.length <= 100) { "Group name must be 1-100 characters" }
        require(commandId.isNotBlank()) { "Invalid creation command" }
        require(createdAt >= 0) { "Invalid creation timestamp" }
        val pubkey = currentAuthor()
        check(expectedAuthorPubkey == null || expectedAuthorPubkey == pubkey) {
            "Identity changed while creating the group"
        }
        val groupId = GroupIdentity.derive(pubkey, createdAt)
        groupRepo.getById(groupId)?.let { return reconcile(it, name, relays, pubkey, createdAt, commandId) }
        require(InviteLinkCodec.fitsInviteLink(relays)) { "Relays do not fit in an invite link" }
        // An attempt that stored the key but rolled back the row continues with the same key material.
        val groupKey = groupRepo.getGroupKeyForEpoch(groupId, 0) ?: encryption.generateGroupKey()
        val myName = settings.displayNameFor(pubkey)
        val names = if (myName.isNotBlank()) mapOf(pubkey to myName) else emptyMap()
        val group =
            Group(
                id = groupId,
                name = name,
                createdBy = pubkey,
                createdAt = createdAt,
                members = listOf(pubkey),
                relays = relays,
                memberNames = names
            )

        val meta = GroupMeta(
            name = group.name,
            description = group.description,
            createdBy = group.createdBy,
            createdAt = group.createdAt,
            members = group.members,
            relays = group.relays,
            memberNames = names
        )
        val metaJson = json.encodeToString(GroupMeta.serializer(), meta)
        val encrypted = encryption.encrypt(metaJson, groupKey)
        val event =
            signer.createSignedCommandEvent(
                groupId = group.id,
                eventType = "group_meta",
                encryptedContent = encrypted,
                commandId = commandId
            )
        if (!eventPublisher.publishCreatedGroup(event, group, groupKey)) {
            return reconcile(checkNotNull(groupRepo.getById(groupId)), name, relays, pubkey, createdAt, commandId)
        }
        Log.i(TAG, "Created group: ${group.id} name=$name creator=${pubkey.take(8)}")

        return group
    }

    /** The current identity's pubkey; the value a creation draft pins before its first attempt. */
    fun currentAuthor(): String = identity.getPublicKeyHex().also { check(it.isNotBlank()) { "Identity unavailable" } }

    /**
     * Reuse the stored group only when its creation event carries [commandId] and identity/details
     * still match. A colliding group id must never overwrite another creation.
     */
    private suspend fun reconcile(
        group: Group,
        name: String,
        relays: List<String>,
        author: String,
        createdAt: Long,
        commandId: String
    ): Group {
        check(currentAuthor() == author) { "Identity changed while creating the group" }
        check(eventPublisher.hasCreatedGroupCommand(group.id, author, commandId)) {
            "Another group was created at the same time. Close this draft and start again"
        }
        check(
            group.createdBy == author && group.createdAt == createdAt && group.name == name && group.relays == relays
        ) {
            "A group was already created with different details. Close this draft and start again"
        }
        checkNotNull(groupRepo.getGroupKeyForEpoch(group.id, group.keyEpoch)) { "Created group key is unavailable" }
        return group
    }

    companion object {
        private const val TAG = "CreateGroupUseCase"
    }
}
