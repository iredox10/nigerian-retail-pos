package pos.data

import kotlin.test.*
import pos.domain.*
import pos.output.CsvService
import pos.output.ReceiptService
import java.nio.file.Files

class DiscountIntegrityTest {
    @Test fun cashSaleStoresOriginalPriceReasonAndNetCashWithoutChangingCatalogue() {
        PosRepository(":memory:").use { repo ->
            val shop=repo.createTenant("Market")
            val item=repo.addProduct(shop.id,"Malt","bottle",50_000,60.0)
            val shift=repo.openCashShift(shop.id,100_000)
            val line=CartLine(item,3,discountPerUnitMinor=5_000,discountReason="Agreed market price")
            val sale=repo.checkout(shop.id,listOf(line),135_000,operationId="discount-sale")
            assertEquals(135_000L,sale.totalMinor)
            assertEquals(235_000L,repo.expectedCash(shop.id,shift.id))
            assertEquals(50_000L,repo.products(shop.id).single().priceMinor)
            assertEquals(57.0,repo.products(shop.id).single().stock)
            val saved=repo.sales(shop.id).single().lines.single()
            assertEquals(50_000L,saved.unitPriceMinor)
            assertEquals(45_000L,saved.netUnitPriceMinor)
            assertEquals("Agreed market price",saved.discountReason)
            val text=ReceiptService.format(shop,repo.sales(shop.id).single())
            assertContains(text,"Before discounts: ₦1,500.00")
            assertContains(text,"Total discount: ₦150.00")
            assertContains(text,"Price paid: ₦450.00 per bottle")
            assertContains(text,"Reason: Agreed market price")
            val csv=CsvService.sales(shop.id,repo.sales(shop.id))
            assertContains(csv,"Before discounts NGN")
            assertContains(csv,"\"1500.00\",\"150.00\"")
        }
    }

    @Test fun mixedPackAndLooseDiscountsRefundEachNetUnitWithoutRoundingOrDuplicateEffects() {
        PosRepository(":memory:").use { repo ->
            val shop=repo.createTenant("Market")
            val item=repo.addProduct(shop.id,"Malt","bottle",50_000,60.0)
            val unit=repo.addSellingUnit(shop.id,item.id,"carton",24,1_100_000)
            val shift=repo.openCashShift(shop.id,1_000_000)
            val pack=CartLine(item,1,unit.id,unit.name,unit.priceMinor,24,100_001,"Negotiated carton price")
            val loose=CartLine(item,3,discountPerUnitMinor=1,discountReason="Agreed unit price")
            val sale=repo.checkout(shop.id,listOf(pack,loose),1_149_996,operationId="mixed")
            assertEquals(1_149_996L,sale.totalMinor)
            val first=repo.returnItems(shop.id,sale.id,mapOf(pack.key to 1,item.id to 1),true,"Returned unopened","return")
            assertEquals(1_049_998L,first.refundMinor)
            assertEquals(58.0,repo.products(shop.id).single().stock)
            val second=repo.returnItems(shop.id,sale.id,mapOf(item.id to 2),true,"Remaining bottles")
            assertEquals(99_998L,second.refundMinor)
            assertEquals(sale.totalMinor,first.refundMinor+second.refundMinor)
            assertEquals(1_000_000L,repo.expectedCash(shop.id,shift.id))
            val pending=repo.pendingSyncCount(shop.id)
            repo.checkout(shop.id,listOf(pack,loose),1_149_996,operationId="mixed")
            repo.returnItems(shop.id,sale.id,mapOf(pack.key to 1,item.id to 1),true,"Returned unopened","return")
            assertEquals(pending,repo.pendingSyncCount(shop.id))
            assertEquals(60.0,repo.products(shop.id).single().stock)
        }
    }

