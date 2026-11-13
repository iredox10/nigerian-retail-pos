package pos.screens

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import pos.data.PosRepository
import pos.domain.Product
import pos.ui.components.*
import pos.ui.theme.PosPalette
import java.math.BigDecimal
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.UUID

/** Reorder levels are reminders in the selling unit, not a purchase or a stock adjustment. */
@Composable
internal fun ReorderSettingsPanel(repo: PosRepository, tenantId: String, product: Product, onChanged: (String) -> Unit) {
    var text by remember(product.id, product.reorderLevel) { mutableStateOf(product.reorderLevel?.toString().orEmpty()) }
    var error by remember(product.id) { mutableStateOf<String?>(null) }
    var saving by remember { mutableStateOf(false) }
    val operationId = remember(product.id, text) { UUID.randomUUID().toString() }
    val value = text.trim().takeIf { it.matches(Regex("[0-9]+")) }?.toIntOrNull()
    val changed = if (text.isBlank()) product.reorderLevel != null else value == null || value != product.reorderLevel
    Column(Modifier.widthIn(max = 620.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        SectionHeader("When should you buy more?", "Low-stock reminder · ${product.unit}")
        LabelValueRow("Recorded stock now", "${inventoryQuantity(product.stock)} ${product.unit}")
        PosTextField(text, { text = it; error = null }, "Reorder level (${product.unit})", hint = "Whole units; blank turns off the reminder", enabled = !product.archived)
        Text("An active item appears in Low stock when its quantity is at or below this level. Enter 0 for an out-of-stock reminder only. Leave blank to turn it off. Saving this setting does not change stock or order goods.", style = MaterialTheme.typography.bodySmall, color = PosPalette.Secondary)
        if (text.isBlank()) Text("Reminder will be off", style = MaterialTheme.typography.bodyMedium)
        else if (value != null) LabelValueRow("Reminder starts at", "$value ${product.unit} or less", emphasized = true)
        error?.let { InlineNotice("Reorder level not saved", it, NoticeTone.Danger) }
        PosButton(if (saving) "Saving reminder…" else "Save reorder level", {
            saving = true
            try {
                require(text.isBlank() || value != null) { "Enter a whole number from 0 to ${Int.MAX_VALUE}, or leave the field blank." }
                repo.setReorderLevel(tenantId, product.id, if (text.isBlank()) null else value, operationId)
                onChanged(if (text.isBlank()) "Low-stock reminder turned off" else "Reorder level saved on this computer")
            } catch (e: Exception) { error = e.message ?: "The reorder level could not be saved. Your entry is still here." }
            finally { saving = false }
        }, enabled = !product.archived && !saving && changed)
    }
}

/** Read-only source history. Never infer an opening count or a running balance for older goods. */
@Composable
internal fun InventoryMovementHistory(repo: PosRepository, tenantId: String, product: Product) {
    var revision by remember(product.id) { mutableIntStateOf(0) }
    var visibleCount by remember(product.id, revision) { mutableIntStateOf(100) }
    val result = remember(tenantId, product.id, revision) { runCatching { repo.inventoryMovements(tenantId, product.id) } }
    SectionHeader("Stock movements", "Saved sources · Quantities in ${product.unit}")
    Surface(color = PosPalette.Surface, shape = MaterialTheme.shapes.medium) {
        Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            LabelValueRow("Recorded stock now", "${inventoryQuantity(product.stock)} ${product.unit}", emphasized = true)
            if (product.openingStock == null) InlineNotice("Opening quantity was not recorded in history", "This older item's saved movements are listed below, but its original opening quantity is unknown. No starting quantity or running balance has been guessed.", NoticeTone.Info)
            else LabelValueRow("Opening quantity recorded", "${inventoryQuantity(product.openingStock!!)} ${product.unit}")
            Text("Purchases add the converted selling quantity. Sales remove stock; returns add it only when marked for restocking. Count corrections show the difference recorded at the count. These are saved stock changes, not a valuation or profit report.", style = MaterialTheme.typography.bodySmall, color = PosPalette.Secondary)
            NeutralButton("Refresh stock history", { revision++ })
        }
    }
    result.exceptionOrNull()?.let { InlineNotice("Stock movements could not be loaded", it.message ?: "Refresh to try again.", NoticeTone.Danger) }
    result.getOrNull()?.let { movements ->
        if (movements.isEmpty()) Text("No stock movements have been recorded for this item.", color = PosPalette.Secondary)
        Text("Showing ${minOf(visibleCount,movements.size)} of ${movements.size} saved movements, newest first.",style=MaterialTheme.typography.bodySmall,color=PosPalette.Secondary)
        movements.asReversed().take(visibleCount).forEach { movement ->
            Surface(color = PosPalette.Surface, shape = MaterialTheme.shapes.small) {
                Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    val label = when (movement.kind) { "opening" -> "Opening count"; "purchase" -> "Stock received"; "sale" -> "Sale"; "return" -> "Return added to stock"; "count" -> "Count correction"; else -> movement.kind }
                    LabelValueRow(label, "${if (movement.quantityDelta > 0) "+" else ""}${inventoryQuantity(movement.quantityDelta)} ${product.unit}", emphasized = true)
                    Text(movement.description)
                    Text(inventoryDate(movement.createdAt), style = MaterialTheme.typography.bodySmall, color = PosPalette.Secondary)
                    Text("Source: ${movement.sourceId}", style = MaterialTheme.typography.bodySmall, color = PosPalette.Secondary)
                }
            }
        }
        if(visibleCount<movements.size) NeutralButton("Show 100 more movements",{visibleCount+=100})
    }
    HorizontalDivider(color = PosPalette.Border)
}

private fun inventoryQuantity(value: Double): String = BigDecimal.valueOf(value).stripTrailingZeros().toPlainString()
private fun inventoryDate(value: String): String = runCatching {
    DateTimeFormatter.ofPattern("dd MMM yyyy · HH:mm 'WAT'").withZone(ZoneId.of("Africa/Lagos")).format(Instant.parse(value))
}.getOrDefault(value)
