package com.splitfree.domain.crypto

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * NIP-59 Gift Wrap service for metadata protection — no SDK.
 * Uses from-scratch Nip59 implementation.
 */
@Singleton
class GiftWrapService
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
        private val identityManager: IdentityManager,
    ) {
        private val prefs: SharedPreferences by lazy {
            try {
                createEncryptedPrefs()
            } catch (e: Exception) {
                Log.e("GiftWrapService", "EncryptedSharedPreferences failed, resetting: ${e.message}")
                try {
                    java.io.File(context.filesDir.parent, "shared_prefs/splitfree_settings.xml").delete()
                } catch (_: Exception) {
                }
                createEncryptedPrefs()
            }
        }

        private fun createEncryptedPrefs(): SharedPreferences {
            val masterKey =
                MasterKey
                    .Builder(context)
                    .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                    .build()
            return EncryptedSharedPreferences.create(
                context,
                "splitfree_settings",
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
            )
        }

        var enabled: Boolean
            get() = prefs.getBoolean(KEY_GIFT_WRAP, true)
            set(value) {
                prefs.edit().putBoolean(KEY_GIFT_WRAP, value).apply()
            }

        /**
         * Wrap a NostrEvent in NIP-59 gift wrap for a recipient.
         * Returns the original event if gift wrap is disabled.
         */
        fun wrapIfEnabled(
            event: NostrEvent,
            recipientPubKeyHex: String,
        ): NostrEvent {
            if (!enabled) return event
            val privKey = identityManager.getPrivateKeyBytes()
            try {
                return Nip59.giftWrap(
                    rumor = event.copy(sig = ""), // rumor must be unsigned
                    senderPrivKey = privKey,
                    recipientPubKey = recipientPubKeyHex.hexToBytes(),
                )
            } finally {
                privKey.fill(0)
            }
        }

        /**
         * Unwrap a gift wrap event. Returns (rumor, senderPubkey) or null.
         */
        fun tryUnwrap(event: NostrEvent): Pair<NostrEvent, String>? {
            if (event.kind != 1059) return null
            val privKey = identityManager.getPrivateKeyBytes()
            try {
                return Nip59.unwrap(event, privKey)
            } finally {
                privKey.fill(0)
            }
        }

        fun getCustomRelays(): List<String> {
            val raw = prefs.getString(KEY_CUSTOM_RELAYS, null) ?: return emptyList()
            return raw.split(",").filter { it.startsWith("wss://") }
        }

        fun setCustomRelays(relays: List<String>) {
            val safe = relays.filter { it.startsWith("wss://") }
            prefs.edit().putString(KEY_CUSTOM_RELAYS, safe.joinToString(",")).apply()
        }

        companion object {
            private const val KEY_GIFT_WRAP = "gift_wrap_enabled"
            private const val KEY_CUSTOM_RELAYS = "custom_relays"
        }
    }
