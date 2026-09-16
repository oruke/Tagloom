package com.oruke.tagloom.ui

import androidx.compose.ui.graphics.asSkiaBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import com.oruke.tagloom.App
import com.oruke.tagloom.core.model.*
import com.oruke.tagloom.core.workspace.WorkspaceDefaults
import com.oruke.tagloom.platform.WorkspaceGateway
import com.oruke.tagloom.platform.WorkspaceNote
import com.oruke.tagloom.time.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonPrimitive
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.Image
import java.nio.file.Files
import java.nio.file.Path
import java.util.Locale
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.*

/** 从生产 App 和真实加密工作区验证显示偏好、日期编辑和重开后的原始值。 */
@OptIn(ExperimentalTestApi::class)
class DateTimeSettingsFlowTest {
    /** 验证时区/格式选择、非法自定义格式、预览、未修改关闭和本地化编辑的完整保存闭环。 */
    @Test
    fun persistsDisplayPreferencesWithoutRewritingNoteTimes() = withEnvironment { workspace, dates ->
        val initial = prepareNote(workspace)
        val savedNotes = CopyOnWriteArrayList<WorkspaceNote>()
        val observedGateway = object : WorkspaceGateway by workspace.gateway {
            /**
             * 记录生产入口实际确认的保存，仍使用真实加密事务；revision 序号不能跨会话比较增量。
             * @param objectId 当前笔记的稳定 ID。
             * @param note 完整待保存内容。
             * @param expectedRevision 编辑器读取的确认版本。
             * @return 真正持久化后的笔记。
             */
            override suspend fun saveNote(
                objectId: ObjectId?,
                note: Note,
                expectedRevision: ObjectRevision?
            ): WorkspaceNote =
                workspace.gateway.saveNote(objectId, note, expectedRevision).also { savedNotes.add(it) }
        }
        runDesktopComposeUiTest(width = 1280, height = 820) {
            setContent {
                App(
                    capabilities = workspace.capabilities.copy(workspace = observedGateway),
                    dateTimeEnvironment = dates
                )
            }
            waitForText("2026/9/17 07:15")
            onNodeWithText("2026/9/16").assertIsDisplayed()
            openDateTimeSettings()
            onNodeWithTag("datetime-preview").assertTextContains("2026/9/17 04:07")
            screenshot("settings-desktop.png")
            onNodeWithTag("datetime-zone").performClick()
            onNodeWithTag("datetime-zone-search").assertIsFocused().performTextReplacement("no-such-zone")
            onNodeWithText("没有匹配的时区").assertIsDisplayed()
            onNodeWithTag("datetime-zone-search").performTextReplacement("new york")
            screenshot("zones-desktop.png")
            onNodeWithTag("datetime-zone-America/New_York").performClick()
            waitForPreview("2026/9/16 16:07")
            choose("datetime-date-format", "年-月-日 · yyyy-MM-dd")
            waitForPreview("2026-09-16 16:07")
            choose("datetime-hour-cycle", "12 小时制")
            waitForPreview("2026-09-16 4:07 下午")
            choose("datetime-date-format", "自定义格式")
            onNodeWithTag("datetime-custom-pattern").performScrollTo().performTextReplacement("YYYY-MM-DD")
            onNodeWithTag("datetime-apply-pattern").assertIsNotEnabled()
            onNodeWithText("请使用 yyyy、M 至 MMMM、d 或 dd，必须包含年月日；不要使用 YYYY 或 DD。").assertIsDisplayed()
            screenshot("invalid-pattern-desktop.png")
            onNodeWithTag("datetime-custom-pattern").performTextReplacement("dd/MM/yyyy")
            onNodeWithTag("datetime-apply-pattern").performClick()
            waitUntil(timeoutMillis = 15_000) { runBlocking { dates.store.load().customDatePattern == "dd/MM/yyyy" } }
            onNodeWithContentDescription("返回设置").performClick()
            onNodeWithText("返回笔记").performClick()
            onNodeWithText("16/09/2026").assertIsDisplayed()
            onNodeWithText("16/09/2026 7:15 下午").assertIsDisplayed().performClick()
            // 没有改动时不能重新解析分钟级显示文本，否则会丢失原始秒和毫秒。
            onNodeWithTag("temporal-value-confirm").performClick()
            assertTrue(savedNotes.isEmpty(), "显示偏好和未修改确认不应触发笔记保存")
            onNodeWithText("16/09/2026 7:15 下午").performClick()
            onNodeWithTag("temporal-value-input").performTextReplacement("invalid")
            onNodeWithTag("temporal-value-confirm").performClick()
            onNodeWithText("日期或时间无效，请按示例填写。").assertIsDisplayed()
            onNodeWithTag("temporal-value-input").assertTextContains("invalid")
                .performTextReplacement("16/09/2026 8:30 下午")
            onNodeWithTag("temporal-value-confirm").performClick()
            waitUntil(timeoutMillis = 15_000) { savedNotes.isNotEmpty() }
            waitForText("已保存")
            onNodeWithText("16/09/2026 8:30 下午").assertIsDisplayed()
            screenshot("note-localized-desktop.png")
        }
        runBlocking {
            val stored = FileDateTimePreferenceStore(workspace.root.resolve("display.json")).load()
            assertEquals("America/New_York", stored.zoneId)
            assertEquals("dd/MM/yyyy", stored.customDatePattern)
            assertEquals(DisplayHourCycle.TWELVE, stored.hourCycle)
            workspace.gateway.lock()
            val reopened = workspace.gateway.openRecentWorkspace().notes.single()
            assertEquals(initial.createdAt, reopened.createdAt)
            assertEquals(1, savedNotes.size, "只有真正修改日期属性才应产生一次保存")
            assertEquals(savedNotes.single(), reopened)
            assertEquals(JsonPrimitive("2026-09-16"), reopened.note.propertyValues.getValue(DATE_ID).value)
            assertEquals(JsonPrimitive("2026-09-17T00:30:00Z"), reopened.note.propertyValues.getValue(TIME_ID).value)
        }
    }

