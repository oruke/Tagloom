package com.oruke.tagloom.ui

import com.oruke.tagloom.core.model.DatabaseBlockContent
import com.oruke.tagloom.core.workspace.WorkspaceDefaults
import com.oruke.tagloom.editor.TagloomEditorState
import kotlin.coroutines.Continuation
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.startCoroutine
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** 验证设计预览中的数据集命令不是只读占位，而是完整的内存交互入口。 */
class PreviewDatabaseControllerTest {
    /** 验证命令会绑定 Block，并立即产生包含演示 Note 的四列投影。 */
    @Test
    fun createsInteractiveDatasetFromSlashCommand() {
        val state = TagloomEditorState.demo()
        val document = assertNotNull(state.selectedDocument)
        val commandBlock = document.note.blocks.last()
        val controller = PreviewDatabaseController(state)

        val databaseId = runImmediateSuspend {
            controller.createDatabaseBlock(
                documentId = document.id,
                blockId = commandBlock.id,
                name = "未命名数据集",
                sourceTypeId = WorkspaceDefaults.DEFAULT_NOTE_TAG_ID,
            )
        }

        val createdId = assertNotNull(databaseId)
        val updatedBlock = state.selectedDocument!!.note.blocks.single { block -> block.id == commandBlock.id }
        assertEquals(createdId, assertIs<DatabaseBlockContent>(updatedBlock.content).databaseId)
        val view = controller.viewsFor(createdId).single()
        val projection = assertNotNull(controller.projection(createdId, view.id))
        assertEquals(4, projection.columns.size)
        assertEquals(3, projection.rows.size)
        assertTrue(projection.rows.any { row -> row.title == "Tagloom 编辑器重构" })
    }

    /**
     * 同步执行不会真正挂起的预览 suspend 操作。
     *
     * @param block 只访问内存状态、必须在当前调用栈完成的操作。
     * @return 操作结果。
     */
    private fun <T> runImmediateSuspend(block: suspend () -> T): T {
        var completed: Result<T>? = null
        block.startCoroutine(
            object : Continuation<T> {
                /** 预览内存操作不需要调度器。 */
                override val context = EmptyCoroutineContext

                /**
                 * 接收当前调用栈中的完成结果。
                 *
                 * @param result 成功值或异常。
                 */
                override fun resumeWith(result: Result<T>) {
                    completed = result
                }
            },
        )
        return checkNotNull(completed) { "预览操作不应挂起" }.getOrThrow()
    }
}
