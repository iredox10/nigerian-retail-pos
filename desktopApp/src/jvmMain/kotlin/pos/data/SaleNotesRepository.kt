package pos.data

internal fun PosRepository.initializeSaleNotes() {
    val columns = featureQuery("PRAGMA table_info(sales)") { it.getString("name") }
    if ("sale_note" !in columns) featureSchema("ALTER TABLE sales ADD COLUMN sale_note TEXT")
    featureSchema(
        """CREATE TRIGGER IF NOT EXISTS sale_note_no_update BEFORE UPDATE OF sale_note ON sales
            WHEN OLD.sale_note IS NOT NEW.sale_note BEGIN
            SELECT RAISE(ABORT, 'A completed sale note cannot be edited'); END"""
    )
}

internal fun PosRepository.validateSaleNotes(tenantId: String) {
    val sales = featureQuery("SELECT id FROM sales WHERE tenant_id=? AND sale_note IS NOT NULL", tenantId) {
        it.getString(1)
    }.toSet()
    val events = featureQuery(
        "SELECT entity_id FROM outbox WHERE tenant_id=? AND kind='sale.note_captured'", tenantId
    ) { it.getString(1) }
    require(events.size == sales.size && events.toSet() == sales) {
        "Backup sale notes are missing or have repeated source history."
    }
}
