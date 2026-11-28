package pos.android

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import pos.android.storage.AndroidSqliteDriver
import pos.data.*
import pos.domain.*
import java.io.File
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class AndroidHeldDraftTest {
    @Test fun removingOneHeldDraftPreservesActiveOtherShopsAndCompletedFinancialEvents() {
        val context=ApplicationProvider.getApplicationContext<Context>()
        val directory=File(context.cacheDir,"held-draft-qa-${UUID.randomUUID()}").apply {check(mkdirs())}
        LocalSqlite.installPlatformAdapter(AndroidSqliteDriver::open)
        try {
            PosRepository(":memory:").use { r ->
                val t=r.createTenant("Held draft QA").id
                val item=r.addProduct(t,"Soap","piece",100,5.0)
                r.openCashShift(t,0)
                val completed=r.checkout(t,listOf(CartLine(item,1)),100,operationId="completed-sale")
                val financialBefore=r.sales(t)
                assertEquals(completed.id,financialBefore.single().id)
                val store=DraftStore(t,directory.toPath())
                val one=SaleDraft("${item.id}:1","held-one");store.save(one);val first=store.hold("First unfinished sale",one)
                val two=SaleDraft("${item.id}:2","held-two");store.save(two);val second=store.hold("Keep this unfinished sale",two)
                val active=SaleDraft("${item.id}:1","active-sale",cashText="1.00");store.save(active)
                val other=r.createTenant("Other held shop").id
                val otherStore=DraftStore(other,directory.toPath())
                val otherDraft=SaleDraft("other-item:1","other-sale");otherStore.save(otherDraft)
                val otherHeld=otherStore.hold("Other shop draft",otherDraft)
                store.discardHeld(first.id)
                val reopened=DraftStore(t,directory.toPath())
                assertEquals(active,reopened.load());assertEquals(listOf(second),reopened.heldSales())
                assertEquals(listOf(otherHeld),DraftStore(other,directory.toPath()).heldSales())
                assertThrows(IllegalArgumentException::class.java) {reopened.discardHeld(first.id)}
                assertEquals(active,reopened.load());assertEquals(listOf(second),reopened.heldSales())
                assertEquals(financialBefore,r.sales(t))
                assertEquals(4L,r.products(t).single().exactStockScaled)
                assertEquals(100L,r.expectedCash(t,r.activeCashShift(t)!!.id))
            }
        } finally {directory.deleteRecursively()}
    }
}
