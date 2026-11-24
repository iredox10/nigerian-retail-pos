package pos.data

import kotlin.test.*
import pos.domain.*
import java.nio.file.Files
import java.sql.DriverManager

class StockCountSessionIntegrityTest {
    private fun PosRepository.fixture():Triple<Tenant,Product,Product> {
        initializeStockCountSessions()
        val shop=createTenant("Counts")
        return Triple(shop,addProduct(shop.id,"Rice","kg",10000,10.25,quantityPrecision=2),addProduct(shop.id,"Soap","piece",5000,20.0))
    }
    @Test fun severalMeasuredCountsPostTogetherAndRetryDoesNotDuplicateCorrections() {
        PosRepository(":memory:").use {repo ->
            val(shop,rice,soap)=repo.fixture()
            val draft=repo.createStockCountSession(shop.id,"Monday",listOf(rice.id,soap.id),"monday")
            val saved=repo.saveStockCountDraft(shop.id,draft.id,mapOf(rice.id to 975,soap.id to 18),"Physical shelf count",draft.version)
            assertEquals("10.25",saved.lines.first().baselineLabel)
            assertEquals("−0.5",saved.lines.first().differenceLabel)
            assertEquals(StockCountStatus.DRAFT,saved.status)
            assertEquals("10.25",repo.products(shop.id).first {it.id==rice.id}.stockLabel)
            val posted=repo.postStockCountSession(shop.id,saved.id,saved.version)
            val snapshot=repo.exportTenantSnapshot(shop.id)
            assertEquals(posted,repo.postStockCountSession(shop.id,saved.id,saved.version))
            assertEquals(snapshot,repo.exportTenantSnapshot(shop.id))
            assertEquals(2,repo.stockAdjustments(shop.id).size)
            assertEquals("9.75",repo.products(shop.id).first {it.id==rice.id}.stockLabel)
            assertEquals("18",repo.products(shop.id).first {it.id==soap.id}.stockLabel)
            assertTrue(repo.sales(shop.id).isEmpty())
            assertTrue(repo.cashEntries(shop.id).isEmpty())
            repo.validateStockCountSessions(shop.id)
            assertFails {repo.saveStockCountDraft(shop.id,posted.id,mapOf(rice.id to 1,soap.id to 1),"Cannot edit",posted.version)}
            assertFails {repo.cancelStockCountSession(shop.id,posted.id,"Cannot cancel",posted.version)}
            val protected=repo.exportTenantSnapshot(shop.id)
            repo.setPermissionCheck {_,permission ->require(permission==StaffPermission.BACKUP)}
            assertFails {repo.postStockCountSession(shop.id,saved.id,saved.version)}
            repo.setPermissionCheck(null) // Trusted inspection after the denied operation.
            assertEquals(protected,repo.exportTenantSnapshot(shop.id))
        }
    }
    @Test fun anyInterveningMovementRejectsWholeSessionEvenWhenStockReturnsToBaseline() {
        PosRepository(":memory:").use {repo ->
            val(shop,rice,soap)=repo.fixture()
            val draft=repo.createStockCountSession(shop.id,"Moving goods",listOf(rice.id,soap.id))
            val saved=repo.saveStockCountDraft(shop.id,draft.id,mapOf(rice.id to 900,soap.id to 18),"Counted goods",draft.version)
            repo.openCashShift(shop.id,10000)
            val sale=repo.checkout(shop.id,listOf(CartLine(soap,1)),5000,operationId="sale")
            repo.returnItems(shop.id,sale.id,mapOf(soap.id to 1),true,"Returned", "return")
            val before=repo.exportTenantSnapshot(shop.id)
            assertFails {repo.postStockCountSession(shop.id,saved.id,saved.version)}
            assertEquals(before,repo.exportTenantSnapshot(shop.id))
            assertTrue(repo.stockAdjustments(shop.id).isEmpty())
            assertEquals(20.0,repo.products(shop.id).first {it.id==soap.id}.stock)
        }
    }
    @Test fun staleDraftEditsIncompleteCountsAndDeniedReplaysLeaveRecordsUnchanged() {
        PosRepository(":memory:").use {repo ->
            val(shop,rice,soap)=repo.fixture()
            val draft=repo.createStockCountSession(shop.id,"Partial",listOf(rice.id,soap.id),"partial")
            val saved=repo.saveStockCountDraft(shop.id,draft.id,mapOf(rice.id to 0,soap.id to null),"Physical check",draft.version)
            val before=repo.exportTenantSnapshot(shop.id)
            assertFails {repo.saveStockCountDraft(shop.id,draft.id,mapOf(rice.id to 1,soap.id to 1),"Stale request",draft.version)}
            assertFails {repo.postStockCountSession(shop.id,saved.id,saved.version)}
            assertEquals(before,repo.exportTenantSnapshot(shop.id))
            val other=repo.createTenant("Other")
            assertFails {repo.postStockCountSession(other.id,saved.id,saved.version)}
            repo.setPermissionCheck {_,permission ->require(permission==StaffPermission.BACKUP)}
            assertFails {repo.createStockCountSession(shop.id,"Partial",listOf(rice.id,soap.id),"partial")}
            assertFails {repo.stockCountSessions(shop.id)}
            repo.setPermissionCheck(null) // Trusted inspection after the denied operation.
            assertEquals(before,repo.exportTenantSnapshot(shop.id))
        }
    }
    @Test fun cancelledDraftPersistsAndCannotBeEditedOrPosted() {
        val directory=Files.createTempDirectory("count-restart-")
        val file=directory.resolve("shop.db")
        var tenant="";var session=""
        try {
            PosRepository(file.toString()).use {repo ->
                val(shop,rice,soap)=repo.fixture();tenant=shop.id
                val draft=repo.createStockCountSession(shop.id,"Cancelled",listOf(rice.id,soap.id));session=draft.id
                val saved=repo.saveStockCountDraft(shop.id,draft.id,mapOf(rice.id to 250,soap.id to null),"Count in progress",draft.version)
                val cancelled=repo.cancelStockCountSession(shop.id,session,"Stock moved; recount",saved.version)
                assertEquals(cancelled,repo.cancelStockCountSession(shop.id,session,"Stock moved; recount",saved.version))
                assertFails {repo.postStockCountSession(shop.id,session,saved.version)}
                assertTrue(repo.stockAdjustments(shop.id).isEmpty())
            }
            PosRepository(file.toString()).use {repo ->
                repo.initializeStockCountSessions()
                val cancelled=repo.stockCountSessions(tenant).single()
                assertEquals(session,cancelled.id)
                assertEquals(StockCountStatus.CANCELLED,cancelled.status)
                assertEquals("2.5",cancelled.lines.first().countedLabel)
                repo.validateStockCountSessions(tenant)
            }
            DriverManager.getConnection("jdbc:sqlite:$file").use {db ->
                assertFails {db.createStatement().use {it.executeUpdate("UPDATE stock_count_session_lines SET counted_scaled=1")}}
                assertFails {db.createStatement().use {it.executeUpdate("DELETE FROM stock_count_sessions")}}
            }
        } finally {directory.toFile().deleteRecursively()}
    }
    @Test fun restorePreservesPostedAndUnfinishedCountsAndRejectsTamperedLinks() {
        PosRepository(":memory:").use {repo ->
            val(shop,rice,soap)=repo.fixture()
            val draft=repo.createStockCountSession(shop.id,"Finished",listOf(rice.id))
            val saved=repo.saveStockCountDraft(shop.id,draft.id,mapOf(rice.id to 975),"Counted rice",draft.version)
            repo.postStockCountSession(shop.id,draft.id,saved.version)
            repo.createStockCountSession(shop.id,"Unfinished",listOf(soap.id))
            val snapshot=repo.exportTenantSnapshot(shop.id)
            assertTrue(snapshot.tables.containsKey("stock_count_sessions"),"Root must include session tables in business backups")
            val restored=repo.restoreTenantSnapshot(snapshot,"Recovered")
            assertEquals(setOf(StockCountStatus.DRAFT,StockCountStatus.POSTED),repo.stockCountSessions(restored.id).map {it.status}.toSet())
            val before=repo.tenants()
            val bad=snapshot.copy(tables=snapshot.tables+("stock_count_session_lines" to snapshot.tables.getValue("stock_count_session_lines").map {row ->if(row["session_id"]==draft.id) row+("counted_scaled" to "1") else row}))
            assertFails {repo.restoreTenantSnapshot(bad,"Bad count")}
            assertEquals(before,repo.tenants())
        }
    }
    @Test fun failureOnSecondCorrectionRollsBackFirstCorrectionAndSessionStatus() {
        val file=Files.createTempFile("count-atomic-",".db")
        try {
            PosRepository(file.toString()).use {repo ->
                val(shop,rice,soap)=repo.fixture()
                val draft=repo.createStockCountSession(shop.id,"Atomic",listOf(rice.id,soap.id))
                val saved=repo.saveStockCountDraft(shop.id,draft.id,mapOf(rice.id to 700,soap.id to 15),"All counted",draft.version)
                DriverManager.getConnection("jdbc:sqlite:$file").use {db ->db.createStatement().use {it.execute("CREATE TRIGGER reject_second_count BEFORE INSERT ON stock_adjustments WHEN NEW.product_id='${soap.id}' BEGIN SELECT RAISE(ABORT,'injected second-write failure'); END")}}
                val before=repo.exportTenantSnapshot(shop.id)
                assertFails {repo.postStockCountSession(shop.id,saved.id,saved.version)}
                assertEquals(before,repo.exportTenantSnapshot(shop.id))
                assertTrue(repo.stockAdjustments(shop.id).isEmpty())
            }
        } finally {Files.deleteIfExists(file)}
    }
    @Test fun omittedCountTablesAndTamperedDraftBaselineCannotHideOrPostCorrections() {
        PosRepository(":memory:").use {repo ->
            val(shop,rice,_)=repo.fixture()
            val draft=repo.createStockCountSession(shop.id,"Draft baseline",listOf(rice.id))
            val saved=repo.saveStockCountDraft(shop.id,draft.id,mapOf(rice.id to 900),"Physical recount",draft.version)
            assertEquals(saved,repo.saveStockCountDraft(shop.id,draft.id,mapOf(rice.id to 900),"Physical recount",draft.version))
            val snapshot=repo.exportTenantSnapshot(shop.id)
            val before=repo.tenants()
            assertFails {repo.restoreTenantSnapshot(snapshot.copy(tables=snapshot.tables-stockCountSessionSnapshotTables.toSet()),"Missing count history")}
            assertEquals(before,repo.tenants())
            val tampered=snapshot.copy(tables=snapshot.tables+("stock_count_session_lines" to snapshot.tables.getValue("stock_count_session_lines").map {it+("baseline_scaled" to "1")}))
            val restored=repo.restoreTenantSnapshot(tampered,"Draft with stale baseline")
            val restoredDraft=repo.stockCountSessions(restored.id).single()
            val original=repo.exportTenantSnapshot(restored.id)
            assertFails {repo.postStockCountSession(restored.id,restoredDraft.id,restoredDraft.version)}
            assertEquals(original,repo.exportTenantSnapshot(restored.id))
        }
    }

    @Test fun archiveAndRestoreAfterStartStillRequireFreshCount() {
        PosRepository(":memory:").use {repo ->
            val(shop,rice,_)=repo.fixture()
            val draft=repo.createStockCountSession(shop.id,"Archive changes",listOf(rice.id))
            val saved=repo.saveStockCountDraft(shop.id,draft.id,mapOf(rice.id to 900),"Shelf counted",draft.version)
            repo.archiveProduct(shop.id,rice.id,true,"archive-during-count")
            repo.archiveProduct(shop.id,rice.id,false,"restore-during-count")
            val before=repo.exportTenantSnapshot(shop.id)
            assertFails {repo.postStockCountSession(shop.id,saved.id,saved.version)}
            assertEquals(before,repo.exportTenantSnapshot(shop.id))
        }
    }

}
