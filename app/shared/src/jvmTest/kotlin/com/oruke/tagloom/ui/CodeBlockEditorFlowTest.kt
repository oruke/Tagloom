package com.oruke.tagloom.ui

import androidx.compose.ui.graphics.asSkiaBitmap
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.ClipboardManager
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.*
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import androidx.compose.ui.text.TextRange
import com.oruke.tagloom.App
import com.oruke.tagloom.core.markdown.MarkdownCodec
import com.oruke.tagloom.core.markdown.MarkdownExportMode
import com.oruke.tagloom.core.model.CodeBlockContent
import com.oruke.tagloom.editor.wrapsCodeLines
import kotlinx.coroutines.runBlocking
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.Image
import java.nio.file.Files
import java.nio.file.Path
import java.util.Locale
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** 从生产 App 验证代码粘贴、选区、高亮、语言、复制、换行与加密保存的完整流程。 */
@Suppress("DEPRECATION")
@OptIn(ExperimentalTestApi::class)
class CodeBlockEditorFlowTest {
    /** 真实桌面编辑后重新解锁，确认语言和软换行设置持久化且正文不受高亮影响。 */
    @Test
    fun editsAndPersistsCodeThroughProductionApp() = withEnvironment { environment ->
        var clipboard: ClipboardManager? = null
        runDesktopComposeUiTest(width = 1440, height = 1024) {
            setContent {
                clipboard = LocalClipboardManager.current
                App(capabilities = environment.capabilities)
            }
            createCodeNote()
            assertCenteredReadingColumn()

            // 通过真实语言菜单筛选别名，再恢复 Kotlin；文本及光标不应被高亮变换替换。
            onAllNodesWithContentDescription("代码语言")[0].performClick()
            onNode(hasSetTextAction() and hasText("搜索语言…")).performTextReplacement("python")
            onNode(hasText("Python") and hasClickAction() and hasAnyAncestor(isPopup())).performClick()
            onAllNodes(codeInput())[0].assertTextEquals(KOTLIN_CODE)
            onAllNodesWithContentDescription("代码语言")[0].performClick()
            onNode(hasSetTextAction() and hasText("搜索语言…")).performTextReplacement("kt")
            onNode(hasText("Kotlin") and hasClickAction() and hasAnyAncestor(isPopup())).performClick()

            onAllNodes(codeInput())[0].performClick().performTextInputSelection(TextRange(KOTLIN_CODE.length))
            onAllNodes(codeInput())[0].performKeyInput { pressKey(Key.Tab) }
            onAllNodes(codeInput())[0].assertTextEquals(KOTLIN_CODE + "    ")
            onAllNodes(codeInput())[0].performTextReplacement(KOTLIN_CODE)
            onAllNodesWithText("复制")[0].performClick()
            onNodeWithText("已复制").assertIsDisplayed()
            runOnIdle { assertEquals(KOTLIN_CODE, clipboard?.getText()?.text) }
            saveScreenshot("code-desktop.png")

            // 关闭软换行后，长行只扩展代码内部的滚动范围，不扩展页面或改变持久化正文。
            onAllNodesWithText("自动换行")[0].performClick().assertIsOff()
            waitUntil(timeoutMillis = 10_000) {
                onAllNodes(hasAnyAncestor(codeBlock()) and hasScrollAction()).fetchSemanticsNodes().any {
                    (it.config.getOrNull(SemanticsProperties.HorizontalScrollAxisRange)?.maxValue() ?: 0f) > 0f
                }
            }
            assertCenteredReadingColumn()
            onNodeWithText("⋯").performClick()
            onNode(hasText("立即保存", substring = true) and hasClickAction()).performClick()
            waitForText("已保存")
        }

        // 从重新解密的快照读取最终结果，避免把 UI 内存状态当作保存成功。
        val saved = runBlocking {
            environment.gateway.lock()
            environment.gateway.openRecentWorkspace().notes.single()
        }
        val codeBlock = saved.note.blocks.first { it.content is CodeBlockContent }
        assertEquals("kotlin", (codeBlock.content as CodeBlockContent).language)
        assertEquals(KOTLIN_CODE, (codeBlock.content as CodeBlockContent).text)
        assertFalse(codeBlock.wrapsCodeLines())
        val markdown = MarkdownCodec.export(saved.note, MarkdownExportMode.STANDARD).markdown
        assertTrue(markdown.contains("```kotlin\n$KOTLIN_CODE\n```"))
    }

