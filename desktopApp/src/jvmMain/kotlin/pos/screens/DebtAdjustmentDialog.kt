package pos.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import java.util.UUID
import pos.data.DebtAdjustment
import pos.data.DebtAdjustmentReview
import pos.data.PosRepository
import pos.data.reviewDebtAdjustment
import pos.data.saveDebtAdjustment
import pos.domain.Customer
import pos.domain.money
import pos.domain.parseNaira
import pos.ui.components.*

@Composable
internal fun DebtAdjustmentDialog(
    repo: PosRepository,
    customer: Customer,
    saleId: String,
    original: DebtAdjustment?,
    close: () -> Unit,
    saved: (DebtAdjustment) -> Unit,
) {
    var entered by remember { mutableStateOf("") }
    var reason by remember { mutableStateOf("") }
    var review by remember { mutableStateOf<DebtAdjustmentReview?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var operationId by remember { mutableStateOf(UUID.randomUUID().toString()) }
    val action = if (original == null) "Write off debt" else "Restore debt"
    val amount = original?.review?.amountMinor ?: parseNaira(entered)
    val receipt = saleId.takeLast(8).uppercase()
    val reviewed = review
    AlertDialog(
        onDismissRequest = close,
        title = { Text(if (reviewed == null) action else "Review debt adjustment") },
        text = {
            Column(
                Modifier.widthIn(max = 480.dp).heightIn(max = 500.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text("${customer.name} · Receipt $receipt", style = MaterialTheme.typography.titleSmall)
                Text(if (original == null) "Remove an amount you no longer expect this customer to pay."
                    else "Add the original written-off amount back to what this customer owes.")
                Text("This changes the amount owed. No cash moves.", style = MaterialTheme.typography.bodySmall)
                if (reviewed == null) {
                    if (original == null) {
                        PosTextField(entered, { entered = it; error = null }, "Amount to write off (₦)")
                    } else {
                        LabelValueRow("Restore amount", money(original.review.amountMinor))
                        Text(original.reason, style = MaterialTheme.typography.bodySmall)
                    }
                    PosTextField(reason, { reason = it; error = null }, "Reason", singleLine = false)
                } else {
                    LabelValueRow("Amount", money(reviewed.amountMinor))
                    LabelValueRow("Owed on this receipt before", money(reviewed.receiptBeforeMinor))
                    LabelValueRow("Owed on this receipt after", money(reviewed.receiptAfterMinor))
                    LabelValueRow("Total owed before", money(reviewed.customerBeforeMinor))
                    LabelValueRow("Total owed after", money(reviewed.customerAfterMinor), emphasized = true)
                    LabelValueRow("Reason", reason.trim())
                }
                error?.let { InlineNotice("Debt adjustment unavailable", it, NoticeTone.Danger) }
            }
        },
        confirmButton = {
            PosButton(if (reviewed == null) "Review adjustment" else action, {
                error = null
                if (reviewed == null) {
                    runCatching {
                        repo.reviewDebtAdjustment(customer.tenantId, saleId, requireNotNull(amount), original?.id)
                    }.onSuccess {
                        operationId = UUID.randomUUID().toString()
                        review = it
                    }.onFailure { error = it.message ?: "Check the amount and try again." }
                } else {
                    runCatching { repo.saveDebtAdjustment(customer.tenantId, reviewed, reason, operationId) }
                        .onSuccess(saved)
                        .onFailure { error = it.message ?: "Refresh the customer and try again." }
                }
            }, enabled = amount != null && amount > 0 && reason.trim().length in 3..250 &&
                reason.trim().none { it.isISOControl() })
        },
        dismissButton = {
            TextButton({
                if (review == null) close() else {
                    review = null
                    error = null
                }
            }) { Text(if (review == null) "Cancel" else "Edit adjustment") }
        },
    )
}
