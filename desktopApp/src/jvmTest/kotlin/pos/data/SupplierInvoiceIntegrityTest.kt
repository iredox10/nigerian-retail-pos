package pos.data

import kotlin.test.*
import pos.domain.*
import pos.output.BackupService
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.sql.DriverManager
import javax.imageio.ImageIO
import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.pdmodel.PDPage

class SupplierInvoiceIntegrityTest {
    private fun png()=ByteArrayOutputStream().also {ImageIO.write(BufferedImage(2,2,BufferedImage.TYPE_INT_RGB),"png",it)}.toByteArray()
    private fun setup(repo:PosRepository):Pair<String,String> {
        val tenant=repo.createTenant("Shop").id
        val supplier=repo.addSupplierProfile(tenant,SupplierDetails("Original supplier"),"supplier")
        val p=repo.addProduct(tenant,"Rice","bag",200,0.0)
        (1..4).forEach {repo.receiveStockForSupplier(tenant,p.id,1,100,0,supplier.id,"r$it")}
        return tenant to supplier.id
    }
    @Test fun duplicatesUseStableSupplierAndRequireExplicitReviewForReusedNumbers() {
        PosRepository(":memory:").use {repo ->
            val (t,s)=setup(repo)
            repo.recordSupplierInvoice(t,s,SupplierInvoiceDetails("inv  1","2026-11-14"),listOf("r1"),operationId="i1")
            val before=repo.exportTenantSnapshot(t)
            assertFails {repo.recordSupplierInvoice(t,s,SupplierInvoiceDetails("INV 1","2026-11-14"),listOf("r2"),operationId="i2")}
            assertFails {repo.recordSupplierInvoice(t,s,SupplierInvoiceDetails("INV 1","2026-11-13"),listOf("r2"),operationId="i2")}
            assertEquals(before,repo.exportTenantSnapshot(t))
            repo.recordSupplierInvoice(t,s,SupplierInvoiceDetails("INV 1","2026-11-13",duplicateReviewReason="Supplier restarts numbering daily"),listOf("r2"),operationId="i2")
            val other=repo.addSupplierProfile(t,SupplierDetails("Original supplier"),"other")
            val p=repo.products(t).single();repo.receiveStockForSupplier(t,p.id,1,100,0,other.id,"other-r")
            repo.recordSupplierInvoice(t,other.id,SupplierInvoiceDetails("INV 1","2026-11-14"),listOf("other-r"),operationId="i3")
            assertEquals(2,repo.supplierInvoices(t,s).size)
            val restored=repo.restoreTenantSnapshot(repo.exportTenantSnapshot(t),"Reviewed duplicate numbers")
            assertEquals(2,repo.supplierInvoices(restored.id,s).size)
        }
    }
    @Test fun correctionsVoidAndReassignmentKeepImmutableHistoryAndAllFinancialEffectsUnchanged() {
        PosRepository(":memory:").use {repo ->
            val (t,s)=setup(repo);val products=repo.products(t);val cash=repo.cashEntries(t);val debt=repo.supplierOutstanding(t,"r1")
            val original=repo.recordSupplierInvoice(t,s,SupplierInvoiceDetails("1","2026-11-14"),listOf("r1"),operationId="invoice")
            assertFails {repo.recordSupplierInvoice(t,s,SupplierInvoiceDetails("2","2026-11-14"),listOf("r1"),operationId="overlap")}
            val corrected=repo.correctSupplierInvoice(t,original.id,original.details.copy(reference="Delivery 5"),original.receiptIds,SupplierInvoiceStatus.ACTIVE,null,reason="Added delivery reference",expectedVersion=1,operationId="correct")
            assertEquals(corrected,repo.correctSupplierInvoice(t,original.id,corrected.details,original.receiptIds,SupplierInvoiceStatus.ACTIVE,null,reason="Added delivery reference",expectedVersion=1,operationId="correct"))
            assertFails {repo.correctSupplierInvoice(t,original.id,original.details,original.receiptIds,SupplierInvoiceStatus.ACTIVE,null,reason="Stale change",expectedVersion=1)}
            repo.correctSupplierInvoice(t,original.id,corrected.details,emptyList(),SupplierInvoiceStatus.VOIDED,null,reason="Document belongs elsewhere",expectedVersion=2,operationId="void")
            repo.recordSupplierInvoice(t,s,SupplierInvoiceDetails("2","2026-11-14"),listOf("r1"),operationId="replacement")
            assertEquals(listOf(1L,2L,3L),repo.supplierInvoiceRevisions(t,original.id).map {it.invoice.version})
            assertEquals(listOf("r1"),repo.supplierInvoiceRevisions(t,original.id).first().invoice.receiptIds)
            assertEquals(products,repo.products(t));assertEquals(cash,repo.cashEntries(t));assertEquals(debt,repo.supplierOutstanding(t,"r1"))
        }
    }
    @Test fun retriesArchivePermissionsAndTenantGuardsNeverCreatePartialRecords() {
        PosRepository(":memory:").use {repo ->
            val (t,s)=setup(repo);val details=SupplierInvoiceDetails("1","2026-11-14")
            val i=repo.recordSupplierInvoice(t,s,details,listOf("r1"),operationId="i")
            assertEquals(i,repo.recordSupplierInvoice(t,s,details,listOf("r1"),operationId="i"))
            assertFails {repo.recordSupplierInvoice(t,s,details.copy(number="2"),listOf("r1"),operationId="i")}
            val foreign=repo.createTenant("Other").id
            assertFails {repo.supplierInvoiceRevisions(foreign,i.id)}
            assertFails {repo.recordSupplierInvoice(foreign,s,details,listOf("r1"),operationId="foreign")}
            val supplier=repo.supplierProfiles(t).single();repo.updateSupplierProfile(t,s,supplier.details,true,1,"Supplier stopped trading","archive")
            assertEquals(i,repo.recordSupplierInvoice(t,s,details,listOf("r1"),operationId="i"))
            assertFails {repo.recordSupplierInvoice(t,s,details.copy(number="2"),listOf("r2"),operationId="new")}
            val before=repo.exportTenantSnapshot(t)
            repo.setPermissionCheck {_,permission ->if(permission!=StaffPermission.BACKUP)error("Denied")}
            assertFails {repo.supplierInvoices(t,s)};assertFails {repo.supplierInvoiceRevisions(t,i.id)};assertFails {repo.supplierInvoiceAttachmentBytes(t,i.id)}
            assertFails {repo.correctSupplierInvoice(t,i.id,details,emptyList(),SupplierInvoiceStatus.VOIDED,null,reason="Void duplicate",expectedVersion=1)}
            assertEquals(before,repo.exportTenantSnapshot(t))
        }
    }
    @Test fun lateAuditFailureRollsBackEvidenceAndInvoiceTogether() {
        PosRepository(":memory:").use {repo ->
            val (t,s)=setup(repo);val before=repo.exportTenantSnapshot(t)
            repo.setActorProvider {error("No actor available")}
            assertFails {repo.recordSupplierInvoice(t,s,SupplierInvoiceDetails("1","2026-11-14"),listOf("r1"),SupplierInvoiceAttachmentUpload.fromBytes("invoice.png",png()),"i")}
            repo.setActorProvider(null);assertEquals(before,repo.exportTenantSnapshot(t))
        }
    }
    @Test fun encryptedBackupRetainsEvidenceAndRejectsOmissionCorruptionAndProjectionTampering() {
        PosRepository(":memory:").use {repo ->
            val (t,s)=setup(repo);val bytes=png()
            val i=repo.recordSupplierInvoice(t,s,SupplierInvoiceDetails("1","2026-11-14"),listOf("r1"),SupplierInvoiceAttachmentUpload.fromBytes("invoice.png",bytes),"i")
            repo.correctSupplierInvoice(t,i.id,i.details.copy(notes="Checked document"),i.receiptIds,SupplierInvoiceStatus.ACTIVE,null,reason="Removed current evidence",expectedVersion=1,operationId="remove")
            val file=Files.createTempDirectory("invoice-backup").resolve("shop.backup")
            BackupService.create(repo,t,"invoice-backup-password",file)
            val snapshot=BackupService.read(file,"invoice-backup-password")
            val recovered=repo.restoreTenantSnapshot(snapshot,"Recovered")
            assertContentEquals(bytes,repo.supplierInvoiceAttachmentBytes(recovered.id,i.id,1))
            assertNull(repo.supplierInvoices(recovered.id,s).single().attachment)
            val before=repo.tenants()
            val corrupt=snapshot.copy(tables=snapshot.tables+("supplier_invoice_attachments" to snapshot.tables.getValue("supplier_invoice_attachments").map {it+("payload" to "aW52YWxpZA==")}))
            assertFails {repo.restoreTenantSnapshot(corrupt,"Corrupt")}
            assertFails {repo.restoreTenantSnapshot(snapshot.copy(tables=snapshot.tables-supplierInvoiceSnapshotTables.toSet()),"Omitted")}
            val projection=snapshot.copy(tables=snapshot.tables+("supplier_invoices" to snapshot.tables.getValue("supplier_invoices").map {it+("notes" to "Altered")}))
            assertFails {repo.restoreTenantSnapshot(projection,"Altered")}
            val first=snapshot.tables.getValue("supplier_invoice_versions").first()
            val reset=snapshot.copy(tables=snapshot.tables+
                ("supplier_invoice_versions" to listOf(first))+
                ("supplier_invoices" to snapshot.tables.getValue("supplier_invoices").map {row ->row+("version" to "1")+("updated_at" to first.getValue("recorded_at"))+("notes" to first["notes"])+("attachment_hash" to first["attachment_hash"])+("attachment_name" to first["attachment_name"])})+
                ("supplier_invoice_receipts" to snapshot.tables.getValue("supplier_invoice_receipts").filter {it["version"]=="1"}))
            assertFails {repo.restoreTenantSnapshot(reset,"Missing correction history")}
            assertEquals(before,repo.tenants())
        }
    }
    @Test fun recordsSurviveRestartAndEvidenceCannotBeEdited() {
        val path=Files.createTempDirectory("invoice-restart").resolve("shop.db").toString();var tenant=""
        PosRepository(path).use {repo ->val(t,s)=setup(repo);tenant=t;repo.recordSupplierInvoice(t,s,SupplierInvoiceDetails("1","2026-11-14"),listOf("r1"),operationId="i")}
        DriverManager.getConnection("jdbc:sqlite:$path").use {db ->
            assertFails {db.createStatement().execute("UPDATE supplier_invoice_versions SET reason='Rewritten'")}
            assertFails {db.createStatement().execute("DELETE FROM supplier_invoice_receipts")}
        }
        PosRepository(path).use {repo ->assertEquals("1",repo.supplierInvoices(tenant,"supplier").single().details.number)}
    }
    @Test fun legacyBackupsWithNoInvoiceEventsMayOmitInvoiceTables() {
        PosRepository(":memory:").use {repo ->val(t,s)=setup(repo);val legacy=repo.exportTenantSnapshot(t).let {it.copy(tables=it.tables-supplierInvoiceSnapshotTables.toSet())};val restored=repo.restoreTenantSnapshot(legacy,"Legacy");assertTrue(repo.supplierInvoices(restored.id,s).isEmpty())}
    }
    @Test fun attachmentsAreRealBoundedDocumentsWithDefensiveCopies() {
        val bytes=png();val upload=SupplierInvoiceAttachmentUpload.fromBytes("invoice.png",bytes);val saved=upload.bytes();bytes.fill(0)
        assertContentEquals(saved,upload.bytes());val exposed=upload.bytes();exposed.fill(0);assertContentEquals(saved,upload.bytes())
        assertFails {SupplierInvoiceAttachmentUpload.fromBytes("fake.png","not an image".toByteArray())}
        assertFails {SupplierInvoiceAttachmentUpload.fromBytes("../invoice.png",saved)}
        assertFails {SupplierInvoiceAttachmentUpload.fromBytes("large.png",ByteArray(1_048_577))}
        val pdf=ByteArrayOutputStream();PDDocument().use {it.addPage(PDPage());it.save(pdf)}
        assertEquals("application/pdf",SupplierInvoiceAttachmentUpload.fromBytes("invoice.pdf",pdf.toByteArray()).reference.mediaType)
        assertFails {SupplierInvoiceAttachmentUpload.fromBytes("broken.pdf","%PDF-broken".toByteArray())}
    }
}
