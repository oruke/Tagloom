package com.oruke.tagloom.core.model

/**
 * 描述一篇笔记从兼容字段 `directTagIds` 中解析出的产品级对象类型状态。
 *
 * 协议继续保留列表以无损读取旧快照；正常编辑状态只允许一个非根应用类型。
 */
data class NoteObjectTypeState(
    /** 当前唯一应用类型；旧快照存在多个候选时为空，等待用户确认。 */
    val appliedTypeId: TagId?,
    /** 快照中全部非根直接类型，按原始顺序去重保留。 */
    val candidateTypeIds: List<TagId>,
) {
    /** 是否需要用户从旧快照的多个直接类型中确认一个应用类型。 */
    val requiresResolution: Boolean
        get() = candidateTypeIds.size > 1
}

/**
 * 从兼容协议字段解析当前对象类型选择，但不修改原始笔记。
 *
 * @param rootTypeId 工作区内置 Note 根类型 ID。
 * @return 唯一应用类型或需要显式处理的旧多类型候选。
 */
fun Note.objectTypeState(rootTypeId: TagId): NoteObjectTypeState {
    val candidates = directTagIds.distinct().filterNot { tagId -> tagId == rootTypeId }
    return NoteObjectTypeState(
        appliedTypeId = candidates.singleOrNull(),
        candidateTypeIds = candidates,
    )
}

/**
 * 显式选择唯一应用类型，并把直接类型规范化为根类型加可选应用类型。
 *
 * 该转换只修改类型关联，不删除任何属性值、局部样式或历史来源，避免切换类型造成数据丢失。
 *
 * @param rootTypeId 工作区内置 Note 根类型 ID。
 * @param appliedTypeId 用户确认的应用类型；为空表示只保留根类型。
 * @return 保留全部非类型数据且直接类型已规范化的新笔记。
 */
fun Note.withAppliedObjectType(
    rootTypeId: TagId,
    appliedTypeId: TagId?,
): Note {
    require(appliedTypeId != rootTypeId) { "根 Note 类型不需要重复设为应用类型" }
    return copy(directTagIds = listOfNotNull(rootTypeId, appliedTypeId))
}
