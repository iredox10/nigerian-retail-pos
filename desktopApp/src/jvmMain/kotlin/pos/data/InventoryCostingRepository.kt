package pos.data

import java.math.BigDecimal
import java.math.BigInteger
import java.sql.ResultSet
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID
import pos.domain.*

data class OpeningInventoryCost(val id: String, val productId: String, val version: Long, val quantityScaled: Long,
    val precision: Int, val totalCostMinor: Long, val reason: String, val createdAt: String)
data class InventoryCostLine(val productId: String, val sourceId: String, val kind: String, val createdAt: String,
    val quantityScaled: Long, val costMinor: Long?, val detail: String)
data class InventoryItemCost(val product: Product, val quantityScaled: Long?, val stockValueMinor: Long?,
    val originalSalesCogsMinor: Long?, val openingBasis: OpeningInventoryCost?, val gaps: List<String>,
    val ledger: List<InventoryCostLine>, val restoredReturnCostMinor: Long? = 0L,
    val nonRestockedReturnCostMinor: Long? = 0L) {
    val netSalesCogsMinor: Long? get() = originalSalesCogsMinor?.let { original ->
        restoredReturnCostMinor?.let { restored -> nonRestockedReturnCostMinor?.let { lost -> Math.subtractExact(Math.subtractExact(original, restored), lost) } }
    }
}
data class InventoryCostingReport(val tenantId: String, val through: String, val items: List<InventoryItemCost>,
    val landedCostsExcludedMinor: Long) {
    val stockValueMinor: Long? get() = if (items.any { it.stockValueMinor == null }) null else
        items.fold(0L) { sum, row -> Math.addExact(sum, requireNotNull(row.stockValueMinor)) }
    val originalSalesCogsMinor: Long? get() = if (items.any { it.originalSalesCogsMinor == null }) null else
        items.fold(0L) { sum, row -> Math.addExact(sum, requireNotNull(row.originalSalesCogsMinor)) }
    val netSalesCogsMinor: Long? get() = if (items.any { it.netSalesCogsMinor == null }) null else
        items.fold(0L) { sum, row -> Math.addExact(sum, requireNotNull(row.netSalesCogsMinor)) }
    val nonRestockedReturnCostMinor: Long? get() = if (items.any { it.nonRestockedReturnCostMinor == null }) null else
        items.fold(0L) { sum, row -> Math.addExact(sum, requireNotNull(row.nonRestockedReturnCostMinor)) }
}
internal val inventoryCostingSnapshotTables = listOf("opening_inventory_costs")
internal fun PosRepository.initializeInventoryCosting() {
    featureSchema("""CREATE TABLE IF NOT EXISTS opening_inventory_costs(tenant_id TEXT NOT NULL,id TEXT NOT NULL,
        product_id TEXT NOT NULL,version INTEGER NOT NULL CHECK(typeof(version)='integer' AND version>0),
        quantity_scaled INTEGER NOT NULL CHECK(typeof(quantity_scaled)='integer' AND quantity_scaled>=0),
        precision INTEGER NOT NULL CHECK(precision BETWEEN 0 AND 6),
        total_cost_minor INTEGER NOT NULL CHECK(typeof(total_cost_minor)='integer' AND total_cost_minor>=0),
        reason TEXT NOT NULL,created_at TEXT NOT NULL,PRIMARY KEY(tenant_id,id),UNIQUE(tenant_id,product_id,version),
        FOREIGN KEY(tenant_id,product_id) REFERENCES products(tenant_id,id))""")
    for (action in listOf("UPDATE", "DELETE")) featureSchema("""CREATE TRIGGER IF NOT EXISTS opening_inventory_costs_no_${action.lowercase()}
        BEFORE $action ON opening_inventory_costs BEGIN SELECT RAISE(ABORT,'Opening cost history cannot be changed'); END""")
}
fun PosRepository.canSetOpeningInventoryCost(tenantId: String): Boolean =
    runCatching { featureRead(tenantId, StaffPermission.MANAGE_STAFF) { true } }.getOrDefault(false)
fun PosRepository.openingInventoryCostHistory(tenantId: String, productId: String): List<OpeningInventoryCost> =
    featureRead(tenantId, StaffPermission.VIEW_REPORTS) { openingCostRows(tenantId).filter { it.productId == productId } }
