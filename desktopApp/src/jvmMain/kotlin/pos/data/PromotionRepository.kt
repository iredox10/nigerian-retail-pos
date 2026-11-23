package pos.data

import java.sql.ResultSet
import java.time.Instant
import java.time.ZoneId
import java.util.UUID
import pos.domain.BasketDiscount
import pos.domain.CartLine
import pos.domain.PromotionReduction
import pos.domain.PromotionSource
import pos.domain.PromotionTerms
import pos.domain.Quantity
import pos.domain.StaffPermission
import pos.domain.UnitDiscount
import pos.domain.validPromotionId

data class Promotion(
    val id: String,
    val tenantId: String,
    val version: Long,
    val terms: PromotionTerms,
    val reason: String,
    val recordedAt: Instant,
    val operationId: String,
)

data class PromotionUsage(val promotion: Promotion, val sales: Long)

data class PromotionQuote(
    val promotion: Promotion,
    val lineKey: String,
    val quantity: Int,
    val discount: UnitDiscount,
    val sales: Long,
)

internal val promotionSnapshotTables = listOf("promotion_revisions")
private val promotionZone = ZoneId.of("Africa/Lagos")

internal fun PosRepository.initializePromotions() {
    featureSchema(
        """CREATE TABLE IF NOT EXISTS promotion_revisions(
            tenant_id TEXT NOT NULL REFERENCES tenants(id),
            id TEXT NOT NULL,
            operation_id TEXT NOT NULL,
            version INTEGER NOT NULL CHECK(version>0),
            name TEXT NOT NULL,
            product_id TEXT NOT NULL,
            selling_unit_id TEXT,
            minimum_quantity INTEGER NOT NULL CHECK(minimum_quantity BETWEEN 1 AND 2147483647),
            quantity_precision INTEGER NOT NULL CHECK(quantity_precision BETWEEN 0 AND 6),
            starts_on TEXT NOT NULL,
            ends_on TEXT NOT NULL,
            reduction TEXT NOT NULL CHECK(reduction IN ('FIXED','PERCENTAGE')),
            amount INTEGER NOT NULL CHECK(amount>0),
            maximum_sales INTEGER CHECK(maximum_sales BETWEEN 1 AND 2147483647),
            archived INTEGER NOT NULL CHECK(archived IN (0,1)),
            reason TEXT NOT NULL,
            created_at TEXT NOT NULL,
            PRIMARY KEY(tenant_id,id,version),
            UNIQUE(tenant_id,operation_id),
            FOREIGN KEY(tenant_id,product_id) REFERENCES products(tenant_id,id),
            FOREIGN KEY(tenant_id,selling_unit_id) REFERENCES selling_units(tenant_id,id)
        )"""
    )
    for (action in listOf("UPDATE", "DELETE")) {
        featureSchema(
            """CREATE TRIGGER IF NOT EXISTS promotion_revisions_no_${action.lowercase()}
                BEFORE $action ON promotion_revisions
                BEGIN SELECT RAISE(ABORT,'Saved promotion history cannot be changed'); END"""
        )
    }
    featureSchema("CREATE INDEX IF NOT EXISTS sale_lines_by_discount_reason ON sale_lines(tenant_id,discount_reason)")
}

fun PosRepository.promotions(tenantId: String): List<PromotionUsage> =
    featureRead(tenantId, StaffPermission.MANAGE_STOCK) {
        currentPromotions(tenantId).map { PromotionUsage(it, promotionUses(tenantId, it.id)) }
    }

fun PosRepository.promotionHistory(tenantId: String, id: String): List<Promotion> =
    featureRead(tenantId, StaffPermission.MANAGE_STOCK) { promotionRows(tenantId, id) }

