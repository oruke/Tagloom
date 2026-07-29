package com.oruke.tagloom.workspace

import com.oruke.tagloom.core.crypto.Argon2idParameters
import com.oruke.tagloom.core.crypto.CryptoProvider
import com.oruke.tagloom.core.index.SearchIndexQuery
import com.oruke.tagloom.core.model.Block
import com.oruke.tagloom.core.model.BlockId
import com.oruke.tagloom.core.model.BlockTypes
import com.oruke.tagloom.core.model.InlineRun
import com.oruke.tagloom.core.model.Note
import com.oruke.tagloom.core.model.RichText
import com.oruke.tagloom.core.model.RichTextBlockContent
import com.oruke.tagloom.core.transaction.ChangeLogCompactionPolicy
import com.oruke.tagloom.core.workspace.ManifestObjectKind
import com.oruke.tagloom.core.workspace.WorkspaceManifestPayload
import com.oruke.tagloom.core.workspace.WorkspaceManifestService
import com.oruke.tagloom.crypto.libsodium.LibsodiumCryptoProvider
import java.nio.file.Files
import java.nio.file.Path
import java.util.Comparator
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

/**
 * 在真实 manifest、加密快照和 SQLite 索引上验证 Change Log checkpoint 压缩。
 */
class DesktopChangeLogCompactionIntegrationTest {
    /**
     * 验证压缩前后当前快照和查询相同，且压缩后新编辑能够从 checkpoint 形成新 Change 根。
     */
    @Test
    fun compactionPreservesCurrentSnapshotsAndRebuildableQueries() = withWorkspace { root ->
        val ids = AtomicInteger()
        val provider = LibsodiumCryptoProvider()
        val manager = testManager(provider, ids)
        manager.prepareCreation(root, TEST_PASSWORD)
        manager.confirmCreation()
        val session = requireNotNull(manager.sessionOrNull())
        val created = session.saveNote(null, note("压缩前标题", "checkpoint 保留当前内容"))
        val updated = session.saveNote(created.id, note("压缩后仍一致", "checkpoint 保留当前内容"))
        val beforeNotes = session.loadNotes()
        val beforeSearch = session.search(SearchIndexQuery(text = "checkpoint 保留当前内容"))
        val manifestBefore = manifestPayload(provider, root)
        val changeCountBefore = manifestBefore.objects.count { record -> record.kind == ManifestObjectKind.CHANGE }

        val result = session.compactChangeLog(
            ChangeLogCompactionPolicy(
                undoRetentionExpired = true,
                archivedToHistoryBackup = false,
                syncEnabled = false,
            ),
        )

        assertEquals(changeCountBefore, result.removedPaths.size)
        assertEquals(beforeNotes, session.loadNotes())
        assertEquals(beforeSearch, session.search(SearchIndexQuery(text = "checkpoint 保留当前内容")))
        assertTrue(Files.isRegularFile(root.resolve("history/checkpoints/${result.checkpoint.checkpointId}.audit.enc")))
        result.removedPaths.forEach { path -> assertFalse(Files.exists(root.resolve(path.value))) }
        val compactedManifest = manifestPayload(provider, root)
        assertTrue(compactedManifest.objects.none { record -> record.kind == ManifestObjectKind.CHANGE })
        assertTrue(compactedManifest.objects.any { record ->
            record.kind == ManifestObjectKind.HISTORY && record.objectId == result.checkpoint.checkpointId
        })

        // checkpoint 已覆盖旧 head；后续写入必须能形成新根而不是引用已删除 Change。
        val postCheckpoint = session.saveNote(updated.id, note("压缩后继续编辑", "checkpoint 后的新变更"))
        assertEquals(updated.id, postCheckpoint.id)
        manager.lock()

        deleteIndexDirectory(root.resolve("index"))
        val reopenedManager = testManager(provider, ids)
        val reopened = reopenedManager.unlock(root, TEST_PASSWORD)
        assertEquals("压缩后继续编辑", reopened.notes.single().snapshot.payload.title)
        assertEquals(
            updated.id,
            requireNotNull(reopenedManager.sessionOrNull())
                .search(SearchIndexQuery(text = "checkpoint 后的新变更"))
                .single()
                .noteId,
        )
        reopenedManager.lock()
    }

