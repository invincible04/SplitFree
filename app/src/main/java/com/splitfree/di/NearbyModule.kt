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

/** Nearby session engine bindings: Google Nearby Connections as the transport, Room as the store. */
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
