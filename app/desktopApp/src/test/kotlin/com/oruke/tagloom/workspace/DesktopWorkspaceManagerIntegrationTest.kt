package com.oruke.tagloom.workspace

import com.oruke.tagloom.core.crypto.Argon2idParameters
import com.oruke.tagloom.core.index.SearchIndexQuery
import com.oruke.tagloom.core.index.PropertyIndexFilter
import com.oruke.tagloom.core.model.Block
import com.oruke.tagloom.core.model.BlockId
import com.oruke.tagloom.core.model.BlockTypes
import com.oruke.tagloom.core.model.DefaultValuePolicy
import com.oruke.tagloom.core.model.InlineRun
import com.oruke.tagloom.core.model.Note
import com.oruke.tagloom.core.model.NotePropertyValue
import com.oruke.tagloom.core.model.PropertyValueSource
import com.oruke.tagloom.core.model.PropertyDefinition
import com.oruke.tagloom.core.model.PropertyId
import com.oruke.tagloom.core.model.PropertyOption
import com.oruke.tagloom.core.model.PropertyTypes
import com.oruke.tagloom.core.model.RichText
import com.oruke.tagloom.core.model.RichTextBlockContent
import com.oruke.tagloom.core.model.SavedQuery
import com.oruke.tagloom.core.model.TagDefinition
import com.oruke.tagloom.core.model.TagId
import com.oruke.tagloom.core.model.TagPropertyBinding
import com.oruke.tagloom.core.workspace.WorkspaceDefaults
import java.nio.file.Files
import java.nio.file.Path
import java.util.Comparator
import java.util.concurrent.atomic.AtomicInteger
import java.util.zip.ZipFile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonPrimitive

/**
 * 使用真实密码学、文件事务和加密 SQLite 验证桌面工作区重启垂直切片。
 */
class DesktopWorkspaceManagerIntegrationTest {
    /**
     * 验证应用冷启动后首次操作就是 Recovery Key 解锁时会先初始化密码学运行时。
     */
    @Test
    fun unlocksFreshManagerWithRecoveryKey() = withWorkspace { root ->
        val ids = AtomicInteger()
        val creationManager = testManager(ids)
        val prepared = creationManager.prepareCreation(root, TEST_PASSWORD)
        creationManager.confirmCreation()
        val saved = requireNotNull(creationManager.sessionOrNull()).saveNote(objectId = null, note = testNote())
        creationManager.lock()

        val recoveryManager = testManager(ids)
        val recovered = recoveryManager.unlockWithRecovery(root, prepared.recoveryKeyText)

        assertEquals(saved.id, recovered.notes.single().id)
        assertEquals("重启后仍可搜索", recovered.notes.single().snapshot.payload.title)
        recoveryManager.lock()
    }

    /**
     * 验证 Recovery Key 可以重建新的高熵本机解锁材料，同时不重写既有对象密文。
     */
    @Test
    fun recoveryKeyReplacesDeviceUnlockMaterialWithoutReencryptingObjects() = withWorkspace { root ->
        val ids = AtomicInteger()
        val creationManager = testManager(ids)
        val prepared = creationManager.prepareCreation(root, TEST_PASSWORD)
        creationManager.confirmCreation()
        val saved = requireNotNull(creationManager.sessionOrNull()).saveNote(objectId = null, note = testNote())
        val noteFile = root.resolve("notes/${saved.id.value}.note.enc")
        val noteBefore = Files.readAllBytes(noteFile)
        creationManager.lock()

        val recoveryManager = testManager(ids)
        val recovered = recoveryManager.recoverAndReplacePasswordSlot(
            root = root,
            recoveryKeyText = prepared.recoveryKeyText,
            newPassword = TEST_DEVICE_UNLOCK_SECRET,
        )

        assertEquals(saved.id, recovered.notes.single().id)
        assertTrue(noteBefore.contentEquals(Files.readAllBytes(noteFile)))
        recoveryManager.lock()

        assertFailsWith<RuntimeException> { testManager(ids).unlock(root, TEST_PASSWORD) }
        val reopenedManager = testManager(ids)
        val reopened = reopenedManager.unlock(root, TEST_DEVICE_UNLOCK_SECRET)
        assertEquals(saved.id, reopened.notes.single().id)
        reopenedManager.lock()
    }

