package pos.output

import pos.data.PosRepository
import pos.data.imageBytes
import pos.data.receiptImageBytes
import pos.data.receiptProfile
import pos.domain.ReceiptAppearance

/** Reads the completed sale and its captured design before preparing a printable copy. */
fun savedReceiptPdf(repo: PosRepository, tenantId: String, saleId: String): ByteArray {
    val shop = repo.tenants().single { it.id == tenantId }
    val sale = requireNotNull(repo.sales(tenantId).singleOrNull { it.id == saleId }) {
        "The saved receipt could not be found. Open Sales to review it."
    }
    val profile = repo.receiptProfile(tenantId, saleId)
    val appearance = profile?.details?.appearance ?: ReceiptAppearance()
    val text = BrandedReceiptService.format(shop, sale, repo.saleReturns(tenantId, saleId), profile)
    return PdfReceiptService.createPdf(text, profile?.details?.receiptPaper ?: "A4",
        repo.receiptImageBytes(tenantId, saleId), appearance,
        appearance.artworkAssetId?.let { repo.imageBytes(tenantId, it) })
}
