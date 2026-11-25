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

@Serializable data class CloudPairingStart(val name:String,val installationId:String)
@Serializable data class CloudPairingTicket(val id:String,val code:String,val secret:String,val expiresAt:String) {
    override fun toString()="CloudPairingTicket(id=$id, credentials=redacted)"
}
@Serializable data class CloudPairingCode(val code:String,val pairingId:String?=null) {
    override fun toString()="CloudPairingCode(redacted)"
}
@Serializable data class CloudPairingReview(val id:String,val deviceName:String,val expiresAt:String)
@Serializable data class CloudPairingClaim(val id:String,val secret:String) {
    override fun toString()="CloudPairingClaim(redacted)"
}
@Serializable data class CloudPairingResult(val status:String,val shopName:String?=null,val device:CloudDeviceSession?=null)
@Serializable data class CloudAccountCapabilities(val emailRecoveryAvailable:Boolean=false)
@Serializable data class CloudEmailRequest(val email:String) {
    override fun toString()="CloudEmailRequest(redacted)"
}
@Serializable data class CloudVerifyEmail(val code:String) {
    override fun toString()="CloudVerifyEmail(redacted)"
}
@Serializable data class CloudResetPassword(val email:String,val code:String,val password:String) {
    override fun toString()="CloudResetPassword(redacted)"
}
@Serializable data class CloudAccountNotice(val message:String)

@Serializable data class CloudDeviceDetails(val device:CloudDeviceSummary,val lastSeenAt:String?=null)
