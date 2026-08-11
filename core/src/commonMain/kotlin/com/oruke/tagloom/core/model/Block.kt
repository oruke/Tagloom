package com.oruke.tagloom.core.model

import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonEncoder
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * 定义 Tagloom 已知的 Block 类型名称。
 *
 * 协议保存字符串类型，使旧客户端能够以只读方式保留未来新增的 Block。
 */
object BlockTypes {
    /** 普通段落。 */
    const val PARAGRAPH: String = "paragraph"

    /** 标题。 */
    const val HEADING: String = "heading"

    /** 无序列表项。 */
    const val BULLETED_LIST: String = "bulleted-list"

    /** 有序列表项。 */
    const val NUMBERED_LIST: String = "numbered-list"

    /** 待办事项。 */
    const val TODO: String = "todo"

    /** 引用段落。 */
    const val QUOTE: String = "quote"

    /** 多行代码块。 */
    const val CODE: String = "code"

    /** 分割线。 */
    const val DIVIDER: String = "divider"

    /** 图片附件。 */
    const val IMAGE: String = "image"

    /** 普通文件附件。 */
    const val FILE: String = "file"

    /** 指向笔记或 Block 的引用。 */
    const val NOTE_REFERENCE: String = "note-reference"

    /** 包含一个或多个页签的结构容器。 */
    const val TABS: String = "tabs"

    /** Tabs 容器下的单个页签。 */
    const val TAB: String = "tab"

    /** Note 正文内的局部结构化表格。 */
    const val BASE_TABLE: String = "base-table"

    /** 在 Note 正文中嵌入一个独立 Database 对象。 */
    const val DATABASE: String = "database"
}

/**
 * 表示不同 Block payload 的封闭协议层次。
 *
 * 未知内容通过 [UnknownBlockContent] 保存原始 JSON，防止旧客户端写回时丢失数据。
 */
@Serializable(with = BlockContentSerializer::class)
sealed interface BlockContent

/**
 * 表示段落、列表项和引用等通用行内富文本内容。
 */
@Serializable
@SerialName("rich-text")
data class RichTextBlockContent(
    /** Block 的规范化行内富文本。 */
    val richText: RichText,
) : BlockContent

/**
 * 表示带层级的标题内容。
 */
@Serializable
@SerialName("heading")
data class HeadingBlockContent(
    /** 标题层级，MVP 支持 1 到 6。 */
    val level: Int,
    /** 标题的规范化行内富文本。 */
    val richText: RichText,
) : BlockContent {
    init {
        require(level in 1..6) { "标题层级必须在 1 到 6 之间" }
    }
}

/**
 * 表示带完成状态的待办内容。
 */
@Serializable
@SerialName("todo")
data class TodoBlockContent(
    /** 待办是否已经完成。 */
    val checked: Boolean,
    /** 待办的规范化行内富文本。 */
    val richText: RichText,
) : BlockContent

/**
 * 表示多行代码 Block 的内容。
 */
@Serializable
@SerialName("code")
data class CodeBlockContent(
    /** 代码语言标识；未知语言可以原样保存。 */
    val language: String? = null,
    /** 多行代码原文。 */
    val text: String,
) : BlockContent

/**
 * 表示不包含额外业务字段的分割线内容。
 */
@Serializable
@SerialName("divider")
data object DividerBlockContent : BlockContent

/**
 * 表示图片或普通文件 Block 的加密附件引用。
 */
@Serializable
@SerialName("attachment")
data class AttachmentBlockContent(
    /** 被引用的加密 Blob 标识。 */
    val blobId: BlobId,
    /** 原始文件名；该字段只能出现在加密 payload 中。 */
    val fileName: String,
    /** 文件 MIME；未知类型可以为空。 */
    val mimeType: String? = null,
    /** 用户可编辑的附件说明。 */
    val caption: RichText = RichText(),
) : BlockContent {
    init {
        require(fileName.isNotBlank()) { "附件文件名不能为空" }
    }
}

/**
 * 表示指向另一篇笔记或其具体 Block 的结构化引用。
 */
@Serializable
@SerialName("note-reference")
data class NoteReferenceBlockContent(
    /** 目标笔记标识。 */
    val noteId: ObjectId,
    /** 目标 Block 标识；为空时引用整篇笔记。 */
    val blockId: BlockId? = null,
) : BlockContent

/**
 * 表示只承担 Block 树结构的 Tabs 容器内容。
 *
 * 当前活动页签属于 UI 会话状态，容器 payload 不保存它。
 */
@Serializable
@SerialName("tabs")
data object TabsBlockContent : BlockContent

/**
 * 表示 Tabs 容器中的一个页签标题。
 */
