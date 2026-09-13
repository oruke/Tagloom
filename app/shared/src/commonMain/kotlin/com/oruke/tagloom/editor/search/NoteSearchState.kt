package com.oruke.tagloom.editor.search

import androidx.compose.runtime.*
import com.oruke.tagloom.editor.TagloomEditorState
import com.oruke.tagloom.editor.EditorSelection
import com.oruke.tagloom.editor.editableRichText
import com.oruke.tagloom.editor.selectTab
import com.oruke.tagloom.core.model.CodeBlockContent

/** 单篇笔记的查找会话；派生匹配随草稿变化重算，所有动作均不写笔记。 */
internal class NoteSearchState(
    /** 提供当前草稿与仅在内存中生效的页签选择。 */
    private val editor: TagloomEditorState,
) {
    /** 是否显示当前笔记的查找栏。 */
    var isOpen by mutableStateOf(false)
        private set

    /** 用户输入的原始查找词，不持久化。 */
    var query by mutableStateOf("")
        private set

    /** 当前会话是否区分大小写。 */
    var matchCase by mutableStateOf(false)
        private set

    /** 显式跳转序号，使单结果循环与重复打开也能重新定位。 */
    var navigationVersion by mutableIntStateOf(0)
        private set

    /** 显式打开动作的序号，重新按快捷键时聚焦并全选查找词。 */
    var focusVersion by mutableIntStateOf(0)
        private set

    /** 根列表完成导航的序号，用于随后定位字段内的字符。 */
    var revealedVersion by mutableIntStateOf(-1)

    /** 最近聚焦的真实文本字段，用于关闭后接续编辑。 */
    var returnFocusTarget by mutableStateOf<NoteSearchTarget?>(null)
        private set

    /** 当前仍持有输入焦点的正文字段，避免查找框重复聚焦覆盖返回选区。 */
    private var focusedTarget by mutableStateOf<NoteSearchTarget?>(null)

    /** 进入查找前的正文选区；避免输入框失焦折叠选区或切换页签清空选区。 */
    var returnSelection by mutableStateOf<EditorSelection?>(null)
        private set

    /** 关闭查找时发出的焦点恢复请求。 */
    var focusReturnVersion by mutableIntStateOf(0)
        private set

    /** 原输入字段已滚入根列表后可执行焦点恢复的请求序号。 */
    var revealedFocusVersion by mutableIntStateOf(-1)

    /** 从当前草稿重新解析焦点返回路径；字段已删除时保持宿主回退焦点。 */
    val returnText: NoteSearchText?
        get() = returnFocusTarget?.let { target ->
            editor.selectedDocument?.note?.let(NoteSearchIndex::texts)?.firstOrNull { it.target == target }
        }

    /** 优先保留的稳定匹配；编辑使其失效后回退到邻近序号。 */
    private var anchor by mutableStateOf<NoteSearchMatch?>(null)

    /** 匹配失效时使用的上一次显示序号。 */
    private var ordinal by mutableIntStateOf(0)

    /** 仅在查找打开时从当前草稿派生文本目录。 */
    val texts by derivedStateOf {
        if (isOpen) editor.selectedDocument?.note?.let(NoteSearchIndex::texts).orEmpty() else emptyList()
    }

    /** 与当前关键词和草稿一致的全部匹配。 */
    val matches by derivedStateOf { NoteSearchIndex.find(texts, query, matchCase) }

    /** 当前结果位置；没有匹配时为 -1。 */
    val activeIndex: Int get() = matches.indexOf(anchor).takeIf { it >= 0 } ?: ordinal.coerceAtMost(matches.lastIndex)

    /** 当前高亮及导航目标。 */
    val active: NoteSearchMatch? get() = matches.getOrNull(activeIndex)

    /** 当前匹配对应的原文字段和导航路径。 */
    val activeText: NoteSearchText? get() = texts.firstOrNull { it.target == active?.target }

    /** 打开或聚焦查找栏；首次打开采用正文选区作为关键词，仅从真实编辑字段重入时更新返回选区。 */
    fun open() {
        // 页签导航会清空编辑器选区，查找框内重复 Ctrl/Cmd+F 必须保留进入查找前的选区。
        if (!isOpen || focusedTarget != null) returnSelection = editor.selection
        if (!isOpen) {
            val selection = editor.selection
            val block = editor.selectedDocument?.note?.blocks?.firstOrNull { it.id == selection.blockId }
            val text = (block?.content as? CodeBlockContent)?.text ?: block?.editableRichText()?.plainText.orEmpty()
            if (selection.isNotEmpty) {
                query = text.substring(
                    minOf(selection.start, selection.end).coerceIn(0, text.length),
                    maxOf(selection.start, selection.end).coerceIn(0, text.length)
                )
            }
            // 保留正文选区以便输入框被列表回收后重建；查找打开时画布已主动隐藏格式工具栏。
        }
        isOpen = true
        navigationVersion++
        focusVersion++
    }

    /** 关闭并清除查找词、高亮及定位，不改变文档与编辑选区。 */
    fun close() {
        isOpen = false
        query = ""
        anchor = null
        ordinal = 0
        focusReturnVersion++
    }

    /**
     * 跟踪真实输入焦点，区分从编辑字段进入查找与查找框自身重复聚焦。
     * @param target 焦点发生变化的可编辑文本字段。
     * @param focused 字段当前是否持有输入焦点；失焦不清除已经保存的返回字段。
     */
    fun updateTextFocus(target: NoteSearchTarget, focused: Boolean) {
        if (focused) {
            focusedTarget = target
            returnFocusTarget = target
        } else if (focusedTarget == target) {
            focusedTarget = null
        }
    }

    /** 在原页签已恢复后还原正文选区，新建的输入框会从该会话状态读取初始偏移。 */
    fun restoreReturnSelection() {
        val selection = returnSelection ?: return
        val blockId = selection.blockId ?: return
        if (returnFocusTarget?.blockId != blockId) return
        val text = returnText?.text ?: return
        editor.updateSelection(
            blockId,
            selection.start.coerceIn(0, text.length),
            selection.end.coerceIn(0, text.length)
        )
    }

    /**
     * 更新关键词并从第一处匹配开始。
     * @param value 用户输入的完整字面量。
     */
    fun updateQuery(value: String) {
        // 查询框的光标或选区变化也会触发输入回调，同词不能重置当前结果和正文视口。
        if (query == value) return
        query = value
        anchor = null
        ordinal = 0
        navigationVersion++
    }

    /** 切换大小写规则并重新从第一处匹配定位。 */
    fun toggleCase() {
        matchCase = !matchCase
        anchor = null
        ordinal = 0
        navigationVersion++
    }

    /**
     * 循环前往相邻匹配，空结果时保持无目标状态。
     * @param backwards 为 true 时前往上一处，否则前往下一处。
     */
    fun move(backwards: Boolean = false) {
        if (matches.isEmpty()) return
        ordinal = (activeIndex + matches.size + if (backwards) -1 else 1) % matches.size
        anchor = matches[ordinal]
        navigationVersion++
    }

    /** 打开当前匹配的所有祖先页签，全部使用既有会话选择动作。 */
    fun revealTabs() {
        activeText?.tabs?.forEach { editor.selectTab(it.containerId, it.tabId) }
    }
}
