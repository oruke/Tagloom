package com.oruke.tagloom.core.sync

import com.oruke.tagloom.core.model.Note
import com.oruke.tagloom.core.model.ObjectSnapshot

/** 描述通过全部公开、签名、AEAD 与 schema 校验的 Change artifact。 */
data class AuthenticatedObjectChangeArtifact(
    /** 原始端到端密文同步包装。 */
    val artifact: BlindSyncArtifact,
    /** 从认证明文解析得到的逻辑 Change。 */
    val change: ObjectChange,
)

/** 描述通过全部公开、签名、AEAD 与 schema 校验的 Note Snapshot artifact。 */
data class AuthenticatedNoteSnapshotArtifact(
    /** 原始端到端密文同步包装。 */
    val artifact: BlindSyncArtifact,
    /** 从认证明文解析得到的 Note 快照副本。 */
    val snapshot: ObjectSnapshot<Note>,
)
