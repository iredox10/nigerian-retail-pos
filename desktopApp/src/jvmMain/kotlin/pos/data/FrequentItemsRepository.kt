package pos.data

import java.time.LocalDate
import java.time.ZoneId
import pos.domain.StaffPermission

internal fun PosRepository.initializeFrequentItems() {
    featureSchema("CREATE INDEX IF NOT EXISTS sale_lines_by_receipt ON sale_lines(tenant_id,sale_id)")
    featureSchema("CREATE INDEX IF NOT EXISTS sale_returns_by_sale ON sale_returns(tenant_id,sale_id)")
    featureSchema("CREATE INDEX IF NOT EXISTS return_lines_by_return ON return_lines(tenant_id,return_id)")
}

/** Rank by receipts with retained goods, so pack and measured units are never added together. */
fun PosRepository.frequentItemIds(
    tenantId: String,
    today: LocalDate = LocalDate.now(ZoneId.of("Africa/Lagos"))
): List<String> = featureRead(tenantId, StaffPermission.SELL) {
    val zone = ZoneId.of("Africa/Lagos")
    val start = today.minusDays(29).atStartOfDay(zone).toInstant()
    val end = today.plusDays(1).atStartOfDay(zone).toInstant()
    featureQuery(
        """SELECT l.product_id, COUNT(DISTINCT l.sale_id) AS frequency
            FROM sales s
            JOIN sale_lines l ON l.tenant_id=s.tenant_id AND l.sale_id=s.id
            JOIN products p ON p.tenant_id=l.tenant_id AND p.id=l.product_id
            WHERE s.tenant_id=? AND p.archived=0
              AND julianday(s.created_at) >= julianday(?) AND julianday(s.created_at) < julianday(?)
              AND l.quantity > COALESCE((
                  SELECT SUM(rl.quantity)
                  FROM sale_returns r
                  JOIN return_lines rl ON rl.tenant_id=r.tenant_id AND rl.return_id=r.id
                  WHERE r.tenant_id=s.tenant_id AND r.sale_id=s.id
                    AND rl.product_id=l.product_id AND rl.selling_unit_id IS l.selling_unit_id
              ), 0)
            GROUP BY l.product_id
            ORDER BY frequency DESC, p.name COLLATE NOCASE, l.product_id
            LIMIT 24""",
        tenantId, start.toString(), end.toString()
    ) { it.getString("product_id") }
}
