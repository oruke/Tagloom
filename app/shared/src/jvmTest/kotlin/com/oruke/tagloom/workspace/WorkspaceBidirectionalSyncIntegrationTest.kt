package com.oruke.tagloom.workspace

import com.oruke.tagloom.core.model.Block
import com.oruke.tagloom.core.model.BlockId
import com.oruke.tagloom.core.model.BlockTypes
import com.oruke.tagloom.core.model.InlineRun
import com.oruke.tagloom.core.model.Note
import com.oruke.tagloom.core.model.ObjectId
import com.oruke.tagloom.core.model.PropertyDefinition
import com.oruke.tagloom.core.model.PropertyId
import com.oruke.tagloom.core.model.PropertyTypes
import com.oruke.tagloom.core.model.RichText
import com.oruke.tagloom.core.model.RichTextBlockContent
import com.oruke.tagloom.core.model.TagDefinition
import com.oruke.tagloom.core.model.TagId
import com.oruke.tagloom.core.model.TagPropertyBinding
import com.oruke.tagloom.platform.WorkspaceNote
import com.oruke.tagloom.sync.http.jvm.ServerRecoveryClient
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/** 使用两个独立设备、正式网关、加密文件、SQLite 和真实 TCP 服务验证双向同步闭环。 */
class WorkspaceBidirectionalSyncIntegrationTest {
    /** 不同笔记、同笔记不同 Block 和独立类型目录离线写入后均收敛，索引与重开结果一致。 */
    @Test
    fun convergesOfflineNotesBlocksAndSchemasAcrossIndependentDevices() = ServerRecoveryTestEnvironment().use { env ->
        runBlocking {
            val first = env.device("sync-first")
            val key = requireNotNull(first.gateway.prepareCreation(null)).recoveryKeyText
            first.gateway.confirmCreation()
            val shared = first.gateway.saveNote(null, syncNote("Shared sync sample", "initial first", "initial second"))
            first.gateway.serverRecovery.publish(env.address, env.bootstrap, key)
            val second = env.device("sync-second")
            second.gateway.serverRecovery.restore(env.address, key, "246810")
            assertNotEquals(first.manager.rootOrNull(), second.manager.rootOrNull())
            assertEquals(first.manager.sessionOrNull()?.workspaceId, second.manager.sessionOrNull()?.workspaceId)

            // 两台设备不交换状态，各自在自己的事务中修改同一笔记的不同 Block。
            first.gateway.saveNote(shared.id, shared.note.replaceSyncBlock(0, "syncalpha first block"), shared.revision)
            second.gateway.saveNote(shared.id, shared.note.replaceSyncBlock(1, "syncbeta second block"), shared.revision)
            val firstProperty = PropertyDefinition(PropertyId("sync-alpha-property"), "Alpha field", PropertyTypes.TEXT)
            val secondProperty = PropertyDefinition(PropertyId("sync-beta-property"), "Beta field", PropertyTypes.TEXT)
            val firstTag = TagDefinition(TagId("sync-alpha-type"), "Alpha type", propertyBindings = listOf(TagPropertyBinding(firstProperty.id)))
            val secondTag = TagDefinition(TagId("sync-beta-type"), "Beta type", propertyBindings = listOf(TagPropertyBinding(secondProperty.id)))
            first.gateway.savePropertyDefinition(firstProperty)
            first.gateway.saveTagDefinition(firstTag)
            second.gateway.savePropertyDefinition(secondProperty)
            second.gateway.saveTagDefinition(secondTag)
            val alpha = first.gateway.saveNote(null, syncNote("syncalpha addition", "Alpha body").copy(directTagIds = listOf(firstTag.id)))
            val beta = second.gateway.saveNote(null, syncNote("syncbeta addition", "Beta body").copy(directTagIds = listOf(secondTag.id)))

            first.gateway.serverRecovery.updateIfChanged()
            second.gateway.serverRecovery.updateIfChanged()
            first.gateway.serverRecovery.updateIfChanged()
            second.gateway.serverRecovery.updateIfChanged()
            val firstState = requireNotNull(first.gateway.reload())
            val secondState = requireNotNull(second.gateway.reload())
            assertEquals(firstState.notes.associate { it.id to it.note }, secondState.notes.associate { it.id to it.note })
            assertEquals(setOf(shared.id, alpha.id, beta.id), firstState.notes.map { it.id }.toSet())
            assertEquals(listOf("syncalpha first block", "syncbeta second block"), firstState.notes.single { it.id == shared.id }.note.syncBlockTexts())
            for (state in listOf(firstState, secondState)) {
                assertTrue(state.tagDefinitions.map { it.definition }.containsAll(listOf(firstTag, secondTag)))
                assertTrue(state.propertyDefinitions.map { it.definition }.containsAll(listOf(firstProperty, secondProperty)))
            }
            assertEquals(setOf(shared.id, alpha.id), first.gateway.search("syncalpha").map { it.noteId }.toSet())
            assertEquals(setOf(shared.id, beta.id), second.gateway.search("syncbeta").map { it.noteId }.toSet())
            assertTrue(first.gateway.serverRecovery.conflicts().none { !it.resolved }, "独立 Block 与独立对象无需冲突选择")

            // 远端 manifest 不能覆盖恢复设备的本机 PIN 或两台设备各自的目录绑定。
            first.gateway.lock()
            second.gateway.lock()
            assertFailsWith<Exception> { second.gateway.openRecentWorkspace() }
            assertFailsWith<Exception> { second.gateway.unlockRecentWorkspace("135790") }
            val firstReopened = first.gateway.openRecentWorkspace()
            val secondReopened = second.gateway.unlockRecentWorkspace("246810")
            assertEquals(firstState.notes.associate { it.id to it.note }, firstReopened.notes.associate { it.id to it.note })
            assertEquals(firstReopened.notes.associate { it.id to it.note }, secondReopened.notes.associate { it.id to it.note })
            assertTrue(second.gateway.recentWorkspaceOrNull()?.requiresPin == true)
        }
    }

