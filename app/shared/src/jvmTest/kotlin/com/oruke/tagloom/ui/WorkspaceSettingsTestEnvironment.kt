package com.oruke.tagloom.ui

import com.oruke.tagloom.capability.TagloomCapabilities
import com.oruke.tagloom.core.crypto.Argon2idParameters
import com.oruke.tagloom.filesystem.jvm.JvmWorkspaceFileSystem
import com.oruke.tagloom.filesystem.jvm.crypto.LibsodiumCryptoProvider
import com.oruke.tagloom.sync.createSyncEngine
import com.oruke.tagloom.workspace.DesktopWorkspaceAccessStore
import com.oruke.tagloom.workspace.DesktopWorkspaceGateway
import com.oruke.tagloom.workspace.DesktopWorkspaceLocalAccessService
import com.oruke.tagloom.workspace.DesktopWorkspaceManager
import com.oruke.tagloom.workspace.JvmWorkspaceIndexEngine
import com.oruke.tagloom.workspace.WorkspaceDocumentAccess
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import java.nio.file.Path

/** 使用真实加密、DPAPI 和索引装配生产 App，仅将系统文件选择替换为独立临时目录。 */
internal class WorkspaceSettingsTestEnvironment : AutoCloseable {
    /** 本次测试的所有工作区、登记及备份都限制在此临时目录中。 */
    val root: Path = Files.createTempDirectory(TEST_PREFIX).toAbsolutePath().normalize()
    /** 供文件选择适配器返回的隔离工作区。 */
    private val workspaceRoot = Files.createDirectory(root.resolve("workspace"))
    /** 与产品一致的密码学实现。 */
    private val crypto = LibsodiumCryptoProvider()
    /** 使用低成本 KDF 的真实工作区管理器。 */
    private val manager = DesktopWorkspaceManager(
        cryptoProvider = crypto,
        argon2idFactory = ::testArgonParameters,
    )
    /** 将最近工作区登记限制到测试目录，避免读取或覆盖实际用户登记。 */
    private val accessStore = DesktopWorkspaceAccessStore(
        registryFile = root.resolve("access.json"),
        cryptoProvider = crypto,
        argon2idFactory = ::testArgonParameters,
    )
    /** 产品工作区网关；PIN 和备份操作均经过真实服务。 */
    val gateway = DesktopWorkspaceGateway(
        manager = manager,
        localAccessService = DesktopWorkspaceLocalAccessService(accessStore),
        documents = SettingsTestDocuments(workspaceRoot, root.resolve("backup.zip")),
    )
    /** 与当前网关绑定的真实加密派生索引。 */
    private val index = JvmWorkspaceIndexEngine(gateway)
    /** 从生产 App 根节点进入测试所需的完整能力集合。 */
    val capabilities = TagloomCapabilities(
        workspace = gateway,
        fileSystem = JvmWorkspaceFileSystem(workspaceRoot),
        indexEngine = index,
        searchEngine = index,
        syncEngine = createSyncEngine(),
    )

    init {
        // 先准备真实的无 PIN 工作区，再关闭；App 启动必须自行走最近工作区认证入口。
        runBlocking {
            checkNotNull(gateway.prepareCreation(null))
            gateway.confirmCreation()
            gateway.lock()
        }
    }

    /** 释放会话后只清理本测试创建的目录，防止锁定的 SQLite 文件阻止清理。 */
    override fun close() {
        runBlocking {
            gateway.lock()
            capabilities.syncEngine.close()
        }
        check(root.parent == Path.of(System.getProperty("java.io.tmpdir")).toAbsolutePath().normalize())
        check(root.fileName.toString().startsWith(TEST_PREFIX))
        Files.walk(root).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists) }
    }

    private companion object {
        /** 仅清理当前 UI 验证创建的临时目录。 */
        const val TEST_PREFIX = "tagloom-settings-ui-test-"

        /**
         * 为真实加密测试降低 Argon2id 的资源成本。
         *
         * @param salt 实际安全随机生成的 salt。
         * @return 不用于生产的低成本 KDF 参数。
         */
        fun testArgonParameters(salt: ByteArray): Argon2idParameters =
            Argon2idParameters(salt, memoryKiB = 8, iterations = 1, parallelism = 1)
    }
}

/** 为 UI 测试提供确定性文件选择结果，备份内容仍由产品服务实际生成。 */
private class SettingsTestDocuments(
    /** 测试专用工作区目录。 */
    private val workspace: Path,
    /** 测试专用密文备份文件。 */
    private val backup: Path,
) : WorkspaceDocumentAccess {
    /** @return 独立的测试工作区目录。 */
    override suspend fun creationDirectory(): Path = workspace

    /** @return 同一测试工作区，供重新认证使用。 */
    override suspend fun recoveryDirectory(): Path = workspace

    /**
     * 执行产品备份逻辑并将可观察结果写入测试目录。
     * @param writeArchive 产品密文备份写入动作。
     * @return 备份完成时为 true。
     */
    override suspend fun exportBackup(writeArchive: suspend (Path) -> Unit): Boolean {
        writeArchive(backup)
        return true
    }

    /**
     * 当前设置验收不触发恢复导入。
     * @param restore 产品恢复动作。
     * @return 空值表示取消导入。
     */
    override suspend fun <T> importBackup(restore: suspend (Path, Path) -> T): T? = null
}