fun PosRepository.saveOpeningInventoryCost(tenantId: String, productId: String, totalCostMinor: Long,
    expectedVersion: Long, reason: String, operationId: String = UUID.randomUUID().toString()): OpeningInventoryCost =
    featureWrite(tenantId, StaffPermission.MANAGE_STAFF) {
        require(operationId.length in 1..150 && operationId.none { it.isISOControl() }) { "Use a valid opening-cost reference." }
        require(totalCostMinor >= 0 && expectedVersion >= 0) { "Enter a non-negative total opening cost." }
        val clean = reason.trim()
        require(clean.length in 3..500 && clean.none { it.isISOControl() }) { "Enter an opening-cost reason from 3 to 500 printable characters." }
        val previous = openingCostRows(tenantId).singleOrNull { it.id == operationId }
        if (previous != null) {
            require(previous.productId == productId && previous.totalCostMinor == totalCostMinor && previous.version == Math.addExact(expectedVersion, 1) && previous.reason == clean) { "This opening cost was already saved with different details." }
            return@featureWrite previous
        }
        val product = requireNotNull(products(tenantId, true).singleOrNull { it.id == productId }) { "This item belongs to another shop." }
        val opening = requireNotNull(openingScaled(product)) { "The original opening quantity is unavailable or inexact. Its historical value cannot be supplied here." }
        require(opening > 0 || totalCostMinor == 0L) { "A zero opening quantity can only have zero opening cost." }
        val last = openingCostRows(tenantId).lastOrNull { it.productId == productId }
        require((last?.version ?: 0L) == expectedVersion) { "The opening cost changed. Reopen this item and review it." }
        require(last?.totalCostMinor != totalCostMinor) { "Change the opening cost before saving." }
        val now = Instant.now()
        require(listOfNotNull(last?.createdAt, product.createdAt).all { now > Instant.parse(it) }) { "Correct the clock before changing the opening cost." }
        val value = OpeningInventoryCost(operationId, productId, Math.addExact(expectedVersion, 1), opening,
            product.quantityPrecision, totalCostMinor, clean, now.toString())
        featureExecute("INSERT INTO opening_inventory_costs VALUES(?,?,?,?,?,?,?,?,?)", tenantId, value.id, productId,
            value.version, opening, product.quantityPrecision, totalCostMinor, clean, value.createdAt)
        featureEvent(tenantId, "stock.opening_cost_declared", value.id)
        value
    }

fun PosRepository.inventoryCosting(tenantId: String, through: LocalDate = LocalDate.now(costingZone)): InventoryCostingReport =
    featureRead(tenantId, StaffPermission.VIEW_REPORTS) {
        featureRead(tenantId, StaffPermission.MANAGE_STOCK) {
            require(!through.isAfter(LocalDate.now(costingZone))) { "Choose today or an earlier WAT date." }
            val cutoff = through.plusDays(1).atStartOfDay(costingZone).toInstant()
            val opening = openingCostRows(tenantId).groupBy { it.productId }.mapValues { (_, rows) -> rows.last() }
            val receipts = stockReceipts(tenantId).associateBy { it.id }
            val returns = saleReturns(tenantId).filter { Instant.parse(it.createdAt) < cutoff }
            val productsById = products(tenantId, true).associateBy { it.id }
            val runs = repackingHistory(tenantId).associateBy { it.id }
            val cache = mutableMapOf<Triple<String, Instant, Boolean>, InventoryItemCost>()
            val calculating = mutableSetOf<Triple<String, Instant, Boolean>>()
            fun compute(product: Product, itemCutoff: Instant, current: Boolean): InventoryItemCost {
                val key = Triple(product.id, itemCutoff, current)
                cache[key]?.let { return it }
                check(calculating.add(key)) { "Repacking chronology cannot be resolved." }
                val result = costProduct(tenantId, product, opening[product.id], receipts, returns, itemCutoff, current) { runId, time ->
                    val run = runs[runId]
                    val source = run?.let { productsById[it.details.sourceId] }
                    val sourceCutoff = time.plusNanos(1)
                    val sourceKey = source?.let { Triple(it.id, sourceCutoff, false) }
                    if (source == null || (sourceKey != null && sourceKey in calculating)) null else
                        compute(source, sourceCutoff, false).ledger.singleOrNull { it.sourceId == runId && it.kind == "repacking_out" }?.costMinor
                }
                calculating.remove(key)
                cache[key] = result
                return result
            }
            val rows = productsById.values.filter { it.createdAt == null || Instant.parse(it.createdAt) < cutoff }.map { product ->
                compute(product, cutoff, through == LocalDate.now(costingZone))
            }
            // Allocations remain existing expenses. They are shown separately instead of being added again to FIFO layers.
            val allocationEvents = featureQuery("SELECT id,expense_id,version,created_at FROM landed_cost_events WHERE tenant_id=? ORDER BY expense_id,version", tenantId) {
                CostAllocationCutoff(it.getString(1), it.getString(2), it.getLong(3), it.getString(4))
            }.filter { Instant.parse(it.createdAt) < cutoff }.groupBy { it.expenseId }.values.map { it.maxBy { row -> row.version }.id }.toSet()
            val excluded = featureQuery("SELECT event_id,amount_minor FROM landed_cost_shares WHERE tenant_id=?", tenantId) { it.getString(1) to it.getLong(2) }
                .filter { it.first in allocationEvents }.fold(0L) { sum, row -> Math.addExact(sum, row.second) }
            InventoryCostingReport(tenantId, through.toString(), rows, excluded)
        }
    }

