package pos.screens

import pos.domain.*

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import pos.data.PosRepository
import pos.data.receiptImageBytes
import pos.data.receiptProfile
import pos.data.creditReturnAllowance
import pos.data.returnCreditItemsReviewed
import pos.output.BrandedReceiptService
import pos.output.PdfDocumentKind
import pos.output.PdfDocumentService
import pos.output.PdfReceiptService
import pos.domain.Sale
import pos.domain.money
import pos.output.ReceiptService
import pos.output.SaleDocumentService
import pos.output.receiptDate
import pos.ui.components.*
import pos.ui.theme.PosPalette
import java.util.UUID

@Composable
fun SalesScreen(repo: PosRepository, tenantId: String, allowReturns: Boolean = true, onChanged: () -> Unit) {
    key(tenantId) { TenantSalesScreen(repo, tenantId, allowReturns, onChanged) }
}

@Composable
private fun TenantSalesScreen(repo: PosRepository, tenantId: String, allowReturns: Boolean, onChanged: () -> Unit) {
    var revision by remember { mutableStateOf(0) }
    val sales = remember(tenantId, revision) { repo.sales(tenantId) }
    val tenant = remember(tenantId) { repo.tenants().single { it.id == tenantId } }
    var search by remember { mutableStateOf("") }
    var selectedId by remember { mutableStateOf<String?>(null) }
    var returning by remember { mutableStateOf(false) }
    var exchanging by remember { mutableStateOf(false) }
    var showingDocuments by remember { mutableStateOf(false) }
    var notice by remember { mutableStateOf<String?>(null) }
    var noticeError by remember { mutableStateOf(false) }
    var outputBusy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val selected = sales.find { it.id == selectedId }
    val returns = remember(tenantId, selectedId, revision) { selectedId?.let { repo.saleReturns(tenantId, it) } ?: emptyList() }
    val profileResult = remember(tenantId, selectedId, revision) {
        runCatching { selectedId?.let { repo.receiptProfile(tenantId, it) } }
    }
    val receiptResult = runCatching {
        val profile = profileResult.getOrThrow()
        selected?.let { BrandedReceiptService.format(tenant, it, returns, profile) }
    }
    val receipt = receiptResult.getOrNull()
    val creditAllowance=remember(tenantId,selectedId,revision,allowReturns) {
        selected?.takeIf {allowReturns && it.creditMinor>0 && it.externalPayments.isEmpty()}?.let {runCatching {repo.creditReturnAllowance(tenantId,it.id)}}
    }
    val returnable = remember(tenantId, selectedId, revision) {
        selectedId?.let { repo.returnableQuantities(tenantId, it) } ?: emptyMap()
    }
    val filtered = sales.filter { sale ->
        search.isBlank() || sale.id.contains(search, true) || sale.lines.any { it.product.name.contains(search, true) } ||
            money(sale.totalMinor).contains(search, true) || receiptDate(sale.createdAt).contains(search, true) ||
            sale.externalPayments.any {it.reference.contains(search,true) || it.receivingAccount.contains(search,true)}
    }

    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        SectionHeader("Sales", if(allowReturns) "Find a saved sale, print a receipt or record returned items." else "Find a saved sale and print or export its receipt.")
        receiptResult.exceptionOrNull()?.let {
            InlineNotice("Receipt could not be loaded", it.message ?: "Reload to try again.", NoticeTone.Danger)
            NeutralButton("Reload receipt", { revision++ })
        }
        notice?.let {
            InlineNotice(if (noticeError) "Action not completed" else "Receipt update", it,
                if (noticeError) NoticeTone.Danger else NoticeTone.Neutral, Modifier.fillMaxWidth())
        }
        BoxWithConstraints(Modifier.weight(1f)) {
            val compact = maxWidth < 800.dp
            Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                if (!compact || selected == null) {
                    Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        PosTextField(search, { search = it }, "Find a receipt", hint = "Receipt number, item name, amount or date")
                        Text("${filtered.size} saved ${if (filtered.size == 1) "sale" else "sales"}", style = MaterialTheme.typography.bodySmall,
                            color = PosPalette.Secondary)
                        if (filtered.isEmpty()) EmptyState(if (sales.isEmpty()) "No sales yet" else "No matching receipts",
                            if (sales.isEmpty()) "Completed sales from this shop will appear here, even without internet."
                            else "Try another receipt number, item or amount, or clear your search.")
                        else LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            items(filtered, key = { it.id }) { sale ->
                                Surface(onClick = { selectedId = sale.id; notice = null },
                                    color = if (sale.id == selectedId) PosPalette.AccentTint else PosPalette.Surface,
                                    shape = MaterialTheme.shapes.medium) {
                                    Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                                            Text("Receipt ${sale.id.take(8)}", Modifier.weight(1f), fontWeight = FontWeight.SemiBold)
                                            Text(money(sale.totalMinor), fontWeight = FontWeight.SemiBold)
                                        }
                                        Text(receiptDate(sale.createdAt), style = MaterialTheme.typography.bodySmall, color = PosPalette.Secondary)
                                        Text(sale.lines.joinToString { "${it.quantityLabel} ${it.sellingUnit} ${it.product.name}" },
                                            maxLines = 2, style = MaterialTheme.typography.bodySmall, color = PosPalette.Secondary)
                                        StatusPill(when {
                                            sale.externalPayments.isNotEmpty() -> "External payment not confirmed"
                                            sale.creditMinor > 0 -> "Part paid / pay later at sale"
                                            sale.exchange != null -> "Replacement sale"
                                            else -> "Paid in cash"
                                        },
                                            if (sale.creditMinor > 0 || sale.externalPayments.isNotEmpty()) NoticeTone.Warning else NoticeTone.Neutral)
                                    }
                                }
                            }
                        }
                    }
                }
                if (selected != null && receipt != null) {
                    Surface(Modifier.then(if (compact) Modifier.weight(1f) else Modifier.width(400.dp)).fillMaxHeight(),
                        color = PosPalette.Surface, shape = MaterialTheme.shapes.large) {
                        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text("Saved receipt", Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                                TextButton({ selectedId = null }) { Text(if (compact) "Back to sales" else "Close") }
                            }
                            SelectionContainer(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState())) {
                                Text(receipt, style = MaterialTheme.typography.bodySmall)
                            }
                            HorizontalDivider(color = PosPalette.Border)
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                NeutralButton("Print receipt", {
                                    outputBusy = true
                                    scope.launch {
                                        try {
                                            val submitted = withContext(Dispatchers.IO) { ReceiptService.printReceipt(receipt) }
                                            noticeError = false
                                            notice = if (submitted) "Receipt sent to the printer. Check the printer for delivery." else "Printing cancelled. The sale is unchanged."
                                        } catch (e: Exception) { noticeError = true; notice = e.message ?: "Printing failed. You can export the receipt instead." }
                                        finally { outputBusy = false }
                                    }
                                }, Modifier.weight(1f), enabled = !outputBusy)
                                NeutralButton("Export text", {
                                    outputBusy = true
                                    scope.launch {
                                        try {
                                            val file = withContext(Dispatchers.IO) { ReceiptService.exportReceipt(receipt) }
                                            noticeError = false
                                            notice = file?.let { "Receipt exported to ${it.absolutePath}" } ?: "Export cancelled. The sale is unchanged."
                                        } catch (e: Exception) { noticeError = true; notice = e.message ?: "The receipt could not be exported." }
                                        finally { outputBusy = false }
                                    }
                                }, Modifier.weight(1f), enabled = !outputBusy)
                            }
                            NeutralButton("Export PDF", {
                                outputBusy = true
                                scope.launch {
                                    try {
                                        val file = withContext(Dispatchers.IO) {
                                            PdfReceiptService.exportReceipt(receipt, profileResult.getOrNull()?.details?.receiptPaper ?: "A4",repo.receiptImageBytes(tenantId,selected.id))
                                        }
                                        noticeError = false
                                        notice = file?.let { "Receipt PDF saved to ${it.absolutePath}" } ?: "PDF export cancelled. The sale is unchanged."
                                    } catch (e: Exception) {
                                        noticeError = true
                                        notice = e.message ?: "The receipt PDF could not be saved."
                                    } finally { outputBusy = false }
                                }
                            }, Modifier.fillMaxWidth(), enabled = !outputBusy)
                            NeutralButton("Sale documents", { showingDocuments = true }, Modifier.fillMaxWidth(), enabled = !outputBusy)
                            if (selected.externalPayments.isNotEmpty()) {
                                Text("Returns involving external payment portions are not available until original-method refund reconciliation is implemented.",
                                    style = MaterialTheme.typography.bodySmall, color = PosPalette.Secondary)
                            } else if (!allowReturns) {
                                Text("A manager or owner must sign in to record returned items or refunds.",style = MaterialTheme.typography.bodySmall,color = PosPalette.Secondary)
                            } else if(selected.creditMinor>0 && creditAllowance?.isSuccess!=true) {
                                Text(creditAllowance?.exceptionOrNull()?.message ?: "Credit-return eligibility could not be loaded. Refresh before continuing.",style=MaterialTheme.typography.bodySmall,color=PosPalette.Secondary)
                                NeutralButton("Refresh return eligibility",{revision++})
                            } else if (returnable.values.any { it > 0 }) {
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    NeutralButton("Return items", { returning = true }, Modifier.weight(1f), enabled = !outputBusy)
                                    if (selected.creditMinor == 0L) NeutralButton("Exchange items", { exchanging = true },
                                        Modifier.weight(1f), enabled = !outputBusy)
                                }
                                Text("Cash refunds require an open shift with enough cash.", style = MaterialTheme.typography.bodySmall, color = PosPalette.Secondary)
                            } else Text("All items from this sale have been returned.", style = MaterialTheme.typography.bodySmall, color = PosPalette.Secondary)
                        }
                    }
                } else if (!compact) {
                    Surface(Modifier.width(400.dp).fillMaxHeight(), color = PosPalette.Surface, shape = MaterialTheme.shapes.large) {
                        EmptyState("Choose a sale", "Its original items, payment amounts and any returns will appear here.")
                    }
                }
            }
        }
    }
    if (showingDocuments && selected != null) {
        SaleDocumentsDialog(
            repo, tenant, selected, profileResult.getOrNull(), returns,
            canPrepareDelivery = returnable.values.any { it > 0 },
            dismiss = { showingDocuments = false },
        )
    }
    if (allowReturns && exchanging && selected != null) {
        CashExchangeDialog(repo, selected, returnable, dismiss = { exchanging = false }) { exchange ->
            exchanging = false
            selectedId = exchange.replacementReceiptId
            revision++
            onChanged()
            noticeError = false
            notice = "Exchange saved on this computer. Replacement receipt ${exchange.replacementReceiptId.take(8)}."
        }
    }
    if (allowReturns && returning && selected != null) {
        ReturnItemsDialog(selected, returnable, creditAllowance?.getOrNull(), { returning = false }) { quantities, restock, reason, operation ->
            val saved=if(selected.creditMinor>0) repo.returnCreditItemsReviewed(tenantId,selected.id,quantities,restock,reason,operation,requireNotNull(creditAllowance?.getOrNull()))
                else repo.returnItems(tenantId, selected.id, quantities, restock, reason, operation)
            returning = false
            revision++
            noticeError = false
            notice = "Return saved: ${money(saved.creditReductionMinor)} debt reduced · ${money(saved.refundMinor)} cash refunded. Original sale history is preserved."
            onChanged()
        }
    }
}

