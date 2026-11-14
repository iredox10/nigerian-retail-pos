package pos.security

import kotlin.test.*
import pos.data.PosRepository
import pos.domain.*
import java.nio.file.Files

class StaffSessionRepositoryIntegrationTest {
    @Test fun genuineCashierSessionSellsCashButCannotDiscountGrantCreditOrUseAnotherShop() {
        val directory=Files.createTempDirectory("staff-session-repo")
        try {
            PosRepository(":memory:").use { repo ->
                val ownerPasswords=WorkspaceAccess(directory)
                val shop=repo.createTenant("Market") {ownerPasswords.register(it.id,"owner-password")}
                val other=repo.createTenant("Other")
                val product=repo.addProduct(shop.id,"Malt","bottle",50_000,10.0)
                val customer=repo.addCustomer(shop.id,"Amina")
                StaffAccess(directory).use { access ->
                    val owner=requireNotNull(access.unlockOwner(shop.id,"owner-password"))
                    val member=access.addMember(owner,"Cashier",StaffRole.CASHIER,"cashier-password")
                    val staff=requireNotNull(access.unlockStaff(shop.id,member.id,"cashier-password"))
                    repo.setPermissionCheck { tenant,permission ->
                        require(tenant==staff.tenantId)
                        access.requireSession(staff,permission)
                    }
                    repo.checkout(shop.id,listOf(CartLine(product,1)),50_000,operationId="cash-sale")
                    assertFails {repo.checkout(shop.id,listOf(CartLine(product,1)),0,customer.id)}
                    assertFails {repo.checkout(shop.id,listOf(CartLine(product,1,discountPerUnitMinor=1_000,discountReason="Agreed price")),49_000)}
                    assertFails {repo.addProduct(other.id,"Foreign","piece",100,1.0)}
                    assertFails {repo.exportTenantSnapshot(shop.id)}
                    assertEquals(9.0,repo.products(shop.id).single().stock)
                    assertEquals(0L,repo.customers(shop.id).single().balanceMinor)
                    assertEquals(1,repo.sales(shop.id).size)
                    access.updateMember(owner,member.id,member.name,member.role,false)
                    assertFails {repo.checkout(shop.id,listOf(CartLine(product,1)),50_000,operationId="cash-sale")}
                    assertEquals(1,repo.sales(shop.id).size)
                }
            }
        } finally { directory.toFile().deleteRecursively() }
    }

    @Test fun managerCannotExportBackupAndRoleChangeRevokesOldSessionBeforeWrites() {
        val directory=Files.createTempDirectory("manager-repo")
        try {
            PosRepository(":memory:").use { repo ->
                val credentials=WorkspaceAccess(directory)
                val shop=repo.createTenant("Shop") {credentials.register(it.id,"owner-password")}
                StaffAccess(directory).use { access ->
                    val owner=requireNotNull(access.unlockOwner(shop.id,"owner-password"))
                    val member=access.addMember(owner,"Manager",StaffRole.MANAGER,"manager-password")
                    val manager=requireNotNull(access.unlockStaff(shop.id,member.id,"manager-password"))
                    repo.setPermissionCheck { tenant,permission -> require(tenant==manager.tenantId);access.requireSession(manager,permission) }
                    val item=repo.addProduct(shop.id,"Soap","piece",20_000,10.0)
                    repo.checkout(shop.id,listOf(CartLine(item,1,discountPerUnitMinor=1_000,discountReason="Agreed price")),19_000)
                    assertFails {repo.exportTenantSnapshot(shop.id)}
                    assertFails {access.addMember(manager,"Another",StaffRole.CASHIER,"another-password")}
                    access.updateMember(owner,member.id,member.name,StaffRole.CASHIER,true)
                    assertFails {repo.addProduct(shop.id,"Blocked","piece",100,1.0)}
                    assertEquals(1,repo.products(shop.id).size)
                    repo.setPermissionCheck {tenant,permission ->require(tenant==owner.tenantId);access.requireSession(owner,permission)}
                    assertEquals(shop.id,repo.exportTenantSnapshot(shop.id).tenant.id)
                }
            }
        } finally { directory.toFile().deleteRecursively() }
    }

    @Test fun businessBackupDoesNotCarryStaffIdentitiesPasswordsOrSessions() {
        val directory=Files.createTempDirectory("staff-backup-exclusion")
        try {
            PosRepository(":memory:").use { repo ->
                val credentials=WorkspaceAccess(directory)
                val shop=repo.createTenant("Shop") {credentials.register(it.id,"owner-password")}
                StaffAccess(directory).use { access ->
                    val owner=requireNotNull(access.unlockOwner(shop.id,"owner-password"))
                    access.addMember(owner,"Private cashier name",StaffRole.CASHIER,"cashier-secret")
                    val snapshot=repo.exportTenantSnapshot(shop.id)
                    // Stock-count sessions are business records; credential/session stores are not.
                    val securityTables=setOf("local_staff","staff_pins","login_attempts","staff_audit","sessions","staff_sessions","owner_credentials")
                    assertTrue(snapshot.tables.keys.intersect(securityTables).isEmpty())
                    val secretColumns=setOf("password","password_hash","pin","pin_hash","salt","creation_fingerprint","creation_salt","token","session_token")
                    assertFalse(snapshot.tables.values.flatten().any {row -> row.keys.any {it in secretColumns}})
                    assertFalse(snapshot.tables.toString().contains("Private cashier name"))
                    assertFalse(snapshot.tables.toString().contains("cashier-secret"))
                    val restored=repo.restoreTenantSnapshot(snapshot,"Restored")
                    assertTrue(access.signInMembers(restored.id).isEmpty())
                }
            }
        } finally { directory.toFile().deleteRecursively() }
    }
}
