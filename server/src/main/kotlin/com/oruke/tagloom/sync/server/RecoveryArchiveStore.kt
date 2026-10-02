package com.oruke.tagloom.sync.server

import com.oruke.tagloom.core.model.WorkspaceId
import io.ktor.http.HttpStatusCode
import io.ktor.utils.io.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardOpenOption.*
import java.security.MessageDigest
import java.util.UUID

/** 已完整落盘、尚待登记库发布的密文恢复归档。 */
internal data class StoredRecoveryArchive(
    /** 服务端生成的不透明文件身份。 */
    val id: String,
    /** 整体密文归档摘要。 */
    val sha256: String,
    /** 实际接收的字节数。 */
    val size: Long,
)

/**
 * 流式保存只由客户端解释的完整密文备份，半途上传不会成为可恢复副本。
 * @param dataRoot 服务端数据目录。
 * @param maximumBytes 单个归档允许的最大字节数。
 * @param durability 目录落盘原语。
 */
internal class RecoveryArchiveStore(
    dataRoot: Path,
    /** 单归档上限，防止匿名或失控请求无限消耗磁盘。 */
    private val maximumBytes: Long = 512L * 1024 * 1024,
    /** 完整文件发布后的目录落盘能力。 */
    private val durability: ServerDirectoryDurability = ServerDirectoryDurabilityFactory.create(),
) {
    /** 独立于普通同步 artifact 的不可变恢复归档目录。 */
    private val root = dataRoot.toAbsolutePath().normalize().resolve(".server/archives")

    /**
     * 验证上传摘要并发布不可变归档；只有调用方随后确认登记库才能对恢复入口可见。
     * @param workspaceId 当前已授权工作区。
     * @param input 请求体的密文字节流。
     * @param expectedSha256 客户端计算的完整归档摘要。
     * @return 已落盘的归档描述。
     */
    suspend fun receive(workspaceId: WorkspaceId, input: ByteReadChannel, expectedSha256: String): StoredRecoveryArchive = withContext(Dispatchers.IO) {
        requireSyncIdentity(workspaceId.value); requireSyncDigest(expectedSha256)
        val directory = root.resolve(workspaceId.value)
        Files.createDirectories(directory)
        require(Files.isDirectory(root, NOFOLLOW_LINKS) && Files.isDirectory(directory, NOFOLLOW_LINKS)) { "恢复归档目录无效" }
        val id = UUID.randomUUID().toString()
        val temporary = directory.resolve(".$id.tmp")
        val target = directory.resolve("$id.archive")
        val digest = MessageDigest.getInstance("SHA-256")
        var total = 0L
        try {
            FileChannel.open(temporary, CREATE_NEW, WRITE).use { output ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    val count = input.readAvailable(buffer, 0, buffer.size)
                    if (count < 0) break
                    if (count == 0) continue
                    total += count
                    if (total > maximumBytes) rejectSync(HttpStatusCode.PayloadTooLarge, "recovery_archive_too_large")
                    digest.update(buffer, 0, count)
                    val bytes = ByteBuffer.wrap(buffer, 0, count)
                    while (bytes.hasRemaining()) output.write(bytes)
                }
                // 取消后的通道也可能直接返回 EOF；不能把失败流误当作完整上传。
                input.closedCause?.let { throw it }
                output.force(true)
            }
            if (total == 0L || digest.digest().syncHex() != expectedSha256)
                rejectSync(HttpStatusCode.BadRequest, "recovery_archive_integrity_failed")
            Files.move(temporary, target, ATOMIC_MOVE)
            durability.force(directory)
            StoredRecoveryArchive(id, expectedSha256, total)
        } finally {
            Files.deleteIfExists(temporary)
        }
    }

    /**
     * 只解析登记库中已确认的受控归档路径，不接受请求直接提供文件路径。
     * @param workspace 已授权且含恢复记录的工作区。
     * @return 可供流式下载的密文文件。
     */
    fun file(workspace: RegisteredSyncWorkspace): Path {
        requireSyncIdentity(workspace.workspaceId.value)
        val id = requireNotNull(workspace.archiveId).also(::requireSyncIdentity)
        val path = root.resolve(workspace.workspaceId.value).resolve("$id.archive")
        check(Files.isRegularFile(path, NOFOLLOW_LINKS) && Files.size(path) == workspace.snapshot?.size) { "已确认恢复归档不可用" }
        return path
    }
}
