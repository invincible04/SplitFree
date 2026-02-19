package com.splitfree.data.settings

import android.content.Context
import android.content.SharedPreferences
import com.splitfree.data.util.EncryptedPrefsFactory
import com.splitfree.domain.repository.SettingsContract
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * User preferences backed by EncryptedSharedPreferences (relay config, privacy toggles).
 */
@Singleton
class UserPreferences
@Inject
constructor(@ApplicationContext private val context: Context) : SettingsContract {
    private val prefs: SharedPreferences by lazy {
        EncryptedPrefsFactory.create(context, "splitfree_settings")
    }

    override var giftWrapEnabled: Boolean
        get() = prefs.getBoolean(KEY_GIFT_WRAP, true)
        set(value) {
            prefs.edit().putBoolean(KEY_GIFT_WRAP, value).apply()
        }

    override fun getCustomRelays(): List<String> {
        val raw = prefs.getString(KEY_CUSTOM_RELAYS, null) ?: return emptyList()
        return raw.split(",").filter { it.startsWith("wss://") }
    }

    override fun setCustomRelays(relays: List<String>) {
        val safe = relays.filter { it.startsWith("wss://") }
        prefs.edit().putString(KEY_CUSTOM_RELAYS, safe.joinToString(",")).apply()
    }

    companion object {
        private const val KEY_GIFT_WRAP = "gift_wrap_enabled"
        private const val KEY_CUSTOM_RELAYS = "custom_relays"
    }
}
