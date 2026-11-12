package pos.data

import java.nio.file.Files
import java.util.Properties
import kotlin.test.*

class HeldSalesTest {
    private fun withStore(block:(DraftStore,java.nio.file.Path)->Unit) {
        val directory=Files.createTempDirectory("held-sales-")
        try {block(DraftStore("a",directory),directory)} finally {directory.toFile().deleteRecursively()}
    }
    @Test fun multipleHeldSalesSurviveRestartWithPaymentDetails()=withStore { store,dir ->
        val first=SaleDraft("malt:2","first","500.00","amina","credit")
        store.save(first);val a=store.hold("Amina",first)
        val second=SaleDraft("rice:1","second")
        store.save(second);store.hold("Collection later",second)
        val reopened=DraftStore("a",dir)
        assertNull(reopened.load());assertEquals(2,reopened.heldSales().size)
        assertEquals(first,reopened.resume(a.id));assertEquals(first,reopened.load());assertEquals(1,reopened.heldSales().size)
    }
    @Test fun holdingAndResumingCannotOverwriteAnActiveSale()=withStore { store,_ ->
        val a=SaleDraft("malt:1","a");store.save(a);val held=store.hold("First",a)
        assertEquals(held,store.hold("First",a))
        assertFailsWith<IllegalArgumentException> {store.save(a)}
        val b=SaleDraft("rice:1","b");store.save(b)
        assertFailsWith<IllegalArgumentException> {store.resume(held.id)}
        assertFailsWith<IllegalArgumentException> {store.hold("Wrong",a)}
        assertEquals(b,store.load());assertEquals(listOf(held),store.heldSales())
    }
    @Test fun completingCurrentSalePreservesHeldSalesAndOtherTenants()=withStore { store,dir ->
        val a=SaleDraft("malt:1","a");store.save(a);val held=store.hold("Held",a)
        val other=DraftStore("b",dir);other.save(SaleDraft("own:3","other"))
        store.save(SaleDraft("rice:2","current"))
        assertFailsWith<IllegalArgumentException> {store.clear("wrong-operation")}
        store.clear("current")
        assertNull(store.load());assertEquals(held,store.heldSales().single());assertEquals("other",other.load()?.operationId)
        store.discardHeld(held.id);assertTrue(store.heldSales().isEmpty());assertNull(store.load())
    }
    @Test fun invalidDraftDoesNotReplaceSavedWork()=withStore { store,_ ->
        val valid=SaleDraft("one:1","valid");store.save(valid)
        listOf(SaleDraft("one:0","bad"),SaleDraft("one:1;one:2","dup"),SaleDraft("one:1",""),SaleDraft("one:1","bad",method="transfer")).forEach { draft -> assertFailsWith<IllegalArgumentException> {store.save(draft)} }
        assertEquals(valid,store.load())
    }
    @Test fun legacyDraftLoadsAndUpgradesWithoutLoss()=withStore { store,dir ->
        store.save(SaleDraft("one:3","legacy"))
        val file=Files.list(dir.resolve("drafts")).use { paths -> paths.filter {it.toString().endsWith(".properties")}.findFirst().get() }
        Files.newOutputStream(file).use {Properties().apply {setProperty("cart","one:3");setProperty("operationId","legacy")}.store(it,"legacy")}
        val restored=store.load()!!;assertEquals(SaleDraft("one:3","legacy"),restored)
        store.hold("Recovered sale",restored);assertEquals(restored,store.heldSales().single().draft)
    }
    @Test fun aSecondStoreCannotOverwriteAnotherActiveSale()=withStore { store,dir ->
        val first=SaleDraft("one:1","first");store.save(first)
        assertFailsWith<IllegalArgumentException> {DraftStore("a",dir).save(SaleDraft("two:2","different"))}
        assertEquals(first,store.load())
    }
    @Test fun partialActiveOrHeldFilesRemainUntouched()=withStore { store,dir ->
        store.save(SaleDraft("one:1","valid"))
        val file=Files.list(dir.resolve("drafts")).use { paths -> paths.filter {it.toString().endsWith(".properties")}.findFirst().get() }
        val incomplete=listOf("cart=one:2\n", "version=2\nheld.0.cart=one:1\nheld.0.operationId=held\n", "version=2\nheldCount=0\nheld.1.cart=one:1\n", "")
        incomplete.forEach { original ->
            Files.writeString(file,original)
            assertFails {store.save(SaleDraft("two:1","new"))}
            assertFails {store.clear()}
            assertEquals(original,Files.readString(file))
        }
    }
    @Test fun corruptFileIsNeverSilentlyOverwritten()=withStore { store,dir ->
        store.save(SaleDraft("one:1","valid"))
        val file=Files.list(dir.resolve("drafts")).use { paths -> paths.filter {it.toString().endsWith(".properties")}.findFirst().get() }
        val corrupt="version=2\nheldCount=200\n";Files.writeString(file,corrupt)
        assertFails {store.save(SaleDraft("two:1","new"))};assertEquals(corrupt,Files.readString(file))
    }
}