    @Test fun retryCannotChangeDiscountAmountOrReasonAndStaleCatalogueCannotBeHidden() {
        PosRepository(":memory:").use { repo ->
            val shop=repo.createTenant("Shop")
            val item=repo.addProduct(shop.id,"Soap","piece",20_000,10.0)
            val line=CartLine(item,2,discountPerUnitMinor=2_000,discountReason="Agreed price")
            repo.checkout(shop.id,listOf(line),36_000,operationId="same")
            assertFails { repo.checkout(shop.id,listOf(line.copy(discountReason="Different reason")),36_000,operationId="same") }
            assertFails { repo.checkout(shop.id,listOf(line.copy(discountPerUnitMinor=3_000)),34_000,operationId="same") }
            repo.updateProduct(shop.id,item.id,"Soap","piece",25_000,null)
            assertFails { repo.checkout(shop.id,listOf(line),36_000,operationId="new") }
            assertEquals(8.0,repo.products(shop.id).single().stock)
            assertEquals(1,repo.sales(shop.id).size)
        }
    }

    @Test fun discountedCreditUsesNetDebtAndEnforcesCustomerLimit() {
        PosRepository(":memory:").use { repo ->
            val shop=repo.createTenant("Shop")
            val item=repo.addProduct(shop.id,"Rice","bag",100_000,10.0)
            val customer=repo.addCustomer(shop.id,"Amina",creditLimitMinor=90_000)
            val line=CartLine(item,1,discountPerUnitMinor=10_000,discountReason="Agreed bag price")
            val sale=repo.checkout(shop.id,listOf(line),0,customer.id)
            assertEquals(90_000L,sale.creditMinor)
            assertEquals(90_000L,repo.customers(shop.id).single().balanceMinor)
            assertFails { repo.checkout(shop.id,listOf(line),0,customer.id) }
            assertEquals(9.0,repo.products(shop.id).single().stock)
        }
    }

    @Test fun fullDiscountRecordsReasonAndStockButNoCashAndRefundsZero() {
        PosRepository(":memory:").use { repo ->
            val shop=repo.createTenant("Shop")
            val item=repo.addProduct(shop.id,"Soap","piece",20_000,2.0)
            repo.openCashShift(shop.id,0)
            val line=CartLine(item,1,discountPerUnitMinor=20_000,discountReason="Owner gave sample")
            val sale=repo.checkout(shop.id,listOf(line),0)
            assertEquals(0L,sale.totalMinor)
            assertTrue(repo.cashEntries(shop.id).isEmpty())
            assertEquals(0L,repo.returnItems(shop.id,sale.id,mapOf(item.id to 1),true,"Sample returned").refundMinor)
            assertEquals(2.0,repo.products(shop.id).single().stock)
            assertTrue(repo.cashEntries(shop.id).isEmpty())
        }
    }

    @Test fun backupPreservesDiscountedSalesAndReturnsAndRejectsTamperingAtomically() {
        PosRepository(":memory:").use { repo ->
            val shop=repo.createTenant("Shop")
            val item=repo.addProduct(shop.id,"Soap","piece",20_000,10.0)
            repo.openCashShift(shop.id,100_000)
            val sale=repo.checkout(shop.id,listOf(CartLine(item,2,discountPerUnitMinor=2_000,discountReason="Agreed price")),36_000)
            repo.returnItems(shop.id,sale.id,mapOf(item.id to 1),true,"One returned")
            val snapshot=repo.exportTenantSnapshot(shop.id)
            val restored=repo.restoreTenantSnapshot(snapshot,"Restored")
            assertEquals(2_000L,repo.sales(restored.id).single().lines.single().discountPerUnitMinor)
            assertEquals(18_000L,repo.saleReturns(restored.id).single().refundMinor)
            val original=snapshot.tables.getValue("sale_lines").single()
            listOf(original+("discount_per_unit_minor" to "25000"), original+("discount_reason" to null), original+("discount_per_unit_minor" to "1000")).forEach { bad ->
                val count=repo.tenants().size
                assertFails { repo.restoreTenantSnapshot(snapshot.copy(tables=snapshot.tables+("sale_lines" to listOf(bad))),"Bad") }
                assertEquals(count,repo.tenants().size)
            }
        }
    }

