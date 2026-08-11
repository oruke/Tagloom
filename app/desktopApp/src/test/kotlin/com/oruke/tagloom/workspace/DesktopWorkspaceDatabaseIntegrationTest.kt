package com.oruke.tagloom.workspace

import com.oruke.tagloom.core.crypto.Argon2idParameters
import com.oruke.tagloom.core.model.DatabaseDefinition
import com.oruke.tagloom.core.model.DatabaseId
import com.oruke.tagloom.core.model.FieldRef
import com.oruke.tagloom.core.model.FieldRefKinds
import com.oruke.tagloom.core.model.Note
import com.oruke.tagloom.core.model.SystemFieldNames
import com.oruke.tagloom.core.model.ViewDefinition
import com.oruke.tagloom.core.model.ViewField
import com.oruke.tagloom.core.model.ViewId
import com.oruke.tagloom.core.model.ViewPresentation
import com.oruke.tagloom.core.workspace.WorkspaceDefaults
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 使用真实密码学、文件事务和会话重开验证 Database/View 权威持久化闭环。
 */
class DesktopWorkspaceDatabaseIntegrationTest {
    /**
     * 验证 Database 与首个 View 原子创建后均为密文，并可跨锁定重启继续更新。
     */
    @Test
    fun persistsEncryptedDatabaseCatalogAcrossLockedRestart() = withWorkspace { root ->
        val ids = AtomicInteger()
        val manager = testManager(ids)
        manager.prepareCreation(root, TEST_PASSWORD)
        manager.confirmCreation()
        val database = testDatabase()
        val view = testView()
        val (storedDatabase, storedView) = try {
            requireNotNull(manager.sessionOrNull()).createDatabaseCatalog(database, view)
        } finally {
            manager.lock()
        }

        assertEquals(database, storedDatabase.snapshot.payload)
        assertEquals(view, storedView.snapshot.payload)
        val databaseFile = root.resolve("databases/${database.id.value}.database.enc")
        val viewFile = root.resolve("views/${view.id.value}.view.enc")
        assertTrue(Files.isRegularFile(databaseFile))
        assertTrue(Files.isRegularFile(viewFile))
        assertFalse(Files.readAllBytes(databaseFile).decodeToString().contains(database.name))
        assertFalse(Files.readAllBytes(viewFile).decodeToString().contains(view.name))

        val reopenedManager = testManager(ids)
        val renamedView = view.copy(name = "项目看板")
        val galleryView = renamedView.copy(
            id = TEST_SECOND_VIEW_ID,
            name = "项目画廊",
            orderKey = "0000000001",
        )
        val (reopenedState, savedViews) = try {
            val state = reopenedManager.unlock(root, TEST_PASSWORD)
            val reopenedSession = requireNotNull(reopenedManager.sessionOrNull())
            val saved = reopenedSession.saveViewDefinition(renamedView)
            val created = reopenedSession.createViewDefinition(galleryView)
            state to listOf(saved, created)
        } finally {
            reopenedManager.lock()
        }

        assertEquals(database, reopenedState.databaseDefinitions.single().snapshot.payload)
        assertEquals(view, reopenedState.viewDefinitions.single().snapshot.payload)
        assertEquals(listOf(renamedView, galleryView), savedViews.map { stored -> stored.snapshot.payload })

        val verifiedManager = testManager(ids)
        val verifiedState = try {
            verifiedManager.unlock(root, TEST_PASSWORD)
        } finally {
            verifiedManager.lock()
        }
        assertEquals(
            listOf("项目看板", "项目画廊"),
            verifiedState.viewDefinitions.map { stored -> stored.snapshot.payload.name },
        )
    }

    /**
     * 验证数据集单元格写回所依赖的 expectedRevision 会拒绝过期快照覆盖。
     */
    @Test
    fun rejectsStaleNoteRevisionDuringDatasetWriteBack() = withWorkspace { root ->
        val ids = AtomicInteger()
        val manager = testManager(ids)
        manager.prepareCreation(root, TEST_PASSWORD)
        manager.confirmCreation()
        try {
            val session = requireNotNull(manager.sessionOrNull())
            val initial = session.saveNote(
                objectId = null,
                note = Note(title = "第一版", directTagIds = emptyList()),
            )
            val current = session.saveNote(
                objectId = initial.id,
                note = initial.snapshot.payload.copy(title = "第二版"),
                expectedRevision = initial.snapshot.header.revision,
            )

            assertEquals("第二版", current.snapshot.payload.title)
            assertFailsWith<IllegalArgumentException> {
                session.saveNote(
                    objectId = initial.id,
                    note = initial.snapshot.payload.copy(title = "过期覆盖"),
                    expectedRevision = initial.snapshot.header.revision,
                )
            }
            assertEquals("第二版", session.loadNotes().single().snapshot.payload.title)
        } finally {
            manager.lock()
        }
    }

