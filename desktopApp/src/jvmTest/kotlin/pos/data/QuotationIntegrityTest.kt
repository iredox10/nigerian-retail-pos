package pos.data

import kotlin.test.*
import pos.domain.*
import java.nio.file.Files
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

class QuotationIntegrityTest {
    private val expiry=LocalDate.now(ZoneId.of("Africa/Lagos")).plusDays(7).toString()
    private fun repo(path:String=":memory:")=PosRepository(path).also {it.initializeQuotations()}
    private fun input(product:Product,price:Long=10000,quantity:Int=1375)=listOf(QuotationLineInput(product.id,null,quantity,price))
    private fun economicState(repo:PosRepository,tenant:String)=repo.exportTenantSnapshot(tenant).tables.filterKeys {it !in quotationSnapshotTables+listOf("outbox","activity_audit")}

    @Test fun issueReviseAcceptCancelPreserveFrozenHistoryWithoutEconomicEffects() {
        repo().use {repo ->
            val shop=repo.createTenant("Shop");val item=repo.addProduct(shop.id,"Rice","kg",12345,10.0,quantityPrecision=3)
            val customer=repo.addCustomer(shop.id,"Amina","08012345678","Original address")
            val before=economicState(repo,shop.id)
            val contact=QuotationCustomer(customer.id,customer.name,customer.phone,customer.address)
            val first=repo.issueQuotation(shop.id,input(item),expiry,contact,"Deliver only after sale","quote-id")
            assertEquals(first,repo.issueQuotation(shop.id,input(item),expiry,contact,"Deliver only after sale","quote-id"))
            assertFails {repo.issueQuotation(shop.id,input(item,price=9999),expiry,contact,"Deliver only after sale","quote-id")}
            val revised=repo.reviseQuotation(shop.id,first.id,input(item,price=9000),expiry,contact,"New agreed amount","Price agreed again",1,"revise")
            assertEquals(revised,repo.reviseQuotation(shop.id,first.id,input(item,price=9000),expiry,contact,"New agreed amount","Price agreed again",1,"revise"))
            assertFails {repo.reviseQuotation(shop.id,first.id,input(item),expiry,contact,"","Stale revision",1,"stale")}
            val accepted=repo.acceptQuotation(shop.id,first.id,2,"accept")
            assertFails {repo.reviseQuotation(shop.id,first.id,input(item),expiry,contact,"","Accepted edit",accepted.version,"no-edit")}
            val cancelled=repo.cancelQuotation(shop.id,first.id,"Customer cancelled",accepted.version,"cancel")
            assertEquals(QuotationStatus.CANCELLED,cancelled.status)
            assertEquals(before,economicState(repo,shop.id))
            val history=repo.quotationEvents(shop.id,first.id)
            assertEquals(listOf("issued","revised","accepted","cancelled"),history.map {it.kind})
            assertEquals(10000L,history.first().snapshot.lines.single().agreedUnitPriceMinor)
            assertEquals("Original address",history.last().snapshot.customer!!.address)
            repo.validateQuotations(shop.id)
        }
    }
    @Test fun realCashConversionPreservesFractionalAgreementStockCashAndStableRetry() {
        repo().use {repo ->
            val shop=repo.createTenant("Shop");val item=repo.addProduct(shop.id,"Rice","kg",12345,10.0,quantityPrecision=3)
            repo.openCashShift(shop.id,0)
            val first=repo.issueQuotation(shop.id,input(item),expiry,operationId="quote")
            val accepted=repo.acceptQuotation(shop.id,first.id,first.version)
            val review=repo.quotationConversionReview(shop.id,accepted.id,accepted.version)
            assertEquals(13750L,cartTotal(review.lines))
            assertFails {repo.convertQuotation(shop.id,accepted.id,accepted.version,review.token,13749,"conversion")}
            val sale=repo.convertQuotation(shop.id,accepted.id,accepted.version,review.token,13750,"conversion")
            assertEquals(13750L,sale.cashMinor);assertEquals(0L,sale.creditMinor)
            assertEquals(10000L,sale.lines.single().netUnitPriceMinor)
            assertEquals(quotationSaleSource(accepted.id,accepted.version),sale.lines.single().discountReason)
            assertEquals(8625L,repo.products(shop.id).single().exactStockScaled)
            assertEquals(13750L,repo.cashShifts(shop.id).single().expectedMinor)
            assertEquals(sale,repo.convertQuotation(shop.id,accepted.id,accepted.version,review.token,13750,"conversion"))
            assertFails {repo.convertQuotation(shop.id,accepted.id,accepted.version,"wrong",13750,"conversion")}
            assertFails {repo.convertQuotation(shop.id,accepted.id,accepted.version,review.token,13750,"another-sale")}
            assertEquals(1,repo.sales(shop.id).size)
            assertEquals(sale.id,repo.quotations(shop.id).single().saleId)
            repo.validateQuotations(shop.id)
        }
    }
    @Test fun changedNormalPriceNeedsNewReviewAndHigherAgreementOrChangedGoodsCannotConvert() {
        repo().use {repo ->
            val shop=repo.createTenant("Shop");val item=repo.addProduct(shop.id,"Rice","bag",10000,20.0)
            val quote=repo.issueQuotation(shop.id,input(item,9000,1),expiry)
            val accepted=repo.acceptQuotation(shop.id,quote.id,quote.version)
            val review=repo.quotationConversionReview(shop.id,accepted.id,accepted.version)
            repo.updateProduct(shop.id,item.id,item.name,item.unit,11000,null)
            assertFails {repo.convertQuotation(shop.id,accepted.id,accepted.version,review.token,9000,"sale")}
            val newReview=repo.quotationConversionReview(shop.id,accepted.id,accepted.version)
            assertNotEquals(review.token,newReview.token)
            repo.updateProduct(shop.id,item.id,item.name,item.unit,8000,null)
            assertFails {repo.quotationConversionReview(shop.id,accepted.id,accepted.version)}
            repo.updateProduct(shop.id,item.id,"Different rice",item.unit,11000,null)
            assertFails {repo.quotationConversionReview(shop.id,accepted.id,accepted.version)}
            assertTrue(repo.sales(shop.id).isEmpty());assertEquals(20.0,repo.products(shop.id).single().stock)
            assertEquals(9000L,repo.quotations(shop.id).single().lines.single().agreedUnitPriceMinor)
        }
    }
    @Test fun packQuotesKeepTheirConversionAndLateAuditFailureRollsBackSaleAndQuoteTogether() {
        repo().use {repo ->
            val shop=repo.createTenant("Shop");val item=repo.addProduct(shop.id,"Soap","piece",1000,100.0)
            val pack=repo.addSellingUnit(shop.id,item.id,"carton",12,10000)
            val quote=repo.issueQuotation(shop.id,listOf(QuotationLineInput(item.id,pack.id,2,9000)),expiry)
            val accepted=repo.acceptQuotation(shop.id,quote.id,quote.version)
            val review=repo.quotationConversionReview(shop.id,quote.id,accepted.version)
            val before=repo.exportTenantSnapshot(shop.id)
            var calls=0
            repo.setActorProvider {tenant ->calls++;if(calls==2) error("Cannot record quotation actor") else AuditActor(tenant,null,"Owner",StaffRole.OWNER)}
            assertFails {repo.convertQuotation(shop.id,quote.id,accepted.version,review.token,18000,"conversion")}
            repo.setActorProvider(null)
            assertEquals(before,repo.exportTenantSnapshot(shop.id))
            val sale=repo.convertQuotation(shop.id,quote.id,accepted.version,review.token,18000,"conversion")
            assertEquals(12,sale.lines.single().baseUnitsPerUnit)
            assertEquals(76.0,repo.products(shop.id).single().stock)
            repo.validateQuotations(shop.id)
        }
    }
    @Test fun tenantPermissionAndSellDenialsNeverCrossShopOrRecordFinancialEvents() {
        repo().use {repo ->
            val shop=repo.createTenant("Shop");val other=repo.createTenant("Other")
            val item=repo.addProduct(shop.id,"Rice","bag",10000,5.0)
            val foreign=repo.addProduct(other.id,"Other rice","bag",10000,5.0)
            assertFails {repo.issueQuotation(shop.id,input(foreign,9000,1),expiry)}
            val quote=repo.issueQuotation(shop.id,input(item,9000,1),expiry)
            assertFails {repo.acceptQuotation(other.id,quote.id,quote.version)}
            val accepted=repo.acceptQuotation(shop.id,quote.id,quote.version)
            val review=repo.quotationConversionReview(shop.id,quote.id,accepted.version)
            val before=repo.exportTenantSnapshot(shop.id)
            repo.setPermissionCheck {_,permission ->require(permission!=StaffPermission.SELL) {"Cannot sell"}}
            assertFails {repo.convertQuotation(shop.id,quote.id,accepted.version,review.token,9000,"sale")}
            repo.setPermissionCheck {_,permission ->require(permission!=StaffPermission.DISCOUNT) {"Cannot manage quotations"}}
            assertFails {repo.cancelQuotation(shop.id,quote.id,"Not allowed",accepted.version)}
            assertEquals(1,repo.quotations(shop.id).size)
            repo.setPermissionCheck(null)
            assertEquals(before,repo.exportTenantSnapshot(shop.id))
        }
    }
    @Test fun baseAndPackGoodsShareStockAndArchivedPacksCannotBeSubstituted() {
        repo().use {repo ->
            val shop=repo.createTenant("Shop");val item=repo.addProduct(shop.id,"Soap","piece",1000,20.0)
            val pack=repo.addSellingUnit(shop.id,item.id,"carton",12,10000)
            val tooMuch=repo.issueQuotation(shop.id,listOf(QuotationLineInput(item.id,null,9,1000),QuotationLineInput(item.id,pack.id,1,10000)),expiry)
            val accepted=repo.acceptQuotation(shop.id,tooMuch.id,tooMuch.version)
            val before=repo.exportTenantSnapshot(shop.id)
            assertFails {repo.quotationConversionReview(shop.id,accepted.id,accepted.version)}
            assertEquals(before,repo.exportTenantSnapshot(shop.id))
            val quote=repo.issueQuotation(shop.id,listOf(QuotationLineInput(item.id,pack.id,1,10000)),expiry)
            val acceptedPack=repo.acceptQuotation(shop.id,quote.id,quote.version)
            val review=repo.quotationConversionReview(shop.id,quote.id,acceptedPack.version)
            repo.archiveSellingUnit(shop.id,pack.id,true)
            repo.addSellingUnit(shop.id,item.id,"carton",6,10000)
            val afterArchive=repo.exportTenantSnapshot(shop.id)
            assertFails {repo.convertQuotation(shop.id,quote.id,acceptedPack.version,review.token,10000,"no-substitution")}
            assertEquals(afterArchive,repo.exportTenantSnapshot(shop.id))
            assertTrue(repo.sales(shop.id).isEmpty())
            assertEquals(12,repo.quotations(shop.id).single {it.id==quote.id}.lines.single().baseUnitsPerUnit)
        }
    }
    @Test fun conversionCannotAdoptAnUnrelatedExistingSaleOperationId() {
        repo().use {repo ->
            val shop=repo.createTenant("Shop");val item=repo.addProduct(shop.id,"Rice","bag",10000,5.0)
            repo.checkout(shop.id,listOf(CartLine(item,1)),10000,operationId="already-a-sale")
            val quote=repo.issueQuotation(shop.id,input(item,10000,1),expiry)
            val accepted=repo.acceptQuotation(shop.id,quote.id,quote.version)
            val review=repo.quotationConversionReview(shop.id,quote.id,accepted.version)
            val before=repo.exportTenantSnapshot(shop.id)
            assertFails {repo.convertQuotation(shop.id,quote.id,accepted.version,review.token,10000,"already-a-sale")}
            assertEquals(before,repo.exportTenantSnapshot(shop.id))
        }
    }
    @Test fun watExpiryIncludesWholeEndDateAndRejectsInvalidOrPastNewTerms() {
        assertFalse(quotationExpired("2026-11-14",Instant.parse("2026-11-14T22:59:59.999999999Z")))
        assertTrue(quotationExpired("2026-11-14",Instant.parse("2026-11-14T23:00:00Z")))
        repo().use {repo ->val shop=repo.createTenant("Shop");val item=repo.addProduct(shop.id,"Rice","bag",10000,1.0)
            assertFails {repo.issueQuotation(shop.id,input(item,9000,1),"2026-02-30")}
            assertFails {repo.issueQuotation(shop.id,input(item,9000,1),LocalDate.now(ZoneId.of("Africa/Lagos")).minusDays(1).toString())}
        }
    }
    @Test fun restartAndBackupPreserveRevisionsAndTamperedWitnessOrLinkRejectsRestore() {
        val folder=Files.createTempDirectory("quotation-restart-");val path=folder.resolve("shop.db").toString()
        try {
            var tenantId="";var quoteId=""
            repo(path).use {repo ->
                val shop=repo.createTenant("Shop");tenantId=shop.id
                val item=repo.addProduct(shop.id,"Rice","bag",10000,5.0)
                val quote=repo.issueQuotation(shop.id,input(item,9000,1),expiry);quoteId=quote.id
                val accepted=repo.acceptQuotation(shop.id,quote.id,quote.version)
                val review=repo.quotationConversionReview(shop.id,quote.id,accepted.version)
                repo.convertQuotation(shop.id,quote.id,accepted.version,review.token,9000,"sale")
            }
            repo(path).use {repo ->
                assertEquals(QuotationStatus.CONVERTED,repo.quotations(tenantId).single().status)
                assertEquals(3,repo.quotationEvents(tenantId,quoteId).size)
                val snapshot=repo.exportTenantSnapshot(tenantId)
                assertTrue(quotationSnapshotTables.all {it in snapshot.tables})
                val restored=repo.restoreTenantSnapshot(snapshot,"Recovered quotations")
                repo.validateQuotations(restored.id)
                assertEquals("sale",repo.quotations(restored.id).single().saleId)
                val before=repo.tenants()
                val missingEvent=snapshot.copy(tables=snapshot.tables+("quotation_events" to snapshot.tables.getValue("quotation_events").dropLast(1)))
                assertFails {repo.restoreTenantSnapshot(missingEvent,"Must fail")}
                val missingWitness=snapshot.copy(tables=snapshot.tables+("outbox" to snapshot.tables.getValue("outbox").filterNot {it["kind"]=="quotation.converted"}))
                assertFails {repo.restoreTenantSnapshot(missingWitness,"Must fail")}
                val changedSale=snapshot.copy(tables=snapshot.tables+("sale_lines" to snapshot.tables.getValue("sale_lines").map {it+("discount_reason" to "Different source")}))
                assertFails {repo.restoreTenantSnapshot(changedSale,"Must fail")}
                assertEquals(before,repo.tenants())
            }
        } finally {folder.toFile().deleteRecursively()}
    }
}
