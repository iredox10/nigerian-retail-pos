package pos.android.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import pos.data.*
import pos.domain.*
import pos.ui.components.*
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID

private data class SupplierCreditData(val suppliers:List<SupplierProfile>,val balances:List<SupplierCreditBalance>,val receipts:List<StockReceipt>,val links:List<SupplierReceiptLink>,val allocations:List<SupplierCreditAllocation>)
@Composable
fun AndroidSupplierCreditsScreen(repo:PosRepository,tenantId:String,onChanged:()->Unit) {
    var revision by remember {mutableIntStateOf(0)}
    var adding by remember {mutableStateOf(false)}
    var applying by remember {mutableStateOf<SupplierCreditBalance?>(null)}
    var voiding by remember {mutableStateOf<SupplierCreditBalance?>(null)}
    var reversing by remember {mutableStateOf<SupplierCreditAllocation?>(null)}
    var notice by remember {mutableStateOf<String?>(null)}
    val loaded=operationsLoad(repo,tenantId,revision) {SupplierCreditData(repo.supplierProfiles(tenantId,true),repo.supplierCreditBalances(tenantId),repo.stockReceipts(tenantId),repo.supplierReceiptLinks(tenantId),repo.supplierCreditAllocations(tenantId))}
    fun changed(message:String) {adding=false;applying=null;voiding=null;reversing=null;revision++;notice=message;onChanged()}
    if(adding)AndroidNewSupplierCredit(repo,tenantId,{adding=false}) {changed("Supplier credit note saved. No cash or stock changed.")}
    applying?.let {balance ->AndroidApplySupplierCredit(repo,tenantId,balance,{applying=null}) {changed("Supplier credit applied to the reviewed receipt. No cash moved.")}}
    voiding?.let {balance ->
        var reason by rememberSaveable(balance.note.id) {mutableStateOf("")};val operation=rememberSaveable {UUID.randomUUID().toString()}
        OperationsDialog("Void unused supplier credit",{voiding=null},"Void this unused note",action={
            withContext(Dispatchers.IO) {repo.voidSupplierCredit(tenantId,balance.note.id,reason.trim(),operation)};changed("Unused supplier credit voided. Original note and history retained.")
        }) {Text(balance.note.reference);LabelValueRow("Original note",money(balance.note.amountMinor));Text("Undo every active application first. Voiding keeps the original note and does not move cash, stock or receipt debt.");PosTextField(reason,{reason=it},"Reason for voiding")}
    }
    reversing?.let {allocation ->
        var reason by rememberSaveable(allocation.id) {mutableStateOf("")};val operation=rememberSaveable {UUID.randomUUID().toString()}
        OperationsDialog("Undo supplier credit application",{reversing=null},"Save application reversal",action={
            withContext(Dispatchers.IO) {repo.reverseSupplierCreditApplication(tenantId,allocation.id,reason.trim(),operation)};changed("Application reversed with history. Supplier receipt debt restored; no cash moved.")
        }) {LabelValueRow("Debt restored to this receipt",money(allocation.amountMinor));Text("This returns the amount to the credit note and restores the original receipt debt. The original application remains recorded.");PosTextField(reason,{reason=it},"Reason for undoing this application")}
    }
    LazyColumn(Modifier.fillMaxSize(),verticalArrangement=Arrangement.spacedBy(12.dp),contentPadding=PaddingValues(bottom=24.dp)) {
        item {SectionHeader("Supplier credit notes","Record supplier-issued credit and apply it to a specific buying receipt")}
        item {Text("These notes reduce supplier debt only when explicitly applied. They are separate from customer store credit, refunds and cash received.")}
        item {PosButton("Record supplier credit note",{adding=true},modifier=Modifier.fillMaxWidth())}
        notice?.let {item {InlineNotice(it,tone=NoticeTone.Success)}}
        item {OperationsLoadState(loaded,"supplier credits",{revision++})}
        if(loaded?.isSuccess==true && loaded.getOrThrow().balances.isEmpty())item {EmptyState("No supplier credit notes","Record the actual supplier reference, date and amount before applying any credit.")}
        loaded?.getOrNull()?.let {data ->items(data.balances,key={it.note.id}) {balance ->OperationsCard {
            val note=balance.note
            Text(note.reference,style=MaterialTheme.typography.titleMedium)
            Text(data.suppliers.singleOrNull {it.id==note.supplierId}?.details?.name ?: "Saved supplier")
            Text("Issued ${note.issuedOn} · Recorded ${note.createdAt.take(19)} UTC")
            LabelValueRow("Original note",money(note.amountMinor))
            LabelValueRow("Applied to receipts",money(balance.appliedMinor))
            LabelValueRow("Available credit",money(balance.availableMinor),emphasized=true)
            Text("Reason: ${note.reason}")
            if(balance.voided)Text("Voided · History retained") else {
                if(balance.availableMinor>0)PosButton("Apply to a buying receipt",{applying=balance})
                if(balance.appliedMinor==0L)TextButton(onClick={voiding=balance}) {Text("Void unused note")}
            }
            val events=data.allocations.filter {it.creditId==note.id}
            if(events.isNotEmpty())Text("Application history",style=MaterialTheme.typography.titleSmall)
            events.forEach {event ->
                val receipt=data.receipts.singleOrNull {it.id==event.receiptId}
                Text("${receipt?.productName ?: "Buying receipt"} · ${money(event.signedAmountMinor)} · ${event.createdAt.take(19)} UTC")
                Text(if(event.reversalOf==null)"Applied: ${event.reason}" else "Reversed: ${event.reason}")
                if(!balance.voided && event.reversalOf==null && events.none {it.reversalOf==event.id})TextButton(onClick={reversing=event}) {Text("Undo this application")}
            }
        }}}
    }
}
@Composable private fun AndroidNewSupplierCredit(repo:PosRepository,tenantId:String,dismiss:()->Unit,saved:()->Unit) {
    var revision by remember {mutableIntStateOf(0)}
    val suppliers=operationsLoad(repo,tenantId,revision) {repo.supplierProfiles(tenantId)}
    var supplierId by rememberSaveable {mutableStateOf<String?>(null)}
    var reference by rememberSaveable {mutableStateOf("")}
    var date by rememberSaveable {mutableStateOf(LocalDate.now(ZoneId.of("Africa/Lagos")).toString())}
    var amount by rememberSaveable {mutableStateOf("")};var reason by rememberSaveable {mutableStateOf("")}
    var reviewed by remember {mutableStateOf(false)}
    val operation=rememberSaveable {UUID.randomUUID().toString()}
    OperationsDialog("Record supplier credit note",dismiss,if(reviewed)"Save supplier credit note" else "Review credit note",action={
        val supplier=requireNotNull(suppliers?.getOrThrow()?.singleOrNull {it.id==supplierId}) {"Choose an active supplier."}
        val value=requireNotNull(parseNaira(amount)?.takeIf {it>0}) {"Enter the positive amount actually issued by the supplier."}
        LocalDate.parse(date.trim());require(reference.isNotBlank()) {"Enter the supplier’s credit reference."};require(reason.trim().length>=3) {"Enter why the supplier issued this credit."}
        if(!reviewed)reviewed=true else {
            val ref=reference.trim();val issued=date.trim();val why=reason.trim()
            withContext(Dispatchers.IO) {repo.recordSupplierCredit(tenantId,supplier.id,ref,issued,value,why,operation)};saved()
        }
    }) {
        OperationsLoadState(suppliers,"suppliers",{revision++;reviewed=false})
        suppliers?.getOrNull().orEmpty().forEach {supplier ->FilterChip(supplierId==supplier.id,{supplierId=supplier.id;reviewed=false},label={Text(supplier.details.name)})}
        PosTextField(reference,{reference=it;reviewed=false},"Supplier credit reference")
        PosTextField(date,{date=it;reviewed=false},"Issued on (YYYY-MM-DD)")
        PosTextField(amount,{amount=it;reviewed=false},"Credit note amount (₦)")
        PosTextField(reason,{reason=it;reviewed=false},"Why was this credit issued?")
        if(reviewed) {LabelValueRow("Credit note",money(requireNotNull(parseNaira(amount))));Text("Saving this note does not apply it to a receipt or record cash received.")}
    }
}
private data class CreditReceipts(val receipts:List<Pair<StockReceipt,Long>>)
@Composable private fun AndroidApplySupplierCredit(repo:PosRepository,tenantId:String,balance:SupplierCreditBalance,dismiss:()->Unit,saved:()->Unit) {
    var revision by remember {mutableIntStateOf(0)}
    val loaded=operationsLoad(repo,tenantId,balance.note.id to revision) {
        val ids=repo.supplierReceiptLinks(tenantId).filter {it.supplierId==balance.note.supplierId}.map {it.receiptId}.toSet()
        CreditReceipts(repo.stockReceipts(tenantId).filter {it.id in ids}.map {it to repo.supplierOutstanding(tenantId,it.id)}.filter {it.second>0})
    }
    var receiptId by rememberSaveable {mutableStateOf<String?>(null)}
    var amount by rememberSaveable {mutableStateOf("")};var reason by rememberSaveable {mutableStateOf("")}
    var reviewed by remember {mutableStateOf<SupplierCreditReview?>(null)}
    val operation=rememberSaveable {UUID.randomUUID().toString()}
    OperationsDialog("Apply supplier credit",dismiss,if(reviewed==null)"Review credit application" else "Save credit application",action={
        val receipt=requireNotNull(loaded?.getOrThrow()?.receipts?.singleOrNull {it.first.id==receiptId}) {"Choose a buying receipt linked to this supplier."}
        require(reason.trim().length>=3) {"Enter why you are applying this credit."}
        val current=reviewed
        if(current==null) {
            val value=requireNotNull(parseNaira(amount)) {"Enter the credit amount to apply."}
            reviewed=withContext(Dispatchers.IO) {repo.reviewSupplierCredit(tenantId,balance.note.id,receipt.first.id,value)}
        } else {
            val why=reason.trim()
            withContext(Dispatchers.IO) {repo.applySupplierCredit(tenantId,current,why,operation)};saved()
        }
    }) {
        Text(balance.note.reference);OperationsLoadState(loaded,"unpaid supplier receipts",{revision++;reviewed=null})
        loaded?.getOrNull()?.receipts.orEmpty().forEach {(receipt,owed) ->FilterChip(receiptId==receipt.id,{receiptId=receipt.id;reviewed=null},label={Text("${receipt.productName} · ${money(owed)} owed · ${receipt.createdAt.take(10)}")})}
        if(loaded?.getOrNull()?.receipts?.isEmpty()==true)Text("No unpaid receipt is linked to this supplier. Link the correct supplier account when receiving goods.")
        PosTextField(amount,{amount=it;reviewed=null},"Credit amount to apply (₦)")
        PosTextField(reason,{reason=it;reviewed=null},"Reason for application")
        reviewed?.let {review ->
            LabelValueRow("Receipt debt before",money(review.owedBeforeMinor));LabelValueRow("Apply credit",money(review.amountMinor))
            LabelValueRow("Receipt debt after",money(review.owedBeforeMinor-review.amountMinor))
            LabelValueRow("Credit remaining",money(review.availableBeforeMinor-review.amountMinor))
            Text("The note and receipt balances are checked again before saving. No cash moves.")
        }
    }
}
