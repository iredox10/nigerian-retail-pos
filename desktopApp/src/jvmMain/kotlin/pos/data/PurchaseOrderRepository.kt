package pos.data

import pos.domain.*
import java.io.*
import java.sql.ResultSet
import java.time.Instant
import java.util.Base64
import java.util.UUID

internal val purchaseOrderSnapshotTables=listOf("purchase_orders","purchase_order_lines","purchase_order_events","purchase_order_deliveries","purchase_order_delivery_lines")

internal fun PosRepository.initializePurchaseOrders() {
    featureSchema("CREATE TABLE IF NOT EXISTS purchase_orders(id TEXT NOT NULL,tenant_id TEXT NOT NULL REFERENCES tenants(id),supplier_id TEXT NOT NULL,supplier_name TEXT NOT NULL,supplier_version INTEGER NOT NULL CHECK(supplier_version>0),status TEXT NOT NULL CHECK(status IN('DRAFT','APPROVED','CANCELLED')),version INTEGER NOT NULL CHECK(version>0),created_at TEXT NOT NULL,approved_at TEXT,cancelled_at TEXT,cancellation_reason TEXT,notes TEXT NOT NULL,PRIMARY KEY(tenant_id,id),FOREIGN KEY(tenant_id,supplier_id) REFERENCES supplier_profiles(tenant_id,id))")
    featureSchema("CREATE TABLE IF NOT EXISTS purchase_order_lines(tenant_id TEXT NOT NULL,order_id TEXT NOT NULL,id TEXT NOT NULL,position INTEGER NOT NULL CHECK(position>=0),product_id TEXT NOT NULL,product_name TEXT NOT NULL,base_unit TEXT NOT NULL,buying_unit TEXT NOT NULL,quantity INTEGER NOT NULL CHECK(quantity>0),quantity_precision INTEGER NOT NULL CHECK(quantity_precision BETWEEN 0 AND 6),base_precision INTEGER NOT NULL CHECK(base_precision BETWEEN 0 AND 6),base_units_per_unit INTEGER NOT NULL CHECK(base_units_per_unit>0),unit_cost_minor INTEGER NOT NULL CHECK(unit_cost_minor>=0),purchase_unit_id TEXT,PRIMARY KEY(tenant_id,order_id,id),UNIQUE(tenant_id,order_id,position),FOREIGN KEY(tenant_id,order_id) REFERENCES purchase_orders(tenant_id,id),FOREIGN KEY(tenant_id,product_id) REFERENCES products(tenant_id,id))")
    featureSchema("CREATE TABLE IF NOT EXISTS purchase_order_events(tenant_id TEXT NOT NULL,id TEXT NOT NULL,order_id TEXT NOT NULL,version INTEGER NOT NULL CHECK(version>0),kind TEXT NOT NULL CHECK(kind IN('created','edited','approved','cancelled')),reason TEXT NOT NULL,created_at TEXT NOT NULL,snapshot TEXT NOT NULL,PRIMARY KEY(tenant_id,id),UNIQUE(tenant_id,order_id,version),FOREIGN KEY(tenant_id,order_id) REFERENCES purchase_orders(tenant_id,id))")
    featureSchema("CREATE TABLE IF NOT EXISTS purchase_order_deliveries(tenant_id TEXT NOT NULL,id TEXT NOT NULL,order_id TEXT NOT NULL,order_version INTEGER NOT NULL CHECK(order_version>0),created_at TEXT NOT NULL,fingerprint TEXT NOT NULL,PRIMARY KEY(tenant_id,id),FOREIGN KEY(tenant_id,order_id) REFERENCES purchase_orders(tenant_id,id))")
    featureSchema("CREATE TABLE IF NOT EXISTS purchase_order_delivery_lines(tenant_id TEXT NOT NULL,delivery_id TEXT NOT NULL,order_id TEXT NOT NULL,line_id TEXT NOT NULL,receipt_id TEXT NOT NULL,quantity INTEGER NOT NULL CHECK(quantity>0),paid_cash_minor INTEGER NOT NULL CHECK(paid_cash_minor>=0),expected_stock_scaled INTEGER NOT NULL CHECK(expected_stock_scaled>=0),PRIMARY KEY(tenant_id,delivery_id,line_id),UNIQUE(tenant_id,receipt_id),FOREIGN KEY(tenant_id,delivery_id) REFERENCES purchase_order_deliveries(tenant_id,id),FOREIGN KEY(tenant_id,order_id,line_id) REFERENCES purchase_order_lines(tenant_id,order_id,id),FOREIGN KEY(tenant_id,receipt_id) REFERENCES stock_receipts(tenant_id,id))")
    listOf("purchase_order_events","purchase_order_deliveries","purchase_order_delivery_lines").forEach {table ->
        featureSchema("CREATE TRIGGER IF NOT EXISTS ${table}_no_update BEFORE UPDATE ON $table BEGIN SELECT RAISE(ABORT,'Purchase history cannot be edited'); END")
        featureSchema("CREATE TRIGGER IF NOT EXISTS ${table}_no_delete BEFORE DELETE ON $table BEGIN SELECT RAISE(ABORT,'Purchase history cannot be removed'); END")
    }
    listOf("UPDATE","DELETE").forEach {action ->featureSchema("CREATE TRIGGER IF NOT EXISTS purchase_order_lines_${action.lowercase()}_approved BEFORE $action ON purchase_order_lines WHEN (SELECT status FROM purchase_orders WHERE tenant_id=OLD.tenant_id AND id=OLD.order_id)<>'DRAFT' BEGIN SELECT RAISE(ABORT,'Approved purchase lines cannot be changed'); END")}
    featureSchema("CREATE TRIGGER IF NOT EXISTS purchase_order_lines_insert_approved BEFORE INSERT ON purchase_order_lines WHEN (SELECT status FROM purchase_orders WHERE tenant_id=NEW.tenant_id AND id=NEW.order_id)<>'DRAFT' AND EXISTS(SELECT 1 FROM purchase_order_events WHERE tenant_id=NEW.tenant_id AND order_id=NEW.order_id) BEGIN SELECT RAISE(ABORT,'Approved purchase lines cannot be added'); END")
}

