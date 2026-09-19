package com.splitfree.data.repository

import com.splitfree.data.local.dao.ControlOperationDao
import com.splitfree.data.local.entities.ControlOperationEntity
import com.splitfree.domain.repository.ControlOperation
import com.splitfree.domain.repository.ControlOperationJournalContract
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ControlOperationJournal @Inject constructor(private val dao: ControlOperationDao) :
    ControlOperationJournalContract {
    override suspend fun get(id: String): ControlOperation? = dao.get(id)?.toOperation()

    override suspend fun getAll(kind: String): List<ControlOperation> = dao.getAll(kind).map { it.toOperation() }

    override suspend fun insert(operation: ControlOperation) = dao.insert(
        ControlOperationEntity(operation.id, operation.kind, operation.intentJson, operation.preparedJson)
    )

    override suspend fun prepare(id: String, preparedJson: String) {
        check(dao.prepare(id, preparedJson) == 1 || dao.get(id)?.preparedJson == preparedJson) {
            "Prepared control operation cannot be replaced"
        }
    }

    override suspend fun rebase(id: String, expectedIntentJson: String, intentJson: String) {
        check(dao.rebase(id, expectedIntentJson, intentJson) == 1 || dao.get(id)?.intentJson == intentJson) {
            "Control operation intent cannot be rebased once prepared"
        }
    }

    override suspend fun amend(id: String, expectedPreparedJson: String, preparedJson: String) {
        check(dao.amend(id, expectedPreparedJson, preparedJson) == 1 || dao.get(id)?.preparedJson == preparedJson) {
            "Prepared control operation changed concurrently"
        }
    }

    override suspend fun move(operation: ControlOperation, id: String, kind: String) {
        check(
            dao.move(operation.id, operation.kind, operation.intentJson, operation.preparedJson, id, kind) == 1 ||
                (dao.get(operation.id) == null && get(id) == operation.copy(id = id, kind = kind))
        ) { "Control operation changed during identity reconciliation" }
    }

    override suspend fun complete(id: String) = dao.delete(id)

    private fun ControlOperationEntity.toOperation() = ControlOperation(id, kind, intentJson, preparedJson)
}
