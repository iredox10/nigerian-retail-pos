package pos.data

import java.security.MessageDigest
import java.time.Instant
import java.util.Locale
import java.util.UUID
import pos.domain.*

data class VariantOptionAxis(val name: String, val values: List<String>)
data class VariantMatrixDefinition(val family: String, val unit: String, val precision: Int,
    val first: VariantOptionAxis, val second: VariantOptionAxis? = null)
data class VariantMatrixRow(val firstValue: String, val secondValue: String?, val sku: String,
    val barcode: String?, val priceMinor: Long, val openingScaled: Long)
data class VariantMatrixReview(val definition: VariantMatrixDefinition, val rows: List<VariantMatrixRow>, val catalogueToken: String)
data class VariantMatrixCreatedRow(val productId: String, val values: VariantMatrixRow)
data class VariantMatrixBatch(val id: String, val definition: VariantMatrixDefinition,
    val rows: List<VariantMatrixCreatedRow>, val reason: String, val createdAt: String)

internal val variantMatrixSnapshotTables = listOf("variant_matrix_batches", "variant_matrix_rows")

internal fun PosRepository.initializeVariantMatrices() {
    featureSchema("""CREATE TABLE IF NOT EXISTS variant_matrix_batches(
        tenant_id TEXT NOT NULL,id TEXT NOT NULL,family TEXT NOT NULL,unit TEXT NOT NULL,
        precision INTEGER NOT NULL CHECK(typeof(precision)='integer' AND precision BETWEEN 0 AND 6),
        first_axis TEXT NOT NULL,first_values TEXT NOT NULL,second_axis TEXT,second_values TEXT,
        reason TEXT NOT NULL,created_at TEXT NOT NULL,fingerprint TEXT NOT NULL,
        PRIMARY KEY(tenant_id,id),CHECK((second_axis IS NULL)=(second_values IS NULL)),
        FOREIGN KEY(tenant_id) REFERENCES tenants(id))""")
    featureSchema("""CREATE TABLE IF NOT EXISTS variant_matrix_rows(
        tenant_id TEXT NOT NULL,batch_id TEXT NOT NULL,position INTEGER NOT NULL CHECK(typeof(position)='integer' AND position BETWEEN 0 AND 255),
        product_id TEXT NOT NULL,first_value TEXT NOT NULL,second_value TEXT,sku TEXT NOT NULL,barcode TEXT,
        price_minor INTEGER NOT NULL CHECK(typeof(price_minor)='integer' AND price_minor>=0),
        opening_scaled INTEGER NOT NULL CHECK(typeof(opening_scaled)='integer' AND opening_scaled BETWEEN 0 AND 2147483647),
        PRIMARY KEY(tenant_id,batch_id,position),UNIQUE(tenant_id,product_id),
        FOREIGN KEY(tenant_id,batch_id) REFERENCES variant_matrix_batches(tenant_id,id),
        FOREIGN KEY(tenant_id,product_id) REFERENCES products(tenant_id,id))""")
    variantMatrixSnapshotTables.forEach { table ->
        for (action in listOf("UPDATE", "DELETE")) featureSchema("""CREATE TRIGGER IF NOT EXISTS ${table}_no_${action.lowercase()}
            BEFORE $action ON $table BEGIN SELECT RAISE(ABORT,'Variant matrix history cannot be changed'); END""")
    }
}

fun variantMatrixCombinations(definition: VariantMatrixDefinition): List<Pair<String, String?>> {
    validateMatrixDefinition(definition)
    return definition.first.values.flatMap { first ->
        definition.second?.values?.map { first to it } ?: listOf(first to null)
    }
}

fun variantMatrixLabel(definition: VariantMatrixDefinition, row: VariantMatrixRow): String =
    "${definition.first.name} ${row.firstValue}" + (definition.second?.let { " · ${it.name} ${row.secondValue}" } ?: "")

fun PosRepository.reviewVariantMatrix(tenantId: String, definition: VariantMatrixDefinition,
    rows: List<VariantMatrixRow>): VariantMatrixReview = featureRead(tenantId, StaffPermission.MANAGE_STOCK) {
    matrixReview(tenantId, definition, rows)
}

