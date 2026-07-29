package com.oruke.tagloom.core.model

/**
 * 对 RichText 的字符区间执行 Mark、Annotation 和文本替换，并保持未触及 Run 的语义。
 */
object RichTextEditor {
    /**
     * 为非空字符区间添加一个结构化 Mark。
     *
     * @param richText 当前富文本。
     * @param startInclusive 起始字符偏移，包含该字符。
     * @param endExclusive 结束字符偏移，不包含该字符。
     * @param mark 待添加样式与参数。
     * @return 完成 Run 切分、样式去重和相邻合并的新 RichText。
     */
    fun addMark(
        richText: RichText,
        startInclusive: Int,
        endExclusive: Int,
        mark: InlineMark,
    ): RichText = transformRange(richText, startInclusive, endExclusive) { run ->
        run.copy(marks = run.marks + mark)
    }

    /**
     * 从字符区间移除指定类型的全部 Mark，其他样式保持不变。
     *
     * @param richText 当前富文本。
     * @param startInclusive 起始字符偏移。
     * @param endExclusive 结束字符偏移。
     * @param markType 待移除 Mark 类型。
     * @return 完成 Run 规范化的新 RichText。
     */
    fun removeMark(
        richText: RichText,
        startInclusive: Int,
        endExclusive: Int,
        markType: String,
    ): RichText = transformRange(richText, startInclusive, endExclusive) { run ->
        run.copy(marks = run.marks.filterNot { mark -> mark.type == markType })
    }

    /**
     * 把独立 Annotation 锚定到非空字符区间。
     *
     * @param richText 当前富文本。
     * @param startInclusive 起始字符偏移。
     * @param endExclusive 结束字符偏移。
     * @param annotationId 待锚定独立加注 ID。
     * @return 只在选区 Run 上包含该 AnnotationId 的新 RichText。
     */
    fun attachAnnotation(
        richText: RichText,
        startInclusive: Int,
        endExclusive: Int,
        annotationId: AnnotationId,
    ): RichText = transformRange(richText, startInclusive, endExclusive) { run ->
        run.copy(annotationIds = run.annotationIds + annotationId)
    }

    /**
     * 根据新旧纯文本的最小差异替换输入，同时保留差异外全部 Mark 与 Annotation。
     *
     * 新插入文本继承插入点相邻 Run 的 Mark，但不自动扩展 Annotation，避免把加注锚点意外扩大。
     *
     * @param richText 编辑前富文本。
     * @param newPlainText 文本框编辑后的完整纯文本。
     * @return 保留未修改语义的规范化 RichText。
     */
    fun replacePlainTextPreservingStyles(
        richText: RichText,
        newPlainText: String,
    ): RichText {
        val oldText = richText.plainText
        if (oldText == newPlainText) {
            return RichTextNormalizer.normalize(richText)
        }
        val commonPrefix = commonPrefixLength(oldText, newPlainText)
        val commonSuffix = commonSuffixLength(oldText, newPlainText, commonPrefix)
        val oldChangedEnd = oldText.length - commonSuffix
        val newChangedEnd = newPlainText.length - commonSuffix
        val before = sliceRuns(richText, 0, commonPrefix)
        val after = sliceRuns(richText, oldChangedEnd, oldText.length)
        val insertedText = newPlainText.substring(commonPrefix, newChangedEnd)
        val inheritedMarks = marksAtInsertion(richText, commonPrefix)
        val inserted = if (insertedText.isEmpty()) {
            emptyList()
        } else {
            listOf(InlineRun(text = insertedText, marks = inheritedMarks))
        }
        return RichTextNormalizer.normalize(RichText(before + inserted + after))
    }

    /**
     * 用结构化 RichText 替换一个字符区间，同时保留区间外全部样式和加注。
     *
     * 该入口用于 Markdown 行内快捷输入：分隔符必须被真实删除，而内部文字需要一次性获得 Mark，
     * 不能先以纯文本覆盖再靠 UI 临时着色。
     *
     * @param richText 编辑前富文本。
     * @param startInclusive 被替换区间起点。
     * @param endExclusive 被替换区间终点；允许与起点相同以表示插入。
     * @param replacement 进入权威模型的结构化替换内容。
     * @return 保留区间外语义并完成规范化的新 RichText。
     */
    fun replaceRange(
        richText: RichText,
        startInclusive: Int,
        endExclusive: Int,
        replacement: RichText,
    ): RichText {
        require(startInclusive in 0..endExclusive) { "RichText 替换区间顺序无效" }
        require(endExclusive <= richText.plainText.length) { "RichText 替换区间越界" }
        val before = sliceRuns(richText, 0, startInclusive)
        val after = sliceRuns(richText, endExclusive, richText.plainText.length)
        return RichTextNormalizer.normalize(RichText(before + replacement.runs + after))
    }

