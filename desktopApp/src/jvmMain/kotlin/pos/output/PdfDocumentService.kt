package pos.output

import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.pdmodel.PDPage
import org.apache.pdfbox.pdmodel.PDPageContentStream
import org.apache.pdfbox.pdmodel.common.PDRectangle
import org.apache.pdfbox.pdmodel.font.PDType0Font
import org.apache.pdfbox.pdmodel.graphics.image.LosslessFactory
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject
import pos.domain.ReceiptAppearance
import java.awt.Color
import java.io.ByteArrayInputStream
import javax.imageio.ImageIO
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.AtomicMoveNotSupportedException
import java.util.concurrent.FutureTask
import javax.swing.JFileChooser
import javax.swing.JOptionPane
import javax.swing.SwingUtilities
import javax.swing.filechooser.FileNameExtensionFilter

/** Explicit document identity is preserved on every page; file output never changes local records. */
enum class PdfDocumentKind(
    val heading: String,
    val label: String,
    val referencePrefix: String,
    val usesSaleBranding: Boolean = false,
) {
    QUOTATION("QUOTATION","quotation","Quotation: "),
    RECEIPT("SALES RECEIPT","sales receipt","Receipt: ", usesSaleBranding = true),
    CUSTOMER_STATEMENT("CUSTOMER STATEMENT","customer statement","Customer ID: "),
    SUPPLIER_STATEMENT("SUPPLIER STATEMENT","supplier statement","Supplier account ID: "),
    SALES_INVOICE("SALES INVOICE", "sales invoice", "Invoice: ", usesSaleBranding = true),
    DELIVERY_NOTE("DELIVERY NOTE", "delivery note", "Delivery note: ", usesSaleBranding = true),
    CREDIT_NOTE("CREDIT NOTE", "credit note", "Credit note: ", usesSaleBranding = true),
}

object PdfDocumentService {
    private data class Row(
        val text: String, val size: Float, val bold: Boolean,
        val centered: Boolean = false, val accent: Boolean = false, val artwork: Boolean = false,
        val height: Float = size * 1.4f, val value: String? = null, val totalPanel: Boolean = false,
        val rightAligned: Boolean = false, val muted: Boolean = false, val rule: Boolean = false,
    )
    private const val FONT_ROOT="/composeResources/pos.shared.generated.resources/font/"

