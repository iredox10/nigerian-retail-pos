package pos.screens

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.unit.dp
import pos.data.PosRepository
import pos.domain.Product
import pos.output.BarcodeLabelService
import pos.ui.components.*

/** Current-price labels are contextual stock tools and never change recorded barcodes. */
@Composable
internal fun BarcodeLabelsPanel(repo:PosRepository,tenantId:String,product:Product) {
    var revision by remember {mutableIntStateOf(0)}
    var chosenKey by remember(product.id) {mutableStateOf<String?>(null)}
    var copiesText by remember {mutableStateOf("1")}
    var menu by remember {mutableStateOf(false)}
    var message by remember {mutableStateOf<String?>(null)}
    var error by remember {mutableStateOf<String?>(null)}
    fun savedLabels() = BarcodeLabelService.options(tenantId,
        repo.products(tenantId,includeArchived=true).single {it.id==product.id},repo.sellingUnits(tenantId,product.id,true))
    val loaded=remember(tenantId,product,revision) {runCatching {savedLabels()}}
    val choices=loaded.getOrDefault(emptyList())
    val chosen=choices.find {it.key==chosenKey} ?: choices.firstOrNull()
    val preview=remember(chosen) {runCatching {chosen?.let(BarcodeLabelService::render)?.toComposeImageBitmap()}}
    val copies=copiesText.takeIf {it.isNotEmpty() && it.all {c -> c in '0'..'9'}}?.toIntOrNull()?.takeIf {it in 1..100}
    Column(verticalArrangement=Arrangement.spacedBy(16.dp)) {
        SectionHeader("Barcode & price labels","Uses current saved item details; no barcode is assigned or changed")
        Text("Save item edits first. Choose the selling unit printed on the label. This version generates Code128 on 80 × 50 mm labels at 300 DPI; physical printer and scanner compatibility still needs testing.",style=MaterialTheme.typography.bodySmall)
        loaded.exceptionOrNull()?.let {InlineNotice("Labels unavailable",it.message ?: "Try again.",NoticeTone.Warning)}
        if(loaded.isSuccess && choices.isEmpty()) InlineNotice("No stored barcode for this item","Save a barcode under Details or on a selling unit before making a label. No code will be invented automatically.",NoticeTone.Info)
        NeutralButton("Refresh saved label details",{revision++;message=null;error=null})
        if(chosen!=null) {
            Box {
                NeutralButton("${chosen.unit} · ${chosen.barcode}",{menu=true})
                DropdownMenu(menu,{menu=false}) {choices.forEach {choice ->DropdownMenuItem(text={Text("${choice.unit} · ${choice.barcode}")},onClick={chosenKey=choice.key;menu=false;error=null;message=null})}}
            }
            preview.exceptionOrNull()?.let {InlineNotice("This label cannot be generated",it.message ?: "Check the saved barcode and item text.",NoticeTone.Warning)}
            preview.getOrNull()?.let {bitmap -> Image(bitmap,"Label preview for ${chosen.itemName}, ${chosen.unit}, barcode ${chosen.barcode}",Modifier.widthIn(max=480.dp).fillMaxWidth().aspectRatio(1.6f))}
            PosTextField(copiesText,{copiesText=it;error=null},"Number of printed labels",hint="1–100",modifier=Modifier.widthIn(max=240.dp))
            Text("Saving PNG creates one label. Copies apply to native printing. Print at actual size, with paper and imageable area large enough for 80 × 50 mm; the barcode is never shrunk to fit.",style=MaterialTheme.typography.bodySmall)
            Row(horizontalArrangement=Arrangement.spacedBy(12.dp)) {
                NeutralButton("Save label PNG",{
                    try {require(savedLabels().find {it.key==chosen.key}==chosen) {"Saved label details changed. Refresh the preview before saving."};val file=BarcodeLabelService.savePng(chosen);message=if(file==null) "Label export cancelled." else "Label PNG saved to ${file.name}.";error=null}
                    catch(e:Exception) {error=e.message ?: "Label not saved. No stock or sale was changed."}
                },enabled=preview.isSuccess && preview.getOrNull()!=null)
                PosButton("Print labels",{
                    try {require(savedLabels().find {it.key==chosen.key}==chosen) {"Saved label details changed. Refresh the preview before printing."};val submitted=BarcodeLabelService.printLabels(chosen,requireNotNull(copies));message=if(submitted) "Labels submitted to the system print service. Check the printer for paper output." else "Printing cancelled.";error=null}
                    catch(e:Exception) {error=e.message ?: "Labels were not submitted. No stock or sale was changed."}
                },enabled=copies!=null && preview.isSuccess && preview.getOrNull()!=null)
            }
        }
        error?.let {InlineNotice("Could not complete label output",it,NoticeTone.Danger)}
        message?.let {InlineNotice(it,tone=NoticeTone.Info)}
    }
}
