package com.oruke.tagloom.core.sync

import com.oruke.tagloom.core.ai.AgentThread
import com.oruke.tagloom.core.ai.AgentThreadValidator
import com.oruke.tagloom.core.ai.WorkspaceConfig
import com.oruke.tagloom.core.ai.WorkspaceConfigValidator
import com.oruke.tagloom.core.domain.tag.TagResolution
import com.oruke.tagloom.core.domain.tag.TagResolver
import com.oruke.tagloom.core.model.*
import com.oruke.tagloom.core.protocol.CanonicalJson
import com.oruke.tagloom.core.workspace.ManifestObjectKind
import com.oruke.tagloom.core.workspace.WorkspaceDefaults
import kotlinx.serialization.KSerializer

/**
 * 在同步事务发布任何对象之前，统一校验完整候选目录的身份、结构和跨对象引用。
 *
 * 校验不补写默认值、不解释未知属性类型，也不清理历史值；删除对象的引用 Block 保留原有不可用展示语义。
 */
object WorkspaceReplicaCatalogValidator {
    /**
     * 对整个候选确认点执行纯预验证，失败时调用方不得发布其中任何对象。
     * @param objects 本地保留、远端快进和并发合并后的完整对象并集，包含 tombstone。
     */
    fun validate(objects: Collection<WorkspaceReplicaObject>) {
        require(objects.map { it.kind to it.snapshot.header.objectId }.distinct().size == objects.size) {
            "同步目录包含重复对象"
        }
        // 阶段 1：所有对象先解码并核对头部身份，不能按输入顺序边解码边发布。
        val notes = decode(objects, ManifestObjectKind.NOTE, ObjectType.NOTE, Note.serializer())
        val tags = decode(objects, ManifestObjectKind.TAG, ObjectType.TAG, TagDefinition.serializer()) { it.id.value }
        val properties = decode(objects, ManifestObjectKind.PROPERTY, ObjectType.PROPERTY, PropertyDefinition.serializer()) { it.id.value }
        val queries = decode(objects, ManifestObjectKind.SAVED_QUERY, ObjectType.SAVED_QUERY, SavedQuery.serializer())
        val databases = decode(objects, ManifestObjectKind.DATABASE, ObjectType.DATABASE, DatabaseDefinition.serializer()) { it.id.value }
        val views = decode(objects, ManifestObjectKind.VIEW, ObjectType.VIEW, ViewDefinition.serializer()) { it.id.value }
        val workspaceConfigs = decode(
            objects,
            ManifestObjectKind.WORKSPACE_CONFIG,
            ObjectType.WORKSPACE_CONFIG,
            WorkspaceConfig.serializer(),
        ) { it.id.value }
        val agentThreads = decode(
            objects,
            ManifestObjectKind.AGENT_THREAD,
            ObjectType.AGENT_THREAD,
            AgentThread.serializer(),
        ) { it.id.value }
        require(
            notes.size + tags.size + properties.size + queries.size +
                databases.size + views.size + workspaceConfigs.size + agentThreads.size == objects.size,
        ) {
            "同步目录包含不支持的对象类别"
        }
        workspaceConfigs.filterNot { it.header.deleted }.forEach { snapshot ->
            require(WorkspaceConfigValidator.validate(snapshot.payload).isEmpty()) {
                "同步工作区 Agent 配置结构无效"
            }
        }
        agentThreads.filterNot { it.header.deleted }.forEach { snapshot ->
            require(AgentThreadValidator.validate(snapshot.payload).isEmpty()) {
                "同步工作区 Agent 会话结构无效"
            }
        }
        val activeTags = tags.filterNot { it.header.deleted }.map { it.payload }.associateBy(TagDefinition::id)
        val allProperties = properties.map { it.payload }.associateBy(PropertyDefinition::id)
        val activeProperties = properties.filterNot { it.header.deleted }.map { it.payload }.associateBy(PropertyDefinition::id)

        // 阶段 2：校验完整继承图和共享属性目录，不把未填写必填值或多父属性冲突当成数据损坏。
        properties.forEach { PropertyOptionTree.validate(it.payload.options, it.payload.hierarchical) }
        if (activeTags.isNotEmpty()) {
            require(TagResolver(WorkspaceDefaults.DEFAULT_NOTE_TAG_ID).resolve(activeTags.keys, activeTags) is TagResolution.Success) {
                "同步目录的类型继承缺少父级或包含循环"
            }
        }
        activeTags.values.forEach { tag ->
            require(tag.propertyBindings.all { it.propertyId in activeProperties }) { "同步类型绑定的属性定义不存在" }
            // 模板与 Note 共享完整 Block 树协议；逐 Block 校验无法发现两侧独立移动组合出的环或悬空父边。
            // 模板没有独立的默认页签，保留默认空值；空模板同样合法，不额外施加 Note 页面选择要求。
            require(NoteValidator.validate(Note(title = "", directTagIds = emptyList(), blocks = tag.templateBlocks)).isEmpty()) {
                "同步类型模板的 Block 树结构无效"
            }
        }
        notes.forEach { snapshot ->
            val note = snapshot.payload
            require(NoteValidator.validate(note).isEmpty()) { "同步笔记结构无效" }
            if (!snapshot.header.deleted) {
                require(note.directTagIds.isEmpty() ||
                    TagResolver(WorkspaceDefaults.DEFAULT_NOTE_TAG_ID).resolve(note.directTagIds, activeTags) is TagResolution.Success) {
                    "同步笔记引用的类型不存在或继承无效"
                }
                require(note.manualPropertyBindings.all { it.propertyId in allProperties }) { "同步笔记手动绑定的属性定义不存在" }
            }
        }

        // 阶段 3：保存查询可继续引用已删除目录的稳定身份，活动 Database/View 必须具备完整所属关系。
        val knownTagIds = tags.map { it.payload.id }.toSet()
        queries.filterNot { it.header.deleted }.forEach { snapshot ->
            require(snapshot.payload.tagIds.all { it in knownTagIds }) { "同步保存查询引用的类型不存在" }
            require(snapshot.payload.propertyFilters.all { it.propertyId in allProperties }) { "同步保存查询引用的属性不存在" }
        }
        val activeDatabases = databases.filterNot { it.header.deleted }.map { it.payload }.associateBy(DatabaseDefinition::id)
        val activeViews = views.filterNot { it.header.deleted }.map { it.payload }
        activeViews.forEach { view ->
            require(view.databaseId in activeDatabases) { "同步视图所属的数据集不存在" }
            require(DatabaseProtocolValidator.validate(view).none { !it.readOnlyCompatibility }) { "同步视图协议结构无效" }
            validateViewReferences(view, allProperties.keys)
        }
        activeDatabases.values.forEach { database ->
            require(database.sourceTypeId in activeTags && database.creationTypeId in activeTags) {
                "同步数据集来源类型或创建类型不存在"
            }
            require(DatabaseProtocolValidator.validate(database, activeViews).none { !it.readOnlyCompatibility }) {
                "同步数据集默认视图或视图目录无效"
            }
        }
    }

