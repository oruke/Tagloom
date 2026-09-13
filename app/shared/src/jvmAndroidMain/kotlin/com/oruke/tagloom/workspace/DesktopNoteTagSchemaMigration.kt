package com.oruke.tagloom.workspace

import com.oruke.tagloom.core.transaction.WorkspaceSchemaWriteContext
import com.oruke.tagloom.core.workspace.UnlockedWorkspaceManifest
import com.oruke.tagloom.core.workspace.WorkspaceDefaults
import com.oruke.tagloom.core.workspace.withoutLegacyNotePropertyBindings

/** 在持有会话写锁且完成完整性检查后，通过既有 schema 事务修正旧根 Note 默认绑定。 */
internal object DesktopNoteTagSchemaMigration {
    /**
     * 检查已认证根定义并按需确认迁移；定义或值均不做物理删除。
     *
     * @param store 当前工作区的 schema 读写入口。
     * @param unlocked 已认证的当前 manifest。
     * @param writeContext 仅在确需写入时分配事务身份与 revision。
     * @return 已确认的新 manifest 密文；无需迁移或 schema 不完整时为空。
     */
    suspend fun migrate(
        store: DesktopWorkspaceSchemaStore,
        unlocked: UnlockedWorkspaceManifest,
        writeContext: () -> WorkspaceSchemaWriteContext,
    ): ByteArray? {
        // 先认证全部 schema；损坏状态下不应自动产生新的确认点。
        val schemas = store.inspect(unlocked)
        if (schemas.issues.isNotEmpty()) return null
        val root = schemas.tags.singleOrNull { it.payload.id == WorkspaceDefaults.DEFAULT_NOTE_TAG_ID }?.payload
            ?: return null
        val migrated = root.withoutLegacyNotePropertyBindings()
        if (migrated == root) return null

        // 只写根类型快照，保留 Note 密文及可供用户主动添加的属性定义。
        return store.saveTag(unlocked, migrated, writeContext()).encryptedManifest
    }
}
