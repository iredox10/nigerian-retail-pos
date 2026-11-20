package pos.data

import java.nio.file.Files
import java.time.LocalDate
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import pos.domain.CartLine
import pos.output.BackupService

class LocalTradingRecoveryTest {
    @Test fun encryptedBackupRestoresLocalTradingWorkflowsInAnotherDatabase() {
        val directory = Files.createTempDirectory("local-trading-recovery-")
        val file = directory.resolve("shop.backup")
        val password = "local-trading-recovery-test"
        try {
            PosRepository(":memory:").use { source ->
                val shop = source.createTenant("Source shop")
                val soap = source.addProduct(shop.id, "Laundry soap 200 g", "piece", 500, 4.0)
                val variant = ProductVariant("Laundry soap", "200 g")
                source.saveProductVariant(shop.id, soap.id, variant, 0, "Group the soap sizes")
                val shift = source.openCashShift(shop.id, 0)
                val cashSale = source.checkout(shop.id, listOf(CartLine(soap, 1)), 500,
                    cashGivenMinor = 1_000, note = "Collected at the counter")
                val customer = source.addCustomer(shop.id, "Amina")
                source.checkout(shop.id, listOf(CartLine(soap, 1)), 0, customer.id)
                val due = LocalDate.now(ZoneId.of("Africa/Lagos")).plusDays(1)
                val reminder = source.addRepaymentReminder(shop.id, customer.id, due, "Ask about the balance")
                val completion = source.finishRepaymentReminder(shop.id, reminder.id, ReminderState.COMPLETED,
                    "Customer agreed a payment date")
                val rice = source.addProduct(shop.id, "Rice", "bag", 1_500, 0.0)
                val receipt = source.receiveStock(shop.id, rice.id, 3, 1_000, 0, "Rice supplier")
                val returned = source.savePurchaseReturn(shop.id, source.reviewPurchaseReturn(shop.id, receipt.id, 1),
                    "Supplier collected one bag")
                BackupService.create(source, shop.id, password, file)

                PosRepository(":memory:").use { destination ->
                    val restored = destination.restoreTenantSnapshot(BackupService.read(file, password), "Recovered shop")
                    val sale = destination.sales(restored.id).single { it.id == cashSale.id }
                    assertEquals(1_000L, sale.cashGivenMinor)
                    assertEquals(500L, sale.changeMinor)
                    assertEquals(cashSale.note, sale.note)
                    assertEquals(500L, destination.expectedCash(restored.id, shift.id))
                    assertEquals(variant, destination.productVariants(restored.id).getValue(soap.id))
                    assertEquals(listOf(soap.id), destination.frequentItemIds(restored.id))
                    assertEquals(listOf(reminder, completion), destination.repaymentReminderHistory(restored.id))
                    assertEquals(500L, destination.customers(restored.id).single().balanceMinor)
                    assertEquals(returned.copy(tenantId = restored.id), destination.purchaseReturns(restored.id).single())
                    assertEquals(2_000L, destination.supplierOutstanding(restored.id, receipt.id))
                    assertEquals(2L, destination.products(restored.id).single { it.id == rice.id }.exactStockScaled)
                }
            }
        } finally {
            Files.deleteIfExists(file)
            Files.delete(directory)
        }
    }
}
