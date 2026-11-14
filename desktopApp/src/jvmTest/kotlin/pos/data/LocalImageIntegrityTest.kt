package pos.data

import java.awt.Color
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import javax.imageio.ImageIO
import kotlin.test.*
import pos.domain.*
import pos.output.BackupService

class LocalImageIntegrityTest {
    private fun picture(color:Color=Color.RED,width:Int=120,height:Int=60,format:String="png"):ByteArray {
        val image=BufferedImage(width,height,BufferedImage.TYPE_INT_RGB)
        image.createGraphics().let {g ->try {g.color=color;g.fillRect(0,0,width,height)} finally {g.dispose()}}
        return ByteArrayOutputStream().also {assertTrue(ImageIO.write(image,format,it))}.toByteArray()
    }
    private fun upload(color:Color=Color.RED)=ImageUpload.fromBytes(picture(color))
    @Test fun boundedRasterImportCopiesNormalizesAndRejectsUnsupportedContent() {
        val source=Files.createTempFile("pos-photo-",".jpg")
        try {
            Files.write(source,picture(width=1400,height=700,format="jpeg"))
            val image=ImageUpload.fromFile(source);Files.delete(source)
            val decoded=ImageIO.read(image.bytes.inputStream())
            assertEquals(768,decoded.width);assertEquals(384,decoded.height)
            assertTrue(image.bytes.size<=524288)
            val exposed=image.bytes;exposed.fill(0);assertNotEquals(0,image.bytes.first().toInt())
            assertFails {ImageUpload.fromBytes("<svg></svg>".toByteArray())}
            assertFails {ImageUpload.fromBytes(ByteArray(8_388_609))}
            assertFails {ImageUpload.fromBytes(byteArrayOf(-119,80,78,71,13,10,26,10))}
        } finally {Files.deleteIfExists(source)}
    }
    @Test fun imagesSurviveFileReopenAndEncryptedBackupWithoutSourcePaths() {
        val directory=Files.createTempDirectory("pos-image-reopen-")
        try {
            val path=directory.resolve("shop.db");val original=upload();var tenant="";var product=""
            PosRepository(path.toString()).use {repo ->
                tenant=repo.createTenant("Shop").id;product=repo.addProduct(tenant,"Soap","piece",100,5.0).id
                repo.saveImage(tenant,"product",product,original,0,"Add actual photo","photo")
            }
            PosRepository(path.toString()).use {repo ->
                val selection=repo.imageSelection(tenant,"product",product)
                assertContentEquals(original.bytes,repo.imageBytes(tenant,selection.assetId!!))
                val backup=directory.resolve("images.posbackup")
                BackupService.create(repo,tenant,"test-backup-password",backup)
                val snapshot=BackupService.read(backup,"test-backup-password")
                val restored=repo.restoreTenantSnapshot(snapshot,"Restored")
                assertContentEquals(original.bytes,repo.imageBytes(restored.id,repo.imageSelection(restored.id,"product",product).assetId!!))
            }
        } finally {directory.toFile().deleteRecursively()}
    }
    @Test fun tenantPermissionsAndLateActorFailureCannotLeakOrPartiallySaveImages() {
        PosRepository(":memory:").use {repo ->
            val a=repo.createTenant("A");val b=repo.createTenant("B");val product=repo.addProduct(a.id,"Soap","piece",100,5.0)
            val image=upload();repo.saveImage(a.id,"product",product.id,image,0,"Original image","image")
            assertFails {repo.imageSelection(b.id,"product",product.id)}
            assertFails {repo.imageBytes(b.id,image.assetId)}
            repo.setPermissionCheck {_,permission ->require(permission==StaffPermission.SELL)}
            assertFails {repo.saveImage(a.id,"product",product.id,upload(Color.BLUE),1,"Denied change")}
            assertContentEquals(image.bytes,repo.imageBytes(a.id,image.assetId))
            repo.setPermissionCheck(null)
            val before=repo.exportTenantSnapshot(a.id)
            repo.setActorProvider {error("Actor revoked")}
            assertFailsWith<IllegalStateException> {repo.saveImage(a.id,"product",product.id,upload(Color.BLUE),1,"No partial write")}
            repo.setActorProvider(null)
            assertEquals(before,repo.exportTenantSnapshot(a.id))
        }
    }
    @Test fun staleSavesAndChangedReplaysRejectWhileRemovalKeepsHistory() {
        PosRepository(":memory:").use {repo ->
            val shop=repo.createTenant("Shop");val item=repo.addProduct(shop.id,"Soap","piece",100,5.0);val image=upload()
            val first=repo.saveImage(shop.id,"product",item.id,image,0,"First photo","save")
            assertEquals(first,repo.saveImage(shop.id,"product",item.id,image,0,"First photo","save"))
            assertFails {repo.saveImage(shop.id,"product",item.id,upload(Color.BLUE),0,"Stale change")}
            assertFails {repo.saveImage(shop.id,"product",item.id,upload(Color.BLUE),0,"First photo","save")}
            repo.saveImage(shop.id,"product",item.id,null,1,"Remove old photo","remove")
            assertNull(repo.imageSelection(shop.id,"product",item.id).assetId)
            assertContentEquals(image.bytes,repo.imageBytes(shop.id,image.assetId))
            assertEquals(listOf(1L,2L),repo.imageHistory(shop.id,"product",item.id).map {it.version})
            assertEquals(5.0,repo.products(shop.id).single().stock)
            assertTrue(repo.cashEntries(shop.id).isEmpty())
        }
    }
    @Test fun receiptsKeepCapturedLogoAndOlderSalesNeverGainTodaysLogo() {
        PosRepository(":memory:").use {repo ->
            val shop=repo.createTenant("Shop");val product=repo.addProduct(shop.id,"Soap","piece",100,10.0)
            repo.openCashShift(shop.id,0)
            val old=repo.checkout(shop.id,listOf(CartLine(product,1)),100)
            val red=upload();repo.saveImage(shop.id,"shop","shop",red,0,"First logo")
            val branded=repo.checkout(shop.id,listOf(CartLine(repo.products(shop.id).single(),1)),100)
            repo.saveImage(shop.id,"shop","shop",upload(Color.BLUE),1,"New logo")
            repo.saveImage(shop.id,"shop","shop",null,2,"Remove logo")
            assertNull(repo.receiptImageBytes(shop.id,old.id))
            assertContentEquals(red.bytes,repo.receiptImageBytes(shop.id,branded.id))
            val restored=repo.restoreTenantSnapshot(repo.exportTenantSnapshot(shop.id),"Recovered")
            assertContentEquals(red.bytes,repo.receiptImageBytes(restored.id,branded.id))
            assertNull(repo.imageSelection(restored.id,"shop","shop").assetId)
        }
    }
    @Test fun tamperedAssetsHistoryAndMissingReceiptSnapshotsRejectAtomically() {
        PosRepository(":memory:").use {repo ->
            val shop=repo.createTenant("Shop");val product=repo.addProduct(shop.id,"Soap","piece",100,5.0)
            repo.saveImage(shop.id,"shop","shop",upload(),0,"First logo")
            repo.openCashShift(shop.id,0);repo.checkout(shop.id,listOf(CartLine(product,1)),100)
            val original=repo.exportTenantSnapshot(shop.id)
            fun changed(table:String,rows:List<Map<String,String?>>)=original.copy(tables=original.tables+(table to rows))
            val corruptions=listOf(
                changed("local_image_assets",original.tables.getValue("local_image_assets").map {it+("byte_size" to "1")}),
                changed("local_image_selections",original.tables.getValue("local_image_selections").map {it+("version" to "9")}),
                changed("local_image_changes",emptyList()),changed("sale_image_snapshots",emptyList()),
                changed("sale_image_snapshots",original.tables.getValue("sale_image_snapshots").map {it+("asset_id" to null)}),
            )
            val tenants=repo.tenants()
            corruptions.forEach {snapshot ->assertFails {repo.restoreTenantSnapshot(snapshot,"Invalid")};assertEquals(tenants,repo.tenants())}
        }
    }
    @Test fun legacyBackupWithoutImageTablesOrMarkerRemainsRecoverable() {
        PosRepository(":memory:").use {repo ->
            val shop=repo.createTenant("Old shop");val item=repo.addProduct(shop.id,"Soap","piece",100,5.0)
            repo.openCashShift(shop.id,0);val sale=repo.checkout(shop.id,listOf(CartLine(item,1)),100)
            val snapshot=repo.exportTenantSnapshot(shop.id)
            val legacy=snapshot.copy(tables=snapshot.tables.filterKeys {it !in localImageSnapshotTables}.mapValues {(table,rows) ->if(table=="sales") rows.map {it-"receipt_image_captured"} else rows})
            val restored=repo.restoreTenantSnapshot(legacy,"Recovered legacy")
            assertNull(repo.receiptImageBytes(restored.id,sale.id))
            assertEquals(0L,repo.imageSelection(restored.id,"shop","shop").version)
        }
    }
}
