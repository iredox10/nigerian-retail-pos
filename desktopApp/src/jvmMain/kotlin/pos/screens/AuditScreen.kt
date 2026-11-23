package pos.screens

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import pos.data.PosRepository
import pos.domain.AuditEvent
import pos.domain.StaffRole
import pos.domain.StaffSession
import pos.output.receiptDate
import pos.security.StaffAccess
import pos.ui.components.*

/** Frozen recorded identities are shown as saved; legacy activity is never assigned to today's user. */
@Composable
fun AuditScreen(repo: PosRepository, tenantId: String, access: StaffAccess, session: StaffSession?) {
    key(tenantId, session?.token) {
        var staffHistory by remember { mutableStateOf(false) }
        var search by remember { mutableStateOf("") }
        var revision by remember { mutableIntStateOf(0) }
        val canViewStaff = session?.role == StaffRole.OWNER && session.tenantId == tenantId
        val showingStaff = staffHistory && canViewStaff
        val loaded = remember(tenantId, session?.token, showingStaff, revision) {
            runCatching {
                if (showingStaff) access.auditEvents(requireNotNull(session)) else repo.auditEvents(tenantId)
            }
        }
        val query = search.trim()
        val events = loaded.getOrNull().orEmpty()
        val visible = events.filter { event ->
            query.isEmpty() || listOfNotNull(event.actor?.name, event.actor?.role?.name,
                activityLabel(event.kind), event.kind, event.entityId, event.id, event.description)
                .any { it.contains(query, ignoreCase = true) }
        }
        Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            SectionHeader("Activity history", "Saved on this computer · This shop only · Times shown in West Africa Time")
            InlineNotice(
                "History keeps the original record",
                "Names and roles show who was recorded when the action was saved. Earlier records may have no actor recorded, and activity from before this history was introduced may be absent. Corrections add history; they do not rewrite earlier activity.",
                NoticeTone.Info,
            )
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                FilterChip(!showingStaff, { staffHistory = false }, label = { Text("Business activity") })
                if (canViewStaff) FilterChip(showingStaff, { staffHistory = true }, label = { Text("Staff access") })
                NeutralButton("Refresh activity", { revision++ })
            }
            if (showingStaff) Text("Owner-only staff changes on this computer. Staff passwords and session tokens are not shown or exported with shop backups.", style = MaterialTheme.typography.bodySmall)
            PosTextField(search, { search = it }, "Find activity", Modifier.fillMaxWidth(), hint = "Person, action or source ID")
            loaded.exceptionOrNull()?.let { failure ->
                InlineNotice("Activity could not be loaded", failure.message ?: "Refresh to try again.", NoticeTone.Danger)
            }
            if (loaded.isSuccess) {
                Text("${visible.size} of ${events.size} saved events · Newest first", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    if (visible.isEmpty()) item {
                        EmptyState(if (query.isEmpty()) "No activity recorded here yet" else "No matching activity",
                            if (query.isEmpty()) "New saved actions appear here. Earlier shop records are not retrospectively attributed to an owner or staff member."
                            else "Try another person, action or source ID. Refresh keeps your search.")
                    }
                    items(visible, key = { it.id }) { event -> AuditEventRow(event) }
                }
            }
        }
    }
}

@Composable
private fun AuditEventRow(event: AuditEvent) {
    Surface(Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.medium) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(activityLabel(event.kind), style = MaterialTheme.typography.titleMedium)
            val actor = event.actor
            if (actor == null) {
                Text("Actor not recorded", fontWeight = FontWeight.Medium)
            } else {
                Text("${actor.name} · ${actor.role.name.lowercase().replaceFirstChar { it.uppercaseChar() }}", fontWeight = FontWeight.Medium)
            }
            Text(receiptDate(event.createdAt), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text("Source ID: ${event.entityId}", style = MaterialTheme.typography.bodySmall)
            event.description?.takeIf { it.isNotBlank() }?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
            Text("Activity ID: ${event.id}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

private fun activityLabel(kind: String): String = when (kind) {
    "stock.disposed" -> "Stock removed"
    "purchase_order.created" -> "Purchase order drafted"
    "purchase_order.edited" -> "Purchase order draft changed"
    "purchase_order.approved" -> "Purchase order approved"
    "purchase_order.cancelled" -> "Purchase order cancelled"
    "purchase_order.received" -> "Purchase order delivery received"
    "supplier.created" -> "Supplier added"
    "supplier.updated" -> "Supplier details changed"
    "supplier.receipt_linked" -> "Purchase linked to supplier"
    "stock_count.started" -> "Stock count started"
    "stock_count.draft_saved" -> "Unfinished stock count saved"
    "stock_count.posted" -> "Stock counts posted"
    "stock_count.cancelled" -> "Stock count cancelled"
    "product.variant_changed" -> "Product variant changed"
    "supplier.purchase_returned" -> "Stock returned to supplier"
    "sale.exchanged" -> "Items exchanged"
    "customer.debt_written_off" -> "Customer debt written off"
    "customer.debt_reinstated" -> "Customer debt reinstated"
    "product.photo_gallery_changed" -> "Product gallery photo changed"
    "customer.followup_open" -> "Repayment follow-up scheduled"
    "customer.followup_completed" -> "Repayment follow-up completed"
    "customer.followup_cancelled" -> "Repayment follow-up cancelled"
    "sale.note_captured" -> "Sale note saved"
    "sale.cash_tender_captured" -> "Cash given and change recorded"
    "owner_cash.recorded" -> "Owner money recorded"
    "stock.repacked" -> "Goods repacked"
    "stock.repacking_recipe_saved" -> "Repacking recipe saved"
    "expense.attachment_changed" -> "Expense receipt changed"
    "minimum_price.changed" -> "Minimum selling price changed"
    "image.changed" -> "Shop or product image changed"
    "shop.receipt_settings_changed" -> "Receipt details changed"
    "staff.pin_configured" -> "Staff PIN configured"
    "staff.pin_removed" -> "Staff PIN removed"
    "product.created" -> "Item added"
    "product.updated" -> "Item details changed"
    "product.metadata_changed" -> "Item catalogue details changed"
    "product.archived" -> "Item archived"
    "product.restored" -> "Item restored"
    "stock.reminder_changed" -> "Low-stock reminder changed"
    "stock.adjusted" -> "Stock count saved"
    "stock.received" -> "Stock received"
    "customer.created" -> "Customer added"
    "customer.updated" -> "Customer details changed"
    "customer.repayment" -> "Customer payment recorded"
    "repayment.allocated" -> "Payment assigned to receipt"
    "repayment.allocation_reversed" -> "Payment assignment undone"
    "sale.completed" -> "Sale saved"
    "sale.returned" -> "Sale return saved"
    "selling_unit.created" -> "Selling unit added"
    "selling_unit.archived" -> "Selling unit archived"
    "selling_unit.restored" -> "Selling unit restored"
    "purchase_unit.created" -> "Buying unit added"
    "purchase_unit.archived" -> "Buying unit archived"
    "purchase_unit.restored" -> "Buying unit restored"
    "expense.recorded" -> "Expense recorded"
    "cash.shift_opened" -> "Cash drawer opened"
    "cash.shift_closed" -> "Cash drawer closed"
    "cash.movement" -> "Cash added or taken out"
    "supplier.payment" -> "Supplier payment recorded"
    "staff.created" -> "Staff account added"
    "staff.updated" -> "Staff account changed"
    "staff.password_reset" -> "Staff password reset"
    else -> "Saved activity ($kind)"
}
