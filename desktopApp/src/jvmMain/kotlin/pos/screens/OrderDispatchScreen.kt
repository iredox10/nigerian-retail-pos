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
import androidx.compose.ui.unit.dp
import java.util.UUID
import pos.data.*
import pos.domain.*
import pos.output.receiptDate
import pos.ui.components.*

@Composable
fun OrderDispatchScreen(repo: PosRepository, tenantId: String, onChanged: () -> Unit, orderId: String? = null) = key(tenantId, orderId) {
    var revision by remember { mutableIntStateOf(0) }
    var creating by remember { mutableStateOf(false) }
    var updating by remember { mutableStateOf<Pair<OrderDispatch, String>?>(null) }
    var notice by remember { mutableStateOf<String?>(null) }
    var includeClosed by remember { mutableStateOf(false) }
    val loaded = remember(revision) { runCatching {
        Triple(repo.customerOrders(tenantId), repo.orderDispatches(tenantId, orderId), repo.sales(tenantId))
    } }
    val orders = loaded.getOrNull()?.first.orEmpty().filter { orderId == null || it.order.id == orderId }
    val dispatches = loaded.getOrNull()?.second.orEmpty()
    val sales = loaded.getOrNull()?.third.orEmpty().associateBy { it.id }
    val sources = orders.flatMap { order -> order.history.filter { it.kind == "collected" && it.saleId in sales }.map { order to sales.getValue(requireNotNull(it.saleId)) } }
    fun refresh(message: String) { revision++; creating = false; updating = null; notice = message; onChanged() }

    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            SectionHeader("Order deliveries", "Record dispatch and recipient handover on this computer.", Modifier.weight(1f))
            PosButton("Record dispatch", { creating = true }, enabled = loaded.isSuccess && sources.isNotEmpty())
        }
        InlineNotice("Locally recorded delivery status", "Choose goods from a completed order collection. Delivery records track handover after those goods were sold. Supplier, courier and bank services do not confirm these entries.")
        notice?.let { InlineNotice(it, tone = NoticeTone.Success) }
        loaded.exceptionOrNull()?.let { InlineNotice("Deliveries unavailable", it.message.orEmpty(), NoticeTone.Danger) }
        FilterChip(includeClosed, { includeClosed = !includeClosed }, label = { Text("Include delivered and returned") })
        LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            val visible = dispatches.filter { includeClosed || it.status !in setOf("delivered", "returned") }.asReversed()
            if (visible.isEmpty()) item {
                EmptyState("No matching deliveries", if (sources.isEmpty()) "Complete an order collection before recording goods handed to a delivery person." else "Record a dispatch or include completed deliveries.")
            }
            items(visible, key = { it.id }) { dispatch ->
                Surface(shape = MaterialTheme.shapes.medium) {
                    Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(dispatch.details.recipient, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                            StatusPill(deliveryLabel(dispatch.status), when (dispatch.status) {
                                "delivered" -> NoticeTone.Success
                                "failed" -> NoticeTone.Warning
                                else -> NoticeTone.Neutral
                            })
                        }
                        Text(dispatch.details.address)
                        Text("Delivery person / courier · ${dispatch.details.courier}")
                        if (dispatch.details.tracking.isNotEmpty()) Text("Tracking reference · ${dispatch.details.tracking}")
                        Text("Dispatch ${dispatch.id.take(8)} · Order ${dispatch.orderId.take(8)} · Sale ${dispatch.saleId.take(8)}", style = MaterialTheme.typography.bodySmall)
                        dispatch.quantities.forEach { (key, count) ->
                            val line = sales[dispatch.saleId]?.lines?.singleOrNull { it.key == key }
                            if (line != null) LabelValueRow(line.product.name, "${quantityLabel(count.toLong(), line.quantityPrecision)} ${line.sellingUnit}")
                        }
                        HorizontalDivider()
                        dispatch.history.forEach { event ->
                            Text("${deliveryLabel(event.kind)} · ${receiptDate(event.createdAt)}", style = MaterialTheme.typography.titleSmall)
                            event.proofName?.let { Text("Recipient recorded at handover · $it") }
                            Text(event.note, style = MaterialTheme.typography.bodySmall)
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            if (dispatch.status == "dispatched") {
                                PosButton("Record delivery", { updating = dispatch to "delivered" })
                                NeutralButton("Delivery failed", { updating = dispatch to "failed" })
                            }
                            if (dispatch.status == "failed") PosButton("Send again", { updating = dispatch to "dispatched" })
                            if (dispatch.status in setOf("dispatched", "failed")) NeutralButton("Goods returned", { updating = dispatch to "returned" })
                        }
                    }
                }
            }
        }
    }
    if (creating) DispatchEditor(repo, tenantId, sources, { creating = false }) { refresh("Dispatch recorded on this computer") }
    updating?.let { (dispatch, kind) -> DeliveryStatusEditor(repo, tenantId, dispatch, kind, { updating = null }) {
        refresh("Delivery status recorded on this computer")
    } }
}

private fun deliveryLabel(kind: String) = when (kind) {
    "dispatched" -> "Dispatched"
    "delivered" -> "Delivery recorded"
    "failed" -> "Delivery failed"
    else -> "Goods returned"
}

