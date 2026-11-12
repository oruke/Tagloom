package com.oruke.tagloom

import com.oruke.tagloom.core.model.*
import com.oruke.tagloom.platform.WorkspaceNote
import com.oruke.tagloom.platform.WorkspaceOpenResult

/** Windows 与 Android 现场验收共用的脱敏笔记约定，不包含部署地址或凭据。 */
internal object AndroidNasSyncFixture {
    /** Windows 创建且双方保持稳定的第一个 Block 身份。 */
    const val FIRST_BLOCK: String = "nas-shared-first"
    /** Windows 创建且双方保持稳定的第二个 Block 身份。 */
    const val SECOND_BLOCK: String = "nas-shared-second"
    /** Windows 离线修改的第一个正文块。 */
    const val WINDOWS_OFFLINE: String = "nas-windows-offline-first"
    /** Android 离线修改的第二个正文块。 */
    const val ANDROID_OFFLINE: String = "nas-android-offline-second"
    /** 同一正文块冲突中 Windows 的完整候选。 */
    const val WINDOWS_CONFLICT: String = "nas-windows-conflict"
    /** 同一正文块冲突中 Android 的完整候选，也是最终明确选择的值。 */
    const val ANDROID_CONFLICT: String = "nas-android-conflict"

    /**
     * 先核对输入确实属于本次脱敏工作区，禁止在配置错误时改动其他工作区的笔记。
     * @param state 当前真实网关返回的确认状态。
     * @param input 该阶段限定的笔记身份。
     * @param runId 当前验收身份。
     * @return 属于当前验收的稳定共享笔记。
     */
    fun shared(state: WorkspaceOpenResult, input: AndroidNasSyncInput, runId: String): WorkspaceNote {
        check(!state.readOnly) { "nas_workspace_read_only" }
        val shared = state.notes.singleOrNull { it.id.value == input.sharedNoteId }
        check(shared != null && shared.note.title == "NAS sync acceptance $runId") { "nas_fixture_identity_mismatch" }
        check(state.notes.all { it.id == shared.id || it.note.title == "nas-android-addition-$runId" }) { "nas_fixture_contains_unexpected_notes" }
        check(shared.note.blocks.map { it.id.value }.toSet() == setOf(FIRST_BLOCK, SECOND_BLOCK)) { "nas_fixture_blocks_mismatch" }
        return shared
    }

    /**
     * 按稳定 Block 身份修改一段正文，其余标题、属性和正文保留。
     * @param note 当前确认笔记。
     * @param blockId 本次允许修改的样例 Block 身份。
     * @param value 预期保存的脱敏正文。
     * @return 只包含指定正文修改的领域对象。
     */
    fun replace(note: Note, blockId: String, value: String): Note = note.copy(blocks = note.blocks.map { block ->
        if (block.id.value == blockId) block.copy(content = RichTextBlockContent(RichText(listOf(InlineRun(value))))) else block
    })

    /**
     * 验证持久化的两个正文值，不让断言把完整业务对象打印到 instrumentation 输出。
     * @param note 当前真实网关重读的共享笔记。
     * @param first 第一个 Block 的精确预期值。
     * @param second 第二个 Block 的精确预期值。
     */
    fun assertTexts(note: Note, first: String, second: String) {
        check(text(note.blocks.single { it.id.value == FIRST_BLOCK }) == first) { "nas_first_block_mismatch" }
        check(text(note.blocks.single { it.id.value == SECOND_BLOCK }) == second) { "nas_second_block_mismatch" }
    }

    /**
     * 读取样例正文，不做平台替代或写入。
     * @param block 经正式快照解密的正文 Block。
     * @return 完整富文本明文，仅在隔离测试结果中使用。
     */
    fun text(block: Block): String = (block.content as RichTextBlockContent).richText.runs.joinToString("") { it.text }

    /**
     * 生成 Android 独立新增笔记，证明上传不只更新已有对象。
     * @param runId 当前隔离验收身份。
     * @return 不含真实内容的新 Note。
     */
    fun addition(runId: String): Note = Note("nas-android-addition-$runId", emptyList(), blocks = listOf(
        Block(BlockId("nas-android-added-block"), BlockTypes.PARAGRAPH, orderKey = "a",
            content = RichTextBlockContent(RichText(listOf(InlineRun("nas-android-added-body"))))),
    ))
}
