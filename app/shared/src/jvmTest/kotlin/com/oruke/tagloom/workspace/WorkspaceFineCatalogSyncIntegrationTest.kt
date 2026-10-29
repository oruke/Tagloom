package com.oruke.tagloom.workspace

import com.oruke.tagloom.core.model.Note
import com.oruke.tagloom.core.model.NotePropertyValue
import com.oruke.tagloom.core.model.PropertyDefinition
import com.oruke.tagloom.core.model.PropertyId
import com.oruke.tagloom.core.model.PropertyOption
import com.oruke.tagloom.core.model.PropertyTypes
import com.oruke.tagloom.core.model.PropertyValueSource
import com.oruke.tagloom.core.model.TagDefinition
import com.oruke.tagloom.core.model.TagId
import com.oruke.tagloom.core.model.TagPropertyBinding
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** 通过真实双设备、密文服务和重新恢复验证定义字段合并，无需恢复整对象历史。 */
class WorkspaceFineCatalogSyncIntegrationTest {
    /** 类型名称与图标、两个不同选项的离线编辑应自动组合，重开和第三台恢复后仍无冲突。 */
    @Test
    fun mergesIndependentCatalogFieldsThroughRealSyncAndPersistsAcrossRecovery() = ServerRecoveryTestEnvironment().use { env ->
        runBlocking {
            val first = env.device("fine-catalog-first")
            val key = requireNotNull(first.gateway.prepareCreation(null)).recoveryKeyText
            first.gateway.confirmCreation()
            val property = PropertyDefinition(
                PropertyId("fine-catalog-property"), "Catalog selection", PropertyTypes.SELECT,
                options = listOf(PropertyOption("option-a", "Option A"), PropertyOption("option-b", "Option B")),
            )
            val tag = TagDefinition(TagId("fine-catalog-tag"), "Catalog type",
                propertyBindings = listOf(TagPropertyBinding(property.id)), iconKey = "note")
            first.gateway.savePropertyDefinition(property)
            first.gateway.saveTagDefinition(tag)
            val note = first.gateway.saveNote(null, Note(
                title = "catalogfixture sample", directTagIds = listOf(tag.id),
                propertyValues = mapOf(property.id to NotePropertyValue(
                    JsonPrimitive("option-a"), PropertyValueSource.USER, updatedAt = first.gateway.currentUtcTimestamp(),
                )),
            ))
            first.gateway.serverRecovery.publish(env.address, env.bootstrap, key)
            val second = env.device("fine-catalog-second")
            second.gateway.serverRecovery.restore(env.address, key, null)

            // 阶段 1：两台设备已持有同一个真实祖先，分别从各自工作区执行离线目录编辑。
            first.gateway.saveTagDefinition(tag.copy(name = "Renamed catalog type"))
            first.gateway.savePropertyDefinition(property.copy(options = listOf(
                property.options[0].copy(label = "Edited option A"), property.options[1],
            )))
            second.gateway.saveTagDefinition(tag.copy(iconKey = "link"))
            second.gateway.savePropertyDefinition(property.copy(options = listOf(
                property.options[0], property.options[1].copy(label = "Edited option B"),
            )))

            // 阶段 2：使用正式 HTTP 同步入口交换密文与 Change，再通过各自网关重读已确认快照。
            first.gateway.serverRecovery.updateIfChanged()
            second.gateway.serverRecovery.updateIfChanged()
            first.gateway.serverRecovery.updateIfChanged()
            second.gateway.serverRecovery.updateIfChanged()
            val expectedTag = tag.copy(name = "Renamed catalog type", iconKey = "link")
            val expectedProperty = property.copy(options = listOf(
                property.options[0].copy(label = "Edited option A"), property.options[1].copy(label = "Edited option B"),
            ))
            for (device in listOf(first, second)) {
                val state = requireNotNull(device.gateway.reload())
                assertEquals(expectedTag, state.tagDefinitions.single { it.definition.id == tag.id }.definition)
                assertEquals(expectedProperty, state.propertyDefinitions.single { it.definition.id == property.id }.definition)
                assertEquals(note.note, state.notes.single { it.id == note.id }.note)
                assertEquals(note.id, device.gateway.search("catalogfixture").single().noteId)
                assertTrue(device.gateway.serverRecovery.conflicts().isEmpty())
            }

            // 阶段 3：关闭会话后从本地认证快照重开，并从服务器密文恢复第三台独立设备。
            first.gateway.lock()
            second.gateway.lock()
            for (device in listOf(first, second)) {
                val state = device.gateway.openRecentWorkspace()
                assertEquals(expectedTag, state.tagDefinitions.single { it.definition.id == tag.id }.definition)
                assertEquals(expectedProperty, state.propertyDefinitions.single { it.definition.id == property.id }.definition)
                assertTrue(device.gateway.serverRecovery.conflicts().isEmpty())
            }
            val recovered = env.device("fine-catalog-recovered")
            val restored = requireNotNull(recovered.gateway.serverRecovery.restore(env.address, key, null))
            assertEquals(expectedTag, restored.tagDefinitions.single { it.definition.id == tag.id }.definition)
            assertEquals(expectedProperty, restored.propertyDefinitions.single { it.definition.id == property.id }.definition)
            assertEquals(note.note, restored.notes.single { it.id == note.id }.note)
            assertTrue(recovered.gateway.serverRecovery.conflicts().isEmpty())
        }
    }
}
