package pos.android.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import pos.data.*
import pos.domain.*
import pos.output.*
import pos.screens.ReportDateRange
import pos.ui.components.*
import java.time.LocalDate
import java.time.ZoneId
import java.math.BigInteger

private data class AndroidDatedReport(val sales:List<Sale>,val returns:List<SaleReturn>,val expenses:List<Expense>,val cash:List<CashEntry>,val customers:List<Customer>,val products:List<Product>,val events:List<AuditEvent>,val datedSales:List<Sale> = emptyList(),val datedReturns:List<SaleReturn> = emptyList(),val datedCash:List<CashEntry> = emptyList(),val datedExpenses:List<Expense> = emptyList())
@Composable
fun AndroidReportsScreen(repo:PosRepository,tenantId:String) {
    val today=LocalDate.now(ZoneId.of("Africa/Lagos"))
    var from by rememberSaveable {mutableStateOf(today.withDayOfMonth(1).toString())}
    var through by rememberSaveable {mutableStateOf(today.toString())}
    var revision by remember {mutableIntStateOf(0)}
    val rangeResult=remember(from,through) {runCatching {ReportDateRange(LocalDate.parse(from),LocalDate.parse(through)).also {it.through.plusDays(1).atStartOfDay(ZoneId.of("Africa/Lagos")).toInstant()}}}
    val range=rangeResult.getOrNull()
    val loaded=operationsLoad(repo,tenantId,Triple(from,through,revision)) {
        requireNotNull(range) {"Enter valid YYYY-MM-DD dates with the end date on or after the start date."}
        repo.featureRead(tenantId,StaffPermission.VIEW_REPORTS) {
            val data=AndroidDatedReport(sales(tenantId),saleReturns(tenantId),expenses(tenantId),cashEntries(tenantId),customers(tenantId),products(tenantId,true),auditEvents(tenantId))
            data.copy(datedSales=data.sales.filter {range.contains(it.createdAt)},datedReturns=data.returns.filter {range.contains(it.createdAt)},datedCash=data.cash.filter {range.contains(it.createdAt)},datedExpenses=data.expenses.filter {range.contains(it.createdAt)})
        }
    }
    fun total(values:Iterable<Long>)=values.fold(BigInteger.ZERO) {sum,v ->sum+BigInteger.valueOf(v)}.longValueExact()
    LazyColumn(Modifier.fillMaxSize().imePadding(),verticalArrangement=Arrangement.spacedBy(12.dp),contentPadding=PaddingValues(bottom=24.dp)) {
        item {SectionHeader("Reports","Saved local records · Inclusive dates in West Africa Time")}
        item {PosTextField(from,{from=it},"From (YYYY-MM-DD)")}
        item {PosTextField(through,{through=it},"Through (YYYY-MM-DD)")}
        item {Row(horizontalArrangement=Arrangement.spacedBy(12.dp)) {
            TextButton(onClick={val day=LocalDate.now(ZoneId.of("Africa/Lagos")).toString();from=day;through=day}) {Text("Today")}
            TextButton(onClick={revision++}) {Text("Refresh")}
        }}
        item {OperationsLoadState(loaded,"dated reports",{revision++})}
        if(range!=null)loaded?.getOrNull()?.let {data ->
            val selectedSales=data.datedSales
            val selectedReturns=data.datedReturns
            val selectedCash=data.datedCash
            val selectedExpenses=data.datedExpenses
            val amounts=runCatching {listOf(total(selectedSales.map {it.totalMinor}),total(selectedSales.map {it.cashMinor}),total(selectedSales.map {it.creditMinor}),total(selectedSales.map {it.externalRecordedMinor}),total(selectedReturns.map {it.refundMinor}),total(selectedExpenses.map {it.amountMinor}),total(selectedCash.map {it.amountMinor}),total(data.customers.map {it.balanceMinor}))}
            amounts.exceptionOrNull()?.let {item {InlineNotice("Amounts need review","A saved total exceeds the supported exact range. No approximate amount is shown.",NoticeTone.Danger)}}
            amounts.getOrNull()?.let {value ->
                item {OperationsCard {
                    Text("$from through $through · WAT",style=MaterialTheme.typography.titleMedium)
                    LabelValueRow("Original sales total",money(value[0]),emphasized=true)
                    LabelValueRow("Cash applied at sale",money(value[1]));LabelValueRow("Customer credit granted at sale",money(value[2]))
                    LabelValueRow("External amounts recorded · not confirmed",money(value[3]))
                    LabelValueRow("Cash sale refunds in range",money(value[4]));LabelValueRow("Cash expenses recorded",money(value[5]))
                    LabelValueRow("Net signed cash movements",money(value[6]))
                    Text("Net cash movements exclude opening counts. They are not the current drawer balance or profit.")
                    LabelValueRow("Current customer debt · all dates",money(value[7]))
                    Text("Current debt is a present balance, not debt as of the selected end date.")
                }}
            }
            item {AndroidReportPdfSave(repo,tenantId,range)}
            item {AndroidReviewedCsvSave(repo,tenantId,StaffPermission.VIEW_REPORTS,"Preview sales CSV","sales-$from-$through.csv") {CsvData.sales(tenantId,selectedSales)}}
            item {AndroidReviewedCsvSave(repo,tenantId,StaffPermission.VIEW_REPORTS,"Preview cash-movement CSV","cash-$from-$through.csv") {CsvData.cashEntries(tenantId,selectedCash)}}
            item {AndroidReviewedCsvSave(repo,tenantId,StaffPermission.VIEW_REPORTS,"Preview cash-expense CSV","expenses-$from-$through.csv") {CsvData.expenses(tenantId,selectedExpenses)}}
            val productReport=runCatching {ProductPerformanceService.build(tenantId,data.sales,data.returns,range)}
            item {Text("Product sales and returns",style=MaterialTheme.typography.titleMedium)}
            productReport.exceptionOrNull()?.let {item {InlineNotice("Product report unavailable",it.message ?: "Saved source records need review.",NoticeTone.Warning)}}
            productReport.getOrNull()?.forEach {row ->item {OperationsCard {
                Text(row.name,style=MaterialTheme.typography.titleSmall)
                LabelValueRow("Sales less returned goods value",money(row.netSalesMinor))
                Text("Sold ${quantityLabel(row.soldBaseQuantity,row.quantityPrecision)} ${row.baseUnit} · Returned ${quantityLabel(row.returnedBaseQuantity,row.quantityPrecision)} ${row.baseUnit}")
            }}}
            if(productReport.isSuccess)item {AndroidReviewedCsvSave(repo,tenantId,StaffPermission.VIEW_REPORTS,"Preview product report CSV","products-$from-$through.csv") {ProductPerformanceService.csv(tenantId,data.sales,data.returns,range)}}
            val categories=runCatching {CategoryPerformanceService.build(tenantId,data.products,data.sales,data.returns,range)}
            item {Text("Current-category comparison",style=MaterialTheme.typography.titleMedium);Text("Both periods use current catalogue categories. This does not reconstruct historical category changes.")}
            categories.exceptionOrNull()?.let {item {InlineNotice("Category report unavailable",it.message ?: "Saved source records need review.",NoticeTone.Warning)}}
            categories.getOrNull()?.let {comparison ->
                item {Text("Compared with ${comparison.previous.from} through ${comparison.previous.through} · WAT")}
                val peak=comparison.rows.maxOfOrNull {kotlin.math.abs(it.netMinor.toDouble())}?.takeIf {it>0} ?: 1.0
                comparison.rows.forEach {row ->item {OperationsCard {
                    Text(row.category,style=MaterialTheme.typography.titleSmall)
                    LabelValueRow("Sales less returned goods value",money(row.netMinor));LabelValueRow("Previous period",money(row.previousNetMinor))
                    LabelValueRow("Change",money(row.changeMinor))
                    LinearProgressIndicator(progress={ (kotlin.math.abs(row.netMinor.toDouble())/peak).toFloat().coerceIn(0f,1f)},modifier=Modifier.fillMaxWidth())
                }}}
                item {AndroidReviewedCsvSave(repo,tenantId,StaffPermission.VIEW_REPORTS,"Preview category report CSV","categories-$from-$through.csv") {CategoryPerformanceService.csv(comparison)}}
            }
            val staff=runCatching {StaffPerformanceService.build(tenantId,data.sales,data.returns,data.events,range)}
            item {Text("Recorded staff activity",style=MaterialTheme.typography.titleMedium);Text("Historical staff are shown only when an actor was recorded. This is activity, not commission or cash custody.")}
            staff.exceptionOrNull()?.let {item {InlineNotice("Staff report unavailable",it.message ?: "Saved source records need review.",NoticeTone.Warning)}}
            staff.getOrNull()?.forEach {row ->item {OperationsCard {
                Text(row.actor?.name ?: "Staff identity not recorded",style=MaterialTheme.typography.titleSmall)
                LabelValueRow("Sales recorded",row.salesCount.toString());LabelValueRow("Sales after discounts",money(row.salesMinor))
                LabelValueRow("Cash refunds recorded",money(row.refundsMinor));LabelValueRow("Customer debt reduced",money(row.debtReducedMinor))
            }}}
            if(staff.isSuccess)item {AndroidReviewedCsvSave(repo,tenantId,StaffPermission.VIEW_REPORTS,"Preview staff report CSV","staff-$from-$through.csv") {StaffPerformanceService.csv(tenantId,data.sales,data.returns,data.events,range)}}
            item {InlineNotice("Profit is unavailable","Complete cost and expense recognition is still required. Sales minus purchases or drawer cash is not profit.",NoticeTone.Info)}
        }
    }
}
