package com.oruke.tagloom.sync.http.jvm

import com.oruke.tagloom.core.model.ObjectId
import com.oruke.tagloom.core.model.WorkspaceId
import com.oruke.tagloom.core.protocol.CanonicalJson
import com.oruke.tagloom.core.sync.BlindSyncArtifact
import com.oruke.tagloom.core.sync.LocalChangeReplicaPort
import com.oruke.tagloom.core.sync.ObjectChangeHeads
import com.oruke.tagloom.core.sync.SyncArtifactIntegrityService
import com.oruke.tagloom.core.sync.SyncArtifactKind
import com.oruke.tagloom.filesystem.jvm.JvmDirectoryDurability
import com.oruke.tagloom.filesystem.jvm.JvmDirectoryDurabilityFactory
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.FileAlreadyExistsException
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.util.UUID

/** 表示本地加密 Change 文件内容寻址或公开完整性校验失败。 */
class ChangeReplicaCorruptionException : IllegalStateException("本地加密 Change 副本损坏")

/**
 * 使用工作区 `changes` 目录保存并验证内容寻址加密 Change。
 *
 * @param workspaceRoot 当前工作区根目录。
 * @param workspaceId 当前工作区随机标识。
 * @param integrityService 密文字节与公开描述符完整性服务。
 * @param directoryDurability 跨平台目录元数据刷新能力。
 * @param dispatcher 执行阻塞文件操作的调度器。
 */
