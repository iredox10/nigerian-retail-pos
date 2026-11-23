package pos.data

import pos.domain.*
import java.io.*
import java.security.MessageDigest
import java.sql.ResultSet
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.Base64
import java.util.UUID

internal val quotationSnapshotTables=listOf("quotations","quotation_events")
internal fun PosRepository.initializeQuotations() {
    featureSchema("CREATE TABLE IF NOT EXISTS quotations(id TEXT NOT NULL,tenant_id TEXT NOT NULL REFERENCES tenants(id),status TEXT NOT NULL CHECK(status IN('ISSUED','ACCEPTED','CANCELLED','CONVERTED')),version INTEGER NOT NULL CHECK(version>0),snapshot TEXT NOT NULL,sale_id TEXT,PRIMARY KEY(tenant_id,id),UNIQUE(tenant_id,sale_id),FOREIGN KEY(tenant_id,sale_id) REFERENCES sales(tenant_id,id))")
    featureSchema("CREATE TABLE IF NOT EXISTS quotation_events(tenant_id TEXT NOT NULL,id TEXT NOT NULL,quotation_id TEXT NOT NULL,version INTEGER NOT NULL CHECK(version>0),kind TEXT NOT NULL CHECK(kind IN('issued','revised','accepted','cancelled','converted')),reason TEXT NOT NULL,created_at TEXT NOT NULL,snapshot TEXT NOT NULL,request_fingerprint TEXT,PRIMARY KEY(tenant_id,id),UNIQUE(tenant_id,quotation_id,version),FOREIGN KEY(tenant_id,quotation_id) REFERENCES quotations(tenant_id,id))")
    listOf("UPDATE","DELETE").forEach {action ->
        featureSchema("CREATE TRIGGER IF NOT EXISTS quotation_events_no_${action.lowercase()} BEFORE $action ON quotation_events BEGIN SELECT RAISE(ABORT,'Quotation history cannot be changed'); END")
        featureSchema("CREATE TRIGGER IF NOT EXISTS quotations_converted_no_${action.lowercase()} BEFORE $action ON quotations WHEN OLD.status='CONVERTED' BEGIN SELECT RAISE(ABORT,'Converted quotation cannot be changed'); END")
    }
}

fun quotationExpired(expiresOn:String,now:Instant=Instant.now()):Boolean =
    !now.isBefore(LocalDate.parse(expiresOn).plusDays(1).atStartOfDay(ZoneId.of("Africa/Lagos")).toInstant())

fun PosRepository.quotations(tenantId:String):List<Quotation> = featureRead(tenantId,StaffPermission.VIEW_SALES) {quoteRows(tenantId)}
fun PosRepository.quotationEvents(tenantId:String,quotationId:String):List<QuotationEvent> = featureRead(tenantId,StaffPermission.VIEW_SALES) {quoteEvents(tenantId,quotationId)}

fun PosRepository.issueQuotation(tenantId:String,lines:List<QuotationLineInput>,expiresOn:String,customer:QuotationCustomer?=null,notes:String="",operationId:String=UUID.randomUUID().toString()):Quotation = featureWrite(tenantId,StaffPermission.DISCOUNT) {
    validQuoteId(operationId)
    quoteEvent(tenantId,operationId)?.let {event ->
        require(event.kind=="issued" && event.quotationId==operationId && event.snapshot.lines.map {it.input()}==lines && event.snapshot.expiresOn==expiresOn && event.snapshot.customer==customer && event.snapshot.notes==notes.trim()) {"This quotation request was already used with different terms."}
        return@featureWrite requireQuote(tenantId,operationId)
    }
    val now=Instant.now().toString();validateExpiry(expiresOn,now);validateQuoteCustomer(tenantId,customer)
    val quote=Quotation(operationId,tenantId,QuotationStatus.ISSUED,1,now,now,expiresOn,customer,notes.trim(),snapshotQuoteLines(tenantId,lines))
    featureExecute("INSERT INTO quotations VALUES(?,?,?,?,?,NULL)",quote.id,tenantId,quote.status.name,quote.version,encodeQuotation(quote))
    recordQuoteEvent(quote,operationId,"issued","Quotation issued; no sale or payment recorded")
    quote
}

