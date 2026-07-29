package com.oruke.tagloom.core.domain.property

import com.oruke.tagloom.core.domain.tag.TagResolution
import com.oruke.tagloom.core.domain.tag.TagResolver
import com.oruke.tagloom.core.model.DefaultValuePolicy
import com.oruke.tagloom.core.model.ManualPropertyBinding
import com.oruke.tagloom.core.model.Note
import com.oruke.tagloom.core.model.NotePropertyValue
import com.oruke.tagloom.core.model.PropertyDefinition
import com.oruke.tagloom.core.model.PropertyId
import com.oruke.tagloom.core.model.PropertyStyle
import com.oruke.tagloom.core.model.PropertyTypes
import com.oruke.tagloom.core.model.PropertyValueSource
import com.oruke.tagloom.core.model.TagDefinition
import com.oruke.tagloom.core.model.TagId
import com.oruke.tagloom.core.model.TagPropertyBinding
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.serialization.json.JsonPrimitive

/**
 * 验证属性继承、同级冲突、笔记覆盖和默认值策略。
 */
class EffectivePropertyResolverTest {
    /** 根 Note 标签。 */
    private val rootId = TagId("note")

    /** 测试属性。 */
    private val statusId = PropertyId("status")

    /** 被测属性解析器。 */
    private val resolver = EffectivePropertyResolver()

    /**
     * 验证子标签绑定覆盖父标签，并把 snapshot 默认值标记为待写入。
     */
    @Test
    fun childBindingOverridesParentAndRequestsSnapshot() {
        val projectId = TagId("project")
        val softwareId = TagId("software")
        val parentBinding = binding(default = "planned", policy = DefaultValuePolicy.DYNAMIC)
        val childBinding = binding(default = "active", policy = DefaultValuePolicy.SNAPSHOT)
        val tags = mapOf(
            rootId to tag(rootId),
            projectId to tag(projectId, rootId, bindings = listOf(parentBinding)),
            softwareId to tag(softwareId, projectId, bindings = listOf(childBinding)),
        )
        val note = Note(title = "示例", directTagIds = listOf(softwareId))

        val result = resolver.resolve(note, resolveTags(note, tags), tags, definitions())
        val property = result.properties.single()

        assertEquals(childBinding, property.binding)
        assertEquals(softwareId, property.sourceTagId)
        assertEquals(JsonPrimitive("active"), property.value)
        assertEquals(EffectivePropertyValueSource.SNAPSHOT_PENDING, property.valueSource)
        assertTrue(property.requiresSnapshot)
        assertFalse(property.conflicted)
    }

    /**
     * 验证多个同级标签的不一致绑定会显式返回冲突。
     */
    @Test
    fun sameDepthBindingsReturnConflict() {
        val alphaId = TagId("alpha")
        val betaId = TagId("beta")
        val tags = mapOf(
            rootId to tag(rootId),
            alphaId to tag(
                alphaId,
                rootId,
                bindings = listOf(binding("alpha", DefaultValuePolicy.DYNAMIC)),
            ),
            betaId to tag(
                betaId,
                rootId,
                bindings = listOf(binding("beta", DefaultValuePolicy.DYNAMIC)),
            ),
        )
        val note = Note(title = "示例", directTagIds = listOf(betaId, alphaId))

        val result = resolver.resolve(note, resolveTags(note, tags), tags, definitions())

        assertEquals(1, result.conflicts.size)
        assertEquals(listOf(alphaId, betaId), result.conflicts.single().sourceTagIds)
        assertTrue(result.properties.single().conflicted)
        assertEquals(alphaId, result.properties.single().sourceTagId)
    }

    /**
     * 验证笔记手动绑定可以作为最高优先级显式解决标签绑定冲突。
     */
    @Test
    fun manualBindingOverridesTagConflict() {
        val alphaId = TagId("alpha")
        val betaId = TagId("beta")
        val tags = mapOf(
            rootId to tag(rootId),
            alphaId to tag(alphaId, rootId, bindings = listOf(binding("alpha", DefaultValuePolicy.DYNAMIC))),
            betaId to tag(betaId, rootId, bindings = listOf(binding("beta", DefaultValuePolicy.DYNAMIC))),
        )
        val note = Note(
            title = "示例",
            directTagIds = listOf(alphaId, betaId),
            manualPropertyBindings = listOf(
                ManualPropertyBinding(
                    propertyId = statusId,
                    required = true,
                    styleOverride = PropertyStyle(display = "pill"),
                ),
            ),
        )

        val result = resolver.resolve(note, resolveTags(note, tags), tags, definitions())
        val property = result.properties.single()

        assertEquals(emptyList(), result.conflicts)
        assertEquals(null, property.sourceTagId)
        assertTrue(property.binding?.required == true)
        assertEquals("pill", property.style.display)
    }

    /**
     * 验证笔记实际值优先于标签默认值且不再请求快照。
     */
    @Test
    fun noteValueHasHighestValuePriority() {
        val projectId = TagId("project")
        val tags = mapOf(
            rootId to tag(rootId),
            projectId to tag(
                projectId,
                rootId,
                bindings = listOf(binding("planned", DefaultValuePolicy.SNAPSHOT)),
            ),
        )
        val note = Note(
            title = "示例",
            directTagIds = listOf(projectId),
            propertyValues = mapOf(
                statusId to NotePropertyValue(
                    value = JsonPrimitive("done"),
                    source = PropertyValueSource.USER,
                    overridden = true,
                    updatedAt = "2026-07-13T00:00:00Z",
                ),
            ),
        )

        val result = resolver.resolve(note, resolveTags(note, tags), tags, definitions())
        val property = result.properties.single()

        assertEquals(JsonPrimitive("done"), property.value)
        assertEquals(EffectivePropertyValueSource.NOTE_VALUE, property.valueSource)
        assertFalse(property.requiresSnapshot)
    }

    /**
     * 解析测试笔记的有效标签。
     *
     * @param note 测试笔记。
     * @param tags 工作区标签。
     * @return 成功的标签解析结果。
     */
    private fun resolveTags(
        note: Note,
        tags: Map<TagId, TagDefinition>,
    ): TagResolution.Success = assertIs(
        TagResolver(rootId).resolve(note.directTagIds, tags),
    )

    /**
     * 创建只包含测试所需字段的标签。
     *
     * @param id 标签 ID。
     * @param parent 父标签；根标签可以为空。
     * @param bindings 属性绑定。
     * @return 测试标签定义。
     */
    private fun tag(
        id: TagId,
        parent: TagId? = null,
        bindings: List<TagPropertyBinding> = emptyList(),
    ): TagDefinition = TagDefinition(
        id = id,
        name = id.value,
        parentTagIds = listOfNotNull(parent),
        propertyBindings = bindings,
    )

    /**
     * 创建状态属性绑定。
     *
     * @param default 默认选项 ID。
     * @param policy 默认值策略。
     * @return 测试属性绑定。
     */
    private fun binding(
        default: String,
        policy: DefaultValuePolicy,
    ): TagPropertyBinding = TagPropertyBinding(
        propertyId = statusId,
        defaultValue = JsonPrimitive(default),
        defaultPolicy = policy,
    )

    /**
     * 创建测试属性定义集合。
     *
     * @return 只包含状态属性的定义 Map。
     */
    private fun definitions(): Map<PropertyId, PropertyDefinition> = mapOf(
        statusId to PropertyDefinition(
            id = statusId,
            name = "状态",
            type = PropertyTypes.SELECT,
            defaultStyle = PropertyStyle(display = "badge"),
        ),
    )
}
