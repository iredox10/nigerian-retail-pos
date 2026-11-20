package pos.screens

import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import java.util.UUID
import pos.data.*
import pos.domain.Product
import pos.output.receiptDate
import pos.ui.components.*

@Composable
fun ProductVariantPanel(repo: PosRepository, tenantId: String, product: Product, onChanged: (String) -> Unit) {
    var revision by remember { mutableIntStateOf(0) }
    val loaded = remember(tenantId, product.id, revision) {
        runCatching { repo.productVariantHistory(tenantId, product.id) }
    }
    Surface(shape = MaterialTheme.shapes.medium) {
        Column(Modifier.fillMaxWidth().padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            SectionHeader("Product variant", "Group separately stocked goods under a shared family name.")
            Text("Use the same family name for related items and a different label for each size, colour or style. Each item keeps its own stock, price and barcode.")
            loaded.exceptionOrNull()?.let {
                InlineNotice("Variant unavailable", it.message ?: "Reload the record to try again.", NoticeTone.Danger)
            }
            if (loaded.isSuccess) key(revision) {
                val history = loaded.getOrThrow()
                val last = history.lastOrNull()
                var family by remember { mutableStateOf(last?.variant?.family.orEmpty()) }
                var label by remember { mutableStateOf(last?.variant?.label.orEmpty()) }
                var reason by remember { mutableStateOf("") }
                var showingHistory by remember { mutableStateOf(false) }
                var error by remember { mutableStateOf<String?>(null) }
                val editable = !product.archived
                val operationId = remember(family, label, reason) { UUID.randomUUID().toString() }
                val candidate = runCatching {
                    if (family.isBlank() && label.isBlank()) null else ProductVariant(family.trim(), label.trim())
                }
                PosTextField(family, { family = it }, "Product family", hint = "For example, School shirt", enabled = editable)
                PosTextField(label, { label = it }, "Variant label", hint = "For example, Blue / Medium", enabled = editable)
                PosTextField(reason, { reason = it }, "Reason for this change", enabled = editable)
                Text("Clear both fields to remove the assignment. Use a distinct item name to identify the variant on receipts.",
                    style = MaterialTheme.typography.bodySmall)
                if (!editable) Text("Restore this item before changing its variant.")
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    PosButton(if (candidate.getOrNull() == null && last?.variant != null) "Remove variant" else "Save variant", {
                        try {
                            repo.saveProductVariant(tenantId, product.id, candidate.getOrThrow(),
                                last?.version ?: 0, reason.trim(), operationId)
                            onChanged("Product variant saved on this computer")
                        } catch (e: Exception) {
                            error = e.message ?: "The variant could not be saved."
                        }
                    }, enabled = editable && candidate.isSuccess && candidate.getOrNull() != last?.variant &&
                        reason.trim().length in 3..250)
                    NeutralButton("Reload", { revision++ })
                }
                error?.let { InlineNotice("Variant not saved", it, NoticeTone.Danger) }
                if (history.isNotEmpty()) {
                    NeutralButton(if (showingHistory) "Hide variant history" else "View variant history", { showingHistory = !showingHistory })
                    if (showingHistory) history.reversed().forEach { change ->
                        Text("${change.variant?.description ?: "Assignment removed"} · ${receiptDate(change.createdAt.toString())}")
                        Text(change.reason, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
    }
}
