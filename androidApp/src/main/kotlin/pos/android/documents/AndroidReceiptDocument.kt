package pos.android.documents

import android.content.Context
import android.content.Intent
import android.graphics.*
import android.graphics.pdf.PdfDocument
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.print.*
import androidx.core.content.FileProvider
import pos.data.*
import pos.domain.*
import java.io.File
import java.io.FileOutputStream
import java.time.Instant
import java.time.ZoneId

/** An immutable saved sale, not a draft or a payment confirmation. */
data class AndroidReceiptSource(val sale:Sale,val details:ShopDetails?,val logo:ByteArray?,val artwork:ByteArray?)
fun loadAndroidReceipt(repo:PosRepository,tenantId:String,saleId:String):AndroidReceiptSource {
    val sale=requireNotNull(repo.sales(tenantId).singleOrNull {it.id==saleId}) {"Saved receipt is unavailable in this shop."}
    val details=repo.receiptProfile(tenantId,saleId)?.details
    return AndroidReceiptSource(sale,details,repo.receiptImageBytes(tenantId,saleId),details?.appearance?.artworkAssetId?.let {repo.imageBytes(tenantId,it)})
}
fun androidReceiptText(source:AndroidReceiptSource):String=buildString {
    val sale=source.sale
    appendLine(source.details?.displayName ?: "Saved sale")
    source.details?.let {it.phone?.let(::appendLine);it.address?.let(::appendLine);it.appearance.tagline?.let(::appendLine)}
    appendLine("Receipt ${sale.id}");appendLine(Instant.parse(sale.createdAt).atZone(ZoneId.of("Africa/Lagos")).toString())
    source.sale.lines.forEach {line ->appendLine("${line.product.name}\n${quantityLabel(line.quantity.toLong(),line.quantityPrecision)} ${line.sellingUnit} × ${money(line.netUnitPriceMinor)}   ${money(line.totalMinor)}")}
    appendLine("TOTAL ${money(sale.totalMinor)}")
    appendLine("Cash recorded ${money(sale.cashMinor)}")
    sale.cashGivenMinor?.let {appendLine("Cash given ${money(it)}");appendLine("Change to give ${money(requireNotNull(sale.changeMinor))}")}
    sale.orderPayment?.let {appendLine("Order deposit applied ${money(it.depositMinor)}")}
    sale.exchange?.let {appendLine("Exchange return value applied ${money(it.amountMinor)}")}
    sale.externalPayments.forEach {appendLine("${it.label}: ${money(it.amountMinor)}\nReference: ${it.reference}\nReceiving account/terminal: ${it.receivingAccount}")}
    if(sale.externalPayments.isNotEmpty())appendLine("This app has not confirmed that the money arrived.")
    if(sale.creditMinor>0){appendLine("Customer owes ${money(sale.creditMinor)}");sale.dueDate?.let {appendLine("Agreed due date $it · WAT")}}
    sale.note?.let {appendLine("Note: $it")}
    source.details?.let {it.appearance.policy?.let(::appendLine);it.footer?.let(::appendLine)}
    if(source.details==null)appendLine("Historical receipt identity was not captured.")
    appendLine("Saved on this device")
}

