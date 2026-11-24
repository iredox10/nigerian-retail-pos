package pos.cloud.client

import kotlinx.serialization.Serializable
import java.net.URI

@Serializable
@ConsistentCopyVisibility
data class CloudEndpoint private constructor(val url: String, val developmentHttp: Boolean = false) {
    init { validate(url, developmentHttp) }
    companion object {
        fun parse(value: String, allowDevelopmentHttp: Boolean = false): CloudEndpoint {
            val uri = validate(value.trim(), allowDevelopmentHttp)
            return CloudEndpoint(URI(uri.scheme.lowercase(), null, uri.host.lowercase(), uri.port, "", null, null).toASCIIString(), allowDevelopmentHttp && uri.scheme.equals("http", true))
        }
        private fun validate(value: String, development: Boolean): URI {
            require(value.length in 1..2048 && value.none { it.isISOControl() }) { "Enter a valid cloud server address." }
            val uri = runCatching { URI(value) }.getOrElse { throw IllegalArgumentException("Enter a valid cloud server address.") }
            require(uri.host != null && uri.rawUserInfo == null && uri.rawQuery == null && uri.rawFragment == null && uri.rawPath in listOf("", "/") && (uri.port == -1 || uri.port in 1..65535)) { "Use the cloud server's root address without a password, path or query." }
            val https = uri.scheme.equals("https", true)
            val localHttp = development && uri.scheme.equals("http", true) && uri.host.lowercase() in setOf("localhost", "127.0.0.1", "[::1]", "::1", "10.0.2.2")
            require(https || localHttp) { "Cloud connections require HTTPS. HTTP is allowed only for explicitly enabled local development." }
            return uri
        }
    }
}

class CloudHttpException(val status: Int, val code: String, message: String, val retryAfterSeconds: Long? = null) : RuntimeException(message)
class CloudConnectionException(message: String) : RuntimeException(message)
