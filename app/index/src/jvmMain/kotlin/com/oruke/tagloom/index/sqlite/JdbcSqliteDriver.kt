package com.oruke.tagloom.index.sqlite

import com.oruke.tagloom.core.crypto.SecretKeyMaterial
import java.nio.file.Path
import java.sql.Connection
import java.sql.ResultSet

/**
 * 打开桌面加密 JDBC 驱动。
 * @param databasePath 受控索引路径。
 * @param indexKey 当前会话密钥。
 * @return 已认证连接。
 */
internal actual fun openSqliteDriver(databasePath: Path, indexKey: SecretKeyMaterial): SqliteDriver =
    JdbcSqliteDriver(EncryptedSqliteConnectionFactory.open(databasePath, indexKey))

/** 在单连接上实现共享索引 SQL 契约。 */
internal class JdbcSqliteDriver(
    /** 外层负责串行化和生命周期的加密 JDBC 连接。 */
    private val connection: Connection,
) : SqliteDriver {
    /**
     * 绑定参数并执行语句，包括可能返回结果的 PRAGMA。
     * @param sql 固定 SQL。
     * @param parameters 占位符参数。
     */
    override fun execute(sql: String, parameters: List<Any?>) {
        connection.prepareStatement(sql).use { statement ->
            parameters.forEachIndexed { index, value -> statement.setObject(index + 1, value) }
            statement.execute()
        }
    }

    /**
     * 在 JDBC 资源作用域中映射查询结果。
     * @param sql 固定查询。
     * @param parameters 占位符参数。
     * @param read 游标映射操作。
     * @return 已读取结果。
     */
    override fun <T> query(sql: String, parameters: List<Any?>, read: (SqliteRows) -> T): T =
        connection.prepareStatement(sql).use { statement ->
            parameters.forEachIndexed { index, value -> statement.setObject(index + 1, value) }
            statement.executeQuery().use { rows -> read(JdbcSqliteRows(rows)) }
        }

    /** 关闭自动提交以开始完整索引事务。 */
    override fun begin() { connection.autoCommit = false }

    /** 提交索引更新并恢复自动提交。 */
    override fun commit() {
        connection.commit()
        connection.autoCommit = true
    }

    /** 回滚索引更新并恢复自动提交。 */
    override fun rollback() {
        connection.rollback()
        connection.autoCommit = true
    }

    /** 释放底层加密连接。 */
    override fun close() { connection.close() }
}

/** 把 JDBC 结果映射为共享索引的作用域游标。 */
private class JdbcSqliteRows(
    /** 由查询作用域管理的底层游标。 */
    private val rows: ResultSet,
) : SqliteRows {
    /** @return 是否成功移动到下一行。 */
    override fun next(): Boolean = rows.next()

    /**
     * @param column 固定投影列名。
     * @return 当前文本或 SQL NULL。
     */
    override fun text(column: String): String? = rows.getString(column)

    /**
     * @param column 固定投影列名。
     * @return 当前相关度。
     */
    override fun number(column: String): Double = rows.getDouble(column)
}
