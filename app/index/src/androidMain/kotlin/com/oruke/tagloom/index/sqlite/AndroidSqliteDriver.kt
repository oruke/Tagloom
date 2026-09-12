package com.oruke.tagloom.index.sqlite

import android.database.Cursor
import android.database.sqlite.SQLiteDatabaseCorruptException
import com.oruke.tagloom.core.crypto.SecretKeyKind
import com.oruke.tagloom.core.crypto.SecretKeyMaterial
import net.zetetic.database.DatabaseErrorHandler
import net.zetetic.database.sqlcipher.SQLiteDatabase
import net.zetetic.database.sqlcipher.SQLiteNotADatabaseException
import java.nio.file.Files
import java.nio.file.Path

/**
 * 打开 Android SQLCipher 索引，认证失败只分类，不让驱动自动删除文件。
 * @param databasePath 私有工作区索引路径。
 * @param indexKey 当前 INDEX 用途密钥。
 * @return 通过页认证的 SQLCipher 会话。
 */
internal actual fun openSqliteDriver(databasePath: Path, indexKey: SecretKeyMaterial): SqliteDriver {
    require(indexKey.kind == SecretKeyKind.INDEX) { "SQLite 索引必须使用 INDEX 用途密钥" }
    check(!indexKey.isDestroyed) { "SQLite 索引密钥已经销毁" }
    System.loadLibrary("sqlcipher")
    // SQLCipher 默认把 SQL 异常送入 Logcat；禁用原始日志以免包含路径或绑定内容。
    net.zetetic.database.Logger.setTarget(null)
    Files.createDirectories(databasePath.toAbsolutePath().parent)
    // SQLCipher 的连接配置保留口令引用，因此副本必须存活到连接关闭，再由驱动清零。
    val passphrase = indexKey.useBytes { it.copyOf() }
    var database: SQLiteDatabase? = null
    try {
        val opened = SQLiteDatabase.openOrCreateDatabase(
            databasePath.toFile(), passphrase, null,
            DatabaseErrorHandler { _, _ -> /* 派生索引的删除只允许由工作区协调器决定。 */ }, null,
        )
        database = opened
        opened.rawQuery("SELECT count(*) FROM sqlite_master", emptyArray<String>()).use { rows ->
            check(rows.moveToFirst()) { "无法验证加密 SQLite 索引" }
        }
        return AndroidSqliteDriver(opened, passphrase)
    } catch (error: Throwable) {
        runCatching { database?.close() }
        passphrase.fill(0)
        // 权限、磁盘满和锁冲突不属于认证失败，必须原样传播以免误删有效索引。
        if (error is SQLiteDatabaseCorruptException || error is SQLiteNotADatabaseException) {
            throw EncryptedSqliteIndexCorruptionException(error)
        }
        throw error
    }
}

/** 通过 SQLCipher 实现共享索引驱动；事务调用必须位于同一阻塞线程。 */
internal class AndroidSqliteDriver(
    /** 当前加密数据库连接池。 */
    private val database: SQLiteDatabase,
    /** 底层连接配置使用的口令副本，关闭时清零。 */
    private val passphrase: ByteArray,
) : SqliteDriver {
    /**
     * 执行参数化 SQL 或消费 PRAGMA 的结果。
     * @param sql 固定 SQL 结构。
     * @param parameters 绑定的用户值。
     */
    override fun execute(sql: String, parameters: List<Any?>) {
        if (sql.trimStart().startsWith("PRAGMA ", ignoreCase = true)) {
            database.rawQuery(sql, emptyArray<String>()).use { it.moveToFirst() }
        } else {
            database.execSQL(sql, parameters.toTypedArray())
        }
    }

    /**
     * 在 Android Cursor 作用域内读取结果。
     * @param sql 固定查询结构。
     * @param parameters 按顺序绑定的参数。
     * @param read 游标读取操作。
     * @return 与游标生命周期无关的结果。
     */
    override fun <T> query(sql: String, parameters: List<Any?>, read: (SqliteRows) -> T): T =
        database.rawQuery(sql, parameters.map { it?.toString() }.toTypedArray()).use { cursor ->
            read(AndroidSqliteRows(cursor))
        }

    /** 开始单线程绑定的索引事务，外层互斥锁阻止查询看到半成品。 */
    override fun begin() { database.beginTransaction() }

    /** 标记完整成功并提交。 */
    override fun commit() {
        database.setTransactionSuccessful()
        database.endTransaction()
    }

    /** 撤销未完成的索引重建和 checkpoint。 */
    override fun rollback() { if (database.inTransaction()) database.endTransaction() }

    /** 关闭连接并清零底层持有的口令副本。 */
    override fun close() {
        try { database.close() } finally { passphrase.fill(0) }
    }
}

/** 把 Android Cursor 适配为共享索引游标。 */
private class AndroidSqliteRows(
    /** 由查询作用域关闭的底层游标。 */
    private val cursor: Cursor,
) : SqliteRows {
    /** @return 是否成功移动到下一行。 */
    override fun next(): Boolean = cursor.moveToNext()

    /**
     * @param column 固定投影列名。
     * @return 文本或 SQL NULL。
     */
    override fun text(column: String): String? {
        val index = cursor.getColumnIndexOrThrow(column)
        return if (cursor.isNull(index)) null else cursor.getString(index)
    }

    /**
     * @param column 固定投影列名。
     * @return 当前相关度。
     */
    override fun number(column: String): Double = cursor.getDouble(cursor.getColumnIndexOrThrow(column))
}