    /**
     * 切分选区边界并只转换选区覆盖的 Run。
     *
     * @param richText 当前富文本。
     * @param startInclusive 起始字符偏移。
     * @param endExclusive 结束字符偏移。
     * @param transform 应用于选区切片的转换。
     * @return 规范化转换结果。
     */
    private fun transformRange(
        richText: RichText,
        startInclusive: Int,
        endExclusive: Int,
        transform: (InlineRun) -> InlineRun,
    ): RichText {
        requireValidRange(richText, startInclusive, endExclusive)
        val before = sliceRuns(richText, 0, startInclusive)
        val selected = sliceRuns(richText, startInclusive, endExclusive).map(transform)
        val after = sliceRuns(richText, endExclusive, richText.plainText.length)
        return RichTextNormalizer.normalize(RichText(before + selected + after))
    }

    /**
     * 从 RichText 中截取字符范围并保留各 Run 的语义。
     *
     * @param richText 来源富文本。
     * @param startInclusive 起始字符偏移。
     * @param endExclusive 结束字符偏移。
     * @return 与范围相交的 Run 切片。
     */
    private fun sliceRuns(
        richText: RichText,
        startInclusive: Int,
        endExclusive: Int,
    ): List<InlineRun> {
        if (startInclusive == endExclusive) {
            return emptyList()
        }
        val output = mutableListOf<InlineRun>()
        var runStart = 0
        richText.runs.forEach { run ->
            val runEnd = runStart + run.text.length
            val intersectionStart = maxOf(startInclusive, runStart)
            val intersectionEnd = minOf(endExclusive, runEnd)
            if (intersectionStart < intersectionEnd) {
                output += run.copy(
                    text = run.text.substring(intersectionStart - runStart, intersectionEnd - runStart),
                )
            }
            runStart = runEnd
        }
        return output
    }

    /**
     * 获取插入点应继续使用的文本样式，不继承 Annotation 锚点。
     *
     * @param richText 当前富文本。
     * @param offset 插入字符偏移。
     * @return 相邻 Run 的 Mark；空文档返回空列表。
     */
    private fun marksAtInsertion(
        richText: RichText,
        offset: Int,
    ): List<InlineMark> {
        var runStart = 0
        richText.runs.forEach { run ->
            val runEnd = runStart + run.text.length
            if (offset in (runStart + 1)..runEnd || (offset == 0 && runStart == 0)) {
                return run.marks
            }
            runStart = runEnd
        }
        return richText.runs.lastOrNull()?.marks.orEmpty()
    }

    /**
     * 校验选区非空且位于纯文本边界内。
     *
     * @param richText 当前富文本。
     * @param startInclusive 起始偏移。
     * @param endExclusive 结束偏移。
     */
    private fun requireValidRange(
        richText: RichText,
        startInclusive: Int,
        endExclusive: Int,
    ) {
        require(startInclusive in 0 until endExclusive) { "RichText 选区必须非空" }
        require(endExclusive <= richText.plainText.length) { "RichText 选区越界" }
    }

    /**
     * 计算两个文本从开头连续相同的字符数。
     *
     * @param first 第一个文本。
     * @param second 第二个文本。
     * @return 公共前缀长度。
     */
    private fun commonPrefixLength(
        first: String,
        second: String,
    ): Int {
        val limit = minOf(first.length, second.length)
        var index = 0
        while (index < limit && first[index] == second[index]) {
            index += 1
        }
        return index
    }

    /**
     * 在不跨过公共前缀的前提下计算公共后缀长度。
     *
     * @param first 编辑前文本。
     * @param second 编辑后文本。
     * @param commonPrefix 已确认公共前缀长度。
     * @return 公共后缀长度。
     */
    private fun commonSuffixLength(
        first: String,
        second: String,
        commonPrefix: Int,
    ): Int {
        val limit = minOf(first.length, second.length) - commonPrefix
        var length = 0
        while (
            length < limit &&
            first[first.lastIndex - length] == second[second.lastIndex - length]
        ) {
            length += 1
        }
        return length
    }
}
