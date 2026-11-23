package pos.data

import java.awt.Color
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.util.Base64
import javax.imageio.ImageIO
import kotlin.test.*
import pos.domain.*

class ReceiptAppearanceIntegrityTest {
    private fun artwork():ImageUpload {
        val picture=BufferedImage(40,20,BufferedImage.TYPE_INT_RGB)
        val graphics=picture.createGraphics()
        try {graphics.color=Color.BLUE;graphics.fillRect(0,0,40,20)} finally {graphics.dispose()}
        val bytes=ByteArrayOutputStream()
        ImageIO.write(picture,"png",bytes)
        return ImageUpload.fromBytes(bytes.toByteArray())
    }

    @Test fun artworkAndDesignAreCapturedForEachReceiptAndRecoverWithHistory() {
        PosRepository(":memory:").use {repo ->
            val shop=repo.createTenant("Shop")
            val image=artwork()
            val asset=repo.saveReceiptArtwork(shop.id,image)
            val imported=repo.exportTenantSnapshot(shop.id)
            assertEquals(asset,repo.saveReceiptArtwork(shop.id,image))
            assertEquals(imported,repo.exportTenantSnapshot(shop.id))
            val details=ShopDetails("Amina Stores",appearance=ReceiptAppearance(
                style="modern",frame="double",accentHex="#183A65",headerAlignment="left",
                artworkAssetId=asset,tagline="Everyday essentials",policy="Keep your receipt for returns",
            ))
            repo.saveShopProfile(shop.id,details,0,"Personalise receipts","design")
            val item=repo.addProduct(shop.id,"Soap","piece",100,3.0)
            val sale=repo.checkout(shop.id,listOf(CartLine(item,1)),100,operationId="sale")
            repo.saveShopProfile(shop.id,details.copy(appearance=ReceiptAppearance()),1,"Return to classic","classic")
            assertEquals(details,repo.receiptProfile(shop.id,sale.id)?.details)
            val saved=repo.exportTenantSnapshot(shop.id)
            val restored=repo.restoreTenantSnapshot(saved,"Recovered")
            assertEquals(details,repo.receiptProfile(restored.id,sale.id)?.details)
            assertContentEquals(image.bytes,repo.imageBytes(restored.id,asset))
            val before=repo.tenants()
            assertFails {repo.restoreTenantSnapshot(saved.copy(tables=saved.tables-"receipt_artwork_imports"),"Missing artwork history")}
            assertEquals(before,repo.tenants())
            val other=repo.createTenant("Other")
            assertFails {repo.saveShopProfile(other.id,details,0,"Cross shop image","foreign")}
        }
    }

    @Test fun legacyVersionOneSettingsRestoreWithDefaultAppearance() {
        PosRepository(":memory:").use {repo ->
            val shop=repo.createTenant("Legacy")
            val details=ShopDetails("Printed shop",footer="Thank you",receiptPaper="80mm")
            repo.saveShopProfile(shop.id,details,0,"Original setup","settings")
            val bytes=ByteArrayOutputStream()
            DataOutputStream(bytes).use {out ->
                out.writeInt(1)
                listOf(details.displayName,details.phone,details.address,details.footer,details.receiptPaper).forEach {
                    out.writeBoolean(it!=null)
                    if(it!=null) out.writeUTF(it)
                }
            }
            val encoded=Base64.getEncoder().encodeToString(bytes.toByteArray())
            val source=repo.exportTenantSnapshot(shop.id)
            val tables=source.tables+mapOf(
                "shop_profiles" to source.tables.getValue("shop_profiles").map {it+("details" to encoded)},
                "shop_profile_changes" to source.tables.getValue("shop_profile_changes").map {it+("current_details" to encoded)},
            )
            val restored=repo.restoreTenantSnapshot(source.copy(tables=tables),"Recovered legacy")
            assertEquals(details,repo.shopProfile(restored.id)?.details)
            assertEquals(ReceiptAppearance(),repo.shopProfile(restored.id)?.details?.appearance)
        }
    }

    @Test fun customizationRejectsInvalidInputsAndUnauthorizedUploads() {
        assertFails {ReceiptAppearance(style="arbitrary")}
        assertFails {ReceiptAppearance(frame="fake")}
        assertFails {ReceiptAppearance(accentHex="#ZZ0000")}
        assertFails {ReceiptAppearance(policy="text\nmore")}
        assertFails {ReceiptAppearance(artworkAssetId="/tmp/file.png")}
        PosRepository(":memory:").use {repo ->
            val shop=repo.createTenant("Shop")
            val source=repo.exportTenantSnapshot(shop.id)
            repo.setPermissionCheck {_,permission ->require(permission==StaffPermission.BACKUP)}
            assertFails {repo.saveReceiptArtwork(shop.id,artwork())}
            assertEquals(source,repo.exportTenantSnapshot(shop.id))
        }
    }
}
