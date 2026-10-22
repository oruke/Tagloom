package com.oruke.tagloom.ui

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.*
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import com.oruke.tagloom.App
import com.oruke.tagloom.core.model.*
import com.oruke.tagloom.core.workspace.LinkNoteSchemas
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.*

/** 从生产 App 根节点验证链接录入、信息回填、目录维护、引用导航与集合查询。 */
@OptIn(ExperimentalTestApi::class)
class LinkNoteEditorFlowTest {
    /** 长目录与搜索结果的键盘游标始终滚入可见区域，返回未分组及取消不会新增笔记。 */
    @Test
    fun keepsKeyboardCursorVisibleInLongGroups() = LinkNoteUiTestEnvironment().use { environment ->
        runBlocking {
            val gateway = environment.workspace.gateway
            val loaded = gateway.openRecentWorkspace()
            val group = loaded.propertyDefinitions.single { it.definition.id == LinkNoteSchemas.GROUP_ID }.definition
            gateway.savePropertyDefinition(group.copy(options = group.options + List(30) { index ->
                PropertyOption("long-group-$index", "长目录 ${index.toString().padStart(2, '0')}")
            }))
            gateway.lock()
        }
        runDesktopComposeUiTest(width = 1280, height = 900) {
            val controller = environment.appState.controller
            setContent { App(capabilities = environment.workspace.capabilities, appState = environment.appState) }
            waitUntil(timeoutMillis = 15000) { controller.phase == WorkspaceUiPhase.OPEN }
            onAllNodes(hasText(LinkNoteUiTestEnvironment.SOURCE_TITLE) and hasClickAction())[0].performClick()
            onNodeWithTag("text-input-${environment.anchorId.value}").performTextInput("/link")
            onNodeWithTag("text-input-${environment.anchorId.value}").performKeyInput { pressKey(Key.Enter) }
            onNodeWithTag("link-entry-group").performClick()
            onNodeWithTag("hierarchy-search").performKeyInput { repeat(40) { pressKey(Key.DirectionDown) } }
            onNodeWithTag("hierarchy-choice-long-group-29").assertIsDisplayed()
            saveLinkScreenshot("desktop-keyboard-long")
            onNodeWithTag("hierarchy-search").performKeyInput { repeat(40) { pressKey(Key.DirectionUp) } }
            onNodeWithTag("hierarchy-choice-none").assertIsDisplayed()
            onNodeWithTag("hierarchy-search").performTextInput("长目录")
            onNodeWithTag("hierarchy-search").performKeyInput { repeat(30) { pressKey(Key.DirectionDown) } }
            onNodeWithTag("hierarchy-choice-long-group-29").assertIsDisplayed()
            saveLinkScreenshot("desktop-keyboard-search")
            onNodeWithTag("hierarchy-search").performKeyInput { pressKey(Key.Enter) }
            onNodeWithTag("link-url-input").assertIsFocused()
            runOnIdle { assertEquals("long-group-29", controller.links.entry?.groupId) }
            onNodeWithTag("link-url-input").performKeyInput { pressKey(Key.Escape) }
            onNodeWithTag("link-entry").assertDoesNotExist()
            runOnIdle { assertEquals(2, controller.editorState.documents.size) }
        }
        runBlocking { assertEquals(2, environment.workspace.gateway.reload()!!.notes.size) }
    }

