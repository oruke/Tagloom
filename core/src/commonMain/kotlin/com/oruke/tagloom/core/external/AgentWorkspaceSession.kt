package com.oruke.tagloom.core.external

import com.oruke.tagloom.core.agent.AgentConfirmationEvidence
import com.oruke.tagloom.core.agent.AgentPreparedToolCall
import com.oruke.tagloom.core.agent.AgentPrincipal
import com.oruke.tagloom.core.agent.AgentToolRequest
import com.oruke.tagloom.core.agent.AgentToolResult

/** 将外部协议绑定到客户端唯一工作区会话；适配器不取得文件路径或解密密钥。 */
interface AgentWorkspaceSession {
    /** 每次解锁重新分配的随机会话身份，用于撤销旧授权与确认。 */
    val sessionId: String

    /**
     * 注册同步失效通知，锁定和密钥轮换必须在销毁密钥前清除外部明文缓存。
     * @param listener 只清理内存、不得阻塞或执行工作区读写的回调。
     */
    fun onClosed(listener: () -> Unit)

    /**
     * 授权并生成无业务副作用的预览。
     * @param principal 可信认证层提供的身份与可访问范围。
     * @param request 根据协议参数推导的工具请求。
     * @param isOwnerDirectCall 当前请求是否直接持有本机 owner token（用于区分 `REFERENCE_ONLY` 按 ID 直读与普通全库 Agent token）。
     * @return 与当前会话绑定的授权结果和预览。
     */
    suspend fun prepare(
        principal: AgentPrincipal,
        request: AgentToolRequest,
        isOwnerDirectCall: Boolean = false,
    ): AgentPreparedToolCall

    /**
     * 在同一工作区会话执行请求，成功写入与审计必须原子确认。
     * @param prepared 此会话预览后的不可变调用。
     * @param confirmation 持有者对本次预览的确认事实；只读调用可为空。
     * @return 结构化业务结果或稳定错误码。
     */
    suspend fun execute(prepared: AgentPreparedToolCall, confirmation: AgentConfirmationEvidence?): AgentToolResult
}

/** 携带可以对外显示的稳定业务失败码；禁止附带底层异常文本或业务内容。 */
class ExternalCallException(
    /** 输入校验、对象缺失、版本冲突或锁定的机器可读原因。 */
    val code: String,
) : IllegalStateException(code)
