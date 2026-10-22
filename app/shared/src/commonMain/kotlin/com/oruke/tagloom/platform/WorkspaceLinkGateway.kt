package com.oruke.tagloom.platform

import com.oruke.tagloom.core.model.*

/** 创建链接时可选的来源文档；包含完整草稿和期望 revision，防止丢失未保存正文。 */
data class LinkNoteSource(
    /** 来源笔记稳定身份，包括尚未落盘的草稿身份。 */
    val id: ObjectId,
    /** 本次录入提交时的完整正文。 */
    val note: Note,
    /** 要替换的空段落身份。 */
    val blockId: BlockId,
    /** 已确认 revision，新草稿为空。 */
    val expectedRevision: ObjectRevision?,
)

/** 链接快捷录入的统一业务请求，不携带界面状态或主密钥。 */
data class CreateLinkNoteRequest(
    /** 用户录入的 HTTP(S) 地址。 */
    val url: String,
    /** 可空的稳定分组身份。 */
    val groupId: String? = null,
    /** 在正文中引用时的来源，集合单独创建时为空。 */
    val source: LinkNoteSource? = null,
    /** 链接预置类型首次落盘时使用的本地化名称。 */
    val typeName: String,
    /** 首次创建分组属性时使用的本地化名称。 */
    val groupName: String,
    /** 多个历史重复地址中用户显式选择的复用目标。 */
    val reuseNoteId: ObjectId? = null,
)

/** 返回同一事务确认的链接、可选来源以及首次补齐的类型目录。 */
data class CreateLinkNoteResult(
    /** 创建或复用的链接笔记。 */
    val link: WorkspaceNote,
    /** 同时确认的来源笔记；单独创建时为空。 */
    val source: WorkspaceNote?,
    /** 链接类型的当前权威定义。 */
    val type: TagDefinition,
    /** 当前 URL 与分组目录。 */
    val properties: List<PropertyDefinition>,
    /** 是否复用已有链接，复用时不会更改其分组或内容。 */
    val reused: Boolean,
    /** 创建引用后继续编辑的段落身份。 */
    val nextBlockId: BlockId? = null,
)

/** 当前平台的链接应用服务入口，写入仍由工作区唯一协调器确认。 */
interface WorkspaceLinkGateway {
    /**
     * 原子创建或复用链接，并按需插入来源引用。
     * @param request 有效录入内容与来源版本。
     * @return 已确认对象与目录。
     */
    suspend fun create(request: CreateLinkNoteRequest): CreateLinkNoteResult

    /**
     * 只请求目标 URL，随后按最新字段来源事务回填；锁定后旧请求失效。
     * @param noteId 目标链接笔记。
     * @return 新确认或未改变的链接笔记。
     */
    suspend fun refresh(noteId: ObjectId): WorkspaceNote

    /**
     * 撤销本次引用；只在刚建目标未被编辑且没有其他引用时同时写入目标 tombstone。
     * @param context 当前解锁会话保存的创建上下文。
     * @return 同一事务确认的来源和删除结果。
     */
    suspend fun undo(context: LinkCreationUndo): LinkCreationUndoResult
}
