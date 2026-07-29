package com.oruke.tagloom.core.sync

import com.oruke.tagloom.core.model.ActorId
import com.oruke.tagloom.core.model.ObjectId
import com.oruke.tagloom.core.model.WorkspaceId
import kotlinx.serialization.Serializable

/** 定义盲同步服务能够存储的端到端加密载荷类型。 */
@Serializable
enum class SyncArtifactKind {
    /** 对象级不可变 Change DAG 节点。 */
    CHANGE,

    /** 可选的权威对象快照副本。 */
    SNAPSHOT,

    /** 加密附件 Blob。 */
    BLOB,

    /** 加密 manifest 副本。 */
    MANIFEST,
}

/**
 * 描述参与内容寻址的公开同步元数据。
 *
 * 字段只包含随机工作区、对象、设备标识和密文摘要，不得包含标题、标签名、属性名或原始文件名。
 */
@Serializable
data class SyncArtifactDescriptor(
    /** 同步协议版本。 */
    val protocolVersion: Int,
    /** 随机工作区标识。 */
    val workspaceId: WorkspaceId,
    /** 密文载荷类型。 */
    val kind: SyncArtifactKind,
    /** Change/Snapshot 对应的随机对象标识；其他类型可为空。 */
    val objectId: ObjectId?,
    /** Change 的直接父内容 ID；其他类型为空。 */
    val parentArtifactIds: List<String>,
    /** 产生载荷的已认证设备或 Agent。 */
    val actorId: ActorId,
    /** Actor 本地严格递增序号。 */
    val actorSequence: Long,
    /** 解码后密文载荷的 SHA-256。 */
    val payloadSha256: String,
    /** 解码后密文载荷字节数。 */
    val payloadByteLength: Int,
) {
    init {
        require(protocolVersion == CURRENT_SYNC_PROTOCOL_VERSION) { "不支持的同步协议版本" }
        require(parentArtifactIds.distinct().size == parentArtifactIds.size) { "同步父内容 ID 不能重复" }
        require(parentArtifactIds.all(SYNC_CONTENT_ID_PATTERN::matches)) { "同步父内容 ID 格式无效" }
        require(actorSequence >= 0) { "同步 Actor 序号不能为负数" }
        require(SYNC_CONTENT_ID_PATTERN.matches(payloadSha256)) { "同步 payload SHA-256 格式无效" }
        require(payloadByteLength in 1..MAX_SYNC_ARTIFACT_BYTES) { "同步密文载荷尺寸超出限制" }
        require(kind != SyncArtifactKind.CHANGE || objectId != null) { "Change 必须关联对象 ID" }
        require(kind == SyncArtifactKind.CHANGE || parentArtifactIds.isEmpty()) {
            "只有 Change 可以声明父内容 ID"
        }
    }
}

/**
 * 表示服务器只能盲存储和按公开 DAG 元数据路由的端到端加密载荷。
 */
@Serializable
data class BlindSyncArtifact(
    /** 对 [descriptor] 与 payload 摘要再次计算得到的内容 ID。 */
    val artifactId: String,
    /** 参与内容寻址的公开描述符。 */
    val descriptor: SyncArtifactDescriptor,
    /** 完整加密信封的标准 Base64 文本。 */
    val payloadBase64: String,
    /** 原始 Actor 对内容 ID 与描述符生成的 Ed25519 detached signature 十六进制文本。 */
    val authorSignatureHex: String? = null,
) {
    init {
        require(SYNC_CONTENT_ID_PATTERN.matches(artifactId)) { "同步内容 ID 格式无效" }
        require(payloadBase64.isNotBlank()) { "同步密文载荷不能为空" }
        require(payloadBase64.length <= MAX_SYNC_ARTIFACT_BASE64_CHARS) { "同步 Base64 载荷尺寸超出限制" }
        require(artifactId !in descriptor.parentArtifactIds) { "Change 不能把自身声明为父节点" }
        require(authorSignatureHex == null || SYNC_SIGNATURE_PATTERN.matches(authorSignatureHex)) {
            "同步设备签名格式无效"
        }
    }
}

/** 描述服务器已知的一个对象及其全部 Change DAG heads。 */
@Serializable
data class ObjectChangeHeads(
    /** 随机对象标识。 */
    val objectId: ObjectId,
    /** 没有已知子节点的内容 ID，按字典序稳定排列。 */
    val headArtifactIds: List<String>,
) {
    init {
        require(headArtifactIds.distinct().size == headArtifactIds.size) { "同步 heads 不能重复" }
        require(headArtifactIds.all(SYNC_CONTENT_ID_PATTERN::matches)) { "同步 head 内容 ID 格式无效" }
    }
}

/** Change heads 查询响应。 */
@Serializable
data class SyncHeadsResponse(
    /** 当前工作区有 Change 的全部对象 heads。 */
    val objects: List<ObjectChangeHeads>,
)

/** 批量推送端到端加密载荷的请求。 */
@Serializable
data class SyncPushRequest(
    /** 一次请求内的载荷；服务端按 artifactId 幂等去重。 */
    val artifacts: List<BlindSyncArtifact>,
) {
    init {
        require(artifacts.size in 1..MAX_SYNC_BATCH_SIZE) { "同步推送批次尺寸无效" }
        require(artifacts.map(BlindSyncArtifact::artifactId).distinct().size == artifacts.size) {
            "同步推送批次不能包含重复内容 ID"
        }
    }
}

