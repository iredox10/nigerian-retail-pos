package pos.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import pos.data.*
import pos.domain.*
import pos.output.CsvService
import pos.output.StatementPdfService
import pos.output.SupplierAccountStatementService
import pos.ui.components.*
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID

/** Current contact profiles and explicitly linked purchase identity; never automatic name matching. */
@Composable
internal fun SupplierProfilesPanel(repo:PosRepository,tenantId:String,onChanged:()->Unit) {
    var revision by remember {mutableIntStateOf(0)}
    var search by rememberSaveable {mutableStateOf("")}
    var includeArchived by rememberSaveable {mutableStateOf(false)}
    var selectedId by rememberSaveable {mutableStateOf<String?>(null)}
    var editing by remember {mutableStateOf(false)}
    var creating by remember {mutableStateOf(false)}
    var statementOpen by remember {mutableStateOf(false)}
    var invoicesOpen by remember {mutableStateOf(false)}
    var linking by remember {mutableStateOf(false)}
    var historyOpen by remember {mutableStateOf(false)}
    var message by remember {mutableStateOf<String?>(null)}
    val loaded=remember(tenantId,revision) {runCatching {
        Triple(repo.supplierProfiles(tenantId,true),repo.supplierReceiptLinks(tenantId),repo.stockReceipts(tenantId))
    }}
    val records=loaded.getOrNull()
    val selected=records?.first?.find {it.id==selectedId}
    fun refresh(text:String) {revision++;message=text;onChanged()}
    Column(Modifier.fillMaxSize(),verticalArrangement=Arrangement.spacedBy(16.dp)) {
        Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(12.dp)) {
            SectionHeader(if(selected==null) "Supplier accounts" else selected.details.name,"Current contacts and purchases explicitly linked to this supplier",Modifier.weight(1f))
            if(selected==null) PosButton("Add supplier",{creating=true}) else NeutralButton("Back to suppliers",{selectedId=null;historyOpen=false})
        }
        message?.let {InlineNotice(it,tone=NoticeTone.Success)}
        loaded.exceptionOrNull()?.let {InlineNotice("Suppliers could not be loaded",it.message ?: "Try again.",NoticeTone.Danger);NeutralButton("Try again",{revision++})}
        if(records!=null && selected==null) {
            PosTextField(search,{search=it},"Find a supplier",hint="Name, phone, contact or address")
            FilterChip(includeArchived,{includeArchived=!includeArchived},label={Text("Include archived suppliers")})
            val visible=records.first.filter {(includeArchived || !it.archived) && listOf(it.details.name,it.details.phone.orEmpty(),it.details.contactPerson.orEmpty(),it.details.address.orEmpty(),it.id).any {field -> field.contains(search,true)}}
            LazyColumn(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(12.dp)) {
                if(visible.isEmpty()) item {EmptyState(if(records.first.isEmpty()) "No supplier profiles yet" else "No matching suppliers","Add a profile for contact details and a stable account identity. Older purchases stay unlinked until you review them.")}
                items(visible,key={it.id}) {profile ->
                    Surface(shape=MaterialTheme.shapes.medium) {Column(Modifier.fillMaxWidth().padding(20.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
                        Row(verticalAlignment=Alignment.CenterVertically) {Text(profile.details.name,Modifier.weight(1f),style=MaterialTheme.typography.titleMedium);if(profile.archived) StatusPill("Archived",NoticeTone.Neutral);NeutralButton("Open supplier",{selectedId=profile.id})}
                        Text(listOfNotNull(profile.details.phone,profile.details.contactPerson).joinToString(" · ").ifEmpty {"No contact details recorded"},style=MaterialTheme.typography.bodySmall)
                        Text("Account ${profile.id.takeLast(8)} · ${records.second.count {it.supplierId==profile.id}} linked purchase(s)",style=MaterialTheme.typography.bodySmall)
                    }}
                }
            }
        } else if(records!=null && selected!=null) {
            val links=records.second.filter {it.supplierId==selected.id}
            val ids=links.map {it.receiptId}.toSet()
            val purchases=records.third.filter {it.id in ids}
            val balance=runCatching {purchases.fold(0L) {sum,receipt -> Math.addExact(sum,repo.supplierOutstanding(tenantId,receipt.id))}}
            LazyColumn(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(16.dp)) {
                item {Surface(shape=MaterialTheme.shapes.medium) {Column(Modifier.fillMaxWidth().padding(20.dp),verticalArrangement=Arrangement.spacedBy(10.dp)) {
                    if(selected.archived) InlineNotice("Supplier archived","History and statements remain available. Restore the profile before linking new purchases.",NoticeTone.Info)
                    LabelValueRow("Account ID",selected.id)
                    listOf("Phone" to selected.details.phone,"Contact person" to selected.details.contactPerson,"Address" to selected.details.address,"Payment terms" to selected.details.paymentTerms,"Notes" to selected.details.notes).forEach {(label,value) -> LabelValueRow(label,value ?: "Not recorded")}
                    LabelValueRow("Owed on linked purchases",balance.getOrNull()?.let(::money) ?: "Could not calculate",emphasized=true)
                    Text("Contact details are current. Each purchase retains the name originally recorded. Profile edits do not change stock, cash or supplier debt.",style=MaterialTheme.typography.bodySmall)
                    Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(12.dp)) {
                        NeutralButton("Edit supplier",{editing=true})
                        NeutralButton("View statement",{statementOpen=true})
                    }
                    NeutralButton("Supplier invoices & evidence",{invoicesOpen=true})
                    NeutralButton("Link an older purchase",{linking=true},enabled=!selected.archived)
                    TextButton({historyOpen=!historyOpen}) {Text(if(historyOpen) "Hide profile history" else "View profile history")}
                }}}
                if(historyOpen) {
                    val changes=runCatching {repo.supplierProfileChanges(tenantId,selected.id)}
                    changes.exceptionOrNull()?.let {failure -> item {InlineNotice("History unavailable",failure.message ?: "Try again.",NoticeTone.Danger)}}
                    items(changes.getOrDefault(emptyList()).reversed(),key={it.id}) {event ->
                        Surface(shape=MaterialTheme.shapes.small) {Column(Modifier.fillMaxWidth().padding(16.dp),verticalArrangement=Arrangement.spacedBy(6.dp)) {
                            Text("Revision ${event.version} · ${event.createdAt}",fontWeight=FontWeight.Medium)
                            Text(event.reason)
                            Text("Name: ${event.current.name} · ${if(event.currentArchived) "Archived" else "Active"}",style=MaterialTheme.typography.bodySmall)
                            Text("Previous name: ${event.previous?.name ?: "New supplier"}",style=MaterialTheme.typography.bodySmall)
                            val fields=listOf("Phone" to (event.previous?.phone to event.current.phone),"Contact person" to (event.previous?.contactPerson to event.current.contactPerson),"Address" to (event.previous?.address to event.current.address),"Payment terms" to (event.previous?.paymentTerms to event.current.paymentTerms),"Notes" to (event.previous?.notes to event.current.notes))
                            fields.filter {it.second.first!=it.second.second}.forEach {(label,values) -> Text("$label: ${values.first ?: "Not recorded"} → ${values.second ?: "Not recorded"}",style=MaterialTheme.typography.bodySmall)}
                        }}
                    }
                }
                item {SectionHeader("Linked purchases","For later cash payments, open Receipts & payments. Linking does not collect or pay money.")}
                if(purchases.isEmpty()) item {EmptyState("No linked purchases","Choose this supplier while receiving stock, or explicitly link a reviewed older purchase.")}
                items(purchases.sortedByDescending {it.createdAt},key={it.id}) {receipt -> Surface(shape=MaterialTheme.shapes.small) {Column(Modifier.fillMaxWidth().padding(16.dp),verticalArrangement=Arrangement.spacedBy(6.dp)) {
                    Text("${receipt.productName} · ${receipt.quantityLabel} ${receipt.unit}",fontWeight=FontWeight.Medium)
                    Text("Name saved on purchase: ${receipt.supplierName}",style=MaterialTheme.typography.bodySmall)
                    LabelValueRow("Receipt ${receipt.id.takeLast(8)}",money(receipt.totalCostMinor))
                }}}
            }
        }
    }
    if(creating || (editing && selected!=null)) SupplierProfileEditor(if(creating) null else selected,{creating=false;editing=false}) {details,archived,reason,operation ->
        if(creating) repo.addSupplierProfile(tenantId,details,operation) else repo.updateSupplierProfile(tenantId,selected!!.id,details,archived,selected.version,reason,operation)
        creating=false;editing=false;refresh("Supplier profile saved on this computer")
    }
    if(invoicesOpen && selected!=null) SupplierInvoicesDialog(repo,selected,{invoicesOpen=false}) {revision++;onChanged()}
    if(statementOpen && selected!=null) SupplierAccountStatementDialog(repo,selected) {statementOpen=false}
    if(linking && selected!=null) LinkSupplierReceiptDialog(repo,selected,records.third.filter {receipt -> records.second.none {it.receiptId==receipt.id}},{linking=false}) {linking=false;refresh("Purchase linked. Its recorded name and money are unchanged.")}
}

