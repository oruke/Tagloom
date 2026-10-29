package com.oruke.tagloom.workspace

import com.oruke.tagloom.core.model.*
import com.oruke.tagloom.platform.WorkspaceNote
import kotlinx.coroutines.runBlocking
import kotlin.test.*

/** 用真实服务、独立设备、正式加密事务验证逐项恢复和处理进度跨设备收敛。 */
class WorkspaceFineConflictSyncIntegrationTest {
    /** 两台设备分别选择标题和 Block 后保留独立正文，进度取并集且锁定重开不丢失。 */
    @Test
    fun mergesIndependentFieldResolutionsWithoutRestoringUnrelatedContent() = ServerRecoveryTestEnvironment().use { env ->
        runBlocking {
            val first = env.device("fine-first")
            val key = requireNotNull(first.gateway.prepareCreation(null)).recoveryKeyText
            first.gateway.confirmCreation()
            val original = first.gateway.saveNote(null, fineNote("Initial title", "initial overlap", "initial left", "initial right"))
            first.gateway.serverRecovery.publish(env.address, env.bootstrap, key)
            val second = env.device("fine-second")
            second.gateway.serverRecovery.restore(env.address, key, null)
            first.gateway.saveNote(original.id, original.note.copy(title = "First title")
                .fineReplace(0, "first overlap").fineReplace(1, "independent first text"), original.revision)
            second.gateway.saveNote(original.id, original.note.copy(title = "Second title")
                .fineReplace(0, "second overlap").fineReplace(2, "independent second text"), original.revision)
            first.gateway.serverRecovery.updateIfChanged()
            second.gateway.serverRecovery.updateIfChanged()
            first.gateway.serverRecovery.updateIfChanged()
            second.gateway.serverRecovery.updateIfChanged()

            val firstConflict = first.gateway.serverRecovery.conflicts().single { it.objectId == original.id }
            val secondConflict = second.gateway.serverRecovery.conflicts().single { it.id == firstConflict.id }
            assertEquals(setOf("TITLE", "BLOCK"), firstConflict.fields.map { it.labelKey }.toSet())
            val titleField = firstConflict.fields.single { it.labelKey == "TITLE" }
            val blockField = secondConflict.fields.single { it.labelKey == "BLOCK" }
            val before = first.fineRead(original.id).note
            assertEquals(listOf("independent first text", "independent second text"), before.fineTexts().drop(1))
            val titleRemote = firstConflict.remoteTitle != before.title
            val expectedTitle = if (titleRemote) firstConflict.remoteTitle else firstConflict.localTitle
            val blockRemote = blockField.remoteContent.contains("first overlap") != before.fineTexts()[0].contains("first overlap")
            val expectedBlock = if ((if (blockRemote) blockField.remoteContent else blockField.localContent).contains("first overlap"))
                "first overlap" else "second overlap"

            // 两台设备都从同一确认点选择不同字段，尚未交换各自的解决 Change。
            first.gateway.serverRecovery.resolveConflict(firstConflict.id, titleRemote, firstConflict.currentRevision, titleField.id)
            second.gateway.serverRecovery.resolveConflict(secondConflict.id, blockRemote, secondConflict.currentRevision, blockField.id)
            val firstProgress = first.gateway.serverRecovery.conflicts().single { it.id == firstConflict.id }
            val secondProgress = second.gateway.serverRecovery.conflicts().single { it.id == firstConflict.id }
            assertFalse(firstProgress.resolved)
            assertFalse(secondProgress.resolved)
            assertEquals(1, firstProgress.fields.count { it.resolved })
            assertEquals(1, secondProgress.fields.count { it.resolved })
            assertEquals(before.fineTexts(), first.fineRead(original.id).note.fineTexts())
            assertEquals(before.title, second.fineRead(original.id).note.title)

            // 拒绝同一字段二次提交；即便重新读取了 revision，也不能复用旧历史动作。
            assertFailsWith<Exception> {
                first.gateway.serverRecovery.resolveConflict(firstConflict.id, !titleRemote, firstProgress.currentRevision, titleField.id)
            }
            first.gateway.serverRecovery.updateIfChanged()
            second.gateway.serverRecovery.updateIfChanged()
            first.gateway.serverRecovery.updateIfChanged()
            second.gateway.serverRecovery.updateIfChanged()
            val result = first.fineRead(original.id).note
            assertEquals(expectedTitle, result.title)
            assertEquals(listOf(expectedBlock, "independent first text", "independent second text"), result.fineTexts())
            assertEquals(result, second.fineRead(original.id).note)
            for (device in listOf(first, second)) {
                val history = device.gateway.serverRecovery.conflicts().single { it.id == firstConflict.id }
                assertTrue(history.resolved)
                assertTrue(history.fields.all { it.resolved })
                assertEquals(1, device.gateway.serverRecovery.conflicts().size)
                device.gateway.lock()
                val reopened = device.gateway.openRecentWorkspace()
                assertEquals(result, reopened.notes.single { it.id == original.id }.note)
                assertTrue(device.gateway.serverRecovery.conflicts().single().resolved)
            }
        }
    }
}

/**
 * 生成具有稳定 Block 身份的脱敏笔记，交给正式写入协调器处理。
 * @param title 初始标题。
 * @param texts 正文各 Block 的文本。
 * @return 供离线分支编辑的领域笔记。
 */
private fun fineNote(title: String, vararg texts: String): Note = Note(title, emptyList(), blocks = texts.mapIndexed { index, text ->
    Block(BlockId("fine-block-$index"), BlockTypes.PARAGRAPH, orderKey = ('a'.code + index).toChar().toString(),
        content = RichTextBlockContent(RichText(listOf(InlineRun(text)))))
})

/**
 * 保留 Block 身份与其他字段，模拟一次正文块编辑。
 * @param index 正文块位置。
 * @param text 要保存的文本。
 * @return 仅目标块变化的笔记。
 */
private fun Note.fineReplace(index: Int, text: String): Note = copy(blocks = blocks.mapIndexed { position, block ->
    if (position == index) block.copy(content = RichTextBlockContent(RichText(listOf(InlineRun(text))))) else block
})

/** @return 当前笔记完整正文，用于核对真实持久化结果。 */
private fun Note.fineTexts(): List<String> = blocks.map { (it.content as RichTextBlockContent).richText.runs.joinToString("") { run -> run.text } }

/**
 * 通过真实网关重读已确认对象，避免把旧内存候选当作持久化结果。
 * @param id 目标笔记的稳定身份。
 * @return 当前确认笔记。
 */
private suspend fun ServerRecoveryTestDevice.fineRead(id: ObjectId): WorkspaceNote = requireNotNull(gateway.reload()).notes.single { it.id == id }
