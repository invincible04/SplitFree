package com.splitfree.di

import com.splitfree.data.repository.ExpenseRepository
import com.splitfree.data.repository.GroupRepository
import com.splitfree.domain.repository.ExpenseRepositoryContract
import com.splitfree.domain.repository.GroupRepositoryContract
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

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
    abstract fun bindExpenseRepository(impl: ExpenseRepository): ExpenseRepositoryContract
}