fun PosRepository.purchaseOrders(tenantId:String):List<PurchaseOrder> = featureRead(tenantId,StaffPermission.MANAGE_SUPPLIERS) {ordersUnchecked(tenantId)}
fun PosRepository.purchaseOrderEvents(tenantId:String,orderId:String):List<PurchaseOrderEvent> = featureRead(tenantId,StaffPermission.MANAGE_SUPPLIERS) {orderEventsUnchecked(tenantId,orderId)}
fun PosRepository.purchaseOrderDeliveries(tenantId:String,orderId:String):List<PurchaseOrderDelivery> = featureRead(tenantId,StaffPermission.MANAGE_SUPPLIERS) {deliveriesUnchecked(tenantId,orderId)}
fun PosRepository.purchaseOrderRemaining(tenantId:String,orderId:String):Map<String,Int> = featureRead(tenantId,StaffPermission.MANAGE_SUPPLIERS) {remainingUnchecked(requireOrder(tenantId,orderId))}

fun PosRepository.createPurchaseOrder(tenantId:String,supplierId:String,lines:List<PurchaseOrderLineInput>,notes:String="",operationId:String=UUID.randomUUID().toString()):PurchaseOrder = featureWrite(tenantId,StaffPermission.MANAGE_SUPPLIERS) {
    validOrderId(operationId)
    val existing=eventById(tenantId,operationId)
    if(existing!=null) {
        require(existing.kind=="created" && existing.orderId==operationId && existing.snapshot.supplierId==supplierId && existing.snapshot.lines.map {it.input()}==lines && existing.snapshot.notes==notes.trim()) {"This purchase-order request was already saved with different details."}
        return@featureWrite requireOrder(tenantId,operationId)
    }
    val supplier=activeOrderSupplier(tenantId,supplierId)
    val order=PurchaseOrder(operationId,tenantId,supplierId,supplier.details.name,supplier.version,PurchaseOrderStatus.DRAFT,1,Instant.now().toString(),null,null,null,notes.trim(),snapshotOrderLines(tenantId,lines))
    featureExecute("INSERT INTO purchase_orders VALUES(?,?,?,?,?,?,?,?,?,?,?,?)",order.id,tenantId,supplierId,order.supplierName,order.supplierVersion,order.status.name,1,order.createdAt,null,null,null,order.notes)
    insertOrderLines(order)
    recordOrderEvent(order,operationId,"created","Draft created",order.createdAt)
    order
}