    fun createPdf(text:String,kind:PdfDocumentKind,paper:String="A4",logoPng:ByteArray?=null,receiptAppearance:ReceiptAppearance?=null,artworkPng:ByteArray?=null):ByteArray {
        require(logoPng == null || kind.usesSaleBranding) {"This document does not use saved sale branding."}
        require(receiptAppearance == null || kind == PdfDocumentKind.RECEIPT) {"Custom receipt styling is available for sales receipts."}
        require(artworkPng == null || receiptAppearance != null) {"Choose a receipt design before adding artwork."}
        require(receiptAppearance?.artworkAssetId == null || artworkPng != null) {"The saved receipt artwork is unavailable. Restore its image before exporting."}
        require(paper in setOf("A4","58mm","80mm")) {"Choose A4, 58 mm or 80 mm PDF paper."}
        require(text.isNotBlank() && text.length<=1_000_000 && text.lines().size<=20_000) {"Document text is empty or too large."}
        return PDDocument().use {document ->
            val logo=logoPng?.takeIf { receiptAppearance?.showLogo != false }?.let {receiptLogo(document,it)}
            val artwork=artworkPng?.let {receiptLogo(document,it)}
            val regular=loadFont(document,"ibm_plex_sans_regular.ttf")
            val bold=loadFont(document,"ibm_plex_sans_semibold.ttf")
            val thermal=paper!="A4"
            var pageSize=if(!thermal) PDRectangle.A4 else PDRectangle(if(paper=="58mm") mm(58f) else mm(80f),mm(200f))
            val margin=if(thermal) { if(receiptAppearance?.frame != null && receiptAppearance.frame != "none") 13f else 9f } else 36f
            val compact=receiptAppearance?.style=="compact"
            val modern=receiptAppearance?.style=="modern"
            val bodySize=if(thermal) {if(compact) 8f else 8.5f} else {if(compact) 9.5f else 10.5f}
            val accent=if(thermal) Color.BLACK else receiptAppearance?.let {Color.decode(it.accentHex)} ?: Color.BLACK
            val topSpace=if(modern) (if(thermal) 16f else 24f) else 0f
            val contentWidth=pageSize.width-2*margin
            val logoScale=logo?.let {minOf((if(thermal) 100f else 144f)/it.width,(if(thermal) 44f else 72f)/it.height,1f)} ?: 0f
            val logoWidth=(logo?.width ?: 0)*logoScale
            val logoHeight=(logo?.height ?: 0)*logoScale
            val logoSpace=if(logo!=null) logoHeight+12f else 0f
            val artworkScale=artwork?.let {minOf(contentWidth/it.width,(if(thermal) 44f else 80f)/it.height,1f)} ?: 0f
            val artworkWidth=(artwork?.width ?: 0)*artworkScale
            val artworkHeight=(artwork?.height ?: 0)*artworkScale
            val sourceLines=text.lines().toMutableList()
            receiptAppearance?.tagline?.let {sourceLines.add(1,it)}
            receiptAppearance?.policy?.let {sourceLines.add("");sourceLines.add("Shop policy");sourceLines.add(it)}
            val headerEnd=sourceLines.indexOf(kind.heading).coerceAtLeast(0)
            var inItems = false
            val rowGroups=sourceLines.mapIndexed {index,line ->
                require(line.none {it.isISOControl() && it!='\t'}) {"Document text contains an unsupported control character."}
                val clean=line.replace('\t',' ')
                val isTitle=index==0 || clean==kind.heading
                val isTotal=clean.startsWith("Original sale total:") || clean.startsWith("Sale value after returns:") || clean.startsWith("Opening debt:") || clean.startsWith("Closing debt:") || clean.startsWith("Opening amount owed:") || clean.startsWith("Closing amount owed:") || clean.startsWith("Agreed quotation total:") || clean.startsWith("Invoice total:") || clean.startsWith("Returned goods value:")
                if (modern && clean == "----------------------------------------") {
                    inItems = !inItems
                    listOf(Row("", bodySize, false, height = if (thermal) 15f else 25f, rule = true))
                } else if (modern && clean.startsWith("Original sale total:") && moneyPair(clean) != null) {
                    val (label, amount) = requireNotNull(moneyPair(clean))
                    wrap(label, bold, bodySize, contentWidth - 20f, kind).mapIndexed { part, labelPart ->
                        Row(labelPart, bodySize, true, value = if (part == 0) amount else null,
                            totalPanel = part == 0, height = if (part == 0) (if (thermal) 48f else 72f) else bodySize * 1.4f)
                    }
                } else {
                    val amount = if (modern) moneyPair(clean) else null
                    val standaloneMoney = modern && clean.trim().matches(Regex("[−-]?₦[0-9,]+\\.[0-9]{2}"))
                    val itemName = modern && inItems && clean.isNotBlank() && !line.startsWith(" ")
                    val boldRow = isTitle || isTotal || itemName || standaloneMoney
                    val size=when {
                        index==0 && modern -> if(thermal) 15f else 26f
                        index==0 -> if(thermal) 12f else 16f
                        isTitle -> if(thermal) 10f else 13f
                        isTotal -> bodySize+1f
                        else -> bodySize
                    }
                    val font=if(boldRow) bold else regular
                    val pairWidth = amount?.second?.let { bold.getStringWidth(it) / 1000f * size + 14f } ?: 0f
                    val canPair = amount != null && contentWidth - pairWidth >= contentWidth * 0.42f
                    val textToWrap = if (canPair) requireNotNull(amount).first else clean
                    val parts = wrap(textToWrap, font, size, if (canPair) contentWidth - pairWidth else contentWidth, kind)
                    parts.mapIndexed { part, value ->
                        Row(value,size,boldRow,
                            centered=receiptAppearance?.headerAlignment=="center" && index<=headerEnd,
                            accent=modern && isTitle,
                            height=size*(if(compact) 1.25f else 1.4f) +
                                (if (modern && index == 0 && part == parts.lastIndex) (if(thermal) 7f else 12f) else 0f),
                            value=if (canPair && part == parts.lastIndex) amount?.second else null,
                            rightAligned=standaloneMoney,
                            muted=modern && !boldRow && (index<=headerEnd || clean.startsWith("Receipt:") ||
                                clean.startsWith("Date:") || line.startsWith(" ") || clean.startsWith("Saved sale record.") ||
                                clean.startsWith("not create another payment")))
                    }
                }
            }
            val paragraphGroups = if (receiptAppearance?.policy != null) {
                rowGroups.dropLast(2) + listOf(rowGroups.takeLast(2).flatten())
            } else rowGroups
            val printableGroups = if (artwork == null) paragraphGroups else paragraphGroups +
                listOf(listOf(Row("", 0f, false, artwork = true, height = artworkHeight + 12f)))
            val identity=text.lines().filter {it.startsWith(kind.referencePrefix) || (kind!=PdfDocumentKind.RECEIPT && it.startsWith("Period: "))}
            val continuation=listOf(Row("${kind.heading} - continued",bodySize,true))+
                identity.flatMap {wrap(it,regular,bodySize,contentWidth,kind).map {part -> Row(part,bodySize,false)}}+Row("",bodySize,false)
            val available=pageSize.height-2*margin-20f
            val pages=mutableListOf<MutableList<Row>>(mutableListOf())
            val continuationHeight = continuation.sumOf { it.height.toDouble() }.toFloat() + topSpace
            var used=logoSpace+topSpace
            fun startPage() {
                require(pages.size < 500) { "Document exceeds the supported PDF page count." }
                pages.add(continuation.toMutableList())
                used = continuationHeight
            }
            printableGroups.forEach { paragraph ->
                val paragraphHeight = paragraph.sumOf { it.height.toDouble() }.toFloat()
                if (paragraphHeight <= available - continuationHeight && used + paragraphHeight > available && pages.last().isNotEmpty()) {
                    startPage()
                }
                paragraph.forEach { row ->
                    if (used + row.height > available && pages.last().isNotEmpty()) startPage()
                    pages.last().add(row)
                    used += row.height
                }
            }
            if (modern && thermal && pages.size == 1) {
                val usedHeight = logoSpace + topSpace + pages.single().sumOf { it.height.toDouble() }.toFloat()
                pageSize = PDRectangle(pageSize.width, maxOf(mm(80f), usedHeight + 2 * margin + 20f))
            }
            document.documentInformation.title="Retail POS ${kind.label}"
            document.documentInformation.creator="Retail POS"
            pages.forEachIndexed {index,pageRows ->
                val page=PDPage(pageSize);document.addPage(page)
                PDPageContentStream(document,page).use {stream ->
                    drawReceiptFrame(stream,pageSize,margin,receiptAppearance,accent)
                    var y=pageSize.height-margin-topSpace
                    if(index==0 && logo!=null) {
                        stream.drawImage(logo,margin+(if(receiptAppearance?.headerAlignment=="left") 0f else (contentWidth-logoWidth)/2),y-logoHeight,logoWidth,logoHeight)
                        y-=logoSpace
                    }
                    pageRows.forEach {row ->
                        if(row.artwork) {
                            requireNotNull(artwork)
                            stream.drawImage(artwork,margin+(contentWidth-artworkWidth)/2,y-artworkHeight,artworkWidth,artworkHeight)
                        } else if (row.rule) {
                            stream.setStrokingColor(if (thermal) Color(150,150,150) else Color(218,225,230))
                            stream.setLineWidth(0.5f)
                            stream.moveTo(margin,y-row.height/2);stream.lineTo(margin+contentWidth,y-row.height/2);stream.stroke()
                        } else if (row.totalPanel) {
                            val panelColor = if (thermal) Color(244,244,244) else Color(
                                (accent.red * 0.08 + 255 * 0.92).toInt(),
                                (accent.green * 0.08 + 255 * 0.92).toInt(),
                                (accent.blue * 0.08 + 255 * 0.92).toInt())
                            stream.setNonStrokingColor(panelColor)
                            roundedRectangle(stream,margin,y-row.height+4f,contentWidth,row.height-8f,if(thermal) 3f else 8f)
                            stream.fill()
                            drawText(stream,row.text,bold,row.size,margin+10f,y-(if(thermal) 16f else 22f),Color.BLACK)
                            row.value?.let { amount ->
                                val targetSize=if(thermal) 14f else 26f
                                val amountSize=minOf(targetSize,(contentWidth-20f)/(bold.getStringWidth(amount)/1000f))
                                drawText(stream,amount,bold,amountSize,
                                    margin+contentWidth-10f-bold.getStringWidth(amount)/1000f*amountSize,
                                    y-(if(thermal) 35f else 55f),if(thermal) Color.BLACK else accent)
                            }
                        } else if(row.text.isNotEmpty()) {
                            val font=if(row.bold) bold else regular
                            val textWidth=font.getStringWidth(row.text)/1000f*row.size
                            val x=when {row.centered -> margin+(contentWidth-textWidth)/2;row.rightAligned -> margin+contentWidth-textWidth;else -> margin}
                            val color=when {row.accent -> accent;row.muted -> if(thermal) Color(75,75,75) else Color(92,104,116);else -> Color.BLACK}
                            drawText(stream,row.text,font,row.size,x,y-row.size,color)
                            row.value?.let { amount ->
                                drawText(stream,amount,bold,row.size,margin+contentWidth-bold.getStringWidth(amount)/1000f*row.size,y-row.size,Color.BLACK)
                            }
                        }
                        y-=row.height
                    }
                    stream.setNonStrokingColor(if (modern) Color(100,110,120) else Color.BLACK)
                    val footer="Page ${index+1} of ${pages.size}"
                    stream.beginText();stream.setFont(regular,if(thermal) 7f else 8f);stream.newLineAtOffset(margin,margin);stream.showText(footer);stream.endText()
                }
            }
            ByteArrayOutputStream().use {out ->document.save(out);out.toByteArray()}
        }
    }

