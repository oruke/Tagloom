package com.oruke.tagloom.workspace

import com.oruke.tagloom.core.crypto.CryptoProvider
import com.oruke.tagloom.core.crypto.WorkspaceKeyService
import com.oruke.tagloom.core.workspace.UnlockedWorkspaceManifest
import com.oruke.tagloom.filesystem.jvm.JvmWorkspacePaths
import com.oruke.tagloom.index.jvm.JvmSqliteIndexEngine
import com.oruke.tagloom.index.sqlite.EncryptedSqliteIndexCorruptionException
import com.oruke.tagloom.index.sqlite.SqliteIndexSchemaMismatchException
import java.nio.file.Files

/**
 * 打开桌面加密索引，并在已知派生文件损坏时安全重建空数据库。
 */
object DesktopIndexFactory {
    /**
     * 使用当前主密钥派生索引密钥；打开失败时只删除固定派生文件。
     *
     * @param paths 受控工作区路径。
     * @param cryptoProvider 密钥派生端口。
     * @param unlocked 当前解锁 manifest。
     * @return 已打开的新索引。
     */
    suspend fun open(
        paths: JvmWorkspacePaths,
        cryptoProvider: CryptoProvider,
        unlocked: UnlockedWorkspaceManifest,
    ): JvmSqliteIndexEngine {
        paths.ensureIndexDirectory()
        val keyService = WorkspaceKeyService(cryptoProvider)
        val firstKey = keyService.deriveIndexKey(
            unlocked.workspaceMasterKey,
            unlocked.file.publicHeader.workspaceId,
        )
        return try {
            JvmSqliteIndexEngine.open(paths.indexDatabase, firstKey)
        } catch (error: EncryptedSqliteIndexCorruptionException) {
            resetDerivedIndex(paths, error)
            val retryKey = keyService.deriveIndexKey(
                unlocked.workspaceMasterKey,
                unlocked.file.publicHeader.workspaceId,
            )
            JvmSqliteIndexEngine.open(paths.indexDatabase, retryKey)
        } catch (error: SqliteIndexSchemaMismatchException) {
            resetDerivedIndex(paths, error)
            val retryKey = keyService.deriveIndexKey(
                unlocked.workspaceMasterKey,
                unlocked.file.publicHeader.workspaceId,
            )
            JvmSqliteIndexEngine.open(paths.indexDatabase, retryKey)
        }
    }

    /**
     * 删除可重建索引的固定文件，并保留清理失败的原始异常。
     *
     * 只有 SQLite 页认证失败或 schema 不兼容才会进入这里；权限、磁盘满、锁冲突和取消
     * 等运行时故障必须继续向调用方报告，避免误删一个可能仍然有效的派生索引。
     *
     * @param paths 受控工作区路径。
     * @param original 原始索引打开异常。
     */
    private fun resetDerivedIndex(
        paths: JvmWorkspacePaths,
        original: Throwable,
    ) {
        try {
            Files.deleteIfExists(paths.indexDatabase)
            listOf(
                "${JvmWorkspacePaths.INDEX_DATABASE_FILE_NAME}-wal",
                "${JvmWorkspacePaths.INDEX_DATABASE_FILE_NAME}-shm",
                "${JvmWorkspacePaths.INDEX_DATABASE_FILE_NAME}-journal",
            ).forEach { fileName ->
                Files.deleteIfExists(paths.indexSidecar(fileName))
            }
        } catch (cleanupFailure: Throwable) {
            if (cleanupFailure !== original) {
                original.addSuppressed(cleanupFailure)
            }
            throw original
        }
    }
}
