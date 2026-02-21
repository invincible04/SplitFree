package com.splitfree.domain.usecase.group

import com.splitfree.domain.crypto.EventSigner
import com.splitfree.domain.crypto.GroupEncryption
import com.splitfree.domain.crypto.NostrEvent
import com.splitfree.domain.crypto.nip.Nip44
import com.splitfree.domain.model.group.Group
import com.splitfree.domain.model.group.GroupMeta
import com.splitfree.domain.model.group.GroupMigration
import com.splitfree.domain.repository.EventPublisherContract
import com.splitfree.domain.repository.GroupRepositoryContract
import com.splitfree.domain.repository.IdentityContract
import com.splitfree.domain.util.hexToBytes
import com.splitfree.util.DebugLog as Log
import java.util.UUID
import javax.inject.Inject
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json

/**
 * Removes a member by migrating the group:
 * 1. Generate new group ID + key
 * 2. Publish "group_migrate" event to OLD group (encrypted with old key)
 *    - Contains new group ID, member list, and per-member encrypted new key
 *    - The new group key is encrypted individually for each remaining member
 *      using NIP-44 (creator privkey → member pubkey), so the removed member
 *      cannot obtain it even though they can decrypt the outer event
 * 3. Create the new group locally with remaining members
 * 4. Publish group_meta to NEW group (encrypted with new key)
 */