fun PosRepository.reviseQuotation(tenantId:String,quotationId:String,lines:List<QuotationLineInput>,expiresOn:String,customer:QuotationCustomer?,notes:String,reason:String,expectedVersion:Long,operationId:String=UUID.randomUUID().toString()):Quotation = featureWrite(tenantId,StaffPermission.DISCOUNT) {
    validQuoteId(operationId)
    quoteEvent(tenantId,operationId)?.let {event ->
        require(event.kind=="revised" && event.quotationId==quotationId && event.snapshot.version==Math.addExact(expectedVersion,1) && event.snapshot.lines.map {it.input()}==lines && event.snapshot.expiresOn==expiresOn && event.snapshot.customer==customer && event.snapshot.notes==notes.trim() && event.reason==reason) {"This revision request was already used with different terms."}
        return@featureWrite requireQuote(tenantId,quotationId)
    }
    val old=requireQuote(tenantId,quotationId)
    require(old.status==QuotationStatus.ISSUED && old.version==expectedVersion) {"Only the current issued quotation can be revised. Accepted terms stay frozen."}
    val now=quoteTime(old);validateExpiry(expiresOn,now);validateQuoteCustomer(tenantId,customer)
    val updated=old.copy(version=Math.addExact(old.version,1),updatedAt=now,expiresOn=expiresOn,customer=customer,notes=notes.trim(),lines=snapshotQuoteLines(tenantId,lines))
    saveQuote(updated);recordQuoteEvent(updated,operationId,"revised",reason);updated
}

fun PosRepository.acceptQuotation(tenantId:String,quotationId:String,expectedVersion:Long,operationId:String=UUID.randomUUID().toString()):Quotation = featureWrite(tenantId,StaffPermission.DISCOUNT) {
    validQuoteId(operationId)
    quoteEvent(tenantId,operationId)?.let {event ->require(event.kind=="accepted" && event.quotationId==quotationId && event.snapshot.version==Math.addExact(expectedVersion,1)) {"This acceptance request was already used."};return@featureWrite requireQuote(tenantId,quotationId)}
    val old=requireQuote(tenantId,quotationId)
    require(old.status==QuotationStatus.ISSUED && old.version==expectedVersion) {"Only the current issued quotation can be accepted."}
    val now=quoteTime(old);require(!quotationExpired(old.expiresOn,Instant.parse(now))) {"This quotation has expired. Issue a reviewed revision before acceptance."}
    val accepted=old.copy(status=QuotationStatus.ACCEPTED,version=Math.addExact(old.version,1),updatedAt=now)
    saveQuote(accepted);recordQuoteEvent(accepted,operationId,"accepted","Customer accepted terms; no sale or payment recorded");accepted
}

fun PosRepository.cancelQuotation(tenantId:String,quotationId:String,reason:String,expectedVersion:Long,operationId:String=UUID.randomUUID().toString()):Quotation = featureWrite(tenantId,StaffPermission.DISCOUNT) {
    validQuoteId(operationId)
    quoteEvent(tenantId,operationId)?.let {event ->require(event.kind=="cancelled" && event.quotationId==quotationId && event.snapshot.version==Math.addExact(expectedVersion,1) && event.reason==reason) {"This cancellation request was already used."};return@featureWrite requireQuote(tenantId,quotationId)}
    val old=requireQuote(tenantId,quotationId)
    require(old.status in setOf(QuotationStatus.ISSUED,QuotationStatus.ACCEPTED) && old.version==expectedVersion) {"Only the current unconverted quotation can be cancelled."}
    val cancelled=old.copy(status=QuotationStatus.CANCELLED,version=Math.addExact(old.version,1),updatedAt=quoteTime(old))
    saveQuote(cancelled);recordQuoteEvent(cancelled,operationId,"cancelled",reason);cancelled
}

