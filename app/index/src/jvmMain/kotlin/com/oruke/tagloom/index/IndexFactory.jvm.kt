package com.oruke.tagloom.index

import com.oruke.tagloom.index.jvm.JvmSqliteIndexEngine
import com.oruke.tagloom.core.crypto.SecretKeyMaterial
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import java.nio.file.Path

/**
 * 创建 JVM 默认索引能力。
 *
 * 无工作区上下文时使用随机密钥的临时加密索引，关闭后删除。真实工作区必须使用
 * [openJvmIndexEngine] 传入显式数据库路径和从 Workspace Master Key 派生的 INDEX 密钥。
 *
 * @return 当前 JVM 宿主的派生索引能力。
 */
actual fun createIndexEngine(): IndexEngine = runBlocking(Dispatchers.IO) {
    JvmSqliteIndexEngine.openEphemeral()
}

/**
 * 创建 JVM 默认搜索能力。
 *
 * @return 当前 JVM 宿主的临时加密搜索能力。
 */
actual fun createSearchEngine(): SearchEngine = runBlocking(Dispatchers.IO) {
    JvmSqliteIndexEngine.openEphemeral()
}

/**
 * 打开真实工作区的 JVM 加密索引和搜索引擎。
 *
 * @param databasePath 工作区 `index/local.sqlite` 路径。
 * @param indexKey 从 Workspace Master Key 独立派生的 INDEX 密钥；所有权转移给返回引擎。
 * @return 同时提供 [IndexEngine] 与 [SearchEngine] 的 JVM 引擎。
 */
fun openJvmIndexEngine(
    databasePath: Path,
    indexKey: SecretKeyMaterial,
): JvmSqliteIndexEngine = runBlocking(Dispatchers.IO) {
    JvmSqliteIndexEngine.open(databasePath, indexKey)
}
