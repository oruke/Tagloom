package com.oruke.tagloom.workspace

import com.oruke.tagloom.core.crypto.Argon2idParameters
import com.oruke.tagloom.core.crypto.CryptoProtocol
import com.oruke.tagloom.core.crypto.CryptoProvider
import com.oruke.tagloom.filesystem.jvm.crypto.LibsodiumCryptoProvider
import java.nio.charset.StandardCharsets
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.PosixFilePermission
import java.util.Base64
import java.util.UUID
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * 保存最近工作区及其仅限本机使用的解锁材料。
 *
 * 默认使用当前 Windows 用户的 DPAPI 保护高熵材料，以便应用启动后无需额外输入即可打开；
 * 用户主动启用 PIN 后，登记会改为由 PIN 派生密钥加密。无论使用哪种模式，PIN、Recovery
 * Key、Workspace Master Key 和明文工作区内容均不得写入登记文件。
 *
 * @param registryFile 本机访问登记文件；不得位于任何工作区目录内。
 * @param cryptoProvider 提供 PIN KDF、随机数和 AEAD 的密码学适配器。
 * @param argon2idFactory 生成 PIN 保护层使用的 Argon2id 参数；测试可注入低成本参数。
 * @param deviceProtector 保护未启用 PIN 的材料；Windows 产品实现使用 DPAPI。
 */
