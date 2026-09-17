package com.oruke.tagloom.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.geometry.Rect
import com.oruke.tagloom.core.model.Block
import com.oruke.tagloom.core.model.BlockId
import com.oruke.tagloom.editor.TagloomEditorState
import com.oruke.tagloom.editor.reorderBlock

/** 一行未施加拖动变换时的几何信息；容器用于避免页签内容跟随父块重复平移。 */
internal data class BlockDragLayout(
    /** 对应的已确认或正在编辑的 Block。 */
    val block: Block,
    /** 所属视觉容器，顶层文档为空。 */
    val containerId: BlockId?,
    /** 行的完整布局范围，包含其上下间距。 */
    val bounds: Rect,
)

/** 指定落点与显示插入线的行；向后插入时线位于目标子树之后。 */
internal data class BlockDropTarget(
    /** 与被拖动 Block 同级的锚点。 */
    val anchorId: BlockId,
    /** 是否插入锚点之后。 */
    val after: Boolean,
    /** 显示落点指示线的可见行。 */
    val indicatorId: BlockId,
)

/** 管理一篇笔记的临时拖动、落点和松手动画；只有确认落点才调用编辑器写入入口。 */
internal class EditorBlockDragState(
    /** 负责文档规则、撤销与自动保存的既有编辑器。 */
    private val editor: TagloomEditorState,
) {
    /** 已排版行，记录变换之前的坐标，避免预览位移反过来影响命中。 */
    private val layouts = mutableStateMapOf<BlockId, BlockDragLayout>()

    /** 父边仅随文档变更更新，指针移动时复用以避免反复扫描完整笔记。 */
    private val parents by derivedStateOf {
        editor.selectedDocument?.note?.blocks.orEmpty().associate { it.id to it.parentId }
    }

    /** 正在拖动的根 Block。 */
    var activeId: BlockId? by mutableStateOf(null)
        private set

    /** 指针在编辑窗口中的位置，用于边缘自动滚动。 */
    var pointerY: Float by mutableFloatStateOf(0f)
        private set

    /** 当前拖动所属视觉容器。 */
    private var containerId: BlockId? = null

    /** 拖动起点到源行顶部的距离，保持按下位置紧跟指针。 */
    private var grabOffset = 0f

    /** 松手前每行的屏幕位置，用于在新布局中连续落位。 */
    var releasedPositions: Map<BlockId, Float> by mutableStateOf(emptyMap())
        private set

    /** 本轮被抬起的行，子树共享一次位移。 */
    var liftedIds: Set<BlockId> by mutableStateOf(emptySet())
        private set

    /** 每次松手或取消开始一个独立且可中断的动画。 */
    var releaseVersion: Int by mutableIntStateOf(0)
        private set

    /** 成功松手的源块；落在屏幕边缘时仅将此块滚入视口，取消不改变滚动位置。 */
    var releasedId: BlockId? by mutableStateOf(null)
        private set

    /**
     * 更新原始排版位置；滚动时仍以同一窗口坐标判定落点。
     * @param layout 当前行的布局信息。
     */
    fun register(layout: BlockDragLayout) {
        layouts[layout.block.id] = layout
    }

    /**
     * 移除已离开组合的行，避免虚拟列表使用过期落点。
     * @param id 已回收行的 Block ID。
     */
    fun unregister(id: BlockId) {
        layouts.remove(id)
    }

    /**
     * 越过指针拖动阈值后进入预览；不会先写入笔记或清除文字选区。
     * @param id 被按住手柄的 Block。
     * @param windowY 指针在窗口中的纵坐标。
     */
    fun start(id: BlockId, windowY: Float) {
        val source = layouts[id] ?: return
        releasedPositions = emptyMap()
        releasedId = null
        activeId = id
        containerId = source.containerId
        grabOffset = windowY - source.bounds.top
        pointerY = windowY
        liftedIds = layouts.values.filter { it.containerId == containerId && belongsTo(it.block.id, id) }
            .map { it.block.id }.toSet()
    }

    /**
     * 累计真实指针位移，源行随指针移动而不按固定距离跳换。
     * @param deltaY 当前事件在纵轴上的位移。
     */
    fun dragBy(deltaY: Float) {
        if (activeId != null) pointerY += deltaY
    }

    /**
     * 返回拖动时一行的预览平移。
     * @param id 被绘制的 Block。
     * @return 非源子树返回零，源子树保持同一位移。
     */
    fun translation(id: BlockId): Float {
        val source = activeId?.let(layouts::get) ?: return 0f
        return if (id in liftedIds) pointerY - grabOffset - source.bounds.top else 0f
    }

    /**
     * 返回松手后相对新布局的补偿位移，使重排不产生瞬间跳跃。
     * @param id 当前 Block。
     * @param progress 从零到一的落位进度。
     * @return 当前帧仍需保留的位移。
     */
    fun releaseTranslation(id: BlockId, progress: Float): Float {
        val previous = releasedPositions[id] ?: return 0f
        val current = layouts[id]?.bounds?.top ?: return 0f
        return (previous - current) * (1f - progress)
    }

    /** 当前落点只允许同级重排，源位置附近和无有效锚点时为空。 */
    val target: BlockDropTarget?
        get() {
            val source = activeId?.let(layouts::get) ?: return null
            val siblings = layouts.values.filter {
                it.containerId == containerId && it.block.parentId == source.block.parentId
            }.sortedBy { it.bounds.top }
            val sourceIndex = siblings.indexOfFirst { it.block.id == source.block.id }
            val others = siblings.filterNot { it.block.id == source.block.id }
            val insertion = others.count { pointerY > groupBounds(it).center.y }
            if (insertion == sourceIndex || others.isEmpty()) return null
            val after = insertion > sourceIndex
            val anchor = others[if (after) insertion - 1 else insertion]
            val indicator = if (after) layouts.values.filter {
                it.containerId == containerId && belongsTo(it.block.id, anchor.block.id)
            }.maxByOrNull { it.bounds.bottom }?.block?.id ?: anchor.block.id else anchor.block.id
            return BlockDropTarget(anchor.block.id, after, indicator)
        }

    /** 当前插入线的未变换窗口坐标，统一在最上层绘制以免被拖动预览遮住。 */
    val indicatorBounds: Rect? get() = target?.indicatorId?.let { layouts[it]?.bounds }

    /**
     * 记录旧视觉位置，再一次性确认顺序或回到原位。
     * @param cancel 是否取消；取消绝不修改文档。
     */
    fun finish(cancel: Boolean = false) {
        val id = activeId ?: return
        val destination = if (cancel) null else target
        releasedPositions = layouts.values.filter { it.containerId == containerId }
            .associate { it.block.id to it.bounds.top + translation(it.block.id) }
        activeId = null
        releasedId = id.takeIf { destination != null }
        releaseVersion += 1
        if (destination != null) editor.reorderBlock(id, destination.anchorId, destination.after)
    }

    /**
     * 只清理已完成的那轮动画，后来的拖动不受旧协程影响。
     * @param version 当前动画创建时的版本。
     */
    fun completeRelease(version: Int) {
        if (releaseVersion == version && activeId == null) {
            releasedPositions = emptyMap()
            liftedIds = emptySet()
            releasedId = null
        }
    }

    /**
     * 计算目标在当前视觉容器中的完整子树范围。
     * @param row 同级锚点行。
     * @return 包含可见后代的范围。
     */
    private fun groupBounds(row: BlockDragLayout): Rect {
        val bottom = layouts.values.filter { it.containerId == containerId && belongsTo(it.block.id, row.block.id) }
            .maxOfOrNull { it.bounds.bottom } ?: row.bounds.bottom
        return row.bounds.copy(bottom = bottom)
    }

    /**
     * 从当前父边识别子树，防御损坏循环并保留折叠页签的领域结构。
     * @param id 待判断的 Block。
     * @param ancestor 子树根。
     * @return 自身或后代为 true。
     */
    private fun belongsTo(id: BlockId, ancestor: BlockId): Boolean {
        val seen = mutableSetOf<BlockId>()
        var current: BlockId? = id
        while (current != null && seen.add(current)) {
            if (current == ancestor) return true
            current = parents[current]
        }
        return false
    }
}

/** 当前笔记的拖动会话；独立预览可不装配此能力。 */
internal val LocalEditorBlockDrag = staticCompositionLocalOf<EditorBlockDragState?> { null }

/** 最近的页签视觉容器，防止父容器和子块重复位移。 */
internal val LocalBlockDragContainer = staticCompositionLocalOf<BlockId?> { null }

/** 松手动画的共享进度，所有移位行在同一帧开始和结束。 */
internal val LocalBlockReleaseProgress = staticCompositionLocalOf { 1f }