fun PosRepository.savePromotion(
    tenantId: String,
    id: String,
    terms: PromotionTerms,
    expectedVersion: Long,
    reason: String,
    operationId: String = UUID.randomUUID().toString(),
): Promotion = featureWrite(tenantId, StaffPermission.MANAGE_STOCK) {
    require(validPromotionId(id) && validPromotionId(operationId) && expectedVersion >= 0) {
        "The promotion reference is invalid."
    }
    require(validPromotionReason(reason)) { "Enter a reason from 3 to 250 printable characters." }
    val version = Math.addExact(expectedVersion, 1L)
    val previousRequest = featureQuery(
        "SELECT * FROM promotion_revisions WHERE tenant_id=? AND operation_id=?", tenantId, operationId,
        map = ::promotionRow,
    ).singleOrNull()
    if (previousRequest != null) {
        require(previousRequest.id == id && previousRequest.version == version &&
            previousRequest.reason == reason && previousRequest.terms == terms) {
            "This promotion was already saved with different details."
        }
        return@featureWrite previousRequest
    }

    val previous = currentPromotion(tenantId, id)
    require((previous?.version ?: 0) == expectedVersion && previous?.terms != terms) {
        "The promotion changed or the details are the same. Reload it and review."
    }
    validatePromotionTarget(tenantId, terms, current = !terms.archived)
    val saleTimes = promotionSaleTimes(tenantId, id)
    require(terms.maximumSales?.let { saleTimes.size <= it } != false) {
        "The sale limit cannot be lower than the number already recorded."
    }
    val now = Instant.now()
    require((previous == null || now > previous.recordedAt) && saleTimes.all(now::isAfter)) {
        "Check the computer clock before editing this promotion."
    }
    featureExecute(
        """INSERT INTO promotion_revisions(
            tenant_id,id,operation_id,version,name,product_id,selling_unit_id,minimum_quantity,
            quantity_precision,starts_on,ends_on,reduction,amount,maximum_sales,archived,reason,created_at
        ) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)""",
        tenantId, id, operationId, version, terms.name, terms.productId, terms.sellingUnitId, terms.minimumQuantity,
        terms.quantityPrecision, terms.from, terms.through, terms.reduction.name, terms.amount, terms.maximumSales,
        if (terms.archived) 1 else 0, reason, now.toString(),
    )
    featureEvent(tenantId, "promotion.changed", operationId)
    Promotion(id, tenantId, version, terms, reason, now, operationId)
}

fun PosRepository.quotePromotions(
    tenantId: String,
    lines: List<CartLine>,
    now: Instant = Instant.now(),
): List<PromotionQuote> = featureRead(tenantId, StaffPermission.DISCOUNT) {
    require(lines.all { it.product.tenantId == tenantId } && lines.map { it.key }.distinct().size == lines.size) {
        "Choose distinct basket lines from this shop."
    }
    val today = promotionDay(now)
    currentPromotions(tenantId).mapNotNull { promotion ->
        val terms = promotion.terms
        val line = lines.singleOrNull(terms::matches) ?: return@mapNotNull null
        if (!terms.isScheduledOn(today) || promotion.recordedAt > now) return@mapNotNull null
        val amount = terms.discountMinor(line.unitPriceMinor)
        if (amount !in 1..line.unitPriceMinor) return@mapNotNull null
        val netTotal = Quantity(line.quantity.toLong(), line.quantityPrecision).lineTotalMinor(line.unitPriceMinor - amount)
        if (netTotal == line.grossTotalMinor) return@mapNotNull null
        val used = promotionUses(tenantId, promotion.id)
        if (terms.maximumSales?.let { used >= it.toLong() } == true) return@mapNotNull null
        val source = PromotionSource(promotion.id, promotion.version, line.quantity, today, terms.name)
        PromotionQuote(promotion, line.key, line.quantity, UnitDiscount(line.unitPriceMinor, amount, source.encode()), used)
    }
}

/** The transaction already holds the write lock and has normalized each line against today's catalogue. */
internal fun PosRepository.validatePromotionDiscounts(
    tenantId: String,
    lines: List<CartLine>,
    basket: BasketDiscount?,
    now: Instant,
) {
    for ((line, source) in promotedLines(lines)) {
        val promotion = currentPromotion(tenantId, source.id)
        requireNotNull(promotion) { "The selected promotion is unavailable. Review this line price." }
        validateApplication(line, source, basket, now, promotion, promotionUses(tenantId, promotion.id))
    }
}

