package pos.data

import kotlin.test.*
import pos.domain.*
import pos.output.BackupService
import java.nio.file.Files
import java.sql.DriverManager

class BulkCatalogueUpdateIntegrityTest {
    private fun PosRepository.fixture():Triple<Tenant,Product,Product> {
        initializeBulkCatalogueUpdates()
        val shop=createTenant("Catalogue changes")
        val rice=addProduct(shop.id,"Rice","kg",10000,10.25,"barcode-rice",quantityPrecision=2)
        val soap=addProduct(shop.id,"Soap","piece",5000,20.0,"barcode-soap")
        updateProductMetadata(shop.id,rice.id,ProductMetadata(sku="SKU-RICE"),"rice-metadata")
        updateProductMetadata(shop.id,soap.id,ProductMetadata(sku="SKU-SOAP"),"soap-metadata")
        return Triple(shop,rice,soap)
    }
    @Test fun reviewedIdentifierSwapsAndRepricingKeepStockAndOriginalReceipts() {
        PosRepository(":memory:").use {repo ->
            val(shop,rice,soap)=repo.fixture()
            val exported=repo.bulkCatalogueTemplateRows(shop.id)
            val sale=repo.checkout(shop.id,listOf(CartLine(rice,100)),10000,operationId="old-sale")
            val rows=exported.map {row ->if(row.productId==rice.id) row.copy(values=row.values.copy(name="Premium rice",priceMinor=12000,barcode="barcode-soap",metadata=ProductMetadata("SKU-SOAP","Food","Market",listOf("Local rice"),true),reorderLevel=2)) else row.copy(values=row.values.copy(barcode="barcode-rice",metadata=ProductMetadata("SKU-RICE","Household")))}
            val preview=repo.previewBulkCatalogueUpdates(shop.id,rows)
            assertTrue(preview.canApply,preview.issues.toString());assertEquals(2,preview.changedCount)
            val batch=repo.applyBulkCatalogueUpdates(shop.id,rows,"Reviewed market prices","market-prices")
            val goods=repo.products(shop.id).associateBy {it.id}
            assertEquals("barcode-soap",goods.getValue(rice.id).barcode)
            assertEquals("SKU-SOAP",goods.getValue(rice.id).sku)
            assertEquals("barcode-rice",goods.getValue(soap.id).barcode)
            assertEquals("SKU-RICE",goods.getValue(soap.id).sku)
            assertEquals("9.25",goods.getValue(rice.id).stockLabel)
            assertEquals(20.0,goods.getValue(soap.id).stock)
            assertEquals(10000L,repo.sales(shop.id).single().totalMinor)
            assertEquals(sale.lines.single().product.name,repo.sales(shop.id).single().lines.single().product.name)
            assertEquals(2,batch.rows.size)
            repo.validateBulkCatalogueUpdates(shop.id)
        }
    }
    @Test fun changedThenRestoredCatalogueValuesStillInvalidateExportedVersion() {
        PosRepository(":memory:").use {repo ->
            val(shop,rice,_)=repo.fixture()
            val rows=repo.bulkCatalogueTemplateRows(shop.id).map {it.copy(values=it.values.copy(priceMinor=it.values.priceMinor+100))}
            repo.updateProduct(shop.id,rice.id,rice.name,rice.unit,11000,rice.barcode,"temporary-price")
            repo.updateProduct(shop.id,rice.id,rice.name,rice.unit,10000,rice.barcode,"restore-price")
            val before=repo.exportTenantSnapshot(shop.id)
            val preview=repo.previewBulkCatalogueUpdates(shop.id,rows)
            assertFalse(preview.canApply)
            assertTrue(preview.issues.any {it.record==rows.single {r->r.productId==rice.id}.record})
            assertFails {repo.applyBulkCatalogueUpdates(shop.id,rows,"Stale exported prices")}
            assertEquals(before,repo.exportTenantSnapshot(shop.id))
        }
    }
    @Test fun packBarcodeSkuUnitAndCrossShopConflictsArePreviewErrors() {
        PosRepository(":memory:").use {repo ->
            val(shop,rice,soap)=repo.fixture()
            repo.addSellingUnit(shop.id,soap.id,"box",2,9000,"pack-barcode")
            val other=repo.createTenant("Other")
            val row=repo.bulkCatalogueTemplateRows(shop.id).single {it.productId==rice.id}
            val bad=row.copy(values=row.values.copy(unit="bag",barcode="pack-barcode",metadata=ProductMetadata("sku-soap")))
            val preview=repo.previewBulkCatalogueUpdates(shop.id,listOf(bad))
            assertFalse(preview.canApply);assertTrue(preview.issues.size>=3)
            assertFalse(repo.previewBulkCatalogueUpdates(other.id,listOf(row)).canApply)
            val restored=repo.restoreTenantSnapshot(repo.exportTenantSnapshot(shop.id),"Recovered shop")
            val foreignFile=repo.previewBulkCatalogueUpdates(restored.id,listOf(row.copy(values=row.values.copy(priceMinor=row.values.priceMinor+1))))
            assertFalse(foreignFile.canApply,"Restored shops retain product IDs, but an exported review version must remain shop-specific")
            assertTrue(foreignFile.issues.isNotEmpty())
            val before=repo.exportTenantSnapshot(shop.id)
            assertFails {repo.applyBulkCatalogueUpdates(shop.id,listOf(bad),"Bad identifiers")}
            assertEquals(before,repo.exportTenantSnapshot(shop.id))
        }
    }
    @Test fun clearingOptionalIdentifiersHasValidHistoryAndEncryptedBackup() {
        val directory=Files.createTempDirectory("bulk-catalogue-backup-")
        try {
            PosRepository(":memory:").use {repo ->
                val(shop,rice,_)=repo.fixture()
                val row=repo.bulkCatalogueTemplateRows(shop.id).single {it.productId==rice.id}
                repo.applyBulkCatalogueUpdates(shop.id,listOf(row.copy(values=row.values.copy(barcode=null,metadata=ProductMetadata()))),"Remove obsolete identifiers","clear-identifiers")
                repo.validateBulkCatalogueUpdates(shop.id)
                val backup=directory.resolve("shop.retailbackup")
                BackupService.create(repo,shop.id,"sample-backup-password",backup)
                val snapshot=BackupService.read(backup,"sample-backup-password")
                assertTrue(snapshot.tables.containsKey("catalogue_update_batches"),"Root must wire bulk catalogue backup tables")
                val restored=repo.restoreTenantSnapshot(snapshot,"Recovered catalogue")
                assertEquals(1,repo.bulkCatalogueBatches(restored.id).size)
                assertNull(repo.products(restored.id).single {it.id==rice.id}.barcode)
                assertNull(repo.products(restored.id).single {it.id==rice.id}.sku)
                val before=repo.tenants()
                assertFails {repo.restoreTenantSnapshot(snapshot.copy(tables=snapshot.tables-bulkCatalogueUpdateSnapshotTables.toSet()),"Missing batch history")}
                val corrupt=snapshot.copy(tables=snapshot.tables+("catalogue_update_batch_rows" to snapshot.tables.getValue("catalogue_update_batch_rows").map {it+("before_values" to it.getValue("after_values"))}))
                assertFails {repo.restoreTenantSnapshot(corrupt,"Corrupt batch history")}
                assertEquals(before,repo.tenants())
            }
        } finally {directory.toFile().deleteRecursively()}
    }
    @Test fun failureAfterEarlierRowsRollsBackIdentifierReleasesAndAllChanges() {
        val file=Files.createTempFile("bulk-catalogue-atomic-",".db")
        try {
            PosRepository(file.toString()).use {repo ->
                val(shop,rice,soap)=repo.fixture()
                val rows=repo.bulkCatalogueTemplateRows(shop.id).map {row ->row.copy(values=row.values.copy(priceMinor=row.values.priceMinor+2000,barcode=if(row.productId==rice.id) soap.barcode else rice.barcode))}
                DriverManager.getConnection("jdbc:sqlite:$file").use {db ->db.createStatement().use {it.execute("CREATE TRIGGER reject_second_bulk_price BEFORE UPDATE ON products WHEN NEW.id='${soap.id}' AND NEW.price_minor<>OLD.price_minor BEGIN SELECT RAISE(ABORT,'injected second-row failure'); END")}}
                val before=repo.exportTenantSnapshot(shop.id)
                assertFails {repo.applyBulkCatalogueUpdates(shop.id,rows,"Price and barcode update")}
                assertEquals(before,repo.exportTenantSnapshot(shop.id))
                assertTrue(repo.bulkCatalogueBatches(shop.id).isEmpty())
            }
        } finally {Files.deleteIfExists(file)}
    }
    @Test fun duplicateReviewRecordsBlockBeforeAnyCatalogueChange() {
        PosRepository(":memory:").use {repo ->
            val(shop,_,_)=repo.fixture()
            val rows=repo.bulkCatalogueTemplateRows(shop.id).map {it.copy(record=2,values=it.values.copy(priceMinor=it.values.priceMinor+1))}
            val before=repo.exportTenantSnapshot(shop.id)
            val preview=repo.previewBulkCatalogueUpdates(shop.id,rows)
            assertFalse(preview.canApply)
            assertTrue(preview.issues.any {it.record==2 && it.message.contains("Record number")})
            assertFails {repo.applyBulkCatalogueUpdates(shop.id,rows,"Duplicated review records")}
            assertEquals(before,repo.exportTenantSnapshot(shop.id))
        }
    }
    @Test fun batchHeadersAndRowsCannotBeEditedOrDeletedDirectly() {
        val file=Files.createTempFile("bulk-catalogue-immutable-",".db")
        try {
            PosRepository(file.toString()).use {repo ->
                val(shop,_,_)=repo.fixture()
                val rows=repo.bulkCatalogueTemplateRows(shop.id).map {it.copy(values=it.values.copy(priceMinor=it.values.priceMinor+1))}
                repo.applyBulkCatalogueUpdates(shop.id,rows,"New shelf prices","immutable-batch")
                val before=repo.exportTenantSnapshot(shop.id)
                DriverManager.getConnection("jdbc:sqlite:$file").use {db ->
                    db.createStatement().use {statement ->
                        assertFails {statement.executeUpdate("UPDATE catalogue_update_batches SET reason='Changed without history'")}
                        assertFails {statement.executeUpdate("DELETE FROM catalogue_update_batches")}
                        assertFails {statement.executeUpdate("UPDATE catalogue_update_batch_rows SET record=record+100")}
                        assertFails {statement.executeUpdate("DELETE FROM catalogue_update_batch_rows")}
                    }
                }
                assertEquals(before,repo.exportTenantSnapshot(shop.id))
            }
        } finally {Files.deleteIfExists(file)}
    }
    @Test fun persistentRetryDoesNotReplayChangesAndPermissionsStillApply() {
        val directory=Files.createTempDirectory("bulk-catalogue-retry-")
        val file=directory.resolve("shop.db")
        lateinit var shop:Tenant;lateinit var rows:List<BulkCatalogueUpdateRow>
        try {
            PosRepository(file.toString()).use {repo ->
                shop=repo.fixture().first
                rows=repo.bulkCatalogueTemplateRows(shop.id).map {it.copy(values=it.values.copy(priceMinor=it.values.priceMinor+1000))}
                repo.applyBulkCatalogueUpdates(shop.id,rows,"New shelf prices","retry-batch")
            }
            PosRepository(file.toString()).use {repo ->
                val before=repo.exportTenantSnapshot(shop.id)
                assertEquals("retry-batch",repo.applyBulkCatalogueUpdates(shop.id,rows,"New shelf prices","retry-batch").id)
                assertEquals(before,repo.exportTenantSnapshot(shop.id))
                assertFails {repo.applyBulkCatalogueUpdates(shop.id,rows,"Different reason","retry-batch")}
                assertFails {repo.applyBulkCatalogueUpdates(shop.id,rows.map {it.copy(record=it.record+10)},"New shelf prices","retry-batch")}
                repo.setPermissionCheck {_,permission ->require(permission==StaffPermission.BACKUP)}
                assertFails {repo.applyBulkCatalogueUpdates(shop.id,rows,"New shelf prices","retry-batch")}
                repo.setPermissionCheck(null) // Trusted inspection after the denied operation.
                assertEquals(before,repo.exportTenantSnapshot(shop.id))
            }
        } finally {directory.toFile().deleteRecursively()}
    }
}
