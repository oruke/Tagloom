package com.oruke.tagloom.filestore.jvm

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
        var current: Path? = allowedRoot
        while (current != null && current.startsWith(allowedRoot) && current.nameCount <= target.nameCount) {
            if (Files.exists(current, LinkOption.NOFOLLOW_LINKS) && Files.isSymbolicLink(current)) {
                throw IllegalArgumentException("工作区路径不能经过符号链接")
            }
            if (current == target) {
                break
            }
            val relativeIndex = current.nameCount - allowedRoot.nameCount
            current = if (relativeIndex < target.nameCount - allowedRoot.nameCount) {
                current.resolve(target.getName(allowedRoot.nameCount + relativeIndex))
            } else {
                null
            }
        }
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
    }
}
