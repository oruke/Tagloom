package com.oruke.tagloom

import android.content.ClipboardManager
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
import com.oruke.tagloom.core.model.CodeBlockContent
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Test
import org.junit.runner.RunWith
import java.nio.file.Files
import kotlin.math.abs
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** 在中文 Android 设备的真实 Compose 宿主、Keystore 和 SQLCipher 上验收代码块。 */
@RunWith(AndroidJUnit4::class)
class AndroidCodeBlockEditorTest {
    /** 使用正式无障碍操作验证代码语言、原文复制、软换行与重新解密的最终快照。 */
    @Test
    fun editsCodeAndReopensEncryptedSnapshot() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val area = AndroidTestArea()
        var capabilities: TagloomCapabilities? = null
        try {
            AndroidTagloomEnvironment.initialize(area.context)
            val active = createAndroidTagloomCapabilities(area.documents)
            capabilities = active
            runBlocking {
                active.workspace.prepareCreation(null)
                active.workspace.confirmCreation()
                active.workspace.lock()
            }
            ActivityScenario.launch(ComponentActivity::class.java).use { scenario ->
                scenario.onActivity { activity ->
                    activity.enableEdgeToEdge()
                    activity.setContent {
                        // 只在测试宿主暴露现有测试标识，生产 App 和业务能力保持原样。
                        Box(Modifier
                            .fillMaxSize()
                            .safeDrawingPadding()
                            .semantics { testTagsAsResourceId = true }) {
                            App(capabilities = active)
                        }
                    }
                }
                val driver = AndroidCodeEditorDriver(instrumentation, onTimeout = ::saveScreenshot)
                driver.click("新建笔记")
                driver.setText("未命名笔记", "代码笔记示例")
                driver.setText("输入内容，按 / 选择块类型", "```json\n$JSON_CODE\n```")
                driver.click("代码语言")
                driver.setText("搜索语言…", "sql")
                driver.click("SQL")
                driver.setText(JSON_CODE, SQL_CODE)
                driver.awaitText(SQL_CODE)
                driver.awaitChecked("自动换行", checked = true)
                driver.click("自动换行")
                driver.awaitChecked("自动换行", checked = false)
                driver.click("复制")
                driver.awaitText("已复制")
                val clipboard = instrumentation.targetContext.getSystemService(ClipboardManager::class.java)
                assertEquals(SQL_CODE, clipboard.primaryClip?.getItemAt(0)?.text?.toString())
                scenario.onActivity { activity ->
                    val inputMethod = activity.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
                    inputMethod.hideSoftInputFromWindow(activity.window.decorView.windowToken, 0)
                }
                val pane = driver.bounds("editor-pane")
                val column = driver.bounds("editor-reading-column")
                assertTrue(abs(pane.exactCenterX() - column.exactCenterX()) <= 1f)
                driver.click("⋯")
                driver.click("立即保存    Ctrl+S")
                driver.awaitText("已保存")
                saveScreenshot()
            }
            // 窗口已经关闭，再重新解密，证明结果不依赖 Compose 内存中的草稿。
            val reopened = runBlocking {
                active.workspace.lock()
                active.workspace.openRecentWorkspace().notes.single()
            }
            val block = reopened.note.blocks.first { it.content is CodeBlockContent }
            assertEquals(SQL_CODE, (block.content as CodeBlockContent).text)
            assertEquals("sql", (block.content as CodeBlockContent).language)
            assertEquals(JsonPrimitive(false), block.attributes["codeWrap"])
        } finally {
            runBlocking {
                capabilities?.workspace?.lock()
                capabilities?.syncEngine?.close()
            }
            AndroidTagloomEnvironment.initialize(instrumentation.targetContext)
            area.close()
        }
    }

    /** 保存设备的真实窗口截图，供宿主拉取并人工核对。 */
    private fun saveScreenshot() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val destination =
            instrumentation.targetContext.getExternalFilesDir(null)!!.toPath().resolve("code-editor-android.png")
        Files.newOutputStream(destination).use { output ->
            checkNotNull(instrumentation.uiAutomation.takeScreenshot()).apply {
                compress(Bitmap.CompressFormat.PNG, 100, output)
                recycle()
            }
        }
    }

    private companion object {
        /** 从真实 Markdown 粘贴入口创建代码块的脱敏 JSON。 */
        const val JSON_CODE = "{\"language\": \"SQL\", \"enabled\": true}"

        /** 与最终语言一致，且含注释、关键字和字符串的脱敏代码。 */
        const val SQL_CODE =
            "-- 示例：筛选代码笔记\nSELECT title, language\nFROM snippets\nWHERE language = 'SQL'\nORDER BY title;"
    }
}
