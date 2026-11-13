package pos.data

import kotlin.test.*
import pos.domain.*
import pos.output.*
import java.nio.file.Files

class FractionalQuantityIntegrityTest {
    @Test fun measuredGoodsReceiveSellCountReturnAndRestoreWithoutChangingUnits() {
        PosRepository(":memory:").use { repo ->
            val shop=repo.createTenant("Measured goods")
            val rice=repo.addProduct(shop.id,"Rice","kg",30_000,10.125,quantityPrecision=3)
            assertEquals(10_125L,rice.exactStockScaled)
            val receipt=repo.receiveStock(shop.id,rice.id,2_375,20_000,0,"Supplier","rice-in")
            assertEquals(47_500L,receipt.totalCostMinor)
            assertEquals("2.375",receipt.quantityLabel)
            assertEquals("12.5",repo.products(shop.id).single().stockLabel)
            repo.openCashShift(shop.id,100_000)
            val sale=repo.checkout(shop.id,listOf(CartLine(repo.products(shop.id).single(),1_375)),41_250,operationId="measured-sale")
            assertEquals(41_250L,sale.totalMinor)
            assertEquals("11.125",repo.products(shop.id).single().stockLabel)
            repo.returnItems(shop.id,sale.id,mapOf(rice.id to 375),true,"Part returned","return-rice")
            assertEquals("11.5",repo.products(shop.id).single().stockLabel)
            repo.adjustStock(shop.id,rice.id,11_250,"Physical count","rice-count",expectedStock=11.5)
            assertEquals("11.25",repo.products(shop.id).single().stockLabel)
            assertEquals(-0.25,repo.stockAdjustments(shop.id).single().difference)
            val restored=repo.restoreTenantSnapshot(repo.exportTenantSnapshot(shop.id),"Recovered")
            assertEquals("11.25",repo.products(restored.id).single().stockLabel)
            assertEquals("1.375",repo.sales(restored.id).single().lines.single().quantityLabel)
            assertEquals("0.375",repo.saleReturns(restored.id).single().lines.single().quantityLabel)
            assertTrue(ReceiptService.format(shop,sale).contains("1.375 kg"))
        }
    }

    @Test fun cumulativeRoundingRefundsExactlyTheSavedLineAndCannotOverReturn() {
        PosRepository(":memory:").use { repo ->
            val shop=repo.createTenant("Tiny amounts")
            val item=repo.addProduct(shop.id,"Measured","metre",1,1.0,quantityPrecision=2)
            repo.openCashShift(shop.id,100)
            val sale=repo.checkout(shop.id,listOf(CartLine(item,100)),1,operationId="one-metre")
            val first=repo.returnItems(shop.id,sale.id,mapOf(item.id to 49),true,"Part one","r1")
            val second=repo.returnItems(shop.id,sale.id,mapOf(item.id to 1),true,"Part two","r2")
            val third=repo.returnItems(shop.id,sale.id,mapOf(item.id to 50),true,"Part three","r3")
            assertEquals(listOf(0L,1L,0L),listOf(first.refundMinor,second.refundMinor,third.refundMinor))
            assertEquals(sale.totalMinor,listOf(first,second,third).sumOf {it.refundMinor})
            assertEquals("1",repo.products(shop.id).single().stockLabel)
            assertFails {repo.returnItems(shop.id,sale.id,mapOf(item.id to 1),true,"Too many")}
            assertEquals(3,repo.saleReturns(shop.id).size)
        }
    }

    @Test fun wholePacksUseBasePrecisionAndRepeatedOperationsAreSafe() {
        val file=Files.createTempFile("fractional-shop-",".db")
        var shopId="";var itemId=""
        try {
            PosRepository(file.toString()).use {repo ->
                val shop=repo.createTenant("Fabric");shopId=shop.id
                val item=repo.addProduct(shop.id,"Fabric","metre",100,2.25,quantityPrecision=2);itemId=item.id
                val roll=repo.addPurchaseUnit(shop.id,item.id,"roll",10)
                repo.receiveStock(shop.id,item.id,2,1000,0,"Supplier","rolls",roll.id,10)
                assertEquals("22.25",repo.products(shop.id).single().stockLabel)
                repo.receiveStock(shop.id,item.id,2,1000,0,"Supplier","rolls",roll.id,10)
                assertEquals("22.25",repo.products(shop.id).single().stockLabel)
                val pack=repo.addSellingUnit(shop.id,item.id,"bundle",2,180,null,"bundle")
                val current=repo.products(shop.id).single()
                repo.checkout(shop.id,listOf(CartLine(current,125),CartLine(current,2,pack.id,pack.name,pack.priceMinor,2)),485,operationId="mixed")
                assertEquals("17",repo.products(shop.id).single().stockLabel)
            }
            PosRepository(file.toString()).use {repo ->
                assertEquals("17",repo.products(shopId).single {it.id==itemId}.stockLabel)
                assertEquals(2,repo.sales(shopId).single().lines.size)
            }
        } finally {Files.deleteIfExists(file)}
    }

    @Test fun fractionalImportRequiresExplicitPrecisionAndRejectsExcessDigits() {
        val rows=ProductImportService.parse("name,unit,price_naira,stock,quantity_precision\nRice,kg,300.00,1.375,3\n")
        assertEquals(1375,rows.single().stock)
        PosRepository(":memory:").use {repo ->
            val shop=repo.createTenant("Import")
            repo.importProducts(shop.id,rows)
            assertEquals("1.375",repo.products(shop.id).single().stockLabel)
        }
        assertFails {ProductImportService.parse("name,unit,price_naira,stock,quantity_precision\nRice,kg,300.00,1.375,2\n")}
    }
}
