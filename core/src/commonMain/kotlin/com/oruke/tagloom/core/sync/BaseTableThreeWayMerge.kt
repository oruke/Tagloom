package com.oruke.tagloom.core.sync

import com.oruke.tagloom.core.model.BaseTableBlockContent
import com.oruke.tagloom.core.model.BaseTableCell
import com.oruke.tagloom.core.model.BaseTableColumn
import com.oruke.tagloom.core.model.BaseTableColumnId
import com.oruke.tagloom.core.model.BaseTableNormalizer
import com.oruke.tagloom.core.model.BaseTableRow
import com.oruke.tagloom.core.model.BaseTableRowId
import com.oruke.tagloom.core.model.BaseTableTextCell

/**
 * 按稳定行、列与单元格身份合并同一 BaseTable Block 的并发编辑。
 *
 * 行列 tombstone 与内容分字段合并，因此删除不会抹掉另一设备补充的可恢复内容；同一单元格的重叠编辑仍交给外层保留完整冲突证据。
 */
internal object BaseTableThreeWayMerge {
    /**
     * 合并 BaseTable schema、标题、列和行。
     *
     * @param base 共同祖先表格。
     * @param local 当前设备表格。
     * @param remote 远端设备表格。
     * @return 规范化合并内容和冲突状态。
     */
    fun merge(
        base: BaseTableBlockContent,
        local: BaseTableBlockContent,
        remote: BaseTableBlockContent,
    ): BlockContentMerge {
        val schemaVersion = ThreeWayValueMerge.merge(base.schemaVersion, local.schemaVersion, remote.schemaVersion)
        val title = RichTextThreeWayMerge.merge(base.title, local.title, remote.title)
        val columns = mergeEntries(
            base = base.columns,
            local = local.columns,
            remote = remote.columns,
            identity = BaseTableColumn::columnId,
            identityText = BaseTableColumnId::value,
            mergeExisting = ::mergeColumn,
        )
        val rows = mergeEntries(
            base = base.rows,
            local = local.rows,
            remote = remote.rows,
            identity = BaseTableRow::rowId,
            identityText = BaseTableRowId::value,
            mergeExisting = ::mergeRow,
        )
        val merged = BaseTableBlockContent(
            schemaVersion = schemaVersion.value,
            title = title.merged ?: local.title,
            columns = columns.value,
            rows = rows.value,
        )
        return BlockContentMerge(
            value = BaseTableNormalizer.normalize(merged),
            conflicted = schemaVersion.conflicted || title.conflicted || columns.conflicted || rows.conflicted,
        )
    }

    /**
     * 分字段合并同一稳定列，允许一侧改名、另一侧调整宽度。
     *
     * @param base 共同祖先列。
     * @param local 当前设备列。
     * @param remote 远端设备列。
     * @return 合并列与冲突状态。
     */
    private fun mergeColumn(
        base: BaseTableColumn,
        local: BaseTableColumn,
        remote: BaseTableColumn,
    ): EntryMerge<BaseTableColumn> {
        val orderKey = ThreeWayValueMerge.merge(base.orderKey, local.orderKey, remote.orderKey)
        val name = RichTextThreeWayMerge.merge(base.name, local.name, remote.name)
        val type = ThreeWayValueMerge.merge(base.type, local.type, remote.type)
        val options = ThreeWayValueMerge.merge(base.options, local.options, remote.options)
        val width = ThreeWayValueMerge.merge(base.width, local.width, remote.width)
        val deleted = ThreeWayValueMerge.merge(base.deleted, local.deleted, remote.deleted)
        return EntryMerge(
            value = BaseTableColumn(
                columnId = base.columnId,
                orderKey = orderKey.value,
                name = name.merged ?: local.name,
                type = type.value,
                options = options.value,
                width = width.value,
                deleted = deleted.value,
            ),
            conflicted = orderKey.conflicted || name.conflicted || type.conflicted ||
                options.conflicted || width.conflicted || deleted.conflicted,
        )
    }

    /**
     * 分字段合并同一稳定行，并按 ColumnId 合并稀疏单元格。
     *
     * @param base 共同祖先行。
     * @param local 当前设备行。
     * @param remote 远端设备行。
     * @return 合并行与冲突状态。
     */
    private fun mergeRow(
        base: BaseTableRow,
        local: BaseTableRow,
        remote: BaseTableRow,
    ): EntryMerge<BaseTableRow> {
        val orderKey = ThreeWayValueMerge.merge(base.orderKey, local.orderKey, remote.orderKey)
        val deleted = ThreeWayValueMerge.merge(base.deleted, local.deleted, remote.deleted)
        val cells = mergeCellMaps(base.cells, local.cells, remote.cells)
        return EntryMerge(
            value = BaseTableRow(
                rowId = base.rowId,
                orderKey = orderKey.value,
                cells = cells.value,
                deleted = deleted.value,
            ),
            conflicted = orderKey.conflicted || deleted.conflicted || cells.conflicted,
        )
    }