    /**
     * 解码一种业务快照并核对 manifest 类别、快照类型和可用的 payload 稳定身份。
     * @param T 该类别的领域 payload 类型。
     * @param objects 完整候选目录。
     * @param kind 需要读取的 manifest 类别。
     * @param objectType 该类别的快照头类型。
     * @param serializer 领域 payload serializer。
     * @param identity payload 内部稳定身份，Note 与 SavedQuery 没有独立字段时为空。
     * @return 保留真实快照头的全部同类对象，包括 tombstone。
     */
    private fun <T> decode(
        objects: Collection<WorkspaceReplicaObject>,
        kind: ManifestObjectKind,
        objectType: ObjectType,
        serializer: KSerializer<T>,
        identity: ((T) -> String)? = null,
    ): List<ObjectSnapshot<T>> = objects.filter { it.kind == kind }.map { candidate ->
        val snapshot = candidate.snapshot
        require(snapshot.header.objectType == objectType) { "同步目录类别与快照头类型不一致" }
        val payload = CanonicalJson.format.decodeFromJsonElement(serializer, snapshot.payload)
        require(identity == null || identity(payload) == snapshot.header.objectId.value) { "同步对象 payload 与快照身份不一致" }
        ObjectSnapshot(snapshot.header, payload)
    }

    /**
     * 校验已知视图语义中的共享属性引用，未知过滤、字段和展示类型继续按只读兼容保留。
     * @param view 当前活动视图。
     * @param propertyIds 完整目录内可识别的稳定属性身份，包含保留的已删除定义。
     */
    private fun validateViewReferences(view: ViewDefinition, propertyIds: Set<PropertyId>) {
        val fields = view.presentation.fields.map { it.field } + view.query.sorts.map { it.field }
        require(fields.all { it.kind != FieldRefKinds.PROPERTY || it.propertyId in propertyIds }) {
            "同步视图展示或排序引用的属性不存在"
        }
        require(listOfNotNull(view.presentation.groupByPropertyId, view.presentation.coverPropertyId).all { it in propertyIds }) {
            "同步视图分组或封面引用的属性不存在"
        }
        validateFilterReferences(view.query.filter, propertyIds)
    }

    /**
     * 遍历当前客户端认识的过滤节点并验证属性引用，不解释未来协议的未知节点内容。
     * @param filter 当前过滤节点。
     * @param propertyIds 完整目录可识别的属性身份。
     */
    private fun validateFilterReferences(filter: FilterExpr, propertyIds: Set<PropertyId>) {
        when (filter.kind) {
            FilterExprKinds.ALL, FilterExprKinds.ANY -> filter.children.forEach { validateFilterReferences(it, propertyIds) }
            FilterExprKinds.NOT -> filter.child?.let { validateFilterReferences(it, propertyIds) }
            FilterExprKinds.PREDICATE -> require(filter.field?.kind != FieldRefKinds.PROPERTY || filter.field?.propertyId in propertyIds) {
                "同步视图过滤引用的属性不存在"
            }
        }
    }
}
