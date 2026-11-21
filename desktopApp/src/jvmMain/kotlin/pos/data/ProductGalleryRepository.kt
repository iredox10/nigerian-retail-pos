package pos.data

import pos.domain.StaffPermission
import java.sql.ResultSet
import java.time.Instant
import java.util.UUID

const val PRODUCT_GALLERY_LIMIT = 6
private const val PHOTO_EVENT = "product.photo_gallery_changed"

data class ProductGalleryChange(
    val id: String,
    val productId: String,
    val photoId: String,
    val assetId: String?,
    val version: Long,
    val reason: String,
    val recordedAt: Instant,
)

internal fun PosRepository.initializeProductGallery() {
    featureSchema(
        """CREATE TABLE IF NOT EXISTS product_gallery_events(
            tenant_id TEXT NOT NULL, id TEXT NOT NULL, product_id TEXT NOT NULL,
            photo_id TEXT NOT NULL, asset_id TEXT, version INTEGER NOT NULL CHECK(version>0),
            reason TEXT NOT NULL, created_at TEXT NOT NULL,
            PRIMARY KEY(tenant_id,id), UNIQUE(tenant_id,product_id,photo_id,version),
            FOREIGN KEY(tenant_id,product_id) REFERENCES products(tenant_id,id),
            FOREIGN KEY(tenant_id,asset_id) REFERENCES local_image_assets(tenant_id,id))"""
    )
    for (action in listOf("UPDATE", "DELETE")) {
        featureSchema(
            """CREATE TRIGGER IF NOT EXISTS product_gallery_no_${action.lowercase()} BEFORE $action ON product_gallery_events
                BEGIN SELECT RAISE(ABORT,'Saved photo history cannot be changed'); END"""
        )
    }
}

fun PosRepository.productGalleryHistory(tenantId: String, productId: String): List<ProductGalleryChange> =
    featureRead(tenantId, StaffPermission.MANAGE_STOCK) {
        galleryProduct(tenantId, productId)
        galleryEvents(tenantId, productId)
    }

fun PosRepository.saveGalleryPhoto(
    tenantId: String,
    productId: String,
    photoId: String?,
    upload: ImageUpload?,
    expectedVersion: Long,
    reason: String,
    operationId: String = UUID.randomUUID().toString(),
): ProductGalleryChange = featureWrite(tenantId, StaffPermission.MANAGE_STOCK) {
    val event = ProductGalleryChange(operationId, productId, photoId ?: operationId, upload?.assetId,
        Math.addExact(expectedVersion, 1), reason, Instant.now())
    validateGalleryChange(event)
    require((photoId == null) == (expectedVersion == 0L)) { "Photo review reference is invalid." }
    val replay = featureQuery("SELECT * FROM product_gallery_events WHERE tenant_id=? AND id=?",
        tenantId, operationId, map = ::galleryEvent).singleOrNull()
    if (replay != null) {
        require(event.copy(recordedAt = replay.recordedAt) == replay) { "This photo change was already saved with different details." }
        return@featureWrite replay
    }
    require(!galleryProduct(tenantId, productId)) { "Restore this item before changing its photos." }
    val history = galleryEvents(tenantId, productId)
    val current = history.associateBy { it.photoId }.toMutableMap()
    val latest = history.lastOrNull()
    require(latest == null || event.recordedAt > latest.recordedAt) { "Correct the computer clock before changing this photo." }
    applyGalleryChange(current, event)
    if (upload != null) saveLocalImageAsset(tenantId, upload)
    featureExecute(
        """INSERT INTO product_gallery_events
            (tenant_id,id,product_id,photo_id,asset_id,version,reason,created_at) VALUES(?,?,?,?,?,?,?,?)""",
        tenantId, event.id, event.productId, event.photoId, event.assetId, event.version, event.reason, event.recordedAt.toString()
    )
    featureEvent(tenantId, PHOTO_EVENT, event.id)
    event
}

private fun PosRepository.galleryProduct(tenantId: String, productId: String): Boolean =
    featureQuery("SELECT archived FROM products WHERE tenant_id=? AND id=?", tenantId, productId) { it.getInt(1) != 0 }
        .singleOrNull() ?: error("This item is unavailable in this shop.")

private fun PosRepository.galleryEvents(tenantId: String, productId: String? = null): List<ProductGalleryChange> =
    (if (productId == null) featureQuery("SELECT * FROM product_gallery_events WHERE tenant_id=?", tenantId, map = ::galleryEvent)
    else featureQuery("SELECT * FROM product_gallery_events WHERE tenant_id=? AND product_id=?",
        tenantId, productId, map = ::galleryEvent)).sortedBy { it.recordedAt }

private fun galleryEvent(row: ResultSet) = ProductGalleryChange(
    row.getString("id"), row.getString("product_id"), row.getString("photo_id"), row.getString("asset_id"),
    row.getLong("version"), row.getString("reason"), Instant.parse(row.getString("created_at")),
)

private fun validateGalleryChange(event: ProductGalleryChange) {
    require(event.id.length in 1..200 && event.id.none { it.isISOControl() } && event.version > 0) {
        "Photo change reference is invalid."
    }
    require(event.reason == event.reason.trim() && event.reason.length in 3..250 && event.reason.none { it.isISOControl() }) {
        "Enter a reason from 3 to 250 printable characters."
    }
}

private fun applyGalleryChange(current: MutableMap<String, ProductGalleryChange>, event: ProductGalleryChange) {
    val previous = current[event.photoId]
    if (previous == null) {
        require(event.id == event.photoId && event.version == 1L && event.assetId != null) { "This photo has no saved starting revision." }
    } else {
        require(previous.assetId != null && event.version == previous.version + 1L) {
            "This photo changed while the form was open. Reload and review it."
        }
        require(previous.assetId != event.assetId) { "Choose a different photo before saving." }
    }
    require(event.assetId == null || current.values.none { it.photoId != event.photoId && it.assetId == event.assetId }) {
        "This image is already in the gallery."
    }
    current[event.photoId] = event
    require(current.values.count { it.assetId != null } <= PRODUCT_GALLERY_LIMIT) {
        "A product can have up to $PRODUCT_GALLERY_LIMIT additional photos."
    }
}

internal fun PosRepository.validateProductGallery(tenantId: String): Set<String> {
    val history = galleryEvents(tenantId)
    history.groupBy { it.productId }.forEach { (_, events) ->
        val current = mutableMapOf<String, ProductGalleryChange>()
        events.forEachIndexed { index, event ->
            validateGalleryChange(event)
            require(index == 0 || event.recordedAt > events[index - 1].recordedAt) { "Backup photo changes are out of order." }
            applyGalleryChange(current, event)
        }
    }
    val witnesses = featureQuery(
        """SELECT o.entity_id,o.created_at,a.id FROM outbox o
           LEFT JOIN activity_audit a ON a.tenant_id=o.tenant_id AND a.id=o.id
           WHERE o.tenant_id=? AND o.kind=?""", tenantId, PHOTO_EVENT
    ) {
        requireNotNull(it.getString(3)) { "Backup photo change has no activity history." }
        it.getString(1) to Instant.parse(it.getString(2))
    }
    val witnessedAt = witnesses.toMap()
    require(witnesses.size == history.size && history.all { event ->
        witnessedAt[event.id]?.let { it >= event.recordedAt } == true
    }) { "Backup photo gallery activity is incomplete." }
    return history.mapNotNull { it.assetId }.toSet()
}
