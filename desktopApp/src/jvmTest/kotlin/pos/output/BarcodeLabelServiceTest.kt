package pos.output

import com.google.zxing.*
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.oned.Code128Reader
import pos.domain.*
import java.awt.image.BufferedImage
import java.awt.print.PageFormat
import java.awt.print.Paper
import java.awt.print.Printable
import java.nio.file.Files
import javax.imageio.ImageIO
import kotlin.test.*

class BarcodeLabelServiceTest {
    private val product=Product("malt","shop","Malt drink 330 ml","bottle",50000,24.0,barcode="6150000000010")
    private val pack=SellingUnit("carton","shop",product.id,"carton",product.unit,24,1100000,"6150000000027",false,"2026-11-14T00:00:00Z")

    @Test fun baseAndPackPngRoundTripToTheirOwnSavedBarcodes() {
        val choices=BarcodeLabelService.options("shop",product,listOf(pack))
        assertEquals(listOf(50000L,1100000L),choices.map {it.priceMinor})
        assertEquals("1 carton = 24 bottle",choices.last().contents)
        choices.forEach {label ->
            val image=BarcodeLabelService.render(label)
            assertEquals(945,image.width);assertEquals(591,image.height)
            assertEquals(label.barcode,decode(image))
        }
        assertEquals("6150000000010",product.barcode)
        assertEquals(24.0,product.stock)
    }

    @Test fun storedAsciiCodesWithPunctuationAreNotRewritten() {
        val label=BarcodeLabel("id","Notebook","piece",125099,"SHOP-42/a")
        assertEquals(label.barcode,decode(BarcodeLabelService.render(label)))
        assertEquals(125099L,label.priceMinor)
    }

    @Test fun quietZonesAndBarsRemainIntegerAlignedAtReadableSize() {
        val image=BarcodeLabelService.render(BarcodeLabelService.options("shop",product,emptyList()).single())
        val y=330
        val first=(0 until image.width).first {(image.getRGB(it,y) and 0xffffff)==0}
        val last=(0 until image.width).last {(image.getRGB(it,y) and 0xffffff)==0}
        assertTrue(first>=30 && image.width-1-last>=30)
        val runLengths=mutableListOf<Int>();var black=true;var count=0
        for(x in first..last) {
            val next=(image.getRGB(x,y) and 0xffffff)==0
            if(next==black)count++ else {runLengths+=count;count=1;black=next}
        }
        runLengths+=count
        assertTrue(runLengths.all {it>=3})
        assertTrue(runLengths.all {it%3==0} || runLengths.all {it%4==0})
    }

    @Test fun unsupportedOrOversizedContentCannotProduceUnreadablySmallLabels() {
        assertFails {BarcodeLabelService.render(BarcodeLabel("a","Rice","kg",100,"é123"))}
        assertFails {BarcodeLabelService.render(BarcodeLabel("a","Rice","kg",100,"A".repeat(128)))}
        assertFails {BarcodeLabelService.render(BarcodeLabel("a","ExtremelyLongUnbreakableName".repeat(8),"piece",100,"123456"))}
        assertFails {BarcodeLabelService.render(BarcodeLabel("a","Item","unit".repeat(100),100,"123456"))}
        // An unsupported base code must not hide a usable configured pack.
        val choices=BarcodeLabelService.options("shop",product.copy(barcode="é123"),listOf(pack))
        assertEquals(pack.barcode,decode(BarcodeLabelService.render(choices.last())))
    }

    @Test fun archivedAndCrossShopCatalogueDataNeverProducesLabels() {
        assertFails {BarcodeLabelService.options("shop",product.copy(archived=true),emptyList())}
        assertFails {BarcodeLabelService.options("other",product,emptyList())}
        assertFails {BarcodeLabelService.options("shop",product,listOf(pack.copy(tenantId="other")))}
        assertFails {BarcodeLabelService.options("shop",product,listOf(pack.copy(productId="other")))}
        assertEquals(emptyList(),BarcodeLabelService.options("shop",product.copy(barcode=null),listOf(pack.copy(archived=true))))
    }

    @Test fun exportHasPhysicalSizeMetadataAndDoesNotSilentlyOverwrite() {
        val dir=Files.createTempDirectory("barcode-label-test-")
        try {
            val file=dir.resolve("label.png")
            val label=BarcodeLabelService.options("shop",product,emptyList()).single()
            BarcodeLabelService.writePng(label,file)
            assertEquals(product.barcode,decode(ImageIO.read(file.toFile())))
            val before=Files.readAllBytes(file)
            assertFails {BarcodeLabelService.writePng(label.copy(priceMinor=999),file)}
            assertContentEquals(before,Files.readAllBytes(file))
            val reader=ImageIO.getImageReadersByFormatName("png").next()
            try {
                ImageIO.createImageInputStream(file.toFile()).use {input ->
                    reader.input=input
                    val nodes=reader.getImageMetadata(0).getAsTree("javax_imageio_png_1.0").childNodes
                    val physical=(0 until nodes.length).map {nodes.item(it)}.single {it.nodeName=="pHYs"}
                    assertEquals("11811",physical.attributes.getNamedItem("pixelsPerUnitXAxis").nodeValue)
                    assertEquals("meter",physical.attributes.getNamedItem("unitSpecifier").nodeValue)
                }
            } finally {reader.dispose()}
            BarcodeLabelService.writePng(label.copy(priceMinor=999),file,true)
            assertFalse(before.contentEquals(Files.readAllBytes(file)))
        } finally {Files.list(dir).use {it.forEach(Files::deleteIfExists)};Files.deleteIfExists(dir)}
    }

    @Test fun printingKeepsPhysicalSizeAndHonoursCopyAndPageBounds() {
        val label=BarcodeLabelService.options("shop",product,emptyList()).single()
        val printable=BarcodeLabelService.printable(label,2)
        val page=PageFormat().apply {paper=Paper().apply {setSize(600.0,800.0);setImageableArea(20.0,20.0,560.0,760.0)}}
        val image=BufferedImage(600,800,BufferedImage.TYPE_INT_RGB)
        image.createGraphics().useGraphics {g ->
            assertEquals(Printable.PAGE_EXISTS,printable.print(g,page,0))
            assertEquals(Printable.PAGE_EXISTS,printable.print(g,page,1))
            assertEquals(Printable.NO_SUCH_PAGE,printable.print(g,page,2))
            assertEquals(Printable.NO_SUCH_PAGE,printable.print(g,page,-1))
            val small=PageFormat().apply {paper=Paper().apply {setSize(100.0,100.0);setImageableArea(0.0,0.0,100.0,100.0)}}
            assertFails {printable.print(g,small,0)}
        }
        assertFails {BarcodeLabelService.printable(label,0)}
        assertFails {BarcodeLabelService.printable(label,101)}
    }

    private fun decode(image:BufferedImage):String {
        val pixels=image.getRGB(0,0,image.width,image.height,null,0,image.width)
        val source=RGBLuminanceSource(image.width,image.height,pixels)
        return Code128Reader().decode(BinaryBitmap(HybridBinarizer(source))).text
    }
    private inline fun <T> java.awt.Graphics2D.useGraphics(block:(java.awt.Graphics2D)->T):T = try {block(this)} finally {dispose()}
}
