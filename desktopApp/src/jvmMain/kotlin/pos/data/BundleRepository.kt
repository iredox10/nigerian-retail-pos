package pos.data

import java.io.*
import java.security.MessageDigest
import java.time.Instant
import java.util.Base64
import java.util.UUID
import pos.domain.*

data class BundleComponentInput(val productId: String, val sellingUnitId: String?, val quantity: Int)
data class BundleComponent(val productId: String, val sellingUnitId: String?, val quantity: Int,
    val quantityPrecision: Int, val productName: String, val baseUnit: String, val basePrecision: Int,
    val sellingUnit: String, val baseUnitsPerUnit: Int, val priceAtDefinitionMinor: Long) {
    val key: String get() = sellingUnitId?.let { "$productId~$it" } ?: productId
}
data class BundleTerms(val name: String, val active: Boolean, val fixedPriceMinor: Long?, val components: List<BundleComponent>)
data class BundleDefinition(val id: String, val version: Long, val terms: BundleTerms, val reason: String, val createdAt: String)
data class BundleSaleReview(val bundleId: String, val version: Long, val name: String, val count: Int,
    val lines: List<CartLine>, val discount: BasketDiscount?, val totalMinor: Long, val token: String) {
    val normalTotalMinor: Long get() = cartSubtotal(lines)
}
data class BundleSale(val saleId: String, val bundleId: String, val bundleVersion: Long, val count: Int,
    val reviewToken: String, val linesFingerprint: String, val createdAt: String)

internal val bundleSnapshotTables = listOf("bundle_versions", "bundle_sales")
internal fun PosRepository.initializeBundles() {
    featureSchema("""CREATE TABLE IF NOT EXISTS bundle_versions(
        tenant_id TEXT NOT NULL,id TEXT NOT NULL,bundle_id TEXT NOT NULL,version INTEGER NOT NULL CHECK(typeof(version)='integer' AND version>0),
        terms TEXT NOT NULL,reason TEXT NOT NULL,created_at TEXT NOT NULL,
        PRIMARY KEY(tenant_id,id),UNIQUE(tenant_id,bundle_id,version),FOREIGN KEY(tenant_id) REFERENCES tenants(id))""")
    featureSchema("""CREATE TABLE IF NOT EXISTS bundle_sales(
        tenant_id TEXT NOT NULL,sale_id TEXT NOT NULL,bundle_id TEXT NOT NULL,bundle_version INTEGER NOT NULL,
        bundle_count INTEGER NOT NULL CHECK(typeof(bundle_count)='integer' AND bundle_count>0),review_token TEXT NOT NULL,
        lines_fingerprint TEXT NOT NULL,created_at TEXT NOT NULL,PRIMARY KEY(tenant_id,sale_id),
        FOREIGN KEY(tenant_id,sale_id) REFERENCES sales(tenant_id,id),
        FOREIGN KEY(tenant_id,bundle_id,bundle_version) REFERENCES bundle_versions(tenant_id,bundle_id,version))""")
    bundleSnapshotTables.forEach { table -> for (action in listOf("UPDATE", "DELETE")) {
        featureSchema("""CREATE TRIGGER IF NOT EXISTS ${table}_no_${action.lowercase()} BEFORE $action ON $table
            BEGIN SELECT RAISE(ABORT,'Saved bundle history cannot be changed'); END""")
    } }
}

fun PosRepository.bundleDefinitions(tenantId: String): List<BundleDefinition> = featureRead(tenantId, StaffPermission.SELL) {
    bundleVersionRows(tenantId).groupBy { it.id }.values.map { it.last() }.sortedBy { it.terms.name.lowercase() }
}
fun PosRepository.bundleHistory(tenantId: String, bundleId: String): List<BundleDefinition> =
    featureRead(tenantId, StaffPermission.MANAGE_STOCK) { bundleVersionRows(tenantId).filter { it.id == bundleId } }
fun PosRepository.bundleSales(tenantId: String): List<BundleSale> = featureRead(tenantId, StaffPermission.VIEW_SALES) { bundleSaleRows(tenantId) }

