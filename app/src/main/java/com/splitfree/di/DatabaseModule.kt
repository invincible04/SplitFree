package com.splitfree.di

import android.content.Context
import androidx.room.Room
import com.splitfree.data.local.AppDatabase
import com.splitfree.data.local.dao.EventDao
import com.splitfree.data.local.dao.GroupDao
import com.splitfree.data.local.dao.OutboxDao
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Database and DAO bindings.
 */
@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {

    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): AppDatabase = Room
        .databaseBuilder(context, AppDatabase::class.java, "splitfree.db")
        .build()

    @Provides fun provideEventDao(db: AppDatabase): EventDao = db.eventDao()

    @Provides fun provideGroupDao(db: AppDatabase): GroupDao = db.groupDao()

    @Provides fun provideOutboxDao(db: AppDatabase): OutboxDao = db.outboxDao()
}