@Composable
private fun SupplierProfileEditor(profile:SupplierProfile?,dismiss:()->Unit,save:(SupplierDetails,Boolean,String,String)->Unit) {
    var name by remember {mutableStateOf(profile?.details?.name.orEmpty())}
    var phone by remember {mutableStateOf(profile?.details?.phone.orEmpty())}
    var contact by remember {mutableStateOf(profile?.details?.contactPerson.orEmpty())}
    var address by remember {mutableStateOf(profile?.details?.address.orEmpty())}
    var terms by remember {mutableStateOf(profile?.details?.paymentTerms.orEmpty())}
    var notes by remember {mutableStateOf(profile?.details?.notes.orEmpty())}
    var archived by remember {mutableStateOf(profile?.archived ?: false)}
    var reason by remember {mutableStateOf("")}
    var error by remember {mutableStateOf<String?>(null)}
    val operation=remember(name,phone,contact,address,terms,notes,archived,reason) {UUID.randomUUID().toString()}
    AlertDialog(onDismissRequest=dismiss,title={Text(if(profile==null) "Add supplier" else "Edit supplier")},text={
        Column(Modifier.heightIn(max=520.dp).verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(12.dp)) {
            PosTextField(name,{name=it;error=null},"Supplier name")
            PosTextField(phone,{phone=it;error=null},"Phone (optional)",hint="08012345678 or +234…")
            PosTextField(contact,{contact=it;error=null},"Contact person (optional)")
            PosTextField(address,{address=it;error=null},"Address (optional)")
            PosTextField(terms,{terms=it;error=null},"Payment terms (optional)",hint="For example, agreed payment within 14 days")
            PosTextField(notes,{notes=it;error=null},"Notes (optional)")
            if(profile!=null) {
                Row(verticalAlignment=Alignment.CenterVertically) {Checkbox(archived,{archived=it});Text("Archive supplier; keep all history")}
                PosTextField(reason,{reason=it;error=null},"Why are these details changing?")
            }
            Text("A similar name does not identify the same business. Profiles are kept separate unless you explicitly link a purchase.",style=MaterialTheme.typography.bodySmall)
            error?.let {InlineNotice("Supplier not saved",it,NoticeTone.Danger)}
        }
    },confirmButton={PosButton("Save supplier",{try {
        fun optional(value:String)=value.trim().ifEmpty {null}
        save(SupplierDetails(name.trim(),normalizeNigerianPhone(phone),optional(contact),optional(address),optional(terms),optional(notes)),archived,reason.trim(),operation)
    } catch(e:Exception) {error=e.message ?: "Supplier could not be saved. Your entries are still here."}})},dismissButton={TextButton(dismiss) {Text("Cancel")}})
}