fun PosRepository.saveBundle(tenantId: String, bundleId: String, name: String, active: Boolean,
    fixedPriceMinor: Long?, components: List<BundleComponentInput>, expectedVersion: Long, reason: String,
    operationId: String = UUID.randomUUID().toString()): BundleDefinition = featureWrite(tenantId, StaffPermission.MANAGE_STOCK) {
    bundleReference(bundleId); bundleReference(operationId); bundleReason(reason)
    require(expectedVersion >= 0 && (fixedPriceMinor == null || fixedPriceMinor >= 0)) { "Enter a non-negative bundle price." }
    val history = bundleVersionRows(tenantId)
    featureQuery("SELECT bundle_id,version,terms,reason,created_at FROM bundle_versions WHERE tenant_id=? AND id=?", tenantId, operationId) {
        BundleDefinition(it.getString("bundle_id"), it.getLong("version"), decodeBundleTerms(it.getString("terms")), it.getString("reason"), it.getString("created_at"))
    }.singleOrNull()?.let { previous ->
        require(previous.id == bundleId && previous.version == Math.addExact(expectedVersion, 1) && previous.reason == reason &&
            previous.terms.name == name && previous.terms.active == active && previous.terms.fixedPriceMinor == fixedPriceMinor &&
            previous.terms.components.map { BundleComponentInput(it.productId, it.sellingUnitId, it.quantity) } == components) {
            "This bundle change was saved with different details."
        }
        return@featureWrite previous
    }
    val previous = history.lastOrNull { it.id == bundleId }
    require((previous?.version ?: 0) == expectedVersion) { "The bundle changed. Reopen and review it before saving." }
    val terms = BundleTerms(name, active, fixedPriceMinor, snapshotBundleComponents(tenantId, components, allowArchived = !active))
    validateBundleTerms(terms)
    require(previous?.terms != terms) { "Change a bundle detail before saving." }
    val normal = terms.components.fold(0L) { total, component -> Math.addExact(total,
        Quantity(component.quantity.toLong(), component.quantityPrecision).lineTotalMinor(component.priceAtDefinitionMinor)) }
    require(fixedPriceMinor == null || fixedPriceMinor <= normal) { "The bundle price cannot exceed its current component prices. Review normal prices first." }
    val now = Instant.now()
    require(previous == null || now >= Instant.parse(previous.createdAt)) { "Correct the clock before changing this bundle." }
    val latestSale = bundleSaleRows(tenantId).filter { it.bundleId == bundleId }.maxOfOrNull { Instant.parse(it.createdAt) }
    require(latestSale == null || now >= latestSale) { "Correct the clock before changing this bundle." }
    val entry = BundleDefinition(bundleId, Math.addExact(expectedVersion, 1), terms, reason, now.toString())
    featureExecute("INSERT INTO bundle_versions VALUES(?,?,?,?,?,?,?)", tenantId, operationId, bundleId,
        entry.version, encodeBundleTerms(terms), reason, entry.createdAt)
    featureEvent(tenantId, "bundle.changed", operationId)
    entry
}

fun PosRepository.reviewBundleSale(tenantId: String, bundleId: String, count: Int): BundleSaleReview =
    featureRead(tenantId, StaffPermission.SELL) { prepareBundleSale(tenantId, bundleId, count) }

