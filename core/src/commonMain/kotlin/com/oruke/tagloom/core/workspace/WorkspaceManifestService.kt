package com.oruke.tagloom.core.workspace

import com.oruke.tagloom.core.crypto.Argon2idParameters
import com.oruke.tagloom.core.crypto.CryptoProvider
import com.oruke.tagloom.core.crypto.EnvelopeCryptoService
import com.oruke.tagloom.core.crypto.EnvelopeDecryptionContext
import com.oruke.tagloom.core.crypto.EnvelopeEncryptionRequest
import com.oruke.tagloom.core.crypto.EnvelopeIdentity
import com.oruke.tagloom.core.crypto.MasterKeySlot
import com.oruke.tagloom.core.crypto.MasterKeySlotService
import com.oruke.tagloom.core.crypto.SecretKeyKind
import com.oruke.tagloom.core.crypto.SecretKeyMaterial
import com.oruke.tagloom.core.crypto.UnlockSlotType
import com.oruke.tagloom.core.crypto.WorkspaceKeyService
import com.oruke.tagloom.core.crypto.WrappingKeyPurpose
import com.oruke.tagloom.core.model.KeyId
import com.oruke.tagloom.core.model.TransactionId
import com.oruke.tagloom.core.model.WorkspaceId
import com.oruke.tagloom.core.protocol.CanonicalJson
import com.oruke.tagloom.core.transaction.WorkspaceRelativePath

/**
 * 编排工作区创建、两种解锁、密码重包和 manifest 权威状态更新。
 *
 * 该服务只持有调用期间的密钥；返回的会话密钥和 Recovery Key 必须由调用方主动销毁。
 */
