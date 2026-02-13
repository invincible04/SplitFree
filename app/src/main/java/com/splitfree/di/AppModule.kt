package com.splitfree.di

import android.content.Context
import androidx.room.Room
import com.splitfree.data.local.AppDatabase
import com.splitfree.data.local.EventDao
import com.splitfree.data.local.GroupDao
import com.splitfree.data.local.OutboxDao
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AppModule {
    @Provides
    @Singleton
    fun provideDatabase(
        @ApplicationContext context: Context,
    ): AppDatabase =
        Room
            .databaseBuilder(context, AppDatabase::class.java, "splitfree.db")
            .addMigrations(AppDatabase.MIGRATION_1_2, AppDatabase.MIGRATION_2_3, AppDatabase.MIGRATION_3_4)
            .build()

    @Provides fun provideEventDao(db: AppDatabase): EventDao = db.eventDao()

    @Provides fun provideGroupDao(db: AppDatabase): GroupDao = db.groupDao()

    @Provides fun provideOutboxDao(db: AppDatabase): OutboxDao = db.outboxDao()
}
