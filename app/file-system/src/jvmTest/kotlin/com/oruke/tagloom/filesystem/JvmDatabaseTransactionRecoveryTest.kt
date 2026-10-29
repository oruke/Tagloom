package com.oruke.tagloom.filesystem

import com.oruke.tagloom.core.crypto.*
import com.oruke.tagloom.core.model.*
import com.oruke.tagloom.core.protocol.CanonicalJson
import com.oruke.tagloom.core.sync.HybridLogicalTime
import com.oruke.tagloom.core.transaction.*
import com.oruke.tagloom.core.workspace.*
import com.oruke.tagloom.filesystem.jvm.*
import com.oruke.tagloom.filesystem.jvm.crypto.LibsodiumCryptoProvider
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.JsonElement
import java.nio.file.Files
import java.nio.file.Path
import java.util.Comparator
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** 使用真实密文、文件 staging 和恢复入口验证 Database/View 事务的发布或回滚。 */
class JvmDatabaseTransactionRecoveryTest {
    /** 正式目录创建在 prepared 后中断，重启恢复必须同时确认 Database、View 与 Change。 */
    @Test
    fun completesPreparedCatalogTransactionThroughRealStorage() = runBlocking {
        withEnvironment { environment ->
            val database = database()
            val view = ViewDefinition(database.defaultViewId, database.id, "Example view", "a")
            val transactionId = TransactionId("catalog-interrupted")
            val interrupted = object : TransactionStoragePort by environment.storage {
                /** @param record 已持久化恢复记录；模拟进程在首次发布前终止。 */
                override suspend fun publishArtifacts(record: PreparedTransactionRecord) = error("simulated-interruption")
            }
            val service = DatabaseCatalogSnapshotTransactionService(environment.crypto, environment.envelopes,
                environment.manifestService, TransactionCoordinator(interrupted, environment.updater))
            assertFailsWith<IllegalStateException> {
                service.createCatalog(environment.unlocked, DatabaseCatalogCreateRequest(database, view,
                    WorkspaceDatabaseWriteContext(ActorId("example-device"), 10, transactionId,
                        HybridLogicalTime(10, 0, ActorId("example-device")), TEST_TIME)))
            }
            assertFalse(Files.exists(environment.root.resolve("databases/${database.id.value}.database.enc")))
            val recovery = TransactionCoordinator(environment.storage, environment.updater).recoverOnStartup()
            assertEquals(listOf(TransactionRecoveryEntry(transactionId, TransactionRecoveryAction.COMPLETED)), recovery.entries)
            assertFalse(recovery.requiresReadOnlyMode)
            val current = environment.manifestService.inspectWithMasterKey(Files.readAllBytes(environment.root.resolve("manifest.enc")),
                environment.creation.workspaceMasterKey)
            assertEquals(environment.unlocked.payload.revision + 1, current.revision)
            assertTrue(transactionId in current.committedTransactionIds)
            assertEquals(2, current.objects.count { it.kind in setOf(ManifestObjectKind.DATABASE, ManifestObjectKind.VIEW) })
            assertTrue(transactionId in environment.updater.transactions)
            val reader = DatabaseCatalogSnapshotEnvelopeService(environment.envelopes)
            val databaseRecord = current.objects.single { it.kind == ManifestObjectKind.DATABASE }
            val actualDatabase = reader.decryptDatabase(Files.readAllBytes(environment.root.resolve(databaseRecord.path.value)),
                TEST_WORKSPACE, ObjectId(database.id.value), databaseRecord.revision, TEST_KEY, environment.creation.workspaceMasterKey)
            val viewRecord = current.objects.single { it.kind == ManifestObjectKind.VIEW }
            val actualView = reader.decryptView(Files.readAllBytes(environment.root.resolve(viewRecord.path.value)), TEST_WORKSPACE,
                ObjectId(view.id.value), viewRecord.revision, TEST_KEY, environment.creation.workspaceMasterKey)
            assertEquals(database, actualDatabase.payload)
            assertEquals(view, actualView.payload)
            assertFalse(Files.exists(environment.root.resolve("staging/${transactionId.value}")))
        }
    }

