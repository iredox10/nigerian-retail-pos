package pos.data

import kotlin.test.*
import pos.domain.*

/** New local feature modules must preserve the existing financial transaction boundary. */
class FeatureTransactionTest {
    @Test fun nestedFinancialMutationRollsBackWithItsFeature() {
        PosRepository(":memory:").use { repo ->
            val shop = repo.createTenant("Atomic modules")
            repo.openCashShift(shop.id, 100_000)
            val before = repo.exportTenantSnapshot(shop.id)
            assertFailsWith<IllegalStateException> {
                repo.featureWrite(shop.id, StaffPermission.MANAGE_MONEY) {
                    recordExpense(shop.id, "Transport", "Delivery fare", 5_000, "module-expense")
                    error("Feature failed after its nested expense")
                }
            }
            assertEquals(before, repo.exportTenantSnapshot(shop.id))
            assertEquals(100_000L, repo.expectedCash(shop.id, repo.activeCashShift(shop.id)!!.id))
        }
    }

    @Test fun modulePermissionsApplyBeforeReadsAndWritesIncludingRetries() {
        PosRepository(":memory:").use { repo ->
            val shop = repo.createTenant("Guarded modules")
            val before = repo.exportTenantSnapshot(shop.id)
            repo.setPermissionCheck { _, permission ->
                require(permission == StaffPermission.BACKUP) { "Denied" }
            }
            var entered = false
            assertFails {
                repo.featureWrite(shop.id, StaffPermission.MANAGE_SUPPLIERS) {
                    entered = true
                    featureExecute("UPDATE tenants SET name=? WHERE id=?", "Changed", shop.id)
                }
            }
            assertFails { repo.featureRead(shop.id, StaffPermission.MANAGE_SUPPLIERS) { entered = true } }
            assertFalse(entered)
            assertEquals(before, repo.exportTenantSnapshot(shop.id))
        }
    }

    @Test fun actorFailureRollsBackModuleRecordAndOutboxTogether() {
        PosRepository(":memory:").use { repo ->
            val shop = repo.createTenant("Actor guard")
            val before = repo.exportTenantSnapshot(shop.id)
            repo.setActorProvider { throw IllegalArgumentException("Expired actor") }
            assertFails {
                repo.featureWrite(shop.id, StaffPermission.MANAGE_SUPPLIERS) {
                    featureExecute("UPDATE tenants SET name=? WHERE id=?", "Changed", shop.id)
                    featureEvent(shop.id, "supplier.created", "sample-profile")
                }
            }
            assertEquals(before, repo.exportTenantSnapshot(shop.id))
        }
    }
}
