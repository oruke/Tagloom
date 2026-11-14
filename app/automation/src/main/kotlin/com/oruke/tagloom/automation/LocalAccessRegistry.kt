package com.oruke.tagloom.automation

import com.oruke.tagloom.core.agent.*
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.UUID

/** 表示已认证且绑定当前解锁会话的调用权限。 */
internal data class LocalAccess(
    /** 不含凭据的最小身份与 scope。 */
    val principal: AgentPrincipal,
    /** 是否为具备批准和授权能力的工作区持有者。 */
    val owner: Boolean,
    /** 是否显式发现高风险删除工具。 */
    val includeDangerous: Boolean,
)

/** 仅在内存保存 Token 摘要和会话授权，不把凭据写入工作区或日志。 */
internal class LocalAccessRegistry(
    /** 由宿主显式配置的高熵持有者凭据。 */
    ownerToken: String,
) {
    /** 用固定时间比较的 owner Token 摘要。 */
    private val ownerDigest = digest(ownerToken)
    /** 本服务生命周期内的随机 owner 身份。 */
    private val ownerId = UUID.randomUUID().toString()
    /** 按随机 principal ID 存储的凭据摘要和 scope。 */
    private val grants = linkedMapOf<String, Pair<ByteArray, LocalAccess>>()

    init { require(ownerToken.length >= 32 && ownerToken.length <= 1024) { "owner_token_invalid" } }

    /**
     * 校验凭据并获取当前会话权限。
     * @param token Bearer 凭据，不用于显示或日志。
     * @return 可信身份；无匹配时返回 401。
     */
    @Synchronized
    fun authenticate(token: String): LocalAccess {
        if (token.length !in 32..1024) throw LocalApiException(401, "unauthorized")
        val actual = digest(token)
        return try {
            if (MessageDigest.isEqual(ownerDigest, actual)) LocalAccess(
                AgentPrincipal(ownerId, AgentPermissionScope.entries.toSet()), true, true,
            ) else grants.values.firstOrNull { MessageDigest.isEqual(it.first, actual) }?.second
                ?: throw LocalApiException(401, "unauthorized")
        } finally { actual.fill(0) }
    }

    /**
     * 创建一个默认只读、当前解锁会话有效的新授权。
     * @param input 持有者明确授予的 scope、Note 范围和数量上限。
     * @param sessionId 当前随机解锁会话。
     * @return 只显示一次的随机 Token 和身份。
     */
    @Synchronized
    fun grant(input: LocalGrantInput, sessionId: String): LocalGrantResponse {
        if (grants.size >= 128) throw LocalApiException(429, "grant_limit")
        if (input.scopes.isEmpty() ||
            input.allowedNoteIds?.size?.let { it > 500 } == true) throw LocalApiException(400, "invalid_arguments")
        val id = UUID.randomUUID().toString()
        val random = ByteArray(32).also { SecureRandom().nextBytes(it) }
        val token = try { Base64.getUrlEncoder().withoutPadding().encodeToString(random) } finally { random.fill(0) }
        val access = LocalAccess(AgentPrincipal(id, input.scopes, input.allowedNoteIds, input.maxItemsPerCall), false, input.includeDangerous)
        grants[id] = digest(token) to access
        return LocalGrantResponse(id, token, sessionId)
    }

    /**
     * 撤销一个 principal，之后调用和待确认写入均被拒绝。
     * @param principalId 待撤销的随机身份。
     */
    @Synchronized
    fun revoke(principalId: String) { grants.remove(principalId)?.first?.fill(0) }

    /** 清除上一解锁会话的全部授权摘要。 */
    @Synchronized
    fun clear() { grants.values.forEach { it.first.fill(0) }; grants.clear() }

    /**
     * 检查请求所属授权是否仍有效。
     * @param principalId 请求身份。
     * @return owner 或未被撤销授权时为真。
     */
    @Synchronized
    fun active(principalId: String): Boolean = principalId == ownerId || principalId in grants

    /**
     * 生成不可用于恢复原 Token 的摘要。
     * @param token 输入凭据。
     * @return 固定长度 SHA-256 摘要。
     */
    private fun digest(token: String): ByteArray {
        val bytes = token.toByteArray(Charsets.UTF_8)
        return try { MessageDigest.getInstance("SHA-256").digest(bytes) } finally { bytes.fill(0) }
    }
}

/** 对外返回稳定 HTTP 失败，不包含异常堆栈或业务参数。 */
class LocalApiException(
    /** 适配器映射的 HTTP 状态。 */
    val status: Int,
    /** 稳定错误码。 */
    val code: String,
) : IllegalStateException(code)