    /** 同步到达的未来查询与展示类型只读保留，合法 View staging 不应因兼容提示回滚。 */
    @Test
    fun recoversForwardCompatibleViewWithoutTreatingCompatibilityAsCorruption() = runBlocking {
        withEnvironment { environment ->
            val view = ViewDefinition(ViewId("future-view"), database().id, "Future view", "a",
                query = QuerySpec(schemaVersion = 2, filter = FilterExpr(kind = "future-filter")),
                presentation = ViewPresentation(kind = "future-presentation"))
            val snapshot = snapshot("future-view", ObjectType.VIEW, ViewDefinition.serializer(), view)
            val transactionId = TransactionId("future-view-interrupted")
            environment.stageSnapshot(ManifestObjectKind.VIEW, snapshot, transactionId)
            val report = TransactionCoordinator(environment.storage, environment.updater).recoverOnStartup()
            assertEquals(TransactionRecoveryAction.COMPLETED, report.entries.single().action)
            val bytes = Files.readAllBytes(environment.root.resolve("views/future-view.view.enc"))
            val opened = DatabaseCatalogSnapshotEnvelopeService(environment.envelopes).decryptView(bytes, TEST_WORKSPACE,
                snapshot.header.objectId, "example-device:10", TEST_KEY, environment.creation.workspaceMasterKey)
            assertEquals(view, opened.payload)
            assertFalse(report.requiresReadOnlyMode)
        }
    }

    /** 身份、快照版本或已知视图结构错误时，恢复必须回滚且不确认任一损坏对象。 */
    @Test
    fun rollsBackInvalidCatalogSnapshotsWithoutPublishingThem() = runBlocking {
        val invalidDatabase = snapshot("database-example", ObjectType.DATABASE, DatabaseDefinition.serializer(),
            database().copy(id = DatabaseId("other-database")))
        val invalidVersion = snapshot("database-example", ObjectType.DATABASE, DatabaseDefinition.serializer(), database())
            .let { it.copy(header = it.header.copy(schemaVersion = 99)) }
        val invalidView = snapshot("invalid-view", ObjectType.VIEW, ViewDefinition.serializer(),
            ViewDefinition(ViewId("invalid-view"), database().id, "Invalid view", "a",
                query = QuerySpec(filter = FilterExpr(kind = FilterExprKinds.NOT))))
        for ((index, input) in listOf(ManifestObjectKind.DATABASE to invalidDatabase,
            ManifestObjectKind.DATABASE to invalidVersion, ManifestObjectKind.VIEW to invalidView).withIndex()) {
            withEnvironment { environment ->
                val before = Files.readAllBytes(environment.root.resolve("manifest.enc"))
                val transactionId = TransactionId("invalid-catalog-$index")
                val path = environment.stageSnapshot(input.first, input.second, transactionId)
                val report = TransactionCoordinator(environment.storage, environment.updater).recoverOnStartup()
                assertEquals(TransactionRecoveryAction.ROLLED_BACK, report.entries.single().action)
                assertEquals(before.toList(), Files.readAllBytes(environment.root.resolve("manifest.enc")).toList())
                assertFalse(Files.exists(environment.root.resolve(path.value)))
                assertFalse(report.requiresReadOnlyMode)
            }
        }
    }

    /**
     * 分配真实临时工作区并在测试结束后销毁密钥、删除本次专用目录。
     * @param block 当前真实恢复测试场景。
     */
    private suspend fun withEnvironment(block: suspend (CatalogRecoveryEnvironment) -> Unit) {
        val environment = CatalogRecoveryEnvironment.create()
        try { block(environment) } finally { environment.close() }
    }

