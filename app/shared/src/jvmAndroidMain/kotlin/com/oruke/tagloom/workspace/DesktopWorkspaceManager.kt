package com.oruke.tagloom.workspace

import com.oruke.tagloom.core.crypto.Argon2idParameters
import com.oruke.tagloom.core.crypto.CryptoProtocol
import com.oruke.tagloom.core.crypto.CryptoProvider
import com.oruke.tagloom.core.crypto.RecoveryKeyCodec
import com.oruke.tagloom.core.model.ActorId
import com.oruke.tagloom.core.model.KeyId
import com.oruke.tagloom.core.model.TransactionId
import com.oruke.tagloom.core.model.WorkspaceId
import com.oruke.tagloom.core.workspace.WorkspaceCreationResult
import com.oruke.tagloom.core.workspace.WorkspaceManifestCodec
import com.oruke.tagloom.core.workspace.WorkspaceManifestService
import com.oruke.tagloom.core.workspace.UnlockedWorkspaceManifest
import com.oruke.tagloom.filesystem.createJvmWorkspaceFileSystem
import com.oruke.tagloom.filesystem.jvm.JvmWorkspaceLifecycleService
import com.oruke.tagloom.filesystem.jvm.JvmEncryptedWorkspaceBackupService
import com.oruke.tagloom.filesystem.jvm.JvmWorkspaceFileSystem
import com.oruke.tagloom.filesystem.jvm.JvmWorkspacePaths
import com.oruke.tagloom.filesystem.jvm.crypto.LibsodiumCryptoProvider
import java.nio.file.Path
import java.time.Instant
import java.util.UUID

/**
 * 编排桌面端工作区选择后的两阶段创建、解锁、会话替换和锁定。
 */
