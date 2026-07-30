package com.oruke.tagloom.filestore.jvm

import com.oruke.tagloom.core.crypto.Argon2idParameters
import com.oruke.tagloom.core.crypto.SecretKeyMaterial
import com.oruke.tagloom.core.model.KeyId
import com.oruke.tagloom.core.model.WorkspaceId
import com.oruke.tagloom.core.workspace.UnlockedWorkspaceManifest
import com.oruke.tagloom.core.workspace.WorkspaceCreationResult
import com.oruke.tagloom.core.workspace.WorkspaceManifestService
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path

/**
 * 在 JVM 文件系统上约束工作区创建确认、解锁、换密码和锁定的生命周期顺序。
 *
 * @param workspaceRoot 用户选择且必须为空的新工作区，或包含 `manifest.enc` 的既有工作区。
 */
class JvmWorkspaceLifecycleService(
    workspaceRoot: Path,
    /** 负责 manifest 密钥槽和认证载荷的领域服务。 */
    private val manifestService: WorkspaceManifestService,
    /** 负责 manifest 原子替换和目录持久化。 */
    private val atomicFiles: JvmAtomicFilePort = JvmAtomicFileOperations(),
) {
    /** 规范化工作区路径并集中执行路径安全检查。 */
    private val paths: JvmWorkspacePaths = JvmWorkspacePaths(workspaceRoot)

    /**
     * 在写入任何文件前生成工作区主密钥、Recovery Key 和初始 manifest。
     *
     * @param workspaceId 新工作区随机标识。
     * @param keyId 首个主密钥版本标识。
     * @param password 初始解锁密码。
     * @param argon2idParameters 已校准且带随机 salt 的 KDF 参数。
     * @param passwordSlotId 密码槽稳定标识。
     * @param recoverySlotId Recovery Key 槽稳定标识。
     * @return 尚未落盘且由调用方负责销毁密钥的创建结果。
     */
    suspend fun prepareCreation(
        workspaceId: WorkspaceId,
        keyId: KeyId,
        password: String,
        argon2idParameters: Argon2idParameters,
        passwordSlotId: String,
        recoverySlotId: String,
    ): WorkspaceCreationResult = manifestService.createWorkspace(
        workspaceId = workspaceId,
        keyId = keyId,
        password = password,
        argon2idParameters = argon2idParameters,
        passwordSlotId = passwordSlotId,
        recoverySlotId = recoverySlotId,
    )

    /**
     * 只有用户明确确认已保存 Recovery Key 后才原子写入初始 manifest。
     *
     * @param creation 尚未落盘的创建结果。
     * @param recoveryKeyConfirmed UI 已取得用户明确确认时为 `true`。
     */
    fun confirmCreation(
        creation: WorkspaceCreationResult,
        recoveryKeyConfirmed: Boolean,
    ) {
        require(recoveryKeyConfirmed) { "必须先确认 Recovery Key 已离线保存" }
        requireSafeEmptyWorkspace()
        atomicFiles.writeAtomically(paths.manifest, creation.manifestBytes)
    }

    /**
     * 从磁盘读取 manifest 并使用密码创建已认证解锁会话。
     *
     * @param password 用户本次输入的密码。
     * @return 持有会话主密钥且必须显式锁定的 manifest。
     */
    suspend fun unlockWithPassword(password: String): UnlockedWorkspaceManifest =
        manifestService.unlockWithPassword(readManifest(), password)

    /**
     * 从磁盘读取 manifest 并使用 Recovery Key 创建已认证解锁会话。
     *
     * @param recoveryKey 用户从离线介质提供的 Recovery Key。
     * @return 持有会话主密钥且必须显式锁定的 manifest。
     */
    suspend fun unlockWithRecovery(recoveryKey: SecretKeyMaterial): UnlockedWorkspaceManifest =
        manifestService.unlockWithRecovery(readManifest(), recoveryKey)

    /**
     * 认证旧密码后原子替换仅重包主密钥的新 manifest。
     *
     * @param oldPassword 当前密码。
     * @param newPassword 新密码。
     * @param newParameters 新密码的 KDF 参数和随机 salt。
     * @param newPasswordSlotId 新密码槽稳定标识。
     */
    suspend fun changePassword(
        oldPassword: String,
        newPassword: String,
        newParameters: Argon2idParameters,
        newPasswordSlotId: String,
    ) {
        val changed = manifestService.changePassword(
            manifestBytes = readManifest(),
            oldPassword = oldPassword,
            newPassword = newPassword,
            newParameters = newParameters,
            newPasswordSlotId = newPasswordSlotId,
        )
        atomicFiles.writeAtomically(paths.manifest, changed)
    }

    /**
     * 使用已经通过 Recovery Key 或已授权设备认证的会话主密钥原子替换活动密码槽。
     *
     * 该操作用于重新建立本机解锁材料。它不重写对象信封，也不改变 Recovery Key，
     * 因此恢复后的工作区仍保持原有权威快照和独立恢复能力。
     *
     * @param unlocked 当前已认证且尚未销毁的 manifest。
     * @param newPassword 新密码槽使用的高熵本机解锁材料。
     * @param newParameters 新密码槽的 Argon2id 参数。
     * @param newPasswordSlotId 新密码槽稳定标识。
     */
    suspend fun replacePasswordSlot(
        unlocked: UnlockedWorkspaceManifest,
        newPassword: String,
        newParameters: Argon2idParameters,
        newPasswordSlotId: String,
    ) {
        val changed = manifestService.replacePasswordSlot(
            unlocked = unlocked,
            newPassword = newPassword,
            newParameters = newParameters,
            newPasswordSlotId = newPasswordSlotId,
        )
        atomicFiles.writeAtomically(paths.manifest, changed)
    }

    /**
     * 锁定工作区并主动清零会话持有的 Workspace Master Key。
     *
     * @param unlocked 当前已解锁会话。
     */
    fun lock(unlocked: UnlockedWorkspaceManifest) {
        unlocked.destroy()
    }

    /**
     * 读取常规且非符号链接的 `manifest.enc` 文件。
     *
     * @return 完整 manifest 字节。
     */
    private fun readManifest(): ByteArray {
        require(Files.isRegularFile(paths.manifest, LinkOption.NOFOLLOW_LINKS)) {
            "所选目录不是 Tagloom 工作区：缺少 manifest.enc。请选择原工作区目录，或先从密文备份恢复"
        }
        require(!Files.isSymbolicLink(paths.manifest)) { "工作区 manifest 不能是符号链接" }
        return Files.readAllBytes(paths.manifest)
    }

    /**
     * 确保创建目标不是符号链接，且除允许不存在外必须是空目录。
     */
    private fun requireSafeEmptyWorkspace() {
        require(!Files.isSymbolicLink(paths.root)) { "工作区根目录不能是符号链接" }
        if (!Files.exists(paths.root, LinkOption.NOFOLLOW_LINKS)) {
            return
        }
        require(Files.isDirectory(paths.root, LinkOption.NOFOLLOW_LINKS)) { "工作区路径不是目录" }
        val hasContent = Files.list(paths.root).use { stream -> stream.findAny().isPresent }
        require(!hasContent) { "新工作区目录必须为空" }
    }
}