fun PosRepository.completeBundleCashSale(tenantId: String, review: BundleSaleReview, cashGivenMinor: Long,
    operationId: String = UUID.randomUUID().toString()): Sale = featureWrite(tenantId, StaffPermission.SELL) {
    bundleReference(operationId)
    require(cashGivenMinor >= review.totalMinor) { "Collect ${money(review.totalMinor)} before completing this cash sale." }
    if (review.discount != null) featureRead(tenantId, StaffPermission.DISCOUNT) { }
    bundleSaleRows(tenantId).singleOrNull { it.saleId == operationId }?.let { previous ->
        val sale = sales(tenantId).single { it.id == operationId }
        require(previous.bundleId == review.bundleId && previous.bundleVersion == review.version && previous.count == review.count &&
            previous.reviewToken == review.token && sale.cashGivenMinor == cashGivenMinor &&
            previous.linesFingerprint == bundleLinesFingerprint(review.lines)) { "This bundle sale was saved with different details." }
        return@featureWrite sale
    }
    val current = prepareBundleSale(tenantId, review.bundleId, review.count)
    require(current == review) { "The bundle, prices, minimums or available stock changed. Review the sale again." }
    val sale = checkout(tenantId, current.lines, current.totalMinor, operationId = operationId,
        basketDiscount = current.discount, cashGivenMinor = cashGivenMinor, note = bundleSaleNote(current.name, current.version, current.count))
    val link = BundleSale(sale.id, current.bundleId, current.version, current.count, current.token,
        bundleLinesFingerprint(sale.lines), sale.createdAt)
    featureExecute("INSERT INTO bundle_sales VALUES(?,?,?,?,?,?,?,?)", tenantId, link.saleId, link.bundleId,
        link.bundleVersion, link.count, link.reviewToken, link.linesFingerprint, link.createdAt)
    featureEvent(tenantId, "bundle.sold", sale.id)
    sales(tenantId).single { it.id == sale.id }
}

private fun PosRepository.prepareBundleSale(tenantId: String, bundleId: String, count: Int): BundleSaleReview {
    require(count in 1..1000000) { "Enter 1 to 1,000,000 whole bundles." }
    val definition = requireNotNull(bundleVersionRows(tenantId).lastOrNull { it.id == bundleId }) { "Choose a bundle in this shop." }
    require(definition.terms.active) { "Activate this bundle before selling it." }
    val products = products(tenantId, true).associateBy { it.id }
    val units = sellingUnits(tenantId, includeArchived = true).associateBy { it.id }
    val normalLines = definition.terms.components.map { component ->
        val product = requireNotNull(products[component.productId]) { "A component is unavailable." }
        val unit = component.sellingUnitId?.let { requireNotNull(units[it]) { "A selling unit is unavailable." } }
        require(!product.archived && unit?.archived != true && product.unit == component.baseUnit &&
            product.quantityPrecision == component.basePrecision && unit?.productId?.let { it == product.id } != false &&
            (unit?.name ?: product.unit) == component.sellingUnit && (unit?.baseUnitsPerUnit ?: 1) == component.baseUnitsPerUnit) {
            "A saved bundle unit changed or was archived. Edit the bundle before selling."
        }
        CartLine(product, Math.multiplyExact(component.quantity, count), unit?.id, unit?.name ?: product.unit,
            unit?.priceMinor ?: product.priceMinor, unit?.baseUnitsPerUnit ?: 1)
    }
    val normalTotal = cartTotal(normalLines)
    val price = definition.terms.fixedPriceMinor?.let { Math.multiplyExact(it, count.toLong()) } ?: normalTotal
    require(price <= normalTotal) { "The bundle price exceeds current component prices. Edit its price before selling." }
    val discount = if (price == normalTotal) null else BasketDiscount("fixed", normalTotal - price,
        "Bundle ${definition.terms.name} · revision ${definition.version} · $count bundle(s)")
    if (discount != null) featureRead(tenantId, StaffPermission.DISCOUNT) { }
    val lines = discount?.let { allocateBasketDiscount(normalLines, it) } ?: normalLines
    val now = Instant.now()
    require(now >= Instant.parse(definition.createdAt)) { "Correct the clock before selling this bundle." }
    lines.groupBy { it.product.id }.forEach { (_, group) ->
        val needed = group.fold(0L) { total, line -> Math.addExact(total, line.baseQuantity) }
        val product = group.first().product
        require(needed <= product.exactStockScaled) { "There is not enough ${product.name} for this many bundles." }
        requireUnreservedStock(tenantId, product, needed, now = now)
    }
    validateMinimumPricesAtCheckout(tenantId, lines)
    val held = orderReservedQuantities(tenantId)
    val token = bundleDigest(listOf(bundleId, definition.version.toString(), count.toString(), encodeBundleTerms(definition.terms), bundleLinesFingerprint(lines)) +
        lines.sortedBy { it.key }.flatMap { listOf(it.product.exactStockScaled.toString(), held[it.product.id].toString()) })
    return BundleSaleReview(bundleId, definition.version, definition.terms.name, count, lines, discount, price, token)
}

