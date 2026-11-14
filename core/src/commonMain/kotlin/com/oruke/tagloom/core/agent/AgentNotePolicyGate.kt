package com.oruke.tagloom.core.agent

import com.oruke.tagloom.core.ai.AiRetrievalScope
import com.oruke.tagloom.core.model.AiAccessPolicy
import com.oruke.tagloom.core.model.ObjectId
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * 统一执行内置 Agent Harness 与外部 API / CLI / MCP 对笔记 [AiAccessPolicy] 的确定性门禁。
 *
 * 硬性安全不变量：
 * 1. [AiAccessPolicy.LOCKED] 在任何自动发现、显式 `@` 引用及外部 `allowedNoteIds` / owner token 调用中均严格禁止；
 * 2. [AiAccessPolicy.REFERENCE_ONLY] 在一切搜索、列表、保存查询与反向链接发现中排除；内置仅允许用户亲手 `@` 挂载后访问，
 *    外部仅允许 `grant` 显式将该 `noteId` 列入 `allowedNoteIds` 白名单或凭 owner token 直接按 ID 调用；
 * 3. 模型或外部工具不得通过任何载荷篡改笔记的 `aiAccessPolicy` 系统字段。
 */
object AgentNotePolicyGate {
    /**
     * 判断具有该有效策略的笔记是否允许出现在搜索、列表、查询或反向链接等自动发现结果中。
     *
     * @param effectivePolicy 笔记经过类型继承收敛后的有效策略。
     * @return 仅当策略为 [AiAccessPolicy.ALLOW] 时为真。
     */
    fun canDiscover(effectivePolicy: AiAccessPolicy): Boolean = effectivePolicy == AiAccessPolicy.ALLOW

    /**
     * 校验内置 Agent Harness 对单篇笔记的读取授权。
     *
     * @param effectivePolicy 笔记经过类型继承收敛后的有效策略。
     * @param retrievalScope 当前会话激活的检索范围（`仅引用` 或 `全部笔记`）。
     * @param explicitlyCited 用户是否在当前上下文中亲手通过 `@` 单篇笔记或段落挂载了该笔记。
     * @param inCitedScopeFilter 该笔记是否命中用户已挂载的 `@` 笔记类型或保存查询范围。
     * @return 允许读取时返回 `null`；拒绝时返回稳定的 [AgentDenialCode]。
     */
    fun checkInternalRead(
        effectivePolicy: AiAccessPolicy,
        retrievalScope: AiRetrievalScope,
        explicitlyCited: Boolean,
        inCitedScopeFilter: Boolean = false,
    ): AgentDenialCode? = when (effectivePolicy) {
        AiAccessPolicy.LOCKED -> AgentDenialCode.AI_ACCESS_POLICY_LOCKED
        AiAccessPolicy.REFERENCE_ONLY -> if (explicitlyCited) null else AgentDenialCode.AI_ACCESS_POLICY_REFERENCE_ONLY
        AiAccessPolicy.ALLOW -> when {
            explicitlyCited || inCitedScopeFilter -> null
            retrievalScope == AiRetrievalScope.ALL_NOTES -> null
            else -> AgentDenialCode.TARGET_OUT_OF_SCOPE
        }
    }

    /**
     * 校验外部 API / CLI / MCP 按 `noteId` 直接读取或操作单篇笔记的策略门禁。
     *
     * @param effectivePolicy 笔记经过类型继承收敛后的有效策略。
     * @param noteId 目标笔记稳定 ID。
     * @param principal 发起调用的外部身份与 `allowedNoteIds` 范围。
     * @param isOwnerDirectCall 当前调用是否直接持有本机 owner token。
     * @return 允许访问时返回 `null`；违反策略时返回稳定的 [AgentDenialCode]。
     */
    fun checkExternalDirectAccess(
        effectivePolicy: AiAccessPolicy,
        noteId: ObjectId,
        principal: AgentPrincipal,
        isOwnerDirectCall: Boolean,
    ): AgentDenialCode? = when (effectivePolicy) {
        AiAccessPolicy.LOCKED -> AgentDenialCode.AI_ACCESS_POLICY_LOCKED
        AiAccessPolicy.REFERENCE_ONLY -> {
            val explicitlyWhitelisted = principal.allowedNoteIds?.contains(noteId) == true
            if (isOwnerDirectCall || explicitlyWhitelisted) {
                null
            } else {
                AgentDenialCode.AI_ACCESS_POLICY_REFERENCE_ONLY
            }
        }
        AiAccessPolicy.ALLOW -> {
            val allowList = principal.allowedNoteIds
            if (allowList != null && noteId !in allowList) {
                AgentDenialCode.TARGET_OUT_OF_SCOPE
            } else {
                null
            }
        }
    }

    /**
     * 检查工具提交的原始 `Note` JSON 对象是否企图篡改受保护的 `aiAccessPolicy` 系统字段。
     *
     * @param existingOverride 目标笔记原本保存的显式覆盖策略（可为空）。
     * @param incomingNoteJson 外部或内部工具提交的原始 `note` JSON 对象。
     * @return 未尝试篡改时为真；若显式传入了不同于原值的 `aiAccessPolicy` 则为假。
     */
    fun isPolicyUnchangedInJson(
        existingOverride: AiAccessPolicy?,
        incomingNoteJson: JsonObject,
    ): Boolean {
        if ("aiAccessPolicy" !in incomingNoteJson) {
            return true
        }
        val element = incomingNoteJson["aiAccessPolicy"]
        if (element == null || element is JsonNull) {
            return existingOverride == null
        }
        val text = (element as? JsonPrimitive)?.contentOrNull ?: return false
        return existingOverride?.name == text
    }
}
