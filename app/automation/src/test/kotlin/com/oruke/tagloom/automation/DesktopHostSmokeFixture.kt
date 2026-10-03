package com.oruke.tagloom.automation

import com.oruke.tagloom.core.model.*
import com.oruke.tagloom.core.workspace.WorkspaceDefaults
import com.oruke.tagloom.workspace.*
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import java.nio.file.Path

/**
 * 仅在测试 classpath 准备真实桌面启动资料；登记通过生产 DPAPI 流程生成，不修改用户登记。
 * @param arguments 必须为空，隔离根目录通过显式测试环境传入。
 */
fun main(arguments: Array<String>) = runBlocking {
    require(arguments.isEmpty())
    val root = Path.of(requireNotNull(System.getenv("TAGLOOM_HOST_SMOKE_DIR"))).toAbsolutePath().normalize()
    val temporary = Path.of(System.getProperty("java.io.tmpdir")).toAbsolutePath().normalize()
    check(root.parent == temporary && root.fileName.toString().startsWith("tagloom-host-smoke-"))
    check(Path.of(requireNotNull(System.getenv("LOCALAPPDATA"))).toAbsolutePath().normalize() == root.resolve("local"))
    val workspace = Files.createDirectory(root.resolve("workspace"))
    val manager = DesktopWorkspaceManager()
    val access = DesktopWorkspaceLocalAccessService()
    try {
        // 阶段 1：按生产生命周期生成随机材料与已确认工作区，恢复密钥不输出。
        access.preparePendingCreation(workspace, null) { manager.prepareCreation(workspace, it) }
        access.confirmPendingCreation(manager::confirmCreation, manager::lock)
        // 阶段 2：写入脱敏基线，正式桌面进程必须自行从登记重新认证和渲染。
        requireNotNull(manager.sessionOrNull()).saveNote(null, Note("Desktop host sample", listOf(WorkspaceDefaults.DEFAULT_NOTE_TAG_ID),
            blocks = listOf(Block(BlockId("host-block-sample"), BlockTypes.PARAGRAPH, orderKey = "a",
                content = RichTextBlockContent(RichText(listOf(InlineRun("Desktop content sample"))))))))
        println("desktop_smoke_fixture_ready")
    } finally { access.discardPendingCreation(); manager.lock() }
}
