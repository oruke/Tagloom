package com.oruke.tagloom.editor

import com.oruke.tagloom.core.model.Annotation
import com.oruke.tagloom.core.model.AnnotationId
import com.oruke.tagloom.core.model.AnnotationKinds
import com.oruke.tagloom.core.model.Block
import com.oruke.tagloom.core.model.BlockId
import com.oruke.tagloom.core.model.BlockTypes
import com.oruke.tagloom.core.model.DefaultValuePolicy
import com.oruke.tagloom.core.model.HeadingBlockContent
import com.oruke.tagloom.core.model.InlineMark
import com.oruke.tagloom.core.model.InlineMarkTypes
import com.oruke.tagloom.core.model.InlineRun
import com.oruke.tagloom.core.model.Note
import com.oruke.tagloom.core.model.NotePropertyValue
import com.oruke.tagloom.core.model.ObjectId
import com.oruke.tagloom.core.model.PropertyOption
import com.oruke.tagloom.core.model.PropertyDefinition
import com.oruke.tagloom.core.model.PropertyId
import com.oruke.tagloom.core.model.PropertyTypes
import com.oruke.tagloom.core.model.PropertyValueSource
import com.oruke.tagloom.core.model.RichText
import com.oruke.tagloom.core.model.RichTextBlockContent
import com.oruke.tagloom.core.model.TodoBlockContent
import com.oruke.tagloom.core.model.TagDefinition
import com.oruke.tagloom.core.model.TagId
import com.oruke.tagloom.core.model.TagPropertyBinding
import com.oruke.tagloom.core.workspace.WorkspaceDefaultSchemas
import com.oruke.tagloom.core.workspace.WorkspaceDefaults
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.JsonArray

/**
 * 构造可直接体验单词 code、Todo 和 Annotation 的内存示例工作区。
 *
 * @return 已选中示例笔记的编辑器状态。
 */