private fun PosRepository.snapshotBundleComponents(tenantId: String, inputs: List<BundleComponentInput>, allowArchived: Boolean): List<BundleComponent> {
    require(inputs.size in 1..50 && inputs.map { it.productId to it.sellingUnitId }.distinct().size == inputs.size) { "Choose 1 to 50 different item/unit combinations." }
    val products = products(tenantId, true).associateBy { it.id }
    val units = sellingUnits(tenantId, includeArchived = true).associateBy { it.id }
    return inputs.map { input ->
        val item = requireNotNull(products[input.productId]) { "A bundle component belongs to another shop or is unavailable." }
        val unit = input.sellingUnitId?.let { requireNotNull(units[it]) { "Choose a selling unit in this shop." } }
        require(unit == null || unit.productId == item.id) { "The selling unit belongs to another item." }
        require(allowArchived || !item.archived && unit?.archived != true) { "Choose active goods and selling units." }
        BundleComponent(item.id, unit?.id, input.quantity, if (unit == null) item.quantityPrecision else 0,
            item.name, item.unit, item.quantityPrecision, unit?.name ?: item.unit, unit?.baseUnitsPerUnit ?: 1, unit?.priceMinor ?: item.priceMinor)
    }
}

private fun validateBundleTerms(terms: BundleTerms) {
    require(terms.name == terms.name.trim() && terms.name.length in 1..100 && terms.name.none { it.isISOControl() }) { "Enter a bundle name up to 100 printable characters." }
    require(terms.fixedPriceMinor == null || terms.fixedPriceMinor >= 0)
    require(terms.components.size in 1..50 && terms.components.map { it.key }.distinct().size == terms.components.size)
    terms.components.forEach {
        require(it.productId.isNotBlank() && it.quantity > 0 && it.quantityPrecision in 0..6 && it.basePrecision in 0..6 &&
            it.quantityPrecision == (if (it.sellingUnitId == null) it.basePrecision else 0) && it.baseUnitsPerUnit > 0 && it.priceAtDefinitionMinor >= 0 &&
            it.productName.isNotBlank() && it.baseUnit.isNotBlank() && it.sellingUnit.isNotBlank()) { "Bundle components have invalid quantities or selling units." }
        require(it.sellingUnitId != null || it.baseUnitsPerUnit == 1 && it.sellingUnit == it.baseUnit)
    }
    val normal = terms.components.fold(0L) { total, component -> Math.addExact(total,
        Quantity(component.quantity.toLong(), component.quantityPrecision).lineTotalMinor(component.priceAtDefinitionMinor)) }
    require(terms.fixedPriceMinor == null || terms.fixedPriceMinor <= normal) { "Bundle price exceeds its saved component prices." }
}
private fun bundleReference(value: String) { require(value.length in 1..150 && value.none { it.isISOControl() }) { "Use a valid bundle reference." } }
private fun bundleReason(value: String) { require(value == value.trim() && value.length in 3..250 && value.none { it.isISOControl() }) { "Enter a reason from 3 to 250 printable characters." } }
private fun bundleSaleNote(name: String, version: Long, count: Int) = "Bundle $name · revision $version · $count bundle(s)"
private fun bundleLinesFingerprint(lines: List<CartLine>): String = bundleDigest(lines.sortedBy { it.key }.flatMap {
    listOf(it.key, it.product.name, it.product.unit, it.product.quantityPrecision.toString(), it.product.priceMinor.toString(),
        it.quantity.toString(), it.quantityPrecision.toString(), it.sellingUnit, it.baseUnitsPerUnit.toString(), it.unitPriceMinor.toString(),
        it.discountPerUnitMinor.toString(), it.discountReason.orEmpty(), it.basketDiscountMinor.toString()) })
