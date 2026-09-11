package com.splitfree.domain.usecase.export

import com.splitfree.domain.crypto.GroupEncryption
import com.splitfree.domain.crypto.NostrEvent
import com.splitfree.domain.crypto.nip.Nip44
import com.splitfree.domain.model.expense.Expense
import com.splitfree.domain.model.expense.Settlement
import com.splitfree.domain.model.export.ExportedEvent
import com.splitfree.domain.model.export.SplitFreeExport
import com.splitfree.domain.model.group.Group
import com.splitfree.domain.model.group.GroupIdentity
import com.splitfree.domain.model.group.GroupMeta
import com.splitfree.domain.model.group.KeyRevocation
import com.splitfree.domain.model.group.KeyRotation
import com.splitfree.domain.repository.EventRepositoryContract
import com.splitfree.domain.repository.EventSnapshot
import com.splitfree.domain.repository.GroupRepositoryContract
import com.splitfree.domain.repository.IdentityContract
import com.splitfree.domain.util.TextSanitizer
import com.splitfree.domain.util.toHex
import com.splitfree.domain.validation.EventValidator
import com.splitfree.util.DebugLog as Log
import java.security.MessageDigest
import javax.inject.Inject
import kotlinx.serialization.json.Json

/**
 * Import group events from a `.splitfree` JSON export.
 *
 * Verifies the file's MAC against a key derived from the user's own private key (see
 * [SplitFreeExport]) before anything else, decrypts the embedded group key, creates the group if
 * needed, restores all epoch keys, then imports events in two passes inside one transaction:
 *
 * 1. `group_meta` / `key_rotation` / `key_revocation` events are stored and replayed so the
 *    member list, creator, name and relays are reconstructed first.
 * 2. Everything else is stored, filtered against the *historical* membership (everyone who was ever
 *    a member according to the structural events) and validated the same way
 *    [com.splitfree.sync.event.EventProcessor] validates live events.
 *
 * On a fresh device the group starts with `members = [me]`; filtering before replay would drop
 * every event authored by anyone else, which is exactly what a restore must not do.
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
     * @throws IllegalArgumentException if the MAC is missing/invalid or the version is unsupported
     * @throws IllegalStateException if the group key cannot be obtained
     */
    suspend operator fun invoke(jsonContent: String): Int = invoke(json.decodeFromString<SplitFreeExport>(jsonContent))

    /**
     * @param export a decoded `.splitfree` export
     * @return number of new events imported (duplicates are skipped)
     * @throws IllegalArgumentException if the MAC is missing/invalid or the version is unsupported
     * @throws IllegalStateException if the group key cannot be obtained
     */
    suspend operator fun invoke(export: SplitFreeExport): Int {
        require(export.version == SplitFreeExport.CURRENT_VERSION) {
            "Unsupported backup version ${export.version}; re-export from the current app"
        }
        // Authenticate before touching the key store or the database.
        verifyMac(export)

        val groupId = export.groupId
        val groupKey = resolveGroupKey(export)

        // Create the group if it doesn't exist locally. The name is cosmetic and replayPostImport
        // overwrites it from the creator's group_meta anyway, so a blank name is no reason to
        // skip creation (which would leave every imported event orphaned).
        if (groupRepo.getById(groupId) == null) {
            val group = Group(
                id = groupId,
                name = sanitizeGroupName(export.groupName),
                createdBy = "",
                createdAt = export.exportedAt,
                members = listOf(identity.getPublicKeyHex()),
                relays = sanitizeRelays(export.relays),
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

        val candidates = export.events.mapNotNull { toCandidate(it) }
        val (structural, content) = candidates.partition { it.eventType in STRUCTURAL_TYPES }

        return eventRepo.withTransaction {
            val knownEventIds = eventRepo.getEventIds(groupId).toMutableSet()
            var imported = 0

            // Pass 1: membership-defining events, then rebuild the group from them.
            for (candidate in structural) {
                if (candidate.eventId in knownEventIds) continue
                val decrypted = decryptForValidation(candidate, groupId, groupKey)
                if (decrypted != null && !eventValidator.isContentSafe(decrypted)) continue
                eventRepo.insert(candidate.toSnapshot(groupId))
                knownEventIds += candidate.eventId
                imported++
            }
            val stored = eventRepo.getEventsByGroup(groupId)
            replayPostImport(groupId, stored)

            // Pass 2: everything else, filtered against everyone who was ever a member.
            //
            // The live path checks membership once, at receipt time: an expense accepted while its
            // author was a member stays valid history after they are removed (and the balances
            // still owe/credit them). Filtering by the *current* member list would silently drop
            // that history on restore, so the filter is the union of every membership the
            // structural events describe plus the reconstructed current list.
            val group = groupRepo.getById(groupId)
            val members = group?.let { collectHistoricalMembers(groupId, groupKey, stored) + it.members }
            for (candidate in content) {
                if (candidate.eventId in knownEventIds) continue
                if (members != null && candidate.pubkey !in members) continue

                val eventType = candidate.eventType
                val expenseUuid = candidate.expenseUuid
                if (eventType == "expense_correction" || eventType == "expense_delete") {
                    val originalCreator = expenseUuid?.let { eventRepo.getExpenseByUuid(it, groupId)?.pubkey }
                    if (!eventValidator.isCorrectionAuthorValid(eventType, candidate.pubkey, originalCreator)) continue
                }

                // Import is a local, integrity-checked batch operation; relay runtime rate limits
                // would incorrectly drop valid historical events from the same author.

                val decrypted = decryptForValidation(candidate, groupId, groupKey)
                if (decrypted != null) {
                    if (!eventValidator.isContentSafe(decrypted)) continue
                    if (!isPayloadValid(candidate, decrypted, members ?: emptySet())) continue
                }

                eventRepo.insert(candidate.toSnapshot(groupId))
                knownEventIds += candidate.eventId
                imported++
            }

            imported
        }
    }

    /**
     * Check [SplitFreeExport.hmac] against the MAC recomputed under this identity's export key.
     *
     * @throws IllegalArgumentException if the MAC is missing, malformed or does not verify
     */
    private fun verifyMac(export: SplitFreeExport) {
        require(export.hmac.isNotEmpty()) { "Export file missing integrity check (HMAC)" }
        val providedHmac = hexToBytes(export.hmac) ?: throw IllegalArgumentException("Invalid HMAC hex")

        val privKey = identity.getPrivateKeyBytes()
        val expectedHmac = try {
            ExportMac.compute(export, privKey)
        } finally {
            privKey.fill(0)
        }
        if (!MessageDigest.isEqual(providedHmac, expectedHmac)) {
            // The key is derived from the private key, so a mismatch on an unmodified file means
            // the backup was made under a different identity (wrong seed phrase / nsec restored).
            Log.w(
                TAG,
                "Backup for group ${export.groupId} failed its integrity check; it was most likely made by a " +
                    "different identity than the one restored on this device"
            )
            throw IllegalArgumentException(
                "Backup integrity check failed: it was made by a different identity or has been modified"
            )
        }
    }

    /** Group name from the file, stripped of control/bidi characters and bounded in length. */
    private fun sanitizeGroupName(raw: String): String =
        TextSanitizer.stripControlChars(raw).take(MAX_GROUP_NAME_LENGTH).trim().ifBlank { DEFAULT_GROUP_NAME }

    /** Relay list from the file, restricted to plausible `wss://` URLs and bounded in count. */
    private fun sanitizeRelays(raw: List<String>): List<String> =
        raw.filter { it.startsWith("wss://") && it.length <= MAX_RELAY_URL_LENGTH }.distinct().take(MAX_RELAYS)

    /**
     * A row from the export that passed authenticity and timestamp checks and is ready to store.
     *
     * @property sig the value to persist: the event's own signature, or the exporter's `seal:`
     *   marker for a rumor (see [EventSnapshot.SEAL_SIG_PREFIX]) so the row stays recognisable as
     *   not third-party verifiable
     */
    private class Candidate(
        val parsed: NostrEvent,
        val originalJson: String,
        val eventType: String,
        val expenseUuid: String?,
        val sig: String,
        val keyEpoch: Int
    ) {
        val eventId: String get() = parsed.id
        val pubkey: String get() = parsed.pubkey

        fun toSnapshot(groupId: String) = EventSnapshot(
            eventId = eventId, groupId = groupId, pubkey = pubkey,
            createdAt = parsed.createdAt, kind = parsed.kind,
            contentEncrypted = parsed.content, eventType = eventType,
            expenseUuid = expenseUuid, sig = sig,
            receivedAt = System.currentTimeMillis() / 1000,
            originalEventJson = originalJson,
            keyEpoch = keyEpoch
        )
    }

    /**
     * Parse and authenticate one exported row, or return null if it must be skipped.
     *
     * Two shapes are accepted:
     *  - a normally signed event whose signature verifies;
     *  - an unsigned NIP-59 rumor (`sig == ""`) whose exported row carries a
     *    [EventSnapshot.SEAL_SIG_PREFIX] marker. The exporting device verified the seal at receipt
     *    time and the export is HMAC-authenticated, so the marker is trusted on restore. The rumor
     *    id is still re-derived so a corrupt row cannot collide with a real event id.
     */
    private fun toCandidate(event: ExportedEvent): Candidate? {
        val originalJson = event.originalEventJson ?: return null
        val parsed = NostrEvent.fromJson(originalJson) ?: return null

        val sig =
            when {
                parsed.verify() -> parsed.sig
                parsed.sig.isEmpty() &&
                    event.sig.startsWith(EventSnapshot.SEAL_SIG_PREFIX) &&
                    parsed.pubkey.isNotEmpty() &&
                    parsed.id == parsed.computeId().toHex() -> event.sig
                else -> return null
            }
        if (!eventValidator.isTimestampValidLenient(parsed.createdAt)) return null

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
        return Candidate(parsed, originalJson, eventType, expenseUuid, sig, event.keyEpoch)
    }

    /** Decrypt with the key of the epoch the event was recorded under, falling back to the current key. */
    private suspend fun decryptForValidation(candidate: Candidate, groupId: String, groupKey: String): String? =
        decryptWithEpochKey(candidate.parsed.content, candidate.keyEpoch, groupId, groupKey)

    private suspend fun decryptWithEpochKey(
        content: String,
        keyEpoch: Int,
        groupId: String,
        groupKey: String
    ): String? {
        val key = groupRepo.getGroupKeyForEpoch(groupId, keyEpoch) ?: groupKey
        return try {
            encryption.decrypt(content, key)
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Every pubkey that the stored structural events say was a member at some point:
     * `group_meta.members`, `key_rotation.members` plus the member it removed, and both sides of a
     * `key_revocation`. Payloads that do not decrypt or parse are skipped. Used by pass 2 so that
     * events from since-removed (or since-rotated) members survive a restore.
     */
    private suspend fun collectHistoricalMembers(
        groupId: String,
        groupKey: String,
        stored: List<EventSnapshot>
    ): Set<String> {
        val members = mutableSetOf<String>()
        for (event in stored) {
            if (event.eventType !in STRUCTURAL_TYPES) continue
            val decrypted = decryptWithEpochKey(event.contentEncrypted, event.keyEpoch, groupId, groupKey) ?: continue
            try {
                when (event.eventType) {
                    "group_meta" -> members += json.decodeFromString<GroupMeta>(decrypted).members
                    "key_rotation" -> {
                        val rotation = json.decodeFromString<KeyRotation>(decrypted)
                        members += rotation.members
                        members += rotation.removedMember
                    }
                    "key_revocation" -> {
                        val revocation = json.decodeFromString<KeyRevocation>(decrypted)
                        members += revocation.oldPubkey
                        members += revocation.newPubkey
                    }
                }
            } catch (_: Exception) { }
        }
        members.remove("")
        return members
    }

    /**
     * Mirror of `EventProcessor.validatePayload`: a decryptable expense/settlement must parse, pass
     * [EventValidator] and agree with its `x` tag. Other event types carry no validated payload.
     */
    private fun isPayloadValid(candidate: Candidate, decrypted: String, members: Set<String>): Boolean {
        when (candidate.eventType) {
            "expense", "expense_correction" -> {
                val expense =
                    try {
                        json.decodeFromString<Expense>(decrypted)
                    } catch (_: Exception) {
                        return false
                    }
                if (!eventValidator.isExpenseValid(expense, members)) return false
                if (expense.id != candidate.expenseUuid) return false
            }

            "settlement" -> {
                val settlement =
                    try {
                        json.decodeFromString<Settlement>(decrypted)
                    } catch (_: Exception) {
                        return false
                    }
                if (!eventValidator.isSettlementValid(settlement, candidate.pubkey, members)) return false
                if (candidate.expenseUuid != null && settlement.id != candidate.expenseUuid) return false
            }
        }
        return true
    }

    /**
     * After pass 1 of the import, replay group_meta events in chronological order so the group
     * entity reflects the full state (members, name, relays, epoch keys) before pass 2 filters
     * content events against it.
     *
     * An imported group starts with an empty `createdBy`. It is filled in only from a
     * `group_meta` whose author is the creator the group id was derived from
     * ([GroupIdentity.matches]); a `created_by` claim by anyone else is ignored, and if no
     * meta is bound to the id the creator stays unknown.
     *
     * Key rotation events are NOT replayed here; all epoch keys are restored
     * directly from [SplitFreeExport.encryptedEpochKeys] before event import.
     *
     * @param stored every event of the group as read after pass 1
     */
    private suspend fun replayPostImport(groupId: String, stored: List<EventSnapshot>) {
        val allEvents = stored
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
        if (hex.length % 2 != 0 || hex.any { Character.digit(it, 16) < 0 }) return null
        return ByteArray(hex.length / 2) { i ->
            ((Character.digit(hex[i * 2], 16) shl 4) + Character.digit(hex[i * 2 + 1], 16)).toByte()
        }
    }

    companion object {
        private const val TAG = "ImportGroupUseCase"
        private const val DEFAULT_GROUP_NAME = "Imported group"
        private const val MAX_GROUP_NAME_LENGTH = 100
        private const val MAX_RELAYS = 10
        private const val MAX_RELAY_URL_LENGTH = 256

        /** Events that define membership/keys; stored and replayed before anything else is filtered. */
        private val STRUCTURAL_TYPES = setOf("group_meta", "key_rotation", "key_revocation")
    }
}
