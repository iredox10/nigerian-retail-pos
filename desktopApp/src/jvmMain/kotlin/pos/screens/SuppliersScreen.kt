package pos.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import pos.data.PosRepository
import pos.domain.StockReceipt
import pos.domain.SupplierPayment
import pos.domain.money
import pos.domain.parseNaira
import pos.output.CsvService
import pos.output.SupplierStatementService
import pos.ui.components.*
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.UUID

/** Receipt-backed supplier balances; free-text names do not establish supplier account identity. */
@Composable
fun SuppliersScreen(repo: PosRepository, tenantId: String, onChanged: () -> Unit) {
    key(tenantId) {
        var tab by remember {mutableStateOf("Supplier accounts")}
        Column(Modifier.fillMaxSize(),verticalArrangement=Arrangement.spacedBy(16.dp)) {
            Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                listOf("Supplier accounts","Receipts & payments").forEach {label -> FilterChip(tab==label,{tab=label},label={Text(label)})}
            }
            Box(Modifier.weight(1f)) {
                if(tab=="Supplier accounts") SupplierProfilesPanel(repo,tenantId,onChanged)
                else TenantSuppliers(repo,tenantId,onChanged)
            }
        }
    }
}

private data class ReceiptBalance(val receipt: StockReceipt, val payments: List<SupplierPayment>, val outstanding: Long)

@Composable
private fun TenantSuppliers(repo: PosRepository, tenantId: String, onChanged: () -> Unit) {
    var revision by remember { mutableIntStateOf(0) }
    var search by remember { mutableStateOf("") }
    var selected by remember { mutableStateOf<StockReceipt?>(null) }
    var statementSupplier by remember { mutableStateOf<String?>(null) }
    var message by remember { mutableStateOf<String?>(null) }
    var showPaid by remember { mutableStateOf(false) }
    val result = remember(revision) { runCatching {
        repo.stockReceipts(tenantId).map { receipt ->
            ReceiptBalance(receipt, repo.supplierPayments(tenantId, receipt.id), repo.supplierOutstanding(tenantId, receipt.id))
        }
    } }
    val shiftResult = remember(revision) { runCatching { repo.activeCashShift(tenantId) } }
    val error = result.exceptionOrNull()?.message ?: shiftResult.exceptionOrNull()?.message
    val rows = result.getOrDefault(emptyList())
    val visible = rows.filter { (showPaid || it.outstanding > 0) &&
        (it.receipt.supplierName.contains(search, true) || it.receipt.productName.contains(search, true) || it.receipt.id.contains(search, true)) }

    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        SectionHeader("Suppliers", "See what you still owe and record cash payments against a stock receipt.")
        PosTextField(search, { search = it }, "Search supplier, item or receipt", modifier = Modifier.fillMaxWidth())
        Row(verticalAlignment = Alignment.CenterVertically) {
            FilterChip(showPaid, { showPaid = !showPaid }, label = { Text("Include fully paid receipts") })
            Spacer(Modifier.weight(1f))
            NeutralButton("Refresh", { revision++ })
        }
        message?.let { InlineNotice(it, tone = NoticeTone.Success) }
        error?.let { InlineNotice("Could not load supplier balances", it, NoticeTone.Danger) }
        if (error == null && shiftResult.getOrNull() == null) {
            InlineNotice("Open today's cash drawer to pay a supplier", "Go to Money → Today's cash and count your opening cash. Balances remain available to view here.", NoticeTone.Info)
        }
        Text("Names are grouped exactly as recorded on purchases. Similar names are not automatically merged into one supplier account.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (error == null) LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            if (visible.isEmpty()) item {
                EmptyState(if (rows.isEmpty()) "No supplier purchases yet" else "No matching unpaid receipts",
                    if (rows.isEmpty()) "Use Stock → Add stock to record a purchase and any supplier balance." else "Try another search or include fully paid receipts.")
            }
            visible.groupBy { it.receipt.supplierName }.toSortedMap().forEach { (name, purchases) ->
                item(key = "supplier:$name") {
                    val total = runCatching { purchases.fold(0L) { sum, row -> Math.addExact(sum, row.outstanding) } }.getOrNull()
                    Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(name.ifBlank { "Supplier not recorded" }, Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
                        Text(total?.let { "Owed on these receipts ${money(it)}" } ?: "See individual receipt balances", fontWeight = FontWeight.SemiBold)
                    }
                    Text("${purchases.size} matching receipt(s) · This shop only", style = MaterialTheme.typography.bodySmall)
                    NeutralButton("View supplier statement", { statementSupplier=name })
                }
                items(purchases, key = { it.receipt.id }) { row ->
                    SupplierReceipt(row, shiftResult.getOrNull() != null) { selected = row.receipt; message = null }
                }
            }
        }
    }
    selected?.let { receipt ->
        SupplierPaymentDialog(repo, tenantId, receipt, { selected = null }) {
            selected = null
            message = "Supplier payment saved on this computer"
            revision++
            onChanged()
        }
    }
    statementSupplier?.let { name ->
        SupplierStatementDialog(repo,tenantId,name) { statementSupplier=null }
    }
}

