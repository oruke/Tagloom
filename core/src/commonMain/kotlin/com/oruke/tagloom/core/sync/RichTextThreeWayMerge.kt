package com.oruke.tagloom.core.sync

import com.oruke.tagloom.core.model.AnnotationId
import com.oruke.tagloom.core.model.InlineMark
import com.oruke.tagloom.core.model.InlineRun
import com.oruke.tagloom.core.model.RichText
import com.oruke.tagloom.core.model.RichTextNormalizer

/** 描述 RichText 三方合并是否需要外层创建冲突记录。 */
data class RichTextMergeOutcome(
    /** 无冲突时的规范化 RichText；冲突时为空。 */
    val merged: RichText?,
    /** 两侧编辑是否重叠且无法自动解释。 */
    val conflicted: Boolean,
)

/** 对包含字符样式和加注锚点的 RichText 执行可解释三方合并。 */
object RichTextThreeWayMerge {
    /**
     * 自动合并两个相对 Base 的非重叠连续编辑。
     *
     * 每个带样式字符都作为 token，因此单词级 code、链接、颜色或加注变化也参与重叠判断。若一侧包含多个分散编辑，算法可能保守地扩大为一个区间并产生冲突，但绝不静默丢弃内容。
     *
     * @param base 共同祖先 RichText。
     * @param local 当前设备 RichText。
     * @param remote 远端设备 RichText。
     * @return 自动合并结果或显式冲突。
     */
    fun merge(
        base: RichText,
        local: RichText,
        remote: RichText,
    ): RichTextMergeOutcome {
        val normalizedBase = RichTextNormalizer.normalize(base)
        val normalizedLocal = RichTextNormalizer.normalize(local)
        val normalizedRemote = RichTextNormalizer.normalize(remote)
        if (normalizedLocal == normalizedRemote) {
            return RichTextMergeOutcome(normalizedLocal, conflicted = false)
        }
        if (normalizedLocal == normalizedBase) {
            return RichTextMergeOutcome(normalizedRemote, conflicted = false)
        }
        if (normalizedRemote == normalizedBase) {
            return RichTextMergeOutcome(normalizedLocal, conflicted = false)
        }

        val baseTokens = normalizedBase.toStyledCharacters()
        val localEdit = editFrom(baseTokens, normalizedLocal.toStyledCharacters())
        val remoteEdit = editFrom(baseTokens, normalizedRemote.toStyledCharacters())
        if (localEdit == remoteEdit) {
            return RichTextMergeOutcome(applyEdits(baseTokens, listOf(localEdit)).toRichText(), conflicted = false)
        }
        if (editsOverlap(localEdit, remoteEdit)) {
            return RichTextMergeOutcome(merged = null, conflicted = true)
        }
        return RichTextMergeOutcome(
            merged = applyEdits(baseTokens, listOf(localEdit, remoteEdit)).toRichText(),
            conflicted = false,
        )
    }

    /**
     * 把 RichText 展开为样式与加注不可分割的字符 token。
     *
     * @return 保持显示顺序的字符 token。
     */
    private fun RichText.toStyledCharacters(): List<StyledCharacter> = runs.flatMap { run ->
        run.text.map { character ->
            StyledCharacter(character, run.marks, run.annotationIds)
        }
    }

    /**
     * 通过最长共同前后缀计算一个保守的连续替换区间。
     *
     * @param base 共同祖先 token。
     * @param variant 某一侧候选 token。
     * @return 相对 Base 的连续编辑。
     */
    private fun editFrom(
        base: List<StyledCharacter>,
        variant: List<StyledCharacter>,
    ): StyledEdit {
        var prefix = 0
        while (prefix < base.size && prefix < variant.size && base[prefix] == variant[prefix]) {
            prefix += 1
        }
        var suffix = 0
        while (
            suffix < base.size - prefix &&
            suffix < variant.size - prefix &&
            base[base.lastIndex - suffix] == variant[variant.lastIndex - suffix]
        ) {
            suffix += 1
        }
        return StyledEdit(
            start = prefix,
            endExclusive = base.size - suffix,
            replacement = variant.subList(prefix, variant.size - suffix),
        )
    }

    /**
     * 判断两个 Base 区间是否语义重叠。
     *
     * @param left 当前设备编辑。
     * @param right 远端编辑。
     * @return 修改同一 Base 字符或在同一点插入不同内容时为真。
     */
    private fun editsOverlap(
        left: StyledEdit,
        right: StyledEdit,
    ): Boolean {
        if (left.start == left.endExclusive && right.start == right.endExclusive && left.start == right.start) {
            return true
        }
        return left.start < right.endExclusive && right.start < left.endExclusive
    }

    /**
     * 从后向前应用互不重叠的编辑，避免前一个编辑改变后续 Base 偏移。
     *
     * @param base 共同祖先 token。
     * @param edits 已确认互不重叠的编辑。
     * @return 合并后的 token。
     */
    private fun applyEdits(
        base: List<StyledCharacter>,
        edits: List<StyledEdit>,
    ): List<StyledCharacter> {
        val result = base.toMutableList()
        edits.sortedWith(compareByDescending<StyledEdit> { it.start }.thenByDescending { it.endExclusive })
            .forEach { edit ->
                result.subList(edit.start, edit.endExclusive).clear()
                result.addAll(edit.start, edit.replacement)
            }
        return result
    }

    /**
     * 合并相邻同样式字符并恢复规范化 RichText。
     *
     * @return 规范化 RichText。
     */
    private fun List<StyledCharacter>.toRichText(): RichText {
        val runs = mutableListOf<InlineRun>()
        forEach { token ->
            val previous = runs.lastOrNull()
            if (previous != null && previous.marks == token.marks && previous.annotationIds == token.annotationIds) {
                runs[runs.lastIndex] = previous.copy(text = previous.text + token.character)
            } else {
                runs += InlineRun(
                    text = token.character.toString(),
                    marks = token.marks,
                    annotationIds = token.annotationIds,
                )
            }
        }
        return RichTextNormalizer.normalize(RichText(runs))
    }

    /** 表示一个带完整样式和加注集合的 UTF-16 字符。 */
    private data class StyledCharacter(
        /** 字符值。 */
        val character: Char,
        /** 应用于该字符的 Mark。 */
        val marks: List<InlineMark>,
        /** 锚定到该字符的加注。 */
        val annotationIds: List<AnnotationId>,
    )

    /** 表示相对 Base 的单个连续替换。 */
    private data class StyledEdit(
        /** Base 起始偏移。 */
        val start: Int,
        /** Base 结束开区间。 */
        val endExclusive: Int,
        /** 替换 token。 */
        val replacement: List<StyledCharacter>,
    )
}
