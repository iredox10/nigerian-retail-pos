package pos.data

import pos.domain.*

internal val externalPaymentSnapshotTables=listOf("sale_external_payments")
internal fun PosRepository.initializeExternalPayments() {
    featureSchema("CREATE TABLE IF NOT EXISTS sale_external_payments(tenant_id TEXT NOT NULL,sale_id TEXT NOT NULL,position INTEGER NOT NULL CHECK(position>=0 AND position<10),method TEXT NOT NULL CHECK(method IN('transfer','card')),amount_minor INTEGER NOT NULL CHECK(amount_minor>0),reference TEXT NOT NULL,receiving_account TEXT NOT NULL,PRIMARY KEY(tenant_id,sale_id,position),UNIQUE(tenant_id,sale_id,method,reference),FOREIGN KEY(tenant_id,sale_id) REFERENCES sales(tenant_id,id))")
    listOf("UPDATE","DELETE").forEach {action -> featureSchema("CREATE TRIGGER IF NOT EXISTS sale_external_payments_no_${action.lowercase()} BEFORE $action ON sale_external_payments BEGIN SELECT RAISE(ABORT,'Recorded external payment history cannot be changed'); END")}
}
internal fun PosRepository.saveExternalPayments(tenantId:String,saleId:String,payments:List<ExternalPayment>) {
    payments.forEachIndexed {index,p ->featureExecute("INSERT INTO sale_external_payments VALUES(?,?,?,?,?,?,?)",tenantId,saleId,index,p.method,p.amountMinor,p.reference,p.receivingAccount)}
    if(payments.isNotEmpty()) featureEvent(tenantId,"sale.external_recorded",saleId)
}
internal fun PosRepository.externalPaymentsUnchecked(tenantId:String,saleId:String):List<ExternalPayment> = featureQuery("SELECT * FROM sale_external_payments WHERE tenant_id=? AND sale_id=? ORDER BY position",tenantId,saleId) {ExternalPayment(it.getString("method"),it.getLong("amount_minor"),it.getString("reference"),it.getString("receiving_account"))}
internal fun PosRepository.validateExternalPayments(tenantId:String) {
    val recorded=featureQuery("SELECT DISTINCT sale_id FROM sale_external_payments WHERE tenant_id=?",tenantId) {it.getString(1)}.toSet()
    val witnesses=featureQuery("SELECT entity_id FROM outbox WHERE tenant_id=? AND kind='sale.external_recorded'",tenantId) {it.getString(1)}
    require(witnesses.size==witnesses.distinct().size && witnesses.toSet()==recorded) {"Backup external-payment records do not match their saved activity."}
    recorded.forEach {saleId ->
        val positions=featureQuery("SELECT position FROM sale_external_payments WHERE tenant_id=? AND sale_id=? ORDER BY position",tenantId,saleId) {it.getInt(1)}
        require(positions==(0 until positions.size).toList()) {"Backup payment portions are incomplete."}
        externalPaymentTotal(externalPaymentsUnchecked(tenantId,saleId))
    }
}
