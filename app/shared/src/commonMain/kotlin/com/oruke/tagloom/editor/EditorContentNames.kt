package com.oruke.tagloom.editor

/**
 * 保存当前界面语言下新内容的命名模板，不修改已经创建或由用户编辑的内容。
 *
 * 未装配 UI 的纯编辑环境使用空标题、解析占位标识和数字序号，不在业务层选择自然语言。
 */
data class EditorContentNames(
    /** 新笔记的初始标题。 */
    val untitledNote: String = "",
    /** 无一级标题的 Markdown 导入名称。 */
    val importedNote: String = "imported-note",
    /** 新增页签的初始标题。 */
    val untitledTab: String = "",
    /** 表格首列名称。 */
    val firstColumn: String = "",
    /** 表格第二列名称。 */
    val secondColumn: String = "",
    /** 后续列的单参数序号模板，使用资源占位符 %1$d。 */
    val columnPattern: String = "%1\$d",
    /** 初始页签的单参数序号模板，使用资源占位符 %1$d。 */
    val tabPattern: String = "%1\$d",
    /** 复制内容的单参数名称模板，使用资源占位符 %1$s。 */
    val copyPattern: String = "%1\$s",
    /** 新数据集的初始名称。 */
    val untitledDatabase: String = "",
    /** 新数据集初始表格视图的名称，纯内存调用以序号作为占位。 */
    val tableViewName: String = "1",
    /** 新视图的单参数序号模板，使用资源占位符 %1$d。 */
    val viewPattern: String = "%1\$d",
) {
    /**
     * 生成新列名称；前两列使用名称和内容，其余列使用当前语言的完整模板。
     * @param ordinal 从 1 开始的列序号。
     * @return 可以直接写入新列的标题。
     */
    fun columnName(ordinal: Int): String = when (ordinal) {
        1 -> firstColumn.takeIf(String::isNotEmpty)
        2 -> secondColumn.takeIf(String::isNotEmpty)
        else -> null
    } ?: columnPattern.replace("%1\$d", ordinal.toString())

    /**
     * 生成初始页签标题。
     * @param ordinal 从 1 开始的页签序号。
     * @return 按模板词序生成的标题。
     */
    fun tabName(ordinal: Int): String = tabPattern.replace("%1\$d", ordinal.toString())

    /**
     * 生成新视图名称。
     * @param ordinal 从 1 开始的视图序号。
     * @return 按当前语言模板生成的名称。
     */
    fun viewName(ordinal: Int): String = viewPattern.replace("%1\$d", ordinal.toString())

    /**
     * 生成副本标题，原始名称作为整体插入，不再次解析其中的占位符。
     * @param original 用户已有的标题。
     * @return 当前语言的副本标题。
     */
    fun copyName(original: String): String = copyPattern.replace("%1\$s", original)
}