@Composable
private fun SaleDocumentsDialog(
    repo: PosRepository,
    tenant: Tenant,
    sale: Sale,
    profile: ReceiptProfileSnapshot?,
    returns: List<SaleReturn>,
    canPrepareDelivery: Boolean,
    dismiss: () -> Unit,
) {
    var selection by remember(sale.id) { mutableStateOf<Pair<PdfDocumentKind, String>?>(null) }
    var error by remember(sale.id) { mutableStateOf<String?>(null) }
    var exported by remember(sale.id) { mutableStateOf<String?>(null) }
    var busy by remember(sale.id) { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val customerResult = remember(sale.id) {
        runCatching {
            sale.customerId?.let { customerId -> repo.customers(tenant.id).find { it.id == customerId } }
        }
    }
    fun preview(kind: PdfDocumentKind, format: (Customer?) -> String) {
        error = null
        exported = null
        runCatching { format(customerResult.getOrThrow()) }
            .onSuccess { selection = kind to it }
            .onFailure { error = it.message ?: "The document could not be loaded." }
    }
    val document = selection
    AlertDialog(
        onDismissRequest = { if (!busy) dismiss() },
        title = { Text(document?.first?.label?.replaceFirstChar { it.uppercaseChar() } ?: "Sale documents") },
        text = {
            Column(Modifier.heightIn(max = 480.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (document == null) {
                    Text("Choose a document for receipt ${sale.id.take(8)}.")
                    LazyColumn(Modifier.weight(1f, fill = false), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        item {
                            NeutralButton("Sales invoice", {
                                preview(PdfDocumentKind.SALES_INVOICE) { SaleDocumentService.invoice(tenant, sale, profile, it) }
                            }, Modifier.fillMaxWidth())
                        }
                        if (canPrepareDelivery) item {
                            NeutralButton("Delivery note", {
                                preview(PdfDocumentKind.DELIVERY_NOTE) { SaleDocumentService.deliveryNote(tenant, sale, returns, profile, it) }
                            }, Modifier.fillMaxWidth())
                        }
                        if (returns.isNotEmpty()) item {
                            Text("Credit notes for saved returns", style = MaterialTheme.typography.titleSmall)
                        }
                        items(returns, key = { it.id }) { returned ->
                            NeutralButton("Return ${returned.id.take(8)} · ${money(returned.returnedValueMinor)}", {
                                preview(PdfDocumentKind.CREDIT_NOTE) { SaleDocumentService.creditNote(tenant, sale, returned, profile, it) }
                            }, Modifier.fillMaxWidth())
                        }
                    }
                } else {
                    SelectionContainer(Modifier.weight(1f, fill = false).fillMaxWidth().verticalScroll(rememberScrollState())) {
                        Text(document.second, style = MaterialTheme.typography.bodySmall)
                    }
                }
                error?.let { InlineNotice("Document unavailable", it, NoticeTone.Danger) }
                exported?.let { InlineNotice("Document export", it, NoticeTone.Neutral) }
            }
        },
        confirmButton = {
            if (document == null) TextButton(dismiss) { Text("Close") }
            else PosButton(if (busy) "Saving PDF…" else "Save PDF", {
                if (!busy) {
                    busy = true
                    error = null
                    exported = null
                    scope.launch {
                        try {
                            val file = withContext(Dispatchers.IO) {
                                PdfDocumentService.export(
                                    document.second, document.first, logoPng = repo.receiptImageBytes(tenant.id, sale.id),
                                )
                            }
                            exported = file?.let { "PDF saved to ${it.absolutePath}" } ?: "Export cancelled."
                        } catch (e: Exception) {
                            error = e.message ?: "The PDF could not be saved."
                        } finally {
                            busy = false
                        }
                    }
                }
            }, enabled = !busy)
        },
        dismissButton = {
            if (document != null) TextButton({
                selection = null
                error = null
                exported = null
            }, enabled = !busy) { Text("Back to documents") }
        },
    )
}

@Composable
private fun ReturnItemsDialog(
    sale: Sale,
    returnable: Map<String, Int>,
    allowance:CreditReturnAllowance?,
    dismiss: () -> Unit,
    save: (Map<String, Int>, Boolean, String, String) -> Unit,
) {
    val quantities = remember(sale.id) { mutableStateMapOf<String, String>() }
    var restock by remember(sale.id) { mutableStateOf(false) }
    var reason by remember(sale.id) { mutableStateOf("") }
    var error by remember(sale.id) { mutableStateOf<String?>(null) }
    var saving by remember { mutableStateOf(false) }
    val operation = remember(sale.id) { UUID.randomUUID().toString() }
    val preview = runCatching {
        sale.lines.fold(0L) { sum, line ->
            val input = quantities[line.key].orEmpty()
            val count = if (input.isBlank()) 0 else quantityCount(input,line.quantityPrecision) ?: error("Invalid quantity")
            require(count in 0..(returnable[line.key] ?: 0))
            Math.addExact(sum, line.cumulativeRefundMinor(line.quantity-(returnable[line.key] ?: 0)+count)-line.cumulativeRefundMinor(line.quantity-(returnable[line.key] ?: 0)))
        }
    }.getOrNull()
    AlertDialog(onDismissRequest = { if (!saving) dismiss() }, title = { Text("Return items · ${sale.id.take(8)}") },
        text = {
            Column(Modifier.heightIn(max = 460.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Enter only the goods being returned. Blank quantities mean no return for that item.", color = PosPalette.Secondary)
                sale.lines.filter { (returnable[it.key] ?: 0) > 0 }.forEach { line ->
                    PosTextField(quantities[line.key].orEmpty(), { quantities[line.key] = it },
                        "${line.product.name} (${line.sellingUnit})", hint = "Up to ${quantityLabel((returnable[line.key] ?: 0).toLong(),line.quantityPrecision)} ${line.sellingUnit}")
                }
                PosTextField(reason, { reason = it }, "Why are the goods being returned?", singleLine = false)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(restock, { restock = it })
                    Text("Add these goods back to sellable stock", Modifier.weight(1f))
                }
                Text("Leave unchecked for damaged or unsellable goods. This choice applies to all goods in this return.",
                    style = MaterialTheme.typography.bodySmall, color = PosPalette.Secondary)
                LabelValueRow("Returned goods value", preview?.let(::money) ?: "Check quantities", emphasized = true)
                if(sale.creditMinor>0) {
                    LabelValueRow("Unpaid credit on this receipt",money(requireNotNull(allowance).outstandingCreditMinor))
                    LabelValueRow("Customer debt reduced",preview?.let {money(minOf(it,allowance.outstandingCreditMinor))} ?: "Check quantities",emphasized=true)
                    LabelValueRow("Cash refund",preview?.let {money(maxOf(0L,it-allowance.outstandingCreditMinor))} ?: "Check quantities")
                    Text("Unpaid receipt credit is reduced first; the paid part is refunded in cash. Repayments must be assigned before returning. No customer advance is created.",style=MaterialTheme.typography.bodySmall)
                } else LabelValueRow("Cash refund", preview?.let(::money) ?: "Check quantities", emphasized = true)
                Text("Any cash refund leaves the current open shift and requires enough recorded cash. A debt-only return does not need a drawer. The original sale remains unchanged.", style = MaterialTheme.typography.bodySmall, color = PosPalette.Secondary)
                error?.let { InlineNotice("Return not saved", it, NoticeTone.Danger) }
            }
        },
        confirmButton = {
            PosButton(if (saving) "Saving return…" else if(sale.creditMinor>0) "Save reviewed return" else "Save return & cash refund", {
                saving = true
                try {
                    val entries = sale.lines.associate { line ->
                        val input = quantities[line.key].orEmpty().trim()
                        val quantity = if (input.isBlank()) 0 else quantityCount(input,line.quantityPrecision)
                            ?: throw IllegalArgumentException("Enter return quantities using the item’s saved decimal places.")
                        require(quantity in 0..(returnable[line.key] ?: 0)) { "Return quantity exceeds the items still available to return." }
                        line.key to quantity
                    }.filterValues { it > 0 }
                    require(entries.isNotEmpty()) { "Enter at least one item to return." }
                    require(reason.isNotBlank()) { "Enter why the goods are being returned." }
                    save(entries, restock, reason.trim(), operation)
                } catch (e: Exception) { error = e.message ?: "The return could not be saved. Your entries are still here." }
                finally { saving = false }
            }, enabled = !saving)
        },
        dismissButton = { TextButton(dismiss, enabled = !saving) { Text("Cancel") } },
    )
}
