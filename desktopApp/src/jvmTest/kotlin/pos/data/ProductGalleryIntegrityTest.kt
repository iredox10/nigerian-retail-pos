package pos.data

import java.awt.Color
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertNull
import kotlin.test.assertTrue
import pos.domain.CartLine
import pos.domain.StaffPermission
import pos.output.BackupService

class ProductGalleryIntegrityTest {
    @Test fun clockwiseRotationAndCropUseThePreviewEdgesAndPreserveTheOriginal() {
        val source = ImageUpload.fromBytes(picture(Color.RED))
        val rotated = source.adjust(1)
        val cropped = rotated.adjust(0, ImageCrop(top = 50))
        val originalPixels = ImageIO.read(source.bytes.inputStream())
        val rotatedPixels = ImageIO.read(rotated.bytes.inputStream())
        val croppedPixels = ImageIO.read(cropped.bytes.inputStream())

        assertEquals(24 to 12, originalPixels.width to originalPixels.height)
        assertEquals(12 to 24, rotatedPixels.width to rotatedPixels.height)
        assertEquals(Color.RED.rgb, rotatedPixels.getRGB(6, 6))
        assertEquals(Color.BLUE.rgb, rotatedPixels.getRGB(6, 18))
        assertEquals(12 to 12, croppedPixels.width to croppedPixels.height)
        assertEquals(Color.BLUE.rgb, croppedPixels.getRGB(6, 6))
        assertEquals(Color.RED.rgb, originalPixels.getRGB(6, 6))
        assertFails { ImageCrop(left = 60, right = 40) }
        assertFails { source.adjust(4) }
    }

    @Test fun jpegOrientationIsAppliedForBothByteOrdersAndMalformedExifIsIgnored() {
        val jpeg = picture(Color.RED, "jpeg")
        for (order in listOf(ByteOrder.BIG_ENDIAN, ByteOrder.LITTLE_ENDIAN)) {
            val withOrientation = jpegWithExif(jpeg, order, 6, 8)
            assertEquals(6, jpegOrientation(withOrientation))
            val imported = ImageUpload.fromBytes(withOrientation)
            val pixels = ImageIO.read(imported.bytes.inputStream())
            assertEquals(12 to 24, pixels.width to pixels.height)
            assertTrue(Color(pixels.getRGB(6, 4)).red > 200)
            assertTrue(Color(pixels.getRGB(6, 20)).blue > 200)
            assertEquals(1, jpegOrientation(imported.bytes))
        }
        val invalid = jpegWithExif(jpeg, ByteOrder.BIG_ENDIAN, 6, Int.MAX_VALUE)
        assertEquals(1, jpegOrientation(invalid))
        val pixels = ImageIO.read(ImageUpload.fromBytes(invalid).bytes.inputStream())
        assertEquals(24 to 12, pixels.width to pixels.height)
    }

    @Test fun galleryLifecycleSurvivesEncryptedBackupAndKeepsMainPhotoAndReceiptLogo() {
        val directory = Files.createTempDirectory("pos-gallery-backup-")
        val backup = directory.resolve("gallery.posbackup")
        try {
            PosRepository(":memory:").use { source ->
                val shop = source.createTenant("Source")
                val item = source.addProduct(shop.id, "Soap", "piece", 100, 5.0)
                val main = ImageUpload.fromBytes(picture(Color.WHITE))
                val logo = ImageUpload.fromBytes(picture(Color.BLACK))
                val gallery = ImageUpload.fromBytes(picture(Color.RED))
                source.saveImage(shop.id, "product", item.id, main, 0, "Main product photo")
                source.saveImage(shop.id, "shop", "shop", logo, 0, "Receipt shop logo")
                val sale = source.checkout(shop.id, listOf(CartLine(item, 1)), 100)
                val added = source.saveGalleryPhoto(shop.id, item.id, null, gallery, 0, "Show the other side", "photo-added")
                assertEquals(added, source.saveGalleryPhoto(shop.id, item.id, null, gallery, 0, added.reason, added.id))
                val rotated = gallery.adjust(1)
                val edited = source.saveGalleryPhoto(shop.id, item.id, added.photoId, rotated, 1, "Rotate the label upright")
                assertFails { source.saveGalleryPhoto(shop.id, item.id, added.photoId, null, 1, "Form opened before edit") }
                val removed = source.saveGalleryPhoto(shop.id, item.id, added.photoId, null, 2, "Remove outdated view")
                val second = source.saveGalleryPhoto(shop.id, item.id, null, gallery, 0, "Add revised gallery view")
                BackupService.create(source, shop.id, "gallery-test-password", backup)

                PosRepository(":memory:").use { destination ->
                    val snapshot = BackupService.read(backup, "gallery-test-password")
                    val restored = destination.restoreTenantSnapshot(snapshot, "Recovered")
                    assertEquals(listOf(added, edited, removed, second), destination.productGalleryHistory(restored.id, item.id))
                    assertNull(removed.assetId)
                    assertContentEquals(rotated.bytes, destination.imageBytes(restored.id, rotated.assetId))
                    assertContentEquals(gallery.bytes, destination.imageBytes(restored.id, gallery.assetId))
                    assertEquals(main.assetId, destination.imageSelection(restored.id, "product", item.id).assetId)
                    assertContentEquals(logo.bytes, destination.receiptImageBytes(restored.id, sale.id))
                    assertEquals(4L, destination.products(restored.id).single().exactStockScaled)
                    val shops = destination.tenants()
                    assertFails { destination.restoreTenantSnapshot(snapshot.copy(tables = snapshot.tables - "product_gallery_events"), "Missing gallery") }
                    val audit = snapshot.tables.getValue("activity_audit").filterNot { it["entity_id"] == added.id }
                    val missingAudit = snapshot.copy(tables = snapshot.tables + ("activity_audit" to audit))
                    assertFails { destination.restoreTenantSnapshot(missingAudit, "Missing gallery audit") }
                    assertEquals(shops, destination.tenants())
                }
            }
        } finally {
            Files.deleteIfExists(backup)
            Files.delete(directory)
        }
    }

