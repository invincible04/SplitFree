package com.splitfree.sync.event

import com.splitfree.di.ApplicationScope
import com.splitfree.domain.model.group.GroupMeta
import com.splitfree.domain.repository.GroupRepositoryContract
import com.splitfree.domain.usecase.group.MigrateGroupUseCase
import com.splitfree.domain.usecase.group.RevokeKeyUseCase
import com.splitfree.domain.usecase.sync.SelfHealUseCase
import com.splitfree.util.DebugLog as Log
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json

/**
 * Handles post-storage side effects for `group_meta`, `group_migrate`, and `key_revocation` events.
 */
@Singleton
class EventPostProcessor
@Inject
constructor(
    private val groupRepo: GroupRepositoryContract,
    private val migrateGroup: MigrateGroupUseCase,
    private val revokeKey: RevokeKeyUseCase,
    private val selfHeal: SelfHealUseCase,
    @ApplicationScope private val appScope: CoroutineScope
) {
    private val json = Json { ignoreUnknownKeys = true }

    /**
     * Process a post-storage side effect.
     *
     * @param eventType one of `group_meta`, `group_migrate`, `key_revocation`
     * @param decrypted decrypted event content JSON, or null to skip
     * @param authorHex pubkey of the event author
     * @param groupId target group UUID
     * @param createdAt event timestamp (unix seconds)
     * @param nonCancellable if true, runs inside [NonCancellable] context
     */
    suspend fun handle(
        eventType: String,
        decrypted: String?,
        authorHex: String,
        groupId: String,
        createdAt: Long,
        nonCancellable: Boolean
    ) {
        if (decrypted == null) return

        when (eventType) {
            "group_meta" -> handleGroupMeta(decrypted, authorHex, groupId, createdAt, nonCancellable)
            "group_migrate" -> runSafe(nonCancellable, "group_migrate", groupId) {
                migrateGroup.handleMigration(decrypted, authorHex, groupId)
            }
            "key_revocation" -> runSafe(nonCancellable, "key_revocation", groupId) {
                revokeKey.handleRevocation(decrypted, authorHex, groupId)
            }
        }
    }

    private suspend fun handleGroupMeta(
        decrypted: String,
        authorHex: String,
        groupId: String,
        createdAt: Long,
        nonCancellable: Boolean
    ) {
        runSafe(nonCancellable, "group_meta", groupId) {
            val meta = json.decodeFromString<GroupMeta>(decrypted)
            if (meta.members.isEmpty()) return@runSafe

            val currentGroup = groupRepo.getById(groupId)
            val isCreator =
                currentGroup == null ||
                    (currentGroup.createdBy.isNotEmpty() && authorHex == currentGroup.createdBy)

            val finalMembers =
                if (isCreator) {
                    meta.members
                } else {
                    ((currentGroup?.members ?: emptyList()) + authorHex).distinct()
                }
            val finalName = if (isCreator) meta.name else (currentGroup?.name ?: meta.name)
            val finalRelays = if (isCreator) meta.relays else (currentGroup?.relays ?: meta.relays)
            val finalMemberNames =
                if (isCreator) {
                    meta.memberNames
                } else {
                    // Non-creator events may only contribute their own display name.
                    (currentGroup?.memberNames ?: emptyMap()).toMutableMap().apply {
                        val authorName = meta.memberNames[authorHex]?.trim().orEmpty().take(50)
                        if (authorName.isNotEmpty()) {
                            put(authorHex, authorName)
                        } else {
                            remove(authorHex)
                        }
                    }
                }
            val trustedCreatedBy =
                if (currentGroup != null &&
                    currentGroup.createdBy.isNotEmpty() &&
                    authorHex == currentGroup.createdBy
                ) {
                    meta.createdBy.ifEmpty { authorHex }
                } else {
                    ""
                }
            val relaysChanged = currentGroup != null && currentGroup.relays.toSet() != finalRelays.toSet()

            Log.i(TAG, "Applying group_meta for $groupId: ${finalMembers.size} members, name=$finalName")
            groupRepo.updateFromMeta(
                groupId,
                finalName,
                finalMembers,
                finalRelays,
                createdAt,
                trustedCreatedBy,
                finalMemberNames
            )

            if (relaysChanged) {
                Log.i(TAG, "Relays changed for $groupId — triggering eager self-heal")
                appScope.launch {
                    try {
                        selfHeal(groupId)
                    } catch (
                        e: Exception
                    ) {
                        Log.w(TAG, "Eager self-heal failed for $groupId: ${e.message}")
                    }
                }
            }
        }
    }

    private suspend inline fun runSafe(
        nonCancellable: Boolean,
        eventType: String,
        groupId: String,
        crossinline block: suspend () -> Unit
    ) {
        try {
            if (nonCancellable) withContext(NonCancellable) { block() } else block()
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Failed to process $eventType for $groupId: ${e.message}", e)
        }
    }

    companion object {
        private const val TAG = "EventPostProcessor"
    }
}
