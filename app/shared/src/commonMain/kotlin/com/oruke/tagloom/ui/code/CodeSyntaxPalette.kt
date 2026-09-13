package com.oruke.tagloom.ui.code

import androidx.compose.ui.graphics.Color

/** 与代码背景匹配的语法角色配色；普通标识符沿用输入框正文颜色。 */
internal data class CodeSyntaxPalette(
    /** 声明、控制流与修饰关键字。 */
    val keyword: Color,
    /** 字符串、正则与文本字面量。 */
    val string: Color,
    /** 数字、布尔值和其他常量。 */
    val literal: Color,
    /** 单行及多行注释。 */
    val comment: Color,
    /** 函数名与方法名。 */
    val function: Color,
    /** 类型与标记语言标签。 */
    val type: Color,
    /** 属性、键名与属性注解。 */
    val property: Color,
    /** 运算符和分隔符。 */
    val punctuation: Color,
    /** Diff 中新增的行。 */
    val inserted: Color,
    /** Diff 中删除的行。 */
    val deleted: Color,
) {
    /**
     * 将词法角色及其细分角色映射到编辑器颜色，不引入会改变布局的字体度量。
     * @param role 分词器输出的点分角色名称。
     * @return 已定义的语法颜色；普通文本等未覆盖角色使用基础正文颜色。
     */
    fun colorFor(role: String): Color? = when {
        role == "markup.diff.addition" -> inserted
        role == "markup.diff.deletion" -> deleted
        role == "markup.diff.context" -> null
        role.startsWith("markup.diff.") || role.startsWith("markup.heading") -> keyword
        role.startsWith("markup.code") -> string
        role.startsWith("markup.link") -> function
        role.startsWith("markup.") -> property
        else -> when (role.substringBefore('.')) {
            "keyword" -> keyword
            "string" -> string
            "number", "boolean", "constant", "escape" -> literal
            "comment" -> comment
            "function" -> function
            "type", "tag" -> type
            "property", "attribute", "annotation" -> property
            "operator", "punctuation" -> punctuation
            else -> null
        }
    }

    companion object {
        /** 浅色代码底板上的 AA 正文对比度配色。 */
        val Light = CodeSyntaxPalette(
            keyword = Color(0xFF8A315C), string = Color(0xFF26613F), literal = Color(0xFF225D91),
            comment = Color(0xFF606961), function = Color(0xFF69509A), type = Color(0xFF87501F),
            property = Color(0xFF13685E), punctuation = Color(0xFF505650),
            inserted = Color(0xFF26613F), deleted = Color(0xFFA33437),
        )

        /** 深色代码底板上的对应角色配色。 */
        val Dark = CodeSyntaxPalette(
            keyword = Color(0xFFE7A0C4), string = Color(0xFFA6C994), literal = Color(0xFF90C7EB),
            comment = Color(0xFFABB4AC), function = Color(0xFFC9B4ED), type = Color(0xFFE6BF8A),
            property = Color(0xFF8FD5C5), punctuation = Color(0xFFBBC4BE),
            inserted = Color(0xFFA6C994), deleted = Color(0xFFF0A1A1),
        )
    }
}
