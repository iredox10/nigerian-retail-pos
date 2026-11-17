package pos.screens

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import pos.data.*
import pos.domain.*
import pos.output.CsvService
import pos.output.OwnerCashCsv
import pos.output.receiptDate
import pos.ui.components.*
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID

/** A Money subsection for recorded owner cash; it does not create sales or operating expenses. */
@Composable
fun OwnerCashScreen(repo:PosRepository,tenantId:String,onChanged:()->Unit) {
    var revision by remember(tenantId) {mutableIntStateOf(0)}
    var creating by remember {mutableStateOf<String?>(null)}
    var correcting by remember {mutableStateOf<OwnerCashRecord?>(null)}
    var message by remember {mutableStateOf<String?>(null)}
    var exportError by remember {mutableStateOf<String?>(null)}
    var allDates by remember {mutableStateOf(true)}
    val today=LocalDate.now(ZoneId.of("Africa/Lagos"))
    var from by remember {mutableStateOf(today.minusDays(30).toString())}
    var through by remember {mutableStateOf(today.toString())}
    val range=runCatching {
        if(allDates) null else {
            require(from.matches(Regex("\\d{4}-\\d{2}-\\d{2}")) && through.matches(Regex("\\d{4}-\\d{2}-\\d{2}"))) {"Use YYYY-MM-DD for both dates."}
            ReportDateRange(LocalDate.parse(from),LocalDate.parse(through))
        }
    }
    val loaded=remember(tenantId,revision) {runCatching {repo.ownerCashRecords(tenantId)}}
    val records=loaded.getOrDefault(emptyList())
    val visible=runCatching {val selected=range.getOrThrow();records.filter {selected==null || selected.contains(it.createdAt)}}
    val correctedIds=records.mapNotNull {it.reversesId}.toSet()
    fun saved(text:String) {creating=null;correcting=null;revision++;message=text;exportError=null;onChanged()}
    Column(Modifier.fillMaxSize(),verticalArrangement=Arrangement.spacedBy(12.dp)) {
        SectionHeader("Owner money","Cash added by or taken by the owner · Original history is kept")
        Text("These entries change the drawer cash. They are recorded separately from sales and operating expenses. This page is not an equity account or profit statement.",style=MaterialTheme.typography.bodySmall)
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),horizontalArrangement=Arrangement.spacedBy(12.dp)) {
            PosButton("Owner adds money",{creating="CONTRIBUTION";message=null})
            NeutralButton("Owner takes money",{creating="DRAWING";message=null})
            NeutralButton("Save owner money CSV",{
                try {
                    val file=CsvService.save(OwnerCashCsv.create(tenantId,loaded.getOrThrow(),range.getOrThrow()),"owner-money.csv")
                    message=if(file==null) "Export cancelled." else "Owner money CSV saved to ${file.name}.";exportError=null
                } catch(e:Exception) {exportError=e.message ?: "Export could not be saved."}
            },enabled=loaded.isSuccess && range.isSuccess)
            NeutralButton("Refresh",{revision++})
        }
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),horizontalArrangement=Arrangement.spacedBy(8.dp)) {
            FilterChip(allDates,{allDates=true},label={Text("All dates")})
            FilterChip(!allDates,{allDates=false},label={Text("Choose dates")})
        }
        if(!allDates) Row(horizontalArrangement=Arrangement.spacedBy(12.dp)) {
            PosTextField(from,{from=it},"From (WAT)",modifier=Modifier.weight(1f))
            PosTextField(through,{through=it},"Through (WAT)",modifier=Modifier.weight(1f))
        }
        range.exceptionOrNull()?.let {InlineNotice("Check dates",it.message ?: "Use valid dates.",NoticeTone.Danger)}
        loaded.exceptionOrNull()?.let {InlineNotice("Owner money unavailable",it.message ?: "Refresh to try again.",NoticeTone.Danger)}
        exportError?.let {InlineNotice("File not saved",it,NoticeTone.Danger)}
        message?.let {InlineNotice(it,tone=NoticeTone.Info)}
        LazyColumn(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(12.dp)) {
            if(loaded.isSuccess && visible.getOrNull()?.isEmpty()==true) item {EmptyState("No owner movements in this period","Record cash only after it has actually been added to or taken from the open drawer.")}
            items(visible.getOrDefault(emptyList()).reversed(),key={it.id}) {row ->
                Surface(shape=MaterialTheme.shapes.medium) {Column(Modifier.fillMaxWidth().padding(20.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
                    Text("${OwnerCashCsv.label(row.kind)} · ${row.ownerName}",style=MaterialTheme.typography.titleMedium)
                    LabelValueRow(if(row.amountMinor>0) "Cash added to drawer" else "Cash taken from drawer",money(if(row.amountMinor>0)row.amountMinor else Math.negateExact(row.amountMinor)),emphasized=true)
                    Text(row.reason)
                    Text("${receiptDate(row.createdAt)} · Record ${row.id}",style=MaterialTheme.typography.bodySmall)
                    row.reversesId?.let {Text("Correction of $it. The original entry remains saved.",style=MaterialTheme.typography.bodySmall)}
                    if(row.id in correctedIds) Text("A linked correction has already been recorded.",style=MaterialTheme.typography.bodySmall)
                    if(row.kind!="REVERSAL" && row.id !in correctedIds) NeutralButton("Record opposite cash correction",{correcting=row;message=null})
                }}
            }
        }
    }
    creating?.let {kind ->OwnerMoneyDialog(repo,tenantId,kind,null,{creating=null}) {saved("Owner cash movement saved on this computer.")}}
    correcting?.let {original ->OwnerMoneyDialog(repo,tenantId,"REVERSAL",original,{correcting=null}) {saved("Opposite cash correction saved. Original owner movement remains in history.")}}
}

