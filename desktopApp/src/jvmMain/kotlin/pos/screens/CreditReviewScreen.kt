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
import pos.data.creditReturnReductions
import pos.domain.money
import pos.output.DebtAgeingService
import pos.output.HistoricalCreditService
import pos.output.AgeingSchedule
import pos.output.ConfigurableAgeingService
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
        var editBands by remember { mutableStateOf(false) }
        var bandText by remember { mutableStateOf("7,30,60,90") }
        val scheduleResult=runCatching {AgeingSchedule.parse(bandText)}
        val today=remember(revision) { LocalDate.now(ZoneId.of("Africa/Lagos")) }
        var historical by remember { mutableStateOf(false) }
        var throughText by remember { mutableStateOf(today.toString()) }
        val dateResult=runCatching {
            if(historical) {
                require(throughText.matches(Regex("\\d{4}-\\d{2}-\\d{2}"))) { "Enter a date as YYYY-MM-DD." }
                LocalDate.parse(throughText).also { require(!it.isAfter(today)) { "Choose today or an earlier date." } }
            } else today
        }
        val reviewDate=dateResult.getOrNull() ?: today
        val loaded=remember(revision,historical,throughText) {runCatching {
            dateResult.getOrThrow()
            if(historical) HistoricalCreditService.build(tenantId,repo.customers(tenantId),repo.sales(tenantId),repo.repayments(tenantId),repo.repaymentAllocations(tenantId,includeReversed=true),repo.allocationReversals(tenantId),reviewDate,repo.creditReturnReductions(tenantId)).rows
            else DebtAgeingService.build(tenantId,repo.customers(tenantId),repo.sales(tenantId),repo.repayments(tenantId),today,repo.repaymentAllocations(tenantId),repo.creditReturnReductions(tenantId))
        }}
        LazyColumn(Modifier.fillMaxSize(),verticalArrangement=Arrangement.spacedBy(16.dp)) {
            item { Column(verticalArrangement=Arrangement.spacedBy(16.dp)) {
            SectionHeader("Review customer credit",if(historical) "Recorded history through $throughText · End of day in West Africa Time" else "Current balances · $today · West Africa Time")
            Row(horizontalArrangement=Arrangement.spacedBy(16.dp)) {
                FilterChip(historical,{historical=!historical;notice=null},label={Text("Review an earlier date")})
                FilterChip(editBands,{editBands=!editBands},label={Text("Change ageing bands")})
            }
            if(historical) {
                PosTextField(throughText,{throughText=it;notice=null},"Include records through (YYYY-MM-DD)",Modifier.fillMaxWidth(),hint="For example, 2026-11-01")
                Text("Balances use actions recorded by the end of that date. Later payments, assignments and assignment corrections are excluded. Customer names and contact details are current.",style=MaterialTheme.typography.bodySmall)
            }
            InlineNotice("Receipt balances use assigned payments",if(historical) "Only assignments recorded by the selected date count here. Assigning a payment now does not rewrite this historical view. Exact overdue totals are unavailable when payments were unassigned at that date." else "Assign existing payments under Customers → View history → Assign payments to receipts. While payments remain unassigned, receipt balances are shown before those payments and an exact overdue total is unavailable.",NoticeTone.Info)
            if(editBands) PosTextField(bandText,{bandText=it;notice=null},"Late-day band limits",Modifier.fillMaxWidth(),hint="7,30,60,90")
            scheduleResult.exceptionOrNull()?.let {InlineNotice("Check the ageing bands",it.message ?: "Use increasing whole day limits.",NoticeTone.Warning)}
            PosTextField(search,{search=it},"Find a customer",Modifier.fillMaxWidth(),hint="Name or phone")
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),horizontalArrangement=Arrangement.spacedBy(16.dp)) {
                FilterChip(pastDueOnly,{pastDueOnly=!pastDueOnly},label={Text("Customers with past-due sale dates")})
                NeutralButton("Refresh",{revision++;notice=null})
                NeutralButton("Export all customer ageing",{
                    try {
                        val text=ConfigurableAgeingService.csv(tenantId,loaded.getOrThrow(),reviewDate,scheduleResult.getOrThrow(),historical)
                        val file=CsvService.save(text,"customer-debt-ageing-$reviewDate.csv")
                        notice=if(file==null) "Export cancelled." else "Customer ageing saved to ${file.name}."
                    } catch(e:Exception) { notice="Export failed: ${e.message}" }
                },enabled=loaded.isSuccess && scheduleResult.isSuccess)
            }
            notice?.let { Text(it,style=MaterialTheme.typography.bodySmall) }
            loaded.exceptionOrNull()?.let {InlineNotice("Credit review could not be loaded",it.message ?: "Refresh to try again.",NoticeTone.Danger)}
            } }
            loaded.getOrNull()?.let { rows ->
                val visible=rows.filter { (!pastDueOnly || it.invoiceBalances.any { balance -> balance.remainingMinor>0 && balance.sale.dueDate?.let { due -> LocalDate.parse(due).isBefore(reviewDate) }==true }) && (it.customer.name.contains(search,true) || it.customer.phone?.contains(search)==true) }

                    if(visible.isEmpty())item {EmptyState("No matching customer debts",if(historical) "Customers with debt at the selected date appear here." else "Customers with a current balance appear here. Use Customers to review a profile or record cash received.")}
                    items(visible,key={it.customer.id}) { row ->
                        Surface(shape=MaterialTheme.shapes.medium) {
                            Column(Modifier.fillMaxWidth().padding(20.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
                                SectionHeader(row.customer.name,row.customer.phone ?: "Phone not recorded")
                                LabelValueRow(if(historical) "Customer debt at selected date" else "Current customer debt — all dates",money(row.currentDebtMinor),emphasized=true)
                                LabelValueRow(if(historical) "Repayments recorded by selected date" else "Later repayments — all dates",money(row.repaymentsTotalMinor))
                                LabelValueRow("Payments not assigned to receipts",money(row.unallocatedRepaymentsMinor))
                                if(row.preciseOverdueMinor!=null) LabelValueRow("Overdue debt with recorded due dates",money(row.preciseOverdueMinor!!),emphasized=true)
                                else InlineNotice(if(historical) "Payments were unassigned at this date" else "Assign remaining payments first","An exact overdue total is unavailable while ${money(row.unallocatedRepaymentsMinor)} of payments is unassigned. The customer debt above already includes these payments.",NoticeTone.Warning)
                                scheduleResult.getOrNull()?.let {schedule -> ConfigurableAgeingService.amounts(row,reviewDate,schedule)?.let { ageing ->
                                    Text("How long payments are late",style=MaterialTheme.typography.titleMedium)
                                    schedule.labels.forEachIndexed {index,label -> LabelValueRow(label,money(ageing[index])) }
                                    Text(if(historical) "These bands use balances and assignments recorded by the selected date. Undated debt is kept separate." else "These bands use current unpaid balances and recorded due dates. Due today is not late; undated debt is kept separate.",style=MaterialTheme.typography.bodySmall)
                                }
                                }
                                HorizontalDivider()
                                Text(if(row.unallocatedRepaymentsMinor>0) "Receipt balances before unassigned payments" else "Unpaid credit receipts",style=MaterialTheme.typography.titleMedium)
                                row.invoiceBalances.filter { it.remainingMinor>0 }.forEach { balance ->
                                    val sale=balance.sale
                                    val dueLabel=sale.dueDate?.let { due ->
                                        when {LocalDate.parse(due).isBefore(reviewDate) -> "Past due $due";LocalDate.parse(due)==reviewDate -> if(historical) "Due on selected date" else "Due today";else -> "Due $due"}
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
