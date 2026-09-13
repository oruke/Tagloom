package com.oruke.tagloom

import android.content.Context
import android.graphics.Bitmap
import android.view.inputmethod.InputMethodManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.oruke.tagloom.capability.AndroidTagloomEnvironment
import com.oruke.tagloom.capability.TagloomCapabilities
import com.oruke.tagloom.capability.createAndroidTagloomCapabilities
import com.oruke.tagloom.core.model.*
import com.oruke.tagloom.core.workspace.WorkspaceDefaults
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith
import java.nio.file.Files
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** 在真实 Android Compose、Keystore 和 SQLCipher 环境验证窄屏笔记内搜索。 */
@RunWith(AndroidJUnit4::class)
class AndroidNoteSearchTest {
    /** 图标下方悬浮查找、大小写切换、循环关闭和设置往返均不改变加密笔记。 */
    @Test
    fun findsCurrentNoteAndKeepsSnapshotUnchanged() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val area = AndroidTestArea()
        var capabilities: TagloomCapabilities? = null
        try {
            AndroidTagloomEnvironment.initialize(area.context)
            val active = createAndroidTagloomCapabilities(area.documents)
            capabilities = active
            val before = runBlocking {
                active.workspace.prepareCreation(null)
                active.workspace.confirmCreation()
                active.workspace.saveNote(null, sampleNote(), null).also { active.workspace.lock() }
            }
            ActivityScenario.launch(ComponentActivity::class.java).use { scenario ->
                scenario.onActivity { activity ->
                    activity.enableEdgeToEdge()
                    activity.setContent {
                        Box(Modifier
                            .fillMaxSize()
                            .safeDrawingPadding()
                            .semantics { testTagsAsResourceId = true }) {
                            App(capabilities = active)
                        }
                    }
                }
                val driver = AndroidCodeEditorDriver(instrumentation, { saveScreenshot() })
                val entryBounds = driver.bounds("note-search-entry")
                driver.click("查找")
                driver.awaitText("第 0 处，共 0 处")
                val toolbarBounds = driver.bounds("note-search-bar")
                val editorBounds = driver.bounds("editor-pane")
                assertTrue(toolbarBounds.top >= entryBounds.bottom, "浮层应位于图标下方")
                assertTrue(toolbarBounds.left >= editorBounds.left && toolbarBounds.right <= editorBounds.right)
                driver.setTaggedText("note-search-input", "needle")
                driver.awaitText("第 1 处，共 3 处")
                driver.click("下一处")
                driver.awaitText("第 2 处，共 3 处")
                driver.click("下一处")
                driver.awaitText("第 3 处，共 3 处")
                driver.click("下一处")
                driver.awaitText("第 1 处，共 3 处")
                driver.click("区分大小写")
                driver.awaitChecked("区分大小写", true)
                driver.awaitText("第 1 处，共 2 处")
                scenario.onActivity { activity ->
                    (activity.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager)
                        .hideSoftInputFromWindow(activity.window.decorView.windowToken, 0)
                }
                assertTrue(driver.bounds("note-search-input").width() > driver.bounds("note-search-bar").width() / 2)
                driver.awaitText("已保存")
                saveScreenshot()
                driver.setTaggedText("note-search-input", "没有这个词")
                driver.awaitText("没有匹配结果")
                driver.click("关闭查找")
                driver.click("查找")
                driver.awaitText("第 0 处，共 0 处")
                driver.click("关闭查找")
                driver.click("设置")
                driver.awaitText("主密钥与恢复密钥")
                saveScreenshot("settings-icons-android.png")
                driver.click("返回笔记")
                driver.awaitText("已保存")
            }
            val after = runBlocking {
                active.workspace.lock()
                active.workspace.openRecentWorkspace().notes.single()
            }
            assertEquals(before, after)
        } finally {
            runBlocking {
                capabilities?.workspace?.lock()
                capabilities?.syncEngine?.close()
            }
            AndroidTagloomEnvironment.initialize(instrumentation.targetContext)
            area.close()
        }
    }

    /** @return 仅用于真实查找流程的脱敏正文与代码笔记。 */
    private fun sampleNote(): Note = Note(
        title = "笔记内查找",
        directTagIds = listOf(WorkspaceDefaults.DEFAULT_NOTE_TAG_ID),
        blocks = listOf(
            Block(
                BlockId("search-paragraph"),
                BlockTypes.PARAGRAPH,
                null,
                "0",
                RichTextBlockContent(RichText(listOf(InlineRun("Needle needle：输入查找词即可定位，正文保持不变。"))))
            ),
            Block(
                BlockId("search-code"),
                BlockTypes.CODE,
                null,
                "1",
                CodeBlockContent(language = "kotlin", text = "val needle = \"示例代码\"\nprintln(42)")
            ),
        ),
    )

    /**
     * 保存隔离测试的实际设备画面，供宿主拉取并人工核对图标、布局与高亮。
     * @param filename 当前场景的固定截图文件名。
     */
    private fun saveScreenshot(filename: String = "note-search-android.png") {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val destination =
            instrumentation.targetContext.getExternalFilesDir(null)!!.toPath().resolve(filename)
        Files.newOutputStream(destination).use { output ->
            checkNotNull(instrumentation.uiAutomation.takeScreenshot()).apply {
                compress(Bitmap.CompressFormat.PNG, 100, output)
                recycle()
            }
        }
    }
}