data class QuotationConversionReview(val quotation:Quotation,val lines:List<CartLine>,val token:String)
fun PosRepository.quotationConversionReview(tenantId:String,quotationId:String,expectedVersion:Long):QuotationConversionReview = featureRead(tenantId,StaffPermission.DISCOUNT) {
    conversionReview(requireQuote(tenantId,quotationId),expectedVersion)
}
fun PosRepository.convertQuotation(tenantId:String,quotationId:String,expectedVersion:Long,reviewToken:String,cashMinor:Long,operationId:String=UUID.randomUUID().toString()):Sale = featureWrite(tenantId,StaffPermission.DISCOUNT) {
    validQuoteId(operationId)
    quoteEvent(tenantId,operationId)?.let {event ->
        require(event.kind=="converted" && event.quotationId==quotationId && event.snapshot.version==Math.addExact(expectedVersion,1) && event.requestFingerprint==reviewToken && cashMinor==event.snapshot.totalMinor && event.snapshot.saleId==operationId) {"This conversion request was already used with different details."}
        return@featureWrite sales(tenantId).single {it.id==operationId}
    }
    require(featureQuery("SELECT id FROM sales WHERE tenant_id=? AND id=?",tenantId,operationId) {it.getString(1)}.isEmpty()) {"This operation ID already belongs to another sale. No quotation was converted."}
    val old=requireQuote(tenantId,quotationId)
    val review=conversionReview(old,expectedVersion)
    require(review.token==reviewToken) {"Goods or prices changed after review. Review the conversion again; nothing has been sold."}
    require(cashMinor==old.totalMinor) {"Quotation conversion currently supports full cash payment only. No short payment becomes debt."}
    val now=quoteTime(old)
    require(!quotationExpired(old.expiresOn,Instant.parse(now))) {"This quotation expired before the sale was recorded."}
    val sale=checkout(tenantId,review.lines,cashMinor,old.customer?.customerId,operationId,cashGivenMinor = cashMinor)
    val completed=quoteTime(old)
    require(!quotationExpired(old.expiresOn,Instant.parse(completed))) {"Quotation expired while conversion was being recorded. No sale was saved."}
    val converted=old.copy(status=QuotationStatus.CONVERTED,version=Math.addExact(old.version,1),updatedAt=completed,saleId=sale.id)
    saveQuote(converted);recordQuoteEvent(converted,operationId,"converted","Converted to fully cash-paid sale",reviewToken)
    sale
}