    /** @return 与根 Note 类型关联的有效测试 Database。 */
    private fun database(): DatabaseDefinition = DatabaseDefinition(DatabaseId("database-example"), "Example database",
        WorkspaceDefaults.DEFAULT_NOTE_TAG_ID, creationTypeId = WorkspaceDefaults.DEFAULT_NOTE_TAG_ID, defaultViewId = ViewId("view-example"))

    /**
     * 为恢复故障注入生成完整快照，保持密文协议与生产 serializer 一致。
     * @param T 领域 payload 类型。
     * @param id 文件与快照身份。
     * @param type 业务快照类别。
     * @param serializer 完整领域 serializer。
     * @param payload 待编码对象。
     * @return 可用于真实信封的 JSON 快照。
     */
    private fun <T> snapshot(id: String, type: ObjectType, serializer: KSerializer<T>, payload: T): ObjectSnapshot<JsonElement> =
        ObjectSnapshot(SnapshotHeader(1, ObjectId(id), type, ObjectRevision(ActorId("example-device"), 10),
            createdAt = TEST_TIME, updatedAt = TEST_TIME), CanonicalJson.format.encodeToJsonElement(serializer, payload))

    private companion object {
        /** 仅为脱敏测试模型提供工作区身份。 */
        val TEST_WORKSPACE = WorkspaceId("catalog-recovery-workspace")
        /** 测试初始主密钥版本身份，不含密钥字节。 */
        val TEST_KEY = KeyId("catalog-recovery-key")
        /** 与主机地区及执行日期无关的 UTC 测试时间。 */
        const val TEST_TIME = "2026-01-01T00:00:00Z"
    }

    /** 持有真实加密工作区及文件存储，仅由本测试场景使用。 */
    private class CatalogRecoveryEnvironment(
        /** 本测试分配并验证过的唯一临时工作区。 */
        val root: Path,
        /** 真实平台密码实现。 */
        val crypto: LibsodiumCryptoProvider,
        /** 与生产相同的随机信封实现。 */
        val envelopes: EnvelopeCryptoService,
        /** 真实 manifest 创建、认证和确认入口。 */
        val manifestService: WorkspaceManifestService,
        /** 需要显式销毁的本次创建材料。 */
        val creation: WorkspaceCreationResult,
        /** 包含真实根 schema 的初始确认点。 */
        val unlocked: UnlockedWorkspaceManifest,
        /** 真正执行 staging、fsync、发布和恢复的文件存储。 */
        val storage: JvmTransactionStorage,
        /** 记录恢复后的派生更新调用，不替代任何加密或文件依赖。 */
        val updater: RecordingCatalogUpdater,
    ) {
        /**
         * 在真实 staging 中注入一个加密候选并中断于 prepared 后，供恢复验证拒绝非法模型。
         * @param kind 路径、信封和 manifest 对象类别。
         * @param snapshot 包含完整头部的测试候选。
         * @param transactionId 本次故障注入事务。
         * @return 预期最终发布的受控路径。
         */
        suspend fun stageSnapshot(kind: ManifestObjectKind, snapshot: ObjectSnapshot<JsonElement>, transactionId: TransactionId): WorkspaceRelativePath {
            val database = kind == ManifestObjectKind.DATABASE
            val path = WorkspaceRelativePath(if (database) "databases/${snapshot.header.objectId.value}.database.enc"
                else "views/${snapshot.header.objectId.value}.view.enc")
            val plaintext = CanonicalJson.encodeToString(ObjectSnapshot.serializer(JsonElement.serializer()), snapshot).encodeToByteArray()
            val encrypted = try { EncryptionEnvelopeCodec.encode(envelopes.encrypt(EnvelopeEncryptionRequest(
                EnvelopeIdentity(TEST_WORKSPACE, if (database) "database-snapshot" else "view-snapshot", snapshot.header.objectId.value,
                    "example-device:10"), TEST_KEY, WrappingKeyPurpose.SNAPSHOT, plaintext), creation.workspaceMasterKey))
            } finally { plaintext.fill(0) }
            val encryptedSha = hash(encrypted)
            val record = ManifestObjectRecord(kind, snapshot.header.objectId.value, "example-device:10", path, encryptedSha, TEST_KEY)
            val manifest = manifestService.confirmTransaction(unlocked, transactionId, listOf(record), emptySet())
            val plan = EncryptedTransactionPlan(transactionId, unlocked.payload.revision, unlocked.payload.revision + 1,
                listOf(EncryptedTransactionArtifact(path, encrypted, encryptedSha)), manifest, hash(manifest))
            storage.persistPrepared(storage.stage(plan))
            return path
        }

        /**
         * 销毁密钥，并只删除已核对位于系统临时目录中的本次专用目录。
         */
        fun close() {
            creation.destroySecrets()
            val normalized = root.toAbsolutePath().normalize()
            val temporaryParent = Path.of(System.getProperty("java.io.tmpdir")).toAbsolutePath().normalize()
            check(normalized.parent == temporaryParent && normalized.fileName.toString().startsWith("tagloom-catalog-recovery-"))
            Files.walk(normalized).use { stream -> stream.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists) }
        }

