package com.oruke.tagloom.ui

import androidx.compose.ui.graphics.asSkiaBitmap
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.*
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import com.oruke.tagloom.App
import com.oruke.tagloom.core.model.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.Image
import java.nio.file.Files
import java.nio.file.Path
import java.util.Locale
import kotlin.test.*

/** 从生产 App 到真实加密快照验证继承默认值、局部覆盖、恢复和多父冲突。 */
@OptIn(ExperimentalTestApi::class)
class SupertagInheritanceFlowTest {
    /** 单选和多选只保存本级覆盖；跨会话恢复后跟随父级，既有笔记快照仍保持原值。 */
    @Test
    fun overridesAndRestoresInheritedDefaultsAcrossSessions() = withEnvironment { environment ->
        val fixture = prepareInheritance(environment)
        runDesktopComposeUiTest(width = 1280, height = 900) {
            setContent { App(capabilities = environment.capabilities) }
            openChild(fixture.child)
            expandProperty(fixture.stage.id)
            onNodeWithTag(headerTag(fixture.stage.id)).assertTextContains("单选")
                .assertTextContains("继承自 Parent example")
            onNodeWithTag(removeTag(fixture.stage.id)).assertDoesNotExist()
            onNodeWithText("Private only").assertDoesNotExist()
            // 添加菜单不重复列出已继承属性；打开和关闭菜单不能创建任何本级绑定。
            onNodeWithText("添加属性").performScrollTo().performClick()
            onNode(hasText("Stage") and hasAnyAncestor(isPopup())).assertDoesNotExist()
            onNode(hasText("Topics") and hasAnyAncestor(isPopup())).assertDoesNotExist()
            onNodeWithTag("supertag-name").performScrollTo().performClick()
            onNodeWithTag("supertag-editor-form").assertExists()

            propertyNode(fixture.stage.id, hasText("Planned") and hasClickAction()).performScrollTo().performClick()
            onNode(hasText("Planned", substring = false) and hasAnyAncestor(isPopup())).performClick()
            onNodeWithTag(headerTag(fixture.stage.id)).assertTextContains("继承自 Parent example")
            onNodeWithTag(removeTag(fixture.stage.id)).assertDoesNotExist()
            propertyNode(fixture.stage.id, hasText("Planned") and hasClickAction()).performClick()
            onNodeWithText("Done", substring = false).performClick()
            onNodeWithTag(headerTag(fixture.stage.id)).assertTextContains("已覆盖 · 继承自 Parent example")
            expandProperty(fixture.topics.id)
            propertyNode(fixture.topics.id, hasText("Build") and hasClickAction()).performScrollTo().performClick()
            screenshot("inherited-overrides-desktop.png")
            onNodeWithTag("supertag-save").performClick()
            waitForTag("supertag-library-search")
            createNote(fixture.child, "Before restore")
            onNodeWithText("Done", substring = false).assertIsDisplayed()
            waitForText("已保存")
        }

        runBlocking {
            environment.gateway.lock()
            val opened = environment.gateway.openRecentWorkspace()
            val child = opened.tagDefinitions.single { it.definition.id == fixture.child.id }.definition
            assertEquals(setOf(fixture.stage.id, fixture.topics.id), child.propertyBindings.map { it.propertyId }.toSet())
            val stage = child.propertyBindings.single { it.propertyId == fixture.stage.id }
            assertEquals(JsonPrimitive("done"), stage.defaultValue)
            assertEquals(5, stage.order)
            assertTrue(stage.required)
            assertEquals(PropertyStyle(showInCard = false), stage.styleOverride)
            assertEquals(JsonArray(listOf(JsonPrimitive("build"), JsonPrimitive("design"))), child.propertyBindings
                .single { it.propertyId == fixture.topics.id }.defaultValue)
            assertEquals(fixture.parent, opened.tagDefinitions.single { it.definition.id == fixture.parent.id }.definition)
            assertEquals(fixture.sibling, opened.tagDefinitions.single { it.definition.id == fixture.sibling.id }.definition)
            assertEquals(fixture.stage, opened.propertyDefinitions.single { it.definition.id == fixture.stage.id }.definition)
            val note = opened.notes.single().note
            assertEquals(JsonPrimitive("done"), note.propertyValues.getValue(fixture.stage.id).value)
            assertEquals(PropertyValueSource.TAG_SNAPSHOT, note.propertyValues.getValue(fixture.stage.id).source)
            // 模拟父级后来更新；子级已覆盖的值须保留，恢复继承后才采用这个新默认值。
            environment.gateway.saveTagDefinition(fixture.parent.copy(propertyBindings = fixture.parent.propertyBindings.map {
                if (it.propertyId == fixture.stage.id) it.copy(defaultValue = JsonPrimitive("later")) else it
            }))
            environment.gateway.lock()
        }

        runDesktopComposeUiTest(width = 1280, height = 900) {
            setContent { App(capabilities = environment.capabilities) }
            openChild(fixture.child)
            expandProperty(fixture.stage.id)
            propertyNode(fixture.stage.id, hasText("Done") and hasClickAction()).assertExists()
            onNodeWithTag(removeTag(fixture.stage.id)).performScrollTo().performClick()
            onNodeWithTag(headerTag(fixture.stage.id)).assertTextContains("继承自 Parent example")
            propertyNode(fixture.stage.id, hasText("Later") and hasClickAction()).assertExists()
            onNodeWithTag(removeTag(fixture.stage.id)).assertDoesNotExist()
            expandProperty(fixture.topics.id)
            onNodeWithTag(removeTag(fixture.topics.id)).performScrollTo().performClick()
            screenshot("inherited-restored-desktop.png")
            onNodeWithTag("supertag-save").performClick()
            waitForTag("supertag-library-search")
            createNote(fixture.child, "After restore")
            onNodeWithText("Later", substring = false).assertIsDisplayed()
            waitForText("已保存")
        }

        runBlocking {
            environment.gateway.lock()
            val reopened = environment.gateway.openRecentWorkspace()
            assertTrue(reopened.tagDefinitions.single { it.definition.id == fixture.child.id }.definition.propertyBindings.isEmpty())
            assertEquals(JsonPrimitive("done"), reopened.notes.single { it.note.title == "Before restore" }.note
                .propertyValues.getValue(fixture.stage.id).value)
            val restoredNote = reopened.notes.single { it.note.title == "After restore" }.note
            assertEquals(JsonPrimitive("later"), restoredNote.propertyValues.getValue(fixture.stage.id).value)
            assertEquals(fixture.parent.id, restoredNote.propertyValues.getValue(fixture.stage.id).sourceTagId)
            assertEquals(JsonArray(listOf(JsonPrimitive("design"))), restoredNote.propertyValues.getValue(fixture.topics.id).value)
            assertTrue(PropertyId("private-only") !in restoredNote.propertyValues)
        }
    }

