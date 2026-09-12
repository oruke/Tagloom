package com.oruke.tagloom.filesystem.jvm

import com.oruke.tagloom.core.transaction.WorkspaceRelativePath
import com.oruke.tagloom.core.workspace.UnlockedWorkspaceManifest
import com.oruke.tagloom.core.workspace.WorkspaceManifestCodec
import com.oruke.tagloom.core.workspace.WorkspaceManifestService
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.security.DigestInputStream
import java.security.MessageDigest
import java.util.Comparator
import java.util.UUID
import java.util.zip.Deflater
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * 创建和恢复只包含权威密文的工作区 ZIP 备份。
 *
 * 索引、staging 与隔离诊断数据均不进入备份；恢复只负责结构安全和原子落盘，密码或
 * Recovery Key 认证仍由工作区生命周期服务完成。
 *
 * @param workspaceRoot 当前工作区根目录。
 * @param manifestService 用当前主密钥认证磁盘 manifest 的服务。
 * @param atomicFiles 文件与目录原子发布原语。
 */
class JvmEncryptedWorkspaceBackupService(
    workspaceRoot: Path,
    private val manifestService: WorkspaceManifestService,
    private val atomicFiles: JvmAtomicFilePort = JvmAtomicFileOperations(),
) {
    /** 当前工作区受控路径。 */
    private val paths: JvmWorkspacePaths = JvmWorkspacePaths(workspaceRoot)

    /**
     * 把已认证 manifest 及其引用的全部权威密文写入单一归档。
     *
     * @param unlocked 当前活动会话的已认证 manifest 与主密钥。
     * @param destination 工作区之外的备份目标文件。
     * @return 已发布归档的对象数量和密文字节数。
     */
    suspend fun createBackup(
        unlocked: UnlockedWorkspaceManifest,
        destination: Path,
    ): WorkspaceBackupReport {
        val target = validateBackupDestination(destination)
        val manifestBytes = readRegularFile(paths.manifest)
        val manifestFile = WorkspaceManifestCodec.decode(manifestBytes)
        require(manifestFile.publicHeader.workspaceId == unlocked.file.publicHeader.workspaceId) {
            "磁盘 manifest 与活动工作区不一致"
        }
        val currentPayload = manifestService.inspectWithMasterKey(manifestBytes, unlocked.workspaceMasterKey)
        check(currentPayload.revision == unlocked.payload.revision) { "活动会话不是磁盘最新确认点" }

        val temporary = target.parent.resolve(".${target.fileName}.${UUID.randomUUID()}.tmp")
        var encryptedBytes = manifestBytes.size.toLong()
        try {
            ZipOutputStream(BufferedOutputStream(Files.newOutputStream(temporary, StandardOpenOption.CREATE_NEW))).use { zip ->
                // 密文几乎不可压缩，关闭压缩可以避免无收益的 CPU 消耗，同时保留流式 ZIP 容器。
                zip.setLevel(Deflater.NO_COMPRESSION)
                writeBytesEntry(zip, JvmWorkspacePaths.MANIFEST_FILE_NAME, manifestBytes)
                currentPayload.objects.sortedBy { record -> record.path.value }.forEach { record ->
                    val artifact = paths.target(record.path)
                    encryptedBytes += writeVerifiedArtifact(zip, artifact, record.path, record.encryptedSha256)
                }
            }
            atomicFiles.publishPreparedFileAtomically(temporary, target)
        } finally {
            Files.deleteIfExists(temporary)
        }
        return WorkspaceBackupReport(currentPayload.objects.size, encryptedBytes)
    }

    /**
     * 把备份恢复到不存在或为空的目标目录，并以一次原子目录移动发布完整工作区。
     *
     * @param archive 由 Tagloom 创建的密文 ZIP 备份。
     * @param targetRoot 待恢复工作区目录。
     * @return 已恢复的密文条目数和字节数。
     */
    fun restoreBackup(
        archive: Path,
        targetRoot: Path,
    ): WorkspaceRestoreReport {
        val source = archive.toAbsolutePath().normalize()
        require(Files.isRegularFile(source, LinkOption.NOFOLLOW_LINKS)) { "备份文件不存在或不是普通文件" }
        val target = validateRestoreTarget(targetRoot)
        val parent = requireNotNull(target.parent) { "恢复目标必须有父目录" }
        val staging = parent.resolve(".${target.fileName}.restore-${UUID.randomUUID()}").normalize()
        require(staging.startsWith(parent) && !Files.exists(staging, LinkOption.NOFOLLOW_LINKS)) { "恢复 staging 路径无效" }

        var report: WorkspaceRestoreReport? = null
        try {
            Files.createDirectory(staging)
            atomicFiles.forceDirectory(parent)
            report = extractArchive(source, staging)
            validateRestoredManifest(staging)
            if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
                Files.delete(target)
                atomicFiles.forceDirectory(parent)
            }
            atomicFiles.moveDirectoryAtomically(staging, target)
            return report
        } finally {
            if (Files.exists(staging, LinkOption.NOFOLLOW_LINKS)) {
                deleteControlledStaging(staging, parent)
            }
        }
    }

    /**
     * 校验目标在工作区之外且父路径不经过符号链接。
     *
     * @param destination 用户选择的备份文件。
     * @return 规范化绝对目标。
     */
    private fun validateBackupDestination(destination: Path): Path {
        val target = destination.toAbsolutePath().normalize()
        val parent = requireNotNull(target.parent) { "备份目标必须有父目录" }
        require(!target.startsWith(paths.root)) { "备份文件不能写入工作区内部" }
        require(Files.isDirectory(parent, LinkOption.NOFOLLOW_LINKS)) { "备份目标父目录不存在" }
        rejectSymbolicLinks(parent)
        require(!Files.exists(target, LinkOption.NOFOLLOW_LINKS) || Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS)) {
            "备份目标必须是普通文件"
        }
        return target
    }

    /**
     * 校验恢复目录不存在或为空，并拒绝符号链接边界。
     *
     * @param targetRoot 用户选择的恢复目录。
     * @return 规范化绝对目录。
     */
    private fun validateRestoreTarget(targetRoot: Path): Path {
        val target = targetRoot.toAbsolutePath().normalize()
        val parent = requireNotNull(target.parent) { "恢复目标必须有父目录" }
        require(Files.isDirectory(parent, LinkOption.NOFOLLOW_LINKS)) { "恢复目标父目录不存在" }
        rejectSymbolicLinks(parent)
        if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
            require(Files.isDirectory(target, LinkOption.NOFOLLOW_LINKS) && !Files.isSymbolicLink(target)) {
                "恢复目标必须是普通目录"
            }
            Files.list(target).use { children -> require(children.findAny().isEmpty) { "恢复目标目录必须为空" } }
        }
        return target
    }

    /**
     * 把 manifest 字节写为固定 ZIP 条目。
     *
     * @param zip 目标 ZIP 流。
     * @param name 规范条目名。
     * @param bytes 完整密文字节。
     */
    private fun writeBytesEntry(
        zip: ZipOutputStream,
        name: String,
        bytes: ByteArray,
    ) {
        zip.putNextEntry(ZipEntry(name).apply { time = 0L })
        zip.write(bytes)
        zip.closeEntry()
    }

    /**
     * 流式核验并写入一个 manifest 引用的密文对象。
     *
     * @param zip 目标 ZIP 流。
     * @param artifact 工作区内密文文件。
     * @param relativePath manifest 中的受控路径。
     * @param expectedSha256 manifest 声明的密文摘要。
     * @return 写入字节数。
     */
    private fun writeVerifiedArtifact(
        zip: ZipOutputStream,
        artifact: Path,
        relativePath: WorkspaceRelativePath,
        expectedSha256: String,
    ): Long {
        require(Files.isRegularFile(artifact, LinkOption.NOFOLLOW_LINKS)) { "manifest 引用的密文文件缺失" }
        val digest = MessageDigest.getInstance("SHA-256")
        var count = 0L
        zip.putNextEntry(ZipEntry(relativePath.value).apply { time = 0L })
        DigestInputStream(BufferedInputStream(Files.newInputStream(artifact)), digest).use { input ->
            count = copyStream(input, zip, Long.MAX_VALUE)
        }
        zip.closeEntry()
        check(digest.digest().toHex() == expectedSha256) { "manifest 引用的密文摘要不匹配" }
        return count
    }

    /**
     * 解压受限条目，拒绝目录穿越、重复项、控制目录和压缩炸弹。
     *
     * @param archive 备份归档。
     * @param staging 隔离恢复目录。
     * @return 解压统计。
     */
    private fun extractArchive(
        archive: Path,
        staging: Path,
    ): WorkspaceRestoreReport {
        val names = mutableSetOf<String>()
        var totalBytes = 0L
        var artifactCount = 0
        ZipInputStream(BufferedInputStream(Files.newInputStream(archive))).use { zip ->
            var entry = zip.nextEntry
            while (entry != null) {
                val name = validateArchiveEntry(entry)
                require(names.add(name)) { "备份包含重复条目" }
                val output = staging.resolve(name).normalize()
                require(output.startsWith(staging)) { "备份条目越界" }
                createParentDirectories(output.parent, staging)
                FileChannel.open(output, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE).use { channel ->
                    val stream = java.nio.channels.Channels.newOutputStream(channel)
                    val remaining = MAX_TOTAL_UNCOMPRESSED_BYTES - totalBytes
                    val written = copyStream(zip, stream, minOf(MAX_ENTRY_UNCOMPRESSED_BYTES, remaining))
                    totalBytes += written
                    channel.force(true)
                }
                output.parent?.let(atomicFiles::forceDirectory)
                if (name != JvmWorkspacePaths.MANIFEST_FILE_NAME) {
                    artifactCount += 1
                }
                zip.closeEntry()
                entry = zip.nextEntry
            }
        }
        require(names.contains(JvmWorkspacePaths.MANIFEST_FILE_NAME)) { "备份缺少 manifest.enc" }
        return WorkspaceRestoreReport(artifactCount, totalBytes)
    }

    /**
     * 校验单个 ZIP 条目只映射到 manifest 或权威对象路径。
     *
     * @param entry 待检查条目。
     * @return 规范条目名。
     */
    private fun validateArchiveEntry(entry: ZipEntry): String {
        require(!entry.isDirectory) { "备份不能包含目录条目" }
        val name = entry.name
        require(name.isNotBlank() && '\\' !in name && !name.startsWith('/')) { "备份条目路径格式无效" }
        if (name != JvmWorkspacePaths.MANIFEST_FILE_NAME) {
            WorkspaceRelativePath(name)
            require(name != "config.enc") { "备份包含非权威控制文件" }
        }
        require(entry.size < 0L || entry.size <= MAX_ENTRY_UNCOMPRESSED_BYTES) { "备份条目超过大小上限" }
        return name
    }

    /**
     * 创建条目父目录并持久化新增目录项。
     *
     * @param directory 条目父目录。
     * @param staging 隔离根目录。
     */
    private fun createParentDirectories(
        directory: Path,
        staging: Path,
    ) {
        require(directory.startsWith(staging)) { "恢复目录越界" }
        if (Files.exists(directory)) {
            return
        }
        Files.createDirectories(directory)
        directory.parent?.let(atomicFiles::forceDirectory)
    }

    /**
     * 对恢复出的 manifest 做公开容器结构校验。
     *
     * @param staging 隔离恢复目录。
     */
    private fun validateRestoredManifest(staging: Path) {
        val manifest = readRegularFile(staging.resolve(JvmWorkspacePaths.MANIFEST_FILE_NAME))
        WorkspaceManifestCodec.decode(manifest)
    }

    /**
     * 读取一个不跟随符号链接的普通文件。
     *
     * @param file 待读取文件。
     * @return 完整字节。
     */
    private fun readRegularFile(file: Path): ByteArray {
        require(Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) { "密文文件不存在或不是普通文件" }
        return Files.readAllBytes(file)
    }

    /**
     * 复制流并对解压字节设置硬上限。
     *
     * @param input 来源流。
     * @param output 目标流。
     * @param maximumBytes 允许复制的最大字节数。
     * @return 实际复制字节数。
     */
    private fun copyStream(
        input: InputStream,
        output: OutputStream,
        maximumBytes: Long,
    ): Long {
        val buffer = ByteArray(STREAM_BUFFER_BYTES)
        var total = 0L
        var read = input.read(buffer)
        while (read >= 0) {
            if (read > 0) {
                require(total + read <= maximumBytes) { "备份解压大小超过安全上限" }
                output.write(buffer, 0, read)
                total += read
            }
            read = input.read(buffer)
        }
        return total
    }

    /**
     * 拒绝既有父路径中的符号链接，避免目标检查与实际 I/O 分离。
     *
     * @param path 必须是普通目录的既有路径。
     */
    private fun rejectSymbolicLinks(path: Path) {
        var current: Path? = path.toAbsolutePath().normalize()
        while (current != null) {
            require(!Files.isSymbolicLink(current)) { "目标路径不能经过符号链接" }
            current = current.parent
        }
    }

    /**
     * 仅清理本次随机命名且仍位于目标父目录内的 staging 树。
     *
     * @param staging 待清理隔离目录。
     * @param expectedParent 允许清理的唯一父目录。
     */
    private fun deleteControlledStaging(
        staging: Path,
        expectedParent: Path,
    ) {
        val normalized = staging.toAbsolutePath().normalize()
        require(normalized.parent == expectedParent.toAbsolutePath().normalize()) { "拒绝清理越界恢复目录" }
        require(normalized.fileName.toString().contains(".restore-")) { "拒绝清理非恢复 staging" }
        Files.walk(normalized).use { stream ->
            stream.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists)
        }
    }

    /** 把摘要字节编码为小写十六进制。 */
    private fun ByteArray.toHex(): String = joinToString("") { byte -> "%02x".format(byte) }

    private companion object {
        /** 流式复制缓冲区大小。 */
        const val STREAM_BUFFER_BYTES: Int = 64 * 1024

        /** 单个恢复条目的解压上限，防止异常归档耗尽磁盘。 */
        const val MAX_ENTRY_UNCOMPRESSED_BYTES: Long = 4L * 1024 * 1024 * 1024

        /** 整个恢复归档的解压上限。 */
        const val MAX_TOTAL_UNCOMPRESSED_BYTES: Long = 16L * 1024 * 1024 * 1024
    }
}

/**
 * 描述一次已发布密文备份的统计结果。
 */
data class WorkspaceBackupReport(
    /** manifest 引用的权威对象数量。 */
    val artifactCount: Int,
    /** manifest 与权威对象的密文字节总数。 */
    val encryptedBytes: Long,
)
/**
 * 描述一次已原子发布恢复目录的统计结果。
 */
data class WorkspaceRestoreReport(
    /** 归档中除 manifest 外的密文条目数。 */
    val artifactCount: Int,
    /** 全部恢复条目的未压缩字节数。 */
    val encryptedBytes: Long,
)
