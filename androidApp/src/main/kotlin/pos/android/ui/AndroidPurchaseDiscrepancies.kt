package pos.android.ui

import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import pos.data.*
import pos.domain.*
import pos.ui.components.*
import java.util.UUID

private data class PurchaseIssues(val history:List<PurchaseDiscrepancyHistory>,val remaining:Map<String,Int>,val products:List<Product>)
@Composable internal fun AndroidPurchaseDiscrepancies(repo:PosRepository,tenantId:String,order:PurchaseOrder,dismiss:()->Unit,onChanged:()->Unit) {
    var revision by remember {mutableIntStateOf(0)}
    var recording by remember {mutableStateOf(false)}
    var resolving by remember {mutableStateOf<PurchaseDiscrepancy?>(null)}
    val loaded=operationsLoad(repo,tenantId,revision) {PurchaseIssues(repo.purchaseDiscrepancies(tenantId,order.id),repo.purchaseOrderRemaining(tenantId,order.id),repo.products(tenantId))}
    resolving?.let {report ->
        var receiptRevision by remember {mutableIntStateOf(0)}
        val receipts=operationsLoad(repo,tenantId,report.id to receiptRevision) {repo.purchaseDiscrepancyReceipts(tenantId,report.id)}
        var receiptId by rememberSaveable {mutableStateOf<String?>(null)}
        var reason by rememberSaveable {mutableStateOf("")}
        val operation=rememberSaveable {UUID.randomUUID().toString()}
        OperationsDialog("Record discrepancy outcome",{resolving=null},"Save outcome",action={
            val why=reason.trim()
            withContext(Dispatchers.IO) {repo.resolvePurchaseDiscrepancy(tenantId,report.id,why,receiptId,operation)}
            resolving=null;revision++;onChanged()
        }) {
            Text("${report.productName} · ${report.quantityLabel} ${report.buyingUnit}")
            Text("Record the outcome without changing the original report. This does not receive goods, pay cash or change supplier debt.")
            OperationsLoadState(receipts,"matching later receipts",{receiptRevision++})
            FilterChip(receiptId==null,{receiptId=null},label={Text("Outcome note only")})
            receipts?.getOrNull().orEmpty().forEach {receipt ->FilterChip(receiptId==receipt.id,{receiptId=receipt.id},label={Text("Link receipt ${receipt.createdAt.take(19)} · ${receipt.productName}")})}
            PosTextField(reason,{reason=it},"What was agreed or resolved?")
        }
    }
    if(recording)AndroidRecordPurchaseDiscrepancy(repo,tenantId,order,loaded?.getOrNull(),{recording=false}) {recording=false;revision++;onChanged()}
    OperationsDialog("Purchase discrepancies",dismiss,"Close",action={dismiss()}) {
        Text(order.supplierName,style=MaterialTheme.typography.titleMedium)
        Text("Shortages, damaged goods and extra goods are observations. They do not change stock, cash or debt; use actual receiving/return actions separately.")
        OperationsLoadState(loaded,"purchase discrepancies",{revision++})
        if(order.status==PurchaseOrderStatus.APPROVED)OutlinedButton(onClick={recording=true}) {Text("Record shortage, damage or extra goods")}
        loaded?.getOrNull()?.history.orEmpty().forEach {entry ->
            HorizontalDivider()
            val report=entry.report
            Text("${report.kind.name.lowercase()} · ${report.productName}",style=MaterialTheme.typography.titleSmall)
            Text("${report.quantityLabel} ${report.buyingUnit} · ${report.createdAt.take(19)} UTC")
            Text(report.reason)
            val resolution=entry.resolution
            if(resolution==null)TextButton(onClick={resolving=report}) {Text("Record outcome")}
            else {Text("Outcome: ${resolution.reason}");resolution.receiptId?.let {Text("Linked later receipt: $it")}}
        }
        if(loaded?.getOrNull()?.history?.isEmpty()==true)Text("No discrepancies reported for this order.")
    }
}
@Composable private fun AndroidRecordPurchaseDiscrepancy(repo:PosRepository,tenantId:String,order:PurchaseOrder,choices:PurchaseIssues?,dismiss:()->Unit,saved:()->Unit) {
    var kind by remember {mutableStateOf(PurchaseDiscrepancyKind.SHORTAGE)}
    var selectedId by rememberSaveable {mutableStateOf<String?>(null)}
    var search by rememberSaveable {mutableStateOf("")}
    var quantity by rememberSaveable {mutableStateOf("")}
    var reason by rememberSaveable {mutableStateOf("")}
    var reviewed by remember {mutableStateOf(false)}
    val operation=rememberSaveable {UUID.randomUUID().toString()}
    val line=if(kind==PurchaseDiscrepancyKind.EXTRA)null else order.lines.singleOrNull {it.id==selectedId}
    val extra=if(kind==PurchaseDiscrepancyKind.EXTRA)choices?.products?.singleOrNull {it.id==selectedId} else null
    val precision=line?.quantityPrecision ?: extra?.quantityPrecision ?: 0
    val unit=line?.buyingUnit ?: extra?.unit.orEmpty()
    OperationsDialog("Record purchase discrepancy",dismiss,if(reviewed)"Save discrepancy report" else "Review discrepancy",action={
        val current=requireNotNull(choices) {"Wait for the order and stock records to load."}
        require(line!=null || extra!=null) {"Choose the affected order line or extra item."}
        val scaled=requireNotNull(parseQuantity(quantity,precision)?.scaledUnits?.takeIf {it in 1..Int.MAX_VALUE.toLong()}) {"Enter a positive quantity in $unit."}
        require(validPurchaseOrderReason(reason.trim())) {"Enter a reason from 3 to 250 characters."}
        if(!reviewed)reviewed=true else {
            val why=reason.trim()
            withContext(Dispatchers.IO) {repo.recordPurchaseDiscrepancy(tenantId,order.id,order.version,kind,line?.id,extra?.id,scaled.toInt(),if(line==null)null else current.remaining.getValue(line.id),why,operation)};saved()
        }
    }) {
        PurchaseDiscrepancyKind.entries.forEach {option ->FilterChip(kind==option,{kind=option;selectedId=null;quantity="";reviewed=false},label={Text(option.name.lowercase().replaceFirstChar {it.uppercase()})})}
        if(kind==PurchaseDiscrepancyKind.EXTRA) {
            PosTextField(search,{search=it},"Find the extra item")
            choices?.products.orEmpty().filter {it.matchesCatalogueSearch(search)}.take(30).forEach {p ->FilterChip(selectedId==p.id,{selectedId=p.id;quantity="";reviewed=false},label={Text(p.name)})}
        } else order.lines.forEach {l ->FilterChip(selectedId==l.id,{selectedId=l.id;quantity="";reviewed=false},label={Text("${l.productName} · ${quantityLabel(choices?.remaining?.get(l.id)?.toLong() ?: 0,l.quantityPrecision)} ${l.buyingUnit} still due")})}
        PosTextField(quantity,{quantity=it;reviewed=false},"Affected quantity in ${unit.ifBlank {"the selected unit"}}")
        PosTextField(reason,{reason=it;reviewed=false},"What happened?")
        if(reviewed)Text("${quantity.trim()} $unit · ${kind.name.lowercase()}. Saving records the observation only; no stock, cash or supplier debt changes.")
    }
}
