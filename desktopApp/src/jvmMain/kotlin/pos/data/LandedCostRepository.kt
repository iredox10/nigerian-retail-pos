package pos.data

import java.math.BigInteger
import java.time.Instant
import java.util.UUID
import pos.domain.Expense
import pos.domain.StaffPermission
import pos.domain.StockReceipt

internal val landedCostSnapshotTables = listOf("landed_cost_events", "landed_cost_shares")

enum class LandedCostMethod(val label: String) {
    PURCHASE_VALUE("By purchase value"),
    EQUAL("Equal per receipt"),
}

data class LandedCostShare(val receiptId: String, val amountMinor: Long)

data class LandedCostEvent(
    val id: String,
    val expenseId: String,
    val version: Long,
    val method: LandedCostMethod?,
    val reason: String,
    val createdAt: String,
    val shares: List<LandedCostShare>,
) {
    internal val kind: String get() = when {
        method == null -> "purchase.landed_cost_removed"
        version == 1L -> "purchase.landed_cost_allocated"
        else -> "purchase.landed_cost_revised"
    }
}

data class LandedCostReview(
    val expenseId: String,
    val expectedVersion: Long,
    val method: LandedCostMethod?,
    val shares: List<LandedCostShare>,
)

data class LandedCostBook(
    val expenses: List<Expense>,
    val receipts: List<StockReceipt>,
    val history: List<LandedCostEvent>,
) {
    val latestByExpense: Map<String, LandedCostEvent> = history.groupBy { it.expenseId }
        .mapValues { (_, events) -> events.maxBy { it.version } }
    val allocatedByReceipt: Map<String, Long> get() = landedSharesByReceipt(latestByExpense.values.flatMap { it.shares })
}

internal fun PosRepository.initializeLandedCosts() {
    featureSchema(
        """CREATE TABLE IF NOT EXISTS landed_cost_events(
            tenant_id TEXT NOT NULL, id TEXT NOT NULL, expense_id TEXT NOT NULL,
            version INTEGER NOT NULL CHECK(typeof(version)='integer' AND version>0),
            method TEXT CHECK(method IN ('PURCHASE_VALUE','EQUAL')), reason TEXT NOT NULL, created_at TEXT NOT NULL,
            PRIMARY KEY(tenant_id,id), UNIQUE(tenant_id,expense_id,version),
            FOREIGN KEY(tenant_id,expense_id) REFERENCES expenses(tenant_id,id)
        )"""
    )
    featureSchema(
        """CREATE TABLE IF NOT EXISTS landed_cost_shares(
            tenant_id TEXT NOT NULL, event_id TEXT NOT NULL, receipt_id TEXT NOT NULL,
            amount_minor INTEGER NOT NULL CHECK(typeof(amount_minor)='integer' AND amount_minor>=0),
            PRIMARY KEY(tenant_id,event_id,receipt_id),
            FOREIGN KEY(tenant_id,event_id) REFERENCES landed_cost_events(tenant_id,id),
            FOREIGN KEY(tenant_id,receipt_id) REFERENCES stock_receipts(tenant_id,id)
        )"""
    )
    featureSchema("CREATE INDEX IF NOT EXISTS landed_cost_shares_receipt ON landed_cost_shares(tenant_id,receipt_id)")
    for (table in landedCostSnapshotTables) {
        for (action in listOf("UPDATE", "DELETE")) {
            featureSchema("""CREATE TRIGGER IF NOT EXISTS ${table}_no_${action.lowercase()}
                BEFORE $action ON $table BEGIN SELECT RAISE(ABORT,'Saved landed cost history cannot be changed'); END""")
        }
    }
}

fun PosRepository.landedCostBook(tenantId: String): LandedCostBook =
    featureRead(tenantId, StaffPermission.MANAGE_STOCK) {
        featureRead(tenantId, StaffPermission.MANAGE_MONEY) {
            loadLandedCostBook(tenantId)
        }
    }

