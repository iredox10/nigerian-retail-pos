package pos.data

import java.math.BigInteger
import java.time.LocalDate
import java.time.ZoneId
import pos.domain.Product
import pos.domain.Quantity
import pos.domain.StaffPermission
import pos.domain.quantityScaleFactor

data class ReorderSuggestion(
    val product: Product,
    val retainedSales: Long,
    val approvedIncoming: Long,
    val target: Long,
    val shortage: Long,
    val reserved: Long = 0L,
    val policy: ReplenishmentPolicy? = null,
)

data class ReorderReview(val from: LocalDate, val through: LocalDate, val coverDays: Int, val rows: List<ReorderSuggestion>)

private const val observationDays = 30L

fun PosRepository.reorderSuggestions(
    tenantId: String,
    coverDays: Int,
    today: LocalDate = LocalDate.now(ZoneId.of("Africa/Lagos")),
): ReorderReview = featureRead(tenantId, StaffPermission.MANAGE_STOCK) {
    require(coverDays in 1..90) { "Choose 1 to 90 days of stock cover." }
    val zone = ZoneId.of("Africa/Lagos")
    val from = today.minusDays(observationDays - 1)
    val start = from.atStartOfDay(zone).toInstant().toString().take(19)
    val end = today.plusDays(1).atStartOfDay(zone).toInstant().toString().take(19)
    val retained = featureQuery(
        """SELECT l.product_id, l.quantity_precision, l.base_precision, l.base_units_per_unit,
            l.quantity - COALESCE((
                SELECT SUM(rl.quantity) FROM sale_returns r
                JOIN return_lines rl ON rl.tenant_id=r.tenant_id AND rl.return_id=r.id
                WHERE r.tenant_id=s.tenant_id AND r.sale_id=s.id
                    AND rl.product_id=l.product_id AND rl.selling_unit_id IS l.selling_unit_id
                    AND substr(r.created_at,1,19) < ?
            ),0) AS retained
            FROM sales s
            JOIN sale_lines l ON l.tenant_id=s.tenant_id AND l.sale_id=s.id
            JOIN products p ON p.tenant_id=l.tenant_id AND p.id=l.product_id AND p.archived=0
            WHERE s.tenant_id=? AND substr(s.created_at,1,19)>=? AND substr(s.created_at,1,19)<?""",
        end, tenantId, start, end,
    ) { row ->
        val count = row.getLong("retained")
        check(count >= 0) { "A recorded return exceeds its saved selling quantity." }
        row.getString("product_id") to Quantity(count, row.getInt("quantity_precision"))
            .rescale(row.getInt("base_precision")).timesWhole(row.getLong("base_units_per_unit")).scaledUnits
    }.quantityByProduct()
    val incoming = featureQuery(
        """SELECT l.product_id, l.quantity_precision, l.base_precision, l.base_units_per_unit,
            l.quantity - COALESCE((
                SELECT SUM(d.quantity) FROM purchase_order_delivery_lines d
                WHERE d.tenant_id=l.tenant_id AND d.order_id=l.order_id AND d.line_id=l.id
            ),0) AS remaining
            FROM purchase_orders o
            JOIN purchase_order_lines l ON l.tenant_id=o.tenant_id AND l.order_id=o.id
            JOIN products p ON p.tenant_id=l.tenant_id AND p.id=l.product_id AND p.archived=0
            JOIN supplier_profiles sp ON sp.tenant_id=o.tenant_id AND sp.id=o.supplier_id AND sp.archived=0
            LEFT JOIN purchase_units u ON u.tenant_id=l.tenant_id AND u.id=l.purchase_unit_id
            WHERE o.tenant_id=? AND o.status='APPROVED' AND (l.purchase_unit_id IS NULL OR u.archived=0)""",
        tenantId,
    ) { row ->
        val count = row.getLong("remaining")
        check(count >= 0) { "A recorded delivery exceeds its approved buying quantity." }
        row.getString("product_id") to Quantity(count, row.getInt("quantity_precision"))
            .rescale(row.getInt("base_precision")).timesWhole(row.getLong("base_units_per_unit")).scaledUnits
    }.quantityByProduct()
    val policies = replenishmentPolicies(tenantId)
    val reserved = orderReservedQuantities(tenantId, permission = StaffPermission.MANAGE_STOCK)
    val rows = products(tenantId).mapNotNull { product ->
        val policy = policies[product.id]
        val held = reserved[product.id] ?: 0L
        val available = product.unreservedStock(held)
        val sold = retained[product.id] ?: 0L
        val pending = incoming[product.id] ?: 0L
        val scale = quantityScaleFactor(product.quantityPrecision)
        val threshold = product.reorderLevel?.let { Math.multiplyExact(it.toLong(), scale) }
        val minimum = if (threshold == null) 0L else Math.addExact(threshold, 1L)
        val demandTarget = (BigInteger.valueOf(sold) * BigInteger.valueOf(coverDays.toLong() + (policy?.leadDays ?: 0)) +
            BigInteger.valueOf(observationDays - 1)).divide(BigInteger.valueOf(observationDays)).longValueExact()
        val target = maxOf(minimum, Math.addExact(demandTarget, policy?.safetyScaled ?: 0L))
        val need = ((target - available).coerceAtLeast(0) - pending).coerceAtLeast(0)
        val multiple = policy?.multipleScaled ?: 1L
        val shortage = if (need == 0L) 0L else Math.multiplyExact(Math.addExact((need - 1) / multiple, 1), multiple)
        if (shortage == 0L && (threshold == null || available > threshold)) null
        else ReorderSuggestion(product, sold, pending, target, shortage, held, policy)
    }.sortedWith(compareBy<ReorderSuggestion> { it.shortage == 0L }
        .thenBy { it.product.name.lowercase(java.util.Locale.ROOT) }.thenBy { it.product.id })
    ReorderReview(from, today, coverDays, rows)
}

private fun List<Pair<String, Long>>.quantityByProduct(): Map<String, Long> {
    val totals = mutableMapOf<String, Long>()
    for ((product, quantity) in this) totals[product] = Math.addExact(totals[product] ?: 0, quantity)
    return totals
}
