package pos.cloud.app

import pos.cloud.client.*
import pos.cloud.protocol.*
import pos.data.*
import pos.security.WorkspaceAccess
import pos.domain.StaffPermission

/** The caller supplies the live local-owner check; ordinary sync keeps repository session checks. */
class RepositoryCloudLocalStore(
    private val repo:PosRepository,
    private val owners:WorkspaceAccess,
    private val importRepository:()->PosRepository,
    private val requireOwner:(String)->Unit,
):CloudLocalStore {
    override fun authorizeCloudSetup(localShopId:String) {
        if(localShopId==CLOUD_NEW_INSTALLATION) importRepository().use {require(it.firstCloudImportAvailable()) {"Open your existing shop as its owner before adding another device."}}
        else requireOwner(localShopId)
    }
    override fun schema(localShopId:String):CloudSchema = if(localShopId==CLOUD_NEW_INSTALLATION) {
        authorizeCloudSetup(localShopId);repo.cloudBusinessSchema()
    } else repo.featureRead(localShopId,StaffPermission.SELL) {cloudBusinessSchema()}
    override fun snapshot(localShopId:String,businessId:String)=repo.cloudBusinessSnapshot(localShopId,businessId)
    override fun applyRemote(localShopId:String,checkpointId:String,base:CloudSnapshot,remote:CloudSnapshot,schema:CloudSchema):CloudSnapshot {
        require(schema.fingerprint==repo.cloudBusinessSchema().fingerprint)
        return repo.applyCloudBusinessSnapshot(localShopId,checkpointId,base,remote)
    }
    override fun importShop(authorizingLocalShopId:String,importId:String,bootstrap:CloudBootstrapReply,localName:String,localPassword:String):String {
        authorizeCloudSetup(authorizingLocalShopId)
        require(localPassword.length in 8..1024)
        // Recovery has its own trusted connection, like encrypted backup import. The running
        // connection's tenant/session guard is never disabled to inspect a newly created shop.
        return importRepository().use {recovery ->
            authorizeCloudSetup(authorizingLocalShopId)
            if(authorizingLocalShopId==CLOUD_NEW_INSTALLATION) recovery.importFirstCloudShop(importId,bootstrap.snapshot,localName) {shop ->owners.register(shop.id,localPassword)}.id
            else recovery.importCloudBusinessShop(authorizingLocalShopId,importId,bootstrap.snapshot,localName) {shop ->
                authorizeCloudSetup(authorizingLocalShopId);owners.register(shop.id,localPassword)
            }.id
        }
    }
}