fun PosRepository.reviewLandedCost(
    tenantId: String,
    expenseId: String,
    expectedVersion: Long,
    method: LandedCostMethod?,
    receiptIds: Set<String>,
): LandedCostReview = featureRead(tenantId, StaffPermission.MANAGE_STOCK) {
    featureRead(tenantId, StaffPermission.MANAGE_MONEY) {
        calculateLandedCostReview(loadLandedCostBook(tenantId), expenseId, expectedVersion, method, receiptIds)
    }
}

fun PosRepository.saveLandedCost(
    tenantId: String,
    review: LandedCostReview,
    reason: String,
    operationId: String = UUID.randomUUID().toString(),
): LandedCostEvent = featureWrite(tenantId, StaffPermission.MANAGE_STOCK) {
    featureWrite(tenantId, StaffPermission.MANAGE_MONEY) money@{
        val note = reason.trim()
        require(validLandedCostText(operationId, 1, 200)) { "The allocation reference is invalid." }
        require(validLandedCostText(note, 3, 250)) { "Enter a reason from 3 to 250 printable characters." }
        val version = Math.addExact(review.expectedVersion, 1L)
        val book = loadLandedCostBook(tenantId)
        book.history.singleOrNull { it.id == operationId }?.let { prior ->
            require(prior.expenseId == review.expenseId && prior.version == version &&
                prior.method == review.method && prior.shares == review.shares && prior.reason == note) {
                "This allocation request was already saved with different details."
            }
            return@money prior
        }
        val resolved = calculateLandedCostReview(book, review.expenseId, review.expectedVersion, review.method,
            review.shares.map { it.receiptId }.toSet())
        require(resolved == review) { "The allocation changed. Review the expense and receipts again." }
        val now = Instant.now()
        val expense = book.expenses.single { it.id == review.expenseId }
        val receipts = book.receipts.associateBy { it.id }
        val previous = book.latestByExpense[expense.id]
        require(!now.isBefore(Instant.parse(expense.createdAt)) &&
            review.shares.all { !now.isBefore(Instant.parse(receipts.getValue(it.receiptId).createdAt)) } &&
            (previous == null || !now.isBefore(Instant.parse(previous.createdAt)))) {
            "Correct this computer’s clock before saving a landed cost."
        }
        val event = LandedCostEvent(operationId, expense.id, version, review.method, note, now.toString(), review.shares)
        featureExecute(
            """INSERT INTO landed_cost_events(tenant_id,id,expense_id,version,method,reason,created_at)
                VALUES(?,?,?,?,?,?,?)""",
            tenantId, event.id, event.expenseId, event.version, event.method?.name, event.reason, event.createdAt,
        )
        for (share in event.shares) {
            featureExecute(
                "INSERT INTO landed_cost_shares(tenant_id,event_id,receipt_id,amount_minor) VALUES(?,?,?,?)",
                tenantId, event.id, share.receiptId, share.amountMinor,
            )
        }
        featureEvent(tenantId, event.kind, event.id)
        event
    }
}

private fun PosRepository.loadLandedCostBook(tenantId: String) =
    LandedCostBook(eligibleLandedCostExpenses(tenantId), stockReceipts(tenantId), landedCostEvents(tenantId))

private fun calculateLandedCostReview(
    book: LandedCostBook,
    expenseId: String,
    expectedVersion: Long,
    method: LandedCostMethod?,
    receiptIds: Set<String>,
): LandedCostReview {
    val expense = requireNotNull(book.expenses.singleOrNull { it.id == expenseId }) {
        "Choose a cash expense already recorded in this shop."
    }
    val latest = book.latestByExpense[expenseId]
    require((latest?.version ?: 0L) == expectedVersion) { "The allocation changed. Refresh and review it again." }
    val receipts = book.receipts.associateBy { it.id }
    val shares = if (method == null) {
        require(latest?.method != null && receiptIds.isEmpty()) { "Choose an active allocation to remove." }
        emptyList()
    } else {
        val selected = receiptIds.map {
            requireNotNull(receipts[it]) { "A selected purchase receipt is unavailable in this shop." }
        }
        val distribution = splitLandedCost(expense.amountMinor, method, selected)
        val currentOtherExpenses = book.latestByExpense.values.filterNot { it.expenseId == expenseId }
        val after = currentOtherExpenses.flatMap { it.shares } + distribution
        checkLandedTotals(receipts, after)
        require(latest?.method != method || latest?.shares != distribution) { "Change the receipts or split method before saving a revision." }
        distribution
    }
    return LandedCostReview(expense.id, expectedVersion, method, shares)
}

