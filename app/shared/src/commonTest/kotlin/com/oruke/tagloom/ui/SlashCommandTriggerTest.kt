package com.oruke.tagloom.ui

import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * 验证 Slash 命令检测不会越过光标或误识别普通路径。
 */
class SlashCommandTriggerTest {
    /**
     * 验证光标位于文本起点时不会对光标后的斜杠执行反向 substring。
     */
    @Test
    fun ignoresSlashAfterCaretAtTextStart() {
        assertNull(
            findSlashCommandTrigger(
                TextFieldValue(text = "/", selection = TextRange.Zero),
            ),
        )
    }

    /**
     * 验证光标越过斜杠后会生成空查询命令范围。
     */
    @Test
    fun detectsSlashBeforeCaret() {
        assertEquals(
            SlashCommandTrigger(start = 0, endExclusive = 1, query = ""),
            findSlashCommandTrigger(
                TextFieldValue(text = "/", selection = TextRange(1)),
            ),
        )
    }

    /**
     * 验证 URL 或路径内部的斜杠不会打开命令菜单。
     */
    @Test
    fun ignoresSlashInsideOrdinaryText() {
        assertNull(
            findSlashCommandTrigger(
                TextFieldValue(text = "path/to", selection = TextRange(7)),
            ),
        )
    }
}
