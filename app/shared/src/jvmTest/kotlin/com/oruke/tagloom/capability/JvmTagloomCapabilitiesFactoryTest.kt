package com.oruke.tagloom.capability

import com.oruke.tagloom.filesystem.jvm.JvmWorkspaceFileSystem
import com.oruke.tagloom.sync.jvm.JvmSyncEngine
import com.oruke.tagloom.workspace.DesktopWorkspaceGateway
import com.oruke.tagloom.workspace.JvmWorkspaceIndexEngine
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import java.nio.file.Path
import java.util.Comparator
import kotlin.test.Test
import kotlin.test.assertIs
import kotlin.test.assertSame

/**
 * 验证 JVM actual 工厂返回真实能力实现，而不是未装配占位。
 */
class JvmTagloomCapabilitiesFactoryTest {
    /**
     * 验证工作区尚未解锁时也能完成能力装配，索引与搜索共享同一代理。
     */
    @Test
    fun createsJvmImplementationsBeforeWorkspaceUnlock() = runBlocking {
        val previousRoot = System.getProperty("tagloom.workspace.root")
        val temporaryRoot = Files.createTempDirectory("tagloom-jvm-capability-test-")
        try {
            System.setProperty("tagloom.workspace.root", temporaryRoot.toString())
            val capabilities = createTagloomCapabilities()

            assertIs<DesktopWorkspaceGateway>(capabilities.workspace)
            assertIs<JvmWorkspaceFileSystem>(capabilities.fileSystem)
            assertIs<JvmWorkspaceIndexEngine>(capabilities.indexEngine)
            assertIs<JvmWorkspaceIndexEngine>(capabilities.searchEngine)
            assertIs<JvmSyncEngine>(capabilities.syncEngine)
            assertSame(capabilities.indexEngine, capabilities.searchEngine)

            capabilities.searchEngine.close()
            capabilities.syncEngine.close()
        } finally {
            if (previousRoot == null) {
                System.clearProperty("tagloom.workspace.root")
            } else {
                System.setProperty("tagloom.workspace.root", previousRoot)
            }
            deleteTemporaryRoot(temporaryRoot)
        }
    }

    /**
     * 删除本测试创建的临时目录，避免测试留下本机工作区文件。
     *
     * @param root 测试专用临时目录。
     */
    private fun deleteTemporaryRoot(root: Path) {
        if (!Files.exists(root)) return
        Files.walk(root).use { paths ->
            paths.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists)
        }
    }
}
