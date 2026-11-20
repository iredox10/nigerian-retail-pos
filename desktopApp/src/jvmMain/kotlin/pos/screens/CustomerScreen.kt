package pos.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.horizontalScroll
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
import pos.data.creditReturnReductions
import pos.domain.Customer
import pos.domain.money
import pos.domain.parseNaira
import pos.output.CustomerStatement
import pos.output.CustomerStatementService
import pos.output.DatedCustomerStatementService
import pos.output.CsvService
import pos.output.DatedCustomerStatement
import pos.output.StatementPdfService
import java.io.File
import pos.ui.components.*
import java.time.Instant
import java.time.LocalDate
import java.math.BigDecimal
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.UUID

/** Customer history is local to the selected shop; original credit and later cash remain distinct. */
@Composable
fun CustomerWorkspace(repo: PosRepository, tenantId: String, onFollowUps: (() -> Unit)? = null, onChanged: () -> Unit) {
    key(tenantId) { TenantCustomers(repo, tenantId, onFollowUps, onChanged) }
}

@Composable
private fun TenantCustomers(repo: PosRepository, tenantId: String, onFollowUps: (() -> Unit)?, onChanged: () -> Unit) {
    var revision by remember { mutableIntStateOf(0) }
    var search by remember { mutableStateOf("") }
    var selectedId by remember { mutableStateOf<String?>(null) }
    var adding by remember { mutableStateOf(false) }
    var paying by remember { mutableStateOf<Customer?>(null) }
    var editing by remember { mutableStateOf<Customer?>(null) }
    var assigning by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    val result = remember(revision) { runCatching { repo.customers(tenantId) } }
    val customers = result.getOrDefault(emptyList())
    val selected = customers.find { it.id == selectedId }
    fun changed() { revision++; onChanged() }

    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        if (selected == null) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                SectionHeader("Customers", "Find a customer, see money owed, or open their history", Modifier.weight(1f))
                PosButton("Add customer", { adding = true; error = null })
            }
            PosTextField(search, { search = it }, "Search for a customer", Modifier.fillMaxWidth(), hint = "Customer name or phone number")
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("${customers.size} customers · This shop only", Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                if (onFollowUps != null) TextButton(onFollowUps) { Text("Repayment follow-ups") }
                NeutralButton("Refresh", { revision++ })
            }
        } else {
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton({ if (assigning) assigning = false else selectedId = null; error = null; message = null }) { Text(if (assigning) "Back to customer history" else "Back to customers") }
                Spacer(Modifier.weight(1f))
                NeutralButton("Refresh", { revision++; error = null; message = null })
            }
        }
        (error ?: result.exceptionOrNull()?.message)?.let { InlineNotice("Could not finish", it, NoticeTone.Danger) }
        message?.let { InlineNotice(it, tone = NoticeTone.Success) }
        if (selected == null && result.isSuccess) {
            val visible = customers.filter { it.name.contains(search, true) || it.phone?.contains(search, true) == true }
            LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (visible.isEmpty()) item {
                    EmptyState(if (customers.isEmpty()) "No customers yet" else "No matching customers", "Add a customer to record a credit sale, or try another name.")
                }
                items(visible, key = { it.id }) { customer ->
                    Surface(shape = MaterialTheme.shapes.small) {
                        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(customer.name, Modifier.weight(1f).padding(end = 16.dp), fontWeight = FontWeight.SemiBold)
                                Text(money(customer.balanceMinor), fontWeight = FontWeight.SemiBold)
                            }
                            Text(if (customer.balanceMinor > 0) "Customer owes your shop" else "No money owed", style = MaterialTheme.typography.bodySmall)
                            customer.phone?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                            Text(creditLimitLabel(customer.creditLimitMinor), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                NeutralButton("View history", { selectedId = customer.id; message = null; error = null })
                                if (customer.balanceMinor > 0) NeutralButton("Record payment", { paying = customer; message = null })
                            }
                        }
                    }
                }
            }
        }
        selected?.let { customer ->
            if (assigning) {
                CustomerAllocationPanel(repo, tenantId, customer) { changed() }
            } else {
                val statement = remember(customer, revision) { runCatching {
                    CustomerStatementService.build(tenantId, customer, repo.sales(tenantId), repo.repayments(tenantId),repo.creditReturnReductions(tenantId))
                } }
                statement.exceptionOrNull()?.let { InlineNotice("History could not be loaded", it.message ?: "Refresh to try again.", NoticeTone.Danger) }
                statement.getOrNull()?.let { history ->
                    CustomerHistory(history, { paying = customer; message = null }, { editing = customer; message = null }, { assigning = true; message = null; error = null }, {
                        try {
                            val file = CsvService.save(CustomerStatementService.csv(history), "retail-pos-customer-${customer.id.take(8)}-statement.csv")
                            if (file != null) { message = "Customer statement saved to ${file.absolutePath}"; error = null }
                        } catch (e: Exception) { error = e.message ?: "Statement could not be saved. No records changed." }
                    }, {view -> StatementPdfService.exportCustomer(repo.tenants().single {it.id==tenantId},view)})
                }
            }
        }
    }
    if (adding) CustomerProfileDialog(null, { adding = false }) { name, phone, address, limit, _ ->
        repo.addCustomer(tenantId, name, phone, address, limit)
        adding = false; message = "Customer saved on this computer"; changed()
    }
    editing?.let { customer ->
        CustomerProfileDialog(customer, { editing = null }) { name, phone, address, limit, operationId ->
            repo.updateCustomer(tenantId, customer.id, name, phone, address, limit, operationId)
            editing = null; message = "Customer details and credit limit saved on this computer"; changed()
        }
    }
    paying?.let { customer ->
        CustomerPaymentDialog(repo, tenantId, customer, { paying = null }) {
            paying = null; message = "Customer payment saved on this computer"; changed()
        }
    }
}

