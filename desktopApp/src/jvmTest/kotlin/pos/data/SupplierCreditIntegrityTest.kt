package pos.data

import java.time.LocalDate
import java.time.ZoneId
import kotlin.test.*
import pos.domain.*

class SupplierCreditIntegrityTest {
    private data class Fixture(val shop: String, val supplier: String, val receipt: StockReceipt)
    private fun fixture(repo: PosRepository): Fixture {
        val shop = repo.createTenant("Shop").id
        val supplier = repo.addSupplierProfile(shop, SupplierDetails("Wholesaler")).id
        val item = repo.addProduct(shop, "Soap", "piece", 2000, 0.0)
        val receipt = repo.receiveStockForSupplier(shop, item.id, 10, 1000, 0, supplier, "purchase")
        repo.openCashShift(shop, 100000)
        return Fixture(shop, supplier, receipt)
    }
    private fun today() = LocalDate.now(ZoneId.of("Africa/Lagos")).toString()

    @Test fun creditApplicationsAreReviewedReversibleAndSeparateFromCash() {
        PosRepository(":memory:").use { repo ->
            val f = fixture(repo)
            val cashBefore = repo.cashEntries(f.shop)
            val note = repo.recordSupplierCredit(f.shop, f.supplier, "CN-001", today(), 8000, "Agreed supplier price allowance", "note")
            assertEquals(note, repo.recordSupplierCredit(f.shop, f.supplier, "CN-001", today(), 8000, "Agreed supplier price allowance", "note"))
            assertFails { repo.recordSupplierCredit(f.shop, f.supplier, "cn-001", today(), 8000, "Duplicate reference", "duplicate") }
            val review = repo.reviewSupplierCredit(f.shop, note.id, f.receipt.id, 3000)
            val event = repo.applySupplierCredit(f.shop, review, "Apply agreed allowance", "apply")
            assertEquals(7000L, repo.supplierOutstanding(f.shop, f.receipt.id))
            assertEquals(cashBefore, repo.cashEntries(f.shop))
            assertEquals(5000L, repo.supplierCreditBalances(f.shop).single().availableMinor)
            val stale = repo.reviewSupplierCredit(f.shop, note.id, f.receipt.id, 1000)
            val second = repo.applySupplierCredit(f.shop, repo.reviewSupplierCredit(f.shop, note.id, f.receipt.id, 500), "Use remaining allowance", "second")
            assertFails { repo.applySupplierCredit(f.shop, stale, "Old review", "stale") }
            assertEquals(event, repo.applySupplierCredit(f.shop, review, "Apply agreed allowance", "apply"))
            assertFails { repo.voidSupplierCredit(f.shop, note.id, "Wrong note", "void-early") }
            val reversal = repo.reverseSupplierCreditApplication(f.shop, event.id, "Applied to the wrong delivery", "undo")
            assertEquals(reversal, repo.reverseSupplierCreditApplication(f.shop, event.id, "Applied to the wrong delivery", "undo"))
            assertEquals(9500L, repo.supplierOutstanding(f.shop, f.receipt.id))
            assertEquals(7500L, repo.supplierCreditBalances(f.shop).single().availableMinor)
            assertFails { repo.reverseSupplierCreditApplication(f.shop, event.id, "Repeat correction", "twice") }
            repo.paySupplierReceipt(f.shop, f.receipt.id, 9500, "cash-payment")
            assertFails { repo.reviewSupplierCredit(f.shop, note.id, f.receipt.id, 1) }
            repo.reverseSupplierCreditApplication(f.shop, second.id, "Credit note withdrawn by supplier", "undo-second")
            val void = repo.voidSupplierCredit(f.shop, note.id, "Supplier issued a replacement reference", "void")
            assertEquals(void, repo.voidSupplierCredit(f.shop, note.id, "Supplier issued a replacement reference", "void"))
            assertTrue(repo.supplierCreditBalances(f.shop).single().voided)
            assertEquals(500L, repo.supplierOutstanding(f.shop, f.receipt.id))
            assertEquals(0L, repo.supplierCreditBalances(f.shop).single().availableMinor)
            assertEquals(4, repo.supplierCreditAllocations(f.shop).size)
        }
    }

    @Test fun linkedCreditReturnsPaymentsAndReversalsSurviveBackupWithHistory() {
        PosRepository(":memory:").use { repo ->
            val f = fixture(repo)
            val note = repo.recordSupplierCredit(f.shop, f.supplier, "CN-002", today(), 2000, "Delivery allowance", "note")
            val applied = repo.applySupplierCredit(f.shop, repo.reviewSupplierCredit(f.shop, note.id, f.receipt.id, 1000), "Apply to receipt", "apply")
            repo.savePurchaseReturn(f.shop, repo.reviewPurchaseReturn(f.shop, f.receipt.id, 1), "Return damaged soap", "return")
            repo.paySupplierReceipt(f.shop, f.receipt.id, 1000, "payment")
            repo.reverseSupplierCreditApplication(f.shop, applied.id, "Supplier corrected allowance", "undo")
            assertEquals(8000L, repo.supplierOutstanding(f.shop, f.receipt.id))
            val snapshot = repo.exportTenantSnapshot(f.shop)
            val restored = repo.restoreTenantSnapshot(snapshot, "Recovered")
            assertEquals(8000L, repo.supplierOutstanding(restored.id, f.receipt.id))
            assertEquals(2000L, repo.supplierCreditBalances(restored.id).single().availableMinor)
            assertEquals(repo.cashEntries(f.shop).map { it.amountMinor }, repo.cashEntries(restored.id).map { it.amountMinor })
            val missing = snapshot.copy(tables = snapshot.tables - "supplier_credit_allocations")
            assertFails { repo.restoreTenantSnapshot(missing, "Missing credit applications") }
            val audit = snapshot.tables.getValue("activity_audit").filterNot { it["kind"] == "supplier.credit_recorded" }
            assertFails { repo.restoreTenantSnapshot(snapshot.copy(tables = snapshot.tables + ("activity_audit" to audit)), "Missing credit evidence") }
        }
    }

    @Test fun creditCannotCrossSuppliersTenantsOrStaffPermissions() {
        PosRepository(":memory:").use { repo ->
            val f = fixture(repo)
            val another = repo.addSupplierProfile(f.shop, SupplierDetails("Another supplier"))
            val note = repo.recordSupplierCredit(f.shop, another.id, "CN-3", today(), 1000, "Supplier note", "note")
            assertFails { repo.reviewSupplierCredit(f.shop, note.id, f.receipt.id, 1000) }
            val other = repo.createTenant("Other")
            assertFails { repo.reviewSupplierCredit(other.id, note.id, f.receipt.id, 1000) }
            val before = repo.exportTenantSnapshot(f.shop)
            repo.setPermissionCheck { _, permission -> require(permission == StaffPermission.SELL) }
            assertFails { repo.recordSupplierCredit(f.shop, f.supplier, "CN-4", today(), 1000, "Not authorized", "unauthorized") }
            repo.setPermissionCheck(null)
            assertEquals(before, repo.exportTenantSnapshot(f.shop))
        }
    }
}