    /** 窄屏多父冲突阻止保存，明确选来源才能覆盖，恢复后仍需重新解决。 */
    @Test
    fun resolvesAndRestoresConflictingParentSettingsOnCompactScreen() = withEnvironment { environment ->
        val fixture = prepareInheritance(environment, conflicting = true)
        runDesktopComposeUiTest(width = 480, height = 900) {
            setContent { App(capabilities = environment.capabilities) }
            openChild(fixture.child)
            onNodeWithTag("supertag-save").assertIsNotEnabled()
            expandProperty(fixture.stage.id)
            onNodeWithTag(headerTag(fixture.stage.id)).assertTextContains("继承冲突", substring = true)
            propertyNode(fixture.stage.id, hasText("默认值：创建时填入")).assertDoesNotExist()
            onNodeWithTag("supertag-inherit-${fixture.stage.id.value}-${fixture.sibling.id.value}")
                .performScrollTo().assertIsDisplayed()
            screenshot("inherited-conflict-compact.png")
            onNodeWithTag("supertag-inherit-${fixture.stage.id.value}-${fixture.sibling.id.value}").performClick()
            onNodeWithTag("supertag-save").assertIsEnabled()
            propertyNode(fixture.stage.id, hasText("Done") and hasClickAction()).assertExists()
            onNodeWithTag(removeTag(fixture.stage.id)).performScrollTo().performClick()
            onNodeWithTag("supertag-save").assertIsNotEnabled()
            onNodeWithTag("supertag-inherit-${fixture.stage.id.value}-${fixture.parent.id.value}")
                .performScrollTo().performClick()
            propertyNode(fixture.stage.id, hasText("Planned") and hasClickAction()).assertExists()
            screenshot("inherited-resolved-compact.png")
            onNodeWithTag("supertag-save").performClick()
            waitForTag("supertag-library-search")
        }
        runBlocking {
            environment.gateway.lock()
            val reopened = environment.gateway.openRecentWorkspace()
            assertEquals(listOf(fixture.parent.propertyBindings.first()), reopened.tagDefinitions
                .single { it.definition.id == fixture.child.id }.definition.propertyBindings)
            assertEquals(fixture.parent, reopened.tagDefinitions.single { it.definition.id == fixture.parent.id }.definition)
            assertEquals(fixture.sibling, reopened.tagDefinitions.single { it.definition.id == fixture.sibling.id }.definition)
        }
    }

