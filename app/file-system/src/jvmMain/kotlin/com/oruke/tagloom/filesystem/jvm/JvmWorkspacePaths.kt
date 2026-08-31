package com.oruke.tagloom.filesystem.jvm

import com.oruke.tagloom.core.model.TransactionId
import com.oruke.tagloom.core.transaction.PreparedArtifactRecord
import com.oruke.tagloom.core.transaction.WorkspaceRelativePath
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path

/**
 * 集中解析工作区控制路径，并拒绝目录穿越和已存在的符号链接父路径。
 *
 * @param workspaceRoot 用户选择的工作区根目录。
 */
class JvmWorkspacePaths(workspaceRoot: Path) {
    /** 绝对规范化工作区根目录。 */
    val root: Path = workspaceRoot.toAbsolutePath().normalize()

    init {
        require(!Files.isSymbolicLink(root)) { "工作区根目录不能是符号链接" }
    }

    /** 加密 manifest 固定路径。 */
    val manifest: Path = root.resolve(MANIFEST_FILE_NAME)

    /** 加密事务暂存根目录。 */
    val stagingRoot: Path = root.resolve(STAGING_DIRECTORY_NAME)

    /** 无法自动恢复的密文隔离根目录。 */
    val quarantineRoot: Path = root.resolve("history").resolve("quarantine")

    /** 可删除并重建的本地派生索引目录。 */
    val indexDirectory: Path = root.resolve(INDEX_DIRECTORY_NAME)

    /** 当前设备的加密 SQLite 派生索引文件。 */
    val indexDatabase: Path = indexDirectory.resolve(INDEX_DATABASE_FILE_NAME)

    /**
     * 解析并验证业务 artifact 最终路径。
     *
     * @param relativePath 已通过公共协议校验的逻辑路径。
     * @return 位于工作区根目录内的绝对路径。
     */
    fun target(relativePath: WorkspaceRelativePath): Path = resolveWithin(root, relativePath.value)

    /**
     * 返回指定事务的 staging 目录。
     *
     * @param transactionId URL-safe 事务标识。
     * @return staging 根目录下的绝对路径。
     */
    fun transactionDirectory(transactionId: TransactionId): Path =
        resolveWithin(stagingRoot, transactionId.value)

    /**
     * 返回 prepared 记录路径。
     *
     * @param transactionId 事务标识。
     * @return 事务目录下固定记录文件。
     */
    fun preparedRecord(transactionId: TransactionId): Path =
        transactionDirectory(transactionId).resolve(PREPARED_FILE_NAME)

    /**
     * 返回 committed 诊断标记路径。
     *
     * @param transactionId 事务标识。
     * @return 事务目录下固定标记文件。
     */
    fun committedMarker(transactionId: TransactionId): Path =
        transactionDirectory(transactionId).resolve(COMMITTED_FILE_NAME)

    /**
     * 返回 staging 中待发布的 artifact 文件路径。
     *
     * @param transactionId 事务标识。
     * @param stagedFileName prepared 记录中的单文件名。
     * @return files 子目录下路径。
     */
    fun stagedArtifact(
        transactionId: TransactionId,
        stagedFileName: String,
    ): Path = resolveWithin(transactionDirectory(transactionId).resolve(FILES_DIRECTORY_NAME), stagedFileName)

    /**
     * 返回 staging 中对应旧版本备份路径。
     *
     * @param transactionId 事务标识。
     * @param artifact prepared artifact 记录。
     * @return previous 子目录下不与目标名称耦合的路径。
     */
    fun previousArtifact(
        transactionId: TransactionId,
        artifact: PreparedArtifactRecord,
    ): Path = resolveWithin(
        transactionDirectory(transactionId).resolve(PREVIOUS_DIRECTORY_NAME),
        artifact.stagedFileName + PREVIOUS_FILE_SUFFIX,
    )

    /**
     * 返回 staging 中待确认 manifest 路径。
     *
     * @param transactionId 事务标识。
     * @param stagedFileName prepared 记录中的单文件名。
     * @return 事务目录内 manifest 路径。
     */
    fun stagedManifest(
        transactionId: TransactionId,
        stagedFileName: String,
    ): Path = resolveWithin(transactionDirectory(transactionId), stagedFileName)

    /**
     * 返回事务隔离目标目录，并验证 `history/quarantine` 目录边界。
     *
     * @param transactionId 被隔离事务标识。
     * @param uniqueSuffix 单次隔离使用的随机后缀。
     * @return 工作区内尚不存在的隔离目标路径。
     */
    fun quarantineDirectory(
        transactionId: TransactionId,
        uniqueSuffix: String,
    ): Path {
        require(uniqueSuffix.matches(UNIQUE_SUFFIX_PATTERN)) { "隔离目录后缀格式无效" }
        return resolveWithin(
            root,
            "history/quarantine/${transactionId.value}-$uniqueSuffix",
        )
    }