fun PosRepository.createVariantMatrix(tenantId: String, review: VariantMatrixReview, reason: String,
    operationId: String = UUID.randomUUID().toString()): VariantMatrixBatch = featureWrite(tenantId, StaffPermission.MANAGE_STOCK) {
    require(operationId.length in 1..150 && operationId.none { it.isISOControl() }) { "Use a valid matrix operation reference." }
    val cleanReason = reason.trim()
    validateMatrixReason(cleanReason)
    val fingerprint = matrixFingerprint(review.definition, review.rows, cleanReason)
    matrixBatches(tenantId).singleOrNull { it.id == operationId }?.let { previous ->
        require(matrixFingerprint(previous.definition, previous.rows.map { it.values }, previous.reason) == fingerprint) {
            "This variant matrix was saved with different details."
        }
        return@featureWrite previous
    }
    require(matrixReview(tenantId, review.definition, review.rows).catalogueToken == review.catalogueToken) {
        "Catalogue identifiers or variant labels changed. Review this matrix again before saving."
    }
    val definition = review.definition
    val created = review.rows.mapIndexed { index, row ->
        val name = matrixProductName(definition, row)
        val product = addProduct(tenantId, name, definition.unit, row.priceMinor,
            projectedQuantity(row.openingScaled, definition.precision), row.barcode, definition.precision)
        updateProductMetadata(tenantId, product.id, ProductMetadata(sku = row.sku), matrixChildId(operationId, index, "sku"))
        saveProductVariant(tenantId, product.id, ProductVariant(definition.family, variantMatrixLabel(definition, row)),
            0, cleanReason, matrixChildId(operationId, index, "variant"))
        VariantMatrixCreatedRow(product.id, row)
    }
    val now = Instant.now().toString()
    featureExecute("INSERT INTO variant_matrix_batches VALUES(?,?,?,?,?,?,?,?,?,?,?,?)", tenantId, operationId,
        definition.family, definition.unit, definition.precision, definition.first.name, definition.first.values.joinToString("\n"),
        definition.second?.name, definition.second?.values?.joinToString("\n"), cleanReason, now, fingerprint)
    created.forEachIndexed { index, row -> featureExecute("INSERT INTO variant_matrix_rows VALUES(?,?,?,?,?,?,?,?,?,?)",
        tenantId, operationId, index, row.productId, row.values.firstValue, row.values.secondValue, row.values.sku,
        row.values.barcode, row.values.priceMinor, row.values.openingScaled) }
    featureEvent(tenantId, "catalogue.variant_matrix_created", operationId)
    VariantMatrixBatch(operationId, definition, created, cleanReason, now)
}

fun PosRepository.variantMatrixBatches(tenantId: String): List<VariantMatrixBatch> =
    featureRead(tenantId, StaffPermission.MANAGE_STOCK) { matrixBatches(tenantId) }

private fun PosRepository.matrixReview(tenantId: String, definition: VariantMatrixDefinition,
    rows: List<VariantMatrixRow>): VariantMatrixReview {
    validateMatrixRows(definition, rows)
    val products = products(tenantId, true)
    val variants = productVariants(tenantId)
    val skus = products.mapNotNull { it.sku?.lowercase(Locale.ROOT) }.toSet()
    val barcodes = products.mapNotNull { it.barcode }.toSet() + sellingUnits(tenantId, includeArchived = true).mapNotNull { it.barcode }
    val labels = variants.values.map { it.key }.toSet()
    rows.forEach { row ->
        require(row.sku.lowercase(Locale.ROOT) !in skus) { "SKU ${row.sku} is already used, including archived goods." }
        require(row.barcode == null || row.barcode !in barcodes) { "Barcode ${row.barcode} is already used." }
        require(ProductVariant(definition.family, variantMatrixLabel(definition, row)).key !in labels) {
            "${variantMatrixLabel(definition, row)} already identifies an item in this family."
        }
    }
    val token = matrixDigest(products.sortedBy { it.id }.flatMap { listOf(it.id, it.sku.orEmpty(), it.barcode.orEmpty()) } +
        variants.entries.sortedBy { it.key }.flatMap { listOf(it.key, it.value.family, it.value.label) } +
        sellingUnits(tenantId, includeArchived = true).sortedBy { it.id }.flatMap { listOf(it.id, it.barcode.orEmpty()) })
    return VariantMatrixReview(definition, rows.toList(), token)
}

