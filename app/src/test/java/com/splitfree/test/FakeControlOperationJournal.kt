package com.splitfree.test

import com.splitfree.domain.repository.ControlOperation
import com.splitfree.domain.repository.ControlOperationJournalContract

class FakeControlOperationJournal : ControlOperationJournalContract {
    private val operations = mutableMapOf<String, ControlOperation>()

    override suspend fun get(id: String): ControlOperation? = operations[id]
    override suspend fun getAll(kind: String): List<ControlOperation> = operations.values.filter { it.kind == kind }
    override suspend fun insert(operation: ControlOperation) {
        check(operation.id !in operations)
        operations[operation.id] = operation
    }
    override suspend fun prepare(id: String, preparedJson: String) {
        val operation = checkNotNull(operations[id])
        check(operation.preparedJson == null || operation.preparedJson == preparedJson)
        operations[id] = operation.copy(preparedJson = preparedJson)
    }
    override suspend fun rebase(id: String, expectedIntentJson: String, intentJson: String) {
        val operation = checkNotNull(operations[id])
        check(operation.preparedJson == null && operation.intentJson == expectedIntentJson)
        operations[id] = operation.copy(intentJson = intentJson)
    }
    override suspend fun amend(id: String, expectedPreparedJson: String, preparedJson: String) {
        val operation = checkNotNull(operations[id])
        check(operation.preparedJson == expectedPreparedJson)
        operations[id] = operation.copy(preparedJson = preparedJson)
    }
    override suspend fun complete(id: String) {
        operations.remove(id)
    }
}
