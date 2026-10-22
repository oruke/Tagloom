package com.oruke.tagloom.core.transaction

import com.oruke.tagloom.core.model.Note
import com.oruke.tagloom.core.model.ObjectSnapshot
import com.oruke.tagloom.core.sync.ObjectChange
import com.oruke.tagloom.core.workspace.ManifestObjectRecord

/** 多 Note 原子确认结果，供创建链接与来源引用等跨对象操作复用。 */
data class NoteBatchTransactionResult(
    /** 按请求顺序同时确认的 Note 快照。 */
    val snapshots: List<ObjectSnapshot<Note>>,
    /** 同一事务中的 Change 节点。 */
    val changes: List<ObjectChange>,
    /** 唯一确认点的认证 manifest。 */
    val encryptedManifest: ByteArray,
    /** 发布与派生更新的可观察结果。 */
    val commit: TransactionCommitResult,
)

/** 单个 Note 在发布前生成的加密事务材料，仅在事务服务内使用。 */
internal data class PreparedNoteSnapshot(
    /** 待确认快照。 */
    val snapshot: ObjectSnapshot<Note>,
    /** 对应 Change。 */
    val change: ObjectChange,
    /** 新快照、Change 和可选审计的 manifest 记录。 */
    val records: List<ManifestObjectRecord>,
    /** 与记录一一对应的密文。 */
    val artifacts: List<EncryptedTransactionArtifact>,
)
