package pos.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import pos.data.*
import pos.domain.*
import pos.output.BulkCatalogueCsvService
import pos.output.CsvService
import pos.output.receiptDate
import pos.ui.components.*
import java.util.UUID
import javax.swing.JFileChooser
import javax.swing.filechooser.FileNameExtensionFilter

/** Contextual Stock workflow: export, edit, review, then one atomic save. */
@Composable
fun BulkCatalogueUpdateScreen(repo:PosRepository,tenantId:String,onChanged:()->Unit) {
    var parsed by remember(tenantId) {mutableStateOf<BulkCatalogueCsvResult?>(null)}
    var preview by remember(tenantId) {mutableStateOf<BulkCataloguePreview?>(null)}
    var filename by remember(tenantId) {mutableStateOf("")}
    var reason by remember(tenantId) {mutableStateOf("")}
    var operationId by remember(tenantId) {mutableStateOf(UUID.randomUUID().toString())}
    var savedId by remember(tenantId) {mutableStateOf<String?>(null)}
    var error by remember(tenantId) {mutableStateOf<String?>(null)}
    var message by remember(tenantId) {mutableStateOf<String?>(null)}
    var confirming by remember {mutableStateOf(false)}
    var history by remember {mutableStateOf(false)}
    var revision by remember {mutableIntStateOf(0)}
    var selectedBatch by remember {mutableStateOf<String?>(null)}
    val histories=remember(tenantId,revision,history) {runCatching {if(history) repo.bulkCatalogueBatches(tenantId) else emptyList()}}
    val catalogue=remember(tenantId,revision) {runCatching {repo.products(tenantId)}}
    val issues=(parsed?.issues.orEmpty()+preview?.issues.orEmpty()).distinct().sortedBy {it.record}
    val reviewedRecords=(parsed?.rows.orEmpty().map {it.record}+parsed?.issues.orEmpty().map {it.record}).distinct().size
    val changed=preview?.changedCount ?: 0
    val canApply=savedId==null && parsed!=null && issues.isEmpty() && preview?.canApply==true && reason.trim().length in 3..500
    Column(Modifier.fillMaxSize(),verticalArrangement=Arrangement.spacedBy(16.dp)) {
        SectionHeader("Update existing goods from CSV", "Review prices and catalogue details before saving the whole batch.")
        Row(horizontalArrangement=Arrangement.spacedBy(12.dp),verticalAlignment=Alignment.CenterVertically) {
            NeutralButton("Export current CSV",{
                try {
                    val rows=repo.bulkCatalogueTemplateRows(tenantId)
                    require(rows.isNotEmpty()) {"Add active goods under Stock before exporting an update file."}
                    val path=CsvService.save(BulkCatalogueCsvService.export(rows),"retail-pos-catalogue-update.csv")
                    message=if(path!=null) "Current catalogue CSV saved to ${path.name}. Edit its values, then choose the file below." else "CSV export cancelled. Catalogue values are unchanged."
                    error=null
                } catch(e:Exception) {error=e.message ?: "No file was saved."}
            },enabled=catalogue.getOrNull()?.isNotEmpty()==true)
            PosButton("Choose update CSV",{
                try {
                    val chooser=JFileChooser().apply {dialogTitle="Choose reviewed catalogue updates";fileFilter=FileNameExtensionFilter("CSV files","csv")}
                    if(chooser.showOpenDialog(null)==JFileChooser.APPROVE_OPTION) {
                        parsed=null;preview=null;savedId=null;error=null;message=null;filename=chooser.selectedFile.name
                        val result=BulkCatalogueCsvService.read(chooser.selectedFile.toPath())
                        val review=repo.previewBulkCatalogueUpdates(tenantId,result.rows)
                        parsed=result;preview=review;filename=chooser.selectedFile.name;operationId=UUID.randomUUID().toString();savedId=null
                        error=null;message=null;history=false
                    }
                } catch(e:Exception) {error=e.message ?: "The file could not be reviewed. No catalogue values changed."}
            })
            NeutralButton(if(history) "Back to CSV review" else "Past CSV updates",{history=!history;selectedBatch=null;error=null})
        }
        catalogue.exceptionOrNull()?.let {InlineNotice("Could not load goods",it.message ?: "Return to Stock and try again.",NoticeTone.Danger)}
        if(catalogue.getOrNull()?.isEmpty()==true) InlineNotice("No active goods", "Add or restore goods under Stock before preparing an update file.",NoticeTone.Info)
        error?.let {InlineNotice("Batch not applied",it,NoticeTone.Danger)}
        message?.let {InlineNotice(it,tone=NoticeTone.Success)}
        if(history) {
            histories.exceptionOrNull()?.let {InlineNotice("Could not load update history",it.message ?: "Try again.",NoticeTone.Danger)}
            val selected=histories.getOrNull()?.singleOrNull {it.id==selectedBatch}
            LazyColumn(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(12.dp)) {
                if(selected==null) {
                    val batches=histories.getOrNull().orEmpty()
                    if(batches.isEmpty()) item {EmptyState("No catalogue CSV updates yet", "Completed updates will appear here with their original before-and-after values.")}
                    items(batches,key={it.id}) {batch ->Surface(shape=MaterialTheme.shapes.medium) {Column(Modifier.fillMaxWidth().padding(20.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
                        Text("${batch.rows.count {it.before!=it.after}} changed items · ${receiptDate(batch.createdAt)}",style=MaterialTheme.typography.titleMedium)
                        Text("Reason: ${batch.reason}")
                        Text("Batch ${batch.id}",style=MaterialTheme.typography.bodySmall)
                        NeutralButton("View saved changes",{selectedBatch=batch.id})
                    }}}
                } else {
                    item {Text("Reason: ${selected.reason}");Text("Saved ${receiptDate(selected.createdAt)}",style=MaterialTheme.typography.bodySmall);TextButton({selectedBatch=null}) {Text("Back to update history")}}
                    items(selected.rows,key={it.productId}) {row ->CatalogueChangeCard(row.record,row.productId,row.before,row.after)}
                }
            }
        } else {
            Text("Export from this shop first. Keep product_id, review_version, unit and quantity_precision unchanged. Edit names, prices, barcodes, SKU, category, brand, aliases, favourite or reorder levels. Blank optional cells clear their values. No goods are added and no stock quantities change.",style=MaterialTheme.typography.bodySmall)
            Text("Aliases use one name per line inside their quoted CSV cell. Spreadsheet-safety apostrophes are decoded on import; double a leading apostrophe when it must remain part of the text.",style=MaterialTheme.typography.bodySmall)
            if(parsed!=null) {
                SectionHeader("$changed changed items · $reviewedRecords reviewed records",filename)
                if(issues.isNotEmpty()) InlineNotice("${issues.size} record errors · Nothing will be applied", "Correct every error in the CSV and choose it again. Current catalogue checks also run before any write.",NoticeTone.Danger)
                else if(changed==0) InlineNotice("No changes in this file", "Edit the exported CSV, then choose the file again to review its changes.",NoticeTone.Info)
                LazyColumn(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(12.dp)) {
                    items(issues.distinct(),key={"${it.record}:${it.message}"}) {issue ->InlineNotice("Record ${issue.record}",issue.message,NoticeTone.Danger)}
                    items(preview?.rows.orEmpty(),key={"${it.requested.record}:${it.requested.productId}"}) {row ->
                        CatalogueChangeCard(row.requested.record,row.requested.productId,row.before,row.requested.values)
                    }
                }
                PosTextField(reason,{reason=it},"Why are you updating these goods?",hint="Required batch reason, 3–500 characters",enabled=savedId==null)
                Row(horizontalArrangement=Arrangement.spacedBy(12.dp)) {
                    PosButton(savedId?.let {"Batch saved"} ?: "Apply $changed changed items",{confirming=true},enabled=canApply)
                    NeutralButton("Check current catalogue",{
                        try {preview=repo.previewBulkCatalogueUpdates(tenantId,requireNotNull(parsed).rows);error=null}
                        catch(e:Exception) {error=e.message ?: "Current values could not be checked."}
                    },enabled=savedId==null)
                }
            } else EmptyState("Choose an exported CSV to review", "The file is checked first. No catalogue change is saved until you review and apply the complete batch.")
        }
    }
    if(confirming && canApply) AlertDialog(onDismissRequest={confirming=false},title={Text("Apply $changed catalogue changes?")},text={Column(verticalArrangement=Arrangement.spacedBy(12.dp)) {
        Text("All reviewed records are checked again. If any catalogue value changed or an identifier conflicts, nothing from this batch is saved.")
        Text("Reason: ${reason.trim()}")
        Text("Existing sale receipts keep their original prices and item details. This operation changes no stock, customer debt or cash.",style=MaterialTheme.typography.bodySmall)
    }},confirmButton={PosButton("Apply reviewed batch",{
        try {
            val batch=repo.applyBulkCatalogueUpdates(tenantId,requireNotNull(parsed).rows,reason,operationId)
            savedId=batch.id;confirming=false;error=null;message="${batch.rows.count {it.before!=it.after}} items updated on this computer. Original details remain in history. Batch ${batch.id}.";revision++;onChanged()
        } catch(e:Exception) {
            confirming=false;error=e.message ?: "No part of this batch was applied."
            runCatching {repo.previewBulkCatalogueUpdates(tenantId,requireNotNull(parsed).rows)}.getOrNull()?.let {preview=it}
        }
    })},dismissButton={TextButton({confirming=false}) {Text("Back to review")}})
}

@Composable
private fun CatalogueChangeCard(record:Int,productId:String,before:BulkCatalogueValues?,after:BulkCatalogueValues) {
    Surface(shape=MaterialTheme.shapes.small) {Column(Modifier.fillMaxWidth().padding(16.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
        Text("Record $record · ${after.name}",style=MaterialTheme.typography.titleMedium)
        Text("Product $productId · ${after.unit} · ${after.precision} quantity decimal places",style=MaterialTheme.typography.bodySmall)
        if(before==null) Text("No matching product in this shop",color=MaterialTheme.colorScheme.error)
        else if(before==after) Text("No changes",style=MaterialTheme.typography.bodySmall)
        else {
            fun text(value:String?)=value ?: "Blank"
            if(before.name!=after.name) LabelValueRow("Item name","${before.name} → ${after.name}")
            if(before.priceMinor!=after.priceMinor) LabelValueRow("Selling price","${money(before.priceMinor)} → ${money(after.priceMinor)}",emphasized=true)
            if(before.barcode!=after.barcode) LabelValueRow("Barcode","${text(before.barcode)} → ${text(after.barcode)}")
            if(before.metadata.sku!=after.metadata.sku) LabelValueRow("SKU","${text(before.metadata.sku)} → ${text(after.metadata.sku)}")
            if(before.metadata.category!=after.metadata.category) LabelValueRow("Category","${text(before.metadata.category)} → ${text(after.metadata.category)}")
            if(before.metadata.brand!=after.metadata.brand) LabelValueRow("Brand","${text(before.metadata.brand)} → ${text(after.metadata.brand)}")
            if(before.metadata.aliases!=after.metadata.aliases) LabelValueRow("Other search names","${before.metadata.aliases.joinToString("; ").ifEmpty {"Blank"}} → ${after.metadata.aliases.joinToString("; ").ifEmpty {"Blank"}}")
            if(before.metadata.favourite!=after.metadata.favourite) LabelValueRow("Favourite","${if(before.metadata.favourite) "Yes" else "No"} → ${if(after.metadata.favourite) "Yes" else "No"}")
            if(before.reorderLevel!=after.reorderLevel) LabelValueRow("Low-stock reminder","${before.reorderLevel?.toString() ?: "Off"} → ${after.reorderLevel?.toString() ?: "Off"}")
        }
    }}
}