    /**
     * 构造测试用 Database 权威定义。
     *
     * @return 使用默认 Note 类型作为来源和创建类型的数据集。
     */
    private fun testDatabase(): DatabaseDefinition = DatabaseDefinition(
        id = TEST_DATABASE_ID,
        name = "项目数据库",
        sourceTypeId = WorkspaceDefaults.DEFAULT_NOTE_TAG_ID,
        includeDescendants = true,
        creationTypeId = WorkspaceDefaults.DEFAULT_NOTE_TAG_ID,
        defaultViewId = TEST_VIEW_ID,
    )

    /**
     * 构造只显示标题列的首个 Table View。
     *
     * @return 与测试 Database 关联的合法视图。
     */
    private fun testView(): ViewDefinition = ViewDefinition(
        id = TEST_VIEW_ID,
        databaseId = TEST_DATABASE_ID,
        name = "全部项目",
        orderKey = "0000000000",
        presentation = ViewPresentation(
            fields = listOf(
                ViewField(
                    field = FieldRef(
                        kind = FieldRefKinds.SYSTEM,
                        systemField = SystemFieldNames.TITLE,
                    ),
                    orderKey = "0000000000",
                ),
            ),
        ),
    )

    /**
     * 构造使用稳定时间与低成本测试 KDF 的桌面 Manager。
     *
     * @param ids 跨锁定重启共享的不透明 ID 计数器。
     * @return 使用真实文件与密码学适配器的 Manager。
     */
    private fun testManager(ids: AtomicInteger): DesktopWorkspaceManager = DesktopWorkspaceManager(
        idFactory = { "database-test-id-${ids.incrementAndGet()}" },
        currentTimeMillis = { TEST_TIME_MILLIS + ids.get() },
        timestampProvider = { TEST_TIMESTAMP },
        argon2idFactory = { salt ->
            Argon2idParameters(
                salt = salt,
                memoryKiB = 8,
                iterations = 1,
                parallelism = 1,
            )
        },
    )

    /**
     * 在独立临时目录执行测试并仅清理本测试创建的路径。
     *
     * @param block 使用临时工作区的测试主体。
     */
    private fun withWorkspace(block: suspend (Path) -> Unit) = runBlocking {
        val root = Files.createTempDirectory(TEST_DIRECTORY_PREFIX).toAbsolutePath().normalize()
        try {
            block(root)
        } finally {
            deleteTestTree(root)
        }
    }

    /**
     * 安全删除带固定测试前缀的临时目录。
     *
     * @param root 待删除测试根目录。
     */
    private fun deleteTestTree(root: Path) {
        val normalized = root.toAbsolutePath().normalize()
        check(normalized.fileName.toString().startsWith(TEST_DIRECTORY_PREFIX)) { "拒绝删除非测试目录" }
        if (!Files.exists(normalized)) {
            return
        }
        Files.walk(normalized).use { stream ->
            stream.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists)
        }
    }

    private companion object {
        /** 脱敏测试密码，不用于任何真实工作区。 */
        const val TEST_PASSWORD: String = "test-only-database-password"

        /** 临时目录固定前缀。 */
        const val TEST_DIRECTORY_PREFIX: String = "tagloom-database-session-test-"

        /** 稳定物理时间，避免测试结果依赖系统时钟。 */
        const val TEST_TIME_MILLIS: Long = 1_789_200_000_000L

        /** 稳定提交时间，避免快照断言受时区影响。 */
        const val TEST_TIMESTAMP: String = "2026-09-12T00:00:00Z"

        /** 测试 Database ID。 */
        val TEST_DATABASE_ID: DatabaseId = DatabaseId("database-projects")

        /** 测试 View ID。 */
        val TEST_VIEW_ID: ViewId = ViewId("view-all-projects")

        /** 测试新增 View ID。 */
        val TEST_SECOND_VIEW_ID: ViewId = ViewId("view-project-gallery")
    }
}