    /**
     * 验证两阶段创建、保存、锁定、重新解锁和全文搜索形成完整闭环。
     */
    @Test
    fun persistsNoteAcrossLockedRestart() = withWorkspace { root ->
        val ids = AtomicInteger()
        val manager = testManager(ids)
        val prepared = manager.prepareCreation(root, TEST_PASSWORD)

        assertTrue(prepared.recoveryKeyText.startsWith("TLRK1-"))
        assertFalse(Files.exists(root.resolve("manifest.enc")))

        manager.confirmCreation()
        val session = requireNotNull(manager.sessionOrNull())
        val saved = session.saveNote(objectId = null, note = testNote())
        assertEquals("重启后仍可搜索", saved.snapshot.payload.title)
        assertEquals(1, session.search(SearchIndexQuery(text = "密文权威状态")).size)

        manager.lock()
        assertFailsWith<IllegalStateException> { session.loadNotes() }

        val reopenedManager = testManager(ids)
        val reopened = reopenedManager.unlock(root, TEST_PASSWORD)
        assertEquals(listOf("重启后仍可搜索"), reopened.notes.map { note -> note.snapshot.payload.title })
        assertEquals(saved.id, reopenedManager.sessionOrNull()?.search(SearchIndexQuery(text = "密文权威状态"))?.single()?.noteId)
        reopenedManager.lock()
    }

    /**
     * 验证密文备份排除派生状态，且可恢复到新目录后重新解锁、搜索。
     */
    @Test
    fun restoresEncryptedBackupAndRebuildsSearchIndex() = withTestArea { workspace, backup, restored ->
        val ids = AtomicInteger()
        val manager = testManager(ids)
        manager.prepareCreation(workspace, TEST_PASSWORD)
        manager.confirmCreation()
        val session = requireNotNull(manager.sessionOrNull())
        val saved = session.saveNote(objectId = null, note = testNote())
        val savedQuery = session.saveQuery(SavedQuery(name = "密文查询", text = "密文权威状态"))
        assertEquals(saved.id, session.runSavedQuery(savedQuery.id).single().noteId)

        val report = session.createBackup(backup)
        assertTrue(report.artifactCount >= 12)
        assertTrue(report.encryptedBytes > 0L)
        assertArchiveContainsOnlyAuthoritativeCiphertext(backup)
        assertTreeDoesNotContain(workspace, SENSITIVE_TEST_TEXTS)
        SENSITIVE_TEST_TEXTS.forEach { text ->
            assertFalse(Files.readAllBytes(backup).containsBytes(text.encodeToByteArray()))
        }
        assertEquals(
            saved.id,
            session.search(
                SearchIndexQuery(
                    propertyFilters = listOf(
                        PropertyIndexFilter(WorkspaceDefaults.DEFAULT_TEXT_PROPERTY_ID, "\"敏感属性值\""),
                    ),
                ),
            ).single().noteId,
        )
        manager.lock()

        val restoredManager = testManager(ids)
        val opened = restoredManager.restoreBackup(backup, restored, TEST_PASSWORD)
        assertEquals(saved.id, opened.notes.single().id)
        assertEquals(savedQuery.id, opened.savedQueries.single().id)
        assertEquals(
            saved.id,
            restoredManager.sessionOrNull()?.search(SearchIndexQuery(text = "密文权威状态"))?.single()?.noteId,
        )
        assertTrue(Files.isRegularFile(restored.resolve("index").resolve("local.sqlite")))
        restoredManager.lock()
    }

    /**
     * 验证更换密码只替换 manifest 密钥槽，对象密文保持逐字节不变。
     */
    @Test
    fun passwordChangeDoesNotReencryptObjectFiles() = withWorkspace { root ->
        val ids = AtomicInteger()
        val manager = testManager(ids)
        manager.prepareCreation(root, TEST_PASSWORD)
        manager.confirmCreation()
        val saved = requireNotNull(manager.sessionOrNull()).saveNote(null, testNote())
        val noteFile = root.resolve("notes/${saved.id.value}.note.enc")
        val noteBefore = Files.readAllBytes(noteFile)
        val manifestBefore = Files.readAllBytes(root.resolve("manifest.enc"))

        manager.changeActivePassword(TEST_PASSWORD, TEST_NEW_PASSWORD)

        assertEquals(noteBefore.toList(), Files.readAllBytes(noteFile).toList())
        assertFalse(manifestBefore.contentEquals(Files.readAllBytes(root.resolve("manifest.enc"))))
        manager.lock()

        val oldPasswordManager = testManager(ids)
        assertFailsWith<RuntimeException> { oldPasswordManager.unlock(root, TEST_PASSWORD) }
        val newPasswordManager = testManager(ids)
        val opened = newPasswordManager.unlock(root, TEST_NEW_PASSWORD)
        assertEquals(saved.id, opened.notes.single().id)
        newPasswordManager.lock()
    }