class DesktopWorkspaceAccessStore internal constructor(
    private val registryFile: Path = DesktopWorkspaceAccessPaths.defaultRegistryFile(),
    private val cryptoProvider: CryptoProvider = LibsodiumCryptoProvider(),
    private val argon2idFactory: (ByteArray) -> Argon2idParameters = { salt -> Argon2idParameters(salt) },
    private val deviceProtector: DesktopWorkspaceCredentialProtector = WindowsWorkspaceCredentialProtector(),
) {
    /**
     * 返回本机最近登记工作区的安全展示信息。
     *
     * @return 没有登记工作区时为空；返回对象不包含本机绝对路径或解锁材料。
     */
    suspend fun activeWorkspaceOrNull(): DesktopWorkspaceAccessTarget? =
        readRegistry().activeCredential?.let { credential ->
            DesktopWorkspaceAccessTarget(
                recordId = credential.recordId,
                displayName = credential.displayName,
                requiresPin = credential.protection == DesktopWorkspaceCredentialProtection.PIN,
            )
        }

    /**
     * 为指定工作区生成一次新的高熵本机解锁材料。
     *
     * 调用方必须在创建或 Recovery Key 重新认证成功后，通过 [rememberAsActive] 持久化；
     * 若流程取消或失败，必须调用返回对象的 [DesktopWorkspaceAccessCredential.destroy]。
     *
     * @param workspaceRoot 将要创建、恢复或重新登记的工作区根目录。
     * @param pin 用户准备启用的 PIN；为空时将采用 Windows 帐户保护。
     * @return 仅在内存中持有随机解锁材料的凭据。
     */
    suspend fun createCredential(
        workspaceRoot: Path,
        pin: String?,
    ): DesktopWorkspaceAccessCredential {
        pin?.let(::validatePin)
        cryptoProvider.initialize()
        val secret = cryptoProvider.secureRandom(CryptoProtocol.KEY_BYTES)
        return try {
            DesktopWorkspaceAccessCredential(workspaceRoot, secret)
        } finally {
            secret.fill(0)
        }
    }

    /**
     * 使用当前本机访问模式解开最近登记的高熵材料。
     *
     * @param pin 用户输入的 6 至 12 位数字 PIN；未启用 PIN 时必须为空。
     * @return 不存在登记工作区时为空；认证失败时不返回任何材料。
     */
    suspend fun unlockActiveCredential(pin: String?): DesktopWorkspaceAccessCredential? {
        val stored = readRegistry().activeCredential ?: return null
        val secret = when (stored.protection) {
            DesktopWorkspaceCredentialProtection.PIN -> unlockPinCredential(stored, requireNotNull(pin))
            DesktopWorkspaceCredentialProtection.WINDOWS_ACCOUNT -> {
                require(pin == null) { "workspace-access-pin-not-enabled" }
                unlockWindowsCredential(stored)
            }
        }
        return try {
            DesktopWorkspaceAccessCredential(Path.of(stored.workspacePath), secret)
        } finally {
            secret.fill(0)
        }
    }

    /**
     * 使用指定本机访问模式加密并原子保存一份高熵解锁材料。
     *
     * 同一目录重复登记时复用稳定 recordId，使认证上下文始终绑定同一个工作区身份；切换
     * 目录时生成新 recordId，防止不同本机记录之间的密文或 DPAPI 数据调包。
     *
     * @param credential 已在内存中持有的高熵本机解锁材料。
     * @param pin 用户为该本机记录设置的 6 至 12 位数字 PIN；为空时使用 Windows DPAPI。
     */
    suspend fun rememberAsActive(
        credential: DesktopWorkspaceAccessCredential,
        pin: String?,
    ) {
        pin?.let(::validatePin)
        cryptoProvider.initialize()
        val existing = readRegistry().activeCredential
        val root = credential.workspaceRoot.toAbsolutePath().normalize()
        val recordId = existing
            ?.takeIf { record -> record.workspacePath == root.toString() }
            ?.recordId
            ?: UUID.randomUUID().toString()
        val stored = if (pin == null) {
            createWindowsAccountRecord(credential, root, recordId)
        } else {
            createPinRecord(credential, root, recordId, pin)
        }
        writeRegistry(DesktopWorkspaceAccessRegistry(activeCredential = stored))
    }

    /**
     * 删除本机最近工作区登记，不影响任何工作区密文、Recovery Key 或备份。
     *
     * @return 无论登记原先是否存在，都在成功写入空登记后返回。
     */
    suspend fun forgetActiveWorkspace() {
        writeRegistry(DesktopWorkspaceAccessRegistry())
    }

    /**
     * 用 PIN 派生密钥保护高熵解锁材料，构造可持久化的本机登记记录。
     *
     * @param credential 仅在当前流程持有的高熵本机解锁材料。
     * @param root 已规范化的工作区根目录。
     * @param recordId 本机登记稳定随机标识。
     * @param pin 用户启用的 6 至 12 位数字 PIN。
     * @return 仅含 PIN 加密材料的登记记录。
     */
    private fun createPinRecord(
        credential: DesktopWorkspaceAccessCredential,
        root: Path,
        recordId: String,
        pin: String,
    ): DesktopWorkspaceCredentialRecord {
        val salt = cryptoProvider.secureRandom(CryptoProtocol.ARGON2_SALT_BYTES)
        val parameters = argon2idFactory(salt)
        val encodedSalt = encodeBase64(parameters.salt)
        val key = cryptoProvider.deriveArgon2id(pin, parameters)
        val nonce = cryptoProvider.secureRandom(CryptoProtocol.NONCE_BYTES)
        val associatedData = associatedData(recordId, root.toString())
        val ciphertext = try {
            credential.useSecret { secret ->
                cryptoProvider.encryptXChaCha20Poly1305(
                    plaintext = secret,
                    associatedData = associatedData,
                    nonce = nonce,
                    key = key,
                )
            }
        } finally {
            associatedData.fill(0)
            key.fill(0)
            salt.fill(0)
        }
        return try {
            DesktopWorkspaceCredentialRecord(
                recordId = recordId,
                workspacePath = root.toString(),
                displayName = root.fileName?.toString()?.takeIf(String::isNotBlank) ?: PRODUCT_NAME,
                protection = DesktopWorkspaceCredentialProtection.PIN,
                pinSalt = encodedSalt,
                pinMemoryKiB = parameters.memoryKiB,
                pinIterations = parameters.iterations,
                pinParallelism = parameters.parallelism,
                nonce = encodeBase64(nonce),
                ciphertext = encodeBase64(ciphertext),
            )
        } finally {
            nonce.fill(0)
            ciphertext.fill(0)
        }
    }

    /**
     * 用当前 Windows 用户的 DPAPI 保护高熵解锁材料，构造无需 PIN 的登记记录。
     *
     * @param credential 仅在当前流程持有的高熵本机解锁材料。
     * @param root 已规范化的工作区根目录。
     * @param recordId 本机登记稳定随机标识。
     * @return 仅含 Windows 帐户保护材料的登记记录。
     */
    private fun createWindowsAccountRecord(
        credential: DesktopWorkspaceAccessCredential,
        root: Path,
        recordId: String,
    ): DesktopWorkspaceCredentialRecord {
        val associatedData = associatedData(recordId, root.toString())
        val ciphertext = try {
            credential.useSecret { secret -> deviceProtector.protect(secret, associatedData) }
        } finally {
            associatedData.fill(0)
        }
        return try {
            DesktopWorkspaceCredentialRecord(
                recordId = recordId,
                workspacePath = root.toString(),
                displayName = root.fileName?.toString()?.takeIf(String::isNotBlank) ?: PRODUCT_NAME,
                protection = DesktopWorkspaceCredentialProtection.WINDOWS_ACCOUNT,
                ciphertext = encodeBase64(ciphertext),
            )
        } finally {
            ciphertext.fill(0)
        }
    }

    /**
     * 使用用户输入 PIN 解开登记的高熵材料。
     *
     * @param stored PIN 模式的持久化本机登记。
     * @param pin 用户输入的 6 至 12 位数字 PIN。
     * @return 认证通过后恢复的随机本机解锁材料。
     */
    private suspend fun unlockPinCredential(
        stored: DesktopWorkspaceCredentialRecord,
        pin: String,
    ): ByteArray {
        validatePin(pin)
        cryptoProvider.initialize()
        val parameters = stored.argon2idParameters()
        val key = cryptoProvider.deriveArgon2id(pin, parameters)
        val ciphertext = decodeBase64(stored.ciphertext)
        val nonce = decodeBase64(requireNotNull(stored.nonce) { "workspace-access-pin-nonce-missing" })
        val associatedData = associatedData(stored.recordId, stored.workspacePath)
        return try {
            cryptoProvider.decryptXChaCha20Poly1305(
                ciphertextAndTag = ciphertext,
                associatedData = associatedData,
                nonce = nonce,
                key = key,
            )
        } finally {
            associatedData.fill(0)
            parameters.salt.fill(0)
            key.fill(0)
            ciphertext.fill(0)
            nonce.fill(0)
        }
    }

    /**
     * 使用当前 Windows 用户的 DPAPI 解开登记的高熵材料。
     *
     * @param stored Windows 帐户模式的持久化本机登记。
     * @return 认证通过后恢复的随机本机解锁材料。
     */
    private fun unlockWindowsCredential(stored: DesktopWorkspaceCredentialRecord): ByteArray {
        val ciphertext = decodeBase64(stored.ciphertext)
        val associatedData = associatedData(stored.recordId, stored.workspacePath)
        return try {
            deviceProtector.unprotect(ciphertext, associatedData)
        } finally {
            associatedData.fill(0)
            ciphertext.fill(0)
        }
    }

    /**
     * 读取并校验本机访问登记。
     *
     * @return 不存在登记文件时返回空登记；损坏或不受支持的文件会抛出稳定内部错误。
     */
    private fun readRegistry(): DesktopWorkspaceAccessRegistry {
        if (!Files.exists(registryFile)) {
            return DesktopWorkspaceAccessRegistry()
        }
        check(Files.isRegularFile(registryFile)) { "workspace-access-registry-is-not-a-file" }
        check(!Files.isSymbolicLink(registryFile)) { "workspace-access-registry-is-a-symbolic-link" }
        val decoded = Json.decodeFromString(
            DesktopWorkspaceAccessRegistry.serializer(),
            Files.readString(registryFile, StandardCharsets.UTF_8),
        )
        check(decoded.formatVersion in LEGACY_FORMAT_VERSION..DesktopWorkspaceAccessRegistry.CURRENT_FORMAT_VERSION) {
            "workspace-access-registry-version-unsupported"
        }
        return decoded
    }

    /**
     * 将本机访问登记写入临时文件并原子替换旧文件。
     *
     * 登记只含路径展示信息和受保护的随机材料；临时文件不会包含 PIN、Recovery Key 或
     * Workspace Master Key。原子替换确保崩溃后最多保留完整旧登记或完整新登记。
     *
     * @param registry 待持久化的完整本机登记。
     */
    private fun writeRegistry(registry: DesktopWorkspaceAccessRegistry) {
        val parent = requireNotNull(registryFile.parent) { "workspace-access-registry-parent-missing" }
        Files.createDirectories(parent)
        if (Files.exists(registryFile)) {
            check(!Files.isSymbolicLink(registryFile)) { "workspace-access-registry-is-a-symbolic-link" }
        }
        val temporary = Files.createTempFile(parent, ".workspace-access-", ".tmp")
        try {
            Files.writeString(
                temporary,
                Json.encodeToString(DesktopWorkspaceAccessRegistry.serializer(), registry),
                StandardCharsets.UTF_8,
                StandardOpenOption.TRUNCATE_EXISTING,
            )
            try {
                Files.move(
                    temporary,
                    registryFile,
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING,
                )
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(temporary, registryFile, StandardCopyOption.REPLACE_EXISTING)
            }
            restrictOwnerPermissions()
        } finally {
            Files.deleteIfExists(temporary)
        }
    }

    /**
     * 在支持 POSIX 权限的开发或发行环境中限制登记文件为当前用户可读写。
     *
     * Windows 使用当前用户配置目录的 ACL；不能错误地把 POSIX 属性不可用当成持久化失败。
     */
    private fun restrictOwnerPermissions() {
        if (!Files.getFileStore(registryFile).supportsFileAttributeView("posix")) {
            return
        }
        Files.setPosixFilePermissions(
            registryFile,
            setOf(
                PosixFilePermission.OWNER_READ,
                PosixFilePermission.OWNER_WRITE,
            ),
        )
    }

    /**
     * 将本机记录身份与工作区路径组合为保护层认证上下文。
     *
     * @param recordId 本机登记的稳定随机标识。
     * @param workspacePath 已规范化的工作区绝对路径。
     * @return 仅参与认证、不写入受保护正文的固定上下文字节。
     */
    private fun associatedData(
        recordId: String,
        workspacePath: String,
    ): ByteArray = "$ACCESS_AAD_DOMAIN\u0000$recordId\u0000$workspacePath".encodeToByteArray()

    /**
     * 验证 PIN 采用明确的数字长度约束，避免把任意低熵文本伪装为便捷解锁码。
     *
     * @param pin 用户输入的本机 PIN。
     */
    private fun validatePin(pin: String) {
        require(PIN_PATTERN.matches(pin)) { "workspace-pin-invalid" }
    }

    /**
     * 使用 URL-safe Base64 保存二进制登记字段。
     *
     * @param bytes 待编码的二进制字段。
     * @return 不带填充字符的稳定文本。
     */
    private fun encodeBase64(bytes: ByteArray): String = BASE64_ENCODER.encodeToString(bytes)

    /**
     * 读取 URL-safe Base64 二进制登记字段。
     *
     * @param encoded 持久化的文本字段。
     * @return 新分配的二进制副本。
     */
    private fun decodeBase64(encoded: String): ByteArray = BASE64_DECODER.decode(encoded)

    private companion object {
        /** 当前实现仍可读取的最早 PIN 专用登记版本。 */
        const val LEGACY_FORMAT_VERSION: Int = 1

        /** 用户可记忆但不至于过低熵的本机 PIN 格式。 */
        val PIN_PATTERN: Regex = Regex("[0-9]{6,12}")

        /** 防止本机材料被移植到其他协议中的认证域分离文本。 */
        const val ACCESS_AAD_DOMAIN: String = "tagloom-desktop-workspace-access-v1"

        /** 本机登记缺省展示名仅在工作区目录没有文件名时使用。 */
        const val PRODUCT_NAME: String = "Tagloom"

        /** 本机登记二进制字段使用的 URL-safe Base64 编码器。 */
        val BASE64_ENCODER: Base64.Encoder = Base64.getUrlEncoder().withoutPadding()

        /** 本机登记二进制字段使用的 URL-safe Base64 解码器。 */
        val BASE64_DECODER: Base64.Decoder = Base64.getUrlDecoder()
    }
}
/**
 * 定义本机访问登记的可序列化受保护材料结构。
 */