    /**
     * 按 ColumnId 合并一行内的稀疏单元格。
     *
     * @param base 共同祖先单元格映射。
     * @param local 当前设备单元格映射。
     * @param remote 远端设备单元格映射。
     * @return 合并后的稀疏单元格和冲突状态。
     */
    private fun mergeCellMaps(
        base: Map<BaseTableColumnId, BaseTableCell>,
        local: Map<BaseTableColumnId, BaseTableCell>,
        remote: Map<BaseTableColumnId, BaseTableCell>,
    ): EntryMerge<Map<BaseTableColumnId, BaseTableCell>> {
        var conflicted = false
        val values = linkedMapOf<BaseTableColumnId, BaseTableCell>()
        (base.keys + local.keys + remote.keys).distinct().sortedBy(BaseTableColumnId::value).forEach { columnId ->
            val merged = mergeNullable(base[columnId], local[columnId], remote[columnId], ::mergeCell)
            conflicted = conflicted || merged.conflicted
            merged.value?.let { cell -> values[columnId] = cell }
        }
        return EntryMerge(values, conflicted)
    }

    /**
     * 合并同一单元格；文本可组合非重叠 RichText 编辑，其余类型使用确定性三方值比较。
     *
     * @param base 共同祖先单元格。
     * @param local 当前设备单元格。
     * @param remote 远端设备单元格。
     * @return 合并单元格和冲突状态。
     */
    private fun mergeCell(
        base: BaseTableCell,
        local: BaseTableCell,
        remote: BaseTableCell,
    ): EntryMerge<BaseTableCell> {
        if (base is BaseTableTextCell && local is BaseTableTextCell && remote is BaseTableTextCell) {
            val text = RichTextThreeWayMerge.merge(base.richText, local.richText, remote.richText)
            return EntryMerge(BaseTableTextCell(text.merged ?: local.richText), text.conflicted)
        }
        val merged = ThreeWayValueMerge.merge(base, local, remote)
        return EntryMerge(merged.value, merged.conflicted)
    }

    /**
     * 按稳定身份合并行或列集合，并保留单侧新增、删除或清理结果。
     *
     * @param T 集合元素类型。
     * @param K 稳定身份类型。
     * @param base 共同祖先集合。
     * @param local 当前设备集合。
     * @param remote 远端设备集合。
     * @param identity 提取稳定身份的函数。
     * @param identityText 提取可稳定排序的身份文本。
     * @param mergeExisting 合并三侧均存在元素的函数。
     * @return 合并集合与冲突状态。
     */
    private fun <T, K> mergeEntries(
        base: List<T>,
        local: List<T>,
        remote: List<T>,
        identity: (T) -> K,
        identityText: (K) -> String,
        mergeExisting: (T, T, T) -> EntryMerge<T>,
    ): EntryMerge<List<T>> {
        val baseById = base.associateBy(identity)
        val localById = local.associateBy(identity)
        val remoteById = remote.associateBy(identity)
        var conflicted = false
        val values = (baseById.keys + localById.keys + remoteById.keys)
            .distinct()
            .sortedBy(identityText)
            .mapNotNull { id ->
                val merged = mergeNullable(baseById[id], localById[id], remoteById[id], mergeExisting)
                conflicted = conflicted || merged.conflicted
                merged.value
            }
        return EntryMerge(values, conflicted)
    }

    /**
     * 合并可缺失的稳定实体；双方对同一新增身份给出不同内容时保守标记冲突。
     *
     * @param T 实体类型。
     * @param base 共同祖先实体。
     * @param local 当前设备实体。
     * @param remote 远端设备实体。
     * @param mergeExisting 三侧均存在时的字段级合并函数。
     * @return 可空合并值与冲突状态。
     */
    private fun <T> mergeNullable(
        base: T?,
        local: T?,
        remote: T?,
        mergeExisting: (T, T, T) -> EntryMerge<T>,
    ): EntryMerge<T?> = when {
        local == remote -> EntryMerge<T?>(local, conflicted = false)
        local == base -> EntryMerge<T?>(remote, conflicted = false)
        remote == base -> EntryMerge<T?>(local, conflicted = false)
        base != null && local != null && remote != null -> mergeExisting(base, local, remote).let { merged ->
            EntryMerge<T?>(merged.value, merged.conflicted)
        }
        else -> EntryMerge<T?>(local, conflicted = true)
    }
}

/** 描述 BaseTable 内部稳定实体的字段级合并结果。 */
private data class EntryMerge<T>(
    /** 合并值。 */ val value: T,
    /** 是否存在无法自动解释的重叠编辑。 */ val conflicted: Boolean,
)