fun PosRepository.editPurchaseOrder(tenantId:String,orderId:String,supplierId:String,lines:List<PurchaseOrderLineInput>,notes:String,reason:String,expectedVersion:Long,operationId:String=UUID.randomUUID().toString()):PurchaseOrder = featureWrite(tenantId,StaffPermission.MANAGE_SUPPLIERS) {
    validOrderId(operationId);require(validPurchaseOrderReason(reason)) {"Enter a reason from 3 to 250 printable characters."}
    eventById(tenantId,operationId)?.let {saved ->
        require(saved.kind=="edited" && saved.orderId==orderId && saved.version==Math.addExact(expectedVersion,1) && saved.snapshot.supplierId==supplierId && saved.snapshot.lines.map {it.input()}==lines && saved.snapshot.notes==notes.trim() && saved.reason==reason) {"This draft edit was already saved with different details."}
        return@featureWrite requireOrder(tenantId,orderId)
    }
    val old=requireOrder(tenantId,orderId)
    require(old.status==PurchaseOrderStatus.DRAFT && old.version==expectedVersion) {"Only the current draft can be edited. Reopen the order."}
    val supplier=activeOrderSupplier(tenantId,supplierId)
    val updated=old.copy(supplierId=supplierId,supplierName=supplier.details.name,supplierVersion=supplier.version,version=Math.addExact(old.version,1),notes=notes.trim(),lines=snapshotOrderLines(tenantId,lines))
    val now=orderEventTime(tenantId,orderId)
    featureExecute("DELETE FROM purchase_order_lines WHERE tenant_id=? AND order_id=?",tenantId,orderId)
    insertOrderLines(updated)
    featureExecute("UPDATE purchase_orders SET supplier_id=?,supplier_name=?,supplier_version=?,version=?,notes=? WHERE tenant_id=? AND id=?",supplierId,updated.supplierName,updated.supplierVersion,updated.version,updated.notes,tenantId,orderId)
    recordOrderEvent(updated,operationId,"edited",reason,now)
    updated
}

fun PosRepository.approvePurchaseOrder(tenantId:String,orderId:String,expectedVersion:Long,operationId:String=UUID.randomUUID().toString()):PurchaseOrder = featureWrite(tenantId,StaffPermission.MANAGE_SUPPLIERS) {
    validOrderId(operationId)
    eventById(tenantId,operationId)?.let {saved ->require(saved.kind=="approved" && saved.orderId==orderId && saved.version==Math.addExact(expectedVersion,1)) {"This approval request was already used."};return@featureWrite requireOrder(tenantId,orderId)}
    val old=requireOrder(tenantId,orderId)
    require(old.status==PurchaseOrderStatus.DRAFT && old.version==expectedVersion) {"Only the current draft can be approved."}
    val supplier=activeOrderSupplier(tenantId,old.supplierId)
    require(supplier.version==old.supplierVersion && supplier.details.name==old.supplierName) {"Supplier details changed. Edit and review the draft before approval."}
    validateCurrentOrderLines(tenantId,old.lines)
    val now=orderEventTime(tenantId,orderId)
    val approved=old.copy(status=PurchaseOrderStatus.APPROVED,version=Math.addExact(old.version,1),approvedAt=now)
    featureExecute("UPDATE purchase_orders SET status='APPROVED',version=?,approved_at=? WHERE tenant_id=? AND id=?",approved.version,now,tenantId,orderId)
    recordOrderEvent(approved,operationId,"approved","Order approved; no stock or money recorded",now)
    approved
}

fun PosRepository.cancelPurchaseOrder(tenantId:String,orderId:String,reason:String,expectedVersion:Long,operationId:String=UUID.randomUUID().toString()):PurchaseOrder = featureWrite(tenantId,StaffPermission.MANAGE_SUPPLIERS) {
    validOrderId(operationId);require(validPurchaseOrderReason(reason)) {"Enter why the remaining order is cancelled."}
    eventById(tenantId,operationId)?.let {saved ->require(saved.kind=="cancelled" && saved.orderId==orderId && saved.version==Math.addExact(expectedVersion,1) && saved.reason==reason) {"This cancellation request was already used differently."};return@featureWrite requireOrder(tenantId,orderId)}
    val old=requireOrder(tenantId,orderId)
    require(old.status!=PurchaseOrderStatus.CANCELLED && old.version==expectedVersion) {"This order changed or was already cancelled. Reopen it."}
    val now=orderEventTime(tenantId,orderId)
    val cancelled=old.copy(status=PurchaseOrderStatus.CANCELLED,version=Math.addExact(old.version,1),cancelledAt=now,cancellationReason=reason)
    featureExecute("UPDATE purchase_orders SET status='CANCELLED',version=?,cancelled_at=?,cancellation_reason=? WHERE tenant_id=? AND id=?",cancelled.version,now,reason,tenantId,orderId)
    recordOrderEvent(cancelled,operationId,"cancelled",reason,now)
    cancelled
}

