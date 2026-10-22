package com.oruke.tagloom.ui

import androidx.compose.runtime.*

/** 链接集合的临时查询状态，无值分组和取消筛选分别表示，不写入业务快照。 */
internal class LinkCollectionState {
    /** 是否显示集合页面。 */
    var visible by mutableStateOf(false)
        private set

    /** 标题、网址与描述检索词。 */
    var query by mutableStateOf("")

    /** 是否启用分组条件；关闭时包含全部分组。 */
    var filtered by mutableStateOf(false)

    /** 当前分组；筛选开启且为空表示未分组。 */
    var groupId by mutableStateOf<String?>(null)

    /** 父项默认包含全部后代，可显式切换精确本级。 */
    var includeChildren by mutableStateOf(true)

    /**
     * 从已使用分组提示打开对应集合。
     * @param id 要筛选的分组身份或未分组。
     */
    fun open(id: String?) {
        groupId = id; filtered = true; includeChildren = true; query = ""; visible = true
    }

    /** 打开全部链接，不继承上一次临时分组条件。 */
    fun showAll() {
        filtered = false; query = ""; visible = true
    }

    /** 返回笔记时关闭集合，不改变工作区数据。 */
    fun close() {
        visible = false
    }

    /** 锁定时清理临时搜索文本和选项身份。 */
    fun clear() {
        visible = false; query = ""; filtered = false; groupId = null; includeChildren = true
    }
}
