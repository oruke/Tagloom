package com.oruke.tagloom.index.sqlite

import com.oruke.tagloom.core.crypto.SecretKeyMaterial
import java.nio.file.Path

/**
 * 打开并认证宿主的加密索引驱动；平台工厂不得自动删除既有文件。
 * @param databasePath 已验证的派生索引路径。
 * @param indexKey 当前会话索引密钥，生命周期由索引会话统一管理。
 * @return 已认证且尚未创建业务 schema 的连接。
 */
internal expect fun openSqliteDriver(databasePath: Path, indexKey: SecretKeyMaterial): SqliteDriver
