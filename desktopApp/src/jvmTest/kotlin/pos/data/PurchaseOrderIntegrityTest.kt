package pos.data

import kotlin.test.*
import pos.domain.*
import pos.output.BackupService
import java.nio.file.Files
import java.sql.DriverManager

class PurchaseOrderIntegrityTest {
    private data class Fixture(val shop:Tenant,val supplier:SupplierProfile,val rice:Product,val malt:Product,val carton:PurchaseUnit,val lines:List<PurchaseOrderLineInput>)
    private fun fixture(repo:PosRepository):Fixture {
        repo.initializePurchaseOrders()
        val shop=repo.createTenant("Purchase shop")
        val supplier=repo.addSupplierProfile(shop.id,SupplierDetails("Supplier"),"supplier")
        val rice=repo.addProduct(shop.id,"Rice","kg",50000,0.0,quantityPrecision=3)
        val malt=repo.addProduct(shop.id,"Malt","bottle",50000,0.0)
        val carton=repo.addPurchaseUnit(shop.id,malt.id,"carton",24,"carton")
        return Fixture(shop,supplier,rice,malt,carton,listOf(PurchaseOrderLineInput("rice-line",rice.id,2500,30000),PurchaseOrderLineInput("malt-line",malt.id,2,1000000,carton.id)))
    }
    private fun approve(repo:PosRepository,f:Fixture):PurchaseOrder {
        val draft=repo.createPurchaseOrder(f.shop.id,f.supplier.id,f.lines,"Bring both items","order")
        return repo.approvePurchaseOrder(f.shop.id,draft.id,draft.version,"approval")
    }
    private fun delivery(f:Fixture,rice:Int=1250,cartons:Int=1,ricePaid:Long=0,maltPaid:Long=0,riceStock:Long=0,maltStock:Long=0)=listOf(PurchaseDeliveryInput("rice-line",rice,ricePaid,riceStock),PurchaseDeliveryInput("malt-line",cartons,maltPaid,maltStock))

