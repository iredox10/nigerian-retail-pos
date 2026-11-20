package pos.data

import java.sql.ResultSet
import java.time.Instant
import java.util.Locale
import java.util.UUID
import pos.domain.StaffPermission

data class ProductVariant(val family: String, val label: String) {
    init {
        for (value in listOf(family, label)) {
            require(value.length in 1..100 && value == value.trim() && value.none { it.isISOControl() }) {
                "Enter a family and variant label of up to 100 printable characters."
            }
        }
    }
    val description: String get() = "$family · $label"
    internal val key: Pair<String, String> get() = family.lowercase(Locale.ROOT) to label.lowercase(Locale.ROOT)
}

data class ProductVariantChange(
    val id: String, val productId: String, val variant: ProductVariant?,
    val version: Long, val reason: String, val createdAt: Instant,
)

internal val variantSnapshotTables = listOf("product_variant_history")

internal fun PosRepository.initializeProductVariants() {
    featureSchema(
        """CREATE TABLE IF NOT EXISTS product_variant_history(
            tenant_id TEXT NOT NULL, id TEXT NOT NULL, product_id TEXT NOT NULL,
            family TEXT, label TEXT, version INTEGER NOT NULL CHECK(version > 0),
            reason TEXT NOT NULL, created_at TEXT NOT NULL,
            PRIMARY KEY(tenant_id,id), UNIQUE(tenant_id,product_id,version),
            CHECK((family IS NULL) = (label IS NULL)),
            FOREIGN KEY(tenant_id,product_id) REFERENCES products(tenant_id,id))"""
    )
    for (action in listOf("UPDATE", "DELETE")) {
        featureSchema(
            """CREATE TRIGGER IF NOT EXISTS product_variant_no_${action.lowercase()}
                BEFORE $action ON product_variant_history BEGIN
                SELECT RAISE(ABORT, 'Product variant history cannot be edited'); END"""
        )
    }
}

fun PosRepository.productVariants(tenantId: String): Map<String, ProductVariant> =
    featureRead(tenantId, StaffPermission.SELL) {
        currentVariants(tenantId).mapNotNull { row -> row.variant?.let { row.productId to it } }.toMap()
    }

fun PosRepository.productVariantHistory(tenantId: String, productId: String): List<ProductVariantChange> =
    featureRead(tenantId, StaffPermission.MANAGE_STOCK) {
        featureQuery(
            "SELECT * FROM product_variant_history WHERE tenant_id=? AND product_id=? ORDER BY version",
            tenantId, productId, map = ::variantRow
        )
    }

fun PosRepository.saveProductVariant(
    tenantId: String, productId: String, variant: ProductVariant?,
    expectedVersion: Long, reason: String, operationId: String = UUID.randomUUID().toString(),
): ProductVariantChange = featureWrite(tenantId, StaffPermission.MANAGE_STOCK) {
    val entry = ProductVariantChange(operationId, productId, variant, Math.addExact(expectedVersion, 1),
        reason.trim(), Instant.now())
    validateVariantChange(entry)
    val replay = featureQuery("SELECT * FROM product_variant_history WHERE tenant_id=? AND id=?",
        tenantId, operationId, map = ::variantRow).singleOrNull()
    if (replay != null) {
        require(replay == entry.copy(createdAt = replay.createdAt)) { "This variant request was saved with different details." }
        return@featureWrite replay
    }
    require(products(tenantId).any { it.id == productId }) { "Choose active goods belonging to this shop." }
    val current = currentVariants(tenantId)
    val previous = current.singleOrNull { it.productId == productId }
    require((previous?.version ?: 0) == expectedVersion) { "The variant changed. Reload and review it." }
    require(previous?.variant != variant) { "Change the product variant before saving." }
    require(previous == null || entry.createdAt >= previous.createdAt) { "Correct the clock before saving this change." }
    require(variant == null || current.none { it.productId != productId && it.variant?.key == variant.key }) {
        "This family and variant label already identify another product. Clear that assignment first."
    }
    featureExecute(
        """INSERT INTO product_variant_history(tenant_id,id,product_id,family,label,version,reason,created_at)
            VALUES(?,?,?,?,?,?,?,?)""",
        tenantId, operationId, productId, variant?.family, variant?.label, entry.version, entry.reason, entry.createdAt.toString()
    )
    featureEvent(tenantId, "product.variant_changed", operationId)
    entry
}

private fun variantRow(row: ResultSet): ProductVariantChange {
    val family = row.getString("family")
    val label = row.getString("label")
    require((family == null) == (label == null)) { "Saved variant has incomplete details." }
    return ProductVariantChange(row.getString("id"), row.getString("product_id"),
        family?.let { ProductVariant(it, requireNotNull(label)) }, row.getLong("version"),
        row.getString("reason"), Instant.parse(row.getString("created_at")))
}

private fun PosRepository.currentVariants(tenantId: String): List<ProductVariantChange> = featureQuery(
    """SELECT v.* FROM product_variant_history v WHERE v.tenant_id=?
        AND v.version=(SELECT MAX(h.version) FROM product_variant_history h
            WHERE h.tenant_id=v.tenant_id AND h.product_id=v.product_id)""",
    tenantId, map = ::variantRow
)

private fun validateVariantChange(change: ProductVariantChange) {
    require(change.id.length in 1..200 && change.id.none { it.isISOControl() } && change.version > 0) {
        "Variant change reference is invalid."
    }
    require(change.reason.length in 3..250 && change.reason == change.reason.trim() &&
        change.reason.none { it.isISOControl() }) { "Enter a reason from 3 to 250 printable characters." }
}

internal fun PosRepository.validateProductVariants(tenantId: String) {
    val events = featureQuery("SELECT * FROM product_variant_history WHERE tenant_id=? ORDER BY product_id,version",
        tenantId, map = ::variantRow)
    events.groupBy { it.productId }.values.forEach { history ->
        history.forEachIndexed { index, event ->
            validateVariantChange(event)
            val previous = history.getOrNull(index - 1)
            require(event.version == index + 1L && event.variant != previous?.variant &&
                (previous == null || event.createdAt >= previous.createdAt)) {
                "Backup variant history is incomplete or out of order."
            }
        }
    }
    val current = currentVariants(tenantId).mapNotNull { it.variant?.key }
    require(current.distinct().size == current.size) { "Backup includes duplicate variant labels in a family." }
    val witnesses = featureQuery(
        "SELECT entity_id FROM outbox WHERE tenant_id=? AND kind='product.variant_changed'", tenantId
    ) { it.getString(1) }
    require(witnesses.size == events.size && witnesses.toSet() == events.map { it.id }.toSet()) {
        "Backup variant activity is missing or repeated."
    }
}
