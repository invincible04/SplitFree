package com.splitfree.di

import com.splitfree.sync.worker.OutboxDrainScheduler
import com.splitfree.sync.worker.WorkManagerOutboxDrainScheduler
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class SyncModule {
    @Binds
    @Singleton
    abstract fun bindOutboxDrainScheduler(impl: WorkManagerOutboxDrainScheduler): OutboxDrainScheduler
}
