package pos.output

import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.pdmodel.PDPage
import org.apache.pdfbox.pdmodel.PDPageContentStream
import org.apache.pdfbox.pdmodel.common.PDRectangle
import org.apache.pdfbox.pdmodel.font.PDType0Font
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
enum class PdfDocumentKind(val heading:String,val label:String,val referencePrefix:String) {
    RECEIPT("SALES RECEIPT","sales receipt","Receipt: "),
    CUSTOMER_STATEMENT("CUSTOMER STATEMENT","customer statement","Customer ID: "),
    SUPPLIER_STATEMENT("SUPPLIER STATEMENT","supplier statement","Supplier account ID: "),
}

object PdfDocumentService {
    private data class Row(val text:String,val size:Float,val bold:Boolean) {val height:Float get()=size*1.4f}
    private const val FONT_ROOT="/composeResources/pos.shared.generated.resources/font/"

    fun createPdf(text:String,kind:PdfDocumentKind,paper:String="A4"):ByteArray {
        require(paper in setOf("A4","58mm","80mm")) {"Choose A4, 58 mm or 80 mm PDF paper."}
        require(text.isNotBlank() && text.length<=1_000_000 && text.lines().size<=20_000) {"Document text is empty or too large."}
        return PDDocument().use {document ->
            val regular=loadFont(document,"ibm_plex_sans_regular.ttf")
            val bold=loadFont(document,"ibm_plex_sans_semibold.ttf")
            val thermal=paper!="A4"
            val pageSize=if(!thermal) PDRectangle.A4 else PDRectangle(if(paper=="58mm") mm(58f) else mm(80f),mm(200f))
            val margin=if(thermal) 9f else 36f
            val bodySize=if(thermal) 8.5f else 10.5f
            val contentWidth=pageSize.width-2*margin
            val rows=text.lines().flatMapIndexed {index,line ->
                require(line.none {it.isISOControl() && it!='\t'}) {"Document text contains an unsupported control character."}
                val clean=line.replace('\t',' ')
                val isTitle=index==0 || clean==kind.heading
                val isTotal=clean.startsWith("Original sale total:") || clean.startsWith("Sale value after returns:") || clean.startsWith("Opening debt:") || clean.startsWith("Closing debt:") || clean.startsWith("Opening amount owed:") || clean.startsWith("Closing amount owed:")
                val size=when {index==0 -> if(thermal) 12f else 16f;isTitle -> if(thermal) 10f else 13f;isTotal -> bodySize+1f;else -> bodySize}
                val font=if(isTitle || isTotal) bold else regular
                wrap(clean,font,size,contentWidth,kind).map {Row(it,size,isTitle || isTotal)}
            }
            val identity=text.lines().filter {it.startsWith(kind.referencePrefix) || (kind!=PdfDocumentKind.RECEIPT && it.startsWith("Period: "))}
            val continuation=listOf(Row("${kind.heading} - continued",bodySize,true))+
                identity.flatMap {wrap(it,regular,bodySize,contentWidth,kind).map {part -> Row(part,bodySize,false)}}+Row("",bodySize,false)
            val available=pageSize.height-2*margin-20f
            val pages=mutableListOf<MutableList<Row>>(mutableListOf())
            var used=0f
            rows.forEach {row ->
                if(used+row.height>available && pages.last().isNotEmpty()) {
                    require(pages.size<500) {"Document exceeds the supported PDF page count."}
                    pages.add(continuation.toMutableList());used=continuation.sumOf {it.height.toDouble()}.toFloat()
                }
                pages.last().add(row);used+=row.height
            }
            document.documentInformation.title="Retail POS ${kind.label}"
            document.documentInformation.creator="Retail POS"
            pages.forEachIndexed {index,pageRows ->
                val page=PDPage(pageSize);document.addPage(page)
                PDPageContentStream(document,page).use {stream ->
                    var y=pageSize.height-margin
                    pageRows.forEach {row ->
                        y-=row.size
                        if(row.text.isNotEmpty()) {
                            stream.beginText();stream.setFont(if(row.bold) bold else regular,row.size);stream.newLineAtOffset(margin,y);stream.showText(row.text);stream.endText()
                        }
                        y-=row.height-row.size
                    }
                    val footer="Page ${index+1} of ${pages.size}"
                    stream.beginText();stream.setFont(regular,if(thermal) 7f else 8f);stream.newLineAtOffset(margin,margin);stream.showText(footer);stream.endText()
                }
            }
            ByteArrayOutputStream().use {out ->document.save(out);out.toByteArray()}
        }
    }

    /** Existing files require a separately obtained user confirmation. Failed rendering leaves them intact. */
    fun writePdf(text:String,kind:PdfDocumentKind,paper:String,destination:Path,replaceExisting:Boolean=false):Path {
        require(replaceExisting || !Files.exists(destination)) {"Choose a new filename; this PDF already exists."}
        val bytes=createPdf(text,kind,paper)
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

    fun export(text:String,kind:PdfDocumentKind,paper:String="A4"):File? {
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
        return writePdf(text,kind,paper,file.toPath(),replace).toFile()
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
                throw IllegalArgumentException("A name or message uses a character this PDF font cannot print. ${if(kind==PdfDocumentKind.RECEIPT) "Export a text receipt" else "Export a CSV statement"} instead; no PDF was saved.")
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
