package com.splitfree.data.settings

import android.content.Context
import android.content.SharedPreferences
import com.splitfree.domain.repository.SettingsContract
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * User preferences backed by plain SharedPreferences (privacy toggles).
 * No sensitive data; Android FBE protects at rest.
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

    /**
     * True once the app has asked for `POST_NOTIFICATIONS` (API 33+). The system prompt is shown at
     * most once per install so a user who declined is not nagged on every launch.
     */
    var notificationsPrompted: Boolean
        get() = prefs.getBoolean(KEY_NOTIFICATIONS_PROMPTED, false)
        set(value) {
            prefs.edit().putBoolean(KEY_NOTIFICATIONS_PROMPTED, value).apply()
        }

    companion object {
        private const val KEY_GIFT_WRAP = "gift_wrap_enabled"
        private const val KEY_DISPLAY_NAME = "display_name"
        private const val KEY_NOTIFICATIONS_PROMPTED = "notifications_prompted"
    }
}