    /** Existing files require a separately obtained user confirmation. Failed rendering leaves them intact. */
    fun writePdf(text:String,kind:PdfDocumentKind,paper:String,destination:Path,replaceExisting:Boolean=false,logoPng:ByteArray?=null,receiptAppearance:ReceiptAppearance?=null,artworkPng:ByteArray?=null):Path {
        require(replaceExisting || !Files.exists(destination)) {"Choose a new filename; this PDF already exists."}
        val bytes=createPdf(text,kind,paper,logoPng,receiptAppearance,artworkPng)
        val absolute=destination.toAbsolutePath()
        val temp=Files.createTempFile(absolute.parent,".document-",".pdf.tmp")
        try {
            java.io.FileOutputStream(temp.toFile()).use {it.write(bytes);it.fd.sync()}
            if(replaceExisting) {
                try {Files.move(temp,absolute,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING)}
                catch(_:AtomicMoveNotSupportedException) {Files.move(temp,absolute,StandardCopyOption.REPLACE_EXISTING)}
            } else Files.move(temp,absolute)
        } finally {Files.deleteIfExists(temp)}
        return absolute
    }

    fun export(text:String,kind:PdfDocumentKind,paper:String="A4",logoPng:ByteArray?=null,receiptAppearance:ReceiptAppearance?=null,artworkPng:ByteArray?=null):File? {
        val chooser=JFileChooser().apply {
            dialogTitle="Save ${kind.label} PDF ($paper)"
            fileFilter=FileNameExtensionFilter("PDF document (*.pdf)","pdf")
            selectedFile=File("${kind.label.replace(' ','-')}.pdf")
        }
        if(onSwing {chooser.showSaveDialog(null)}!=JFileChooser.APPROVE_OPTION) return null
        val chosen=chooser.selectedFile
        val file=if(chosen.name.endsWith(".pdf",true)) chosen else File(chosen.parentFile,chosen.name+".pdf")
        val replace=file.exists()
        if(replace && onSwing {JOptionPane.showConfirmDialog(null,"Replace ${file.name}?","Replace ${kind.label} PDF",JOptionPane.YES_NO_OPTION)}!=JOptionPane.YES_OPTION) return null
        return writePdf(text,kind,paper,file.toPath(),replace,logoPng,receiptAppearance,artworkPng).toFile()
    }