    /** 桌面键盘录入并继续正文，维护层级目录后，已有引用和集合仍连接同一笔记。 */
    @Test
    fun createsAndMaintainsLinksThroughProductionEditor() = LinkNoteUiTestEnvironment().use { environment ->
        runDesktopComposeUiTest(width = 1280, height = 900) {
            val controller = environment.appState.controller
            setContent { App(capabilities = environment.workspace.capabilities, appState = environment.appState) }
            waitUntil(timeoutMillis = 15000) { controller.phase == WorkspaceUiPhase.OPEN }
            onAllNodes(hasText(LinkNoteUiTestEnvironment.SOURCE_TITLE) and hasClickAction())[0].performClick()
            onNodeWithTag("text-input-${environment.anchorId.value}").performTextInput("/link")
            onNodeWithTag("text-input-${environment.anchorId.value}").performKeyInput { pressKey(Key.Enter) }
            onNodeWithTag("link-url-input").assertIsFocused()
            onNodeWithTag("link-url-input").performTextReplacement("invalid")
            onNodeWithTag("link-url-input").performKeyInput { pressKey(Key.Enter) }
            onNodeWithTag("link-entry-error").assertIsDisplayed()
            runOnIdle { assertEquals(2, controller.editorState.documents.size) }
            onNodeWithTag("link-url-input").performTextReplacement(environment.server.url("/page"))
            onNodeWithTag("link-entry-group").performClick()
            onNodeWithTag("hierarchy-search").performTextInput("CSS")
            onNodeWithText("技术 / 前端 / CSS").assertIsDisplayed()
            onNodeWithText("设计 / 界面 / CSS").assertIsDisplayed()
            saveLinkScreenshot("desktop-group-search")
            onNodeWithTag("hierarchy-choice-design-css").performClick()
            onNodeWithTag("link-url-input").assertIsFocused()
            saveLinkScreenshot("desktop-quick-entry")
            onNodeWithTag("link-url-input").performKeyInput { pressKey(Key.Enter); pressKey(Key.Enter) }
            try {
                waitUntil(timeoutMillis = 15000) { controller.editorState.documents.any { it.note.title == "Metadata page example" } }
            } catch (failure: Throwable) {
                saveLinkScreenshot("desktop-submit-failure")
                throw AssertionError(
                    "link-submit: busy=${controller.busy}, creating=${controller.links.creating}, error=${controller.links.errorCode}, documents=${controller.editorState.documents.size}",
                    failure
                )
            }
            val source = controller.editorState.documents.single { it.id == environment.sourceId }
            val reference = source.note.blocks.single { it.content is NoteReferenceBlockContent }
            val linkId = (reference.content as NoteReferenceBlockContent).noteId
            val next = source.note.blocks.single { it.type == BlockTypes.PARAGRAPH && !it.deleted }
            onNodeWithTag("text-input-${next.id.value}").assertIsFocused()
            onNodeWithTag("text-input-${next.id.value}").performTextInput("回车后继续正文示例")
            onNodeWithTag("reference-open-${reference.id.value}").assertIsDisplayed()
            saveLinkScreenshot("desktop-reference")
            onNodeWithTag("reference-open-${reference.id.value}").performClick()
            onNodeWithTag("hierarchy-value-${LinkNoteSchemas.GROUP_ID.value}").performClick()
            onNodeWithTag("hierarchy-choice-design-css").assertIsDisplayed().assertIsSelected()
            saveLinkScreenshot("desktop-picker-reopen")
            onNodeWithText("管理分组").performClick()
            onNodeWithTag("option-edit-design-css").performClick()
            onNodeWithTag("option-delete").assertIsNotEnabled()
            onNodeWithTag("option-name").performTextReplacement("样式")
            onNodeWithTag("option-parent").performClick()
            onNodeWithText("技术 / 前端").performClick()
            onNodeWithTag("option-save").performClick()
            waitUntil(timeoutMillis = 10000) { !controller.propertyOptions.busy && controller.editorState.propertyDefinitions.single { it.id == LinkNoteSchemas.GROUP_ID }.options.single { it.id == "design-css" }.label == "样式" }
            saveLinkScreenshot("desktop-group-management")
            onNodeWithText("完成").performClick()
            onNodeWithText("技术 / 前端 / 样式").assertIsDisplayed()
            saveLinkScreenshot("desktop-link-note")
            onNodeWithText("引用此链接的笔记").assertDoesNotExist()
            onAllNodes(hasText(LinkNoteUiTestEnvironment.SOURCE_TITLE) and hasClickAction())[0].performClick()
            waitUntil { controller.editorState.selectedDocumentId == environment.sourceId }
            onNodeWithTag("reference-open-${reference.id.value}").assertIsDisplayed()
            onNodeWithTag("link-navigation").performClick()
            onNodeWithTag("link-library-search").performTextInput("Metadata page")
            onNodeWithTag("link-library-note-${linkId.value}").assertIsDisplayed()
            onNodeWithTag("link-library-group").performClick()
            onNodeWithTag("hierarchy-search").performTextInput("技术")
            onNodeWithTag("hierarchy-choice-tech").performClick()
            onNodeWithTag("link-library-note-${linkId.value}").assertIsDisplayed()
            onNodeWithTag("link-include-children").performClick()
            onNodeWithText("没有匹配的链接").assertIsDisplayed()
            onNodeWithTag("link-include-children").performClick()
            saveLinkScreenshot("desktop-library")
            runOnIdle {
                assertFalse(controller.propertyOptions.failed)
                val note = controller.editorState.documents.single { it.id == linkId }.note
                assertEquals(
                    "design-css",
                    (note.propertyValues[LinkNoteSchemas.GROUP_ID]?.value as JsonPrimitive).content
                )
                assertEquals(3, controller.editorState.documents.size)
            }
        }
        runBlocking {
            val loaded = environment.workspace.gateway.reload()!!
            assertEquals(3, loaded.notes.size)
            assertTrue(loaded.notes.single { it.id == environment.sourceId }.note.blocks.any { it.extractPlainText() == "回车后继续正文示例" })
            assertTrue(loaded.propertyDefinitions.single { it.definition.id == LinkNoteSchemas.GROUP_ID }.definition.options.any { it.id == "design-css" && it.parentOptionId == "front" && it.label == "样式" })
        }
    }

