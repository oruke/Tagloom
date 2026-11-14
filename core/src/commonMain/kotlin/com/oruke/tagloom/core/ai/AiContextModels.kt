package com.oruke.tagloom.core.ai

import com.oruke.tagloom.core.model.BlockId
import com.oruke.tagloom.core.model.ObjectId

/** 定义 AI 推理发生位置及明文是否离开设备。 */
enum class AiExecutionMode {
    /** 只调用当前设备本地模型。 */
    LOCAL_ONLY,

    /** 调用用户自部署模型服务。 */
    SELF_HOSTED,

    /** 通过自部署 AI Gateway 转发供应商。 */
    GATEWAY,

    /** 客户端直接调用模型供应商。 */
    DIRECT_PROVIDER,
}

/** 限制一次 AI 上下文选择可以包含的明文规模。 */
data class AiContextBudget(
    /** 最多包含 Note 数量。 */
    val maxNotes: Int = 8,
    /** 最多包含 Block 数量。 */
    val maxBlocks: Int = 32,
    /** 所有 Block 文本合计最大 UTF-16 字符数。 */
    val maxCharacters: Int = 12_000,
) {
    init {
        require(maxNotes in 1..100) { "AI 上下文 Note 上限必须在 1 到 100 之间" }
        require(maxBlocks in 1..500) { "AI 上下文 Block 上限必须在 1 到 500 之间" }
        require(maxCharacters in 1..200_000) { "AI 上下文字符上限必须在 1 到 200000 之间" }
    }
}

/** 描述用户预览和授权的一个最小 AI 上下文 Block。 */
data class AiContextBlock(
    /** 来源 Note ID。 */
    val noteId: ObjectId,
    /** 来源 Note 标题。 */
    val noteTitle: String,
    /** 来源 Block ID。 */
    val blockId: BlockId,
    /** 移除样式且按预算截断的正文。 */
    val text: String,
)

/** 描述经过候选排序和硬预算裁剪的 AI 上下文。 */
data class AiContextSelection(
    /** 按发送顺序排列的最小 Block 集合。 */
    val blocks: List<AiContextBlock>,
    /** 实际包含的 Note 数量。 */
    val noteCount: Int,
    /** 实际文本字符总数。 */
    val characterCount: Int,
    /** 是否因任一预算删除或截断候选内容。 */
    val truncated: Boolean,
)

/** 抽象本地模型、自部署模型或远端 Gateway 的统一调用边界。 */
interface AiCompletionPort {
    /**
     * 使用已经完成 `AiAccessPolicy` 权限判定的最小上下文执行单次推理。
     *
     * 实现不得自行扩大上下文、扫描工作区或记录正文；流式交互式对话与工具循环统一走 [AiStreamPort]。
     *
     * @param mode 推理位置。
     * @param task 不包含密钥和隐式工作区访问指令的任务文本。
     * @param context 已获授权的最小上下文。
     * @return 模型返回文本；调用方必须把建议与实际写入分离。
     */
    suspend fun complete(
        mode: AiExecutionMode,
        task: String,
        context: AiContextSelection,
    ): String
}