/** All selected order lines become real receipts together, or none of them do. */
fun PosRepository.receivePurchaseOrder(tenantId:String,orderId:String,lines:List<PurchaseDeliveryInput>,expectedVersion:Long,operationId:String=UUID.randomUUID().toString()):PurchaseOrderDelivery = featureWrite(tenantId,StaffPermission.MANAGE_SUPPLIERS) {
    featureRead(tenantId,StaffPermission.MANAGE_STOCK) {Unit}
    validOrderId(operationId)
    require(lines.size in 1..200 && lines.map {it.lineId}.distinct().size==lines.size) {"Enter at least one distinct received order line."}
    val fingerprint=deliveryFingerprint(orderId,expectedVersion,lines)
    val replay=featureQuery("SELECT * FROM purchase_order_deliveries WHERE tenant_id=? AND id=?",tenantId,operationId) {it.getString("order_id") to it.getString("fingerprint")}.singleOrNull()
    if(replay!=null) {
        require(replay.first==orderId && replay.second==fingerprint) {"This delivery request was already saved with different details."}
        return@featureWrite deliveriesUnchecked(tenantId,orderId).single {it.id==operationId}
    }
    val order=requireOrder(tenantId,orderId)
    require(order.status==PurchaseOrderStatus.APPROVED && order.version==expectedVersion) {"Only the current approved order can receive goods."}
    val supplier=activeOrderSupplier(tenantId,order.supplierId)
    val byId=order.lines.associateBy {it.id}
    val remaining=remainingUnchecked(order)
    val chosen=lines.sortedBy {it.lineId}.map {input -> requireNotNull(byId[input.lineId]) {"A received line does not belong to this order."} to input}
    validateCurrentOrderLines(tenantId,chosen.map {it.first})
    val products=products(tenantId,true).associateBy {it.id}
    chosen.forEach {(line,input) ->
        require(input.quantity<=remaining.getValue(line.id)) {"Received quantity exceeds what remains on ${line.productName}."}
        require(products.getValue(line.productId).exactStockScaled==input.expectedStockScaled) {"Stock changed after the receiving preview. Refresh and review this delivery."}
        require(input.paidCashMinor<=Quantity(input.quantity.toLong(),line.quantityPrecision).lineTotalMinor(line.unitCostMinor)) {"Cash paid exceeds this delivery line's buying cost."}
    }
    // Sum all incoming base units first, including several units of the same product.
    chosen.groupBy {it.first.productId}.forEach {(productId,group) ->
        val incoming=group.fold(0L) {sum,pair -> Math.addExact(sum,Quantity(pair.second.quantity.toLong(),pair.first.quantityPrecision).rescale(pair.first.basePrecision).timesWhole(pair.first.baseUnitsPerUnit.toLong()).scaledUnits)}
        require(Math.addExact(products.getValue(productId).exactStockScaled,incoming)<=9_007_199_254_740_991L) {"Delivered stock exceeds the supported exact range."}
    }
    val now=orderEventTime(tenantId,orderId)
    require(!Instant.parse(now).isBefore(Instant.parse(requireNotNull(order.approvedAt))))
    featureExecute("INSERT INTO purchase_order_deliveries VALUES(?,?,?,?,?,?)",tenantId,operationId,orderId,expectedVersion,now,fingerprint)
    val savedLines=chosen.map {(line,input) ->
        val receiptId=deliveryReceiptId(operationId,line.id)
        val receipt=receiveStockForSupplier(tenantId,line.productId,input.quantity,line.unitCostMinor,input.paidCashMinor,order.supplierId,receiptId,line.purchaseUnitId,line.baseUnitsPerUnit,supplier.version)
        require(!Instant.parse(receipt.createdAt).isBefore(Instant.parse(now))) {"The computer clock moved backwards. No delivery was saved."}
        featureExecute("INSERT INTO purchase_order_delivery_lines VALUES(?,?,?,?,?,?,?,?)",tenantId,operationId,orderId,line.id,receipt.id,input.quantity,input.paidCashMinor,input.expectedStockScaled)
        PurchaseOrderDeliveryLine(line.id,receipt.id,input.quantity,input.paidCashMinor,input.expectedStockScaled)
    }
    featureEvent(tenantId,"purchase_order.received",operationId)
    PurchaseOrderDelivery(operationId,tenantId,orderId,expectedVersion,now,savedLines)
}

