package com.oruke.tagloom.workspace

import com.oruke.tagloom.core.crypto.CryptoProvider
import com.oruke.tagloom.core.model.ObjectId
import com.oruke.tagloom.core.model.ObjectSnapshot
import com.oruke.tagloom.core.model.PropertyDefinition
import com.oruke.tagloom.core.model.TagDefinition
import com.oruke.tagloom.core.model.TagId
import com.oruke.tagloom.core.domain.tag.TagResolver
import com.oruke.tagloom.core.domain.tag.TagResolution
import com.oruke.tagloom.core.workspace.WorkspaceDefaults
import com.oruke.tagloom.core.transaction.PropertyDefinitionSnapshotTransactionRequest
import com.oruke.tagloom.core.transaction.TagDefinitionSnapshotTransactionRequest
import com.oruke.tagloom.core.transaction.WorkspaceSchemaSnapshotEnvelopeService
import com.oruke.tagloom.core.transaction.WorkspaceSchemaSnapshotTransactionResult
import com.oruke.tagloom.core.transaction.WorkspaceSchemaSnapshotTransactionService
import com.oruke.tagloom.core.transaction.WorkspaceSchemaWriteContext
import com.oruke.tagloom.core.workspace.ManifestObjectKind
import com.oruke.tagloom.core.workspace.ManifestObjectRecord
import com.oruke.tagloom.core.workspace.UnlockedWorkspaceManifest
import com.oruke.tagloom.filesystem.jvm.JvmWorkspacePaths
import java.nio.file.Files
import java.nio.file.LinkOption
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 从权威密文加载、认证并提交工作区 Tag 与 PropertyDefinition schema。
 *
 * @param paths 当前工作区受控路径。
 * @param cryptoProvider 计算密文摘要的密码学端口。
 * @param envelopeService 解密和校验 schema 快照的服务。
 * @param transactionService schema 对象统一事务入口。
 */
