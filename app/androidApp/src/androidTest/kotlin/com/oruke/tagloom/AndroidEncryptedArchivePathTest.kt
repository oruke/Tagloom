package com.oruke.tagloom

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.oruke.tagloom.capability.AndroidTagloomEnvironment
import com.oruke.tagloom.capability.TagloomCapabilities
import com.oruke.tagloom.capability.createAndroidTagloomCapabilities
import com.oruke.tagloom.core.index.SearchIndexQuery
import com.oruke.tagloom.core.model.*
import com.oruke.tagloom.filesystem.jvm.createEncryptedTemporaryArchive
import com.oruke.tagloom.workspace.WorkspaceDocumentAccess
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import kotlin.test.assertFailsWith

/** 在真实 Android 缓存别名、密文备份恢复和恶意链接场景上验证临时归档边界。 */
@RunWith(AndroidJUnit4::class)
class AndroidEncryptedArchivePathTest {
    /** 默认临时缓存别名不阻止密文备份恢复；任意目标父链接与工作区内链接仍须拒绝。 */
    @Test
    fun restoresThroughSystemTemporaryAliasAndRejectsBusinessPathLinks(): Unit = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val area = AndroidTestArea()
        val archive = createEncryptedTemporaryArchive("tagloom-path-regression-")
        val restoredRoot = area.root.resolve("restored-real-archive")
        var backupTarget = archive
        var capabilities: TagloomCapabilities? = null
        try {
            AndroidTagloomEnvironment.initialize(area.context)
            val documents = object : WorkspaceDocumentAccess by area.documents {
                /**
                 * 将正式密文备份写入当前测试指定的归档路径。
                 * @param writeArchive 正式密文备份流程。
                 * @return 成功写入实际临时归档时为真。
                 */
                override suspend fun exportBackup(writeArchive: suspend (Path) -> Unit): Boolean {
                    writeArchive(backupTarget)
                    return true
                }

                /**
                 * 从测试准备的临时归档恢复到隔离目录并执行完整认证。
                 * @param restore 正式密文恢复与认证流程。
                 * @return 在本测试新目录认证后的结果。
                 */
                override suspend fun <T> importBackup(restore: suspend (Path, Path) -> T): T = restore(archive, restoredRoot)
            }
            val active = createAndroidTagloomCapabilities(documents)
            capabilities = active
            val prepared = requireNotNull(active.workspace.prepareCreation(null))
            active.workspace.confirmCreation()
            val saved = active.workspace.saveNote(null, Note("Temporary archive fixture", emptyList(), blocks = listOf(
                Block(BlockId("archive-fixture-block"), BlockTypes.PARAGRAPH, orderKey = "a",
                    content = RichTextBlockContent(RichText(listOf(InlineRun(FIXTURE_TEXT))))),
            )))

            // 使用与服务器同步相同的默认临时归档分配器，必须通过原有严格备份校验并完成恢复认证。
            check(archive == archive.toRealPath()) { "archive_not_resolved" }
            check(active.workspace.createBackup()) { "archive_export_failed" }
            val encrypted = Files.readAllBytes(archive)
            check(!encrypted.decodeToString().contains(FIXTURE_TEXT)) { "archive_contains_plaintext" }
            val originalDigest = MessageDigest.getInstance("SHA-256").digest(encrypted)
            active.workspace.lock()
            val restored = requireNotNull(active.workspace.restoreBackup(prepared.recoveryKeyText, null))
            check(restored.notes.single().id == saved.id && restored.notes.single().note == saved.note) { "archive_restore_content_mismatch" }
            check(active.searchEngine.search(SearchIndexQuery(text = FIXTURE_TEXT)).single().noteId == saved.id) { "archive_restore_index_mismatch" }

            // 用户提供的目标不能因本次临时文件修复被规范化放行，已有父级链接仍原样拒绝。
            val outside = Files.createDirectory(area.root.resolve("outside"))
            val redirected = area.root.resolve("redirected-output")
            Files.createSymbolicLink(redirected, outside)
            backupTarget = redirected.resolve("unexpected.zip")
            assertFailsWith<IllegalArgumentException> { active.workspace.createBackup() }
            check(!Files.exists(outside.resolve("unexpected.zip"))) { "linked_destination_was_written" }

            // 链接指向完全相同的合法密文，仍必须因工作区边界被拒绝，不能仅依赖摘要不同才阻止它。
            val notePath = restoredRoot.resolve("notes/${saved.id.value}.note.enc")
            val outsideCiphertext = area.root.resolve("outside-note.enc")
            Files.copy(notePath, outsideCiphertext)
            Files.delete(notePath)
            Files.createSymbolicLink(notePath, outsideCiphertext)
            backupTarget = archive
            assertFailsWith<IllegalArgumentException> { active.workspace.createBackup() }
            check(originalDigest.contentEquals(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(archive)))) {
                "rejected_backup_replaced_archive"
            }
        } finally {
            capabilities?.workspace?.lock()
            capabilities?.syncEngine?.close()
            AndroidTagloomEnvironment.initialize(instrumentation.targetContext)
            Files.deleteIfExists(archive)
            area.close()
        }
    }

    private companion object {
        /** 仅用于核对实际持久化和索引的脱敏正文。 */
        const val FIXTURE_TEXT: String = "archiveregressionplaceholder"
    }
}
