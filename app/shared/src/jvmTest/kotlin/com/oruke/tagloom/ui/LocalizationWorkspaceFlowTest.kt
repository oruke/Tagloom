package com.oruke.tagloom.ui

import androidx.compose.ui.graphics.asSkiaBitmap
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.*
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import com.oruke.tagloom.App
import com.oruke.tagloom.core.model.BaseTableBlockContent
import com.oruke.tagloom.core.model.TabBlockContent
import com.oruke.tagloom.core.workspace.WorkspaceDefaults
import com.oruke.tagloom.editor.editableRichText
import kotlinx.coroutines.runBlocking
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.getString
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.Image
import tagloom.app.shared.generated.resources.*
import java.nio.file.Files
import java.nio.file.Path
import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** 从生产 App 与真实加密工作区验证中英文文案、创建名称和跨语言重开后的数据保留。 */
@OptIn(ExperimentalTestApi::class)
class LocalizationWorkspaceFlowTest {
    /** 验证英文表格、标签页和数据集操作，包括窄屏文案及真实保存。 */
    @Test
    fun localizesEnglishWorkspace() = verifyWorkspace(Locale.US, width = 480)

    /** 验证中文资源仍正确显示，并沿用同一套业务操作与保存流程。 */
    @Test
    fun localizesChineseWorkspace() = verifyWorkspace(Locale.SIMPLIFIED_CHINESE, width = 1280)

    /**
     * 在隔离工作区中实际新建内容、打开菜单、确认保存，再换语言重开核对快照。
     * @param locale 本轮界面与新工作区使用的语言。
     * @param width 测试窗口宽度，用于覆盖桌面及紧凑布局。
     */
    private fun verifyWorkspace(locale: Locale, width: Int) {
        val previous = Locale.getDefault()
        Locale.setDefault(locale)
        try {
            WorkspaceSettingsTestEnvironment().use { environment ->
                val english = locale.language == "en"
                val firstColumn = if (english) "Name" else "名称"
                val secondColumn = if (english) "Content" else "内容"
                val firstTab = if (english) "Tab 1" else "页签 1"
                val duplicatedTab = if (english) "Tab 1 copy" else "页签 1 副本"
                assertEquals(if (english) "Filter" else "筛选", text(Res.string.database_filter))
                runDesktopComposeUiTest(width = width, height = 900) {
                    setContent { App(capabilities = environment.capabilities) }
                    waitForText(text(Res.string.editor_action_new_note))
                    createNote("Table example")
                    bodyInput().performClick().performTextReplacement("/table")
                    onNodeWithText(text(Res.string.editor_block_command_base_table_label)).performClick()
                    onNodeWithText(text(Res.string.editor_base_table_insert_description)).assertIsDisplayed()
                    screenshot("${locale.language}-table-picker.png")
                    onNodeWithText(text(Res.string.editor_action_insert)).performClick()
                    onNode(hasSetTextAction() and hasText(firstColumn)).assertExists()
                    onNode(hasSetTextAction() and hasText(secondColumn)).assertExists()
                    onNodeWithContentDescription(text(Res.string.editor_base_table_resize)).performScrollTo().assertIsDisplayed().performClick()
                    onNodeWithText(text(Res.string.editor_base_table_dimensions)).assertIsDisplayed()
                    onNodeWithText(text(Res.string.editor_action_apply)).performClick()
                    awaitSavedNote()

                    createNote("Tabs example")
                    bodyInput().performClick().performTextReplacement("/tabs")
                    onNodeWithText(text(Res.string.editor_block_command_tabs_label)).performClick()
                    onNodeWithText(firstTab).performClick()
                    onNodeWithText(text(Res.string.editor_tabs_duplicate)).assertIsDisplayed().performClick()
                    onNodeWithText(duplicatedTab).assertExists()
                    bodyInput().performClick().performTextReplacement("Content example")
                    screenshot("${locale.language}-tabs.png")
                    onNodeWithText(duplicatedTab).performClick()
                    onNodeWithText(text(Res.string.editor_tabs_delete)).performClick()
                    onNodeWithText(text(Res.string.editor_tabs_delete_cascade)).assertIsDisplayed()
                    onNodeWithText(text(Res.string.editor_tabs_delete_unpack)).assertIsDisplayed()
                    screenshot("${locale.language}-tab-delete.png")
                    onNodeWithText(text(Res.string.workspace_action_cancel)).performClick()
                    awaitSavedNote()

                    createNote("Database example")
                    bodyInput().performClick().performTextReplacement("/database")
                    onNodeWithText(text(Res.string.editor_block_command_database_label)).performClick()
                    waitForText(if (english) "Table" else "表格")
                    onNodeWithText(if (english) "Filter" else "筛选", substring = true).performClick()
                    onNodeWithText(text(Res.string.database_filter_title)).assertIsDisplayed()
                    onNodeWithText(text(Res.string.database_filter_scope)).assertIsDisplayed()
                    screenshot("${locale.language}-filter.png")
                    onNodeWithText(text(Res.string.workspace_action_cancel)).performClick()
                    awaitSavedNote()
                }

                // 从加密快照重开核对结果，保证本地化名称确实经过生产事务写入。
                val saved = runBlocking {
                    environment.gateway.lock()
                    environment.gateway.openRecentWorkspace()
                }
                val table = saved.notes.single { it.note.title == "Table example" }.note.blocks
                    .mapNotNull { it.content as? BaseTableBlockContent }.single()
                assertEquals(listOf(firstColumn, secondColumn), table.columns.take(2).map { it.name.plainText })
                val tabs = saved.notes.single { it.note.title == "Tabs example" }.note.blocks
                    .mapNotNull { it.content as? TabBlockContent }.map { it.title.plainText }
                assertTrue(firstTab in tabs)
                assertTrue(duplicatedTab in tabs)
                assertTrue(saved.notes.single { it.note.title == "Tabs example" }.note.blocks.any {
                    it.editableRichText()?.plainText == "Content example"
                })
                assertEquals(if (english) "Table" else "表格", saved.viewDefinitions.single().definition.name)
                assertEquals(
                    if (english) "Status" else "状态",
                    saved.propertyDefinitions.single { it.definition.id == WorkspaceDefaults.DEFAULT_SELECT_PROPERTY_ID }.definition.name,
                )

                // 更改显示语言不应把既有用户内容或已经确认的默认名称重新写成另一种语言。
                runBlocking { environment.gateway.lock() }
                Locale.setDefault(if (english) Locale.SIMPLIFIED_CHINESE else Locale.US)
                val reopened = runBlocking { environment.gateway.openRecentWorkspace() }
                assertEquals(saved.notes, reopened.notes)
                assertEquals(saved.propertyDefinitions, reopened.propertyDefinitions)
                assertEquals(saved.viewDefinitions, reopened.viewDefinitions)
            }
        } finally {
            Locale.setDefault(previous)
        }
    }

