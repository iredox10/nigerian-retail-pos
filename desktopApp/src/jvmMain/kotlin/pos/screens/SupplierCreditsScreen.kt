package pos.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import pos.data.*
import pos.domain.*
import pos.output.receiptDate
import pos.ui.components.*
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID

private data class SupplierCreditsView(val balances: List<SupplierCreditBalance>, val profiles: List<SupplierProfile>,
    val allocations: List<SupplierCreditAllocation>, val voids: List<SupplierCreditVoid>)

@Composable
fun SupplierCreditsScreen(repo: PosRepository, tenantId: String, onChanged: () -> Unit) = key(tenantId) {
    var revision by remember { mutableIntStateOf(0) }
    var creating by remember { mutableStateOf(false) }
    var applying by remember { mutableStateOf<SupplierCreditBalance?>(null) }
    var correcting by remember { mutableStateOf<SupplierCreditAllocation?>(null) }
    var voiding by remember { mutableStateOf<SupplierCreditBalance?>(null) }
    var notice by remember { mutableStateOf<String?>(null) }
    val loaded = remember(revision) { runCatching {
        SupplierCreditsView(repo.supplierCreditBalances(tenantId), repo.supplierProfiles(tenantId, true), repo.supplierCreditAllocations(tenantId), repo.supplierCreditVoids(tenantId))
    } }
    fun refresh(message: String) { revision++; notice = message; onChanged() }
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            SectionHeader("Supplier credit notes", "Record credit agreed with a supplier and apply it to an unpaid purchase.", Modifier.weight(1f))
            PosButton("Record credit note", { creating = true })
        }
        InlineNotice("Merchant-recorded supplier credit", "Keep the supplier's reference and reason. Applying credit reduces a linked purchase balance.", NoticeTone.Info)
        notice?.let { InlineNotice(it, tone = NoticeTone.Success) }
        loaded.exceptionOrNull()?.let { InlineNotice("Supplier credits unavailable", it.message.orEmpty(), NoticeTone.Danger) }
        loaded.getOrNull()?.let { (balances, profiles, events, voids) ->
            LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                if (balances.isEmpty()) item { EmptyState("No supplier credit notes", "Record a credit note using the supplier's reference and agreed amount.") }
                items(balances.asReversed(), key = { it.note.id }) { balance ->
                    val note = balance.note
                    Surface(shape = MaterialTheme.shapes.medium) {
                        Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            Text("${profiles.singleOrNull { it.id == note.supplierId }?.details?.name ?: note.supplierId} · ${note.reference}", style = MaterialTheme.typography.titleMedium)
                            Text("Supplier date ${note.issuedOn} · Recorded ${receiptDate(note.createdAt)}", style = MaterialTheme.typography.bodySmall)
                            StatusPill(if (balance.voided) "Voided" else if (balance.availableMinor == 0L) "Fully applied" else "Credit available")
                            LabelValueRow("Original credit", money(note.amountMinor))
                            LabelValueRow("Applied to purchases", money(balance.appliedMinor))
                            LabelValueRow("Available credit", money(balance.availableMinor), emphasized = true)
                            Text(note.reason, style = MaterialTheme.typography.bodySmall)
                            voids.singleOrNull { it.creditId == note.id }?.let { Text("Voided ${receiptDate(it.createdAt)} · ${it.reason}", style = MaterialTheme.typography.bodySmall) }
                            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                if (!balance.voided && balance.availableMinor > 0) NeutralButton("Apply to purchase", { applying = balance })
                                if (!balance.voided && balance.appliedMinor == 0L) NeutralButton("Void unused note", { voiding = balance })
                            }
                            val history = events.filter { it.creditId == note.id }
                            if (history.isNotEmpty()) {
                                HorizontalDivider()
                                Text("Application history", style = MaterialTheme.typography.titleSmall)
                                history.forEach { event ->
                                    Text("${if (event.reversalOf == null) "Applied" else "Application reversed"} ${money(event.amountMinor)} · Purchase ${event.receiptId.take(8)} · ${receiptDate(event.createdAt)}")
                                    Text(event.reason, style = MaterialTheme.typography.bodySmall)
                                    if (event.reversalOf == null && history.none { it.reversalOf == event.id }) TextButton({ correcting = event }) { Text("Undo application") }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
    if (creating) RecordSupplierCreditDialog(repo, tenantId, { creating = false }) {
        creating = false; refresh("Supplier credit note saved")
    }
    applying?.let { balance -> ApplySupplierCreditDialog(repo, tenantId, balance, { applying = null }) {
        applying = null; refresh("Credit applied to the purchase")
    } }
    correcting?.let { event -> CreditCorrectionDialog("Undo credit application", "This restores ${money(event.amountMinor)} to purchase ${event.receiptId.take(8)} and makes the credit available again.",
        { correcting = null }, { reason, operation -> repo.reverseSupplierCreditApplication(tenantId, event.id, reason, operation) }) {
        correcting = null; refresh("Credit application reversed with history")
    } }
    voiding?.let { balance -> CreditCorrectionDialog("Void unused credit note", "The ${money(balance.note.amountMinor)} note will remain in history with your reason.",
        { voiding = null }, { reason, operation -> repo.voidSupplierCredit(tenantId, balance.note.id, reason, operation) }) {
        voiding = null; refresh("Unused supplier credit note voided")
    } }
}

@Composable
private fun RecordSupplierCreditDialog(repo: PosRepository, tenantId: String, dismiss: () -> Unit, saved: () -> Unit) {
    val suppliers = remember { runCatching { repo.supplierProfiles(tenantId) } }
    var supplierId by remember { mutableStateOf<String?>(null) }
    var menu by remember { mutableStateOf(false) }
    var reference by remember { mutableStateOf("") }
    var date by remember { mutableStateOf(LocalDate.now(ZoneId.of("Africa/Lagos")).toString()) }
    var amount by remember { mutableStateOf("") }
    var reason by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    val operation = remember(supplierId, reference, date, amount, reason) { UUID.randomUUID().toString() }
    AlertDialog(onDismissRequest = dismiss, title = { Text("Record supplier credit note") }, text = {
        Column(Modifier.widthIn(min = 380.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            suppliers.exceptionOrNull()?.let { InlineNotice("Suppliers unavailable", it.message.orEmpty(), NoticeTone.Danger) }
            Box {
                NeutralButton(suppliers.getOrDefault(emptyList()).singleOrNull { it.id == supplierId }?.details?.name ?: "Choose supplier", { menu = true })
                DropdownMenu(menu, { menu = false }) {
                    suppliers.getOrDefault(emptyList()).forEach { supplier -> DropdownMenuItem(text = { Text(supplier.details.name) }, onClick = { supplierId = supplier.id; menu = false }) }
                }
            }
            PosTextField(reference, { reference = it }, "Supplier credit-note reference")
            PosTextField(date, { date = it }, "Credit-note date (WAT)", hint = "YYYY-MM-DD")
            PosTextField(amount, { amount = it }, "Credit amount (₦)")
            PosTextField(reason, { reason = it }, "Why did the supplier grant this credit?")
            error?.let { InlineNotice("Credit not saved", it, NoticeTone.Danger) }
        }
    }, confirmButton = { PosButton("Save credit note", {
        try { repo.recordSupplierCredit(tenantId, requireNotNull(supplierId) { "Choose a supplier." }, reference, date,
            requireNotNull(parseNaira(amount)) { "Enter a valid naira amount." }, reason, operation); saved() }
        catch (e: Exception) { error = e.message }
    }) }, dismissButton = { TextButton(dismiss) { Text("Cancel") } })
}

@Composable
private fun ApplySupplierCreditDialog(repo: PosRepository, tenantId: String, balance: SupplierCreditBalance, dismiss: () -> Unit, saved: () -> Unit) {
    val receipts = remember { runCatching {
        val ids = repo.supplierReceiptLinks(tenantId).filter { it.supplierId == balance.note.supplierId }.map { it.receiptId }.toSet()
        repo.stockReceipts(tenantId).filter { it.id in ids }.map { it to repo.supplierOutstanding(tenantId, it.id) }.filter { it.second > 0 }
    } }
    var receiptId by remember { mutableStateOf<String?>(null) }
    var menu by remember { mutableStateOf(false) }
    var amount by remember { mutableStateOf("") }
    var reason by remember { mutableStateOf("") }
    var review by remember { mutableStateOf<SupplierCreditReview?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    val operation = remember(receiptId, amount, reason) { UUID.randomUUID().toString() }
    AlertDialog(onDismissRequest = dismiss, title = { Text("Apply supplier credit") }, text = {
        Column(Modifier.widthIn(min = 380.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("${balance.note.reference} · Available ${money(balance.availableMinor)}")
            receipts.exceptionOrNull()?.let { InlineNotice("Purchases unavailable", it.message.orEmpty(), NoticeTone.Danger) }
            if (receipts.getOrDefault(emptyList()).isEmpty()) Text("No unpaid purchase is linked to this supplier. Link the purchase under the supplier account first.")
            Box {
                NeutralButton(receiptId?.let { "Purchase ${it.take(8)}" } ?: "Choose unpaid purchase", { menu = true })
                DropdownMenu(menu, { menu = false }) {
                    receipts.getOrDefault(emptyList()).forEach { (receipt, owed) -> DropdownMenuItem(text = { Text("${receipt.productName} · ${receipt.id.take(8)} · ${money(owed)} owed") },
                        onClick = { receiptId = receipt.id; review = null; menu = false }) }
                }
            }
            PosTextField(amount, { amount = it; review = null }, "Credit to apply (₦)")
            PosTextField(reason, { reason = it; review = null }, "Reason for applying credit")
            review?.let {
                LabelValueRow("Purchase owed before", money(it.owedBeforeMinor))
                LabelValueRow("Credit applied", money(it.amountMinor))
                LabelValueRow("Purchase owed after", money(it.owedBeforeMinor - it.amountMinor), emphasized = true)
                LabelValueRow("Credit remaining", money(it.availableBeforeMinor - it.amountMinor))
            }
            error?.let { InlineNotice("Credit not applied", it, NoticeTone.Danger) }
        }
    }, confirmButton = { PosButton(if (review == null) "Review application" else "Apply credit", {
        try {
            val checked = review
            if (checked == null) review = repo.reviewSupplierCredit(tenantId, balance.note.id,
                requireNotNull(receiptId) { "Choose a purchase." }, requireNotNull(parseNaira(amount)) { "Enter a valid naira amount." })
            else { repo.applySupplierCredit(tenantId, checked, reason, operation); saved() }
            error = null
        } catch (e: Exception) { error = e.message; review = null }
    }) }, dismissButton = { TextButton(dismiss) { Text("Cancel") } })
}

@Composable
private fun CreditCorrectionDialog(title: String, detail: String, dismiss: () -> Unit,
    commit: (String, String) -> Any, saved: () -> Unit) {
    var reason by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    val operation = remember(reason) { UUID.randomUUID().toString() }
    AlertDialog(onDismissRequest = dismiss, title = { Text(title) }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(detail)
            PosTextField(reason, { reason = it }, "Reason for this correction")
            error?.let { InlineNotice("Correction not saved", it, NoticeTone.Danger) }
        }
    }, confirmButton = { PosButton("Save correction", {
        try { commit(reason, operation); saved() } catch (e: Exception) { error = e.message }
    }) }, dismissButton = { TextButton(dismiss) { Text("Cancel") } })
}
