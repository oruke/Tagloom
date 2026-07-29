package com.oruke.tagloom.editor

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * 验证编辑器草稿自动生成创建时间，并在内容变化时推进更新时间。
 */
class EditorDocumentTimestampTest {
    /**
     * 验证创建和更新都由注入时钟生成，且标题编辑不会改写创建时间。
     */
    @Test
    fun tracksDraftCreationAndUpdateTime() {
        val timestamps = listOf("2026-07-15T08:00:00Z", "2026-07-15T08:01:00Z")
        var timestampIndex = 0
        val state = TagloomEditorState(
            idFactory = { prefix -> "$prefix-test" },
            timestampProvider = { timestamps[timestampIndex++] },
        )

        state.createDocument()
        assertEquals(timestamps[0], state.selectedDocument!!.createdAt)
        assertEquals(timestamps[0], state.selectedDocument!!.updatedAt)

        state.updateTitle("新标题")
        assertEquals(timestamps[0], state.selectedDocument!!.createdAt)
        assertEquals(timestamps[1], state.selectedDocument!!.updatedAt)
    }
}
