package com.oruke.tagloom.core.model

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

/**
 * 定义 Tagloom 已知的属性类型名称。
 */
object PropertyTypes {
    /** 单行或多行文本。 */
    const val TEXT: String = "text"

    /** 数值。 */
    const val NUMBER: String = "number"

    /** 布尔值。 */
    const val BOOLEAN: String = "boolean"

    /** 不含时区的日期。 */
    const val DATE: String = "date"

    /** UTC 或带偏移量的日期时间。 */
    const val DATETIME: String = "datetime"

    /** 单选选项。 */
    const val SELECT: String = "select"

    /** 多选选项。 */
    const val MULTI_SELECT: String = "multi-select"

    /** URL。 */
    const val URL: String = "url"

    /** 电子邮箱。 */
    const val EMAIL: String = "email"

    /** 指向其他笔记的关系。 */
    const val RELATION: String = "relation"

    /** 指向加密附件的文件属性。 */
    const val FILE: String = "file"

    /** 由表达式计算的属性。 */
    const val FORMULA: String = "formula"
}

/**
 * 描述 select 或 multi-select 属性的一个稳定选项。
 */
@Serializable
data class PropertyOption(
    /** 选项稳定 ID；修改显示名称时保持不变。 */
    val id: String,
    /** 面向用户的选项名称。 */
    val label: String,
    /** 选项使用的语义色 token。 */
    val colorToken: String? = null,
    /** 层级单选的父项稳定 ID；根项和平铺选项为空。 */
    val parentOptionId: String? = null,
) {
    init {
        require(id.isNotBlank()) { "属性选项 ID 不能为空" }
        require(label.isNotBlank()) { "属性选项名称不能为空" }
    }
}

/**
 * 定义 select 类属性是否允许在赋值时扩充选项目录。
 */
@Serializable
enum class PropertyOptionInputMode {
    /** 只能选择属性定义中已经确认的固定选项。 */
    FIXED,

    /** 可以搜索既有选项，也可以创建并持久化新的稳定选项。 */
    FREE,
}

/**
 * 描述属性在不同视图中的表现方式。
 *
 * 样式不改变属性值语义，颜色必须使用主题可解释的语义 token。
 */
@Serializable
data class PropertyStyle(
    /** 属性图标名称。 */
    val icon: String? = null,
    /** 属性使用的语义色 token。 */
    val colorToken: String? = null,
    /** 展示组件类型，例如 badge、pill 或 text。 */
    val display: String? = null,
    /** 属性相对排序值。 */
    val order: Int? = null,
    /** 属性是否可见。 */
    val visible: Boolean? = null,
    /** 属性是否显示在卡片中。 */
    val showInCard: Boolean? = null,
    /** 属性是否显示在列表中。 */
    val showInList: Boolean? = null,
    /** 属性面板是否默认折叠。 */
    val collapsed: Boolean? = null,
    /** 日期格式标识。 */
    val dateFormat: String? = null,
    /** 数字格式标识。 */
    val numberFormat: String? = null,
    /** 选项展示方式。 */
    val optionStyle: String? = null,
    /** 关系属性展示方式。 */
    val relationDisplay: String? = null,
)

/**
 * 描述工作区级属性 schema。
 */
@Serializable
data class PropertyDefinition(
    /** 属性稳定业务标识。 */
    val id: PropertyId,
    /** 面向用户的属性名称。 */
    val name: String,
    /** 属性类型；未知类型必须原样保留。 */
    val type: String,
    /** select 类属性的稳定选项列表。 */
    val options: List<PropertyOption> = emptyList(),
    /** select 类属性采用固定选项还是允许输入创建。 */
    val optionInputMode: PropertyOptionInputMode = PropertyOptionInputMode.FIXED,
    /** 属性默认展示样式。 */
    val defaultStyle: PropertyStyle = PropertyStyle(),
    /** formula 属性使用的表达式；其他类型为空。 */
    val formulaExpression: String? = null,
    /** 单选目录是否按父子关系展示；旧定义保持平铺。 */
    val hierarchical: Boolean = false,
) {
    init {
        require(name.isNotBlank()) { "属性名称不能为空" }
        require(type.isNotBlank()) { "属性类型不能为空" }
        require(options.map(PropertyOption::id).distinct().size == options.size) {
            "属性选项 ID 不能重复"
        }
        PropertyOptionTree.validate(options, hierarchical)
        require(!hierarchical || type == PropertyTypes.SELECT) { "只有单选属性支持层级目录" }
        require(type == PropertyTypes.SELECT || type == PropertyTypes.MULTI_SELECT || optionInputMode == PropertyOptionInputMode.FIXED) {
            "只有单选和多选属性可以启用自由选项"
        }
    }
}

/**
 * 描述标签默认值如何影响关联笔记。
 */
@Serializable
enum class DefaultValuePolicy {
    /** 未覆盖笔记实时跟随标签默认值。 */
    DYNAMIC,

    /** 首次关联标签时把默认值写入笔记快照。 */
    SNAPSHOT,

    /** 只提供属性定义，不产生默认值。 */
    NONE,
}

/**
 * 描述标签如何引入、约束和展示某个属性。
 */
@Serializable
data class TagPropertyBinding(
    /** 被绑定的工作区属性。 */
    val propertyId: PropertyId,
    /** 属性在该标签下是否必填。 */
    val required: Boolean = false,
    /** 标签提供的默认值。 */
    val defaultValue: JsonElement? = null,
    /** 默认值的动态、快照或无默认策略。 */
    val defaultPolicy: DefaultValuePolicy = DefaultValuePolicy.NONE,
    /** 属性在该标签下是否只读。 */
    val readonly: Boolean = false,
    /** 子标签是否继承此绑定。 */
    val inheritable: Boolean = true,
    /** 属性在该标签内的排序值。 */
    val order: Int = 0,
    /** 标签级展示样式覆盖。 */
    val styleOverride: PropertyStyle = PropertyStyle(),
)

/**
 * 描述属性值来自用户、标签快照还是系统计算。
 */
@Serializable
enum class PropertyValueSource {
    /** 用户明确输入或修改。 */
    USER,

    /** 标签关联时写入的快照默认值。 */
    TAG_SNAPSHOT,

    /** 系统字段或自动计算结果。 */
    SYSTEM,
}

/**
 * 描述笔记实际保存的属性值及其来源。
 */
@Serializable
data class NotePropertyValue(
    /** 按属性 schema 解释的 JSON 值。 */
    val value: JsonElement,
    /** 当前值的来源。 */
    val source: PropertyValueSource,
    /** 产生标签快照值的标签；其他来源可以为空。 */
    val sourceTagId: TagId? = null,
    /** 用户是否明确覆盖了继承或默认值。 */
    val overridden: Boolean = false,
    /** 值最近更新时间，使用 UTC ISO-8601 文本。 */
    val updatedAt: String,
) {
    init {
        require(updatedAt.isNotBlank()) { "属性值更新时间不能为空" }
    }
}

/**
 * 描述笔记手动复用的工作区属性及其局部配置。
 */
@Serializable
data class ManualPropertyBinding(
    /** 手动加入笔记的属性。 */
    val propertyId: PropertyId,
    /** 当前笔记是否要求填写该属性。 */
    val required: Boolean = false,
    /** 当前笔记是否只读显示该属性。 */
    val readonly: Boolean = false,
    /** 当前笔记的样式覆盖。 */
    val styleOverride: PropertyStyle = PropertyStyle(),
)