class DesktopWorkspaceSchemaStore(
    private val paths: JvmWorkspacePaths,
    private val cryptoProvider: CryptoProvider,
    private val envelopeService: WorkspaceSchemaSnapshotEnvelopeService,
    private val transactionService: WorkspaceSchemaSnapshotTransactionService,
) {
    /**
     * 加载全部未删除 schema，并按名称和 ID 稳定排序。
     *
     * @param unlocked 当前已认证 manifest 与主密钥。
     * @return 可供编辑器使用的标签、属性定义与逻辑隔离问题。
     */
    suspend fun load(unlocked: UnlockedWorkspaceManifest): DesktopWorkspaceSchemaLoadResult = inspect(unlocked)

    /**
     * 扫描 schema 密文并逻辑隔离缺失、摘要错误或认证失败对象。
     *
     * @param unlocked 当前已认证 manifest 与主密钥。
     * @return 有效 schema 快照和脱敏完整性问题。
     */
    suspend fun inspect(unlocked: UnlockedWorkspaceManifest): DesktopWorkspaceSchemaLoadResult = withContext(Dispatchers.IO) {
        val tagResult = loadRecords(
            unlocked = unlocked,
            kind = ManifestObjectKind.TAG,
            decrypt = { encrypted, record ->
                envelopeService.decryptTag(
                    encryptedBytes = encrypted,
                    workspaceId = unlocked.file.publicHeader.workspaceId,
                    objectId = ObjectId(record.objectId),
                    revision = record.revision,
                    keyId = record.keyId,
                    workspaceMasterKey = unlocked.workspaceMasterKey,
                )
            },
        )
        val propertyResult = loadRecords(
            unlocked = unlocked,
            kind = ManifestObjectKind.PROPERTY,
            decrypt = { encrypted, record ->
                envelopeService.decryptProperty(
                    encryptedBytes = encrypted,
                    workspaceId = unlocked.file.publicHeader.workspaceId,
                    objectId = ObjectId(record.objectId),
                    revision = record.revision,
                    keyId = record.keyId,
                    workspaceMasterKey = unlocked.workspaceMasterKey,
                )
            },
        )
        DesktopWorkspaceSchemaLoadResult(
            tags = tagResult.snapshots
                .filterNot { snapshot -> snapshot.header.deleted }
                .sortedWith(compareBy({ snapshot -> snapshot.payload.name }, { snapshot -> snapshot.header.objectId.value })),
            properties = propertyResult.snapshots
                .filterNot { snapshot -> snapshot.header.deleted }
                .sortedWith(compareBy({ snapshot -> snapshot.payload.name }, { snapshot -> snapshot.header.objectId.value })),
            issues = tagResult.issues + propertyResult.issues,
        )
    }

    /**
     * 创建或更新一个标签定义，并自动使用当前已确认快照作为事务基线。
     *
     * @param unlocked 当前已认证 manifest。
     * @param definition 待确认完整标签定义。
     * @param context 写入身份与时间。
     * @return 已确认标签事务结果。
     */
    suspend fun saveTag(
        unlocked: UnlockedWorkspaceManifest,
        definition: TagDefinition,
        context: WorkspaceSchemaWriteContext,
    ): WorkspaceSchemaSnapshotTransactionResult<TagDefinition> {
        if (definition.id == com.oruke.tagloom.core.workspace.LinkNoteSchemas.TYPE_ID) {
            require(WorkspaceDefaults.DEFAULT_NOTE_TAG_ID in definition.parentTagIds && definition.propertyBindings.any { it.propertyId == com.oruke.tagloom.core.workspace.LinkNoteSchemas.URL_ID && it.required } &&
                definition.propertyBindings.any { it.propertyId == com.oruke.tagloom.core.workspace.LinkNoteSchemas.GROUP_ID && !it.required && it.defaultPolicy == com.oruke.tagloom.core.model.DefaultValuePolicy.NONE && it.defaultValue == null }) { "link-schema-incompatible" }
        }
        val catalog = inspect(unlocked)
        require(catalog.issues.isEmpty()) { "schema-integrity-failure" }
        val definitions = catalog.tags.map { it.payload }.associateBy { it.id } + (definition.id to definition)
        require(TagResolver(WorkspaceDefaults.DEFAULT_NOTE_TAG_ID).resolve(listOf(definition.id), definitions) is TagResolution.Success) {
            "invalid-supertag-inheritance"
        }
        require(definition.propertyBindings.all { binding -> catalog.properties.any { it.payload.id == binding.propertyId } }) {
            "missing-property-definition"
        }
        val previous = catalog.tags.singleOrNull { snapshot -> snapshot.payload.id == definition.id }
        return transactionService.commitTag(
            unlocked,
            TagDefinitionSnapshotTransactionRequest(definition, previous, context),
        )
    }

    /**
     * 为已经完成引用检查的超级标签写入 tombstone，保留历史密文和稳定身份。
     * @param unlocked 当前已认证 manifest。
     * @param tagId 待删除超级标签。
     * @param context 唯一事务身份与时间。
     * @return 经事务确认的删除结果。
     */
    suspend fun deleteTag(
        unlocked: UnlockedWorkspaceManifest,
        tagId: TagId,
        context: WorkspaceSchemaWriteContext,
    ): WorkspaceSchemaSnapshotTransactionResult<TagDefinition> {
        val previous = inspect(unlocked).tags.single { it.payload.id == tagId }
        return transactionService.commitTag(
            unlocked,
            TagDefinitionSnapshotTransactionRequest(previous.payload, previous, context, deleted = true),
        )
    }

    /**
     * 创建或更新一个属性定义，并自动使用当前已确认快照作为事务基线。
     *
     * @param unlocked 当前已认证 manifest。
     * @param definition 待确认完整属性定义。
     * @param context 写入身份与时间。
     * @return 已确认属性定义事务结果。
     */
    suspend fun saveProperty(
        unlocked: UnlockedWorkspaceManifest,
        definition: PropertyDefinition,
        context: WorkspaceSchemaWriteContext,
    ): WorkspaceSchemaSnapshotTransactionResult<PropertyDefinition> {
        val previous = inspect(unlocked).properties.singleOrNull { snapshot -> snapshot.payload.id == definition.id }
        return transactionService.commitProperty(
            unlocked,
            PropertyDefinitionSnapshotTransactionRequest(definition, previous, context),
        )
    }

    /**
     * 加载并认证一种 manifest schema 记录。
     *
     * @param T schema payload 类型。
     * @param unlocked 当前已认证 manifest。
     * @param kind 待加载 manifest 类别。
     * @param decrypt 绑定 schema 类型的解密与快照认证函数。
     * @return 有效快照与逻辑隔离问题。
     */
    private suspend fun <T> loadRecords(
        unlocked: UnlockedWorkspaceManifest,
        kind: ManifestObjectKind,
        decrypt: suspend (ByteArray, ManifestObjectRecord) -> ObjectSnapshot<T>,
    ): DesktopSchemaRecordLoadResult<T> {
        val issues = mutableListOf<DesktopObjectIntegrityIssue>()
        val snapshots = unlocked.payload.objects
            .filter { record -> record.kind == kind }
            .mapNotNull { record ->
                val file = paths.target(record.path)
                if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
                    issues += record.toIssue(DesktopIntegrityFailure.MISSING_FILE)
                    return@mapNotNull null
                }
                val encrypted = Files.readAllBytes(file)
                if (cryptoProvider.sha256(encrypted).toHex() != record.encryptedSha256) {
                    issues += record.toIssue(DesktopIntegrityFailure.HASH_MISMATCH)
                    return@mapNotNull null
                }
                runCatching { decrypt(encrypted, record) }.getOrElse {
                    issues += record.toIssue(DesktopIntegrityFailure.AUTHENTICATION_FAILED)
                    null
                }
            }
        return DesktopSchemaRecordLoadResult(snapshots, issues)
    }

    /**
     * 把 manifest 记录转换为不含名称和值的完整性问题。
     *
     * @param failure 失败分类。
     * @return 脱敏问题。
     */
    private fun ManifestObjectRecord.toIssue(failure: DesktopIntegrityFailure): DesktopObjectIntegrityIssue =
        DesktopObjectIntegrityIssue(kind, objectId, failure)

    /**
     * 把摘要字节编码为小写十六进制。
     *
     * @return 小写十六进制文本。
     */
    private fun ByteArray.toHex(): String = joinToString("") { byte ->
        (byte.toInt() and 0xFF).toString(16).padStart(2, '0')
    }
}

/**
 * 保存一次工作区 schema 扫描的有效快照与逻辑隔离问题。
 */
data class DesktopWorkspaceSchemaLoadResult(
    /** 可展示和编辑的活动标签快照。 */
    val tags: List<ObjectSnapshot<TagDefinition>>,
    /** 可展示和编辑的活动属性定义快照。 */
    val properties: List<ObjectSnapshot<PropertyDefinition>>,
    /** 已跳过的损坏 schema 对象。 */
    val issues: List<DesktopObjectIntegrityIssue>,
)
/**
 * 保存单一 schema 类别的读取结果。
 *
 * @param T schema payload 类型。
 */
private data class DesktopSchemaRecordLoadResult<T>(
    /** 通过摘要与 AEAD 认证的快照。 */
    val snapshots: List<ObjectSnapshot<T>>,
    /** 已逻辑隔离的对象问题。 */
    val issues: List<DesktopObjectIntegrityIssue>,
)
