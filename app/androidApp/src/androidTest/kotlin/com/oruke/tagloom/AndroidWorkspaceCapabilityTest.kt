package com.oruke.tagloom

import android.content.Context
import android.content.ContextWrapper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.oruke.tagloom.capability.AndroidTagloomEnvironment
import com.oruke.tagloom.capability.TagloomCapabilities
import com.oruke.tagloom.capability.createAndroidTagloomCapabilities
import com.oruke.tagloom.core.index.SearchIndexQuery
import com.oruke.tagloom.core.model.Block
import com.oruke.tagloom.core.model.BlockId
import com.oruke.tagloom.core.model.BlockTypes
import com.oruke.tagloom.core.model.InlineRun
import com.oruke.tagloom.core.model.Note
import com.oruke.tagloom.core.model.RichText
import com.oruke.tagloom.core.model.RichTextBlockContent
import com.oruke.tagloom.workspace.WorkspaceDocumentAccess
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.util.Comparator
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** 从生产 Android 能力工厂验证真实文件、Keystore、SQLCipher 和工作区服务闭环。 */
@RunWith(AndroidJUnit4::class)
class AndroidWorkspaceCapabilityTest {
    /** 验证创建、加密保存、搜索、租约、锁定、重开、PIN、索引重建和密文备份恢复。 */
    @Test
    fun persistsSearchesAndRecoversThroughProductionCapabilities(): Unit = runBlocking {
        val area = AndroidTestArea()
        var capabilities: TagloomCapabilities? = null
        try {
            AndroidTagloomEnvironment.initialize(area.context)
            val first = createAndroidTagloomCapabilities(area.documents)
            capabilities = first
            val prepared = assertNotNull(first.workspace.prepareCreation(null))
            assertTrue(prepared.recoveryKeyText.startsWith("TLRK1-"))
            assertFalse(Files.exists(area.documents.root.resolve("manifest.enc")))
            assertFalse(first.workspace.confirmCreation().readOnly)

            val saved = first.workspace.saveNote(null, testNote())
            assertEquals(saved.id, first.searchEngine.search(SearchIndexQuery(text = "android-placeholder")).single().noteId)
            assertFailsWith<IllegalStateException> { first.fileSystem.acquireWriteLease() }
            assertFalse(Files.readAllBytes(area.documents.root.resolve("notes/${saved.id.value}.note.enc"))
                .decodeToString().contains("android-placeholder"))
            assertFalse(Files.readAllBytes(area.documents.root.resolve("index/local.sqlite"))
                .decodeToString().contains("SQLite format 3"))
            assertTrue(first.workspace.createBackup())
            first.workspace.lock()
            assertFailsWith<IllegalArgumentException> { first.searchEngine.search(SearchIndexQuery()) }

            // 重建能力容器证明密钥从 Android Keystore 登记重新读取，而不是复用旧会话。
            val reopened = createAndroidTagloomCapabilities(area.documents)
            capabilities = reopened
            assertFalse(assertNotNull(reopened.workspace.recentWorkspaceOrNull()).requiresPin)
            assertEquals(saved.id, reopened.workspace.openRecentWorkspace().notes.single().id)
            reopened.workspace.enablePin(TEST_PIN)
            reopened.workspace.lock()
            assertFailsWith<RuntimeException> { reopened.workspace.unlockRecentWorkspace(WRONG_PIN) }
            assertEquals(saved.id, reopened.workspace.unlockRecentWorkspace(TEST_PIN).notes.single().id)
            reopened.workspace.disablePin(TEST_PIN)
            reopened.workspace.lock()

            // SQLCipher 页损坏属于可重建派生状态，不允许驱动自行删除权威工作区文件。
            Files.write(area.documents.root.resolve("index/local.sqlite"), byteArrayOf(1, 2, 3))
            assertEquals(saved.id, reopened.workspace.openRecentWorkspace().notes.single().id)
            assertEquals(saved.id, reopened.searchEngine.search(SearchIndexQuery(text = "android-placeholder")).single().noteId)
            reopened.workspace.lock()

            // 派生索引删除后必须只从 manifest 确认的快照恢复全文数据。
            Files.delete(area.documents.root.resolve("index/local.sqlite"))
            assertEquals(saved.id, reopened.workspace.openRecentWorkspace().notes.single().id)
            assertEquals(saved.id, reopened.searchEngine.search(SearchIndexQuery(text = "android-placeholder")).single().noteId)
            reopened.workspace.lock()
            val restored = assertNotNull(reopened.workspace.restoreBackup(prepared.recoveryKeyText, null))
            assertFalse(restored.readOnly)
            assertEquals(saved.note, restored.notes.single().note)
            assertEquals(saved.id, reopened.searchEngine.search(SearchIndexQuery(text = "android-placeholder")).single().noteId)
            val rotated = reopened.workspace.rotateMasterKey(null)
            assertTrue(rotated.recoveryKeyText != prepared.recoveryKeyText)
            assertEquals(saved.id, rotated.openResult.notes.single().id)
        } finally {
            capabilities?.workspace?.lock()
            capabilities?.syncEngine?.close()
            area.close()
        }
    }

