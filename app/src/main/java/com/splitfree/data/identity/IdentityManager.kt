package com.splitfree.data.identity

import android.content.Context
import com.splitfree.domain.crypto.NostrEvent
import com.splitfree.domain.crypto.nip.Bip39
import com.splitfree.domain.repository.IdentityContract
import com.splitfree.domain.repository.SecureStorage
import com.splitfree.domain.util.hexToBytes
import com.splitfree.domain.util.toHex
import com.splitfree.util.DebugLog as Log
import dagger.hilt.android.qualifiers.ApplicationContext
import fr.acinq.secp256k1.Secp256k1
import java.security.SecureRandom
import javax.inject.Inject
import javax.inject.Named
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Manages the user's secp256k1 keypair using ACINQ secp256k1-kmp.
 * Keys stored in Android Keystore-backed encrypted storage.
 *
 * The private key (`nsec`) is the single source of truth. The public key is derived from it on
 * demand and memoised in [cachedPub]; the persisted `npub` is written only for backward
 * compatibility with older builds and is read solely if derivation fails. Every write path stores
 * the private key first, so a crash between the two writes can never leave a mismatched pair.
 */
@Singleton
class IdentityManager
@Inject
constructor(
    @ApplicationContext private val context: Context,
    @Named("identity") private val storage: SecureStorage
) : IdentityContract {

    /**
     * True only if a private key is stored AND can actually be decrypted. If the Android
     * Keystore lost the wrapping key (identity storage never auto-resets), this returns false
     * so the app routes to onboarding / mnemonic restore instead of crashing on first use.
     */
    override fun hasIdentity(): Boolean = storage.contains(KEY_PRIVATE) && storage.canDecrypt(KEY_PRIVATE)

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
     * Public key derived from the active private key. Deriving costs a Keystore decrypt plus an
     * EC multiplication, so the result is memoised here and invalidated (under [keyLock]) by every
     * write that replaces `nsec`: [generateKeyPair], [importKey], [commitPendingKeyPair].
     */
    @Volatile
    private var cachedPub: String? = null

    override fun observeHasIdentity(): StateFlow<Boolean> = identityState.asStateFlow()

    /**
     * Derived from the stored private key, never read from a separately persisted value, so the
     * pair can never disagree. Returns an empty string when no identity exists.
     */
    override fun getPublicKeyHex(): String {
        cachedPub?.let { return it }
        synchronized(keyLock) {
            cachedPub?.let { return it }
            val privHex = storage.getString(KEY_PRIVATE, null) ?: return ""
            return try {
                derivePublicKeyHex(privHex).also { cachedPub = it }
            } catch (e: Exception) {
                // Unreachable for a key this class wrote (every write path validates it first). Kept
                // only so a corrupted legacy `nsec` degrades to the stored `npub` instead of crashing.
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

    override fun generateKeyPair(): String {
        val (privHex, pubHex) = newKeyPairHex()
        installActiveKey(privHex, pubHex)
        storage.remove(KEY_PENDING_PRIVATE)
        storage.remove(KEY_PENDING_PUBLIC)
        markIdentityCreated()
        return pubHex
    }

    /**
     * Generate a new keypair and store it as "pending" alongside the current one.
     * The old key is NOT overwritten until [commitPendingKeyPair] is called.
     */
    override fun generatePendingKeyPair(): String {
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
        storage.remove(KEY_PENDING_PRIVATE)
        storage.remove(KEY_PENDING_PUBLIC)
        storage.remove(KEY_REVOCATION_EVENT_IDS)
        storage.remove(KEY_REVOCATION_START)
        identityState.value = true
    }

    /** Discard a pending keypair (e.g., on revocation failure). */
    override fun discardPendingKeyPair() {
        storage.remove(KEY_PENDING_PRIVATE)
        storage.remove(KEY_PENDING_PUBLIC)
        storage.remove(KEY_REVOCATION_EVENT_IDS)
        storage.remove(KEY_REVOCATION_START)
    }

    /** Check if there's an incomplete revocation to resume. */
    override fun hasPendingKeyPair(): Boolean = storage.contains(KEY_PENDING_PRIVATE)

    /**
     * Set a plain SharedPreferences flag so BootReceiver can check without encrypted storage, and
     * wake anyone collecting [observeHasIdentity].
     */
    private fun markIdentityCreated() {
        context.getSharedPreferences("splitfree_boot", Context.MODE_PRIVATE)
            .edit()
            .putBoolean("identity_created", true)
            .apply()
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

    /**
     * Import a key from hex string, or BIP-39 mnemonic (space-separated words).
     *
     * Replaces the active identity outright, so any half-finished revocation (pending keypair and
     * its tracking state) is discarded: it belonged to the key being replaced.
     *
     * @throws IllegalArgumentException if the key is invalid; nothing is written in that case
     */
    override fun importKey(input: String) {
        val trimmed = input.trim()
        val words = trimmed.split("\\s+".toRegex())
        val privBytes =
            if (Bip39.isMnemonic(trimmed)) {
                require(words.size == 24) { "Only 24-word seed phrases are supported (got ${words.size})" }
                Bip39.toEntropy(words)
            } else {
                trimmed.hexToBytes()
            }
        try {
            require(privBytes.size == 32 && Secp256k1.secKeyVerify(privBytes)) { "Invalid private key" }
            installActiveKey(privBytes.toHex(), NostrEvent.pubkeyFromPrivkey(privBytes))
        } finally {
            privBytes.fill(0)
        }
        storage.remove(KEY_PENDING_PRIVATE)
        storage.remove(KEY_PENDING_PUBLIC)
        storage.remove(KEY_REVOCATION_EVENT_IDS)
        storage.remove(KEY_REVOCATION_START)
        markIdentityCreated()
    }

    /**
     * Persist a new active keypair. The private key is written FIRST because it is the single
     * source of truth; `npub` is only a compatibility mirror for older builds, so a crash between
     * the two writes leaves a fully usable identity. The derived-pubkey cache is dropped under
     * [keyLock] so no reader can memoise a value computed from the previous key.
     */
    private fun installActiveKey(privHex: String, pubHex: String) {
        synchronized(keyLock) {
            storage.putString(KEY_PRIVATE, privHex)
            cachedPub = null
            storage.putString(KEY_PUBLIC, pubHex)
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
    }
}
