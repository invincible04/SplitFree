package com.splitfree.di

import com.splitfree.data.ble.NearbySync
import com.splitfree.sync.nearby.NearbyTransport
import com.splitfree.sync.nearby.ReconciliationStore
import com.splitfree.sync.nearby.RoomReconciliationStore
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/** Singleton bindings for the Nearby transport and Room-backed reconciliation store. */
@Module
@InstallIn(SingletonComponent::class)
abstract class NearbyModule {
    @Binds
    @Singleton
    abstract fun bindNearbyTransport(impl: NearbySync): NearbyTransport

    @Binds
    @Singleton
    abstract fun bindReconciliationStore(impl: RoomReconciliationStore): ReconciliationStore
}
