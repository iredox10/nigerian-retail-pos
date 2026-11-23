package pos.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import pos.data.PosRepository
import pos.domain.*
import pos.output.CsvService
import pos.output.ProductPerformanceService
import pos.output.StaffPerformanceService
import pos.ui.components.*
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.math.BigInteger

private val reportZone: ZoneId = ZoneId.of("Africa/Lagos")

/** Date boundaries are inclusive WAT dates, represented as a half-open Instant interval. */
data class ReportDateRange(val from: LocalDate, val through: LocalDate) {
    init { require(!through.isBefore(from)) { "End date must be on or after the start date." } }
    fun contains(iso: String): Boolean {
        val instant = Instant.parse(iso)
        return !instant.isBefore(from.atStartOfDay(reportZone).toInstant()) && instant.isBefore(through.plusDays(1).atStartOfDay(reportZone).toInstant())
    }
}

private data class ReportRecords(
    val sales: List<Sale>, val customers: List<Customer>, val products: List<Product>,
    val expenses: List<Expense>, val cash: List<CashEntry>, val receipts: List<StockReceipt>,
    val returns: List<SaleReturn>, val supplierPayments: List<SupplierPayment>,
)

@Composable
fun ReportsScreen(repo: PosRepository, tenantId: String) {
    val today = remember { LocalDate.now(reportZone) }
    var start by rememberSaveable(tenantId) { mutableStateOf(today.withDayOfMonth(1).toString()) }
    var end by rememberSaveable(tenantId) { mutableStateOf(today.toString()) }
    var revision by remember { mutableIntStateOf(0) }
    var selectedExport by rememberSaveable { mutableStateOf("Sales") }
    var exportMenu by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    val loaded = remember(tenantId, revision) { runCatching {
        ReportRecords(repo.sales(tenantId), repo.customers(tenantId), repo.products(tenantId,includeArchived=true), repo.expenses(tenantId), repo.cashEntries(tenantId), repo.stockReceipts(tenantId), repo.saleReturns(tenantId), repo.supplierPayments(tenantId))
    } }
    val rangeResult = remember(start, end) { runCatching { ReportDateRange(LocalDate.parse(start), LocalDate.parse(end)) } }
    val range = rangeResult.getOrNull()
    val records = loaded.getOrNull()
    fun sum(values: Iterable<Long>) = values.fold(BigInteger.ZERO) { total, value -> total.add(BigInteger.valueOf(value)) }.longValueExact()

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(20.dp)) {
        SectionHeader("Your shop's reports", "Local records only · Dates include the whole day in West Africa Time")
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.Bottom) {
            PosTextField(start, { start = it; error = null }, "From (YYYY-MM-DD)", Modifier.weight(1f))
            PosTextField(end, { end = it; error = null }, "Through (YYYY-MM-DD)", Modifier.weight(1f))
            NeutralButton("Today", { val currentDay=LocalDate.now(reportZone).toString(); start = currentDay; end = currentDay })
            NeutralButton("Refresh", { revision++; message = null; error = null })
        }
        rangeResult.exceptionOrNull()?.let { InlineNotice("Check the dates", "Use YYYY-MM-DD. The end date must be on or after the start date.", NoticeTone.Warning) }
        loaded.exceptionOrNull()?.let { InlineNotice("Reports could not be loaded", it.message ?: "Refresh to try again.", NoticeTone.Danger) }
        error?.let { InlineNotice("Could not finish", it, NoticeTone.Danger) }
        message?.let { InlineNotice(it, tone = NoticeTone.Success) }
        if (range != null && records != null) {
            val calculated = runCatching {
                val sales = records.sales.filter { range.contains(it.createdAt) }
                val expenses = records.expenses.filter { range.contains(it.createdAt) }
                val cash = records.cash.filter { range.contains(it.createdAt) }
                val receipts = records.receipts.filter { range.contains(it.createdAt) }
                val returns = records.returns.filter { range.contains(it.createdAt) }
                val supplierPayments = records.supplierPayments.filter { range.contains(it.createdAt) }
                ReportAmounts(
                    sum(sales.map { it.totalMinor }), sum(sales.map { it.cashMinor }), sum(sales.map { it.creditMinor }),
                    sum(records.customers.map { it.balanceMinor }), sum(cash.filter { it.kind == "repayment" }.map { it.amountMinor }),
                    sum(expenses.map { it.amountMinor }), sum(returns.map { it.refundMinor }), sum(receipts.map { it.totalCostMinor }),
                    sum(receipts.map { it.paidCashMinor }), sum(supplierPayments.map { it.amountMinor }), sum(cash.map { it.amountMinor }),sum(sales.map {it.externalRecordedMinor}),sum(returns.map {it.creditReductionMinor}),
                    sum(sales.map { it.exchange?.amountMinor ?: 0L }), sum(returns.map { it.returnedValueMinor }),
                )
            }
            calculated.exceptionOrNull()?.let { InlineNotice("Report could not be calculated", it.message ?: "A stored date or amount needs checking.", NoticeTone.Danger) }
            calculated.getOrNull()?.let { amounts ->
                Surface(shape = MaterialTheme.shapes.medium) { Column(Modifier.fillMaxWidth().padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    SectionHeader("Sales in this date range", "$start through $end · WAT")
                    LabelValueRow("Original sales total", money(amounts.sales), emphasized = true)
                    LabelValueRow("Cash applied when these sales were made", money(amounts.saleCash))
                    if (amounts.exchangeApplied > 0) LabelValueRow("Returned value used for replacement sales", money(amounts.exchangeApplied))
                    if (amounts.returnedValue > 0) LabelValueRow("Value of goods returned in range", money(amounts.returnedValue))
                    LabelValueRow("Credit granted when these sales were made", money(amounts.saleCredit))
                    LabelValueRow("External portions recorded · not confirmed",money(amounts.externalRecorded))
                    LabelValueRow("Customer debt reduced by returns in range",money(amounts.creditReduced))
                    LabelValueRow("Cash customer repayments received in range", money(amounts.repayments))
                    LabelValueRow("Cash refunds recorded in range", money(amounts.refunds))
                    Text("Refunds use the return date and may relate to sales outside this range. Original sale totals and credit do not change after later repayments or returns.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    HorizontalDivider()
                    LabelValueRow("Customers still owe — all dates, current", money(amounts.currentDebt), emphasized = true)
                    Text("Current debt is a live shop-wide balance, not the historical debt as of your end date.", style = MaterialTheme.typography.bodySmall)
                } }
                Surface(shape = MaterialTheme.shapes.medium) { Column(Modifier.fillMaxWidth().padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    SectionHeader("Costs and cash movements", "Keep stock purchases separate from operating expenses")
                    LabelValueRow("Operating expenses paid in range", money(amounts.expenses))
                    LabelValueRow("Stock purchase value received in range", money(amounts.purchases))
                    LabelValueRow("Cash paid when receiving that stock", money(amounts.purchaseCash))
                    LabelValueRow("Later supplier payments made in range", money(amounts.supplierPayments))
                    LabelValueRow("Net recorded cash movements in range", money(amounts.netCash))
                    Text("Net movements include manual cash in/out and unassigned entries, but not opening cash counts. This is not the current drawer balance. Use Money → Today's cash for an active shift.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                } }
                InlineNotice("Profit is not available yet", "Historical cost of goods sold is not recorded for every sale. Sales minus stock purchases is not profit; no profit figure is estimated here.", NoticeTone.Info)
            }

            Surface(shape = MaterialTheme.shapes.medium) { Column(Modifier.fillMaxWidth().padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                SectionHeader("Export this shop's records", "CSV for a spreadsheet · Current product export includes archived items; customer and stock snapshots are not historical views")
                if(selectedExport=="Staff activity") Text("Sales and returns are grouped by the person who recorded each action, using their saved name and role. Returns are not charged to the original salesperson. Earlier actions may have no actor recorded. This is not a commission or profit report.",style=MaterialTheme.typography.bodySmall)
                if(selectedExport=="Product performance") Text("Sales and returned goods use their own recorded dates. Returns may relate to older sales, so net sales and quantities can be negative. This is not profit or cash received. Quantities use each historical base unit.",style=MaterialTheme.typography.bodySmall)
                if(selectedExport=="Payment assignments") Text("Assignment dates use the selected period; Active/Undone status reflects all corrections saved now.",style=MaterialTheme.typography.bodySmall)
                if(selectedExport=="Assignment corrections") Text("Includes undo events recorded in the selected period, even when the original assignment is older.",style=MaterialTheme.typography.bodySmall)
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Box {
                        NeutralButton("$selectedExport ▾", { exportMenu = true })
                        DropdownMenu(exportMenu, { exportMenu = false }) {
                            listOf("Sales", "Product performance", "Staff activity", "Products", "Customers", "Expenses", "Cash activity", "Stock received", "Payment assignments", "Assignment corrections", "Activity history", "Stock movements", "Low stock").forEach { kind -> DropdownMenuItem(text = { Text(kind) }, onClick = { selectedExport = kind; exportMenu = false }) }
                        }
                    }
                    PosButton("Save CSV file", {
                        try {
                            val csv = when(selectedExport) {
                                "Staff activity" -> StaffPerformanceService.csv(tenantId,records.sales,records.returns,repo.auditEvents(tenantId),range)
                                "Product performance" -> ProductPerformanceService.csv(tenantId,records.sales,records.returns,range)
                                "Sales" -> CsvService.sales(tenantId, records.sales.filter { range.contains(it.createdAt) })
                                "Low stock" -> CsvService.products(tenantId,records.products.filter { !it.archived && it.reorderLevel?.let { level -> it.stock<=level }==true })
                                "Stock movements" -> CsvService.inventoryMovements(tenantId,records.products,records.products.flatMap { repo.inventoryMovements(tenantId,it.id) }.filter { range.contains(it.createdAt) })
                                "Products" -> CsvService.products(tenantId, records.products)
                                "Customers" -> CsvService.customers(tenantId, records.customers)
                                "Expenses" -> CsvService.expenses(tenantId, records.expenses.filter { range.contains(it.createdAt) })
                                "Cash activity" -> CsvService.cashEntries(tenantId, records.cash.filter { range.contains(it.createdAt) })
                                "Payment assignments" -> {
                                    val assignments=repo.repaymentAllocations(tenantId,includeReversed=true).filter { range.contains(it.createdAt) }
                                    val ids=assignments.map {it.id}.toSet()
                                    CsvService.repaymentAllocations(tenantId,assignments,repo.allocationReversals(tenantId).filter {it.allocationId in ids})
                                }
                                "Activity history" -> CsvService.auditEvents(tenantId,repo.auditEvents(tenantId).filter {range.contains(it.createdAt)})
                                "Assignment corrections" -> CsvService.allocationReversals(tenantId,repo.allocationReversals(tenantId).filter {range.contains(it.createdAt)})
                                else -> CsvService.stockReceipts(tenantId, records.receipts.filter { range.contains(it.createdAt) })
                            }
                            val file = CsvService.save(csv, "retail-pos-${selectedExport.lowercase().replace(' ', '-')}-${if(selectedExport in listOf("Products", "Customers", "Low stock")) "current" else "$start-to-$end"}.csv")
                            if(file != null) { message = "CSV saved to ${file.absolutePath}"; error = null }
                        } catch(e: Exception) { error = e.message ?: "CSV could not be saved. No business records changed."; message = null }
                    })
                }
                Text("Text that could be interpreted as a spreadsheet formula is prefixed with an apostrophe. Numeric amounts stay numeric. Store exported customer and financial records somewhere you control.", style = MaterialTheme.typography.bodySmall)
            } }
        }
    }
}

private data class ReportAmounts(val sales: Long, val saleCash: Long, val saleCredit: Long, val currentDebt: Long, val repayments: Long, val expenses: Long, val refunds: Long, val purchases: Long, val purchaseCash: Long, val supplierPayments: Long, val netCash: Long,val externalRecorded:Long,val creditReduced:Long, val exchangeApplied: Long, val returnedValue: Long)
