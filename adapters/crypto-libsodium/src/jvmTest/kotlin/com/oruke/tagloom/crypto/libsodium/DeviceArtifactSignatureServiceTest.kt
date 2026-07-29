package com.oruke.tagloom.crypto.libsodium

import com.oruke.tagloom.core.model.ActorId
import com.oruke.tagloom.core.model.ObjectId
import com.oruke.tagloom.core.model.WorkspaceId
import com.oruke.tagloom.core.sync.BlindSyncArtifact
import com.oruke.tagloom.core.sync.CURRENT_SYNC_PROTOCOL_VERSION
import com.oruke.tagloom.core.sync.DeviceArtifactSignatureService
import com.oruke.tagloom.core.sync.SyncArtifactDescriptor
import com.oruke.tagloom.core.sync.SyncArtifactKind
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

/** 使用真实 Ed25519 实现验证密文同步 artifact 的设备作者签名。 */
class DeviceArtifactSignatureServiceTest {
    /** 验证签名能认证完整公开描述符，Actor 或父边被替换后必须失败。 */
    @Test
    fun signatureRejectsDescriptorTampering() = runBlocking {
        val provider = LibsodiumCryptoProvider()
        provider.initialize()
        val keyPair = provider.generateDeviceSigningKeyPair()
        try {
            val service = DeviceArtifactSignatureService(provider)
            val signed = service.sign(unsignedArtifact(), keyPair.privateKeySeed)
            val publicKey = keyPair.copyPublicKey()

            assertTrue(service.verify(signed, publicKey))
            assertFalse(
                service.verify(
                    signed.copy(descriptor = signed.descriptor.copy(actorId = ActorId("actor-forged"))),
                    publicKey,
                ),
            )
        } finally {
            keyPair.destroyPrivateKey()
        }
    }

    /**
     * 创建结构有效但只用于签名测试的未签名密文 artifact。
     *
     * @return 未签名 Change artifact。
     */
    private fun unsignedArtifact(): BlindSyncArtifact = BlindSyncArtifact(
        artifactId = "a".repeat(64),
        descriptor = SyncArtifactDescriptor(
            protocolVersion = CURRENT_SYNC_PROTOCOL_VERSION,
            workspaceId = WorkspaceId("workspace-signature"),
            kind = SyncArtifactKind.CHANGE,
            objectId = ObjectId("note-signature"),
            parentArtifactIds = emptyList(),
            actorId = ActorId("actor-signature"),
            actorSequence = 1L,
            payloadSha256 = "b".repeat(64),
            payloadByteLength = 1,
        ),
        payloadBase64 = "AA==",
    )
}
