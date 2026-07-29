package com.oruke.tagloom.core.sync

/** 执行不产生副作用的标准 Base/Local/Remote 值合并。 */
internal object ThreeWayValueMerge {
    /**
     * 合并一个字段值，并识别两侧是否做了彼此不兼容的修改。
     *
     * @param T 字段值类型。
     * @param base 共同祖先值。
     * @param local 当前设备值。
     * @param remote 远端设备值。
     * @return 自动结果；两侧均偏离 Base 且不相等时标记冲突。
     */
    fun <T> merge(
        base: T,
        local: T,
        remote: T,
    ): ThreeWayFieldMerge<T> = when {
        local == remote -> ThreeWayFieldMerge(local, conflicted = false)
        local == base -> ThreeWayFieldMerge(remote, conflicted = false)
        remote == base -> ThreeWayFieldMerge(local, conflicted = false)
        else -> ThreeWayFieldMerge(local, conflicted = true)
    }
}

/** 表示一个三方合并字段值及其冲突状态。 */
internal data class ThreeWayFieldMerge<T>(
    /** 自动结果或仅供上层选择临时胜者的候选值。 */
    val value: T,
    /** 两侧均偏离 Base 且不相等时为真。 */
    val conflicted: Boolean,
)
