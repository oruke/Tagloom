package com.oruke.tagloom.filestore.jvm

import com.oruke.tagloom.core.model.TransactionId
import com.oruke.tagloom.core.transaction.DerivedStateUpdater

/** 测试中确认事务但不产生派生索引副作用。 */
internal object NoteMergeNoOpDerivedStateUpdater : DerivedStateUpdater {
    /**
     * 接收已确认事务。
     *
     * @param transactionId 已确认事务 ID。
     */
    override suspend fun update(transactionId: TransactionId) = Unit
}