    @Test fun draftEditApprovalAndCancellationHaveNoStockCashOrSupplierDebtEffects() {
        PosRepository(":memory:").use {repo ->
            val f=fixture(repo)
            val goods=repo.products(f.shop.id)
            val draft=repo.createPurchaseOrder(f.shop.id,f.supplier.id,f.lines,"Initial request","order")
            val edited=repo.editPurchaseOrder(f.shop.id,draft.id,f.supplier.id,f.lines.map {if(it.id=="rice-line")it.copy(quantity=1375) else it},"Revised request","Adjusted quantity",draft.version,"edit")
            assertEquals(1375,edited.lines.first().quantity)
            val approved=repo.approvePurchaseOrder(f.shop.id,draft.id,edited.version,"approve")
            assertEquals(PurchaseOrderStatus.APPROVED,approved.status)
            assertTrue(repo.stockReceipts(f.shop.id).isEmpty())
            assertTrue(repo.cashEntries(f.shop.id).isEmpty())
            assertEquals(goods,repo.products(f.shop.id))
            val cancelled=repo.cancelPurchaseOrder(f.shop.id,draft.id,"Supplier cannot deliver",approved.version,"cancel")
            assertEquals(PurchaseOrderStatus.CANCELLED,cancelled.status)
            assertEquals(listOf("created","edited","approved","cancelled"),repo.purchaseOrderEvents(f.shop.id,draft.id).map {it.kind})
            assertEquals(goods,repo.products(f.shop.id))
            assertTrue(repo.stockReceipts(f.shop.id).isEmpty())
        }
    }
    @Test fun multiLinePartialDeliveriesAddBaseStockAndRecordOnlyActualCashAndSupplierDebt() {
        PosRepository(":memory:").use {repo ->
            val f=fixture(repo);val order=approve(repo,f)
            val shift=repo.openCashShift(f.shop.id,2_000_000)
            val first=repo.receivePurchaseOrder(f.shop.id,order.id,delivery(f,maltPaid=500000),order.version,"delivery-1")
            assertEquals(2,first.lines.size)
            assertEquals(1250L,repo.products(f.shop.id).single {it.id==f.rice.id}.exactStockScaled)
            assertEquals(24L,repo.products(f.shop.id).single {it.id==f.malt.id}.exactStockScaled)
            assertEquals(1_500_000L,repo.expectedCash(f.shop.id,shift.id))
            assertEquals(mapOf("rice-line" to 1250,"malt-line" to 1),repo.purchaseOrderRemaining(f.shop.id,order.id))
            val receiptIds=first.lines.map {it.receiptId}.toSet()
            assertEquals(2,repo.supplierReceiptLinks(f.shop.id).count {it.receiptId in receiptIds && it.supplierId==f.supplier.id})
            val second=repo.receivePurchaseOrder(f.shop.id,order.id,delivery(f,riceStock=1250,maltStock=24),order.version,"delivery-2")
            assertEquals(2,second.lines.size)
            assertEquals(0,repo.purchaseOrderRemaining(f.shop.id,order.id).values.sum())
            assertEquals(2500L,repo.products(f.shop.id).single {it.id==f.rice.id}.exactStockScaled)
            assertEquals(48L,repo.products(f.shop.id).single {it.id==f.malt.id}.exactStockScaled)
            assertEquals(1_575_000L,repo.stockReceipts(f.shop.id).sumOf {repo.supplierOutstanding(f.shop.id,it.id)})
            assertEquals(PurchaseOrderStatus.APPROVED,repo.purchaseOrders(f.shop.id).single().status)
        }
    }
    @Test fun draftAndDeliveryRetriesAreExactEvenAfterCancellationAndProfileArchival() {
        PosRepository(":memory:").use {repo ->
            val f=fixture(repo)
            val draft=repo.createPurchaseOrder(f.shop.id,f.supplier.id,f.lines,"","order")
            assertEquals(draft,repo.createPurchaseOrder(f.shop.id,f.supplier.id,f.lines,"","order"))
            val order=repo.approvePurchaseOrder(f.shop.id,draft.id,draft.version,"approve")
            val request=delivery(f).reversed()
            val first=repo.receivePurchaseOrder(f.shop.id,order.id,request,order.version,"delivery")
            repo.cancelPurchaseOrder(f.shop.id,order.id,"Cancel remaining goods",order.version,"cancel")
            repo.updateSupplierProfile(f.shop.id,f.supplier.id,f.supplier.details,true,1,"Supplier stopped trading","archive")
            val before=repo.exportTenantSnapshot(f.shop.id)
            assertEquals(first,repo.receivePurchaseOrder(f.shop.id,order.id,request.reversed(),order.version,"delivery"))
            assertFails {repo.receivePurchaseOrder(f.shop.id,order.id,request.map {it.copy(paidCashMinor=1)},order.version,"delivery")}
            assertFails {repo.createPurchaseOrder(f.shop.id,f.supplier.id,f.lines,"Different","order")}
            assertEquals(before,repo.exportTenantSnapshot(f.shop.id))
        }
    }
    @Test fun overReceivingStaleStockChangedItemsAndArchivedConversionsLeaveNoPartialRecords() {
        PosRepository(":memory:").use {repo ->
            val f=fixture(repo);val order=approve(repo,f)
            var before=repo.exportTenantSnapshot(f.shop.id)
            assertFails {repo.receivePurchaseOrder(f.shop.id,order.id,delivery(f,rice=2501),order.version,"over")}
            assertEquals(before,repo.exportTenantSnapshot(f.shop.id))
            repo.receiveStock(f.shop.id,f.rice.id,1,1,0,"Other source","intervening")
            before=repo.exportTenantSnapshot(f.shop.id)
            assertFails {repo.receivePurchaseOrder(f.shop.id,order.id,delivery(f),order.version,"stale-stock")}
            assertEquals(before,repo.exportTenantSnapshot(f.shop.id))
            repo.updateProduct(f.shop.id,f.rice.id,"Renamed rice",f.rice.unit,f.rice.priceMinor,null,"rename")
            before=repo.exportTenantSnapshot(f.shop.id)
            assertFails {repo.receivePurchaseOrder(f.shop.id,order.id,delivery(f,riceStock=1),order.version,"stale-name")}
            assertEquals(before,repo.exportTenantSnapshot(f.shop.id))
        }
    }
    @Test fun staleSupplierDraftMustBeReviewedAndApprovedLinesCannotBeEdited() {
        PosRepository(":memory:").use {repo ->
            val f=fixture(repo)
            val draft=repo.createPurchaseOrder(f.shop.id,f.supplier.id,f.lines,"","order")
            repo.updateSupplierProfile(f.shop.id,f.supplier.id,f.supplier.details.copy(name="Renamed supplier"),false,1,"Trading name changed","rename")
            val before=repo.exportTenantSnapshot(f.shop.id)
            assertFails {repo.approvePurchaseOrder(f.shop.id,draft.id,draft.version,"stale-approval")}
            assertEquals(before,repo.exportTenantSnapshot(f.shop.id))
            val edited=repo.editPurchaseOrder(f.shop.id,draft.id,f.supplier.id,f.lines,"","Reviewed supplier",draft.version,"edit")
            val approved=repo.approvePurchaseOrder(f.shop.id,draft.id,edited.version,"approve")
            assertEquals("Renamed supplier",approved.supplierName)
            assertFails {repo.editPurchaseOrder(f.shop.id,draft.id,f.supplier.id,f.lines,"","Not allowed",approved.version,"bad-edit")}
        }
    }
    @Test fun lateCashOrActorFailureRollsBackEveryDeliveryLineAndSourceLink() {
        PosRepository(":memory:").use {repo ->
            val f=fixture(repo);val order=approve(repo,f)
            repo.openCashShift(f.shop.id,150)
            val before=repo.exportTenantSnapshot(f.shop.id)
            assertFails {repo.receivePurchaseOrder(f.shop.id,order.id,delivery(f,ricePaid=100,maltPaid=100),order.version,"short-cash")}
            assertEquals(before,repo.exportTenantSnapshot(f.shop.id))
            var actors=0
            repo.setActorProvider {tenant ->actors++;if(actors==3) error("Cannot save second receipt actor") else AuditActor(tenant,null,"Owner",StaffRole.OWNER)}
            assertFails {repo.receivePurchaseOrder(f.shop.id,order.id,delivery(f),order.version,"bad-actor")}
            repo.setActorProvider(null)
            assertEquals(before,repo.exportTenantSnapshot(f.shop.id))
            assertTrue(repo.purchaseOrderDeliveries(f.shop.id,order.id).isEmpty())
        }
    }
    @Test fun crossShopAndDeniedReceivingOrReplaysNeverChangeTheLedger() {
        PosRepository(":memory:").use {repo ->
            val f=fixture(repo);val order=approve(repo,f)
            val other=repo.createTenant("Other")
            assertFails {repo.approvePurchaseOrder(other.id,order.id,order.version,"foreign")}
            assertFails {repo.createPurchaseOrder(other.id,f.supplier.id,f.lines,"","foreign-create")}
            val received=repo.receivePurchaseOrder(f.shop.id,order.id,delivery(f),order.version,"delivery")
            val before=repo.exportTenantSnapshot(f.shop.id)
            repo.setPermissionCheck {_,permission ->if(permission==StaffPermission.MANAGE_STOCK) error("Denied stock")}
            assertFails {repo.receivePurchaseOrder(f.shop.id,order.id,delivery(f),order.version,received.id)}
            repo.setPermissionCheck {_,permission ->if(permission!=StaffPermission.BACKUP) error("Denied")}
            assertFails {repo.purchaseOrders(f.shop.id)}
            assertFails {repo.cancelPurchaseOrder(f.shop.id,order.id,"Denied cancellation",order.version,"denied")}
            repo.setPermissionCheck(null)
            assertEquals(before,repo.exportTenantSnapshot(f.shop.id))
        }
    }
    @Test fun cancellationAfterPartialDeliveryPreservesFinancialHistoryAndStopsTheRemainder() {
        PosRepository(":memory:").use {repo ->
            val f=fixture(repo);val order=approve(repo,f)
            repo.receivePurchaseOrder(f.shop.id,order.id,delivery(f),order.version,"delivery")
            val receipts=repo.stockReceipts(f.shop.id);val goods=repo.products(f.shop.id)
            val cancelled=repo.cancelPurchaseOrder(f.shop.id,order.id,"Supplier short delivery",order.version,"cancel")
            assertEquals(receipts,repo.stockReceipts(f.shop.id));assertEquals(goods,repo.products(f.shop.id))
            assertEquals(mapOf("rice-line" to 1250,"malt-line" to 1),repo.purchaseOrderRemaining(f.shop.id,order.id))
            assertFails {repo.receivePurchaseOrder(f.shop.id,order.id,delivery(f,riceStock=1250,maltStock=24),cancelled.version,"after-cancel")}
        }
    }
    @Test fun encryptedRecoveryPreservesApprovedSnapshotsReceiptsAndExactRetryIdentity() {
        PosRepository(":memory:").use {repo ->
            val f=fixture(repo);val order=approve(repo,f)
            val delivery=repo.receivePurchaseOrder(f.shop.id,order.id,delivery(f),order.version,"delivery")
            val file=Files.createTempDirectory("po-backup-").resolve("shop.backup")
            BackupService.create(repo,f.shop.id,"purchase-backup-password",file)
            val snapshot=BackupService.read(file,"purchase-backup-password")
            val restored=repo.restoreTenantSnapshot(snapshot,"Recovered orders")
            val recovered=repo.purchaseOrders(restored.id).single()
            assertEquals(order.copy(tenantId=restored.id),recovered)
            assertEquals(delivery.copy(tenantId=restored.id),repo.purchaseOrderDeliveries(restored.id,order.id).single())
            val before=repo.exportTenantSnapshot(restored.id)
            repo.receivePurchaseOrder(restored.id,order.id,delivery(f),order.version,"delivery")
            assertEquals(before,repo.exportTenantSnapshot(restored.id))
            assertEquals(2,repo.supplierReceiptLinks(restored.id).size)
        }
    }
    @Test fun tamperedApprovedLinesMissingDeliveryRowsAndOmittedHistoryRejectWholeRestore() {
        PosRepository(":memory:").use {repo ->
            val f=fixture(repo);val order=approve(repo,f)
            repo.receivePurchaseOrder(f.shop.id,order.id,delivery(f),order.version,"delivery")
            val snapshot=repo.exportTenantSnapshot(f.shop.id);val shops=repo.tenants()
            val changed=snapshot.copy(tables=snapshot.tables+("purchase_order_lines" to snapshot.tables.getValue("purchase_order_lines").map {it+("quantity" to "9999")}))
            val missing=snapshot.copy(tables=snapshot.tables+("purchase_order_delivery_lines" to emptyList()))
            val omitted=snapshot.copy(tables=snapshot.tables-purchaseOrderSnapshotTables.toSet())
            listOf(changed,missing,omitted).forEach {bad ->assertFails {repo.restoreTenantSnapshot(bad,"Must not exist")};assertEquals(shops,repo.tenants())}
        }
    }
    @Test fun legacySnapshotsAndRestartKeepDraftsWithoutInventingOrdersOrReceipts() {
        val path=Files.createTempDirectory("po-reopen-").resolve("shop.db").toString()
        var tenant="";var orderId=""
        PosRepository(path).use {repo ->
            val f=fixture(repo);tenant=f.shop.id
            val old=repo.exportTenantSnapshot(tenant)
            val restored=repo.restoreTenantSnapshot(old.copy(tables=old.tables-purchaseOrderSnapshotTables.toSet()),"Old backup")
            assertTrue(repo.purchaseOrders(restored.id).isEmpty())
            orderId=repo.createPurchaseOrder(tenant,f.supplier.id,f.lines,"Restart draft","order").id
        }
        PosRepository(path).use {repo ->assertEquals(orderId,repo.purchaseOrders(tenant).single().id);assertEquals(PurchaseOrderStatus.DRAFT,repo.purchaseOrders(tenant).single().status);assertTrue(repo.stockReceipts(tenant).isEmpty())}
    }
    @Test fun archivedSupplierOrBuyingUnitCannotAcceptNewDeliveries() {
        PosRepository(":memory:").use {repo ->
            val f=fixture(repo);val order=approve(repo,f)
            repo.updateSupplierProfile(f.shop.id,f.supplier.id,f.supplier.details,true,1,"Stopped trading","archive")
            var before=repo.exportTenantSnapshot(f.shop.id)
            assertFails {repo.receivePurchaseOrder(f.shop.id,order.id,delivery(f),order.version,"archived-supplier")}
            assertEquals(before,repo.exportTenantSnapshot(f.shop.id))
            repo.updateSupplierProfile(f.shop.id,f.supplier.id,f.supplier.details,false,2,"Supplier restored","restore")
            repo.archivePurchaseUnit(f.shop.id,f.carton.id,true)
            before=repo.exportTenantSnapshot(f.shop.id)
            assertFails {repo.receivePurchaseOrder(f.shop.id,order.id,delivery(f),order.version,"archived-unit")}
            assertEquals(before,repo.exportTenantSnapshot(f.shop.id))
        }
    }
    @Test fun independentlyRoundedDeliveriesRecordActualCostsRatherThanEstimatedOrderDebt() {
        PosRepository(":memory:").use {repo ->
            repo.initializePurchaseOrders()
            val shop=repo.createTenant("Fine costs")
            val supplier=repo.addSupplierProfile(shop.id,SupplierDetails("Supplier"),"supplier")
            val item=repo.addProduct(shop.id,"Ribbon","metre",100,0.0,quantityPrecision=2)
            val draft=repo.createPurchaseOrder(shop.id,supplier.id,listOf(PurchaseOrderLineInput("line",item.id,100,1)),"","order")
            val order=repo.approvePurchaseOrder(shop.id,draft.id,1,"approve")
            assertEquals(1L,order.estimatedTotalMinor)
            repo.receivePurchaseOrder(shop.id,order.id,listOf(PurchaseDeliveryInput("line",50,0,0)),order.version,"half-1")
            repo.receivePurchaseOrder(shop.id,order.id,listOf(PurchaseDeliveryInput("line",50,0,50)),order.version,"half-2")
            assertEquals(2L,repo.stockReceipts(shop.id).sumOf {it.totalCostMinor})
            assertEquals(2L,repo.stockReceipts(shop.id).sumOf {repo.supplierOutstanding(shop.id,it.id)})
            assertEquals("1",repo.products(shop.id).single().stockLabel)
        }
    }
    @Test fun approvedLineAndDeliveryHistoryRejectSqlMutation() {
        val path=Files.createTempDirectory("po-immutable-").resolve("shop.db").toString()
        PosRepository(path).use {repo ->val f=fixture(repo);val order=approve(repo,f);repo.receivePurchaseOrder(f.shop.id,order.id,delivery(f),order.version,"delivery")}
        DriverManager.getConnection("jdbc:sqlite:$path").use {db ->
            listOf("UPDATE purchase_order_lines SET quantity=1","DELETE FROM purchase_order_lines","UPDATE purchase_order_events SET reason='Rewrite'","DELETE FROM purchase_order_deliveries","DELETE FROM purchase_order_delivery_lines").forEach {sql ->assertFails {db.createStatement().use {it.execute(sql)}}}
        }
    }
}
