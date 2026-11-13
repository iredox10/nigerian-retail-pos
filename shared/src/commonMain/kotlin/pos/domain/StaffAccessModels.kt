package pos.domain

enum class StaffPermission {
    SELL, SELL_CREDIT, DISCOUNT, VIEW_SALES, RETURN_SALE, MANAGE_STOCK,
    MANAGE_CUSTOMERS, MANAGE_MONEY, VIEW_REPORTS, MANAGE_SUPPLIERS, BACKUP, MANAGE_STAFF,
    MANAGE_SETTINGS
}

enum class StaffRole {
    OWNER, MANAGER, CASHIER;
    fun allows(permission: StaffPermission): Boolean = when(this) {
        OWNER -> true
        MANAGER -> permission != StaffPermission.BACKUP && permission != StaffPermission.MANAGE_STAFF
        CASHIER -> permission == StaffPermission.SELL || permission == StaffPermission.VIEW_SALES
    }
}

data class StaffMember(
    val id:String, val tenantId:String, val name:String, val role:StaffRole,
    val active:Boolean, val createdAt:String,
) {
    init {
        require(id.isNotBlank() && tenantId.isNotBlank() && createdAt.isNotBlank())
        require(name == name.trim() && name.length in 1..100 && name.none {it.isISOControl()}) { "Use a staff name from 1 to 100 characters." }
        require(role != StaffRole.OWNER) { "The shop owner uses the existing owner account." }
    }
}

/** A token is useful only while its issuing StaffAccess instance recognizes it. */
data class StaffSession(val token:String,val tenantId:String,val staffId:String?,val name:String,val role:StaffRole) {
    override fun toString() = "StaffSession(tenantId=$tenantId, staffId=$staffId, role=$role, token=redacted)"
}
