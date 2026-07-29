package com.oruke.tagloom.core.domain.tag

import com.oruke.tagloom.core.model.TagDefinition
import com.oruke.tagloom.core.model.TagId
import kotlinx.serialization.Serializable

/**
 * 描述有效标签解析错误的稳定分类。
 */
@Serializable
enum class TagResolutionErrorCode {
    /** 输入或继承路径引用了不存在的标签。 */
    MISSING_TAG,

    /** 标签继承图包含直接或间接循环。 */
    INHERITANCE_CYCLE,
}

/**
 * 描述无法完成有效标签解析的具体原因。
 */
@Serializable
data class TagResolutionError(
    /** 稳定错误分类。 */
    val code: TagResolutionErrorCode,
    /** 直接触发错误的标签；无法定位时为空。 */
    val tagId: TagId? = null,
    /** 从解析入口到错误位置的标签路径。 */
    val path: List<TagId> = emptyList(),
    /** 面向开发与恢复界面的中文说明。 */
    val message: String,
)

/**
 * 描述一个有效标签在稳定拓扑序中的领域信息。
 */
@Serializable
data class ResolvedTag(
    /** 有效标签标识。 */
    val tagId: TagId,
    /** 标签距离根 Note 标签的最大继承深度。 */
    val depth: Int,
    /** 笔记是否直接关联此标签。 */
    val direct: Boolean,
)

/**
 * 表示有效标签解析的成功或失败结果。
 */
@Serializable
sealed interface TagResolution {
    /**
     * 表示解析成功，标签按父级在前、子级在后的稳定拓扑序排列。
     */
    @Serializable
    data class Success(
        /** 解析得到的全部有效标签。 */
        val tags: List<ResolvedTag>,
    ) : TagResolution

    /**
     * 表示解析失败，调用方不得继续计算有效属性。
     */
    @Serializable
    data class Failure(
        /** 去重后的全部解析错误。 */
        val errors: List<TagResolutionError>,
    ) : TagResolution
}

/**
 * 解析笔记直接标签的完整继承闭包，并检测缺失标签与循环继承。
 *
 * 没有显式父标签的普通标签隐式继承根 Note 标签。输出父级在前，便于属性解析器按父到子应用重载。
 */
class TagResolver(
    /** 工作区根 Note 标签的稳定标识。 */
    private val rootTagId: TagId,
) {
    /**
     * 解析直接标签对应的有效标签和稳定拓扑顺序。
     *
     * @param directTagIds 笔记直接关联的标签。
     * @param tags 工作区内可用的标签定义。
     * @return 成功时返回父级在前的有效标签；失败时返回全部可发现错误。
     */
    fun resolve(
        directTagIds: Collection<TagId>,
        tags: Map<TagId, TagDefinition>,
    ): TagResolution {
        val errors = mutableListOf<TagResolutionError>()
        val states = mutableMapOf<TagId, VisitState>()
        val depths = mutableMapOf<TagId, Int>()
        val orderedIds = mutableListOf<TagId>()
        val path = mutableListOf<TagId>()
        val directIds = directTagIds.toSet()

        if (tags[rootTagId] == null) {
            errors += TagResolutionError(
                code = TagResolutionErrorCode.MISSING_TAG,
                tagId = rootTagId,
                path = listOf(rootTagId),
                message = "工作区缺少根 Note 标签 ${rootTagId.value}",
            )
        }

        directIds
            .sortedBy(TagId::value)
            .forEach { tagId ->
                visit(
                    tagId = tagId,
                    tags = tags,
                    states = states,
                    depths = depths,
                    orderedIds = orderedIds,
                    path = path,
                    errors = errors,
                )
            }

        if (errors.isNotEmpty()) {
            return TagResolution.Failure(
                errors = errors.distinctBy { error ->
                    listOf(
                        error.code.name,
                        error.tagId?.value.orEmpty(),
                        error.path.joinToString(separator = "/", transform = TagId::value),
                    ).joinToString(separator = "|")
                },
            )
        }

        return TagResolution.Success(
            tags = orderedIds.map { tagId ->
                ResolvedTag(
                    tagId = tagId,
                    depth = depths.getValue(tagId),
                    direct = tagId in directIds,
                )
            },
        )
    }

    /**
     * 深度优先访问一个标签，先加入全部父级，再加入当前标签。
     *
     * @param tagId 当前访问的标签。
     * @param tags 工作区标签定义。
     * @param states DFS 访问状态，用于检测回边。
     * @param depths 已计算的最大继承深度。
     * @param orderedIds 父级在前的稳定拓扑结果。
     * @param path 当前递归路径。
     * @param errors 收集到的解析错误。
     * @return 当前标签深度；无法解析时返回 `null`。
     */
    private fun visit(
        tagId: TagId,
        tags: Map<TagId, TagDefinition>,
        states: MutableMap<TagId, VisitState>,
        depths: MutableMap<TagId, Int>,
        orderedIds: MutableList<TagId>,
        path: MutableList<TagId>,
        errors: MutableList<TagResolutionError>,
    ): Int? {
        when (states[tagId]) {
            VisitState.VISITED -> return depths[tagId]
            VisitState.VISITING -> {
                val cycleStart = path.indexOf(tagId).coerceAtLeast(0)
                val cyclePath = path.drop(cycleStart) + tagId
                errors += TagResolutionError(
                    code = TagResolutionErrorCode.INHERITANCE_CYCLE,
                    tagId = tagId,
                    path = cyclePath,
                    message = "检测到标签循环继承：${cyclePath.joinToString(" -> ") { it.value }}",
                )
                return null
            }

            null -> Unit
        }

        val tag = tags[tagId]
        if (tag == null) {
            errors += TagResolutionError(
                code = TagResolutionErrorCode.MISSING_TAG,
                tagId = tagId,
                path = path + tagId,
                message = "标签 ${tagId.value} 不存在",
            )
            return null
        }

        states[tagId] = VisitState.VISITING
        path += tagId

        val parentIds = effectiveParentIds(tag)
        val parentDepths = parentIds
            .sortedBy(TagId::value)
            .mapNotNull { parentId ->
                visit(
                    tagId = parentId,
                    tags = tags,
                    states = states,
                    depths = depths,
                    orderedIds = orderedIds,
                    path = path,
                    errors = errors,
                )
            }

        path.removeAt(path.lastIndex)
        states[tagId] = VisitState.VISITED

        val depth = if (tagId == rootTagId) {
            0
        } else {
            (parentDepths.maxOrNull() ?: 0) + 1
        }
        depths[tagId] = depth

        if (tagId !in orderedIds) {
            orderedIds += tagId
        }

        return depth
    }

    /**
     * 取得标签的显式父级或根 Note 标签隐式父级。
     *
     * @param tag 当前标签定义。
     * @return 用于继承解析的父标签列表。
     */
    private fun effectiveParentIds(tag: TagDefinition): List<TagId> = when {
        tag.id == rootTagId -> emptyList()
        tag.parentTagIds.isEmpty() -> listOf(rootTagId)
        else -> tag.parentTagIds
    }

    /**
     * 表示 DFS 节点尚在递归栈内或已经完成访问。
     */
    private enum class VisitState {
        /** 节点位于当前递归栈内。 */
        VISITING,

        /** 节点及全部父级已经访问完成。 */
        VISITED,
    }
}
