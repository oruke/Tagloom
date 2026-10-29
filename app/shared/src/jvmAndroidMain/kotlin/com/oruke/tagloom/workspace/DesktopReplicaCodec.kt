package com.oruke.tagloom.workspace

import com.oruke.tagloom.core.crypto.*
import com.oruke.tagloom.core.model.*
import com.oruke.tagloom.core.protocol.CanonicalJson
import com.oruke.tagloom.core.sync.ObjectChange
import com.oruke.tagloom.core.sync.WorkspaceReplicaObject
import com.oruke.tagloom.core.transaction.WorkspaceRelativePath
import com.oruke.tagloom.core.workspace.*
import com.oruke.tagloom.filesystem.jvm.crypto.LibsodiumCryptoProvider
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.decodeFromJsonElement
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.io.InputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipFile

/** 只在解锁作用域内存在的认证副本；所有持久文件及传输字节始终为密文。 */
internal data class DesktopReplicaContent(
    /** 已通过主密钥认证的对象目录。 */
    val manifest: WorkspaceManifestPayload,
    /** 对象要求的最低客户端格式。 */
    val formatVersion: Int,
    /** 按受控相对路径索引的完整密文。 */
    val ciphertext: Map<WorkspaceRelativePath, ByteArray>,
    /** 已核对信封、快照头和目录身份的对象。 */
    val objects: List<WorkspaceReplicaObject>,
    /** 原始作者与父边均保留的领域 Change。 */
    val changes: List<ObjectChange>,
)

