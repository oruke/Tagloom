package com.oruke.tagloom.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.oruke.tagloom.core.model.Block
import com.oruke.tagloom.core.model.BlockId
import com.oruke.tagloom.core.model.BlockTypes
import com.oruke.tagloom.core.model.DatabaseDefinition
import com.oruke.tagloom.core.model.DatabaseId
import com.oruke.tagloom.core.model.DatasetProjection
import com.oruke.tagloom.core.model.FieldRef
import com.oruke.tagloom.core.model.FieldRefKinds
import com.oruke.tagloom.core.model.FilterExpr
import com.oruke.tagloom.core.model.FilterExprKinds
import com.oruke.tagloom.core.model.Note
import com.oruke.tagloom.core.model.NotePropertyValue
import com.oruke.tagloom.core.model.ObjectId
import com.oruke.tagloom.core.model.PropertyDefinition
import com.oruke.tagloom.core.model.PropertyValueSource
import com.oruke.tagloom.core.model.RichText
import com.oruke.tagloom.core.model.RichTextBlockContent
import com.oruke.tagloom.core.model.SystemFieldNames
import com.oruke.tagloom.core.model.TagDefinition
import com.oruke.tagloom.core.model.TagId
import com.oruke.tagloom.core.model.ViewDefinition
import com.oruke.tagloom.core.model.ViewField
import com.oruke.tagloom.core.model.ViewId
import com.oruke.tagloom.core.model.ViewPresentation
import com.oruke.tagloom.core.model.ViewPresentationKinds
import com.oruke.tagloom.core.query.DatabaseProjectionService
import com.oruke.tagloom.core.query.ProjectionNote
import com.oruke.tagloom.editor.EditorDocument
import com.oruke.tagloom.editor.TagloomEditorState
import com.oruke.tagloom.editor.acceptBackgroundCreatedDocument
import com.oruke.tagloom.editor.acceptBackgroundPersistedDocument
import com.oruke.tagloom.editor.bindDatabaseBlock
import com.oruke.tagloom.editor.resolveEffectiveProperties
import com.oruke.tagloom.platform.WorkspaceDatabaseDefinition
import com.oruke.tagloom.platform.WorkspaceGateway
import com.oruke.tagloom.platform.WorkspaceViewDefinition
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlin.coroutines.cancellation.CancellationException

/**
 * 管理 Database/View 目录状态、派生投影和通过 Note 事务完成的单元格写入。
 *
 * @param gateway 当前平台权威写入入口。
 * @param editorState 当前解锁会话的 Note 与 schema 状态。
 */
