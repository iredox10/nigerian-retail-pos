package pos.security

import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.Provider
import java.security.Signature

/** Platform adapter for exportable Ed25519 keys. Does not alter global TLS/JCA provider order. */
object ApprovalCryptography {
    @Volatile private var configured: Provider? = null
    @Synchronized fun installProvider(provider: Provider) {
        require(configured == null || configured === provider) { "Approval cryptography is already initialized." }
        configured = provider
    }
    internal fun generator(): KeyPairGenerator = configured?.let { KeyPairGenerator.getInstance("Ed25519", it) }
        ?: KeyPairGenerator.getInstance("Ed25519")
    internal fun factory(): KeyFactory = configured?.let { KeyFactory.getInstance("Ed25519", it) }
        ?: KeyFactory.getInstance("Ed25519")
    internal fun signature(): Signature = configured?.let { Signature.getInstance("Ed25519", it) }
        ?: Signature.getInstance("Ed25519")
}