/** Real pagination, wrapping and immutable captured branding using native Android PDF. */
fun writeAndroidReceiptPdf(context:Context,source:AndroidReceiptSource):File {
    val directory=File(context.cacheDir,"receipts").apply {mkdirs()}
    val destination=File(directory,"receipt-${java.util.UUID.randomUUID()}.pdf")
    val paper=source.details?.receiptPaper ?: "A4"
    val width=when(paper){"58mm"->164;"80mm"->227;else->595}
    val height=842;val margin=if(width<300)12f else 32f;val bodySize=if(width<300) {if(source.details?.appearance?.style=="compact")8f else 9f} else {if(source.details?.appearance?.style=="compact")10f else 11f}
    val pdf=PdfDocument();var page:PdfDocument.Page?=null;var pageNumber=0;var y=0f
    val paint=Paint(Paint.ANTI_ALIAS_FLAG).apply {color=Color.rgb(24,34,48);textSize=bodySize;typeface=Typeface.create("sans-serif",Typeface.NORMAL)}
    val accent=runCatching {Color.parseColor(source.details?.appearance?.accentHex ?: "#087F73")}.getOrDefault(Color.rgb(8,127,115))
    val usable=width-2*margin
    fun start(){pageNumber++;page=pdf.startPage(PdfDocument.PageInfo.Builder(width,height,pageNumber).create());y=margin+bodySize
        if(pageNumber>1){paint.textSize=bodySize;paint.color=Color.GRAY;page!!.canvas.drawText("Receipt ${source.sale.id.take(12)} · continued",margin,y,paint);y+=bodySize*2;paint.color=Color.rgb(24,34,48)}
    }
    fun finish(){page?.let {val canvas=it.canvas;
        if(source.details?.appearance?.frame in listOf("line","double")){val border=Paint(Paint.ANTI_ALIAS_FLAG).apply {style=Paint.Style.STROKE;strokeWidth=.7f;color=accent};canvas.drawRect(4f,4f,width-4f,height-4f,border);if(source.details?.appearance?.frame=="double")canvas.drawRect(7f,7f,width-7f,height-7f,border)}
        paint.textSize=8f;paint.color=Color.GRAY;canvas.drawText("Receipt ${source.sale.id.take(12)} · Page $pageNumber",margin,height-margin,paint);pdf.finishPage(it)};page=null}
    fun ensure(lines:Int=1){if(page==null)start();if(y+lines*paint.textSize*1.5f>height-margin-20){finish();start()}}
    fun wrapped(value:String,bold:Boolean=false,size:Float=bodySize,color:Int=Color.rgb(24,34,48)) {
        paint.textSize=size;paint.color=color;paint.typeface=Typeface.create("sans-serif",if(bold)Typeface.BOLD else Typeface.NORMAL)
        for(paragraph in value.split('\n')){
            if(paragraph.isEmpty()){ensure();y+=size*.7f;continue}
            var remaining=paragraph
            while(remaining.isNotEmpty()){
                val fit=paint.breakText(remaining,true,usable,null).coerceAtLeast(1)
                val end=if(fit<remaining.length) remaining.lastIndexOf(' ',fit).takeIf {it>0} ?: fit else fit
                ensure();page!!.canvas.drawText(remaining.substring(0,end),margin,y,paint);y+=size*1.5f;remaining=remaining.substring(end).trimStart()
            }
        }
    }
    fun pair(label:String,amount:Long,strong:Boolean=false){wrapped(label,strong);paint.textSize=if(strong)bodySize+3 else bodySize;paint.typeface=Typeface.create("sans-serif",if(strong)Typeface.BOLD else Typeface.NORMAL);paint.color=if(strong)accent else Color.rgb(24,34,48);ensure();val value=money(amount);page!!.canvas.drawText(value,width-margin-paint.measureText(value),y,paint);y+=paint.textSize*1.7f}
    fun image(bytes:ByteArray?,maxHeight:Float){if(bytes==null)return;val bitmap=BitmapFactory.decodeByteArray(bytes,0,bytes.size) ?: return;val scale=minOf(usable/bitmap.width,maxHeight/bitmap.height);val w=bitmap.width*scale;val h=bitmap.height*scale;paint.textSize=bodySize;ensure(kotlin.math.ceil(h/(bodySize*1.5f)).toInt()+1);page!!.canvas.drawBitmap(bitmap,null,RectF(margin,y,margin+w,y+h),null);y+=h+bodySize;bitmap.recycle()}
    try {
        start()
        if(source.details?.appearance?.showLogo!=false)image(source.logo,64f)
        wrapped(source.details?.displayName ?: "Saved sale",true,if(width<300)16f else 25f,accent)
        source.details?.let {it.appearance.tagline?.let {v->wrapped(v)};it.phone?.let {v->wrapped(v)};it.address?.let {v->wrapped(v)}}
        wrapped("Receipt ${source.sale.id}");wrapped(Instant.parse(source.sale.createdAt).atZone(ZoneId.of("Africa/Lagos")).toString())
        val sale=source.sale
        sale.lines.forEach {line->wrapped(line.product.name,true);wrapped("${quantityLabel(line.quantity.toLong(),line.quantityPrecision)} ${line.sellingUnit} × ${money(line.netUnitPriceMinor)}");pair("Line total",line.totalMinor);if(line.discountPerUnitMinor>0 || line.basketDiscountMinor>0)wrapped("Discount included",size=bodySize-1)}
        pair("TOTAL",sale.totalMinor,true);pair("Cash recorded",sale.cashMinor)
        sale.cashGivenMinor?.let {pair("Cash given",it);pair("Change to give",requireNotNull(sale.changeMinor))}
        sale.orderPayment?.let {pair("Order deposit applied",it.depositMinor)};sale.exchange?.let {pair("Exchange return value applied",it.amountMinor)}
        sale.externalPayments.forEach {pair(it.label,it.amountMinor);wrapped("Reference: ${it.reference}");wrapped("Account/terminal: ${it.receivingAccount}")}
        if(sale.externalPayments.isNotEmpty())wrapped("This app has not confirmed that the money arrived.",true)
        if(sale.creditMinor>0){pair("Customer owes",sale.creditMinor,true);sale.dueDate?.let {wrapped("Agreed due date $it · WAT")}}
        sale.note?.let {wrapped("Note: $it")};image(source.artwork,90f)
        source.details?.let {it.appearance.policy?.let {v->wrapped(v)};it.footer?.let {v->wrapped(v)}}
        if(source.details==null)wrapped("Historical receipt identity was not captured.")
        wrapped("Saved on this device");finish()
        FileOutputStream(destination).use {pdf.writeTo(it);it.fd.sync()}
        return destination
    } catch(t:Throwable){destination.delete();throw t} finally {pdf.close()}
}
fun shareAndroidReceipt(context:Context,file:File,mime:String="application/pdf") {
    val uri=FileProvider.getUriForFile(context,"${context.packageName}.files",file)
    context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {type=mime;putExtra(Intent.EXTRA_STREAM,uri);addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)},"Share saved receipt"))
}
fun printAndroidReceipt(context:Context,file:File) {
    val manager=context.getSystemService(Context.PRINT_SERVICE) as PrintManager
    manager.print("Saved receipt",object:PrintDocumentAdapter(){
        override fun onLayout(oldAttributes:PrintAttributes?,newAttributes:PrintAttributes?,cancellationSignal:CancellationSignal?,callback:LayoutResultCallback,extras:android.os.Bundle?){
            if(cancellationSignal?.isCanceled==true)callback.onLayoutCancelled() else callback.onLayoutFinished(PrintDocumentInfo.Builder(file.name).setContentType(PrintDocumentInfo.CONTENT_TYPE_DOCUMENT).build(),true)
        }
        override fun onWrite(pages:Array<out PageRange>?,destination:ParcelFileDescriptor,cancellationSignal:CancellationSignal?,callback:WriteResultCallback){
            try {if(cancellationSignal?.isCanceled==true){callback.onWriteCancelled();return};file.inputStream().use {input->FileOutputStream(destination.fileDescriptor).use {output->input.copyTo(output)}};callback.onWriteFinished(arrayOf(PageRange.ALL_PAGES))}catch(_:Exception){callback.onWriteFailed("Saved receipt could not be sent to the print service. The sale stays saved.")}
        }
    },PrintAttributes.Builder().build())
}
