package pos.data

import java.nio.file.Files
import pos.domain.CartLine
import pos.output.ReceiptService
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFails

class SaleNotesIntegrityTest {
    @Test fun heldNoteSurvivesReloadAndIsCapturedOnTheOriginalReceipt() {
        val folder = Files.createTempDirectory("pos-sale-notes")
        try {
            PosRepository(":memory:").use { repo ->
                val shop = repo.createTenant("Shop")
                val item = repo.addProduct(shop.id, "Soap", "piece", 500, 5.0)
                val draft = SaleDraft("${item.id}:1", "sale-notes", note = "Collect at the counter")
                val store = DraftStore(shop.id, folder)
                store.save(draft)
                val held = store.hold("Customer order", draft)
                val resumed = DraftStore(shop.id, folder).resume(held.id)
                assertEquals(draft.note, resumed.note)

                val lines = listOf(CartLine(item, 1))
                val sale = repo.checkout(shop.id, lines, 500, operationId = resumed.operationId, note = resumed.note)
                assertContains(ReceiptService.format(shop, sale), "Sale note: Collect at the counter")
                assertFails { repo.checkout(shop.id, lines, 500, operationId = sale.id, note = "Altered") }
                val snapshot = repo.exportTenantSnapshot(shop.id)
                val restored = repo.restoreTenantSnapshot(snapshot, "Restored")
                assertEquals(draft.note, repo.sales(restored.id).single().note)
                val missing = snapshot.tables.getValue("sales").map { it - "sale_note" }
                assertFails { repo.restoreTenantSnapshot(snapshot.copy(tables = snapshot.tables + ("sales" to missing)), "Missing") }
                assertEquals(4.0, repo.products(shop.id).single().stock)
            }
        } finally {
            folder.toFile().deleteRecursively()
        }
    }
}
