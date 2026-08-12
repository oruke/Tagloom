package com.oruke.tagloom.ui

import com.oruke.tagloom.core.model.BlockId
import com.oruke.tagloom.core.model.DatabaseDefinition
import com.oruke.tagloom.core.model.DatabaseId
import com.oruke.tagloom.core.model.DatasetProjection
import com.oruke.tagloom.core.model.FieldRef
import com.oruke.tagloom.core.model.ObjectId
import com.oruke.tagloom.core.model.PropertyDefinition
import com.oruke.tagloom.core.model.TagDefinition
import com.oruke.tagloom.core.model.TagId
import com.oruke.tagloom.core.model.ViewDefinition
import com.oruke.tagloom.core.model.ViewId
import kotlinx.serialization.json.JsonElement

/**
 * 定义正文数据集 Block 所需的最小会话能力。
 *
 * 生产实现通过加密工作区事务写入，预览实现只写内存；UI 不得据此绕开正式工作区写入协议。
 */
internal interface DatabaseEditorController {
    /** 当前是否正在执行数据集写入。 */
    val busy: Boolean

    /** 最近一次数据集操作是否失败。 */
    val operationFailed: Boolean

    /**
     * 返回稳定 ID 对应的数据集定义。
     *
     * @param databaseId 数据集稳定 ID。
     * @return 已加载定义；不存在时为 null。
     */
    fun database(databaseId: DatabaseId): DatabaseDefinition?

    /**
     * 返回数据集的有序视图。
     *
     * @param databaseId 数据集稳定 ID。
     * @return 按 orderKey 排序的视图副本。
     */
    fun viewsFor(databaseId: DatabaseId): List<ViewDefinition>

    /**
     * 判断 Slash 命令所在 Block 是否仍在等待创建事务。
     *
     * @param blockId 命令 Block 稳定 ID。
     * @return 正在创建时为 true。
     */
    fun isCreatingBlock(blockId: BlockId): Boolean

    /**
     * 创建数据集与首个视图，并把命令 Block 绑定为数据集引用。
     *
     * @param documentId 命令所在 Note 稳定 ID。
     * @param blockId 命令所在 Block 稳定 ID。
     * @param name 初始数据集名称。
     * @param sourceTypeId 初始来源对象类型。
     * @return 创建成功的数据集 ID；失败时为 null。
     */
    suspend fun createDatabaseBlock(
        documentId: ObjectId,
        blockId: BlockId,
        name: String,
        sourceTypeId: TagId,
    ): DatabaseId?

    /**
     * 复制当前配置并创建一个新视图。
     *
     * @param databaseId 所属数据集 ID。
     * @param currentViewId 模板视图 ID。
     * @param name 新视图名称。
     * @return 新视图 ID；失败时为 null。
     */
    suspend fun createView(
        databaseId: DatabaseId,
        currentViewId: ViewId,
        name: String,
    ): ViewId?

    /**
     * 为指定数据集创建一篇空 Note 行。
     *
     * @param databaseId 目标数据集 ID。
     * @return 完成时为 Unit；被并发写入拒绝或失败时为 null。
     */
    suspend fun createRow(databaseId: DatabaseId): Unit?

    /**
     * 更新数据集定义。
     *
     * @param databaseId 目标数据集 ID。
     * @param transform 纯定义转换。
     * @return 完成时为 Unit；被并发写入拒绝或失败时为 null。
     */
    suspend fun updateDatabase(
        databaseId: DatabaseId,
        transform: (DatabaseDefinition) -> DatabaseDefinition,
    ): Unit?

    /**
     * 更新视图定义。
     *
     * @param viewId 目标视图 ID。
     * @param transform 纯视图转换。
     * @return 完成时为 Unit；被并发写入拒绝或失败时为 null。
     */
    suspend fun updateView(
        viewId: ViewId,
        transform: (ViewDefinition) -> ViewDefinition,
    ): Unit?

    /**
     * 设置视图的全文过滤文本。
     *
     * @param viewId 目标视图 ID。
     * @param text 用户输入的过滤文本；空白表示清除。
     * @return 完成时为 Unit；被并发写入拒绝或失败时为 null。
     */
    suspend fun setFullTextFilter(
        viewId: ViewId,
        text: String,
    ): Unit?

    /**
     * 计算数据集当前派生投影。
     *
     * @param databaseId 数据集 ID。
     * @param viewId 视图 ID。
     * @return 可重建投影；引用不完整时为 null。
     */
    fun projection(
        databaseId: DatabaseId,
        viewId: ViewId,
    ): DatasetProjection?

    /**
     * 返回数据集来源类型的有效属性定义。
     *
     * @param databaseId 数据集 ID。
     * @return 可配置属性列表。
     */
    fun propertyDefinitions(databaseId: DatabaseId): List<PropertyDefinition>

    /**
     * 返回当前会话的对象类型目录。
     *
     * @return 标签定义的不可变副本。
     */
    fun tagDefinitions(): List<TagDefinition>

    /**
     * 更新投影行对应 Note 的一个字段。
     *
     * @param noteId 目标 Note ID。
     * @param field 稳定字段引用。
     * @param value 新 JSON 值；null 表示清除属性。
     * @return 完成时为 Unit；被并发写入拒绝或失败时为 null。
     */
    suspend fun updateCell(
        noteId: ObjectId,
        field: FieldRef,
        value: JsonElement?,
    ): Unit?
}