    /** 验证损坏 prepared 的真实隔离、只读状态和写入拒绝。 */
    @Test
    fun quarantinesCorruptPreparedBeforeAllowingWrites(): Unit = runBlocking {
        val area = AndroidTestArea()
        var capabilities: TagloomCapabilities? = null
        try {
            AndroidTagloomEnvironment.initialize(area.context)
            val first = createAndroidTagloomCapabilities(area.documents)
            capabilities = first
            first.workspace.prepareCreation(null)
            first.workspace.confirmCreation()
            val saved = first.workspace.saveNote(null, testNote())
            first.workspace.lock()
            val staging = area.documents.root.resolve("staging/interrupted-test")
            Files.createDirectories(staging)
            Files.write(staging.resolve("prepared.json"), byteArrayOf(1, 2, 3))
            val reopened = createAndroidTagloomCapabilities(area.documents)
            capabilities = reopened
            val result = reopened.workspace.openRecentWorkspace()
            assertTrue(result.readOnly)
            assertTrue(result.recoveryMessages.isNotEmpty())
            assertFalse(Files.exists(staging))
            assertTrue(Files.list(area.documents.root.resolve("history/quarantine")).use { it.findAny().isPresent })
            assertFailsWith<IllegalStateException> { reopened.workspace.saveNote(saved.id, saved.note, saved.revision) }
        } finally {
            capabilities?.workspace?.lock()
            area.close()
        }
    }

    /**
     * 创建不包含真实业务信息的测试笔记。
     * @return 带可验证全文的标准 Note。
     */
    private fun testNote(): Note = Note(
        title = "android-placeholder",
        directTagIds = emptyList(),
        blocks = listOf(Block(
            id = BlockId("placeholder-block"),
            type = BlockTypes.PARAGRAPH,
            orderKey = "0000000000",
            content = RichTextBlockContent(RichText(listOf(InlineRun("android-placeholder content")))),
        )),
    )

    private companion object {
        /** 仅用于隔离测试目录的脱敏 PIN。 */
        const val TEST_PIN: String = "135790"
        /** 用于验证错误 PIN 拒绝的脱敏输入。 */
        const val WRONG_PIN: String = "246801"
    }
}

/** 隔离 Android 测试目录，保留真实 ContentResolver、Keystore 和原生文件系统。 */
internal class AndroidTestArea {
    /** 实际设备目标应用的 Context。 */
    private val target = InstrumentationRegistry.getInstrumentation().targetContext
    /** 仅本次测试创建的随机私有目录。 */
    val root: Path = Files.createTempDirectory(target.cacheDir.canonicalFile.toPath(), "android-capability-test-")
    /** 只替换目录路由，密码学和数据库仍使用真实 Android 运行时。 */
    val context: Context = object : ContextWrapper(target) {
        /** @return 隔离目录路由的 Application 环境。 */
        override fun getApplicationContext(): Context = this
        /** @return 测试私有数据目录。 */
        override fun getNoBackupFilesDir(): File = root.resolve("private").toFile().apply { mkdirs() }
        /** @return 测试缓存目录。 */
        override fun getCacheDir(): File = root.resolve("cache").toFile().apply { mkdirs() }
    }
    /** 代替用户选择路径的确定性文档入口，不替代任何业务服务。 */
    val documents = TestWorkspaceDocuments(root)

    /** 清理且只清理本次测试生成的目录。 */
    fun close() {
        check(root.parent == target.cacheDir.canonicalFile.toPath() && root.fileName.toString().startsWith("android-capability-test-"))
        Files.walk(root).use { stream -> stream.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists) }
    }
}

/** 用固定测试路径提供与系统选择器等价的输入，实际备份和恢复仍由生产服务执行。 */
internal class TestWorkspaceDocuments(
    /** 本次测试唯一根目录。 */
    private val area: Path,
) : WorkspaceDocumentAccess {
    /** 与生产文件工厂绑定到同一个默认工作区。 */
    val root: Path = area.resolve("private/workspaces/default")
    /** 仅保存密文备份的测试目标。 */
    val archive: Path = area.resolve("backup.zip")
    /** 全新的恢复目录。 */
    val restored: Path = area.resolve("restored")

    /** @return 本次测试的新工作区目录。 */
    override suspend fun creationDirectory(): Path = root
    /** @return 本次测试需要重新认证的工作区目录。 */
    override suspend fun recoveryDirectory(): Path = root

    /**
     * 执行真实密文备份。
     * @param writeArchive 生产备份服务。
     * @return 写入完成为 true。
     */
    override suspend fun exportBackup(writeArchive: suspend (Path) -> Unit): Boolean {
        writeArchive(archive)
        return true
    }

    /**
     * 执行真实备份验证和恢复。
     * @param restore 生产恢复服务。
     * @return 已认证结果。
     */
    override suspend fun <T> importBackup(restore: suspend (Path, Path) -> T): T = restore(archive, restored)
}