internal fun splitLandedCost(amountMinor: Long, method: LandedCostMethod, receipts: List<StockReceipt>): List<LandedCostShare> {
    require(amountMinor > 0 && receipts.size in 1..200 && receipts.map { it.id }.distinct().size == receipts.size) {
        "Select 1 to 200 different purchase receipts."
    }
    val weights = receipts.associate { receipt ->
        receipt.id to BigInteger.valueOf(if (method == LandedCostMethod.EQUAL) 1L else receipt.totalCostMinor)
    }
    val totalWeight = weights.values.fold(BigInteger.ZERO, BigInteger::add)
    require(totalWeight.signum() > 0) { "Purchase value is zero for these receipts. Choose Equal per receipt." }
    val amount = BigInteger.valueOf(amountMinor)
    val quotients = weights.mapValues { (_, weight) -> (amount * weight).divideAndRemainder(totalWeight) }
    val roundedDown = quotients.mapValues { (_, portion) -> portion[0].toLongExact() }.toMutableMap()
    val remaining = Math.subtractExact(amountMinor, roundedDown.values.fold(0L, Math::addExact)).toInt()
    val roundingOrder = quotients.entries.sortedWith(
        compareByDescending<Map.Entry<String, Array<BigInteger>>> { it.value[1] }.thenBy { it.key }
    )
    for (entry in roundingOrder.take(remaining)) roundedDown[entry.key] = roundedDown.getValue(entry.key) + 1L
    return roundedDown.entries.sortedBy { it.key }.map { (receiptId, share) -> LandedCostShare(receiptId, share) }
}

internal fun PosRepository.landedCostTotalsForPurchases(tenantId: String): Map<String, Long> =
    featureRead(tenantId, StaffPermission.MANAGE_STOCK) {
        val shares = featureQuery(
            """SELECT s.receipt_id,s.amount_minor FROM landed_cost_shares s JOIN landed_cost_events e
                ON e.tenant_id=s.tenant_id AND e.id=s.event_id
                WHERE s.tenant_id=? AND e.version=(
                    SELECT MAX(current.version) FROM landed_cost_events current
                    WHERE current.tenant_id=e.tenant_id AND current.expense_id=e.expense_id)""",
            tenantId,
        ) { LandedCostShare(it.getString(1), it.getLong(2)) }
        landedSharesByReceipt(shares)
    }

private fun landedSharesByReceipt(shares: List<LandedCostShare>): Map<String, Long> =
    shares.groupBy { it.receiptId }.mapValues { (_, portions) -> portions.fold(0L) { sum, item -> Math.addExact(sum, item.amountMinor) } }

private fun checkLandedTotals(receipts: Map<String, StockReceipt>, shares: List<LandedCostShare>) {
    for ((receiptId, total) in landedSharesByReceipt(shares)) {
        Math.addExact(receipts.getValue(receiptId).totalCostMinor, total)
    }
}

private fun validLandedCostText(value: String, min: Int, max: Int): Boolean =
    value.length in min..max && value == value.trim() && value.none { it.isISOControl() }

