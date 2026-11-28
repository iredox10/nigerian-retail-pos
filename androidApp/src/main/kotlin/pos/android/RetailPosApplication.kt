package pos.android

import android.app.Application
import org.bouncycastle.jce.provider.BouncyCastleProvider
import pos.security.ApprovalCryptography

/** Use the same exportable Ed25519 representation as desktop and historical proofs. */
class RetailPosApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        ApprovalCryptography.installProvider(signingProvider)
    }
    companion object { private val signingProvider = BouncyCastleProvider() }
}
