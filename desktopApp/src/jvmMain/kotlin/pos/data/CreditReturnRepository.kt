package pos.data

import pos.domain.*
import java.sql.ResultSet
import java.time.Instant

internal val creditReturnSnapshotTables=listOf("credit_return_reductions")
internal fun PosRepository.initializeCreditReturns() {
    featureSchema("CREATE TABLE IF NOT EXISTS credit_return_reductions(tenant_id TEXT NOT NULL,return_id TEXT NOT NULL,sale_id TEXT NOT NULL,customer_id TEXT NOT NULL,amount_minor INTEGER NOT NULL CHECK(amount_minor>=0),created_at TEXT NOT NULL,cash_refund_minor INTEGER NOT NULL CHECK(cash_refund_minor>=0),PRIMARY KEY(tenant_id,return_id),FOREIGN KEY(tenant_id,return_id) REFERENCES sale_returns(tenant_id,id),FOREIGN KEY(tenant_id,sale_id) REFERENCES sales(tenant_id,id),FOREIGN KEY(tenant_id,customer_id) REFERENCES customers(tenant_id,id))")
    listOf("UPDATE","DELETE").forEach {action ->featureSchema("CREATE TRIGGER IF NOT EXISTS credit_return_reductions_no_${action.lowercase()} BEFORE $action ON credit_return_reductions BEGIN SELECT RAISE(ABORT,'Credit-return debt history cannot be edited or deleted'); END")}
}
fun PosRepository.creditReturnReductions(tenantId:String,customerId:String?=null):List<CreditReturnReduction> =featureRead(tenantId,StaffPermission.MANAGE_CUSTOMERS) {
    if(customerId!=null) require(customers(tenantId).any {it.id==customerId}) {"Customer belongs to another shop or is unavailable."}
    creditReturnRows(tenantId,customerId)
}
fun PosRepository.creditReturnAllowance(tenantId:String,saleId:String):CreditReturnAllowance=featureRead(tenantId,StaffPermission.RETURN_SALE) {
    featureRead(tenantId,StaffPermission.MANAGE_CUSTOMERS) {creditReturnAllowanceUnchecked(tenantId,saleId)}
}
internal fun PosRepository.creditReturnedMinor(tenantId:String,saleId:String):Long =featureQuery("SELECT amount_minor FROM credit_return_reductions WHERE tenant_id=? AND sale_id=?",tenantId,saleId) {it.getLong(1)}.fold(0L,Math::addExact)
internal fun PosRepository.creditReturnAmount(tenantId:String,returnId:String):Long=featureQuery("SELECT amount_minor FROM credit_return_reductions WHERE tenant_id=? AND return_id=?",tenantId,returnId) {it.getLong(1)}.singleOrNull() ?: 0L
internal data class CreditReturnPlan(val allowance:CreditReturnAllowance,val amountMinor:Long,val cashRefundMinor:Long,val createdAt:String)
internal fun PosRepository.planCreditReturn(tenantId:String,saleId:String,amountMinor:Long):CreditReturnPlan {
    val allowance=creditReturnAllowanceUnchecked(tenantId,saleId)
    require(amountMinor>=0) {"Return value cannot be negative."}
    val reduction=minOf(amountMinor,allowance.outstandingCreditMinor)
    val cashRefund=Math.subtractExact(amountMinor,reduction)
    val sale=sales(tenantId).single {it.id==saleId}
    val times=listOf(sale.createdAt)+repayments(tenantId,allowance.customerId).map {it.createdAt}+repaymentAllocations(tenantId,allowance.customerId,true).map {it.createdAt}+allocationReversals(tenantId,allowance.customerId).map {it.createdAt}+saleReturns(tenantId,saleId).map {it.createdAt}
    val now=Instant.now()
    require(times.all {!Instant.parse(it).isAfter(now)}) {"The computer clock is earlier than this customer's saved history. Correct it before recording the return."}
    return CreditReturnPlan(allowance,reduction,cashRefund,now.toString())
}
private fun PosRepository.creditReturnAllowanceUnchecked(tenantId:String,saleId:String):CreditReturnAllowance {
    val sale=requireNotNull(sales(tenantId).find {it.id==saleId}) {"Sale belongs to another shop or is unavailable."}
    require(sale.creditMinor>0 && sale.externalPayments.isEmpty()) {"Choose a credit sale without external payment portions. No debt or payment was changed."}
    val customer=requireNotNull(customers(tenantId).find {it.id==sale.customerId}) {"Credit sale has no customer."}
    require(repayments(tenantId,customer.id).all {unallocatedRepayment(tenantId,it.id)==0L}) {"Assign this customer's saved repayments to receipts before returning credit goods. The app will not guess which receipt was paid."}
    val allocated=repaymentAllocations(tenantId,customer.id).filter {it.saleId==sale.id}.fold(0L) {sum,value ->Math.addExact(sum,value.amountMinor)}
    val available=Math.subtractExact(Math.subtractExact(sale.creditMinor,creditReturnedMinor(tenantId,saleId)),allocated)
    require(available>=0 && available<=customer.balanceMinor) {"Receipt credit does not reconcile with customer debt. Review the customer's assignments and history."}
    return CreditReturnAllowance(saleId,customer.id,available,customer.balanceMinor)
}
/** Called only after the return row exists, inside the same return transaction. */
internal fun PosRepository.captureCreditReturn(tenantId:String,returned:SaleReturn,plan:CreditReturnPlan) {
    require(returned.saleId==plan.allowance.saleId && returned.refundMinor==plan.cashRefundMinor && returned.creditReductionMinor==plan.amountMinor && returned.createdAt==plan.createdAt)
    check(featureExecute("UPDATE customers SET balance_minor=? WHERE tenant_id=? AND id=? AND balance_minor=?",Math.subtractExact(plan.allowance.customerDebtMinor,plan.amountMinor),tenantId,plan.allowance.customerId,plan.allowance.customerDebtMinor)==1) {"Customer debt changed. Refresh and review the return."}
    featureExecute("INSERT INTO credit_return_reductions VALUES(?,?,?,?,?,?,?)",tenantId,returned.id,returned.saleId,plan.allowance.customerId,plan.amountMinor,returned.createdAt,plan.cashRefundMinor)
    featureEvent(tenantId,"sale.credit_returned",returned.id)
}
internal fun PosRepository.validateCreditReturns(tenantId:String) {
    val sources=creditReturnRows(tenantId,null)
    val returns=saleReturns(tenantId).associateBy {it.id}
    val sales=sales(tenantId).associateBy {it.id}
    val events=featureQuery("SELECT entity_id FROM outbox WHERE tenant_id=? AND kind='sale.credit_returned'",tenantId) {it.getString(1)}
    require(events.size==sources.size && events.toSet()==sources.map {it.returnId}.toSet()) {"Backup credit-return debt history is missing."}
    sources.forEach {source ->
        val original=requireNotNull(sales[source.saleId]) {"Backup credit return has no original sale."}
        val returned=requireNotNull(returns[source.returnId]) {"Backup credit return has no returned goods."}
        require(original.creditMinor>0 && original.externalPayments.isEmpty() && original.customerId==source.customerId && returned.saleId==source.saleId && returned.refundMinor==source.cashRefundMinor && returned.creditReductionMinor==source.amountMinor && returned.returnedValueMinor==Math.addExact(source.amountMinor,source.cashRefundMinor) && returned.createdAt==source.createdAt && !Instant.parse(source.createdAt).isBefore(Instant.parse(original.createdAt))) {"Backup credit return does not match its sale, customer or debt reduction."}
    }
    sources.groupBy {it.saleId}.forEach {(saleId,rows) ->
        val sale=sales.getValue(saleId)
        val reductions=rows.fold(0L) {sum,row ->Math.addExact(sum,row.amountMinor)}
        require(reductions<=sale.creditMinor) {"Backup credit returns exceed original credit."}
        validateCreditReturnFunding(tenantId,sale)
    }
    returns.values.filter {sales.getValue(it.saleId).creditMinor>0}.forEach {require(it.id in sources.map {source ->source.returnId}) {"Backup credit return has no debt-reduction source."}}
}
private fun PosRepository.creditReturnRows(tenantId:String,customerId:String?)=featureQuery("SELECT * FROM credit_return_reductions WHERE tenant_id=?"+(if(customerId==null) "" else " AND customer_id=?")+" ORDER BY created_at,return_id",*listOfNotNull(tenantId,customerId).toTypedArray(),map=::creditReturnRow)
private fun creditReturnRow(r:ResultSet)=CreditReturnReduction(r.getString("return_id"),r.getString("tenant_id"),r.getString("sale_id"),r.getString("customer_id"),r.getLong("amount_minor"),r.getString("created_at"),r.getLong("cash_refund_minor"))

