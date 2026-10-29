package com.oruke.tagloom.workspace

import com.oruke.tagloom.core.crypto.*
import com.oruke.tagloom.core.model.*
import com.oruke.tagloom.core.protocol.CanonicalJson
import com.oruke.tagloom.core.sync.*
import com.oruke.tagloom.core.transaction.*
import com.oruke.tagloom.core.workspace.*
import com.oruke.tagloom.filesystem.jvm.crypto.LibsodiumCryptoProvider
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.*
import java.nio.file.Path

/** 在工作区唯一写入锁内把认证副本合并为单个原子业务事务。 */
internal class DesktopReplicaTransaction(
    /** 会话持有唯一主密钥、写入租约及确认点。 */
    private val session: DesktopWorkspaceSession,
) {
    /** 复用当前平台的随机信封和摘要实现。 */
    private val crypto = LibsodiumCryptoProvider()
    /** 与普通写入相同的信封加密服务。 */
    private val envelopes = EnvelopeCryptoService(crypto)
    /** 本事务将一起确认的密文对象，路径唯一。 */
    private val artifacts = linkedMapOf<WorkspaceRelativePath, EncryptedTransactionArtifact>()
    /** 与密文同事务确认的目录记录。 */
    private val records = linkedMapOf<WorkspaceRelativePath, ManifestObjectRecord>()

    /**
     * 在下载完成后重新读取最新本地状态，避免网络等待期间的新编辑被覆盖。
     * @param archive 已通过网络摘要校验的服务器密文确认点。
     * @return 本地权威确认点是否发生变化。
     */
    suspend fun merge(archive: Path): Boolean = session.sessionMutex.withLock {
        session.requireWritable()
        crypto.initialize()
        val codec = DesktopReplicaCodec(session)
        val local = codec.local()
        val remote = codec.remote(archive)
        val known = (local.changes + remote.changes).groupBy { it.changeId }.map { (_, candidates) ->
            require(candidates.all { it == candidates.first() }) { "sync_integrity_failed" }
            candidates.first()
        }
        session.hybridClock.observe(known.map { it.hybridLogicalTime })
        val now = session.timestampProvider()
        val plan = WorkspaceReplicaCatalogConflictResolver.plan(local.objects, remote.objects, known, now) { kind, id, left, right ->
            hash("${kind.name}\n${id.value}\n${listOf(left?.value.orEmpty(), right?.value.orEmpty()).sorted().joinToString("\n")}".encodeToByteArray())
        }
        // 阶段 1：整批 schema、引用和对象结构预验证；失败时不产生任何 staging。
        WorkspaceReplicaCatalogValidator.validate(plan.objects.map { WorkspaceReplicaObject(it.kind, it.snapshot) })
        val localByPath = local.manifest.objects.associateBy { it.path }
        val remoteById = remote.manifest.objects.associateBy { it.kind to it.objectId }
        for (record in remote.manifest.objects.filter { it.kind in setOf(ManifestObjectKind.CHANGE, ManifestObjectKind.HISTORY, ManifestObjectKind.BLOB) }) {
            val existing = localByPath[record.path]
            if (existing == null) adopt(record, remote)
            else if (existing.encryptedSha256 != record.encryptedSha256 && record.kind == ManifestObjectKind.BLOB) {
                error("sync_integrity_failed")
            } else if (existing.encryptedSha256 != record.encryptedSha256 && record.path.value.startsWith(REPLICA_HISTORY_PREFIX)) {
                // 同一冲突的字段处理结果取并集，不能用整条记录二选一丢弃另一设备已确认的字段。
                val old = readReplicaConflict(session, existing, local.ciphertext.getValue(existing.path))
                val incoming = readReplicaConflict(session, record, remote.ciphertext.getValue(record.path))
                val progress = WorkspaceReplicaConflictProgress.merge(old, incoming, known)
                if (progress != old) addEncrypted(record.path, ManifestObjectKind.HISTORY, progress.conflictId,
                    maxOf(existing.revision, record.revision), "audit-record", WrappingKeyPurpose.AUDIT,
                    WorkspaceReplicaConflict.serializer(), progress)
            }
        }
        // 阶段 2：先准备完整对象并集，所有父 Change 与新 Merge 结果在同一事务发布。
        val transactionId = TransactionId(session.idFactory())
        for (decision in plan.objects) {
            currentCoroutineContext().ensureActive()
            when (decision.action) {
                WorkspaceReplicaAction.KEEP_LOCAL -> Unit
                WorkspaceReplicaAction.FAST_FORWARD -> adopt(remoteById.getValue(decision.kind to decision.objectId.value), remote)
                WorkspaceReplicaAction.MERGE -> {
                    val previous = local.objects.single { it.kind == decision.kind && it.snapshot.header.objectId == decision.objectId }.snapshot
                    val result = prepareVersion(decision.kind, decision.snapshot, previous,
                        decision.parentChangeIds, transactionId, now)
                    for (conflict in decision.conflicts) {
                        val path = WorkspaceRelativePath("$REPLICA_HISTORY_PREFIX${conflict.conflictId}.audit.enc")
                        if (path !in localByPath && path !in records) addEncrypted(path, ManifestObjectKind.HISTORY,
                            conflict.conflictId, protocolRevision(result.header.revision), "audit-record", WrappingKeyPurpose.AUDIT,
                            WorkspaceReplicaConflict.serializer(), conflict)
                    }
                }
            }
        }
        if (artifacts.isEmpty()) return@withLock false
        commit(transactionId, maxOf(local.formatVersion, remote.formatVersion))
        true
    }

    /**
     * 将用户选中的字段应用到当前快照，旧格式冲突才恢复整版本，并原子确认处理标记。
     * @param conflict 待处理的认证历史。
     * @param selected 用户明确选择的完整旧快照。
     * @param expectedRevision 用户审阅时的当前 revision。
     * @param fieldId 指定字段身份；为空时处理全部未解决字段，旧历史恢复完整版本。
     */
    suspend fun restore(conflict: WorkspaceReplicaConflict, selected: ObjectSnapshot<JsonElement>, expectedRevision: ObjectRevision,
        fieldId: String? = null) {
        session.requireWritable()
        crypto.initialize()
        val local = DesktopReplicaCodec(session).local()
        session.hybridClock.observe(local.changes.map { it.hybridLogicalTime })
        val current = local.objects.single { it.kind == conflict.kind && it.snapshot.header.objectId == conflict.objectId }.snapshot
        check(current.header.revision == expectedRevision && conflict.status == ConflictStatus.UNRESOLVED) { "revision_conflict" }
        val resolvedIds = WorkspaceReplicaConflictProgress.resolvedFieldIds(conflict)
        val fields = conflict.fields.filter { it.fieldId !in resolvedIds && (fieldId == null || it.fieldId == fieldId) }
        check(fieldId == null || fields.size == 1) { "revision_conflict" }
        check(conflict.fields.isEmpty() || fields.isNotEmpty() && !current.header.deleted) { "revision_conflict" }
        val side = if (selected == conflict.remoteSnapshot) MergeSide.REMOTE else MergeSide.LOCAL
        val candidate = if (conflict.fields.isEmpty()) selected else current.copy(payload = fields.fold(current.payload) { payload, field ->
            // 对当前确认内容打补丁，避免标题选择把其他 Block 或后续独立修改恢复成旧版本。
            ReplicaJsonFieldMerge.replace(payload, field.path, if (side == MergeSide.REMOTE) field.remoteValue else field.localValue)
        })
        val candidates = local.objects.map {
            if (it.kind == conflict.kind && it.snapshot.header.objectId == conflict.objectId) it.copy(snapshot = candidate) else it
        }
        WorkspaceReplicaCatalogValidator.validate(candidates)
        val transactionId = TransactionId(session.idFactory())
        val now = session.timestampProvider()
        val next = prepareVersion(conflict.kind, candidate, current, listOfNotNull(current.header.lastChangeId), transactionId, now)
        val resolved = WorkspaceReplicaConflictProgress.resolve(conflict, fields.map { it.fieldId }.toSet(), side,
            requireNotNull(next.header.lastChangeId), now)
        addEncrypted(WorkspaceRelativePath("$REPLICA_HISTORY_PREFIX${conflict.conflictId}.audit.enc"), ManifestObjectKind.HISTORY,
            conflict.conflictId, protocolRevision(next.header.revision), "audit-record", WrappingKeyPurpose.AUDIT,
            WorkspaceReplicaConflict.serializer(), resolved)
        commit(transactionId, local.formatVersion)
    }

    /**
     * 保留远端原作者及信封，只通过本地事务正式采用认证候选。
     * @param record 远端认证目录记录。
     * @param source 该记录对应的完整认证副本。
     */
    private fun adopt(record: ManifestObjectRecord, source: DesktopReplicaContent) {
        val bytes = source.ciphertext.getValue(record.path)
        artifacts[record.path] = EncryptedTransactionArtifact(record.path, bytes, record.encryptedSha256)
        records[record.path] = record
    }

    /**
     * 为合并或恢复创建携带完整 payload 的新 Change，后续设备可以重放真实共同基线。
     * @param kind 对象目录类别。
     * @param candidate 用户选择或自动合并的候选。
     * @param previous 当前已确认快照。
     * @param parents 新 Change 引用的全部 heads。
     * @param transactionId 本次原子事务身份。
     * @param now 当前 UTC 确认时间。
     * @return 已准备加密的完整新快照。
     */
    private suspend fun prepareVersion(kind: ManifestObjectKind, candidate: ObjectSnapshot<JsonElement>, previous: ObjectSnapshot<JsonElement>,
        parents: List<ChangeId>, transactionId: TransactionId, now: String): ObjectSnapshot<JsonElement> {
        val revision = ObjectRevision(session.actorId, session.nextActorSequence++)
        val changeId = ChangeId(hash("${transactionId.value}\n${candidate.header.objectId.value}\n${protocolRevision(revision)}".encodeToByteArray()))
        val change = ObjectChange(changeId, candidate.header.objectId, parents.distinct().sortedBy { it.value }, session.actorId,
            session.hybridClock.next(), previous.header.revision, transactionId,
            if (candidate.header.deleted) ChangeKind.DELETE else ChangeKind.MERGE,
            listOf(ChangeOperation("merge-replica-object", payload = candidate.payload)))
        val oldRecord = session.unlocked.payload.objects.single { it.kind == kind && it.objectId == candidate.header.objectId.value }
        val next = candidate.copy(header = candidate.header.copy(revision = revision, lastChangeId = changeId,
            previousSnapshotHash = oldRecord.encryptedSha256, createdAt = minOf(previous.header.createdAt, candidate.header.createdAt),
            updatedAt = now))
        addEncrypted(oldRecord.path, kind, candidate.header.objectId.value, protocolRevision(revision), replicaSnapshotType(kind),
            WrappingKeyPurpose.SNAPSHOT, ObjectSnapshot.serializer(JsonElement.serializer()), next)
        addEncrypted(WorkspaceRelativePath("changes/${changeId.value}.change.enc"), ManifestObjectKind.CHANGE, changeId.value,
            protocolRevision(revision), "object-change", WrappingKeyPurpose.CHANGE, ObjectChange.serializer(), change)
        return next
    }

    /**
     * 用随机 DEK/nonce 准备一个现有协议信封，明文只保留在受限内存作用域。
     * @param path 受控目标路径。
     * @param kind 目录类别。
     * @param objectId 信封与目录使用的不透明身份。
     * @param revision 认证修订。
     * @param objectType 现有信封业务类型。
     * @param purpose 密钥用途。
     * @param serializer 明文对象序列化协议。
     * @param value 待加密业务内容。
     */
    private suspend fun <T> addEncrypted(path: WorkspaceRelativePath, kind: ManifestObjectKind, objectId: String, revision: String,
        objectType: String, purpose: WrappingKeyPurpose, serializer: KSerializer<T>, value: T) {
        val plaintext = CanonicalJson.encodeToString(serializer, value).encodeToByteArray()
        val bytes = try {
            EncryptionEnvelopeCodec.encode(envelopes.encrypt(EnvelopeEncryptionRequest(
                EnvelopeIdentity(session.workspaceId, objectType, objectId, revision), session.unlocked.file.publicHeader.activeKeyId,
                purpose, plaintext), session.unlocked.workspaceMasterKey))
        } finally { plaintext.fill(0) }
        val sha = hash(bytes)
        artifacts[path] = EncryptedTransactionArtifact(path, bytes, sha)
        records[path] = ManifestObjectRecord(kind, objectId, revision, path, sha, session.unlocked.file.publicHeader.activeKeyId)
    }

    /**
     * 以本机解锁槽生成新 manifest，索引失败不会回滚已经确认的业务状态。
     * @param transactionId 原子事务身份。
     * @param minimumFormatVersion 所有候选要求的最低读取版本。
     */
    private suspend fun commit(transactionId: TransactionId, minimumFormatVersion: Int) {
        currentCoroutineContext().ensureActive()
        val next = session.manifestService.confirmTransaction(session.unlocked, transactionId, records.values.toList(), emptySet(), minimumFormatVersion)
        // 已进入 prepared 发布阶段后先完成本地事务再响应取消，避免留下与磁盘不一致的内存确认点。
        withContext(NonCancellable) {
            try {
                session.transactionCoordinator.commit(EncryptedTransactionPlan(transactionId, session.unlocked.payload.revision,
                    session.unlocked.payload.revision + 1, artifacts.values.toList(), next, hash(next)))
            } catch (error: Throwable) {
                val recovered = session.transactionCoordinator.recoverOnStartup()
                if (recovered.requiresReadOnlyMode) session.readOnly = true
                throw error
            } finally { session.refreshManifest() }
        }
    }

    /** @param bytes 待摘要的非持久明文或密文。 @return 不透明小写摘要。 */
    private fun hash(bytes: ByteArray): String = crypto.sha256(bytes).serverRecoveryHex()

    /** @param revision 完整对象修订。 @return 现有信封协议使用的修订文本。 */
    private fun protocolRevision(revision: ObjectRevision): String = "${revision.actorId.value}:${revision.sequence}"
}

/** 同步完整双版本使用独立历史路径，避免与旧字段级 ConflictRecord 协议混用。 */
internal const val REPLICA_HISTORY_PREFIX: String = "history/replicas/"