private fun PosRepository.conversionReview(quote:Quotation,expectedVersion:Long):QuotationConversionReview {
    require(quote.status==QuotationStatus.ACCEPTED && quote.version==expectedVersion) {"Accept the current quotation before reviewing a sale. A converted quote cannot be sold again."}
    require(!quotationExpired(quote.expiresOn)) {"This quotation has expired; conversion is unavailable."}
    val products=products(quote.tenantId,true).associateBy {it.id};val units=sellingUnits(quote.tenantId,includeArchived=true).associateBy {it.id}
    val lines=quote.lines.map {frozen ->
        val product=requireNotNull(products[frozen.productId]) {"Quoted goods are unavailable."}
        require(!product.archived && product.name==frozen.productName && product.unit==frozen.baseUnit && product.quantityPrecision==frozen.basePrecision) {"Quoted goods details changed. Conversion is unavailable; the quotation remains unchanged."}
        val unit=frozen.sellingUnitId?.let {requireNotNull(units[it]) {"Quoted selling unit is unavailable."}}
        require(unit==null || (!unit.archived && unit.productId==product.id && unit.name==frozen.sellingUnit && unit.baseUnit==frozen.baseUnit && unit.baseUnitsPerUnit==frozen.baseUnitsPerUnit)) {"Quoted pack details changed. Conversion is unavailable."}
        val normal=unit?.priceMinor ?: product.priceMinor
        require(frozen.agreedUnitPriceMinor<=normal) {"A quoted price exceeds the current normal price. Conversion is unavailable; no price was substituted."}
        val discount=normal-frozen.agreedUnitPriceMinor
        CartLine(product.copy(stockScaled=null),frozen.quantity,unit?.id,unit?.name ?: product.unit,normal,unit?.baseUnitsPerUnit ?: 1,discount,if(discount>0) quotationSaleSource(quote.id,quote.version) else null,frozen.quantityPrecision)
    }
    lines.groupBy {it.product.id}.forEach {(id,group) ->require(group.fold(0L) {sum,line ->Math.addExact(sum,line.baseQuantity)}<=products.getValue(id).exactStockScaled) {"Not enough stock for this quotation. No goods are reserved by a quotation."}}
    require(cartTotal(lines)==quote.totalMinor) {"Agreed quotation total cannot be preserved."}
    return QuotationConversionReview(quote,lines,conversionFingerprint(quote,lines))
}
internal fun quotationSaleSource(id:String,version:Long)="Quotation $id revision $version"
private fun validQuoteId(id:String) {require(id.isNotBlank() && id.length<=120 && id.none {it.isISOControl()}) {"Use a quotation operation ID of at most 120 printable characters."}}
private fun validateExpiry(value:String,now:String) {require(isValidCreditDueDate(value) && !LocalDate.parse(value).isBefore(Instant.parse(now).atZone(ZoneId.of("Africa/Lagos")).toLocalDate())) {"Choose today or a later quotation expiry date in WAT."}}
private fun quoteTime(old:Quotation):String=Instant.now().also {require(!it.isBefore(Instant.parse(old.updatedAt))) {"The computer clock moved backwards. Correct it before changing this quotation."}}.toString()
private fun PosRepository.validateQuoteCustomer(tenantId:String,customer:QuotationCustomer?) {customer?.customerId?.let {id ->require(featureQuery("SELECT id FROM customers WHERE tenant_id=? AND id=?",tenantId,id) {it.getString(1)}.size==1) {"Quoted customer belongs to another shop or is unavailable."}}}
internal fun PosRepository.snapshotQuoteLines(tenantId:String,inputs:List<QuotationLineInput>):List<QuotationLine> {
    require(inputs.size in 1..100 && inputs.map {it.key}.distinct().size==inputs.size) {"Choose 1–100 distinct items and units."}
    val products=products(tenantId,true).associateBy {it.id};val units=sellingUnits(tenantId,includeArchived=true).associateBy {it.id}
    return inputs.map {input ->
        val product=requireNotNull(products[input.productId]) {"Choose goods from this shop."};require(!product.archived) {"Restore archived goods before quoting them."}
        val unit=input.sellingUnitId?.let {requireNotNull(units[it]) {"Choose a selling unit from this shop."}}
        require(unit==null || (!unit.archived && unit.productId==product.id && unit.baseUnit==product.unit)) {"Choose an active selling unit for these goods."}
        QuotationLine(product.id,product.name,product.unit,product.quantityPrecision,unit?.id,unit?.name ?: product.unit,unit?.baseUnitsPerUnit ?: 1,if(unit==null) product.quantityPrecision else 0,input.quantity,input.agreedUnitPriceMinor)
    }
}
private fun PosRepository.saveQuote(quote:Quotation) {featureExecute("UPDATE quotations SET status=?,version=?,snapshot=?,sale_id=? WHERE tenant_id=? AND id=?",quote.status.name,quote.version,encodeQuotation(quote),quote.saleId,quote.tenantId,quote.id)}
private fun PosRepository.recordQuoteEvent(quote:Quotation,operationId:String,kind:String,reason:String,fingerprint:String?=null) {
    val event=QuotationEvent(operationId,quote.id,kind,reason,quote.updatedAt,quote,fingerprint)
    featureExecute("INSERT INTO quotation_events VALUES(?,?,?,?,?,?,?,?,?)",quote.tenantId,event.id,quote.id,quote.version,kind,reason,event.createdAt,encodeQuotation(quote),fingerprint)
    featureEvent(quote.tenantId,"quotation.$kind",operationId)
}
private fun PosRepository.quoteRows(tenantId:String)=featureQuery("SELECT * FROM quotations WHERE tenant_id=? ORDER BY id DESC",tenantId,map=::quoteRow)
private fun PosRepository.requireQuote(tenantId:String,id:String)=requireNotNull(featureQuery("SELECT * FROM quotations WHERE tenant_id=? AND id=?",tenantId,id,map=::quoteRow).singleOrNull()) {"Quotation belongs to another shop or is unavailable."}
private fun quoteRow(row:ResultSet)=decodeQuotation(row.getString("snapshot"),row.getString("tenant_id")).also {require(it.id==row.getString("id") && it.status.name==row.getString("status") && it.version==row.getLong("version") && it.saleId==row.getString("sale_id")) {"Quotation projection does not match its snapshot."}}
private fun PosRepository.quoteEvents(tenantId:String,id:String)=featureQuery("SELECT * FROM quotation_events WHERE tenant_id=? AND quotation_id=? ORDER BY version",tenantId,id,map=::quoteEventRow)
private fun PosRepository.quoteEvent(tenantId:String,id:String)=featureQuery("SELECT * FROM quotation_events WHERE tenant_id=? AND id=?",tenantId,id,map=::quoteEventRow).singleOrNull()
private fun quoteEventRow(row:ResultSet)=QuotationEvent(row.getString("id"),row.getString("quotation_id"),row.getString("kind"),row.getString("reason"),row.getString("created_at"),decodeQuotation(row.getString("snapshot"),row.getString("tenant_id")),row.getString("request_fingerprint")).also {require(it.snapshot.version==row.getLong("version"))}

