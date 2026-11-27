package pos.android.ui

import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import pos.data.*
import pos.domain.*
import pos.output.SupplierAccountStatementService
import pos.ui.components.*
import java.time.LocalDate
import java.time.ZoneId

/** Reconciles complete, explicitly linked history before selecting a WAT statement period. */
@Composable
internal fun AndroidSupplierStatement(repo: PosRepository, tenantId: String, profile: SupplierProfile, dismiss: () -> Unit) {
    val today = LocalDate.now(ZoneId.of("Africa/Lagos"))
    var from by rememberSaveable { mutableStateOf(today.withDayOfMonth(1).toString()) }
    var through by rememberSaveable { mutableStateOf(today.toString()) }
    var revision by remember { mutableIntStateOf(0) }
    var visibleEvents by remember(from, through) { mutableIntStateOf(20) }
    val loaded = operationsLoad(repo, tenantId, Triple(from, through, revision)) {
        repo.featureRead(tenantId, StaffPermission.MANAGE_SUPPLIERS) {
            require(isValidCreditDueDate(from) && isValidCreditDueDate(through)) { "Enter valid dates as YYYY-MM-DD." }
            val current = supplierProfiles(tenantId, true).single { it.id == profile.id }
            SupplierAccountStatementService.build(current, supplierReceiptLinks(tenantId), stockReceipts(tenantId),
                supplierPayments(tenantId), LocalDate.parse(from), LocalDate.parse(through),
                purchaseReturns(tenantId), supplierCreditAllocations(tenantId))
        }
    }
    OperationsDialog("Supplier statement", dismiss, "Close", action = { dismiss() }) {
        Text(profile.details.name, style = MaterialTheme.typography.titleMedium)
        Text("Only receipts explicitly linked to this supplier account are included. Similar names and older name-only receipts are not silently combined.")
        PosTextField(from, { from = it }, "From (YYYY-MM-DD)")
        PosTextField(through, { through = it }, "Through (YYYY-MM-DD)")
        OperationsLoadState(loaded, "supplier statement", { revision++ })
        loaded?.getOrNull()?.let { account ->
            val statement = account.statement
            Text("Inclusive West Africa Time dates. Cash payments, purchase returns and credit notes stay separate.")
            LabelValueRow("Opening amount owed", money(statement.openingMinor))
            statement.events.take(visibleEvents).forEach { event ->
                HorizontalDivider()
                Text("${event.createdAt.take(19)} UTC · ${event.activity}")
                Text("Buying receipt ${event.receiptId.takeLast(8)}")
                if (event.purchaseMinor != 0L) LabelValueRow("Buying total", money(event.purchaseMinor))
                if (event.paidAtReceiptMinor != 0L) LabelValueRow("Cash paid on arrival", money(event.paidAtReceiptMinor))
                if (event.laterPaymentMinor != 0L) LabelValueRow("Later cash paid", money(event.laterPaymentMinor))
                if (event.returnCreditMinor != 0L) LabelValueRow("Debt reduced by return", money(event.returnCreditMinor))
                if (event.returnCashMinor != 0L) LabelValueRow("Cash received on return", money(event.returnCashMinor))
                if (event.creditNoteMinor != 0L) LabelValueRow("Credit note adjustment", money(event.creditNoteMinor))
                LabelValueRow("Amount owed after event", money(event.balanceAfterMinor))
            }
            if (statement.events.size > visibleEvents) TextButton(onClick = { visibleEvents += 20 }) { Text("Show more statement events") }
            LabelValueRow("Closing amount owed through date", money(statement.closingMinor), emphasized = true)
            LabelValueRow("Current amount owed · all dates", money(statement.currentOutstandingMinor))
            Text("Unallocated supplier credit notes are shown separately in Supplier credit notes. They do not reduce these receipt balances until applied.")
            AndroidReviewedCsvSave(repo, tenantId, StaffPermission.MANAGE_SUPPLIERS, "Preview supplier statement CSV", "supplier-statement-${profile.id}.csv") {
                SupplierAccountStatementService.csv(account)
            }
        }
    }
}
