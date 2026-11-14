package pos.output

import com.google.zxing.oned.Code128Writer
import pos.domain.*
import java.awt.Color
import java.awt.Font
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.awt.print.PageFormat
import java.awt.print.Printable
import java.awt.print.PrinterJob
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.concurrent.FutureTask
import javax.imageio.ImageIO
import javax.imageio.metadata.IIOMetadataNode
import javax.imageio.stream.ImageOutputStream
import javax.swing.JFileChooser
import javax.swing.JOptionPane
import javax.swing.SwingUtilities
import javax.swing.filechooser.FileNameExtensionFilter

/** A label is a snapshot of current saved catalogue details, never a new barcode assignment. */
data class BarcodeLabel(val key:String,val itemName:String,val unit:String,val priceMinor:Long,val barcode:String,val contents:String?=null) {
    init {
        require(key.isNotBlank() && itemName.isNotBlank() && unit.isNotBlank() && priceMinor>=0)
        require(barcode.isNotEmpty() && barcode.length<=128)
    }
}

/** Code128 is encoded by ZXing; integer module widths preserve sharp bars and explicit quiet zones.
 * API reference: https://zxing.github.io/zxing/apidocs/com/google/zxing/oned/Code128Writer.html
 * Physical printer/scanner compatibility must be tested separately from local image decoding.
 */
object BarcodeLabelService {
    const val WIDTH_MM=80
    const val HEIGHT_MM=50
    const val DPI=300
    const val WIDTH=945
    const val HEIGHT=591
    private const val MARGIN=36
    private const val QUIET_MODULES=10
    private val baseFont:Font by lazy {
        val stream=requireNotNull(javaClass.classLoader.getResourceAsStream("composeResources/pos.shared.generated.resources/font/ibm_plex_sans_regular.ttf")) {"The bundled label font is unavailable."}
        stream.use {Font.createFont(Font.TRUETYPE_FONT,it)}
    }

    fun options(tenantId:String,product:Product,units:List<SellingUnit>):List<BarcodeLabel> {
        require(product.tenantId==tenantId && units.all {it.tenantId==tenantId && it.productId==product.id}) {"Label data belongs to another shop or item."}
        require(!product.archived) {"Restore this archived item before generating current-price labels."}
        return buildList {
            product.barcode?.let {add(BarcodeLabel(product.id,product.name,product.unit,product.priceMinor,it))}
            units.filterNot {it.archived}.forEach {unit -> unit.barcode?.let {
                add(BarcodeLabel(product.id+"~"+unit.id,product.name,unit.name,unit.priceMinor,it,"1 ${unit.name} = ${unit.baseUnitsPerUnit} ${unit.baseUnit}"))
            }}
        }
    }

    fun render(label:BarcodeLabel):BufferedImage {
        require(label.barcode.all {it in ' '..'~'}) {"This label format supports stored barcodes containing printable ASCII characters only."}
        val bars=Code128Writer().encode(label.barcode)
        val module=((WIDTH-2*MARGIN)/(bars.size+2*QUIET_MODULES)).coerceAtMost(4)
        require(module>=3) {"This stored barcode is too long for the supported 80 mm label at readable size. No label was generated."}
        val image=BufferedImage(WIDTH,HEIGHT,BufferedImage.TYPE_INT_RGB)
        val g=image.createGraphics()
        try {
            g.color=Color.WHITE;g.fillRect(0,0,WIDTH,HEIGHT);g.color=Color.BLACK
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING,RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
            g.font=baseFont.deriveFont(32f)
            val name=wrap(g,label.itemName.replace(Regex("\\s+")," "),WIDTH-2*MARGIN)
            require(name.size<=3) {"The saved item name is too long for this label. Use a larger document instead."}
            name.forEachIndexed {i,line ->g.drawString(line,MARGIN,64+i*38)}
            g.font=baseFont.deriveFont(28f)
            drawText(g,label.contents ?: "Selling unit: ${label.unit}",MARGIN,193,WIDTH-2*MARGIN)
            g.font=baseFont.deriveFont(Font.BOLD,40f)
            drawText(g,"${money(label.priceMinor)} / ${label.unit}",MARGIN,246,WIDTH-2*MARGIN)
            val x=(WIDTH-bars.size*module)/2
            bars.forEachIndexed {i,black ->if(black)g.fillRect(x+i*module,282,module,174)}
            g.font=baseFont.deriveFont(28f)
            require(g.font.canDisplayUpTo(label.barcode)<0)
            require(g.fontMetrics.stringWidth(label.barcode)<=WIDTH-2*MARGIN) {"The barcode text does not fit this label."}
            g.drawString(label.barcode,(WIDTH-g.fontMetrics.stringWidth(label.barcode))/2,500)
            g.font=baseFont.deriveFont(22f)
            g.drawString("Code128 · ${WIDTH_MM} × ${HEIGHT_MM} mm · print at actual size",MARGIN,554)
        } finally {g.dispose()}
        return image
    }

