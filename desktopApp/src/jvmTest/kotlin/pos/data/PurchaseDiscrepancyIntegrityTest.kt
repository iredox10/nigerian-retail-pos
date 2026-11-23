package pos.data

import java.nio.file.Files
import kotlin.test.*
import pos.domain.*
import pos.output.BackupService

class PurchaseDiscrepancyIntegrityTest {
    private data class Fixture(val order: PurchaseOrder, val malt: Product, val soap: Product, val carton: PurchaseUnit) {
        val tenantId get() = order.tenantId
        val supplierId get() = order.supplierId
    }

    private fun fixture(repo: PosRepository): Fixture {
        val shop = repo.createTenant("Shop")
        val supplier = repo.addSupplierProfile(shop.id, SupplierDetails("Wholesaler"))
        val malt = repo.addProduct(shop.id, "Malt", "bottle", 800, 0.0)
        val soap = repo.addProduct(shop.id, "Soap", "pack", 600, 0.0)
        val carton = repo.addPurchaseUnit(shop.id, malt.id, "carton", 24)
        val draft = repo.createPurchaseOrder(shop.id, supplier.id,
            listOf(PurchaseOrderLineInput("malt", malt.id, 3, 8_000, carton.id)))
        return Fixture(repo.approvePurchaseOrder(shop.id, draft.id, draft.version), malt, soap, carton)
    }

    private fun Fixture.record(
        repo: PosRepository, kind: PurchaseDiscrepancyKind, count: Int, remaining: Int?, operation: String,
    ) = repo.recordPurchaseDiscrepancy(tenantId, order.id, order.version, kind, "malt", null, count, remaining,
        "Supplier accepted the delivery report", operation)

    @Test fun reportsUseTheOriginalBuyingUnitAndDoNotPostStockDebtOrCash() {
        PosRepository(":memory:").use { repo ->
            val f = fixture(repo)
            val delivered = repo.receivePurchaseOrder(f.tenantId, f.order.id, listOf(PurchaseDeliveryInput("malt", 1, 0, 0)),
                f.order.version)
            val opening = repo.products(f.tenantId)
            assertFails { f.record(repo, PurchaseDiscrepancyKind.SHORTAGE, 1, 3, "outdated") }
            val shortage = f.record(repo, PurchaseDiscrepancyKind.SHORTAGE, 1, 2, "missing")
            assertEquals(shortage, f.record(repo, PurchaseDiscrepancyKind.SHORTAGE, 1, 2, "missing"))
            assertEquals("carton", shortage.buyingUnit)
            assertEquals(24L, shortage.baseQuantity)
            assertEquals(2, shortage.remainingAtReport)
            assertFails { f.record(repo, PurchaseDiscrepancyKind.DAMAGED, 2, 2, "too many") }
            assertFails { f.record(repo, PurchaseDiscrepancyKind.DAMAGED, 1, 2, "missing") }
            val damaged = f.record(repo, PurchaseDiscrepancyKind.DAMAGED, 1, 2, "damaged")
            assertEquals(2, repo.purchaseDiscrepancies(f.tenantId, f.order.id).count { it.resolution == null })
            assertEquals(mapOf("malt" to 2), repo.purchaseOrderRemaining(f.tenantId, f.order.id))
            assertEquals(opening, repo.products(f.tenantId))
            assertEquals(1, repo.stockReceipts(f.tenantId).size)
            assertEquals(8_000L, repo.supplierOutstanding(f.tenantId, delivered.lines.single().receiptId))
            assertTrue(repo.cashEntries(f.tenantId).isEmpty())
            assertFails {
                repo.resolvePurchaseDiscrepancy(f.tenantId, damaged.id, "Wrong earlier receipt", delivered.lines.single().receiptId)
            }
            val resolved = repo.resolvePurchaseDiscrepancy(f.tenantId, damaged.id, "Supplier collected damaged goods",
                operationId = "closed")
            assertEquals(resolved, repo.resolvePurchaseDiscrepancy(f.tenantId, damaged.id, resolved.reason, operationId = "closed"))
            assertFails { repo.resolvePurchaseDiscrepancy(f.tenantId, damaged.id, "Second resolution") }
            assertEquals(mapOf("malt" to 2), repo.purchaseOrderRemaining(f.tenantId, f.order.id))
        }
    }