    /**
     * 通过真实事务准备共享选项目录和父子关系。
     * @param environment 独立加密工作区。
     * @param conflicting 是否让第二个父级提供不一致的单选默认值。
     * @return 后续交互与持久化断言共用的确认定义。
     */
    private fun prepareInheritance(environment: WorkspaceSettingsTestEnvironment, conflicting: Boolean = false): InheritanceFixture = runBlocking {
        val stage = PropertyDefinition(PropertyId("stage"), "Stage", PropertyTypes.SELECT, options = listOf(
            PropertyOption("planned", "Planned"), PropertyOption("done", "Done"), PropertyOption("later", "Later"),
        ))
        val topics = PropertyDefinition(PropertyId("topics"), "Topics", PropertyTypes.MULTI_SELECT, options = listOf(
            PropertyOption("design", "Design"), PropertyOption("build", "Build"),
        ))
        val hidden = PropertyDefinition(PropertyId("private-only"), "Private only", PropertyTypes.TEXT)
        val parent = TagDefinition(TagId("parent-example"), "Parent example", propertyBindings = listOf(
            TagPropertyBinding(stage.id, required = true, defaultValue = JsonPrimitive("planned"),
                defaultPolicy = DefaultValuePolicy.SNAPSHOT, order = 5, styleOverride = PropertyStyle(showInCard = false)),
            TagPropertyBinding(topics.id, defaultValue = JsonArray(listOf(JsonPrimitive("design"))),
                defaultPolicy = DefaultValuePolicy.SNAPSHOT, order = 6),
            TagPropertyBinding(hidden.id, defaultValue = JsonPrimitive("Hidden"),
                defaultPolicy = DefaultValuePolicy.SNAPSHOT, inheritable = false, order = 7),
        ))
        val sibling = TagDefinition(TagId("second-example"), "Second example", propertyBindings = if (conflicting) {
            listOf(parent.propertyBindings.first().copy(defaultValue = JsonPrimitive("done")))
        } else emptyList())
        val child = TagDefinition(TagId("child-example"), "Child example", parentTagIds = if (conflicting) {
            listOf(parent.id, sibling.id)
        } else listOf(parent.id))
        environment.gateway.openRecentWorkspace()
        listOf(stage, topics, hidden).forEach { environment.gateway.savePropertyDefinition(it) }
        listOf(parent, sibling, child).forEach { environment.gateway.saveTagDefinition(it) }
        environment.gateway.lock()
        InheritanceFixture(stage, topics, parent, sibling, child)
    }

    /**
     * 进入已确认子类型的生产维护页。
     * @param child 要编辑的子类型。
     */
    private fun ComposeUiTest.openChild(child: TagDefinition) {
        waitForText("笔记类型")
        onNode(hasText("笔记类型", substring = false) and hasClickAction()).performClick()
        onNodeWithText(child.name, substring = false).performClick()
        waitForTag("supertag-editor-form")
    }