private fun conversionFingerprint(quote:Quotation,lines:List<CartLine>):String {
    val data=encodeQuotation(quote)+lines.joinToString("|") {listOf(it.key,it.product.name,it.product.unit,it.product.priceMinor,it.quantity,it.quantityPrecision,it.sellingUnit,it.unitPriceMinor,it.baseUnitsPerUnit,it.discountPerUnitMinor,it.discountReason).joinToString(":")}
    return MessageDigest.getInstance("SHA-256").digest(data.toByteArray()).joinToString("") {"%02x".format(it.toInt() and 255)}
}

private fun encodeQuotation(quote:Quotation):String {
    val buffer=ByteArrayOutputStream()
    DataOutputStream(buffer).use {out ->
        out.writeInt(1);out.writeUTF(quote.id);out.writeUTF(quote.status.name);out.writeLong(quote.version)
        listOf(quote.createdAt,quote.updatedAt,quote.expiresOn,quote.notes).forEach(out::writeUTF)
        out.writeBoolean(quote.customer!=null);quote.customer?.let {customer ->out.nullText(customer.customerId);out.writeUTF(customer.name);out.nullText(customer.phone);out.nullText(customer.address)}
        out.nullText(quote.saleId);out.writeInt(quote.lines.size)
        quote.lines.forEach {line ->
            listOf(line.productId,line.productName,line.baseUnit).forEach(out::writeUTF);out.writeInt(line.basePrecision)
            out.nullText(line.sellingUnitId);out.writeUTF(line.sellingUnit);out.writeInt(line.baseUnitsPerUnit);out.writeInt(line.quantityPrecision);out.writeInt(line.quantity);out.writeLong(line.agreedUnitPriceMinor)
        }
    }
    require(buffer.size()<=700000) {"Quotation is too large for a recoverable document."}
    return Base64.getEncoder().encodeToString(buffer.toByteArray())
}
private fun decodeQuotation(value:String,tenantId:String):Quotation {
    require(value.length<=1000000) {"Quotation snapshot is too large."}
    return DataInputStream(ByteArrayInputStream(Base64.getDecoder().decode(value))).use {input ->
        require(input.readInt()==1) {"Quotation snapshot format is unsupported."}
        val id=input.readUTF();val status=QuotationStatus.valueOf(input.readUTF());val version=input.readLong()
        val text=List(4) {input.readUTF()}
        val customer=if(input.readBoolean()) QuotationCustomer(input.nullText(),input.readUTF(),input.nullText(),input.nullText()) else null
        val sale=input.nullText();val size=input.readInt().also {require(it in 1..100)}
        val lines=List(size) {
            val product=input.readUTF();val name=input.readUTF();val base=input.readUTF();val basePrecision=input.readInt();val unitId=input.nullText();val unit=input.readUTF()
            QuotationLine(product,name,base,basePrecision,unitId,unit,input.readInt(),input.readInt(),input.readInt(),input.readLong())
        }
        require(input.read()==-1) {"Quotation snapshot has trailing data."}
        Quotation(id,tenantId,status,version,text[0],text[1],text[2],customer,text[3],lines,sale)
    }
}
private fun DataOutputStream.nullText(value:String?) {writeBoolean(value!=null);value?.let(::writeUTF)}
private fun DataInputStream.nullText():String?=if(readBoolean()) readUTF() else null