    @Test fun backupsWithoutDiscountColumnsRestoreAtOriginalPrice() {
        PosRepository(":memory:").use { repo ->
            val shop=repo.createTenant("Shop")
            val item=repo.addProduct(shop.id,"Soap","piece",20_000,10.0)
            repo.checkout(shop.id,listOf(CartLine(item,2)),40_000)
            val snapshot=repo.exportTenantSnapshot(shop.id)
            val legacy=snapshot.copy(tables=snapshot.tables+("sale_lines" to snapshot.tables.getValue("sale_lines").map { it- "discount_per_unit_minor"-"discount_reason" }))
            val restored=repo.restoreTenantSnapshot(legacy,"Legacy")
            val line=repo.sales(restored.id).single().lines.single()
            assertEquals(0L,line.discountPerUnitMinor)
            assertNull(line.discountReason)
            assertEquals(40_000L,line.totalMinor)
        }
    }

    @Test fun discountsAreIsolatedByShopEvenWhenOperationReferencesMatch() {
        PosRepository(":memory:").use { repo ->
            val a=repo.createTenant("A");val b=repo.createTenant("B")
            val p=repo.addProduct(a.id,"Soap","piece",20_000,10.0)
            val q=repo.addProduct(b.id,"Soap","piece",20_000,10.0)
            val line=CartLine(p,1,discountPerUnitMinor=2_000,discountReason="Agreed price")
            assertFails { repo.checkout(b.id,listOf(line),18_000,operationId="same") }
            repo.checkout(a.id,listOf(line),18_000,operationId="same")
            repo.checkout(b.id,listOf(CartLine(q,1)),20_000,operationId="same")
            assertEquals(18_000L,repo.sales(a.id).single().totalMinor)
            assertEquals(20_000L,repo.sales(b.id).single().totalMinor)
        }
    }

    @Test fun discountedDraftSurvivesRestartHoldResumeAndPaymentEdits() {
        val dir=Files.createTempDirectory("discount-draft")
        try {
            val draft=SaleDraft("item~carton:1;item:3","operation","12000",null,"cash","",mapOf("item~carton" to UnitDiscount(1_100_000,100_000,"Agreed carton price")))
            val store=DraftStore("shop",dir)
            store.save(draft)
            assertEquals(draft,DraftStore("shop",dir).load())
            val held=store.hold("Agreed price",draft)
            assertEquals(draft,DraftStore("shop",dir).heldSales().single().draft)
            assertEquals(draft,store.resume(held.id))
            store.save(draft.copy(cashText="11500"))
            assertEquals(draft.discounts,store.load()!!.discounts)
            assertNull(DraftStore("other-shop",dir).load())
            assertFails { store.save(draft.copy(cart="item:1")) }
            assertEquals("11500",store.load()!!.cashText)
        } finally { dir.toFile().deleteRecursively() }
    }

    @Test fun corruptDraftDiscountsCannotBeOverwrittenOrDecodedWithTrailingData() {
        val dir=Files.createTempDirectory("discount-corrupt")
        try {
            val store=DraftStore("shop",dir)
            store.save(SaleDraft("item:1","operation",discounts=mapOf("item" to UnitDiscount(50_000,5_000,"Agreed price"))))
            val file=Files.list(dir.resolve("drafts")).use { stream -> stream.filter { it.toString().endsWith(".properties") }.findFirst().get() }
            val props=java.util.Properties().apply { Files.newInputStream(file).use { load(it) } }
            props.setProperty("discounts","not-base64!")
            Files.newOutputStream(file).use { props.store(it,"Corrupt fixture") }
            val before=Files.readAllBytes(file)
            assertFails { store.load() }
            assertFails { store.save(SaleDraft("item:2","operation")) }
            assertContentEquals(before,Files.readAllBytes(file))
            val encoded=DraftDiscountCodec.encode(mapOf("item" to UnitDiscount(50_000,5_000,"Agreed price")))
            val data=java.util.Base64.getDecoder().decode(encoded)+byteArrayOf(0)
            assertFails { DraftDiscountCodec.decode(java.util.Base64.getEncoder().encodeToString(data)) }
        } finally { dir.toFile().deleteRecursively() }
    }
}
