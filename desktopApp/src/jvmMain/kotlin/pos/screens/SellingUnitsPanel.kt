package pos.screens

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import pos.data.PosRepository
import pos.domain.Product
import pos.domain.SellingUnit
import pos.domain.money
import pos.domain.parseNaira
import pos.ui.components.*
import pos.ui.theme.PosPalette
import java.math.BigDecimal
import java.util.UUID

/** Pack definitions are prospective; sales retain the unit, conversion and price sold. */
@Composable
internal fun SellingUnitsPanel(repo: PosRepository, tenantId: String, product: Product) {
    var revision by remember { mutableIntStateOf(0) }
    var name by remember { mutableStateOf("") }
    var contains by remember { mutableStateOf("") }
    var price by remember { mutableStateOf("") }
    var barcode by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var message by remember { mutableStateOf<String?>(null) }
    var confirming by remember { mutableStateOf<SellingUnit?>(null) }
    val operationId = remember(name, contains, price, barcode) { UUID.randomUUID().toString() }
    val factor = contains.trim().takeIf { it.matches(Regex("[0-9]+")) }?.toIntOrNull()?.takeIf { it > 0 }
    val amount = parseNaira(price)
    val result = remember(tenantId, product.id, revision) {
        runCatching { repo.sellingUnits(tenantId, product.id, includeArchived = true) }
    }

    Column(verticalArrangement = Arrangement.spacedBy(20.dp)) {
        SectionHeader("Selling units", "Sell single ${product.unit}, cartons or packs from the same stock")
        Text("Set exactly what each pack contains and its selling price. For example, 1 carton = 24 bottles; selling 1 carton removes 24 bottles from stock. Buying units do not automatically create selling units.")
        LabelValueRow("Base selling price", "${money(product.priceMinor)} / ${product.unit}")
        Text("Checkout accepts whole selling units only. For a part-pack sale, choose the base unit instead.", style = MaterialTheme.typography.bodySmall, color = PosPalette.Secondary)
        result.exceptionOrNull()?.let {
            InlineNotice("Selling units could not be loaded", it.message ?: "Refresh to try again.", NoticeTone.Danger)
        }
        error?.let { InlineNotice("Selling unit not saved", it, NoticeTone.Danger) }
        message?.let { InlineNotice(it, tone = NoticeTone.Success) }
        NeutralButton("Refresh selling units", { revision++; error = null; message = null })
        result.getOrNull()?.let { units ->
            if (units.isEmpty()) Text("No pack selling units yet. You can still sell by ${product.unit}.", color = PosPalette.Secondary)
            units.sortedBy { it.archived }.forEach { unit ->
                Surface(color = PosPalette.Surface, shape = MaterialTheme.shapes.small) {
                    Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text("1 ${unit.name} = ${unit.baseUnitsPerUnit} ${unit.baseUnit}", style = MaterialTheme.typography.titleMedium)
                        LabelValueRow("Selling price per ${unit.name}", money(unit.priceMinor), emphasized = true)
                        LabelValueRow("Barcode", unit.barcode ?: "No pack barcode")
                        if (unit.archived) StatusPill("Archived selling unit", NoticeTone.Warning)
                        if (!unit.archived && product.stock >= 0.0 && product.stock % 1.0 == 0.0) {
                            val count = product.stock.toLong()
                            Text("Same stock: ${count / unit.baseUnitsPerUnit} ${unit.name} + ${count % unit.baseUnitsPerUnit} ${unit.baseUnit} (${BigDecimal.valueOf(product.stock).stripTrailingZeros().toPlainString()} ${product.unit} in total).", style = MaterialTheme.typography.bodySmall, color = PosPalette.Secondary)
                        }
                        Text(if (unit.archived) "Unavailable for new sales. Saved receipts keep this unit, conversion and sale price."
                            else "Available in Sell. Each ${unit.name} uses ${unit.baseUnitsPerUnit} ${unit.baseUnit} from the same stock pool.", style = MaterialTheme.typography.bodySmall, color = PosPalette.Secondary)
                        NeutralButton(if (unit.archived) "Restore selling unit" else "Archive selling unit", {
                            confirming = unit; error = null; message = null
                        }, enabled = !product.archived)
                    }
                }
            }
        }
        Surface(color = PosPalette.Surface, shape = MaterialTheme.shapes.medium) {
            Column(Modifier.fillMaxWidth().padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                SectionHeader("Add a selling unit", "Check the packaging: carton and pack sizes can differ between products")
                Column(Modifier.widthIn(max = 620.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    PosTextField(name, { name = it; error = null; message = null }, "Selling unit name", hint = "For example, carton of 24", enabled = !product.archived)
                    PosTextField(contains, { contains = it; error = null; message = null }, "How many ${product.unit} in one selling unit?", hint = "Whole units greater than 0", enabled = !product.archived)
                    PosTextField(price, { price = it; error = null; message = null }, "Selling price per ${name.trim().ifBlank { "pack" }} (₦)", hint = "Price for the whole pack, not one ${product.unit}", enabled = !product.archived)
                    PosTextField(barcode, { barcode = it; error = null; message = null }, "Pack barcode (optional)", hint = "Scan the pack's code; each barcode must be unique in this shop", enabled = !product.archived)
                    if (name.isNotBlank() && factor != null) {
                        LabelValueRow("Conversion to save", "1 ${name.trim()} = $factor ${product.unit}", emphasized = true)
                        if (amount != null) LabelValueRow("Customer pays for 1 ${name.trim()}", money(amount), emphasized = true)
                        Text("Selling 1 ${name.trim()} removes $factor ${product.unit}. This adds a way to sell existing stock; it does not add more stock.", style = MaterialTheme.typography.bodySmall, color = PosPalette.Secondary)
                    }
                    Text("The name, quantity, price and barcode cannot be edited after saving. For a change, archive this definition and add a new definition. An archived name can be reused; its barcode stays reserved, so use another barcode or leave it blank. Earlier receipts keep their original details.", style = MaterialTheme.typography.bodySmall, color = PosPalette.Secondary)
                    PosButton("Save selling unit", {
                        try {
                            require(name.isNotBlank()) { "Enter a name for the selling unit." }
                            require(factor != null) { "Enter a whole number from 1 to ${Int.MAX_VALUE}." }
                            require(amount != null) { "Enter the pack selling price in naira with at most two decimal places." }
                            repo.addSellingUnit(tenantId, product.id, name.trim(), factor, amount, barcode.trim().ifBlank { null }, operationId)
                            revision++; name = ""; contains = ""; price = ""; barcode = ""; error = null
                            message = "Selling unit saved on this computer. Choose it in Sell."
                        } catch (e: Exception) {
                            error = e.message ?: "The selling unit could not be saved. Your entries are still here."
                        }
                    }, enabled = !product.archived && result.isSuccess)
                }
            }
        }
    }
    confirming?.let { unit ->
        AlertDialog(
            onDismissRequest = { confirming = null },
            title = { Text(if (unit.archived) "Restore this selling unit?" else "Archive this selling unit?") },
            text = { Text("1 ${unit.name} = ${unit.baseUnitsPerUnit} ${unit.baseUnit}, at ${money(unit.priceMinor)} per ${unit.name}. " +
                if (unit.archived) "Restore this original definition for new sales. Check that its quantity and price are still correct. Existing stock and saved receipts stay unchanged."
                else "It will no longer be offered for new sales. Existing stock and saved receipts keep their quantities, conversions and prices. Its barcode remains reserved; its name can be reused for a replacement definition.") },
            confirmButton = { PosButton(if (unit.archived) "Restore selling unit" else "Archive selling unit", {
                try {
                    repo.archiveSellingUnit(tenantId, unit.id, !unit.archived)
                    revision++; error = null
                    message = if (unit.archived) "Selling unit restored" else "Selling unit archived; saved receipts are preserved"
                } catch (e: Exception) { error = e.message ?: "The selling unit could not be changed." }
                confirming = null
            }) },
            dismissButton = { TextButton({ confirming = null }) { Text("Cancel") } },
        )
    }
}
