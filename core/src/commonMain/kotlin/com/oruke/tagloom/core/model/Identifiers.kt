package com.oruke.tagloom.core.model

import kotlinx.serialization.Serializable
import kotlin.jvm.JvmInline

/**
 * 表示工作区内稳定且不透明的业务对象标识。
 *
 * 标识不得由标题、名称或本机路径派生，避免重命名改变身份或泄露敏感元数据。
 */
@Serializable
@JvmInline
value class ObjectId(
    /** 对象的稳定标识文本。 */
    val value: String,
) {
    init {
        requireValidOpaqueIdentifier(value, "对象 ID")
    }
}

/**
 * 表示笔记正文中稳定 Block 的业务标识。
 */
@Serializable
@JvmInline
value class BlockId(
    /** Block 的稳定标识文本。 */
    val value: String,
) {
    init {
        requireValidOpaqueIdentifier(value, "Block ID")
    }
}

/**
 * 表示 BaseTable 内稳定且不透明的行标识。
 *
 * RowId 只在所属 BaseTable 内容中有意义，不代表 Note，也不得由行号或单元格内容派生。
 */
@Serializable
@JvmInline
value class BaseTableRowId(
    /** 行的稳定标识文本。 */
    val value: String,
) {
    init {
        requireValidOpaqueIdentifier(value, "BaseTable Row ID")
    }
}

/**
 * 表示 BaseTable 内稳定且不透明的列标识。
 *
 * ColumnId 属于正文表格的局部 schema，不能作为工作区属性 ID 使用。
 */
@Serializable
@JvmInline
value class BaseTableColumnId(
    /** 列的稳定标识文本。 */
    val value: String,
) {
    init {
        requireValidOpaqueIdentifier(value, "BaseTable Column ID")
    }
}

/**
 * 表示工作区 DatabaseDefinition 的稳定业务标识。
 */
@Serializable
@JvmInline
value class DatabaseId(
    /** 数据集的稳定标识文本。 */
    val value: String,
) {
    init {
        requireValidOpaqueIdentifier(value, "Database ID")
    }
}

/**
 * 表示工作区 ViewDefinition 的稳定业务标识。
 */
@Serializable
@JvmInline
value class ViewId(
    /** 视图的稳定标识文本。 */
    val value: String,
) {
    init {
        requireValidOpaqueIdentifier(value, "View ID")
    }
}

/**
 * 表示超级标签的稳定业务标识。
 */
@Serializable
@JvmInline
value class TagId(
    /** 标签的稳定标识文本。 */
    val value: String,
) {
    init {
        requireValidOpaqueIdentifier(value, "标签 ID")
    }
}

/**
 * 表示属性定义的稳定业务标识。
 */
@Serializable
@JvmInline
value class PropertyId(
    /** 属性定义的稳定标识文本。 */
    val value: String,
) {
    init {
        requireValidOpaqueIdentifier(value, "属性 ID")
    }
}

/**
 * 表示行内加注的稳定业务标识。
 */
@Serializable
@JvmInline
value class AnnotationId(
    /** 加注的稳定标识文本。 */
    val value: String,
) {
    init {
        requireValidOpaqueIdentifier(value, "加注 ID")
    }
}

/**
 * 表示 Change DAG 中不可变变更节点的内容标识。
 */
@Serializable
@JvmInline
value class ChangeId(
    /** 变更节点的内容标识文本。 */
    val value: String,
) {
    init {
        requireValidOpaqueIdentifier(value, "Change ID")
    }
}

/**
 * 表示创建设备或 Agent 的稳定参与者标识。
 */
@Serializable
@JvmInline
value class ActorId(
    /** 参与者的稳定标识文本。 */
    val value: String,
) {
    init {
        requireValidOpaqueIdentifier(value, "Actor ID")
    }
}

/**
 * 表示加密附件的稳定且不透明的业务标识。
 */
@Serializable
@JvmInline
value class BlobId(
    /** 附件的稳定标识文本。 */
    val value: String,
) {
    init {
        requireValidOpaqueIdentifier(value, "Blob ID")
    }
}

/**
 * 表示一次跨对象原子业务提交的稳定标识。
 */
@Serializable
@JvmInline
value class TransactionId(
    /** 事务的稳定标识文本。 */
    val value: String,
) {
    init {
        requireValidOpaqueIdentifier(value, "Transaction ID")
    }
}

/**
 * 表示工作区的稳定不透明标识。
 */
@Serializable
@JvmInline
value class WorkspaceId(
    /** 工作区标识文本。 */
    val value: String,
) {
    init {
        requireValidOpaqueIdentifier(value, "Workspace ID")
    }
}

/**
 * 表示工作区密钥版本的稳定标识。
 */
@Serializable
@JvmInline
value class KeyId(
    /** 密钥版本标识文本。 */
    val value: String,
) {
    init {
        requireValidOpaqueIdentifier(value, "Key ID")
    }
}

/**
 * 验证可用于协议身份和文件名的 URL-safe 不透明标识。
 *
 * @param value 待验证标识文本。
 * @param label 异常中使用的非敏感字段名称。
 */
private fun requireValidOpaqueIdentifier(
    value: String,
    label: String,
) {
    require(OPAQUE_IDENTIFIER_PATTERN.matches(value)) {
        "$label 必须是 1 到 128 位 URL-safe 不透明标识"
    }
}

/** 允许进入工作区文件名的稳定标识格式。 */
private val OPAQUE_IDENTIFIER_PATTERN: Regex = Regex("[A-Za-z0-9_-]{1,128}")
