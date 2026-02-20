package com.splitfree.di

import com.splitfree.data.identity.IdentityManager
import com.splitfree.data.nostr.NostrClient
import com.splitfree.data.nostr.relay.Relay
import com.splitfree.data.repository.EventRepository
import com.splitfree.data.repository.ExpenseRepository
import com.splitfree.data.repository.GroupRepository
import com.splitfree.data.settings.UserPreferences
import com.splitfree.data.util.CompressionUtil
import com.splitfree.domain.repository.EventPublisherContract
import com.splitfree.domain.repository.EventRepositoryContract
import com.splitfree.domain.repository.ExpenseRepositoryContract
import com.splitfree.domain.repository.GroupRepositoryContract
import com.splitfree.domain.repository.IdentityContract
import com.splitfree.domain.repository.NostrClientContract
import com.splitfree.domain.repository.SettingsContract
import com.splitfree.domain.repository.SyncEngineContract
import com.splitfree.domain.util.CompressionProvider
import com.splitfree.sync.event.EventPublisher
import com.splitfree.sync.worker.SyncEngine
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton
import okhttp3.OkHttpClient

/**
 * Binds repository interfaces to their implementations (Dependency Inversion).
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class RepositoryModule {
    @Binds
    @Singleton
    abstract fun bindGroupRepository(impl: GroupRepository): GroupRepositoryContract

    @Binds
    @Singleton
    abstract fun bindEventRepository(impl: EventRepository): EventRepositoryContract

    @Binds
    @Singleton
    abstract fun bindExpenseRepository(impl: ExpenseRepository): ExpenseRepositoryContract

    @Binds
    @Singleton
    abstract fun bindNostrClient(impl: NostrClient): NostrClientContract

    @Binds
    @Singleton
    abstract fun bindEventPublisher(impl: EventPublisher): EventPublisherContract

    @Binds
    @Singleton
    abstract fun bindSettings(impl: UserPreferences): SettingsContract

    @Binds
    @Singleton
    abstract fun bindSyncEngine(impl: SyncEngine): SyncEngineContract

    @Binds
    @Singleton
    abstract fun bindIdentity(impl: IdentityManager): IdentityContract

    companion object {
        @Provides
        @Singleton
        fun provideCompressionProvider(): CompressionProvider = CompressionUtil

        @Provides
        @Singleton
        fun provideOkHttpClient(): OkHttpClient = Relay.sharedClient
    }
}
