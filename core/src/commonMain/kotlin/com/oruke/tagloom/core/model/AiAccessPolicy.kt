package com.oruke.tagloom.core.model

import kotlinx.serialization.Serializable

/**
 * 定义笔记与笔记类型（Supertag）共享的系统级三态 AI 访问控制策略。
 *
 * 作为系统受控字段直接挂载在 [Note] 与 [TagDefinition] 上，不建模为普通自定义属性，防止被属性工具删除或篡改。
 */
@Serializable
enum class AiAccessPolicy(
    /** 策略严格程度序号，数值越大越严格（`LOCKED > REFERENCE_ONLY > ALLOW`）。 */
    val severity: Int,
) {
    /** 允许在自动搜索、列表、双链、显式 `@` 引用及受控修改提案中访问（默认）。 */
    ALLOW(0),

    /** 自动搜索、列表与反向链接中完全排除；仅在用户显式 `@` 挂载或单篇显式白名单/owner 按 ID 直连时可读。 */
    REFERENCE_ONLY(1),

    /** 严禁在内置与外部任何入口发现、引用、读取或生成修改提案。 */
    LOCKED(2),
    ;

    companion object {
        /**
         * 返回两个策略中更严格的一项。
         *
         * @param left 第一项策略。
         * @param right 第二项策略。
         * @return 按 `LOCKED > REFERENCE_ONLY > ALLOW` 收敛后的策略。
         */
        fun strictest(left: AiAccessPolicy, right: AiAccessPolicy): AiAccessPolicy =
            if (left.severity >= right.severity) left else right

        /**
         * 计算一组候选策略的最严格收敛值；集合为空时回退为默认的 [ALLOW]。
         *
         * @param policies 待收敛的策略集合。
         * @return 集合中最严格的策略。
         */
        fun strictestOf(policies: Iterable<AiAccessPolicy>): AiAccessPolicy =
            policies.fold(ALLOW, ::strictest)
    }
}