internal class WorkspaceDatabaseController(
    private val gateway: WorkspaceGateway,
    private val editorState: TagloomEditorState,
) : DatabaseEditorController {
    /** 当前已认证 Database 定义。 */
    val databases = mutableStateListOf<DatabaseDefinition>()

    /** 当前已认证 View 定义。 */
    val views = mutableStateListOf<ViewDefinition>()

    /** 正在通过 Slash 命令创建 Database 的 Block ID。 */
    private val creatingBlockIds = mutableStateListOf<BlockId>()

    /** Database 目录或单元格写入是否正在执行。 */
    override var busy: Boolean by mutableStateOf(false)
        private set

    /** 最近一次不包含用户内容的失败状态。 */
    override var operationFailed: Boolean by mutableStateOf(false)
        private set

    /**
     * 加载工作区已认证目录对象并清除跨会话选择。
     *
     * @param loadedDatabases 已认证 Database 包装。
     * @param loadedViews 已认证 View 包装。
     */
    fun load(
        loadedDatabases: List<WorkspaceDatabaseDefinition>,
        loadedViews: List<WorkspaceViewDefinition>,
    ) {
        databases.clear()
        databases += loadedDatabases.map { saved -> saved.definition }
            .sortedWith(compareBy(DatabaseDefinition::name, { database -> database.id.value }))
        views.clear()
        views += loadedViews.map { saved -> saved.definition }
            .sortedWith(compareBy({ view -> view.databaseId.value }, ViewDefinition::orderKey, { view -> view.id.value }))
        operationFailed = false
    }

    /** 清除锁定会话中的目录明文。 */
    fun clear() {
        databases.clear()
        views.clear()
        creatingBlockIds.clear()
        operationFailed = false
    }

    /**
     * 返回指定 Database 定义。
     *
     * @param databaseId 待查找的稳定 ID。
     * @return 当前目录中的定义；引用尚未加载或已经删除时为空。
     */
    override fun database(databaseId: DatabaseId): DatabaseDefinition? =
        databases.firstOrNull { item -> item.id == databaseId }

    /**
     * 返回指定 Database 下按稳定顺序排列的全部 View。
     *
     * @param databaseId 所属 Database ID。
     * @return 不可变的 View 列表。
     */
    override fun viewsFor(databaseId: DatabaseId): List<ViewDefinition> =
        views.filter { view -> view.databaseId == databaseId }.sortedBy(ViewDefinition::orderKey)

    /**
     * 判断指定 Block 是否仍在等待 Database 加密事务完成。
     *
     * @param blockId Slash 命令所在 Block ID。
     * @return 正在创建时为 true。
     */
    override fun isCreatingBlock(blockId: BlockId): Boolean = blockId in creatingBlockIds

    /**
     * 原子创建 Database 与首个 Table View，并把命令所在文本 Block 绑定为引用 Block。
     *
     * @param documentId 发起命令时所在 Note ID。
     * @param blockId 发起命令的稳定 Block ID。
     * @param name 初始用户可见名称。
     * @param sourceTypeId 初始来源对象类型。
     * @return 创建并绑定成功后的 Database ID；失败或已有写入进行中时为空。
     */
    override suspend fun createDatabaseBlock(
        documentId: ObjectId,
        blockId: BlockId,
        name: String,
        sourceTypeId: TagId,
    ): DatabaseId? {
        creatingBlockIds += blockId
        return try {
            runOperation {
                val databaseId = DatabaseId(gateway.createOpaqueId("database"))
                val viewId = ViewId(gateway.createOpaqueId("view"))
                val fields = listOf(
                    ViewField(FieldRef(FieldRefKinds.SYSTEM, systemField = SystemFieldNames.TITLE), "a000000", width = 220),
                ) + propertyDefinitionsFor(sourceTypeId).mapIndexed { index, definition ->
                    ViewField(
                        FieldRef(FieldRefKinds.PROPERTY, propertyId = definition.id),
                        "a${(index + 1).toString().padStart(6, '0')}",
                    )
                }
                val database = DatabaseDefinition(databaseId, name.trim(), sourceTypeId, true, sourceTypeId, viewId)
                val view = ViewDefinition(
                    id = viewId,
                    databaseId = databaseId,
                    name = "Table",
                    orderKey = "a000000",
                    presentation = ViewPresentation(ViewPresentationKinds.TABLE, fields),
                )
                val saved = gateway.createDatabase(database, view)
                databases += saved.database.definition
                views += saved.initialView.definition
                check(editorState.bindDatabaseBlock(documentId, blockId, databaseId)) {
                    "Database 已创建，但原始命令 Block 已不存在"
                }
                databaseId
            }
        } finally {
            creatingBlockIds -= blockId
        }
    }

    /**
     * 复制指定视图配置并创建一个新的独立 View。
     *
     * @param databaseId 所属 Database ID。
     * @param currentViewId 作为模板的当前 View ID。
     * @param name 新视图用户可见名称。
     * @return 新确认的 View ID；失败时为空。
     */
    override suspend fun createView(
        databaseId: DatabaseId,
        currentViewId: ViewId,
        name: String,
    ): ViewId? = runOperation {
        val database = requireNotNull(database(databaseId)) { "Database 不存在" }
        val current = views.single { view -> view.id == currentViewId && view.databaseId == database.id }
        val ownViews = viewsFor(database.id)
        val definition = current.copy(
            id = ViewId(gateway.createOpaqueId("view")),
            name = name.trim(),
            orderKey = ownViews.maxOfOrNull(ViewDefinition::orderKey)?.plus("a") ?: "a000000",
        )
        val saved = gateway.createViewDefinition(definition).definition
        views += saved
        saved.id
    }

    /**
     * 以指定 Database 的 creationTypeId 创建一个空 Note 行并立即加入投影来源。
     *
     * @param databaseId 目标 Database ID。
     */
    override suspend fun createRow(databaseId: DatabaseId) = runOperation {
        val database = requireNotNull(database(databaseId)) { "Database 不存在" }
        val note = Note(
            title = "",
            directTagIds = listOf(database.creationTypeId),
            blocks = listOf(
                Block(
                    id = BlockId(gateway.createOpaqueId("block")),
                    type = BlockTypes.PARAGRAPH,
                    orderKey = "a000000",
                    content = RichTextBlockContent(RichText()),
                ),
            ),
        )
        val saved = gateway.saveNote(objectId = null, note = note, expectedRevision = null)
        editorState.acceptBackgroundCreatedDocument(
            EditorDocument(
                id = saved.id,
                note = saved.note,
                revision = saved.revision,
                createdAt = saved.createdAt,
                updatedAt = saved.updatedAt,
                persisted = true,
                dirty = false,
            ),
        )
    }

    /**
     * 更新指定 Database 定义。
     *
     * @param databaseId 目标 Database ID。
     * @param transform 纯定义转换。
     */
    override suspend fun updateDatabase(
        databaseId: DatabaseId,
        transform: (DatabaseDefinition) -> DatabaseDefinition,
    ) = runOperation {
        val current = requireNotNull(database(databaseId)) { "Database 不存在" }
        val saved = gateway.saveDatabaseDefinition(transform(current)).definition
        databases.replaceById(saved)
    }

    /**
     * 更新指定 View 定义。
     *
     * @param viewId 目标 View ID。
     * @param transform 纯视图转换。
     */
    override suspend fun updateView(
        viewId: ViewId,
        transform: (ViewDefinition) -> ViewDefinition,
    ) = runOperation {
        val current = views.single { view -> view.id == viewId }
        val saved = gateway.saveViewDefinition(transform(current)).definition
        views.replaceById(saved)
    }

    /**
     * 设置指定 View 的全文过滤文本。
     *
     * @param viewId 目标 View ID。
     * @param text 用户查询文本；空文本恢复恒真过滤。
     */
    override suspend fun setFullTextFilter(
        viewId: ViewId,
        text: String,
    ) = updateView(viewId) { view ->
        view.copy(
            query = view.query.copy(
                filter = if (text.isBlank()) FilterExpr() else FilterExpr(FilterExprKinds.FULL_TEXT, text = text.trim()),
            ),
        )
    }

    /**
     * 计算指定 Database/View 的可删除派生投影。
     *
     * @param databaseId Database ID。
     * @param viewId View ID。
     * @return 当前目录完整且归属匹配时的投影；否则为空。
     */
    override fun projection(
        databaseId: DatabaseId,
        viewId: ViewId,
    ): DatasetProjection? {
        val database = database(databaseId) ?: return null
        val view = views.firstOrNull { item -> item.id == viewId && item.databaseId == databaseId } ?: return null
        val rootId = com.oruke.tagloom.core.workspace.WorkspaceDefaults.DEFAULT_NOTE_TAG_ID
        return DatabaseProjectionService(rootId).project(
            database,
            view,
            editorState.documents.filter(EditorDocument::persisted).map { document ->
                ProjectionNote(document.id, document.note, document.revision, document.createdAt, document.updatedAt)
            },
            editorState.tagDefinitions,
            editorState.propertyDefinitions,
        )
    }

    /**
     * 返回指定 Database 来源类型的有效属性定义目录。
     *
     * @param databaseId Database ID。
     * @return 按编辑器加载顺序排列的不可变副本。
     */
    override fun propertyDefinitions(databaseId: DatabaseId): List<PropertyDefinition> = database(databaseId)
        ?.let { database -> propertyDefinitionsFor(database.sourceTypeId) }
        .orEmpty()

    /**
     * 返回当前解锁工作区的对象类型目录。
     *
     * @return 按编辑器加载顺序排列的不可变副本。
     */
    override fun tagDefinitions(): List<TagDefinition> = editorState.tagDefinitions.toList()

    /**
     * 通过 Note expectedRevision 写入一个 Database 投影单元格。
     *
     * @param noteId 目标 Note ID。
     * @param field 稳定字段引用。
     * @param value 新 JSON 值；为空表示清除属性。
     */
    override suspend fun updateCell(
        noteId: ObjectId,
        field: FieldRef,
        value: JsonElement?,
    ) = runOperation {
        val document = editorState.documents.single { item -> item.id == noteId }
        val updated = when (field.kind) {
            FieldRefKinds.SYSTEM -> {
                require(field.systemField == SystemFieldNames.TITLE) { "当前系统字段不可写" }
                document.note.copy(title = (value as? JsonPrimitive)?.content.orEmpty())
            }
            FieldRefKinds.PROPERTY -> {
                val propertyId = requireNotNull(field.propertyId) { "属性字段缺少 ID" }
                val values = if (value == null) {
                    document.note.propertyValues - propertyId
                } else {
                    document.note.propertyValues + (
                        propertyId to NotePropertyValue(
                            value = value,
                            source = PropertyValueSource.USER,
                            overridden = true,
                            updatedAt = gateway.currentUtcTimestamp(),
                        )
                    )
                }
                document.note.copy(propertyValues = values)
            }
            else -> throw IllegalArgumentException("对象类型列不可直接编辑")
        }
        val saved = gateway.saveNote(document.id, updated, document.revision)
        editorState.acceptBackgroundPersistedDocument(
            document.id,
            document.copy(
                id = saved.id,
                note = saved.note,
                revision = saved.revision,
                createdAt = saved.createdAt,
                updatedAt = saved.updatedAt,
                dirty = false,
            ),
        )
    }

    /**
     * 统一 busy 与失败状态。
     *
     * @param block 单次目录或 Note 写入。
     */
    private suspend fun <T> runOperation(block: suspend () -> T): T? {
        if (busy) {
            operationFailed = true
            return null
        }
        busy = true
        operationFailed = false
        return try {
            block()
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            operationFailed = true
            null
        } finally {
            busy = false
        }
    }

    /**
     * 按对象类型继承关系计算可用于 Database 列配置的有效属性定义。
     *
     * @param sourceTypeId Database 来源对象类型。
     * @return 只包含该类型有效属性的稳定定义列表。
     */
    private fun propertyDefinitionsFor(sourceTypeId: TagId): List<PropertyDefinition> {
        val probe = Note(title = "", directTagIds = listOf(sourceTypeId))
        val effectiveIds = editorState.resolveEffectiveProperties(probe)
            ?.properties
            ?.map { property -> property.propertyId }
            ?.toSet()
            .orEmpty()
        return editorState.propertyDefinitions.filter { definition -> definition.id in effectiveIds }
    }

    /**
     * 按稳定 ID 替换 Database。
     *
     * @param database 新确认定义。
     */
    private fun MutableList<DatabaseDefinition>.replaceById(database: DatabaseDefinition) {
        val index = indexOfFirst { item -> item.id == database.id }
        require(index >= 0) { "Database 不存在" }
        this[index] = database
    }

    /**
     * 按稳定 ID 替换 View。
     *
     * @param view 新确认定义。
     */
    private fun MutableList<ViewDefinition>.replaceById(view: ViewDefinition) {
        val index = indexOfFirst { item -> item.id == view.id }
        require(index >= 0) { "View 不存在" }
        this[index] = view
    }
}