@Serializable
@SerialName("tab")
data class TabBlockContent(
    /** 页签的规范化富文本标题。 */
    val title: RichText,
) : BlockContent

/**
 * 表示 Note 正文中对独立 Database 权威对象的稳定引用。
 *
 * DatabaseDefinition 与 ViewDefinition 继续独立加密和版本化，Block 不复制投影行或视图配置。
 */
@Serializable
@SerialName("database")
data class DatabaseBlockContent(
    /** 被嵌入正文的 Database 稳定标识。 */
    val databaseId: DatabaseId,
) : BlockContent

/**
 * 保存当前客户端尚未理解的 Block payload。
 */
@Serializable
@SerialName("unknown")
data class UnknownBlockContent(
    /** 未知 payload 的原始 JSON。 */
    val raw: JsonObject,
) : BlockContent

/**
 * 将 BlockContent 序列化为带 `_contentType` 的 JSON，并保留未知 payload。
 *
 * 默认 sealed serializer 遇到未来新增类型会直接失败，因此这里显式回退到 [UnknownBlockContent]。
 */
object BlockContentSerializer : KSerializer<BlockContent> {
    /** BlockContent 使用 JSON 对象描述符。 */
    override val descriptor: SerialDescriptor = JsonObject.serializer().descriptor

    /**
     * 将已知内容编码为带类型字段的 JSON，未知内容则原样写回。
     *
     * @param encoder 目标编码器，必须为 JSON 编码器。
     * @param value 待编码的 Block 内容。
     */
    override fun serialize(
        encoder: Encoder,
        value: BlockContent,
    ) {
        val jsonEncoder = requireNotNull(encoder as? JsonEncoder) {
            "BlockContent 只支持 JSON 序列化"
        }
        val json = jsonEncoder.json

        val element = when (value) {
            is RichTextBlockContent -> typedJson(
                type = "rich-text",
                payload = json.encodeToJsonElement(RichTextBlockContent.serializer(), value).jsonObject,
            )

            is HeadingBlockContent -> typedJson(
                type = "heading",
                payload = json.encodeToJsonElement(HeadingBlockContent.serializer(), value).jsonObject,
            )

            is TodoBlockContent -> typedJson(
                type = "todo",
                payload = json.encodeToJsonElement(TodoBlockContent.serializer(), value).jsonObject,
            )

            is CodeBlockContent -> typedJson(
                type = "code",
                payload = json.encodeToJsonElement(CodeBlockContent.serializer(), value).jsonObject,
            )

            DividerBlockContent -> typedJson(
                type = "divider",
                payload = buildJsonObject { },
            )

            is AttachmentBlockContent -> typedJson(
                type = "attachment",
                payload = json.encodeToJsonElement(AttachmentBlockContent.serializer(), value).jsonObject,
            )

            is NoteReferenceBlockContent -> typedJson(
                type = "note-reference",
                payload = json.encodeToJsonElement(NoteReferenceBlockContent.serializer(), value).jsonObject,
            )

            TabsBlockContent -> typedJson(
                type = "tabs",
                payload = buildJsonObject { },
            )

            is TabBlockContent -> typedJson(
                type = "tab",
                payload = json.encodeToJsonElement(TabBlockContent.serializer(), value).jsonObject,
            )

            is BaseTableBlockContent -> typedJson(
                type = "base-table",
                payload = json.encodeToJsonElement(BaseTableBlockContent.serializer(), value).jsonObject,
            )

            is DatabaseBlockContent -> typedJson(
                type = "database",
                payload = json.encodeToJsonElement(DatabaseBlockContent.serializer(), value).jsonObject,
            )

            is UnknownBlockContent -> value.raw
        }

        jsonEncoder.encodeJsonElement(element)
    }

