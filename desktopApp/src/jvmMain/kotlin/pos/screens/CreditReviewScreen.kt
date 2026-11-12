package pos.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import pos.data.PosRepository
import pos.domain.money
import pos.output.DebtAgeingService
import pos.ui.components.*
import java.time.LocalDate
import java.time.ZoneId

/** Review agreed dates without pretending that unallocated repayments settle specific receipts. */
@Composable
fun CreditReviewScreen(repo:PosRepository,tenantId:String) {
    key(tenantId) {
        var revision by remember { mutableIntStateOf(0) }
        var search by remember { mutableStateOf("") }
        var pastDueOnly by remember { mutableStateOf(false) }
        val today=remember(revision) { LocalDate.now(ZoneId.of("Africa/Lagos")) }
        val loaded=remember(revision) {runCatching {DebtAgeingService.build(tenantId,repo.customers(tenantId),repo.sales(tenantId),repo.repayments(tenantId),today)}}
        Column(Modifier.fillMaxSize(),verticalArrangement=Arrangement.spacedBy(16.dp)) {
            SectionHeader("Review customer credit","As of $today · West Africa Time · Saved records on this computer")
            InlineNotice("Review dates alongside repayments","Repayments reduce total customer debt but are not assigned to individual sales. A past-due sale below may have been partly or fully covered by later payments. No unpaid overdue invoice balance is calculated.",NoticeTone.Info)
            PosTextField(search,{search=it},"Find a customer",Modifier.fillMaxWidth(),hint="Name or phone")
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),horizontalArrangement=Arrangement.spacedBy(16.dp)) {
                FilterChip(pastDueOnly,{pastDueOnly=!pastDueOnly},label={Text("Customers with past-due sale dates")})
                NeutralButton("Refresh",{revision++})
            }
            loaded.exceptionOrNull()?.let {InlineNotice("Credit review could not be loaded",it.message ?: "Refresh to try again.",NoticeTone.Danger)}
            loaded.getOrNull()?.let { rows ->
                val visible=rows.filter { (!pastDueOnly || it.pastDueSales.isNotEmpty()) && (it.customer.name.contains(search,true) || it.customer.phone?.contains(search)==true) }
                LazyColumn(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(16.dp)) {
                    if(visible.isEmpty())item {EmptyState("No matching customer debts","Customers with a current balance appear here. Use Customers to review a profile or record cash received.")}
                    items(visible,key={it.customer.id}) { row ->
                        Surface(shape=MaterialTheme.shapes.medium) {
                            Column(Modifier.fillMaxWidth().padding(20.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
                                SectionHeader(row.customer.name,row.customer.phone ?: "Phone not recorded")
                                LabelValueRow("Current customer debt — all dates",money(row.currentDebtMinor),emphasized=true)
                                LabelValueRow("Later repayments — all dates",money(row.repaymentsTotalMinor))
                                Text("${row.pastDueSales.size} original sales with past-due dates · ${row.dueTodaySales.size} due today · ${row.futureDueSales.size} future dates · ${row.undatedSales.size} without a due date",style=MaterialTheme.typography.bodySmall)
                                if(row.pastDueSales.isNotEmpty()) {
                                    HorizontalDivider()
                                    Text("Original sales with past-due dates",style=MaterialTheme.typography.titleMedium)
                                    row.pastDueSales.forEach { sale ->
                                        LabelValueRow("${sale.dueDate} · Receipt ${sale.id.takeLast(8).uppercase()}","${money(sale.creditMinor)} original credit")
                                    }
                                    Text("These original amounts are not the unpaid balances on these receipts. Open Customers → View history to compare the full sales and repayment history.",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                if(row.dueTodaySales.isNotEmpty() || row.futureDueSales.isNotEmpty()) {
                                    HorizontalDivider()
                                    Text("Original sales due today or later",style=MaterialTheme.typography.titleMedium)
                                    (row.dueTodaySales+row.futureDueSales).forEach { sale ->
                                        LabelValueRow("${sale.dueDate} · Receipt ${sale.id.takeLast(8).uppercase()}","${money(sale.creditMinor)} original credit")
                                    }
                                }
                                if(row.undatedSales.isNotEmpty())Text("${row.undatedSales.size} credit sales have no agreed date recorded. They have not been assigned an assumed due date.",style=MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                }
            }
        }
    }
}
