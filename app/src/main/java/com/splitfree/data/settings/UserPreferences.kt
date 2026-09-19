package com.splitfree.data.settings

import android.annotation.SuppressLint
import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import com.splitfree.domain.repository.DisplayNameIntent
import com.splitfree.domain.repository.SettingsContract
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.serialization.json.Json

/**
 * Plain SharedPreferences for privacy toggles and identity-scoped display-name intents.
 * Names and identity associations rely on Android's sandbox and file-based encryption, not app encryption.
 */
@Singleton
class UserPreferences
@Inject
constructor(@ApplicationContext private val context: Context) : SettingsContract {
    private val prefs: SharedPreferences by lazy {
        context.getSharedPreferences("splitfree_settings", Context.MODE_PRIVATE)
    }

    override var giftWrapEnabled: Boolean
        get() = prefs.getBoolean(KEY_GIFT_WRAP, true)
        set(value) {
            prefs.edit { putBoolean(KEY_GIFT_WRAP, value) }
        }

    override var displayName: String
        get() = prefs.getString(KEY_DISPLAY_NAME, "") ?: ""
        set(value) {
            prefs.edit { putString(KEY_DISPLAY_NAME, value.take(50).trim()) }
        }

    private data class FailedIntentWrite(val intent: String?, val legacyName: String?, val migrated: Boolean)

    private val failedIntents = mutableMapOf<String, FailedIntentWrite>()

    @Synchronized
    override fun getDisplayNameIntent(identityPubkey: String): DisplayNameIntent? {
        check(identityPubkey !in failedIntents) { "Display name persistence needs a retry" }
        return prefs.getString("$KEY_NAME_INTENT:$identityPubkey", null)?.let {
            Json.decodeFromString<DisplayNameIntent>(it).also { intent ->
                check(intent.identityPubkey == identityPubkey)
            }
        }
    }

    @Synchronized
    override fun displayNameFor(identityPubkey: String): String = getDisplayNameIntent(identityPubkey)?.name
        ?: if (prefs.getBoolean(KEY_NAME_INTENT_MIGRATED, false)) "" else displayName

    @Synchronized
    override fun saveDisplayNameIntent(identityPubkey: String, name: String): DisplayNameIntent {
        require(identityPubkey.isNotBlank())
        val safeName = name.take(50).trim()
        retryFailedIntentWrite(identityPubkey)
        getDisplayNameIntent(identityPubkey)?.takeIf { it.name == safeName }?.let { return it }
        return persistIntent(identityPubkey, safeName, updateLegacyName = true)
    }

    @Synchronized
    override fun initializeDisplayNameIntent(identityPubkey: String): DisplayNameIntent {
        retryFailedIntentWrite(identityPubkey)
        getDisplayNameIntent(identityPubkey)?.let { return it }
        // Only the identity active during the upgrade inherits the old, unscoped preference.
        return persistIntent(identityPubkey, displayNameFor(identityPubkey), updateLegacyName = false)
    }

    @Synchronized
    override fun transferDisplayNameIntent(oldPubkey: String, newPubkey: String): DisplayNameIntent {
        require(oldPubkey.isNotBlank() && newPubkey.isNotBlank() && oldPubkey != newPubkey)
        retryFailedIntentWrite(newPubkey)
        val previous = initializeDisplayNameIntent(oldPubkey)
        getDisplayNameIntent(newPubkey)?.takeIf { it.name == previous.name }?.let { return it }
        return persistIntent(newPubkey, previous.name, updateLegacyName = false)
    }

    private fun retryFailedIntentWrite(identityPubkey: String) {
        val previous = failedIntents[identityPubkey] ?: return
        check(restoreIntent(identityPubkey, previous)) { "Display name persistence needs a retry" }
        failedIntents -= identityPubkey
    }

    @SuppressLint("ApplySharedPref", "UseKtx")
    private fun restoreIntent(identityPubkey: String, previous: FailedIntentWrite): Boolean = prefs.edit()
        .putString("$KEY_NAME_INTENT:$identityPubkey", previous.intent)
        .putString(KEY_DISPLAY_NAME, previous.legacyName)
        .putBoolean(KEY_NAME_INTENT_MIGRATED, previous.migrated)
        .commit()

    // Check synchronous persistence before publication; KTX edit discards the commit result.
    @SuppressLint("ApplySharedPref", "UseKtx")
    private fun persistIntent(identityPubkey: String, name: String, updateLegacyName: Boolean): DisplayNameIntent {
        val key = "$KEY_NAME_INTENT:$identityPubkey"
        val previous = FailedIntentWrite(
            prefs.getString(key, null),
            prefs.getString(KEY_DISPLAY_NAME, null),
            prefs.getBoolean(KEY_NAME_INTENT_MIGRATED, false)
        )
        val intent = DisplayNameIntent(identityPubkey, UUID.randomUUID().toString(), name, System.currentTimeMillis())
        val editor = prefs.edit()
            .putString(key, Json.encodeToString(DisplayNameIntent.serializer(), intent))
            .putBoolean(KEY_NAME_INTENT_MIGRATED, true)
        if (updateLegacyName) editor.putString(KEY_DISPLAY_NAME, name)
        // A false commit result can still change memory. Block reads for this identity and restore
        // prior values so later writes cannot flush the rejected intent; disk rollback is not guaranteed.
        try {
            check(editor.commit()) { "Could not persist display name" }
        } catch (e: Exception) {
            failedIntents[identityPubkey] = previous
            try {
                restoreIntent(identityPubkey, previous)
            } catch (rollback: Exception) {
                e.addSuppressed(rollback)
            }
            throw e
        }
        failedIntents -= identityPubkey
        return intent
    }

    /**
     * True once the app has asked for `POST_NOTIFICATIONS` (API 33+). The system prompt is shown at
     * most once per install so a user who declined is not nagged on every launch.
     */
    var notificationsPrompted: Boolean
        get() = prefs.getBoolean(KEY_NOTIFICATIONS_PROMPTED, false)
        set(value) {
            prefs.edit { putBoolean(KEY_NOTIFICATIONS_PROMPTED, value) }
        }

    companion object {
        private const val KEY_GIFT_WRAP = "gift_wrap_enabled"
        private const val KEY_NAME_INTENT = "display_name_intent"
        private const val KEY_NAME_INTENT_MIGRATED = "display_name_intent_migrated"
        private const val KEY_DISPLAY_NAME = "display_name"
        private const val KEY_NOTIFICATIONS_PROMPTED = "notifications_prompted"
    }
}
