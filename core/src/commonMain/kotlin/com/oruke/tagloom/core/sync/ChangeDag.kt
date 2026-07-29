package com.oruke.tagloom.core.sync

import com.oruke.tagloom.core.model.ActorId
import com.oruke.tagloom.core.model.BlockId
import com.oruke.tagloom.core.model.ChangeId
import com.oruke.tagloom.core.model.ObjectId
import com.oruke.tagloom.core.model.ObjectRevision
import com.oruke.tagloom.core.model.TransactionId
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

/**
 * 描述跨设备可比较的 Hybrid Logical Clock 时间。
 */
@Serializable
data class HybridLogicalTime(
    /** 设备观测到的 UTC Unix 毫秒。 */
    val physicalTimeMillis: Long,
    /** 同一物理时间或时钟回退时递增的逻辑计数器。 */
    val logicalCounter: Int,
    /** 用于完全相同时确定性排序的参与者标识。 */
    val actorId: ActorId,
) {
    init {
        require(physicalTimeMillis >= 0) { "HLC 物理时间不能为负数" }
        require(logicalCounter >= 0) { "HLC 逻辑计数器不能为负数" }
    }
}

/**
 * 定义 Change 对对象造成的高层业务影响。
 */
@Serializable
enum class ChangeKind {
    /** 创建新对象。 */
    CREATE,

    /** 更新现有对象。 */
    UPDATE,

    /** 创建或更新 tombstone。 */
    DELETE,

    /** 合并多个并发 heads。 */
    MERGE,
}

/**
 * 描述 Change 中一个可扩展的领域操作。
 */
@Serializable
data class ChangeOperation(
    /** 稳定操作类型；未知类型必须原样保留。 */
    val type: String,
    /** 操作目标 Block；对象级操作可以为空。 */
    val targetBlockId: BlockId? = null,
    /** 操作的结构化 payload。 */
    val payload: JsonElement? = null,
) {
    init {
        require(type.isNotBlank()) { "Change 操作类型不能为空" }
    }
}

/**
 * 表示对象级 Change DAG 中一个不可变变更节点。
 */
@Serializable
data class ObjectChange(
    /** 对加密 Change 信封计算得到的内容标识。 */
    val changeId: ChangeId,
    /** 被修改的稳定对象标识。 */
    val objectId: ObjectId,
    /** 当前节点直接引用的一个或多个父 Change。 */
    val parentChangeIds: List<ChangeId>,
    /** 产生变更的设备或 Agent。 */
    val actorId: ActorId,
    /** 用于并发分支确定性排序和展示的 HLC。 */
    val hybridLogicalTime: HybridLogicalTime,
    /** 生成此变更时依据的权威快照修订。 */
    val baseSnapshotRevision: ObjectRevision?,
    /** 变更所属跨对象事务。 */
    val transactionId: TransactionId,
    /** 变更对对象的高层影响。 */
    val kind: ChangeKind,
    /** 按业务顺序排列的领域操作。 */
    val operations: List<ChangeOperation>,
    /** 设备签名的编码文本；本地未签名草稿可以为空。 */
    val signature: String? = null,
) {
    init {
        require(parentChangeIds.distinct().size == parentChangeIds.size) { "Change 父节点不能重复" }
        require(actorId == hybridLogicalTime.actorId) { "Change actorId 必须与 HLC actorId 一致" }
        require(kind == ChangeKind.DELETE || operations.isNotEmpty()) {
            "除 DELETE 外的 Change 至少需要一个领域操作"
        }
    }
}

/**
 * 描述 Change DAG 结构校验失败的稳定分类。
 */
@Serializable
enum class ChangeDagErrorCode {
    /** 输入包含相同 ID 的不同或重复节点。 */
    DUPLICATE_CHANGE,

    /** 一个节点引用了未提供的父 Change。 */
    MISSING_PARENT,

    /** 输入混入了其他业务对象的 Change。 */
    MIXED_OBJECTS,

    /** Change 父边形成循环。 */
    CYCLE,
}

/**
 * 描述 Change DAG 的一个结构问题。
 */
@Serializable
data class ChangeDagError(
    /** 稳定错误分类。 */
    val code: ChangeDagErrorCode,
    /** 直接触发错误的 Change；无法定位时为空。 */
    val changeId: ChangeId? = null,
    /** 与错误相关的父 Change；不适用时为空。 */
    val parentChangeId: ChangeId? = null,
    /** 面向开发与恢复界面的中文说明。 */
    val message: String,
)

/**
 * 汇总 Change DAG 的稳定拓扑顺序、heads 和结构问题。
 */
@Serializable
data class ChangeDagAnalysis(
    /** 父节点在前的稳定拓扑顺序。 */
    val topologicalOrder: List<ChangeId>,
    /** 没有已知后继节点的全部 heads。 */
    val heads: List<ChangeId>,
    /** 输入中发现的结构问题。 */
    val errors: List<ChangeDagError>,
) {
    /** DAG 是否不存在任何结构错误。 */
    val valid: Boolean
        get() = errors.isEmpty()
}

/**
 * 分析对象级 Change DAG，并提供因果关系查询。
 */
