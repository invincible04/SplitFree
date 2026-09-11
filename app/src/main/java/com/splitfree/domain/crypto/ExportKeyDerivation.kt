package com.splitfree.domain.crypto

import com.splitfree.domain.crypto.nip.Nip44

/**
 * Derives the key that authenticates `.splitfree` backup files.
 *
 * The MAC key is bound to the user's **identity**, not to a group key: every group member holds
 * the group key, so a group-keyed MAC would let any member craft a backup that the importer
 * trusts (including `seal:`-marked rumors that are not third-party verifiable). Deriving from the
 * private key means only the identity that exported a file can produce one that verifies for it,
 * which is exactly the restore scenario (same key, new device).
 *
 * HKDF-SHA256 (RFC 5869) with a fixed salt and info label so the derived key is domain-separated
 * from every other use of the private key (signing, NIP-44 ECDH, seed derivation).
 */
object ExportKeyDerivation {
    private val SALT = "splitfree-export-v2".toByteArray(Charsets.UTF_8)
    private val MAC_INFO = "mac".toByteArray(Charsets.UTF_8)

    /** Output length of [deriveExportMacKey] in bytes. */
    const val MAC_KEY_LENGTH = 32

    /**
     * @param privKey 32-byte secp256k1 private key; not modified, caller must zero it afterwards
     * @return 32-byte HMAC key; caller must zero it after use
     */
    fun deriveExportMacKey(privKey: ByteArray): ByteArray {
        require(privKey.size == 32) { "private key must be 32 bytes" }
        val prk = Nip44.hkdfExtract(salt = SALT, ikm = privKey)
        try {
            return Nip44.hkdfExpand(prk, info = MAC_INFO, length = MAC_KEY_LENGTH)
        } finally {
            prk.fill(0)
        }
    }
}