    /** 窄屏上的默认软换行、语言菜单和正文栏均在视口内，并保留可编辑的完整代码。 */
    @Test
    fun keepsCodeUsableOnCompactScreens() = withEnvironment { environment ->
        runDesktopComposeUiTest(width = 480, height = 900) {
            setContent { App(capabilities = environment.capabilities) }
            createCodeNote()
            assertCenteredReadingColumn()
            onAllNodes(codeInput())[0].assertTextEquals(KOTLIN_CODE)
            onAllNodesWithText("自动换行")[0].assertIsOn()
            onAllNodesWithContentDescription("代码语言")[0].assertIsDisplayed().performClick()
            onNode(hasSetTextAction() and hasText("搜索语言…")).performTextReplacement("unknown-language")
            onNodeWithText("没有匹配的语言").assertIsDisplayed()
            onNode(hasSetTextAction() and hasText("unknown-language")).performTextReplacement("kt")
            onNode(hasText("Kotlin") and hasClickAction() and hasAnyAncestor(isPopup())).performClick()
            saveScreenshot("code-compact.png")
        }
    }

    /**
     * 从新建笔记和真实粘贴入口生成包含不同代码语言的示例笔记。
     */
    private fun ComposeUiTest.createCodeNote() {
        waitForText("设置")
        onAllNodes(hasText("新建笔记", substring = true) and hasClickAction())[0].performClick()
        onNode(hasSetTextAction() and hasText("未命名笔记")).performTextReplacement("代码笔记示例")
        onNode(SemanticsMatcher("正文输入框") {
            it.config.getOrNull(SemanticsProperties.TestTag)?.startsWith("text-input-") == true
        }).performTextReplacement(MARKDOWN)
        waitUntil(timeoutMillis = 15_000) { onAllNodes(codeInput()).fetchSemanticsNodes().isNotEmpty() }
    }

    /** 验证栏宽受限且左右留白一致，避免只凭截图判断正文居中。 */
    private fun ComposeUiTest.assertCenteredReadingColumn() {
        val pane = onNodeWithTag("editor-pane").fetchSemanticsNode().boundsInRoot
        val column =
            onAllNodesWithTag("editor-reading-column", useUnmergedTree = true)[0].fetchSemanticsNode().boundsInRoot
        assertTrue(abs(pane.center.x - column.center.x) <= 1f)
        assertTrue(column.width <= 760f + 1f)
        assertTrue(column.left > pane.left && column.right < pane.right)
    }

    /** @return 具有稳定代码输入语义的真实文本编辑节点。 */
    private fun codeInput(): SemanticsMatcher = SemanticsMatcher("代码输入框") {
        it.config.getOrNull(SemanticsProperties.TestTag)?.startsWith("code-input-") == true
    }

    /** @return 用于约束滚动范围检查的代码容器节点。 */
    private fun codeBlock(): SemanticsMatcher = SemanticsMatcher("代码块") {
        it.config.getOrNull(SemanticsProperties.TestTag)?.startsWith("code-block-") == true
    }

    /**
     * 等待真实文件 I/O 完成对应的界面状态。
     * @param text 预期出现的状态文案。
     */
    private fun ComposeUiTest.waitForText(text: String) {
        waitUntil(timeoutMillis = 15_000) { onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty() }
    }

    /**
     * 保存真实 Compose 窗口用于人工布局与语法颜色核对。
     * @param filename 构建目录中的截图文件名。
     */
    private fun ComposeUiTest.saveScreenshot(filename: String) {
        val directory = Path.of("build", "reports", "code-editor-ui")
        Files.createDirectories(directory)
        Image.makeFromBitmap(onAllNodes(isRoot())[0].captureToImage().asSkiaBitmap()).use { image ->
            image.encodeToData(EncodedImageFormat.PNG)!!.use { Files.write(directory.resolve(filename), it.bytes) }
        }
    }

    /**
     * 为完整交互提供隔离的真实文件、加密和索引服务，并恢复进程的 Locale。
     * @param scenario 本次交互及持久化断言。
     */
    private fun withEnvironment(scenario: (WorkspaceSettingsTestEnvironment) -> Unit) {
        val previousLocale = Locale.getDefault()
        try {
            Locale.setDefault(Locale.SIMPLIFIED_CHINESE)
            WorkspaceSettingsTestEnvironment().use(scenario)
        } finally {
            Locale.setDefault(previousLocale)
        }
    }

    private companion object {
        /** 含长行、注释、Unicode 和多个词法角色的脱敏代码示例。 */
        val KOTLIN_CODE = """
            // 示例：整理代码片段，保留原文与语言
            data class Snippet(val language: String, val text: String)

            fun main() {
                val languages = listOf("Kotlin", "JavaScript", "TypeScript", "Python", "SQL", "JSON", "YAML", "Rust")
                println("你好，代码 🌿")
                languages.forEach { println(it) }
            }
        """.trimIndent()

        /** 只通过真实 Markdown 粘贴入口构造示例页面。 */
        val MARKDOWN = """
            正文保持舒适的阅读宽度。代码片段可以选择语言、复制原文，并按需要自动换行。

            ```kt
            ${KOTLIN_CODE.prependIndent("            ").trimStart()}
            ```

            JSON 配置也使用独立的语法颜色。

            ```json
            {"language": "Kotlin", "wrap": true, "count": 39}
            ```
        """.trimIndent()
    }
}