private fun bundleDigest(fields: List<String>) = MessageDigest.getInstance("SHA-256").digest(fields.joinToString("") { "${it.length}:$it" }.toByteArray())
    .joinToString("") { "%02x".format(it) }

private fun encodeBundleTerms(value: BundleTerms): String = ByteArrayOutputStream().use { bytes ->
    DataOutputStream(bytes).use { out ->
        out.writeInt(1); out.writeUTF(value.name); out.writeBoolean(value.active)
        out.writeBoolean(value.fixedPriceMinor != null); value.fixedPriceMinor?.let(out::writeLong)
        out.writeInt(value.components.size)
        value.components.forEach { c ->
            out.writeUTF(c.productId); out.writeBoolean(c.sellingUnitId != null); c.sellingUnitId?.let(out::writeUTF)
            out.writeInt(c.quantity); out.writeInt(c.quantityPrecision); out.writeUTF(c.productName); out.writeUTF(c.baseUnit)
            out.writeInt(c.basePrecision); out.writeUTF(c.sellingUnit); out.writeInt(c.baseUnitsPerUnit); out.writeLong(c.priceAtDefinitionMinor)
        }
    }
    Base64.getEncoder().encodeToString(bytes.toByteArray())
}
private fun decodeBundleTerms(value: String): BundleTerms {
    require(value.length <= 200000) { "Bundle details exceed the supported size." }
    return DataInputStream(ByteArrayInputStream(Base64.getDecoder().decode(value))).use { input ->
        require(input.readInt() == 1)
        val name = input.readUTF(); val active = input.readBoolean(); val price = if (input.readBoolean()) input.readLong() else null
        val size = input.readInt(); require(size in 1..50)
        val components = List(size) {
            BundleComponent(input.readUTF(), if (input.readBoolean()) input.readUTF() else null, input.readInt(), input.readInt(),
                input.readUTF(), input.readUTF(), input.readInt(), input.readUTF(), input.readInt(), input.readLong())
        }
        require(input.read() == -1)
        BundleTerms(name, active, price, components).also(::validateBundleTerms)
    }
}
private fun PosRepository.bundleVersionRows(tenantId: String): List<BundleDefinition> = featureQuery(
    "SELECT * FROM bundle_versions WHERE tenant_id=? ORDER BY bundle_id,version", tenantId) {
    BundleDefinition(it.getString("bundle_id"), it.getLong("version"), decodeBundleTerms(it.getString("terms")), it.getString("reason"), it.getString("created_at"))
}
private fun PosRepository.bundleSaleRows(tenantId: String): List<BundleSale> = featureQuery("SELECT * FROM bundle_sales WHERE tenant_id=? ORDER BY created_at DESC,sale_id", tenantId) {
    BundleSale(it.getString("sale_id"), it.getString("bundle_id"), it.getLong("bundle_version"), it.getInt("bundle_count"),
        it.getString("review_token"), it.getString("lines_fingerprint"), it.getString("created_at"))
}

