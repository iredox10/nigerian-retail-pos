package pos.data

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class DraftStoreTest {
    @Test fun draftSurvivesStoreRecreationWithSameOperationReference() {
        val directory = Files.createTempDirectory("pos-draft-test")
        try {
            DraftStore("shop-a", directory).save(SaleDraft("product-1:3;product-2:1", "operation-1"))
            assertEquals(SaleDraft("product-1:3;product-2:1", "operation-1"), DraftStore("shop-a", directory).load())
        } finally { directory.toFile().deleteRecursively() }
    }

    @Test fun tenantsHaveIndependentDraftsAndClearingOneDoesNotClearAnother() {
        val directory = Files.createTempDirectory("pos-draft-test")
        try {
            val a = DraftStore("shop-a", directory)
            val b = DraftStore("shop-b", directory)
            a.save(SaleDraft("item-a:2", "operation-a"))
            b.save(SaleDraft("item-b:5", "operation-b"))
            a.clear()
            assertNull(a.load())
            assertEquals(SaleDraft("item-b:5", "operation-b"), b.load())
        } finally { directory.toFile().deleteRecursively() }
    }

    @Test fun replacementContainsLatestWholeDraft() {
        val directory = Files.createTempDirectory("pos-draft-test")
        try {
            val store = DraftStore("shop", directory)
            store.save(SaleDraft("item:1", "operation"))
            store.save(SaleDraft("item:4", "operation"))
            assertEquals(SaleDraft("item:4", "operation"), store.load())
        } finally { directory.toFile().deleteRecursively() }
    }
}
