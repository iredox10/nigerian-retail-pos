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
import pos.output.DebtAgeBand
import pos.output.CsvService
import pos.ui.components.*
import java.time.LocalDate
import java.time.ZoneId

/** Review agreed dates without pretending that unallocated repayments settle specific receipts. */
@Composable
fun CreditReviewScreen(repo:PosRepository,tenantId:String) {
    key(tenantId) {
        var revision by remember { mutableIntStateOf(0) }
        var notice by remember { mutableStateOf<String?>(null) }
        var search by remember { mutableStateOf("") }
        var pastDueOnly by remember { mutableStateOf(false) }
        val today=remember(revision) { LocalDate.now(ZoneId.of("Africa/Lagos")) }
        val loaded=remember(revision) {runCatching {DebtAgeingService.build(tenantId,repo.customers(tenantId),repo.sales(tenantId),repo.repayments(tenantId),today,repo.repaymentAllocations(tenantId))}}
        Column(Modifier.fillMaxSize(),verticalArrangement=Arrangement.spacedBy(16.dp)) {
            SectionHeader("Review customer credit","As of $today · West Africa Time · Saved records on this computer")
            InlineNotice("Receipt balances use assigned payments","Assign existing payments under Customers → View history → Assign payments to receipts. While payments remain unassigned, receipt balances are shown before those payments and an exact overdue total is unavailable.",NoticeTone.Info)
            PosTextField(search,{search=it},"Find a customer",Modifier.fillMaxWidth(),hint="Name or phone")
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),horizontalArrangement=Arrangement.spacedBy(16.dp)) {
                FilterChip(pastDueOnly,{pastDueOnly=!pastDueOnly},label={Text("Customers with past-due sale dates")})
                NeutralButton("Refresh",{revision++;notice=null})
                NeutralButton("Export all customer ageing",{
                    try {
                        val text=DebtAgeingService.csv(tenantId,loaded.getOrThrow(),today)
                        val file=CsvService.save(text,"customer-debt-ageing-$today.csv")
                        notice=if(file==null) "Export cancelled." else "Customer ageing saved to ${file.name}."
                    } catch(e:Exception) { notice="Export failed: ${e.message}" }
                },enabled=loaded.isSuccess)
            }
            notice?.let { Text(it,style=MaterialTheme.typography.bodySmall) }
            loaded.exceptionOrNull()?.let {InlineNotice("Credit review could not be loaded",it.message ?: "Refresh to try again.",NoticeTone.Danger)}
            loaded.getOrNull()?.let { rows ->
                val visible=rows.filter { (!pastDueOnly || it.invoiceBalances.any { balance -> balance.remainingMinor>0 && balance.sale.dueDate?.let { due -> LocalDate.parse(due).isBefore(today) }==true }) && (it.customer.name.contains(search,true) || it.customer.phone?.contains(search)==true) }
                LazyColumn(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(16.dp)) {
                    if(visible.isEmpty())item {EmptyState("No matching customer debts","Customers with a current balance appear here. Use Customers to review a profile or record cash received.")}
                    items(visible,key={it.customer.id}) { row ->
                        Surface(shape=MaterialTheme.shapes.medium) {
                            Column(Modifier.fillMaxWidth().padding(20.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
                                SectionHeader(row.customer.name,row.customer.phone ?: "Phone not recorded")
                                LabelValueRow("Current customer debt — all dates",money(row.currentDebtMinor),emphasized=true)
                                LabelValueRow("Later repayments — all dates",money(row.repaymentsTotalMinor))
                                LabelValueRow("Payments not assigned to receipts",money(row.unallocatedRepaymentsMinor))
                                if(row.preciseOverdueMinor!=null) LabelValueRow("Overdue debt with recorded due dates",money(row.preciseOverdueMinor!!),emphasized=true)
                                else InlineNotice("Assign remaining payments first","An exact overdue total is unavailable while ${money(row.unallocatedRepaymentsMinor)} of payments is unassigned. Current customer debt above already includes these payments.",NoticeTone.Warning)
                                row.ageing?.let { ageing ->
                                    Text("How long payments are late",style=MaterialTheme.typography.titleMedium)
                                    DebtAgeBand.entries.forEach { band -> LabelValueRow(band.label,money(ageing.amounts.getValue(band))) }
                                    Text("These bands use current unpaid balances and recorded due dates. Due today is not late; undated debt is kept separate.",style=MaterialTheme.typography.bodySmall)
                                }
                                HorizontalDivider()
                                Text(if(row.unallocatedRepaymentsMinor>0) "Receipt balances before unassigned payments" else "Unpaid credit receipts",style=MaterialTheme.typography.titleMedium)
                                row.invoiceBalances.filter { it.remainingMinor>0 }.forEach { balance ->
                                    val sale=balance.sale
                                    val dueLabel=sale.dueDate?.let { due ->
                                        when {LocalDate.parse(due).isBefore(today) -> "Past due $due";LocalDate.parse(due)==today -> "Due today";else -> "Due $due"}
                                    } ?: "No agreed due date"
                                    LabelValueRow("$dueLabel · ${sale.id.takeLast(8).uppercase()}",money(balance.remainingMinor))
                                }
                                if(row.undatedSales.isNotEmpty())Text("Credit without an agreed due date is excluded from the dated overdue total. No due date has been assumed.",style=MaterialTheme.typography.bodySmall)

                            }
                        }
                    }
                }
            }
        }
    }
}
