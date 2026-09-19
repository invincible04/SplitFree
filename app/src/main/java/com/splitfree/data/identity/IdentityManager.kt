package com.splitfree.data.identity

import android.content.Context
import androidx.core.content.edit
import com.splitfree.domain.crypto.NostrEvent
import com.splitfree.domain.crypto.nip.Bip39
import com.splitfree.domain.repository.IdentityContract
import com.splitfree.domain.repository.IdentityState
import com.splitfree.domain.repository.IdentitySwitchTarget
import com.splitfree.domain.repository.SecureStorage
import com.splitfree.domain.repository.SecureStorageException
import com.splitfree.domain.repository.SecureStorageKeyLostException
import com.splitfree.domain.util.hexToBytes
import com.splitfree.domain.util.toHex
import com.splitfree.util.DebugLog as Log
import dagger.hilt.android.qualifiers.ApplicationContext
import fr.acinq.secp256k1.Secp256k1
import java.security.SecureRandom
import java.util.UUID
import javax.inject.Inject
import javax.inject.Named
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Manages the user's secp256k1 keypair using ACINQ secp256k1-kmp.
 * Keys stored in Android Keystore-backed encrypted storage.
 *
 * The private key (`nsec`) is the single source of truth. The public key is derived from it on
 * demand and memoised in [cachedPub]; the persisted `npub` is a mirror of the derived value,
 * written on every key install and used as a recovery fallback. Installs write the private key
 * first, so normal reads derive the new public key even if a crash leaves the mirror stale.
 */