    /** 同 Block 冲突保留完整两份内容；正式恢复版本经 revision 门禁、同步与重开后仍一致。 */
    @Test
    fun retainsBothConflictingVersionsAndSynchronizesExplicitResolution() = ServerRecoveryTestEnvironment().use { env ->
        runBlocking {
            val first = env.device("conflict-first")
            val key = requireNotNull(first.gateway.prepareCreation(null)).recoveryKeyText
            first.gateway.confirmCreation()
            val original = first.gateway.saveNote(null, syncNote("Conflict sample", "initial conflict body"))
            first.gateway.serverRecovery.publish(env.address, env.bootstrap, key)
            val second = env.device("conflict-second")
            second.gateway.serverRecovery.restore(env.address, key, "246810")
            first.gateway.saveNote(original.id, original.note.replaceSyncBlock(0, "localversion alpha complete body"), original.revision)
            second.gateway.saveNote(original.id, original.note.replaceSyncBlock(0, "remoteversion beta complete body"), original.revision)
            first.gateway.serverRecovery.updateIfChanged()
            second.gateway.serverRecovery.updateIfChanged()
            first.gateway.serverRecovery.updateIfChanged()
            second.gateway.serverRecovery.updateIfChanged()
            val conflict = second.gateway.serverRecovery.conflicts().first { it.objectId == original.id && !it.resolved }
            val versions = listOf(conflict.localContent, conflict.remoteContent)
            assertTrue(versions.any { it.contains("localversion alpha complete body") })
            assertTrue(versions.any { it.contains("remoteversion beta complete body") })
            assertTrue(first.gateway.serverRecovery.conflicts().any { it.objectId == original.id && !it.resolved })

            // 在用户审阅之后出现的新编辑不能被旧选择覆盖，拒绝时也不能提前标记已解决。
            val before = second.noteById(original.id)
            val current = second.gateway.saveNote(original.id, before.note.copy(title = "Conflict sample after review"), before.revision)
            assertFailsWith<Exception> {
                second.gateway.serverRecovery.resolveConflict(conflict.id, useRemote = true, expectedRevision = conflict.currentRevision)
            }
            assertFalse(second.gateway.serverRecovery.conflicts().single { it.id == conflict.id }.resolved)
            val refreshed = second.gateway.serverRecovery.conflicts().single { it.id == conflict.id }
            assertEquals(current.revision, refreshed.currentRevision)
            val useRemote = refreshed.remoteContent.contains("remoteversion beta complete body")
            second.gateway.serverRecovery.resolveConflict(refreshed.id, useRemote, refreshed.currentRevision)
            assertEquals(listOf("remoteversion beta complete body"), second.noteById(original.id).note.syncBlockTexts())
            assertTrue(second.gateway.serverRecovery.conflicts().single { it.id == conflict.id }.resolved)
            second.gateway.serverRecovery.updateIfChanged()
            first.gateway.serverRecovery.updateIfChanged()
            second.gateway.serverRecovery.updateIfChanged()
            assertEquals(first.noteById(original.id).note, second.noteById(original.id).note)
            assertTrue(first.gateway.serverRecovery.conflicts().single { it.id == conflict.id }.resolved)

            first.gateway.lock()
            second.gateway.lock()
            assertFailsWith<Exception> { second.gateway.serverRecovery.conflicts() }
            first.gateway.openRecentWorkspace()
            second.gateway.unlockRecentWorkspace("246810")
            assertEquals(listOf("remoteversion beta complete body"), first.noteById(original.id).note.syncBlockTexts())
            assertEquals(first.noteById(original.id).note, second.noteById(original.id).note)
            assertTrue(second.gateway.serverRecovery.conflicts().single { it.id == conflict.id }.resolved)
        }
    }

