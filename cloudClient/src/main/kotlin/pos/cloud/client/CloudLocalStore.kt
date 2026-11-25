package pos.cloud.client

import pos.cloud.protocol.*

/** Business persistence remains owned by the installed app, never by the HTTP client. */
const val CLOUD_NEW_INSTALLATION = "new-installation"

interface CloudLocalStore {
    /** Require the current local owner before configuring access or publishing records. */
    fun authorizeCloudSetup(localShopId: String)
    fun schema(localShopId: String): CloudSchema
    /** Normalize tenant_id, generated row IDs and tenant identity to this cloud business. */
    fun snapshot(localShopId: String, businessId: String): CloudSnapshot
    /**
     * In ONE DB transaction: recheck local permission, read the latest local state, merge only
     * diff(base,currentLocal) onto remote, validate, write remote source records and checkpointId.
     * Repeating an already-applied checkpointId must return current state without applying twice.
     * `base` is the exact sent snapshot after an accepted upload, otherwise the old acknowledged base.
     */
    fun applyRemote(localShopId: String, checkpointId: String, base: CloudSnapshot, remote: CloudSnapshot, schema: CloudSchema): CloudSnapshot
    /** Restore as a NEW local shop, provision its local owner, and atomically remember importId.
     * Replay of importId returns that same shop; it must not import a duplicate or overwrite one.
     */
    fun importShop(authorizingLocalShopId: String, importId: String, bootstrap: CloudBootstrapReply, localName: String, localPassword: String): String
}

interface CloudCredentialStore {
    fun read(key: String): String?
    fun write(key: String, token: String)
    fun remove(key: String)
}

interface CloudTransport {
    fun startPairing(endpoint:CloudEndpoint,request:CloudPairingStart):CloudPairingTicket = error("Update the cloud connection client.")
    fun reviewPairing(endpoint:CloudEndpoint,accountToken:String,businessId:String,request:CloudPairingCode):CloudPairingReview = error("Update the cloud connection client.")
    fun approvePairing(endpoint:CloudEndpoint,accountToken:String,businessId:String,request:CloudPairingCode) {error("Update the cloud connection client.")}
    fun claimPairing(endpoint:CloudEndpoint,request:CloudPairingClaim):CloudPairingResult = error("Update the cloud connection client.")
    fun finishPairing(endpoint:CloudEndpoint,request:CloudPairingClaim) {error("Update the cloud connection client.")}
    fun capabilities(endpoint:CloudEndpoint):CloudAccountCapabilities = CloudAccountCapabilities()
    fun sendVerification(endpoint:CloudEndpoint,token:String):CloudAccountNotice = error("Email delivery is unavailable.")
    fun verifyEmail(endpoint:CloudEndpoint,token:String,request:CloudVerifyEmail):CloudAccountNotice = error("Email delivery is unavailable.")
    fun requestReset(endpoint:CloudEndpoint,request:CloudEmailRequest):CloudAccountNotice = error("Email delivery is unavailable.")
    fun resetPassword(endpoint:CloudEndpoint,request:CloudResetPassword):CloudAccountNotice = error("Email delivery is unavailable.")
    fun deviceDetails(endpoint:CloudEndpoint,accountToken:String,businessId:String):List<CloudDeviceDetails> = devices(endpoint,accountToken,businessId).map {CloudDeviceDetails(it)}
    fun register(endpoint: CloudEndpoint, request: CloudAccountRequest): CloudAccountSession
    fun login(endpoint: CloudEndpoint, request: CloudAccountRequest): CloudAccountSession
    fun businesses(endpoint: CloudEndpoint, accountToken: String): List<CloudBusinessSummary>
    fun createBusiness(endpoint: CloudEndpoint, accountToken: String, request: CloudCreateBusinessRequest): CloudBusinessSummary
    fun enrollDevice(endpoint: CloudEndpoint, accountToken: String, businessId: String, request: CloudDeviceEnrollRequest): CloudDeviceSession
    fun devices(endpoint: CloudEndpoint, accountToken: String, businessId: String): List<CloudDeviceSummary>
    fun revokeDevice(endpoint: CloudEndpoint, accountToken: String, businessId: String, deviceId: String)
    fun bootstrap(endpoint: CloudEndpoint, deviceToken: String, businessId: String): CloudBootstrapReply
    fun push(endpoint: CloudEndpoint, deviceToken: String, businessId: String, request: CloudPushRequest): CloudPushReply
    fun pull(endpoint: CloudEndpoint, deviceToken: String, businessId: String, afterRevision: Long, limit: Int = 100): CloudPullReply
}
