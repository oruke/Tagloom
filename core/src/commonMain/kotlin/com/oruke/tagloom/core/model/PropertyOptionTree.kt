package com.oruke.tagloom.core.model

/** 共享选项目录的层级校验、路径与后代计算；值仍只保存一个稳定选项 ID。 */
object PropertyOptionTree {
    /**
     * 校验同级名称、父身份和无环约束，平铺目录维持历史的全局名称唯一规则。
     * @param options 完整选项目录。
     * @param hierarchical 是否启用层级单选。
     */
    fun validate(options: List<PropertyOption>, hierarchical: Boolean) {
        val byId = options.associateBy { it.id }
        require(byId.size == options.size) { "property-option-id-duplicate" }
        require(hierarchical || options.all { it.parentOptionId == null }) { "property-hierarchy-disabled" }
        val names = options.map { (if (hierarchical) it.parentOptionId else null) to it.label.trim().lowercase() }
        require(names.distinct().size == names.size) { "property-option-name-duplicate" }
        options.forEach { option ->
            val visited = mutableSetOf(option.id)
            var parentId = option.parentOptionId
            while (parentId != null) {
                require(visited.add(parentId)) { "property-option-cycle" }
                val parent = requireNotNull(byId[parentId]) { "property-option-parent-missing" }
                parentId = parent.parentOptionId
            }
        }
    }

    /**
     * 返回从根到当前项的稳定身份链，缺失身份返回空。
     * @param options 已校验目录。
     * @param id 当前项身份。
     * @return 根到当前项的有序选项。
     */
    fun path(options: List<PropertyOption>, id: String?): List<PropertyOption> {
        val byId = options.associateBy { it.id }
        val result = mutableListOf<PropertyOption>()
        val seen = mutableSetOf<String>()
        var current = id
        while (current != null && seen.add(current)) {
            val option = byId[current] ?: return emptyList()
            result.add(0, option)
            current = option.parentOptionId
        }
        return result
    }

    /**
     * 计算本项及后代，供父级筛选和移动时排除循环目标。
     * @param options 已校验目录。
     * @param id 子树根项。
     * @return 包含根项的稳定 ID 集合。
     */
    fun descendants(options: List<PropertyOption>, id: String): Set<String> {
        val result = mutableSetOf(id)
        var size: Int
        do {
            size = result.size
            options.filter { it.parentOptionId in result }.forEach { result.add(it.id) }
        } while (size != result.size)
        return result
    }

    /**
     * 将选项展示为可区分同名项的完整路径。
     * @param options 已校验目录。
     * @param id 当前选项。
     * @return 路径文本，空值或失效身份为空文本。
     */
    fun label(options: List<PropertyOption>, id: String?): String = path(options, id).joinToString(" / ") { it.label }
}
