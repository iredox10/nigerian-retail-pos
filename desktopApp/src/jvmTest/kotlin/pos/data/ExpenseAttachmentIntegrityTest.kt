package pos.data

import java.awt.Color
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.sql.DriverManager
import javax.imageio.ImageIO
import kotlin.test.*
import pos.domain.StaffPermission
import pos.output.BackupService

class ExpenseAttachmentIntegrityTest {
    private fun png(color:Color=Color.RED):ByteArray {
        val image=BufferedImage(20,10,BufferedImage.TYPE_INT_RGB)
        image.createGraphics().let {g ->try {g.color=color;g.fillRect(0,0,20,10)} finally {g.dispose()}}
        return ByteArrayOutputStream().also {ImageIO.write(image,"png",it)}.toByteArray()
    }
    private fun receipt(color:Color=Color.RED)=SupplierInvoiceAttachmentUpload.fromBytes("receipt.png",png(color))
    private fun setup(repo:PosRepository):Pair<String,String> {
        repo.initializeExpenseAttachments()
        val shop=repo.createTenant("Shop");repo.openCashShift(shop.id,10000)
        val expense=repo.recordExpense(shop.id,"Fuel","Generator fuel",1000,"fuel")
        return shop.id to expense.id
    }
    @Test fun copiedEvidenceSurvivesSourceDeletionReopenAndEncryptedRecovery() {
        val directory=Files.createTempDirectory("expense-evidence-reopen-")
        try {
            val source=directory.resolve("receipt.png");val bytes=png();Files.write(source,bytes)
            val path=directory.resolve("shop.db");var tenant="";var expense=""
            PosRepository(path.toString()).use {repo ->
                val(t,e)=setup(repo);tenant=t;expense=e
                repo.saveExpenseAttachment(t,e,SupplierInvoiceAttachmentUpload.fromFile(source),0,"Receipt supplied","attach")
                Files.delete(source)
                assertContentEquals(bytes,repo.expenseAttachmentBytes(t,e))
            }
            PosRepository(path.toString()).use {repo ->
                val backup=directory.resolve("evidence.backup")
                BackupService.create(repo,tenant,"expense-backup-password",backup)
                val restored=repo.restoreTenantSnapshot(BackupService.read(backup,"expense-backup-password"),"Recovered")
                assertContentEquals(bytes,repo.expenseAttachmentBytes(restored.id,expense))
                assertEquals(1000L,repo.expenses(restored.id).single().amountMinor)
                assertEquals(9000L,repo.expectedCash(restored.id,repo.cashShifts(restored.id).single().id))
            }
        } finally {directory.toFile().deleteRecursively()}
    }
    @Test fun replacementRemovalAndRetryKeepOriginalExpenseAndHistoricalFiles() {
        PosRepository(":memory:").use {repo ->
            val(t,e)=setup(repo);val cash=repo.cashEntries(t);val expenses=repo.expenses(t)
            val red=receipt();val blue=receipt(Color.BLUE)
            val first=repo.saveExpenseAttachment(t,e,red,0,"Original receipt","first")
            assertEquals(first,repo.saveExpenseAttachment(t,e,red,0,"Original receipt","first"))
            assertFails {repo.saveExpenseAttachment(t,e,blue,0,"Stale receipt")}
            assertFails {repo.saveExpenseAttachment(t,e,blue,0,"Original receipt","first")}
            repo.saveExpenseAttachment(t,e,blue,1,"Clearer receipt","second")
            repo.saveExpenseAttachment(t,e,null,2,"Receipt not applicable","remove")
            assertNull(repo.expenseAttachment(t,e)!!.attachment)
            assertFails {repo.expenseAttachmentBytes(t,e)}
            assertContentEquals(red.bytes(),repo.expenseAttachmentBytes(t,e,1))
            assertContentEquals(blue.bytes(),repo.expenseAttachmentBytes(t,e,2))
            assertEquals(listOf(1L,2L,3L),repo.expenseAttachmentHistory(t,e).map {it.version})
            assertEquals(first,repo.saveExpenseAttachment(t,e,red,0,"Original receipt","first"))
            assertNull(repo.expenseAttachment(t,e)!!.attachment)
            assertEquals(cash,repo.cashEntries(t));assertEquals(expenses,repo.expenses(t))
        }
    }
    @Test fun shopBoundaryPermissionAndLateActorFailureProtectAllEvidenceWrites() {
        PosRepository(":memory:").use {repo ->
            val(t,e)=setup(repo);val other=repo.createTenant("Other")
            assertFails {repo.expenseAttachment(other.id,e)}
            assertFails {repo.saveExpenseAttachment(other.id,e,receipt(),0,"Wrong shop")}
            repo.setPermissionCheck {_,permission ->require(permission!=StaffPermission.MANAGE_MONEY)}
            assertFails {repo.expenseAttachment(t,e)}
            assertFails {repo.saveExpenseAttachment(t,e,receipt(),0,"Denied change")}
            repo.setPermissionCheck(null)
            val before=repo.exportTenantSnapshot(t)
            repo.setActorProvider {error("Actor revoked")}
            assertFailsWith<IllegalStateException> {repo.saveExpenseAttachment(t,e,receipt(),0,"No partial write")}
            repo.setActorProvider(null)
            assertEquals(before,repo.exportTenantSnapshot(t))
        }
    }
    @Test fun malformedChangedOrOmittedSnapshotEvidenceIsRejectedAtomically() {
        PosRepository(":memory:").use {repo ->
            val(t,e)=setup(repo)
            repo.saveExpenseAttachment(t,e,receipt(),0,"Original document","one")
            repo.saveExpenseAttachment(t,e,null,1,"Remove current document","two")
            val snapshot=repo.exportTenantSnapshot(t)
            fun changed(table:String,rows:List<Map<String,String?>>)=snapshot.copy(tables=snapshot.tables+(table to rows))
            val assets=snapshot.tables.getValue("expense_attachment_assets")
            val versions=snapshot.tables.getValue("expense_attachment_versions")
            val bad=listOf(
                changed("expense_attachment_assets",assets.map {it+("payload" to "bm90IGFuIGltYWdl")}),
                changed("expense_attachment_assets",assets.map {it+("byte_size" to "1")}),
                changed("expense_attachment_versions",versions.dropLast(1)),
                changed("expense_attachment_versions",versions.map {it+("version" to "9")}),
                changed("expense_attachment_versions",versions.map {it+("created_at" to "2000-01-01T00:00:00Z")}),
                snapshot.copy(tables=snapshot.tables-expenseAttachmentSnapshotTables.toSet()),
                changed("outbox",snapshot.tables.getValue("outbox").filter {it["kind"]!="expense.attachment_changed"}),
            )
            val shops=repo.tenants()
            bad.forEach {assertFails {repo.restoreTenantSnapshot(it,"Invalid")};assertEquals(shops,repo.tenants())}
            val recovered=repo.restoreTenantSnapshot(snapshot,"Recovered")
            assertNull(repo.expenseAttachment(recovered.id,e)!!.attachment)
            assertContentEquals(receipt().bytes(),repo.expenseAttachmentBytes(recovered.id,e,1))
        }
    }
    @Test fun legacyBackupWithoutEvidenceRemainsCompatible() {
        PosRepository(":memory:").use {repo ->
            val(t,e)=setup(repo)
            val original=repo.exportTenantSnapshot(t)
            val legacy=original.copy(tables=original.tables-expenseAttachmentSnapshotTables.toSet())
            val recovered=repo.restoreTenantSnapshot(legacy,"Legacy")
            assertNull(repo.expenseAttachment(recovered.id,e))
            assertTrue(repo.expenseAttachmentHistory(recovered.id,e).isEmpty())
        }
    }
    @Test fun completedReceiptAssetsAndRevisionsCannotBeOverwrittenOrDeleted() {
        val directory=Files.createTempDirectory("expense-evidence-immutable-")
        try {
            val path=directory.resolve("shop.db")
            PosRepository(path.toString()).use {repo ->val(t,e)=setup(repo);repo.saveExpenseAttachment(t,e,receipt(),0,"Original evidence")}
            DriverManager.getConnection("jdbc:sqlite:$path").use {db ->
                expenseAttachmentSnapshotTables.forEach {table ->
                    assertFails {db.createStatement().execute("DELETE FROM $table")}
                }
                assertFails {db.createStatement().execute("UPDATE expense_attachment_versions SET reason='Rewritten'")}
                assertFails {db.createStatement().execute("UPDATE expense_attachment_assets SET payload='Rewritten'")}
            }
        } finally {directory.toFile().deleteRecursively()}
    }
    @Test fun retainedEvidenceBudgetRejectsNewFileWithoutDeletingEarlierReceipts() {
        PosRepository(":memory:").use {repo ->
            val(t,e)=setup(repo)
            val random=java.util.Random(1701);var version=0L;var refused=false
            repeat(10) {index ->
                if(!refused) {
                    val image=BufferedImage(600,500,BufferedImage.TYPE_INT_RGB)
                    for(y in 0 until image.height) for(x in 0 until image.width) image.setRGB(x,y,random.nextInt(0x1000000))
                    val bytes=ByteArrayOutputStream().also {ImageIO.write(image,"png",it)}.toByteArray()
                    val before=repo.expenseAttachmentHistory(t,e)
                    val attempt=runCatching {repo.saveExpenseAttachment(t,e,SupplierInvoiceAttachmentUpload.fromBytes("receipt-$index.png",bytes),version,"Replace receipt $index")}
                    if(attempt.isSuccess) version++ else {
                        assertContains(attempt.exceptionOrNull()!!.message.orEmpty(),"8 MB")
                        assertEquals(before,repo.expenseAttachmentHistory(t,e));refused=true
                    }
                }
            }
            assertTrue(refused);assertTrue(version>=8)
            assertEquals(version,repo.expenseAttachment(t,e)!!.version)
            assertEquals(1000L,repo.expenses(t).single().amountMinor)
        }
    }
}