    /** 宽窄窗口抓取失败保持网址标题和空描述，可直接手动编辑并真实保存，不展示额外页脚。 */
    @Test
    fun supportsCreationWithSilentFetchFailure() = listOf(1280 to 900, 480 to 1000).forEach { (width, height) ->
        LinkNoteUiTestEnvironment().use { environment ->
            runDesktopComposeUiTest(width = width, height = height) {
                val prefix = if (width < 720) "compact" else "desktop"
                val controller = environment.appState.controller
                setContent { App(capabilities = environment.workspace.capabilities, appState = environment.appState) }
                waitUntil(timeoutMillis = 15000) { controller.phase == WorkspaceUiPhase.OPEN }
                onAllNodes(hasText("新建笔记", substring = true) and hasClickAction())[0].performClick()
                onNodeWithTag("supertag-picker-item-${LinkNoteSchemas.TYPE_ID.value}").performClick()
                onNodeWithTag("link-url-input").assertIsFocused()
                runOnIdle { assertEquals(2, controller.editorState.documents.size) }
                onNodeWithTag("link-url-input").performTextReplacement(environment.server.url("/retry"))
                onNodeWithTag("link-entry-group").performClick()
                onNodeWithTag("hierarchy-search").performTextInput("CSS")
                onNodeWithText("技术 / 前端 / CSS").assertIsDisplayed()
                saveLinkScreenshot("$prefix-picker")
                onNodeWithTag("hierarchy-choice-tech-css").performClick()
                onNodeWithTag("link-submit").performClick()
                waitUntil(timeoutMillis = 15000) { controller.links.fetchStates.values.any { it == LinkFetchState.FAILED } }
                onNodeWithText("暂时无法获取网页信息，可以手动编辑或重试。").assertDoesNotExist()
                onNodeWithText("重试").assertDoesNotExist()
                onNodeWithText("重新获取网页信息").assertDoesNotExist()
                onNodeWithText("引用此链接的笔记").assertDoesNotExist()
                onNodeWithText("还没有其他笔记引用此链接。").assertDoesNotExist()
                onNodeWithTag("note-title-input").assertTextEquals(environment.server.url("/retry"))
                runOnIdle { assertEquals("", LinkNotePolicy.description(controller.editorState.selectedDocument!!.note)) }
                saveLinkScreenshot("$prefix-failure")
                onNodeWithTag("note-title-input").performTextReplacement("我自己的书签")
                onNodeWithTag("note-description-input").performTextReplacement("我自己填写的描述")
                onNodeWithTag("hierarchy-value-${LinkNoteSchemas.GROUP_ID.value}").performScrollTo().performClick()
                onNodeWithText("未分组").performClick()
                waitUntil(timeoutMillis = 10000) { controller.editorState.selectedDocument?.dirty == false }
                runOnIdle {
                    assertEquals(3, controller.editorState.documents.size)
                    assertFalse(LinkNoteSchemas.GROUP_ID in controller.editorState.selectedDocument!!.note.propertyValues)
                }
                saveLinkScreenshot("$prefix-manual-link")
            }
            runBlocking {
                val loaded = environment.workspace.gateway.reload()!!
                assertEquals(3, loaded.notes.size)
                val saved = loaded.notes.single { it.note.title == "我自己的书签" }.note
                assertFalse(LinkNoteSchemas.GROUP_ID in saved.propertyValues)
                assertEquals("我自己填写的描述", LinkNotePolicy.description(saved))
                assertEquals(environment.server.url("/retry"), LinkNotePolicy.address(saved)?.value)
            }
        }
    }
}
