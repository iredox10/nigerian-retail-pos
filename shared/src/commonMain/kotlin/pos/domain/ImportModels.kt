package pos.domain

data class ProductImportRow(val name: String, val unit: String, val priceMinor: Long, val stock: Int) {
    init {
        require(name.isNotBlank() && unit.isNotBlank()) { "Every item needs a name and selling unit." }
        require(priceMinor >= 0 && stock >= 0) { "Price and opening stock cannot be negative." }
    }
}
