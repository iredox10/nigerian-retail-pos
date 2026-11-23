package pos.data

import java.sql.ResultSet
import java.time.Instant
import java.util.UUID
import pos.domain.PurchaseDiscrepancy
import pos.domain.PurchaseDiscrepancyHistory
import pos.domain.PurchaseDiscrepancyKind
import pos.domain.PurchaseDiscrepancyResolution
import pos.domain.PurchaseOrder
import pos.domain.PurchaseOrderDelivery
import pos.domain.PurchaseOrderStatus
import pos.domain.StaffPermission
import pos.domain.StockReceipt

internal val purchaseDiscrepancySnapshotTables = listOf("purchase_discrepancies", "purchase_discrepancy_resolutions")

internal fun PosRepository.initializePurchaseDiscrepancies() {
    featureSchema(
        """CREATE TABLE IF NOT EXISTS purchase_discrepancies(
            tenant_id TEXT NOT NULL,id TEXT NOT NULL,order_id TEXT NOT NULL,
            approval_version INTEGER NOT NULL CHECK(approval_version>0),kind TEXT NOT NULL CHECK(kind IN ('SHORTAGE','DAMAGED','EXTRA')),
            line_id TEXT,product_id TEXT NOT NULL,product_name TEXT NOT NULL,buying_unit TEXT NOT NULL,base_unit TEXT NOT NULL,
            quantity INTEGER NOT NULL CHECK(typeof(quantity)='integer' AND quantity BETWEEN 1 AND 2147483647),
            quantity_precision INTEGER NOT NULL CHECK(quantity_precision BETWEEN 0 AND 6),
            base_precision INTEGER NOT NULL CHECK(base_precision BETWEEN 0 AND 6),
            base_units_per_unit INTEGER NOT NULL CHECK(base_units_per_unit BETWEEN 1 AND 2147483647),
            remaining_at_report INTEGER CHECK(remaining_at_report BETWEEN 0 AND 2147483647),
            reason TEXT NOT NULL,created_at TEXT NOT NULL,
            PRIMARY KEY(tenant_id,id),
            FOREIGN KEY(tenant_id,order_id) REFERENCES purchase_orders(tenant_id,id),
            FOREIGN KEY(tenant_id,order_id,line_id) REFERENCES purchase_order_lines(tenant_id,order_id,id),
            FOREIGN KEY(tenant_id,product_id) REFERENCES products(tenant_id,id)
        )"""
    )
    featureSchema(
        """CREATE TABLE IF NOT EXISTS purchase_discrepancy_resolutions(
            tenant_id TEXT NOT NULL,id TEXT NOT NULL,discrepancy_id TEXT NOT NULL,
            reason TEXT NOT NULL,receipt_id TEXT,created_at TEXT NOT NULL,
            PRIMARY KEY(tenant_id,id),UNIQUE(tenant_id,discrepancy_id),UNIQUE(tenant_id,receipt_id),
            FOREIGN KEY(tenant_id,discrepancy_id) REFERENCES purchase_discrepancies(tenant_id,id),
            FOREIGN KEY(tenant_id,receipt_id) REFERENCES stock_receipts(tenant_id,id)
        )"""
    )
    featureSchema("CREATE INDEX IF NOT EXISTS purchase_discrepancies_by_order ON purchase_discrepancies(tenant_id,order_id)")
    for (table in purchaseDiscrepancySnapshotTables) {
        for (action in listOf("UPDATE", "DELETE")) {
            featureSchema(
                """CREATE TRIGGER IF NOT EXISTS ${table}_no_${action.lowercase()} BEFORE $action ON $table
                    BEGIN SELECT RAISE(ABORT,'Purchase discrepancy history cannot be changed'); END"""
            )
        }
    }
}

fun PosRepository.purchaseDiscrepancies(tenantId: String, orderId: String): List<PurchaseDiscrepancyHistory> =
    featureRead(tenantId, StaffPermission.MANAGE_SUPPLIERS) {
        requireOrder(tenantId, orderId)
        discrepancyHistory(tenantId, orderId)
    }