    /** 验证已保存设置加载、窄屏布局、搜索选择和恢复默认真实写盘。 */
    @Test
    fun loadsPreferencesAndRestoresSystemDefaultsOnCompactLayout() = withEnvironment { workspace, dates ->
        runBlocking {
            dates.store.save(
                DateTimePreferences(
                    "UTC",
                    DateDisplayFormat.ISO,
                    hourCycle = DisplayHourCycle.TWENTY_FOUR
                )
            )
        }
        prepareNote(workspace)
        runDesktopComposeUiTest(width = 480, height = 900) {
            setContent { App(capabilities = workspace.capabilities, dateTimeEnvironment = dates) }
            waitForText("2026-09-16 23:15")
            openDateTimeSettings()
            onNodeWithTag("datetime-preview").assertTextContains("2026-09-16 20:07")
            screenshot("settings-compact.png")
            onNodeWithTag("datetime-zone").performClick()
            onNodeWithTag("datetime-zone-search").performTextReplacement("kathmandu")
            screenshot("zones-compact.png")
            onNodeWithTag("datetime-zone-Asia/Kathmandu").performClick()
            waitForPreview("2026-09-17 01:52")
            onNodeWithTag("datetime-reset").performScrollTo().performClick()
            waitUntil(timeoutMillis = 15_000) { runBlocking { dates.store.load() == DateTimePreferences() } }
            onNodeWithContentDescription("返回设置").performClick()
            onNodeWithText("返回笔记").performClick()
            onNodeWithText("2026/9/17 07:15").assertIsDisplayed()
            onNodeWithText("2026/9/16").performClick()
            onNodeWithTag("temporal-value-input").performTextReplacement("")
            onNodeWithTag("temporal-value-confirm").performClick()
            waitForText("已保存")
        }
        assertEquals(
            DateTimePreferences(),
            runBlocking { FileDateTimePreferenceStore(workspace.root.resolve("display.json")).load() })
        runBlocking {
            workspace.gateway.lock()
            val reopened = workspace.gateway.openRecentWorkspace().notes.single()
            assertNull(reopened.note.propertyValues[DATE_ID])
            assertEquals(
                JsonPrimitive("2026-09-16T23:15:00.123Z"),
                reopened.note.propertyValues.getValue(TIME_ID).value
            )
        }
    }

