package pos.data

import java.awt.image.BufferedImage
import java.nio.file.Files
import java.nio.file.Path
import javax.imageio.ImageIO
import kotlin.test.*
import pos.domain.ProductMetadata

class BulkProductPhotoImportTest {
    @Test fun matchesActiveSkuAndBarcodeAndAppliesOnlySelectedRowsWithStableHistory() = photoFolder { folder ->
        PosRepository(":memory:").use { repo ->
            val shop = repo.createTenant("Shop")
            val other = repo.createTenant("Other")
            val soap = repo.addProduct(shop.id, "Soap", "piece", 500, 6.0)
            repo.updateProductMetadata(shop.id, soap.id, ProductMetadata(sku = "SOAP-200"), "sku")
            val malt = repo.addProduct(shop.id, "Malt", "bottle", 1_000, 3.0, "6150000000010")
            val powder = repo.addProduct(shop.id, "Powder", "sachet", 100, 2.0, "6150000000027")
            repo.archiveProduct(shop.id, powder.id, true)
            writePhoto(folder.resolve("soap-200.png"), 0xff009999.toInt())
            writePhoto(folder.resolve("6150000000010.png"), 0xfff77700.toInt())
            writePhoto(folder.resolve("6150000000027.png"), 0xff545444.toInt())
            writePhoto(folder.resolve("unknown.png"), 0xff0099ff.toInt())
            val reviewed = repo.reviewProductPhotoFolder(shop.id, folder)
            val matched = reviewed.rows.filter { it.candidate != null }
            assertEquals(setOf(soap.id, malt.id), matched.map { it.candidate!!.product.id }.toSet())
            assertEquals(2, reviewed.rows.count { it.skippedReason != null })
            val selected = setOf("soap-200.png")
            assertFails { repo.importProductPhotos(other.id, reviewed, selected, "Wrong shop") }
            assertEquals(1, repo.importProductPhotos(shop.id, reviewed, selected, "Updated label photo"))
            assertEquals(1, repo.importProductPhotos(shop.id, reviewed, selected, "Updated label photo"))
            assertEquals(1, repo.imageHistory(shop.id, "product", soap.id).size)
            assertEquals(0L, repo.imageSelection(shop.id, "product", malt.id).version)
            val restored = repo.restoreTenantSnapshot(repo.exportTenantSnapshot(shop.id), "Recovered")
            assertEquals(repo.imageSelection(shop.id, "product", soap.id),
                repo.imageSelection(restored.id, "product", soap.id))
        }
    }

    @Test fun duplicateFilesAndStaleSecondItemCannotCausePartialImport() = photoFolder { folder ->
        PosRepository(":memory:").use { repo ->
            val shop = repo.createTenant("Shop")
            val first = repo.addProduct(shop.id, "First", "piece", 500, 5.0, "0010")
            val second = repo.addProduct(shop.id, "Second", "piece", 600, 3.0, "0020")
            val firstFile = folder.resolve("0010.png")
            val duplicate = folder.resolve("0010.jpg")
            val secondFile = folder.resolve("0020.png")
            writePhoto(firstFile, 0xff787878.toInt())
            writePhoto(duplicate, 0xffabc010.toInt())
            writePhoto(secondFile, 0xff0022ff.toInt())
            val duplicates = repo.reviewProductPhotoFolder(shop.id, folder).rows
            assertEquals(2, duplicates.count { it.skippedReason?.contains("More than one file") == true })
            Files.delete(duplicate)
            val preview = repo.reviewProductPhotoFolder(shop.id, folder)
            repo.saveImage(shop.id, "product", second.id, ImageUpload.fromFile(firstFile), 0, "Photo saved while reviewing")
            val before = repo.exportTenantSnapshot(shop.id)
            assertFails { repo.importProductPhotos(shop.id, preview, setOf("0010.png", "0020.png"), "Reviewed new photos") }
            assertEquals(before, repo.exportTenantSnapshot(shop.id))
            assertNull(repo.imageSelection(shop.id, "product", first.id).assetId)
        }
    }

    private fun writePhoto(path: Path, colour: Int) {
        val image = BufferedImage(2, 3, BufferedImage.TYPE_INT_ARGB)
        image.setRGB(0, 0, colour)
        check(ImageIO.write(image, "png", path.toFile()))
    }

    private fun photoFolder(block: (Path) -> Unit) {
        val folder = Files.createTempDirectory("pos-photo-import-")
        try { block(folder) }
        finally { folder.toFile().deleteRecursively() }
    }
}