class JvmFileChangeReplica(
    workspaceRoot: Path,
    /** 当前工作区随机标识。 */
    private val workspaceId: WorkspaceId,
    /** 密文字节与公开描述符完整性服务。 */
    private val integrityService: SyncArtifactIntegrityService,
    /** 跨平台目录元数据刷新能力。 */
    private val directoryDurability: JvmDirectoryDurability = JvmDirectoryDurabilityFactory.create(),
    /** 执行阻塞文件操作的调度器。 */
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
) : LocalChangeReplicaPort {
    /** 归一化且固定在工作区下的同步副本根目录。 */
    private val workspaceRoot: Path = workspaceRoot.toAbsolutePath().normalize()

    /** 归一化且固定在工作区下的 Change 内容寻址根目录。 */
    private val changeRoot: Path = workspaceRoot.resolve("changes")

    /** 串行化本地 Change 文件的扫描、读取与写入。 */
    private val accessMutex: Mutex = Mutex()

    init {
        JvmSyncPathSecurity.ensureDirectory(workspaceRoot)
        JvmSyncPathSecurity.ensureDirectory(changeRoot)
    }

    /**
     * 保存由当前设备创建、准备参与同步的加密 Change。
     *
     * @param artifact 已完成 AEAD 信封加密的 Change。
     */
    suspend fun saveLocalChange(artifact: BlindSyncArtifact) {
        saveVerified(artifact)
    }

    /**
     * 扫描全部已验证 Change 并按对象重算 heads。
     *
     * @return 按对象 ID 稳定排列的 heads。
     */
    override suspend fun listChangeHeads(): List<ObjectChangeHeads> = withContext(dispatcher) {
        accessMutex.withLock {
            val changes = listAllVerified()
            changes.groupBy { artifact -> requireNotNull(artifact.descriptor.objectId) }
                .toList()
                .sortedBy { (objectId, _) -> objectId.value }
                .map { (objectId, objectChanges) -> headsForObject(objectId, objectChanges) }
        }
    }

    /**
     * 验证本地是否存在且能够完整读取指定 Change。
     *
     * @param artifactId Change 内容 ID。
     * @return 文件存在且公开完整性有效时为真。
     */
    override suspend fun containsChange(artifactId: String): Boolean = withContext(dispatcher) {
        accessMutex.withLock {
            val path = artifactPath(artifactId)
            if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) {
                false
            } else {
                readVerified(path) ?: throw ChangeReplicaCorruptionException()
                true
            }
        }
    }

    /**
     * 读取并验证指定本地加密 Change。
     *
     * @param artifactId Change 内容 ID。
     * @return 不存在时为空；存在但损坏时抛出异常。
     */
    override suspend fun loadChange(artifactId: String): BlindSyncArtifact? = withContext(dispatcher) {
        accessMutex.withLock {
            val path = artifactPath(artifactId)
            if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) {
                null
            } else {
                readVerified(path) ?: throw ChangeReplicaCorruptionException()
            }
        }
    }

    /**
     * 幂等保存已经由同步服务验证的远端加密 Change。
     *
     * @param artifact 待保存 Change。
     */
    override suspend fun saveRemoteChange(artifact: BlindSyncArtifact) {
        saveVerified(artifact)
    }

    /**
     * 校验身份和内容寻址后，通过 force 与原子移动保存 Change。
     *
     * @param artifact 本地或远端加密 Change。
     */
    private suspend fun saveVerified(artifact: BlindSyncArtifact): Unit = withContext(dispatcher) {
        accessMutex.withLock {
            require(artifact.descriptor.workspaceId == workspaceId) { "Change 工作区身份不匹配" }
            require(artifact.descriptor.kind == SyncArtifactKind.CHANGE) { "Change 副本不能保存其他载荷类型" }
            if (!integrityService.verify(artifact)) {
                throw ChangeReplicaCorruptionException()
            }
            val target = artifactPath(artifact.artifactId)
            if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
                val existing = readVerified(target) ?: throw ChangeReplicaCorruptionException()
                if (existing != artifact) {
                    throw ChangeReplicaCorruptionException()
                }
                return@withLock
            }

            JvmSyncPathSecurity.ensureDirectory(requireNotNull(target.parent))
            val temporary = target.resolveSibling(".${target.fileName}.${UUID.randomUUID()}.tmp")
            val bytes = CanonicalJson.encodeToString(BlindSyncArtifact.serializer(), artifact).encodeToByteArray()
            try {
                writeForced(temporary, bytes)
                try {
                    Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE)
                } catch (_: FileAlreadyExistsException) {
                    // 其他进程可能刚刚写入同一内容 ID；重新认证后把该竞争视为幂等成功。
                    val existing = readVerified(target)
                    if (existing != artifact) throw ChangeReplicaCorruptionException()
                    return@withLock
                }
                directoryDurability.force(target.parent)
            } finally {
                bytes.fill(0)
                Files.deleteIfExists(temporary)
            }
        }
    }

    /**
     * 为单个对象计算未被同对象子节点引用的 heads。
     *
     * @param objectId 当前对象。
     * @param changes 当前对象全部 Change。
     * @return 稳定排序的 heads。
     */
    private fun headsForObject(
        objectId: ObjectId,
        changes: List<BlindSyncArtifact>,
    ): ObjectChangeHeads {
        val ids = changes.map(BlindSyncArtifact::artifactId).toSet()
        val referenced = changes.flatMap { artifact -> artifact.descriptor.parentArtifactIds }.toSet()
        return ObjectChangeHeads(objectId, ids.filterNot(referenced::contains).sorted())
    }

    /**
     * 扫描并验证本地全部 Change。
     *
     * @return 按路径稳定排列的有效 Change。
     */
    private suspend fun listAllVerified(): List<BlindSyncArtifact> {
        val paths = JvmSyncPathSecurity.listRegularFiles(changeRoot)
            .filter { path -> path.fileName.toString().endsWith(ARTIFACT_EXTENSION) }
        return paths.map { path -> readVerified(path) ?: throw ChangeReplicaCorruptionException() }
    }

    /**
     * 解码并验证单个本地 Change 文件。
     *
     * @param path 已限定在 Change 根目录下的文件。
     * @return 完整性有效的 Change，否则为空。
     */
    private suspend fun readVerified(path: Path): BlindSyncArtifact? {
        if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) {
            return null
        }
        JvmSyncPathSecurity.requireRegularFile(path)
        val artifact = runCatching {
            CanonicalJson.decodeFromString(BlindSyncArtifact.serializer(), Files.readString(path))
        }.getOrNull() ?: return null
        return artifact.takeIf { candidate ->
            candidate.descriptor.workspaceId == workspaceId &&
                candidate.descriptor.kind == SyncArtifactKind.CHANGE &&
                integrityService.verify(candidate)
        }
    }

    /**
     * 写入临时文件并强制刷新内容与元数据。
     *
     * @param path 唯一同目录临时路径。
     * @param bytes 待写入 UTF-8 JSON 字节。
     */
    private fun writeForced(
        path: Path,
        bytes: ByteArray,
    ) {
        FileChannel.open(path, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE).use { channel ->
            val buffer = ByteBuffer.wrap(bytes)
            while (buffer.hasRemaining()) {
                channel.write(buffer)
            }
            channel.force(true)
        }
    }

    /**
     * 生成内容寻址 Change 路径并验证没有逃逸工作区。
     *
     * @param artifactId SHA-256 内容 ID。
     * @return `changes/<前缀>/<id>.json` 绝对路径。
     */
    private fun artifactPath(artifactId: String): Path {
        require(CONTENT_ID_PATTERN.matches(artifactId)) { "Change 内容 ID 格式无效" }
        return JvmSyncPathSecurity.resolveWithin(
            root = workspaceRoot,
            relative = "changes/${artifactId.take(2)}/$artifactId$ARTIFACT_EXTENSION",
        )
    }

    private companion object {
        /** 本地 Change 载荷文件扩展名。 */
        const val ARTIFACT_EXTENSION: String = ".json"

        /** SHA-256 小写十六进制格式。 */
        val CONTENT_ID_PATTERN: Regex = Regex("[a-f0-9]{64}")
    }
}
