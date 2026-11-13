package pos.domain

/** Merchant-defined catalogue organization; no price, quantity or selling-unit meaning. */
data class ProductMetadata(
    val sku: String? = null,
    val category: String? = null,
    val brand: String? = null,
    val aliases: List<String> = emptyList(),
    val favourite: Boolean = false,
) {
    init {
        fun valid(value: String?, limit: Int) = value == null ||
            (value.isNotBlank() && value == value.trim() && value.length <= limit && value.none { it.isISOControl() })
        require(valid(sku, 64)) { "Use an SKU of at most 64 printable characters, or leave it empty." }
        require(valid(category, 100) && valid(brand, 100)) { "Use category and brand names of at most 100 printable characters." }
        require(aliases.size <= 20 && aliases.all { valid(it, 100) }) { "Use up to 20 search names, each at most 100 printable characters." }
        require(aliases.map { it.lowercase() }.distinct().size == aliases.size) { "Each search name must be different." }
    }
}

fun Product.metadata() = ProductMetadata(sku, category, brand, aliases, favourite)

/** A newline separates names because printable-name validation excludes all control characters. */
fun encodeProductAliases(aliases: List<String>): String {
    ProductMetadata(aliases = aliases)
    return aliases.joinToString("\n")
}

fun decodeProductAliases(value: String): List<String> =
    (if (value.isEmpty()) emptyList() else value.split('\n')).also { ProductMetadata(aliases = it) }

fun Product.matchesCatalogueSearch(text: String): Boolean {
    val query = text.trim()
    return listOfNotNull(name, unit, barcode, sku, category, brand).plus(aliases).any { it.contains(query, true) }
}

data class ProductMetadataChange(
    val id: String, val tenantId: String, val productId: String,
    val previous: ProductMetadata, val current: ProductMetadata, val createdAt: String,
    val sequence: Long,
) {
    init {
        require(listOf(id,tenantId,productId,createdAt).all { it.isNotBlank() })
        require(sequence > 0) { "Catalogue history sequence must be positive." }
        require(previous != current) { "Catalogue history must describe a change." }
    }
}
