package pos.data

import pos.domain.Sale

internal val saleTenderSnapshotTables = listOf("sale_cash_tender")

internal fun PosRepository.initializeSaleTender() {
    featureSchema(
        """CREATE TABLE IF NOT EXISTS sale_cash_tender(
            tenant_id TEXT NOT NULL, sale_id TEXT NOT NULL,
            given_minor INTEGER NOT NULL CHECK(given_minor >= 0),
            PRIMARY KEY(tenant_id, sale_id),
            FOREIGN KEY(tenant_id, sale_id) REFERENCES sales(tenant_id, id))"""
    )
    for (action in listOf("UPDATE", "DELETE")) {
        featureSchema(
            """CREATE TRIGGER IF NOT EXISTS sale_cash_tender_no_${action.lowercase()}
                BEFORE $action ON sale_cash_tender BEGIN
                SELECT RAISE(ABORT, 'Saved cash tender cannot be edited or removed'); END"""
        )
    }
}

/** The checkout transaction already validates tender and owns SELL permission. */
internal fun PosRepository.captureSaleTender(sale: Sale) {
    val tender = sale.cashGivenMinor ?: return
    featureExecute(
        "INSERT INTO sale_cash_tender VALUES(?,?,?)",
        sale.tenantId, sale.id, tender
    )
    featureEvent(sale.tenantId, "sale.cash_tender_captured", sale.id)
}

internal fun PosRepository.saleTenderUnchecked(tenantId: String, saleId: String): Long? =
    featureQuery(
        "SELECT given_minor FROM sale_cash_tender WHERE tenant_id=? AND sale_id=?",
        tenantId, saleId
    ) { it.getLong(1) }.singleOrNull()

internal fun PosRepository.validateSaleTender(tenantId: String) {
    val tenderSales = featureQuery("SELECT sale_id FROM sale_cash_tender WHERE tenant_id=?", tenantId) {
        it.getString(1)
    }.toSet()
    val events = featureQuery(
        "SELECT entity_id FROM outbox WHERE tenant_id=? AND kind='sale.cash_tender_captured'", tenantId
    ) { it.getString(1) }
    require(events.size == tenderSales.size && events.toSet() == tenderSales) {
        "Backup cash tender is missing or has repeated source history."
    }
}