    /**
     * 通过真实超级标签选择入口创建普通笔记，并填写用于持久化核对的脱敏标题。
     * @param title 本次新笔记的标题。
     */
    private fun ComposeUiTest.createNote(title: String) {
        onAllNodes(hasText(text(Res.string.editor_action_new_note), substring = true) and hasClickAction())[0].performClick()
        onNodeWithTag("supertag-picker-item-default-note-tag").performClick()
        onNode(hasSetTextAction() and hasText(text(Res.string.editor_new_note_title))).performTextReplacement(title)
    }

    /** @return 当前笔记唯一的真实段落输入框。 */
    private fun ComposeUiTest.bodyInput(): SemanticsNodeInteraction = onNode(SemanticsMatcher("正文输入框") {
        it.config.getOrNull(SemanticsProperties.TestTag)?.startsWith("text-input-") == true
    })

    /** 等待产品自动保存完成再切换笔记，最终通过重开加密快照核对持久化结果。 */
    private fun ComposeUiTest.awaitSavedNote() {
        waitForText(text(Res.string.editor_page_saved))
    }

    /**
     * 等待实际异步事务后的界面状态。
     * @param value 必须出现的完整本地化文本。
     */
    private fun ComposeUiTest.waitForText(value: String) {
        waitUntil(timeoutMillis = 15_000) { onAllNodesWithText(value).fetchSemanticsNodes().isNotEmpty() }
    }

    /**
     * 读取当前语言的资源，用于定位真实控件；关键中英文名称另外使用明确断言核对。
     * @param resource 控件使用的文案资源。
     * @return 当前系统语言的完整文本。
     */
    private fun text(resource: StringResource): String = runBlocking { getString(resource) }

    /**
     * 保存本轮真实窗口及弹层，用于人工核对翻译后的换行和可见范围。
     * @param filename 隔离构建报告中的图片名称。
     */
    private fun ComposeUiTest.screenshot(filename: String) {
        val directory = Path.of("build", "reports", "localization-ui")
        Files.createDirectories(directory)
        onAllNodes(isRoot()).fetchSemanticsNodes().indices.forEach { index ->
            Image.makeFromBitmap(onAllNodes(isRoot())[index].captureToImage().asSkiaBitmap()).use { image ->
                image.encodeToData(EncodedImageFormat.PNG)!!.use {
                    Files.write(directory.resolve("$index-$filename"), it.bytes)
                }
            }
        }
    }
}