    private fun moneyPair(text: String): Pair<String,String>? {
        val match = Regex("^(.+:) ([−-]?₦[0-9,]+\\.[0-9]{2})$").matchEntire(text.trim()) ?: return null
        return match.groupValues[1] to match.groupValues[2]
    }

    private fun drawText(stream: PDPageContentStream, text: String, font: PDType0Font, size: Float, x: Float, y: Float, color: Color) {
        stream.setNonStrokingColor(color)
        stream.beginText();stream.setFont(font,size);stream.newLineAtOffset(x,y);stream.showText(text);stream.endText()
    }

    private fun roundedRectangle(stream: PDPageContentStream, x: Float, y: Float, width: Float, height: Float, radius: Float) {
        val curve=radius*0.5522848f
        stream.moveTo(x+radius,y)
        stream.lineTo(x+width-radius,y);stream.curveTo(x+width-radius+curve,y,x+width,y+radius-curve,x+width,y+radius)
        stream.lineTo(x+width,y+height-radius);stream.curveTo(x+width,y+height-radius+curve,x+width-radius+curve,y+height,x+width-radius,y+height)
        stream.lineTo(x+radius,y+height);stream.curveTo(x+radius-curve,y+height,x,y+height-radius+curve,x,y+height-radius)
        stream.lineTo(x,y+radius);stream.curveTo(x,y+radius-curve,x+radius-curve,y,x+radius,y)
        stream.closePath()
    }

