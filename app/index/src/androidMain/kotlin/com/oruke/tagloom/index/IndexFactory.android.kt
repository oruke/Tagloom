package com.oruke.tagloom.index

import com.oruke.tagloom.index.jvm.JvmSqliteIndexEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking

/**
 * 创建 Android 临时加密索引，真实工作区由共享会话传入派生密钥和私有路径。
 *
 * @return 临时索引能力。
 */
actual fun createIndexEngine(): IndexEngine =
    runBlocking(Dispatchers.IO) { JvmSqliteIndexEngine.openEphemeral(AndroidIndexEnvironment.cacheDirectory()) }

/**
 * 创建 Android 临时搜索能力，真实工作区的搜索和写入共用同一个引擎。
 *
 * @return 临时搜索能力。
 */
actual fun createSearchEngine(): SearchEngine =
    runBlocking(Dispatchers.IO) { JvmSqliteIndexEngine.openEphemeral(AndroidIndexEnvironment.cacheDirectory()) }
