package pos.data

import java.sql.ResultSet
import java.time.Instant
import pos.domain.CartLine
import pos.domain.ExchangePortion
import pos.domain.ReturnLine
import pos.domain.cartTotal

@ConsistentCopyVisibility
data class CashExchangeReview internal constructor(
    val originalReceiptId: String,
    val returned: List<ReturnLine>,
    val replacements: List<CartLine>,
    val restock: Boolean,
    val reason: String,
    val shiftId: String?,
) {
    val returnedValueMinor: Long get() = returned.fold(0L) { total, line -> Math.addExact(total, line.totalMinor) }
    val replacementTotalMinor: Long get() = cartTotal(replacements)
    val appliedMinor: Long get() = minOf(returnedValueMinor, replacementTotalMinor)
    val cashToCollectMinor: Long get() = replacementTotalMinor - appliedMinor
    val cashToRefundMinor: Long get() = returnedValueMinor - appliedMinor
}

data class CashExchange(
    val id: String,
    val originalReceiptId: String,
    val returnId: String,
    val replacementReceiptId: String,
    val appliedMinor: Long,
    val recordedAt: String,
)

internal val cashExchangeSnapshotTables = listOf("sale_exchanges")

internal fun PosRepository.initializeCashExchanges() {
    featureSchema(
        """CREATE TABLE IF NOT EXISTS sale_exchanges(
            tenant_id TEXT NOT NULL, id TEXT NOT NULL, original_sale_id TEXT NOT NULL,
            return_id TEXT NOT NULL, replacement_sale_id TEXT NOT NULL,
            applied_minor INTEGER NOT NULL CHECK(typeof(applied_minor)='integer' AND applied_minor>0),
            created_at TEXT NOT NULL,
            PRIMARY KEY(tenant_id,id), UNIQUE(tenant_id,return_id), UNIQUE(tenant_id,replacement_sale_id),
            CHECK(original_sale_id<>replacement_sale_id),
            FOREIGN KEY(tenant_id,original_sale_id) REFERENCES sales(tenant_id,id),
            FOREIGN KEY(tenant_id,replacement_sale_id) REFERENCES sales(tenant_id,id),
            FOREIGN KEY(tenant_id,return_id) REFERENCES sale_returns(tenant_id,id)
        )"""
    )
    for (action in listOf("UPDATE", "DELETE")) {
        featureSchema(
            """CREATE TRIGGER IF NOT EXISTS sale_exchanges_no_${action.lowercase()} BEFORE $action ON sale_exchanges
                BEGIN SELECT RAISE(ABORT,'Saved exchanges cannot be changed'); END"""
        )
    }
}

private fun exchangeRow(row: ResultSet) = CashExchange(
    row.getString("id"), row.getString("original_sale_id"), row.getString("return_id"),
    row.getString("replacement_sale_id"), row.getLong("applied_minor"), row.getString("created_at"),
)

internal fun PosRepository.cashExchange(tenantId: String, exchangeId: String): CashExchange? =
    featureQuery("SELECT * FROM sale_exchanges WHERE tenant_id=? AND id=?", tenantId, exchangeId, map = ::exchangeRow).singleOrNull()

internal fun PosRepository.exchangeForSale(tenantId: String, saleId: String): ExchangePortion? =
    featureQuery("SELECT * FROM sale_exchanges WHERE tenant_id=? AND replacement_sale_id=?", tenantId, saleId, map = ::exchangeRow)
        .singleOrNull()?.let { ExchangePortion(it.id, it.originalReceiptId, it.appliedMinor) }

internal fun PosRepository.exchangeForReturn(tenantId: String, returnId: String): ExchangePortion? =
    featureQuery("SELECT * FROM sale_exchanges WHERE tenant_id=? AND return_id=?", tenantId, returnId, map = ::exchangeRow)
        .singleOrNull()?.let { ExchangePortion(it.id, it.replacementReceiptId, it.appliedMinor) }

internal fun PosRepository.recordCashExchange(tenantId: String, exchange: CashExchange) {
    featureExecute(
        """INSERT INTO sale_exchanges(tenant_id,id,original_sale_id,return_id,replacement_sale_id,applied_minor,created_at)
            VALUES(?,?,?,?,?,?,?)""",
        tenantId, exchange.id, exchange.originalReceiptId, exchange.returnId, exchange.replacementReceiptId,
        exchange.appliedMinor, exchange.recordedAt,
    )
    featureEvent(tenantId, "sale.exchanged", exchange.id)
}

internal fun PosRepository.validateCashExchanges(tenantId: String) {
    val records = featureQuery("SELECT * FROM sale_exchanges WHERE tenant_id=?", tenantId, map = ::exchangeRow)
    val witnesses = featureQuery(
        """SELECT o.entity_id, o.created_at, a.id FROM outbox o
            LEFT JOIN activity_audit a ON a.tenant_id=o.tenant_id AND a.id=o.id
            WHERE o.tenant_id=? AND o.kind='sale.exchanged'""", tenantId,
    ) {
        require(it.getString(3) != null) { "Backup exchange is missing activity history." }
        it.getString(1) to Instant.parse(it.getString(2))
    }
    require(records.size == witnesses.size && records.map { it.id }.toSet() == witnesses.map { it.first }.toSet()) {
        "Backup exchange history is incomplete."
    }
    if (records.isEmpty()) return
    val witnessedAt = witnesses.toMap()
    val sales = sales(tenantId).associateBy { it.id }
    val returns = saleReturns(tenantId).associateBy { it.id }
    for (exchange in records) {
        val original = sales.getValue(exchange.originalReceiptId)
        val returned = returns.getValue(exchange.returnId)
        val replacement = sales.getValue(exchange.replacementReceiptId)
        val returnedAt = Instant.parse(returned.createdAt)
        val replacedAt = Instant.parse(replacement.createdAt)
        require(original.creditMinor == 0L && original.externalPayments.isEmpty() && returned.saleId == original.id &&
            replacement.customerId == original.customerId &&
            replacement.exchange == ExchangePortion(exchange.id, original.id, exchange.appliedMinor) &&
            returned.exchange == ExchangePortion(exchange.id, replacement.id, exchange.appliedMinor) &&
            exchange.appliedMinor == minOf(returned.returnedValueMinor, replacement.totalMinor)) {
            "Backup exchange does not reconcile with its original and replacement receipts."
        }
        require(Instant.parse(original.createdAt) < returnedAt && returnedAt <= replacedAt &&
            replacedAt == Instant.parse(exchange.recordedAt) && witnessedAt.getValue(exchange.id) >= replacedAt) {
            "Backup exchange dates are inconsistent."
        }
    }
}
