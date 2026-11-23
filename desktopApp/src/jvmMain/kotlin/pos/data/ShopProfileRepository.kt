package pos.data

import pos.domain.*
import java.io.*
import java.sql.ResultSet
import java.time.Instant
import java.util.Base64
import java.util.UUID

internal val shopProfileSnapshotTables=listOf("shop_profiles","shop_profile_changes","sale_receipt_profiles")

internal fun PosRepository.initializeShopProfiles() {
    val saleColumns=featureQuery("PRAGMA table_info(sales)") {it.getString("name")}
    if("receipt_profile_captured" !in saleColumns) featureSchema("ALTER TABLE sales ADD COLUMN receipt_profile_captured INTEGER NOT NULL DEFAULT 0 CHECK(receipt_profile_captured IN(0,1))")
    featureSchema("CREATE TABLE IF NOT EXISTS shop_profiles(tenant_id TEXT PRIMARY KEY REFERENCES tenants(id),details TEXT NOT NULL,version INTEGER NOT NULL CHECK(version>0),updated_at TEXT NOT NULL)")
    featureSchema("CREATE TABLE IF NOT EXISTS shop_profile_changes(id TEXT NOT NULL,tenant_id TEXT NOT NULL REFERENCES tenants(id),previous_details TEXT,current_details TEXT NOT NULL,version INTEGER NOT NULL CHECK(version>0),reason TEXT NOT NULL,created_at TEXT NOT NULL,PRIMARY KEY(tenant_id,id),UNIQUE(tenant_id,version))")
    featureSchema("CREATE TABLE IF NOT EXISTS sale_receipt_profiles(tenant_id TEXT NOT NULL,sale_id TEXT NOT NULL,details TEXT NOT NULL,profile_version INTEGER NOT NULL CHECK(profile_version>=0),captured_at TEXT NOT NULL,PRIMARY KEY(tenant_id,sale_id),FOREIGN KEY(tenant_id,sale_id) REFERENCES sales(tenant_id,id))")
    listOf("shop_profile_changes","sale_receipt_profiles").forEach { table ->
        featureSchema("CREATE TRIGGER IF NOT EXISTS ${table}_no_update BEFORE UPDATE ON $table BEGIN SELECT RAISE(ABORT,'Receipt identity history cannot be edited'); END")
        featureSchema("CREATE TRIGGER IF NOT EXISTS ${table}_no_delete BEFORE DELETE ON $table BEGIN SELECT RAISE(ABORT,'Receipt identity history cannot be removed'); END")
    }
}

fun PosRepository.shopProfile(tenantId:String):ShopProfile?=featureRead(tenantId,StaffPermission.MANAGE_SETTINGS) {
    featureQuery("SELECT * FROM shop_profiles WHERE tenant_id=?",tenantId,map=::shopProfileRow).singleOrNull()
}
fun PosRepository.shopProfileHistory(tenantId:String):List<ShopProfileChange> =featureRead(tenantId,StaffPermission.MANAGE_SETTINGS) {
    featureQuery("SELECT * FROM shop_profile_changes WHERE tenant_id=? ORDER BY version",tenantId,map=::shopChangeRow)
}
fun PosRepository.saveShopProfile(tenantId:String,details:ShopDetails,expectedVersion:Long,reason:String,operationId:String=UUID.randomUUID().toString()):ShopProfile=featureWrite(tenantId,StaffPermission.MANAGE_SETTINGS) {
    require(operationId.isNotBlank() && operationId.length<=200) {"Use a valid settings operation reference."}
    require(expectedVersion>=0)
    val replay=featureQuery("SELECT * FROM shop_profile_changes WHERE tenant_id=? AND id=?",tenantId,operationId,map=::shopChangeRow).singleOrNull()
    if(replay!=null) {
        require(replay.current==details && replay.version==Math.addExact(expectedVersion,1) && replay.reason==reason) {"This settings request was already saved with different details."}
        return@featureWrite requireNotNull(featureQuery("SELECT * FROM shop_profiles WHERE tenant_id=?",tenantId,map=::shopProfileRow).singleOrNull())
    }
    val old=featureQuery("SELECT * FROM shop_profiles WHERE tenant_id=?",tenantId,map=::shopProfileRow).singleOrNull()
    require((old?.version ?: 0)==expectedVersion) {"Shop settings changed while you were editing. Reload and review them."}
    require(old?.details!=details) {"Change a receipt detail before saving."}
    val now=Instant.now()
    validateReceiptArtwork(tenantId, details.appearance.artworkAssetId, now)
    require(old==null || !now.isBefore(Instant.parse(old.updatedAt))) {"The computer clock moved backwards. Correct it before changing shop details."}
    val change=ShopProfileChange(operationId,tenantId,old?.details,details,Math.addExact(expectedVersion,1),reason,now.toString())
    featureExecute("INSERT INTO shop_profiles VALUES(?,?,?,?) ON CONFLICT(tenant_id) DO UPDATE SET details=excluded.details,version=excluded.version,updated_at=excluded.updated_at",tenantId,encodeShopDetails(details),change.version,change.createdAt)
    featureExecute("INSERT INTO shop_profile_changes VALUES(?,?,?,?,?,?,?)",operationId,tenantId,old?.details?.let(::encodeShopDetails),encodeShopDetails(details),change.version,reason,change.createdAt)
    featureEvent(tenantId,"shop.receipt_settings_changed",operationId)
    ShopProfile(tenantId,details,change.version,change.createdAt)
}

