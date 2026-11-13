package pos.data

import kotlin.test.*
import pos.domain.*
import pos.output.*
import java.nio.file.Files
import java.time.LocalDate
import pos.screens.ReportDateRange

class BasketDiscountIntegrityTest {
    @Test fun mixedMeasuredAndWholeLinesPersistAndPartialRefundsConserveCash() {
        PosRepository(":memory:").use {repo ->
            val shop=repo.createTenant("Basket discounts")
            val rice=repo.addProduct(shop.id,"Loose rice","kg",101,10.0,quantityPrecision=3)
            val tins=repo.addProduct(shop.id,"Tomato tins","tin",101,10.0)
            val rule=BasketDiscount("fixed",101,"Agreed basket total")
            val lines=allocateBasketDiscount(listOf(CartLine(rice,1375),CartLine(tins,3)),rule)
            val shift=repo.openCashShift(shop.id,10_000)
            val sale=repo.checkout(shop.id,lines,341,operationId="basket-sale",basketDiscount=rule)
            assertEquals(rule,repo.sales(shop.id).single().basketDiscount)
            assertEquals(lines.map {it.basketDiscountMinor},sale.lines.map {it.basketDiscountMinor})
            assertEquals(sale.id,repo.checkout(shop.id,lines,341,operationId="basket-sale",basketDiscount=rule).id)
            listOf(1,374,1000).forEachIndexed {index,count -> repo.returnItems(shop.id,sale.id,mapOf(rice.id to count),true,"Partial measured return","rice-return-$index")}
            repeat(3) {repo.returnItems(shop.id,sale.id,mapOf(tins.id to 1),true,"One tin returned","tin-return-$it")}
            assertEquals(sale.totalMinor,repo.saleReturns(shop.id).sumOf {it.refundMinor})
            assertEquals(10_000L,repo.expectedCash(shop.id,shift.id))
            assertEquals(listOf("10","10"),repo.products(shop.id).map {it.stockLabel})
            assertContains(ReceiptService.format(shop,sale),"Basket discount: ₦1.01")
            assertContains(CsvService.sales(shop.id,listOf(sale)),"Agreed basket total")
            val today=LocalDate.now()
            val report=ProductPerformanceService.build(shop.id,listOf(sale),repo.saleReturns(shop.id),ReportDateRange(today.minusDays(1),today.plusDays(1)))
            assertEquals(0L,report.sumOf {it.netSalesMinor})
        }
    }
    @Test fun encryptedBackupRestoresRulesAndRejectsTamperedAllocationsAtomically() {
        PosRepository(":memory:").use {repo ->
            val shop=repo.createTenant("Original")
            val item=repo.addProduct(shop.id,"Fabric","metre",200,10.0,quantityPrecision=2)
            val rule=BasketDiscount("percentage",1250,"Sale promotion")
            val lines=allocateBasketDiscount(listOf(CartLine(item,125)),rule)
            repo.checkout(shop.id,lines,cartTotal(lines),operationId="sale",basketDiscount=rule)
            val backupDirectory=Files.createTempDirectory("basket-backup-")
            val snapshot=try {
                val file=backupDirectory.resolve("shop.posbackup")
                BackupService.create(repo,shop.id,"Backup-password-2026",file)
                BackupService.read(file,"Backup-password-2026")
            } finally {backupDirectory.toFile().deleteRecursively()}
            val restored=repo.restoreTenantSnapshot(snapshot,"Recovered")
            assertEquals(rule,repo.sales(restored.id).single().basketDiscount)
            assertEquals(lines.single().totalMinor,repo.sales(restored.id).single().totalMinor)
            val before=repo.tenants()
            val tampered=snapshot.copy(tables=snapshot.tables+("sale_lines" to snapshot.tables.getValue("sale_lines").map {it+("basket_discount_minor" to "1")}))
            assertFails {repo.restoreTenantSnapshot(tampered,"Bad allocation")}
            assertEquals(before,repo.tenants())
        }
    }
    @Test fun missingPermissionForgedSharesAndChangedRetryLeaveAllRecordsUnchanged() {
        PosRepository(":memory:").use {repo ->
            val shop=repo.createTenant("Shop")
            val item=repo.addProduct(shop.id,"Soap","piece",100,10.0)
            val rule=BasketDiscount("fixed",1,"Basket reduction")
            val lines=allocateBasketDiscount(listOf(CartLine(item,2)),rule)
            val before=repo.exportTenantSnapshot(shop.id)
            repo.setPermissionCheck {_,permission -> require(permission!=StaffPermission.DISCOUNT)}
            assertFails {repo.checkout(shop.id,lines,199,operationId="sale",basketDiscount=rule)}
            repo.setPermissionCheck(null)
            assertEquals(before,repo.exportTenantSnapshot(shop.id))
            assertFails {repo.checkout(shop.id,listOf(lines.single().copy(basketDiscountMinor=2)),198,operationId="forged",basketDiscount=rule)}
            assertEquals(before,repo.exportTenantSnapshot(shop.id))
            repo.checkout(shop.id,lines,199,operationId="sale",basketDiscount=rule)
            val saved=repo.exportTenantSnapshot(shop.id)
            assertFails {repo.checkout(shop.id,lines,199,operationId="sale",basketDiscount=rule.copy(reason="Changed reason"))}
            assertEquals(saved,repo.exportTenantSnapshot(shop.id))
        }
    }
    @Test fun fullyDiscountedSaleAndZeroRefundStillRecordStockExactlyOnce() {
        PosRepository(":memory:").use {repo ->
            val shop=repo.createTenant("Samples")
            val item=repo.addProduct(shop.id,"Sample item","piece",100,3.0)
            val rule=BasketDiscount("percentage",10000,"Complimentary sale")
            repo.openCashShift(shop.id,0)
            val sale=repo.checkout(shop.id,allocateBasketDiscount(listOf(CartLine(item,2)),rule),0,operationId="free",basketDiscount=rule)
            assertEquals(0L,sale.totalMinor)
            val returned=repo.returnItems(shop.id,sale.id,mapOf(item.id to 2),true,"Sample returned","free-return")
            assertEquals(0L,returned.refundMinor)
            assertEquals("3",repo.products(shop.id).single().stockLabel)
        }
    }
    @Test fun heldDiscountAndReviewFingerprintSurviveRestart() {
        val directory=Files.createTempDirectory("basket-draft-")
        try {
            val rule=BasketDiscount("fixed",1,"Agreed basket")
            val original=SaleDraft("item:2","draft",discounts=emptyMap(),basketDiscount=rule,basketBasis="saved review")
            DraftStore("shop",directory).apply {save(original);hold("Later",original)}
            val reopened=DraftStore("shop",directory)
            assertEquals(original,reopened.resume(reopened.heldSales().single().id))
            assertEquals(original,DraftStore("shop",directory).load())
        } finally {Files.walk(directory).use {it.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists)}}
    }
}
