package pos.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class AmountInputTest {
    @Test fun wholeAndFractionalAmountsPreserveKoboExactly() {
        assertEquals(0L, parseNaira("0"))
        assertEquals(0L, parseNaira("0.00"))
        assertEquals(1L, parseNaira("0.01"))
        assertEquals(10L, parseNaira("0.1"))
        assertEquals(10_000L, parseNaira("100"))
        assertEquals(10_010L, parseNaira("100.1"))
        assertEquals(10_099L, parseNaira("100.99"))
        assertEquals(2_750_000L, parseNaira("27500.00"))
    }

    @Test fun whitespaceAndLeadingZerosAreUnambiguous() {
        assertEquals(123L, parseNaira(" \t001.23\n"))
        assertEquals(0L, parseNaira("0000000.00"))
        assertEquals(100L, parseNaira("00000001"))
        assertEquals(0L, parseNaira("0".repeat(1000)))
        assertNull(parseNaira("1 000"))
        assertNull(parseNaira("1 .00"))
    }

    @Test fun upperBoundaryIsAcceptedWithoutRoundingOrOverflow() {
        assertEquals(Long.MAX_VALUE, parseNaira("92233720368547758.07"))
        assertEquals(Long.MAX_VALUE - 7, parseNaira("92233720368547758"))
        assertNull(parseNaira("92233720368547758.08"))
        assertNull(parseNaira("92233720368547758.1"))
        assertNull(parseNaira("92233720368547759"))
        assertNull(parseNaira("9223372036854775807"))
    }

    @Test fun ambiguousUnsupportedAndOverpreciseInputsAreRejected() {
        val rejected = listOf(
            "", " ", ".", ".50", "1.", "1.001", "1.000", "1.2.3",
            "-1", "-0", "+1", "1,000", "1,50", "1e2", "1E+2", "NaN",
            "Infinity", "₦1.00", "NGN 1.00", "1_000", "١٢", "１２", "1\n.0",
        )
        for (input in rejected) assertNull(parseNaira(input), "Unexpected acceptance: $input")
    }
}
