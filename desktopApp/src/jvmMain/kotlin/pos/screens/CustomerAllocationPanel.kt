package pos.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import pos.data.PosRepository
import pos.data.creditReturnReductions
import pos.data.DebtAdjustment
import pos.data.debtAdjustments
import pos.data.netWrittenOff
import pos.domain.CreditReturnReduction
import pos.domain.Customer
import pos.domain.AllocationReversal
import pos.domain.CustomerRepayment
import pos.domain.RepaymentAllocation
import pos.domain.Sale
import pos.domain.money
import pos.domain.parseNaira
import pos.ui.components.*
import java.math.BigDecimal
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.UUID

private data class AllocationRecords(
    val customer: Customer,
    val payments: List<CustomerRepayment>,
    val sales: List<Sale>,
    val allocations: List<RepaymentAllocation>,
    val history: List<RepaymentAllocation>,
    val reversals: List<AllocationReversal>,
    val creditReturns:List<CreditReturnReduction>,
    val adjustments: List<DebtAdjustment>,
) {
    fun paymentAvailable(id: String): Long {
        val original = payments.single { it.id == id }.amountMinor
        val assigned = allocations.filter { it.repaymentId == id }.fold(0L) { sum, row -> Math.addExact(sum, row.amountMinor) }
        return Math.subtractExact(original, assigned).also { require(it >= 0) { "Saved assignments exceed a payment. Refresh before continuing." } }
    }
    fun receiptBalance(id: String): Long {
        val original = sales.single { it.id == id }.creditMinor
        val assigned = allocations.filter { it.saleId == id }.fold(0L) { sum, row -> Math.addExact(sum, row.amountMinor) }
        val returned=creditReturns.filter {it.saleId==id}.fold(0L) {sum,row ->Math.addExact(sum,row.amountMinor)}
        val writtenOff = adjustments.filter { it.review.saleId == id }.netWrittenOff()
        return Math.subtractExact(Math.subtractExact(Math.subtractExact(original, assigned), returned), writtenOff)
            .also { require(it >= 0) { "Saved assignments and debt reductions exceed this receipt’s credit. Refresh before continuing." } }
    }
    val unassigned: Long get() = payments.fold(0L) { sum, payment -> Math.addExact(sum, paymentAvailable(payment.id)) }
}