@Composable
private fun LinkSupplierReceiptDialog(repo:PosRepository,profile:SupplierProfile,receipts:List<StockReceipt>,dismiss:()->Unit,saved:()->Unit) {
    var search by remember {mutableStateOf("")}
    var error by remember {mutableStateOf<String?>(null)}
    val operation=remember {UUID.randomUUID().toString()}
    AlertDialog(onDismissRequest=dismiss,title={Text("Link purchase to ${profile.details.name}")},text={Column(Modifier.widthIn(max=520.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
        Text("Review the original purchase before linking. This records supplier identity only; its name, cost, cash and debt are unchanged. A saved link cannot be reassigned here.")
        PosTextField(search,{search=it},"Find an unlinked receipt",hint="Item, original supplier or receipt ID")
        val visible=receipts.filter {listOf(it.productName,it.supplierName,it.id).any {field -> field.contains(search,true)}}
        LazyColumn(Modifier.heightIn(max=320.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
            if(visible.isEmpty()) item {EmptyState("No matching unlinked receipts","Purchases already linked to a profile are excluded.")}
            items(visible,key={it.id}) {receipt -> Column(verticalArrangement=Arrangement.spacedBy(4.dp)) {
                Text("${receipt.productName} · ${money(receipt.totalCostMinor)}",fontWeight=FontWeight.Medium)
                Text("${receipt.supplierName} · ${receipt.id.takeLast(8)} · ${receipt.createdAt}",style=MaterialTheme.typography.bodySmall)
                NeutralButton("Link this receipt",{try {repo.linkSupplierReceipt(profile.tenantId,receipt.id,profile.id,operation);saved()} catch(e:Exception) {error=e.message ?: "Receipt was not linked."}})
            }}
        }
        error?.let {InlineNotice("Receipt not linked",it,NoticeTone.Danger)}
    }},confirmButton={TextButton(dismiss) {Text("Close")}})
}

@Composable
private fun SupplierAccountStatementDialog(repo:PosRepository,profile:SupplierProfile,dismiss:()->Unit) {
    val today=remember {LocalDate.now(ZoneId.of("Africa/Lagos"))}
    var from by remember {mutableStateOf(today.withDayOfMonth(1).toString())}
    var through by remember {mutableStateOf(today.toString())}
    var revision by remember {mutableIntStateOf(0)}
    var message by remember {mutableStateOf<String?>(null)}
    var error by remember {mutableStateOf<String?>(null)}
    val loaded=remember(profile,from,through,revision) {runCatching {
        require(isValidCreditDueDate(from) && isValidCreditDueDate(through)) {"Use YYYY-MM-DD for both dates."}
        val end=LocalDate.parse(through);require(!end.isAfter(today)) {"Choose today or an earlier end date."}
        SupplierAccountStatementService.build(
            profile,repo.supplierReceiptLinks(profile.tenantId),repo.stockReceipts(profile.tenantId),
            repo.supplierPayments(profile.tenantId),LocalDate.parse(from),end,repo.purchaseReturns(profile.tenantId),
        )
    }}
    AlertDialog(onDismissRequest=dismiss,title={Text("Supplier account statement")},text={Column(Modifier.widthIn(max=560.dp).heightIn(max=550.dp).verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(12.dp)) {
        Text("Current profile: ${profile.details.name} · Account ${profile.id.takeLast(8)}")
        Text("Includes purchases explicitly linked to this supplier and their payments and returns, dated in West Africa Time.",style=MaterialTheme.typography.bodySmall)
        PosTextField(from,{from=it;message=null},"From (YYYY-MM-DD)")
        PosTextField(through,{through=it;message=null},"Through (YYYY-MM-DD)")
        NeutralButton("Refresh",{revision++})
        loaded.exceptionOrNull()?.let {InlineNotice("Statement unavailable",it.message ?: "Check the dates and refresh.",NoticeTone.Danger)}
        loaded.getOrNull()?.statement?.let {s ->
            LabelValueRow("Opening amount owed",money(s.openingMinor))
            LabelValueRow("Purchases in period",money(s.purchasesMinor))
            LabelValueRow("Cash paid at receipt",money(s.paidAtReceiptMinor))
            LabelValueRow("Later payments",money(s.laterPaymentsMinor))
            LabelValueRow("Purchase return credits",money(s.returnCreditsMinor))
            LabelValueRow("Closing amount owed",money(s.closingMinor),emphasized=true)
            LabelValueRow("Owed now, all dates",money(s.currentOutstandingMinor))
            if(s.events.isEmpty()) Text("No linked purchases, payments or returns were recorded in this period.",style=MaterialTheme.typography.bodySmall)
            s.events.forEach {event ->
                val amount = maxOf(event.returnCreditMinor, event.laterPaymentMinor, event.purchaseMinor)
                Text("${event.createdAt} · ${event.receiptId.takeLast(8)} · ${event.activity} ${money(amount)}",style=MaterialTheme.typography.bodySmall)
            }
        }
        message?.let {InlineNotice(it,tone=NoticeTone.Success)}
        NeutralButton("Save statement PDF",{try {
            val file=StatementPdfService.exportSupplier(repo.tenants().single {it.id==profile.tenantId},loaded.getOrThrow())
            message=if(file==null) "Export cancelled." else "Statement PDF saved to ${file.name}.";error=null
        } catch(e:Exception) {error=e.message ?: "Statement could not be exported."}},enabled=loaded.isSuccess)
        error?.let {InlineNotice("Statement not saved",it,NoticeTone.Danger)}
    }},confirmButton={PosButton("Save statement CSV",{try {
        val value=loaded.getOrThrow()
        val file=CsvService.save(SupplierAccountStatementService.csv(value),"supplier-account-${profile.id.takeLast(8)}-$from-to-$through.csv")
        message=if(file==null) "Export cancelled." else "Statement CSV saved to ${file.name}.";error=null
    } catch(e:Exception) {error=e.message ?: "Statement could not be exported."}},enabled=loaded.isSuccess)},dismissButton={TextButton(dismiss) {Text("Close")}})
}