internal fun PosRepository.validatePromotionHistory(tenantId: String) {
    val revisions = promotionRows(tenantId)
    val history = revisions.groupBy { it.id }
    for (events in history.values) {
        events.forEachIndexed { index, event ->
            require(event.version == index.toLong() + 1 && validPromotionId(event.id) &&
                validPromotionId(event.operationId) && validPromotionReason(event.reason)) {
                "Backup promotion revisions are incomplete."
            }
            require(index == 0 || (event.recordedAt > events[index - 1].recordedAt &&
                event.terms != events[index - 1].terms)) { "Backup promotion history is out of order." }
            validatePromotionTarget(tenantId, event.terms, current = false)
        }
    }
    val audit = featureQuery(
        """SELECT o.entity_id,o.created_at,a.id FROM outbox o
            LEFT JOIN activity_audit a ON a.tenant_id=o.tenant_id AND a.id=o.id
            WHERE o.tenant_id=? AND o.kind='promotion.changed'""", tenantId,
    ) {
        require(it.getString(3) != null) { "Backup promotion is missing activity history." }
        it.getString(1) to Instant.parse(it.getString(2))
    }
    val auditDates = audit.toMap()
    require(audit.size == revisions.size && revisions.all { event ->
        auditDates[event.operationId]?.let { it >= event.recordedAt } == true
    }) { "Backup promotion activity is incomplete." }

    val used = mutableMapOf<String, Long>()
    val promotedSales = sales(tenantId).filter { sale -> sale.lines.any { it.discountReason?.startsWith("@promotion/") == true } }
        .sortedWith(compareBy({ Instant.parse(it.createdAt) }, { it.id }))
    for (sale in promotedSales) {
        val whenSold = Instant.parse(sale.createdAt)
        for ((line, source) in promotedLines(sale.lines)) {
            val promotion = history[source.id]?.lastOrNull { it.recordedAt <= whenSold }
            requireNotNull(promotion) { "Backup sale is missing its promotion revision." }
            val priorUses = used.getOrDefault(source.id, 0L)
            validateApplication(line, source, sale.basketDiscount, whenSold, promotion, priorUses)
            used[source.id] = Math.addExact(priorUses, 1L)
        }
    }
    for ((id, events) in history) {
        require(events.last().terms.maximumSales?.let { used.getOrDefault(id, 0L) <= it.toLong() } != false) {
            "The saved promotion limit is below its recorded use."
        }
    }
}

private fun promotedLines(lines: List<CartLine>): List<Pair<CartLine, PromotionSource>> {
    val promoted = lines.mapNotNull { line -> line.discountReason?.let(PromotionSource::decode)?.let { line to it } }
    require(promoted.map { it.second.id }.distinct().size == promoted.size) {
        "A promotion can be used once on each sale."
    }
    return promoted
}

private fun validateApplication(
    line: CartLine,
    source: PromotionSource,
    basket: BasketDiscount?,
    now: Instant,
    promotion: Promotion,
    priorUses: Long,
) {
    val today = promotionDay(now)
    val terms = promotion.terms
    require(promotion.recordedAt <= now && promotion.version == source.version && terms.name == source.title) {
        "This promotion changed. Review the line price before taking payment."
    }
    require(source.reviewedOn == today && terms.isScheduledOn(today)) {
        "Review this promotion for today's West Africa Time date."
    }
    require(source.quantity == line.quantity && terms.matches(line) &&
        line.discountPerUnitMinor == terms.discountMinor(line.unitPriceMinor) && line.beforeBasketMinor < line.grossTotalMinor) {
        "The promotion's quantity or price changed. Review the line price."
    }
    require(terms.maximumSales?.let { priorUses < it.toLong() } != false) {
        "This promotion has reached its sale limit."
    }
    require(basket == null && line.basketDiscountMinor == 0L) {
        "Remove the basket discount before using this promotion."
    }
}