class DesktopWorkspaceManager(
    /** 真实 libsodium 密码学端口。 */
    private val cryptoProvider: CryptoProvider = LibsodiumCryptoProvider(),
    /** 生成随机 URL-safe 协议 ID。 */
    private val idFactory: () -> String = { UUID.randomUUID().toString() },
    /** 提供 HLC 物理毫秒。 */
    private val currentTimeMillis: () -> Long = System::currentTimeMillis,
    /** 提供 UTC ISO-8601 时间。 */
    private val timestampProvider: () -> String = { Instant.now().toString() },
    /** 允许测试注入低成本参数；产品默认使用设计基线。 */
    private val argon2idFactory: (ByteArray) -> Argon2idParameters = { salt -> Argon2idParameters(salt) },
    /** 为每个已选工作区创建绑定根目录的 JVM 文件能力。 */
    private val workspaceFileSystemFactory: (Path, CryptoProvider) -> JvmWorkspaceFileSystem =
        { root, provider -> createJvmWorkspaceFileSystem(root, provider) },
) {
    /** manifest 与密钥槽领域服务。 */
    private val manifestService: WorkspaceManifestService = WorkspaceManifestService(cryptoProvider)

    /** Recovery Key 带版本文本编解码器。 */
    private val recoveryKeyCodec: RecoveryKeyCodec = RecoveryKeyCodec(cryptoProvider)

    /** 尚未由用户确认 Recovery Key 的创建流程。 */
    private var pendingCreation: PendingDesktopWorkspaceCreation? = null

    /** 当前已解锁会话。 */
    @Volatile
    private var activeSession: DesktopWorkspaceSession? = null

    /** 当前会话工作区根目录；锁定时同步清除。 */
    private var activeRoot: Path? = null

    /**
     * 在空目录中准备新工作区，但不写入任何文件。
     *
     * @param root 用户选择的新工作区目录。
     * @param password 初始解锁密码。
     * @return 只展示一次的 Recovery Key 和工作区 ID。
     */
    suspend fun prepareCreation(
        root: Path,
        password: String,
    ): PreparedDesktopWorkspaceCreation {
        require(password.length >= MINIMUM_PASSWORD_CHARACTERS) { "工作区密码至少需要 10 个字符" }
        check(activeSession == null) { "必须先锁定当前工作区" }
        cancelPendingCreation()
        cryptoProvider.initialize()
        val lifecycle = JvmWorkspaceLifecycleService(root, manifestService)
        val creation = lifecycle.prepareCreation(
            workspaceId = WorkspaceId(idFactory()),
            keyId = KeyId(idFactory()),
            password = password,
            argon2idParameters = argon2idFactory(cryptoProvider.secureRandom(CryptoProtocol.ARGON2_SALT_BYTES)),
            passwordSlotId = idFactory(),
            recoverySlotId = idFactory(),
        )
        return try {
            val presentation = PreparedDesktopWorkspaceCreation(
                workspaceId = WorkspaceManifestCodec.decode(creation.manifestBytes).publicHeader.workspaceId,
                recoveryKeyText = recoveryKeyCodec.encode(creation.recoveryKey),
            )
            pendingCreation = PendingDesktopWorkspaceCreation(root, lifecycle, creation)
            presentation
        } catch (error: Throwable) {
            creation.destroySecrets()
            throw error
        }
    }

    /**
     * 在用户明确确认已离线保存 Recovery Key 后创建并打开工作区。
     *
     * @return 已完成恢复和索引校验的打开状态。
     */
    suspend fun confirmCreation(): DesktopWorkspaceOpenState {
        val pending = pendingCreation ?: error("没有待确认的工作区创建流程")
        pending.lifecycle.confirmCreation(pending.creation, recoveryKeyConfirmed = true)
        val payload = manifestService.inspectWithMasterKey(
            pending.creation.manifestBytes,
            pending.creation.workspaceMasterKey,
        )
        val unlocked = UnlockedWorkspaceManifest(
            file = WorkspaceManifestCodec.decode(pending.creation.manifestBytes),
            payload = payload,
            workspaceMasterKey = pending.creation.workspaceMasterKey,
        )
        pending.creation.recoveryKey.destroy()
        pendingCreation = null
        return openSession(pending.root, unlocked)
    }

    /**
     * 放弃尚未确认的创建流程并清零主密钥与 Recovery Key。
     */
    fun cancelPendingCreation() {
        pendingCreation?.creation?.destroySecrets()
        pendingCreation = null
    }

    /**
     * 使用密码打开已有工作区并先执行事务恢复。
     *
     * @param root 包含 `manifest.enc` 的工作区根目录。
     * @param password 用户本次输入密码。
     * @return 已确认 Note、恢复报告和只读状态。
     */
    suspend fun unlock(
        root: Path,
        password: String,
    ): DesktopWorkspaceOpenState {
        check(activeSession == null) { "必须先锁定当前工作区" }
        cancelPendingCreation()
        val unlocked = JvmWorkspaceLifecycleService(root, manifestService).unlockWithPassword(password)
        return openSession(root, unlocked)
    }

    /**
     * 使用带校验码的 Recovery Key 打开已有工作区。
     *
     * @param root 包含 `manifest.enc` 的工作区根目录。
     * @param recoveryKeyText 用户从离线介质提供的 Recovery Key 文本。
     * @return 已确认 Note、恢复报告和只读状态。
     */
    suspend fun unlockWithRecovery(
        root: Path,
        recoveryKeyText: String,
    ): DesktopWorkspaceOpenState {
        check(activeSession == null) { "必须先锁定当前工作区" }
        cancelPendingCreation()
        // Recovery Key 校验码依赖密码学 Provider；必须先初始化，不能等到 manifest 解包阶段再初始化。
        cryptoProvider.initialize()
        val recoveryKey = recoveryKeyCodec.decode(recoveryKeyText)
        val unlocked = try {
            JvmWorkspaceLifecycleService(root, manifestService).unlockWithRecovery(recoveryKey)
        } finally {
            recoveryKey.destroy()
        }
        return openSession(root, unlocked)
    }

    /**
     * 使用 Recovery Key 认证既有工作区，并为该设备建立新的高熵解锁材料后打开会话。
     *
     * 为什么必须先用 Recovery Key 认证再替换密码槽：本机 PIN 只是本机材料的保护层，
     * 不能成为遗失本机配置后的恢复后门；只有持有独立 Recovery Key 才能重建本机访问。
     *
     * @param root 包含 `manifest.enc` 的工作区根目录。
     * @param recoveryKeyText 用户离线保存的 Recovery Key 文本。
     * @param newPassword 新生成、不会向用户展示的本机高熵解锁材料。
     * @return 使用新本机解锁材料重新认证后的打开状态。
     */
    suspend fun recoverAndReplacePasswordSlot(
        root: Path,
        recoveryKeyText: String,
        newPassword: String,
    ): DesktopWorkspaceOpenState {
        check(activeSession == null) { "必须先锁定当前工作区" }
        cancelPendingCreation()
        cryptoProvider.initialize()
        val lifecycle = JvmWorkspaceLifecycleService(root, manifestService)
        val recoveryKey = recoveryKeyCodec.decode(recoveryKeyText)
        val unlocked = try {
            lifecycle.unlockWithRecovery(recoveryKey)
        } finally {
            recoveryKey.destroy()
        }
        try {
            lifecycle.replacePasswordSlot(
                unlocked = unlocked,
                newPassword = newPassword,
                newParameters = argon2idFactory(cryptoProvider.secureRandom(CryptoProtocol.ARGON2_SALT_BYTES)),
                newPasswordSlotId = idFactory(),
            )
        } finally {
            unlocked.destroy()
        }
        return unlock(root, newPassword)
    }

    /**
     * 在未打开会话时认证旧密码并仅重包主密钥，不重加密对象正文。
     *
     * @param root 既有工作区根目录。
     * @param oldPassword 当前密码。
     * @param newPassword 新密码。
     */
    suspend fun changePassword(
        root: Path,
        oldPassword: String,
        newPassword: String,
    ) {
        check(activeSession == null) { "更换密码前必须锁定工作区" }
        require(newPassword.length >= MINIMUM_PASSWORD_CHARACTERS) { "工作区密码至少需要 10 个字符" }
        cryptoProvider.initialize()
        JvmWorkspaceLifecycleService(root, manifestService).changePassword(
            oldPassword = oldPassword,
            newPassword = newPassword,
            newParameters = argon2idFactory(cryptoProvider.secureRandom(CryptoProtocol.ARGON2_SALT_BYTES)),
            newPasswordSlotId = idFactory(),
        )
    }

    /**
     * 更换当前已打开工作区的密码并让会话读取新 manifest 确认点。
     *
     * 对象信封和 Workspace Master Key 均保持不变；若凭据写入后会话刷新异常，则立即锁定，
     * 防止旧 manifest revision 继续参与写入。
     *
     * @param oldPassword 当前密码。
     * @param newPassword 新密码。
     * @return 刷新后的打开状态。
     */
    suspend fun changeActivePassword(
        oldPassword: String,
        newPassword: String,
    ): DesktopWorkspaceOpenState {
        val session = requireNotNull(activeSession) { "工作区尚未解锁" }
        val root = requireNotNull(activeRoot) { "活动工作区路径缺失" }
        require(newPassword.length >= MINIMUM_PASSWORD_CHARACTERS) { "工作区密码至少需要 10 个字符" }
        cryptoProvider.initialize()
        JvmWorkspaceLifecycleService(root, manifestService).changePassword(
            oldPassword = oldPassword,
            newPassword = newPassword,
            newParameters = argon2idFactory(cryptoProvider.secureRandom(CryptoProtocol.ARGON2_SALT_BYTES)),
            newPasswordSlotId = idFactory(),
        )
        return try {
            session.refreshAfterCredentialChange()
        } catch (error: Throwable) {
            activeSession = null
            activeRoot = null
            runCatching { session.lock() }
            throw error
        }
    }

    /**
     * 认证当前密码后轮换 Workspace Master Key、全部权威信封和 Recovery Key。
     *
     * 轮换尝试一旦进入事务阶段就关闭旧会话；提交前失败时会使用旧密码重新打开旧确认点，
     * 提交成功则只允许新密码和新 Recovery Key 进入新会话。
     *
     * @param currentPassword 当前工作区密码。
     * @param newPassword 新主密钥密码槽使用的密码。
     * @return 新会话状态和只展示一次的新 Recovery Key。
     */
    suspend fun rotateActiveMasterKey(
        currentPassword: String,
        newPassword: String,
    ): DesktopMasterKeyRotationOpenResult {
        val session = requireNotNull(activeSession) { "工作区尚未解锁" }
        val root = requireNotNull(activeRoot) { "活动工作区路径缺失" }
        require(newPassword.length >= MINIMUM_PASSWORD_CHARACTERS) { "工作区密码至少需要 10 个字符" }
        cryptoProvider.initialize()

        // 步骤 1：独立解锁当前 manifest，防止仅凭已打开会话即可静默轮换恢复凭据。
        JvmWorkspaceLifecycleService(root, manifestService).unlockWithPassword(currentPassword).destroy()
        val request = DesktopMasterKeyRotationRequest(
            newKeyId = KeyId(idFactory()),
            newPassword = newPassword,
            newParameters = argon2idFactory(cryptoProvider.secureRandom(CryptoProtocol.ARGON2_SALT_BYTES)),
            passwordSlotId = idFactory(),
            recoverySlotId = idFactory(),
            transactionId = TransactionId(idFactory()),
        )
        activeSession = null
        activeRoot = null

        return try {
            // 步骤 2：全部新信封与新 manifest 在同一事务确认，旧会话在调用返回前必定销毁。
            val rotation = session.rotateMasterKey(
                service = DesktopWorkspaceKeyRotationService(
                    paths = JvmWorkspacePaths(root),
                    fileSystem = session.fileSystem,
                    cryptoProvider = cryptoProvider,
                    manifestService = manifestService,
                ),
                request = request,
            )
            val recoveryKeyText = try {
                recoveryKeyCodec.encode(rotation.recoveryKey)
            } catch (error: Throwable) {
                rotation.destroy()
                throw error
            } finally {
                rotation.recoveryKey.destroy()
            }

            // 步骤 3：新派生索引使用新 keyId 派生密钥重建，随后才把会话重新暴露给 UI。
            val state = openSession(root, rotation.unlocked)
            DesktopMasterKeyRotationOpenResult(state, recoveryKeyText)
        } catch (error: Throwable) {
            // manifest 尚未切换时旧密码仍有效，恢复旧会话以保持失败操作的全旧状态。
            if (activeSession == null) {
                runCatching { unlock(root, currentPassword) }
            }
            throw error
        }
    }

    /**
     * 把密文备份原子恢复到空目录，并立即使用密码认证恢复结果。
     *
     * 恢复认证失败时保留目标目录，便于用户改用 Recovery Key 或检查备份；它不会替换任何
     * 已存在的非空工作区。
     *
     * @param archive Tagloom 密文备份文件。
     * @param targetRoot 不存在或为空的恢复目录。
     * @param password 原工作区密码。
     * @return 已认证、完成事务恢复并重建索引的打开状态。
     */
    suspend fun restoreBackup(
        archive: Path,
        targetRoot: Path,
        password: String,
    ): DesktopWorkspaceOpenState {
        check(activeSession == null) { "恢复备份前必须锁定当前工作区" }
        cancelPendingCreation()
        cryptoProvider.initialize()
        JvmEncryptedWorkspaceBackupService(targetRoot, manifestService).restoreBackup(archive, targetRoot)
        return unlock(targetRoot, password)
    }

    /**
     * 把密文备份恢复到空目录，再使用 Recovery Key 建立新的本机解锁材料。
     *
     * 备份本身只保存密文和 manifest；恢复后必须重新认证，不能因文件复制成功就把未认证
     * 的内容暴露给 UI 或索引。
     *
     * @param archive Tagloom 密文备份文件。
     * @param targetRoot 不存在或为空的恢复目录。
     * @param recoveryKeyText 用户离线保存的 Recovery Key 文本。
     * @param newPassword 新生成、不会向用户展示的本机高熵解锁材料。
     * @return 已认证、完成恢复并建立索引的打开状态。
     */
    suspend fun restoreBackupWithRecovery(
        archive: Path,
        targetRoot: Path,
        recoveryKeyText: String,
        newPassword: String,
    ): DesktopWorkspaceOpenState {
        check(activeSession == null) { "恢复备份前必须锁定当前工作区" }
        cancelPendingCreation()
        cryptoProvider.initialize()
        JvmEncryptedWorkspaceBackupService(targetRoot, manifestService).restoreBackup(archive, targetRoot)
        return recoverAndReplacePasswordSlot(targetRoot, recoveryKeyText, newPassword)
    }

    /**
     * 返回当前已解锁会话。
     *
     * @return 活动会话；尚未解锁时为空。
     */
    fun sessionOrNull(): DesktopWorkspaceSession? = activeSession

    /** @return 当前已认证工作区的本机目录；仅供平台服务绑定私有配置。 */
    internal fun rootOrNull(): Path? = activeRoot

    /**
     * 锁定活动会话并使全部读取、索引和写入入口立即失效。
     */
    suspend fun lock() {
        val session = activeSession ?: return
        activeSession = null
        activeRoot = null
        session.lock()
    }

    /**
     * 组装并初始化一个取得主密钥所有权的桌面会话。
     *
     * @param root 工作区根目录。
     * @param unlocked 已认证 manifest。
     * @return 打开状态。
     */
    private suspend fun openSession(
        root: Path,
        unlocked: UnlockedWorkspaceManifest,
    ): DesktopWorkspaceOpenState {
        var session: DesktopWorkspaceSession? = null
        return try {
            val fileSystem = workspaceFileSystemFactory(root, cryptoProvider)
            session = DesktopWorkspaceSessionFactory.open(
                root = root,
                fileSystem = fileSystem,
                cryptoProvider = cryptoProvider,
                manifestService = manifestService,
                unlocked = unlocked,
                actorId = ActorId(idFactory()),
                idFactory = idFactory,
                currentTimeMillis = currentTimeMillis,
                timestampProvider = timestampProvider,
            )
            val state = session.initialize()
            activeSession = session
            activeRoot = root.toAbsolutePath().normalize()
            state
        } catch (error: Throwable) {
            if (session == null) {
                unlocked.destroy()
            } else {
                runCatching { session.lock() }
            }
            throw error
        }
    }

    private companion object {
        /** 防止明显弱口令进入高成本 KDF；UI 还应提示使用密码管理器。 */
        const val MINIMUM_PASSWORD_CHARACTERS: Int = 10
    }
}

/**
 * 保存尚未落盘的桌面工作区创建上下文。
 */
private data class PendingDesktopWorkspaceCreation(
    /** 用户选择的空目录。 */
    val root: Path,
    /** 约束两阶段创建的 JVM 生命周期服务。 */
    val lifecycle: JvmWorkspaceLifecycleService,
    /** 尚未落盘且仍持有主密钥和 Recovery Key 的结果。 */
    val creation: WorkspaceCreationResult,
)
