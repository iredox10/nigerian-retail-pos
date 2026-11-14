package pos.output

import kotlin.test.*
import pos.domain.*

class SupplierInvoiceCsvTest {
    private val receipt=StockReceipt("r","t","p","Rice","bag",1,12345,12345,0,"Supplier","2026-11-14T00:00:00Z")
    private val invoice=SupplierInvoice("i","t","s",SupplierInvoiceDetails("=1","2026-11-14",reference="@ref",notes="+notes"),SupplierInvoiceStatus.ACTIVE,1,"2026-11-14T00:00:00Z","2026-11-14T00:00:00Z",listOf("r"))
    @Test fun exportsUseActualPurchaseCostAndFormulaSafeTextWithoutInventingInvoicePayments() {
        val csv=SupplierInvoiceCsv.current("t",listOf(invoice),listOf(receipt))
        assertTrue(csv.contains("'=1"));assertTrue(csv.contains("'@ref"));assertTrue(csv.contains("'+notes"));assertTrue(csv.contains("123.45"));assertTrue(csv.contains("not payment status"))
        val history=SupplierInvoiceCsv.history("t",listOf(SupplierInvoiceRevision("op",invoice,"Originally recorded"),SupplierInvoiceRevision("void",invoice.copy(status=SupplierInvoiceStatus.VOIDED,version=2,receiptIds=emptyList()),"Wrong document")),listOf(receipt))
        assertTrue(history.contains("VOIDED"));assertTrue(history.contains("Wrong document"));assertTrue(history.contains("123.45"))
    }
    @Test fun missingForeignAndRepeatedRecordsCannotInventExportTotals() {
        assertFails {SupplierInvoiceCsv.current("t",listOf(invoice),emptyList())}
        assertFails {SupplierInvoiceCsv.current("other",listOf(invoice),listOf(receipt))}
        assertFails {SupplierInvoiceCsv.current("t",listOf(invoice,invoice),listOf(receipt))}
        assertFails {SupplierInvoiceCsv.current("t",listOf(invoice),listOf(receipt.copy(tenantId="other")))}
    }
}
