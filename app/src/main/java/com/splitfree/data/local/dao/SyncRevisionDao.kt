package com.splitfree.data.local.dao

import androidx.room.Dao
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface SyncRevisionDao {
    @Query("SELECT COALESCE((SELECT revision FROM sync_revisions WHERE groupId = :groupId), 0)")
    fun observeRevision(groupId: String): Flow<Long>
}