private fun validateMatrixDefinition(value: VariantMatrixDefinition) {
    matrixText(value.family, 100, "family name")
    matrixText(value.unit, 40, "selling unit")
    require(value.precision in 0..6) { "Choose a supported quantity precision." }
    fun axis(axis: VariantOptionAxis) {
        matrixText(axis.name, 30, "option name")
        require(axis.values.size in 1..32) { "Use 1 to 32 values for each option." }
        axis.values.forEach { matrixText(it, 40, "option value") }
        require(axis.values.map { it.lowercase(Locale.ROOT) }.distinct().size == axis.values.size) { "Each option value must be different." }
    }
    axis(value.first)
    value.second?.let { second ->
        axis(second)
        require(!second.name.equals(value.first.name, true)) { "Give the two options different names." }
    }
    require(value.first.values.size * (value.second?.values?.size ?: 1) <= 256) { "Create at most 256 combinations in one matrix." }
}

private fun validateMatrixRows(definition: VariantMatrixDefinition, rows: List<VariantMatrixRow>) {
    val combinations = variantMatrixCombinations(definition).toSet()
    require(rows.size in 1..256 && rows.map { it.firstValue to it.secondValue }.distinct().size == rows.size) {
        "Choose 1 to 256 different combinations."
    }
    require(rows.map { it.sku.lowercase(Locale.ROOT) }.distinct().size == rows.size) { "Give each combination a different SKU." }
    require(rows.mapNotNull { it.barcode }.distinct().size == rows.count { it.barcode != null }) { "Each barcode must be different." }
    require(rows.map { variantMatrixLabel(definition, it).lowercase(Locale.ROOT) }.distinct().size == rows.size) {
        "Option names and values produce repeated variant labels. Use simpler distinguishing values."
    }
    rows.forEach { row ->
        require((row.firstValue to row.secondValue) in combinations) { "A row does not match the chosen options." }
        matrixText(row.sku, 64, "SKU")
        require(row.barcode == null || row.barcode.length in 1..128 && row.barcode == row.barcode.trim() && row.barcode.none { it.isISOControl() }) { "Use a printable barcode up to 128 characters." }
        require(row.priceMinor >= 0 && row.openingScaled in 0..2147483647L) { "Enter a non-negative price and opening count within the supported range." }
        require(matrixProductName(definition, row).length <= 150) { "Shorten the family or option values so item names fit 150 characters." }
        ProductVariant(definition.family, variantMatrixLabel(definition, row))
    }
}

private fun matrixProductName(definition: VariantMatrixDefinition, row: VariantMatrixRow) =
    "${definition.family} · ${row.firstValue}" + (row.secondValue?.let { " · $it" } ?: "")
private fun matrixText(value: String, maximum: Int, label: String) {
    require(value == value.trim() && value.length in 1..maximum && value.none { it.isISOControl() }) { "Enter a printable $label up to $maximum characters." }
}
private fun validateMatrixReason(value: String) = matrixText(value, 250, "reason").also {
    require(value.length >= 3) { "Enter a reason with at least three characters." }
}
private fun matrixChildId(batch: String, index: Int, kind: String) = UUID.nameUUIDFromBytes("matrix:$batch:$index:$kind".toByteArray()).toString()
private fun matrixFingerprint(definition: VariantMatrixDefinition, rows: List<VariantMatrixRow>, reason: String) = matrixDigest(
    listOf(definition.family, definition.unit, definition.precision.toString(), definition.first.name, definition.first.values.size.toString()) + definition.first.values +
        listOf((definition.second != null).toString(), definition.second?.name.orEmpty(), (definition.second?.values?.size ?: 0).toString()) +
        definition.second?.values.orEmpty() + listOf(reason, rows.size.toString()) + rows.flatMap {
        listOf(it.firstValue, it.secondValue.orEmpty(), it.sku, it.barcode.orEmpty(), it.priceMinor.toString(), it.openingScaled.toString()) })
private fun matrixDigest(fields: List<String>) = MessageDigest.getInstance("SHA-256").digest(
    fields.joinToString("") { "${it.length}:$it" }.toByteArray()).joinToString("") { "%02x".format(it) }