    private fun drawReceiptFrame(stream:PDPageContentStream,page:PDRectangle,margin:Float,appearance:ReceiptAppearance?,accent:Color) {
        if(appearance==null) return
        stream.setStrokingColor(accent)
        stream.setLineWidth(0.7f)
        val inset=margin/2
        if(appearance.frame!="none") {
            stream.addRect(inset,inset,page.width-2*inset,page.height-2*inset)
            stream.stroke()
            if(appearance.frame=="double") {
                stream.addRect(inset+3f,inset+3f,page.width-2*inset-6f,page.height-2*inset-6f)
                stream.stroke()
            }
        }
        if(appearance.style=="modern") {
            stream.setNonStrokingColor(accent)
            stream.addRect(margin,page.height-margin-4f,page.width-2*margin,4f)
            stream.fill()
            stream.setNonStrokingColor(Color.BLACK)
        }
    }

    /** Decode only bounded local PNG bytes. Bad branding must not overwrite an existing export. */
    private fun receiptLogo(document:PDDocument,bytes:ByteArray):PDImageXObject {
        require(bytes.size in 8..1_048_576 && bytes.copyOfRange(0,8).contentEquals(byteArrayOf(-119,80,78,71,13,10,26,10))) {"The saved shop logo must be a PNG image up to 1 MB."}
        val image=ImageIO.createImageInputStream(ByteArrayInputStream(bytes)).use {input ->
            require(input!=null) {"The saved shop logo could not be read."}
            val readers=ImageIO.getImageReaders(input)
            require(readers.hasNext()) {"The saved shop logo is not a readable PNG."}
            val reader=readers.next()
            try {
                reader.input=input
                require(reader.formatName.equals("png",true) && reader.getWidth(0) in 1..1024 && reader.getHeight(0) in 1..1024) {"The saved shop logo exceeds the supported 1024-pixel dimensions."}
                requireNotNull(reader.read(0)) {"The saved shop logo could not be decoded."}
            } finally {reader.dispose()}
        }
        return LosslessFactory.createFromImage(document,image)
    }

    private fun loadFont(document:PDDocument,name:String):PDType0Font {
        val stream=requireNotNull(PdfDocumentService::class.java.getResourceAsStream(FONT_ROOT+name)) {"The bundled PDF font is unavailable. Reinstall the application or export text/CSV."}
        return stream.use {PDType0Font.load(document,it,true)}
    }
    private fun wrap(text:String,font:PDType0Font,size:Float,width:Float,kind:PdfDocumentKind):List<String> {
        if(text.isEmpty()) return listOf("")
        val points=text.codePoints().toArray().map {String(Character.toChars(it))}
        points.forEach {value ->
            try {font.encode(value)} catch(_:IllegalArgumentException) {
                val fallback = when (kind) {
                    PdfDocumentKind.RECEIPT -> "Export a text receipt instead."
                    PdfDocumentKind.QUOTATION -> "Export a text quotation instead."
                    PdfDocumentKind.CUSTOMER_STATEMENT, PdfDocumentKind.SUPPLIER_STATEMENT -> "Export a CSV statement instead."
                    else -> "You can copy the text from the preview."
                }
                throw IllegalArgumentException("A name or message uses a character this PDF font cannot print. $fallback No PDF was saved.")
            }
        }
        val result=mutableListOf<String>()
        var start=0
        while(start<points.size) {
            var end=start;var fit=start;var space=-1
            val builder=StringBuilder()
            while(end<points.size) {
                builder.append(points[end])
                if(font.getStringWidth(builder.toString())/1000f*size>width) break
                fit=end+1
                if(points[end]==" ") space=end
                end++
            }
            require(fit>start) {"The selected PDF paper is too narrow for this document font."}
            val split=if(fit<points.size && space>start) space else fit
            result+=points.subList(start,split).joinToString("").trimEnd()
            start=split
            while(start<points.size && points[start]==" ") start++
        }
        return result
    }
    private fun mm(value:Float)=value*72f/25.4f
    private fun <T> onSwing(block:()->T):T {
        if(SwingUtilities.isEventDispatchThread()) return block()
        val task=FutureTask<T> {block()};SwingUtilities.invokeAndWait(task);return task.get()
    }
}
