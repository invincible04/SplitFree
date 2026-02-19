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
 * If the encrypted prefs file is corrupted due to a permanent KeyStore failure
 * (e.g. after a key migration or factory reset), the file is deleted and recreated.
 * Transient errors (OOM, I/O) are propagated to the caller to avoid silent key destruction.
 */
object EncryptedPrefsFactory {
    fun create(context: Context, name: String): SharedPreferences = try {
        buildPrefs(context, name)
    } catch (e: Exception) {
        when (e) {
            is java.security.KeyStoreException,
            is java.security.InvalidKeyException,
            is android.security.keystore.KeyPermanentlyInvalidatedException -> {
                Log.e("EncryptedPrefsFactory", "Key corrupted for '$name', resetting: ${e.message}")
                try {
                    File(context.filesDir.parent, "shared_prefs/$name.xml").delete()
                } catch (_: Exception) {}
                buildPrefs(context, name)
            }
            else -> throw e
        }
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