/** 批量推送端到端加密载荷的响应。 */
@Serializable
data class SyncPushResponse(
    /** 首次存储的内容数量。 */
    val storedCount: Int,
    /** 已存在且内容相同的幂等重复数量。 */
    val duplicateCount: Int,
)

/** 按内容 ID 精确拉取 Change 的请求。 */
@Serializable
data class SyncFetchRequest(
    /** 待获取的 Change 内容 ID。 */
    val artifactIds: List<String>,
) {
    init {
        require(artifactIds.size in 1..MAX_SYNC_BATCH_SIZE) { "同步拉取批次尺寸无效" }
        require(artifactIds.distinct().size == artifactIds.size) { "同步拉取内容 ID 不能重复" }
        require(artifactIds.all(SYNC_CONTENT_ID_PATTERN::matches)) { "同步拉取内容 ID 格式无效" }
    }
}

/** 按内容 ID 精确拉取 Change 的响应。 */
@Serializable
data class SyncFetchResponse(
    /** 服务端找到的 Change；客户端必须检查请求项是否全部返回。 */
    val artifacts: List<BlindSyncArtifact>,
)

/** 描述一种非 Change 密文载荷的服务端内容清单。 */
@Serializable
data class SyncArtifactInventoryEntry(
    /** 密文载荷类型。 */
    val kind: SyncArtifactKind,
    /** 服务端当前保存的全部内容 ID。 */
    val artifactIds: List<String>,
) {
    init {
        require(kind != SyncArtifactKind.CHANGE) { "Change 必须通过 DAG heads 交换" }
        require(artifactIds.size <= MAX_SYNC_INVENTORY_ITEMS) { "同步内容清单超过上限" }
        require(artifactIds.distinct().size == artifactIds.size) { "同步内容清单不能重复" }
        require(artifactIds.all(SYNC_CONTENT_ID_PATTERN::matches)) { "同步内容清单 ID 格式无效" }
    }
}

/** 非 Change 密文载荷内容清单响应。 */
@Serializable
data class SyncArtifactInventoryResponse(
    /** 按 kind 稳定排列的内容清单。 */
    val entries: List<SyncArtifactInventoryEntry>,
) {
    init {
        require(entries.map(SyncArtifactInventoryEntry::kind).distinct().size == entries.size) {
            "同步内容清单类型不能重复"
        }
    }
}

/** 按类型和内容 ID 精确拉取非 Change 密文载荷的请求。 */
@Serializable
data class SyncArtifactFetchRequest(
    /** 待拉取密文载荷类型。 */
    val kind: SyncArtifactKind,
    /** 待拉取内容 ID。 */
    val artifactIds: List<String>,
) {
    init {
        require(kind != SyncArtifactKind.CHANGE) { "Change 必须通过 DAG 拉取接口" }
        require(artifactIds.size in 1..MAX_SYNC_BATCH_SIZE) { "同步 artifact 拉取批次尺寸无效" }
        require(artifactIds.distinct().size == artifactIds.size) { "同步 artifact 拉取内容 ID 不能重复" }
        require(artifactIds.all(SYNC_CONTENT_ID_PATTERN::matches)) { "同步 artifact 拉取内容 ID 格式无效" }
    }
}

/** 非 Change 密文载荷精确拉取响应。 */
@Serializable
data class SyncArtifactFetchResponse(
    /** 服务端找到的同类型密文载荷。 */
    val artifacts: List<BlindSyncArtifact>,
)

/** 客户端一次 push/pull 会话的可观测结果。 */
data class ChangeDagSyncReport(
    /** 首次上传到服务端的 Change 数量。 */
    val uploadedCount: Int,
    /** 服务端已存在的幂等重复数量。 */
    val duplicateUploadCount: Int,
    /** 从服务端验证并保存到本地的 Change 数量。 */
    val downloadedCount: Int,
    /** 同步结束时服务端报告的对象数量。 */
    val remoteObjectCount: Int,
)

/** 描述一次非 Change 密文清单差集同步结果。 */
data class BlindArtifactSyncReport(
    /** 首次上传到服务端的密文载荷数。 */
    val uploadedCount: Int,
    /** 服务端已经存在的幂等重复数。 */
    val duplicateUploadCount: Int,
    /** 从服务端验证并首次保存的密文载荷数。 */
    val downloadedCount: Int,
)

/** 当前盲同步协议版本。 */
const val CURRENT_SYNC_PROTOCOL_VERSION: Int = 1

/** 单个密文载荷的 MVP 上限；大附件后续使用分块 Blob。 */
const val MAX_SYNC_ARTIFACT_BYTES: Int = 8 * 1024 * 1024

/** 单个密文载荷编码为标准 Base64 后的最大字符数。 */
const val MAX_SYNC_ARTIFACT_BASE64_CHARS: Int = ((MAX_SYNC_ARTIFACT_BYTES + 2) / 3) * 4

/** 单次网络请求允许的最大内容数量。 */
const val MAX_SYNC_BATCH_SIZE: Int = 100

/** 单次完整清单允许的最大内容 ID 数量；更大工作区后续改用分页或 Merkle diff。 */
const val MAX_SYNC_INVENTORY_ITEMS: Int = 100_000

/** SHA-256 小写十六进制内容 ID 格式。 */
internal val SYNC_CONTENT_ID_PATTERN: Regex = Regex("[a-f0-9]{64}")

/** Ed25519 detached signature 的 64 字节小写十六进制格式。 */
internal val SYNC_SIGNATURE_PATTERN: Regex = Regex("[a-f0-9]{128}")
