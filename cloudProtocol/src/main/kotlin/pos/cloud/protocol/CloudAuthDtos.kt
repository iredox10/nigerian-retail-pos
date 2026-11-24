package pos.cloud.protocol

import kotlinx.serialization.Serializable

@Serializable data class CloudAccountRequest(val email:String,val password:String) {
    override fun toString()="CloudAccountRequest(credentials=redacted)"
}
@Serializable data class CloudAccountSession(val accountId:String,val accessToken:String,val expiresAt:String) {
    override fun toString()="CloudAccountSession(accountId=$accountId, accessToken=redacted, expiresAt=$expiresAt)"
}
@Serializable data class CloudCreateBusinessRequest(val operationId:String,val name:String,val schemaHash:String,val snapshot:CloudSnapshot)
@Serializable data class CloudBusinessSummary(val id:String,val name:String,val revision:Long)
@Serializable data class CloudDeviceEnrollRequest(val name:String,val installationId:String)
@Serializable data class CloudDeviceSession(val deviceId:String,val businessId:String,val deviceToken:String,val expiresAt:String) {
    override fun toString()="CloudDeviceSession(deviceId=$deviceId, businessId=$businessId, deviceToken=redacted, expiresAt=$expiresAt)"
}
@Serializable data class CloudDeviceSummary(val id:String,val name:String,val createdAt:String,val expiresAt:String,val revokedAt:String?=null)
@Serializable data class CloudApiError(val code:String,val message:String)