    /** 验证真实写盘失败不会替换当前偏好，无效历史配置和系统更新也能恢复。 */
    @Test
    fun preservesPreferencesOnStorageFailureAndRefreshesSystemRules() = withEnvironment { workspace, dates ->
        runBlocking {
            val unusable = Files.createDirectory(workspace.root.resolve("not-a-file"))
            val controller = DateTimeSettingsController(dates.copy(store = FileDateTimePreferenceStore(unusable)))
            controller.load()
            assertFalse(controller.save(DateTimePreferences(zoneId = "UTC")))
            assertTrue(controller.failed)
            assertEquals(DateTimePreferences(), controller.preferences)
            val file = workspace.root.resolve("display.json")
            Files.writeString(
                file,
                """{"zoneId":"invalid/zone","dateFormat":"CUSTOM","customDatePattern":"YYYY-MM-DD"}"""
            )
            val recovered = DateTimeSettingsController(dates)
            recovered.load()
            assertEquals(DateTimePreferences(), recovered.preferences)
            var system = TEST_SYSTEM
            val changing = DateTimeSettingsController(dates.copy(platform = JvmDateTimePlatform { system }))
            changing.load()
            assertEquals("2026/9/17 07:15", changing.format("2026-09-16T23:15:00Z"))
            system =
                system.copy(zoneId = "UTC", datePattern = "dd.MM.yyyy", timePattern = "h:mm a", localeTag = "en-US")
            changing.refreshSystem()
            assertEquals("16.09.2026 11:15 PM", changing.format("2026-09-16T23:15:00Z"))
            val started = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val gatedStore = object : DateTimePreferenceStore by dates.store {
                /**
                 * 暂停真实写盘以模拟离开设置页，恢复后仍写入隔离设备配置。
                 * @param preferences 需要持久化的完整偏好。
                 */
                override suspend fun save(preferences: DateTimePreferences) {
                    started.complete(Unit)
                    release.await()
                    dates.store.save(preferences)
                }
            }
            val ongoing = DateTimeSettingsController(dates.copy(store = gatedStore))
            ongoing.load()
            val next = DateTimePreferences(zoneId = "UTC")
            val saveJob = launch { ongoing.save(next) }
            started.await()
            saveJob.cancel()
            release.complete(Unit)
            saveJob.join()
            assertEquals(next, dates.store.load())
            assertEquals(next, ongoing.preferences)
            assertFalse(ongoing.saving)
        }
    }

    /**
     * 从正式网关准备可观察的日期属性，之后锁定以便 App 自行解锁。
     * @param environment 隔离真实工作区。
     * @return 初始已确认笔记，用于重开后的创建时间比较。
     */
    private fun prepareNote(environment: WorkspaceSettingsTestEnvironment): WorkspaceNote = runBlocking {
        environment.gateway.openRecentWorkspace()
        environment.gateway.savePropertyDefinition(PropertyDefinition(DATE_ID, "Event date", PropertyTypes.DATE))
        environment.gateway.savePropertyDefinition(PropertyDefinition(TIME_ID, "Event time", PropertyTypes.DATETIME))
        val note = environment.gateway.saveNote(
            null, Note(
                title = "Date display example", directTagIds = listOf(WorkspaceDefaults.DEFAULT_NOTE_TAG_ID),
                manualPropertyBindings = listOf(ManualPropertyBinding(DATE_ID), ManualPropertyBinding(TIME_ID)),
                propertyValues = mapOf(
                    DATE_ID to NotePropertyValue(
                        JsonPrimitive("2026-09-16"),
                        PropertyValueSource.USER,
                        updatedAt = PREVIEW_TIME
                    ),
                    TIME_ID to NotePropertyValue(
                        JsonPrimitive("2026-09-16T23:15:00.123Z"),
                        PropertyValueSource.USER,
                        updatedAt = PREVIEW_TIME
                    ),
                ),
            )
        )
        environment.gateway.lock()
        note
    }