    /**
     * 根据 `_contentType` 解码已知内容，未知类型原样保留。
     *
     * @param decoder 来源解码器，必须为 JSON 解码器。
     * @return 已知结构化内容或保存原始 JSON 的未知内容。
     */
    override fun deserialize(decoder: Decoder): BlockContent {
        val jsonDecoder = requireNotNull(decoder as? JsonDecoder) {
            "BlockContent 只支持 JSON 反序列化"
        }
        val raw = jsonDecoder.decodeJsonElement().jsonObject
        val type = raw[CONTENT_TYPE_FIELD]?.jsonPrimitive?.content
        val payload = JsonObject(raw.filterKeys { key -> key != CONTENT_TYPE_FIELD })
        val json = jsonDecoder.json

        return when (type) {
            "rich-text" -> json.decodeFromJsonElement(RichTextBlockContent.serializer(), payload)
            "heading" -> json.decodeFromJsonElement(HeadingBlockContent.serializer(), payload)
            "todo" -> json.decodeFromJsonElement(TodoBlockContent.serializer(), payload)
            "code" -> json.decodeFromJsonElement(CodeBlockContent.serializer(), payload)
            "divider" -> DividerBlockContent
            "attachment" -> json.decodeFromJsonElement(AttachmentBlockContent.serializer(), payload)
            "note-reference" -> json.decodeFromJsonElement(NoteReferenceBlockContent.serializer(), payload)
            "tabs" -> TabsBlockContent
            "tab" -> json.decodeFromJsonElement(TabBlockContent.serializer(), payload)
            "base-table" -> json.decodeFromJsonElement(BaseTableBlockContent.serializer(), payload)
            "database" -> json.decodeFromJsonElement(DatabaseBlockContent.serializer(), payload)
            else -> UnknownBlockContent(raw = raw)
        }
    }

    /**
     * 把内容类型字段放在 payload 前面，生成稳定的 JSON 对象。
     *
     * @param type 内容协议类型。
     * @param payload 已编码的内容字段。
     * @return 包含 `_contentType` 的 JSON 对象。
     */
    private fun typedJson(
        type: String,
        payload: JsonObject,
    ): JsonObject = buildJsonObject {
        put(CONTENT_TYPE_FIELD, type)
        payload.forEach { (key, value) -> put(key, value) }
    }

    /** BlockContent JSON 中保存类型的稳定字段名。 */
    private const val CONTENT_TYPE_FIELD: String = "_contentType"
}

/**
 * 表示笔记正文中的稳定结构化 Block。
 */
@Serializable
data class Block(
    /** Block 的稳定业务标识。 */
    val id: BlockId,
    /** Block 类型；未知类型必须原样保留。 */
    val type: String,
    /** 父 Block 标识；顶层 Block 为空。 */
    val parentId: BlockId? = null,
    /** 同一父节点下用于稳定排序的键。 */
    val orderKey: String,
    /** 按类型区分的结构化内容。 */
    val content: BlockContent,
    /** 不属于核心 schema 的可扩展属性。 */
    val attributes: JsonObject = buildJsonObject { },
    /** Block 是否为等待清理的 tombstone。 */
    val deleted: Boolean = false,
) {
    init {
        require(type.isNotBlank()) { "Block 类型不能为空" }
        require(orderKey.isNotBlank()) { "Block orderKey 不能为空" }
    }
}

/**
 * 描述 Block 类型与 payload 不一致时的领域校验问题。
 */
@Serializable
data class BlockValidationIssue(
    /** 出现问题的 Block 标识。 */
    val blockId: BlockId,
    /** 稳定错误码。 */
    val code: String,
    /** 面向开发和恢复界面的中文说明。 */
    val message: String,
)

/**
 * 校验 Block 外层类型与结构化 payload 是否匹配。
 */
object BlockValidator {
    /**
     * 校验单个 Block 的类型与 payload 组合。
     *
     * 未知 Block 必须使用 [UnknownBlockContent]，保证旧客户端不会误解释新协议。
     *
     * @param block 待校验的 Block。
     * @return 校验问题列表；空列表表示结构有效。
     */
    fun validate(block: Block): List<BlockValidationIssue> {
        val valid = when (block.type) {
            BlockTypes.PARAGRAPH,
            BlockTypes.BULLETED_LIST,
            BlockTypes.NUMBERED_LIST,
            BlockTypes.QUOTE,
            -> block.content is RichTextBlockContent

            BlockTypes.HEADING -> block.content is HeadingBlockContent
            BlockTypes.TODO -> block.content is TodoBlockContent
            BlockTypes.CODE -> block.content is CodeBlockContent
            BlockTypes.DIVIDER -> block.content is DividerBlockContent
            BlockTypes.IMAGE,
            BlockTypes.FILE,
            -> block.content is AttachmentBlockContent

            BlockTypes.NOTE_REFERENCE -> block.content is NoteReferenceBlockContent
            BlockTypes.TABS -> block.content is TabsBlockContent
            BlockTypes.TAB -> block.content is TabBlockContent
            BlockTypes.BASE_TABLE -> block.content is BaseTableBlockContent
            BlockTypes.DATABASE -> block.content is DatabaseBlockContent
            else -> block.content is UnknownBlockContent
        }

        return if (valid) {
            emptyList()
        } else {
            listOf(
                BlockValidationIssue(
                    blockId = block.id,
                    code = "block_content_type_mismatch",
                    message = "Block 类型 ${block.type} 与 payload ${block.content::class.simpleName} 不匹配",
                ),
            )
        }
    }
}
