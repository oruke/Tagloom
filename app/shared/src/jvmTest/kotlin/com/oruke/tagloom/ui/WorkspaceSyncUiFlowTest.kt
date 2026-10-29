package com.oruke.tagloom.ui

import androidx.compose.ui.graphics.asSkiaBitmap
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isEnabled
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import com.oruke.tagloom.App
import com.oruke.tagloom.core.model.Block
import com.oruke.tagloom.core.model.BlockId
import com.oruke.tagloom.core.model.BlockTypes
import com.oruke.tagloom.core.model.InlineRun
import com.oruke.tagloom.core.model.Note
import com.oruke.tagloom.core.model.RichText
import com.oruke.tagloom.core.model.RichTextBlockContent
import com.oruke.tagloom.workspace.ServerRecoveryTestEnvironment
import kotlinx.coroutines.runBlocking
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.Image
import java.nio.file.Files
import java.nio.file.Path
import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** 从真实双设备离线修改到生产 App 版本恢复，核对宽窄窗口交互及加密持久化结果。 */
@OptIn(ExperimentalTestApi::class)
class WorkspaceSyncUiFlowTest {
    /** 逐项解决标题和正文冲突，保留独立 Block 修改；完整历史、重开与跨设备状态一致。 */
    @Test
    fun reviewsAndRestoresPreservedVersionsThroughProductionUi() {
        val previousLocale = Locale.getDefault()
        Locale.setDefault(Locale.SIMPLIFIED_CHINESE)
        try {
            for (width in listOf(1280, 480)) {
                ServerRecoveryTestEnvironment().use { environment ->
                    val source = environment.device("ui-first")
                    val target = environment.device("ui-second")
                    val conflict = runBlocking {
                        val recoveryKey = requireNotNull(source.gateway.prepareCreation(null)).recoveryKeyText
                        source.gateway.confirmCreation()
                        val original = source.gateway.saveNote(null, Note(title = "同步冲突界面样例", directTagIds = emptyList(),
                            blocks = listOf(uiSyncBlock(0, "初始冲突正文"), uiSyncBlock(1, "初始甲方正文"), uiSyncBlock(2, "初始乙方正文"))))
                        source.gateway.serverRecovery.publish(environment.address, environment.bootstrap, recoveryKey)
                        target.gateway.serverRecovery.restore(environment.address, recoveryKey, null)
                        source.gateway.saveNote(original.id, original.note.copy(title = "设备甲保留版本", blocks = listOf(
                            uiSyncBlock(0, "甲方冲突正文"), uiSyncBlock(1, "甲方独立修改保留"), original.note.blocks[2])), original.revision)
                        target.gateway.saveNote(original.id, original.note.copy(title = "设备乙保留版本", blocks = listOf(
                            uiSyncBlock(0, "乙方冲突正文"), original.note.blocks[1], uiSyncBlock(2, "乙方独立修改保留"))), original.revision)
                        source.gateway.serverRecovery.updateIfChanged()
                        target.gateway.serverRecovery.updateIfChanged()
                        source.gateway.serverRecovery.updateIfChanged()
                        target.gateway.serverRecovery.conflicts().single().also {
                            assertTrue(it.localTitle in setOf("设备甲保留版本", "设备乙保留版本"))
                            assertEquals(2, it.fields.size)
                            assertEquals(setOf("TITLE", "BLOCK"), it.fields.map { field -> field.labelKey }.toSet())
                            target.gateway.lock()
                        }
                    }
                    val titleField = conflict.fields.single { it.labelKey == "TITLE" }
                    val blockField = conflict.fields.single { it.labelKey == "BLOCK" }
                    val titleTag = "sync-conflict-field-${conflict.id}-${titleField.id}"
                    val blockTag = "sync-conflict-field-${conflict.id}-${blockField.id}"
                    val expectedBlock = if (conflict.remoteTitle == "设备甲保留版本") "甲方冲突正文" else "乙方冲突正文"
                    runDesktopComposeUiTest(width = width, height = 1000) {
                        setContent { App(capabilities = target.capabilities) }
                        waitUntil(timeoutMillis = 20_000) {
                            onAllNodesWithText("设置").fetchSemanticsNodes().isNotEmpty()
                        }
                        onNode(hasText("设置") and hasClickAction()).performClick()
                        onNodeWithText("管理工作区模式").performScrollTo().performClick()
                        waitUntil(timeoutMillis = 20_000) {
                            onAllNodesWithText("保留的版本").fetchSemanticsNodes().isNotEmpty()
                        }
                        onNodeWithTag("sync-conflicts-toggle").performScrollTo().performClick()
                        waitUntil(timeoutMillis = 20_000) {
                            onAllNodesWithText("1 项冲突待核对").fetchSemanticsNodes().isNotEmpty() &&
                                onAllNodesWithText(conflict.localTitle).fetchSemanticsNodes().isNotEmpty()
                        }
                        onNodeWithTag("sync-conflict-toggle-${conflict.id}").performScrollTo().performClick()
                        onNodeWithText("笔记标题").performScrollTo().assertIsDisplayed()
                        onNodeWithText("正文块").performScrollTo().assertIsDisplayed()
                        assertTrue(onAllNodesWithText("设备甲保留版本").fetchSemanticsNodes().isNotEmpty())
                        assertTrue(onAllNodesWithText("设备乙保留版本").fetchSemanticsNodes().isNotEmpty())
                        onNodeWithTag("sync-conflict-history-${conflict.id}").performScrollTo().performClick()
                        assertTrue(onAllNodesWithText("甲方冲突正文", substring = true).fetchSemanticsNodes().isNotEmpty())
                        assertTrue(onAllNodesWithText("乙方冲突正文", substring = true).fetchSemanticsNodes().isNotEmpty())
                        onNodeWithTag("sync-conflict-history-${conflict.id}").performScrollTo().performClick()
                        // 页面读取或前台提交期间不能点击，后台轮询则不再禁用审阅按钮。
                        onNode(hasText("使用版本一") and hasAnyAncestor(hasTestTag(titleTag))).performScrollTo()
                        waitUntil(timeoutMillis = 20_000) {
                            onAllNodes(hasText("使用版本一") and hasAnyAncestor(hasTestTag(titleTag)) and isEnabled())
                                .fetchSemanticsNodes().isNotEmpty()
                        }
                        onNode(hasText("使用版本一") and hasAnyAncestor(hasTestTag(titleTag))).assertIsEnabled().performClick()
                        saveSyncUiScreenshot(onRoot().captureToImage(), "selection-$width.png")
                        waitUntil(timeoutMillis = 20_000) { onAllNodesWithText("确认恢复").fetchSemanticsNodes().isNotEmpty() }
                        onNodeWithText("确认恢复").performScrollTo().assertIsDisplayed()
                        saveSyncUiScreenshot(onRoot().captureToImage(), "conflict-confirm-$width.png")
                        onNodeWithText("确认恢复").performClick()
                        waitUntil(timeoutMillis = 20_000) {
                            onAllNodesWithText("已处理 · 双方历史值已保留").fetchSemanticsNodes().isNotEmpty()
                        }
                        onNode(hasText("使用版本一") and hasAnyAncestor(hasTestTag(titleTag))).performScrollTo().assertIsNotEnabled()
                        runBlocking {
                            val partiallyResolved = target.gateway.serverRecovery.conflicts().single()
                            assertFalse(partiallyResolved.resolved)
                            assertTrue(partiallyResolved.fields.single { it.id == titleField.id }.resolved)
                            assertFalse(partiallyResolved.fields.single { it.id == blockField.id }.resolved)
                            val current = requireNotNull(target.gateway.reload()).notes.single().note
                            assertEquals(conflict.localTitle, current.title)
                            assertEquals(listOf("甲方独立修改保留", "乙方独立修改保留"), current.uiSyncTexts().drop(1))
                        }
                        onNode(hasText("使用版本二") and hasAnyAncestor(hasTestTag(blockTag))).performScrollTo()
                        waitUntil(timeoutMillis = 20_000) {
                            onAllNodes(hasText("使用版本二") and hasAnyAncestor(hasTestTag(blockTag)) and isEnabled())
                                .fetchSemanticsNodes().isNotEmpty()
                        }
                        onNode(hasText("使用版本二") and hasAnyAncestor(hasTestTag(blockTag))).assertIsEnabled().performClick()
                        waitUntil(timeoutMillis = 20_000) { onAllNodesWithText("确认恢复").fetchSemanticsNodes().isNotEmpty() }
                        onNodeWithText("确认恢复").performScrollTo().performClick()
                        waitUntil(timeoutMillis = 20_000) {
                            onAllNodesWithText("已处理 · 历史已保留", substring = true).fetchSemanticsNodes().isNotEmpty()
                        }
                        onNode(hasText("使用版本二") and hasAnyAncestor(hasTestTag(blockTag))).performScrollTo().assertIsNotEnabled()
                    }
                    runBlocking {
                        val confirmed = requireNotNull(target.gateway.reload()).notes.single().note
                        assertEquals(conflict.localTitle, confirmed.title)
                        assertEquals(listOf(expectedBlock, "甲方独立修改保留", "乙方独立修改保留"), confirmed.uiSyncTexts())
                        target.gateway.lock()
                        assertEquals(confirmed, target.gateway.openRecentWorkspace().notes.single().note)
                        assertTrue(target.gateway.serverRecovery.conflicts().single().resolved)
                        target.gateway.serverRecovery.updateIfChanged()
                        source.gateway.serverRecovery.updateIfChanged()
                        assertEquals(confirmed, source.gateway.reload()?.notes?.single()?.note)
                        assertTrue(source.gateway.serverRecovery.conflicts().single().resolved)
                    }
                }
            }
        } finally {
            Locale.setDefault(previousLocale)
        }
    }
}