@Composable
private fun DispatchEditor(repo: PosRepository, tenantId: String, sources: List<Pair<CustomerOrderState, Sale>>,
    dismiss: () -> Unit, saved: () -> Unit) {
    var sourceId by remember { mutableStateOf(sources.first().second.id) }
    var sourceMenu by remember { mutableStateOf(false) }
    val source = sources.single { it.second.id == sourceId }
    val review = remember(sourceId) { runCatching { repo.reviewOrderDispatch(tenantId, source.first.order.id, sourceId) } }
    val quantities = remember(sourceId) { mutableStateMapOf<String, String>() }
    var recipient by remember(sourceId) { mutableStateOf(source.first.order.customerName) }
    var address by remember { mutableStateOf("") }
    var courier by remember { mutableStateOf("") }
    var tracking by remember { mutableStateOf("") }
    var note by remember { mutableStateOf("Goods handed over for delivery") }
    var error by remember { mutableStateOf<String?>(null) }
    var checked by remember { mutableStateOf(false) }
    val operation = remember(sourceId, recipient, address, courier, tracking, note, quantities.toMap()) { UUID.randomUUID().toString() }
    fun chosen(): Map<String, Int> = quantities.filterValues { it.isNotBlank() }.mapValues { (key, input) ->
        val line = review.getOrThrow().lines.single { it.key == key }
        requireNotNull(quantityCount(input, line.quantityPrecision)) { "Enter an exact quantity in ${line.sellingUnit}." }
    }.filterValues { it > 0 }
    AlertDialog(onDismissRequest = dismiss, title = { Text("Record goods leaving for delivery") },
        text = {
            Column(Modifier.width(520.dp).heightIn(max = 620.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("These goods were already included in the linked completed sale. Review what leaves with this delivery person.")
                Box {
                    NeutralButton("${source.first.order.customerName} · Sale ${sourceId.take(8)}", { sourceMenu = true })
                    DropdownMenu(sourceMenu, { sourceMenu = false }) {
                        sources.forEach { (order, sale) -> DropdownMenuItem(text = { Text("${order.order.customerName} · ${sale.id.take(8)} · ${receiptDate(sale.createdAt)}") },
                            onClick = { sourceId = sale.id; sourceMenu = false; checked = false; error = null }) }
                    }
                }
                review.exceptionOrNull()?.let { InlineNotice("Goods unavailable", it.message.orEmpty(), NoticeTone.Danger) }
                review.getOrNull()?.let { value ->
                    value.lines.forEach { line ->
                        val available = value.available.getValue(line.key)
                        if (available > 0) PosTextField(quantities[line.key].orEmpty(), { quantities[line.key] = it; checked = false },
                            "${line.product.name} · ${line.sellingUnit}", hint = "Up to ${quantityLabel(available.toLong(), line.quantityPrecision)} ${line.sellingUnit}")
                    }
                    if (value.available.values.none { it > 0 }) Text("All goods in this receipt have already been dispatched or returned.")
                }
                PosTextField(recipient, { recipient = it; checked = false }, "Recipient")
                PosTextField(address, { address = it; checked = false }, "Delivery address and landmark")
                PosTextField(courier, { courier = it; checked = false }, "Courier or person delivering")
                PosTextField(tracking, { tracking = it; checked = false }, "Tracking reference (optional)")
                PosTextField(note, { note = it; checked = false }, "Dispatch note")
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked, { checked = it })
                    Text("I checked these quantities and handed the goods to this delivery person.")
                }
                error?.let { InlineNotice("Dispatch not saved", it, NoticeTone.Danger) }
            }
        }, confirmButton = { TextButton({
            try {
                repo.createOrderDispatch(tenantId, review.getOrThrow(), chosen(),
                    OrderDispatchDetails(recipient.trim(), address.trim(), courier.trim(), tracking.trim(), note.trim()), operation)
                saved()
            } catch (e: Exception) { error = e.message ?: "Review this delivery again." }
        }, enabled = checked && review.isSuccess) { Text("Save dispatch") } },
        dismissButton = { TextButton(dismiss) { Text("Cancel") } })
}

@Composable
private fun DeliveryStatusEditor(repo: PosRepository, tenantId: String, dispatch: OrderDispatch, kind: String,
    dismiss: () -> Unit, saved: () -> Unit) {
    var proofName by remember { mutableStateOf("") }
    var note by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var checked by remember { mutableStateOf(false) }
    val operation = remember(proofName, note, dispatch.id, dispatch.revision) { UUID.randomUUID().toString() }
    AlertDialog(onDismissRequest = dismiss, title = { Text(deliveryLabel(kind)) }, text = {
        Column(Modifier.width(480.dp).heightIn(max = 500.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("${dispatch.details.recipient} · ${dispatch.details.address}")
            if (kind == "delivered") {
                Text("Record the person who accepted the goods and the handover evidence supplied to your shop.")
                PosTextField(proofName, { proofName = it; checked = false }, "Name of the person accepting the goods")
            }
            if (kind == "returned") Text("This records the delivery outcome. Inspect the goods and use the linked sale return workflow for any stock or refund correction.")
            PosTextField(note, { note = it; checked = false }, if (kind == "delivered") "Handover proof or recipient acknowledgement" else "What happened?")
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(checked, { checked = it })
                Text("I checked this update before recording it.")
            }
            error?.let { InlineNotice("Status not saved", it, NoticeTone.Danger) }
        }
    }, confirmButton = { TextButton({
        try {
            repo.recordOrderDeliveryStatus(tenantId, dispatch.id, dispatch.revision, kind,
                proofName.trim().takeIf { kind == "delivered" }, note.trim(), operation)
            saved()
        } catch (e: Exception) { error = e.message ?: "Review this delivery again." }
    }, enabled = checked) { Text("Save status") } }, dismissButton = { TextButton(dismiss) { Text("Cancel") } })
}