internal fun PosRepository.validateBundles(tenantId: String) {
    val history = bundleVersionRows(tenantId)
    val versions = history.associateBy { it.id to it.version }
    val products = products(tenantId, true).associateBy { it.id }
    val units = sellingUnits(tenantId, includeArchived = true).associateBy { it.id }
    history.groupBy { it.id }.forEach { (_, entries) -> entries.forEachIndexed { index, version ->
        validateBundleTerms(version.terms); bundleReason(version.reason); bundleReference(version.id)
        require(version.version == index + 1L && (index == 0 || version.terms != entries[index - 1].terms && Instant.parse(version.createdAt) >= Instant.parse(entries[index - 1].createdAt))) {
            "Bundle version history is inconsistent."
        }
        version.terms.components.forEach { component ->
            val item = requireNotNull(products[component.productId]) { "Bundle goods are missing." }
            val unit = component.sellingUnitId?.let { requireNotNull(units[it]) { "Bundle selling unit is missing." } }
            require(item.unit == component.baseUnit && item.quantityPrecision == component.basePrecision &&
                unit?.productId?.let { it == item.id } != false && (unit?.name ?: item.unit) == component.sellingUnit &&
                (unit?.baseUnitsPerUnit ?: 1) == component.baseUnitsPerUnit &&
                item.createdAt?.let { Instant.parse(it) <= Instant.parse(version.createdAt) } != false) { "Bundle quantity definitions differ from their goods." }
        }
    } }
    val sales = sales(tenantId).associateBy { it.id }
    val links = bundleSaleRows(tenantId)
    links.forEach { link ->
        val version = requireNotNull(versions[link.bundleId to link.bundleVersion]) { "Saved bundle sale revision is missing." }
        val sale = requireNotNull(sales[link.saleId]) { "Bundle sale receipt is missing." }
        val nextVersion = versions[link.bundleId to Math.addExact(link.bundleVersion, 1)]
        require(nextVersion == null || Instant.parse(sale.createdAt) <= Instant.parse(nextVersion.createdAt)) { "Bundle sale used a superseded revision." }
        require(version.terms.active && link.count in 1..1000000 && link.reviewToken.matches(Regex("[a-f0-9]{64}")) &&
            link.createdAt == sale.createdAt && Instant.parse(sale.createdAt) >= Instant.parse(version.createdAt) &&
            sale.cashMinor == sale.totalMinor && sale.cashGivenMinor != null && sale.creditMinor == 0L && sale.externalPayments.isEmpty() &&
            sale.exchange == null && sale.orderPayment == null && sale.note == bundleSaleNote(version.terms.name, version.version, link.count)) {
            "Bundle sale does not match its recorded cash receipt."
        }
        require(sale.lines.map { it.key }.toSet() == version.terms.components.map { it.key }.toSet()) { "Bundle sale components differ from the saved revision." }
        version.terms.components.forEach { component ->
            val line = sale.lines.single { it.key == component.key }
            require(line.quantity == Math.multiplyExact(component.quantity, link.count) && line.quantityPrecision == component.quantityPrecision &&
                line.product.unit == component.baseUnit && line.product.quantityPrecision == component.basePrecision &&
                line.sellingUnit == component.sellingUnit && line.baseUnitsPerUnit == component.baseUnitsPerUnit && line.discountPerUnitMinor == 0L) {
                "Bundle sale quantities do not match the saved bundle."
            }
        }
        val normal = cartSubtotal(sale.lines)
        val total = version.terms.fixedPriceMinor?.let { Math.multiplyExact(it, link.count.toLong()) } ?: normal
        val expectedDiscount = if (normal == total) null else BasketDiscount("fixed", normal - total,
            "Bundle ${version.terms.name} · revision ${version.version} · ${link.count} bundle(s)")
        require(sale.totalMinor == total && sale.basketDiscount == expectedDiscount && link.linesFingerprint == bundleLinesFingerprint(sale.lines)) { "Bundle sale price or source snapshot is inconsistent." }
    }
    val changed = featureQuery("""SELECT o.entity_id,a.id FROM outbox o LEFT JOIN activity_audit a
        ON a.tenant_id=o.tenant_id AND a.id=o.id AND a.kind=o.kind AND a.entity_id=o.entity_id
        WHERE o.tenant_id=? AND o.kind='bundle.changed'""", tenantId) { require(it.getString(2) != null); it.getString(1) }
    val ids = featureQuery("SELECT id FROM bundle_versions WHERE tenant_id=?", tenantId) { it.getString(1) }
    require(changed.size == ids.size && changed.toSet() == ids.toSet()) { "Bundle edit activity is incomplete." }
    val sold = featureQuery("""SELECT o.entity_id,a.id FROM outbox o LEFT JOIN activity_audit a
        ON a.tenant_id=o.tenant_id AND a.id=o.id AND a.kind=o.kind AND a.entity_id=o.entity_id
        WHERE o.tenant_id=? AND o.kind='bundle.sold'""", tenantId) { require(it.getString(2) != null); it.getString(1) }
    require(sold.size == links.size && sold.toSet() == links.map { it.saleId }.toSet()) { "Bundle sale activity is incomplete." }
}
