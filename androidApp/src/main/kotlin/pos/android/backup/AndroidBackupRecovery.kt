package pos.android.backup

import pos.data.PosRepository
import pos.domain.StaffPermission
import pos.domain.Tenant
import pos.data.TenantSnapshot
import pos.security.WorkspaceAccess

/** Decryption/document selection may outlast the owner's session. Recheck before recovery writes. */
internal fun restoreAndroidBackup(
    live:PosRepository,sourceTenantId:String,snapshot:TenantSnapshot,databasePath:String,
    name:String,ownerPassword:String,access:WorkspaceAccess,
):Tenant {
    fun authorize()=live.featureRead(sourceTenantId,StaffPermission.BACKUP) {Unit}
    authorize()
    require(name.isNotBlank() && ownerPassword.length in 8..1024) {"Enter a restored shop name and new owner password."}
    // Do not remove or bypass the live connection's session guard.
    return PosRepository(databasePath).use {recovery ->
        recovery.restoreTenantSnapshot(snapshot,name.trim()) {tenant ->
            authorize()
            access.register(tenant.id,ownerPassword)
        }
    }
}