/**
 * 构造具有稳定身份和顺序的正文 Block，两台设备仅替换内容而不改变定位。
 * @param index 测试正文中的零起始位置。
 * @param text 当前版本的完整文字。
 * @return 可通过正式笔记网关保存的正文 Block。
 */
private fun uiSyncBlock(index: Int, text: String): Block = Block(
    BlockId("ui-sync-block-$index"), BlockTypes.PARAGRAPH, orderKey = ('a'.code + index).toChar().toString(),
    content = RichTextBlockContent(RichText(listOf(InlineRun(text)))),
)

/** @return 当前确认笔记按顺序排列的完整正文，供闭环核对。 */
private fun Note.uiSyncTexts(): List<String> = blocks.map {
    (it.content as RichTextBlockContent).richText.runs.joinToString("") { run -> run.text }
}

/**
 * 保存生产 App 的实际帧，供失败定位与宽窄窗口人工核对。
 * @param bitmap 当前界面捕获的完整图像。
 * @param fileName 不含用户路径或业务数据的截图文件名。
 */
private fun saveSyncUiScreenshot(bitmap: androidx.compose.ui.graphics.ImageBitmap, fileName: String) {
    val directory = Path.of("build/reports/workspace-sync-ui")
    Files.createDirectories(directory)
    val encoded = requireNotNull(Image.makeFromBitmap(bitmap.asSkiaBitmap()).encodeToData(EncodedImageFormat.PNG))
    Files.write(directory.resolve(fileName), encoded.bytes)
}