/** Internal checkout hook: caller already owns the sale transaction and SELL permission. */
internal fun PosRepository.captureReceiptProfile(tenantId:String,saleId:String):ReceiptProfileSnapshot? {
    val saleTime=featureQuery("SELECT created_at FROM sales WHERE tenant_id=? AND id=?",tenantId,saleId) {it.getString(1)}.singleOrNull() ?: error("Receipt sale does not belong to this shop.")
    val existing=featureQuery("SELECT * FROM sale_receipt_profiles WHERE tenant_id=? AND sale_id=?",tenantId,saleId,map=::receiptProfileRow).singleOrNull()
    if(existing!=null) return existing
    val profile=featureQuery("SELECT * FROM shop_profiles WHERE tenant_id=?",tenantId,map=::shopProfileRow).singleOrNull()
    val name=featureQuery("SELECT name FROM tenants WHERE id=?",tenantId) {it.getString(1)}.single()
    // Legacy workspace names were unrestricted. Do not block an ordinary sale or
    // silently truncate its identity when it cannot become a new configured profile.
    val details=profile?.details ?: runCatching {ShopDetails(name)}.getOrNull() ?: return null
    require(profile==null || !Instant.parse(saleTime).isBefore(Instant.parse(profile.updatedAt))) {"Correct the computer clock before using the saved receipt settings."}
    val snapshot=ReceiptProfileSnapshot(tenantId,saleId,details,profile?.version ?: 0,saleTime)
    featureExecute("INSERT INTO sale_receipt_profiles VALUES(?,?,?,?,?)",tenantId,saleId,encodeShopDetails(snapshot.details),snapshot.profileVersion,saleTime)
    featureExecute("UPDATE sales SET receipt_profile_captured=1 WHERE tenant_id=? AND id=?",tenantId,saleId)
    return snapshot
}
fun PosRepository.receiptProfile(tenantId:String,saleId:String):ReceiptProfileSnapshot?=featureRead(tenantId,StaffPermission.VIEW_SALES) {
    require(featureQuery("SELECT id FROM sales WHERE tenant_id=? AND id=?",tenantId,saleId) {it.getString(1)}.isNotEmpty()) {"This receipt belongs to another shop or is unavailable."}
    featureQuery("SELECT * FROM sale_receipt_profiles WHERE tenant_id=? AND sale_id=?",tenantId,saleId,map=::receiptProfileRow).singleOrNull()
}

/** Restore validation uses the already-authorized recovery transaction, not a new-tenant session. */
internal fun PosRepository.validateShopProfileSnapshot(tenantId:String) {
    val profiles=featureQuery("SELECT * FROM shop_profiles WHERE tenant_id=?",tenantId,map=::shopProfileRow)
    val changes=featureQuery("SELECT * FROM shop_profile_changes WHERE tenant_id=? ORDER BY version",tenantId,map=::shopChangeRow)
    require(profiles.size<=1 && (profiles.isEmpty()==changes.isEmpty())) {"Backup shop settings have incomplete history."}
    changes.forEachIndexed { index,event ->
        Instant.parse(event.createdAt)
        require(event.version==index.toLong()+1 && (index==0 || event.previous==changes[index-1].current)) {"Backup shop settings history is inconsistent."}
        require(index==0 || !Instant.parse(event.createdAt).isBefore(Instant.parse(changes[index-1].createdAt))) {"Backup shop settings dates are inconsistent."}
    }
    profiles.singleOrNull()?.let {profile ->
        val last=changes.last()
        require(profile.version==last.version && profile.details==last.current && profile.updatedAt==last.createdAt) {"Backup shop settings do not match their latest history."}
    }
    val snapshots=featureQuery("SELECT * FROM sale_receipt_profiles WHERE tenant_id=?",tenantId,map=::receiptProfileRow)
    val sales=featureQuery("SELECT id,created_at,receipt_profile_captured FROM sales WHERE tenant_id=?",tenantId) {Triple(it.getString(1),it.getString(2),it.getInt(3)==1)}
    require(sales.filter {it.third}.map {it.first}.toSet()==snapshots.map {it.saleId}.toSet()) {"Backup receipt identity snapshots are incomplete."}
    snapshots.forEach {snapshot ->
        val sale=requireNotNull(sales.singleOrNull {it.first==snapshot.saleId}) {"Backup receipt identity has no sale."}
        require(snapshot.capturedAt==sale.second) {"Backup receipt identity date differs from its sale."}
        if(snapshot.profileVersion>0) {
            val source=requireNotNull(changes.singleOrNull {it.version==snapshot.profileVersion}) {"Backup receipt identity has no source settings."}
            require(source.current==snapshot.details && !Instant.parse(source.createdAt).isAfter(Instant.parse(snapshot.capturedAt))) {"Backup receipt identity differs from its captured settings."}
        } else require(snapshot.details.phone==null && snapshot.details.address==null && snapshot.details.footer==null && snapshot.details.receiptPaper=="A4" && snapshot.details.appearance==ReceiptAppearance()) {"Default receipt identities cannot invent contact details."}
    }
    changes.forEach { validateReceiptArtwork(tenantId, it.current.appearance.artworkAssetId, Instant.parse(it.createdAt)) }
    snapshots.forEach { validateReceiptArtwork(tenantId, it.details.appearance.artworkAssetId, Instant.parse(it.capturedAt)) }
    val events=featureQuery("SELECT entity_id FROM outbox WHERE tenant_id=? AND kind='shop.receipt_settings_changed'",tenantId) {it.getString(1)}
    require(events.toSet()==changes.map {it.id}.toSet()) {"Backup shop settings are missing source history."}
}