internal fun createDemoEditorState(): TagloomEditorState {
    var counter = 0
    val state = TagloomEditorState(
        idFactory = { prefix -> "$prefix-${counter++}" },
        timestampProvider = { "2026-07-13T00:00:00Z" },
    )
    val annotationId = AnnotationId("annotation-demo")
    val ordinaryTagsDefinition = WorkspaceDefaultSchemas.ordinaryTagsPropertyDefinition().copy(
        options = listOf(
            PropertyOption("tag-option-kmp", "KMP"),
            PropertyOption("tag-option-product-design", "产品设计"),
            PropertyOption("tag-option-local-first", "本地优先"),
        ),
    )
    val statusDefinition = WorkspaceDefaultSchemas.propertyDefinitions()
        .first { definition -> definition.id == WorkspaceDefaults.DEFAULT_SELECT_PROPERTY_ID }
        .copy(
            options = listOf(
                PropertyOption("status-active", "进行中", "status-active"),
                PropertyOption(WorkspaceDefaults.DEFAULT_SELECT_TODO_OPTION_ID, "待办", "status-pending"),
                PropertyOption(WorkspaceDefaults.DEFAULT_SELECT_DONE_OPTION_ID, "完成", "status-success"),
            ),
        )
    val dueDateDefinition = WorkspaceDefaultSchemas.propertyDefinitions()
        .first { definition -> definition.id == WorkspaceDefaults.DEFAULT_DATE_PROPERTY_ID }
        .copy(name = "截止日期")
    val priorityDefinition = PropertyDefinition(
        id = PropertyId("property-priority"),
        name = "优先级",
        type = PropertyTypes.SELECT,
        options = listOf(
            PropertyOption("priority-high", "高", "priority-high"),
            PropertyOption("priority-medium", "中", "priority-medium"),
            PropertyOption("priority-low", "低", "priority-low"),
        ),
    )
    val ownerDefinition = PropertyDefinition(
        id = PropertyId("property-owner"),
        name = "负责人",
        type = PropertyTypes.TEXT,
    )
    val pageTag = TagDefinition(
        id = TagId("tag-page"),
        name = "Page",
    )
    val projectTag = TagDefinition(
        id = TagId("tag-project"),
        name = "Project",
        parentTagIds = listOf(pageTag.id),
        propertyBindings = listOf(
            TagPropertyBinding(
                propertyId = WorkspaceDefaults.DEFAULT_SELECT_PROPERTY_ID,
                required = true,
                defaultValue = JsonPrimitive("status-active"),
                defaultPolicy = DefaultValuePolicy.DYNAMIC,
                order = 1,
            ),
            TagPropertyBinding(
                propertyId = priorityDefinition.id,
                defaultValue = JsonPrimitive("priority-high"),
                defaultPolicy = DefaultValuePolicy.DYNAMIC,
                order = 2,
            ),
            TagPropertyBinding(
                propertyId = WorkspaceDefaults.DEFAULT_DATE_PROPERTY_ID,
                defaultValue = JsonPrimitive("2026-07-20"),
                defaultPolicy = DefaultValuePolicy.SNAPSHOT,
                order = 3,
            ),
            TagPropertyBinding(propertyId = ownerDefinition.id, order = 4),
        ),
    )
    val document = EditorDocument(
        id = ObjectId("note-demo"),
        note = Note(
            title = "Tagloom 编辑器重构",
            directTagIds = listOf(WorkspaceDefaults.DEFAULT_NOTE_TAG_ID, projectTag.id),
            propertyValues = mapOf(
                WorkspaceDefaults.DEFAULT_TEXT_PROPERTY_ID to NotePropertyValue(
                    value = JsonPrimitive("重新设计对象类型、标签与动态属性，让编辑器自然回到写作本身。"),
                    source = PropertyValueSource.USER,
                    updatedAt = "2026-07-14T00:00:00Z",
                ),
                WorkspaceDefaults.DEFAULT_TAGS_PROPERTY_ID to NotePropertyValue(
                    value = JsonArray(
                        listOf(
                            JsonPrimitive("tag-option-kmp"),
                            JsonPrimitive("tag-option-product-design"),
                            JsonPrimitive("tag-option-local-first"),
                        ),
                    ),
                    source = PropertyValueSource.USER,
                    updatedAt = "2026-07-14T00:00:00Z",
                ),
                WorkspaceDefaults.DEFAULT_SELECT_PROPERTY_ID to NotePropertyValue(
                    value = JsonPrimitive("status-active"),
                    source = PropertyValueSource.USER,
                    updatedAt = "2026-07-14T00:00:00Z",
                ),
                priorityDefinition.id to NotePropertyValue(
                    value = JsonPrimitive("priority-high"),
                    source = PropertyValueSource.USER,
                    updatedAt = "2026-07-14T00:00:00Z",
                ),
                WorkspaceDefaults.DEFAULT_DATE_PROPERTY_ID to NotePropertyValue(
                    value = JsonPrimitive("2026-07-20"),
                    source = PropertyValueSource.TAG_SNAPSHOT,
                    sourceTagId = projectTag.id,
                    updatedAt = "2026-07-14T00:00:00Z",
                ),
                ownerDefinition.id to NotePropertyValue(
                    value = JsonPrimitive("未指定"),
                    source = PropertyValueSource.USER,
                    updatedAt = "2026-07-14T00:00:00Z",
                ),
            ),
            blocks = listOf(
                Block(
                    id = BlockId("block-heading"),
                    type = BlockTypes.HEADING,
                    orderKey = "0000000000",
                    content = HeadingBlockContent(
                        level = 2,
                        richText = RichText(listOf(InlineRun("为什么要重构元数据区"))),
                    ),
                ),
                Block(
                    id = BlockId("block-paragraph"),
                    type = BlockTypes.PARAGRAPH,
                    orderKey = "0000000001",
                    content = RichTextBlockContent(
                        RichText(
                            listOf(
                                InlineRun("在旧版本中，属性面板长期占据写作空间。这次重构让 "),
                                InlineRun(
                                    text = "identity",
                                    marks = listOf(InlineMark(InlineMarkTypes.CODE)),
                                    annotationIds = listOf(annotationId),
                                ),
                                InlineRun("、标签和动态属性回到标题下方，正文重新成为页面的视觉中心。"),
                            ),
                        ),
                    ),
                ),
                Block(
                    id = BlockId("block-todo"),
                    type = BlockTypes.TODO,
                    orderKey = "0000000002",
                    content = TodoBlockContent(
                        checked = true,
                        richText = RichText(listOf(InlineRun("对象类型只保留一个应用类型，切换时不丢失旧属性值"))),
                    ),
                ),
            ),
            annotations = mapOf(
                annotationId to Annotation(
                    id = annotationId,
                    kind = AnnotationKinds.DEFINITION,
                    body = "对象类型、普通标签与属性来源共同构成的页面身份。",
                    createdAt = "2026-07-13T00:00:00Z",
                    updatedAt = "2026-07-13T00:00:00Z",
                ),
            ),
        ),
        createdAt = "2026-07-13T09:00:00Z",
        updatedAt = "2026-07-14T10:30:00Z",
    )
    val roadmapDocument = EditorDocument(
        id = ObjectId("note-roadmap"),
        note = Note(
            title = "产品路线图",
            directTagIds = listOf(WorkspaceDefaults.DEFAULT_NOTE_TAG_ID, projectTag.id),
            blocks = listOf(
                Block(
                    id = BlockId("block-roadmap-heading"),
                    type = BlockTypes.HEADING,
                    orderKey = "0000000000",
                    content = HeadingBlockContent(
                        level = 2,
                        richText = RichText(listOf(InlineRun("下一阶段"))),
                    ),
                ),
                Block(
                    id = BlockId("block-roadmap-body"),
                    type = BlockTypes.PARAGRAPH,
                    orderKey = "0000000001",
                    content = RichTextBlockContent(
                        RichText(listOf(InlineRun("完善链接、反向引用与多设备同步体验。"))),
                    ),
                ),
            ),
        ),
        createdAt = "2026-07-12T02:10:00Z",
        updatedAt = "2026-07-14T08:20:00Z",
    )
    val dailyDocument = EditorDocument(
        id = ObjectId("note-daily"),
        note = Note(
            title = "今日笔记",
            directTagIds = listOf(WorkspaceDefaults.DEFAULT_NOTE_TAG_ID),
            blocks = listOf(
                Block(
                    id = BlockId("block-daily"),
                    type = BlockTypes.PARAGRAPH,
                    orderKey = "0000000000",
                    content = RichTextBlockContent(RichText(listOf(InlineRun("从这里开始记录。")))),
                ),
            ),
        ),
        createdAt = "2026-07-14T00:10:00Z",
        updatedAt = "2026-07-14T00:10:00Z",
    )
    state.loadWorkspaceDocuments(
        loadedDocuments = listOf(document, roadmapDocument, dailyDocument),
        loadedTagDefinitions = listOf(
            WorkspaceDefaultSchemas.noteTagDefinition(),
            pageTag,
            projectTag,
        ),
        loadedPropertyDefinitions = WorkspaceDefaultSchemas.propertyDefinitions().map { definition ->
            when (definition.id) {
                ordinaryTagsDefinition.id -> ordinaryTagsDefinition
                statusDefinition.id -> statusDefinition
                dueDateDefinition.id -> dueDateDefinition
                else -> definition
            }
        } + listOf(priorityDefinition, ownerDefinition),
    )
    return state
}