private fun PosRepository.matrixBatches(tenantId: String): List<VariantMatrixBatch> = featureQuery(
    "SELECT * FROM variant_matrix_batches WHERE tenant_id=? ORDER BY created_at DESC,id", tenantId) { row ->
    val id = row.getString("id")
    VariantMatrixBatch(id, VariantMatrixDefinition(row.getString("family"), row.getString("unit"), row.getInt("precision"),
        VariantOptionAxis(row.getString("first_axis"), row.getString("first_values").split('\n')),
        row.getString("second_axis")?.let { VariantOptionAxis(it, row.getString("second_values").split('\n')) }),
        featureQuery("SELECT * FROM variant_matrix_rows WHERE tenant_id=? AND batch_id=? ORDER BY position", tenantId, id) {
            VariantMatrixCreatedRow(it.getString("product_id"), VariantMatrixRow(it.getString("first_value"), it.getString("second_value"),
                it.getString("sku"), it.getString("barcode"), it.getLong("price_minor"), it.getLong("opening_scaled")))
        }, row.getString("reason"), row.getString("created_at"))
}

internal fun PosRepository.validateVariantMatrices(tenantId: String) {
    val batches = matrixBatches(tenantId)
    val products = products(tenantId, true).associateBy { it.id }
    val witnesses = featureQuery("""SELECT o.entity_id,o.created_at,a.id FROM outbox o
        LEFT JOIN activity_audit a ON a.tenant_id=o.tenant_id AND a.id=o.id AND a.kind=o.kind AND a.entity_id=o.entity_id
        WHERE o.tenant_id=? AND o.kind='catalogue.variant_matrix_created'""", tenantId) {
        require(it.getString(3) != null) { "Variant matrix activity is missing." }
        it.getString(1) to Instant.parse(it.getString(2))
    }
    require(witnesses.size == batches.size && witnesses.map { it.first }.toSet() == batches.map { it.id }.toSet()) { "Variant matrix history is incomplete." }
    batches.forEach { batch ->
        validateMatrixRows(batch.definition, batch.rows.map { it.values })
        validateMatrixReason(batch.reason)
        val at = Instant.parse(batch.createdAt)
        require(witnesses.single { it.first == batch.id }.second >= at) { "Variant matrix activity predates its batch." }
        val fingerprint = featureQuery("SELECT fingerprint FROM variant_matrix_batches WHERE tenant_id=? AND id=?", tenantId, batch.id) { it.getString(1) }.single()
        require(fingerprint == matrixFingerprint(batch.definition, batch.rows.map { it.values }, batch.reason)) { "Variant matrix rows differ from their saved review." }
        val positions = featureQuery("SELECT position FROM variant_matrix_rows WHERE tenant_id=? AND batch_id=? ORDER BY position", tenantId, batch.id) { it.getInt(1) }
        require(positions == batch.rows.indices.toList()) { "Variant matrix row order is incomplete." }
        batch.rows.forEachIndexed { index, row ->
            val product = requireNotNull(products[row.productId]) { "Variant matrix item is missing." }
            require(product.unit == batch.definition.unit && product.quantityPrecision == batch.definition.precision &&
                product.openingStock == projectedQuantity(row.values.openingScaled, batch.definition.precision) &&
                product.createdAt != null && Instant.parse(product.createdAt) <= at) { "Variant matrix opening units or quantity are inconsistent." }
            val initialPrice = featureQuery("SELECT previous_price_minor FROM catalogue_changes WHERE tenant_id=? AND product_id=? ORDER BY created_at,id LIMIT 1", tenantId, product.id) { it.getLong(1) }.singleOrNull()
            require((initialPrice ?: product.priceMinor) == row.values.priceMinor) { "Variant matrix opening price does not match its item history." }
            val metadata = productMetadataChanges(tenantId, product.id).singleOrNull { it.id == matrixChildId(batch.id, index, "sku") }
            require(metadata?.current?.sku == row.values.sku) { "Variant matrix SKU source is missing." }
            val variant = featureQuery("SELECT family,label,created_at FROM product_variant_history WHERE tenant_id=? AND id=? AND product_id=?", tenantId,
                matrixChildId(batch.id, index, "variant"), product.id) { Triple(it.getString(1), it.getString(2), Instant.parse(it.getString(3))) }.singleOrNull()
            require(variant != null && variant.first == batch.definition.family && variant.second == variantMatrixLabel(batch.definition, row.values) && variant.third <= at) {
                "Variant matrix option source is inconsistent."
            }
        }
    }
}