    /** 网络中断保留可重开的本地写入，重连后两端补齐，重复空同步不递增确认版本。 */
    @Test
    fun retriesAfterOfflineWritesAndKeepsUnchangedCheckpointsStable() = ServerRecoveryTestEnvironment().use { env ->
        runBlocking {
            val first = env.device("offline-first")
            val key = requireNotNull(first.gateway.prepareCreation(null)).recoveryKeyText
            first.gateway.confirmCreation()
            val original = first.gateway.saveNote(null, syncNote("Offline sample", "base first", "base second"))
            first.gateway.serverRecovery.publish(env.address, env.bootstrap, key)
            val second = env.device("offline-second")
            second.gateway.serverRecovery.restore(env.address, key, null)
            env.disconnect()
            val local = first.gateway.saveNote(original.id, original.note.replaceSyncBlock(0, "offlinealpha survives restart"), original.revision)
            second.gateway.saveNote(original.id, original.note.replaceSyncBlock(1, "offlinebeta remote write"), original.revision)
            assertFailsWith<Exception> { first.gateway.serverRecovery.updateIfChanged() }
            first.gateway.lock()
            assertEquals(local.note, first.gateway.openRecentWorkspace().notes.single().note)
            env.reconnect()
            second.gateway.serverRecovery.updateIfChanged()
            first.gateway.serverRecovery.updateIfChanged()
            second.gateway.serverRecovery.updateIfChanged()
            assertEquals(listOf("offlinealpha survives restart", "offlinebeta remote write"), first.noteById(original.id).note.syncBlockTexts())
            assertEquals(first.noteById(original.id).note, second.noteById(original.id).note)
            assertEquals(original.id, first.gateway.search("offlinebeta").single().noteId)
            val firstRevision = requireNotNull(first.manager.sessionOrNull()).unlocked.payload.revision
            val secondRevision = requireNotNull(second.manager.sessionOrNull()).unlocked.payload.revision
            val remoteRevision = env.confirmedRemoteRevision(first)
            repeat(3) {
                assertFalse(requireNotNull(first.gateway.serverRecovery.updateIfChanged()).pendingChanges)
                assertFalse(requireNotNull(second.gateway.serverRecovery.updateIfChanged()).pendingChanges)
            }
            assertEquals(firstRevision, requireNotNull(first.manager.sessionOrNull()).unlocked.payload.revision)
            assertEquals(secondRevision, requireNotNull(second.manager.sessionOrNull()).unlocked.payload.revision)
            assertEquals(remoteRevision, env.confirmedRemoteRevision(second))
        }
    }
}

/**
 * 构造规范化的脱敏正文样例，两个设备按同一稳定 Block ID 修改。
 * @param title 测试可读标题。
 * @param texts 按正文顺序排列的非空文本。
 * @return 通过正式网关持久化的初始 Note。
 */
private fun syncNote(title: String, vararg texts: String): Note = Note(title = title, directTagIds = emptyList(), blocks = texts.mapIndexed { index, text ->
    Block(BlockId("sync-block-$index"), BlockTypes.PARAGRAPH, orderKey = ('a'.code + index).toChar().toString(),
        content = RichTextBlockContent(RichText(listOf(InlineRun(text)))))
})

/**
 * 保持对象身份、类型与其他 Block 不变，只替换一段 RichText。
 * @param index 要修改的 Block 下标。
 * @param text 新的完整正文文本。
 * @return 独立离线编辑后的 payload。
 */
private fun Note.replaceSyncBlock(index: Int, text: String): Note = copy(blocks = blocks.mapIndexed { current, block ->
    if (current == index) block.copy(content = RichTextBlockContent(RichText(listOf(InlineRun(text))))) else block
})

/** @return 当前样例笔记按顺序保存的完整 RichText 正文，供持久化人工核对。 */
private fun Note.syncBlockTexts(): List<String> = blocks.map { (it.content as RichTextBlockContent).richText.runs.joinToString("") { run -> run.text } }

/**
 * 通过正式网关重新读取当前确认快照，不能使用保存调用的旧返回值代表合并结果。
 * @param id 要读取的稳定 Note ID。
 * @return 当前已确认 Note。
 */
private suspend fun ServerRecoveryTestDevice.noteById(id: ObjectId): WorkspaceNote = requireNotNull(gateway.reload()).notes.single { it.id == id }

/**
 * 通过新增的已认证元数据 API 核对服务器真实确认点，短期连接材料不进入断言或日志。
 * @param device 当前已解锁并完成服务器登记的设备。
 * @return 服务端最新确认副本版本。
 */
private suspend fun ServerRecoveryTestEnvironment.confirmedRemoteRevision(device: ServerRecoveryTestDevice): Long {
    val session = requireNotNull(device.manager.sessionOrNull())
    val access = requireNotNull(ServerRecoveryAccessStore(device.root.resolve("remote")).read(requireNotNull(device.manager.rootOrNull()), session.workspaceId))
    return try {
        ServerRecoveryClient(address, allowLoopbackHttp = true).use { client ->
            requireNotNull(client.latestSnapshot(session.workspaceId, access.actorId, access.token)).revision
        }
    } finally { access.destroy() }
}
