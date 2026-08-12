package com.oruke.tagloom.ui

import com.oruke.tagloom.core.model.DatabaseId
import com.oruke.tagloom.core.model.FieldRef
import com.oruke.tagloom.core.model.FieldRefKinds
import com.oruke.tagloom.core.model.PropertyDefinition
import com.oruke.tagloom.core.model.PropertyId
import com.oruke.tagloom.core.model.PropertyTypes
import com.oruke.tagloom.core.model.SystemFieldNames
import com.oruke.tagloom.core.model.ViewDefinition
import com.oruke.tagloom.core.model.ViewField
import com.oruke.tagloom.core.model.ViewId
import com.oruke.tagloom.core.model.ViewPresentation
import com.oruke.tagloom.core.model.ViewPresentationKinds
import kotlin.test.Test
import kotlin.test.assertEquals

/** 验证数据集字段面板排序与列宽调整共用的确定性视图转换。 */
class DatasetViewTransformsTest {
    /** 验证列宽会限制在协议范围并只更新目标字段。 */
    @Test
    fun resizesOnlyTargetFieldWithinProtocolRange() {
        val view = view()
        val updated = view.withDatasetFieldWidth(titleField, 900)

        assertEquals(720, updated.presentation.fields[0].width)
        assertEquals(120, updated.presentation.fields[1].width)
    }

    /** 验证字段面板仅在可见属性之间移动，并保持标题字段位于首列。 */
    @Test
    fun movesVisiblePropertyWithoutCountingHiddenFields() {
        val updated = sortableView().movedDatasetVisiblePropertyField(priorityField, -1)

        assertEquals(
            listOf(titleField, priorityField, statusField, dueField),
            updated.presentation.fields.map(ViewField::field),
        )
        assertEquals(
            listOf("a000000", "a000001", "a000002", "a000003"),
            updated.presentation.fields.map(ViewField::orderKey),
        )
    }

    /** 验证重新选择隐藏属性时会保留列宽并追加到可见字段末尾。 */
    @Test
    fun appendsFieldWhenShowingItAgain() {
        val updated = sortableView().toggledDatasetField(dueField)

        assertEquals(
            listOf(titleField, statusField, priorityField, dueField),
            updated.presentation.fields.map(ViewField::field),
        )
        assertEquals(true, updated.presentation.fields.last().visible)
        assertEquals(160, updated.presentation.fields.last().width)
    }

    /** 验证当前视图切换看板时会自动选择可用单选属性。 */
    @Test
    fun switchesCurrentViewToKanbanWithGroupingProperty() {
        val updated = view().withDatasetPresentationKind(
            ViewPresentationKinds.KANBAN,
            listOf(PropertyDefinition(statusId, "状态", PropertyTypes.SELECT)),
        )

        assertEquals(ViewPresentationKinds.KANBAN, updated.presentation.kind)
        assertEquals(statusId, updated.presentation.groupByPropertyId)
    }

    /**
     * 构造包含标题和状态两列的最小视图。
     *
     * @return 可用于字段转换断言的视图。
     */
    private fun view(): ViewDefinition = ViewDefinition(
        id = ViewId("view-test"),
        databaseId = DatabaseId("database-test"),
        name = "表格",
        orderKey = "a000000",
        presentation = ViewPresentation(
            fields = listOf(
                ViewField(titleField, "a000000", width = 220),
                ViewField(statusField, "a000001", width = 120),
            ),
        ),
    )

    /**
     * 构造包含标题、两个可见属性和一个隐藏属性的排序视图。
     *
     * @return 可验证属性排序不会受系统字段和隐藏字段干扰的视图。
     */
    private fun sortableView(): ViewDefinition = ViewDefinition(
        id = ViewId("view-sortable"),
        databaseId = DatabaseId("database-test"),
        name = "表格",
        orderKey = "a000000",
        presentation = ViewPresentation(
            fields = listOf(
                ViewField(titleField, "a000000", width = 220),
                ViewField(statusField, "a000001", width = 120),
                ViewField(dueField, "a000002", visible = false, width = 160),
                ViewField(priorityField, "a000003", width = 120),
            ),
        ),
    )

    companion object {
        /** 测试状态属性 ID。 */
        private val statusId = PropertyId("property-status")

        /** 测试截止日期属性 ID。 */
        private val dueId = PropertyId("property-due")

        /** 测试优先级属性 ID。 */
        private val priorityId = PropertyId("property-priority")

        /** 测试标题字段。 */
        private val titleField = FieldRef(FieldRefKinds.SYSTEM, systemField = SystemFieldNames.TITLE)

        /** 测试状态字段。 */
        private val statusField = FieldRef(FieldRefKinds.PROPERTY, propertyId = statusId)

        /** 测试截止日期字段。 */
        private val dueField = FieldRef(FieldRefKinds.PROPERTY, propertyId = dueId)

        /** 测试优先级字段。 */
        private val priorityField = FieldRef(FieldRefKinds.PROPERTY, propertyId = priorityId)
    }
}
