package com.splitfree.data.util

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.splitfree.util.DebugLog as Log
import java.io.File

/**
 * Factory for AES-256 EncryptedSharedPreferences.
 *
 * If the encrypted prefs file is corrupted (e.g. after a failed key migration),
 * the file is deleted and recreated rather than crashing the app.
 */
object EncryptedPrefsFactory {
    fun create(context: Context, name: String): SharedPreferences = try {
        buildPrefs(context, name)
    } catch (e: Exception) {
        Log.e("EncryptedPrefsFactory", "EncryptedSharedPreferences '$name' failed, resetting: ${e.message}")
        try {
            File(context.filesDir.parent, "shared_prefs/$name.xml").delete()
        } catch (_: Exception) {
        }
        buildPrefs(context, name)
    }

    private fun buildPrefs(context: Context, name: String): SharedPreferences {
        val masterKey =
            MasterKey
                .Builder(context)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()
        return EncryptedSharedPreferences.create(
            context,
            name,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }
}