fun PosRepository.recordPurchaseDiscrepancy(
    tenantId: String, orderId: String, approvalVersion: Long, kind: PurchaseDiscrepancyKind,
    lineId: String?, extraProductId: String?, quantity: Int, expectedRemaining: Int?, reason: String,
    operationId: String = UUID.randomUUID().toString(),
): PurchaseDiscrepancy = featureWrite(tenantId, StaffPermission.MANAGE_SUPPLIERS) {
    featureRead(tenantId, StaffPermission.MANAGE_STOCK) { Unit }
    validateDiscrepancyOperation(operationId)
    require((lineId == null) != (extraProductId == null)) { "Choose an order line or an extra item." }
    require(lineId != null || kind == PurchaseDiscrepancyKind.EXTRA)
    require((kind == PurchaseDiscrepancyKind.EXTRA) == (expectedRemaining == null))
    val note = reason.trim()
    val existing = discrepancyById(tenantId, operationId)
    if (existing != null) {
        require(existing.orderId == orderId && existing.approvalVersion == approvalVersion && existing.kind == kind &&
            existing.lineId == lineId && (extraProductId == null || existing.productId == extraProductId) &&
            existing.quantity == quantity && existing.remainingAtReport == expectedRemaining && existing.reason == note) {
            "This discrepancy was already saved with different details."
        }
        return@featureWrite existing
    }
    val order = requireOrder(tenantId, orderId)
    require(order.status == PurchaseOrderStatus.APPROVED && order.version == approvalVersion) {
        "Open the current approved order and review the discrepancy again."
    }
    val line = lineId?.let { key ->
        requireNotNull(order.lines.singleOrNull { it.id == key }) { "This item is not on the selected order." }
    }
    val extra = extraProductId?.let { key ->
        requireNotNull(products(tenantId).singleOrNull { it.id == key }) { "Choose an active extra item from this shop." }
    }
    val history = discrepancyHistory(tenantId, orderId)
    if (expectedRemaining != null) {
        val actualRemaining = remainingUnchecked(order).getValue(requireNotNull(lineId))
        require(expectedRemaining == actualRemaining) { "The quantity still due changed. Reopen the order and review it." }
        val openQuantity = history.filter { it.resolution == null && it.report.lineId == lineId && it.report.remainingAtReport != null }
            .sumOf { it.report.quantity.toLong() }
        require(quantity in 1..actualRemaining && openQuantity + quantity <= actualRemaining.toLong()) {
            "Reported shortages and damage cannot exceed the unreceived quantity. Review open discrepancies first."
        }
    }
    val now = Instant.now()
    require(now.isAfter(latestOrderDiscrepancyInputTime(order, history, deliveriesUnchecked(tenantId, orderId)))) {
        "Correct the computer clock before recording this discrepancy."
    }
    val report = PurchaseDiscrepancy(
        operationId, tenantId, orderId, approvalVersion, kind, lineId,
        line?.productId ?: requireNotNull(extra).id, line?.productName ?: requireNotNull(extra).name,
        line?.buyingUnit ?: requireNotNull(extra).unit, line?.baseUnit ?: requireNotNull(extra).unit, quantity,
        line?.quantityPrecision ?: requireNotNull(extra).quantityPrecision,
        line?.basePrecision ?: requireNotNull(extra).quantityPrecision, line?.baseUnitsPerUnit ?: 1,
        expectedRemaining, note, now.toString(),
    )
    featureExecute(
        """INSERT INTO purchase_discrepancies(
            tenant_id,id,order_id,approval_version,kind,line_id,product_id,product_name,buying_unit,base_unit,quantity,
            quantity_precision,base_precision,base_units_per_unit,remaining_at_report,reason,created_at
        ) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)""",
        tenantId, report.id, report.orderId, report.approvalVersion, report.kind.name, report.lineId, report.productId,
        report.productName, report.buyingUnit, report.baseUnit, report.quantity, report.quantityPrecision,
        report.basePrecision, report.baseUnitsPerUnit, report.remainingAtReport, report.reason, report.createdAt,
    )
    featureEvent(tenantId, "purchase_discrepancy.recorded", report.id)
    report
}

fun PosRepository.purchaseDiscrepancyReceipts(tenantId: String, discrepancyId: String): List<StockReceipt> =
    featureRead(tenantId, StaffPermission.MANAGE_SUPPLIERS) {
        val report = requireDiscrepancy(tenantId, discrepancyId)
        val order = requireOrder(tenantId, report.orderId)
        val incoming = discrepancyReceipts(tenantId, order.supplierId)
        val used = featureQuery(
            "SELECT receipt_id FROM purchase_discrepancy_resolutions WHERE tenant_id=? AND receipt_id IS NOT NULL", tenantId,
        ) { it.getString(1) }.toSet()
        incoming.values.filter { it.receipt.id !in used && matchesDiscrepancyReceipt(report, it, Instant.now()) }
            .map { it.receipt }.sortedByDescending { Instant.parse(it.createdAt) }
    }

