package com.oruke.tagloom.index.sqlite

import com.oruke.tagloom.core.crypto.SecretKeyKind
import com.oruke.tagloom.core.crypto.SecretKeyMaterial
import org.sqlite.mc.SQLiteMCChacha20Config
import java.nio.file.Files
import java.nio.file.Path
import java.sql.Connection

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
        val connection = indexKey.useBytes { keyBytes ->
            SQLiteMCChacha20Config.getDefault()
                .withHexKey(keyBytes)
                .build()
                .createConnection(jdbcUrl)
        }

        return try {
            connection.createStatement().use { statement ->
                statement.executeQuery("SELECT count(*) FROM sqlite_master").use { result ->
                    check(result.next()) { "无法验证加密 SQLite 索引" }
                }
            }
            connection
        } catch (error: Throwable) {
            runCatching(connection::close)
            throw error
        }
    }
}