/** Uses fresh complete histories, independently of the balances screen's filters. */
@Composable
private fun SupplierStatementDialog(repo:PosRepository,tenantId:String,supplierName:String,dismiss:()->Unit) {
    val today=remember {LocalDate.now(ZoneId.of("Africa/Lagos"))}
    var fromText by remember {mutableStateOf(today.withDayOfMonth(1).toString())}
    var throughText by remember {mutableStateOf(today.toString())}
    var revision by remember {mutableIntStateOf(0)}
    var message by remember {mutableStateOf<String?>(null)}
    var exportError by remember {mutableStateOf<String?>(null)}
    val history=remember(tenantId,revision) {runCatching {repo.stockReceipts(tenantId) to repo.supplierPayments(tenantId)}}
    val loaded=remember(history,fromText,throughText) {runCatching {
        require(listOf(fromText,throughText).all {it.matches(Regex("\\d{4}-\\d{2}-\\d{2}"))}) {"Use YYYY-MM-DD for both dates."}
        val from=LocalDate.parse(fromText)
        val through=LocalDate.parse(throughText)
        require(!through.isAfter(today)) {"Choose today or an earlier end date."}
        val records=history.getOrThrow()
        SupplierStatementService.build(tenantId,supplierName,records.first,records.second,from,through)
    }}
    AlertDialog(
        onDismissRequest=dismiss,
        title={Text("Statement · ${supplierName.ifBlank {"Supplier not recorded"}}")},
        text={
            Column(Modifier.heightIn(max=520.dp).verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(12.dp)) {
                Text("Saved receipt and cash-payment history for this exact recorded name. Includes fully paid receipts and ignores the supplier list's search filters. This is not a supplier master account.",style=MaterialTheme.typography.bodySmall)
                PosTextField(fromText,{fromText=it;message=null;exportError=null},"From (YYYY-MM-DD)",Modifier.fillMaxWidth())
                PosTextField(throughText,{throughText=it;message=null;exportError=null},"Through (YYYY-MM-DD)",Modifier.fillMaxWidth())
                Text("Dates include the whole day in West Africa Time.",style=MaterialTheme.typography.bodySmall)
                NeutralButton("Refresh statement",{revision++;message=null;exportError=null})
                loaded.exceptionOrNull()?.let {InlineNotice("Statement unavailable",it.message ?: "Check the dates or refresh the saved records.",NoticeTone.Warning)}
                loaded.getOrNull()?.let {statement ->
                    LabelValueRow("Opening debt before ${statement.from}",money(statement.openingMinor))
                    LabelValueRow("Purchases received in range",money(statement.purchasesMinor))
                    LabelValueRow("Cash paid when receiving this stock",money(statement.paidAtReceiptMinor))
                    LabelValueRow("Later cash payments in range",money(statement.laterPaymentsMinor))
                    LabelValueRow("Closing debt through ${statement.through}",money(statement.closingMinor),emphasized=true)
                    HorizontalDivider()
                    LabelValueRow("Current outstanding — all recorded dates",money(statement.currentOutstandingMinor))
                    Text("Current outstanding includes records after the chosen end date. No supplier credits, purchase returns or opening balances outside saved receipts are assumed.",style=MaterialTheme.typography.bodySmall)
                    HorizontalDivider()
                    Text("Activity in this date range",style=MaterialTheme.typography.titleSmall)
                    if(statement.events.isEmpty()) Text("No purchases or payments were recorded in this period. Any opening debt is carried into the closing debt.",style=MaterialTheme.typography.bodySmall)
                    statement.events.forEach {event ->
                        Column(verticalArrangement=Arrangement.spacedBy(4.dp)) {
                            Text(if(event.laterPaymentMinor>0) "Later cash payment" else "Stock received",fontWeight=FontWeight.SemiBold)
                            Text("${supplierTime(event.createdAt)} · Receipt ${event.receiptId.takeLast(8).uppercase()}",style=MaterialTheme.typography.bodySmall)
                            if(event.laterPaymentMinor>0) LabelValueRow("Cash paid",money(event.laterPaymentMinor))
                            else {
                                LabelValueRow("Purchase total",money(event.purchaseMinor))
                                LabelValueRow("Cash paid at receipt",money(event.paidAtReceiptMinor))
                            }
                            LabelValueRow("Debt after this record",money(event.balanceAfterMinor))
                        }
                    }
                }
                message?.let {Text(it,style=MaterialTheme.typography.bodySmall)}
                exportError?.let {InlineNotice("CSV not saved",it,NoticeTone.Danger)}
            }
        },
        confirmButton={PosButton("Save statement CSV",{
            exportError=null;message=null
            try {
                val statement=loaded.getOrThrow()
                val file=CsvService.save(SupplierStatementService.csv(statement),"supplier-statement-${statement.from}-to-${statement.through}.csv")
                message=if(file==null) "Export cancelled." else "Statement CSV saved to ${file.name}."
            } catch(e:Exception) {exportError=e.message ?: "The statement could not be exported. No records changed."}
        },enabled=loaded.isSuccess)},
        dismissButton={TextButton(onClick=dismiss) {Text("Close")}},
    )
}

