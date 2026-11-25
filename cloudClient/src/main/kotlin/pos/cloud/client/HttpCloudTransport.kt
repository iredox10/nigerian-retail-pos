package pos.cloud.client

import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import pos.cloud.protocol.*
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URLEncoder
import java.util.zip.GZIPInputStream

/** No redirects, permissive TLS, body logging or credentials in URLs. */
class HttpCloudTransport(
    private val connectTimeoutMillis: Int = 15_000,
    private val readTimeoutMillis: Int = 45_000,
    private val maximumBytes: Int = 96 * 1024 * 1024,
) : CloudTransport {
    init { require(connectTimeoutMillis in 1..120_000 && readTimeoutMillis in 1..180_000 && maximumBytes in 1024..128 * 1024 * 1024) }
    override fun startPairing(endpoint:CloudEndpoint,request:CloudPairingStart):CloudPairingTicket = decode(exchange(endpoint,"POST","/api/v1/pairings",null,CloudCodec.encode(request)))
    override fun reviewPairing(endpoint:CloudEndpoint,accountToken:String,businessId:String,request:CloudPairingCode):CloudPairingReview = decode(exchange(endpoint,"POST",businessPath(businessId)+"/pairings/review",accountToken,CloudCodec.encode(request)))
    override fun approvePairing(endpoint:CloudEndpoint,accountToken:String,businessId:String,request:CloudPairingCode) {exchange(endpoint,"POST",businessPath(businessId)+"/pairings/approve",accountToken,CloudCodec.encode(request))}
    override fun claimPairing(endpoint:CloudEndpoint,request:CloudPairingClaim):CloudPairingResult = decode(exchange(endpoint,"POST","/api/v1/pairings/claim",null,CloudCodec.encode(request)))
    override fun finishPairing(endpoint:CloudEndpoint,request:CloudPairingClaim) {exchange(endpoint,"POST","/api/v1/pairings/finish",null,CloudCodec.encode(request))}
    override fun capabilities(endpoint:CloudEndpoint):CloudAccountCapabilities = decode(exchange(endpoint,"GET","/api/v1/accounts/capabilities",null))
    override fun sendVerification(endpoint:CloudEndpoint,token:String):CloudAccountNotice = decode(exchange(endpoint,"POST","/api/v1/accounts/verification/send",token))
    override fun verifyEmail(endpoint:CloudEndpoint,token:String,request:CloudVerifyEmail):CloudAccountNotice = decode(exchange(endpoint,"POST","/api/v1/accounts/verification/confirm",token,CloudCodec.encode(request)))
    override fun requestReset(endpoint:CloudEndpoint,request:CloudEmailRequest):CloudAccountNotice = decode(exchange(endpoint,"POST","/api/v1/accounts/password/request",null,CloudCodec.encode(request)))
    override fun resetPassword(endpoint:CloudEndpoint,request:CloudResetPassword):CloudAccountNotice = decode(exchange(endpoint,"POST","/api/v1/accounts/password/reset",null,CloudCodec.encode(request)))
    override fun deviceDetails(endpoint:CloudEndpoint,accountToken:String,businessId:String):List<CloudDeviceDetails> = decode(exchange(endpoint,"GET",businessPath(businessId)+"/devices/details",accountToken))
    override fun register(endpoint: CloudEndpoint, request: CloudAccountRequest): CloudAccountSession = decode(exchange(endpoint,"POST","/api/v1/accounts/register",null,CloudCodec.json.encodeToString(request)))
    override fun login(endpoint: CloudEndpoint, request: CloudAccountRequest): CloudAccountSession = decode(exchange(endpoint,"POST","/api/v1/accounts/login",null,CloudCodec.json.encodeToString(request)))
    override fun businesses(endpoint: CloudEndpoint, accountToken: String): List<CloudBusinessSummary> = decode(exchange(endpoint,"GET","/api/v1/businesses",accountToken))
    override fun createBusiness(endpoint: CloudEndpoint, accountToken: String, request: CloudCreateBusinessRequest): CloudBusinessSummary = decode(exchange(endpoint,"POST","/api/v1/businesses",accountToken,CloudCodec.json.encodeToString(request)))
    override fun enrollDevice(endpoint: CloudEndpoint, accountToken: String, businessId: String, request: CloudDeviceEnrollRequest): CloudDeviceSession = decode(exchange(endpoint,"POST",businessPath(businessId)+"/devices",accountToken,CloudCodec.json.encodeToString(request)))
    override fun devices(endpoint: CloudEndpoint, accountToken: String, businessId: String): List<CloudDeviceSummary> = decode(exchange(endpoint,"GET",businessPath(businessId)+"/devices",accountToken))
    override fun revokeDevice(endpoint: CloudEndpoint, accountToken: String, businessId: String, deviceId: String) { exchange(endpoint,"DELETE",businessPath(businessId)+"/devices/"+segment(deviceId),accountToken) }
    override fun bootstrap(endpoint: CloudEndpoint, deviceToken: String, businessId: String): CloudBootstrapReply = decode(exchange(endpoint,"GET",businessPath(businessId)+"/bootstrap",deviceToken))
    override fun push(endpoint: CloudEndpoint, deviceToken: String, businessId: String, request: CloudPushRequest): CloudPushReply = decode(exchange(endpoint,"POST",businessPath(businessId)+"/sync/push",deviceToken,CloudCodec.json.encodeToString(request)))
    override fun pull(endpoint: CloudEndpoint, deviceToken: String, businessId: String, afterRevision: Long, limit: Int): CloudPullReply {
        require(afterRevision>=0 && limit in 1..100)
        return decode(exchange(endpoint,"GET",businessPath(businessId)+"/sync/pull?after=$afterRevision&limit=$limit",deviceToken))
    }
    private fun businessPath(id: String)="/api/v1/businesses/"+segment(id)
    private fun segment(value:String):String {require(value.length in 1..200 && value.none {it.isISOControl()});return URLEncoder.encode(value,"UTF-8").replace("+","%20")}
    private inline fun <reified T> decode(value:String):T = try {CloudCodec.json.decodeFromString(value)} catch(_:Exception) {throw CloudConnectionException("The server returned an unsupported response. Saved local records were kept.")}
    private fun exchange(endpoint:CloudEndpoint,method:String,path:String,token:String?,body:String?=null):String {
        // Revalidate serialized endpoints too, not only values constructed by the UI.
        val checked=CloudEndpoint.parse(endpoint.url,endpoint.developmentHttp)
        require(token==null || token.length in 16..4096 && token.none {it.isISOControl() || it.isWhitespace()}) {"Cloud authorization is invalid. Sign in again."}
        val connection=java.net.URL(checked.url+path).openConnection() as HttpURLConnection
        try {
            connection.requestMethod=method;connection.instanceFollowRedirects=false
            connection.connectTimeout=connectTimeoutMillis;connection.readTimeout=readTimeoutMillis
            connection.setRequestProperty("Accept","application/json");connection.setRequestProperty("Accept-Encoding","gzip")
            if(token!=null) connection.setRequestProperty("Authorization","Bearer $token")
            if(body!=null) {
                val bytes=body.toByteArray(Charsets.UTF_8)
                require(bytes.size<=maximumBytes) {"This sync batch exceeds the supported transfer size. Local records were kept."}
                connection.doOutput=true;connection.setRequestProperty("Content-Type","application/json; charset=utf-8")
                connection.setFixedLengthStreamingMode(bytes.size)
                connection.outputStream.use {it.write(bytes)}
            }
            val status=connection.responseCode
            if(status !in 200..299) {
                val error=connection.errorStream?.use {readBounded(it,16*1024)}.orEmpty()
                val code=runCatching {CloudCodec.json.parseToJsonElement(error).jsonObject["code"]?.jsonPrimitive?.content}.getOrNull()?.takeIf {it.matches(Regex("[a-z_]{1,80}"))} ?: "cloud_request_failed"
                val message=when(code) {
                    "email_unavailable"->"Email recovery is not configured yet. Your local shop access is unchanged."
                    "pairing_expired","pairing_unavailable"->"This device code is incorrect, expired or already used. Get a new code on the new device."
                    "code_not_accepted"->"The email code is incorrect, expired or already used. Request a new code."
                    "device_already_connected"->"This device is already known. Sign in to renew its access."
                    "sign_in_failed"->"The email or account password was not accepted. Try again or choose Forgot password."
                    "account_exists"->"This email already has an account. Choose Sign in."
                    else->when(status) {401->"Cloud sign-in expired or this device was revoked. Sign in again; local work is kept.";403->"This cloud account or device cannot access that shop.";409->if(code=="schema_mismatch") "This app and cloud server need matching data versions. Update the app before syncing." else "The cloud request needs review. Local work is kept.";413->"This sync batch exceeds the server's supported size. Local work is kept.";429->"Cloud requests are temporarily limited. Sync will retry later.";in 300..399->"The cloud server redirected the request. Check its configured HTTPS address.";else->"The cloud server could not complete the request. Local work is kept for retry."}
                }
                throw CloudHttpException(status,code,message,connection.getHeaderField("Retry-After")?.toLongOrNull()?.coerceIn(1,3600))
            }
            if(status==204) return ""
            require(connection.contentType?.substringBefore(';')?.trim()?.lowercase()=="application/json") {"The cloud server returned an unsupported content type."}
            val stream=connection.inputStream
            val decoded=if(connection.contentEncoding.equals("gzip",true)) GZIPInputStream(stream) else stream
            return decoded.use {readBounded(it,maximumBytes)}
        } catch(e:CloudHttpException) {throw e}
        catch(e:IllegalArgumentException) {throw e}
        catch(_:Exception) {throw CloudConnectionException("Could not reach the cloud server. Records remain saved on this device for retry.")}
        finally {connection.disconnect()}
    }
    private fun readBounded(input:InputStream,limit:Int):String {
        val output=ByteArrayOutputStream();val buffer=ByteArray(8192);var total=0L
        while(true) {val count=input.read(buffer);if(count<0)break;total+=count;require(total<=limit) {"The cloud response exceeds the supported size."};output.write(buffer,0,count)}
        return output.toString("UTF-8")
    }
}
