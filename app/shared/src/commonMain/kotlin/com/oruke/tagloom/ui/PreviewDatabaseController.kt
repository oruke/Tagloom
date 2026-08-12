package com.oruke.tagloom.ui

import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
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
import com.oruke.tagloom.core.query.DatabaseProjectionService
import com.oruke.tagloom.core.query.ProjectionNote
import com.oruke.tagloom.core.workspace.WorkspaceDefaults
import com.oruke.tagloom.editor.EditorDocument
import com.oruke.tagloom.editor.TagloomEditorState
import com.oruke.tagloom.editor.bindDatabaseBlock
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive

/**
 * 为桌面设计预览提供可交互的数据集会话。
 *
 * 该实现绝不接触工作区文件或密钥，只用于验证 Slash 命令、视图和单元格交互；正式运行仍使用
 * [WorkspaceDatabaseController] 经过统一事务入口写入加密快照。
 *
 * @param editorState 当前内存预览编辑器状态。
 */
internal class PreviewDatabaseController(
    private val editorState: TagloomEditorState,
) : DatabaseEditorController {
    /** 预览会话中的数据集定义。 */
    private val databases = mutableStateListOf<DatabaseDefinition>()

    /** 预览会话中的视图定义。 */
    private val views = mutableStateListOf<ViewDefinition>()

    /** 当前是否正在执行内存操作。 */
    override var busy: Boolean by mutableStateOf(false)
        private set

    /** 最近一次内存操作是否失败。 */
    override var operationFailed: Boolean by mutableStateOf(false)
        private set

    /**
     * 返回预览数据集定义。
     *
     * @param databaseId 数据集稳定 ID。
     * @return 匹配定义；不存在时为 null。
     */
    override fun database(databaseId: DatabaseId): DatabaseDefinition? =
        databases.firstOrNull { database -> database.id == databaseId }

    /**
     * 返回数据集的预览视图。
     *
     * @param databaseId 数据集稳定 ID。
     * @return 按 orderKey 排序的视图。
     */
    override fun viewsFor(databaseId: DatabaseId): List<ViewDefinition> =
        views.filter { view -> view.databaseId == databaseId }.sortedBy(ViewDefinition::orderKey)

    /**
     * 预览操作同步完成，因此不存在持续中的命令 Block。
     *
     * @param blockId 命令 Block ID。
     * @return 始终为 false。
     */
    override fun isCreatingBlock(blockId: BlockId): Boolean = false

    /**
     * 在内存中创建数据集、首个表格视图并绑定命令 Block。
     *
     * @param documentId 命令所在 Note ID。
     * @param blockId 命令所在 Block ID。
     * @param name 初始名称。
     * @param sourceTypeId 来源对象类型。
     * @return 成功绑定后的数据集 ID。
     */
    override suspend fun createDatabaseBlock(
        documentId: ObjectId,
        blockId: BlockId,
        name: String,
        sourceTypeId: TagId,
    ): DatabaseId? = runOperation {
        val databaseId = DatabaseId(editorState.createEditorId("preview-database"))
        val viewId = ViewId(editorState.createEditorId("preview-view"))
        val database = DatabaseDefinition(
            id = databaseId,
            name = name.ifBlank { "未命名数据集" },
            sourceTypeId = sourceTypeId,
            includeDescendants = true,
            creationTypeId = sourceTypeId,
            defaultViewId = viewId,
        )
        val view = ViewDefinition(
            id = viewId,
            databaseId = databaseId,
            name = "表格",
            orderKey = "a000000",
            presentation = ViewPresentation(fields = defaultPreviewFields()),
        )
        databases += database
        views += view
        check(editorState.bindDatabaseBlock(documentId, blockId, databaseId)) {
            "预览数据集创建完成，但命令 Block 已不存在"
        }
        databaseId
    }

    /**
     * 在内存中复制当前视图。
     *
     * @param databaseId 所属数据集 ID。
     * @param currentViewId 模板视图 ID。
     * @param name 新视图名称。
     * @return 新视图 ID。
     */
    override suspend fun createView(
        databaseId: DatabaseId,
        currentViewId: ViewId,
        name: String,
    ): ViewId? = runOperation {
        val current = views.single { view -> view.id == currentViewId && view.databaseId == databaseId }
        val viewId = ViewId(editorState.createEditorId("preview-view"))
        views += current.copy(
            id = viewId,
            name = name.ifBlank { "未命名视图" },
            orderKey = "a${viewsFor(databaseId).size.toString().padStart(6, '0')}",
        )
        viewId
    }

    /**
     * 在内存目录中创建一篇可立即参与投影的空 Note。
     *
     * @param databaseId 目标数据集 ID。
     * @return 操作成功时为 Unit。
     */
    override suspend fun createRow(databaseId: DatabaseId): Unit? = runOperation {
        val database = requireNotNull(database(databaseId)) { "预览数据集不存在" }
        val noteId = ObjectId(editorState.createEditorId("preview-note"))
        val blockId = BlockId(editorState.createEditorId("preview-block"))
        editorState.documents += EditorDocument(
            id = noteId,
            note = Note(
                title = "未命名笔记",
                directTagIds = listOf(database.creationTypeId),
                blocks = listOf(
                    Block(
                        id = blockId,
                        type = BlockTypes.PARAGRAPH,
                        orderKey = "a000000",
                        content = RichTextBlockContent(RichText()),
                    ),
                ),
            ),
            createdAt = PREVIEW_TIMESTAMP,
            updatedAt = PREVIEW_TIMESTAMP,
            persisted = true,
            dirty = false,
        )
    }

    /**
     * 在内存中更新数据集定义。
     *
     * @param databaseId 目标数据集 ID。
     * @param transform 定义转换。
     * @return 操作成功时为 Unit。
     */
    override suspend fun updateDatabase(
        databaseId: DatabaseId,
        transform: (DatabaseDefinition) -> DatabaseDefinition,
    ): Unit? = runOperation {
        val index = databases.indexOfFirst { database -> database.id == databaseId }
        require(index >= 0) { "预览数据集不存在" }
        databases[index] = transform(databases[index])
    }

    /**
     * 在内存中更新视图定义。
     *
     * @param viewId 目标视图 ID。
     * @param transform 视图转换。
     * @return 操作成功时为 Unit。
     */
    override suspend fun updateView(
        viewId: ViewId,
        transform: (ViewDefinition) -> ViewDefinition,
    ): Unit? = runOperation {
        val index = views.indexOfFirst { view -> view.id == viewId }
        require(index >= 0) { "预览视图不存在" }
        views[index] = transform(views[index])
    }

    /**
     * 在预览视图中设置全文过滤。
     *
     * @param viewId 目标视图 ID。
     * @param text 过滤文本；空白表示清除。
     * @return 操作成功时为 Unit。
     */
    override suspend fun setFullTextFilter(
        viewId: ViewId,
        text: String,
    ): Unit? = updateView(viewId) { view ->
        view.copy(
            query = view.query.copy(
                filter = if (text.isBlank()) FilterExpr() else FilterExpr(FilterExprKinds.FULL_TEXT, text = text.trim()),
            ),
        )
    }

    /**
     * 从预览 Note 和 schema 实时计算派生投影。
     *
     * @param databaseId 数据集 ID。
     * @param viewId 视图 ID。
     * @return 当前投影；引用不完整时为 null。
     */
    override fun projection(
        databaseId: DatabaseId,
        viewId: ViewId,
    ): DatasetProjection? {
        val database = database(databaseId) ?: return null
        val view = views.firstOrNull { item -> item.id == viewId && item.databaseId == databaseId } ?: return null
        return DatabaseProjectionService(WorkspaceDefaults.DEFAULT_NOTE_TAG_ID).project(
            database = database,
            view = view,
            notes = editorState.documents.map { document ->
                ProjectionNote(document.id, document.note, document.revision, document.createdAt, document.updatedAt)
            },
            tags = editorState.tagDefinitions,
            properties = editorState.propertyDefinitions,
        )
    }

    /**
     * 返回预览工作区可选属性。
     *
     * @param databaseId 数据集 ID；用于确认引用仍有效。
     * @return 当前属性目录副本。
     */
    override fun propertyDefinitions(databaseId: DatabaseId): List<PropertyDefinition> =
        if (database(databaseId) == null) emptyList() else editorState.propertyDefinitions.toList()

    /**
     * 返回预览工作区对象类型。
     *
     * @return 当前标签目录副本。
     */
    override fun tagDefinitions(): List<TagDefinition> = editorState.tagDefinitions.toList()

    /**
     * 在预览文档中更新标题或属性值。
     *
     * @param noteId 目标 Note ID。
     * @param field 稳定字段引用。
     * @param value 新值；null 表示删除属性。
     * @return 操作成功时为 Unit。
     */
    override suspend fun updateCell(
        noteId: ObjectId,
        field: FieldRef,
        value: JsonElement?,
    ): Unit? = runOperation {
        val index = editorState.documents.indexOfFirst { document -> document.id == noteId }
        require(index >= 0) { "预览 Note 不存在" }
        val document = editorState.documents[index]
        val note = when (field.kind) {
            FieldRefKinds.SYSTEM -> {
                require(field.systemField == SystemFieldNames.TITLE) { "当前系统字段不可写" }
                document.note.copy(title = (value as? JsonPrimitive)?.content.orEmpty())
            }
            FieldRefKinds.PROPERTY -> {
                val propertyId = requireNotNull(field.propertyId) { "属性字段缺少 ID" }
                val propertyValues = if (value == null) {
                    document.note.propertyValues - propertyId
                } else {
                    document.note.propertyValues + (
                        propertyId to NotePropertyValue(
                            value = value,
                            source = PropertyValueSource.USER,
                            overridden = true,
                            updatedAt = PREVIEW_TIMESTAMP,
                        )
                    )
                }
                document.note.copy(propertyValues = propertyValues)
            }
            else -> throw IllegalArgumentException("对象类型列不可直接编辑")
        }
        editorState.documents[index] = document.copy(
            note = note,
            updatedAt = PREVIEW_TIMESTAMP,
            dirty = true,
            draftVersion = document.draftVersion + 1L,
        )
    }

    /**
     * 统一管理预览操作状态并把异常收敛为安全失败状态。
     *
     * @param block 单次内存操作。
     * @return 操作结果；失败时为 null。
     */
    private inline fun <T> runOperation(block: () -> T): T? {
        if (busy) {
            operationFailed = true
            return null
        }
        busy = true
        operationFailed = false
        return try {
            block()
        } catch (_: Throwable) {
            operationFailed = true
            null
        } finally {
            busy = false
        }
    }

    /**
     * 构造与 Stitch 数据集表格一致的默认列顺序和宽度。
     *
     * @return 标题、日期、状态和描述四列。
     */
    private fun defaultPreviewFields(): List<ViewField> = listOf(
        ViewField(
            field = FieldRef(FieldRefKinds.SYSTEM, systemField = SystemFieldNames.TITLE),
            orderKey = "a000000",
            width = 300,
        ),
        ViewField(
            field = FieldRef(FieldRefKinds.PROPERTY, propertyId = WorkspaceDefaults.DEFAULT_DATE_PROPERTY_ID),
            orderKey = "a000001",
            width = 120,
        ),
        ViewField(
            field = FieldRef(FieldRefKinds.PROPERTY, propertyId = WorkspaceDefaults.DEFAULT_SELECT_PROPERTY_ID),
            orderKey = "a000002",
            width = 130,
        ),
        ViewField(
            field = FieldRef(FieldRefKinds.PROPERTY, propertyId = WorkspaceDefaults.DEFAULT_TEXT_PROPERTY_ID),
            orderKey = "a000003",
            width = 280,
        ),
    )

    companion object {
        /** 预览写入使用的固定非敏感时间。 */
        private const val PREVIEW_TIMESTAMP: String = "2026-07-14T10:30:00Z"
    }
}
