package pos.data
import pos.domain.*
import pos.output.*
import kotlin.test.*
import java.nio.file.Files
class ExternalPaymentIntegrityTest {
    @Test fun splitPaymentPreservesCashDebtAndUnverifiedRecordAcrossRetryAndRestore() {
        PosRepository(":memory:").use {r ->
            val t=r.createTenant("Shop");val p=r.addProduct(t.id,"Rice","bag",10000,10.0);val c=r.addCustomer(t.id,"Customer");val shift=r.openCashShift(t.id,0)
            val external=listOf(ExternalPayment("transfer",5000,"TX123","Shop bank"))
            val sale=r.checkout(t.id,listOf(CartLine(p,1)),3000,c.id,"sale",externalPayments=external)
            assertEquals(2000L,sale.creditMinor);assertEquals(5000L,sale.externalRecordedMinor)
            assertEquals(3000L,r.expectedCash(t.id,shift.id));assertEquals(2000L,r.customers(t.id).single().balanceMinor)
            assertEquals(9.0,r.products(t.id).single().stock)
            r.checkout(t.id,listOf(CartLine(p,1)),3000,c.id,"sale",externalPayments=external)
            assertEquals(1,r.sales(t.id).size);assertEquals(external,r.sales(t.id).single().externalPayments)
            assertFails {r.checkout(t.id,listOf(CartLine(p,1)),3000,c.id,"sale",externalPayments=listOf(external.single().copy(reference="different")))}
            val receipt=ReceiptService.format(t,sale)
            assertContains(receipt,"Transfer not confirmed");assertFalse(receipt.contains("Paid in cash"));assertContains(receipt,"TX123")
            val csv=CsvService.sales(t.id,listOf(sale));assertContains(csv,"unverified");assertContains(csv,"50.00")
            val snapshot=r.exportTenantSnapshot(t.id)
            val restored=r.restoreTenantSnapshot(snapshot,"Recovered")
            assertEquals(external,r.sales(restored.id).single().externalPayments)
            assertEquals(2000L,r.customers(restored.id).single().balanceMinor)
            assertFails {r.restoreTenantSnapshot(snapshot.copy(tables=snapshot.tables-externalPaymentSnapshotTables.toSet()),"Missing payments")}
            val bad=snapshot.copy(tables=snapshot.tables+("sale_external_payments" to snapshot.tables.getValue("sale_external_payments").map {it+("amount_minor" to "4000")}))
            assertFails {r.restoreTenantSnapshot(bad,"Changed payments")}
            assertFails {r.returnItems(t.id,sale.id,mapOf(p.id to 1),true,"Wrong refund","return")}
        }
    }
    @Test fun noDrawerElectronicOnlyDoesNotInventCashOrCustomerDebt() {
        PosRepository(":memory:").use {r ->
            val t=r.createTenant("Shop");val p=r.addProduct(t.id,"Soap","piece",10000,10.0)
            val ext=listOf(ExternalPayment("card",10000,"terminal-9","Terminal A"))
            val sale=r.checkout(t.id,listOf(CartLine(p,1)),0,operationId="card",externalPayments=ext)
            assertEquals(0L,sale.creditMinor);assertTrue(r.cashEntries(t.id).isEmpty());assertNull(r.activeCashShift(t.id))
            assertFails {r.checkout(t.id,listOf(CartLine(p,1)),1,externalPayments=ext)}
            assertEquals(1,r.sales(t.id).size);assertEquals(9.0,r.products(t.id).single().stock)
        }
    }
    @Test fun permissionOrLateActorFailureLeavesAllLedgersUnchanged() {
        PosRepository(":memory:").use {r ->
            val t=r.createTenant("Shop");val p=r.addProduct(t.id,"Soap","piece",10000,10.0);val c=r.addCustomer(t.id,"Customer")
            val ext=listOf(ExternalPayment("transfer",5000,"ref","Bank"))
            r.setPermissionCheck {_,permission ->require(permission!=StaffPermission.SELL_CREDIT)}
            assertFails {r.checkout(t.id,listOf(CartLine(p,1)),0,c.id,externalPayments=ext)}
            r.setPermissionCheck(null);r.openCashShift(t.id,0);r.setActorProvider {error("Actor revoked")}
            val revoked=assertFails {r.checkout(t.id,listOf(CartLine(p,1)),5000,externalPayments=ext)}
            assertEquals("Actor revoked",revoked.message)
            r.setActorProvider(null)
            assertTrue(r.sales(t.id).isEmpty());assertTrue(r.cashEntries(t.id).isEmpty());assertEquals(10.0,r.products(t.id).single().stock)
            assertTrue(r.exportTenantSnapshot(t.id).tables.getValue("sale_external_payments").isEmpty())
        }
    }
    @Test fun externalRecordsSurviveReopenAndCannotBeChangedOrMovedAcrossShops() {
        val file=Files.createTempFile("external-ledger", ".sqlite")
        try {
            val shopId=PosRepository(file.toString()).use {r ->
                val shop=r.createTenant("Shop")
                val other=r.createTenant("Other shop")
                val product=r.addProduct(shop.id,"Soap","piece",10000,2.0)
                val external=listOf(ExternalPayment("card",10000,"CARD-1","Terminal A"))
                assertFails {r.checkout(other.id,listOf(CartLine(product,1)),0,externalPayments=external)}
                assertEquals(2.0,r.products(shop.id).single().stock)
                r.checkout(shop.id,listOf(CartLine(product,1)),0,operationId="recorded",externalPayments=external)
                assertFails {r.featureExecute("UPDATE sale_external_payments SET reference=? WHERE tenant_id=?","changed",shop.id)}
                assertFails {r.featureExecute("DELETE FROM sale_external_payments WHERE tenant_id=?",shop.id)}
                val snapshot=r.exportTenantSnapshot(shop.id)
                val foreign=snapshot.copy(tables=snapshot.tables+("sale_external_payments" to snapshot.tables.getValue("sale_external_payments").map {it+("tenant_id" to other.id)}))
                assertFails {r.restoreTenantSnapshot(foreign,"Mixed records")}
                val gap=snapshot.copy(tables=snapshot.tables+("sale_external_payments" to snapshot.tables.getValue("sale_external_payments").map {it+("position" to "1")}))
                assertFails {r.restoreTenantSnapshot(gap,"Incomplete portions")}
                shop.id
            }
            PosRepository(file.toString()).use {r ->
                val sale=r.sales(shopId).single()
                assertEquals("CARD-1",sale.externalPayments.single().reference)
                assertEquals("Card payment not confirmed",sale.externalPayments.single().label)
                assertTrue(r.cashEntries(shopId).isEmpty())
            }
        } finally {
            Files.deleteIfExists(file)
            Files.deleteIfExists(file.resolveSibling(file.fileName.toString()+"-wal"))
            Files.deleteIfExists(file.resolveSibling(file.fileName.toString()+"-shm"))
        }
    }
    @Test fun draftsRetainInvalidEntryTextThroughHoldAndRestart() {
        val dir=Files.createTempDirectory("external-draft")
        try {
            val store=DraftStore("shop",dir)
            val draft=SaleDraft("p:1","op",cashText="0",method="split",externalMethod="card",externalAmountText="1.",externalReference="=ABC",externalAccount="Counter A")
            store.save(draft);val held=store.hold("Customer order",draft)
            assertEquals(draft,DraftStore("shop",dir).resume(held.id));assertEquals(draft,DraftStore("shop",dir).load())
        } finally {Files.walk(dir).use {it.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists)}}
    }
}