    private fun drawText(g:Graphics2D,text:String,x:Int,y:Int,width:Int) {
        require(g.font.canDisplayUpTo(text)<0) {"The saved label text contains a character unavailable in the bundled font."}
        require(g.fontMetrics.stringWidth(text)<=width) {"The saved unit or price is too long for this label."}
        g.drawString(text,x,y)
    }
    private fun wrap(g:Graphics2D,text:String,width:Int):List<String> {
        require(g.font.canDisplayUpTo(text)<0) {"The saved item name contains a character unavailable in the bundled font."}
        val lines=mutableListOf<String>();var line=""
        text.split(' ').forEach {word ->
            require(g.fontMetrics.stringWidth(word)<=width) {"A word in the saved item name is too long for this label."}
            val next=if(line.isEmpty())word else "$line $word"
            if(g.fontMetrics.stringWidth(next)>width) {lines+=line;line=word} else line=next
        }
        if(line.isNotEmpty())lines+=line
        return lines
    }

    /** A single label with 300 DPI PNG metadata. Existing destinations are never silently replaced. */
    fun writePng(label:BarcodeLabel,destination:Path,replace:Boolean=false) {
        val image=render(label)
        val target=destination.toAbsolutePath()
        require(replace || !Files.exists(target)) {"This file already exists. Choose another filename or confirm replacement."}
        val temporary=Files.createTempFile(target.parent,".barcode-label-",".png")
        try {
            val writer=ImageIO.getImageWritersByFormatName("png").next()
            try {
                ImageIO.createImageOutputStream(temporary.toFile()).use {stream:ImageOutputStream ->
                    writer.output=stream
                    val metadata=writer.getDefaultImageMetadata(javax.imageio.ImageTypeSpecifier.createFromRenderedImage(image),writer.defaultWriteParam)
                    val root=IIOMetadataNode("javax_imageio_png_1.0")
                    root.appendChild(IIOMetadataNode("pHYs").apply {setAttribute("pixelsPerUnitXAxis","11811");setAttribute("pixelsPerUnitYAxis","11811");setAttribute("unitSpecifier","meter")})
                    metadata.mergeTree("javax_imageio_png_1.0",root)
                    writer.write(null,javax.imageio.IIOImage(image,null,metadata),writer.defaultWriteParam)
                }
            } finally {writer.dispose()}
            if(replace) Files.move(temporary,target,StandardCopyOption.REPLACE_EXISTING) else Files.move(temporary,target)
        } finally {Files.deleteIfExists(temporary)}
    }

    fun savePng(label:BarcodeLabel):File? {
        val chooser=JFileChooser().apply {dialogTitle="Save one barcode label";selectedFile=File("barcode-label.png");fileFilter=FileNameExtensionFilter("PNG label (*.png)","png")}
        if(onLabelSwing {chooser.showSaveDialog(null)}!=JFileChooser.APPROVE_OPTION)return null
        val selected=chooser.selectedFile
        val file=if(selected.name.endsWith(".png",true))selected else File(selected.parentFile,selected.name+".png")
        if(file.exists() && onLabelSwing {JOptionPane.showConfirmDialog(null,"Replace ${file.name}?","Replace label",JOptionPane.YES_NO_OPTION)}!=JOptionPane.YES_OPTION)return null
        writePng(label,file.toPath(),file.exists())
        return file
    }

    /** One 80×50 mm label per page. Too-small imageable areas fail instead of shrinking the bars. */
    fun printable(label:BarcodeLabel,copies:Int):Printable {
        require(copies in 1..100) {"Print from 1 to 100 labels at a time."}
        val image=render(label)
        val widthPoints=WIDTH_MM*72.0/25.4;val heightPoints=HEIGHT_MM*72.0/25.4
        return Printable {graphics,page,index ->
            if(index !in 0 until copies) Printable.NO_SUCH_PAGE else {
                require(page.imageableWidth+0.01>=widthPoints && page.imageableHeight+0.01>=heightPoints) {"The printer's imageable area is smaller than the 80 × 50 mm label. Select suitable paper; the barcode will not be shrunk."}
                val g=graphics.create() as Graphics2D
                try {g.translate(page.imageableX,page.imageableY);g.scale(widthPoints/WIDTH,heightPoints/HEIGHT);g.drawImage(image,0,0,null)} finally {g.dispose()}
                Printable.PAGE_EXISTS
            }
        }
    }

    fun printLabels(label:BarcodeLabel,copies:Int):Boolean {
        val printable=printable(label,copies)
        check(PrinterJob.lookupPrintServices().isNotEmpty()) {"No printer is available. Save a PNG label or connect a printer."}
        val job=PrinterJob.getPrinterJob();job.jobName="Retail POS barcode labels";job.setPrintable(printable)
        if(!onLabelSwing {job.printDialog()})return false
        job.print();return true
    }
}

private fun <T> onLabelSwing(block:()->T):T {
    if(SwingUtilities.isEventDispatchThread())return block()
    val task=FutureTask<T>(block);SwingUtilities.invokeAndWait(task);return task.get()
}
