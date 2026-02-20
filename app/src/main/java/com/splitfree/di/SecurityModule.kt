package com.splitfree.di

import android.content.Context
import com.splitfree.data.util.KeystoreEncryptedStorage
import com.splitfree.domain.repository.SecureStorage
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Named
import javax.inject.Singleton

/** Provides Android Keystore-backed [SecureStorage] instances for sensitive data. */
@Module
@InstallIn(SingletonComponent::class)
object SecurityModule {
    @Provides
    @Singleton
    @Named("identity")
    fun provideIdentityStorage(@ApplicationContext context: Context): SecureStorage =
        KeystoreEncryptedStorage(context, "splitfree_identity", "splitfree_identity_key", resetOnCorruption = false)

    @Provides
    @Singleton
    @Named("groupKeys")
    fun provideGroupKeysStorage(@ApplicationContext context: Context): SecureStorage =
        KeystoreEncryptedStorage(context, "splitfree_group_keys", "splitfree_group_keys_key", resetOnCorruption = true)
}