internal fun PosRepository.validatePurchaseOrderSnapshot(tenantId:String) {
    val orders=ordersUnchecked(tenantId)
    val products=products(tenantId,true).associateBy {it.id}
    val suppliers=featureQuery("SELECT id FROM supplier_profiles WHERE tenant_id=?",tenantId) {it.getString(1)}.toSet()
    val receipts=stockReceipts(tenantId).associateBy {it.id}
    val allDeliveries=orders.flatMap {deliveriesUnchecked(tenantId,it.id)}
    val deliveredReceiptIds=allDeliveries.flatMap {it.lines}.map {it.receiptId}.toSet()
    require(receipts.keys.filter {it.matches(Regex("po-receipt:[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}"))}.all {it in deliveredReceiptIds}) {"Backup order receipt is missing its delivery history."}
    val supplierLinks=featureQuery("SELECT receipt_id,supplier_id FROM supplier_receipt_links WHERE tenant_id=?",tenantId) {it.getString(1) to it.getString(2)}.toMap()
    orders.forEach {order ->
        require(order.supplierId in suppliers) {"Backup purchase order has no supplier."}
        val created=Instant.parse(order.createdAt)
        order.approvedAt?.let {require(!Instant.parse(it).isBefore(created))}
        order.cancelledAt?.let {require(!Instant.parse(it).isBefore(Instant.parse(order.approvedAt ?: order.createdAt)))}
        order.lines.forEach {line ->
            val product=requireNotNull(products[line.productId]) {"Backup order item is missing."}
            require(product.unit==line.baseUnit && product.quantityPrecision==line.basePrecision) {"Backup order uses a different base unit or precision."}
            line.purchaseUnitId?.let {id ->
                val unit=requireNotNull(purchaseUnits(tenantId,line.productId,true).find {it.id==id}) {"Backup order buying unit is missing."}
                require(unit.name==line.buyingUnit && unit.baseUnit==line.baseUnit && unit.baseUnitsPerUnit==line.baseUnitsPerUnit) {"Backup order conversion differs from its immutable buying unit."}
            }
        }
        val events=orderEventsUnchecked(tenantId,order.id)
        require(events.isNotEmpty() && events.first().kind=="created" && events.first().snapshot.status==PurchaseOrderStatus.DRAFT && events.first().createdAt==order.createdAt) {"Backup order creation history is missing."}
        events.forEachIndexed {index,event ->
            require(event.version==index.toLong()+1 && event.snapshot.createdAt==order.createdAt && event.snapshot.supplierId in suppliers && event.snapshot.lines.all {it.productId in products} && !Instant.parse(event.createdAt).isBefore(created)) {"Backup order revision history is incomplete."}
            if(index>0) {
                val previous=events[index-1]
                require(!Instant.parse(event.createdAt).isBefore(Instant.parse(previous.createdAt))) {"Backup order history dates are inconsistent."}
                require(when(previous.snapshot.status) {PurchaseOrderStatus.DRAFT -> event.kind in setOf("edited","approved","cancelled");PurchaseOrderStatus.APPROVED -> event.kind=="cancelled";PurchaseOrderStatus.CANCELLED -> false}) {"Backup changes an approved or cancelled order illegally."}
                require(event.kind!="created")
                if(event.kind=="approved" || event.kind=="cancelled") require(event.snapshot.lines==previous.snapshot.lines && event.snapshot.supplierId==previous.snapshot.supplierId && event.snapshot.supplierName==previous.snapshot.supplierName && event.snapshot.supplierVersion==previous.snapshot.supplierVersion && event.snapshot.notes==previous.snapshot.notes) {"Backup changes lines or supplier during an order state transition."}
            }
            require(when(event.kind) {"created","edited" -> event.snapshot.status==PurchaseOrderStatus.DRAFT;"approved" -> event.snapshot.status==PurchaseOrderStatus.APPROVED && event.snapshot.approvedAt==event.createdAt;else -> event.snapshot.status==PurchaseOrderStatus.CANCELLED && event.snapshot.cancelledAt==event.createdAt && event.snapshot.cancellationReason==event.reason}) {"Backup order event does not match its state."}
        }
        require(events.last().snapshot==order) {"Backup order does not match its latest history."}
        val deliveries=deliveriesUnchecked(tenantId,order.id)
        deliveries.forEach {delivery ->
            val approval=events.singleOrNull {it.version==delivery.orderVersion && it.kind=="approved"}
            require(approval!=null && !Instant.parse(delivery.createdAt).isBefore(Instant.parse(approval.createdAt)) && (order.cancelledAt==null || !Instant.parse(delivery.createdAt).isAfter(Instant.parse(order.cancelledAt)))) {"Backup delivery was not recorded against an approved order."}
            val savedFingerprint=featureQuery("SELECT fingerprint FROM purchase_order_deliveries WHERE tenant_id=? AND id=?",tenantId,delivery.id) {it.getString(1)}.single()
            require(savedFingerprint==deliveryFingerprint(order.id,delivery.orderVersion,delivery.lines.map {it.input()})) {"Backup delivery request does not match its saved lines."}
            delivery.lines.forEach {received ->
                val line=requireNotNull(order.lines.find {it.id==received.lineId}) {"Backup delivery has no order line."}
                val receipt=requireNotNull(receipts[received.receiptId]) {"Backup delivery stock receipt is missing."}
                require(receipt.id==deliveryReceiptId(delivery.id,line.id) && supplierLinks[receipt.id]==order.supplierId && receipt.productId==line.productId && receipt.productName==line.productName && receipt.quantity==received.quantity && receipt.quantityPrecision==line.quantityPrecision && receipt.basePrecision==line.basePrecision && receipt.unit==line.buyingUnit && receipt.baseUnit==line.baseUnit && receipt.baseUnitsPerUnit==line.baseUnitsPerUnit && receipt.purchaseUnitId==line.purchaseUnitId && receipt.unitCostMinor==line.unitCostMinor && receipt.paidCashMinor==received.paidCashMinor && !Instant.parse(receipt.createdAt).isBefore(Instant.parse(delivery.createdAt))) {"Backup delivery does not match its purchase receipt or supplier account."}
            }
        }
        remainingUnchecked(order)
    }
    featureQuery("SELECT kind,entity_id FROM outbox WHERE tenant_id=? AND kind LIKE 'purchase_order.%'",tenantId) {it.getString(1) to it.getString(2)}.forEach {(kind,id) ->require(if(kind=="purchase_order.received") allDeliveries.any {it.id==id} else orders.any {it.id==id}) {"Backup purchase-order activity refers to missing records."}}
}

