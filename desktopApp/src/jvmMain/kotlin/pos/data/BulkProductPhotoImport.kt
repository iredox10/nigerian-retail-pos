package pos.data

import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.util.Locale
import java.util.UUID
import pos.domain.Product
import pos.domain.StaffPermission

private const val MAX_PHOTOS = 64

data class ProductPhotoCandidate(
    val product: Product,
    val current: LocalImageSelection,
    val image: ImageUpload,
)

data class ProductPhotoImportRow(
    val filename: String,
    val candidate: ProductPhotoCandidate? = null,
    val skippedReason: String? = null,
)

data class ProductPhotoImport(val tenantId: String, val rows: List<ProductPhotoImportRow>)

/** Match only active catalogue items by their own SKU or barcode, using the complete filename stem. */
fun PosRepository.reviewProductPhotoFolder(tenantId: String, folder: Path): ProductPhotoImport =
    featureRead(tenantId, StaffPermission.MANAGE_STOCK) {
        val files = Files.list(folder).use { paths ->
            paths.filter {
                Files.isRegularFile(it, NOFOLLOW_LINKS) &&
                    it.fileName.toString().substringAfterLast('.', "").lowercase(Locale.ROOT) in setOf("png", "jpg", "jpeg")
            }.limit((MAX_PHOTOS + 1).toLong()).toList()
        }
        require(files.size <= MAX_PHOTOS) { "Choose a folder with at most $MAX_PHOTOS PNG or JPEG files." }
        val codes = cataloguePhotoCodes(products(tenantId))
        val matches = files.sortedBy { it.fileName.toString().lowercase(Locale.ROOT) }
            .associateWith { codes[photoKey(it.fileName.toString())].orEmpty() }
        val countByProduct = matches.values.mapNotNull { it.singleOrNull()?.id }.groupingBy { it }.eachCount()
        val rows = matches.map { (file, matching) ->
            val filename = file.fileName.toString()
            val product = matching.singleOrNull()
            when {
                matching.isEmpty() -> ProductPhotoImportRow(filename, skippedReason = "No active item matches this SKU or barcode.")
                product == null -> ProductPhotoImportRow(filename, skippedReason = "This code matches more than one item.")
                countByProduct.getValue(product.id) > 1 -> ProductPhotoImportRow(filename, skippedReason = "More than one file matches ${product.name}.")
                else -> {
                    val current = selectionUnchecked(tenantId, "product", product.id)
                    runCatching { ImageUpload.fromFile(file) }.fold(
                        onSuccess = { image ->
                            if (image.assetId == current.assetId) ProductPhotoImportRow(filename, skippedReason = "${product.name} already has this photo.")
                            else ProductPhotoImportRow(filename, ProductPhotoCandidate(product, current, image))
                        },
                        onFailure = { ProductPhotoImportRow(filename, skippedReason = "The file could not be read as a supported PNG or JPEG.") },
                    )
                }
            }
        }
        ProductPhotoImport(tenantId, rows)
    }

/** Use the existing image revision transaction, with stable operation references for the reviewed batch. */
fun PosRepository.importProductPhotos(
    tenantId: String, preview: ProductPhotoImport, selected: Set<String>, reason: String,
): Int = featureWrite(tenantId, StaffPermission.MANAGE_STOCK) {
    require(preview.tenantId == tenantId) { "These photos were reviewed in another shop." }
    val rows = preview.rows.filter { it.filename in selected }.sortedBy { it.filename }
    require(rows.size in 1..MAX_PHOTOS && rows.map { it.filename }.toSet() == selected &&
        rows.size == selected.size && rows.all { it.candidate?.product?.tenantId == tenantId }) {
        "Choose photos that match items in this shop."
    }
    val approved = rows.map { it.filename to requireNotNull(it.candidate) }
    require(approved.map { it.second.product.id }.distinct().size == approved.size) { "Choose one photo per item." }
    val cleanReason = reason.trim()
    require(cleanReason.length in 3..250 && cleanReason.none { it.isISOControl() }) {
        "Enter a reason from 3 to 250 printable characters."
    }
    val codes = cataloguePhotoCodes(products(tenantId))
    val request = approved.flatMap { (filename, photo) ->
        require(codes[photoKey(filename)]?.singleOrNull()?.id == photo.product.id) {
            "The match for ${photo.product.name} changed. Review the folder again."
        }
        listOf(filename, photo.product.id, photo.current.version.toString(), photo.image.assetId)
    }
    val batchKey = (listOf(cleanReason) + request).joinToString("") { "${it.length}:$it" }
    for ((_, photo) in approved) {
        val operationId = UUID.nameUUIDFromBytes("product-photo-import:$batchKey:${photo.product.id}".toByteArray()).toString()
        try {
            saveImage(tenantId, "product", photo.product.id, photo.image, photo.current.version, cleanReason, operationId)
        } catch (failure: IllegalArgumentException) {
            throw IllegalArgumentException("${photo.product.name}. ${failure.message}", failure)
        }
    }
    approved.size
}

private fun cataloguePhotoCodes(products: List<Product>): Map<String, List<Product>> =
    products.flatMap { product ->
        listOfNotNull(product.sku, product.barcode).map { it.lowercase(Locale.ROOT) }.distinct().map { it to product }
    }.groupBy({ it.first }, { it.second })

private fun photoKey(filename: String): String = filename.substringBeforeLast('.').lowercase(Locale.ROOT)