    /** 从已解锁的生产导航进入日期时间设置。 */
    private fun ComposeUiTest.openDateTimeSettings() {
        onNode(hasText("设置") and hasClickAction()).performClick()
        onNodeWithText("调整").performClick()
        waitUntil { onAllNodes(hasTestTag("datetime-zone") and isEnabled()).fetchSemanticsNodes().isNotEmpty() }
    }

    /**
     * 选择下拉选项，并等待写盘完成。
     * @param tag 设置控件标识。
     * @param label 选项名称。
     */
    private fun ComposeUiTest.choose(tag: String, label: String) {
        onNodeWithTag(tag).performScrollTo().performClick()
        onNodeWithText(label).performClick()
        waitUntil { onAllNodes(hasTestTag(tag) and isEnabled()).fetchSemanticsNodes().isNotEmpty() }
    }

    /**
     * 等待异步设置写盘后刷新预览。
     * @param text 预期完整预览值。
     */
    private fun ComposeUiTest.waitForPreview(text: String) {
        waitForText(text)
        onNodeWithTag("datetime-preview").assertTextEquals(text)
        // 预览更新可能比保存协程退出早一帧；等待交互重新启用后才继续下一次选择。
        waitUntil(timeoutMillis = 15_000) {
            onAllNodes(hasTestTag("datetime-zone") and isEnabled()).fetchSemanticsNodes().isNotEmpty()
        }
    }

    /**
     * 等待真实界面状态出现。
     * @param text 预期文本。
     */
    private fun ComposeUiTest.waitForText(text: String) {
        waitUntil(timeoutMillis = 15_000) { onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty() }
    }

    /**
     * 截图保存到忽略的 build 目录，弹窗根单独保存。
     * @param name 不含用户数据的截图文件名。
     */
    private fun ComposeUiTest.screenshot(name: String) {
        val directory = Path.of("build", "reports", "datetime-ui")
        Files.createDirectories(directory)
        onAllNodes(isRoot()).fetchSemanticsNodes().indices.forEach { index ->
            Image.makeFromBitmap(onAllNodes(isRoot())[index].captureToImage().asSkiaBitmap()).use { image ->
                image.encodeToData(EncodedImageFormat.PNG)!!
                    .use { data -> Files.write(directory.resolve("$index-$name"), data.bytes) }
            }
        }
    }

    /**
     * 创建隔离配置与真实平台能力，恢复测试前的 UI 语言。
     * @param scenario 生产入口验证流程。
     */
    private fun withEnvironment(scenario: (WorkspaceSettingsTestEnvironment, DateTimeEnvironment) -> Unit) {
        val previous = Locale.getDefault()
        try {
            Locale.setDefault(Locale.SIMPLIFIED_CHINESE)
            WorkspaceSettingsTestEnvironment().use { workspace ->
                val platform = object : DateTimePlatform by JvmDateTimePlatform({ TEST_SYSTEM }) {
                    /** @return 固定预览时钟；时间换算仍由真实平台实现。 */
                    override fun currentTimestamp(): String = PREVIEW_TIME
                }
                scenario(
                    workspace,
                    DateTimeEnvironment(platform, FileDateTimePreferenceStore(workspace.root.resolve("display.json")))
                )
            }
        } finally {
            Locale.setDefault(previous)
        }
    }

    private companion object {
        /** 测试专用纯日期字段。 */
        val DATE_ID = PropertyId("date-display-example")

        /** 测试专用时间字段。 */
        val TIME_ID = PropertyId("time-display-example")

        /** 脱敏固定预览时钟。 */
        const val PREVIEW_TIME = "2026-09-16T20:07:00Z"

        /** 确定性的系统规则，与真实配置隔离。 */
        val TEST_SYSTEM = SystemDateTimeSettings("zh-CN", "Asia/Shanghai", "yyyy/M/d", "HH:mm")
    }
}
