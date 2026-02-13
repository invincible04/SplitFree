package com.splitfree.domain.usecase

import android.util.Log
import com.splitfree.data.local.EventDao
import com.splitfree.data.local.OutboxDao
import com.splitfree.data.local.entities.EventEntity
import com.splitfree.data.local.entities.OutboxEntity
import com.splitfree.data.nostr.EventThrottler
import com.splitfree.data.repository.GroupRepository
import com.splitfree.domain.crypto.EventSigner
import com.splitfree.domain.crypto.GroupEncryption
import com.splitfree.domain.crypto.IdentityManager
import com.splitfree.domain.crypto.Nip44
import com.splitfree.domain.crypto.NostrEvent
import com.splitfree.domain.crypto.hexToBytes
import com.splitfree.domain.model.Group
import com.splitfree.domain.model.GroupMeta
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.util.UUID
import javax.inject.Inject

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
        private val groupRepo: GroupRepository,
        private val encryption: GroupEncryption,
        private val identity: IdentityManager,
        private val signer: EventSigner,
        private val eventDao: EventDao,
        private val outboxDao: OutboxDao,
        private val throttler: EventThrottler,
    ) {
        private val json = Json { ignoreUnknownKeys = true }

        /**
         * @param oldGroupId the group to migrate from
         * @param removePubkey the member pubkey to exclude
         * @return the new Group
         * @throws IllegalStateException if caller is not the group creator or member not found
         */
        suspend operator fun invoke(
            oldGroupId: String,
            removePubkey: String,
        ): Group {
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
                            removedMember = removePubkey,
                        ),
                    )
                val migrateEncrypted = encryption.encrypt(migratePayload, oldGroupKey)
                val migrateEvent =
                    signer.createSignedEvent(
                        groupId = oldGroupId,
                        eventType = "group_migrate",
                        encryptedContent = migrateEncrypted,
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
                        ),
                    )
                val metaEncrypted = encryption.encrypt(metaJson, newGroupKey)
                val metaEvent =
                    signer.createSignedEvent(
                        groupId = newGroup.id,
                        eventType = "group_meta",
                        encryptedContent = metaEncrypted,
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
        suspend fun handleMigration(
            decryptedContent: String,
            authorPubkey: String,
            oldGroupId: String,
        ) {
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
                )
            groupRepo.save(newGroup, newGroupKey)
            Log.i(TAG, "Auto-joined migrated group ${newGroup.id} (removed: ${migration.removedMember})")
        }

        private suspend fun saveAndPublish(
            event: NostrEvent,
            groupId: String,
            encrypted: String,
            plaintext: String,
            eventType: String,
        ) {
            eventDao.insert(
                EventEntity(
                    eventId = event.id,
                    groupId = groupId,
                    pubkey = event.pubkey,
                    createdAt = event.createdAt,
                    kind = 30078,
                    contentEncrypted = encrypted,
                    contentDecrypted = plaintext,
                    eventType = eventType,
                    expenseUuid = null,
                    sig = event.sig,
                    receivedAt = System.currentTimeMillis() / 1000,
                    originalEventJson = event.toJson(),
                ),
            )
            outboxDao.insert(OutboxEntity(eventId = event.id, eventJson = event.toJson(), createdAt = event.createdAt))
            throttler.enqueue(event)
        }

        companion object {
            private const val TAG = "MigrateGroupUseCase"
        }
    }

/**
 * Migration payload. The newGroupKey is NOT included in plaintext.
 * Instead, encryptedKeys maps each remaining member's pubkey to their
 * individually NIP-44-encrypted copy of the new group key.
 */
@Serializable
data class GroupMigration(
    val newGroupId: String,
    val encryptedKeys: Map<String, String>,
    val members: List<String>,
    val removedMember: String,
)
