package pos.domain

/** Immutable buying package. Stock and checkout remain measured in the product's base selling unit. */
data class PurchaseUnit(
    val id: String, val tenantId: String, val productId: String, val name: String,
    val baseUnit: String, val baseUnitsPerUnit: Int, val archived: Boolean = false,
    val createdAt: String,
) {
    init {
        require(listOf(id,tenantId,productId,baseUnit,createdAt).all { it.isNotBlank() })
        require(name.isNotBlank() && name == name.trim() && name.length <= 100) { "Use a buying unit name from 1 to 100 characters." }
        require(!name.equals(baseUnit, ignoreCase = true)) { "A buying package must have a different name from the selling unit." }
        require(baseUnitsPerUnit > 0) { "A buying unit must contain at least one selling unit." }
    }
}
