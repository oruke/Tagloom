package com.oruke.tagloom.ui

import androidx.compose.ui.graphics.asSkiaBitmap
import androidx.compose.ui.test.*
import com.oruke.tagloom.TagloomAppState
import com.oruke.tagloom.core.model.*
import com.oruke.tagloom.core.workspace.LinkNoteSchemas
import com.oruke.tagloom.core.workspace.WorkspaceDefaults
import com.oruke.tagloom.platform.CreateLinkNoteRequest
import kotlinx.coroutines.*
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.Image
import java.nio.file.Files
import java.nio.file.Path
import java.util.Locale

/** 使用真实工作区和网页服务的 UI 测试环境，只有文件选择和低成本 KDF 属于测试装配。 */
internal class LinkNoteUiTestEnvironment : AutoCloseable {
    /** 独立的真实加密工作区。 */
    val workspace = WorkspaceSettingsTestEnvironment()

    /** 本次测试的真实 HTTP 页面服务。 */
    val server = LinkMetadataTestServer()

    /** 与生产 App 相同的生命周期协程。 */
    private val operationScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    /** 可观察但不绕过生产入口的 App 会话。 */
    val appState = TagloomAppState(workspace.capabilities, operationScope)

    /** 原位输入使用的稳定测试段落身份。 */
    val anchorId = BlockId("link-source-input")

    /** 本次测试的来源笔记稳定身份。 */
    val sourceId: ObjectId get() = requireNotNull(loadedSourceId)

    /** 初始化期间暂存来源身份，不使用业务路径或标题派生 ID。 */
    private var loadedSourceId: ObjectId? = null

    /** 结束时恢复测试进程语言。 */
    private val previousLocale = Locale.getDefault()

    init {
        Locale.setDefault(Locale.SIMPLIFIED_CHINESE)
        runBlocking {
            val gateway = workspace.gateway
            gateway.openRecentWorkspace()
            val preset =
                gateway.links.create(CreateLinkNoteRequest(server.url("/seed"), typeName = "链接", groupName = "分组"))
            gateway.savePropertyDefinition(
                preset.properties.single { it.id == LinkNoteSchemas.GROUP_ID }.copy(
                    options = listOf(
                        PropertyOption("tech", "技术"),
                        PropertyOption("front", "前端", parentOptionId = "tech"),
                        PropertyOption("tech-css", "CSS", parentOptionId = "front"),
                        PropertyOption("design", "设计"),
                        PropertyOption("ui", "界面", parentOptionId = "design"),
                        PropertyOption("design-css", "CSS", parentOptionId = "ui"),
                    )
                )
            )
            loadedSourceId = gateway.saveNote(
                null, Note(
                    SOURCE_TITLE, listOf(WorkspaceDefaults.DEFAULT_NOTE_TAG_ID), blocks = listOf(
                        Block(
                            anchorId,
                            BlockTypes.PARAGRAPH,
                            orderKey = "0000000000",
                            content = RichTextBlockContent(RichText())
                        ),
                    )
                )
            ).id
            gateway.lock()
        }
    }

    /** 取消生产状态中的后台任务，再释放本测试独占的网络、工作区与语言配置。 */
    override fun close() {
        operationScope.cancel()
        appState.controller.links.clear()
        workspace.close()
        server.close()
        Locale.setDefault(previousLocale)
    }

    companion object {
        /** 脱敏、与界面文案无关的来源标题。 */
        const val SOURCE_TITLE = "链接来源示例"
    }
}

/**
 * 从实际 Compose 根节点保存界面与弹层，避免把没有弹层的主窗口误称为选择器截图。
 * @param filename 不含扩展名的固定截图标识。
 */
@OptIn(ExperimentalTestApi::class)
internal fun ComposeUiTest.saveLinkScreenshot(filename: String) {
    val directory = Path.of("build", "reports", "link-ui")
    Files.createDirectories(directory)
    onAllNodes(isRoot()).fetchSemanticsNodes().indices.forEach { index ->
        Image.makeFromBitmap(onAllNodes(isRoot())[index].captureToImage().asSkiaBitmap()).use { image ->
            image.encodeToData(EncodedImageFormat.PNG)!!
                .use { data -> Files.write(directory.resolve("$filename-root$index.png"), data.bytes) }
        }
    }
}
