package pos.domain

import kotlin.test.*

class QuantityTest {
    @Test fun decimalArithmeticIsExactAndProductPrecisionIsExplicit() {
        val sum = requireNotNull(parseQuantity("0.1", 3)) + requireNotNull(parseQuantity("0.2", 3))
        assertEquals(Quantity(300, 3), sum)
        assertEquals("0.300", sum.toString())
        assertEquals(Quantity(0, 3), sum - sum)
        assertEquals(Quantity(123450, 4), parseQuantity(" 0012.345 ", 4))
        assertEquals("0", Quantity(0, 0).toString())
        assertFails { sum + Quantity(1, 0) }
        assertFails { Quantity(1, 3) - sum }
    }

    @Test fun parserRejectsAmbiguousAndOverpreciseInput() {
        listOf("", " ", ".5", "1.", "1.0001", "1.2.3", "-1", "+1", "1,000", "1e2", "NaN", "١٢", "1 2", "1\n.2").forEach {
            assertNull(parseQuantity(it, 3), it)
        }
        assertNull(parseQuantity("1.0", 0))
        assertEquals(Quantity(1, 6), parseQuantity("0.000001", 6))
        assertFails { parseQuantity("1", 7) }
        assertFails { Quantity(-1, 2) }
        assertFails { Quantity(1, -1) }
    }

    @Test fun rescalingNeverDiscardsQuantity() {
        assertEquals(Quantity(12300, 4), Quantity(123, 2).rescale(4))
        assertEquals(Quantity(123, 2), Quantity(12300, 4).rescale(2))
        assertEquals(Quantity(0, 0), Quantity(0, 6).rescale(0))
        assertFails { Quantity(12301, 4).rescale(2) }
        assertFails { Quantity(Long.MAX_VALUE, 0).rescale(1) }
    }

    @Test fun wholePackagesPreserveTheExplicitBaseUnitDefinition() {
        val pack = Quantity(1250, 3) // A merchant-defined 1.250 kg pack.
        assertEquals(Quantity(3750, 3), pack.timesWhole(3))
        assertEquals(3L, pack.timesWhole(3).wholePackageCount(pack))
        assertEquals(0L, pack.timesWhole(0).wholePackageCount(pack))
        assertFails { Quantity(3751, 3).wholePackageCount(pack) }
        assertFails { pack.wholePackageCount(Quantity(0, 3)) }
        assertFails { pack.wholePackageCount(Quantity(125, 2)) }
        assertFails { pack.timesWhole(-1) }
        assertFails { pack.timesWhole(Long.MAX_VALUE) }
    }

    @Test fun completeLineRoundsHalfUpOnceToKobo() {
        assertEquals(1L, Quantity(5, 1).lineTotalMinor(1))
        assertEquals(0L, Quantity(499999, 6).lineTotalMinor(1))
        assertEquals(1L, Quantity(500000, 6).lineTotalMinor(1))
        assertEquals(152L, Quantity(15, 1).lineTotalMinor(101))
        assertEquals(12501L, Quantity(125, 2).lineTotalMinor(10001))
        assertEquals(0L, Quantity(0, 6).lineTotalMinor(Long.MAX_VALUE))
        assertEquals(0L, Quantity(Long.MAX_VALUE, 6).lineTotalMinor(0))
        assertFails { Quantity(1, 0).lineTotalMinor(-1) }
    }

    @Test fun moneyUsesRepresentableResultsWithoutOverflowingIntermediates() {
        assertEquals(Long.MAX_VALUE, Quantity(1000000, 6).lineTotalMinor(Long.MAX_VALUE))
        assertEquals(4611686018427387904L, Quantity(500000, 6).lineTotalMinor(Long.MAX_VALUE))
        assertEquals(9223372036855L, Quantity(Long.MAX_VALUE, 6).lineTotalMinor(1))
        assertFails { Quantity(2, 0).lineTotalMinor(Long.MAX_VALUE) }
        assertFails { Quantity(1000001, 6).lineTotalMinor(Long.MAX_VALUE) }
    }

    @Test fun scaledQuantityBoundaryIsCheckedDuringParseAndAddition() {
        assertEquals(Quantity(Long.MAX_VALUE, 6), parseQuantity("9223372036854.775807", 6))
        assertNull(parseQuantity("9223372036854.775808", 6))
        assertNull(parseQuantity("9223372036854775807", 6))
        assertEquals(Quantity(Long.MAX_VALUE, 0), parseQuantity(Long.MAX_VALUE.toString(), 0))
        assertFails { Quantity(Long.MAX_VALUE, 0) + Quantity(1, 0) }
    }
}
