package com.oruke.tagloom.capability

import com.oruke.tagloom.filesystem.WorkspaceFileSystem
import com.oruke.tagloom.index.IndexEngine
import com.oruke.tagloom.index.SearchEngine
import com.oruke.tagloom.platform.WorkspaceGateway
import com.oruke.tagloom.sync.SyncEngine

/**
 * 聚合共享 App 使用的跨平台能力。
 *
 * 能力本身分别位于三个 KMP 模块；此容器只负责在应用入口传递依赖，不能承载平台分支或具体实现。
 */
data class TagloomCapabilities(
    /** 工作区生命周期和权威写入入口。 */
    val workspace: WorkspaceGateway,
    /** 加密文件读写、恢复和单写入者租约能力。 */
    val fileSystem: WorkspaceFileSystem,
    /** 从已确认快照维护派生索引的能力。 */
    val indexEngine: IndexEngine,
    /** 执行全文、结构化过滤和双链查询的能力。 */
    val searchEngine: SearchEngine,
    /** 交换密文变更并报告冲突的能力。 */
    val syncEngine: SyncEngine,
)
