package pos.domain

import kotlin.test.*

class CashDenominationsTest {
    @Test fun noteAndCoinCountsSumInKoboAndRoundTripCanonically() {
        val count = CashCount(mapOf(100000L to 12, 50000L to 3, 50L to 3, 1L to 1, 500L to 0))
        assertEquals(1350151L, count.totalMinor)
        assertEquals("v1|1:1;50:3;50000:3;100000:12", count.encode())
        assertEquals(count, CashCount.decode(count.encode()))
        assertEquals(0L, CashCount.decode("v1|").totalMinor)
    }

    @Test fun inputAcceptsOnlyWholePhysicalPieceCounts() {
        assertEquals(0L, parseCashPieceCount(""))
        assertEquals(12L, parseCashPieceCount(" 0012 "))
        listOf("-1", "+1", "1.0", "1,000", "1e2", "١", "1 2", "9223372036854775808").forEach {
            assertNull(parseCashPieceCount(it), it)
        }
        assertEquals(200000L, cashCountFromInputs(mapOf(100000L to "2", 50000L to ""))?.totalMinor)
        assertNull(cashCountFromInputs(mapOf(100000L to "1.2")))
        assertNull(cashCountFromInputs(emptyMap()))
        assertNull(cashCountFromInputs(mapOf(100000L to "")))
        assertEquals(0L, cashCountFromInputs(mapOf(100000L to "0"))?.totalMinor)
    }

    @Test fun snapshotsCannotChangeWhenTheCallersMapChanges() {
        val source = mutableMapOf(100000L to 1L)
        val count = CashCount(source)
        source[100000L] = 2
        assertEquals(100000L, count.totalMinor)
        assertEquals(1L, count.counts[100000L])
        assertEquals(CashCount(mapOf(100000L to 1)), count)
    }

    @Test fun invalidAndOverflowingSheetsNeverProduceATotal() {
        assertFails { CashCount(mapOf(300L to 1)) }
        assertFails { CashCount(mapOf(100000L to -1)) }
        assertFails { CashCount(mapOf(100000L to Long.MAX_VALUE)) }
        assertFails { CashCount(mapOf(1L to Long.MAX_VALUE, 10L to 1)) }
        assertEquals(Long.MAX_VALUE, CashCount(mapOf(1L to Long.MAX_VALUE)).totalMinor)
    }

    @Test fun corruptedOrAmbiguousSavedSheetsAreRejected() {
        listOf("", "v2|", "v1|100000:1;100000:2", "v1|100000:0", "v1|100000:-1", "v1|300:2", "v1|100000:01", "v1|100000:1;", "v1|:1", "v1|100000:", "v1|100000:1:2", "v1|100000:1;50000:1").forEach {
            assertFails(it) { CashCount.decode(it) }
        }
    }
}