private data class CostLayer(val sourceId: String, val quantity: Long, var totalCost: Long?, var consumed: Long = 0) {
    val remaining get() = quantity - consumed
    fun take(amount: Long): Long? {
        val prior = consumed
        consumed = Math.addExact(consumed, amount)
        return totalCost?.let { cost -> proportionalCost(cost, consumed, quantity) - proportionalCost(cost, prior, quantity) }
    }
    fun remainingCost(): Long? = totalCost?.let { it - proportionalCost(it, consumed, quantity) }
}
private fun proportionalCost(cost: Long, quantity: Long, total: Long): Long =
    (BigInteger.valueOf(cost) * BigInteger.valueOf(quantity) / BigInteger.valueOf(total)).toLongExact()
private fun openingScaled(product: Product): Long? = product.openingStock?.let { value ->
    runCatching { BigDecimal.valueOf(value).movePointRight(product.quantityPrecision).longValueExact() }.getOrNull()
}
private data class SoldCostSlice(val quantity: Long, val lotQuantity: Long, val lotCost: Long?, val start: Long, var returned: Long = 0) {
    val returnable get() = quantity - returned
    fun restore(amount: Long): Long? {
        val before = Math.addExact(start, returned)
        returned = Math.addExact(returned, amount)
        return lotCost?.let { proportionalCost(it, Math.addExact(before, amount), lotQuantity) - proportionalCost(it, before, lotQuantity) }
    }
}
private data class CostSource(val id: String, val sourceId: String, val kind: String, val createdAt: String,
    val quantity: Long?, val detail: String, val saleReturn: SaleReturn? = null)