    /**
     * 创建并验证派生索引目录，拒绝通过符号链接重定向到工作区外。
     */
    fun ensureIndexDirectory() {
        ensureManagedDirectory(indexDirectory)
    }

    /**
     * 返回索引目录下的固定旁车文件路径。
     *
     * @param fileName 索引旁车文件名，不允许目录分隔符或点段。
     * @return 位于工作区索引目录内的路径。
     */
    fun indexSidecar(fileName: String): Path {
        require(fileName.matches(INDEX_FILE_NAME_PATTERN)) { "索引旁车文件名无效" }
        return resolveWithin(root, "$INDEX_DIRECTORY_NAME/$fileName")
    }

    /**
     * 验证即将访问的路径没有逃逸允许根目录，也没有通过既有符号链接重定向。
     *
     * @param allowedRoot 允许的根目录。
     * @param relative 规范化相对路径。
     * @return 安全绝对路径。
     */
    private fun resolveWithin(
        allowedRoot: Path,
        relative: String,
    ): Path {
        require(relative.isNotBlank()) { "工作区受控路径不能为空" }
        val segments = relative.split('/')
        require(
            !relative.startsWith('/') && !relative.startsWith('\\') &&
                !java.nio.file.Path.of(relative).isAbsolute &&
                segments.none { segment -> segment.isBlank() || segment == "." || segment == ".." } &&
                '\\' !in relative,
        ) { "工作区受控路径格式无效" }
        val normalizedRoot = allowedRoot.toAbsolutePath().normalize()
        val resolved = normalizedRoot.resolve(relative).normalize()
        require(resolved.startsWith(normalizedRoot)) { "工作区路径越界" }
        rejectExistingSymbolicLinks(normalizedRoot, resolved)
        return resolved
    }

    /**
     * 拒绝从允许根到目标之间已经存在的符号链接，避免路径检查与实际写入目标分离。
     *
     * @param allowedRoot 允许根目录。
     * @param target 已确认词法范围的目标路径。
     */
    private fun rejectExistingSymbolicLinks(
        allowedRoot: Path,
        target: Path,
    ) {
        require(!Files.isSymbolicLink(allowedRoot)) { "工作区路径不能经过符号链接" }
        var current = allowedRoot
        for (segment in allowedRoot.relativize(target)) {
            current = current.resolve(segment.toString())
            if (Files.isSymbolicLink(current)) {
                throw IllegalArgumentException("工作区路径不能经过符号链接")
            }
        }
    }

    /**
     * 创建并验证受控目录，允许目录此前不存在但不允许已有符号链接。
     *
     * @param directory 待创建的受控目录。
     */
    private fun ensureManagedDirectory(directory: Path) {
        val normalized = directory.toAbsolutePath().normalize()
        require(normalized.startsWith(root)) { "工作区目录越界" }
        rejectExistingSymbolicLinks(root, normalized)
        Files.createDirectories(normalized)
        require(Files.isDirectory(normalized, LinkOption.NOFOLLOW_LINKS)) { "工作区路径不是目录" }
        rejectExistingSymbolicLinks(root, normalized)
    }

    companion object {
        /** manifest 文件名。 */
        const val MANIFEST_FILE_NAME: String = "manifest.enc"

        /** staging 目录名。 */
        const val STAGING_DIRECTORY_NAME: String = "staging"

        /** prepared 记录文件名。 */
        const val PREPARED_FILE_NAME: String = "prepared.json"

        /** committed 标记文件名。 */
        const val COMMITTED_FILE_NAME: String = "committed"

        /** 新 artifact staging 子目录。 */
        const val FILES_DIRECTORY_NAME: String = "files"

        /** 可重建派生索引目录名。 */
        const val INDEX_DIRECTORY_NAME: String = "index"

        /** 本地加密 SQLite 文件名。 */
        const val INDEX_DATABASE_FILE_NAME: String = "local.sqlite"

        /** 旧版本备份子目录。 */
        const val PREVIOUS_DIRECTORY_NAME: String = "previous"

        /** 旧版本备份后缀。 */
        const val PREVIOUS_FILE_SUFFIX: String = ".previous.enc"

        /** 隔离目录随机后缀格式。 */
        private val UNIQUE_SUFFIX_PATTERN: Regex = Regex("[A-Za-z0-9-]{1,64}")

        /** 索引旁车文件名格式。 */
        private val INDEX_FILE_NAME_PATTERN: Regex = Regex("[A-Za-z0-9._-]{1,128}")
    }
}
