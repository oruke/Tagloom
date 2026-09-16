package com.oruke.tagloom.editor

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.oruke.tagloom.core.markdown.MarkdownCodec
import com.oruke.tagloom.core.markdown.MarkdownExportMode
import com.oruke.tagloom.core.markdown.MarkdownExportResult
import com.oruke.tagloom.core.markdown.MarkdownImportOptions
import com.oruke.tagloom.core.model.Block
import com.oruke.tagloom.core.model.BlockId
import com.oruke.tagloom.core.model.BlockTypes
import com.oruke.tagloom.core.model.CodeBlockContent
import com.oruke.tagloom.core.model.Note
import com.oruke.tagloom.core.model.ObjectId
import com.oruke.tagloom.core.model.PropertyDefinition
import com.oruke.tagloom.core.model.RichTextEditor
import com.oruke.tagloom.core.model.TagDefinition
import com.oruke.tagloom.core.model.TodoBlockContent
import com.oruke.tagloom.core.workspace.WorkspaceDefaults

/**
 * 管理 Compose 编辑器文档、选区、块命令和 Markdown 导入导出状态。
 *
 * @param idFactory 为新文档、Block 和 Annotation 生成稳定不透明 ID。
 * @param timestampProvider 为 Annotation 提供 UTC ISO-8601 时间。
 */