    /**
     * 验证单个 Note 密文损坏只隔离该对象，并从索引移除其旧明文投影。
     */
    @Test
    fun corruptedNoteIsIsolatedWhileHealthyNotesRemainAvailable() = withWorkspace { root ->
        val ids = AtomicInteger()
        val manager = testManager(ids)
        manager.prepareCreation(root, TEST_PASSWORD)
        manager.confirmCreation()
        val session = requireNotNull(manager.sessionOrNull())
        val corrupted = session.saveNote(null, testNote())
        val healthy = session.saveNote(null, healthyNote())
        manager.lock()

        val corruptedFile = root.resolve("notes/${corrupted.id.value}.note.enc")
        val damagedBytes = Files.readAllBytes(corruptedFile).also { bytes ->
            bytes[bytes.lastIndex] = (bytes.last().toInt() xor 1).toByte()
        }
        Files.write(corruptedFile, damagedBytes)

        val reopenedManager = testManager(ids)
        val opened = reopenedManager.unlock(root, TEST_PASSWORD)
        assertEquals(listOf(healthy.id), opened.notes.map(DesktopStoredNote::id))
        assertEquals(corrupted.id.value, opened.integrityIssues.single().objectId)
        assertEquals(DesktopIntegrityFailure.HASH_MISMATCH, opened.integrityIssues.single().failure)
        val reopened = requireNotNull(reopenedManager.sessionOrNull())
        assertTrue(reopened.search(SearchIndexQuery(text = "密文权威状态")).isEmpty())
        assertEquals(healthy.id, reopened.search(SearchIndexQuery(text = "可继续读取")).single().noteId)
        reopenedManager.lock()
    }

    /**
     * 验证加密 SQLite 文件损坏时只删除派生状态，并从权威快照自动重建搜索。
     */
    @Test
    fun corruptedIndexIsDiscardedAndRebuiltFromSnapshots() = withWorkspace { root ->
        val ids = AtomicInteger()
        val manager = testManager(ids)
        manager.prepareCreation(root, TEST_PASSWORD)
        manager.confirmCreation()
        val saved = requireNotNull(manager.sessionOrNull()).saveNote(null, testNote())
        manager.lock()

        val indexFile = root.resolve("index/local.sqlite")
        Files.write(indexFile, ByteArray(4096) { offset -> (offset * 31).toByte() })

        val reopenedManager = testManager(ids)
        val reopened = reopenedManager.unlock(root, TEST_PASSWORD)
        assertEquals(saved.id, reopened.notes.single().id)
        assertEquals(
            saved.id,
            requireNotNull(reopenedManager.sessionOrNull())
                .search(SearchIndexQuery(text = "密文权威状态"))
                .single()
                .noteId,
        )
        reopenedManager.lock()
    }

    /**
     * 验证错误密码、错误 Recovery Key 和损坏 manifest 的失败路径均不写工作区。
     */
    @Test
    fun credentialAndManifestFailuresDoNotModifyWorkspace() = withWorkspace { root ->
        val ids = AtomicInteger()
        val manager = testManager(ids)
        val prepared = manager.prepareCreation(root, TEST_PASSWORD)
        manager.confirmCreation()
        requireNotNull(manager.sessionOrNull()).saveNote(null, testNote())
        manager.lock()
        val beforeCredentials = snapshotTree(root)

        assertFailsWith<RuntimeException> { testManager(ids).unlock(root, "wrong-test-password") }
        val wrongRecoveryKey = prepared.recoveryKeyText.dropLast(1) +
            if (prepared.recoveryKeyText.last() == 'A') "B" else "A"
        assertFailsWith<RuntimeException> { testManager(ids).unlockWithRecovery(root, wrongRecoveryKey) }
        assertEquals(beforeCredentials, snapshotTree(root))

        val manifest = root.resolve("manifest.enc")
        Files.write(
            manifest,
            Files.readAllBytes(manifest).also { bytes ->
                bytes[bytes.lastIndex] = (bytes.last().toInt() xor 1).toByte()
            },
        )
        val damagedState = snapshotTree(root)
        assertFailsWith<RuntimeException> { testManager(ids).unlock(root, TEST_PASSWORD) }
        assertEquals(damagedState, snapshotTree(root))
    }

