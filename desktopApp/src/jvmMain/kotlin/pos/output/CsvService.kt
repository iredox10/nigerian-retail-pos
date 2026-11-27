package pos.output

import pos.domain.*
import java.io.File
import java.math.BigDecimal
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import javax.swing.JFileChooser
import javax.swing.JOptionPane
import javax.swing.filechooser.FileNameExtensionFilter

/** Desktop file-picker adapter around portable CSV data. */
object CsvService {
    fun encode(headers:List<String>,rows:List<List<CsvCell>>)=CsvData.encode(headers,rows)
    fun sales(tenantId:String,values:List<Sale>)=CsvData.sales(tenantId,values)
    fun products(tenantId:String,values:List<Product>)=CsvData.products(tenantId,values)
    fun customers(tenantId:String,values:List<Customer>)=CsvData.customers(tenantId,values)
    fun expenses(tenantId:String,values:List<Expense>)=CsvData.expenses(tenantId,values)
    fun cashEntries(tenantId:String,values:List<CashEntry>)=CsvData.cashEntries(tenantId,values)
    fun stockReceipts(tenantId:String,values:List<StockReceipt>)=CsvData.stockReceipts(tenantId,values)
    fun repaymentAllocations(tenantId:String,values:List<RepaymentAllocation>,reversals:List<AllocationReversal> = emptyList())=CsvData.repaymentAllocations(tenantId,values,reversals)
    fun allocationReversals(tenantId:String,values:List<AllocationReversal>)=CsvData.allocationReversals(tenantId,values)
    fun auditEvents(tenantId:String,values:List<AuditEvent>)=CsvData.auditEvents(tenantId,values)
    fun inventoryMovements(tenantId:String,products:List<Product>,values:List<InventoryMovement>)=CsvData.inventoryMovements(tenantId,products,values)
    fun save(text: String, suggestedName: String): File? {
        val chooser = JFileChooser().apply {
            dialogTitle = "Save this shop's CSV export"
            fileFilter = FileNameExtensionFilter("CSV spreadsheet (*.csv)", "csv")
            selectedFile = File(suggestedName.replace(Regex("[^A-Za-z0-9._-]"), "_"))
        }
        if (chooser.showSaveDialog(null) != JFileChooser.APPROVE_OPTION) return null
        val chosen = chooser.selectedFile
        val file = if (chosen.name.endsWith(".csv", true)) chosen else File(chosen.parentFile, chosen.name + ".csv")
        if (file.exists() && JOptionPane.showConfirmDialog(null, "Replace ${file.name}?", "Replace export", JOptionPane.YES_NO_OPTION) != JOptionPane.YES_OPTION) return null
        val target = file.toPath().toAbsolutePath()
        val temporary = Files.createTempFile(target.parent, ".csv-", ".tmp")
        try {
            java.io.FileOutputStream(temporary.toFile()).use { output -> output.write(("\uFEFF" + text).toByteArray(Charsets.UTF_8)); output.fd.sync() }
            try { Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING) }
            catch (_: java.nio.file.AtomicMoveNotSupportedException) { Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING) }
        } finally { Files.deleteIfExists(temporary) }
        return target.toFile()
    }

}
