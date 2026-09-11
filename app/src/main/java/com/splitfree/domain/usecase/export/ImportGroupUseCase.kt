package com.splitfree.domain.usecase.export

import com.splitfree.domain.crypto.GroupEncryption
import com.splitfree.domain.crypto.NostrEvent
import com.splitfree.domain.crypto.nip.Nip44
import com.splitfree.domain.model.export.SplitFreeExport
import com.splitfree.domain.model.group.Group
import com.splitfree.domain.model.group.GroupIdentity
import com.splitfree.domain.model.group.GroupMeta
import com.splitfree.domain.repository.EventRepositoryContract
import com.splitfree.domain.repository.EventSnapshot
import com.splitfree.domain.repository.GroupRepositoryContract
import com.splitfree.domain.repository.IdentityContract
import com.splitfree.domain.validation.EventValidator
import com.splitfree.util.DebugLog as Log
import java.security.MessageDigest
import javax.inject.Inject
import kotlinx.serialization.json.Json

/**
 * Import group events from a `.splitfree` JSON export.
 *
 * Decrypts the embedded group key using the user's private key,
 * creates the group if needed, restores all epoch keys, then imports all events.
 */
class ImportGroupUseCase
@Inject
constructor(
    private val eventRepo: EventRepositoryContract,
    private val groupRepo: GroupRepositoryContract,
    private val encryption: GroupEncryption,
    private val eventValidator: EventValidator,
    private val identity: IdentityContract
) {
    private val json = Json { ignoreUnknownKeys = true }

    /**
     * @param jsonContent raw JSON string from a `.splitfree` export file
     * @return number of new events imported (duplicates are skipped)
     * @throws IllegalArgumentException if HMAC is missing/invalid or version is unsupported
     * @throws IllegalStateException if the group key cannot be obtained
     */
    suspend operator fun invoke(jsonContent: String): Int {
        val export = json.decodeFromString<SplitFreeExport>(jsonContent)
        require(export.version == 1) { "Unsupported export version: ${export.version}" }

        val groupId = export.groupId
        val groupKey = resolveGroupKey(export)

        require(export.hmac.isNotEmpty()) { "Export file missing integrity check (HMAC)" }
        val eventsJson = Json.encodeToString(export.events)
        val providedHmac = hexToBytes(export.hmac) ?: throw IllegalArgumentException("Invalid HMAC hex")
        val expectedHmac = HmacUtil.computeBytes(eventsJson, groupKey)
        require(MessageDigest.isEqual(providedHmac, expectedHmac)) {
            "Export file integrity check failed — file may have been tampered with"
        }

        // Create the group if it doesn't exist locally
        if (groupRepo.getById(groupId) == null && export.groupName.isNotEmpty()) {
            val group = Group(
                id = groupId,
                name = export.groupName.ifEmpty { "Imported Group" },
                createdBy = "",
                createdAt = export.exportedAt,
                members = listOf(identity.getPublicKeyHex()),
                relays = export.relays,
                keyEpoch = export.keyEpoch
            )
            groupRepo.save(group, groupKey)
        }

        // Restore all epoch keys so events from before key rotations can be decrypted
        if (export.encryptedEpochKeys.isNotEmpty()) {
            val privKey = identity.getPrivateKeyBytes()
            try {
                val convKey = Nip44.getConversationKey(privKey, identity.getPublicKeyBytes())
                for ((epochStr, encKey) in export.encryptedEpochKeys) {
                    val epoch = epochStr.toIntOrNull() ?: continue
                    val key = try {
                        Nip44.decrypt(encKey, convKey)
                    } catch (_: Exception) {
                        continue
                    }
                    groupRepo.saveGroupKeyForEpoch(groupId, epoch, key)
                }
            } finally {
                privKey.fill(0)
            }
        }

        val knownEventIds = eventRepo.getEventIds(groupId).toMutableSet()
        var imported = 0
        val group = groupRepo.getById(groupId)

        for (event in export.events) {
            val originalJson = event.originalEventJson ?: continue
            val parsed = NostrEvent.fromJson(originalJson) ?: continue
            if (!parsed.verify()) continue
            if (!eventValidator.isTimestampValidLenient(parsed.createdAt)) continue

            val eventId = parsed.id
            if (eventId in knownEventIds) continue

            val pubkey = parsed.pubkey
            val createdAt = parsed.createdAt
            val contentEncrypted = parsed.content
            val sig = parsed.sig
            val kind = parsed.kind

            var eventType = "unknown"
            var expenseUuid: String? = null
            for (tag in parsed.tags) {
                if (tag.size >= 2) {
                    when (tag[0]) {
                        "t" -> eventType = tag[1]
                        "x" -> expenseUuid = tag[1]
                    }
                }
            }

            if (group != null &&
                pubkey !in group.members &&
                eventType !in setOf("group_meta", "key_rotation", "key_revocation")
            ) {
                continue
            }

            if (eventType == "expense_correction" || eventType == "expense_delete") {
                val originalCreator = expenseUuid?.let { eventRepo.getExpenseByUuid(it, groupId)?.pubkey }
                if (!eventValidator.isCorrectionAuthorValid(eventType, pubkey, originalCreator)) continue
            }

            // Import is a local, integrity-checked batch operation; relay runtime rate limits
            // would incorrectly drop valid historical events from the same author.

            val decrypted = try {
                encryption.decrypt(contentEncrypted, groupKey)
            } catch (_: Exception) {
                null
            }
            if (decrypted != null && !eventValidator.isContentSafe(decrypted)) continue

            eventRepo.insert(
                EventSnapshot(
                    eventId = eventId, groupId = groupId, pubkey = pubkey,
                    createdAt = createdAt, kind = kind,
                    contentEncrypted = contentEncrypted, eventType = eventType,
                    expenseUuid = expenseUuid, sig = sig,
                    receivedAt = System.currentTimeMillis() / 1000,
                    originalEventJson = originalJson,
                    keyEpoch = event.keyEpoch
                )
            )
            knownEventIds += eventId
            imported++
        }

        // Replay group_meta events so member list, name, relays, and display names
        // are reconstructed. Epoch keys are already restored from encryptedEpochKeys.
        if (imported > 0) {
            replayPostImport(groupId)
        }

        return imported
    }

    /**
     * After importing events, replay group_meta events in chronological order
     * so the group entity reflects the full state (members, name, relays, epoch keys).
     *
     * An imported group starts with an empty `createdBy`. It is filled in only from a
     * `group_meta` whose author is the creator the group id was derived from
     * ([GroupIdentity.matches]); a `created_by` claim by anyone else is ignored, and if no
     * meta is bound to the id the creator stays unknown.
     *
     * Key rotation events are NOT replayed here — all epoch keys are restored
     * directly from [SplitFreeExport.encryptedEpochKeys] before event import.
     */
    private suspend fun replayPostImport(groupId: String) {
        val allEvents = eventRepo.getEventsByGroup(groupId)
            .filter { it.eventType == "group_meta" }
            .sortedBy { it.createdAt }

        var creatorKnown = false
        for (event in allEvents) {
            val key = groupRepo.getGroupKeyForEpoch(groupId, event.keyEpoch)
                ?: groupRepo.getGroupKey(groupId) ?: continue
            val decrypted = try {
                encryption.decrypt(event.contentEncrypted, key)
            } catch (_: Exception) {
                null
            } ?: continue

            try {
                val meta = json.decodeFromString<GroupMeta>(decrypted)
                if (meta.members.isEmpty()) continue

                var currentGroup = groupRepo.getById(groupId)

                // Bootstrap createdBy only from the author the group id is cryptographically bound to.
                if (currentGroup != null &&
                    currentGroup.createdBy.isEmpty() &&
                    meta.createdBy == event.pubkey &&
                    GroupIdentity.matches(groupId, event.pubkey, meta.createdAt)
                ) {
                    groupRepo.updateCreator(groupId, event.pubkey, meta.createdAt)
                    currentGroup = currentGroup.copy(createdBy = event.pubkey, createdAt = meta.createdAt)
                }
                if (currentGroup == null || currentGroup.createdBy.isNotEmpty()) creatorKnown = true

                val isCreator = currentGroup == null ||
                    (currentGroup.createdBy.isNotEmpty() && event.pubkey == currentGroup.createdBy)

                val finalMembers = if (isCreator) {
                    meta.members
                } else {
                    ((currentGroup?.members ?: emptyList()) + event.pubkey).distinct()
                }
                val finalName = if (isCreator) meta.name else (currentGroup?.name ?: meta.name)
                val finalRelays = if (isCreator) meta.relays else (currentGroup?.relays ?: meta.relays)
                val finalMemberNames = if (isCreator) {
                    meta.memberNames
                } else {
                    (currentGroup?.memberNames ?: emptyMap()).toMutableMap().apply {
                        val authorName = meta.memberNames[event.pubkey]?.trim().orEmpty().take(50)
                        if (authorName.isNotEmpty()) put(event.pubkey, authorName) else remove(event.pubkey)
                    }
                }
                val trustedCreatedBy = if (currentGroup != null &&
                    currentGroup.createdBy.isNotEmpty() &&
                    event.pubkey == currentGroup.createdBy
                ) {
                    meta.createdBy.ifEmpty { event.pubkey }
                } else {
                    ""
                }

                groupRepo.updateFromMeta(
                    groupId,
                    finalName,
                    finalMembers,
                    finalRelays,
                    event.createdAt,
                    trustedCreatedBy,
                    finalMemberNames
                )
            } catch (_: Exception) { }
        }

        if (allEvents.isNotEmpty() && !creatorKnown) {
            Log.w(
                TAG,
                "Imported ${allEvents.size} group_meta event(s) for $groupId but none was authored by the " +
                    "creator the group id is bound to; creator stays unknown"
            )
        }
    }

    /** Resolve the group key: local storage first, then embedded encrypted key. */
    private suspend fun resolveGroupKey(export: SplitFreeExport): String {
        groupRepo.getGroupKey(export.groupId)?.let { return it }

        if (export.encryptedGroupKey.isNotEmpty()) {
            val privKey = identity.getPrivateKeyBytes()
            try {
                val convKey = Nip44.getConversationKey(privKey, identity.getPublicKeyBytes())
                return Nip44.decrypt(export.encryptedGroupKey, convKey)
            } finally {
                privKey.fill(0)
            }
        }

        throw IllegalStateException("No key for group ${export.groupId}")
    }

    private fun hexToBytes(hex: String): ByteArray? {
        if (hex.length % 2 != 0) return null
        return try {
            ByteArray(hex.length / 2) { i ->
                ((Character.digit(hex[i * 2], 16) shl 4) + Character.digit(hex[i * 2 + 1], 16)).toByte()
            }
        } catch (_: Exception) {
            null
        }
    }

    companion object {
        private const val TAG = "ImportGroupUseCase"
    }
}