@Composable
private fun SupplierReceipt(row: ReceiptBalance, canPay: Boolean, pay: () -> Unit) {
    val receipt = row.receipt
    Surface(shape = MaterialTheme.shapes.medium) {
        Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(receipt.productName, fontWeight = FontWeight.SemiBold)
                    Text("${receipt.quantityLabel} ${receipt.unit} · Receipt ${receipt.id.takeLast(8).uppercase()} · ${supplierTime(receipt.createdAt)}", style = MaterialTheme.typography.bodySmall)
                }
                StatusPill(if (row.outstanding > 0) "Money owed" else "Fully paid", if (row.outstanding > 0) NoticeTone.Warning else NoticeTone.Success)
            }
            if(receipt.purchaseUnitId!=null) Text("${receipt.quantityLabel} ${receipt.unit} × ${receipt.baseUnitsPerUnit} ${receipt.baseUnit} = ${receipt.baseQuantityLabel} ${receipt.baseUnit} added to stock",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
            LabelValueRow("Purchase total", money(receipt.totalCostMinor))
            LabelValueRow("Cash paid when stock arrived", money(receipt.paidCashMinor))
            val laterPaid = row.payments.fold(0L) { sum, payment -> Math.addExact(sum, payment.amountMinor) }
            LabelValueRow("Payments recorded later", money(laterPaid))
            LabelValueRow("Still owed on this receipt", money(row.outstanding), emphasized = true)
            Text("Originally owed ${money(receipt.unpaidMinor)} when stock arrived. The current amount above includes later payments.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (row.payments.isNotEmpty()) {
                HorizontalDivider()
                Text("Later payments", style = MaterialTheme.typography.titleSmall)
                row.payments.forEach { payment ->
                    LabelValueRow(supplierTime(payment.createdAt), money(payment.amountMinor))
                }
            }
            if (row.outstanding > 0) NeutralButton("Record supplier payment", pay, enabled = canPay)
        }
    }
}

@Composable
private fun SupplierPaymentDialog(repo: PosRepository, tenantId: String, receipt: StockReceipt, dismiss: () -> Unit, saved: () -> Unit) {
    val operationId = remember(receipt.id) { UUID.randomUUID().toString() }
    var amount by remember(receipt.id) { mutableStateOf("") }
    var error by remember(receipt.id) { mutableStateOf<String?>(null) }
    var committing by remember { mutableStateOf(false) }
    val outstandingResult = remember(receipt.id) { runCatching { repo.supplierOutstanding(tenantId, receipt.id) } }
    val outstanding = outstandingResult.getOrNull()
    val value = parseNaira(amount)
    AlertDialog(
        onDismissRequest = { if (!committing) dismiss() },
        title = { Text("Pay ${receipt.supplierName}") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("${receipt.productName} · Receipt ${receipt.id.takeLast(8).uppercase()}")
                outstanding?.let { LabelValueRow("Still owed", money(it), emphasized = true) }
                Text("This payment takes cash from your open drawer and reduces only this receipt's supplier balance.")
                PosTextField(amount, { amount = it }, "Cash paid in naira", hint = "For example, 2500.00")
                if (value != null && value > 0 && outstanding != null && value <= outstanding) {
                    LabelValueRow("Owed after payment", money(outstanding - value))
                }
                (error ?: outstandingResult.exceptionOrNull()?.message)?.let { InlineNotice("Payment not saved", it, NoticeTone.Danger) }
            }
        },
        confirmButton = {
            PosButton(if (committing) "Saving payment…" else "Save cash payment", {
                error = null
                committing = true
                val result = runCatching {
                    val parsed = parseNaira(amount) ?: throw IllegalArgumentException("Enter a valid amount, for example 2500.00.")
                    require(parsed > 0) { "Enter a payment greater than zero." }
                    require(repo.activeCashShift(tenantId) != null) { "Open today's cash drawer before paying a supplier." }
                    repo.paySupplierReceipt(tenantId, receipt.id, parsed, operationId)
                }
                committing = false
                if (result.isSuccess) saved() else error = result.exceptionOrNull()?.message ?: "Could not save the supplier payment. Your entries are still here."
            }, enabled = !committing && outstanding != null)
        },
        dismissButton = { TextButton(onClick = dismiss, enabled = !committing) { Text("Cancel") } },
    )
}

private fun supplierTime(value: String): String = runCatching {
    DateTimeFormatter.ofPattern("dd MMM yyyy, HH:mm").withZone(ZoneId.of("Africa/Lagos")).format(Instant.parse(value))
}.getOrDefault(value)