class MigrateGroupUseCase
@Inject
constructor(
    private val groupRepo: GroupRepositoryContract,
    private val encryption: GroupEncryption,
    private val identity: IdentityContract,
    private val signer: EventSigner,
    private val eventPublisher: EventPublisherContract
) {
    private val json = Json { ignoreUnknownKeys = true }

    /**
     * @param oldGroupId the group to migrate from
     * @param removePubkey the member pubkey to exclude
     * @return the new Group
     * @throws IllegalStateException if caller is not the group creator or member not found
     */
    suspend operator fun invoke(oldGroupId: String, removePubkey: String): Group {
        // NonCancellable: group migration must complete atomically even if the
        // calling coroutine scope is cancelled (screen rotation, back press)
        return withContext(NonCancellable) {
            val oldGroup =
                groupRepo.getById(oldGroupId)
                    ?: error("Group $oldGroupId not found")
            val myPubkey = identity.getPublicKeyHex()
            check(oldGroup.createdBy == myPubkey) { "Only the group creator can remove members" }
            check(removePubkey in oldGroup.members) { "Member not in group" }
            check(removePubkey != myPubkey) { "Cannot remove yourself" }

            val remainingMembers = oldGroup.members - removePubkey
            val newGroupKey = encryption.generateGroupKey()
            val newGroup =
                Group(
                    id = UUID.randomUUID().toString(),
                    name = oldGroup.name,
                    description = oldGroup.description,
                    createdBy = myPubkey,
                    createdAt = System.currentTimeMillis() / 1000,
                    members = remainingMembers,
                    relays = oldGroup.relays,
                    memberNames = oldGroup.memberNames.filterKeys { it in remainingMembers }
                )

            // Encrypt the new group key individually for each remaining member
            // using NIP-44 direct encryption (creator privkey → member pubkey).
            // The removed member cannot decrypt these even if they decrypt the outer event.
            val privKey = identity.getPrivateKeyBytes()
            val encryptedKeys = mutableMapOf<String, String>()
            try {
                for (memberPubHex in remainingMembers) {
                    val memberPubBytes = memberPubHex.hexToBytes()
                    val convKey = Nip44.getConversationKey(privKey, memberPubBytes)
                    encryptedKeys[memberPubHex] = Nip44.encrypt(newGroupKey, convKey)
                }
            } finally {
                privKey.fill(0)
            }

            // 1. Publish group_migrate to OLD group so remaining members auto-migrate
            val oldGroupKey =
                groupRepo.getGroupKey(oldGroupId)
                    ?: error("Old group key not found")
            val migratePayload =
                json.encodeToString(
                    GroupMigration.serializer(),
                    GroupMigration(
                        newGroupId = newGroup.id,
                        encryptedKeys = encryptedKeys,
                        members = remainingMembers,
                        removedMember = removePubkey
                    )
                )
            val migrateEncrypted = encryption.encrypt(migratePayload, oldGroupKey)
            val migrateEvent =
                signer.createSignedEvent(
                    groupId = oldGroupId,
                    eventType = "group_migrate",
                    encryptedContent = migrateEncrypted
                )
            saveAndPublish(migrateEvent, oldGroupId, migrateEncrypted, migratePayload, "group_migrate")

            // 2. Save new group locally
            groupRepo.save(newGroup, newGroupKey)

            // 3. Delete old group key — forward secrecy: removed member must not benefit from key lingering
            groupRepo.deleteGroupKey(oldGroupId)

            // 4. Publish group_meta to NEW group
            val metaJson =
                json.encodeToString(
                    GroupMeta.serializer(),
                    GroupMeta(
                        name = newGroup.name,
                        description = newGroup.description,
                        createdBy = newGroup.createdBy,
                        createdAt = newGroup.createdAt,
                        members = newGroup.members,
                        relays = newGroup.relays,
                        memberNames = newGroup.memberNames
                    )
                )
            val metaEncrypted = encryption.encrypt(metaJson, newGroupKey)
            val metaEvent =
                signer.createSignedEvent(
                    groupId = newGroup.id,
                    eventType = "group_meta",
                    encryptedContent = metaEncrypted
                )
            saveAndPublish(metaEvent, newGroup.id, metaEncrypted, metaJson, "group_meta")

            Log.i(TAG, "Migrated group ${oldGroup.name}: removed $removePubkey, new group ${newGroup.id}")
            newGroup
        }
    }

    /**
     * Handle an incoming group_migrate event — auto-join the new group.
     * Called from sync processing when eventType == "group_migrate".
     */
    suspend fun handleMigration(decryptedContent: String, authorPubkey: String, oldGroupId: String) {
        val migration =
            try {
                json.decodeFromString<GroupMigration>(decryptedContent)
            } catch (e: Exception) {
                Log.w(TAG, "Invalid group_migrate payload: ${e.message}")
                return
            }

        val myPubkey = identity.getPublicKeyHex()
        if (myPubkey !in migration.members) {
            Log.i(TAG, "I was removed from group $oldGroupId")
            return
        }

        // Don't re-create if we already have the new group
        if (groupRepo.getById(migration.newGroupId) != null) return

        // Validate new group ID is a valid UUID format
        try {
            java.util.UUID.fromString(migration.newGroupId)
        } catch (_: Exception) {
            Log.w(TAG, "Invalid newGroupId format in group_migrate: ${migration.newGroupId}")
            return
        }

        val oldGroup = groupRepo.getById(oldGroupId) ?: return
        // Only trust migration from the group creator
        if (authorPubkey != oldGroup.createdBy) {
            Log.w(TAG, "Ignoring group_migrate from non-creator $authorPubkey")
            return
        }

        // Validate that new member list is a subset of old members minus the removed member
        val expectedMembers = oldGroup.members.toSet() - migration.removedMember
        if (!expectedMembers.containsAll(migration.members.toSet())) {
            Log.w(TAG, "group_migrate contains members not in original group — rejecting")
            return
        }
        if (migration.removedMember !in oldGroup.members) {
            Log.w(TAG, "group_migrate claims to remove non-member ${migration.removedMember}")
            return
        }

        // Decrypt the new group key using NIP-44 (my privkey + creator's pubkey)
        val myEncryptedKey = migration.encryptedKeys[myPubkey]
        if (myEncryptedKey == null) {
            Log.w(TAG, "No encrypted key for me in group_migrate")
            return
        }
        val privKey = identity.getPrivateKeyBytes()
        val newGroupKey: String
        try {
            val creatorPubBytes = authorPubkey.hexToBytes()
            val convKey = Nip44.getConversationKey(privKey, creatorPubBytes)
            newGroupKey = Nip44.decrypt(myEncryptedKey, convKey)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to decrypt new group key: ${e.message}")
            return
        } finally {
            privKey.fill(0)
        }

        val newGroup =
            Group(
                id = migration.newGroupId,
                name = oldGroup.name,
                description = oldGroup.description,
                createdBy = authorPubkey,
                createdAt = System.currentTimeMillis() / 1000,
                members = migration.members,
                relays = oldGroup.relays,
                memberNames = oldGroup.memberNames.filterKeys { it in migration.members }
            )
        groupRepo.save(newGroup, newGroupKey)
        Log.i(TAG, "Auto-joined migrated group ${newGroup.id} (removed: ${migration.removedMember})")
    }

    private suspend fun saveAndPublish(
        event: NostrEvent,
        groupId: String,
        encrypted: String,
        plaintext: String,
        eventType: String
    ) {
        eventPublisher.publishDirect(event, groupId, encrypted, eventType)
    }

    companion object {
        private const val TAG = "MigrateGroupUseCase"
    }
}