/** Assigning previously received money changes receipt coverage, never cash or the customer's debt. */
@Composable
internal fun ColumnScope.CustomerAllocationPanel(repo: PosRepository, tenantId: String, customer: Customer, onChanged: () -> Unit) {
    key(tenantId, customer.id) {
        var revision by remember { mutableIntStateOf(0) }
        var paymentId by remember { mutableStateOf<String?>(null) }
        var saleId by remember { mutableStateOf<String?>(null) }
        var amountText by remember { mutableStateOf("") }
        var error by remember { mutableStateOf<String?>(null) }
        var message by remember { mutableStateOf<String?>(null) }
        var saving by remember { mutableStateOf(false) }
        var undoing by remember { mutableStateOf<RepaymentAllocation?>(null) }
        var undoReason by remember { mutableStateOf("") }
        var undoError by remember { mutableStateOf<String?>(null) }
        var undoSaving by remember { mutableStateOf(false) }
        var undoOperationId by remember { mutableStateOf(UUID.randomUUID().toString()) }
        val operationId = remember(paymentId, saleId, amountText) { UUID.randomUUID().toString() }
        val loaded = remember(customer, revision) { runCatching {
            val current = repo.customers(tenantId).single { it.id == customer.id }
            val records = AllocationRecords(current, repo.repayments(tenantId, customer.id),
                repo.sales(tenantId).filter { it.customerId == customer.id && it.creditMinor > 0 },
                repo.repaymentAllocations(tenantId, customer.id),
                repo.repaymentAllocations(tenantId, customer.id, includeReversed = true),
                repo.allocationReversals(tenantId, customer.id),repo.creditReturnReductions(tenantId,customer.id),
                repo.debtAdjustments(tenantId, customer.id))
            // Validate every displayed available amount before showing an assignment form.
            val totalReceipts = records.sales.fold(0L) { sum, sale -> Math.addExact(sum, records.receiptBalance(sale.id)) }
            require(Math.subtractExact(totalReceipts, records.unassigned) == current.balanceMinor) { "Customer debt does not match receipt and payment history. Refresh before assigning payments." }
            records
        } }
        val records = loaded.getOrNull()
        val payment = records?.payments?.find { it.id == paymentId }
        val sale = records?.sales?.find { it.id == saleId }
        val paymentAvailable = payment?.let { requireNotNull(records).paymentAvailable(it.id) }
        val receiptBalance = sale?.let { requireNotNull(records).receiptBalance(it.id) }
        val amount = parseNaira(amountText)
        val maximum = if (paymentAvailable != null && receiptBalance != null) minOf(paymentAvailable, receiptBalance) else null
        LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            item { SectionHeader("Assign payments to receipts", "${customer.name} · Saved payments only · This shop") }
            loaded.exceptionOrNull()?.let { failure -> item { InlineNotice("Assignments could not be loaded", failure.message ?: "Refresh to try again.", NoticeTone.Danger) } }
            error?.let { item { InlineNotice("Assignment not saved", it, NoticeTone.Danger) } }
            message?.let { item { InlineNotice(it, tone = NoticeTone.Success) } }
            item {
                NeutralButton("Refresh payments & receipts", { revision++; error = null; message = null })
            }
            if (records != null) {
                item {
                    Surface(shape = MaterialTheme.shapes.medium) {
                        Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            LabelValueRow("Current customer debt", money(records.customer.balanceMinor), emphasized = true)
                            LabelValueRow("Payments not assigned to receipts", money(records.unassigned))
                            Text("Assign money already recorded as received. This does not collect money again, change cash, reduce total debt again, or edit the original sale.", style = MaterialTheme.typography.bodySmall)
                            if (records.unassigned > 0) InlineNotice("Some payments still need assigning", "Receipt balances below are before these unassigned payments. Choose the receipts the customer paid for; no payment order is assumed.", NoticeTone.Warning)
                            else Text("All recorded payments have been assigned. Receipt balances now show the credit still unpaid on each receipt.", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
                item { SectionHeader("1. Choose a recorded payment", "Only the part not yet assigned can be used") }
                if (records.payments.isEmpty()) item { EmptyState("No payments recorded yet", "Return to customer history and record cash received before assigning a payment.") }
                items(if (payment != null) listOf(payment) else records.payments, key = { "payment:${it.id}" }) { choice ->
                    val available = records.paymentAvailable(choice.id)
                    Surface(shape = MaterialTheme.shapes.small) {
                        Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            Text("Payment ${choice.id.takeLast(8).uppercase()} · ${allocationTime(choice.createdAt)}", fontWeight = FontWeight.SemiBold)
                            LabelValueRow("Cash originally received", money(choice.amountMinor))
                            LabelValueRow("Available to assign", money(available), emphasized = true)
                            if (available > 0) NeutralButton(if (paymentId == choice.id) "Change payment" else "Use this payment", {
                                paymentId = if (paymentId == choice.id) null else choice.id; saleId = null; amountText = ""; error = null; message = null
                            }) else StatusPill("Fully assigned", NoticeTone.Neutral)
                        }
                    }
                }
                if (payment != null) {
                    item { SectionHeader("2. Choose a credit receipt", "Payment ${payment.id.takeLast(8).uppercase()} · ${money(paymentAvailable ?: 0)} available") }
                    if (records.sales.isEmpty()) item { EmptyState("No credit receipts available", "This customer's original credit sales will appear here.") }
                    items(if (sale != null) listOf(sale) else records.sales, key = { "sale:${it.id}" }) { choice ->
                        val balance = records.receiptBalance(choice.id)
                        Surface(shape = MaterialTheme.shapes.small) {
                            Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                Text("Receipt ${choice.id.takeLast(8).uppercase()} · ${allocationTime(choice.createdAt)}", fontWeight = FontWeight.SemiBold)
                                Text(choice.lines.joinToString(" · ") { "${it.quantityLabel} ${it.sellingUnit} ${it.product.name}" }, style = MaterialTheme.typography.bodySmall)
                                LabelValueRow("Credit on original sale", money(choice.creditMinor))
                                LabelValueRow(if (records.unassigned > 0) "Receipt balance before unassigned payments" else "Unpaid credit on receipt", money(balance), emphasized = true)
                                Text(choice.dueDate?.let { "Agreed repayment due date: $it · WAT" } ?: "No repayment due date recorded", style = MaterialTheme.typography.bodySmall)
                                if (balance > 0 && (paymentAvailable ?: 0) > 0) NeutralButton(if (saleId == choice.id) "Change receipt" else "Use this receipt", {
                                    saleId = if (saleId == choice.id) null else choice.id; amountText = ""; error = null; message = null
                                }) else StatusPill(if (balance == 0L) "Original credit fully covered" else "No amount available on selected payment", NoticeTone.Neutral)
                            }
                        }
                    }
                }
                if (payment != null && sale != null && maximum != null) item {
                    Surface(shape = MaterialTheme.shapes.medium) {
                        Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                            SectionHeader("3. How much covers this receipt?", "Payment ${payment.id.takeLast(8).uppercase()} → Receipt ${sale.id.takeLast(8).uppercase()}")
                            LabelValueRow("Maximum available for this assignment", money(maximum))
                            PosTextField(amountText, { amountText = it; error = null }, "Amount to assign in naira", hint = "For example, 2500.00")
                            TextButton({ amountText = BigDecimal.valueOf(maximum, 2).toPlainString(); error = null }) { Text("Use maximum amount") }
                            if (amount != null && amount > 0 && amount <= maximum) {
                                LabelValueRow("Still unassigned on this payment", money(requireNotNull(paymentAvailable) - amount))
                                LabelValueRow("Receipt balance after this assignment", money(requireNotNull(receiptBalance) - amount))
                                LabelValueRow("Customer debt stays the same", money(records.customer.balanceMinor), emphasized = true)
                            }
                            Text("Check the payment, receipt and amount before saving. To correct an assignment later, undo it in Saved assignments, then assign the money to the right receipt. The original link stays in history.", style = MaterialTheme.typography.bodySmall)
                            error?.let { InlineNotice("Assignment not saved", it, NoticeTone.Danger) }
                            PosButton(if (saving) "Saving assignment…" else "Save payment assignment", {
                                saving = true
                                try {
                                    val value = requireNotNull(parseNaira(amountText)) { "Enter a valid naira amount with at most two decimal places." }
                                    require(value > 0 && value <= maximum) { "Assign an amount greater than zero and no more than ${money(maximum)}." }
                                    repo.allocateRepayment(tenantId, payment.id, sale.id, value, operationId)
                                    revision++; paymentId = null; saleId = null; amountText = ""; error = null
                                    message = "Payment assignment saved on this computer. Customer debt and cash are unchanged."
                                    onChanged()
                                } catch (e: Exception) { error = e.message ?: "The assignment could not be saved. Your selections are still here." }
                                finally { saving = false }
                            }, enabled = !saving && maximum > 0)
                        }
                    }
                }
                item { SectionHeader("Saved assignments", "Historical links between payments and receipts") }
                if (records.history.isEmpty()) item { Text("No payments have been assigned to receipts yet.") }
                items(records.history, key = { "assignment:${it.id}" }) { assignment ->
                    val reversal = records.reversals.singleOrNull { it.allocationId == assignment.id }
                    Surface(shape = MaterialTheme.shapes.small) {
                        Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("Payment ${assignment.repaymentId.takeLast(8).uppercase()} → Receipt ${assignment.saleId.takeLast(8).uppercase()}", fontWeight = FontWeight.SemiBold)
                            LabelValueRow(allocationTime(assignment.createdAt), money(assignment.amountMinor))
                            if (reversal == null) {
                                StatusPill("Active assignment", NoticeTone.Neutral)
                                NeutralButton("Undo assignment", {
                                    undoing = assignment
                                    undoReason = ""
                                    undoError = null
                                    undoOperationId = UUID.randomUUID().toString()
                                }, enabled = !saving && !undoSaving)
                            } else {
                                StatusPill("Assignment undone", NoticeTone.Warning)
                                Text("Undone ${allocationTime(reversal.createdAt)}", style = MaterialTheme.typography.bodySmall)
                                Text("Reason: ${reversal.reason}", style = MaterialTheme.typography.bodySmall)
                                Text("This link no longer covers the receipt. The recorded payment and original assignment remain in history.", style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                }
            }
        }
        undoing?.let { assignment ->
            AlertDialog(
                onDismissRequest = { if (!undoSaving) undoing = null },
                title = { Text("Undo this payment assignment?") },
                text = {
                    Column(Modifier.heightIn(max = 460.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text(customer.name, fontWeight = FontWeight.SemiBold)
                        LabelValueRow("Payment", assignment.repaymentId.takeLast(8).uppercase())
                        LabelValueRow("Receipt", assignment.saleId.takeLast(8).uppercase())
                        LabelValueRow("Amount to make unassigned", money(assignment.amountMinor), emphasized = true)
                        Text("The cash already received and the customer's total debt stay the same. This amount will no longer cover this receipt. Choose the correct receipt and assign the payment again after saving.")
                        Text("The original assignment stays in history together with this correction and its reason.", style = MaterialTheme.typography.bodySmall)
                        PosTextField(undoReason, { undoReason = it; undoError = null }, "Why are you undoing this assignment?",
                            hint = "For example, payment linked to the wrong receipt", enabled = !undoSaving, singleLine = false)
                        undoError?.let { InlineNotice("Assignment not undone", it, NoticeTone.Danger) }
                    }
                },
                confirmButton = {
                    PosButton(if (undoSaving) "Saving correction…" else "Undo assignment", {
                        undoSaving = true
                        try {
                            val reason = undoReason.trim()
                            require(reason.length in 3..250 && reason.none { it.isISOControl() }) { "Enter a reason from 3 to 250 characters." }
                            repo.reverseRepaymentAllocation(tenantId, assignment.id, reason, undoOperationId)
                            revision++; paymentId = null; saleId = null; amountText = ""; error = null
                            message = "Assignment undone on this computer. ${money(assignment.amountMinor)} is available to assign again. Customer debt and cash are unchanged."
                            undoing = null
                            onChanged()
                        } catch (e: Exception) {
                            undoError = e.message ?: "The correction could not be saved. Your reason is still here."
                        } finally {
                            undoSaving = false
                        }
                    }, enabled = !undoSaving)
                },
                dismissButton = { TextButton({ undoing = null }, enabled = !undoSaving) { Text("Cancel") } },
            )
        }
    }
}

private fun allocationTime(value: String): String = runCatching {
    DateTimeFormatter.ofPattern("dd MMM yyyy, HH:mm 'WAT'").withZone(ZoneId.of("Africa/Lagos")).format(Instant.parse(value))
}.getOrDefault(value)
