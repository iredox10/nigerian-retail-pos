package pos.android.security

import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.junit.Assert.*
import org.junit.Test
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec

class ApprovalInteroperabilityTest {
    @Test fun androidSoftwareKeysAndSignaturesInteroperateWithDesktopInBothDirections() {
        val android = BouncyCastleProvider()
        val payload = "retail-pos-approved-action\u0000exact-reviewed-amount".toByteArray()
        val androidPair = KeyPairGenerator.getInstance("Ed25519", android).generateKeyPair()
        val desktopPair = KeyPairGenerator.getInstance("Ed25519", "SunEC").generateKeyPair()
        for ((signingPair, signerProvider, verifierProvider) in listOf(
            Triple(androidPair, android, java.security.Security.getProvider("SunEC")),
            Triple(desktopPair, java.security.Security.getProvider("SunEC"), android),
        )) {
            val factory = KeyFactory.getInstance("Ed25519", verifierProvider)
            val publicKey = factory.generatePublic(X509EncodedKeySpec(signingPair.public.encoded))
            val privateKey = factory.generatePrivate(PKCS8EncodedKeySpec(signingPair.private.encoded))
            assertEquals("X.509", publicKey.format)
            assertEquals("PKCS#8", privateKey.format)
            val signer = Signature.getInstance("Ed25519", signerProvider)
            signer.initSign(signingPair.private); signer.update(payload); val proof = signer.sign()
            assertEquals(64, proof.size)
            val verifier = Signature.getInstance("Ed25519", verifierProvider)
            verifier.initVerify(publicKey); verifier.update(payload); assertTrue(verifier.verify(proof))
            verifier.initVerify(publicKey); verifier.update(payload + 1.toByte()); assertFalse(verifier.verify(proof))
            val restored = Signature.getInstance("Ed25519", verifierProvider)
            restored.initSign(privateKey); restored.update(payload)
            assertArrayEquals(proof, restored.sign())
        }
    }
}