private fun shopProfileRow(row:ResultSet)=ShopProfile(row.getString("tenant_id"),decodeShopDetails(row.getString("details")),row.getLong("version"),row.getString("updated_at"))
private fun shopChangeRow(row:ResultSet)=ShopProfileChange(row.getString("id"),row.getString("tenant_id"),row.getString("previous_details")?.let(::decodeShopDetails),decodeShopDetails(row.getString("current_details")),row.getLong("version"),row.getString("reason"),row.getString("created_at"))
private fun receiptProfileRow(row:ResultSet)=ReceiptProfileSnapshot(row.getString("tenant_id"),row.getString("sale_id"),decodeShopDetails(row.getString("details")),row.getLong("profile_version"),row.getString("captured_at"))
private fun encodeShopDetails(value:ShopDetails):String {
    val bytes=ByteArrayOutputStream()
    DataOutputStream(bytes).use { out ->
        out.writeInt(2)
        listOf(value.displayName,value.phone,value.address,value.footer,value.receiptPaper).forEach { out.writeNullableText(it) }
        val appearance = value.appearance
        listOf(appearance.style, appearance.frame, appearance.accentHex, appearance.headerAlignment).forEach { out.writeUTF(it) }
        out.writeBoolean(appearance.showLogo)
        listOf(appearance.artworkAssetId, appearance.tagline, appearance.policy).forEach { out.writeNullableText(it) }
    }
    return Base64.getEncoder().encodeToString(bytes.toByteArray())
}
private fun DataOutputStream.writeNullableText(value:String?) {
    writeBoolean(value!=null)
    if(value!=null) writeUTF(value)
}
private fun DataInputStream.readNullableText():String? = if(readBoolean()) readUTF() else null

private fun decodeShopDetails(value:String):ShopDetails {
    require(value.length<=20_000) {"Receipt settings are too large."}
    return DataInputStream(ByteArrayInputStream(Base64.getDecoder().decode(value))).use { input ->
        val version = input.readInt()
        require(version in 1..2) {"Receipt settings format is unsupported."}
        val fields=List(5) { input.readNullableText() }
        val appearance = if(version == 1) ReceiptAppearance() else ReceiptAppearance(
            style=input.readUTF(), frame=input.readUTF(), accentHex=input.readUTF(), headerAlignment=input.readUTF(),
            showLogo=input.readBoolean(), artworkAssetId=input.readNullableText(), tagline=input.readNullableText(), policy=input.readNullableText(),
        )
        require(input.read()==-1) {"Receipt settings contain unsupported data."}
        ShopDetails(requireNotNull(fields[0]),fields[1],fields[2],fields[3],requireNotNull(fields[4]),appearance)
    }
}

private fun PosRepository.validateReceiptArtwork(tenantId:String, assetId:String?, through:Instant) {
    if(assetId == null) return
    val imported=featureQuery("SELECT created_at FROM receipt_artwork_imports WHERE tenant_id=? AND asset_id=?",tenantId,assetId) {Instant.parse(it.getString(1))}.singleOrNull()
    require(imported!=null && !imported.isAfter(through)) {
        "Receipt artwork is unavailable at the recorded time in this shop. Import the image again or correct the clock."
    }
}
