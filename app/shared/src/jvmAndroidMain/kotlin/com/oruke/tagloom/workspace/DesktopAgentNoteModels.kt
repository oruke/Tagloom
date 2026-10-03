package com.oruke.tagloom.workspace

import com.oruke.tagloom.core.external.ExternalCallException
import com.oruke.tagloom.core.model.*
import com.oruke.tagloom.core.protocol.CanonicalJson
import kotlinx.serialization.json.*

/** 保存预览时冻结的笔记写入计划；确认后不能重新解释原始 Markdown。 */
internal data class DesktopAgentNoteMutation(
    /** 预览时分配的新 ID 或待更新的对象 ID。 */
    val noteId: ObjectId,
    /** 创建时为空，更新或删除时为预览所用的旧 revision。 */
    val expectedRevision: ObjectRevision?,
    /** 完整、已校验且与预览一致的 Note。 */
    val note: Note,
    /** 是否只提交 tombstone。 */
    val deleted: Boolean = false,
    /** 写入计划依赖的类型与属性版本；删除不依赖 schema。 */
    val schemaRevisions: Map<ObjectId, ObjectRevision> = emptyMap(),
)

/**
 * 将 revision 转换为外部客户端可回传的稳定文本。
 * @return `actorId:sequence`，不依赖本机语言或时间格式。
 */
internal fun ObjectRevision.externalText(): String = "${actorId.value}:$sequence"

/**
 * 严格解析客户端的乐观锁 revision。
 * @param value 外部协议文本。
 * @return 领域 revision；无效值返回脱敏错误。
 */
internal fun parseExternalRevision(value: String): ObjectRevision {
    val parts = value.split(':')
    if (parts.size != 2) throw ExternalCallException("invalid_arguments")
    return try { ObjectRevision(ActorId(parts[0]), parts[1].toLong()) }
    catch (_: IllegalArgumentException) { throw ExternalCallException("invalid_arguments") }
}

/**
 * 序列化一篇已确认 Note，不泄露路径、密钥或未确认草稿。
 * @param stored 已确认快照。
 * @param includeContent 是否包含完整笔记正文。
 * @return 稳定 JSON 结果。
 */
internal fun externalNoteJson(stored: DesktopStoredNote, includeContent: Boolean = true): JsonObject = buildJsonObject {
    put("noteId", stored.id.value)
    put("revision", stored.snapshot.header.revision.externalText())
    put("createdAt", stored.snapshot.header.createdAt)
    put("updatedAt", stored.snapshot.header.updatedAt)
    put("title", stored.snapshot.payload.title)
    if (includeContent) put("note", CanonicalJson.format.encodeToJsonElement(Note.serializer(), stored.snapshot.payload))
}

/**
 * 对已授权结果分页，返回是否有更多和下一偏移。
 * @param items 在分页前已完成授权交集的有序元素。
 * @param limit 返回数量上限。
 * @param offset 授权集合中的偏移。
 * @return 统一集合响应；不返回越权集合总量。
 */
internal fun externalPage(items: List<JsonElement>, limit: Int, offset: Int): JsonObject {
    val page = items.drop(offset).take(limit)
    val hasMore = offset.toLong() + page.size < items.size
    return buildJsonObject {
        put("items", JsonArray(page)); put("hasMore", hasMore)
        put("nextOffset", if (hasMore) JsonPrimitive(offset + page.size) else JsonNull)
    }
}