@Serializable
private data class DesktopWorkspaceAccessRegistry(
    /** 结构化文件格式版本。 */
    val formatVersion: Int = CURRENT_FORMAT_VERSION,
    /** 当前默认工作区的本机保护解锁材料。 */
    val activeCredential: DesktopWorkspaceCredentialRecord? = null,
) {
    companion object {
        /** 当前登记文件格式版本。 */
        const val CURRENT_FORMAT_VERSION: Int = 2
    }
}

/**
 * 区分本机解锁材料使用的保护方式。
 */
@Serializable
private enum class DesktopWorkspaceCredentialProtection {
    /** 用用户主动设置的 PIN 和 Argon2id 保护材料。 */
    PIN,

    /** 用当前 Windows 用户帐户的 DPAPI 保护材料。 */
    WINDOWS_ACCOUNT,
}

/**
 * 保存一个工作区的安全展示信息和受保护的高熵本机解锁材料。
 */
@Serializable
private data class DesktopWorkspaceCredentialRecord(
    /** 本机登记稳定随机标识，参与保护层认证上下文。 */
    val recordId: String,
    /** 已规范化工作区绝对路径，仅用于下次定位本机目录。 */
    val workspacePath: String,
    /** 不读取工作区明文即可显示的目录名称。 */
    val displayName: String,
    /** 解锁材料当前采用的本机保护模式。 */
    val protection: DesktopWorkspaceCredentialProtection = DesktopWorkspaceCredentialProtection.PIN,
    /** PIN KDF 使用的随机 salt；Windows 帐户模式必须为空。 */
    val pinSalt: String? = null,
    /** PIN KDF 内存成本，单位 KiB；Windows 帐户模式必须为空。 */
    val pinMemoryKiB: Int? = null,
    /** PIN KDF 迭代次数；Windows 帐户模式必须为空。 */
    val pinIterations: Int? = null,
    /** PIN KDF 并行度；Windows 帐户模式必须为空。 */
    val pinParallelism: Int? = null,
    /** PIN AEAD 使用的一次性随机 nonce；Windows 帐户模式必须为空。 */
    val nonce: String? = null,
    /** PIN AEAD 或 Windows DPAPI 输出的高熵材料保护数据。 */
    val ciphertext: String,
) {
    /**
     * 还原并校验此 PIN 记录写入时使用的 KDF 参数。
     *
     * @return 可直接用于 Argon2id 派生的参数对象。
     */
    fun argon2idParameters(): Argon2idParameters {
        check(protection == DesktopWorkspaceCredentialProtection.PIN) {
            "workspace-access-pin-parameters-not-applicable"
        }
        return Argon2idParameters(
            salt = Base64.getUrlDecoder().decode(requireNotNull(pinSalt) { "workspace-access-pin-salt-missing" }),
            memoryKiB = requireNotNull(pinMemoryKiB) { "workspace-access-pin-memory-missing" },
            iterations = requireNotNull(pinIterations) { "workspace-access-pin-iterations-missing" },
            parallelism = requireNotNull(pinParallelism) { "workspace-access-pin-parallelism-missing" },
        )
    }
}
