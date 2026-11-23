package pos.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import pos.data.PosRepository
import pos.data.PurchaseReturnReview
import pos.data.reviewPurchaseReturn
import pos.data.savePurchaseReturn
import pos.domain.StockReceipt
import pos.domain.money
import pos.domain.quantityCount
import pos.domain.quantityLabel
import pos.ui.components.*
import java.util.UUID

@Composable
internal fun PurchaseReturnDialog(repo: PosRepository, receipt: StockReceipt, dismiss: () -> Unit, saved: () -> Unit) {
    var quantity by remember(receipt.id) { mutableStateOf("") }
    var reason by remember(receipt.id) { mutableStateOf("") }
    var receiveCash by remember(receipt.id) { mutableStateOf(false) }
    var review by remember(receipt.id) { mutableStateOf<PurchaseReturnReview?>(null) }
    var error by remember(receipt.id) { mutableStateOf<String?>(null) }
    val operationId = remember(receipt.id) { UUID.randomUUID().toString() }
    val preview = review
    AlertDialog(
        onDismissRequest = dismiss,
        title = { Text(if (preview == null) "Return to supplier" else "Review supplier return") },
        text = {
            Column(
                Modifier.widthIn(max = 520.dp).heightIn(max = 520.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(receipt.productName, style = MaterialTheme.typography.titleMedium)
                Text("${receipt.supplierName} · Receipt ${receipt.id.takeLast(8).uppercase()}")
                if (preview == null) {
                    PosTextField(quantity, { quantity = it; error = null }, "Quantity to return in ${receipt.unit}")
                    PosTextField(reason, { reason = it; error = null }, "Reason for return", hint = "For example, supplier accepted the wrong items back")
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(receiveCash, { receiveCash = it; error = null })
                        Text("Receive the difference in cash from the supplier")
                    }
                    Text("The return reduces what you owe first. Any difference received goes into today's drawer.",
                        style = MaterialTheme.typography.bodySmall)
                } else {
                    val returnedQuantity = quantityLabel(preview.quantity.toLong(), receipt.quantityPrecision)
                    val stockAfter = quantityLabel(preview.stockAfter, receipt.basePrecision)
                    val stockRemoved = quantityLabel(preview.stockBefore - preview.stockAfter, receipt.basePrecision)
                    LabelValueRow("Returning to supplier", "$returnedQuantity ${receipt.unit}")
                    LabelValueRow("Remove from stock", "$stockRemoved ${receipt.baseUnit}")
                    LabelValueRow("Stock after return", "$stockAfter ${receipt.baseUnit}")
                    HorizontalDivider()
                    LabelValueRow("Owed before return", money(preview.owedBeforeMinor))
                    LabelValueRow("Reduction in supplier debt", money(preview.creditMinor))
                    LabelValueRow("Owed after return", money(preview.owedBeforeMinor - preview.creditMinor), emphasized = true)
                    if (preview.cashReceivedMinor > 0) {
                        LabelValueRow("Cash to receive from supplier", money(preview.cashReceivedMinor), emphasized = true)
                        Text("Save after you receive this cash.", style = MaterialTheme.typography.bodySmall)
                    }
                    LabelValueRow("Reason", reason.trim())
                }
                error?.let { InlineNotice("Return could not be saved", it, NoticeTone.Danger) }
            }
        },
        confirmButton = {
            val action = when {
                preview == null -> "Review return"
                preview.cashReceivedMinor > 0 -> "Record cash received and save"
                else -> "Save return"
            }
            PosButton(action, {
                error = null
                val result = runCatching {
                    if (preview == null) {
                        val count = requireNotNull(quantityCount(quantity, receipt.quantityPrecision)) {
                            "Enter a positive quantity in ${receipt.unit} using the receipt's precision."
                        }
                        require(reason.trim().length in 3..500 && reason.trim().none { it.isISOControl() }) {
                            "Enter a reason from 3 to 500 printable characters."
                        }
                        review = repo.reviewPurchaseReturn(receipt.tenantId, receipt.id, count, receiveCash)
                    } else {
                        repo.savePurchaseReturn(receipt.tenantId, preview, reason, operationId)
                    }
                }
                result.onFailure {
                    review = null
                    error = it.message ?: "Check the return details and try again."
                }
                if (result.isSuccess && preview != null) saved()
            })
        },
        dismissButton = {
            TextButton({
                if (preview == null) dismiss()
                else {
                    review = null
                    error = null
                }
            }) { Text(if (preview == null) "Cancel" else "Edit return") }
        },
    )
}
