package com.oruke.tagloom.filesystem

import com.oruke.tagloom.core.crypto.CryptoProvider
import com.oruke.tagloom.core.transaction.DerivedStateUpdater
import com.oruke.tagloom.core.workspace.WorkspaceManifestService
import com.oruke.tagloom.filesystem.jvm.JvmDirectoryDurability
import com.oruke.tagloom.filesystem.jvm.JvmWorkspaceFileSystem
import com.oruke.tagloom.filesystem.jvm.JvmWorkspaceRecoveryContext
import com.oruke.tagloom.filesystem.jvm.crypto.LibsodiumCryptoProvider
import java.nio.file.Path

/**
 * 创建带显式根目录和可选恢复上下文的 JVM 文件能力。
 *
 * @param workspaceRoot 工作区根目录。
 * @param cryptoProvider JVM 密码学实现。
 * @param recoveryContext 已解锁工作区恢复上下文；为空时不启用 prepared 恢复。
 * @param directoryDurability 目录元数据持久化实现。
 * @return 已配置的 JVM 文件系统能力。
 */
fun createJvmWorkspaceFileSystem(
    workspaceRoot: Path,
    cryptoProvider: CryptoProvider = LibsodiumCryptoProvider(),
    recoveryContext: JvmWorkspaceRecoveryContext? = null,
    directoryDurability: JvmDirectoryDurability = com.oruke.tagloom.filesystem.jvm.JvmDirectoryDurabilityFactory.create(),
): JvmWorkspaceFileSystem = JvmWorkspaceFileSystem(
    workspaceRoot = workspaceRoot,
    cryptoProvider = cryptoProvider,
    recoveryContext = recoveryContext,
    directoryDurability = directoryDurability,
)

/**
 * 创建用于恢复事务的 JVM 上下文。
 *
 * @param workspaceId 当前工作区公开 ID。
 * @param activeKeyId 当前 manifest 主密钥版本。
 * @param workspaceMasterKey 当前解锁会话主密钥。
 * @param manifestService manifest 加密和确认点服务。
 * @param derivedStateUpdater 恢复后更新派生状态的端口。
 * @return 可传入 [createJvmWorkspaceFileSystem] 的恢复上下文。
 */
fun createJvmWorkspaceRecoveryContext(
    workspaceId: com.oruke.tagloom.core.model.WorkspaceId,
    activeKeyId: com.oruke.tagloom.core.model.KeyId,
    workspaceMasterKey: com.oruke.tagloom.core.crypto.SecretKeyMaterial,
    manifestService: WorkspaceManifestService,
    derivedStateUpdater: DerivedStateUpdater? = null,
): JvmWorkspaceRecoveryContext = JvmWorkspaceRecoveryContext(
    workspaceId = workspaceId,
    activeKeyId = activeKeyId,
    workspaceMasterKey = workspaceMasterKey,
    manifestService = manifestService,
    derivedStateUpdater = derivedStateUpdater ?: object : DerivedStateUpdater {
        /**
         * 未配置索引时不执行派生状态更新，调用方随后应主动重建索引。
         *
         * @param transactionId 已确认事务 ID。
         */
        override suspend fun update(transactionId: com.oruke.tagloom.core.model.TransactionId) = Unit
    },
)
