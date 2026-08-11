package com.oruke.tagloom.editor

import com.oruke.tagloom.core.model.ObjectId

/**
 * 接受由 Database 投影单元格触发的 Note 保存结果，但不改变当前页面选择。
 *
 * @param previousId 保存前 Note ID。
 * @param persistedDocument 新确认文档。
 */
fun TagloomEditorState.acceptBackgroundPersistedDocument(
    previousId: ObjectId,
    persistedDocument: EditorDocument,
) {
    require(persistedDocument.persisted) { "后台保存结果必须是已确认文档" }
    val index = documents.indexOfFirst { document -> document.id == previousId }
    require(index >= 0) { "后台保存目标 Note 不存在" }
    documents[index] = persistedDocument
}

/**
 * 接受由 Database 新建行触发的新 Note 保存结果，但不切换当前 Note 页面。
 *
 * @param persistedDocument 新确认且尚未存在于编辑器列表的文档。
 */
fun TagloomEditorState.acceptBackgroundCreatedDocument(persistedDocument: EditorDocument) {
    require(persistedDocument.persisted && !persistedDocument.dirty) { "新建结果必须是未修改的已确认文档" }
    require(documents.none { document -> document.id == persistedDocument.id }) { "新建 Note 已存在于编辑器列表" }
    documents += persistedDocument
}
