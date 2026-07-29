package com.oruke.tagloom.sync.http.jvm

import com.oruke.tagloom.core.model.WorkspaceId
import com.oruke.tagloom.core.protocol.CanonicalJson
import com.oruke.tagloom.core.sync.BlindSyncArtifact
import com.oruke.tagloom.core.sync.LocalBlindArtifactReplicaPort
import com.oruke.tagloom.core.sync.SyncArtifactIntegrityService
import com.oruke.tagloom.core.sync.SyncArtifactKind
import com.oruke.tagloom.filestore.jvm.JvmDirectoryDurability
import com.oruke.tagloom.filestore.jvm.JvmDirectoryDurabilityFactory
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.util.UUID
import java.util.stream.Collectors
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** 表示本地非 Change 密文副本损坏或身份不一致。 */
class BlindArtifactReplicaCorruptionException : IllegalStateException("本地加密 artifact 副本损坏")

/**
 * 在工作区派生同步缓存中按内容 ID 保存 Snapshot、Blob 与 Manifest 密文包装。
 *
 * 这些文件不是权威快照；远端 Snapshot 只有通过应用服务 AEAD/schema 校验并生成本地事务后才能进入权威目录。
 */
class JvmFileBlindArtifactReplica(
    workspaceRoot: Path,
    /** 当前工作区随机标识。 */
    private val workspaceId: WorkspaceId,
    /** 密文字节与公开描述符完整性服务。 */
    private val integrityService: SyncArtifactIntegrityService,
    /** 跨平台目录元数据刷新能力。 */
    private val directoryDurability: JvmDirectoryDurability = JvmDirectoryDurabilityFactory.create(),
    /** 执行阻塞文件操作的调度器。 */
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
) : LocalBlindArtifactReplicaPort {
    /** 与权威对象目录分离、可删除重建的同步缓存根目录。 */
    private val artifactRoot: Path = workspaceRoot.toAbsolutePath().normalize().resolve(".sync/artifacts")

    /** 串行化当前进程内扫描、读取和写入。 */
    private val accessMutex: Mutex = Mutex()

    init {
        Files.createDirectories(artifactRoot)
    }

    /**
     * 保存当前设备准备上传的已签名端到端密文。
     *
     * @param artifact 已完成内容寻址和设备签名的非 Change 载荷。
     */
    suspend fun saveLocalArtifact(artifact: BlindSyncArtifact) {
        saveVerified(artifact)
    }

    /**
     * 扫描并验证指定类型的全部本地内容 ID。
     *
     * @param kind 非 Change 密文载荷类型。
     * @return 按字典序稳定排列的内容 ID。
     */
    override suspend fun listArtifactIds(kind: SyncArtifactKind): List<String> = withContext(dispatcher) {
        accessMutex.withLock {
            requireNonChangeKind(kind)
            listVerified(kind).map(BlindSyncArtifact::artifactId).sorted()
        }
    }

    /**
     * 读取并验证指定本地密文载荷。
     *
     * @param kind 非 Change 密文载荷类型。
     * @param artifactId 内容 ID。
     * @return 不存在时为空；存在但损坏时抛出异常。
     */
    override suspend fun loadArtifact(
        kind: SyncArtifactKind,
        artifactId: String,
    ): BlindSyncArtifact? = withContext(dispatcher) {
        accessMutex.withLock {
            requireNonChangeKind(kind)
            val path = artifactPath(kind, artifactId)
            if (!Files.exists(path)) null else readVerified(path, kind) ?: throw BlindArtifactReplicaCorruptionException()
        }
    }

    /**
     * 幂等保存已由同步应用服务验证的远端密文。
     *
     * @param artifact 待保存非 Change 载荷。
     */
    override suspend fun saveRemoteArtifact(artifact: BlindSyncArtifact) {
        saveVerified(artifact)
    }

    /**
     * 校验公开身份与内容寻址后原子保存密文包装。
     *
     * @param artifact 本地或远端非 Change 载荷。
     */
    private suspend fun saveVerified(artifact: BlindSyncArtifact): Unit = withContext(dispatcher) {
        accessMutex.withLock {
            val kind = artifact.descriptor.kind
            requireNonChangeKind(kind)
            require(artifact.descriptor.workspaceId == workspaceId) { "artifact 工作区身份不匹配" }
            if (!integrityService.verify(artifact)) throw BlindArtifactReplicaCorruptionException()
            val target = artifactPath(kind, artifact.artifactId)
            if (Files.exists(target)) {
                val existing = readVerified(target, kind) ?: throw BlindArtifactReplicaCorruptionException()
                if (existing != artifact) throw BlindArtifactReplicaCorruptionException()
                return@withLock
            }

            Files.createDirectories(target.parent)
            val temporary = target.resolveSibling(".${target.fileName}.${UUID.randomUUID()}.tmp")
            val bytes = CanonicalJson.encodeToString(BlindSyncArtifact.serializer(), artifact).encodeToByteArray()
            try {
                writeForced(temporary, bytes)
                Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE)
                directoryDurability.force(target.parent)
            } finally {
                bytes.fill(0)
                Files.deleteIfExists(temporary)
            }
        }
    }

    /**
     * 扫描并验证一种非 Change 载荷。
     *
     * @param kind 密文载荷类型。
     * @return 按路径排列的有效载荷。
     */
    private suspend fun listVerified(kind: SyncArtifactKind): List<BlindSyncArtifact> {
        val directory = kindDirectory(kind)
        if (!Files.exists(directory)) return emptyList()
        val paths = Files.walk(directory).use { stream ->
            stream.filter(Files::isRegularFile)
                .filter { path -> path.fileName.toString().endsWith(ARTIFACT_EXTENSION) }
                .sorted()
                .collect(Collectors.toList())
        }
        return paths.map { path -> readVerified(path, kind) ?: throw BlindArtifactReplicaCorruptionException() }
    }

    /**
     * 解码并验证一个本地密文包装。
     *
     * @param path 已限定在派生同步缓存中的文件。
     * @param kind 路径预期载荷类型。
     * @return 完整性有效的载荷，否则为空。
     */
    private suspend fun readVerified(
        path: Path,
        kind: SyncArtifactKind,
    ): BlindSyncArtifact? {
        val artifact = runCatching {
            CanonicalJson.decodeFromString(BlindSyncArtifact.serializer(), Files.readString(path))
        }.getOrNull() ?: return null
        return artifact.takeIf { candidate ->
            candidate.descriptor.workspaceId == workspaceId &&
                candidate.descriptor.kind == kind &&
                integrityService.verify(candidate)
        }
    }

    /**
     * 强制写入新临时文件。
     *
     * @param path 唯一同目录临时路径。
     * @param bytes 待写入规范 JSON 字节。
     */
    private fun writeForced(
        path: Path,
        bytes: ByteArray,
    ) {
        FileChannel.open(path, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE).use { channel ->
            val buffer = ByteBuffer.wrap(bytes)
            while (buffer.hasRemaining()) channel.write(buffer)
            channel.force(true)
        }
    }

    /**
     * 生成内容寻址缓存路径并拒绝目录逃逸。
     *
     * @param kind 非 Change 载荷类型。
     * @param artifactId SHA-256 内容 ID。
     * @return 归一化绝对路径。
     */
    private fun artifactPath(
        kind: SyncArtifactKind,
        artifactId: String,
    ): Path {
        require(CONTENT_ID_PATTERN.matches(artifactId)) { "artifact 内容 ID 格式无效" }
        val result = kindDirectory(kind).resolve(artifactId.take(2)).resolve("$artifactId$ARTIFACT_EXTENSION").normalize()
        check(result.startsWith(artifactRoot)) { "artifact 路径越界" }
        return result
    }

    /**
     * 返回一种载荷的派生缓存目录。
     *
     * @param kind 非 Change 载荷类型。
     * @return 载荷类型目录。
     */
    private fun kindDirectory(kind: SyncArtifactKind): Path = artifactRoot.resolve(kind.name.lowercase())

    /**
     * 拒绝把 Change 放入不具备父边语义的通用缓存。
     *
     * @param kind 待校验载荷类型。
     */
    private fun requireNonChangeKind(kind: SyncArtifactKind) {
        require(kind != SyncArtifactKind.CHANGE) { "Change 必须使用 Change DAG 副本" }
    }

    private companion object {
        /** 本地密文包装文件扩展名。 */
        const val ARTIFACT_EXTENSION: String = ".json"

        /** SHA-256 小写十六进制格式。 */
        val CONTENT_ID_PATTERN: Regex = Regex("[a-f0-9]{64}")
    }
}