fun PosRepository.resolvePurchaseDiscrepancy(
    tenantId: String, discrepancyId: String, reason: String, receiptId: String? = null,
    operationId: String = UUID.randomUUID().toString(),
): PurchaseDiscrepancyResolution = featureWrite(tenantId, StaffPermission.MANAGE_SUPPLIERS) {
    featureRead(tenantId, StaffPermission.MANAGE_STOCK) { Unit }
    validateDiscrepancyOperation(operationId)
    val note = reason.trim()
    val original = requireDiscrepancy(tenantId, discrepancyId)
    val existing = featureQuery(
        "SELECT * FROM purchase_discrepancy_resolutions WHERE tenant_id=? AND id=?", tenantId, operationId, map = ::readDiscrepancyResolution,
    ).singleOrNull()
    if (existing != null) {
        require(existing.discrepancyId == discrepancyId && existing.reason == note && existing.receiptId == receiptId) {
            "This resolution was already saved with different details."
        }
        return@featureWrite existing
    }
    val order = requireOrder(tenantId, original.orderId)
    val history = discrepancyHistory(tenantId, order.id)
    require(history.single { it.report.id == discrepancyId }.resolution == null) { "This discrepancy is already resolved." }
    val now = Instant.now()
    require(now.isAfter(latestOrderDiscrepancyInputTime(order, history, deliveriesUnchecked(tenantId, order.id)))) {
        "Correct the computer clock before saving this resolution."
    }
    if (receiptId != null) {
        val source = discrepancyReceipts(tenantId, order.supplierId)[receiptId]
        require(source != null && matchesDiscrepancyReceipt(original, source, now)) {
            "Choose a later receipt for the same supplier, item and quantity."
        }
        require(featureQuery(
            "SELECT id FROM purchase_discrepancy_resolutions WHERE tenant_id=? AND receipt_id=?", tenantId, receiptId,
        ) { it.getString(1) }.isEmpty()) { "That receipt already supports another discrepancy." }
    }
    val result = PurchaseDiscrepancyResolution(operationId, discrepancyId, note, receiptId, now.toString())
    featureExecute(
        """INSERT INTO purchase_discrepancy_resolutions(tenant_id,id,discrepancy_id,reason,receipt_id,created_at)
            VALUES(?,?,?,?,?,?)""", tenantId, result.id, result.discrepancyId, result.reason, result.receiptId, result.createdAt,
    )
    featureEvent(tenantId, "purchase_discrepancy.resolved", result.id)
    result
}

/** Keep later receipt and order events behind discrepancy observations when the workstation clock changes. */
internal fun PosRepository.latestPurchaseDiscrepancyTime(tenantId: String, orderId: String): Instant? =
    featureQuery(
        """SELECT d.created_at FROM purchase_discrepancies d WHERE d.tenant_id=? AND d.order_id=?
            UNION ALL SELECT r.created_at FROM purchase_discrepancy_resolutions r
            JOIN purchase_discrepancies d ON d.tenant_id=r.tenant_id AND d.id=r.discrepancy_id
            WHERE d.tenant_id=? AND d.order_id=?""",
        tenantId, orderId, tenantId, orderId,
    ) { Instant.parse(it.getString(1)) }.maxOrNull()

