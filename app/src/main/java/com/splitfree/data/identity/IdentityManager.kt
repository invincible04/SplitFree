package com.splitfree.data.identity

import android.content.Context
import com.splitfree.domain.crypto.NostrEvent
import com.splitfree.domain.crypto.nip.Bip39
import com.splitfree.domain.repository.IdentityContract
import com.splitfree.domain.repository.SecureStorage
import com.splitfree.domain.util.hexToBytes
import com.splitfree.domain.util.toHex
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

    override fun observeHasIdentity(): StateFlow<Boolean> = identityState.asStateFlow()

    override fun getPublicKeyHex(): String = storage.getString(KEY_PUBLIC, "")!!

    /**
     * Returns private key as hex string. WARNING: String is immutable and cannot be
     * zeroed from memory. Use getPrivateKeyBytes() + fill(0) for crypto operations.
     * Only use this for user-facing display (Settings screen reveal).
     */
    override fun getPrivateKeyHex(): String = storage.getString(KEY_PRIVATE, "")!!

    override fun getPrivateKeyBytes(): ByteArray = getPrivateKeyHex().hexToBytes()

    override fun getPublicKeyBytes(): ByteArray = getPublicKeyHex().hexToBytes()

    override fun generateKeyPair(): Pair<String, String> {
        val privKey = ByteArray(32)
        val random = SecureRandom()
        do {
            random.nextBytes(privKey)
        } while (!Secp256k1.secKeyVerify(privKey))

        val pubHex = NostrEvent.pubkeyFromPrivkey(privKey)
        val privHex = privKey.toHex()
        privKey.fill(0)

        storage.putString(KEY_PRIVATE, privHex)
        storage.putString(KEY_PUBLIC, pubHex)
        storage.remove(KEY_PENDING_PRIVATE)
        storage.remove(KEY_PENDING_PUBLIC)
        markIdentityCreated()
        return privHex to pubHex
    }

    /**
     * Generate a new keypair and store it as "pending" alongside the current one.
     * The old key is NOT overwritten until [commitPendingKeyPair] is called.
     */
    override fun generatePendingKeyPair(): Pair<String, String> {
        val privKey = ByteArray(32)
        val random = SecureRandom()
        do {
            random.nextBytes(privKey)
        } while (!Secp256k1.secKeyVerify(privKey))

        val pubHex = NostrEvent.pubkeyFromPrivkey(privKey)
        val privHex = privKey.toHex()
        privKey.fill(0)

        storage.putString(KEY_PENDING_PRIVATE, privHex)
        storage.putString(KEY_PENDING_PUBLIC, pubHex)
        return privHex to pubHex
    }

    /** Promote the pending keypair to active and delete the old one. */
    override fun commitPendingKeyPair() {
        val pendingPriv =
            storage.getString(KEY_PENDING_PRIVATE, null)
                ?: error("No pending keypair to commit")
        val pendingPub =
            storage.getString(KEY_PENDING_PUBLIC, null)
                ?: error("No pending keypair to commit")
        storage.putString(KEY_PRIVATE, pendingPriv)
        storage.putString(KEY_PUBLIC, pendingPub)
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

    override fun getPendingPublicKeyHex(): String? = storage.getString(KEY_PENDING_PUBLIC, null)

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
     * @throws IllegalArgumentException if the key is invalid.
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
        require(privBytes.size == 32 && Secp256k1.secKeyVerify(privBytes)) { "Invalid private key" }

        val pubHex = NostrEvent.pubkeyFromPrivkey(privBytes)
        storage.putString(KEY_PRIVATE, privBytes.toHex())
        storage.putString(KEY_PUBLIC, pubHex)
        privBytes.fill(0)
        markIdentityCreated()
    }

    companion object {
        private const val KEY_PRIVATE = "nsec"
        private const val KEY_PUBLIC = "npub"
        private const val KEY_PENDING_PRIVATE = "nsec_pending"
        private const val KEY_PENDING_PUBLIC = "npub_pending"
        private const val KEY_REVOCATION_EVENT_IDS = "revocation_event_ids"
        private const val KEY_REVOCATION_START = "revocation_start"
    }
}