private data class OwnerDrawerReview(val shiftId:String,val balanceMinor:Long)
@Composable
private fun OwnerMoneyDialog(repo:PosRepository,tenantId:String,kind:String,original:OwnerCashRecord?,dismiss:()->Unit,saved:()->Unit) {
    var amount by remember {mutableStateOf("")}
    var owner by remember {mutableStateOf(original?.ownerName.orEmpty())}
    var reason by remember {mutableStateOf("")}
    var revision by remember {mutableIntStateOf(0)}
    val drawer=remember(tenantId,revision) {runCatching {
        val shift=requireNotNull(repo.activeCashShift(tenantId)) {"Open today's cash drawer before recording owner money."}
        OwnerDrawerReview(shift.id,repo.expectedCash(tenantId,shift.id))
    }}
    var reviewing by remember {mutableStateOf(false)}
    var error by remember {mutableStateOf<String?>(null)}
    var saving by remember {mutableStateOf(false)}
    val value=if(original==null) parseNaira(amount) else Math.negateExact(original.amountMinor).let {if(it<0)Math.negateExact(it) else it}
    val cashIn=if(original!=null) original.amountMinor<0 else kind=="CONTRIBUTION"
    val signed=value?.let {if(cashIn) it else Math.negateExact(it)}
    val operation=remember(amount,owner,reason,revision) {UUID.randomUUID().toString()}
    val canSave=drawer.isSuccess && value!=null && value>0 && owner.trim().length in 1..150 && reason.trim().length in 3..500 && (cashIn || value<=drawer.getOrThrow().balanceMinor) && (signed==null || !cashIn || drawer.getOrThrow().balanceMinor<=Long.MAX_VALUE-signed)
    AlertDialog(onDismissRequest={if(!saving)dismiss()},title={Text(if(kind=="REVERSAL") "Review opposite cash correction" else if(cashIn) "Owner adds money" else "Owner takes money")},text={
        Column(Modifier.heightIn(max=520.dp).verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(12.dp)) {
            Text(if(original==null) "Record the cash that actually moved between the owner and this drawer." else "This creates an equal opposite movement in the current drawer. Confirm that the cash actually moved back; the original entry will remain saved.")
            if(original!=null) {
                Text("Original record ${original.id} · ${receiptDate(original.createdAt)}",style=MaterialTheme.typography.bodySmall)
                Text(original.reason,style=MaterialTheme.typography.bodySmall)
            }
            drawer.getOrNull()?.let {LabelValueRow("Drawer cash at review",money(it.balanceMinor));Text("Drawer shift ${it.shiftId}",style=MaterialTheme.typography.bodySmall)}
            drawer.exceptionOrNull()?.let {InlineNotice("Drawer not ready",it.message ?: "Open or refresh the drawer.",NoticeTone.Danger)}
            if(original==null) {
                PosTextField(owner,{owner=it;reviewing=false;error=null},"Owner's name",enabled=!saving)
                PosTextField(amount,{amount=it;reviewing=false;error=null},"Cash amount (₦)",enabled=!saving)
            } else Text("Owner: ${original.ownerName}")
            PosTextField(reason,{reason=it;reviewing=false;error=null},"Why is this cash moving?",enabled=!saving)
            if(value!=null && value>0) LabelValueRow(if(cashIn) "Cash to add" else "Cash to take",money(value),emphasized=true)
            if(value!=null && !cashIn && drawer.isSuccess && value>drawer.getOrThrow().balanceMinor) InlineNotice("Not enough recorded drawer cash","Review the amount or refresh the drawer. Nothing has been saved.",NoticeTone.Danger)
            if(reviewing && canSave) {
                HorizontalDivider()
                Text("Check the amount, owner and cash direction before saving.",style=MaterialTheme.typography.titleMedium)
                LabelValueRow("Owner",owner.trim())
                LabelValueRow("Cash direction",if(cashIn) "Into this drawer" else "Out of this drawer")
                LabelValueRow("Reason",reason.trim())
            }
            error?.let {InlineNotice("Owner money not saved",it,NoticeTone.Danger)}
            NeutralButton("Refresh drawer review",{revision++;reviewing=false;error=null},enabled=!saving)
        }
    },confirmButton={PosButton(if(saving) "Saving…" else if(reviewing) "Save reviewed cash movement" else "Review cash movement",{
        if(!reviewing) {reviewing=true;error=null}
        else {
            saving=true
            try {
                val current=drawer.getOrThrow()
                if(original==null) repo.recordOwnerCash(tenantId,kind,requireNotNull(value),owner.trim(),reason.trim(),current.shiftId,current.balanceMinor,operation)
                else repo.reverseOwnerCash(tenantId,original.id,reason.trim(),current.shiftId,current.balanceMinor,operation)
                saved()
            } catch(e:Exception) {error=e.message ?: "Cash could not be saved. Your entries are still here.";reviewing=false}
            finally {saving=false}
        }
    },enabled=!saving && canSave)},dismissButton={TextButton(dismiss,enabled=!saving) {Text("Cancel")}})
}
