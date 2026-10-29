package com.oruke.tagloom.workspace

import com.oruke.tagloom.core.crypto.*
import com.oruke.tagloom.core.markdown.MarkdownCodec
import com.oruke.tagloom.core.markdown.MarkdownExportMode
import com.oruke.tagloom.core.model.*
import com.oruke.tagloom.core.protocol.CanonicalJson
import com.oruke.tagloom.core.sync.ConflictStatus
import com.oruke.tagloom.core.sync.WorkspaceReplicaConflict
import com.oruke.tagloom.core.workspace.ManifestObjectRecord
import com.oruke.tagloom.filesystem.jvm.crypto.LibsodiumCryptoProvider
import com.oruke.tagloom.platform.WorkspaceSyncConflict
import kotlinx.serialization.json.*
import java.nio.file.Files

/**
 * 从权威目录中读取经过摘要和 AEAD 双重认证的完整冲突，拒绝读取目录外残留文件。
 * @param session 当前唯一已解锁会话。
 * @return 保留已处理记录的全部冲突历史。
 */
internal suspend fun loadReplicaConflicts(session: DesktopWorkspaceSession): List<WorkspaceReplicaConflict> {
    session.requireOpen()
    return session.unlocked.payload.objects.filter { it.path.value.startsWith(REPLICA_HISTORY_PREFIX) }
        .map { record -> readReplicaConflict(session, record, Files.readAllBytes(session.paths.target(record.path))) }
        .sortedWith(compareByDescending<WorkspaceReplicaConflict> { it.createdAt }.thenBy { it.conflictId })
}

/**
 * 使用目录声明的身份验证一个同步历史，短期明文字节始终清零。
 * @param session 当前持有主密钥的会话。
 * @param record 已认证目录记录。
 * @param bytes 完整密文对象。
 * @return 可供本机会话查看或恢复的完整冲突。
 */
internal suspend fun readReplicaConflict(session: DesktopWorkspaceSession, record: ManifestObjectRecord, bytes: ByteArray): WorkspaceReplicaConflict {
    val crypto = LibsodiumCryptoProvider()
    crypto.initialize()
    require(crypto.sha256(bytes).serverRecoveryHex() == record.encryptedSha256) { "sync_integrity_failed" }
    val plaintext = EnvelopeCryptoService(crypto).decrypt(EncryptionEnvelopeCodec.decode(bytes), EnvelopeDecryptionContext(
        EnvelopeIdentity(session.workspaceId, "audit-record", record.objectId, record.revision), record.keyId, WrappingKeyPurpose.AUDIT,
    ), session.unlocked.workspaceMasterKey)
    return try {
        CanonicalJson.decodeFromString(WorkspaceReplicaConflict.serializer(), plaintext.decodeToString()).also {
            require(it.conflictId == record.objectId) { "sync_integrity_failed" }
        }
    } finally { plaintext.fill(0) }
}

/**
 * 生成设置页使用的双版本预览，保留完整可读正文并绑定审阅时 revision。
 * @param session 当前已解锁会话。
 * @return 不脱离当前会话生命周期的冲突视图。
 */
internal suspend fun replicaConflictViews(session: DesktopWorkspaceSession): List<WorkspaceSyncConflict> {
    val propertyNames = session.schemaStore.load(session.unlocked).properties
        .associate { it.payload.id.value to it.payload.name }
    return loadReplicaConflicts(session).map { conflict ->
        val current = session.unlocked.payload.objects.single { it.kind == conflict.kind && it.objectId == conflict.objectId.value }
        WorkspaceSyncConflict(conflict.conflictId, conflict.objectId, conflict.kind.name,
            ObjectRevision(ActorId(current.revision.substringBeforeLast(':')), current.revision.substringAfterLast(':').toLong()),
            replicaVersionTitle(conflict.localSnapshot), replicaVersionContent(conflict.localSnapshot), conflict.localSnapshot.header.deleted,
            replicaVersionTitle(conflict.remoteSnapshot), replicaVersionContent(conflict.remoteSnapshot), conflict.remoteSnapshot.header.deleted,
            conflict.createdAt, conflict.status == ConflictStatus.RESOLVED, replicaFieldViews(conflict, propertyNames))
    }
}

/** @param snapshot 保留版本。 @return 版本自身的标题或目录名称，不以本机路径作为标签。 */
private fun replicaVersionTitle(snapshot: ObjectSnapshot<JsonElement>): String {
    val objectValue = snapshot.payload as? JsonObject
    return (objectValue?.get("title") as? JsonPrimitive)?.contentOrNull
        ?: (objectValue?.get("name") as? JsonPrimitive)?.contentOrNull.orEmpty()
}

/** @param snapshot 完整保留版本。 @return 笔记的完整 Markdown，目录对象则为可读字段内容。 */
private fun replicaVersionContent(snapshot: ObjectSnapshot<JsonElement>): String =
    if (snapshot.header.objectType == ObjectType.NOTE) {
        val note = CanonicalJson.format.decodeFromJsonElement(Note.serializer(), snapshot.payload)
        MarkdownCodec.export(note.copy(title = ""), MarkdownExportMode.TAGLOOM_EXTENDED).markdown
    } else {
        Json { prettyPrint = true }.encodeToString(JsonElement.serializer(), snapshot.payload)
    }
