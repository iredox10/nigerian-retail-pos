package pos.domain

/** An immutable pack definition. Change a definition by archiving it and creating another. */
data class SellingUnit(
    val id: String, val tenantId: String, val productId: String, val name: String,
    val baseUnit: String, val baseUnitsPerUnit: Int, val priceMinor: Long,
    val barcode: String?, val archived: Boolean, val createdAt: String,
) {
    init {
        require(id.isNotBlank() && tenantId.isNotBlank() && productId.isNotBlank())
        require(name.isNotBlank() && name == name.trim() && baseUnit.isNotBlank() && createdAt.isNotBlank())
        require(baseUnitsPerUnit > 0 && priceMinor >= 0)
        require(barcode == null || (barcode.isNotBlank() && barcode == barcode.trim() && barcode.length <= 128 && barcode.none { it.isISOControl() }))
    }
}
