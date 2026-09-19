package com.splitfree.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import com.splitfree.data.local.entities.ControlOperationEntity

@Dao
interface ControlOperationDao {
    @Query("SELECT * FROM operation_journal WHERE id = :id")
    suspend fun get(id: String): ControlOperationEntity?

    @Query("SELECT * FROM operation_journal WHERE kind = :kind ORDER BY id")
    suspend fun getAll(kind: String): List<ControlOperationEntity>

    @Insert
    suspend fun insert(operation: ControlOperationEntity)

    @Query("UPDATE operation_journal SET preparedJson = :preparedJson WHERE id = :id AND preparedJson IS NULL")
    suspend fun prepare(id: String, preparedJson: String): Int

    /** Re-snapshots an intent that has prepared nothing yet; a prepared plan is never rebased. */
    @Query(
        "UPDATE operation_journal SET intentJson = :intentJson " +
            "WHERE id = :id AND intentJson = :expectedIntentJson AND preparedJson IS NULL"
    )
    suspend fun rebase(id: String, expectedIntentJson: String, intentJson: String): Int

    /** Compare-and-set; callers must preserve signed events when extending or upgrading a prepared plan. */
    @Query(
        "UPDATE operation_journal SET preparedJson = :preparedJson " +
            "WHERE id = :id AND preparedJson = :expectedPreparedJson"
    )
    suspend fun amend(id: String, expectedPreparedJson: String, preparedJson: String): Int

    @Query(
        "UPDATE operation_journal SET id = :newId, kind = :newKind " +
            "WHERE id = :id AND kind = :kind AND intentJson = :intentJson AND preparedJson IS :preparedJson"
    )
    suspend fun move(
        id: String,
        kind: String,
        intentJson: String,
        preparedJson: String?,
        newId: String,
        newKind: String
    ): Int

    @Query("DELETE FROM operation_journal WHERE id = :id")
    suspend fun delete(id: String)
}
