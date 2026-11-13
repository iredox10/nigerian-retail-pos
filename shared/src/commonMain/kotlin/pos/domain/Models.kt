package pos.domain

/** Identifiers are scoped to their business; a name is never an authorization boundary. */
data class Tenant(val id: String, val name: String) {
    init { require(id.isNotBlank()); require(name.isNotBlank()) }
}

data class Product(
    val id: String,
    val tenantId: String,
    val name: String,
    val unit: String,
    val priceMinor: Long,
    val stock: Double,
    val barcode: String? = null,
    val archived: Boolean = false,
    val reorderLevel: Int? = null,
    val openingStock: Double? = null,
    val createdAt: String? = null,
) {
    init {
        require(id.isNotBlank() && tenantId.isNotBlank())
        require(name.isNotBlank() && unit.isNotBlank())
        require(priceMinor >= 0) { "Selling price cannot be negative." }
        require(reorderLevel == null || reorderLevel >= 0) { "Reorder level cannot be negative." }
        require((openingStock == null) == (createdAt == null)) { "Opening stock and its recorded time must be present together." }
        require(openingStock == null || (openingStock.isFinite() && openingStock in 0.0..9_007_199_254_740_991.0)) { "Opening stock exceeds the supported range." }
        require(createdAt == null || createdAt.isNotBlank()) { "Opening stock time cannot be empty." }
        require(barcode == null || (barcode.isNotBlank() && barcode == barcode.trim() && barcode.length <= 128 && barcode.none { it.isISOControl() })) { "Use a non-empty barcode of at most 128 characters." }
        require(stock.isFinite() && stock >= 0 && stock <= 9_007_199_254_740_991.0) { "Stock must be a non-negative quantity within the supported exact range." }
    }
}

data class Customer(
    val id: String, val tenantId: String, val name: String, val balanceMinor: Long,
    val phone: String? = null, val address: String? = null, val creditLimitMinor: Long? = null,
) {
    init {
        require(id.isNotBlank() && tenantId.isNotBlank() && name.isNotBlank())
        require(balanceMinor >= 0) { "Customer debt cannot be negative." }
        require(creditLimitMinor == null || creditLimitMinor >= 0) { "Credit limit cannot be negative." }
        require(phone == null || normalizeNigerianPhone(phone) == phone) { "Customer phone must be stored in international format." }
        require(address == null || (address.isNotBlank() && address == address.trim() && address.length <= 1000)) { "Use an address of at most 1000 characters, or leave it empty." }
    }
}

data class CartLine(
    val product: Product, val quantity: Int,
    val sellingUnitId: String? = null,
    val sellingUnit: String = product.unit,
    val unitPriceMinor: Long = product.priceMinor,
    val baseUnitsPerUnit: Int = 1,
) {
    init {
        require(quantity > 0) { "Choose a quantity greater than zero." }
        require(sellingUnitId == null || sellingUnitId.isNotBlank())
        require(sellingUnit.isNotBlank() && unitPriceMinor >= 0 && baseUnitsPerUnit > 0)
        require(sellingUnitId != null || (sellingUnit == product.unit && unitPriceMinor == product.priceMinor && baseUnitsPerUnit == 1)) { "Base selling units must match the item." }
    }
    val key: String get() = sellingUnitId?.let { product.id + "~" + it } ?: product.id
    val baseQuantity: Long get() = quantity.toLong() * baseUnitsPerUnit
    val totalMinor: Long get() = multiplyMoney(unitPriceMinor, quantity)
}

/** Cash means cash applied to this sale, excluding change handed back. */
data class Sale(
    val id: String,
    val tenantId: String,
    val lines: List<CartLine>,
    val totalMinor: Long,
    val cashMinor: Long,
    val creditMinor: Long,
    val customerId: String?,
    val createdAt: String,
    val dueDate: String? = null,
) {
    init {
        require(dueDate == null || (creditMinor > 0 && isValidCreditDueDate(dueDate))) { "A due date must be a valid YYYY-MM-DD date for a credit sale." }
        require(id.isNotBlank() && tenantId.isNotBlank() && createdAt.isNotBlank())
        require(lines.isNotEmpty()) { "Add an item before taking payment." }
        require(lines.all { it.product.tenantId == tenantId }) { "Items belong to another shop." }
        require(lines.map { it.key }.distinct().size == lines.size) { "Combine repeated items into one line." }
        require(totalMinor >= 0 && totalMinor == cartTotal(lines)) { "Sale total does not match its items." }
        require(cashMinor >= 0 && creditMinor >= 0 && addMoney(cashMinor, creditMinor) == totalMinor) {
            "Cash and credit must cover the sale exactly."
        }
        require(customerId == null || customerId.isNotBlank())
        require(creditMinor == 0L || customerId != null) { "Choose a customer for money owed." }
    }
}

data class CheckoutResult(val sale: Sale, val changeMinor: Long)

fun cartTotal(lines: List<CartLine>): Long = lines.fold(0L) { sum, line -> addMoney(sum, line.totalMinor) }

/** Pure checkout validation; persistence must re-read current tenant stock in a transaction. */
fun checkout(
    tenantId: String,
    id: String,
    lines: List<CartLine>,
    cashGivenMinor: Long,
    customer: Customer? = null,
    allowCredit: Boolean = false,
    createdAt: String,
    dueDate: String? = null,
): CheckoutResult {
    require(tenantId.isNotBlank())
    require(cashGivenMinor >= 0) { "Enter a non-negative cash amount." }
    require(customer == null || customer.tenantId == tenantId) { "Customer belongs to another shop." }
    require(lines.isNotEmpty()) { "Add an item before taking payment." }
    require(lines.all { it.product.tenantId == tenantId }) { "Items belong to another shop." }
    require(lines.groupBy { it.product.id }.values.all { group -> group.fold(0L) { sum, line -> addMoney(sum, line.baseQuantity) }.toDouble() <= group.first().product.stock }) { "There is not enough stock for this sale." }
    val total = cartTotal(lines)
    val appliedCash = minOf(cashGivenMinor, total)
    val credit = total - appliedCash
    require(credit == 0L || (allowCredit && customer != null)) {
        "Still to pay: choose another payment or explicitly allow customer credit."
    }
    if (customer != null) {
        val debt=addMoney(customer.balanceMinor, credit)
        require(credit == 0L || customer.creditLimitMinor == null || debt <= customer.creditLimitMinor) { "This sale would exceed the customer's credit limit." }
    }
    return CheckoutResult(
        Sale(id, tenantId, lines.toList(), total, appliedCash, credit, customer?.id, createdAt, dueDate),
        cashGivenMinor - appliedCash,
    )
}

internal fun addMoney(left: Long, right: Long): Long {
    require(left >= 0 && right >= 0 && left <= Long.MAX_VALUE - right) { "Amount exceeds supported money range." }
    return left + right
}

internal fun multiplyMoney(amount: Long, quantity: Int): Long {
    require(amount >= 0 && quantity > 0 && amount <= Long.MAX_VALUE / quantity) { "Amount exceeds supported money range." }
    return amount * quantity
}

/** Formats integer kobo without floating-point rounding or a network/locale dependency. */
fun money(minor: Long): String {
    val digits = minor.toString().removePrefix("-").padStart(3, '0')
    val whole = digits.dropLast(2).reversed().chunked(3).joinToString(",").reversed()
    return "${if (minor < 0) "−" else ""}₦$whole.${digits.takeLast(2)}"
}