    /**
     * 验证用户新建和编辑的标签、属性定义通过加密快照与 Change 跨重启保留。
     */
    @Test
    fun persistsEditableTagAndPropertySchemasAcrossRestart() = withWorkspace { root ->
        val ids = AtomicInteger()
        val manager = testManager(ids)
        manager.prepareCreation(root, TEST_PASSWORD)
        val initial = manager.confirmCreation()
        assertTrue(initial.tagDefinitions.any { stored -> stored.snapshot.payload.id == WorkspaceDefaults.DEFAULT_NOTE_TAG_ID })
        assertEquals(4, initial.propertyDefinitions.size)
        assertTrue(
            initial.propertyDefinitions.any { stored ->
                stored.snapshot.payload.id == WorkspaceDefaults.DEFAULT_TAGS_PROPERTY_ID
            },
        )

        val session = requireNotNull(manager.sessionOrNull())
        val property = PropertyDefinition(
            id = PropertyId("property-user-test"),
            name = "测试优先级",
            type = PropertyTypes.SELECT,
            options = listOf(PropertyOption("option-high", "高"), PropertyOption("option-low", "低")),
        )
        session.savePropertyDefinition(property)
        val parentTag = TagDefinition(
            id = TagId("tag-parent-test"),
            name = "测试工作项",
        )
        session.saveTagDefinition(parentTag)
        val tag = TagDefinition(
            id = TagId("tag-user-test"),
            name = "测试项目",
            parentTagIds = listOf(parentTag.id),
            propertyBindings = listOf(
                TagPropertyBinding(
                    propertyId = property.id,
                    required = true,
                    defaultValue = JsonPrimitive("option-high"),
                    defaultPolicy = DefaultValuePolicy.DYNAMIC,
                    readonly = true,
                ),
            ),
        )
        session.saveTagDefinition(tag)
        session.savePropertyDefinition(property.copy(name = "测试优先级已更新"))
        manager.lock()

        val reopenedManager = testManager(ids)
        val reopened = reopenedManager.unlock(root, TEST_PASSWORD)
        val reopenedTag = reopened.tagDefinitions.single { stored -> stored.snapshot.payload.id == tag.id }.snapshot.payload
        assertEquals("测试项目", reopenedTag.name)
        assertEquals(listOf(parentTag.id), reopenedTag.parentTagIds)
        assertEquals(DefaultValuePolicy.DYNAMIC, reopenedTag.propertyBindings.single().defaultPolicy)
        assertEquals(JsonPrimitive("option-high"), reopenedTag.propertyBindings.single().defaultValue)
        assertTrue(reopenedTag.propertyBindings.single().required)
        assertTrue(reopenedTag.propertyBindings.single().readonly)
        assertEquals(
            "测试优先级已更新",
            reopened.propertyDefinitions.single { stored -> stored.snapshot.payload.id == property.id }.snapshot.payload.name,
        )
        assertTreeDoesNotContain(root, listOf("测试项目", "测试工作项", "测试优先级", "测试优先级已更新"))
        reopenedManager.lock()
    }

    /**
     * 断言 ZIP 只含 manifest 和允许的权威密文目录。
     *
     * @param backup 待审计备份。
     */
    private fun assertArchiveContainsOnlyAuthoritativeCiphertext(backup: Path) {
        ZipFile(backup.toFile()).use { zip ->
            val names = zip.entries().asSequence().map { entry -> entry.name }.toList()
            assertTrue("manifest.enc" in names)
            assertTrue("tags/default-note-tag.tag.enc" in names)
            assertTrue("properties/default-note-text-property.property.enc" in names)
            assertTrue("properties/default-note-select-property.property.enc" in names)
            assertTrue("properties/default-note-date-property.property.enc" in names)
            assertTrue(names.none { name -> name.startsWith("index/") || name.startsWith("staging/") })
            assertTrue(names.all { name ->
                name == "manifest.enc" || AUTHORITATIVE_BACKUP_ROOTS.any { root -> name.startsWith("$root/") }
            })
        }
    }

    /**
     * 判断字节数组是否包含连续明文片段。
     *
     * @param needle 待查找片段。
     * @return 找到连续片段时为 true。
     */
    private fun ByteArray.containsBytes(needle: ByteArray): Boolean {
        if (needle.isEmpty() || size < needle.size) {
            return false
        }
        return indices.take(size - needle.size + 1).any { start ->
            needle.indices.all { offset -> this[start + offset] == needle[offset] }
        }
    }

    /**
     * 扫描工作区全部普通文件，证明测试标题、正文和属性值没有连续明文片段。
     *
     * @param root 待扫描工作区。
     * @param sensitiveTexts 不应出现的测试明文集合。
     */
    private fun assertTreeDoesNotContain(
        root: Path,
        sensitiveTexts: List<String>,
    ) {
        Files.walk(root).use { paths ->
            paths.filter(Files::isRegularFile).forEach { file ->
                val bytes = Files.readAllBytes(file)
                sensitiveTexts.forEach { text ->
                    assertFalse(bytes.containsBytes(text.encodeToByteArray()), "工作区文件包含测试明文")
                }
            }
        }
    }

