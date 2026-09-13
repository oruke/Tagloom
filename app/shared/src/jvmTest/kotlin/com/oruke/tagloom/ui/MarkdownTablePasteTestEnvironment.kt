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

/** 使用真实文件、libsodium、DPAPI 和 SQLite 验证粘贴结果；仅替换系统目录选择。 */
internal class MarkdownTablePasteTestEnvironment : AutoCloseable {
    /** 所有测试数据都限制在本次随机创建的临时目录中。 */
    private val root: Path = Files.createTempDirectory(TEST_PREFIX).toAbsolutePath().normalize()
    /** 真实工作区快照与派生索引所在目录。 */
    val workspaceRoot: Path = Files.createDirectory(root.resolve("workspace"))
    /** 与生产环境一致的密码学实现。 */
    private val crypto = LibsodiumCryptoProvider()
    /** 使用低成本测试 KDF 参数，其他创建、恢复及事务流程保持真实。 */
    private val manager = DesktopWorkspaceManager(cryptoProvider = crypto, argon2idFactory = ::testArgonParameters)
    /** 独立登记文件避免访问或改变用户的最近工作区。 */
    private val accessStore = DesktopWorkspaceAccessStore(
        registryFile = root.resolve("access.json"),
        cryptoProvider = crypto,
        argon2idFactory = ::testArgonParameters,
    )
    /** 真实生产入口，负责工作区认证、笔记事务和最终快照读取。 */
    val gateway = DesktopWorkspaceGateway(
        manager = manager,
        localAccessService = DesktopWorkspaceLocalAccessService(accessStore),
        documents = MarkdownPasteTestDocuments(workspaceRoot),
    )
    /** 绑定当前工作区的真实加密派生索引。 */
    private val index = JvmWorkspaceIndexEngine(gateway)
    /** 生产 App 所需的三项能力及工作区编排入口。 */
    val capabilities = TagloomCapabilities(
        workspace = gateway,
        fileSystem = JvmWorkspaceFileSystem(workspaceRoot),
        indexEngine = index,
        searchEngine = index,
        syncEngine = createSyncEngine(),
    )

    init {
        // 只预建空工作区；App 必须通过真实最近工作区入口重新认证并创建笔记。
        runBlocking {
            checkNotNull(gateway.prepareCreation(null))
            gateway.confirmCreation()
            gateway.lock()
        }
    }

    /** 释放写入租约和数据库后，只清理本测试拥有的临时目录。 */
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
        /** 测试清理边界使用的目录前缀。 */
        const val TEST_PREFIX = "tagloom-markdown-paste-test-"

        /**
         * 降低测试 KDF 成本，同时保留真实 Argon2id 算法和随机 salt。
         * @param salt 生产安全随机源生成的 salt。
         * @return 仅用于当前集成测试的低成本参数。
         */
        fun testArgonParameters(salt: ByteArray): Argon2idParameters =
            Argon2idParameters(salt, memoryKiB = 8, iterations = 1, parallelism = 1)
    }
}

/** 仅把用户目录选择固定到测试目录，正式业务服务不做替换。 */
private class MarkdownPasteTestDocuments(
    /** 本次测试拥有的工作区目录。 */
    private val workspace: Path,
) : WorkspaceDocumentAccess {
    /** @return 供生产创建流程使用的隔离工作区目录。 */
    override suspend fun creationDirectory(): Path = workspace

    /** @return 供生产认证流程使用的同一工作区目录。 */
    override suspend fun recoveryDirectory(): Path = workspace

    /**
     * 当前场景不选择备份导出目标。
     * @param writeArchive 生产密文归档写入动作。
     * @return false 表示用户未选择导出目标。
     */
    override suspend fun exportBackup(writeArchive: suspend (Path) -> Unit): Boolean = false

    /**
     * 当前场景不选择恢复归档。
     * @param restore 生产归档恢复动作。
     * @return null 表示用户未选择恢复归档。
     */
    override suspend fun <T> importBackup(restore: suspend (Path, Path) -> T): T? = null
}
