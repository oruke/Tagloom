package com.oruke.tagloom.index.sqlite

import com.oruke.tagloom.core.crypto.SecretKeyKind
import com.oruke.tagloom.core.crypto.SecretKeyMaterial
import org.sqlite.SQLiteErrorCode
import org.sqlite.SQLiteException
import org.sqlite.mc.SQLiteMCChacha20Config
import java.nio.file.Files
import java.nio.file.Path
import java.sql.Connection
import java.sql.SQLException

/** 创建使用固定 ChaCha20 配置的 SQLite3MultipleCiphers JDBC 连接。 */
internal object EncryptedSqliteConnectionFactory {
    /**
     * 创建目录、应用十六进制会话密钥并验证数据库页可读。
     *
     * 采用 `withHexKey(ByteArray)`，避免由 Tagloom 主动创建长期 String 密钥；驱动内部不可避免的临时表示由其生命周期管理。
     * 第一次读取 `sqlite_master` 是必要的认证检查，因为 JDBC 建连本身可能尚未触碰加密页。
     *
     * @param databasePath 本地派生索引文件路径。
     * @param indexKey 已从 Workspace Master Key 独立派生的索引密钥。
     * @return 已验证、尚未初始化业务 schema 的加密 JDBC 连接。
     */
    fun open(
        databasePath: Path,
        indexKey: SecretKeyMaterial,
    ): Connection {
        require(indexKey.kind == SecretKeyKind.INDEX) { "SQLite 索引必须使用 INDEX 用途密钥" }
        check(!indexKey.isDestroyed) { "SQLite 索引密钥已经销毁" }

        val normalizedPath = databasePath.toAbsolutePath().normalize()
        normalizedPath.parent?.let(Files::createDirectories)
        val jdbcUrl = "jdbc:sqlite:${normalizedPath.toString().replace('\\', '/')}"
        var connection: Connection? = null
        return try {
            val openedConnection = indexKey.useBytes { keyBytes ->
                SQLiteMCChacha20Config.getDefault()
                    .withHexKey(keyBytes)
                    .build()
                    .createConnection(jdbcUrl)
            }
            connection = openedConnection
            openedConnection.createStatement().use { statement ->
                statement.executeQuery("SELECT count(*) FROM sqlite_master").use { result ->
                    check(result.next()) { "无法验证加密 SQLite 索引" }
                }
            }
            openedConnection
        } catch (error: SQLException) {
            runCatching { connection?.close() }
            // 只有页损坏、密钥认证或文件格式错误允许删除派生索引重建；权限、锁、磁盘满和 I/O 错误必须保留原始语义。
            val resultCode = generateSequence<Throwable>(error) { it.cause }
                .filterIsInstance<SQLiteException>()
                .map(SQLiteException::getResultCode)
                .firstOrNull()
            if (resultCode in REBUILDABLE_ERROR_CODES) {
                throw EncryptedSqliteIndexCorruptionException(error)
            }
            throw error
        } catch (error: Throwable) {
            runCatching { connection?.close() }
            throw error
        }
    }

    /** 允许索引层删除并重建的 SQLite 认证、格式和页损坏错误码。 */
    private val REBUILDABLE_ERROR_CODES: Set<SQLiteErrorCode> = setOf(
        SQLiteErrorCode.SQLITE_AUTH,
        SQLiteErrorCode.SQLITE_AUTH_USER,
        SQLiteErrorCode.SQLITE_FORMAT,
        SQLiteErrorCode.SQLITE_NOTADB,
        SQLiteErrorCode.SQLITE_CORRUPT,
        SQLiteErrorCode.SQLITE_CORRUPT_INDEX,
        SQLiteErrorCode.SQLITE_CORRUPT_SEQUENCE,
        SQLiteErrorCode.SQLITE_CORRUPT_VTAB,
    )
}