internal fun PosRepository.validatePurchaseDiscrepancySnapshot(tenantId: String) {
    val reports = featureQuery("SELECT * FROM purchase_discrepancies WHERE tenant_id=?", tenantId, map = ::readDiscrepancy)
    val resolutions = featureQuery("SELECT * FROM purchase_discrepancy_resolutions WHERE tenant_id=?", tenantId, map = ::readDiscrepancyResolution)
    val expected = reports.map { "purchase_discrepancy.recorded" to it.id } + resolutions.map { "purchase_discrepancy.resolved" to it.id }
    val witnesses = featureQuery(
        """SELECT o.kind,o.entity_id,a.id,o.created_at
            FROM outbox o LEFT JOIN activity_audit a ON a.tenant_id=o.tenant_id AND a.id=o.id
            WHERE o.tenant_id=? AND o.kind IN ('purchase_discrepancy.recorded','purchase_discrepancy.resolved')""", tenantId,
    ) {
        require(it.getString(3) != null) { "A saved discrepancy is missing its activity history." }
        (it.getString(1) to it.getString(2)) to Instant.parse(it.getString(4))
    }
    require(expected.size == witnesses.size && expected.toSet() == witnesses.map { it.first }.toSet()) {
        "Backup purchase discrepancy records are incomplete."
    }
    if (reports.isEmpty()) return
    val witnessedAt = witnesses.toMap()
    val resolvedById = resolutions.associateBy { it.discrepancyId }
    val catalogue = products(tenantId, includeArchived = true).associateBy { it.id }
    for ((orderId, orderReports) in reports.groupBy { it.orderId }) {
        val order = requireOrder(tenantId, orderId)
        val approval = orderEventsUnchecked(tenantId, orderId).single { it.kind == "approved" }
        val deliveries = deliveriesUnchecked(tenantId, orderId)
        val lines = order.lines.associateBy { it.id }
        val incoming = discrepancyReceipts(tenantId, order.supplierId)
        val orderedReports = orderReports.sortedBy { Instant.parse(it.createdAt) }
        for ((index, report) in orderedReports.withIndex()) {
            validateDiscrepancyOperation(report.id)
            val time = Instant.parse(report.createdAt)
            require(report.approvalVersion == approval.version && time > Instant.parse(approval.createdAt) &&
                (order.cancelledAt?.let { time < Instant.parse(it) } ?: true) &&
                witnessedAt.getValue("purchase_discrepancy.recorded" to report.id) >= time &&
                (index == 0 || time > Instant.parse(orderedReports[index - 1].createdAt))) {
                "Backup discrepancy dates do not match its approved order."
            }
            val line = report.lineId?.let(lines::getValue)
            if (line != null) {
                require(listOf(report.productId,report.productName,report.buyingUnit,report.baseUnit) ==
                    listOf(line.productId,line.productName,line.buyingUnit,line.baseUnit) &&
                    report.quantityPrecision == line.quantityPrecision && report.basePrecision == line.basePrecision &&
                    report.baseUnitsPerUnit == line.baseUnitsPerUnit) { "Backup discrepancy uses a different buying unit from its order." }
            } else {
                val item = catalogue.getValue(report.productId)
                require(report.baseUnit == item.unit && report.basePrecision == item.quantityPrecision) {
                    "Backup extra item uses a different stock unit."
                }
            }
            report.remainingAtReport?.let { remaining ->
                val received = deliveries.filter { Instant.parse(it.createdAt) < time }.flatMap { it.lines }
                    .filter { it.lineId == report.lineId }.sumOf { it.quantity.toLong() }
                val earlierOpen = orderedReports.take(index).filter { earlier ->
                    earlier.lineId == report.lineId && earlier.remainingAtReport != null &&
                        (resolvedById[earlier.id]?.let { Instant.parse(it.createdAt) > time } ?: true)
                }.sumOf { it.quantity.toLong() }
                require(requireNotNull(line).quantity.toLong() - received == remaining.toLong() &&
                    earlierOpen + report.quantity <= remaining.toLong()) { "Backup discrepancy quantity exceeds the stock still due when reported." }
            }
            resolvedById[report.id]?.let { resolution ->
                validateDiscrepancyOperation(resolution.id)
                val resolvedAt = Instant.parse(resolution.createdAt)
                require(resolvedAt > time && witnessedAt.getValue("purchase_discrepancy.resolved" to resolution.id) >= resolvedAt)
                resolution.receiptId?.let { receiptId ->
                    val source = incoming[receiptId]
                    require(source != null && matchesDiscrepancyReceipt(report, source, resolvedAt)) {
                        "Backup discrepancy receipt does not match the supplier, item or quantity."
                    }
                }
            }
        }
    }
}

private fun PosRepository.latestOrderDiscrepancyInputTime(
    order: PurchaseOrder, history: List<PurchaseDiscrepancyHistory>, deliveries: List<PurchaseOrderDelivery>,
): Instant {
    val stockReceiptTimes = featureQuery(
        """SELECT s.created_at FROM stock_receipts s
            JOIN purchase_order_delivery_lines l ON l.tenant_id=s.tenant_id AND l.receipt_id=s.id
            WHERE l.tenant_id=? AND l.order_id=?""", order.tenantId, order.id,
    ) { it.getString(1) }
    return (listOfNotNull(order.approvedAt, order.cancelledAt) + deliveries.map { it.createdAt } + stockReceiptTimes +
        history.flatMap { listOfNotNull(it.report.createdAt, it.resolution?.createdAt) }).maxOf(Instant::parse)
}