    /**
     * 从生产选择器创建笔记，使默认值经过真实编辑器和自动保存流程。
     * @param child 新笔记应用的笔记类型。
     * @param title 脱敏测试标题。
     */
    private fun ComposeUiTest.createNote(child: TagDefinition, title: String) {
        onAllNodes(hasText("新建笔记", substring = true) and hasClickAction())[0].performClick()
        onNodeWithTag("supertag-picker-search").performTextReplacement(child.name)
        onNodeWithTag("supertag-picker-search").performKeyInput { pressKey(Key.Enter) }
        onNode(hasSetTextAction() and hasText("未命名笔记")).performTextReplacement(title)
    }

    /**
     * 展开一个当前收起的属性。
     * @param id 属性稳定 ID。
     */
    private fun ComposeUiTest.expandProperty(id: PropertyId) {
        onNodeWithTag(headerTag(id)).performScrollTo().performClick()
    }

    /**
     * 在特定属性内定位控件，避免继承属性重复标签引起歧义。
     * @param id 属性稳定 ID。
     * @param matcher 控件的语义条件。
     * @return 供真实输入与断言使用的控件。
     */
    private fun ComposeUiTest.propertyNode(id: PropertyId, matcher: SemanticsMatcher): SemanticsNodeInteraction =
        onNode(matcher and hasAnyAncestor(hasTestTag("supertag-property-${id.value}")))

    /**
     * 定位可折叠属性标题。
     * @param id 属性稳定 ID。
     * @return 属性标题的语义标识。
     */
    private fun headerTag(id: PropertyId): String = "supertag-property-header-${id.value}"

    /**
     * 定位本级绑定的移除操作。
     * @param id 属性稳定 ID。
     * @return 移除本级绑定或恢复继承操作的语义标识。
     */
    private fun removeTag(id: PropertyId): String = "supertag-property-remove-${id.value}"

    /** @param tag 异步写入后预期出现的页面标识。 */
    private fun ComposeUiTest.waitForTag(tag: String) {
        waitUntil(timeoutMillis = 15_000) { onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty() }
    }

    /** @param text 等待自动保存后的状态文本。 */
    private fun ComposeUiTest.waitForText(text: String) {
        waitUntil(timeoutMillis = 15_000) { onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty() }
    }

    /** @param filename 写入忽略的构建目录中的脱敏截图名。 */
    private fun ComposeUiTest.screenshot(filename: String) {
        val directory = Path.of("build", "reports", "supertags-ui")
        Files.createDirectories(directory)
        Image.makeFromBitmap(onAllNodes(isRoot())[0].captureToImage().asSkiaBitmap()).use { image ->
            image.encodeToData(EncodedImageFormat.PNG)!!.use { data -> Files.write(directory.resolve(filename), data.bytes) }
        }
    }

    /** @param scenario 使用独立真实工作区执行的中文端到端场景。 */
    private fun withEnvironment(scenario: (WorkspaceSettingsTestEnvironment) -> Unit) {
        val locale = Locale.getDefault()
        try {
            Locale.setDefault(Locale.SIMPLIFIED_CHINESE)
            WorkspaceSettingsTestEnvironment().use(scenario)
        } finally {
            Locale.setDefault(locale)
        }
    }
}

/** 保存同一真实事务夹具的确认定义，用于核对父级与共享属性未被子级修改。 */
private data class InheritanceFixture(
    /** 含稳定选项 ID 的单选属性。 */
    val stage: PropertyDefinition,
    /** 含稳定选项 ID 的多选属性。 */
    val topics: PropertyDefinition,
    /** 提供原始默认值与不可继承属性的父级。 */
    val parent: TagDefinition,
    /** 可提供竞争默认值的同级父标签。 */
    val sibling: TagDefinition,
    /** 最初没有本级属性绑定的子级。 */
    val child: TagDefinition,
)