        /** @param bytes 待校验的密文。 @return 协议要求的小写 SHA-256。 */
        private fun hash(bytes: ByteArray): String = crypto.sha256(bytes).joinToString("") { (it.toInt() and 255).toString(16).padStart(2, '0') }

        companion object {
            /** @return 含真实根类型、基础属性和已确认初始事务的加密文件环境。 */
            suspend fun create(): CatalogRecoveryEnvironment {
                val root = Files.createTempDirectory("tagloom-catalog-recovery-")
                val crypto = LibsodiumCryptoProvider().also { it.initialize() }
                val envelopes = EnvelopeCryptoService(crypto)
                val service = WorkspaceManifestService(crypto)
                val creation = service.createWorkspace(TEST_WORKSPACE, TEST_KEY, "example-password",
                    Argon2idParameters(crypto.secureRandom(16), 8, 1, 1), "password-slot", "recovery-slot")
                Files.write(root.resolve("manifest.enc"), creation.manifestBytes)
                val initial = UnlockedWorkspaceManifest(WorkspaceManifestCodec.decode(creation.manifestBytes),
                    service.inspectWithMasterKey(creation.manifestBytes, creation.workspaceMasterKey), creation.workspaceMasterKey)
                val verifier = JvmWorkspaceContentVerifier(TEST_WORKSPACE, TEST_KEY, creation.workspaceMasterKey, envelopes, service)
                val storage = JvmTransactionStorage(root, verifier)
                val updater = RecordingCatalogUpdater()
                val bootstrap = WorkspaceBootstrapTransactionService(crypto, envelopes, service, TransactionCoordinator(storage, updater))
                    .createDefaultNoteTag(initial, ActorId("example-device"), 1, TransactionId("bootstrap-example"),
                        HybridLogicalTime(1, 0, ActorId("example-device")), TEST_TIME)
                val unlocked = UnlockedWorkspaceManifest(WorkspaceManifestCodec.decode(bootstrap.encryptedManifest),
                    service.inspectWithMasterKey(bootstrap.encryptedManifest, creation.workspaceMasterKey), creation.workspaceMasterKey)
                return CatalogRecoveryEnvironment(root, crypto, envelopes, service, creation, unlocked, storage, updater)
            }
        }
    }

    /** 记录真实文件事务确认后派生更新的调用，用于检查恢复顺序。 */
    private class RecordingCatalogUpdater : DerivedStateUpdater {
        /** 已确认后才通知更新的事务集合。 */
        val transactions = mutableListOf<TransactionId>()
        /** @param transactionId 已由真实 manifest 确认的事务身份。 */
        override suspend fun update(transactionId: TransactionId) { transactions += transactionId }
    }
}