private data class DiscrepancyReceipt(
    val receipt: StockReceipt,
    val linkedAt: Instant,
    val orderId: String?,
    val lineId: String?,
)

private fun PosRepository.discrepancyReceipts(tenantId: String, supplierId: String): Map<String, DiscrepancyReceipt> {
    val linked = featureQuery(
        """SELECT s.receipt_id,s.created_at,d.order_id,d.line_id FROM supplier_receipt_links s
            LEFT JOIN purchase_order_delivery_lines d ON d.tenant_id=s.tenant_id AND d.receipt_id=s.receipt_id
            WHERE s.tenant_id=? AND s.supplier_id=?""", tenantId, supplierId,
    ) { row ->
        row.getString(1) to Triple(Instant.parse(row.getString(2)), row.getString(3), row.getString(4))
    }.toMap()
    return stockReceipts(tenantId).mapNotNull { receipt ->
        linked[receipt.id]?.let { (time, orderId, lineId) -> receipt.id to DiscrepancyReceipt(receipt, time, orderId, lineId) }
    }.toMap()
}

private fun matchesDiscrepancyReceipt(report: PurchaseDiscrepancy, source: DiscrepancyReceipt, through: Instant): Boolean {
    val receipt = source.receipt
    val receivedAt = Instant.parse(receipt.createdAt)
    val orderLinkMatches = source.orderId == null || (report.kind != PurchaseDiscrepancyKind.EXTRA &&
        source.orderId == report.orderId && source.lineId == report.lineId)
    return orderLinkMatches && receivedAt > Instant.parse(report.createdAt) && receivedAt <= through &&
        source.linkedAt <= through && receipt.productId == report.productId && receipt.baseUnit == report.baseUnit &&
        receipt.basePrecision == report.basePrecision && receipt.baseQuantity == report.baseQuantity
}

private fun PosRepository.discrepancyHistory(tenantId: String, orderId: String): List<PurchaseDiscrepancyHistory> {
    val reports = featureQuery(
        "SELECT * FROM purchase_discrepancies WHERE tenant_id=? AND order_id=?", tenantId, orderId, map = ::readDiscrepancy,
    )
    val resolutions = featureQuery(
        """SELECT r.* FROM purchase_discrepancy_resolutions r
            JOIN purchase_discrepancies d ON d.tenant_id=r.tenant_id AND d.id=r.discrepancy_id
            WHERE r.tenant_id=? AND d.order_id=?""", tenantId, orderId, map = ::readDiscrepancyResolution,
    ).associateBy { it.discrepancyId }
    return reports.sortedByDescending { Instant.parse(it.createdAt) }.map { PurchaseDiscrepancyHistory(it, resolutions[it.id]) }
}

private fun PosRepository.discrepancyById(tenantId: String, id: String): PurchaseDiscrepancy? = featureQuery(
    "SELECT * FROM purchase_discrepancies WHERE tenant_id=? AND id=?", tenantId, id, map = ::readDiscrepancy,
).singleOrNull()

private fun PosRepository.requireDiscrepancy(tenantId: String, id: String): PurchaseDiscrepancy =
    requireNotNull(discrepancyById(tenantId, id)) { "Delivery discrepancy does not belong to this shop." }

private fun readDiscrepancy(row: ResultSet) = PurchaseDiscrepancy(
    row.getString("id"),row.getString("tenant_id"),row.getString("order_id"),row.getLong("approval_version"),
    PurchaseDiscrepancyKind.valueOf(row.getString("kind")),row.getString("line_id"),row.getString("product_id"),
    row.getString("product_name"),row.getString("buying_unit"),row.getString("base_unit"),row.getInt("quantity"),
    row.getInt("quantity_precision"),row.getInt("base_precision"),row.getInt("base_units_per_unit"),
    (row.getObject("remaining_at_report") as? Number)?.toInt(),row.getString("reason"),row.getString("created_at"),
)

private fun readDiscrepancyResolution(row: ResultSet) = PurchaseDiscrepancyResolution(
    row.getString("id"),row.getString("discrepancy_id"),row.getString("reason"),row.getString("receipt_id"),row.getString("created_at"),
)

private fun validateDiscrepancyOperation(id: String) {
    require(id.isNotBlank() && id.length <= 200 && id.none { it.isISOControl() }) { "Use a valid discrepancy reference." }
}
