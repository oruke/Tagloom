package com.oruke.tagloom.workspace

import com.oruke.tagloom.core.crypto.CryptoProvider
import com.oruke.tagloom.core.crypto.WorkspaceKeyService
import com.oruke.tagloom.core.workspace.UnlockedWorkspaceManifest
import com.oruke.tagloom.filestore.jvm.JvmWorkspacePaths
import com.oruke.tagloom.index.sqlite.EncryptedSqliteSearchIndex
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
    ): EncryptedSqliteSearchIndex {
        Files.createDirectories(paths.indexDirectory)
        val keyService = WorkspaceKeyService(cryptoProvider)
        val firstKey = keyService.deriveIndexKey(
            unlocked.workspaceMasterKey,
            unlocked.file.publicHeader.workspaceId,
        )
        return try {
            EncryptedSqliteSearchIndex.open(paths.indexDatabase, firstKey)
        } catch (_: Throwable) {
            // 索引不是权威数据，只删除固定文件名及其 SQLite 临时文件，绝不触碰对象快照。
            Files.deleteIfExists(paths.indexDatabase)
            Files.deleteIfExists(paths.indexDirectory.resolve("${JvmWorkspacePaths.INDEX_DATABASE_FILE_NAME}-wal"))
            Files.deleteIfExists(paths.indexDirectory.resolve("${JvmWorkspacePaths.INDEX_DATABASE_FILE_NAME}-shm"))
            val retryKey = keyService.deriveIndexKey(
                unlocked.workspaceMasterKey,
                unlocked.file.publicHeader.workspaceId,
            )
            EncryptedSqliteSearchIndex.open(paths.indexDatabase, retryKey)
        }
    }
}
