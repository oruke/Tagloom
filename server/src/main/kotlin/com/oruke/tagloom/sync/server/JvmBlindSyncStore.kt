package com.oruke.tagloom.sync.server

import com.oruke.tagloom.core.model.ObjectId
import com.oruke.tagloom.core.model.WorkspaceId
import com.oruke.tagloom.core.protocol.CanonicalJson
import com.oruke.tagloom.core.sync.BlindSyncArtifact
import com.oruke.tagloom.core.sync.ObjectChangeHeads
import com.oruke.tagloom.core.sync.SyncArtifactKind
import com.oruke.tagloom.filestore.jvm.JvmDirectoryDurability
import com.oruke.tagloom.filestore.jvm.JvmDirectoryDurabilityFactory
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.util.UUID
import java.util.stream.Collectors

/**
 * 使用一内容一 JSON 文件实现可在启动时扫描恢复的盲同步存储。
 *
 * 文件只包含随机公开标识、DAG 父边和完整加密信封；不会保存标题、标签、属性名或原始文件名。
 *
 * @param rootDirectory 同步服务数据根目录。
 * @param directoryDurability 跨平台目录元数据刷新能力。
 * @param dispatcher 执行阻塞文件操作的协程调度器。
 */
class JvmBlindSyncStore(
    /** 同步服务数据根目录。 */
    private val rootDirectory: Path,
    /** 跨平台目录元数据刷新能力。 */
    private val directoryDurability: JvmDirectoryDurability = JvmDirectoryDurabilityFactory.create(),
    /** 执行阻塞文件操作的协程调度器。 */
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
) : BlindSyncStore {
    /** 串行化当前进程内的写入与扫描，避免读取半完成目录状态。 */
    private val accessMutex: Mutex = Mutex()

    init {
        Files.createDirectories(rootDirectory.toAbsolutePath().normalize())
    }

    /**
     * 通过同目录临时文件、force 与原子移动幂等保存密文载荷。
     *
     * @param artifact 已完成公开完整性验证的载荷。
     * @return 首次写入或完全一致重复。
     */
    override suspend fun put(artifact: BlindSyncArtifact): BlindSyncPutResult = withContext(dispatcher) {
        accessMutex.withLock {
            val target = artifactPath(artifact.descriptor.workspaceId, artifact.descriptor.kind, artifact.artifactId)
            if (Files.exists(target)) {
                val existing = readArtifact(target)
                if (existing == artifact) BlindSyncPutResult.DUPLICATE else throw BlindSyncContentConflictException()
            } else {
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
                BlindSyncPutResult.STORED
            }
        }
    }

    /**
     * 扫描 Change 文件并按对象重算当前 heads。
     *
     * @param workspaceId 随机工作区标识。
     * @return 按对象 ID 稳定排列的 heads。
     */
    override suspend fun listChangeHeads(workspaceId: WorkspaceId): List<ObjectChangeHeads> = withContext(dispatcher) {
        accessMutex.withLock {
            val changes = listArtifacts(workspaceId, SyncArtifactKind.CHANGE)
            changes.groupBy { artifact -> requireNotNull(artifact.descriptor.objectId) }
                .toList()
                .sortedBy { (objectId, _) -> objectId.value }
                .map { (objectId, objectChanges) -> headsForObject(objectId, objectChanges) }
        }
    }

    /**
     * 按请求顺序读取存在的 Change。
     *
     * @param workspaceId 随机工作区标识。
     * @param artifactIds 待读取内容 ID。
     * @return 实际存在且身份一致的 Change。
     */
    override suspend fun getChanges(
        workspaceId: WorkspaceId,
        artifactIds: List<String>,
    ): List<BlindSyncArtifact> = getArtifacts(workspaceId, SyncArtifactKind.CHANGE, artifactIds)

    /**
     * 扫描指定类型并返回稳定内容 ID 清单。
     *
     * @param workspaceId 随机工作区标识。
     * @param kind 密文载荷类型。
     * @return 按字典序排列的内容 ID。
     */
    override suspend fun listArtifactIds(
        workspaceId: WorkspaceId,
        kind: SyncArtifactKind,
    ): List<String> = withContext(dispatcher) {
        accessMutex.withLock {
            listArtifacts(workspaceId, kind).map(BlindSyncArtifact::artifactId).sorted()
        }
    }

    /**
     * 按请求顺序读取指定类型中实际存在的密文载荷。
     *
     * @param workspaceId 随机工作区标识。
     * @param kind 密文载荷类型。
     * @param artifactIds 待读取内容 ID。
     * @return 实际存在且类型一致的密文载荷。
     */
    override suspend fun getArtifacts(
        workspaceId: WorkspaceId,
        kind: SyncArtifactKind,
        artifactIds: List<String>,
    ): List<BlindSyncArtifact> = withContext(dispatcher) {
        accessMutex.withLock {
            artifactIds.mapNotNull { artifactId ->
                val path = artifactPath(workspaceId, kind, artifactId)
                if (Files.exists(path)) {
                    readArtifact(path).takeIf { artifact -> artifact.descriptor.kind == kind }
                } else {
                    null
                }
            }
        }
    }

    /**
     * 为一个对象计算未被同对象子节点引用的 Change。
     *
     * @param objectId 当前对象。
     * @param changes 当前对象全部已知 Change。
     * @return 稳定排序的 heads 描述。
     */
    private fun headsForObject(
        objectId: ObjectId,
        changes: List<BlindSyncArtifact>,
    ): ObjectChangeHeads {
        val ownIds = changes.map(BlindSyncArtifact::artifactId).toSet()
        val referenced = changes.flatMap { artifact -> artifact.descriptor.parentArtifactIds }.toSet()
        val heads = ownIds.filterNot(referenced::contains).sorted()
        return ObjectChangeHeads(objectId, heads)
    }

    /**
     * 扫描工作区指定类型的全部载荷文件。
     *
     * @param workspaceId 随机工作区标识。
     * @param kind 载荷类型。
     * @return 按内容 ID 稳定排列的载荷。
     */
    private fun listArtifacts(
        workspaceId: WorkspaceId,
        kind: SyncArtifactKind,
    ): List<BlindSyncArtifact> {
        val directory = kindDirectory(workspaceId, kind)
        if (!Files.exists(directory)) {
            return emptyList()
        }
        val paths = Files.walk(directory).use { stream ->
            stream.filter(Files::isRegularFile)
                .filter { path -> path.fileName.toString().endsWith(ARTIFACT_EXTENSION) }
                .sorted()
                .collect(Collectors.toList())
        }
        return paths.map(::readArtifact)
    }

    /**
     * 读取并解码单个载荷文件。
     *
     * @param path 已限制在同步数据根目录下的文件。
     * @return 解码后的同步载荷。
     */
    private fun readArtifact(path: Path): BlindSyncArtifact = CanonicalJson.decodeFromString(
        BlindSyncArtifact.serializer(),
        Files.readString(path),
    )

    /**
     * 写入新文件并强制刷新文件内容与元数据。
     *
     * @param path 同目录唯一临时路径。
     * @param bytes 待持久化 UTF-8 JSON 字节。
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
     * 生成随机标识限定的载荷路径。
     *
     * @param workspaceId 随机工作区标识。
     * @param kind 载荷类型。
     * @param artifactId SHA-256 内容 ID。
     * @return 归一化后仍位于根目录内的路径。
     */
    private fun artifactPath(
        workspaceId: WorkspaceId,
        kind: SyncArtifactKind,
        artifactId: String,
    ): Path {
        val directory = kindDirectory(workspaceId, kind).resolve(artifactId.take(2))
        val result = directory.resolve("$artifactId$ARTIFACT_EXTENSION").normalize()
        check(result.startsWith(rootDirectory.toAbsolutePath().normalize())) { "同步载荷路径越界" }
        return result
    }

    /**
     * 生成工作区指定类型目录。
     *
     * @param workspaceId 随机工作区标识。
     * @param kind 载荷类型。
     * @return 归一化绝对目录。
     */
    private fun kindDirectory(
        workspaceId: WorkspaceId,
        kind: SyncArtifactKind,
    ): Path = rootDirectory.toAbsolutePath().normalize()
        .resolve("workspaces")
        .resolve(workspaceId.value)
        .resolve("artifacts")
        .resolve(kind.name.lowercase())

    private companion object {
        /** 盲存储载荷文件扩展名。 */
        const val ARTIFACT_EXTENSION: String = ".json"
    }
}
