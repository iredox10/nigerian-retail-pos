package pos.android.documents

import pos.data.*
import pos.domain.*

/** Layout-only sample. Never insert these items, amounts or the sample sale into shop records. */
fun androidReceiptDesignSource(details: ShopDetails, logo: ByteArray?, artwork: ByteArray?): AndroidReceiptSource {
    val sampleShop = "receipt-design-preview"
    val soap = Product("preview-soap", sampleShop, "Laundry soap · Sample item", "piece", 45000, 3.0)
    val malt = Product("preview-malt", sampleShop, "Malt drink · Sample item", "bottle", 50000, 3.0)
    val sample = Sale("DESIGN-PREVIEW", sampleShop, listOf(CartLine(soap, 2), CartLine(malt, 1)),
        140000, 140000, 0, null, "2026-01-01T09:00:00Z", cashGivenMinor = 150000,
        note = "Sample layout only · Not a recorded transaction")
    return AndroidReceiptSource(sample, details, logo, artwork, designPreview = true)
}

/** A stale/rejected settings review must not leave a newly imported artwork record behind. */
fun PosRepository.saveReviewedAndroidReceiptDesign(tenantId: String, details: ShopDetails, artwork: ImageUpload?, expectedVersion: Long, reason: String, operationId: String): ShopProfile =
    featureWrite(tenantId, StaffPermission.MANAGE_SETTINGS) {
        val asset = artwork?.let { saveReceiptArtwork(tenantId, it) } ?: details.appearance.artworkAssetId
        saveShopProfile(tenantId, details.copy(appearance = details.appearance.copy(artworkAssetId = asset)), expectedVersion, reason, operationId)
    }