/** Restore uses direct tenant-scoped SQL, never the currently unlocked shop's read permissions. */
internal fun PosRepository.validateQuotations(tenantId:String) {
    val quotes=quoteRows(tenantId)
    quotes.forEach {quote ->
        validQuoteId(quote.id)
        val events=quoteEvents(tenantId,quote.id)
        require(events.isNotEmpty() && events.last().snapshot==quote) {"Quotation projection has missing or inconsistent history."}
        events.forEachIndexed {index,event ->
            validQuoteId(event.id)
            val snapshot=event.snapshot;val now=Instant.parse(event.createdAt)
            require(snapshot.version==index.toLong()+1 && snapshot.tenantId==tenantId && snapshot.id==quote.id && snapshot.createdAt==quote.createdAt && snapshot.updatedAt==event.createdAt) {"Quotation revision history is incomplete."}
            require(featureQuery("SELECT id FROM outbox WHERE tenant_id=? AND kind=? AND entity_id=?",tenantId,"quotation.${event.kind}",event.id) {it.getString(1)}.size==1) {"Quotation history has no unique saved event witness."}
            snapshot.lines.forEach {line ->require(featureQuery("SELECT id FROM products WHERE tenant_id=? AND id=?",tenantId,line.productId) {it.getString(1)}.size==1) {"Quoted goods are missing from this shop."}}
            validateQuoteCustomer(tenantId,snapshot.customer)
            if(index==0) {
                require(event.kind=="issued" && snapshot.status==QuotationStatus.ISSUED && snapshot.createdAt==snapshot.updatedAt && snapshot.saleId==null) {"Quotation does not begin with issued terms."}
                validateExpiry(snapshot.expiresOn,event.createdAt)
            } else {
                val previous=events[index-1].snapshot
                require(!now.isBefore(Instant.parse(previous.updatedAt))) {"Quotation history runs backwards."}
                when(event.kind) {
                    "revised" -> {require(previous.status==QuotationStatus.ISSUED && snapshot.status==QuotationStatus.ISSUED);validateExpiry(snapshot.expiresOn,event.createdAt)}
                    "accepted" -> require(previous.status==QuotationStatus.ISSUED && snapshot==previous.copy(status=QuotationStatus.ACCEPTED,version=snapshot.version,updatedAt=event.createdAt) && !quotationExpired(snapshot.expiresOn,now)) {"Quotation acceptance changed agreed terms or was expired."}
                    "cancelled" -> require(previous.status in setOf(QuotationStatus.ISSUED,QuotationStatus.ACCEPTED) && snapshot==previous.copy(status=QuotationStatus.CANCELLED,version=snapshot.version,updatedAt=event.createdAt)) {"Quotation cancellation changed terms."}
                    "converted" -> {
                        require(previous.status==QuotationStatus.ACCEPTED && snapshot==previous.copy(status=QuotationStatus.CONVERTED,version=snapshot.version,updatedAt=event.createdAt,saleId=snapshot.saleId) && !quotationExpired(snapshot.expiresOn,now)) {"Quotation conversion changed terms or was expired."}
                        validateQuotationSale(previous,snapshot,event)
                    }
                    else -> error("Quotation has an invalid transition.")
                }
            }
        }
    }
    val witnesses=featureQuery("SELECT kind,entity_id FROM outbox WHERE tenant_id=? AND kind LIKE 'quotation.%'",tenantId) {it.getString(1) to it.getString(2)}
    val recorded=featureQuery("SELECT kind,id FROM quotation_events WHERE tenant_id=?",tenantId) {"quotation."+it.getString(1) to it.getString(2)}
    require(witnesses.size==recorded.size && witnesses.toSet()==recorded.toSet()) {"Quotation source history or its event witness is missing."}
}
private fun PosRepository.validateQuotationSale(accepted:Quotation,converted:Quotation,event:QuotationEvent) {
    val saleId=requireNotNull(converted.saleId)
    require(event.id==saleId) {"Quotation conversion operation does not identify its linked sale."}
    val headers=featureQuery("SELECT * FROM sales WHERE tenant_id=? AND id=?",converted.tenantId,saleId) {row ->
        require(row.getLong("cash_minor")==converted.totalMinor && row.getLong("total_minor")==converted.totalMinor && row.getLong("credit_minor")==0L && row.getString("customer_id")==converted.customer?.customerId) {"Converted quotation does not match a fully cash-paid sale."}
        require(!Instant.parse(row.getString("created_at")).isBefore(Instant.parse(accepted.updatedAt)) && !Instant.parse(row.getString("created_at")).isAfter(Instant.parse(event.createdAt))) {"Quotation sale chronology is inconsistent."}
        row.getString("id")
    }
    require(headers.size==1) {"Converted quotation sale is missing."}
    val lines=featureQuery("SELECT * FROM sale_lines WHERE tenant_id=? AND sale_id=? ORDER BY id",converted.tenantId,saleId) {row ->
        require(row.getLong("basket_discount_minor")==0L) {"Quotation sale contains an unagreed basket reduction."}
        val product=Product(row.getString("product_id"),converted.tenantId,row.getString("name"),row.getString("unit"),row.getLong("price_minor"),0.0,quantityPrecision=row.getInt("base_precision"))
        CartLine(product,row.getInt("quantity"),row.getString("selling_unit_id"),row.getString("selling_unit"),row.getLong("unit_price_minor"),row.getInt("base_units_per_unit"),row.getLong("discount_per_unit_minor"),row.getString("discount_reason"),row.getInt("quantity_precision"))
    }
    require(lines.size==converted.lines.size && lines.map {it.key}.distinct().size==lines.size) {"Quotation sale goods differ from agreed terms."}
    val ordered=converted.lines.map {frozen ->
        val line=requireNotNull(lines.singleOrNull {it.key==frozen.key}) {"Quotation sale is missing quoted goods."}
        require(line.product.name==frozen.productName && line.product.unit==frozen.baseUnit && line.product.quantityPrecision==frozen.basePrecision && line.sellingUnit==frozen.sellingUnit && line.baseUnitsPerUnit==frozen.baseUnitsPerUnit && line.quantityPrecision==frozen.quantityPrecision && line.quantity==frozen.quantity && line.netUnitPriceMinor==frozen.agreedUnitPriceMinor && line.discountReason==(if(line.discountPerUnitMinor>0) quotationSaleSource(converted.id,accepted.version) else null)) {"Quotation sale changed an agreed price, quantity, conversion or source."}
        line
    }
    require(event.requestFingerprint==conversionFingerprint(accepted,ordered)) {"Quotation conversion review fingerprint is inconsistent."}
}
