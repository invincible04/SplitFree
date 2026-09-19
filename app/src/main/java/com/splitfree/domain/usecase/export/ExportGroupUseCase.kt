package com.splitfree.domain.usecase.export

import com.splitfree.domain.crypto.ExportKeyDerivation
import com.splitfree.domain.crypto.NostrEvent
import com.splitfree.domain.crypto.nip.Nip44
import com.splitfree.domain.model.export.ExportedEvent
import com.splitfree.domain.model.export.SplitFreeExport
import com.splitfree.domain.model.group.CreatorTransition
import com.splitfree.domain.model.group.GroupIdentity
import com.splitfree.domain.repository.EventRepositoryContract
import com.splitfree.domain.repository.GroupRepositoryContract
import com.splitfree.domain.repository.IdentityContract
import com.splitfree.domain.util.hexToBytes
import com.splitfree.domain.util.toHex
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import javax.inject.Inject
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.json.Json

/**
 * Export group events as an HMAC-authenticated JSON backup for device transfer.
 *
 * Available epoch keys are NIP-44 encrypted to the exporter's identity; the file MAC is derived
 * from the same private key (see [SplitFreeExport]). Restore therefore requires that identity.
 * Events or epoch keys absent from this device cannot be recovered from this backup alone.
 *
 * @see ImportGroupUseCase for the corresponding import path
 */
class ExportGroupUseCase
@Inject
constructor(
    private val eventRepo: EventRepositoryContract,
    private val groupRepo: GroupRepositoryContract,
    private val identity: IdentityContract
) {
    // encodeDefaults so the written file spells out every authenticated field.
    private val json = Json {
        prettyPrint = true
        encodeDefaults = true
    }

    /**
     * @param groupId target group UUID
     * @return pretty-printed JSON string of the export
     */
    suspend operator fun invoke(groupId: String): String = openSession().use { session ->
        invoke(groupId, session)
    }

    /** One borrowed identity for the entire output document, not a fresh identity per group. */
    fun openSession(): ExportSession = ExportSession(identity)

    suspend operator fun invoke(groupId: String, session: ExportSession): String {
        session.requireCurrentIdentity()
        // Both repositories share the application Room database. Keys live outside Room, but epoch
        // keys are immutable: read them by this captured epoch after releasing the transaction.
        val (group, events) = eventRepo.withTransaction {
            val group = checkNotNull(groupRepo.getById(groupId)) { "Group no longer exists; export again" }
            group to eventRepo.getExportableEvents(groupId)
        }
        require(group.keyEpoch >= 0 && events.all { it.keyEpoch in 0..group.keyEpoch }) {
            "Group history changed before its key epoch was applied; sync and export again"
        }
        val rootedGroup = group.takeIf { GroupIdentity.matches(groupId, it.originalCreator, it.createdAt) }
        require(group.creatorTransitions.isEmpty() || rootedGroup != null) {
            "Creator transitions require a verified original creator"
        }
        if (rootedGroup != null) {
            require(
                CreatorTransition.validate(
                    groupId,
                    rootedGroup.originalCreator,
                    rootedGroup.createdAt,
                    rootedGroup.creatorTransitions
                )
            ) { "Invalid creator transition evidence" }
        }
        val epochKeys = linkedMapOf<Int, String>()
        for (epoch in 0..group.keyEpoch) {
            currentCoroutineContext().ensureActive()
            val key = groupRepo.getGroupKeyForEpoch(groupId, epoch) ?: continue
            require(runCatching { Base64.getDecoder().decode(key).size == 32 }.getOrDefault(false)) {
                "Invalid key for epoch $epoch; backup not written"
            }
            epochKeys[epoch] = key
        }
        val requiredEpochs = events.map { it.keyEpoch }.toSet() + group.keyEpoch
        check(epochKeys.keys.containsAll(requiredEpochs)) {
            "Keys required by the captured history are unavailable; sync and export again"
        }
        session.requireCurrentIdentity()
        val exportedEvents = events.map { e ->
            ExportedEvent(
                eventId = e.eventId, pubkey = e.pubkey, createdAt = e.createdAt, kind = e.kind,
                contentEncrypted = e.contentEncrypted, eventType = e.eventType,
                expenseUuid = e.expenseUuid, sig = e.sig, originalEventJson = e.originalEventJson,
                keyEpoch = e.keyEpoch
            )
        }
        val convKey = Nip44.getConversationKey(session.privateKey, session.publicKey.hexToBytes())
        val encryptedEpochKeys = try {
            epochKeys.mapKeys { it.key.toString() }.mapValues { (_, key) -> Nip44.encrypt(key, convKey) }
        } finally {
            convKey.fill(0)
        }
        val unsigned = SplitFreeExport(
            version = SplitFreeExport.CURRENT_VERSION,
            groupId = groupId,
            exportedAt = System.currentTimeMillis() / 1000,
            events = exportedEvents,
            encryptedGroupKey = encryptedEpochKeys.getValue(group.keyEpoch.toString()),
            groupName = group.name,
            relays = group.relays,
            keyEpoch = group.keyEpoch,
            encryptedEpochKeys = encryptedEpochKeys,
            rootCreator = rootedGroup?.originalCreator.orEmpty(),
            rootCreatedAt = rootedGroup?.createdAt ?: 0,
            creatorTransitions = rootedGroup?.creatorTransitions.orEmpty()
        )
        val export = unsigned.copy(hmac = ExportMac.compute(unsigned, session.privateKey).toHex())
        currentCoroutineContext().ensureActive()
        session.requireCurrentIdentity()
        return json.encodeToString(export)
    }
}

/** Owns a single private-key copy; callers must close it on success, failure and cancellation. */
class ExportSession internal constructor(private val identity: IdentityContract) : AutoCloseable {
    internal val privateKey: ByteArray = identity.getPrivateKeyBytes()
    internal val publicKey: String
    private var closed = false

    init {
        try {
            require(privateKey.size == 32) { "A ready identity is required to export" }
            publicKey = NostrEvent.pubkeyFromPrivkey(privateKey)
            requireCurrentIdentity()
        } catch (failure: Throwable) {
            privateKey.fill(0)
            throw failure
        }
    }

    fun requireCurrentIdentity() {
        check(!closed) { "Export identity session is closed" }
        check(identity.getPublicKeyHex() == publicKey) { "Identity changed during export; export again" }
    }

    override fun close() {
        privateKey.fill(0)
        closed = true
    }
}

/**
 * HMAC-SHA256 over [SplitFreeExport.canonicalBody] under the identity-derived export key.
 */
internal object ExportMac {
    /**
     * @param export the export to authenticate; its `hmac` field is ignored
     * @param privKey the user's 32-byte private key; not modified, caller zeroes it
     * @return raw 32-byte MAC
     */
    fun compute(export: SplitFreeExport, privKey: ByteArray): ByteArray {
        val macKey = ExportKeyDerivation.deriveExportMacKey(privKey)
        try {
            val mac = Mac.getInstance("HmacSHA256")
            mac.init(SecretKeySpec(macKey, "HmacSHA256"))
            return mac.doFinal(export.canonicalBody().toByteArray(Charsets.UTF_8))
        } finally {
            macKey.fill(0)
        }
    }
}
