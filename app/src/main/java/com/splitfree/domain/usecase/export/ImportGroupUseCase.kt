package com.splitfree.domain.usecase.export

import com.splitfree.domain.crypto.GroupEncryption
import com.splitfree.domain.crypto.NostrEvent
import com.splitfree.domain.crypto.nip.Nip44
import com.splitfree.domain.invite.InviteLinkCodec
import com.splitfree.domain.model.expense.Expense
import com.splitfree.domain.model.expense.Settlement
import com.splitfree.domain.model.export.ExportedEvent
import com.splitfree.domain.model.export.SplitFreeExport
import com.splitfree.domain.model.group.CreatorTransition
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
import com.splitfree.domain.util.hexToBytes
import com.splitfree.domain.util.toHex
import com.splitfree.domain.validation.EventValidator
import com.splitfree.util.DebugLog as Log
import java.security.MessageDigest
import java.util.Base64
import javax.inject.Inject
import kotlinx.serialization.json.Json

/**
 * Import group events from a `.splitfree` JSON export.
 *
 * After checking the format version, authenticate the file and validate its root evidence and keys.
 * Import then runs inside a Room transaction:
 * 1. Reconcile immutable epoch keys and creator evidence with local state.
 * 2. Store structural events and replay metadata/revocations before supported rotation boundaries.
 * 3. Store content using historical membership, with originals before same-author corrections/deletes.
 *
 * Historical membership preserves events from members since removed; the restored current roster
 * alone is too restrictive. Signed envelopes are verified, while sealed rumors rely on the file MAC.
 * Decryptable payloads are validated; unreadable ciphertext is retained for later key recovery.
 * Room changes roll back together, but secure-store key writes can survive a failed import.
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
     * @throws IllegalArgumentException if the MAC is missing/invalid, the version is unsupported, or
     *   the backup's key material is malformed or conflicts with keys already stored for the group,
     *   or a new group's relay list cannot fit in an invite link
     * @throws IllegalStateException if the key for the backup's epoch cannot be obtained
     */
    suspend operator fun invoke(jsonContent: String): Int = invoke(json.decodeFromString<SplitFreeExport>(jsonContent))

    /**
     * @param export a decoded `.splitfree` export
     * @return number of new events imported (duplicates are skipped)
     * @throws IllegalArgumentException if the MAC is missing/invalid, the version is unsupported, or
     *   the backup's key material is malformed or conflicts with keys already stored for the group,
     *   or a new group's relay list cannot fit in an invite link
     * @throws IllegalStateException if the key for the backup's epoch cannot be obtained
     */
    suspend operator fun invoke(export: SplitFreeExport): Int {
        require(export.version == SplitFreeExport.CURRENT_VERSION) {
            "Unsupported backup version ${export.version}; re-export from the current app"
        }
        // Authenticate before touching the key store or the database.
        verifyMac(export)
        require(
            if (export.rootCreator.isEmpty()) {
                export.rootCreatedAt == 0L && export.creatorTransitions.isEmpty()
            } else {
                CreatorTransition.validate(
                    export.groupId,
                    export.rootCreator,
                    export.rootCreatedAt,
                    export.creatorTransitions
                )
            }
        ) { "Backup has invalid original creator or transition evidence" }
        require(export.creatorTransitions.all { it.hasAdmissibleTimestamp() }) {
            "Backup contains creator evidence too far in the future"
        }
        val backupKeys = decryptBackupKeys(export)

        val groupId = export.groupId
        val candidates = export.events.mapNotNull { toCandidate(it) }
        val (structural, content) = candidates.partition { it.eventType in STRUCTURAL_TYPES }

        return eventRepo.withTransaction {
            val groupKey = reconcileKeyState(export, backupKeys)
            val knownEventIds = eventRepo.getEventIds(groupId).toMutableSet()
            val newEventIds = mutableSetOf<String>()
            var imported = 0

            // Pass 1: membership-defining events, then rebuild the group from them.
            for (candidate in structural) {
                if (candidate.eventId in knownEventIds) continue
                val decrypted = decryptForValidation(candidate, groupId, groupKey)
                if (decrypted != null && !eventValidator.isContentSafe(decrypted)) continue
                eventRepo.insert(candidate.toSnapshot(groupId))
                knownEventIds += candidate.eventId
                newEventIds += candidate.eventId
                imported++
            }
            val stored = eventRepo.getEventsByGroup(groupId)
            replayPostImport(groupId, stored, newEventIds)

            // Preserve removed members' history, not just the reconstructed current roster.
            // Originals precede corrections/deletes so same-author dependency checks can find them.
            val group = groupRepo.getById(groupId)
            val members = group?.let { collectHistoricalMembers(groupId, groupKey, stored) + it.members }
            for (candidate in content.sortedWith(DEPENDENCY_ORDER)) {
                if (candidate.eventId in knownEventIds) continue
                if (members != null && candidate.pubkey !in members) continue

                val eventType = candidate.eventType
                val expenseUuid = candidate.expenseUuid
                if (eventType in DEPENDENT_TYPES) {
                    val original = expenseUuid?.let { eventRepo.getExpenseByAuthor(it, groupId, candidate.pubkey) }
                    if (!eventValidator.isCorrectionAuthorValid(eventType, candidate.pubkey, original?.pubkey)) {
                        Log.w(TAG, "Skipping $eventType ${candidate.eventId.take(8)}: no original by its author")
                        continue
                    }
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
     * Decrypt every key the backup carries to this identity and check it, before anything is written.
     *
     * Epoch labels must be canonical integers in `0..keyEpoch`, every key must be a 32-byte group key,
     * and a current key present both as [SplitFreeExport.encryptedGroupKey] and under its own epoch must
     * agree. The file is authenticated, so a key that fails these checks means a corrupt or incompatible
     * export and the import is refused rather than partially applied.
     *
     * @return decrypted key per epoch; empty when the backup carries no keys
     * @throws IllegalArgumentException if any key or epoch label is malformed
     */
    private fun decryptBackupKeys(export: SplitFreeExport): Map<Int, String> {
        require(export.keyEpoch >= 0) { "Backup key epoch must not be negative" }
        if (export.encryptedEpochKeys.isEmpty() && export.encryptedGroupKey.isEmpty()) return emptyMap()

        val keys = LinkedHashMap<Int, String>()
        val privKey = identity.getPrivateKeyBytes()
        try {
            val convKey = Nip44.getConversationKey(privKey, identity.getPublicKeyBytes())
            try {
                for ((label, encryptedKey) in export.encryptedEpochKeys) {
                    val epoch = label.toIntOrNull()
                    require(epoch != null && epoch.toString() == label && epoch in 0..export.keyEpoch) {
                        "Backup names an epoch key outside 0..${export.keyEpoch}: $label"
                    }
                    keys[epoch] = decryptGroupKey(encryptedKey, convKey, "epoch $epoch")
                }
                if (export.encryptedGroupKey.isNotEmpty()) {
                    val current = decryptGroupKey(export.encryptedGroupKey, convKey, "epoch ${export.keyEpoch}")
                    val listed = keys[export.keyEpoch]
                    require(listed == null || listed == current) {
                        "Backup's current key disagrees with its epoch ${export.keyEpoch} key"
                    }
                    keys[export.keyEpoch] = current
                }
            } finally {
                convKey.fill(0)
            }
        } finally {
            privKey.fill(0)
        }
        return keys
    }

    /** Decrypts a self-encrypted key and requires its base64 value to decode to exactly 32 bytes. */
    private fun decryptGroupKey(encryptedKey: String, convKey: ByteArray, label: String): String {
        val key = try {
            Nip44.decrypt(encryptedKey, convKey)
        } catch (e: Exception) {
            throw IllegalArgumentException("Backup key for $label cannot be decrypted", e)
        }
        val bytes = try {
            Base64.getDecoder().decode(key)
        } catch (_: IllegalArgumentException) {
            null
        }
        try {
            require(bytes != null && bytes.size == GROUP_KEY_BYTES) { "Backup key for $label is not a group key" }
        } finally {
            bytes?.fill(0)
        }
        return key
    }

    /**
     * Reconcile keys before writing, then merge creator evidence and advance the key checkpoint.
     * Runs inside the Room transaction; secure-store keys are not part of that rollback.
     *
     * - Every epoch held by both the backup and this device must carry the same key; a mismatch fails
     *   the import with nothing written. Epoch key material is immutable, so a stored key is never
     *   replaced and only missing epochs are installed.
     * - The key for the backup's epoch must be available, from the backup or from local storage;
     *   older key material is never reused for a newer epoch.
     * - The group's current epoch only moves forward. A backup newer than the group installs its epoch
     *   as a key-availability checkpoint, never as a fabricated authenticated rotation. The current
     *   roster is retained until authenticated current-epoch metadata arrives. An older
     *   or equal backup leaves the epoch alone.
     * - A group unknown to this device is created at the backup's epoch.
     *
     * @return the key of the backup's epoch, the fallback for rows whose epoch key is not stored
     * @throws IllegalArgumentException if the backup conflicts with a stored key
     * @throws IllegalStateException if no key for the backup's epoch is available
     */
    private suspend fun reconcileKeyState(export: SplitFreeExport, backupKeys: Map<Int, String>): String {
        val groupId = export.groupId
        val local = groupRepo.getById(groupId)
        for ((epoch, key) in backupKeys) {
            val stored = groupRepo.getGroupKeyForEpoch(groupId, epoch) ?: continue
            require(stored == key) { "Backup carries a different key for epoch $epoch of group $groupId" }
        }
        val groupKey = backupKeys[export.keyEpoch]
            ?: groupRepo.getGroupKeyForEpoch(groupId, export.keyEpoch)
            ?: throw IllegalStateException("No key for group $groupId at epoch ${export.keyEpoch}")

        if (local != null) mergeBackupCreator(export)

        // A blank cosmetic name must not prevent creating the parent row for imported events.
        // Authenticated creator metadata can replace this fallback during replay.
        if (local == null) {
            val group = Group(
                id = groupId,
                name = sanitizeGroupName(export.groupName),
                createdBy = export.rootCreator,
                createdAt = if (export.rootCreator.isNotEmpty()) export.rootCreatedAt else export.exportedAt,
                members = listOf(identity.getPublicKeyHex()),
                relays = sanitizeRelays(export.relays),
                keyEpoch = export.keyEpoch
            )
            groupRepo.save(group, groupKey)
            mergeBackupCreator(export)
        }
        // Restore every supplied epoch key, including keys needed for historical ciphertext.
        for ((epoch, key) in backupKeys) groupRepo.saveGroupKeyForEpoch(groupId, epoch, key)

        if (local != null && export.keyEpoch > local.keyEpoch) {
            val advanced = if (groupRepo.hasCanonicalProjection(groupId)) {
                groupRepo.updateKeyEpoch(groupId, export.keyEpoch)
                groupRepo.getById(groupId)?.keyEpoch == export.keyEpoch
            } else {
                groupRepo.applyKeyRotation(groupId, export.keyEpoch, local.members, local.memberNames)
            }
            if (advanced) {
                Log.i(TAG, "Backup advanced ${groupId.take(8)} from epoch ${local.keyEpoch} to ${export.keyEpoch}")
            } else {
                Log.w(TAG, "Group ${groupId.take(8)} did not advance to epoch ${export.keyEpoch}; epoch kept")
            }
        }
        return groupKey
    }

    private suspend fun mergeBackupCreator(export: SplitFreeExport) {
        if (export.rootCreator.isEmpty()) return
        val merged = groupRepo.mergeCreatorBootstrap(
            export.groupId,
            export.rootCreator,
            export.rootCreatedAt,
            export.creatorTransitions
        )
        require(merged || !groupRepo.hasCanonicalProjection(export.groupId)) {
            "Backup creator evidence conflicts with local control history"
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

    private fun sanitizeRelays(raw: List<String>): List<String> {
        val relays = raw.filter(InviteLinkCodec::relayFits).distinct()
        require(InviteLinkCodec.fitsInviteLink(relays)) {
            "Backup relays do not fit in an invite link; reduce the group's relays on the source device and export again"
        }
        return relays
    }

    /**
     * A row that passed envelope authenticity and timestamp checks; payload admission is still pending.
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

    /** Use the recorded epoch key, falling back to the backup epoch key when that key is absent. */
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
                if (settlement.id != candidate.expenseUuid) return false
            }
        }
        return true
    }

    /**
     * Canonical repositories retain facts instead of clearing visible state: metadata and revocations
     * establish authority before rotation replay. Existing checkpoints survive a partial backup.
     * Noncanonical contracts use the reset/replay fallback below.
     */
    private suspend fun replayPostImport(groupId: String, stored: List<EventSnapshot>, newEventIds: Set<String>) {
        val controlEvents = stored
            .filter { it.eventType in STRUCTURAL_TYPES }
            .sortedWith(EventSnapshot.CANONICAL_ORDER)

        val canonical = groupRepo.hasCanonicalProjection(groupId)
        val beforeReset = if (canonical) null else reprojectIfOutOfOrder(groupId, controlEvents, newEventIds)

        var metaCount = 0
        var creatorKnown = false
        for (event in controlEvents) {
            if (event.eventType == "key_rotation") continue
            val key = groupRepo.getGroupKeyForEpoch(groupId, event.keyEpoch)
                ?: groupRepo.getGroupKey(groupId) ?: continue
            val decrypted = try {
                encryption.decrypt(event.contentEncrypted, key)
            } catch (_: Exception) {
                null
            } ?: continue

            if (event.eventType == "key_revocation") {
                replayRevocation(groupId, event, decrypted)
                continue
            }
            metaCount++
            if (canonical) {
                val meta = try {
                    json.decodeFromString<GroupMeta>(decrypted)
                } catch (_: Exception) {
                    continue
                }
                groupRepo.applyAuthenticatedMeta(
                    groupId,
                    meta,
                    event.pubkey,
                    event.createdAt,
                    event.eventId,
                    event.keyEpoch
                )
                creatorKnown = groupRepo.getById(groupId)?.createdBy?.isNotEmpty() == true
            } else if (replayMeta(groupId, event, decrypted)) {
                creatorKnown = true
            }
        }

        if (canonical) {
            // Metadata/revocations establish historical creator authority before rotation replay.
            // Never take ControlOperationLock inside the Room import transaction.
            for (event in controlEvents.filter { it.eventType == "key_rotation" }) replayRotation(groupId, event)
        }
        if (metaCount > 0 && !creatorKnown) {
            Log.w(
                TAG,
                "Imported $metaCount group_meta event(s) for $groupId but none was authored by the " +
                    "creator the group id is bound to; creator stays unknown"
            )
        }
        if (beforeReset != null) restoreIfEmptied(groupId, beforeReset)
    }

    private suspend fun replayRotation(groupId: String, event: EventSnapshot) {
        val group = groupRepo.getById(groupId) ?: return
        if (event.pubkey != group.createdBy &&
            !groupRepo.isHistoricalCreator(groupId, event.pubkey, event.createdAt, event.eventId)
        ) {
            return
        }
        var plaintext: String? = null
        for (epoch in group.keyEpoch downTo 0) {
            val key = groupRepo.getGroupKeyForEpoch(groupId, epoch) ?: continue
            plaintext = try {
                encryption.decrypt(event.contentEncrypted, key)
            } catch (_: Exception) {
                null
            }
            if (plaintext != null) break
        }
        if (plaintext == null) {
            val envelope = event.originalEventJson?.let(NostrEvent::fromJson) ?: return
            val recipient = envelope.tags.firstOrNull { it.size >= 2 && it[0] == "p" }?.get(1) ?: return
            val me = identity.getPublicKeyHex()
            val peer = when (me) {
                event.pubkey -> recipient
                recipient -> event.pubkey
                else -> return
            }
            val privateKey = identity.getPrivateKeyBytes()
            try {
                val key = Nip44.getConversationKey(privateKey, peer.hexToBytes())
                try {
                    plaintext = Nip44.decrypt(event.contentEncrypted, key)
                } catch (
                    _: Exception
                ) {
                    return
                } finally {
                    key.fill(0)
                }
            } finally {
                privateKey.fill(0)
            }
        }
        val rotation = try {
            json.decodeFromString<KeyRotation>(checkNotNull(plaintext))
        } catch (
            _: Exception
        ) {
            return
        }
        if (rotation.epoch <= 0 ||
            rotation.epoch > group.keyEpoch ||
            rotation.members.isEmpty() ||
            rotation.members.distinct().size != rotation.members.size ||
            rotation.removedMember in rotation.members
        ) {
            return
        }
        // Restore only boundaries supported by already validated backup key material. Do not invent
        // missing epoch keys or transfer encrypted key recipients along identity-succession links.
        val epochKey = groupRepo.getGroupKeyForEpoch(groupId, rotation.epoch) ?: return
        rotation.encryptedKeys[identity.getPublicKeyHex()]?.let { encrypted ->
            val privateKey = identity.getPrivateKeyBytes()
            try {
                val key = Nip44.getConversationKey(privateKey, event.pubkey.hexToBytes())
                try {
                    val decoded = try {
                        Nip44.decrypt(encrypted, key)
                    } catch (_: Exception) {
                        return
                    }
                    require(decoded == epochKey) { "Rotation disagrees with backup epoch key" }
                } finally {
                    key.fill(0)
                }
            } finally {
                privateKey.fill(0)
            }
        }
        groupRepo.applyAuthenticatedRotation(groupId, rotation, event.pubkey, event.createdAt, event.eventId)
    }

    /**
     * Resets the roster projection when one of [newEventIds] is a control record canonically older than the
     * group's watermark (see [replayPostImport]).
     *
     * @param controlEvents every stored structural event, in canonical order
     * @return the group as it was before the reset, or null if nothing was reset
     */
    private suspend fun reprojectIfOutOfOrder(
        groupId: String,
        controlEvents: List<EventSnapshot>,
        newEventIds: Set<String>
    ): Group? {
        val earliestNew = controlEvents.firstOrNull { it.eventId in newEventIds } ?: return null
        val before = groupRepo.getById(groupId) ?: return null
        val reset = groupRepo.resetRosterProjection(groupId, earliestNew.createdAt, earliestNew.eventId)
        if (!reset) return null
        Log.i(TAG, "Re-projecting the roster of ${groupId.take(8)} from ${controlEvents.size} control record(s)")
        return before
    }

    /**
     * Compatibility fallback after reset/replay: restore [before]'s roster if replay leaves it empty,
     * and restore its creator only if still unknown. A zero membership clock preserves the watermark.
     */
    private suspend fun restoreIfEmptied(groupId: String, before: Group) {
        val after = groupRepo.getById(groupId) ?: return
        if (after.members.isNotEmpty()) {
            if (after.createdBy.isEmpty() && before.createdBy.isNotEmpty()) {
                groupRepo.updateCreator(groupId, before.createdBy, before.createdAt)
            }
            return
        }
        Log.w(TAG, "Replaying ${groupId.take(8)} seated nobody; keeping the roster it had before the import")
        groupRepo.overrideMembership(
            groupId,
            before.members,
            before.memberNames,
            createdBy = if (after.createdBy.isEmpty()) before.createdBy else "",
            eventTimestamp = 0,
            eventId = ""
        )
    }

    /** Records one authenticated `key_revocation` row's effect; a malformed or unauthorised one is skipped. */
    private suspend fun replayRevocation(groupId: String, event: EventSnapshot, decrypted: String) {
        val revocation = try {
            json.decodeFromString<KeyRevocation>(decrypted)
        } catch (_: Exception) {
            Log.w(TAG, "Skipping key_revocation ${event.eventId.take(8)}: unreadable payload")
            return
        }
        if (!revocation.isAuthorizedBy(event.pubkey)) {
            Log.w(TAG, "Skipping key_revocation ${event.eventId.take(8)}: not a valid retirement by its author")
            return
        }
        val applied = if (groupRepo.hasCanonicalProjection(groupId)) {
            groupRepo.applyAuthenticatedRevocation(
                groupId,
                revocation.oldPubkey,
                revocation.newPubkey,
                event.createdAt,
                event.eventId,
                event.keyEpoch,
                revocation.provesSuccessor(groupId)
            )
        } else {
            groupRepo.applyIdentityRevocation(
                groupId,
                revocation.oldPubkey,
                revocation.newPubkey,
                event.createdAt,
                event.eventId,
                allowAbsent = true,
                successorProven = revocation.provesSuccessor(groupId)
            )
        }
        if (!applied) Log.w(TAG, "key_revocation ${event.eventId.take(8)} could not be projected onto $groupId")
    }

    /**
     * Replays one `group_meta` row onto the group entity.
     *
     * @return true if, after this meta, the group's creator is known
     */
    private suspend fun replayMeta(groupId: String, event: EventSnapshot, decrypted: String): Boolean {
        var creatorKnown = false
        try {
            val meta = json.decodeFromString<GroupMeta>(decrypted)
            if (meta.members.isEmpty()) return false

            var currentGroup = groupRepo.getById(groupId)

            // Bootstrap createdBy only from the author the group id is cryptographically bound to.
            val bootstrapsCreator = currentGroup != null &&
                currentGroup.createdBy.isEmpty() &&
                meta.createdBy == event.pubkey &&
                GroupIdentity.matches(groupId, event.pubkey, meta.createdAt)
            val isCreator = currentGroup == null ||
                bootstrapsCreator ||
                (currentGroup.createdBy.isNotEmpty() && event.pubkey == currentGroup.createdBy)
            if (isCreator && !InviteLinkCodec.fitsInviteLink(meta.relays.filter(InviteLinkCodec::relayFits))) {
                return false
            }
            if (bootstrapsCreator) {
                // The recorded creator may be this author's replacement if their revocation was replayed
                // first, or stay empty if they were revoked without one; read back what was recorded.
                groupRepo.updateCreator(groupId, event.pubkey, meta.createdAt)
                currentGroup = groupRepo.getById(groupId)
            }
            if (currentGroup == null || currentGroup.createdBy.isNotEmpty()) creatorKnown = true

            if (!isCreator && currentGroup != null && !InviteLinkCodec.fitsInviteLink(currentGroup.relays)) {
                // A member's own change must not re-admit or rewrite a legacy relay list.
                groupRepo.applyMemberSelfUpdate(
                    groupId,
                    event.pubkey,
                    event.createdAt,
                    event.eventId,
                    join = event.pubkey !in currentGroup.members,
                    displayName = meta.memberNames[event.pubkey]?.trim().orEmpty().take(50)
                )
                return creatorKnown
            }

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
        return creatorKnown
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

        /** Length of a decoded symmetric group key, as [GroupEncryption] requires. */
        private const val GROUP_KEY_BYTES = 32

        /** Events that define membership/keys; stored and replayed before anything else is filtered. */
        private val STRUCTURAL_TYPES = setOf("group_meta", "key_rotation", "key_revocation")

        /** Events that only apply against an original `expense` stored by the same author. */
        private val DEPENDENT_TYPES = setOf("expense_correction", "expense_delete")

        /** Pass 2 order: independent records first, then dependent ones, canonical order within each. */
        private val DEPENDENCY_ORDER: Comparator<Candidate> =
            compareBy<Candidate> { it.eventType in DEPENDENT_TYPES }
                .thenBy { it.parsed.createdAt }
                .thenBy { it.eventId }
    }
}
