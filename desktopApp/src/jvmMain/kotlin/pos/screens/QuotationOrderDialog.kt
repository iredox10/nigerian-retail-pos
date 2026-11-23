package pos.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import java.util.UUID
import pos.data.*
import pos.domain.*
import pos.ui.components.*

@Composable
internal fun QuotationOrderDialog(repo: PosRepository, quote: Quotation, dismiss: () -> Unit, saved: (CustomerOrder) -> Unit) {
    val quotedCustomer = quote.customer
    val customers = remember(quote.tenantId) { runCatching { repo.customers(quote.tenantId) } }
    var customerId by remember { mutableStateOf(quotedCustomer?.customerId) }
    var customerMenu by remember { mutableStateOf(false) }
    var collectionDate by remember { mutableStateOf("") }
    var note by remember { mutableStateOf("") }
    var deposit by remember { mutableStateOf("0") }
    var received by remember { mutableStateOf(false) }
    var reviewed by remember { mutableStateOf<QuotationOrderReview?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    val operation = remember(quote.id, quote.version, customerId, collectionDate, note, deposit) { UUID.randomUUID().toString() }
    val amount = parseNaira(deposit)
    AlertDialog(onDismissRequest = dismiss, title = { Text("Create order from quotation") }, text = {
        Column(Modifier.widthIn(max = 600.dp).heightIn(max = 620.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Quotation ${quote.id.takeLast(8)} · Accepted revision ${quote.version}")
            Text("This copies the agreed goods and prices into a customer order. Record stock holds and collection from that order. Current selling-unit definitions are checked before saving.")
            customers.exceptionOrNull()?.let { InlineNotice("Customers unavailable", it.message.orEmpty(), NoticeTone.Danger) }
            val customer = customers.getOrDefault(emptyList()).singleOrNull { it.id == customerId }
            if (quotedCustomer?.customerId == null) Box {
                NeutralButton(customer?.name ?: "Choose existing customer", { customerMenu = true })
                DropdownMenu(customerMenu, { customerMenu = false }) {
                    customers.getOrDefault(emptyList()).forEach { option ->
                        DropdownMenuItem(text = { Text(option.name) }, onClick = { customerId = option.id; customerMenu = false; reviewed = null })
                    }
                }
            } else Text("Linked customer · ${customer?.name ?: quotedCustomer.name}")
            PosTextField(collectionDate, { collectionDate = it; reviewed = null }, "Planned collection date (optional)", hint = "YYYY-MM-DD, West Africa Time")
            PosTextField(note, { note = it; reviewed = null }, "Order note (optional)")
            PosTextField(deposit, { deposit = it; received = false; reviewed = null }, "Cash deposit received (₦)", hint = "0 when no deposit is received")
            if ((amount ?: 0L) > 0L) Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(received, { received = it })
                Text("I counted and received this cash deposit.")
            }
            Text("A cash deposit is recorded in the open drawer. It stays with the order until collection or an explicit cancellation refund.", style = MaterialTheme.typography.bodySmall)
            LabelValueRow("Agreed quotation total", money(quote.totalMinor), emphasized = true)
            reviewed?.let { review ->
                HorizontalDivider()
                Text("Review agreed order", style = MaterialTheme.typography.titleMedium)
                Text("Customer · ${review.customerName}")
                review.quotation.lines.forEach { line ->
                    Text(line.productName, style = MaterialTheme.typography.titleSmall)
                    LabelValueRow("${line.quantityLabel} ${line.sellingUnit} × ${money(line.agreedUnitPriceMinor)}", money(line.totalMinor))
                }
                LabelValueRow("Cash deposit", money(amount ?: 0L))
                LabelValueRow("Remaining order balance", money(quote.totalMinor - (amount ?: 0L)))
                if (quote.notes.isNotEmpty()) Text("Original quotation notes remain in its saved revision.", style = MaterialTheme.typography.bodySmall)
            }
            error?.let { InlineNotice("Order not saved", it, NoticeTone.Danger) }
        }
    }, confirmButton = {
        PosButton(if (reviewed == null) "Review order" else "Create customer order", {
            try {
                require(amount != null && amount in 0..quote.totalMinor) { "Enter a deposit from zero up to the agreed total." }
                require(amount == 0L || received) { "Confirm the cash deposit was received." }
                val customer = requireNotNull(customerId) { "Choose an existing customer for this order." }
                if (reviewed == null) {
                    reviewed = repo.reviewQuotationOrder(quote.tenantId, quote.id, quote.version, customer,
                        collectionDate.trim().ifEmpty { null }, note.trim())
                    error = null
                } else saved(repo.convertQuotationToOrder(quote.tenantId, requireNotNull(reviewed), amount, operation))
            } catch (e: Exception) { error = e.message ?: "Review the current quotation before creating its order." }
        }, enabled = customers.isSuccess)
    }, dismissButton = { TextButton(dismiss) { Text("Back to quotation") } })
}
