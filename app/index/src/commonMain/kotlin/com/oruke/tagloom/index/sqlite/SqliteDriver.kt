package com.oruke.tagloom.index.sqlite

/** 加密索引使用的最小 SQL 会话；只属于索引内部，不向业务层暴露数据库 API。 */
internal interface SqliteDriver {
    /**
     * 执行参数化语句，用户值只能通过参数传递。
     * @param sql 固定 SQL 结构。
     * @param parameters 按占位符顺序绑定的文本、数字或空值。
     */
    fun execute(sql: String, parameters: List<Any?> = emptyList())

    /**
     * 在游标有效期内读取查询结果，退出时必须关闭游标。
     * @param sql 固定 SQL 结构。
     * @param parameters 按占位符顺序绑定的值。
     * @param read 不得把游标引用带出当前作用域的读取操作。
     * @return 已脱离游标生命周期的结果。
     */
    fun <T> query(sql: String, parameters: List<Any?> = emptyList(), read: (SqliteRows) -> T): T

    /** 开始一个独占写事务；调用方已串行化访问。 */
    fun begin()

    /** 提交当前完整事务。 */
    fun commit()

    /** 回滚当前事务并保留旧索引和 checkpoint。 */
    fun rollback()

    /** 释放连接；关闭后不可再次访问。 */
    fun close()
}

/** 查询作用域内的只读游标；列名必须来自固定 SQL 投影。 */
internal interface SqliteRows {
    /**
     * 前进到下一行。
     * @return 存在下一行时为 true。
     */
    fun next(): Boolean

    /**
     * 读取文本列。
     * @param column SQL 投影列名。
     * @return 当前行文本，SQL NULL 返回空。
     */
    fun text(column: String): String?

    /**
     * 读取相关度数值。
     * @param column SQL 投影列名。
     * @return 当前行数值。
     */
    fun number(column: String): Double
}
