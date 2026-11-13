package pos.screens

import pos.domain.quantityLabel

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import pos.data.PosRepository
import pos.domain.ProductImportRow
import pos.domain.money
import pos.output.CsvService
import pos.output.ProductImportService
import pos.ui.components.*
import javax.swing.JFileChooser
import javax.swing.filechooser.FileNameExtensionFilter

@Composable
fun ProductImportScreen(repo:PosRepository,tenantId:String,onChanged:()->Unit) {
    var rows by remember(tenantId) { mutableStateOf<List<ProductImportRow>>(emptyList()) }
    var filename by remember(tenantId) { mutableStateOf("") }
    var error by remember(tenantId) { mutableStateOf<String?>(null) }
    var message by remember(tenantId) { mutableStateOf<String?>(null) }
    var imported by remember(tenantId) { mutableStateOf(false) }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(16.dp)) {
        SectionHeader("Import new items", "Preview first · Add-only import for this shop")
        Text("Use the template columns: name, unit, price_naira, stock. Add an optional quantity_precision column (0–6) for measured goods; otherwise stock must use whole units. Prices must have at most two decimal places. Existing items are never overwritten; use Add stock for goods already in the shop.",Modifier.widthIn(max=800.dp))
        Row(horizontalArrangement=Arrangement.spacedBy(12.dp)) {
            NeutralButton("Save CSV template",{
                try { CsvService.save(ProductImportService.TEMPLATE,"retail-pos-item-import-template.csv") }
                catch(e:Exception) {error=e.message ?: "Template could not be saved."}
            })
            PosButton("Choose CSV file",{
                error=null;message=null;rows=emptyList();imported=false
                try {
                    val chooser=JFileChooser().apply { dialogTitle="Choose items to import";fileFilter=FileNameExtensionFilter("CSV files","csv") }
                    if(chooser.showOpenDialog(null)==JFileChooser.APPROVE_OPTION) {
                        rows=ProductImportService.read(chooser.selectedFile.toPath());filename=chooser.selectedFile.name
                    }
                }catch(e:Exception) {error=e.message ?: "The CSV could not be read. No records changed."}
            })
        }
        error?.let { InlineNotice("No items imported",it,NoticeTone.Danger) }
        message?.let { InlineNotice("Import completed",it,NoticeTone.Success) }
        if(rows.isNotEmpty()) {
            SectionHeader("${rows.size} items ready for review",filename)
            Text("Showing the first ${minOf(10,rows.size)} items. Every record in the file has been parsed; duplicate checks run again before any write.")
            rows.take(10).forEach { row ->
                Surface(shape=MaterialTheme.shapes.small) { Row(Modifier.fillMaxWidth().padding(16.dp),horizontalArrangement=Arrangement.spacedBy(20.dp)) {
                    Text(row.name,Modifier.weight(1f));Text(row.unit,Modifier.width(100.dp));Text(money(row.priceMinor),Modifier.width(160.dp));Text("${quantityLabel(row.stock.toLong(),row.quantityPrecision)} opening units",Modifier.width(160.dp))
                } }
            }
            PosButton(if(imported) "Items imported" else "Import ${rows.size} items into this shop",{
                try {
                    repo.importProducts(tenantId,rows)
                    imported=true;error=null;message="${rows.size} new items saved on this computer.";onChanged()
                } catch(e:Exception) {error=e.message ?: "Import failed. No partial batch was saved."}
            },enabled=!imported)
        }
    }
}