private fun PosRepository.costProduct(tenantId: String, product: Product, basis: OpeningInventoryCost?,
    receipts: Map<String, StockReceipt>, returns: List<SaleReturn>, cutoff: Instant, current: Boolean,
    repackedInputCost: (String, Instant) -> Long?): InventoryItemCost {
    val gaps = linkedSetOf<String>()
    val layers = mutableListOf<CostLayer>()
    val ledger = mutableListOf<InventoryCostLine>()
    val sales = mutableMapOf<String, List<SoldCostSlice>>()
    var originalCogs = 0L
    var restoredCost = 0L
    var nonRestockedCost = 0L
    var cogsKnown = true
    var restoredKnown = true
    var nonRestockedKnown = true
    var quantityCoverage = true
    val opening = openingScaled(product)
    val completeOpening = opening != null && product.createdAt != null
    if (!completeOpening) gaps += "Original opening quantity or date is unavailable."
    if (opening != null && opening > 0 && basis == null) gaps += "Declare the total cost of the opening stock."
    val returnEvents = returns.filter { Instant.parse(it.createdAt) < cutoff && it.lines.any { line -> line.productId == product.id } }
    val returnsById = returnEvents.associateBy { it.id }
    val physical = inventoryMovements(tenantId, product.id).filter { Instant.parse(it.createdAt) < cutoff }
    val movements = (physical.map { movement ->
        CostSource(movement.id, movement.sourceId, movement.kind, movement.createdAt,
            runCatching { BigDecimal.valueOf(movement.quantityDelta).movePointRight(product.quantityPrecision).longValueExact() }.getOrNull(),
            movement.description, if (movement.kind == "return") returnsById[movement.sourceId] else null)
    } + returnEvents.filterNot { it.restock }.map { event ->
        CostSource("return-loss:${event.id}", event.id, "return_not_restocked", event.createdAt, 0L,
            "Returned goods kept out of sellable stock: ${event.reason}", event)
    }).sortedWith(compareBy<CostSource> { Instant.parse(it.createdAt) }.thenBy { it.id })
    val ambiguous = movements.groupBy { it.createdAt }.values.any { same -> same.count { it.quantity != 0L } > 1 }
    if (ambiguous) gaps += "Stock movements share a timestamp, so their FIFO order cannot be established."
    for (movement in movements) {
        val quantity = movement.quantity
        if (quantity == null) {
            gaps += "A legacy stock movement cannot be expressed exactly in this item's unit."
            layers.forEach { it.totalCost = null }
            cogsKnown = false
            quantityCoverage = false
            continue
        }
        var cost: Long? = null
        var detail = movement.detail
        val returned = movement.saleReturn
        if (returned != null) {
            var remaining = returned.lines.filter { it.productId == product.id }.fold(0L) { sum, line -> Math.addExact(sum, line.baseQuantity) }
            val source = sales[returned.saleId]
            var known = source != null && completeOpening && !ambiguous && (!returned.restock || remaining == quantity)
            var restored = 0L
            for (slice in source.orEmpty()) {
                val take = minOf(remaining, slice.returnable)
                if (take == 0L) continue
                val portion = slice.restore(take)
                if (portion == null) known = false else restored = Math.addExact(restored, portion)
                if (returned.restock) layers += CostLayer("${returned.id}:${layers.size}", take, if (known) portion else null)
                remaining -= take
                if (remaining == 0L) break
            }
            if (remaining > 0L) {
                known = false
                if (returned.restock) layers += CostLayer(returned.id, remaining, null)
            }
            cost = if (known) restored else null
            if (returned.restock) {
                if (cost == null) restoredKnown = false else restoredCost = Math.addExact(restoredCost, cost)
                detail += " · Restored from the original sale's FIFO cost slices"
            } else {
                if (cost == null) nonRestockedKnown = false else nonRestockedCost = Math.addExact(nonRestockedCost, cost)
                detail += " · Original goods cost classified separately as non-restocked return loss"
            }
            if (cost == null) gaps += "A return has no complete original sale-cost source."
        } else if (quantity > 0) {
            cost = when (movement.kind) {
                "opening" -> if (basis?.quantityScaled == quantity) basis.totalCostMinor else null
                "purchase" -> receipts[movement.sourceId]?.takeIf { it.productId == product.id && it.baseQuantity == quantity }?.totalCostMinor
                "repacking_in" -> repackedInputCost(movement.sourceId, Instant.parse(movement.createdAt))
                else -> null
            }
            if (movement.kind == "opening" && basis != null) {
                detail += " · Opening-cost declaration ${basis.version} (${basis.id}), recorded ${basis.createdAt}: ${basis.reason}"
            }
            if (movement.kind == "repacking_in") detail += " · Declared input material cost transferred to this output; labor and overhead require separate sources"
            if (cost == null) {
                detail += " · Cost unavailable"
                gaps += when (movement.kind) {
                    "opening" -> "Declare the total cost of the opening stock."
                    "return" -> "Restocked returns need a complete original sale-cost source."
                    "repacking_in" -> "Repacked output has an incomplete or ambiguous input material-cost source."
                    "count" -> "Added stock from a count has no purchase-cost source."
                    else -> "A stock receipt has no supported cost source."
                }
            }
            layers += CostLayer(movement.sourceId, quantity, cost)
        } else if (quantity < 0) {
            var needed = Math.negateExact(quantity)
            var takenCost = 0L
            var known = completeOpening && !ambiguous
            val consumed = mutableListOf<SoldCostSlice>()
            for (layer in layers) {
                val take = minOf(layer.remaining, needed)
                if (take == 0L) continue
                if (movement.kind == "sale") consumed += SoldCostSlice(take, layer.quantity, if (known) layer.totalCost else null, layer.consumed)
                val portion = layer.take(take)
                if (portion == null) known = false else takenCost = Math.addExact(takenCost, portion)
                needed -= take
                if (needed == 0L) break
            }
            if (needed > 0) {
                known = false; quantityCoverage = false
                gaps += "A recorded stock reduction exceeds the retained FIFO layers."
                if (movement.kind == "sale") consumed += SoldCostSlice(needed, needed, null, 0)
            }
            cost = if (known) takenCost else null
            if (movement.kind == "sale") {
                sales[movement.sourceId] = consumed
                if (cost == null) cogsKnown = false else originalCogs = Math.addExact(originalCogs, cost)
            }
            if (movement.kind == "purchase_return") detail += " · FIFO stock cost removed; supplier buying-credit value remains in the supplier ledger"
            if (movement.kind == "repacking_out") detail += " · FIFO input material cost transferred to the declared output"
        } else cost = 0L
        ledger += InventoryCostLine(product.id, movement.sourceId, movement.kind, movement.createdAt, quantity, cost, detail)
    }
    val derived = layers.fold(0L) { sum, layer -> Math.addExact(sum, layer.remaining) }
    val expected = if (current) product.exactStockScaled else derived
    val reconciled = completeOpening && expected == derived && !ambiguous && quantityCoverage
    if (!reconciled && completeOpening && current && expected != derived) gaps += "Current stock differs from the retained cost-layer quantities."
    val valuation = if (reconciled && layers.none { it.remaining > 0 && it.totalCost == null })
        layers.fold(0L) { sum, layer -> Math.addExact(sum, layer.remainingCost() ?: 0L) } else null
    return InventoryItemCost(product, if (completeOpening) derived else if (current) product.exactStockScaled else null,
        valuation, if (cogsKnown && completeOpening && !ambiguous) originalCogs else null, basis, gaps.toList(), ledger,
        if (restoredKnown && !ambiguous) restoredCost else null, if (nonRestockedKnown && !ambiguous) nonRestockedCost else null)
}