private fun PosRepository.validatePromotionTarget(tenantId: String, terms: PromotionTerms, current: Boolean) {
    val product = products(tenantId, includeArchived = true).find { it.id == terms.productId }
    requireNotNull(product) { "The promotion item is unavailable in this shop." }
    val unit = terms.sellingUnitId?.let { id ->
        requireNotNull(sellingUnits(tenantId, product.id, includeArchived = true).find { it.id == id }) {
            "The promotion selling unit is unavailable for this item."
        }
    }
    require(terms.quantityPrecision == if (unit == null) product.quantityPrecision else 0) {
        "Use the saved quantity precision for this selling unit."
    }
    if (current) {
        require(!product.archived && unit?.archived != true) { "Choose an active item and selling unit." }
        val price = unit?.priceMinor ?: product.priceMinor
        require(terms.discountMinor(price) in 1..price) { "Choose a discount that reduces the selling price by at least one kobo." }
    }
}

private fun promotionDay(now: Instant): String = now.atZone(promotionZone).toLocalDate().toString()

private fun validPromotionReason(value: String): Boolean =
    value == value.trim() && value.length in 3..250 && value.none { it.isISOControl() }

private fun PosRepository.promotionUses(tenantId: String, id: String): Long {
    val prefix = PromotionSource.prefix(id)
    return featureQuery(
        """SELECT COUNT(DISTINCT sale_id) FROM sale_lines
            WHERE tenant_id=? AND discount_reason>=? AND discount_reason<?""",
        tenantId, prefix, prefix.dropLast(1) + "0",
    ) { it.getLong(1) }.single()
}

private fun PosRepository.promotionSaleTimes(tenantId: String, id: String): List<Instant> {
    val prefix = PromotionSource.prefix(id)
    return featureQuery(
        """SELECT s.created_at FROM sale_lines l JOIN sales s ON s.tenant_id=l.tenant_id AND s.id=l.sale_id
            WHERE l.tenant_id=? AND l.discount_reason>=? AND l.discount_reason<?""",
        tenantId, prefix, prefix.dropLast(1) + "0",
    ) { Instant.parse(it.getString(1)) }
}

private fun PosRepository.currentPromotions(tenantId: String): List<Promotion> =
    featureQuery(
        """SELECT p.* FROM promotion_revisions p WHERE p.tenant_id=? AND p.version=(
            SELECT MAX(h.version) FROM promotion_revisions h WHERE h.tenant_id=p.tenant_id AND h.id=p.id
        ) ORDER BY p.name COLLATE NOCASE,p.id""", tenantId, map = ::promotionRow,
    )

private fun PosRepository.currentPromotion(tenantId: String, id: String): Promotion? =
    featureQuery(
        "SELECT * FROM promotion_revisions WHERE tenant_id=? AND id=? ORDER BY version DESC LIMIT 1",
        tenantId, id, map = ::promotionRow,
    ).singleOrNull()

private fun PosRepository.promotionRows(tenantId: String, id: String? = null): List<Promotion> =
    if (id == null) featureQuery(
        "SELECT * FROM promotion_revisions WHERE tenant_id=? ORDER BY id,version", tenantId, map = ::promotionRow,
    ) else featureQuery(
        "SELECT * FROM promotion_revisions WHERE tenant_id=? AND id=? ORDER BY version", tenantId, id, map = ::promotionRow,
    )

private fun promotionRow(row: ResultSet): Promotion {
    val archived = row.getInt("archived")
    require(archived in 0..1)
    return Promotion(
        row.getString("id"), row.getString("tenant_id"), row.getLong("version"),
        PromotionTerms(
            row.getString("name"), row.getString("product_id"), row.getString("selling_unit_id"),
            Math.toIntExact(row.getLong("minimum_quantity")), Math.toIntExact(row.getLong("quantity_precision")),
            row.getString("starts_on"), row.getString("ends_on"), PromotionReduction.valueOf(row.getString("reduction")),
            row.getLong("amount"), (row.getObject("maximum_sales") as? Number)?.let { Math.toIntExact(it.toLong()) },
            archived == 1,
        ),
        row.getString("reason"), Instant.parse(row.getString("created_at")), row.getString("operation_id"),
    )
}