object ObjectChangeDag {
    /**
     * 校验 Change 集合并生成稳定拓扑顺序与 heads。
     *
     * @param changes 同一对象的 Change 节点集合。
     * @return DAG 分析结果；存在错误时仍返回可确定的部分信息。
     */
    fun analyze(changes: Collection<ObjectChange>): ChangeDagAnalysis {
        val errors = mutableListOf<ChangeDagError>()
        val groupedById = changes.groupBy(ObjectChange::changeId)
        groupedById.filterValues { nodes -> nodes.size > 1 }.forEach { (changeId, _) ->
            errors += ChangeDagError(
                code = ChangeDagErrorCode.DUPLICATE_CHANGE,
                changeId = changeId,
                message = "Change ${changeId.value} 重复",
            )
        }

        val nodes = groupedById.mapValues { (_, value) -> value.first() }
        val objectIds = nodes.values.map(ObjectChange::objectId).distinct()
        if (objectIds.size > 1) {
            errors += ChangeDagError(
                code = ChangeDagErrorCode.MIXED_OBJECTS,
                message = "一次 DAG 分析只能包含同一对象的 Change",
            )
        }

        val childrenByParent = mutableMapOf<ChangeId, MutableSet<ChangeId>>()
        val indegree = nodes.keys.associateWith { 0 }.toMutableMap()

        nodes.values.forEach { change ->
            change.parentChangeIds.forEach { parentId ->
                if (parentId !in nodes) {
                    errors += ChangeDagError(
                        code = ChangeDagErrorCode.MISSING_PARENT,
                        changeId = change.changeId,
                        parentChangeId = parentId,
                        message = "Change ${change.changeId.value} 缺少父节点 ${parentId.value}",
                    )
                } else if (childrenByParent.getOrPut(parentId) { mutableSetOf() }.add(change.changeId)) {
                    indegree[change.changeId] = indegree.getValue(change.changeId) + 1
                }
            }
        }

        val ready = indegree
            .filterValues { degree -> degree == 0 }
            .keys
            .sortedBy(ChangeId::value)
            .toMutableList()
        val order = mutableListOf<ChangeId>()

        while (ready.isNotEmpty()) {
            val next = ready.removeAt(0)
            order += next
            childrenByParent[next].orEmpty().sortedBy(ChangeId::value).forEach { childId ->
                val remaining = indegree.getValue(childId) - 1
                indegree[childId] = remaining
                if (remaining == 0) {
                    ready += childId
                    ready.sortBy(ChangeId::value)
                }
            }
        }

        if (order.size != nodes.size) {
            errors += ChangeDagError(
                code = ChangeDagErrorCode.CYCLE,
                message = "Change DAG 包含循环父边",
            )
        }

        val referencedParents = nodes.values.flatMap(ObjectChange::parentChangeIds).toSet()
        val heads = nodes.keys
            .filterNot(referencedParents::contains)
            .sortedBy(ChangeId::value)

        return ChangeDagAnalysis(
            topologicalOrder = order,
            heads = heads,
            errors = errors.distinct(),
        )
    }

    /**
     * 判断一个 Change 是否是另一个 Change 的直接或间接祖先。
     *
     * @param ancestorId 待判断的祖先 Change。
     * @param descendantId 待判断的后继 Change。
     * @param changes 可查询的 Change 集合。
     * @return 存在从后继沿父边到祖先的路径时返回 `true`。
     */
    fun isAncestor(
        ancestorId: ChangeId,
        descendantId: ChangeId,
        changes: Collection<ObjectChange>,
    ): Boolean {
        if (ancestorId == descendantId) {
            return false
        }

        val nodes = changes.associateBy(ObjectChange::changeId)
        val pending = ArrayDeque<ChangeId>()
        val visited = mutableSetOf<ChangeId>()
        nodes[descendantId]?.parentChangeIds?.forEach(pending::addLast)

        while (pending.isNotEmpty()) {
            val current = pending.removeFirst()
            if (!visited.add(current)) {
                continue
            }
            if (current == ancestorId) {
                return true
            }
            nodes[current]?.parentChangeIds?.forEach(pending::addLast)
        }

        return false
    }

    /**
     * 为 Merge Change 生成去重且稳定排序的父 heads。
     *
     * @param heads 待合并的分支 heads。
     * @return 可直接写入 Merge Change 的父 ID 列表。
     */
    fun mergeParentIds(heads: Collection<ChangeId>): List<ChangeId> =
        heads.distinct().sortedBy(ChangeId::value)

    /**
     * 按物理时间、逻辑计数器和 Actor ID 比较两个 HLC。
     *
     * @param left 左侧时间。
     * @param right 右侧时间。
     * @return 负数表示左侧更早，正数表示左侧更晚，零表示完全相同。
     */
    fun compareHybridTime(
        left: HybridLogicalTime,
        right: HybridLogicalTime,
    ): Int = compareValuesBy(
        left,
        right,
        HybridLogicalTime::physicalTimeMillis,
        HybridLogicalTime::logicalCounter,
        { time -> time.actorId.value },
    )
}
