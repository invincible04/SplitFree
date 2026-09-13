package com.splitfree.domain.usecase.group

import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Lock order: control operation -> repository group lock -> Room transaction.
 * Never enter a control use case while holding a repository group lock or Room transaction.
 * Shared across use-case instances and both rotation and identity revocation.
 */
@Singleton
class ControlOperationLock @Inject constructor() {
    private val mutex = Mutex()

    suspend fun <T> withLock(block: suspend () -> T): T = mutex.withLock { block() }
}
