package pos.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import pos.data.*
import pos.domain.parseQuantity
import pos.domain.quantityLabel
import pos.output.receiptDate
import pos.ui.components.*
import java.util.UUID

@Composable
internal fun ReplenishmentPolicyDialog(repo: PosRepository, tenantId: String, dismiss: () -> Unit, saved: () -> Unit) {
    val items = remember { repo.products(tenantId) }
    val policies = remember { repo.replenishmentPolicies(tenantId) }
    val history = remember { repo.replenishmentPolicyHistory(tenantId) }
    var selectedId by remember { mutableStateOf(items.firstOrNull()?.id) }
    val item = items.firstOrNull { it.id == selectedId }
    val current = policies[selectedId]
    var menu by remember { mutableStateOf(false) }
    var lead by remember(selectedId) { mutableStateOf((current?.leadDays ?: 0).toString()) }
    var safety by remember(selectedId) { mutableStateOf(quantityLabel(current?.safetyScaled ?: 0, item?.quantityPrecision ?: 0)) }
    var multiple by remember(selectedId) { mutableStateOf(quantityLabel(current?.multipleScaled ?: 1, item?.quantityPrecision ?: 0)) }
    var reason by remember(selectedId) { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var showHistory by remember { mutableStateOf(false) }
    val operationId = remember(selectedId, lead, safety, multiple, reason) { UUID.randomUUID().toString() }
    Dialog(onDismissRequest = dismiss) {
        Surface(shape = MaterialTheme.shapes.large) {
            Column(Modifier.width(520.dp).heightIn(max = 740.dp).padding(24.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(16.dp)) {
                SectionHeader("Buying rules", "Set the lead time and buffer used in reorder suggestions.")
                Box {
                    NeutralButton(item?.name ?: "Choose an item", { menu = true })
                    DropdownMenu(menu, { menu = false }) {
                        items.forEach { product -> DropdownMenuItem(text = { Text(product.name) },
                            onClick = { selectedId = product.id; menu = false; error = null }) }
                    }
                }
                if (item != null) {
                    PosTextField(lead, { lead = it }, "Supplier lead time in days", hint = "0 to 365")
                    PosTextField(safety, { safety = it }, "Safety stock in ${item.unit}", hint = "Extra stock to keep as a buffer")
                    PosTextField(multiple, { multiple = it }, "Buy in multiples of ${item.unit}", hint = "For example, 12 when buying a dozen")
                    Text("Suggestions add demand during the lead time and your safety stock, deduct reserved stock from availability, allow for approved incoming goods and round up to this buying multiple.", style = MaterialTheme.typography.bodySmall)
                    PosTextField(reason, { reason = it }, "Why are these buying rules changing?")
                    error?.let { InlineNotice("Buying rules not saved", it, NoticeTone.Danger) }
                    PosButton("Save buying rules", {
                        try {
                            val days = requireNotNull(lead.toIntOrNull()) { "Enter whole lead-time days." }
                            val buffer = requireNotNull(parseQuantity(safety, item.quantityPrecision)) { "Enter safety stock in the displayed unit." }
                            val step = requireNotNull(parseQuantity(multiple, item.quantityPrecision)) { "Enter the buying multiple in the displayed unit." }
                            repo.saveReplenishmentPolicy(tenantId, item.id, days, buffer.scaledUnits, step.scaledUnits,
                                current?.version ?: 0, reason, operationId)
                            saved()
                        } catch (e: Exception) { error = e.message ?: "Review the buying rules and try again." }
                    })
                    NeutralButton(if (showHistory) "Hide history" else "View buying rule history", { showHistory = !showHistory })
                    if (showHistory) history.filter { it.productId == item.id }.asReversed().forEach {
                        Text("Revision ${it.version} · ${receiptDate(it.createdAt)}", style = MaterialTheme.typography.titleSmall)
                        Text("${it.leadDays} lead days · ${quantityLabel(it.safetyScaled, it.precision)} ${it.unit} safety stock · buy in ${quantityLabel(it.multipleScaled, it.precision)} ${it.unit}", style = MaterialTheme.typography.bodySmall)
                        Text(it.reason, style = MaterialTheme.typography.bodySmall)
                    }
                } else EmptyState("Add an item first", "Buying rules apply to goods already in this shop.")
                TextButton(dismiss) { Text("Close") }
            }
        }
    }
}
