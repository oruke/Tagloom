package com.oruke.tagloom.filesystem.jvm

import com.oruke.tagloom.core.crypto.WrappingKeyPurpose

/**
 * 区分 JVM staging 明文需要执行的 schema 校验类型。
 */
internal enum class ArtifactSchemaKind {
    /** Note 对象快照。 */
    NOTE,

    /** Tag 对象快照。 */
    TAG,

    /** PropertyDef 对象快照。 */
    PROPERTY,

    /** SavedQuery 对象快照。 */
    SAVED_QUERY,

    /** Database 权威定义快照。 */
    DATABASE,

    /** View 权威定义快照，未知查询或展示版本仍可按只读兼容保留。 */
    VIEW,

    /** 工作区级 Agent 连接、模型上下文窗口与偏好加密快照。 */
    WORKSPACE_CONFIG,

    /** 工作区级 Agent 对话线程加密快照。 */
    AGENT_THREAD,

    /** Change DAG 单节点。 */
    CHANGE,

    /** Base/Local/Remote 冲突证据。 */
    CONFLICT,

    /** 已认证快照根与被压缩 Change 集合。 */
    CHECKPOINT,

    /** Blob 等只校验 AEAD 且不解释内容的字节。 */
    OPAQUE,
}

/**
 * 保存由受控工作区路径推导出的信封身份、用途和 schema。
 */
internal data class ArtifactRole(
    /** 信封公开头固定对象类型。 */
    val envelopeObjectType: String,
    /** 从文件名提取的稳定对象标识。 */
    val objectId: String,
    /** 用途隔离的 Wrapping Key 类别。 */
    val wrappingKeyPurpose: WrappingKeyPurpose,
    /** 解密后执行的 schema 校验类型。 */
    val kind: ArtifactSchemaKind,
) {
    companion object {
        /**
         * 从已经过 `WorkspaceRelativePath` 校验的路径推导对象角色。
         *
         * @param path 工作区受控相对路径文本。
         * @return 对应信封和 schema 角色。
         */
        fun from(path: String): ArtifactRole = when {
            path.startsWith("notes/") -> snapshotRole(path, ".note.enc", "note-snapshot", ArtifactSchemaKind.NOTE)
            path.startsWith("tags/") -> snapshotRole(path, ".tag.enc", "tag-snapshot", ArtifactSchemaKind.TAG)
            path.startsWith("properties/") -> snapshotRole(
                path,
                ".property.enc",
                "property-snapshot",
                ArtifactSchemaKind.PROPERTY,
            )
            path.startsWith("queries/") -> snapshotRole(
                path,
                ".query.enc",
                "saved-query-snapshot",
                ArtifactSchemaKind.SAVED_QUERY,
            )
            path.startsWith("databases/") -> snapshotRole(path, ".database.enc", "database-snapshot", ArtifactSchemaKind.DATABASE)
            path.startsWith("views/") -> snapshotRole(path, ".view.enc", "view-snapshot", ArtifactSchemaKind.VIEW)
            path.startsWith("configs/") -> snapshotRole(
                path,
                ".config.enc",
                "workspace-config-snapshot",
                ArtifactSchemaKind.WORKSPACE_CONFIG,
            )
            path.startsWith("threads/") -> snapshotRole(
                path,
                ".thread.enc",
                "agent-thread-snapshot",
                ArtifactSchemaKind.AGENT_THREAD,
            )
            path.startsWith("changes/") -> ArtifactRole(
                envelopeObjectType = "object-change",
                objectId = extractFileId(path, ".change.enc"),
                wrappingKeyPurpose = WrappingKeyPurpose.CHANGE,
                kind = ArtifactSchemaKind.CHANGE,
            )
            path.startsWith("blobs/") -> ArtifactRole(
                envelopeObjectType = "blob",
                objectId = extractFileId(path, ".blob.enc"),
                wrappingKeyPurpose = WrappingKeyPurpose.BLOB,
                kind = ArtifactSchemaKind.OPAQUE,
            )
            path.startsWith("history/conflicts/") -> ArtifactRole(
                envelopeObjectType = "audit-record",
                objectId = extractFileId(path, ".audit.enc"),
                wrappingKeyPurpose = WrappingKeyPurpose.AUDIT,
                kind = ArtifactSchemaKind.CONFLICT,
            )
            path.startsWith("history/checkpoints/") -> ArtifactRole(
                envelopeObjectType = "change-checkpoint",
                objectId = extractFileId(path, ".audit.enc"),
                wrappingKeyPurpose = WrappingKeyPurpose.AUDIT,
                kind = ArtifactSchemaKind.CHECKPOINT,
            )
            path.startsWith("history/") -> ArtifactRole(
                envelopeObjectType = "audit-record",
                objectId = extractFileId(path, ".audit.enc"),
                wrappingKeyPurpose = WrappingKeyPurpose.AUDIT,
                kind = ArtifactSchemaKind.OPAQUE,
            )
            else -> throw IllegalArgumentException("不支持的 staging artifact 路径")
        }

        /**
         * 构造权威对象快照角色。
         *
         * @param path 完整受控相对路径。
         * @param suffix 路径类别固定后缀。
         * @param objectType 信封固定对象类型。
         * @param kind 解密后的 schema 类型。
         * @return 使用 SNAPSHOT wrapping key 的角色。
         */
        private fun snapshotRole(
            path: String,
            suffix: String,
            objectType: String,
            kind: ArtifactSchemaKind,
        ): ArtifactRole = ArtifactRole(
            envelopeObjectType = objectType,
            objectId = extractFileId(path, suffix),
            wrappingKeyPurpose = WrappingKeyPurpose.SNAPSHOT,
            kind = kind,
        )

        /**
         * 从路径末段剥离严格后缀并验证不透明 ID。
         *
         * @param path 完整受控相对路径。
         * @param suffix 该类别要求的文件后缀。
         * @return 文件名中的稳定对象 ID。
         */
        private fun extractFileId(
            path: String,
            suffix: String,
        ): String {
            val fileName = path.substringAfterLast('/')
            require(fileName.endsWith(suffix)) { "artifact 文件后缀不匹配" }
            val objectId = fileName.removeSuffix(suffix)
            require(objectId.matches(OPAQUE_ID_PATTERN)) { "artifact 对象 ID 格式无效" }
            return objectId
        }

        /** 工作区文件名允许的不透明 ID 格式。 */
        private val OPAQUE_ID_PATTERN: Regex = Regex("[A-Za-z0-9_-]{1,128}")
    }
}
