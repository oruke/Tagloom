package com.oruke.tagloom.core.domain.tag

import com.oruke.tagloom.core.model.TagDefinition
import com.oruke.tagloom.core.model.TagId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * 验证超级标签继承闭包、稳定顺序和错误检测。
 */
class TagResolverTest {
    /** 根 Note 标签 ID。 */
    private val rootId = TagId("note")

    /** 被测标签解析器。 */
    private val resolver = TagResolver(rootId)

    /**
     * 验证无显式父级的普通标签会隐式继承根 Note 标签。
     */
    @Test
    fun tagWithoutParentsInheritsRoot() {
        val projectId = TagId("project")
        val result = resolver.resolve(
            directTagIds = listOf(projectId),
            tags = mapOf(
                rootId to tag(rootId),
                projectId to tag(projectId),
            ),
        )

        val success = assertIs<TagResolution.Success>(result)
        assertEquals(listOf(rootId, projectId), success.tags.map(ResolvedTag::tagId))
        assertEquals(listOf(0, 1), success.tags.map(ResolvedTag::depth))
        assertTrue(success.tags.last().direct)
    }

    /**
     * 验证菱形继承只输出一次公共祖先，并保持稳定拓扑顺序。
     */
    @Test
    fun diamondInheritanceProducesStableTopologicalOrder() {
        val leftId = TagId("left")
        val rightId = TagId("right")
        val childId = TagId("child")
        val tags = mapOf(
            rootId to tag(rootId),
                leftId to tag(leftId, listOf(rootId)),
                rightId to tag(rightId, listOf(rootId)),
                childId to tag(childId, listOf(rightId, leftId)),
        )

        val first = assertIs<TagResolution.Success>(resolver.resolve(listOf(childId), tags))
        val second = assertIs<TagResolution.Success>(resolver.resolve(listOf(childId), tags.toList().reversed().toMap()))

        assertEquals(listOf(rootId, leftId, rightId, childId), first.tags.map(ResolvedTag::tagId))
        assertEquals(first, second)
        assertEquals(2, first.tags.last().depth)
    }

    /**
     * 验证间接循环会返回包含完整闭环的显式错误。
     */
    @Test
    fun indirectCycleReturnsFailure() {
        val firstId = TagId("first")
        val secondId = TagId("second")
        val result = resolver.resolve(
            directTagIds = listOf(firstId),
            tags = mapOf(
                rootId to tag(rootId),
                firstId to tag(firstId, listOf(secondId)),
                secondId to tag(secondId, listOf(firstId)),
            ),
        )

        val failure = assertIs<TagResolution.Failure>(result)
        assertEquals(TagResolutionErrorCode.INHERITANCE_CYCLE, failure.errors.single().code)
        assertEquals(listOf(firstId, secondId, firstId), failure.errors.single().path)
    }

    /**
     * 验证缺失父标签不会被静默当作根标签处理。
     */
    @Test
    fun missingParentReturnsFailure() {
        val childId = TagId("child")
        val missingId = TagId("missing")
        val result = resolver.resolve(
            directTagIds = listOf(childId),
            tags = mapOf(
                rootId to tag(rootId),
                childId to tag(childId, listOf(missingId)),
            ),
        )

        val failure = assertIs<TagResolution.Failure>(result)
        assertEquals(TagResolutionErrorCode.MISSING_TAG, failure.errors.single().code)
        assertEquals(missingId, failure.errors.single().tagId)
    }

    /**
     * 创建测试标签定义。
     *
     * @param id 标签 ID。
     * @param parents 显式父标签。
     * @return 只包含继承关系的标签定义。
     */
    private fun tag(
        id: TagId,
        parents: List<TagId> = emptyList(),
    ): TagDefinition = TagDefinition(
        id = id,
        name = id.value,
        parentTagIds = parents,
    )
}
