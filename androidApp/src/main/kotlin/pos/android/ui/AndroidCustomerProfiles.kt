package pos.android.ui

import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import pos.android.documents.updateReviewedAndroidCustomer
import pos.data.*
import pos.domain.*
import pos.output.*
import pos.screens.ReportDateRange
import pos.ui.components.*
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID

@Composable internal fun AndroidCustomerProfileEditor(repo:PosRepository,tenantId:String,customer:Customer,dismiss:()->Unit,saved:()->Unit) {
    var name by rememberSaveable(customer.id) {mutableStateOf(customer.name)}
    var phone by rememberSaveable(customer.id) {mutableStateOf(customer.phone.orEmpty())}
    var address by rememberSaveable(customer.id) {mutableStateOf(customer.address.orEmpty())}
    var limit by rememberSaveable(customer.id) {mutableStateOf(customer.creditLimitMinor?.let {java.math.BigDecimal.valueOf(it,2).toPlainString()}.orEmpty())}
    var reviewed by remember {mutableStateOf(false)}
    val operation=rememberSaveable {UUID.randomUUID().toString()}
    fun requested():Customer {
        require(name.isNotBlank()) { "Enter the customer’s name." }
        val number=phone.trim().ifBlank {null}?.let {requireNotNull(normalizeNigerianPhone(it)) {"Enter a valid Nigerian customer phone number."}}
        val amount=if(limit.isBlank())null else requireNotNull(parseNaira(limit)) {"Enter an exact non-negative credit limit, or leave it blank."}
        return customer.copy(name=name.trim(),phone=number,address=address.trim().ifBlank {null},creditLimitMinor=amount)
    }
    OperationsDialog("Manage customer",dismiss,if(reviewed)"Save customer details" else "Review customer details",action={
        val edited=requested()
        require(edited!=customer) {"Change the customer details or credit limit before saving."}
        if(!reviewed)reviewed=true else {
            withContext(Dispatchers.IO) {repo.updateReviewedAndroidCustomer(tenantId,customer,edited,operation)};saved()
        }
    }) {
        Text("Previous contacts and limits remain in history. Editing a customer does not change any sale, repayment or debt balance.")
        PosTextField(name,{name=it;reviewed=false},"Customer name")
        PosTextField(phone,{phone=it;reviewed=false},"Phone number (optional)")
        PosTextField(address,{address=it;reviewed=false},"Address (optional)")
        PosTextField(limit,{limit=it;reviewed=false},"Credit limit (₦)",hint="Blank means no limit; zero prevents new credit")
        if(reviewed) {Text(requested().name,style=MaterialTheme.typography.titleMedium);LabelValueRow("New credit limit",requested().creditLimitMinor?.let(::money) ?: "No limit set");Text("Existing debt is retained even when the new limit is lower.")}
    }
}
@Composable internal fun AndroidCustomerProfileHistory(repo:PosRepository,tenantId:String,customer:Customer,dismiss:()->Unit) {
    var revision by remember {mutableIntStateOf(0)}
    var limit by remember {mutableIntStateOf(20)}
    val loaded=operationsLoad(repo,tenantId,customer.id to revision) {repo.customerChanges(tenantId,customer.id)}
    OperationsDialog("Customer contact history",dismiss,"Close",action={dismiss()}) {
        Text(customer.name,style=MaterialTheme.typography.titleMedium)
        OperationsLoadState(loaded,"customer contact changes",{revision++})
        loaded?.getOrNull()?.let {changes ->
            if(changes.isEmpty())Text("No contact or credit-limit changes recorded.")
            changes.take(limit).forEach {change ->
                HorizontalDivider();Text("${change.createdAt.take(19)} UTC")
                Text("Name: ${change.previousName} → ${change.name}")
                Text("Phone: ${change.previousPhone ?: "Not recorded"} → ${change.phone ?: "Not recorded"}")
                Text("Address: ${change.previousAddress ?: "Not recorded"} → ${change.address ?: "Not recorded"}")
                Text("Credit limit: ${change.previousCreditLimitMinor?.let(::money) ?: "No limit"} → ${change.creditLimitMinor?.let(::money) ?: "No limit"}")
            }
            if(changes.size>limit)TextButton(onClick={limit+=20}) {Text("Show earlier changes")}
        }
    }
}
@Composable internal fun AndroidCustomerStatement(repo:PosRepository,tenantId:String,customer:Customer,dismiss:()->Unit) {
    val today=LocalDate.now(ZoneId.of("Africa/Lagos"))
    var from by rememberSaveable {mutableStateOf(today.withDayOfMonth(1).toString())}
    var through by rememberSaveable {mutableStateOf(today.toString())}
    var revision by remember {mutableIntStateOf(0)}
    var visibleEvents by remember(from,through) {mutableIntStateOf(20)}
    val loaded=operationsLoad(repo,tenantId,Triple(from,through,revision)) {
        repo.featureRead(tenantId,StaffPermission.MANAGE_CUSTOMERS) {
            require(isValidCreditDueDate(from) && isValidCreditDueDate(through)) { "Enter valid dates as YYYY-MM-DD." }
            val range=ReportDateRange(LocalDate.parse(from),LocalDate.parse(through))
            val current=customers(tenantId).single {it.id==customer.id}
            val complete=CustomerStatementService.build(tenantId,current,sales(tenantId),repayments(tenantId),creditReturnReductions(tenantId),debtAdjustments(tenantId),advanceFundedRepaymentIds(tenantId))
            DatedCustomerStatementService.build(complete,range)
        }
    }
    OperationsDialog("Customer statement",dismiss,"Close",action={dismiss()}) {
        Text(customer.name,style=MaterialTheme.typography.titleMedium)
        Text("Inclusive West Africa Time dates. Original sales, later repayments and debt corrections stay separate.")
        PosTextField(from,{from=it},"From (YYYY-MM-DD)");PosTextField(through,{through=it},"Through (YYYY-MM-DD)")
        OperationsLoadState(loaded,"customer statement",{revision++})
        loaded?.getOrNull()?.let {statement ->
            LabelValueRow("Opening debt before period",money(statement.openingMinor))
            statement.events.take(visibleEvents).forEach {(event,balance) ->
                Text("${event.createdAt.take(19)} UTC · ${event.activity}")
                LabelValueRow("Debt change",money(event.debtDeltaMinor));LabelValueRow("Debt after event",money(balance))
            }
            if(statement.events.size>visibleEvents)TextButton(onClick={visibleEvents+=20}) {Text("Show more statement events")}
            LabelValueRow("Closing debt through selected date",money(statement.closingMinor),emphasized=true)
            LabelValueRow("Current debt · all dates",money(statement.complete.customer.balanceMinor))
            Text("The closing date balance and today’s balance can differ. No date filtering is used to guess current debt.")
            AndroidReviewedCsvSave(repo,tenantId,StaffPermission.MANAGE_CUSTOMERS,"Preview dated statement CSV","customer-statement-${customer.id}.csv") {DatedCustomerStatementService.csv(statement.complete,statement.range)}
        }
    }
}