    /**
     * 读取工作区普通文件的相对路径和完整字节，用于证明失败路径零写入。
     *
     * @param root 工作区根目录。
     * @return 路径到无引用相等陷阱的字节列表映射。
     */
    private fun snapshotTree(root: Path): Map<String, List<Byte>> = Files.walk(root).use { paths ->
        val snapshot = linkedMapOf<String, List<Byte>>()
        paths.filter(Files::isRegularFile).sorted().forEach { file ->
            snapshot[root.relativize(file).toString()] = Files.readAllBytes(file).toList()
        }
        snapshot
    }

    /**
     * 构造使用低成本测试 KDF 和稳定时间源的 Manager。
     *
     * @param ids 跨重启共享的不透明 ID 计数器。
     * @return 可执行真实 I/O 的 Manager。
     */
    private fun testManager(ids: AtomicInteger): DesktopWorkspaceManager = DesktopWorkspaceManager(
        idFactory = { "id-${ids.incrementAndGet()}" },
        currentTimeMillis = { 1_789_200_000_000L + ids.get() },
        timestampProvider = { "2026-09-12T00:00:00Z" },
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
     * 构造包含可索引段落的合法 Note。
     *
     * @return 用于保存和搜索的 Note。
     */
    private fun testNote(): Note = Note(
        title = "重启后仍可搜索",
        directTagIds = emptyList(),
        propertyValues = mapOf(
            WorkspaceDefaults.DEFAULT_TEXT_PROPERTY_ID to NotePropertyValue(
                value = JsonPrimitive("敏感属性值"),
                source = PropertyValueSource.USER,
                overridden = true,
                updatedAt = "2026-09-12T00:00:00Z",
            ),
        ),
        blocks = listOf(
            Block(
                id = BlockId("block-main"),
                type = BlockTypes.PARAGRAPH,
                orderKey = "0000000000",
                content = RichTextBlockContent(RichText(listOf(InlineRun("密文权威状态")))),
            ),
        ),
    )

    /**
     * 构造用于对象损坏隔离测试的第二篇健康 Note。
     *
     * @return 标题和 Block ID 均独立的 Note。
     */
    private fun healthyNote(): Note = Note(
        title = "健康对象",
        directTagIds = emptyList(),
        blocks = listOf(
            Block(
                id = BlockId("block-healthy"),
                type = BlockTypes.PARAGRAPH,
                orderKey = "0000000000",
                content = RichTextBlockContent(RichText(listOf(InlineRun("可继续读取")))),
            ),
        ),
    )

    /**
     * 在独立临时目录运行测试并只清理本测试创建的路径。
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
     * 在同一临时父目录提供源工作区、备份文件和恢复目录，确保原子目录移动可用。
     *
     * @param block 使用三条受控测试路径的主体。
     */
    private fun withTestArea(block: suspend (Path, Path, Path) -> Unit) = runBlocking {
        val area = Files.createTempDirectory(TEST_DIRECTORY_PREFIX).toAbsolutePath().normalize()
        val workspace = area.resolve("workspace")
        Files.createDirectory(workspace)
        try {
            block(workspace, area.resolve("backup.tagloom"), area.resolve("restored"))
        } finally {
            deleteTestTree(area)
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
        const val TEST_PASSWORD: String = "test-only-password"

        /** 更换密码测试使用的脱敏新密码。 */
        const val TEST_NEW_PASSWORD: String = "test-only-new-password"

        /** 模拟桌面端生成、不会展示给用户的高熵本机解锁材料。 */
        const val TEST_DEVICE_UNLOCK_SECRET: String = "test-only-device-unlock-material-0123456789"

        /** 临时目录固定前缀。 */
        const val TEST_DIRECTORY_PREFIX: String = "tagloom-desktop-session-test-"

        /** 备份协议允许出现的权威密文根目录。 */
        val AUTHORITATIVE_BACKUP_ROOTS: Set<String> = setOf(
            "notes",
            "tags",
            "properties",
            "blobs",
            "changes",
            "queries",
            "history",
        )

        /** 工作区与备份中都不得出现的测试明文。 */
        val SENSITIVE_TEST_TEXTS: List<String> = listOf(
            "重启后仍可搜索",
            "密文权威状态",
            "敏感属性值",
            "密文查询",
        )
    }
}
