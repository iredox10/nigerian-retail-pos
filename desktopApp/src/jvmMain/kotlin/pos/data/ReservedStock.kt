package pos.data

import java.time.Instant
import pos.domain.Product

internal fun Product.unreservedStock(held: Long): Long = (exactStockScaled - held).coerceAtLeast(0L)

internal fun PosRepository.requireUnreservedStock(
    tenantId: String,
    product: Product,
    deduction: Long,
    excludingOrderId: String? = null,
    now: Instant = Instant.now(),
) {
    if (deduction <= 0L) return
    val held = reservedStock(tenantId, product.id, excludingOrderId, now)
    require(held == 0L || deduction <= product.unreservedStock(held)) {
        "Some stock for ${product.name} is reserved for orders. Choose a smaller quantity or release a reservation."
    }
}