class WorkspaceManifestService(
    /** 提供摘要、随机数、KDF 和 AEAD 的跨平台密码学端口。 */
    private val cryptoProvider: CryptoProvider,
    /** 负责主密钥生成与用途隔离派生。 */
    private val workspaceKeyService: WorkspaceKeyService = WorkspaceKeyService(cryptoProvider),
    /** 负责密码和 Recovery Key 槽的主密钥包装。 */
    private val masterKeySlotService: MasterKeySlotService = MasterKeySlotService(cryptoProvider, workspaceKeyService),
    /** 负责 manifest 载荷的随机 DEK 双层信封加密。 */
    private val envelopeCryptoService: EnvelopeCryptoService = EnvelopeCryptoService(cryptoProvider, workspaceKeyService),
) {
    /**
     * 创建同时具有密码和 Recovery Key 解锁能力的空工作区。
     *
     * @param workspaceId 新工作区公开随机标识。
     * @param keyId 首个 Workspace Master Key 版本标识。
     * @param password 用户设置的初始密码。
     * @param argon2idParameters 已按目标设备校准并包含随机 salt 的 KDF 参数。
     * @param passwordSlotId 密码解锁槽稳定标识。
     * @param recoverySlotId Recovery Key 解锁槽稳定标识。
     * @return manifest 字节、会话主密钥和只展示一次的 Recovery Key。
     */
    suspend fun createWorkspace(
        workspaceId: WorkspaceId,
        keyId: KeyId,
        password: String,
        argon2idParameters: Argon2idParameters,
        passwordSlotId: String,
        recoverySlotId: String,
    ): WorkspaceCreationResult {
        val workspaceMasterKey = workspaceKeyService.generateRandomKey(SecretKeyKind.WORKSPACE_MASTER)
        val recoveryKey = masterKeySlotService.generateRecoveryKey()
        return try {
            val passwordSlot = masterKeySlotService.createPasswordSlot(
                workspaceId = workspaceId,
                keyId = keyId,
                slotId = passwordSlotId,
                password = password,
                parameters = argon2idParameters,
                workspaceMasterKey = workspaceMasterKey,
            )
            val recoverySlot = masterKeySlotService.createRecoverySlot(
                workspaceId = workspaceId,
                keyId = keyId,
                slotId = recoverySlotId,
                recoveryKey = recoveryKey,
                workspaceMasterKey = workspaceMasterKey,
            )
            val publicHeader = WorkspaceManifestPublicHeader(
                workspaceId = workspaceId,
                activeKeyId = keyId,
                unlockSlots = listOf(passwordSlot, recoverySlot),
            )
            val payload = WorkspaceManifestPayload(
                publicHeaderSha256 = publicHeaderSha256(publicHeader),
                revision = 0L,
            )
            WorkspaceCreationResult(
                manifestBytes = encryptManifest(publicHeader, payload, workspaceMasterKey),
                workspaceMasterKey = workspaceMasterKey,
                recoveryKey = recoveryKey,
            )
        } catch (exception: Throwable) {
            workspaceMasterKey.destroy()
            recoveryKey.destroy()
            throw exception
        }
    }

    /**
     * 使用活动密码槽解锁并认证完整 manifest。
     *
     * @param manifestBytes 不可信磁盘 manifest 字节。
     * @param password 用户本次输入的密码。
     * @return 持有可主动销毁会话主密钥的已解锁 manifest。
     */
    suspend fun unlockWithPassword(
        manifestBytes: ByteArray,
        password: String,
    ): UnlockedWorkspaceManifest {
        val file = WorkspaceManifestCodec.decode(manifestBytes)
        val slot = activeSlot(file, UnlockSlotType.PASSWORD)
        val workspaceMasterKey = masterKeySlotService.unlockWithPassword(slot, password)
        return openWithMasterKey(file, workspaceMasterKey)
    }

    /**
     * 使用离线 Recovery Key 解锁并认证完整 manifest。
     *
     * @param manifestBytes 不可信磁盘 manifest 字节。
     * @param recoveryKey 用户提供且由调用方继续负责销毁的 Recovery Key。
     * @return 持有独立会话主密钥的已解锁 manifest。
     */
    suspend fun unlockWithRecovery(
        manifestBytes: ByteArray,
        recoveryKey: SecretKeyMaterial,
    ): UnlockedWorkspaceManifest {
        recoveryKey.requireKind(SecretKeyKind.RECOVERY)
        val file = WorkspaceManifestCodec.decode(manifestBytes)
        val slot = activeSlot(file, UnlockSlotType.RECOVERY)
        val workspaceMasterKey = masterKeySlotService.unlockWithRecovery(slot, recoveryKey)
        return openWithMasterKey(file, workspaceMasterKey)
    }

    /**
     * 更换密码并仅重包 Workspace Master Key，同时更新公开头认证和 manifest 小载荷。
     *
     * @param manifestBytes 当前 manifest 字节。
     * @param oldPassword 当前密码。
     * @param newPassword 新密码。
     * @param newParameters 新密码槽的 Argon2id 参数与随机 salt。
     * @param newPasswordSlotId 新密码槽稳定标识。
     * @return 可原子替换旧 manifest 的新字节；对象文件无需重加密。
     */
    suspend fun changePassword(
        manifestBytes: ByteArray,
        oldPassword: String,
        newPassword: String,
        newParameters: Argon2idParameters,
        newPasswordSlotId: String,
    ): ByteArray {
        val unlocked = unlockWithPassword(manifestBytes, oldPassword)
        return try {
            replacePasswordSlot(
                unlocked = unlocked,
                newPassword = newPassword,
                newParameters = newParameters,
                newPasswordSlotId = newPasswordSlotId,
            )
        } finally {
            unlocked.destroy()
        }
    }

    /**
     * 使用已经认证的 Workspace Master Key 建立新的活动密码槽。
     *
     * 该入口用于 Recovery Key 或已授权设备重新建立本机解锁材料。它只替换活动密码槽，
     * 保留 Recovery Key 槽和全部对象信封，因此不会把恢复流程误实现为重加密业务正文。
     *
     * @param unlocked 已通过任意合法解锁方式认证、且仍持有活动主密钥的 manifest。
     * @param newPassword 新活动密码槽使用的高熵解锁材料。
     * @param newParameters 新密码槽的 Argon2id 参数与随机 salt。
     * @param newPasswordSlotId 新密码槽的稳定标识。
     * @return 可原子替换当前 manifest 的新字节。
     */
    suspend fun replacePasswordSlot(
        unlocked: UnlockedWorkspaceManifest,
        newPassword: String,
        newParameters: Argon2idParameters,
        newPasswordSlotId: String,
    ): ByteArray {
        val oldHeader = unlocked.file.publicHeader
        val newSlot = masterKeySlotService.createPasswordSlot(
            workspaceId = oldHeader.workspaceId,
            keyId = oldHeader.activeKeyId,
            slotId = newPasswordSlotId,
            password = newPassword,
            parameters = newParameters,
            workspaceMasterKey = unlocked.workspaceMasterKey,
        )
        val retainedSlots = oldHeader.unlockSlots.filterNot { slot ->
            slot.header.keyId == oldHeader.activeKeyId && slot.header.slotType == UnlockSlotType.PASSWORD
        }
        val newHeader = oldHeader.copy(unlockSlots = retainedSlots + newSlot)
        val newPayload = unlocked.payload.copy(
            publicHeaderSha256 = publicHeaderSha256(newHeader),
            revision = unlocked.payload.revision + 1L,
        )
        return encryptManifest(newHeader, newPayload, unlocked.workspaceMasterKey)
    }

    /**
     * 为已经全部换用新主密钥的对象生成新活动密钥槽和原子确认 manifest。
     *
     * v1 信封把 `keyId` 纳入 payload AEAD，因此调用方必须先在内存中生成全部新信封，并把
     * 它们与本方法返回的 manifest 放入同一 Transaction Coordinator 计划。
     *
     * @param unlocked 当前旧主密钥 manifest。
     * @param newKeyId 新 Workspace Master Key 版本。
     * @param newWorkspaceMasterKey 新工作区主密钥。
     * @param newRecoveryKey 新主密钥对应且只展示一次的 Recovery Key。
     * @param newPassword 新主密钥密码槽使用的密码。
     * @param newParameters 新密码槽 Argon2id 参数。
     * @param passwordSlotId 新密码槽 ID。
     * @param recoverySlotId 新恢复槽 ID。
     * @param transactionId 轮换事务 ID。
     * @param rotatedObjects 与旧 manifest 路径集合相同、全部使用新 keyId 的对象记录。
     * @return 可与全部新对象信封一起原子提交的 manifest 字节。
     */
    suspend fun rotateMasterKey(
        unlocked: UnlockedWorkspaceManifest,
        newKeyId: KeyId,
        newWorkspaceMasterKey: SecretKeyMaterial,
        newRecoveryKey: SecretKeyMaterial,
        newPassword: String,
        newParameters: Argon2idParameters,
        passwordSlotId: String,
        recoverySlotId: String,
        transactionId: TransactionId,
        rotatedObjects: List<ManifestObjectRecord>,
    ): ByteArray {
        newWorkspaceMasterKey.requireKind(SecretKeyKind.WORKSPACE_MASTER)
        newRecoveryKey.requireKind(SecretKeyKind.RECOVERY)
        require(newKeyId != unlocked.file.publicHeader.activeKeyId) { "轮换必须使用新的 keyId" }
        require(rotatedObjects.all { record -> record.keyId == newKeyId }) { "轮换对象未全部使用新 keyId" }
        require(rotatedObjects.map(ManifestObjectRecord::path).toSet() ==
            unlocked.payload.objects.map(ManifestObjectRecord::path).toSet()) {
            "轮换前后权威对象路径集合必须一致"
        }
        val workspaceId = unlocked.file.publicHeader.workspaceId
        val passwordSlot = masterKeySlotService.createPasswordSlot(
            workspaceId = workspaceId,
            keyId = newKeyId,
            slotId = passwordSlotId,
            password = newPassword,
            parameters = newParameters,
            workspaceMasterKey = newWorkspaceMasterKey,
        )
        val recoverySlot = masterKeySlotService.createRecoverySlot(
            workspaceId = workspaceId,
            keyId = newKeyId,
            slotId = recoverySlotId,
            recoveryKey = newRecoveryKey,
            workspaceMasterKey = newWorkspaceMasterKey,
        )
        val newHeader = WorkspaceManifestPublicHeader(
            manifestFormatVersion = unlocked.file.publicHeader.manifestFormatVersion,
            workspaceId = workspaceId,
            activeKeyId = newKeyId,
            unlockSlots = listOf(passwordSlot, recoverySlot),
        )
        val transactionHistory = (
            unlocked.payload.committedTransactionIds.filterNot { committed -> committed == transactionId } +
                transactionId
            ).takeLast(WorkspaceManifestPayload.MAX_COMMITTED_TRANSACTION_HISTORY)
        val newPayload = unlocked.payload.copy(
            publicHeaderSha256 = publicHeaderSha256(newHeader),
            revision = unlocked.payload.revision + 1L,
            lastCommittedTransactionId = transactionId,
            committedTransactionIds = transactionHistory,
            objects = rotatedObjects.sortedBy { record -> record.path.value },
        )
        return encryptManifest(newHeader, newPayload, newWorkspaceMasterKey)
    }

    /**
     * 生成确认一个跨对象事务后的下一版 manifest 字节。
     *
     * @param unlocked 当前已认证且仍持有会话主密钥的 manifest。
     * @param transactionId 本次被确认的事务标识。
     * @param upserts 新增或替换的对象摘要。
     * @param removedPaths 本次删除的对象路径。
     * @param minimumFormatVersion 本次 payload 所需的最低读取版本；升级和对象发布原子确认，永不降级。
     * @return revision 增一并记录确认事务的加密 manifest 字节。
     */
    suspend fun confirmTransaction(
        unlocked: UnlockedWorkspaceManifest,
        transactionId: TransactionId,
        upserts: List<ManifestObjectRecord>,
        removedPaths: Set<WorkspaceRelativePath>,
        minimumFormatVersion: Int = 1,
    ): ByteArray {
        require(upserts.map(ManifestObjectRecord::path).distinct().size == upserts.size) {
            "同一事务不能重复更新对象路径"
        }
        require(upserts.none { record -> record.path in removedPaths }) {
            "同一事务不能同时更新和删除对象路径"
        }
        val byPath = unlocked.payload.objects
            .filterNot { record -> record.path in removedPaths }
            .associateBy(ManifestObjectRecord::path)
            .toMutableMap()
        upserts.forEach { record -> byPath[record.path] = record }
        val transactionHistory = (
            unlocked.payload.committedTransactionIds.filterNot { committed -> committed == transactionId } +
                transactionId
            ).takeLast(WorkspaceManifestPayload.MAX_COMMITTED_TRANSACTION_HISTORY)
        val nextHeader = unlocked.file.publicHeader.copy(
            manifestFormatVersion = maxOf(unlocked.file.publicHeader.manifestFormatVersion, minimumFormatVersion),
        )
        val nextPayload = unlocked.payload.copy(
            publicHeaderSha256 = publicHeaderSha256(nextHeader),
            revision = unlocked.payload.revision + 1L,
            lastCommittedTransactionId = transactionId,
            committedTransactionIds = transactionHistory,
            objects = byPath.values.sortedBy { record -> record.path.value },
        )
        return encryptManifest(
            publicHeader = nextHeader,
            payload = nextPayload,
            workspaceMasterKey = unlocked.workspaceMasterKey,
        )
    }

    /**
     * 使用已授权会话主密钥直接认证 manifest，供事务恢复和索引重建使用。
     *
     * @param manifestBytes 不可信 manifest 字节。
     * @param workspaceMasterKey 与 manifest 活动 keyId 对应的会话主密钥。
     * @return 已认证 payload；返回对象复用传入密钥且不得调用其 destroy。
     */
    suspend fun inspectWithMasterKey(
        manifestBytes: ByteArray,
        workspaceMasterKey: SecretKeyMaterial,
    ): WorkspaceManifestPayload {
        val file = WorkspaceManifestCodec.decode(manifestBytes)
        return decryptPayload(file, workspaceMasterKey)
    }

    /**
     * 使用已恢复的主密钥打开 manifest，并在任何认证失败时清零该密钥。
     *
     * @param file 已完成外层结构校验的 manifest。
     * @param workspaceMasterKey 由密码槽或 Recovery Key 槽恢复的主密钥。
     * @return 取得密钥所有权的已解锁 manifest。
     */
    private suspend fun openWithMasterKey(
        file: WorkspaceManifestFile,
        workspaceMasterKey: SecretKeyMaterial,
    ): UnlockedWorkspaceManifest = try {
        UnlockedWorkspaceManifest(
            file = file,
            payload = decryptPayload(file, workspaceMasterKey),
            workspaceMasterKey = workspaceMasterKey,
        )
    } catch (exception: Throwable) {
        workspaceMasterKey.destroy()
        throw exception
    }

    /**
     * 选择活动主密钥唯一匹配的指定解锁槽。
     *
     * @param file 待解锁 manifest。
     * @param slotType 密码或 Recovery Key 槽类型。
     * @return 唯一活动槽。
     */
    private fun activeSlot(
        file: WorkspaceManifestFile,
        slotType: UnlockSlotType,
    ): MasterKeySlot = file.publicHeader.unlockSlots.singleOrNull { slot ->
        slot.header.keyId == file.publicHeader.activeKeyId && slot.header.slotType == slotType
    } ?: throw WorkspaceManifestException(WorkspaceManifestFailureCode.UNSUPPORTED_STATE)

    /**
     * 解密并核对 manifest 载荷身份、revision 与公开头摘要。
     *
     * @param file 已完成外层结构校验的 manifest。
     * @param workspaceMasterKey 活动主密钥。
     * @return 完成全部认证的载荷。
     */
    private suspend fun decryptPayload(
        file: WorkspaceManifestFile,
        workspaceMasterKey: SecretKeyMaterial,
    ): WorkspaceManifestPayload {
        val envelope = file.encryptedPayload
        val plaintext = envelopeCryptoService.decrypt(
            envelope = envelope,
            context = EnvelopeDecryptionContext(
                expectedIdentity = EnvelopeIdentity(
                    workspaceId = file.publicHeader.workspaceId,
                    objectType = MANIFEST_OBJECT_TYPE,
                    objectId = file.publicHeader.workspaceId.value,
                    revision = envelope.header.revision,
                ),
                expectedKeyId = file.publicHeader.activeKeyId,
                expectedWrappingKeyPurpose = WrappingKeyPurpose.MANIFEST,
            ),
            workspaceMasterKey = workspaceMasterKey,
        )
        return try {
            val payload = CanonicalJson.decodeFromString(
                WorkspaceManifestPayload.serializer(),
                plaintext.decodeToString(),
            )
            if (
                payload.revision.toString() != envelope.header.revision ||
                payload.publicHeaderSha256 != publicHeaderSha256(file.publicHeader)
            ) {
                throw WorkspaceManifestException(WorkspaceManifestFailureCode.UNSUPPORTED_STATE)
            }
            payload
        } catch (exception: WorkspaceManifestException) {
            throw exception
        } catch (exception: Throwable) {
            throw WorkspaceManifestException(WorkspaceManifestFailureCode.MALFORMED_FILE, exception)
        } finally {
            plaintext.fill(0)
        }
    }

    /**
     * 规范化载荷并使用独立随机 DEK 加密完整 manifest 文件。
     *
     * @param publicHeader 当前公开头。
     * @param payload 已绑定该公开头摘要的权威载荷。
     * @param workspaceMasterKey 当前活动主密钥。
     * @return 可持久化 manifest 字节。
     */
    private suspend fun encryptManifest(
        publicHeader: WorkspaceManifestPublicHeader,
        payload: WorkspaceManifestPayload,
        workspaceMasterKey: SecretKeyMaterial,
    ): ByteArray {
        require(payload.publicHeaderSha256 == publicHeaderSha256(publicHeader)) {
            "manifest 载荷未绑定当前公开头"
        }
        val plaintext = CanonicalJson
            .encodeToString(WorkspaceManifestPayload.serializer(), payload)
            .encodeToByteArray()
        return try {
            val envelope = envelopeCryptoService.encrypt(
                request = EnvelopeEncryptionRequest(
                    identity = EnvelopeIdentity(
                        workspaceId = publicHeader.workspaceId,
                        objectType = MANIFEST_OBJECT_TYPE,
                        objectId = publicHeader.workspaceId.value,
                        revision = payload.revision.toString(),
                    ),
                    keyId = publicHeader.activeKeyId,
                    wrappingKeyPurpose = WrappingKeyPurpose.MANIFEST,
                    plaintext = plaintext,
                ),
                workspaceMasterKey = workspaceMasterKey,
            )
            WorkspaceManifestCodec.encode(
                WorkspaceManifestFile(
                    publicHeader = publicHeader,
                    encryptedPayload = envelope,
                ),
            )
        } finally {
            plaintext.fill(0)
        }
    }

    /**
     * 对确定性公开头编码计算 SHA-256 小写十六进制摘要。
     *
     * @param publicHeader 待认证的公开头。
     * @return 64 位小写十六进制摘要。
     */
    private fun publicHeaderSha256(publicHeader: WorkspaceManifestPublicHeader): String = cryptoProvider
        .sha256(
            CanonicalJson
                .encodeToString(WorkspaceManifestPublicHeader.serializer(), publicHeader)
                .encodeToByteArray(),
        )
        .joinToString(separator = "") { byte ->
            (byte.toInt() and 0xFF).toString(16).padStart(2, '0')
        }

    companion object {
        /** manifest 信封的固定对象类型域。 */
        private const val MANIFEST_OBJECT_TYPE: String = "workspace-manifest"
    }
}