    @Test fun galleryBoundsAndPermissionsRejectWritesWithoutCreatingAssets() {
        PosRepository(":memory:").use { repo ->
            val shop = repo.createTenant("Shop")
            val other = repo.createTenant("Other")
            val item = repo.addProduct(shop.id, "Soap", "piece", 100, 5.0)
            val first = ImageUpload.fromBytes(picture(Color(1, 2, 3)))
            assertFails { repo.saveGalleryPhoto(other.id, item.id, null, first, 0, "Other shop image") }
            repo.setPermissionCheck { _, permission -> require(permission == StaffPermission.SELL) }
            assertFails { repo.saveGalleryPhoto(shop.id, item.id, null, first, 0, "Denied photo upload") }
            repo.setPermissionCheck(null)
            repo.archiveProduct(shop.id, item.id, true)
            assertFails { repo.saveGalleryPhoto(shop.id, item.id, null, first, 0, "Archived product") }
            repo.archiveProduct(shop.id, item.id, false)
            repeat(PRODUCT_GALLERY_LIMIT) { index ->
                val upload = ImageUpload.fromBytes(picture(Color(index + 1, 2, 3)))
                repo.saveGalleryPhoto(shop.id, item.id, null, upload, 0, "Add picture ${index + 1}")
            }
            val extra = ImageUpload.fromBytes(picture(Color.YELLOW))
            assertFails { repo.saveGalleryPhoto(shop.id, item.id, null, extra, 0, "Would exceed gallery limit") }
            assertFails { repo.imageBytes(shop.id, extra.assetId) }
            val current = repo.productGalleryHistory(shop.id, item.id)
            assertEquals(PRODUCT_GALLERY_LIMIT, current.size)
            assertFails { repo.saveGalleryPhoto(shop.id, item.id, current.last().photoId, first, 1, "Duplicate saved image") }
            assertEquals(current, repo.productGalleryHistory(shop.id, item.id))
        }
    }

    private fun picture(firstHalf: Color, format: String = "png"): ByteArray {
        val image = BufferedImage(24, 12, BufferedImage.TYPE_INT_RGB)
        val graphics = image.createGraphics()
        try {
            graphics.color = firstHalf
            graphics.fillRect(0, 0, 12, 12)
            graphics.color = Color.BLUE
            graphics.fillRect(12, 0, 12, 12)
        } finally {
            graphics.dispose()
        }
        return ByteArrayOutputStream().also { assertTrue(ImageIO.write(image, format, it)) }.toByteArray()
    }

    private fun jpegWithExif(jpeg: ByteArray, order: ByteOrder, orientation: Int, directoryOffset: Int): ByteArray {
        val payload = ByteBuffer.allocate(32).order(order)
        payload.put(byteArrayOf(69, 120, 105, 102, 0, 0))
        payload.putShort(if (order == ByteOrder.BIG_ENDIAN) 0x4d4d else 0x4949)
        payload.putShort(42).putInt(directoryOffset)
        payload.putShort(1).putShort(0x0112).putShort(3).putInt(1).putShort(orientation.toShort()).putShort(0)
        payload.putInt(0)
        return jpeg.copyOfRange(0, 2) + byteArrayOf(0xff.toByte(), 0xe1.toByte(), 0, 34) +
            payload.array() + jpeg.copyOfRange(2, jpeg.size)
    }
}