private fun PosRepository.eligibleLandedCostExpenses(tenantId: String): List<Expense> {
    val posted = featureQuery(
        """SELECT e.id FROM expenses e JOIN cash_entries c
            ON c.tenant_id=e.tenant_id AND c.source_id=e.id
            WHERE e.tenant_id=? AND c.kind='expense' AND c.shift_id IS NOT NULL AND c.amount_minor=-e.amount_minor""",
        tenantId,
    ) { it.getString(1) }.toSet()
    return expenses(tenantId).filter { it.id in posted }
}

private fun PosRepository.landedCostEvents(tenantId: String): List<LandedCostEvent> {
    val shares = featureQuery(
        """SELECT event_id,receipt_id,amount_minor FROM landed_cost_shares WHERE tenant_id=?
            ORDER BY event_id,receipt_id""", tenantId,
    ) { it.getString(1) to LandedCostShare(it.getString(2), it.getLong(3)) }
        .groupBy({ it.first }, { it.second })
    return featureQuery("SELECT * FROM landed_cost_events WHERE tenant_id=? ORDER BY expense_id,version", tenantId) {
        val id = it.getString("id")
        LandedCostEvent(id, it.getString("expense_id"), it.getLong("version"),
            it.getString("method")?.let(LandedCostMethod::valueOf), it.getString("reason"),
            it.getString("created_at"), shares[id].orEmpty())
    }
}

internal fun PosRepository.validateLandedCostSnapshot(tenantId: String) {
    val history = landedCostEvents(tenantId)
    val witnesses = featureQuery(
        """SELECT o.entity_id,o.kind,o.created_at,a.id FROM outbox o
            LEFT JOIN activity_audit a ON a.tenant_id=o.tenant_id AND a.id=o.id
            WHERE o.tenant_id=? AND o.kind IN ('purchase.landed_cost_allocated','purchase.landed_cost_revised','purchase.landed_cost_removed')""",
        tenantId,
    ) {
        require(it.getString(4) != null) { "Backup landed cost is missing its activity record." }
        it.getString(1) to (it.getString(2) to Instant.parse(it.getString(3)))
    }
    require(witnesses.size == history.size && witnesses.map { it.first }.toSet() == history.map { it.id }.toSet()) {
        "Backup landed cost history is incomplete."
    }
    if (history.isEmpty()) return
    val recorded = witnesses.toMap()
    val expenses = eligibleLandedCostExpenses(tenantId).associateBy { it.id }
    val receipts = stockReceipts(tenantId).associateBy { it.id }
    val groups = history.groupBy { it.expenseId }
    for ((expenseId, events) in groups) {
        val expense = requireNotNull(expenses[expenseId]) { "Backup landed cost has no saved cash expense." }
        var lastTime = Instant.parse(expense.createdAt)
        var previous: LandedCostEvent? = null
        for (event in events) {
            val at = Instant.parse(event.createdAt)
            val witness = recorded.getValue(event.id)
            require(witness.first == event.kind && !witness.second.isBefore(at) && !at.isBefore(lastTime) &&
                event.version == Math.addExact(previous?.version ?: 0L, 1L) &&
                validLandedCostText(event.id, 1, 200) && validLandedCostText(event.reason, 3, 250)) {
                "Backup landed cost revisions are inconsistent."
            }
            val method = event.method
            if (method == null) {
                require(previous?.method != null && event.shares.isEmpty()) { "Backup landed cost removal has no active allocation." }
            } else {
                val selected = event.shares.map { share ->
                    requireNotNull(receipts[share.receiptId]) { "Backup landed cost refers to a missing receipt." }
                }
                require(selected.all { !at.isBefore(Instant.parse(it.createdAt)) } &&
                    splitLandedCost(expense.amountMinor, method, selected) == event.shares &&
                    (previous?.method != method || previous?.shares != event.shares)) {
                    "Backup landed cost shares do not match their expense and receipts."
                }
                checkLandedTotals(receipts, event.shares)
            }
            lastTime = at
            previous = event
        }
    }
    checkLandedTotals(receipts, groups.values.flatMap { it.last().shares })
}
