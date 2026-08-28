package com.oruke.tagloom.sync

import com.oruke.tagloom.core.model.ActorId
import com.oruke.tagloom.core.model.WorkspaceId

/**
 * 定义跨平台同步会话能力。
 *
 * 契约只描述工作区同步，不暴露 HTTP、WebDAV、对象存储或平台网络类型；传输协议和本地副本
 * 的选择属于后续实现细节。同步结果不能覆盖本地已确认权威快照。
 */
interface SyncEngine {
    /**
     * 执行一次离线变更交换与远端内容补齐。
     *
     * @param request 当前工作区和设备的同步请求。
     * @return 只包含计数和冲突数量的脱敏同步结果。
     */
    suspend fun synchronize(request: SyncRequest): SyncResult

    /**
     * 关闭同步会话并使会话凭据失效。
     */
    suspend fun close()
}

/**
 * 描述一次同步会话的身份和可选增量边界。
 */
data class SyncRequest(
    /** 待同步的随机工作区 ID。 */
    val workspaceId: WorkspaceId,
    /** 当前认证设备的 Actor ID。 */
    val actorId: ActorId,
    /** 已确认的本地同步边界；为空表示由实现自行读取当前边界。 */
    val checkpoint: String? = null,
)

/**
 * 描述一次同步的非敏感统计结果。
 */
data class SyncResult(
    /** 首次上传的密文数量。 */
    val uploaded: Int,
    /** 本地新接收的密文数量。 */
    val downloaded: Int,
    /** 需要由本地合并流程处理的并发分支数量。 */
    val conflicts: Int,
)

/**
 * 创建当前宿主的同步能力。
 *
 * @return 当前宿主的同步实现。
 */
expect fun createSyncEngine(): SyncEngine
