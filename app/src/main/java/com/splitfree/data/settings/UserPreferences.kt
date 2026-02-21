package com.splitfree.data.settings

import android.content.Context
import android.content.SharedPreferences
import com.splitfree.domain.repository.SettingsContract
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * User preferences backed by plain SharedPreferences (relay config, privacy toggles).
 * No sensitive data — Android FBE protects at rest.
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
            prefs.edit().putBoolean(KEY_GIFT_WRAP, value).apply()
        }

    override var displayName: String
        get() = prefs.getString(KEY_DISPLAY_NAME, "") ?: ""
        set(value) {
            prefs.edit().putString(KEY_DISPLAY_NAME, value.take(50).trim()).apply()
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
        private const val KEY_DISPLAY_NAME = "display_name"
        private const val KEY_CUSTOM_RELAYS = "custom_relays"
    }
}