/** Refunded repayment funding cannot be released and allocated to another receipt again. */
internal fun PosRepository.validateCreditReturnAllocationReversal(tenantId:String,original:RepaymentAllocation) {
    val sale=sales(tenantId).single {it.id==original.saleId}
    if(sale.creditMinor==0L) return
    val assigned=repaymentAllocations(tenantId,original.customerId).filter {it.saleId==sale.id}.fold(0L) {sum,row ->Math.addExact(sum,row.amountMinor)}
    val refund=creditReturnRows(tenantId,original.customerId).filter {it.saleId==sale.id}.fold(0L) {sum,row ->Math.addExact(sum,row.cashRefundMinor)}
    require(Math.subtractExact(assigned,original.amountMinor)>=maxOf(0L,Math.subtractExact(refund,sale.cashMinor))) {"This assignment funded a saved cash refund. It cannot be undone and spent on another receipt. Original payments and refunds remain in history."}
}
private fun PosRepository.validateCreditReturnFunding(tenantId:String,sale:Sale) {
    val customer=requireNotNull(sale.customerId)
    val assigned=repaymentAllocations(tenantId,customer).filter {it.saleId==sale.id}.fold(0L) {sum,row ->Math.addExact(sum,row.amountMinor)}
    val refund=creditReturnRows(tenantId,customer).filter {it.saleId==sale.id}.fold(0L) {sum,row ->Math.addExact(sum,row.cashRefundMinor)}
    require(refund<=Math.addExact(sale.cashMinor,assigned)) {"Cash refunds exceed this credit receipt's applied cash and assigned repayments."}
    val assignments=repaymentAllocations(tenantId,customer,true).filter {it.saleId==sale.id}
    val reversals=allocationReversals(tenantId,customer).associateBy {it.allocationId}
    var previousReduction=0L
    creditReturnRows(tenantId,customer).filter {it.saleId==sale.id}.sortedWith(compareBy<CreditReturnReduction> {Instant.parse(it.createdAt)}.thenBy {it.returnId}).forEach {returned ->
        val time=Instant.parse(returned.createdAt)
        val assignedAtReturn=assignments.filter {assignment ->
            !Instant.parse(assignment.createdAt).isAfter(time) &&
                (reversals[assignment.id]?.let {Instant.parse(it.createdAt).isAfter(time)} ?: true)
        }.fold(0L) {sum,row ->Math.addExact(sum,row.amountMinor)}
        val outstanding=Math.subtractExact(Math.subtractExact(sale.creditMinor,previousReduction),assignedAtReturn)
        require(outstanding>=0 && returned.amountMinor==minOf(Math.addExact(returned.amountMinor,returned.cashRefundMinor),outstanding)) {"Credit-return cash and debt split does not match recorded receipt funding at the time of the return."}
        previousReduction=Math.addExact(previousReduction,returned.amountMinor)
    }
}

/** Compare the reviewed debt/cash split before committing; a retry keeps the saved original. */
fun PosRepository.returnCreditItemsReviewed(tenantId:String,saleId:String,quantities:Map<String,Int>,restock:Boolean,reason:String,operationId:String,expected:CreditReturnAllowance):SaleReturn=featureWrite(tenantId,StaffPermission.RETURN_SALE) {
    featureRead(tenantId,StaffPermission.MANAGE_CUSTOMERS) {
        if(saleReturns(tenantId).none {it.id==operationId}) require(creditReturnAllowanceUnchecked(tenantId,saleId)==expected) {"Customer debt or payment assignments changed after review. Close and reopen the return; no goods or money were changed."}
        returnItems(tenantId,saleId,quantities,restock,reason,operationId)
    }
}