internal fun PosRepository.validateInventoryCosting(tenantId: String) {
    val rows = openingCostRows(tenantId)
    val products = products(tenantId, true).associateBy { it.id }
    rows.groupBy { it.productId }.forEach { (id, history) ->
        val product = requireNotNull(products[id]) { "Backup opening cost belongs to a missing item." }
        val quantity = requireNotNull(openingScaled(product)) { "Backup opening cost has no exact opening quantity." }
        var previous = requireNotNull(product.createdAt).let(Instant::parse)
        history.forEachIndexed { index, row ->
            require(row.id.length in 1..150 && row.id.none { it.isISOControl() } && row.version == index + 1L &&
                row.quantityScaled == quantity && row.precision == product.quantityPrecision && row.totalCostMinor >= 0 &&
                (quantity > 0 || row.totalCostMinor == 0L) && row.reason == row.reason.trim() && row.reason.length in 3..500 &&
                row.reason.none { it.isISOControl() } && Instant.parse(row.createdAt) > previous) { "Backup opening-cost history is inconsistent." }
            previous = Instant.parse(row.createdAt)
        }
    }
    val expected = rows.associateBy { it.id }
    val witnessed = featureQuery("""SELECT o.entity_id,o.created_at,a.id FROM outbox o LEFT JOIN activity_audit a
        ON a.tenant_id=o.tenant_id AND a.id=o.id AND a.kind=o.kind AND a.entity_id=o.entity_id
        WHERE o.tenant_id=? AND o.kind='stock.opening_cost_declared'""", tenantId) {
        require(it.getString(3) != null) { "Backup opening cost has no activity record." }
        it.getString(1) to Instant.parse(it.getString(2))
    }
    require(witnessed.size == expected.size && witnessed.map { it.first }.toSet() == expected.keys &&
        witnessed.all { (id, time) -> time >= Instant.parse(expected.getValue(id).createdAt) }) { "Backup opening-cost source history is incomplete." }
}
private fun openingCostRow(row: ResultSet) = OpeningInventoryCost(row.getString("id"), row.getString("product_id"), row.getLong("version"),
    row.getLong("quantity_scaled"), row.getInt("precision"), row.getLong("total_cost_minor"), row.getString("reason"), row.getString("created_at"))
private fun PosRepository.openingCostRows(tenantId: String) = featureQuery("SELECT * FROM opening_inventory_costs WHERE tenant_id=? ORDER BY product_id,version", tenantId, map = ::openingCostRow)
private val costingZone = ZoneId.of("Africa/Lagos")

private data class CostAllocationCutoff(val id: String, val expenseId: String, val version: Long, val createdAt: String)
