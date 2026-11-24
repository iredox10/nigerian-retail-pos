package pos.android

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import pos.android.documents.*
import pos.android.storage.AndroidSqliteDriver
import pos.data.*
import pos.domain.*
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class AndroidCsvExportTest {
    @Test fun nativeExportsAreScopedAuthorizedConsistentAndLeaveFinancialRecordsUnchanged() {
        val context=ApplicationProvider.getApplicationContext<Context>()
        val directory=java.io.File(context.cacheDir,"csv-export-${UUID.randomUUID()}").apply {mkdirs()}
        LocalSqlite.installPlatformAdapter(AndroidSqliteDriver::open)
        try {PosRepository(directory.resolve("pos.db").path).use {repo ->
            val shop=repo.createTenant("Export shop").id
            val other=repo.createTenant("Another shop").id
            val item=repo.addProduct(shop,"=Unsafe name","piece",12345,5.0)
            repo.addProduct(other,"Must stay private","piece",999,1.0)
            repo.addCustomer(shop,"Ada")
            repo.openCashShift(shop,100000)
            repo.checkout(shop,listOf(CartLine(item,2)),24690,operationId="sale",cashGivenMinor=30000)
            repo.recordExpense(shop,"Transport","Fare, paid",12345,"expense")
            val before=repo.exportTenantSnapshot(shop)
            val beforeOther=repo.exportTenantSnapshot(other)
            val all=AndroidCsvKind.entries.associateWith {repo.prepareAndroidCsv(shop,it)}
            assertEquals(1,all.getValue(AndroidCsvKind.SALES).rowCount)
            assertTrue(all.getValue(AndroidCsvKind.SALES).csv.contains("\"300.00\",\"53.10\""))
            assertTrue(all.getValue(AndroidCsvKind.CASH).csv.contains("\"-123.45\""))
            assertTrue(all.getValue(AndroidCsvKind.STOCK).csv.contains("\"'=Unsafe name\""))
            all.values.forEach {export ->
                assertEquals(shop,export.tenantId)
                assertEquals(export.csv,export.previewCsv)
                assertFalse(export.csv.contains("Must stay private"))
                repo.authorizeAndroidCsvSave(shop,export)
            }
            denied {repo.authorizeAndroidCsvSave(other,all.getValue(AndroidCsvKind.SALES))}
            repo.setPermissionCheck {_,permission ->require(permission==StaffPermission.VIEW_REPORTS)}
            AndroidCsvKind.entries.forEach {denied {repo.prepareAndroidCsv(shop,it)}}
            denied {repo.authorizeAndroidCsvSave(shop,all.getValue(AndroidCsvKind.CASH))}
            repo.setPermissionCheck {_,_ ->throw IllegalArgumentException("Session locked")}
            denied {repo.authorizeAndroidCsvSave(shop,all.getValue(AndroidCsvKind.SALES))}
            repo.setPermissionCheck(null)
            assertEquals(before,repo.exportTenantSnapshot(shop))
            assertEquals(beforeOther,repo.exportTenantSnapshot(other))
        }}finally {directory.deleteRecursively()}
    }
    private fun denied(action:()->Unit) {try {action();fail("Unauthorized export accepted")}catch(_:IllegalArgumentException){}}
}
