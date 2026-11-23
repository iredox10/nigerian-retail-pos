package pos.screens

import kotlinx.coroutines.delay

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import java.time.Instant
import java.util.UUID
import pos.data.*
import pos.domain.isValidCreditDueDate
import pos.domain.quantityLabel
import pos.ui.components.*

@Composable
internal fun OrderReservationDialog(
    repo: PosRepository, state: CustomerOrderState, revision: Int, release: Boolean,
    dismiss: () -> Unit, saved: (String) -> Unit,
) {
    val order = state.order
    val today by produceState(orderReservationDate(Instant.now()), order.id) {
        while (true) {
            delay(30_000)
            value = orderReservationDate(Instant.now())
        }
    }
    var expiresOn by remember(order.id) { mutableStateOf(order.collectOn?.takeIf { it >= today } ?: today) }
    var reason by remember(order.id) { mutableStateOf("") }
    var error by remember(order.id) { mutableStateOf<String?>(null) }
    val operationId = remember(order.id, release) { UUID.randomUUID().toString() }
    val action = if (release) "Release stock hold" else "Hold stock"
    AlertDialog(
        onDismissRequest = dismiss,
        title = { Text(action) },
        text = {
            Column(
                Modifier.heightIn(max = 380.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                if (release) {
                    Text("The remaining goods will be available for other sales.")
                    PosTextField(reason, { reason = it; error = null }, "Reason for release", Modifier.fillMaxWidth())
                } else {
                    Text("Set aside the goods still waiting for ${order.customerName}.")
                    order.lines.filter { state.remaining.getValue(it.key) > 0 }.forEach { line ->
                        val quantity = quantityLabel(state.remaining.getValue(line.key).toLong(), line.quantityPrecision)
                        LabelValueRow(line.productName, "$quantity ${line.sellingUnit}")
                    }
                    PosTextField(expiresOn, { expiresOn = it; error = null }, "Hold through (WAT)",
                        Modifier.fillMaxWidth(), hint = "YYYY-MM-DD")
                }
                error?.let { InlineNotice("Stock hold could not be saved", it, NoticeTone.Danger) }
            }
        },
        confirmButton = {
            PosButton(action, {
                runCatching {
                    if (release) {
                        repo.releaseCustomerOrderStock(order.tenantId, order.id, reason, state.history.size, revision, operationId)
                        "Stock hold released."
                    } else {
                        repo.holdCustomerOrderStock(order.tenantId, order.id, expiresOn.trim(), state.history.size, revision, operationId)
                        "Stock held through ${expiresOn.trim()} (WAT)."
                    }
                }.onSuccess(saved).onFailure { error = it.message ?: "Check the current order and try again." }
            }, enabled = if (release) reason.trim().length in 3..250 else isValidCreditDueDate(expiresOn.trim()) && expiresOn.trim() >= today)
        },
        dismissButton = { TextButton(dismiss) { Text("Cancel") } },
    )
}

internal fun orderReservationStatus(state: CustomerOrderState, latest: OrderReservationEvent?, today: String): String {
    val expiry = latest?.expiresOn ?: return if (latest == null) "No stock held" else "Released"
    if (!state.open) {
        val closedOn = orderReservationDate(Instant.parse(state.history.last().createdAt))
        if (closedOn <= expiry) return if (state.cancelled) "Order cancelled" else "Goods collected"
    }
    return if (expiry >= today) "Held through $expiry (WAT)" else "Expired after $expiry (WAT)"
}
