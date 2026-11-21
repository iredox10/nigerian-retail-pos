package pos.data

import java.math.BigInteger
import java.time.Instant
import pos.domain.StaffPermission
import pos.domain.StockReceipt
import pos.domain.SupplierProfile

data class PurchaseCostRecord(val receipt: StockReceipt, val supplier: SupplierProfile?) {
    init {
        require(supplier == null || supplier.tenantId == receipt.tenantId)
    }

    private val divisor get() = receipt.baseUnitsPerUnit.toLong()

    val roundedBaseCostMinor: Long
        get() {
            val remainder = receipt.unitCostMinor % divisor
            return receipt.unitCostMinor / divisor + if (remainder * 2 >= divisor) 1 else 0
        }

    val baseCostIsRounded: Boolean get() = receipt.unitCostMinor % divisor != 0L

    val purchaseDate: Instant get() = Instant.parse(receipt.createdAt)

    fun compareBaseCost(other: PurchaseCostRecord): Int {
        require(receipt.tenantId == other.receipt.tenantId &&
            receipt.productId == other.receipt.productId && receipt.baseUnit == other.receipt.baseUnit)
        // Compare exact ratios before rounding either price to kobo for presentation.
        val left = BigInteger.valueOf(receipt.unitCostMinor) * BigInteger.valueOf(other.divisor)
        val right = BigInteger.valueOf(other.receipt.unitCostMinor) * BigInteger.valueOf(divisor)
        return left.compareTo(right)
    }
}

fun PosRepository.purchaseCostHistory(tenantId: String): List<PurchaseCostRecord> =
    featureRead(tenantId, StaffPermission.MANAGE_STOCK) {
        val profiles = supplierProfiles(tenantId, includeArchived = true).associateBy { it.id }
        val links = supplierReceiptLinks(tenantId).associateBy { it.receiptId }
        stockReceipts(tenantId).map { receipt ->
            PurchaseCostRecord(receipt, links[receipt.id]?.let { profiles.getValue(it.supplierId) })
        }.sortedWith(compareByDescending<PurchaseCostRecord> { it.purchaseDate }.thenBy { it.receipt.id })
    }

/** Name-only receipts stay in history. Comparison uses only explicitly linked supplier accounts. */
fun List<PurchaseCostRecord>.latestSupplierPrices(
    tenantId: String,
    productId: String,
    baseUnit: String,
): List<PurchaseCostRecord> {
    require(all { it.receipt.tenantId == tenantId })
    return filter { it.receipt.productId == productId && it.receipt.baseUnit == baseUnit && it.supplier != null }
        .sortedWith(compareByDescending<PurchaseCostRecord> { it.purchaseDate }.thenBy { it.receipt.id })
        .distinctBy { it.supplier?.id }
        .sortedWith { first, second ->
            first.compareBaseCost(second).takeIf { it != 0 } ?: first.receipt.id.compareTo(second.receipt.id)
        }
}