/** 在唯一会话锁内认证本地或下载的完整密文确认点，不提取 ZIP 到业务目录。 */
internal class DesktopReplicaCodec(
    /** 主密钥与事务生命周期的唯一持有者。 */
    private val session: DesktopWorkspaceSession,
) {
    /** 当前平台的现有密码学实现，不引入新的算法。 */
    private val crypto = LibsodiumCryptoProvider()
    /** 与普通业务写入相同的信封认证。 */
    private val envelopes = EnvelopeCryptoService(crypto)

    /** @return 从当前已确认目录读取并认证的完整本地副本。 */
    suspend fun local(): DesktopReplicaContent {
        session.requireWritable()
        return decode(session.unlocked.payload, session.unlocked.file.publicHeader.manifestFormatVersion, ::readLocalCiphertext)
    }

    /** 重新打开或恢复后先观察已经认证的历史时间，避免第一次离线编辑落在父版本之前。 */
    suspend fun observeClock() {
        session.requireOpen()
        val history = session.unlocked.payload.copy(objects = session.unlocked.payload.objects.filter { it.kind == ManifestObjectKind.CHANGE })
        val decoded = decode(history, session.unlocked.file.publicHeader.manifestFormatVersion, ::readLocalCiphertext)
        session.hybridClock.observe(decoded.changes.map { it.hybridLogicalTime })
    }

    /** @param path 只允许来自已认证目录的受控路径。 @return 有界的本地密文。 */
    private fun readLocalCiphertext(path: WorkspaceRelativePath): ByteArray {
        val target = session.paths.target(path)
        require(Files.isRegularFile(target, NOFOLLOW_LINKS)) { "sync_integrity_failed" }
        require(Files.size(target) <= MAX_REPLICA_BYTES) { "sync_integrity_failed" }
        return Files.readAllBytes(target)
    }

    /**
     * 核对服务器副本的工作区、主密钥和全部对象；本机解锁槽不参与覆盖。
     * @param archive 已经通过传输大小及摘要校验的密文 ZIP。
     * @return 仅在当前会话内使用的认证对象与原始密文。
     */
    suspend fun remote(archive: Path): DesktopReplicaContent = ZipFile(archive.toFile()).use { zip ->
        val names = zip.entries().asSequence().map { it.name }.toList()
        require(names.distinct().size == names.size) { "sync_integrity_failed" }
        val manifestEntry = zip.getEntry("manifest.enc") ?: error("sync_integrity_failed")
        val bytes = zip.getInputStream(manifestEntry).use { readReplicaBytes(it, MAX_MANIFEST_BYTES) }
        require(bytes.size <= MAX_MANIFEST_BYTES) { "sync_integrity_failed" }
        val file = WorkspaceManifestCodec.decode(bytes)
        require(file.publicHeader.workspaceId == session.workspaceId) { "sync_workspace_mismatch" }
        if (file.publicHeader.activeKeyId != session.unlocked.file.publicHeader.activeKeyId) {
            error("sync_key_changed")
        }
        val manifest = session.manifestService.inspectWithMasterKey(bytes, session.unlocked.workspaceMasterKey)
        var remaining = MAX_REPLICA_BYTES
        decode(manifest, file.publicHeader.manifestFormatVersion) { path ->
            val entry = zip.getEntry(path.value) ?: error("sync_integrity_failed")
            require(!entry.isDirectory && remaining > 0) { "sync_integrity_failed" }
            zip.getInputStream(entry).use { input ->
                readReplicaBytes(input, remaining).also {
                    require(it.size <= remaining) { "sync_integrity_failed" }
                    remaining -= it.size
                }
            }
        }
    }

    /**
     * 认证每个记录并解码领域对象，所有网络或文件输入均在规划之前完成验证。
     * @param manifest 已认证的目录。
     * @param formatVersion 已认证的读取版本。
     * @param readCiphertext 只按 manifest 路径读取密文的受限入口。
     * @return 不含任何未确认对象的副本。
     */
    private suspend fun decode(
        manifest: WorkspaceManifestPayload,
        formatVersion: Int,
        readCiphertext: (WorkspaceRelativePath) -> ByteArray,
    ): DesktopReplicaContent {
        crypto.initialize()
        val ciphertext = linkedMapOf<WorkspaceRelativePath, ByteArray>()
        val objects = mutableListOf<WorkspaceReplicaObject>()
        val changes = mutableListOf<ObjectChange>()
        for (record in manifest.objects) {
            currentCoroutineContext().ensureActive()
            val bytes = readCiphertext(record.path)
            require(crypto.sha256(bytes).serverRecoveryHex() == record.encryptedSha256) { "sync_integrity_failed" }
            val envelope = EncryptionEnvelopeCodec.decode(bytes)
            val purpose = when (record.kind) {
                ManifestObjectKind.CHANGE -> WrappingKeyPurpose.CHANGE
                ManifestObjectKind.HISTORY -> WrappingKeyPurpose.AUDIT
                ManifestObjectKind.BLOB -> WrappingKeyPurpose.BLOB
                else -> WrappingKeyPurpose.SNAPSHOT
            }
            val plaintext = envelopes.decrypt(envelope, EnvelopeDecryptionContext(
                EnvelopeIdentity(session.workspaceId, envelope.header.objectType, record.objectId, record.revision),
                record.keyId, purpose,
            ), session.unlocked.workspaceMasterKey)
            try {
                when (record.kind) {
                    ManifestObjectKind.CHANGE -> {
                        require(envelope.header.objectType == "object-change") { "sync_integrity_failed" }
                        val change = CanonicalJson.decodeFromString(ObjectChange.serializer(), plaintext.decodeToString())
                        require(change.changeId.value == record.objectId) { "sync_integrity_failed" }
                        changes += change
                    }
                    ManifestObjectKind.BLOB, ManifestObjectKind.HISTORY -> Unit
                    else -> {
                        require(envelope.header.objectType == replicaSnapshotType(record.kind)) { "sync_integrity_failed" }
                        val snapshot = CanonicalJson.decodeFromString(ObjectSnapshot.serializer(JsonElement.serializer()), plaintext.decodeToString())
                        require(snapshot.header.objectId.value == record.objectId &&
                            snapshot.header.objectType.value == record.kind.name &&
                            "${snapshot.header.revision.actorId.value}:${snapshot.header.revision.sequence}" == record.revision &&
                            snapshot.header.schemaVersion in 1..2) { "sync_integrity_failed" }
                        if (record.kind == ManifestObjectKind.NOTE) {
                            val note = CanonicalJson.format.decodeFromJsonElement(Note.serializer(), snapshot.payload)
                            require(NoteValidator.validate(note).isEmpty()) { "sync_integrity_failed" }
                        }
                        objects += WorkspaceReplicaObject(record.kind, snapshot)
                    }
                }
                ciphertext[record.path] = bytes
            } finally { plaintext.fill(0) }
        }
        return DesktopReplicaContent(manifest, formatVersion, ciphertext, objects, changes)
    }

    companion object {
        /** 与现有服务器恢复传输相同的解压后上限，阻止 ZIP 膨胀。 */
        private const val MAX_REPLICA_BYTES = 512 * 1024 * 1024
        /** manifest 容器的现有认证读取上限。 */
        private const val MAX_MANIFEST_BYTES = 4 * 1024 * 1024
    }
}

/**
 * 将目录类别映射为现有信封协议，禁止从远端文件名猜测业务类型。
 * @param kind 权威目录的对象类别。
 * @return 当前业务快照的固定信封类型。
 */
internal fun replicaSnapshotType(kind: ManifestObjectKind): String = when (kind) {
    ManifestObjectKind.NOTE -> "note-snapshot"
    ManifestObjectKind.TAG -> "tag-snapshot"
    ManifestObjectKind.PROPERTY -> "property-snapshot"
    ManifestObjectKind.SAVED_QUERY -> "saved-query-snapshot"
    ManifestObjectKind.DATABASE -> "database-snapshot"
    ManifestObjectKind.VIEW -> "view-snapshot"
    else -> error("sync_object_kind_invalid")
}

/**
 * 使用 Android 最低版本已有的流 API 有界读取，不依赖较新平台的 readNBytes。
 * @param input 只包含密文的受限输入流。
 * @param limit 最大允许字节数，超过时立即拒绝该副本。
 * @return 有界完整密文字节。
 */
private fun readReplicaBytes(input: InputStream, limit: Int): ByteArray {
    val output = ByteArrayOutputStream()
    val buffer = ByteArray(64 * 1024)
    while (true) {
        val count = input.read(buffer)
        if (count < 0) break
        require(output.size().toLong() + count <= limit) { "sync_integrity_failed" }
        output.write(buffer, 0, count)
    }
    return output.toByteArray()
}