@Singleton
class IdentityManager
@Inject
constructor(
    @ApplicationContext private val context: Context,
    @Named("identity") private val storage: SecureStorage
) : IdentityContract {

    /**
     * A decryption check, not a diagnosis: false also covers temporary storage failures.
     * Use [identityState] before choosing recovery or replacing an identity.
     */
    override fun hasIdentity(): Boolean = storage.contains(KEY_PRIVATE) && storage.canDecrypt(KEY_PRIVATE)

    /**
     * Distinguishes absent or unreadable key material from an unavailable store. Unknown read
     * failures must not authorize replacement of a potentially recoverable identity.
     */
    override fun identityState(): IdentityState {
        if (!storage.contains(KEY_PRIVATE)) return IdentityState.ABSENT
        return try {
            if (storage.getString(KEY_PRIVATE, null) != null) IdentityState.READY else IdentityState.RECOVERY_REQUIRED
        } catch (e: SecureStorageKeyLostException) {
            Log.w(TAG, "Identity store key is lost; recovery phrase required", e)
            IdentityState.RECOVERY_REQUIRED
        } catch (e: SecureStorageException) {
            Log.w(TAG, "Identity store temporarily unavailable", e)
            IdentityState.UNAVAILABLE
        } catch (e: Exception) {
            Log.w(TAG, "Identity store failed unexpectedly; treating as unavailable", e)
            IdentityState.UNAVAILABLE
        }
    }

    /**
     * Backs [observeHasIdentity]. Seeded lazily from [hasIdentity] so merely constructing the
     * manager never touches the Keystore; flipped to true by every write that installs a key.
     */
    private val identityState: MutableStateFlow<Boolean> by lazy { MutableStateFlow(hasIdentity()) }

    /**
     * Serialises every write that replaces `nsec` against the cache fill in [getPublicKeyHex], so a
     * derivation that started against the old key can never be memoised after the key changed.
     */
    private val keyLock = Any()

    /**
     * Avoids repeated Keystore decrypts and EC multiplication. [installActiveKey] invalidates it
     * under [keyLock] before replacing `nsec`, including when a write reports failure.
     */
    @Volatile
    private var cachedPub: String? = null

    private var activePublicKey: MutableStateFlow<String?>? = null

    override fun observeHasIdentity(): StateFlow<Boolean> = identityState.asStateFlow()

    override fun observeActivePublicKey(): StateFlow<String?> = synchronized(keyLock) {
        val state = activePublicKey ?: MutableStateFlow<String?>(null).also { activePublicKey = it }
        refreshActivePublicKey()
        state.asStateFlow()
    }

    private fun refreshActivePublicKey() {
        val pubkey = try {
            getPublicKeyHex().takeIf { it.isNotEmpty() }
        } catch (e: Exception) {
            Log.w(TAG, "Active identity cannot be read", e)
            null
        }
        activePublicKey?.value = pubkey
    }

    /**
     * Derives from `nsec`; falls back to the persisted `npub` only if derivation fails.
     * An uncached null private-key read returns an empty string; storage exceptions propagate.
     */
    override fun getPublicKeyHex(): String {
        cachedPub?.let { return it }
        synchronized(keyLock) {
            cachedPub?.let { return it }
            val privHex = storage.getString(KEY_PRIVATE, null) ?: return ""
            return try {
                derivePublicKeyHex(privHex).also {
                    cachedPub = it
                    activePublicKey?.value = it
                }
            } catch (e: Exception) {
                // Preserve a displayable identity if the decrypted private-key value is malformed.
                Log.w(TAG, "Could not derive public key from stored private key; using stored npub", e)
                storage.getString(KEY_PUBLIC, "")!!
            }
        }
    }

    /**
     * Returns private key as hex string. WARNING: String is immutable and cannot be
     * zeroed from memory. Use getPrivateKeyBytes() + fill(0) for crypto operations.
     * Only use this for user-facing display (Settings screen reveal).
     */
    override fun getPrivateKeyHex(): String = storage.getString(KEY_PRIVATE, "")!!

    override fun getPrivateKeyBytes(): ByteArray = getPrivateKeyHex().hexToBytes()

    override fun getPublicKeyBytes(): ByteArray = getPublicKeyHex().hexToBytes()

    /**
     * Refuses replacement while storage is unavailable. Confirmed wrapping-key loss permits
     * repair because the old ciphertext is unrecoverable; other storage failures propagate.
     *
     * @throws SecureStorageException if the store is unavailable or the write fails
     */
    override fun generateKeyPair(): String {
        requireClassifiableStore("generate")
        val (privHex, pubHex) = newKeyPairHex()
        installActiveKeyRepairingLostStore(privHex, pubHex)
        markIdentityCreated()
        return pubHex
    }

    /**
     * Generate a new keypair and store it as "pending" alongside the current one.
     * The old key is NOT overwritten until [commitPendingKeyPair] is called.
     */
    override fun generatePendingKeyPair(): String {
        getPendingPublicKeyHex()?.let { return it }
        val (privHex, pubHex) = newKeyPairHex()
        // Private key first, for the same reason as in installActiveKey.
        storage.putString(KEY_PENDING_PRIVATE, privHex)
        storage.putString(KEY_PENDING_PUBLIC, pubHex)
        return pubHex
    }

    /** Promote the pending keypair to active and delete the old one. */
    override fun commitPendingKeyPair() {
        val pendingPriv =
            storage.getString(KEY_PENDING_PRIVATE, null)
                ?: error("No pending keypair to commit")
        val pendingPub = getPendingPublicKeyHex() ?: error("No pending keypair to commit")
        installActiveKey(pendingPriv, pendingPub)
        clearPendingState()
        identityState.value = true
    }

    override fun finishPendingKeyPair(expectedPubkey: String) {
        synchronized(keyLock) {
            if (getPublicKeyHex() != expectedPubkey) {
                check(getPendingPublicKeyHex() == expectedPubkey) { "Pending identity does not match journal" }
                commitPendingKeyPair()
            } else {
                check(getPendingPublicKeyHex().let { it == null || it == expectedPubkey }) {
                    "Pending identity does not match journal"
                }
                // The private-key write is authoritative even if the public mirror or cleanup failed.
                storage.putString(KEY_PUBLIC, expectedPubkey)
                clearPendingState()
                identityState.value = true
                refreshActivePublicKey()
            }
        }
    }

    private fun clearPendingState() {
        storage.remove(KEY_PENDING_PUBLIC)
        storage.remove(KEY_REVOCATION_EVENT_IDS)
        storage.remove(KEY_REVOCATION_START)
        // Last: failure before this point still leaves a usable replacement key.
        storage.remove(KEY_PENDING_PRIVATE)
    }

    /** Discard only when publication is known to be impossible; a failed attempt may already have escaped. */
    override fun discardPendingKeyPair() {
        storage.remove(KEY_PENDING_PRIVATE)
        storage.remove(KEY_PENDING_PUBLIC)
        storage.remove(KEY_REVOCATION_EVENT_IDS)
        storage.remove(KEY_REVOCATION_START)
    }

    /** Presence only: the pending key may still require storage recovery. */
    override fun hasPendingKeyPair(): Boolean = storage.contains(KEY_PENDING_PRIVATE)

    /**
     * Set a plain SharedPreferences flag so BootReceiver can check without encrypted storage, and
     * wake anyone collecting [observeHasIdentity].
     */
    private fun markIdentityCreated() {
        context.getSharedPreferences("splitfree_boot", Context.MODE_PRIVATE)
            .edit { putBoolean("identity_created", true) }
        identityState.value = true
    }

    /** Derived from the pending private key, exactly like [getPublicKeyHex] is from the active one. */
    override fun getPendingPublicKeyHex(): String? {
        val privHex = storage.getString(KEY_PENDING_PRIVATE, null) ?: return null
        return try {
            derivePublicKeyHex(privHex)
        } catch (e: Exception) {
            Log.w(TAG, "Could not derive pending public key from stored private key; using stored npub", e)
            storage.getString(KEY_PENDING_PUBLIC, null)
        }
    }

    override fun getPendingPrivateKeyBytes(): ByteArray? = storage.getString(KEY_PENDING_PRIVATE, null)?.hexToBytes()

    override fun markRevocationStarted() {
        storage.putLong(KEY_REVOCATION_START, System.currentTimeMillis() / 1000)
    }

    override fun setRevocationEventIds(eventIds: List<String>) {
        storage.putString(KEY_REVOCATION_EVENT_IDS, eventIds.joinToString(","))
    }

    override fun getRevocationEventIds(): List<String> = storage.getString(KEY_REVOCATION_EVENT_IDS, null)
        ?.split(",")
        ?.filter { it.isNotEmpty() }
        ?: emptyList()

    override fun getRevocationStartTime(): Long = storage.getLong(KEY_REVOCATION_START, 0L)

    /**
     * Export private key as 24-word BIP-39 mnemonic.
     */
    override fun exportAsMnemonic(): List<String> {
        val privBytes = getPrivateKeyBytes()
        try {
            return Bip39.toMnemonic(privBytes)
        } finally {
            privBytes.fill(0)
        }
    }

    override fun importKey(input: String) {
        val (privHex, pubHex) = parseKey(input)
        requireClassifiableStore("import")
        installActiveKeyRepairingLostStore(privHex, pubHex)
        markIdentityCreated()
    }

    private fun parseKey(input: String): Pair<String, String> {
        val trimmed = input.trim()
        val words = trimmed.split("\\s+".toRegex())
        val bytes = if (Bip39.isMnemonic(trimmed)) {
            require(words.size == 24) { "Only 24-word seed phrases are supported (got ${words.size})" }
            Bip39.toEntropy(words)
        } else {
            trimmed.hexToBytes()
        }
        try {
            require(bytes.size == 32 && Secp256k1.secKeyVerify(bytes)) { "Invalid private key" }
            return bytes.toHex() to NostrEvent.pubkeyFromPrivkey(bytes)
        } finally {
            bytes.fill(0)
        }
    }

    @Serializable
    private data class StagedIdentity(val target: IdentitySwitchTarget, val privateKey: String)

    @Serializable
    private data class ArchivedPending(val privateKey: String, val eventIds: List<String>, val startedAt: Long)

    override fun stageIdentitySwitch(input: String?, supersededSwitchId: String?): IdentitySwitchTarget =
        synchronized(keyLock) {
            val pair = input?.let(::parseKey)
            requireClassifiableStore("switch")
            var lostStage = false
            if (storage.contains(KEY_SWITCH)) {
                try {
                    check(readStagedIdentity() == null) { "Finish the staged identity switch first" }
                } catch (_: SecureStorageKeyLostException) {
                    lostStage = true
                }
            }
            val (privateKey, publicKey) = pair ?: newKeyPairHex()
            val owner = if (lostStage) null else (storedIdentity() as? StoredIdentity.Known)?.pubHex
            val pending = if (owner != null) getPendingPublicKeyHex() else null
            val stage = StagedIdentity(
                IdentitySwitchTarget(UUID.randomUUID().toString(), owner, publicKey, pending, supersededSwitchId),
                privateKey
            )
            val encoded = Json.encodeToString(stage)
            try {
                storage.putString(KEY_SWITCH, encoded)
            } catch (e: SecureStorageKeyLostException) {
                storage.resetAfterKeyLoss()
                storage.putString(KEY_SWITCH, encoded)
            }
            checkSecureReadback(KEY_SWITCH, encoded)
            stage.target
        }

    override fun stagedIdentitySwitch(): IdentitySwitchTarget? =
        if (storage.contains(KEY_SWITCH)) readStagedIdentity()?.target else null

    private fun readStagedIdentity(): StagedIdentity? =
        storage.getString(KEY_SWITCH, null)?.let { Json.decodeFromString<StagedIdentity>(it) }

    override fun commitIdentitySwitch(target: IdentitySwitchTarget) = synchronized(keyLock) {
        val stage = checkNotNull(readStagedIdentity()) { "Staged identity unavailable" }
        check(stage.target == target && derivePublicKeyHex(stage.privateKey) == target.newPubkey) {
            "Staged identity does not match journal"
        }
        installActiveKeyRepairingLostStore(stage.privateKey, target.newPubkey)
        markIdentityCreated()
    }

    override fun completeIdentitySwitch(target: IdentitySwitchTarget) = synchronized(keyLock) {
        val stage = readStagedIdentity()
        check(stage == null || stage.target == target) { "Staged identity changed" }
        check(getPublicKeyHex() == target.newPubkey) { "Identity switch did not commit" }
        storage.remove(KEY_SWITCH)
    }

    override fun archivePendingKeyPair(owner: String) = synchronized(keyLock) {
        val privateKey = storage.getString(KEY_PENDING_PRIVATE, null) ?: return@synchronized
        val archived = ArchivedPending(privateKey, getRevocationEventIds(), getRevocationStartTime())
        val key = "$KEY_PENDING_ARCHIVE:$owner"
        val successor = derivePublicKeyHex(privateKey)
        val entries = readArchivedPending(owner)
        val existing = entries[successor]
        check(existing == null || existing.privateKey == privateKey) { "Archived successor key changed" }
        val encoded = Json.encodeToString(entries + (successor to (existing ?: archived)))
        storage.putString(key, encoded)
        checkSecureReadback(key, encoded)
        clearPendingState()
    }

    override fun restoreArchivedPendingKeyPair(owner: String, expectedPubkey: String?) = synchronized(keyLock) {
        val entries = readArchivedPending(owner)
        val archived = if (expectedPubkey != null) {
            entries[expectedPubkey]
        } else {
            check(entries.size <= 1) { "Archived successor is ambiguous; preserve every candidate" }
            entries.values.singleOrNull()
        } ?: return@synchronized
        val existing = storage.getString(KEY_PENDING_PRIVATE, null)
        check(existing == null || existing == archived.privateKey) { "Another pending successor needs recovery" }
        storage.putString(KEY_PENDING_PRIVATE, archived.privateKey)
        checkSecureReadback(KEY_PENDING_PRIVATE, archived.privateKey)
        storage.putString(KEY_PENDING_PUBLIC, derivePublicKeyHex(archived.privateKey))
        setRevocationEventIds(archived.eventIds)
        storage.putLong(KEY_REVOCATION_START, archived.startedAt)
    }

    override fun getArchivedPendingPublicKeyHex(owner: String, expectedPubkey: String?): String? {
        val entries = readArchivedPending(owner)
        return if (expectedPubkey != null) {
            entries[expectedPubkey]?.privateKey?.let(::derivePublicKeyHex)
        } else {
            entries.keys.singleOrNull()
        }
    }

    private fun readArchivedPending(owner: String): Map<String, ArchivedPending> =
        storage.getString("$KEY_PENDING_ARCHIVE:$owner", null)?.let {
            Json.decodeFromString<Map<String, ArchivedPending>>(it)
        }.orEmpty()

    private fun checkSecureReadback(key: String, value: String) {
        if (storage.getString(key, null) != value) throw SecureStorageException("Identity write did not read back")
    }

    /**
     * Refuse to overwrite an identity whose recoverability cannot currently be determined.
     */
    private fun requireClassifiableStore(op: String) {
        if (identityState() == IdentityState.UNAVAILABLE) {
            throw SecureStorageException("Identity store unavailable; refusing to $op a key over it")
        }
    }

    private sealed interface StoredIdentity {
        /** Best available identity: derived from `nsec`, or recovered from the potentially stale `npub` mirror. */
        data class Known(val pubHex: String) : StoredIdentity

        /** The store positively holds no identity: a fresh install, or a repair that was reset but never finished. */
        data object None : StoredIdentity

        /**
         * Ownership cannot be established from the private key or mirror; absence is not proven.
         */
        data object Unknown : StoredIdentity
    }

    /**
     * Derives ownership from `nsec`, falling back to `npub`. Secure-storage failures mean unknown
     * ownership, not an empty store; a surviving mirror can identify a corrupt private-key blob.
     */
    private fun storedIdentity(): StoredIdentity = try {
        val privateKey = storage.getString(KEY_PRIVATE, null)
        val derived = privateKey?.let { key -> runCatching { derivePublicKeyHex(key) }.getOrNull() }
        val mirrored = derived ?: storage.getString(KEY_PUBLIC, null)
        when {
            !mirrored.isNullOrEmpty() -> StoredIdentity.Known(mirrored)
            storage.contains(KEY_PRIVATE) -> StoredIdentity.Unknown
            else -> StoredIdentity.None
        }
    } catch (e: SecureStorageException) {
        Log.w(TAG, "Could not determine the stored identity; keeping any pending successor", e)
        StoredIdentity.Unknown
    }

    /**
     * Repairs only confirmed wrapping-key loss; [SecureStorage.resetAfterKeyLoss] rechecks before
     * discarding ciphertext. Other failures propagate without requesting a reset.
     *
     * Read-back verifies the installed value before caching its public key; it is not a second
     * durability guarantee. A crash after reset but before installation leaves an empty store.
     */
    private fun installActiveKeyRepairingLostStore(privHex: String, pubHex: String) {
        synchronized(keyLock) {
            try {
                installActiveKey(privHex, pubHex)
            } catch (e: SecureStorageKeyLostException) {
                Log.w(TAG, "Identity store key is lost; replacing the unreadable store with the new key", e)
                storage.resetAfterKeyLoss()
                installActiveKey(privHex, pubHex)
            }
        }
    }

    /**
     * Writes the authoritative private key before its public mirror. Normal reads can derive
     * the public key if the second write fails. [keyLock] serializes installation with cache fill.
     */
    private fun installActiveKey(privHex: String, pubHex: String) {
        synchronized(keyLock) {
            // A storage error may be reported after the durable write landed.
            cachedPub = null
            try {
                storage.putString(KEY_PRIVATE, privHex)
                checkSecureReadback(KEY_PRIVATE, privHex)
                storage.putString(KEY_PUBLIC, pubHex)
            } finally {
                // A failed mirror/commit can still leave the successor as the authoritative key.
                refreshActivePublicKey()
            }
        }
    }

    /** @return `(privHex, pubHex)` of a fresh random secp256k1 keypair; the raw bytes are zeroed before returning */
    private fun newKeyPairHex(): Pair<String, String> {
        val privKey = ByteArray(32)
        val random = SecureRandom()
        try {
            do {
                random.nextBytes(privKey)
            } while (!Secp256k1.secKeyVerify(privKey))
            return privKey.toHex() to NostrEvent.pubkeyFromPrivkey(privKey)
        } finally {
            privKey.fill(0)
        }
    }

    /** Derive the x-only public key for [privHex], zeroing the decoded private bytes afterwards. */
    private fun derivePublicKeyHex(privHex: String): String {
        val privBytes = privHex.hexToBytes()
        try {
            return NostrEvent.pubkeyFromPrivkey(privBytes)
        } finally {
            privBytes.fill(0)
        }
    }

    companion object {
        private const val TAG = "IdentityManager"
        private const val KEY_PRIVATE = "nsec"
        private const val KEY_PUBLIC = "npub"
        private const val KEY_PENDING_PRIVATE = "nsec_pending"
        private const val KEY_PENDING_PUBLIC = "npub_pending"
        private const val KEY_REVOCATION_EVENT_IDS = "revocation_event_ids"
        private const val KEY_REVOCATION_START = "revocation_start"
        private const val KEY_SWITCH = "identity_switch"
        private const val KEY_PENDING_ARCHIVE = "identity_pending_archive"
    }
}
