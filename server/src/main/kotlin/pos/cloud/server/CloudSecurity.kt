package pos.cloud.server

import jakarta.servlet.FilterChain
import jakarta.servlet.ReadListener
import jakarta.servlet.ServletInputStream
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletRequestWrapper
import jakarta.servlet.http.HttpServletResponse
import kotlinx.serialization.encodeToString
import org.springframework.core.Ordered
import org.springframework.core.annotation.Order
import org.springframework.stereotype.Component
import org.springframework.web.filter.OncePerRequestFilter
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
import org.springframework.http.ResponseEntity
import org.springframework.http.MediaType
import pos.cloud.protocol.CloudApiError
import pos.cloud.protocol.CloudCodec
import java.io.BufferedReader
import java.io.InputStreamReader
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.concurrent.Semaphore
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

class CloudApiProblem(val status:Int,val code:String,override val message:String):RuntimeException(message)
internal fun badRequest(message:String):Nothing=throw CloudApiProblem(400,"invalid_request",message)
internal fun denied():Nothing=throw CloudApiProblem(403,"forbidden","This account or device cannot access this business.")
internal fun unauthenticated():Nothing=throw CloudApiProblem(401,"authentication_required","Sign in again to connect this device.")
internal fun sha256(value:String):String=MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8)).joinToString("") {"%02x".format(it)}
internal fun randomSecret():String=ByteArray(32).also(SecureRandom()::nextBytes).let {Base64.getUrlEncoder().withoutPadding().encodeToString(it)}
internal fun safeId(value:String):String {if(value.isBlank() || value.length>200 || value.any {it.isISOControl()}) badRequest("Use a valid operation reference of at most 200 characters.");return value}
internal fun safeName(value:String,max:Int=150):String {val name=value.trim();if(name.length !in 1..max || name.any {it.isISOControl()}) badRequest("Use a printable name of 1 to $max characters.");return name}
internal fun uuid(value:String):java.util.UUID=try {java.util.UUID.fromString(value).also {if(it.toString()!=value.lowercase())badRequest("Invalid identifier.")}} catch(_:IllegalArgumentException) {badRequest("Invalid identifier.")}

internal object CloudPassword {
    private const val ITERATIONS=310_000
    fun hash(password:String,salt:String):ByteArray {
        val chars=password.toCharArray()
        val spec=PBEKeySpec(chars,Base64.getDecoder().decode(salt),ITERATIONS,256)
        return try {SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded} finally {spec.clearPassword();chars.fill('\u0000')}
    }
    fun create(password:String):Pair<String,String> {
        if(password.length !in 12..1024)badRequest("Use a cloud password of 12 to 1024 characters.")
        val salt=Base64.getEncoder().encodeToString(ByteArray(24).also(SecureRandom()::nextBytes))
        return salt to Base64.getEncoder().encodeToString(hash(password,salt))
    }
    fun matches(password:String,salt:String,expected:String):Boolean {
        if(password.length>1024)return false
        val actual=hash(password,salt)
        return try {MessageDigest.isEqual(actual,Base64.getDecoder().decode(expected))} finally {actual.fill(0)}
    }
    val dummySalt:String=Base64.getEncoder().encodeToString(ByteArray(24))
    val dummyHash:String=Base64.getEncoder().encodeToString(ByteArray(32))
}

@RestControllerAdvice
class CloudErrors {
    @ExceptionHandler(CloudApiProblem::class)
    fun problem(error:CloudApiProblem):ResponseEntity<String> = ResponseEntity.status(error.status).contentType(MediaType.APPLICATION_JSON)
        .apply {if(error.status==429)header("Retry-After","60")}.body(CloudCodec.json.encodeToString(CloudApiError(error.code,error.message)))
    @ExceptionHandler(IllegalArgumentException::class,org.springframework.http.converter.HttpMessageNotReadableException::class)
    fun invalid(error:Exception):ResponseEntity<String> = ResponseEntity.badRequest().contentType(MediaType.APPLICATION_JSON).body(CloudCodec.json.encodeToString(CloudApiError("invalid_request","The request is incomplete or unsupported. No records were changed.")))
    @ExceptionHandler(Exception::class)
    fun unavailable(error:Exception):ResponseEntity<String> = ResponseEntity.internalServerError().contentType(MediaType.APPLICATION_JSON).body(CloudCodec.json.encodeToString(CloudApiError("temporarily_unavailable","The cloud service could not complete this request. Saved local records are unchanged; retry later.")))
}

/** Restricts request memory and accepts insecure HTTP only from loopback development or Heroku TLS termination. */
@Component @Order(Ordered.HIGHEST_PRECEDENCE)
class CloudTransportGuard:OncePerRequestFilter() {
    private val slots=Semaphore(2,true)
    override fun doFilterInternal(request:HttpServletRequest,response:HttpServletResponse,chain:FilterChain) {
        fun reject(status:Int,code:String,message:String) {response.status=status;response.contentType="application/json";response.writer.write(CloudCodec.json.encodeToString(CloudApiError(code,message)))}
        val loopback=request.remoteAddr in setOf("127.0.0.1","::1","0:0:0:0:0:0:0:1")
        val herokuTls=!System.getenv("DYNO").isNullOrBlank() && request.getHeader("X-Forwarded-Proto")=="https"
        if(!request.isSecure && !loopback && !herokuTls) {reject(400,"https_required","Use an HTTPS cloud address.");return}
        if(request.contentLengthLong>MAX_BODY) {reject(413,"request_too_large","The cloud request exceeds the supported size.");return}
        if(!slots.tryAcquire()) {response.setHeader("Retry-After","5");reject(503,"busy","Cloud storage is busy. Your local records remain saved; retry shortly.");return}
        try {
            val wrapped=object:HttpServletRequestWrapper(request) {
                private val bounded:ServletInputStream by lazy {
                    val source=request.inputStream
                    object:ServletInputStream() {
                        private var count=0L
                        private fun account(n:Int):Int {if(n>0)count+=n;if(count>MAX_BODY)throw java.io.IOException("Request size limit exceeded");return n}
                        override fun read():Int=source.read().also {if(it>=0)account(1)}
                        override fun read(b:ByteArray,off:Int,len:Int):Int=account(source.read(b,off,len))
                        override fun isFinished()=source.isFinished
                        override fun isReady()=source.isReady
                        override fun setReadListener(listener:ReadListener)=source.setReadListener(listener)
                    }
                }
                override fun getInputStream()=bounded
                override fun getReader()=BufferedReader(InputStreamReader(bounded,Charsets.UTF_8))
            }
            response.setHeader("Cache-Control","no-store")
            response.setHeader("X-Content-Type-Options","nosniff")
            chain.doFilter(wrapped,response)
        } finally {slots.release()}
    }
    companion object {const val MAX_BODY=96L*1024*1024}
}
