package pos.output

import pos.domain.*
import pos.screens.ReportDateRange
import java.math.BigDecimal
import java.time.LocalDate

/** Orders are requests, not stock receipts, payments, supplier debt or profit. */
object PurchaseOrderCsv {
    fun createdInRange(tenantId:String,orders:List<PurchaseOrder>,from:LocalDate,through:LocalDate):List<PurchaseOrder> {
        require(tenantId.isNotBlank() && orders.all {it.tenantId==tenantId} && orders.map {it.id}.distinct().size==orders.size) {"Purchase order export includes foreign or repeated orders."}
        val range=ReportDateRange(from,through)
        return orders.filter {range.contains(it.createdAt)}.sortedWith(compareBy<PurchaseOrder> {it.createdAt}.thenBy {it.id})
    }
    fun order(order:PurchaseOrder,deliveries:List<PurchaseOrderDelivery>,receipts:List<StockReceipt>):String {
        require(deliveries.all {it.tenantId==order.tenantId && it.orderId==order.id} && deliveries.map {it.id}.distinct().size==deliveries.size && receipts.all {it.tenantId==order.tenantId} && receipts.map {it.id}.distinct().size==receipts.size) {"Purchase history includes foreign or duplicate records."}
        val deliveredIds=deliveries.flatMap {it.lines}.map {it.receiptId}
        require(deliveredIds.distinct().size==deliveredIds.size) {"A stock receipt appears in more than one delivery."}
        val byReceipt=receipts.associateBy {it.id}
        val received=deliveries.flatMap {it.lines}.groupBy {it.lineId}
        require(received.keys.all {id ->order.lines.any {it.id==id}})
        fun text(s:String)=CsvCell.Text(s)
        fun number(n:Long)=CsvCell.Number(n.toString())
        fun amount(n:Long)=CsvCell.Number(BigDecimal.valueOf(n,2).toPlainString())
        val rows=order.lines.map {line ->
            val delivered=received[line.id].orEmpty()
            val quantity=delivered.fold(0L) {sum,d ->Math.addExact(sum,d.quantity.toLong())}
            require(quantity<=line.quantity)
            var actualCost=0L;var paid=0L
            delivered.forEach {d ->
                val receipt=requireNotNull(byReceipt[d.receiptId]) {"A delivery's stock receipt is missing."}
                require(receipt.productId==line.productId && receipt.productName==line.productName && receipt.unit==line.buyingUnit && receipt.baseUnit==line.baseUnit && receipt.quantity==d.quantity && receipt.paidCashMinor==d.paidCashMinor && receipt.unitCostMinor==line.unitCostMinor && receipt.quantityPrecision==line.quantityPrecision && receipt.basePrecision==line.basePrecision && receipt.purchaseUnitId==line.purchaseUnitId && receipt.baseUnitsPerUnit==line.baseUnitsPerUnit) {"Delivery does not match its receipt."}
                actualCost=Math.addExact(actualCost,receipt.totalCostMinor);paid=Math.addExact(paid,receipt.paidCashMinor)
            }
            listOf(text(order.id),text(order.status.name),text(order.supplierId),text(order.supplierName),text(order.createdAt),text(order.approvedAt.orEmpty()),text(line.id),text(line.productName),text(line.buyingUnit),CsvCell.Number(line.quantityLabel),CsvCell.Number(quantityLabel(quantity,line.quantityPrecision)),CsvCell.Number(quantityLabel(line.quantity.toLong()-quantity,line.quantityPrecision)),amount(line.unitCostMinor),amount(line.estimatedTotalMinor),amount(actualCost),amount(paid),text(delivered.joinToString(" | ") {it.receiptId}),text(order.notes))
        }
        return CsvService.encode(listOf("Purchase order ID","Order state — not payment status","Supplier account ID","Supplier name saved on order","Created at UTC","Approved at UTC","Order line ID","Item saved on order","Buying unit","Ordered quantity","Received quantity","Unreceived quantity — cancelled orders will not receive","Agreed buying cost per unit NGN","Estimated order line cost NGN","Actual received cost NGN","Cash paid at receipt NGN","Linked stock receipt IDs","Order notes"),rows)
    }
}
