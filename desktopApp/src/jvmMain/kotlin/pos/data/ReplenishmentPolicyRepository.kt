package pos.data

import java.time.Instant
import java.util.UUID
import pos.domain.StaffPermission

data class ReplenishmentPolicy(
    val id: String, val productId: String, val unit: String, val precision: Int,
    val leadDays: Int, val safetyScaled: Long, val multipleScaled: Long,
    val version: Long, val reason: String, val createdAt: String,
)

internal val replenishmentPolicySnapshotTables = listOf("replenishment_policy_events")

internal fun PosRepository.initializeReplenishmentPolicies() {
    featureSchema("""CREATE TABLE IF NOT EXISTS replenishment_policy_events(
        tenant_id TEXT NOT NULL,id TEXT NOT NULL,product_id TEXT NOT NULL,unit TEXT NOT NULL,
        precision INTEGER NOT NULL CHECK(typeof(precision)='integer' AND precision BETWEEN 0 AND 6),
        lead_days INTEGER NOT NULL CHECK(typeof(lead_days)='integer' AND lead_days BETWEEN 0 AND 365),
        safety_scaled INTEGER NOT NULL CHECK(typeof(safety_scaled)='integer' AND safety_scaled BETWEEN 0 AND 2147483647),
        multiple_scaled INTEGER NOT NULL CHECK(typeof(multiple_scaled)='integer' AND multiple_scaled BETWEEN 1 AND 2147483647),
        version INTEGER NOT NULL CHECK(typeof(version)='integer' AND version>0),reason TEXT NOT NULL,created_at TEXT NOT NULL,
        PRIMARY KEY(tenant_id,id),UNIQUE(tenant_id,product_id,version),
        FOREIGN KEY(tenant_id,product_id) REFERENCES products(tenant_id,id))""")
    for (action in listOf("UPDATE", "DELETE")) featureSchema("""CREATE TRIGGER IF NOT EXISTS
        replenishment_policy_no_${action.lowercase()} BEFORE $action ON replenishment_policy_events
        BEGIN SELECT RAISE(ABORT,'Buying rule history cannot be changed'); END""")
}

fun PosRepository.replenishmentPolicies(tenantId: String): Map<String, ReplenishmentPolicy> =
    featureRead(tenantId, StaffPermission.MANAGE_STOCK) {
        replenishmentPolicyRows(tenantId).groupBy { it.productId }.mapValues { it.value.last() }
    }

fun PosRepository.replenishmentPolicyHistory(tenantId: String): List<ReplenishmentPolicy> =
    featureRead(tenantId, StaffPermission.MANAGE_STOCK) { replenishmentPolicyRows(tenantId) }

private fun PosRepository.replenishmentPolicyRows(tenantId: String): List<ReplenishmentPolicy> =
    featureQuery("SELECT * FROM replenishment_policy_events WHERE tenant_id=? ORDER BY product_id,version", tenantId) {
        ReplenishmentPolicy(it.getString("id"), it.getString("product_id"), it.getString("unit"),
            it.getInt("precision"), it.getInt("lead_days"), it.getLong("safety_scaled"),
            it.getLong("multiple_scaled"), it.getLong("version"), it.getString("reason"), it.getString("created_at"))
    }

fun PosRepository.saveReplenishmentPolicy(
    tenantId: String, productId: String, leadDays: Int, safetyScaled: Long, multipleScaled: Long,
    expectedVersion: Long, reason: String, operationId: String = UUID.randomUUID().toString(),
): ReplenishmentPolicy = featureWrite(tenantId, StaffPermission.MANAGE_STOCK) {
    require(operationId.isNotBlank() && operationId.length <= 150 && operationId.none { it.isISOControl() })
    require(leadDays in 0..365 && safetyScaled in 0..2147483647L && multipleScaled in 1..2147483647L) {
        "Enter 0–365 lead days, non-negative safety stock and a positive buying multiple."
    }
    val clean = reason.trim()
    require(clean.length in 3..250 && clean.none { it.isISOControl() }) { "Enter a reason from 3 to 250 printable characters." }
    val history = replenishmentPolicyRows(tenantId)
    history.singleOrNull { it.id == operationId }?.let {
        require(it.productId == productId && it.leadDays == leadDays && it.safetyScaled == safetyScaled &&
            it.multipleScaled == multipleScaled && it.reason == clean && it.version == Math.addExact(expectedVersion, 1)) {
            "This buying rule was saved with different details."
        }
        return@featureWrite it
    }
    val product = requireNotNull(products(tenantId).singleOrNull { it.id == productId }) { "Choose an active item in this shop." }
    val previous = history.lastOrNull { it.productId == productId }
    require((previous?.version ?: 0) == expectedVersion) { "Buying rules changed. Review them before saving." }
    val now = Instant.now()
    require(previous == null || now > Instant.parse(previous.createdAt)) { "Correct the clock before changing buying rules." }
    val event = ReplenishmentPolicy(operationId, productId, product.unit, product.quantityPrecision,
        leadDays, safetyScaled, multipleScaled, Math.addExact(expectedVersion, 1), clean, now.toString())
    featureExecute("INSERT INTO replenishment_policy_events VALUES(?,?,?,?,?,?,?,?,?,?,?)", tenantId,
        event.id, productId, event.unit, event.precision, leadDays, safetyScaled, multipleScaled, event.version, clean, event.createdAt)
    featureEvent(tenantId, "stock.buying_rule_changed", event.id)
    event
}

internal fun PosRepository.validateReplenishmentPolicies(tenantId: String) {
    val events = replenishmentPolicyRows(tenantId)
    val products = products(tenantId, true).associateBy { it.id }
    events.groupBy { it.productId }.forEach { (productId, history) ->
        val product = requireNotNull(products[productId]) { "Buying rule item is unavailable." }
        history.forEachIndexed { index, event ->
            require(event.unit == product.unit && event.precision == product.quantityPrecision && event.version == index + 1L &&
                event.leadDays in 0..365 && event.safetyScaled in 0..2147483647L && event.multipleScaled in 1..2147483647L &&
                event.reason == event.reason.trim() && event.reason.length in 3..250 && event.reason.none { it.isISOControl() }) {
                "Backup buying rules are inconsistent."
            }
            require(index == 0 || Instant.parse(event.createdAt) > Instant.parse(history[index - 1].createdAt)) {
                "Backup buying rule dates are inconsistent."
            }
            Instant.parse(event.createdAt)
        }
    }
    val witnessed = featureQuery("""SELECT o.entity_id,a.id FROM outbox o
        LEFT JOIN activity_audit a ON a.tenant_id=o.tenant_id AND a.id=o.id AND a.kind=o.kind AND a.entity_id=o.entity_id
        WHERE o.tenant_id=? AND o.kind='stock.buying_rule_changed'""", tenantId) {
        require(it.getString(2) != null) { "Backup buying rules are missing activity history." }
        it.getString(1)
    }
    require(witnessed.size == events.size && witnessed.toSet() == events.map { it.id }.toSet()) { "Backup buying rule history is incomplete." }
}