private fun PosRepository.snapshotOrderLines(tenantId:String,inputs:List<PurchaseOrderLineInput>):List<PurchaseOrderLine> {
    require(inputs.size in 1..200 && inputs.map {it.id}.distinct().size==inputs.size && inputs.map {it.productId to it.purchaseUnitId}.distinct().size==inputs.size) {"Choose 1–200 different item/buying-unit combinations."}
    val byId=products(tenantId,true).associateBy {it.id}
    return inputs.map {input ->
        validOrderId(input.id)
        val item=requireNotNull(byId[input.productId]) {"An ordered item does not belong to this shop."}
        require(!item.archived) {"Restore archived items before ordering them."}
        val unit=input.purchaseUnitId?.let {id -> requireNotNull(purchaseUnits(tenantId,item.id,true).find {it.id==id && !it.archived}) {"Choose an active buying unit belonging to this item."}}
        PurchaseOrderLine(input.id,item.id,item.name,item.unit,unit?.name ?: item.unit,input.quantity,if(unit==null)item.quantityPrecision else 0,item.quantityPrecision,unit?.baseUnitsPerUnit ?: 1,input.unitCostMinor,unit?.id)
    }
}
private fun PosRepository.validateCurrentOrderLines(tenantId:String,lines:List<PurchaseOrderLine>) {
    val current=snapshotOrderLines(tenantId,lines.map {it.input()})
    require(current==lines) {"An item or buying unit changed. Review and update the draft, or cancel the unreceived remainder and create a new order."}
}
private fun PosRepository.activeOrderSupplier(tenantId:String,id:String)=supplierProfiles(tenantId,true).singleOrNull {it.id==id && !it.archived} ?: error("Choose an active supplier account belonging to this shop.")
private fun PosRepository.insertOrderLines(order:PurchaseOrder) {order.lines.forEachIndexed {position,line ->featureExecute("INSERT INTO purchase_order_lines VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?)",order.tenantId,order.id,line.id,position,line.productId,line.productName,line.baseUnit,line.buyingUnit,line.quantity,line.quantityPrecision,line.basePrecision,line.baseUnitsPerUnit,line.unitCostMinor,line.purchaseUnitId)}}
private fun PosRepository.recordOrderEvent(order:PurchaseOrder,id:String,kind:String,reason:String,now:String) {
    val event=PurchaseOrderEvent(id,order.tenantId,order.id,order.version,kind,reason,now,order)
    featureExecute("INSERT INTO purchase_order_events VALUES(?,?,?,?,?,?,?,?)",order.tenantId,id,order.id,order.version,kind,reason,now,encodeOrderSnapshot(event.snapshot))
    featureEvent(order.tenantId,"purchase_order.$kind",order.id)
}
private fun PosRepository.orderEventTime(tenantId:String,orderId:String):String {
    val now=Instant.now()
    val latest=featureQuery("SELECT created_at FROM purchase_order_events WHERE tenant_id=? AND order_id=? UNION ALL SELECT created_at FROM purchase_order_deliveries WHERE tenant_id=? AND order_id=? UNION ALL SELECT r.created_at FROM stock_receipts r JOIN purchase_order_delivery_lines l ON l.tenant_id=r.tenant_id AND l.receipt_id=r.id WHERE l.tenant_id=? AND l.order_id=?",tenantId,orderId,tenantId,orderId,tenantId,orderId) {Instant.parse(it.getString(1))}.maxOrNull()
    require(latest==null || !now.isBefore(latest)) {"The computer clock moved backwards. Correct it before changing this order."}
    return now.toString()
}
private fun PosRepository.ordersUnchecked(tenantId:String)=featureQuery("SELECT * FROM purchase_orders WHERE tenant_id=? ORDER BY created_at DESC,id",tenantId) {row ->orderFromRow(row,orderLinesUnchecked(tenantId,row.getString("id")))}
private fun PosRepository.requireOrder(tenantId:String,id:String)=featureQuery("SELECT * FROM purchase_orders WHERE tenant_id=? AND id=?",tenantId,id) {row ->orderFromRow(row,orderLinesUnchecked(tenantId,id))}.singleOrNull() ?: error("Purchase order does not belong to this shop.")
private fun PosRepository.orderLinesUnchecked(tenantId:String,id:String)=featureQuery("SELECT * FROM purchase_order_lines WHERE tenant_id=? AND order_id=? ORDER BY position",tenantId,id,map=::orderLineFromRow)
private fun orderFromRow(r:ResultSet,lines:List<PurchaseOrderLine>)=PurchaseOrder(r.getString("id"),r.getString("tenant_id"),r.getString("supplier_id"),r.getString("supplier_name"),r.getLong("supplier_version"),PurchaseOrderStatus.valueOf(r.getString("status")),r.getLong("version"),r.getString("created_at"),r.getString("approved_at"),r.getString("cancelled_at"),r.getString("cancellation_reason"),r.getString("notes"),lines)
private fun orderLineFromRow(r:ResultSet)=PurchaseOrderLine(r.getString("id"),r.getString("product_id"),r.getString("product_name"),r.getString("base_unit"),r.getString("buying_unit"),exactInt(r.getLong("quantity")),exactInt(r.getLong("quantity_precision")),exactInt(r.getLong("base_precision")),exactInt(r.getLong("base_units_per_unit")),r.getLong("unit_cost_minor"),r.getString("purchase_unit_id"))
private fun PosRepository.orderEventsUnchecked(tenantId:String,orderId:String)=featureQuery("SELECT * FROM purchase_order_events WHERE tenant_id=? AND order_id=? ORDER BY version",tenantId,orderId,map=::orderEventFromRow)
private fun PosRepository.eventById(tenantId:String,id:String)=featureQuery("SELECT * FROM purchase_order_events WHERE tenant_id=? AND id=?",tenantId,id,map=::orderEventFromRow).singleOrNull()
private fun orderEventFromRow(r:ResultSet)=PurchaseOrderEvent(r.getString("id"),r.getString("tenant_id"),r.getString("order_id"),r.getLong("version"),r.getString("kind"),r.getString("reason"),r.getString("created_at"),decodeOrderSnapshot(r.getString("snapshot"),r.getString("tenant_id")))
private fun PosRepository.deliveriesUnchecked(tenantId:String,orderId:String)=featureQuery("SELECT * FROM purchase_order_deliveries WHERE tenant_id=? AND order_id=? ORDER BY created_at,id",tenantId,orderId) {r ->
    PurchaseOrderDelivery(r.getString("id"),tenantId,orderId,r.getLong("order_version"),r.getString("created_at"),featureQuery("SELECT * FROM purchase_order_delivery_lines WHERE tenant_id=? AND delivery_id=? ORDER BY line_id",tenantId,r.getString("id")) {line ->require(line.getString("order_id")==orderId) {"Delivery line belongs to a different purchase order."};PurchaseOrderDeliveryLine(line.getString("line_id"),line.getString("receipt_id"),exactInt(line.getLong("quantity")),line.getLong("paid_cash_minor"),line.getLong("expected_stock_scaled"))})
}
private fun PosRepository.remainingUnchecked(order:PurchaseOrder):Map<String,Int> {
    val received=deliveriesUnchecked(order.tenantId,order.id).flatMap {it.lines}.groupBy {it.lineId}.mapValues {(_,lines) ->lines.fold(0L) {sum,line ->Math.addExact(sum,line.quantity.toLong())}}
    require(received.keys.all {key ->order.lines.any {it.id==key}}) {"Delivery refers to an unknown order line."}
    return order.lines.associate {line ->val rest=Math.subtractExact(line.quantity.toLong(),received[line.id] ?: 0L);require(rest>=0) {"Delivered quantity exceeds the order."};line.id to exactInt(rest)}
}
private fun deliveryReceiptId(delivery:String,line:String)="po-receipt:"+UUID.nameUUIDFromBytes((delivery+"\u0000"+line).toByteArray(Charsets.UTF_8))
private fun deliveryFingerprint(order:String,version:Long,lines:List<PurchaseDeliveryInput>):String {
    val b=ByteArrayOutputStream();DataOutputStream(b).use {out ->out.writeUTF(order);out.writeLong(version);out.writeInt(lines.size);lines.sortedBy {it.lineId}.forEach {out.writeUTF(it.lineId);out.writeInt(it.quantity);out.writeLong(it.paidCashMinor);out.writeLong(it.expectedStockScaled)}}
    return Base64.getEncoder().encodeToString(b.toByteArray())
}
private fun exactInt(value:Long):Int {require(value in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong()) {"Purchase integer exceeds supported bounds."};return value.toInt()}
private fun validOrderId(value:String) {require(value.isNotBlank() && value.length<=200 && value.none {it.isISOControl()}) {"Use a valid purchase operation reference."}}
private fun encodeOrderSnapshot(order:PurchaseOrder):String {
    val b=ByteArrayOutputStream();DataOutputStream(b).use {out ->
        out.writeInt(1);listOf(order.id,order.supplierId,order.supplierName).forEach(out::writeUTF);out.writeLong(order.supplierVersion);out.writeUTF(order.status.name);out.writeLong(order.version);out.writeUTF(order.createdAt)
        listOf(order.approvedAt,order.cancelledAt,order.cancellationReason).forEach {out.writeBoolean(it!=null);if(it!=null)out.writeUTF(it)}
        out.writeUTF(order.notes);out.writeInt(order.lines.size)
        order.lines.forEach {line ->listOf(line.id,line.productId,line.productName,line.baseUnit,line.buyingUnit).forEach(out::writeUTF);out.writeInt(line.quantity);out.writeInt(line.quantityPrecision);out.writeInt(line.basePrecision);out.writeInt(line.baseUnitsPerUnit);out.writeLong(line.unitCostMinor);out.writeBoolean(line.purchaseUnitId!=null);line.purchaseUnitId?.let(out::writeUTF)}
    };return Base64.getEncoder().encodeToString(b.toByteArray())
}
private fun decodeOrderSnapshot(encoded:String,tenant:String):PurchaseOrder {
    require(encoded.length<=1_000_000) {"Purchase history is too large."}
    return DataInputStream(ByteArrayInputStream(Base64.getDecoder().decode(encoded))).use {input ->
        require(input.readInt()==1) {"Purchase snapshot format is not supported."}
        val id=input.readUTF();val supplier=input.readUTF();val name=input.readUTF();val supplierVersion=input.readLong();val status=PurchaseOrderStatus.valueOf(input.readUTF());val version=input.readLong();val created=input.readUTF()
        val nullable=List(3) {if(input.readBoolean())input.readUTF() else null};val notes=input.readUTF();val size=input.readInt();require(size in 1..200)
        val lines=List(size) {val fields=List(5) {input.readUTF()};val quantity=input.readInt();val precision=input.readInt();val basePrecision=input.readInt();val factor=input.readInt();val cost=input.readLong();val unit=if(input.readBoolean())input.readUTF() else null;PurchaseOrderLine(fields[0],fields[1],fields[2],fields[3],fields[4],quantity,precision,basePrecision,factor,cost,unit)}
        require(input.read()==-1) {"Purchase history has unsupported data."}
        PurchaseOrder(id,tenant,supplier,name,supplierVersion,status,version,created,nullable[0],nullable[1],nullable[2],notes,lines)
    }
}