    @Test fun extraGoodsCanLinkOnlyOneLaterMatchingReceiptFromTheSameSupplier() {
        PosRepository(":memory:").use { repo ->
            val f = fixture(repo)
            val alias = repo.addSupplierProfile(f.tenantId, SupplierDetails("Wholesaler"))
            val extra = repo.recordPurchaseDiscrepancy(f.tenantId, f.order.id, f.order.version, PurchaseDiscrepancyKind.EXTRA,
                null, f.soap.id, 2, null, "Boxes included unpackaged soap", "extra")
            val other = repo.receiveStockForSupplier(f.tenantId, f.soap.id, 2, 300, 0, alias.id, "other supplier")
            val ordered = repo.receivePurchaseOrder(f.tenantId, f.order.id,
                listOf(PurchaseDeliveryInput("malt", 1, 0, 0)), f.order.version).lines.single().receiptId
            val match = repo.receiveStockForSupplier(f.tenantId, f.soap.id, 2, 300, 0, f.supplierId, "separate")
            assertEquals(listOf(match.id), repo.purchaseDiscrepancyReceipts(f.tenantId, extra.id).map { it.id })
            for (receiptId in listOf(other.id, ordered)) {
                assertFails { repo.resolvePurchaseDiscrepancy(f.tenantId, extra.id, "Incorrect receipt", receiptId) }
            }
            val recorded = repo.resolvePurchaseDiscrepancy(f.tenantId, extra.id, "Agreed to buy under separate receipt", match.id)
            assertEquals(match.id, recorded.receiptId)
            assertEquals(3, repo.stockReceipts(f.tenantId).size)
            assertEquals(2, repo.purchaseOrderRemaining(f.tenantId, f.order.id).getValue("malt"))
            assertEquals(600L, repo.supplierOutstanding(f.tenantId, match.id))
        }
    }

    @Test fun permissionsAndLateActorFailureRollBackReportsAndResolutions() {
        PosRepository(":memory:").use { repo ->
            val f = fixture(repo)
            val before = repo.exportTenantSnapshot(f.tenantId)
            val other = repo.createTenant("Other shop")
            repo.setPermissionCheck { _, permission -> require(permission != StaffPermission.MANAGE_STOCK) }
            assertFails { f.record(repo, PurchaseDiscrepancyKind.SHORTAGE, 1, 3, "denied") }
            repo.setPermissionCheck(null)
            assertFails { repo.purchaseDiscrepancies(other.id, f.order.id) }
            repo.setActorProvider { error("Actor revoked") }
            assertFails { f.record(repo, PurchaseDiscrepancyKind.SHORTAGE, 1, 3, "rollback") }
            repo.setActorProvider(null)
            assertEquals(before, repo.exportTenantSnapshot(f.tenantId))
            val saved = f.record(repo, PurchaseDiscrepancyKind.SHORTAGE, 1, 3, "missing")
            repo.setActorProvider { error("Actor revoked") }
            assertFails { repo.resolvePurchaseDiscrepancy(f.tenantId, saved.id, "Supplier has sent another carton") }
            repo.setActorProvider(null)
            assertNull(repo.purchaseDiscrepancies(f.tenantId, f.order.id).single().resolution)
        }
    }

    @Test fun encryptedBackupRestoresEvidenceAndRejectsMissingOrAlteredDiscrepancies() {
        val directory = Files.createTempDirectory("purchase-discrepancy-test-")
        val file = directory.resolve("shop.backup")
        try {
            PosRepository(":memory:").use { repo ->
                val f = fixture(repo)
                val report = f.record(repo, PurchaseDiscrepancyKind.SHORTAGE, 1, 3, "missing")
                val delivery = repo.receivePurchaseOrder(f.tenantId, f.order.id,
                    listOf(PurchaseDeliveryInput("malt", 1, 0, 0)), f.order.version)
                val resolution = repo.resolvePurchaseDiscrepancy(f.tenantId, report.id, "Supplier sent the missing carton",
                    delivery.lines.single().receiptId, "resolved")
                repo.cancelPurchaseOrder(f.tenantId, f.order.id, "Supplier cancelled the remainder", f.order.version)
                BackupService.create(repo, f.tenantId, "purchase-discrepancy-password", file)
                val snapshot = BackupService.read(file, "purchase-discrepancy-password")
                val restored = repo.restoreTenantSnapshot(snapshot, "Restored")
                val recovered = repo.purchaseDiscrepancies(restored.id, f.order.id).single()
                assertEquals(report.copy(tenantId = restored.id), recovered.report)
                assertEquals(resolution, recovered.resolution)
                val missingEvents = snapshot.copy(tables = snapshot.tables - "purchase_discrepancy_resolutions")
                assertFails { repo.restoreTenantSnapshot(missingEvents, "Missing resolution") }
                val row = snapshot.tables.getValue("purchase_discrepancies").single()
                val wrongQuantity = snapshot.copy(tables = snapshot.tables + ("purchase_discrepancies" to listOf(row + ("quantity" to "2"))))
                assertFails { repo.restoreTenantSnapshot(wrongQuantity, "Altered quantity") }
                val wrongAudit = snapshot.tables.getValue("activity_audit").filterNot { it["kind"] == "purchase_discrepancy.recorded" }
                val omittedAudit = snapshot.copy(tables = snapshot.tables + ("activity_audit" to wrongAudit))
                assertFails { repo.restoreTenantSnapshot(omittedAudit, "Missing audit") }
            }
        } finally {
            Files.deleteIfExists(file)
            Files.delete(directory)
        }
    }
}