    /**
     * 使用独立会话认证并读取 manifest 载荷。
     *
     * @param provider 与工作区一致的密码学端口。
     * @param root 工作区根目录。
     * @return 当前认证 manifest 载荷。
     */
    private suspend fun manifestPayload(
        provider: CryptoProvider,
        root: Path,
    ): WorkspaceManifestPayload {
        val unlocked = WorkspaceManifestService(provider).unlockWithPassword(
            manifestBytes = Files.readAllBytes(root.resolve("manifest.enc")),
            password = TEST_PASSWORD,
        )
        return try {
            unlocked.payload
        } finally {
            unlocked.destroy()
        }
    }

    /**
     * 构造使用真实适配器、稳定时间和低成本测试 KDF 的 Manager。
     *
     * @param provider 共享密码学端口。
     * @param ids 不透明 ID 计数器。
     * @return 桌面工作区 Manager。
     */
    private fun testManager(
        provider: CryptoProvider,
        ids: AtomicInteger,
    ): DesktopWorkspaceManager = DesktopWorkspaceManager(
        cryptoProvider = provider,
        idFactory = { "compaction-id-${ids.incrementAndGet()}" },
        currentTimeMillis = { 1_789_200_000_000L + ids.get() },
        timestampProvider = { "2026-09-12T00:00:00Z" },
        argon2idFactory = { salt ->
            Argon2idParameters(salt = salt, memoryKiB = 8, iterations = 1, parallelism = 1)
        },
    )

    /**
     * 构造一篇单段落 Note。
     *
     * @param title Note 标题。
     * @param text 可检索正文。
     * @return 合法 Note。
     */
    private fun note(
        title: String,
        text: String,
    ): Note = Note(
        title = title,
        directTagIds = emptyList(),
        blocks = listOf(
            Block(
                id = BlockId("compaction-block"),
                type = BlockTypes.PARAGRAPH,
                orderKey = "0000000000",
                content = RichTextBlockContent(RichText(listOf(InlineRun(text)))),
            ),
        ),
    )

    /**
     * 删除已经关闭连接的派生索引目录，模拟从零重建。
     *
     * @param indexDirectory 固定工作区 index 路径。
     */
    private fun deleteIndexDirectory(indexDirectory: Path) {
        if (!Files.exists(indexDirectory)) {
            return
        }
        Files.walk(indexDirectory).use { paths ->
            paths.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists)
        }
    }

    /**
     * 在受控临时目录执行测试并完成清理。
     *
     * @param block 使用工作区根目录的测试主体。
     */
    private fun withWorkspace(block: suspend (Path) -> Unit): Unit = runBlocking {
        val root = Files.createTempDirectory(TEST_DIRECTORY_PREFIX).toAbsolutePath().normalize()
        try {
            block(root)
        } finally {
            deleteWorkspace(root)
        }
    }

    /**
     * 安全删除固定前缀的测试工作区。
     *
     * @param root 待删除临时目录。
     */
    private fun deleteWorkspace(root: Path) {
        val normalized = root.toAbsolutePath().normalize()
        check(normalized.fileName.toString().startsWith(TEST_DIRECTORY_PREFIX)) { "拒绝删除非测试目录" }
        if (!Files.exists(normalized)) {
            return
        }
        Files.walk(normalized).use { paths ->
            paths.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists)
        }
    }

    private companion object {
        /** 脱敏测试密码。 */
        const val TEST_PASSWORD: String = "compaction-test-password"

        /** 受控临时目录前缀。 */
        const val TEST_DIRECTORY_PREFIX: String = "tagloom-compaction-test-"
    }
}
