package pos.android.cloud

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import pos.cloud.client.*
import java.security.KeyStore
import java.security.MessageDigest
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Tokens are encrypted with an installation-only key that never leaves Android Keystore. */
class AndroidCloudCredentials(private val files:PrivateCloudFiles):CloudCredentialStore {
    private val alias="retailpos-cloud-device-v1"
    @Synchronized private fun key():SecretKey {
        val store=KeyStore.getInstance("AndroidKeyStore").apply {load(null)}
        (store.getKey(alias,null) as? SecretKey)?.let {return it}
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES,"AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(alias,KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        }.generateKey()
    }
    private fun name(value:String)="secret-"+MessageDigest.getInstance("SHA-256").digest(value.toByteArray()).joinToString("") {"%02x".format(it)}
    override fun write(key:String,token:String) {
        require(token.length in 16..4096 && token.none {it.isWhitespace() || it.isISOControl()})
        val cipher=Cipher.getInstance("AES/GCM/NoPadding").apply {init(Cipher.ENCRYPT_MODE,key());updateAAD(key.toByteArray())}
        files.write(name(key),Base64.getEncoder().encodeToString(cipher.iv+cipher.doFinal(token.toByteArray())))
    }
    override fun read(key:String):String? {
        val stored=files.read(name(key),8192) ?:return null
        val bytes=Base64.getDecoder().decode(stored);require(bytes.size>28)
        return Cipher.getInstance("AES/GCM/NoPadding").run {
            init(Cipher.DECRYPT_MODE,key(),GCMParameterSpec(128,bytes.copyOfRange(0,12)));updateAAD(key.toByteArray())
            String(doFinal(bytes.copyOfRange(12,bytes.size)),Charsets.UTF_8)
        }
    }
    override fun remove(key:String)=files.remove(name(key))
}
