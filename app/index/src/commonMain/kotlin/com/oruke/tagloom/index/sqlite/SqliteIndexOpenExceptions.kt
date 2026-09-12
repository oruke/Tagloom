package com.oruke.tagloom.index.sqlite

/**
 * 表示加密 SQLite 派生索引的页、密钥或文件格式无法认证。
 *
 * 该异常只用于已经触碰 SQLite 数据页后的认证失败；上层可以安全删除并从权威快照重建
 * 派生索引，但不能把它当作任意 I/O 故障处理。
 *
 * @param cause SQLite 驱动提供的底层认证或格式异常。
 */
class EncryptedSqliteIndexCorruptionException(
    cause: Throwable,
) : IllegalStateException("加密 SQLite 索引损坏或密钥不匹配", cause)

/**
 * 表示 SQLite 派生索引的 schema 版本已经不能由当前实现读取。
 *
 * 索引属于可删除派生状态，因此该异常允许上层删除索引后重新建立当前 schema。
 *
 * @param message 不包含敏感数据的 schema 兼容性说明。
 */
class SqliteIndexSchemaMismatchException(
    message: String,
) : IllegalStateException(message)