@Composable
private fun ColumnScope.CustomerHistory(statement: CustomerStatement, pay: () -> Unit, edit: () -> Unit, assign: () -> Unit, export: () -> Unit, exportPdf: (DatedCustomerStatement) -> File?) {
    val customer = statement.customer
    var dated by remember(customer.id) { mutableStateOf(false) }
    val today=remember {LocalDate.now(ZoneId.of("Africa/Lagos"))}
    var from by remember(customer.id) {mutableStateOf(today.withDayOfMonth(1).toString())}
    var through by remember(customer.id) {mutableStateOf(today.toString())}
    var exportNotice by remember(customer.id) {mutableStateOf<String?>(null)}
    val period=remember(from,through,statement) {runCatching {
        require(from.matches(Regex("\\d{4}-\\d{2}-\\d{2}")) && through.matches(Regex("\\d{4}-\\d{2}-\\d{2}"))) {"Use dates as YYYY-MM-DD."}
        DatedCustomerStatementService.build(statement,ReportDateRange(LocalDate.parse(from),LocalDate.parse(through)))
    }}
    LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        item {
            Surface(shape = MaterialTheme.shapes.medium) {
                Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    SectionHeader(customer.name, "All saved dates · This shop only")
                    LabelValueRow("Phone", customer.phone ?: "Not recorded")
                    LabelValueRow("Address", customer.address ?: "Not recorded")
                    HorizontalDivider()
                    LabelValueRow("Customer still owes", money(customer.balanceMinor), emphasized = true)
                    LabelValueRow("Credit limit", when (val limit = customer.creditLimitMinor) { null -> "No limit set"; 0L -> "No new credit allowed"; else -> money(limit) })
                    customer.creditLimitMinor?.let { limit ->
                        LabelValueRow("Available for a new credit sale", money(if (customer.balanceMinor >= limit) 0 else limit - customer.balanceMinor))
                        if (customer.balanceMinor > limit) InlineNotice("Debt is above the credit limit", "Existing debt stays recorded. More credit is blocked until repayments reduce the debt or the owner changes the limit. Cash sales can continue.", NoticeTone.Warning)
                        else if (limit == 0L) Text("Cash sales and debt repayments remain available. New credit sales are blocked.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    LabelValueRow("Original sales total — before any cash returns", money(statement.originalSalesMinor))
                    LabelValueRow("Credit added on original sales", money(statement.creditGrantedMinor))
                    LabelValueRow("Cash debt repayments received later", money(statement.repaidMinor))
                    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        if (customer.balanceMinor > 0) PosButton("Record payment", pay)
                        NeutralButton("Assign payments to receipts", assign)
                        NeutralButton("Edit details & credit limit", edit)
                        NeutralButton("Save CSV statement", export)
                        NeutralButton("Dated statement", {dated=!dated;exportNotice=null})
                    }
                    if(dated) {
                        HorizontalDivider()
                        Text("Statement for selected dates",style=MaterialTheme.typography.titleMedium)
                        PosTextField(from,{from=it;exportNotice=null},"From (YYYY-MM-DD)",Modifier.fillMaxWidth())
                        PosTextField(through,{through=it;exportNotice=null},"Through (YYYY-MM-DD)",Modifier.fillMaxWidth())
                        Text("Includes the whole day in West Africa Time. Customer names and contact details are current. Later repayments do not rewrite the closing debt at your selected date.",style=MaterialTheme.typography.bodySmall)
                        period.getOrNull()?.let {view ->
                            LabelValueRow("Opening debt before period",money(view.openingMinor))
                            LabelValueRow("Closing debt through selected date",money(view.closingMinor),emphasized=true)
                            LabelValueRow("Current debt — all saved dates",money(customer.balanceMinor))
                        }
                        period.exceptionOrNull()?.let {InlineNotice("Statement unavailable",it.message ?: "Check the dates and refresh the complete history.",NoticeTone.Warning)}
                        NeutralButton("Save dated CSV statement",{
                            try {
                                val text=DatedCustomerStatementService.csv(statement,period.getOrThrow().range)
                                val file=CsvService.save(text,"customer-${customer.id.take(8)}-$from-to-$through.csv")
                                exportNotice=if(file==null) "Export cancelled." else "Dated statement saved to ${file.name}."
                            } catch(e:Exception) {exportNotice="Export failed: ${e.message}"}
                        },enabled=period.isSuccess)
                        NeutralButton("Save dated PDF statement",{
                            try {
                                val file=exportPdf(period.getOrThrow())
                                exportNotice=if(file==null) "Export cancelled." else "Dated statement PDF saved to ${file.name}."
                            } catch(e:Exception) {exportNotice="Export failed: ${e.message}"}
                        },enabled=period.isSuccess)
                        exportNotice?.let {Text(it,style=MaterialTheme.typography.bodySmall)}
                    }
                    Text("Repayments reduce the customer's total debt. Use Assign payments to receipts to specify which credit sales they cover. Unassigned payments are never automatically applied to a receipt. Credit-sale returns appear separately below; cash-only sale refunds stay under Sales.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        item { SectionHeader("Sales and payments", "Oldest first · Times shown in West Africa Time") }
        if (statement.events.isEmpty()) item { EmptyState("No sales or payments yet", "Choose this customer during a sale to start their history.") }
        items(statement.events, key = { "${if (it.sale != null) "sale" else if(it.repayment!=null) "repayment" else "credit-return"}:${it.id}" }) { event ->
            Surface(shape = MaterialTheme.shapes.small) {
                Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(if (event.sale != null) "Original sale · Receipt ${event.id.takeLast(8).uppercase()}" else if(event.repayment!=null) "Cash debt repayment · ${event.id.takeLast(8).uppercase()}" else "Credit-sale return · ${event.id.takeLast(8).uppercase()}", fontWeight = FontWeight.SemiBold)
                    Text(customerTime(event.createdAt), style = MaterialTheme.typography.bodySmall)
                    event.sale?.let { sale ->
                        Text(sale.lines.joinToString(" · ") { "${it.quantityLabel} ${it.sellingUnit} ${it.product.name}" }, style = MaterialTheme.typography.bodySmall)
                        LabelValueRow("Original sale total", money(sale.totalMinor))
                        LabelValueRow("Cash applied at sale", money(sale.cashMinor))
                        LabelValueRow("Credit added at sale", money(sale.creditMinor), emphasized = true)
                        if(sale.creditMinor>0) Text(sale.dueDate?.let { "Agreed repayment due date: $it · WAT" } ?: "No repayment due date recorded",style=MaterialTheme.typography.bodySmall)
                    }
                    event.repayment?.let { LabelValueRow("Cash received against debt", money(it.amountMinor), emphasized = true) }
                    event.creditReturn?.let {
                        Text("Original sale ${it.saleId}",style=MaterialTheme.typography.bodySmall)
                        LabelValueRow("Customer debt reduced",money(it.amountMinor),emphasized=true)
                        LabelValueRow("Cash refunded",money(it.cashRefundMinor))
                        Text("Returned goods reduce unpaid credit first. This is not another customer repayment.",style=MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
    }
}

@Composable
private fun CustomerProfileDialog(
    customer: Customer?,
    dismiss: () -> Unit,
    save: (String, String?, String?, Long?, String) -> Unit,
) {
    var name by remember(customer?.id) { mutableStateOf(customer?.name.orEmpty()) }
    var phone by remember(customer?.id) { mutableStateOf(customer?.phone.orEmpty()) }
    var address by remember(customer?.id) { mutableStateOf(customer?.address.orEmpty()) }
    var limitText by remember(customer?.id) { mutableStateOf(customer?.creditLimitMinor?.let { BigDecimal.valueOf(it, 2).toPlainString() }.orEmpty()) }
    var error by remember(customer?.id) { mutableStateOf<String?>(null) }
    var working by remember { mutableStateOf(false) }
    val operationId = remember(customer?.id, name, phone, address, limitText) { UUID.randomUUID().toString() }
    val parsedLimit = if (limitText.isBlank()) null else parseNaira(limitText)
    AlertDialog(
        onDismissRequest = { if (!working) dismiss() },
        title = { Text(if (customer == null) "Add customer" else "Edit customer details") },
        text = {
            Column(Modifier.widthIn(max = 500.dp).heightIn(max = 600.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                PosTextField(name, { name = it; error = null }, "Customer name")
                PosTextField(phone, { phone = it; error = null }, "Nigerian phone number (optional)", hint = "For example, 08012345678 or +2348012345678")
                PosTextField(address, { address = it; error = null }, "Address (optional)", singleLine = false)
                HorizontalDivider()
                PosTextField(limitText, { limitText = it; error = null }, "Credit limit in naira (optional)", hint = "Blank means no limit; 0 blocks new credit")
                Text("This is the most this customer may owe after a new credit sale. Leave it blank for no limit, or enter 0 to allow cash sales only. Existing debt is never removed by changing this setting.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (limitText.isBlank()) Text("No credit limit will be set.", style = MaterialTheme.typography.bodySmall)
                else if (parsedLimit != null) LabelValueRow("Credit limit to save", if (parsedLimit == 0L) "No new credit allowed" else money(parsedLimit), emphasized = true)
                if (customer != null && parsedLimit != null && parsedLimit < customer.balanceMinor) {
                    InlineNotice("This limit is below the current debt", "The customer still owes ${money(customer.balanceMinor)}. Saving this lower limit keeps that debt and blocks additional credit until the debt is reduced or the limit is raised.", NoticeTone.Warning)
                }
                error?.let { InlineNotice("Customer not saved", it, NoticeTone.Danger) }
            }
        },
        confirmButton = {
            PosButton(if (working) "Saving…" else if (customer == null) "Save customer" else "Save customer changes", {
                working = true
                try {
                    require(name.isNotBlank()) { "Enter a customer name." }
                    require(limitText.isBlank() || parsedLimit != null) { "Enter a valid credit limit with at most two decimal places, 0 to block credit, or leave the field blank for no limit." }
                    save(name.trim(), phone.trim().ifBlank { null }, address.trim().ifBlank { null }, parsedLimit, operationId)
                } catch (e: Exception) { error = e.message ?: "Could not save customer. Your entries are still here." }
                finally { working = false }
            }, enabled = !working)
        },
        dismissButton = { TextButton({ if (!working) dismiss() }, enabled = !working) { Text("Cancel") } },
    )
}

private fun creditLimitLabel(limit: Long?): String = when (limit) {
    null -> "Credit limit: not set"
    0L -> "New credit: not allowed"
    else -> "Credit limit: ${money(limit)}"
}

@Composable
private fun CustomerPaymentDialog(repo: PosRepository, tenantId: String, customer: Customer, dismiss: () -> Unit, saved: () -> Unit) {
    var amount by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    // Stable for retries of the submitted payload. Editing after failure starts another request.
    var operationId by remember { mutableStateOf(UUID.randomUUID().toString()) }
    var attempted by remember { mutableStateOf(false) }
    AlertDialog(onDismissRequest = dismiss, title = { Text("Record payment · ${customer.name}") }, text = {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            LabelValueRow("Customer still owes", money(customer.balanceMinor), emphasized = true)
            Text("Record cash you have received. This reduces this customer's debt and adds cash to today's open drawer, if one is open. Without an open drawer it remains unassigned cash.")
            PosTextField(amount, {
                if (attempted) { operationId = UUID.randomUUID().toString(); attempted = false }
                amount = it; error = null
            }, "Cash received in naira", hint = "For example, 2500.00")
            val parsed = parseNaira(amount)
            if (parsed != null && parsed > 0 && parsed <= customer.balanceMinor) LabelValueRow("Owed after payment", money(customer.balanceMinor - parsed))
            error?.let { InlineNotice("Payment not saved", it, NoticeTone.Danger) }
        }
    }, confirmButton = { PosButton("Save cash payment", {
        attempted = true
        try {
            val parsed = parseNaira(amount) ?: throw IllegalArgumentException("Enter a valid naira amount with at most two decimal places.")
            require(parsed > 0) { "Enter an amount greater than zero." }
            repo.recordRepayment(tenantId, customer.id, parsed, operationId); saved()
        } catch (e: Exception) { error = e.message ?: "Could not save payment. Your entry is still here." }
    }) }, dismissButton = { TextButton(dismiss) { Text("Cancel") } })
}

private fun customerTime(value: String): String = runCatching {
    DateTimeFormatter.ofPattern("dd MMM yyyy, HH:mm").withZone(ZoneId.of("Africa/Lagos")).format(Instant.parse(value)) + " WAT"
}.getOrDefault(value)
