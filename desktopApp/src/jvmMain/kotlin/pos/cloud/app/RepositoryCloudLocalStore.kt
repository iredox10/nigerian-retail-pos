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
    override fun authorizeCloudSetup(localShopId:String)=requireOwner(localShopId)
    override fun schema(localShopId:String):CloudSchema = repo.featureRead(localShopId,StaffPermission.SELL) {cloudBusinessSchema()}
    override fun snapshot(localShopId:String,businessId:String)=repo.cloudBusinessSnapshot(localShopId,businessId)
    override fun applyRemote(localShopId:String,checkpointId:String,base:CloudSnapshot,remote:CloudSnapshot,schema:CloudSchema):CloudSnapshot {
        require(schema.fingerprint==repo.cloudBusinessSchema().fingerprint)
        return repo.applyCloudBusinessSnapshot(localShopId,checkpointId,base,remote)
    }
    override fun importShop(authorizingLocalShopId:String,importId:String,bootstrap:CloudBootstrapReply,localName:String,localPassword:String):String {
        requireOwner(authorizingLocalShopId)
        require(localPassword.length in 8..1024)
        // Recovery has its own trusted connection, like encrypted backup import. The running
        // connection's tenant/session guard is never disabled to inspect a newly created shop.
        return importRepository().use {recovery ->
            requireOwner(authorizingLocalShopId)
            recovery.importCloudBusinessShop(authorizingLocalShopId,importId,bootstrap.snapshot,localName) {shop ->
                requireOwner(authorizingLocalShopId)
                owners.register(shop.id,localPassword)
            }.id
        }
    }
}
