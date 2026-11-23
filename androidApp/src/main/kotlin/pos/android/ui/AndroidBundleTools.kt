package pos.android.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import pos.data.*
import pos.domain.*
import pos.ui.components.*
import java.util.UUID

@Composable
fun AndroidBundlesScreen(repo: PosRepository, tenantId: String, onChanged: () -> Unit) = key(tenantId) {
    var revision by remember { mutableIntStateOf(0) }
    var selling by remember { mutableStateOf<BundleDefinition?>(null) }
    var saved by remember { mutableStateOf<Sale?>(null) }
    var receiptId by remember { mutableStateOf<String?>(null) }
    val loaded = operationsLoad(repo, tenantId, revision) { repo.bundleDefinitions(tenantId) }
    if (receiptId != null) {
        AndroidReceiptScreen(repo, tenantId, requireNotNull(receiptId)) { receiptId = null }
    } else {
        selling?.let { bundle -> AndroidBundleSale(repo, tenantId, bundle, { selling = null }) { sale ->
            selling = null; saved = sale; revision++; onChanged()
        } }
        LazyColumn(Modifier.fillMaxSize().imePadding(), verticalArrangement = Arrangement.spacedBy(12.dp), contentPadding = PaddingValues(bottom = 24.dp)) {
            item { SectionHeader("Bundles", "Sell saved groups of real stock items with a reviewed cash payment.") }
            saved?.let { sale -> item { OperationsCard {
                Text("Bundle sale saved on this device", style = MaterialTheme.typography.titleMedium)
                LabelValueRow("Sale total", money(sale.totalMinor), emphasized = true)
                sale.cashGivenMinor?.let { LabelValueRow("Cash given", money(it)) }
                sale.changeMinor?.let { LabelValueRow("Change to give", money(it)) }
                Text("Receipt ${sale.id.takeLast(8)}")
                PosButton("Print or share receipt", { receiptId = sale.id }, Modifier.fillMaxWidth())
                TextButton({ saved = null }) { Text("Continue") }
            } } }
            item { OperationsLoadState(loaded, "bundles", { revision++ }) }
            val bundles = loaded?.getOrNull().orEmpty()
            if (loaded?.isSuccess == true && bundles.isEmpty()) item { EmptyState("No saved bundles", "Bundle definitions are not created in this Android screen yet. No stock group or sale is fabricated.") }
            items(bundles, key = { it.id }) { bundle -> OperationsCard {
                Text(bundle.terms.name, style = MaterialTheme.typography.titleMedium)
                Text("Revision ${bundle.version} · ${if (bundle.terms.active) "Active" else "Inactive"}", style = MaterialTheme.typography.bodySmall)
                LabelValueRow("Price per bundle", bundle.terms.fixedPriceMinor?.let(::money) ?: "Current component prices")
                bundle.terms.components.forEach { component -> Text("${quantityLabel(component.quantity.toLong(), component.quantityPrecision)} ${component.sellingUnit} · ${component.productName}") }
                PosButton("Sell bundle", { selling = bundle; saved = null }, Modifier.fillMaxWidth(), enabled = bundle.terms.active)
            } }
            item { Text("Bundle definition editing, mixed baskets and non-cash bundle payments are not available in this Android screen. Discounted bundle prices require discount permission, and every sale checks minimum prices and available component stock.", style = MaterialTheme.typography.bodySmall) }
        }
    }
}

@Composable
private fun AndroidBundleSale(repo: PosRepository, tenantId: String, bundle: BundleDefinition, dismiss: () -> Unit, saved: (Sale) -> Unit) {
    var count by rememberSaveable { mutableStateOf("1") }
    var cash by rememberSaveable { mutableStateOf("") }
    var review by remember { mutableStateOf<BundleSaleReview?>(null) }
    var confirmed by remember { mutableStateOf(false) }
    val operation = rememberSaveable { UUID.randomUUID().toString() }
    OperationsDialog(bundle.terms.name, dismiss, if (review == null) "Review bundle sale" else "Finish bundle sale", action = {
        val number = requireNotNull(count.toIntOrNull()?.takeIf { it in 1..1_000_000 }) { "Enter from 1 to 1,000,000 whole bundles." }
        val checked = review
        if (checked == null) {
            review = withContext(Dispatchers.IO) { repo.reviewBundleSale(tenantId, bundle.id, number) }
            cash = ""; confirmed = false
        } else {
            require(checked.count == number) { "Review the changed bundle quantity again." }
            val given = requireNotNull(parseNaira(cash)) { "Enter the cash given by the customer." }
            require(given >= checked.totalMinor) { "Cash is short by ${money(checked.totalMinor - given)}. This screen does not create customer debt." }
            require(confirmed) { "Confirm the components and counted cash." }
            val sale = withContext(Dispatchers.IO) { repo.completeBundleCashSale(tenantId, checked, given, operation) }
            saved(sale)
        }
    }) {
        PosTextField(count, { count = it; review = null; confirmed = false }, "Number of whole bundles")
        val checked = review
        if (checked == null) {
            Text("The app checks the latest definition, quantities, prices and stock when you review.")
            bundle.terms.components.forEach { Text("${quantityLabel(it.quantity.toLong(), it.quantityPrecision)} ${it.sellingUnit} · ${it.productName}") }
        } else {
            Text("${checked.count} whole bundle(s) · Revision ${checked.version}", style = MaterialTheme.typography.titleSmall)
            checked.lines.forEach { line -> LabelValueRow("${line.product.name} · ${quantityLabel(line.quantity.toLong(), line.quantityPrecision)} ${line.sellingUnit}", money(line.totalMinor)) }
            LabelValueRow("Normal component total", money(checked.normalTotalMinor))
            LabelValueRow("Cash sale total", money(checked.totalMinor), emphasized = true)
            PosTextField(cash, { cash = it; confirmed = false }, "Cash given by customer (₦)")
            TextButton({ cash = (java.math.BigDecimal.valueOf(checked.totalMinor, 2)).toPlainString(); confirmed = false }) { Text("Exact amount") }
            parseNaira(cash)?.let { value -> LabelValueRow(if (value >= checked.totalMinor) "Change to give" else "Still to pay", money(if (value >= checked.totalMinor) value - checked.totalMinor else checked.totalMinor - value)) }
            OperationsCheck(confirmed, { confirmed = it }, "I checked these goods and counted the customer's cash.")
            Text("Saving deducts each component once and records actual cash with change. A changed definition, price, reservation or stock balance requires a fresh review.", style = MaterialTheme.typography.bodySmall)
            TextButton({ review = null; confirmed = false }) { Text("Review again") }
        }
    }
}