class TagloomEditorState(
    /** 生成不同业务前缀 ID 的注入函数。 */
    private val idFactory: (String) -> String,
    /** 生成当前 UTC 时间文本的注入函数。 */
    private val timestampProvider: () -> String,
) {
    /** 当前界面注入的新内容命名规则；更新此配置不改变现有文档或脏状态。 */
    var contentNames: EditorContentNames = EditorContentNames()
        internal set

    /** 当前内存工作区中的文档集合。 */
    val documents = mutableStateListOf<EditorDocument>()

    /** 当前已认证工作区标签定义；锁定时必须清空。 */
    val tagDefinitions = mutableStateListOf<TagDefinition>()

    /** 当前已认证工作区属性定义；锁定时必须清空。 */
    val propertyDefinitions = mutableStateListOf<PropertyDefinition>()

    /** 当前选中文档 ID。 */
    var selectedDocumentId: ObjectId? by mutableStateOf(null)
        private set

    /** 当前活动 Block 选区。 */
    var selection: EditorSelection by mutableStateOf(EditorSelection())
        private set

    /** 左侧笔记列表使用的即时过滤文本。 */
    var filterText: String by mutableStateOf("")

    /** 选区工具栏中待创建的加注正文。 */
    var annotationDraft: String by mutableStateOf("")

    /** 当前选区待应用的链接地址。 */
    var linkDraft: String by mutableStateOf("")

    /** 最近一次 Markdown 导入的非阻断警告。 */
    var importWarnings: List<String> by mutableStateOf(emptyList())
        private set

    /** 当前解锁 UI 会话中每个 Tabs 容器选择的活动 tab；不会进入 Note 快照。 */
    internal val activeTabIds = mutableStateMapOf<BlockId, BlockId>()

    /** 最近一次结构化 Block 编辑失败的稳定错误码，由 UI 按当前语言显示。 */
    var structuredEditError: String? by mutableStateOf(null)
        internal set

    /** 基础属性值编辑入口；最终仍通过当前 Note 快照保存。 */
    val propertyEditor: EditorPropertyController = EditorPropertyController(timestampProvider, ::updateSelectedNote)

    /** 当前选中文档；未选中时为 null。 */
    val selectedDocument: EditorDocument?
        get() = documents.firstOrNull { document -> document.id == selectedDocumentId }

    /** 应用过滤后的文档列表。 */
    val visibleDocuments: List<EditorDocument>
        get() = if (filterText.isBlank()) {
            documents
        } else {
            documents.filter { document -> document.note.title.contains(filterText, ignoreCase = true) }
        }

    /**
     * 选择一篇既有文档、清空跨文档选区，并补齐可确定的标签 snapshot 默认值。
     *
     * @param documentId 待打开文档 ID。
     */
    fun selectDocument(documentId: ObjectId) {
        require(documents.any { document -> document.id == documentId }) { "编辑器文档不存在" }
        selectedDocumentId = documentId
        selection = EditorSelection()
        structuredEditError = null
        materializeSelectedSnapshotDefaults()
    }

    /**
     * 创建含一个空段落的新笔记并立即选中。
     */
    fun createDocument() {
        val documentId = ObjectId(idFactory("note"))
        val block = EditorBlockFactory.create(BlockTypes.PARAGRAPH, 0, idFactory)
        val createdAt = timestampProvider()
        documents += EditorDocument(
            id = documentId,
            note = Note(
                title = contentNames.untitledNote,
                directTagIds = listOf(WorkspaceDefaults.DEFAULT_NOTE_TAG_ID),
                blocks = listOf(block),
            ),
            createdAt = createdAt,
            updatedAt = createdAt,
            persisted = false,
            dirty = true,
        )
        selectDocument(documentId)
    }

    /**
     * 更新当前笔记标题。
     *
     * @param title 新标题文本。
     */
    fun updateTitle(title: String) {
        updateSelectedNote { note -> note.copy(title = title) }
    }

    /**
     * 根据文本框新纯文本更新 Block，同时保留差异外 RichText 样式。
     *
     * @param blockId 待更新 Block。
     * @param newText 文本框当前完整纯文本。
     */
    fun updateBlockText(
        blockId: BlockId,
        newText: String,
    ) {
        updateBlock(blockId) { block ->
            val current = block.editableRichText() ?: return@updateBlock block
            block.withEditableRichText(RichTextEditor.replacePlainTextPreservingStyles(current, newText))
        }
    }

    /**
     * 更新代码 Block 的原始多行文本。
     *
     * @param blockId 代码 Block ID。
     * @param newText 新代码正文。
     */
    fun updateCodeBlockText(
        blockId: BlockId,
        newText: String,
    ) {
        updateBlock(blockId) { block ->
            val content = block.content as? CodeBlockContent ?: return@updateBlock block
            block.copy(content = content.copy(text = newText))
        }
    }

    /**
     * 切换 Todo Block 完成状态。
     *
     * @param blockId Todo Block ID。
     */
    fun toggleTodo(blockId: BlockId) {
        updateBlock(blockId) { block ->
            val content = block.content as? TodoBlockContent ?: return@updateBlock block
            block.copy(content = content.copy(checked = !content.checked))
        }
    }

    /**
     * 在当前笔记末尾新增指定 MVP Block。
     *
     * @param type paragraph、heading、todo 或 code。
     */
    fun appendBlock(type: String) {
        updateSelectedNote { note ->
            val block = when (type) {
                BlockTypes.CODE -> Block(
                    id = BlockId(idFactory("block")),
                    type = BlockTypes.CODE,
                    orderKey = EditorBlockFactory.orderKey(note.blocks.size),
                    content = CodeBlockContent(language = null, text = ""),
                )
                else -> EditorBlockFactory.create(type, note.blocks.size, idFactory)
            }
            note.copy(blocks = note.blocks + block)
        }
    }

    /**
     * 删除当前笔记中的一个 Block，并清除其活动选区。
     *
     * @param blockId 待删除 Block ID。
     */
    fun deleteBlock(blockId: BlockId) {
        updateSelectedNote { note ->
            val tombstoned = BlockTreeReducer.tombstone(note.blocks, blockId)
            if (BlockTreeReducer.orderedActive(tombstoned).isNotEmpty()) {
                note.copy(blocks = tombstoned)
            } else {
                // 末尾“继续写作”占位区已经移除；保留一个真实空段落，避免删除最后一块后失去编辑入口。
                note.copy(
                    blocks = tombstoned + EditorBlockFactory.create(
                        type = BlockTypes.PARAGRAPH,
                        index = tombstoned.size,
                        idFactory = idFactory,
                    ),
                )
            }
        }
        if (selection.blockId == blockId) {
            selection = EditorSelection()
        }
    }

    /**
     * 在同一父节点内向上或向下移动 Block，同时保持稳定 ID。
     *
     * @param blockId 目标 Block。
     * @param direction 移动方向。
     */
    fun moveBlock(
        blockId: BlockId,
        direction: BlockMoveDirection,
    ) {
        updateSelectedNote { note -> note.copy(blocks = BlockTreeReducer.move(note.blocks, blockId, direction)) }
    }

    /**
     * 把 Block 缩进为前一个同级 Block 的子节点。
     *
     * @param blockId 目标 Block。
     */
    fun indentBlock(blockId: BlockId) {
        updateSelectedNote { note -> note.copy(blocks = BlockTreeReducer.indent(note.blocks, blockId)) }
    }

    /**
     * 把 Block 提升为其父节点之后的同级节点。
     *
     * @param blockId 目标 Block。
     */
    fun outdentBlock(blockId: BlockId) {
        updateSelectedNote { note -> note.copy(blocks = BlockTreeReducer.outdent(note.blocks, blockId)) }
    }

    /**
     * 返回编辑器应渲染的活动 Block 树形先序列表。
     *
     * @param note 当前 Note。
     * @return 不包含 tombstone 的稳定列表。
     */
    fun orderedActiveBlocks(note: Note): List<Block> = BlockTreeReducer.orderedActive(note.blocks)

    /**
     * 返回 Block 在当前 Note 树中的安全展示深度。
     *
     * @param block 目标 Block。
     * @param note 当前 Note。
     * @return 从零开始的深度。
     */
    fun blockDepth(
        block: Block,
        note: Note,
    ): Int = BlockTreeReducer.depth(block, note.blocks)

    /**
     * 保存当前 Block 字符选区，自动规范正反向拖选。
     *
     * @param blockId 活动 Block ID。
     * @param start 第一个偏移。
     * @param end 第二个偏移。
     */
    fun updateSelection(
        blockId: BlockId,
        start: Int,
        end: Int,
    ) {
        selection = EditorSelection(blockId, minOf(start, end), maxOf(start, end))
    }

    /**
     * 清空当前文本选区。
     *
     * 结构化 Block 在切换内部视图时调用该入口，避免把上一页签的光标状态错误应用到新页签内容。
     */
    internal fun clearSelection() {
        selection = EditorSelection()
    }

    /**
     * 导出当前笔记并保留能力差异清单。
     *
     * @param mode 标准或 Tagloom 扩展 Markdown。
     * @return 当前文档的导出结果；未选中文档时为 null。
     */
    fun exportSelected(mode: MarkdownExportMode): MarkdownExportResult? =
        selectedDocument?.note?.let { note -> MarkdownCodec.export(note, mode) }

    /**
     * 导入 Markdown 为新文档并立即选中。
     *
     * @param markdown 待导入文本。
     */
    fun importMarkdown(markdown: String) {
        var blockCounter = 0
        val importedAt = timestampProvider()
        val result = MarkdownCodec.import(
            markdown = markdown,
            options = MarkdownImportOptions(
                defaultTitle = contentNames.importedNote,
                importedAt = importedAt,
                blockIdFactory = {
                    blockCounter += 1
                    BlockId(idFactory("block"))
                },
                tableIdFactory = { idFactory("table-item") },
            ),
        )
        val documentId = ObjectId(idFactory("note"))
        documents += EditorDocument(
            id = documentId,
            note = result.note.copy(directTagIds = listOf(WorkspaceDefaults.DEFAULT_NOTE_TAG_ID)),
            createdAt = importedAt,
            updatedAt = importedAt,
            persisted = false,
            dirty = true,
        )
        importWarnings = result.warnings
        selectDocument(documentId)
    }

    /**
     * 用已解锁工作区的已确认 Note 替换当前内存文档。
     *
     * 首篇笔记若存在可确定但尚未落盘的标签 snapshot 默认值，会立即标记为待保存，
     * 再交给统一自动保存链路确认，避免 UI 长期展示 `SNAPSHOT_PENDING`。
     *
     * @param loadedDocuments 已由 manifest 和 AEAD 认证的文档。
     */
    fun loadWorkspaceDocuments(
        loadedDocuments: List<EditorDocument>,
        loadedTagDefinitions: List<TagDefinition> = emptyList(),
        loadedPropertyDefinitions: List<PropertyDefinition> = emptyList(),
    ) {
        require(loadedDocuments.all { document -> document.persisted && !document.dirty }) {
            "工作区加载结果必须全部是未修改的已确认文档"
        }
        require(loadedDocuments.map(EditorDocument::id).distinct().size == loadedDocuments.size) {
            "工作区加载结果包含重复 Note ID"
        }
        documents.clear()
        documents += loadedDocuments
        tagDefinitions.clear()
        tagDefinitions += loadedTagDefinitions.sortedWith(compareBy(TagDefinition::name, { definition -> definition.id.value }))
        propertyDefinitions.clear()
        propertyDefinitions += loadedPropertyDefinitions.sortedWith(
            compareBy(PropertyDefinition::name, { definition -> definition.id.value }),
        )
        selectedDocumentId = loadedDocuments.firstOrNull()?.id
        selection = EditorSelection()
        importWarnings = emptyList()
        activeTabIds.clear()
        structuredEditError = null
        materializeSelectedSnapshotDefaults()
    }

    /**
     * 用事务确认结果替换草稿或旧版本，并保持当前选择指向最终稳定 ID。
     *
     * @param previousId 保存前草稿或已持久化 ID。
     * @param persistedDocument 新确认文档。
     */
    fun acceptPersistedDocument(
        previousId: ObjectId,
        persistedDocument: EditorDocument,
    ) {
        require(persistedDocument.persisted) { "保存结果必须是已确认文档" }
        val index = documents.indexOfFirst { document -> document.id == previousId }
        require(index >= 0) { "待确认编辑器文档不存在" }
        documents[index] = persistedDocument
        selectedDocumentId = persistedDocument.id
        if (selection.blockId?.let { blockId -> persistedDocument.note.blocks.any { block -> block.id == blockId } } != true) {
            selection = EditorSelection()
        }
    }

    /**
     * 在锁定工作区时清除全部明文 UI 状态。
     */
    fun clearWorkspace() {
        documents.clear()
        tagDefinitions.clear()
        propertyDefinitions.clear()
        selectedDocumentId = null
        selection = EditorSelection()
        annotationDraft = ""
        linkDraft = ""
        importWarnings = emptyList()
        filterText = ""
        activeTabIds.clear()
        structuredEditError = null
    }

    /**
     * 从编辑器移除已经 tombstone 或被放弃的文档，并选择下一个可用文档。
     *
     * @param documentId 待移除文档 ID。
     */
    fun removeDocument(documentId: ObjectId) {
        val index = documents.indexOfFirst { document -> document.id == documentId }
        if (index < 0) {
            return
        }
        documents.removeAt(index)
        if (selectedDocumentId == documentId) {
            selectedDocumentId = documents.getOrNull(index.coerceAtMost(documents.lastIndex))?.id
            selection = EditorSelection()
        }
    }

    /**
     * 替换当前选中文档的 Note payload。
     *
     * @param transform Note 转换函数。
     */
    internal fun updateSelectedNote(transform: (Note) -> Note) {
        val documentId = selectedDocumentId ?: return
        val index = documents.indexOfFirst { document -> document.id == documentId }
        if (index >= 0) {
            val current = documents[index]
            val updated = transform(current.note)
            if (updated != current.note) {
                documents[index] = current.copy(
                    note = updated,
                    updatedAt = timestampProvider(),
                    dirty = true,
                    draftVersion = current.draftVersion + 1L,
                )
            }
        }
    }

    /**
     * 为拆分、插入和类型转换生成编辑器业务 ID。
     *
     * @param prefix ID 业务前缀。
     * @return 平台注入工厂生成的不透明 ID。
     */
    internal fun createEditorId(prefix: String): String = idFactory(prefix)

    /**
     * 为加注等编辑器对象提供当前 UTC 时间。
     *
     * @return 平台注入的 UTC 时间文本。
     */
    internal fun currentEditorTimestamp(): String = timestampProvider()

    /**
     * 更新当前笔记中的指定 Block。
     *
     * @param blockId Block ID。
     * @param transform Block 转换函数。
     */
    internal fun updateBlock(
        blockId: BlockId,
        transform: (Block) -> Block,
    ) {
        updateSelectedNote { note ->
            note.copy(blocks = note.blocks.map { block -> if (block.id == blockId) transform(block) else block })
        }
    }

    companion object {
        /**
         * 构造可直接体验单词 code、Todo 和 Annotation 的内存示例工作区。
         *
         * @return 已选中示例笔记的编辑器状态。
         */
        fun demo(): TagloomEditorState = createDemoEditorState()
    }
}
